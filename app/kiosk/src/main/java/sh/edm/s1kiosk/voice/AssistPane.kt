/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package sh.edm.s1kiosk.voice

import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import dev.glassworks.pane.PanelTimer
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.ApiServer
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.EspHomeLog
import sh.edm.esphome.NumberEntity
import sh.edm.esphome.SwitchEntity
import sh.edm.s1kiosk.esphome.UrlPlayer
import sh.edm.s1kiosk.esphome.VoiceAssistantController
import sh.edm.s1kiosk.esphome.WakeWordCatalog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The microphone and wake word side of the assistant. [VoiceEngine] is the real one. */
interface VoiceInput : WakeWordCatalog {
    var controller: VoiceAssistantController?
    var threshold: Float

    /** Local failures (models missing, microphone). Background thread. */
    var onError: ((String) -> Unit)?

    fun start(muted: Boolean)
    fun stop()
    fun setMuted(m: Boolean)
    fun setGain(g: Float)
}

/** The panel's speaker as the assistant sees it: music it pauses while it listens and talks. */
interface SpeakerMedia {
    /** Music is playing now. */
    val playing: Boolean

    /** An announcement holds the speaker. The assistant leaves the music alone then. */
    val announcing: Boolean

    fun pause()
    fun resume()
}

/**
 * The voice assistant as a pane: wake word, the Assist run over the ESPHome API, the voice
 * entities and the wake word, microphone and gain settings. It claims the speaker while it listens
 * and talks ([PanelEvent.SpeakerClaimed]) and reports voice timers as [PanelEvent.TimersChanged] and
 * [PanelEvent.TimerFinished].
 *
 * The core starts it only while voice is on (its switch, [AssistDefinition.switch]), and may stop and start
 * it again while the panel runs. Its entities stay while it is off, because the panel has always shown them.
 *
 * The core keeps the speaker. It hands over [player] (the voice channel, shared with announcements),
 * [speaker] (the music to pause), [resolveUrl] and [wakeChime] (the chime player is shared with timers).
 * [createInput] makes the microphone and wake word engine from the config, the stored wake word id and
 * a callback that stores the word the user picks. [onVoiceEvent] is how the voice overlay hears about
 * every event.
 */
