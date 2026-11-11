package sh.edm.s1kiosk.settings

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ThreadRouterState
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.setup.RadioFollowUp
import sh.edm.s1kiosk.setup.RadioFollowUp.Action
import sh.edm.s1kiosk.setup.RadioFollowUp.Request
import sh.edm.s1kiosk.setup.RadioStep
import sh.edm.s1kiosk.setup.SetupOverlay
import sh.edm.s1kiosk.setup.SetupStep
import sh.edm.s1kiosk.setup.WifiSetupStatus
import sh.edm.s1kiosk.ui.Inter
import java.io.File
import java.time.Duration

/**
 * Mock-ups of the radio views: the real KioskSettingsOverlay / SetupOverlay rendered by Robolectric's
 * native graphics at the panel's 960x480 dp, density 1.5 (1440x720 px). Illustrative values only
 * (RFC 5737 addresses, homeassistant.local, a made-up network name). Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*RadioMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadioMockups {

    private val dir = System.getProperty("s1.mockupDir")

    /** Robolectric runs without the app's resources: read Inter from the source tree, as the app does from res/font. */
    @Before fun interFromSources() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
        Inter.load = { File(root, "app/kiosk/src/main/res/font/inter.ttf").readBytes() }
    }
    private val ha = "http://homeassistant.local:8123"
    private val ip = "192.0.2.10"

    private fun facts(
        mode: RadioMode, status: String, stack: ZigbeeStack = ZigbeeStack.AQARA, stored: Boolean? = null,
        thread: ThreadRouterState? = null,
    ) = RadioFollowUp.Facts(mode, status, HelperFacts(true, 4), stack, stored, thread, ha, ip)

    private val off = facts(RadioMode.OFF, "ok Off: Aqara radio services stopped, radio idle")
    private val coordinator = facts(
        RadioMode.ZIGBEE_TCP,
        "ok Zigbee coordinator (TCP): listening on :6638 for Home Assistant (192.0.2.20). ZHA: radio type EZSP, serial path socket://$ip:6638",
    )
    private val coordinatorSilabs = coordinator.copy(
        stack = ZigbeeStack.SISDK,
        status = "ok Zigbee coordinator (TCP): listening on :6638 for Home Assistant (192.0.2.20). Silicon Labs zigbeed 8.2.2 (EZSP 18).",
    )
    private val router = facts(RadioMode.ZIGBEE_ROUTER, "ok Zigbee router (repeater): in a network on channel 15")
    private val border = facts(
        RadioMode.THREAD,
        "ok Thread border router: border router on wpan0, backbone wlan0, REST :8081 (leader); REST open to Home Assistant (192.0.2.20)",
        stored = false, // a 0.4.3 helper: no Thread router network stored, so no Leave button
    )
    private val threadState = ThreadRouterState(true, "router", "Example-Net")
    private val threadRouter = facts(
        RadioMode.THREAD_ROUTER, "ok Thread router (repeater): ${threadState.describe()}", stored = true, thread = threadState,
    )

    private fun values() = KioskSettingsOverlay.Values(
        url = "", zoom = "", secureHosts = "", hideHeader = true, radioAllow = "", presence = true, idleOff = "120",
        nightIdleOff = "20", nightWindow = "22:00-07:00", presenceRange = "", autoBrightness = true,
    )

    private fun menu(f: RadioFollowUp.Facts, bottom: Boolean, prepare: (KioskSettingsOverlay) -> View): Pair<Activity, View> {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val o = KioskSettingsOverlay(a)
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        o.show(
            KioskSettingsOverlay.Model(
                values = values(), facts = SettingsMenu.Facts(deviceSettings = true, radio = true), radioRunning = true,
                allowedNow = SettingsMenu.allowedNow(true, if (f.mode == RadioMode.OFF) null else "192.0.2.20"), version = "0.4.3",
                status = "screen on · presence near", radio = f,
            ),
        )
        idle()
        val target = prepare(o)
        layout(o)
        o.previewScrollTo(target, alignBottom = bottom)
        return a to o
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    private fun layout(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
    }

    private fun save(name: String, v: View) {
        layout(v)
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        val out = File(dir!!).apply { mkdirs() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(name: String, f: RadioFollowUp.Facts, bottom: Boolean = false, prepare: (KioskSettingsOverlay) -> View) {
        val (_, o) = menu(f, bottom, prepare)
        save(name, o)
    }

    /** The Radio section's top (its label, the tiles). */
    private fun section(o: KioskSettingsOverlay): View = o.previewRadio.tiles.parent as View
    private fun panel(o: KioskSettingsOverlay): View = o.previewRadio.panel

    private val waitingThread = facts(
        RadioMode.THREAD_ROUTER, "ok Thread router (repeater): no dataset yet: waiting for the Thread network's active dataset",
        stored = true, thread = ThreadRouterState(false, null, null),
    )

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", dir != null)

        // The settings menu's Radio section (design B: role tiles + panel)
        shot("01-off", off) { section(it) }
        shot("02-choose-role", coordinator) { o -> o.previewRadio.previewSelect(RadioMode.THREAD_ROUTER); section(o) }
        shot("03-zigbee-coordinator-aqara", coordinator) { section(it) }
        shot("04-zigbee-coordinator-silicon-labs", coordinatorSilabs, bottom = true) { panel(it) }
        shot("05-zigbee-router-joined", router) { section(it) }
        shot("06-thread-border-router", border) { section(it) }
        shot("07-thread-router-waiting-for-dataset", waitingThread) { section(it) }
        shot("08-thread-router-advanced-open", waitingThread, bottom = true) { o -> o.previewRadio.previewOpen(dataset = true); panel(o) }
        shot("09-thread-router-joined", threadRouter) { section(it) }
        shot("10-how-does-this-work-open", coordinator, bottom = true) { o -> o.previewRadio.previewOpen(more = true); panel(o) }
        shot("11-confirm-switch", coordinator) { o ->
            o.previewDialog.show(RadioFollowUp.ask(Request.Mode(RadioMode.THREAD_ROUTER), coordinator)!!) {}
            section(o)
        }
        shot("12-confirm-leave-thread", threadRouter) { o ->
            o.previewDialog.show(RadioFollowUp.ask(Request.Act(Action.THREAD_LEAVE), threadRouter)!!) {}
            section(o)
        }
        shot("13-confirm-zigbee-stack", coordinator) { o ->
            o.previewDialog.show(RadioFollowUp.ask(Request.Stack(ZigbeeStack.SISDK), coordinator)!!) {}
            section(o)
        }
        shot("14-applying", facts(RadioMode.THREAD_ROUTER, "applying Thread router (repeater): waiting for otbr-agent")) { section(it) }
        shot("15-refused", coordinator) { o ->
            o.previewRadio.previewRefused(
                RadioMode.THREAD_ROUTER,
                "Thread router (repeater) needs the root helper v2 or newer (installed: v1; update the panel's boot image). Still in Zigbee coordinator (TCP).",
            )
            section(o)
        }
        shot("16-error", facts(RadioMode.THREAD, "error Thread border router: otbr-agent not installed (/data/s1kiosk/radio or bundle)", stored = false)) { section(it) }

        // 17: what the step shows right after Apply (no dataset yet); 19-22: the other modes and the
        // longest Thread line, to check that nothing in the step card ever clips.
        val child = ThreadRouterState(true, "child", "Example-Net")
        for ((name, f) in listOf(
            "17-setup-thread-router" to threadRouter.copy(
                status = "ok Thread router (repeater): no dataset yet", thread = ThreadRouterState(false, null, null),
            ),
            "18-setup-thread-border-router" to border,
            "19-setup-coordinator" to coordinator,
            "20-setup-coordinator-silicon-labs" to coordinatorSilabs,
            "21-setup-zigbee-router" to router,
            "22-setup-thread-router-child" to threadRouter.copy(thread = child),
        )) {
            val a = Robolectric.buildActivity(Activity::class.java).setup().get()
            val s = SetupOverlay(a)
            a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
            s.render(
                SetupOverlay.Model(
                    step = SetupStep.RADIO, wifiDone = true, haDone = true, signinDone = false, radioDone = false,
                    wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
                    haUrl = ha, haSource = "mdns", panelIp = ip, signedIn = false,
                    radio = SetupOverlay.Radio(
                        current = f.mode, applied = RadioStep.Applied(f.mode, true, f.status), facts = f,
                    ),
                ),
            )
            idle()
            save(name, s)
        }
        // The setup step's choice itself: the role icons in their technology's colour
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val s = SetupOverlay(a)
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(
            SetupOverlay.Model(
                step = SetupStep.RADIO, wifiDone = true, haDone = true, signinDone = false, radioDone = false,
                wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
                haUrl = ha, haSource = "mdns", panelIp = ip, signedIn = false,
                radio = SetupOverlay.Radio(current = RadioMode.OFF, facts = off),
            ),
        )
        idle()
        save("23-setup-choose-role", s)
    }

    private companion object {
        const val W = 1440
        const val H = 720
    }
}
