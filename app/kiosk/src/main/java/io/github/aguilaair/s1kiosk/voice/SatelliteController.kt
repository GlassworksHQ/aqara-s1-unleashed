package io.github.aguilaair.s1kiosk.voice

import org.json.JSONObject

/** Sink for TTS / announcement audio sent by Home Assistant. */
interface AudioOutput {
    /** Starts a new stream (cancels any stream still playing). */
    fun begin(rate: Int, width: Int, channels: Int)

    /** Queues raw PCM in the format given to [begin]. Must not block for long. */
    fun write(pcm: ByteArray)

    /** Marks the end of the stream; [onDone] runs once everything queued has been played. */
    fun end(onDone: () -> Unit)

    /** Stops playback immediately and drops queued audio. */
    fun cancel()
}

/**
 * Wyoming satellite state machine (pure JVM, no Android APIs), modelled on rhasspy
 * wyoming-satellite's `WakeStreamingSatellite` and on what Home Assistant's
 * `wyoming/assist_satellite.py` sends and expects.
 *
 * Flow with local wake word:
 * ```
 * HA  -> describe             sat -> info
 * HA  -> run-satellite        (this connection becomes the active server)
 * HA  -> ping (every ~2 s)    sat -> pong
 * [wake word]                 sat -> detection, run-pipeline{asr->tts}, audio-start, audio-chunk...
 * HA  -> transcribe, voice-started, voice-stopped, transcript   (sat stops streaming: audio-stop)
 * HA  -> synthesize, audio-start, audio-chunk..., audio-stop    (sat plays, then -> played)
 * HA  -> error                (pipeline failed; sat stops streaming)
 * HA  -> pause-satellite      (muted / removed; sat stops streaming)
 * ```
 * Home Assistant never sends an explicit "pipeline finished" event, so the satellite goes back
 * to idle after `played`, on `error`, on disconnect, or after a watchdog timeout.
 */
