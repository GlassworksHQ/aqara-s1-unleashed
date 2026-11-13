package sh.edm.s1kiosk.settings

import android.app.Application
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.settingsOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.RadioDefinition
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.radio.ui.RadioFollowUp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsMenuTest {

    private fun extras(vararg kv: Pair<String, Boolean>): (String) -> Boolean? = { k -> kv.toMap()[k] }

    private val app get() = RuntimeEnvironment.getApplication()

    private val off = RadioFollowUp.Facts(
        RadioMode.OFF, "ok Off: Aqara radio services stopped, radio idle", HelperFacts(true, 4), ZigbeeStack.AQARA,
        null, null, "http://homeassistant.local:8123", "192.0.2.10",
    )

    private fun menu(radioRunning: Boolean = true, deviceSettings: Boolean = true) =
        MenuFixture.model(app, radioDefinition(app, FixedReadout(if (radioRunning) off else null, running = radioRunning)), deviceSettings).sections

    private fun titles(items: List<SettingsItem>): List<String> = items.flatMap { i ->
        when (i) {
            is SettingsItem.Text -> listOf(i.title)
            is SettingsItem.Switch -> listOf(i.title)
            is SettingsItem.Link -> listOf(i.title)
            is SettingsItem.Note -> listOf("note")
            is SettingsItem.Info -> listOf("info")
            is SettingsItem.Group -> listOf("[${i.title}]") + titles(i.items)
            is SettingsItem.Native -> listOf("native ${i.key}")
        }
    }

    @Test
    fun theNotificationExtraOpensTheMenu() {
        assertTrue(SettingsMenu.requested(extras("kiosk_settings" to true)))
        assertEquals("kiosk_settings", SettingsMenu.EXTRA)
    }

    @Test
    fun theOlderAdbExtraStillWorks() = assertTrue(SettingsMenu.requested(extras("settings" to true)))

    @Test
    fun absentOrFalseDoesNot() {
        assertFalse(SettingsMenu.requested(extras()))
        assertFalse(SettingsMenu.requested(extras("kiosk_settings" to false, "settings" to false)))
        assertFalse(SettingsMenu.requested(extras("open_settings" to true))) // that one opens Aqara's app
    }

    @Test
    fun sectionsFollowTheManagePagesNav() {
        // #33: Dashboard, Screen, Network, the panes, then Log, Security, About. Empty ones are left out.
        fun s(id: String, n: Int = 1) = SettingsSection(id, id, List(n) { SettingsItem.Note { "" } })
        val core = mapOf(
            SettingsPlace.ABOUT to s("about"), SettingsPlace.LOG to s("log"), SettingsPlace.DASHBOARD to s("dashboard"),
            SettingsPlace.SECURITY to s("security"), SettingsPlace.SCREEN to s("screen"), SettingsPlace.NETWORK to s("network", 0),
        )
        assertEquals(
            listOf("dashboard", "screen", "voice", "radio", "log", "security", "about"),
            settingsOrder(core, listOf(s("voice"), s("music", 0), s("radio"))).map { it.id },
        )
    }

    @Test
    fun theS1sSectionsInOrder() {
        assertEquals(listOf("dashboard", "screen", "radio", "about"), menu().map { it.id })
        assertEquals(listOf("Dashboard", "Screen", "Radio", "About"), menu().map { it.title })
        assertEquals("Zigbee / Thread radio", menu().first { it.id == "radio" }.heading)
    }

    @Test
    fun radioSectionPickerThenWhoMayConnect() {
        val radio = menu().first { it.id == "radio" }.items
        assertEquals(listOf("native radio", "[Who may connect]", "IP addresses or ranges, or any", "note"), titles(radio))
        // kiosk service not running: no picker or card (nothing to drive), the allow-list stays, disabled
        val off = menu(radioRunning = false).first { it.id == "radio" }.items
        assertEquals(listOf("IP addresses or ranges, or any", "note"), titles(off))
        assertFalse((off[0] as SettingsItem.Text).enabled())
        assertEquals("Kiosk service not running", (off[1] as SettingsItem.Note).text())
    }

    @Test
    fun theSectionsAsDrawn() {
        val m = menu().associate { it.id to titles(it.items) }
        assertEquals(listOf("Home Assistant", "Full screen", "Zoom", "Secure hosts"), m["dashboard"])
        assertEquals(
            listOf(
                "Wake when someone's near", "Presence range", "Turn off when idle", "At night", "At night, turn off after",
                "Brightness", SettingsMenu.AQARA_TITLE,
            ),
            m["screen"],
        )
        assertEquals(listOf("info", CoreSettings.LICENCES_TITLE), m["about"])
    }

    @Test
    fun aqaraSettingsOnlyWhenItResolves() {
        val screen = menu(deviceSettings = false).first { it.id == "screen" }
        assertFalse(SettingsMenu.AQARA_TITLE in titles(screen.items))
        assertEquals(6, screen.items.size) // everything else stays
    }

    @Test
    fun aqaraEntryText() {
        assertEquals("Aqara's own settings", SettingsMenu.AQARA_TITLE)
        assertEquals("", SettingsMenu.AQARA_DETAIL)
    }

    @Test
    fun allowedNowLine() {
        assertEquals("Kiosk service not running", RadioDefinition.allowedNow(false, "192.0.2.20"))
        assertTrue(RadioDefinition.allowedNow(true, null).startsWith("Allowed now: no firewall"))
        assertTrue(RadioDefinition.allowedNow(true, "").contains("only the panel itself"))
        assertEquals("Allowed now: 192.0.2.20, 2001:db8::20", RadioDefinition.allowedNow(true, "192.0.2.20 2001:db8::20"))
    }

    @Test
    fun eachSettingWritesWhatItSays() {
        val prefs = KioskPrefs(app)
        val typed = mapOf(
            "Home Assistant" to " ", "Zoom" to "1.5", "Secure hosts" to "ha.local", "Presence range" to "",
            "Turn off when idle" to "x", "At night" to "23:00-06:00", "At night, turn off after" to "30",
        )
        val problems = ArrayList<String?>()
        for (s in menu()) for (i in s.items) when (i) {
            is SettingsItem.Text -> typed[i.title]?.let { v -> i.save(v) { problems += it } }
            is SettingsItem.Switch -> i.save(i.title == "Brightness") { problems += it }
            else -> {}
        }
        assertEquals(List(10) { null }, problems)
        assertEquals(KioskPrefs.AUTO, prefs.urlSetting) // blank = auto
        assertEquals(1.5f, prefs.zoom)
        assertEquals("ha.local", prefs.extraSecureHosts)
        assertFalse(prefs.hideHeader); assertFalse(prefs.hideSidebar) // one switch for both
        assertEquals("always", prefs.screenMode)
        assertEquals(120, prefs.idleOffSec) // not a number: the default
        assertEquals(30, prefs.nightIdleOffSec)
        assertEquals("23:00-06:00", prefs.nightWindow)
        assertEquals(0f, prefs.presenceRangeM)
        assertTrue(prefs.autoBrightness)
    }

    @Test
    fun onlyWhatGeckoReadsAtStartAsksForARestart() {
        // The same ones whose adb extras restart the app: the dashboard's address, zoom, secure hosts.
        val restarts = menu().flatMap { it.items }.mapNotNull { i ->
            when (i) {
                is SettingsItem.Text -> i.title.takeIf { i.restartsApp }
                is SettingsItem.Switch -> i.title.takeIf { i.restartsApp }
                else -> null
            }
        }
        assertEquals(listOf("Home Assistant", "Zoom", "Secure hosts"), restarts)
    }

    @Test
    fun rowsSayTheValueInWords() {
        assertEquals("After 2 minutes", CoreSettings.afterIdle(120))
        assertEquals("After 1 minute", CoreSettings.afterIdle(60))
        assertEquals("After 45 s", CoreSettings.afterIdle(45))
        assertEquals("Never", CoreSettings.afterIdle(0))
        assertEquals("1.25", CoreSettings.number(1.25f))
        assertEquals("2", CoreSettings.number(2f))
    }
}
