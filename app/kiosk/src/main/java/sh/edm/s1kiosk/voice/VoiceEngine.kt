package sh.edm.s1kiosk.voice

import android.content.Context
import sh.edm.s1kiosk.esphome.VoiceAssistantController
import sh.edm.s1kiosk.esphome.WakeWordCatalog
import sh.edm.esphome.WakeWordInfo
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Microphone + on-device wake word, feeding the ESPHome voice assistant ([controller]).
 *
 * The microphone has one consumer: this engine. Every 80 ms frame goes through the wake word model
 * (so its buffers never hold stale audio), and while a pipeline run is starting/streaming the same
 * frame is handed to the controller, which sends it to Home Assistant. Detections only count while
 * the controller is idle and after the refractory period.
 *
 * Also the [WakeWordCatalog] behind Home Assistant's wake word select: the microWakeWord models
 * ([WakeWords.MICRO], fetched on first use into `micro/`), then, as a legacy fallback for one
 * release, every openWakeWord `*.tflite` already in the models dir except the shared
 * melspectrogram/embedding models. The active word id is persisted by the caller through
 * [onActiveWordChanged].
 *
 * A detection reports the word of the detector that fired. While a new word loads, the old one keeps
 * listening as itself. If the new word fails to load, the old detector is closed (a word HA didn't
 * pick must not answer), the error is reported, and loading is retried every 30 s.
 *
 * [loadDetector] loads a word's model; null loads the real ones (tests pass a fake).
 */