class SatelliteController(
    private val infoProvider: () -> JSONObject,
    private val output: AudioOutput,
    private val emit: (VoiceEvent) -> Unit,
    private val refractoryMs: Long,
    private val maxStreamMs: Long,
    private val connectionTimeoutMs: Long = 30_000L,
    private val thinkingTimeoutMs: Long = 60_000L,
    private val clock: () -> Long = ::nowMs,
) : WyomingServer.Handler {

    enum class State { IDLE, STREAMING, THINKING, SPEAKING }

    companion object {
        const val MIC_RATE = 16_000
        const val MIC_WIDTH = 2
        const val MIC_CHANNELS = 1
    }

    private val lock = Any()
    private var active: WyomingServer.Connection? = null
    private var paused = false
    private var state = State.IDLE
    private var stateSince = clock()
    private var refractoryUntil = Long.MIN_VALUE
    private var streamTimestampMs = 0L
    private var playbackGen = 0
    private var outputOpen = false

    val currentState: State get() = synchronized(lock) { state }
    val isConnected: Boolean get() = synchronized(lock) { active?.isOpen == true && !paused }

    /** Collects side effects inside the lock and runs them after it is released. */
    private inner class Effects {
        val sends = ArrayList<Pair<WyomingServer.Connection, WyomingEvent>>()
        val events = ArrayList<VoiceEvent>()
        val actions = ArrayList<() -> Unit>()

        fun send(conn: WyomingServer.Connection?, type: String, data: JSONObject = JSONObject(), payload: ByteArray? = null) {
            if (conn != null) sends += conn to WyomingEvent(type, data, payload)
        }

        fun setState(s: State) {
            if (state == s) return
            state = s
            stateSince = clock()
            events += when (s) {
                State.IDLE -> VoiceEvent.Idle
                State.STREAMING -> VoiceEvent.Listening
                State.THINKING -> VoiceEvent.Thinking
                State.SPEAKING -> VoiceEvent.Speaking
            }
        }

        fun run() {
            for ((c, e) in sends) c.send(e)
            for (a in actions) a()
            for (e in events) {
                try { emit(e) } catch (t: Throwable) { VoiceLog.w("listener threw", t) }
            }
        }
    }

    private inline fun tx(block: Effects.() -> Unit) {
        val fx = Effects()
        synchronized(lock) { fx.block() }
        fx.run()
    }

    private fun Effects.stopStreaming(sendAudioStop: Boolean, next: State) {
        if (state != State.STREAMING) return
        if (sendAudioStop) send(active, WyomingTypes.AUDIO_STOP, JSONObject().put("timestamp", streamTimestampMs))
        setState(next)
    }

    // ---------------------------------------------------------------- local side (mic thread)

    /** Called by the wake word detector (mic thread) when a score crosses the threshold. */
    fun onWakeWord(name: String) = tx {
        val now = clock()
        if (state != State.IDLE || now < refractoryUntil) return@tx
        refractoryUntil = now + refractoryMs
        events += VoiceEvent.WakeDetected
        val conn = active
        if (conn == null || !conn.isOpen || paused) {
            events += VoiceEvent.Error(
                if (paused) "Wake word ignored: satellite is paused/muted in Home Assistant"
                else "Wake word ignored: Home Assistant is not connected"
            )
            events += VoiceEvent.Idle
            return@tx
        }
        VoiceLog.i("Wake word '$name' -> running pipeline on $conn")
        streamTimestampMs = 0L
        send(conn, WyomingTypes.DETECTION, JSONObject().put("name", name))
        send(
            conn, WyomingTypes.RUN_PIPELINE,
            JSONObject()
                .put("start_stage", "asr")
                .put("end_stage", "tts")
                .put("restart_on_end", false)
                .put("wake_word_name", name),
        )
        send(conn, WyomingTypes.AUDIO_START, audioFormat(MIC_RATE, MIC_WIDTH, MIC_CHANNELS).put("timestamp", 0))
        setState(State.STREAMING)
    }

    /** 16 kHz / 16-bit / mono PCM from the microphone (mic thread). */
    fun onMicAudio(pcm: ByteArray) {
        val conn: WyomingServer.Connection
        val ts: Long
        synchronized(lock) {
            if (state != State.STREAMING) return
            conn = active ?: return
            ts = streamTimestampMs
            streamTimestampMs += pcm.size / (MIC_WIDTH * MIC_CHANNELS) * 1000L / MIC_RATE
        }
        conn.send(
            WyomingEvent(
                WyomingTypes.AUDIO_CHUNK,
                audioFormat(MIC_RATE, MIC_WIDTH, MIC_CHANNELS).put("timestamp", ts),
                pcm,
            )
        )
    }

    /** Watchdog; call about once per second. */
    fun tick() {
        var stale: WyomingServer.Connection? = null
        tx {
            val now = clock()
            val conn = active
            if (conn != null && now - conn.lastReceivedAt > connectionTimeoutMs) {
                VoiceLog.w("No events from $conn for ${connectionTimeoutMs} ms; dropping it")
                stale = conn
            }
            when (state) {
                State.STREAMING -> if (now - stateSince > maxStreamMs) {
                    VoiceLog.i("Streaming limit reached; sending audio-stop")
                    stopStreaming(sendAudioStop = true, next = State.THINKING)
                }
                State.THINKING -> if (now - stateSince > thinkingTimeoutMs) setState(State.IDLE)
                else -> {}
            }
        }
        stale?.close() // the reader thread then calls onClosed()
    }

    fun shutdown() = tx {
        val conn = active
        active = null
        if (outputOpen) {
            outputOpen = false
            playbackGen++
            actions += { output.cancel() }
        }
        conn?.let { actions += { it.close() } }
        setState(State.IDLE)
    }

    // ---------------------------------------------------------------- Home Assistant side

    override fun onEvent(conn: WyomingServer.Connection, event: WyomingEvent) {
        val type = event.type
        if (type != WyomingTypes.AUDIO_CHUNK && type != WyomingTypes.PING) VoiceLog.d("<- $conn $event")
        when (type) {
            WyomingTypes.DESCRIBE -> conn.send(WyomingEvent(WyomingTypes.INFO, infoProvider()))
            WyomingTypes.PING -> {
                val pong = JSONObject()
                if (event.data.has("text")) pong.put("text", event.data.opt("text"))
                conn.send(WyomingEvent(WyomingTypes.PONG, pong))
                adoptIfNone(conn)
            }
            WyomingTypes.PONG -> {}
            WyomingTypes.RUN_SATELLITE -> onRunSatellite(conn)
            WyomingTypes.PAUSE_SATELLITE -> onPauseSatellite(conn)
            else -> {
                if (!adoptIfNone(conn)) {
                    VoiceLog.d("Ignoring ${event.type} from inactive $conn")
                    return
                }
                onServerEvent(conn, event)
            }
        }
    }

    override fun onClosed(conn: WyomingServer.Connection) = tx {
        if (active !== conn) return@tx
        active = null
        if (state == State.STREAMING || state == State.THINKING) setState(State.IDLE)
    }

    /** Like wyoming-satellite: a connection that talks to us becomes the server if there's none. */
    private fun adoptIfNone(conn: WyomingServer.Connection): Boolean = synchronized(lock) {
        val cur = active
        if (cur == null || !cur.isOpen) {
            active = conn
            true
        } else {
            cur === conn
        }
    }

    private fun onRunSatellite(conn: WyomingServer.Connection) = tx {
        val previous = active
        if (previous != null && previous !== conn) {
            VoiceLog.i("run-satellite from $conn replaces $previous")
            actions += { previous.close() }
        }
        active = conn
        paused = false
        // Back to wake word detection (same as wyoming-satellite on run-satellite).
        stopStreaming(sendAudioStop = false, next = State.IDLE)
        if (state == State.THINKING) setState(State.IDLE)
        // Report readiness even if the state didn't change.
        if (state == State.IDLE && VoiceEvent.Idle !in events) events += VoiceEvent.Idle
    }

    private fun onPauseSatellite(conn: WyomingServer.Connection) = tx {
        if (active != null && active !== conn) return@tx
        paused = true
        stopStreaming(sendAudioStop = false, next = State.IDLE)
        if (state == State.THINKING) setState(State.IDLE)
    }

    private fun onServerEvent(conn: WyomingServer.Connection, event: WyomingEvent) {
        val data = event.data
        when (event.type) {
            WyomingTypes.TRANSCRIBE, WyomingTypes.VOICE_STARTED -> {}
            WyomingTypes.VOICE_STOPPED -> tx { stopStreaming(sendAudioStop = true, next = State.THINKING) }
            WyomingTypes.TRANSCRIPT -> tx {
                VoiceLog.i("Transcript: ${data.optString("text")}")
                stopStreaming(sendAudioStop = true, next = State.THINKING)
            }
            WyomingTypes.SYNTHESIZE -> VoiceLog.i("Response: ${data.optString("text")}")
            WyomingTypes.ERROR -> tx {
                val text = data.optString("text", "Unknown error")
                VoiceLog.w("Pipeline error: $text (${data.optString("code")})")
                events += VoiceEvent.Error(text)
                stopStreaming(sendAudioStop = false, next = State.IDLE)
                if (state == State.THINKING) setState(State.IDLE)
            }
            WyomingTypes.AUDIO_START -> tx {
                stopStreaming(sendAudioStop = true, next = State.SPEAKING)
                beginOutput(data)
                setState(State.SPEAKING)
            }
            WyomingTypes.AUDIO_CHUNK -> {
                val pcm = event.payload ?: return
                tx {
                    if (!outputOpen) {
                        stopStreaming(sendAudioStop = true, next = State.SPEAKING)
                        beginOutput(data)
                        setState(State.SPEAKING)
                    }
                }
                output.write(pcm)
            }
            WyomingTypes.AUDIO_STOP -> tx {
                if (!outputOpen) {
                    // Nothing was played, but HA still waits for `played`.
                    send(active, WyomingTypes.PLAYED)
                    return@tx
                }
                outputOpen = false
                val gen = playbackGen
                actions += { output.end { onPlaybackDone(gen) } }
            }
            WyomingTypes.DETECT, WyomingTypes.DETECTION -> {}
            else -> if (!event.type.startsWith("timer-")) VoiceLog.d("Unhandled ${event.type}")
        }
    }

    private fun Effects.beginOutput(data: JSONObject) {
        val rate = data.optInt("rate", 22_050)
        val width = data.optInt("width", 2)
        val channels = data.optInt("channels", 1)
        playbackGen++
        outputOpen = true
        actions += { output.begin(rate, width, channels) }
    }

    private fun onPlaybackDone(gen: Int) = tx {
        if (gen != playbackGen) return@tx // superseded by a newer stream
        send(active, WyomingTypes.PLAYED)
        if (state == State.SPEAKING) setState(State.IDLE)
    }

    private fun audioFormat(rate: Int, width: Int, channels: Int) =
        JSONObject().put("rate", rate).put("width", width).put("channels", channels)
}
