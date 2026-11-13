package sh.edm.s1kiosk.settings

import android.app.Application
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.saveAll
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
import sh.edm.s1kiosk.ui.Mockup

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
        assertEquals(listOf("Home Assistant & dashboard", "Panel", "Radio", "About"), menu().map { it.title })
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
    fun theSettingsAsBefore() {
        val m = menu().associate { it.id to titles(it.items) }
        assertEquals(listOf("Dashboard URL", "Zoom (1.0 – 2.0)", "Extra secure-context hosts", "Hide Home Assistant's header & sidebar"), m["dashboard"])
        assertEquals(
            listOf(
                "Screen follows presence", "Screen off after idle (s, 0 = never)", "At night: off after (s)", "Night window",
                "Presence range (m, 0.1 – 2.5)", "Auto brightness", SettingsMenu.AQARA_TITLE,
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
        assertEquals("Panel settings (Aqara)", SettingsMenu.AQARA_TITLE)
        assertEquals("Wi-Fi, sound, display, updates, factory reset", SettingsMenu.AQARA_DETAIL)
    }

    @Test
    fun allowedNowLine() {
        assertEquals("Kiosk service not running", RadioDefinition.allowedNow(false, "192.0.2.20"))
        assertTrue(RadioDefinition.allowedNow(true, null).startsWith("Allowed now: no firewall"))
        assertTrue(RadioDefinition.allowedNow(true, "").contains("only the panel itself"))
        assertEquals("Allowed now: 192.0.2.20, 2001:db8::20", RadioDefinition.allowedNow(true, "192.0.2.20 2001:db8::20"))
    }

    @Test
    fun shortFieldsPairUp() {
        val m = menu().associate { it.id to it.items }
        fun rows(id: String) = SettingsMenu.rows(m.getValue(id)).map { r -> titles(r) }
        assertEquals(
            listOf(listOf("Dashboard URL"), listOf("Zoom (1.0 – 2.0)", "Extra secure-context hosts"), listOf("Hide Home Assistant's header & sidebar")),
            rows("dashboard"),
        )
        assertEquals(
            listOf(
                listOf("Screen follows presence"), listOf("Screen off after idle (s, 0 = never)", "At night: off after (s)"),
                listOf("Night window", "Presence range (m, 0.1 – 2.5)"), listOf("Auto brightness"), listOf(SettingsMenu.AQARA_TITLE),
            ),
            rows("screen"),
        )
        // not adjacent: no pair
        val d = m.getValue("dashboard")
        assertEquals(listOf(1, 1, 1), SettingsMenu.rows(listOf(d[1], d[0], d[2])).map { it.size })
    }

    @Test
    fun aDisabledFieldIsNotSaved() {
        // The radio's allow-list shows "" and is disabled without its service: saving it could wipe the list
        // if the service started while the menu was open.
        Mockup.interFromSources()
        fun saves(running: Boolean): Int {
            val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
            o.show(MenuFixture.model(app, radioDefinition(app, FixedReadout(if (running) off else null, running = running))))
            return o.current().size
        }
        assertEquals(11, saves(running = true)) // 4 dashboard + 6 screen + the allow-list
        assertEquals(10, saves(running = false))
    }

    @Test
    fun saveAndRestartWritesWhatTheMenuShows() {
        val prefs = KioskPrefs(app)
        val changes = ArrayList<(done: (String?) -> Unit) -> Unit>()
        val typed = mapOf(
            "Dashboard URL" to " ", "Zoom (1.0 – 2.0)" to "1.5", "Extra secure-context hosts" to "ha.local",
            "Screen off after idle (s, 0 = never)" to "x", "At night: off after (s)" to "30", "Night window" to "23:00-06:00",
            "Presence range (m, 0.1 – 2.5)" to "",
        )
        for (s in menu()) for (i in s.items) when (i) {
            is SettingsItem.Text -> typed[i.title]?.let { v -> changes += { done -> i.save(v, done) } }
            is SettingsItem.Switch -> changes += { done -> i.save(i.title == "Auto brightness", done) }
            else -> {}
        }
        var problems: List<String>? = null
        saveAll(changes) { problems = it }
        assertEquals(emptyList<String>(), problems)
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
    fun savesRunInOrderAndCollectProblems() {
        val log = ArrayList<String>()
        var later: (() -> Unit)? = null
        var problems: List<String>? = null
        saveAll(listOf(
            { done -> log += "a"; done(null) },
            { done -> log += "b"; later = { done("Radio allow-list not changed: no") } }, // answers later (the radio thread)
            { done -> log += "c"; done(null) },
        )) { problems = it }
        assertEquals(listOf("a", "b"), log)
        assertEquals(null, problems) // waits for the slow one
        later!!()
        assertEquals(listOf("a", "b", "c"), log)
        assertEquals(listOf("Radio allow-list not changed: no"), problems)
    }
}
