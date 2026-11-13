package sh.edm.s1kiosk.setup

import android.app.Application
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
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.radioDefinition
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

    private val flow = SetupFlow.ids(true)

    private fun model(
        step: String, wifi: WifiSetupStatus.Snapshot = online, haUrl: String? = ha,
        improv: ImprovFacts = ImprovFacts(), installer: InstallerWifi? = null, preview: Boolean = false,
    ) = SetupOverlay.Model(
        step = step, done = flow.takeWhile { it != step }.toSet(), wifi = wifi, haUrl = haUrl, haSource = "mdns", panelIp = ip,
        signedIn = false, improv = WifiStep.improv(improv), installer = installer, preview = preview,
    )

    /** The app's cards: voice and music on, the radio in [radio]. */
    private fun choices(a: android.content.Context, radio: RadioMode): List<PaneChoice> = listOf(
        PaneChoice("Voice", "Assist and timers", on = { true }, apply = { _, _ -> null }),
        PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { _, _ -> null }),
        radioDefinition(a, FixedReadout(null, mode = radio, haBase = ha, panelIp = ip)).setupChoice,
    )

    private fun shot(name: String, m: SetupOverlay.Model, radio: RadioMode = RadioMode.ZIGBEE_TCP, then: (SetupOverlay) -> Unit = {}) {
        val a = Mockup.activity()
        val s = SetupOverlay(a, choices(a, radio))
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

        shot("setup-01-wifi-allow", model(
            SetupFlow.WIFI, wifi = offline,
            improv = ImprovFacts(running = true, advertising = true, clients = 1, state = ImprovState.AUTHORIZATION_REQUIRED),
        ))
        shot("setup-02-wifi-bluetooth-off", model(SetupFlow.WIFI, wifi = offline))
        shot("setup-03-wifi-from-computer", model(
            SetupFlow.WIFI, wifi = offline, installer = InstallerWifi("Example-WiFi", InstallerWifi.Phase.CONNECTING),
        ))
        shot("setup-04-ha-add", model(SetupFlow.HA))
        shot("setup-05-ha-looking", model(SetupFlow.HA, haUrl = null))
        shot("setup-06-panes", model(SetupFlow.PANES))
        shot("setup-07-panes-radio-off", model(SetupFlow.PANES), RadioMode.OFF)
        shot("setup-08-signin", model(SetupFlow.SIGNIN))
        shot("setup-09-preview", model(SetupFlow.WIFI, preview = true))
        shot("setup-10-done", model(SetupFlow.SIGNIN)) { it.finish(holdMs = 60_000) {} }
    }
}
