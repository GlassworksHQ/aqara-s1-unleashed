package sh.edm.s1kiosk.esphome

import sh.edm.esphome.*
import sh.edm.s1kiosk.voice.VoiceEvent
import sh.edm.s1kiosk.voice.VoiceLog
import sh.edm.s1kiosk.voice.nowMs

/** Plays audio URLs (TTS responses, announcements) one after another. */
interface UrlPlayer {
    /** Plays [urls] in order; [onDone] runs once (success = all played) when done, failed or stopped. */
    fun play(urls: List<String>, onDone: (Boolean) -> Unit)
    fun stop()
}

/** The wake words the panel can detect locally (VoiceEngine: microWakeWord, legacy openWakeWord). */
interface WakeWordCatalog {
    fun available(): List<WakeWordInfo>
    fun activeIds(): List<String>
    fun setActive(ids: List<String>)
}

/**
 * Voice assistant over the ESPHome API, mirroring what esphome `voice_assistant.cpp` does for a
 * device with a media player (Home Assistant Voice PE): API audio, TTS played from a URL.
 *
 * ```
 * [local wake word]  dev -> VoiceAssistantRequest{start, flags=USE_VAD, wake_word_phrase, conversation_id}
 *                    HA  -> VoiceAssistantResponse{port=0}      (mic audio is buffered until here)
 *                    dev -> VoiceAssistantAudio{data}...         (16 kHz s16 mono, 80 ms chunks)
 * HA -> RUN_START{url}, STT_START, STT_VAD_START, STT_VAD_END (stop streaming), STT_END{text},
 *       INTENT_START, INTENT_PROGRESS{tts_start_streaming=1} (start playing RUN_START url early),
 *       INTENT_END{conversation_id, continue_conversation}, TTS_START{text}, TTS_END{url}, RUN_END
 * [playback done]    dev -> VoiceAssistantAnnounceFinished{success}   (HA: tts_response_finished)
 *                    continue_conversation=1 -> start again without a wake word
 * HA -> VoiceAssistantAnnounceRequest{media_id, preannounce_media_id, start_conversation}
 *                    play -> VoiceAssistantAnnounceFinished
 * ```
 * Pure JVM; [send] writes to the subscribed API connection.
 */
