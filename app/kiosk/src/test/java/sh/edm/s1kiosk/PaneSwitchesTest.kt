package sh.edm.s1kiosk

import android.app.Application
import dev.glassworks.pane.NOT_RUNNING
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.paneSections
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.frame.AqaraS1Plus

/** #67 on the S1: every pane has an on/off, in the settings, on setup's Panes step (design 05) and in Home Assistant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PaneSwitchesTest {
    private val app = RuntimeEnvironment.getApplication()
    private val definitions = GlassworksRuntime(AqaraS1Plus()).paneDefinitions(app)

    @Test fun withoutBluetoothLeTheBluetoothProxyHasNoCard() {
        org.robolectric.Shadows.shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE, false)
        assertEquals(null, definitions.single { it.id == "bluetooth-proxy" }.setupChoice)
    }

    @Test fun everyPaneSectionHasItsOnOffAtTheTop() {
        val sections = paneSections(definitions)
        assertEquals(listOf("assist", "sendspin", "notifications", "radio", "bluetooth-proxy", "wall-base", "time-zone", "diagnostics"), sections.map { it.id })
        assertEquals(listOf("Voice", "Music", "Notifications", "Zigbee / Thread radio", "Bluetooth proxy", "Wall base", "Time zone", "Diagnostics"), sections.map { it.master?.title })
        // No device runs here: a page that shows the running pane says so (#212); the radio, the Bluetooth proxy
        // and notifications (off by default, so its page is its off note) have their own.
        val waiting = sections.filter { s -> s.items.singleOrNull().let { it is SettingsItem.Note && it.text() == NOT_RUNNING } }
        assertEquals(listOf("assist", "sendspin", "wall-base", "time-zone", "diagnostics"), waiting.map { it.id })
    }

    @Test fun setupsPanesStepHasTheCardsOfTheDesign() {
        // A panel with Bluetooth LE, as the S1: without it the Bluetooth proxy has no card.
        org.robolectric.Shadows.shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE, true)
        assertEquals(
            listOf(
                "Voice" to "Assist and timers", "Music" to "A Sendspin speaker", "Notifications" to "On-screen alerts",
                "Zigbee / Thread radio" to "For your devices", "Bluetooth proxy" to "For BLE sensors", "Wall base" to "The S1's wired base",
            ),
            definitions.mapNotNull { it.setupChoice }.map { it.title to it.detail },
        )
    }

    /** Ids are permanent in Home Assistant. Only `sendspin` existed, and keeps its name. Each is off in HA until turned on there (#132, #208). */
    @Test fun eachSwitchIsAConfigEntityHomeAssistantKeeps() {
        val s = definitions.mapNotNull { it.switch }.map { it.entity() }
        assertEquals(listOf("voice", "sendspin", "notifications", "bluetooth_proxy", "wall_base", "keep_time_zone", "diagnostics"), s.map { it.objectId })
        assertEquals("Music Assistant player (Sendspin)", s[1].name)
        assertEquals(List(7) { true }, s.map { it.disabledByDefault })
        assertEquals(setOf(sh.edm.esphome.EntityCategory.CONFIG), s.map { it.category }.toSet())
        // #210 verifier: turned on, a pane opens the microphone or a port; a keyless LAN client may not.
        assertEquals("only an authenticated connection changes a pane's on/off", List(7) { true }, s.map { it.authOnly })
    }

    /** The device info's voice features follow the voice pane's switch, which says so: the shell names no pane. */
    @Test fun onlyTheVoicePaneSetsTheVoiceFeatures() {
        assertEquals(listOf("assist"), definitions.filter { it.switch?.voiceAssistant == true }.map { it.id })
    }

    /** Notifications (#118, Principle 7) and the Bluetooth proxy (it starts the radio scanning, #121) are off until turned on. */
    @Test fun everyPaneButNotificationsAndTheBluetoothProxyIsOnUntilTurnedOff() {
        assertEquals(
            listOf(
                "assist" to true, "sendspin" to true, "notifications" to false, "bluetooth-proxy" to false,
                "wall-base" to true, "time-zone" to true, "diagnostics" to true,
            ),
            definitions.mapNotNull { d -> d.switch?.let { d.id to it.on } },
        )
    }
}
