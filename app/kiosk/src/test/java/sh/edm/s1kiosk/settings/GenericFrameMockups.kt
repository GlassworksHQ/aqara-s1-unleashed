package sh.edm.s1kiosk.settings

import android.app.Application
import android.content.pm.PackageManager
import dev.glassworks.frame.Frame
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.frame.friendlyName
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.paneSections
import dev.glassworks.pane.settingsOrder
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupOverlay
import sh.edm.s1kiosk.setup.WifiSetupStatus
import sh.edm.s1kiosk.ui.Mockup

/**
 * Mock-ups of what differs on the generic Android frame (#23): the menu's device-settings link, the radio
 * that can't run, and setup's Panes and Home Assistant steps, from the real runtime's pane definitions.
 * The S1's same screens come with them (`-s1`), to show they stay as they were. A made-up Pixel Tablet
 * with a microphone, a speaker, Bluetooth LE and a camera. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*GenericFrameMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenericFrameMockups {
    private val app = RuntimeEnvironment.getApplication()

    @Before fun aPixelTablet() {
        Mockup.interFromSources()
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)) {
            shadowOf(app.packageManager).setSystemFeature(f, true)
        }
        KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel"; zoom = 1.25f }
    }

    private fun menu(frame: Frame, section: String): KioskSettingsOverlay {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        val core = CoreSettings(
            KioskPrefs(app), "0.4.3", status = { "screen on" },
            deviceSettings = SettingsItem.Link(SettingsMenu.deviceSettingsTitle(frame), SettingsMenu.AQARA_DETAIL) {},
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {},
        )
        val sections = paneSections(GlassworksRuntime(frame).paneDefinitions(app))
        o.show(KioskSettingsOverlay.Model(settingsOrder(core.sections(), sections), "0.4.3", sections.map { it.id }.toSet()))
        o.previewSection(section)
        Mockup.idle()
        Mockup.layout(o)
        return o
    }

    /** The device-settings link is the Screen page's last row. */
    private fun scrollDown(v: android.view.View) {
        if (v is android.widget.ScrollView) v.fullScroll(android.view.View.FOCUS_DOWN)
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) scrollDown(v.getChildAt(i))
    }

    private fun setup(frame: Frame, step: String): SetupOverlay {
        val a = Mockup.activity()
        val s = SetupOverlay(a, GlassworksRuntime(frame).setupPanes(app), frame.friendlyName())
        a.setContentView(s, android.view.ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(
            step = step, done = setOf(SetupFlow.WIFI), wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
            haUrl = "http://homeassistant.local:8123", haSource = "mdns", panelIp = "192.0.2.10", signedIn = false,
        ))
        Mockup.idle()
        return s
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        for ((suffix, frame) in listOf("generic" to GenericAndroid(app), "s1" to AqaraS1Plus())) {
            Mockup.save("frame-01-screen-$suffix", menu(frame, "screen").also { o -> scrollDown(o); Mockup.idle() })
            Mockup.save("frame-02-radio-$suffix", menu(frame, "radio"))
            Mockup.save("frame-03-setup-panes-$suffix", setup(frame, SetupFlow.PANES))
            Mockup.save("frame-04-setup-ha-$suffix", setup(frame, SetupFlow.HA))
        }
    }
}