class VoiceEngine(
    context: Context,
    private val config: VoiceConfig,
    initialWord: String,
    private val onActiveWordChanged: (String) -> Unit,
    loadDetector: ((WakeWord) -> WakeDetector)? = null,
) : VoiceInput {

    /** A loaded model and the word it detects. */
    private class Loaded(val id: String, val phrase: String, val detector: WakeDetector)

    @Volatile override var controller: VoiceAssistantController? = null
    @Volatile override var threshold: Float = config.threshold

    private val modelsDir: File = config.resolveModelsDir(context.applicationContext)
    private val microDir = File(modelsDir, "micro")
    private val exec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "voice-main").apply { isDaemon = true } }

    private val load: (WakeWord) -> WakeDetector = loadDetector ?: { word ->
        when (word) {
            is WakeWord.Micro -> loadMicro(word.model)
            is WakeWord.Legacy -> loadLegacy("${word.id}.tflite")
        }
    }
    private val wwLock = Any()
    @Volatile private var loaded: Loaded? = null // written under wwLock
    @Volatile private var activeWord: String = initialWord
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
    @Volatile override var onError: ((String) -> Unit)? = null

    override fun start(muted: Boolean) {
        running = true
        this.muted = muted
        exec.execute { loadModel() }
        exec.scheduleWithFixedDelay({ if (loaded == null && activeWord.isNotEmpty()) loadModel() }, 30, 30, TimeUnit.SECONDS)
        if (!muted) exec.execute { startMic() }
    }

    override fun stop() {
        running = false
        exec.shutdownNow()
        mic?.stop()
        mic = null
        replace(null)
    }

    override fun setMuted(m: Boolean) {
        if (m == muted || !running) return
        muted = m
        VoiceLog.i("Microphone ${if (m) "muted (released)" else "unmuted"}")
        exec.execute { if (m) stopMic() else startMic() }
    }

    override fun setGain(g: Float) {
        gain = g
        mic?.gain = g
    }

    // ------------------------------------------------------------------ WakeWordCatalog

    override fun available(): List<WakeWordInfo> =
        WakeWords.MICRO.map { WakeWordInfo(it.id, it.phrase) } +
            legacyFiles().map { WakeWord.Legacy(TfliteWakeWord.modelName(it.name)) }
                .map { WakeWordInfo(it.id, "${it.phrase} (openWakeWord)") }

    override fun activeIds(): List<String> = if (activeWord.isEmpty()) emptyList() else listOf(activeWord)

    override fun setActive(ids: List<String>) {
        val id = ids.firstOrNull() ?: ""
        if (id.isNotEmpty() && available().none { it.id == id }) {
            VoiceLog.w("Unknown wake word '$id'; ignoring")
            return
        }
        if (id == activeWord) return
        activeWord = id
        onActiveWordChanged(id)
        if (running) exec.execute { loadModel() }
    }

    /** openWakeWord models already on the panel (0.4 and earlier fetched them). */
    private fun legacyFiles(): List<File> =
        modelsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".tflite") &&
                f.name != TfliteWakeWord.MELSPEC_FILE && f.name != TfliteWakeWord.EMBEDDING_FILE
        }.orEmpty().sortedBy { it.name }

    // ------------------------------------------------------------------ internals

    private fun error(msg: String) {
        VoiceLog.w(msg)
        try { onError?.invoke(msg) } catch (_: Exception) {}
    }

    private fun replace(next: Loaded?) {
        val old = synchronized(wwLock) { loaded.also { loaded = next } }
        if (old != null && old !== next) old.detector.close()
    }

    /** Executor thread. Loads [activeWord], replacing the current one. */
    private fun loadModel() {
        val id = activeWord
        val word = WakeWords.parse(id)
        if (word == null) {
            replace(null)
            VoiceLog.i("No wake word selected; wake word detection off")
            return
        }
        if (loaded?.id == id) return
        val failure = try {
            val detector = load(word)
            if (id != activeWord) { detector.close(); return }
            replace(Loaded(id, word.phrase, detector))
            lastError = null
            val engine = if (word is WakeWord.Micro) "microWakeWord" else "openWakeWord"
            VoiceLog.i("Wake word '$id' ('${word.phrase}', $engine) loaded; threshold $threshold")
            return
        } catch (e: ModelsMissingException) {
            e.message ?: "Wake word models missing"
        } catch (e: Throwable) {
            "Wake word init failed: ${e.message}"
        }
        if (id != activeWord) return
        loaded?.let { VoiceLog.w("Wake word '${it.id}' stopped: '$id' could not load") }
        replace(null)
        if (failure != lastError) error(failure) // don't spam on every retry
        lastError = failure
    }

    /**
     * The panel fetches the models itself (a web install, or a wiped app, has none yet): all four
     * at once when this one is missing or fails its hash, so switching words later works offline.
     */
    private fun loadMicro(model: MicroModel): WakeDetector {
        val file = "${model.id}.tflite"
        val fetcher = ModelFetcher.micro()
        if (file in fetcher.missing(microDir)) {
            try {
                fetcher.ensure(microDir)
            } catch (io: java.io.IOException) {
                throw ModelsMissingException("Downloading the wake word model $file failed: ${io.message}")
            }
        }
        return MicroWakeWord.load(File(microDir, file), model)
    }

    private fun loadLegacy(file: String): WakeDetector = try {
        TfliteWakeWord.load(modelsDir, file)
    } catch (e: ModelsMissingException) {
        val fetched = try { ModelFetcher().ensure(modelsDir) } catch (io: java.io.IOException) {
            throw ModelsMissingException("${e.message}; downloading them failed: ${io.message}")
        }
        if (fetched.isEmpty()) throw e
        TfliteWakeWord.load(modelsDir, file)
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
    internal fun onMicFrame(frame: ShortArray) {
        val c = controller
        val state = c?.currentState
        val streaming = state == VoiceAssistantController.State.STARTING || state == VoiceAssistantController.State.STREAMING
        if (streaming) {
            c?.onMicAudio(toBytes(frame))
            // For the voice overlay's orb (after micGain, i.e. what Home Assistant receives).
            VoiceLevels.mic = AudioLevel.fromRms(AudioLevel.rms(frame))
            for (v in frame) {
                val a = kotlin.math.abs(v.toInt())
                if (a > runPeak) runPeak = a
                runSumSq += a.toDouble() * a
            }
            runSamples += frame.size
        } else if (runSamples > 0) {
            VoiceLevels.mic = 0f
            // Level of the audio HA received for the last run (helps tune mic gain).
            val rms = kotlin.math.sqrt(runSumSq / runSamples) / 32768.0
            VoiceLog.i("Run audio: %.1f s, peak %.3f, rms %.4f (gain %.1f)".format(
                runSamples / 16000.0, runPeak / 32768.0, rms, gain))
            runPeak = 0; runSumSq = 0.0; runSamples = 0
        }
        val t0 = nowMs()
        var phrase = ""
        val score = synchronized(wwLock) {
            val l = loaded ?: return
            phrase = l.phrase
            try {
                l.detector.process(frame)
            } catch (e: Exception) {
                VoiceLog.w("wake word inference failed", e)
                return
            }
        }
        val now = nowMs()
        if (now - t0 > 60 && ++slowFrames % 50 == 1) VoiceLog.w("Wake word inference slow: ${now - t0} ms per ${frame.size / 16} ms frame")
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
}
