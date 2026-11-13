package sh.edm.s1kiosk.settings

import android.app.Application
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.radio.ui.RadioFollowUp
import sh.edm.s1kiosk.ui.Mockup

/**
 * Mock-ups of the settings menu, one per section (the Radio section's states are in RadioMockups),
 * rendered at the panel's 1440x720 px. Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*SettingsMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsMockups {

    @Before fun interFromSources() = Mockup.interFromSources()

    private val off = RadioFollowUp.Facts(
        RadioMode.OFF, "ok Off: Aqara radio services stopped, radio idle", HelperFacts(true, 4), ZigbeeStack.AQARA,
        null, null, "http://homeassistant.local:8123", "192.0.2.10",
    )

    private fun menu(radioRunning: Boolean, deviceSettings: Boolean = true): KioskSettingsOverlay {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        val app = RuntimeEnvironment.getApplication()
        KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel"; zoom = 1.25f }
        val radio = radioDefinition(app, FixedReadout(if (radioRunning) off else null, running = radioRunning))
        o.show(MenuFixture.model(app, radio, deviceSettings, status = "screen on · presence near · voice=idle"))
        Mockup.idle()
        return o
    }

    /** The menu on the section [id], after [then]. */
    private fun shot(name: String, o: KioskSettingsOverlay, id: String, then: (KioskSettingsOverlay) -> Unit = {}) {
        o.previewSection(id)
        then(o)
        Mockup.idle()
        Mockup.layout(o)
        Mockup.save(name, o)
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)

        shot("settings-01-dashboard", menu(radioRunning = true), "dashboard")
        shot("settings-02-radio", menu(radioRunning = true), "radio")
        shot("settings-03-screen", menu(radioRunning = true), "screen")
        shot("settings-04-about", menu(radioRunning = true), "about")
        shot("settings-05-radio-service-not-running", menu(radioRunning = false), "radio")
        shot("settings-06-screen-no-device-settings", menu(radioRunning = true, deviceSettings = false), "screen")
        shot("settings-07-edit-a-value", menu(radioRunning = true), "dashboard") { it.previewEdit("Home Assistant") }
        shot("settings-08-restart-needed", menu(radioRunning = true), "dashboard") { it.previewRestart() }
    }
}
