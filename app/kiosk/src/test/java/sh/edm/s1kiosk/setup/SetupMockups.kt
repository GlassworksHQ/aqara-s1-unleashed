package sh.edm.s1kiosk.setup

import android.app.Application
import android.content.Context
import dev.glassworks.pane.PaneChoice
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.improv.ImprovState
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.RadioDefinition
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ThreadRouterState
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.radio.ui.RadioFollowUp
import sh.edm.s1kiosk.ui.Mockup

/**
 * Mock-ups of every step of the setup screen and of the connecting screen (#63), rendered at the
 * panel's 1440x720 px. Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*SetupMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupMockups {

    @Before fun interFromSources() = Mockup.interFromSources()

    private val ha = "http://homeassistant.local:8123"
    private val ip = "192.0.2.10"
    private val offline = WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)
    private val online = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi")

    private val flow = SetupFlow.ids(true, listOf("radio"))

    private fun model(
        step: String, wifi: WifiSetupStatus.Snapshot = online, haUrl: String? = ha,
        improv: ImprovFacts = ImprovFacts(), installer: InstallerWifi? = null, preview: Boolean = false,
        done: Set<String> = flow.takeWhile { it != step }.toSet(),
    ) = SetupOverlay.Model(
        step = step, done = done, wifi = wifi, haUrl = haUrl, haSource = "mdns", panelIp = ip,
        signedIn = false, improv = WifiStep.improv(improv), installer = installer, preview = preview,
    )

    private fun facts(mode: RadioMode, status: String, thread: ThreadRouterState? = null) =
        RadioFollowUp.Facts(mode, status, HelperFacts(true, 4), ZigbeeStack.AQARA, if (thread != null) false else null, thread, ha, ip)

    /** The app's panes: voice, music and the wall base on, the radio as [radio] (its follow-up open when [followUp]). */
    private fun panes(a: Context, radio: RadioFollowUp.Facts?, followUp: Boolean = false): List<SetupPane> {
        val def: RadioDefinition = radioDefinition(a, FixedReadout(radio, mode = radio?.mode ?: RadioMode.OFF, haBase = ha, panelIp = ip))
        def.followUpOpen = followUp
        return listOf(
            SetupPane(PaneChoice("Voice", "Assist and timers", on = { true }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { null }), emptyList()),
            SetupPane(def.setupChoice, def.setupSteps),
            SetupPane(PaneChoice("Bluetooth proxy", "For BLE sensors", on = { false }, apply = { null }), emptyList()),
            SetupPane(PaneChoice("Wall base", "The S1's wired base", on = { true }, apply = { null }), emptyList()),
        )
    }

    private fun shot(name: String, m: SetupOverlay.Model, radio: RadioFollowUp.Facts? = null, followUp: Boolean = false, then: (SetupOverlay) -> Unit = {}) {
        val a = Mockup.activity()
        val s = SetupOverlay(a, panes(a, radio, followUp))
        a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
        s.render(m)
        then(s)
        Mockup.idle()
        Mockup.save(name, s)
    }

    private fun connecting(name: String, state: LoadingOverlay.State, phase: Float? = null) {
        val a = Mockup.activity()
        val o = LoadingOverlay(a)
        a.setContentView(o, android.view.ViewGroup.LayoutParams(-1, -1))
        o.show(state)
        phase?.let(o::previewPhase)
        Mockup.idle()
        Mockup.save(name, o)
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)

        connecting("connecting-01", LoadingOverlay.State.Connecting(LoadingOverlay.CONNECTING, "homeassistant.local"), phase = 0.62f)
        connecting("connecting-02-wifi-off", LoadingOverlay.State.Offline(OfflineText.WIFI_OFF_TITLE, OfflineText.SETUP_HINT, OfflineText.SETUP_ACTION))
        connecting("connecting-03-unreachable", LoadingOverlay.State.Unreachable("homeassistant.local", retryAt = android.os.SystemClock.elapsedRealtime() + 4_000))
        connecting("connecting-04-joining-wifi", LoadingOverlay.State.Connecting(OfflineText.CONNECTING_TITLE, OfflineText.CONNECTING_DETAIL, host = false), phase = 0.3f)

        val coordinator = facts(RadioMode.ZIGBEE_TCP, "ok Zigbee coordinator (TCP): listening on :6638 for Home Assistant (192.0.2.20). ZHA: radio type EZSP, serial path socket://$ip:6638")
        val threadRouter = facts(RadioMode.THREAD_ROUTER, "ok Thread router (repeater): no dataset yet", thread = ThreadRouterState(false, null, null))

        shot("setup-01-wifi-allow", model(
            SetupFlow.WIFI, wifi = offline,
            improv = ImprovFacts(running = true, advertising = true, clients = 1, state = ImprovState.AUTHORIZATION_REQUIRED),
        ))
        shot("setup-02-wifi-connected", model(SetupFlow.WIFI, done = setOf(SetupFlow.WIFI), preview = true))
        shot("setup-03-ha-add", model(SetupFlow.HA))
        shot("setup-04-ha-added", model(SetupFlow.HA, done = setOf(SetupFlow.WIFI, SetupFlow.HA), preview = true))
        shot("setup-05-ha-looking", model(SetupFlow.HA, haUrl = null))
        shot("setup-06-signin", model(SetupFlow.SIGNIN))
        shot("setup-07-signed-in", model(SetupFlow.SIGNIN, done = setOf(SetupFlow.WIFI, SetupFlow.HA, SetupFlow.SIGNIN), preview = true))
        shot("setup-08-panes", model(SetupFlow.PANES))
        shot("setup-08b-panes-scrolled", model(SetupFlow.PANES)) { s ->
            // The list scrolls above Continue once the cards don't fit (#121): its end, once the
            // pending layout passes have run at the panel's size (scrollTo clamps to the end).
            Mockup.idle()
            Mockup.layout(s)
            generateSequence(Mockup.label(s, "Voice") as android.view.View) { it.parent as? android.view.View }
                .firstOrNull { it is android.widget.ScrollView }?.scrollTo(0, 100_000)
        }
        shot("setup-09-radio-mode", model("radio"), radio = coordinator) // turned on in Panes, the mode offered
        shot("setup-10-radio-coordinator-next", model("radio"), radio = coordinator, followUp = true)
        shot("setup-11-radio-thread-router-next", model("radio"), radio = threadRouter, followUp = true)
        shot("setup-12-preview", model(SetupFlow.WIFI, preview = true))
        shot("setup-13-done", model(SetupFlow.PANES)) { it.finish(holdMs = 60_000) {} }
        shot("setup-14-done-preview", model(SetupFlow.PANES, preview = true)) { it.finish {} }
    }
}
