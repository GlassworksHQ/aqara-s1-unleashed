package sh.edm.s1kiosk.camera

import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import dev.glassworks.camera.CameraDefinition
import dev.glassworks.camera.CameraFacts
import dev.glassworks.camera.CameraPane
import dev.glassworks.camera.CameraPrefs
import dev.glassworks.camera.CameraPresence
import dev.glassworks.camera.CameraUse
import dev.glassworks.camera.CameraUsePickers
import dev.glassworks.camera.FrameEgress
import dev.glassworks.camera.FrameSink
import dev.glassworks.camera.FrameSource
import dev.glassworks.camera.LumaFrame
import dev.glassworks.camera.StreamAddress
import dev.glassworks.camera.StreamOutput
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.PaneContext
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.esphome.DeviceIdentity
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.radio.ui.RadioFollowUp
import sh.edm.s1kiosk.settings.KioskSettingsOverlay
import sh.edm.s1kiosk.settings.MenuFixture
import sh.edm.s1kiosk.setup.ImprovFacts
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupOverlay
import sh.edm.s1kiosk.setup.SetupPane
import sh.edm.s1kiosk.setup.WifiSetupStatus
import sh.edm.s1kiosk.setup.WifiStep
import sh.edm.s1kiosk.ui.Mockup
import java.util.concurrent.Executors

