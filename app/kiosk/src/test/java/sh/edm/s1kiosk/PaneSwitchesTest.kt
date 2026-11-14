package sh.edm.s1kiosk

import android.app.Application
import dev.glassworks.pane.NO_SETTINGS
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

    @Test fun everyPaneSectionHasItsOnOffAtTheTop() {
        val sections = paneSections(definitions)
        assertEquals(listOf("assist", "sendspin", "radio", "wall-base", "time-zone", "diagnostics"), sections.map { it.id })
        assertEquals(listOf("Voice", "Music", "Zigbee / Thread radio", "Wall base", "Time zone", "Diagnostics"), sections.map { it.master?.title })
        // Only the radio has settings of its own so far; the others say so (#163's page).
        val noSettings = sections.filter { s -> s.items.singleOrNull().let { it is SettingsItem.Note && it.text() == NO_SETTINGS } }
        assertEquals(listOf("assist", "sendspin", "wall-base", "time-zone", "diagnostics"), noSettings.map { it.id })
    }

    @Test fun setupsPanesStepHasTheCardsOfTheDesign() {
        assertEquals(
            listOf("Voice" to "Assist and timers", "Music" to "A Sendspin speaker", "Zigbee / Thread radio" to "For your devices", "Wall base" to "The S1's wired base"),
            definitions.mapNotNull { it.setupChoice }.map { it.title to it.detail },
        )
    }

    /** Ids are permanent in Home Assistant. Only `sendspin` existed, and keeps its name. Each is off in HA until turned on there (#132, #208). */
    @Test fun eachSwitchIsAConfigEntityHomeAssistantKeeps() {
        val s = definitions.mapNotNull { it.switch }.map { it.entity() }
        assertEquals(listOf("voice", "sendspin", "wall_base", "keep_time_zone", "diagnostics"), s.map { it.objectId })
        assertEquals("Music Assistant player (Sendspin)", s[1].name)
        assertEquals(List(5) { true }, s.map { it.disabledByDefault })
        assertEquals(setOf(sh.edm.esphome.EntityCategory.CONFIG), s.map { it.category }.toSet())
        // #210 verifier: turned on, a pane opens the microphone or a port; a keyless LAN client may not.
        assertEquals("only an authenticated connection changes a pane's on/off", List(5) { true }, s.map { it.authOnly })
    }

    /** The device info's voice features follow the voice pane's switch, which says so: the shell names no pane. */
    @Test fun onlyTheVoicePaneSetsTheVoiceFeatures() {
        assertEquals(listOf("assist"), definitions.filter { it.switch?.voiceAssistant == true }.map { it.id })
    }

    @Test fun everyPaneIsOnUntilTurnedOff() {
        assertEquals(List(5) { true }, definitions.mapNotNull { it.switch?.on })
    }
}
