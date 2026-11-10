package sh.edm.s1kiosk.voice

import android.content.Context
import sh.edm.s1kiosk.esphome.VoiceAssistantController
import sh.edm.s1kiosk.esphome.WakeWordCatalog
import sh.edm.s1kiosk.esphome.WakeWordInfo
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Microphone + on-device openWakeWord, feeding the ESPHome voice assistant ([controller]).
 *
 * The microphone has one consumer: this engine. Every 80 ms frame goes through the wake word model
 * (so its buffers never hold stale audio), and while a pipeline run is starting/streaming the same
 * frame is handed to the controller, which sends it to Home Assistant. Detections only count while
 * the controller is idle and after the refractory period.
 *
 * Also the [WakeWordCatalog] behind Home Assistant's wake word select: every `*.tflite` in the
 * models dir except the shared melspectrogram/embedding models. The active model is persisted by
 * the caller through [onActiveModelChanged].
 */
class VoiceEngine(
    context: Context,
    private val config: VoiceConfig,
    initialModel: String,
    private val onActiveModelChanged: (String) -> Unit,
) : WakeWordCatalog {

    @Volatile var controller: VoiceAssistantController? = null
    @Volatile var threshold: Float = config.threshold

    private val modelsDir: File = config.resolveModelsDir(context.applicationContext)
    private val exec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "voice-main").apply { isDaemon = true } }

    private val wwLock = Any()
    private var wakeWord: TfliteWakeWord? = null // guarded by wwLock
    @Volatile private var activeModel: String = initialModel
    @Volatile private var phrase: String = friendlyName(initialModel)
    private var mic: Microphone? = null
    @Volatile private var gain = config.micGain
    // Mic thread only: level stats for the current pipeline run.
    private var runPeak = 0
    private var runSumSq = 0.0
    private var runSamples = 0L
    @Volatile private var muted = false
    @Volatile private var running = false
    private var refractoryUntil = Long.MIN_VALUE
    private var lastError: String? = null
    private var slowFrames = 0

    /** Errors for the UI (models missing, mic failure). Background thread. */
    @Volatile var onError: ((String) -> Unit)? = null

    fun start(muted: Boolean) {
        running = true
        this.muted = muted
        exec.execute { loadModel() }
        exec.scheduleWithFixedDelay({ if (wakeWord == null && activeModel.isNotEmpty()) loadModel() }, 30, 30, TimeUnit.SECONDS)
        if (!muted) exec.execute { startMic() }
    }

    fun stop() {
        running = false
        exec.shutdownNow()
        mic?.stop()
        mic = null
        synchronized(wwLock) { wakeWord?.close(); wakeWord = null }
    }

    fun setMuted(m: Boolean) {
        if (m == muted || !running) return
        muted = m
        VoiceLog.i("Microphone ${if (m) "muted (released)" else "unmuted"}")
        exec.execute { if (m) stopMic() else startMic() }
    }

    fun setGain(g: Float) {
        gain = g
        mic?.gain = g
    }

    // ------------------------------------------------------------------ WakeWordCatalog

    override fun available(): List<WakeWordInfo> =
        modelFiles().map { WakeWordInfo(TfliteWakeWord.modelName(it.name), friendlyName(it.name)) }

    override fun activeIds(): List<String> =
        if (activeModel.isEmpty()) emptyList() else listOf(TfliteWakeWord.modelName(activeModel))

    override fun setActive(ids: List<String>) {
        val file = ids.firstOrNull()?.let { "$it.tflite" } ?: ""
        if (file.isNotEmpty() && modelFiles().none { it.name == file }) {
            VoiceLog.w("Unknown wake word '$file'; ignoring")
            return
        }
        if (file == activeModel) return
        activeModel = file
        phrase = friendlyName(file)
        onActiveModelChanged(file)
        if (running) exec.execute { loadModel() }
    }

    private fun modelFiles(): List<File> =
        modelsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".tflite") &&
                f.name != TfliteWakeWord.MELSPEC_FILE && f.name != TfliteWakeWord.EMBEDDING_FILE
        }.orEmpty().sortedBy { it.name }

    // ------------------------------------------------------------------ internals

    private fun error(msg: String) {
        VoiceLog.w(msg)
        try { onError?.invoke(msg) } catch (_: Exception) {}
    }

    /** Executor thread. Loads [activeModel], replacing the current one. */
    private fun loadModel() {
        val model = activeModel
        if (model.isEmpty()) {
            synchronized(wwLock) { wakeWord?.close(); wakeWord = null }
            VoiceLog.i("No wake word selected; wake word detection off")
            return
        }
        try {
            val ww = try {
                TfliteWakeWord.load(modelsDir, model)
            } catch (e: ModelsMissingException) {
                // A web install (or a wiped app) has no models yet: fetch them on the panel itself.
                val fetched = try { ModelFetcher().ensure(modelsDir) } catch (io: java.io.IOException) {
                    throw ModelsMissingException("${e.message}; downloading them failed: ${io.message}")
                }
                if (fetched.isEmpty()) throw e
                TfliteWakeWord.load(modelsDir, model)
            }
            if (model != activeModel) { ww.close(); return }
            synchronized(wwLock) { wakeWord?.close(); wakeWord = ww }
            lastError = null
            VoiceLog.i("Wake word '${ww.name}' ('$phrase') loaded; threshold $threshold")
        } catch (e: ModelsMissingException) {
            val msg = e.message ?: "Wake word models missing"
            if (msg != lastError) error(msg) // don't spam on every retry
            lastError = msg
        } catch (e: Throwable) {
            error("Wake word init failed: ${e.message}")
        }
    }

    private fun startMic() {
        if (!running || muted || mic != null) return
        val m = Microphone(gain = gain, onError = ::error, onFrame = ::onMicFrame)
        mic = m
        m.start()
    }

    private fun stopMic() {
        mic?.stop()
        mic = null
    }

    /** Mic thread. */
    private fun onMicFrame(frame: ShortArray) {
        val c = controller
        val state = c?.currentState
        val streaming = state == VoiceAssistantController.State.STARTING || state == VoiceAssistantController.State.STREAMING
        if (streaming) {
            c?.onMicAudio(toBytes(frame))
            for (v in frame) {
                val a = kotlin.math.abs(v.toInt())
                if (a > runPeak) runPeak = a
                runSumSq += a.toDouble() * a
            }
            runSamples += frame.size
        } else if (runSamples > 0) {
            // Level of the audio HA received for the last run (helps tune mic gain).
            val rms = kotlin.math.sqrt(runSumSq / runSamples) / 32768.0
            VoiceLog.i("Run audio: %.1f s, peak %.3f, rms %.4f (gain %.1f)".format(
                runSamples / 16000.0, runPeak / 32768.0, rms, gain))
            runPeak = 0; runSumSq = 0.0; runSamples = 0
        }
        val t0 = nowMs()
        val score = synchronized(wwLock) {
            val ww = wakeWord ?: return
            try {
                ww.process(frame)
            } catch (e: Exception) {
                VoiceLog.w("wake word inference failed", e)
                return
            }
        }
        val now = nowMs()
        if (now - t0 > 60 && ++slowFrames % 50 == 1) VoiceLog.w("Wake word inference slow: ${now - t0} ms per 80 ms frame")
        if (score >= threshold && now >= refractoryUntil && state == VoiceAssistantController.State.IDLE) {
            refractoryUntil = now + config.refractoryMs
            VoiceLog.i("Wake word '$phrase' detected (score %.3f)".format(score))
            c?.onWakeWord(phrase)
        }
    }

    private fun toBytes(frame: ShortArray): ByteArray {
        val out = ByteArray(frame.size * 2)
        for (i in frame.indices) {
            val s = frame[i].toInt()
            out[2 * i] = s.toByte()
            out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    companion object {
        /** `hey_jarvis_v0.1.tflite` -> "Hey Jarvis". */
        fun friendlyName(modelFile: String): String =
            TfliteWakeWord.phraseFor(modelFile).split(' ').filter { it.isNotEmpty() }
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }
}
