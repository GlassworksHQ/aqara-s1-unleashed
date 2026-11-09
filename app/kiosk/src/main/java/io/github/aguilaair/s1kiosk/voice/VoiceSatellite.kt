package io.github.aguilaair.s1kiosk.voice

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import io.github.aguilaair.s1kiosk.BuildConfig
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Home Assistant Wyoming voice satellite with on-device openWakeWord.
 *
 * - Wyoming TCP server on [VoiceConfig.port], advertised as `_wyoming._tcp` over mDNS.
 * - Microphone runs continuously; every 80 ms frame goes through openWakeWord (TFLite).
 * - On detection: `detection` + `run-pipeline` (asr -> tts), then mic audio is streamed to HA
 *   until HA reports `voice-stopped` / `transcript` / `error`.
 * - TTS audio from HA is played with AudioTrack, then `played` is sent.
 *
 * [start] and [stop] return quickly and may be called from the main thread (e.g. a foreground
 * Service); all I/O and inference run on background threads. [listener] is invoked on those
 * background threads. If the wake word models are missing, an [VoiceEvent.Error] is emitted, the
 * Wyoming server stays up (HA can still add the satellite), and loading is retried every 30 s,
 * so `adb push`ing the models is enough.
 */
class VoiceSatellite(context: Context, private val config: VoiceConfig) {

    /** Status callback for UI; called on a background thread. */
    @Volatile var listener: ((VoiceEvent) -> Unit)? = null

    private val appContext = context.applicationContext
    private val modelsDir = config.resolveModelsDir(appContext)
    private val wakeName = TfliteWakeWord.modelName(config.wakeModel)
    private val wakePhrase = TfliteWakeWord.phraseFor(config.wakeModel)

    private val lock = Any()
    private var running = false
    private var executor: ScheduledExecutorService? = null
    private var teardown: Thread? = null

    // Owned by the executor thread while running.
    private var server: WyomingServer? = null
    private var controller: SatelliteController? = null
    private var player: TtsPlayer? = null
    private var mic: Microphone? = null
    private var zeroconf: ZeroconfAdvertiser? = null
    @Volatile private var wakeWord: TfliteWakeWord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastError: String? = null

    val isRunning: Boolean get() = synchronized(lock) { running }

    fun start() {
        val exec: ScheduledExecutorService
        val previousTeardown: Thread?
        synchronized(lock) {
            if (running) return
            if (!config.enabled) {
                VoiceLog.i("Voice satellite disabled in config")
                return
            }
            running = true
            previousTeardown = teardown
            exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "voice-main").apply { isDaemon = true } }
            executor = exec
        }
        exec.execute {
            previousTeardown?.join(5_000)
            startInternal(exec)
        }
    }

    fun stop() {
        val exec: ScheduledExecutorService
        synchronized(lock) {
            if (!running) return
            running = false
            exec = executor ?: return
            executor = null
        }
        exec.shutdownNow()
        val t = Thread({
            exec.awaitTermination(5, TimeUnit.SECONDS)
            stopInternal()
        }, "voice-teardown")
        synchronized(lock) { teardown = t }
        t.start()
    }

    // ------------------------------------------------------------------ internals

    private fun dispatch(event: VoiceEvent) {
        if (event is VoiceEvent.Error) VoiceLog.w("VoiceEvent.Error: ${event.message}")
        try { listener?.invoke(event) } catch (e: Exception) { VoiceLog.w("listener threw", e) }
    }

    private fun startInternal(exec: ScheduledExecutorService) {
        VoiceLog.i("Starting voice satellite '${config.name}' on port ${config.port}, models in $modelsDir")
        val p = TtsPlayer().also { player = it }
        val c = SatelliteController(
            infoProvider = {
                WyomingInfo.build(
                    satelliteName = config.name,
                    area = config.area,
                    version = BuildConfig.VERSION_NAME,
                    wakeModelName = wakeName,
                    wakePhrase = wakePhrase,
                    wakeInstalled = wakeWord != null,
                )
            },
            output = p,
            emit = ::dispatch,
            refractoryMs = config.refractoryMs,
            maxStreamMs = config.maxStreamMs,
        ).also { controller = it }

        acquireLocks()
        startServer(exec, c)
        exec.scheduleWithFixedDelay({ c.tick() }, 1, 1, TimeUnit.SECONDS)
        if (!loadModels()) {
            exec.scheduleWithFixedDelay({ if (wakeWord == null && loadModels()) startMic() }, 30, 30, TimeUnit.SECONDS)
        } else {
            startMic()
        }
        dispatch(VoiceEvent.Idle)
    }

    private fun startServer(exec: ScheduledExecutorService, c: SatelliteController) {
        val s = WyomingServer(config.port, c)
        try {
            s.start()
            server = s
            if (config.zeroconf) {
                zeroconf = ZeroconfAdvertiser(appContext, zeroconfName(), s.localPort).also { it.start() }
            }
        } catch (e: IOException) {
            dispatch(VoiceEvent.Error("Cannot listen on port ${config.port}: ${e.message}; retrying"))
            exec.schedule({ startServer(exec, c) }, 10, TimeUnit.SECONDS)
        }
    }

    private fun loadModels(): Boolean = try {
        val ww = TfliteWakeWord.load(modelsDir, config.wakeModel)
        wakeWord = ww
        lastError = null
        VoiceLog.i("Wake word '$wakeName' ('$wakePhrase') loaded; threshold ${config.threshold}")
        true
    } catch (e: ModelsMissingException) {
        val msg = e.message ?: "Wake word models missing"
        if (msg != lastError) dispatch(VoiceEvent.Error(msg)) // don't spam on every retry
        lastError = msg
        false
    } catch (e: Throwable) {
        dispatch(VoiceEvent.Error("Wake word init failed: ${e.message}"))
        false
    }

    private fun startMic() {
        val c = controller ?: return
        val m = Microphone(
            gain = config.micGain,
            onError = { dispatch(VoiceEvent.Error(it)) },
            onFrame = { frame -> onMicFrame(c, frame) },
        )
        mic = m
        m.start()
    }

    private var slowFrames = 0

    /** Mic thread. */
    private fun onMicFrame(c: SatelliteController, frame: ShortArray) {
        if (c.currentState == SatelliteController.State.STREAMING) c.onMicAudio(toBytes(frame))
        val ww = wakeWord ?: return
        val t0 = nowMs()
        // Keep feeding the model even while busy so its buffers never hold stale audio.
        val score = try {
            ww.process(frame)
        } catch (e: Exception) {
            VoiceLog.w("wake word inference failed", e)
            return
        }
        val dt = nowMs() - t0
        if (dt > 60 && ++slowFrames % 50 == 1) VoiceLog.w("Wake word inference slow: $dt ms per 80 ms frame")
        if (score >= config.threshold) {
            VoiceLog.d("Wake score %.3f".format(score))
            c.onWakeWord(ww.name)
        }
    }

    private fun stopInternal() {
        mic?.stop()
        mic = null
        zeroconf?.stop()
        zeroconf = null
        controller?.shutdown()
        controller = null
        server?.stop()
        server = null
        player?.release()
        player = null
        wakeWord?.close()
        wakeWord = null
        releaseLocks()
        dispatch(VoiceEvent.Idle)
        VoiceLog.i("Voice satellite stopped")
    }

    @SuppressLint("HardwareIds")
    private fun zeroconfName(): String {
        val id = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return "s1kiosk-" + (id.take(8).ifEmpty { "panel" })
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "s1kiosk:voice").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "s1kiosk:voice").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            VoiceLog.w("Could not acquire wake/wifi lock", e)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
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
