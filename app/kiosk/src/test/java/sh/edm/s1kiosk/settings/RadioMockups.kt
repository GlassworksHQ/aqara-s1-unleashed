package sh.edm.s1kiosk.settings

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assume.assumeTrue
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

    private fun followUpCard(o: KioskSettingsOverlay): View = o.previewFollowUp.parent as View

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", dir != null)

        shot("01-mode-picker", coordinator, bottom = true) { o -> o.previewPicker.apply { select(RadioMode.THREAD_ROUTER) } }
        shot("02-follow-up-off", off) { followUpCard(it) }
        shot("03-follow-up-coordinator-aqara", coordinator) { followUpCard(it) }
        shot("04-follow-up-coordinator-silicon-labs", coordinatorSilabs) { followUpCard(it) }
        shot("05-follow-up-zigbee-router", router) { followUpCard(it) }
        shot("06-follow-up-thread-border-router", border) { followUpCard(it) }
        shot("07-follow-up-thread-router-advanced-collapsed", threadRouter) { followUpCard(it) }
        shot("08-follow-up-thread-router-advanced-open", threadRouter, bottom = true) { o ->
            o.previewFollowUp.previewDatasetOpen(true)
            followUpCard(o)
        }

        // Confirmations (inline, under the buttons they belong to)
        shot("09-confirm-mode-switch", coordinator, bottom = true) { o ->
            o.previewPicker.apply {
                select(RadioMode.THREAD_ROUTER)
                flow.press(Request.Mode(RadioMode.THREAD_ROUTER), coordinator)
                select(RadioMode.THREAD_ROUTER) // rebinds with the question
            }
        }
        shot("10-confirm-leave-zigbee", router, bottom = true) { o ->
            o.previewFollowUp.apply { flow.press(Request.Act(Action.ROUTER_LEAVE), router); bind(router) }
            followUpCard(o)
        }
        shot("11-confirm-leave-thread", threadRouter, bottom = true) { o ->
            o.previewFollowUp.apply { flow.press(Request.Act(Action.THREAD_LEAVE), threadRouter); bind(threadRouter) }
            followUpCard(o)
        }
        shot("12-confirm-zigbee-stack", coordinator, bottom = true) { o ->
            o.previewFollowUp.apply { flow.press(Request.Stack(ZigbeeStack.SISDK), coordinator); bind(coordinator) }
            followUpCard(o)
        }

        // The same card in the setup screen's radio step
        // 13: what the step shows right after Apply (no dataset yet); 15-18: the other modes and the
        // longest Thread line, to check that nothing in the step card ever clips.
        val child = ThreadRouterState(true, "child", "Example-Net")
        for ((name, f) in listOf(
            "13-setup-thread-router" to threadRouter.copy(
                status = "ok Thread router (repeater): no dataset yet", thread = ThreadRouterState(false, null, null),
            ),
            "14-setup-thread-border-router" to border,
            "15-setup-coordinator" to coordinator,
            "16-setup-coordinator-silicon-labs" to coordinatorSilabs,
            "17-setup-zigbee-router" to router,
            "18-setup-thread-router-child" to threadRouter.copy(thread = child),
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
    }

    private companion object {
        const val W = 1440
        const val H = 720
    }
}
