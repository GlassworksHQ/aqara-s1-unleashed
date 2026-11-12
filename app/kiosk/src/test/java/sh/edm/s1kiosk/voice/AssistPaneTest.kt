package sh.edm.s1kiosk.voice

import android.app.Application
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import dev.glassworks.pane.PanelTimer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.ApiServer
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.ProtoMessage
import sh.edm.esphome.ProtoWriter
import sh.edm.esphome.TimerEventType
import sh.edm.esphome.WakeWordInfo
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.esphome.UrlPlayer
import sh.edm.s1kiosk.esphome.VoiceAssistantController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AssistPaneTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)
    private val events = ArrayList<PanelEvent>()
    private val heard = ArrayList<VoiceEvent>()
    private var chimes = 0
    private val speaker = FakeSpeaker()
    private val input = FakeInput()
    private var created: Triple<VoiceConfig, String, (String) -> Unit>? = null

    private fun pane(voiceEnabled: Boolean = true, onVoiceEvent: (VoiceEvent) -> Unit = { heard += it }) = AssistPane(
        ctx = PaneContext(
            app, prefs,
            DeviceIdentity(
                name = "test", friendlyName = "Test", macAddress = "02:00:00:00:00:01", esphomeVersion = "x",
                compilationTime = "x", manufacturer = "x", model = "x", voiceFeatures = 0,
            ),
            publishStates = {}, emit = { events += it }, entitiesChanged = {}, sendEvent = { _, _ -> },
        ),
        voiceEnabled = voiceEnabled,
        player = object : UrlPlayer {
            override fun play(urls: List<String>, onDone: (Boolean) -> Unit) {}
            override fun stop() {}
        },
        speaker = speaker,
        resolveUrl = { it },
        wakeChime = { chimes++ },
        onVoiceEvent = onVoiceEvent,
        createInput = { cfg, model, onModel -> created = Triple(cfg, model, onModel); input },
    )

    private val panes = ArrayList<AssistPane>()
    private fun started(voiceEnabled: Boolean = true) = pane(voiceEnabled).also { panes += it; it.start() }

    @Before fun setUp() {
        prefs.micMuted = false
        prefs.wakeSound = true
    }

    @After fun tearDown() = panes.forEach { it.stop() }

    // ---- the speaker

    @Test
    fun listeningPausesPlayingMusicAndClaimsTheSpeaker() {
        speaker.playing = true
        pane().dispatch(VoiceEvent.Listening)
        assertEquals(1, speaker.pauses)
        assertEquals(listOf<PanelEvent>(PanelEvent.SpeakerClaimed(true)), events)
    }

    @Test
    fun idleAfterAPauseResumesTheMusicAndReleasesTheSpeaker() {
        speaker.playing = true
        val p = pane()
        p.dispatch(VoiceEvent.WakeDetected)
        p.dispatch(VoiceEvent.Speaking)
        p.dispatch(VoiceEvent.Idle)
        assertEquals(1, speaker.pauses)
        assertEquals(1, speaker.resumes)
        assertEquals(PanelEvent.SpeakerClaimed(false), events.last())
    }

    @Test
    fun anErrorLetsTheMusicGoOnToo() {
        speaker.playing = true
        val p = pane()
        p.dispatch(VoiceEvent.Listening)
        p.dispatch(VoiceEvent.Error("boom"))
        assertEquals(1, speaker.resumes)
        assertEquals(PanelEvent.SpeakerClaimed(false), events.last())
    }

    @Test
    fun musicThatWasNotPlayingIsNotResumed() {
        val p = pane()
        p.dispatch(VoiceEvent.Listening)
        p.dispatch(VoiceEvent.Idle)
        assertEquals(0, speaker.pauses)
        assertEquals(0, speaker.resumes)
    }

    @Test
    fun anAnnouncementKeepsTheSpeakerAndTheMusicAlone() {
        speaker.playing = true
        speaker.announcing = true
        val p = pane()
        p.dispatch(VoiceEvent.Listening)
        p.dispatch(VoiceEvent.Idle)
        assertEquals(0, speaker.pauses)
        assertEquals(listOf<PanelEvent>(PanelEvent.SpeakerClaimed(true)), events)
    }

    @Test
    fun thinkingAndTextsChangeNothingOnTheSpeaker() {
        speaker.playing = true
        val p = pane()
        p.dispatch(VoiceEvent.Thinking)
        p.dispatch(VoiceEvent.Heard("hello"))
        assertEquals(0, speaker.pauses)
        assertTrue(events.isEmpty())
    }

    @Test
    fun aThrowingOverlayDoesNotStopTheSpeakerFromBeingReleased() {
        val p = pane { throw IllegalStateException("overlay") }
        p.dispatch(VoiceEvent.Thinking)
        p.dispatch(VoiceEvent.Idle)
        assertEquals(listOf<PanelEvent>(PanelEvent.SpeakerClaimed(false)), events)
    }

    @Test
    fun everyEventReachesTheVoiceOverlay() {
        val p = pane()
        p.dispatch(VoiceEvent.WakeDetected)
        p.dispatch(VoiceEvent.Heard("hi"))
        assertEquals(listOf(VoiceEvent.WakeDetected, VoiceEvent.Heard("hi")), heard)
    }

    // ---- the wake sound

    @Test
    fun theWakeChimePlaysWhenWakeSoundIsOn() {
        pane().dispatch(VoiceEvent.WakeDetected)
        assertEquals(1, chimes)
    }

    @Test
    fun noWakeChimeWhenWakeSoundIsOffOrOnTheFollowUp() {
        val p = pane()
        prefs.wakeSound = false
        p.dispatch(VoiceEvent.WakeDetected)
        prefs.wakeSound = true
        p.dispatch(VoiceEvent.Listening)
        assertEquals(0, chimes)
    }

    // ---- settings

    @Test
    fun settingsReachTheEngine() {
        val p = started()
        prefs.micMuted = true
        p.onEvent(PanelEvent.SettingChanged("mic_muted"))
        prefs.micGain = 2.5f
        p.onEvent(PanelEvent.SettingChanged("mic_gain"))
        prefs.wakeThreshold = 0.7f
        p.onEvent(PanelEvent.SettingChanged("wake_threshold"))
        prefs.wakeModel = "hey_jarvis.tflite"
        p.onEvent(PanelEvent.SettingChanged("wake_model"))
        assertEquals(listOf(true), input.muted)
        assertEquals(listOf(2.5f), input.gains)
        assertEquals(0.7f, input.threshold)
        assertEquals(listOf(listOf("hey_jarvis")), input.active)
    }

    @Test
    fun clearingTheWakeModelDeactivatesEveryWakeWord() {
        val p = started()
        prefs.wakeModel = ""
        p.onEvent(PanelEvent.SettingChanged("wake_model"))
        assertEquals(listOf(emptyList<String>()), input.active)
    }

    @Test
    fun otherSettingsAndEventsAreIgnored() {
        val p = started()
        p.onEvent(PanelEvent.SettingChanged("zoom"))
        p.onEvent(PanelEvent.SettingChanged(null))
        p.onEvent(PanelEvent.SpeakerClaimed(true))
        assertTrue(input.muted.isEmpty() && input.gains.isEmpty() && input.active.isEmpty())
    }

    // ---- lifecycle

    @Test
    fun startBuildsTheEngineFromTheStoredSettingsAndStartsItMutedOrNot() {
        prefs.micGain = 1.5f
        prefs.wakeThreshold = 0.6f
        prefs.wakeModel = "alexa.tflite"
        prefs.micMuted = true
        started()
        val (cfg, model, _) = created!!
        assertEquals(1.5f, cfg.micGain)
        assertEquals(0.6f, cfg.threshold)
        assertEquals("alexa.tflite", model)
        assertEquals(listOf(true), input.starts)
        assertNotNull(input.controller)
    }

    @Test
    fun thePickedWakeModelIsStored() {
        started()
        created!!.third("hey_jarvis.tflite")
        assertEquals("hey_jarvis.tflite", prefs.wakeModel)
    }

    @Test
    fun stopStopsTheEngine() {
        started().stop()
        assertEquals(1, input.stops)
    }

    @Test
    fun localEngineErrorsShowAsLocalVoiceErrors() {
        started()
        input.onError!!("no microphone")
        assertEquals(VoiceEvent.Error("no microphone", local = true), heard.single())
        assertEquals(PanelEvent.SpeakerClaimed(false), events.single())
    }

    @Test
    fun withVoiceOffTheEntitiesStayAndNothingRuns() {
        val p = started(voiceEnabled = false)
        assertNull(created)
        assertEquals(
            listOf("mute_microphone", "wake_sound", "voice_overlay", "mic_gain", "wake_word_threshold"),
            p.entities().map { it.objectId },
        )
        prefs.micMuted = true
        p.onEvent(PanelEvent.SettingChanged("mic_muted"))
        p.cancel()
        val server = ApiServer(0, { error("unused") }, emptyList(), null as ByteArray?)
        p.bind(server)
        assertNull(server.voice)
    }

    @Test
    fun theVoiceEntitiesWriteTheSettings() {
        val e = pane().entities().associateBy { it.objectId }
        e.getValue("mute_microphone").command(ProtoMessage(ProtoWriter().bool(2, true).toByteArray()))
        assertTrue(prefs.micMuted)
        e.getValue("wake_sound").command(ProtoMessage(ProtoWriter().bool(2, false).toByteArray()))
        assertFalse(prefs.wakeSound)
        e.getValue("mic_gain").command(ProtoMessage(ProtoWriter().float(2, 3f).toByteArray()))
        assertEquals(3f, prefs.micGain)
        e.getValue("wake_word_threshold").command(ProtoMessage(ProtoWriter().float(2, 0.8f).toByteArray()))
        assertEquals(0.8f, prefs.wakeThreshold)
    }

    @Test
    fun bindHandsHomeAssistantTheController() {
        val p = started()
        val server = ApiServer(0, { error("unused") }, emptyList(), null as ByteArray?)
        p.bind(server)
        assertSame(input.controller, server.voice)
    }

    // ---- timers

    @Test
    fun timerEventsFromHomeAssistantBecomePanelEvents() {
        started()
        val c = input.controller!!
        c.onTimer(TimerEventType.STARTED, "t1", "Pasta", 600, 600, true)
        c.onTimer(TimerEventType.FINISHED, "t1", "Pasta", 600, 0, false)
        assertEquals(
            listOf<PanelEvent>(
                PanelEvent.TimersChanged(listOf(PanelTimer("t1", "Pasta", 600, 600, true))),
                PanelEvent.TimerFinished("Pasta"),
                PanelEvent.TimersChanged(emptyList()),
            ),
            events,
        )
    }

    private class FakeSpeaker : SpeakerMedia {
        override var playing = false
        override var announcing = false
        var pauses = 0
        var resumes = 0
        override fun pause() { pauses++; playing = false }
        override fun resume() { resumes++; playing = true }
    }

    private class FakeInput : VoiceInput {
        override var controller: VoiceAssistantController? = null
        override var threshold = 0.5f
        override var onError: ((String) -> Unit)? = null
        val starts = ArrayList<Boolean>()
        var stops = 0
        val muted = ArrayList<Boolean>()
        val gains = ArrayList<Float>()
        val active = ArrayList<List<String>>()
        override fun start(muted: Boolean) { starts += muted }
        override fun stop() { stops++ }
        override fun setMuted(m: Boolean) { muted += m }
        override fun setGain(g: Float) { gains += g }
        override fun available() = emptyList<WakeWordInfo>()
        override fun activeIds() = emptyList<String>()
        override fun setActive(ids: List<String>) { active += ids }
    }
}