class AssistPane(
    private val ctx: PaneContext,
    private val player: UrlPlayer,
    private val speaker: SpeakerMedia,
    private val resolveUrl: (String) -> String,
    private val wakeChime: () -> Unit,
    private val onVoiceEvent: (VoiceEvent) -> Unit,
    private val createInput: (VoiceConfig, String, (String) -> Unit) -> VoiceInput,
) : Pane {
    override val id = "assist"

    private val prefs = VoicePrefs(ctx.prefs(id))

    @Volatile private var input: VoiceInput? = null
    @Volatile private var controller: VoiceAssistantController? = null
    @Volatile private var server: ApiServer? = null
    @Volatile private var mediaPausedForVoice = false
    private var watchdog: ScheduledExecutorService? = null

    override fun entities(): List<ApiEntity> = listOf(
        SwitchEntity(
            "mute_microphone", "Mute microphone", "mdi:microphone-off", EntityCategory.CONFIG,
            get = { prefs.micMuted }, set = { prefs.micMuted = it },
        ),
        SwitchEntity(
            "wake_sound", "Wake sound", "mdi:bell-ring-outline", EntityCategory.CONFIG,
            disabledByDefault = true, get = { prefs.wakeSound }, set = { prefs.wakeSound = it },
        ),
        SwitchEntity(
            "voice_overlay", "Voice overlay", "mdi:message-badge-outline", EntityCategory.CONFIG,
            disabledByDefault = true, get = { prefs.overlay }, set = { prefs.overlay = it },
        ),
        NumberEntity(
            "mic_gain", "Mic gain", "mdi:microphone-plus", disabledByDefault = true, min = 0.5f, max = 4f, step = 0.1f, unit = "x",
            get = { prefs.micGain }, set = { prefs.micGain = it },
        ),
        // microWakeWord's cutoffs go up to 0.97 (WakeWords.MICRO), openWakeWord's default is 0.5.
        NumberEntity(
            "wake_word_threshold", "Wake word threshold", "mdi:account-voice", disabledByDefault = true, min = 0.1f, max = 0.99f, step = 0.01f,
            get = { prefs.threshold }, set = { prefs.threshold = it },
        ),
    )

    /** The Assist satellite's state in Home Assistant's words (Idle, Listening, Processing, Responding), for the settings page (#212). */
    @Volatile var satellite: String = IDLE
        private set

    /** The timers running or paused now, as last reported. */
    @Volatile var timers: List<PanelTimer> = emptyList()
        private set

    /**
     * A wake word picked on the panel's settings page (#212). Home Assistant reads the satellite's wake
     * words only when it sets the satellite up, which it does on each connect (esphome/manager.py
     * on_disconnect unloads the assist_satellite platform). Its wake word select then keeps its own pick
     * unless that pick reads "no wake word": the old word is no longer active, so the first read turns
     * it to no_wake_word, and only a second read takes the panel's word (esphome/select.py
     * async_satellite_config_updated, read 2026-11-14; the root's panel run saw no_wake_word after one).
     * So Home Assistant is asked to connect again twice: now, and once more after its next read. Only
     * within [REREAD_WINDOW_MS] of the pick: a read much later (HA was down) is HA's own and buys nothing.
     */
    fun useWakeWord(id: String) {
        if (id == prefs.wakeWord) return
        prefs.wakeWord = id
        synchronized(rereadLock) {
            rereadUntil = clock() + REREAD_WINDOW_MS
            rereads = 2
        }
        ctx.entitiesChanged()
    }

    /** Guards [rereads] and [rereadUntil] together: a late read must not clear a newer pick's count (CodeRabbit on #249). */
    private val rereadLock = Any()

    /**
     * Configuration reads by Home Assistant still to count after a word picked on the panel ([useWakeWord]):
     * any read, the first one after the pick included, whatever started it.
     */
    private var rereads = 0

    /** Until when ([clock]) a read still counts toward the second reconnect. */
    private var rereadUntil = 0L

    /** `SystemClock.elapsedRealtime`; a test moves it. */
    internal var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /**
     * How long after Home Assistant's read it is asked to connect again: time for it to apply the read.
     * Empirical: 2 s worked in the root's panel run on 2026-11-14, the only evidence; HA applies the read
     * at once in its event loop (esphome/select.py), so it is a margin, not a measured need.
     */
    internal var rereadDelayMs = 2_000L

    /** After a read: the first of two, within the window, asks for the second ([useWakeWord]). Any thread. */
    private fun configurationRead() {
        val again = synchronized(rereadLock) {
            when {
                rereads == 0 -> false
                clock() > rereadUntil -> { rereads = 0; false }
                else -> --rereads == 1
            }
        }
        if (again) {
            val w = watchdog
            if (w == null) ctx.entitiesChanged()
            else runCatching { w.schedule({ ctx.entitiesChanged() }, rereadDelayMs, TimeUnit.MILLISECONDS) }.onFailure { ctx.entitiesChanged() }
        }
    }

    /** Hands Home Assistant's voice requests to the controller. Called again if the server restarts. */
    override fun bind(server: ApiServer) {
        this.server = server
        server.voice = controller
    }

    override fun start() {
        val cfg = VoiceConfig(threshold = prefs.threshold, micGain = prefs.micGain)
        val engine = createInput(cfg, prefs.wakeWord) { id -> prefs.wakeWord = id }
        engine.onError = { msg -> dispatch(VoiceEvent.Error(msg, local = true)) }
        val c = VoiceAssistantController(
            send = { type, payload -> server?.sendVoice(type, payload) ?: false },
            player = player,
            wakeWords = engine,
            emit = ::dispatch,
            resolveUrl = resolveUrl,
            onTimerFinished = { name -> ctx.emit(PanelEvent.TimerFinished(name)) },
            onTimersChanged = { list ->
                ctx.emit(PanelEvent.TimersChanged(list.map { PanelTimer(it.id, it.name, it.totalSeconds, it.secondsLeft, it.active) }))
            },
            maxStreamMs = cfg.maxStreamMs,
            onConfigurationRead = ::configurationRead,
        )
        engine.controller = c
        controller = c
        // Started again while the server runs (voice turned on): bind() has run already.
        server?.voice = c
        input = engine
        engine.start(muted = prefs.micMuted)
        watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "assist-watchdog").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ c.tick() }, 1, 1, TimeUnit.SECONDS) }
    }

    override fun stop() {
        val running = controller != null
        // Turned off mid-run: end the run, so its Idle resumes the music and lets go of the speaker. Closed,
        // not only cancelled: a wake word on its way from the microphone can't start a new run.
        controller?.close()
        watchdog?.shutdownNow()
        watchdog = null
        input?.stop()
        input = null
        // The server outlives the pane (voice turned off, or the panel stopping): take the stopped controller back.
        server?.let { if (it.voice === controller) it.voice = null }
        controller = null
        satellite = IDLE
        // No voice, no timer reports: the board would stand still.
        if (running) ctx.emit(PanelEvent.TimersChanged(emptyList()))
    }

    override fun onEvent(event: PanelEvent) {
        if (event is PanelEvent.TimersChanged) timers = event.timers
        if (event !is PanelEvent.SettingChanged) return
        val engine = input ?: return
        when (event.key) {
            VoicePrefs.KEY_MIC_MUTED -> engine.setMuted(prefs.micMuted)
            VoicePrefs.KEY_MIC_GAIN -> engine.setGain(prefs.micGain)
            VoicePrefs.KEY_WAKE_THRESHOLD, VoicePrefs.KEY_WAKE_CUTOFF -> engine.threshold = prefs.threshold
            VoicePrefs.KEY_WAKE_WORD -> {
                engine.setActive(listOfNotNull(prefs.wakeWord.ifEmpty { null }))
                engine.threshold = prefs.threshold
            }
        }
    }

    /** The user tapped the voice overlay: stop streaming and playback. */
    fun cancel() {
        controller?.cancel()
    }

    internal fun dispatch(e: VoiceEvent) {
        if (e is VoiceEvent.WakeDetected && prefs.wakeSound) wakeChime()
        satellite = when (e) {
            is VoiceEvent.WakeDetected, is VoiceEvent.Listening -> "Listening"
            is VoiceEvent.Thinking -> "Processing"
            is VoiceEvent.Speaking -> "Responding"
            is VoiceEvent.Idle, is VoiceEvent.Error -> IDLE
            else -> satellite
        }
        // Pause music while the assistant listens and talks, resume afterwards.
        when (e) {
            is VoiceEvent.WakeDetected, is VoiceEvent.Listening, is VoiceEvent.Speaking -> {
                if (speaker.playing && !speaker.announcing) { mediaPausedForVoice = true; speaker.pause() }
                ctx.emit(PanelEvent.SpeakerClaimed(true))
            }
            is VoiceEvent.Idle, is VoiceEvent.Error -> {
                if (mediaPausedForVoice) { mediaPausedForVoice = false; speaker.resume() }
                if (!speaker.announcing) ctx.emit(PanelEvent.SpeakerClaimed(false))
            }
            else -> Unit
        }
        try { onVoiceEvent(e) } catch (t: Throwable) { EspHomeLog.w("voice listener failed", t) }
    }

    internal companion object {
        const val IDLE = "Idle"

        /** How long after the panel's pick Home Assistant's reads still count ([useWakeWord]). Internal for tests. */
        internal const val REREAD_WINDOW_MS = 30_000L
    }
}
