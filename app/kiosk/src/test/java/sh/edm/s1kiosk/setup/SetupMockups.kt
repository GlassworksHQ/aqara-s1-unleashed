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
import sh.edm.s1kiosk.radio.RadioMode
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

    private fun model(
        step: SetupStep, wifi: WifiSetupStatus.Snapshot = online, haUrl: String? = ha,
        improv: ImprovFacts = ImprovFacts(), installer: InstallerWifi? = null,
        radio: SetupOverlay.Radio = SetupOverlay.Radio(), preview: Boolean = false,
    ) = SetupOverlay.Model(
        step = step, wifiDone = step > SetupStep.WIFI, haDone = step > SetupStep.HA, signinDone = step > SetupStep.SIGNIN,
        radioDone = step > SetupStep.RADIO, wifi = wifi, haUrl = haUrl, haSource = "mdns", panelIp = ip, signedIn = false,
        improv = WifiStep.improv(improv), installer = installer, radio = radio, preview = preview,
    )

    private fun shot(name: String, m: SetupOverlay.Model) {
        val s = Mockup.show(SetupOverlay(Mockup.activity()))
        s.render(m)
        Mockup.idle()
        Mockup.save(name, s)
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)

        shot("setup-01-wifi-bluetooth-off", model(SetupStep.WIFI, wifi = offline))
        shot("setup-02-wifi-allow", model(
            SetupStep.WIFI, wifi = offline,
            improv = ImprovFacts(running = true, advertising = true, clients = 1, state = ImprovState.AUTHORIZATION_REQUIRED),
        ))
        shot("setup-03-wifi-from-computer", model(
            SetupStep.WIFI, wifi = offline, installer = InstallerWifi("Example-WiFi", InstallerWifi.Phase.CONNECTING),
        ))
        shot("setup-04-ha-looking", model(SetupStep.HA, haUrl = null))
        shot("setup-05-ha-add", model(SetupStep.HA))
        shot("setup-06-radio-choose", model(SetupStep.RADIO))
        shot("setup-07-radio-applying", model(SetupStep.RADIO, radio = SetupOverlay.Radio(RadioMode.OFF, busy = true)))
        shot("setup-08-radio-off-applied", model(
            SetupStep.RADIO,
            radio = SetupOverlay.Radio(RadioMode.OFF, RadioStep.Applied(RadioMode.OFF, true, "Off: Aqara's radio services stopped, radio idle")),
        ))
        shot("setup-09-radio-refused", model(
            SetupStep.RADIO,
            radio = SetupOverlay.Radio(
                RadioMode.ZIGBEE_TCP,
                RadioStep.Applied(RadioMode.ZIGBEE_TCP, false, "Zigbee coordinator (TCP) needs the root helper (init service s1kiosk_radio is not installed)."),
            ),
        ))
        shot("setup-10-signin", model(SetupStep.SIGNIN))
        shot("setup-11-preview", model(SetupStep.WIFI, preview = true))
        val s = Mockup.show(SetupOverlay(Mockup.activity()))
        s.render(model(SetupStep.SIGNIN))
        s.finish(holdMs = 60_000) {}
        Mockup.idle()
        Mockup.save("setup-12-done", s)
    }
}