class VoiceAssistantController(
    private val send: (type: Int, payload: ProtoWriter) -> Boolean,
    private val player: UrlPlayer,
    private val wakeWords: WakeWordCatalog,
    private val emit: (VoiceEvent) -> Unit,
    private val resolveUrl: (String) -> String = { it },
    private val onTimerFinished: (String) -> Unit = {},
    /** Called (off the lock) with a snapshot whenever the set of timers changes. */
    private val onTimersChanged: (List<Timer>) -> Unit = {},
    private val maxStreamMs: Long = 15_000L,
    private val clock: () -> Long = ::nowMs,
) : VoiceAssistantHandler {

    enum class State { IDLE, STARTING, STREAMING, AWAITING, RESPONDING, ANNOUNCING }

    companion object {
        private const val START_TIMEOUT_MS = 10_000L
        private const val AWAIT_TIMEOUT_MS = 60_000L
        private const val PLAY_TIMEOUT_MS = 10 * 60_000L
        private const val CONVERSATION_TIMEOUT_MS = 300_000L // esphome conversation_timeout default
        private const val MAX_PREROLL_CHUNKS = 25 // 2 s of 80 ms chunks
        private val IGNORED_ERRORS = setOf("wake-word-timeout", "wake_word_detection_aborted", "no_wake_word")
    }

    data class Timer(val id: String, val name: String, val totalSeconds: Int, val secondsLeft: Int, val active: Boolean)

    private val lock = Any()
    private var subscribed = false
    private var state = State.IDLE
    private var stateSince = clock()
    private val preroll = ArrayDeque<ByteArray>()
    private var conversationId = ""
    private var conversationAt = Long.MIN_VALUE / 2
    private var continueConversation = false
    private var runStartUrl: String? = null
    private var streamingTts = false
    private var playGen = 0
    /** The user cancelled the current run: ignore its late events (transcript, response, TTS). */
    private var discardRun = false
    /** The voice pane stopped ([close]): no new run starts, also from a wake word already on its way. */
    private var closed = false
    /**
     * Held while playback starts ([startPlayback]) and while [close] runs, so a playback effect collected
     * before the close either starts first, and the close stops it, or sees the close and starts nothing.
     */
    private val playbackStart = Any()
    private val timers = LinkedHashMap<String, Timer>()

    val currentState: State get() = synchronized(lock) { state }
    val isSubscribed: Boolean get() = synchronized(lock) { subscribed }
    val activeTimers: List<Timer> get() = synchronized(lock) { timers.values.toList() }

    private fun setState(s: State) {
        if (s != state) VoiceLog.d("voice: $state -> $s")
        state = s
        stateSince = clock()
    }

    /** Side effects collected under the lock and run after it is released. */
    private fun locked(block: MutableList<() -> Unit>.() -> Unit) {
        val fx = ArrayList<() -> Unit>()
        synchronized(lock) { fx.block() }
        for (f in fx) try { f() } catch (t: Throwable) { VoiceLog.w("voice effect failed", t) }
    }

    // ------------------------------------------------------------------ device side

    /** A local wake word fired. Returns true if a pipeline run was requested. */
    fun onWakeWord(phrase: String): Boolean {
        var started = false
        locked {
            if (state != State.IDLE) return@locked
            started = startRun(phrase, this)
        }
        return started
    }

    private fun startRun(phrase: String?, fx: MutableList<() -> Unit>): Boolean {
        if (closed) return false
        if (!subscribed) {
            fx += { emit(VoiceEvent.Error("Home Assistant is not connected")) }
            return false
        }
        val conv = if (clock() - conversationAt < CONVERSATION_TIMEOUT_MS) conversationId else ""
        val req = ProtoWriter()
            .bool(1, true)
            .string(2, conv)
            .uint32(3, VoiceRequestFlag.USE_VAD)
            .message(4, ProtoWriter().uint32(1, 0).uint32(2, 0).float(3, 1f))
            .string(5, phrase)
        preroll.clear()
        runStartUrl = null
        streamingTts = false
        continueConversation = false
        discardRun = false
        setState(State.STARTING)
        if (!send(Msg.VOICE_ASSISTANT_REQUEST, req)) {
            setState(State.IDLE)
            fx += { emit(VoiceEvent.Error("Could not request a pipeline run")) }
            return false
        }
        VoiceLog.i("voice: requested run (wake word '${phrase ?: "-"}', conversation '${conv.ifEmpty { "-" }}')")
        fx += { emit(if (phrase != null) VoiceEvent.WakeDetected else VoiceEvent.Listening) }
        return true
    }

    /** Mic thread: one chunk of 16 kHz s16le mono audio. */
    fun onMicAudio(pcm: ByteArray) {
        locked {
            when (state) {
                State.STARTING -> {
                    preroll.addLast(pcm)
                    while (preroll.size > MAX_PREROLL_CHUNKS) preroll.removeFirst()
                }
                State.STREAMING -> {
                    // Sent under the lock so chunks can't overtake the pre-roll flush.
                    send(Msg.VOICE_ASSISTANT_AUDIO, ProtoWriter().bytes(1, pcm))
                    if (clock() - stateSince > maxStreamMs) endStream("max stream ${maxStreamMs} ms", this)
                }
                else -> Unit
            }
        }
    }

    /** Stops streaming locally: tells HA the audio ended (`VoiceAssistantAudio{end}` -> handle_stop). */
    private fun endStream(reason: String, fx: MutableList<() -> Unit>) {
        VoiceLog.i("voice: end of stream ($reason)")
        send(Msg.VOICE_ASSISTANT_AUDIO, ProtoWriter().bool(2, true))
        setState(State.AWAITING)
        fx += { emit(VoiceEvent.Thinking) }
    }

    /**
     * The user cancelled (tapped the voice overlay): stop streaming and playback and go idle, like
     * esphome `request_stop()`. Late events of the cancelled run are ignored. Returns true if
     * something was running.
     */
    fun cancel(): Boolean {
        var cancelled = false
        locked {
            when (state) {
                State.IDLE -> return@locked
                State.STARTING, State.STREAMING, State.AWAITING ->
                    send(Msg.VOICE_ASSISTANT_REQUEST, ProtoWriter()) // start=false: HA stops the pipeline
                State.RESPONDING, State.ANNOUNCING -> {
                    playGen++ // the player's onDone (stopped) is ignored
                    send(Msg.VOICE_ASSISTANT_ANNOUNCE_FINISHED, ProtoWriter().bool(1, false))
                    add { player.stop() }
                }
            }
            VoiceLog.i("voice: cancelled by the user in $state")
            cancelled = true
            discardRun = true
            continueConversation = false
            streamingTts = false
            setState(State.IDLE)
            add { emit(VoiceEvent.Idle) }
        }
        return cancelled
    }

    /**
     * The voice pane stops (voice turned off, or the panel stopping): from now on no run starts, and the
     * current one ends as [cancel] ends it, so its Idle resumes the music and lets go of the speaker.
     * Unlike [cancel], which the person uses between runs, this controller takes no run again.
     */
    fun close(): Boolean = synchronized(playbackStart) {
        locked { closed = true }
        cancel()
    }

    /** Called about once a second: watchdogs. */
    fun tick() {
        locked {
            val age = clock() - stateSince
            when (state) {
                State.STARTING -> if (age > START_TIMEOUT_MS) {
                    setState(State.IDLE)
                    add { emit(VoiceEvent.Error("Home Assistant did not start the pipeline")) }
                }
                State.STREAMING -> if (age > maxStreamMs) endStream("max stream ${maxStreamMs} ms", this)
                State.AWAITING -> if (age > AWAIT_TIMEOUT_MS) {
                    setState(State.IDLE)
                    add { emit(VoiceEvent.Idle) }
                }
                State.RESPONDING, State.ANNOUNCING -> if (age > PLAY_TIMEOUT_MS) {
                    playGen++
                    setState(State.IDLE)
                    add { player.stop(); emit(VoiceEvent.Idle) }
                }
                State.IDLE -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ from Home Assistant

    override fun onSubscribe(subscribed: Boolean) {
        locked {
            this@VoiceAssistantController.subscribed = subscribed
            if (!subscribed && state in setOf(State.STARTING, State.STREAMING, State.AWAITING)) {
                setState(State.IDLE)
                add { emit(VoiceEvent.Idle) }
            }
        }
    }

    override fun onStartResponse(port: Int, error: Boolean) {
        locked {
            if (state != State.STARTING) return@locked
            if (port != 0) VoiceLog.w("voice: HA offered UDP audio on port $port; streaming over the API instead")
            if (error) {
                setState(State.IDLE)
                add { emit(VoiceEvent.Error("Home Assistant could not start the pipeline")) }
                return@locked
            }
            setState(State.STREAMING)
            while (preroll.isNotEmpty()) send(Msg.VOICE_ASSISTANT_AUDIO, ProtoWriter().bytes(1, preroll.removeFirst()))
            add { emit(VoiceEvent.Listening) }
        }
    }

    override fun onEvent(type: Int, data: Map<String, String>) {
        locked {
            when (type) {
                VoiceEventType.RUN_START -> {
                    runStartUrl = data["url"]?.takeIf { it.isNotEmpty() }
                    streamingTts = false
                }
                VoiceEventType.STT_VAD_END -> if (state == State.STREAMING || state == State.STARTING) {
                    setState(State.AWAITING)
                    add { emit(VoiceEvent.Thinking) }
                }
                VoiceEventType.STT_END -> {
                    val text = data["text"].orEmpty()
                    VoiceLog.i("voice: heard \"$text\"")
                    if (discardRun) return@locked
                    add { emit(VoiceEvent.Heard(text)) }
                    if (state == State.STREAMING || state == State.STARTING) {
                        setState(State.AWAITING)
                        add { emit(VoiceEvent.Thinking) }
                    }
                }
                VoiceEventType.INTENT_PROGRESS -> {
                    val url = runStartUrl
                    if (data["tts_start_streaming"] == "1" && url != null && !streamingTts && !discardRun) {
                        streamingTts = true
                        playResponse(url, this)
                    }
                }
                VoiceEventType.INTENT_END -> {
                    data["conversation_id"]?.let { conversationId = it; conversationAt = clock() }
                    continueConversation = data["continue_conversation"] == "1"
                }
                VoiceEventType.TTS_START -> {
                    val text = data["text"].orEmpty()
                    VoiceLog.i("voice: response \"$text\"")
                    if (!discardRun) add { emit(VoiceEvent.Reply(text, announcement = false)) }
                }
                VoiceEventType.TTS_END -> {
                    val url = data["url"]?.takeIf { it.isNotEmpty() }
                    if (url != null && !streamingTts && state != State.RESPONDING && !discardRun) playResponse(url, this)
                }
                VoiceEventType.RUN_END -> when (state) {
                    State.STARTING, State.STREAMING, State.AWAITING -> {
                        // No TTS ("never mind") or the run ended early.
                        setState(State.IDLE)
                        add { emit(VoiceEvent.Idle) }
                    }
                    else -> Unit
                }
                VoiceEventType.ERROR -> {
                    val code = data["code"].orEmpty()
                    if (code in IGNORED_ERRORS) return@locked
                    val message = data["message"].orEmpty()
                    VoiceLog.w("voice: pipeline error $code: $message")
                    if (state == State.STARTING || state == State.STREAMING || state == State.AWAITING) {
                        if (state != State.AWAITING) send(Msg.VOICE_ASSISTANT_REQUEST, ProtoWriter()) // start=false
                        setState(State.IDLE)
                        add { emit(VoiceEvent.Error("$code: $message")) }
                    }
                }
                else -> Unit // STT_START, STT_VAD_START, INTENT_START, WAKE_WORD_*, TTS_STREAM_*
            }
        }
    }

    private fun playResponse(url: String, fx: MutableList<() -> Unit>) {
        if (closed) return
        val gen = ++playGen
        setState(State.RESPONDING)
        val resolved = resolveUrl(url)
        VoiceLog.i("voice: playing response $resolved")
        fx += { startPlayback(gen, listOf(resolved), startConversation = false) { emit(VoiceEvent.Speaking) } }
    }

    /**
     * Effect: starts playback for [gen] unless the run was cancelled or the controller closed since the
     * effect was collected. [before] runs first (the events that go with it).
     */
    private fun startPlayback(gen: Int, urls: List<String>, startConversation: Boolean, before: () -> Unit) {
        synchronized(playbackStart) {
            if (synchronized(lock) { closed || gen != playGen }) return
            before()
            player.play(urls) { ok -> onPlaybackDone(gen, ok, startConversation) }
        }
    }

    private fun onPlaybackDone(gen: Int, ok: Boolean, startConversation: Boolean) {
        locked {
            if (gen != playGen) return@locked
            // ESPHome sends this after media-player playback, for TTS responses as well.
            send(Msg.VOICE_ASSISTANT_ANNOUNCE_FINISHED, ProtoWriter().bool(1, ok))
            val again = startConversation || (state == State.RESPONDING && continueConversation)
            setState(State.IDLE)
            if (!(again && ok && startRun(null, this))) add { emit(VoiceEvent.Idle) }
        }
    }

    override fun onAnnounce(mediaId: String, text: String, preannounceMediaId: String, startConversation: Boolean) {
        locked {
            VoiceLog.i("voice: announce '$text' media=$mediaId preannounce=$preannounceMediaId")
            if (mediaId.isEmpty() || closed) {
                send(Msg.VOICE_ASSISTANT_ANNOUNCE_FINISHED, ProtoWriter().bool(1, false))
                return@locked
            }
            if (state == State.STARTING || state == State.STREAMING) send(Msg.VOICE_ASSISTANT_REQUEST, ProtoWriter())
            val gen = ++playGen
            setState(State.ANNOUNCING)
            val urls = listOf(preannounceMediaId, mediaId).filter { it.isNotEmpty() }.map(resolveUrl)
            add {
                startPlayback(gen, urls, startConversation) {
                    emit(VoiceEvent.Reply(text, announcement = true))
                    emit(VoiceEvent.Speaking)
                }
            }
        }
    }

    override fun onTimer(type: Int, id: String, name: String, totalSeconds: Int, secondsLeft: Int, active: Boolean) {
        locked {
            val t = Timer(id, name, totalSeconds, secondsLeft, active)
            VoiceLog.i("voice: timer event $type $t")
            when (type) {
                TimerEventType.STARTED, TimerEventType.UPDATED -> timers[id] = t
                TimerEventType.CANCELLED -> timers.remove(id)
                TimerEventType.FINISHED -> {
                    timers.remove(id)
                    add { onTimerFinished(name) }
                }
            }
            val snapshot = timers.values.toList()
            add { onTimersChanged(snapshot) }
        }
    }

    override fun configuration(): VoiceConfiguration =
        VoiceConfiguration(wakeWords.available(), wakeWords.activeIds(), maxActive = 1)

    override fun setActiveWakeWords(ids: List<String>) {
        VoiceLog.i("voice: HA set active wake words $ids")
        wakeWords.setActive(ids.take(1))
    }
}
