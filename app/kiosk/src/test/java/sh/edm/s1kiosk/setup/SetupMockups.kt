package sh.edm.s1kiosk.setup

import android.app.Application
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
import sh.edm.s1kiosk.radio.RadioSetupScreen
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.ui.Mockup

/**
 * Mock-ups of every step of the setup screen (the radio step's follow-up cards are in RadioMockups),
 * rendered at the panel's 1440x720 px. Illustrative values only. Skipped unless asked:
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

    private val flow = SetupFlow.ids(listOf("radio"))

    /** The radio step's state: the mode in effect, and the screen's last "Apply". */
    private data class Radio(val current: RadioMode = RadioMode.OFF, val applied: RadioStep.Applied? = null, val busy: Boolean = false)

    private fun model(
        step: String, wifi: WifiSetupStatus.Snapshot = online, haUrl: String? = ha,
        improv: ImprovFacts = ImprovFacts(), installer: InstallerWifi? = null, preview: Boolean = false,
    ) = SetupOverlay.Model(
        step = step, done = flow.takeWhile { it != step }.toSet(), wifi = wifi, haUrl = haUrl, haSource = "mdns", panelIp = ip,
        signedIn = false, improv = WifiStep.improv(improv), installer = installer, preview = preview,
    )

    private fun overlay(radio: Radio = Radio()): SetupOverlay {
        val a = Mockup.activity()
        val def = radioDefinition(a, FixedReadout(null, mode = radio.current, haBase = ha, panelIp = ip))
        val s = SetupOverlay(a, def.setupSteps)
        a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
        (s.previewScreen("radio") as RadioSetupScreen).previewApplied(radio.applied, radio.busy)
        return s
    }

    private fun shot(name: String, m: SetupOverlay.Model, radio: Radio = Radio()) {
        val s = overlay(radio)
        s.render(m)
        Mockup.idle()
        Mockup.save(name, s)
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)

        shot("setup-01-wifi-bluetooth-off", model(SetupFlow.WIFI, wifi = offline))
        shot("setup-02-wifi-allow", model(
            SetupFlow.WIFI, wifi = offline,
            improv = ImprovFacts(running = true, advertising = true, clients = 1, state = ImprovState.AUTHORIZATION_REQUIRED),
        ))
        shot("setup-03-wifi-from-computer", model(
            SetupFlow.WIFI, wifi = offline, installer = InstallerWifi("Example-WiFi", InstallerWifi.Phase.CONNECTING),
        ))
        shot("setup-04-ha-looking", model(SetupFlow.HA, haUrl = null))
        shot("setup-05-ha-add", model(SetupFlow.HA))
        shot("setup-06-radio-choose", model("radio"))
        shot("setup-07-radio-applying", model("radio"), Radio(RadioMode.OFF, busy = true))
        shot("setup-08-radio-off-applied", model("radio"),
            Radio(RadioMode.OFF, RadioStep.Applied(RadioMode.OFF, true, "Off: Aqara's radio services stopped, radio idle")))
        shot("setup-09-radio-refused", model("radio"), Radio(
            RadioMode.ZIGBEE_TCP,
            RadioStep.Applied(RadioMode.ZIGBEE_TCP, false, "Zigbee coordinator (TCP) needs the root helper (init service s1kiosk_radio is not installed)."),
        ))
        shot("setup-10-signin", model(SetupFlow.SIGNIN))
        shot("setup-11-preview", model(SetupFlow.WIFI, preview = true))
        val s = overlay()
        s.render(model(SetupFlow.SIGNIN))
        s.finish(holdMs = 60_000) {}
        Mockup.idle()
        Mockup.save("setup-12-done", s)
    }
}
