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
    private val prefs = KioskPrefs(app).voice
    private val events = ArrayList<PanelEvent>()
    private val heard = ArrayList<VoiceEvent>()
    private var chimes = 0
    private val speaker = FakeSpeaker()
    private val input = FakeInput()
    private var created: Triple<VoiceConfig, String, (String) -> Unit>? = null

    private fun pane(onVoiceEvent: (VoiceEvent) -> Unit = { heard += it }) = AssistPane(
        ctx = PaneContext(
            app, KioskPrefs(app)::paneFile,
            DeviceIdentity(
                name = "test", friendlyName = "Test", macAddress = "02:00:00:00:00:01", esphomeVersion = "x",
                compilationTime = "x", manufacturer = "x", model = "x", voiceFeatures = 0,
            ),
            publishStates = {}, emit = { events += it }, entitiesChanged = {}, sendEvent = { _, _ -> },
        ),
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
    private fun started() = pane().also { panes += it; it.start() }

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
        prefs.wakeCutoff = 0.8f
        p.onEvent(PanelEvent.SettingChanged("wake_cutoff"))
        assertEquals(0.8f, input.threshold)
        prefs.wakeWord = "alexa"
        p.onEvent(PanelEvent.SettingChanged("wake_word"))
        assertEquals(listOf(true), input.muted)
        assertEquals(listOf(2.5f), input.gains)
        assertEquals(listOf(listOf("alexa")), input.active)
        assertEquals("a new word starts at its own cutoff", 0.9f, input.threshold)
    }

    @Test
    fun aLegacyWordUsesOpenWakeWordsThreshold() {
        val p = started()
        prefs.wakeThreshold = 0.6f
        prefs.wakeWord = "hey_jarvis_v0.1"
        p.onEvent(PanelEvent.SettingChanged("wake_word"))
        assertEquals(0.6f, input.threshold)
        prefs.wakeThreshold = 0.7f
        p.onEvent(PanelEvent.SettingChanged("wake_threshold"))
        assertEquals(0.7f, input.threshold)
    }

    @Test
    fun clearingTheWakeWordDeactivatesEveryWakeWord() {
        val p = started()
        prefs.wakeWord = ""
        p.onEvent(PanelEvent.SettingChanged("wake_word"))
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
        prefs.wakeWord = "alexa"
        prefs.wakeCutoff = 0.85f
        prefs.micMuted = true
        started()
        val (cfg, word, _) = created!!
        assertEquals(1.5f, cfg.micGain)
        assertEquals(0.85f, cfg.threshold)
        assertEquals("alexa", word)
        assertEquals(listOf(true), input.starts)
        assertNotNull(input.controller)
    }

    @Test
    fun thePickedWakeWordIsStored() {
        started()
        created!!.third("okay_nabu")
        assertEquals("okay_nabu", prefs.wakeWord)
    }

    // ---- update from openWakeWord (0.4)

    private fun savedBy04(model: String?, threshold: Float?) {
        val sp = app.getSharedPreferences("kiosk", 0).edit()
        if (model != null) sp.putString("wake_model", model)
        if (threshold != null) sp.putFloat("wake_threshold", threshold)
        sp.apply()
    }

    @Test
    fun theWordSavedBy04MovesToItsMicroWakeWordModelAtItsOwnCutoff() {
        for ((saved, id, cutoff) in listOf(
            Triple("hey_jarvis_v0.1.tflite", "hey_jarvis", 0.97f),
            Triple("alexa_v0.1.tflite", "alexa", 0.9f),
            Triple("hey_mycroft_v0.1.tflite", "hey_mycroft", 0.95f),
        )) {
            app.getSharedPreferences("kiosk", 0).edit().clear().commit()
            savedBy04(saved, 0.6f)
            created = null
            started()
            assertEquals(saved, id, created!!.second)
            assertEquals("the 0.4 threshold was on openWakeWord's scale", cutoff, created!!.first.threshold)
            assertEquals(saved, saved, app.getSharedPreferences("kiosk", 0).getString("wake_model", null))
        }
    }

    @Test
    fun aNewPanelListensForHeyJarvis() {
        started()
        assertEquals("hey_jarvis", created!!.second)
        assertEquals(0.97f, created!!.first.threshold)
    }

    @Test
    fun aCustomOpenWakeWordModelSavedBy04StaysOnOpenWakeWord() {
        savedBy04("my_word_v2.tflite", 0.6f)
        started()
        assertEquals("my_word_v2", created!!.second)
        assertEquals(0.6f, created!!.first.threshold)
    }

    @Test
    fun noWakeWordSavedBy04StaysOff() {
        savedBy04("", null)
        started()
        assertEquals("", created!!.second)
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

    /**
     * #210 review: voice turned off in the middle of a run ends the run. Its Idle resumes the music it
     * paused and lets go of the speaker; nothing else would, with the watchdog gone.
     */
    @Test
    fun turnedOffMidRunItResumesTheMusicAndLetsGoOfTheSpeaker() {
        val p = started()
        val server = ApiServer(0, { identity }, emptyList(), null as sh.edm.esphome.NoiseKeys?, java.net.InetAddress.getLoopbackAddress())
        p.bind(server)
        server.start()
        try {
            java.net.Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                // As Home Assistant: hello, then subscribe to the voice assistant.
                val out = s.getOutputStream()
                out.write(frame(sh.edm.esphome.Msg.HELLO_REQUEST, ProtoWriter().string(1, "Home Assistant 2026.11.0").uint32(2, 1).uint32(3, 10).toByteArray()))
                out.write(frame(sh.edm.esphome.Msg.SUBSCRIBE_VOICE_ASSISTANT_REQUEST, ProtoWriter().bool(1, true).toByteArray()))
                out.flush()
                val c = input.controller!!
                val end = System.currentTimeMillis() + 5_000
                while (!c.isSubscribed && System.currentTimeMillis() < end) Thread.sleep(10)
                assertTrue("Home Assistant subscribed", c.isSubscribed)
                speaker.playing = true
                assertTrue("a run started", c.onWakeWord("hey jarvis"))
                assertEquals(1, speaker.pauses)
                assertEquals(PanelEvent.SpeakerClaimed(true), events.last { it is PanelEvent.SpeakerClaimed })

                p.stop() // voice turned off while it listens

                assertEquals("the music plays again", 1, speaker.resumes)
                assertEquals(PanelEvent.SpeakerClaimed(false), events.last { it is PanelEvent.SpeakerClaimed })
            }
        } finally {
            server.stop()
        }
    }

    private val identity = DeviceIdentity(
        name = "test", friendlyName = "Test", macAddress = "02:00:00:00:00:01", esphomeVersion = "x",
        compilationTime = "x", manufacturer = "x", model = "x", voiceFeatures = 0,
    )

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun varint(v: Int) { var x = v; while (x >= 0x80) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }; o.write(x) }
        o.write(0); varint(payload.size); varint(type); o.write(payload)
        return o.toByteArray()
    }

    @Test
    fun turnedOffAndOnAgainItTakesTheServerAndClearsTheTimers() {
        val p = started()
        val server = ApiServer(0, { error("unused") }, emptyList(), null as ByteArray?)
        p.bind(server)
        val first = server.voice
        assertNotNull(first)
        p.stop() // voice turned off while the server runs
        assertNull(server.voice)
        assertEquals("the board stops showing timers nobody reports", PanelEvent.TimersChanged(emptyList()), events.last())
        created = null
        events.clear()
        p.start() // and on again: no bind() this time
        assertNotNull("a new engine", created)
        assertNotNull(server.voice)
        assertTrue("a new controller", server.voice !== first)
        p.stop()
        p.stop() // the panel stopping too: nothing more to clear
        assertEquals(listOf(PanelEvent.TimersChanged(emptyList())), events)
    }

    @Test
    fun neverStartedTheEntitiesStayAndNothingRuns() {
        val p = pane().also { panes += it }
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
        assertEquals(0.8f, prefs.threshold)
        assertEquals("a microWakeWord word's cutoff, not openWakeWord's threshold", 0.5f, prefs.wakeThreshold)
    }

    @Test
    fun bindHandsHomeAssistantTheController() {
        val p = started()
        val server = ApiServer(0, { error("unused") }, emptyList(), null as ByteArray?)
        p.bind(server)
        assertSame(input.controller, server.voice)
    }

    @Test
    fun stopTakesTheControllerBackFromTheServer() {
        val p = started()
        val server = ApiServer(0, { error("unused") }, emptyList(), null as ByteArray?)
        p.bind(server)
        p.stop()
        assertNull(server.voice)
    }

    @Test
    fun stopLeavesAVoiceHandlerItDidNotSet() {
        val p = started()
        val server = ApiServer(0, { error("unused") }, emptyList(), null as ByteArray?)
        p.bind(server)
        val other = started()
        other.bind(server)
        p.stop()
        assertSame("the handler bound later stays", input.controller, server.voice)
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