/**
 * Mock-ups of the camera pane's screens (#117) on a panel with a camera, at the panel's 1440x720 px:
 * its page in each state, the "● Camera" pill on a dashboard, and setup's Panes and Camera steps. The
 * page reads a real [CameraPane] driven with a fake camera and stream. Illustrative values only: RFC
 * 5737 addresses, no images of anyone. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*CameraMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraMockups {

    @Before fun interFromSources() = Mockup.interFromSources()

    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val coordinator = RadioFollowUp.Facts(
        RadioMode.ZIGBEE_TCP, "ok Zigbee coordinator (TCP): listening on :6638 for Home Assistant (192.0.2.20)",
        HelperFacts(true, 4), ZigbeeStack.AQARA, null, null, "http://homeassistant.local:8123", "192.0.2.10",
    )

    @After fun tearDown() {
        exec.shutdownNow()
        KioskPrefs(app).paneFile("camera").edit().clear().commit()
    }

    private class Camera : FrameSource {
        var sink: FrameSink? = null
        override fun open(fps: Float, sink: FrameSink): AutoCloseable { this.sink = sink; return AutoCloseable { this.sink = null } }
    }

    private class Stream(val watching: List<String>) : StreamOutput {
        var on = false
        override fun start(signIn: Pair<String, String>): Boolean { on = true; return true }
        override fun stop() { on = false }
        override fun setMotion(active: Boolean) {}
        override fun address() = StreamAddress("192.0.2.41", 8554, 8000)
        override fun watchers() = if (on) watching else emptyList()
    }

    /** A camera device's pane in one state, set up as [setUp] says, then started as the panel would. */
    private inner class Rig(allowed: Boolean = true, watching: List<String> = emptyList(), setUp: (CameraPrefs) -> Unit = {}) {
        val file = KioskPrefs(app).paneFile("camera").also { it.edit().clear().commit() }
        val def = CameraDefinition(file, hasCamera = true, granted = { allowed }, pickers = CameraUsePickers { CameraUseCards(it) }, time = { "14:31" })
        val camera = Camera()
        var now = 0L
        val egress = FrameEgress({ now }, { _, _ -> }, wall = { 0L }) {}
        val stream = Stream(watching)
        val pane: CameraPane

        init {
            def.switch.on = true
            setUp(def.prefs)
            pane = CameraPane(
                PaneContext(
                    app, prefs = { file }, DeviceIdentity("panel", "Hallway panel", "02:00:00:00:00:01", "x", "x", "x", "x", 0),
                    publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
                ),
                def.prefs, camera, { byteArrayOf(1) }, stream, egress,
                permitted = { allowed }, ownSensor = { false }, toScreen = { _, _ -> }, exec = exec,
                // The front camera: presence asks 5 frames a second, the stream 15 (CameraHub on a device).
                facts = object : CameraFacts {
                    override val lens = CameraFacts.Lens.FRONT
                    override fun frameRate() = if (stream.on) 15 else if (camera.sink != null) 5 else 0
                },
                presenceFactory = { source, onMotion, onProblem ->
                    CameraPresence(source, { def.prefs.sensitivity }, clock = { now }, post = { it.run() }, after = { _, _ -> }, onProblem = onProblem, onMotion = onMotion)
                },
            )
            pane.start()
        }

        /** Someone walks up: a seed frame, then four with a person-sized change. */
        fun someoneWalksUp() = apply {
            repeat(5) { i ->
                now += 200
                camera.sink?.frame(LumaFrame(96, 54, 96, ByteArray(96 * 54) { p -> if (i > 0 && p % 96 in (i * 8) until (i * 8 + 16)) 120 else 60 }))
            }
        }

        fun stillLeft() = apply { egress.still() }
    }

    private fun menu(r: Rig?, def: CameraDefinition = r!!.def): KioskSettingsOverlay {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel"; zoom = 1.25f }
        val radio = radioDefinition(app, FixedReadout(coordinator, running = true))
        val panes = listOf(
            MenuFixture.cardOnly("voice", "Voice", "Assist and timers"),
            MenuFixture.cardOnly("sendspin", "Music", "A Sendspin speaker"), radio,
        ) + MenuFixture.switchOnly(app) + def
        o.show(MenuFixture.model(app, radio, status = "screen on · presence near", panes = panes, running = { id -> if (id == "camera") r?.pane else null }))
        Mockup.idle()
        return o
    }

    private fun shot(name: String, o: KioskSettingsOverlay, scrollTo: String? = null, bottom: Boolean = true) {
        o.previewSection("camera")
        Mockup.idle()
        Mockup.layout(o)
        scrollTo?.let { o.previewScrollTo(Mockup.label(o, it), alignBottom = bottom); Mockup.idle() }
        Mockup.save(name, o)
    }

    /** A stand-in for a dashboard, as the timer mock-ups draw it, with the pill as MainActivity places it. */
    private fun dashboard(name: String) {
        val a = Mockup.activity()
        val root = FrameLayout(a)
        root.addView(FakeDashboard(a), FrameLayout.LayoutParams(-1, -1))
        val pill = CameraPill(a).apply { show(true) }
        root.addView(pill, pill.layoutParams())
        a.setContentView(root, android.view.ViewGroup.LayoutParams(-1, -1))
        Mockup.idle()
        Mockup.save(name, root)
    }

    private class FakeDashboard(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            c.drawColor(Color.rgb(17, 18, 22))
            fun card(l: Float, t: Float, r: Float, b: Float) { p.color = Color.rgb(27, 29, 34); c.drawRoundRect(l * d, t * d, r * d, b * d, 14 * d, 14 * d, p) }
            card(12f, 12f, 298f, 230f); card(12f, 242f, 298f, 468f)
            card(310f, 12f, 610f, 200f); card(310f, 212f, 610f, 468f)
            card(622f, 48f, 948f, 200f); card(622f, 212f, 948f, 468f)
            p.color = Color.rgb(150, 152, 160); p.textSize = 15 * d
            for ((i, y) in listOf(60f, 110f, 160f, 290f, 340f, 390f).withIndex()) c.drawText("Dashboard text ${i + 1}", 30 * d, y * d, p)
        }
    }

    /** Setup on [step], with the camera's card and step from the real definition. [then] acts on the screen. */
    private fun setupShot(name: String, step: String, allowed: Boolean = false, then: (SetupOverlay) -> Unit = {}) {
        val a = Mockup.activity()
        val file = KioskPrefs(app).paneFile("camera").also { it.edit().clear().commit() }
        val def = CameraDefinition(file, hasCamera = true, granted = { allowed }, pickers = CameraUsePickers { CameraUseCards(it) })
        def.switch.on = true
        val panes = listOf(
            SetupPane(PaneChoice("Voice", "Assist and timers", on = { true }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Bluetooth proxy", "For BLE sensors", on = { false }, apply = { null }), emptyList()),
            SetupPane(def.setupChoice!!, def.setupSteps),
        )
        val s = SetupOverlay(a, panes)
        a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
        val flow = SetupFlow.ids(true, listOf("camera"))
        s.render(SetupOverlay.Model(
            step = step, done = flow.takeWhile { it != step }.toSet(),
            wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"), haUrl = "http://homeassistant.local:8123",
            haSource = "mdns", panelIp = "192.0.2.41", signedIn = false, improv = WifiStep.improv(ImprovFacts()), installer = null, preview = false,
        ))
        Mockup.idle()
        then(s)
        Mockup.idle()
        Mockup.save(name, s)
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)

        shot("camera-01-presence-only", menu(Rig().someoneWalksUp()))
        shot("camera-01b-presence-only-scrolled", menu(Rig().someoneWalksUp()), scrollTo = "Camera sensitivity")
        shot("camera-02-snapshots-sending", menu(Rig { it.use = CameraUse.PRESENCE_AND_SNAPSHOTS }.someoneWalksUp().stillLeft()))
        val streaming = Rig(watching = listOf("192.0.2.20", "192.0.2.25")) {
            it.use = CameraUse.PRESENCE_AND_SNAPSHOTS; it.onvif = true; it.signIn = "viewer" to "made-up-pass"
        }.someoneWalksUp()
        shot("camera-03-onvif-streaming", menu(streaming))
        shot("camera-03b-onvif-addresses", menu(streaming), scrollTo = "Sign-in", bottom = false)
        shot("camera-04-onvif-no-sign-in", menu(Rig { it.use = CameraUse.PRESENCE_AND_SNAPSHOTS; it.onvif = true }.someoneWalksUp()), scrollTo = "Sign-in", bottom = false)
        shot("camera-05-privacy-on", menu(Rig { it.privacy = true }))
        shot("camera-05b-privacy-stream-refused", menu(Rig { it.use = CameraUse.PRESENCE_AND_SNAPSHOTS; it.privacy = true }), scrollTo = "Privacy", bottom = false)
        shot("camera-06-not-allowed", menu(Rig(allowed = false)))
        val off = Rig()
        off.pane.stop()
        off.def.switch.on = false
        shot("camera-07-off", menu(off))
        dashboard("camera-08-pill")
        setupShot("camera-09-setup-panes", SetupFlow.PANES)
        setupShot("camera-10-setup-camera-asks", "camera")
        setupShot("camera-11-setup-camera-refused", "camera") { s -> Mockup.label(s, "Allow the camera").let { if (!it.performClick()) (it.parent as View).performClick() } }
        setupShot("camera-12-setup-camera-allowed", "camera", allowed = true)
    }
}
