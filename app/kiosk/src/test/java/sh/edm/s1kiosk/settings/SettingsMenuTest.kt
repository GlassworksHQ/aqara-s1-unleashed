package sh.edm.s1kiosk.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.settings.SettingsMenu.Entry
import sh.edm.s1kiosk.settings.SettingsMenu.Section

class SettingsMenuTest {

    private fun extras(vararg kv: Pair<String, Boolean>): (String) -> Boolean? = { k -> kv.toMap()[k] }

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
    fun sectionsInOrder() {
        val l = SettingsMenu.layout(SettingsMenu.Facts(deviceSettings = true))
        assertEquals(listOf(Section.DASHBOARD, Section.RADIO, Section.PANEL, Section.ABOUT), l.map { it.first })
        assertEquals("Radio", Section.RADIO.title)
    }

    @Test
    fun radioSectionPickerThenFollowUpThenWhoMayConnect() {
        val radio = SettingsMenu.layout(SettingsMenu.Facts(deviceSettings = false)).toMap().getValue(Section.RADIO)
        assertEquals(listOf(Entry.RADIO_MODE, Entry.RADIO_FOLLOW_UP, Entry.RADIO_ALLOW, Entry.RADIO_ALLOWED_NOW), radio)
        // kiosk service not running: no picker or card (nothing to drive), the allow-list stays
        val off = SettingsMenu.layout(SettingsMenu.Facts(deviceSettings = false, radio = false)).toMap().getValue(Section.RADIO)
        assertEquals(listOf(Entry.RADIO_ALLOW, Entry.RADIO_ALLOWED_NOW), off)
    }

    @Test
    fun everyEntryShowsOnceWithAqaraSettings() {
        val all = SettingsMenu.layout(SettingsMenu.Facts(deviceSettings = true)).flatMap { it.second }
        assertEquals(Entry.values().toSet(), all.toSet())
        assertEquals(all.size, all.toSet().size)
        val panel = SettingsMenu.layout(SettingsMenu.Facts(deviceSettings = true)).first { it.first == Section.PANEL }.second
        assertEquals(Entry.AQARA_SETTINGS, panel.last())
    }

    @Test
    fun aqaraSettingsOnlyWhenItResolves() {
        val all = SettingsMenu.layout(SettingsMenu.Facts(deviceSettings = false)).flatMap { it.second }
        assertFalse(Entry.AQARA_SETTINGS in all)
        assertEquals(Entry.values().size - 1, all.size) // everything else stays
    }

    @Test
    fun aboutHasVersionAndLicences() {
        val about = SettingsMenu.layout(SettingsMenu.Facts(deviceSettings = false)).first { it.first == Section.ABOUT }.second
        assertEquals(listOf(Entry.VERSION, Entry.LICENCES), about)
    }

    @Test
    fun aqaraEntryText() {
        assertEquals("Panel settings (Aqara)", SettingsMenu.AQARA_TITLE)
        assertEquals("Wi-Fi, sound, display, updates, factory reset", SettingsMenu.AQARA_DETAIL)
    }

    @Test
    fun allowedNowLine() {
        assertEquals("Kiosk service not running", SettingsMenu.allowedNow(false, "10.0.0.2"))
        assertTrue(SettingsMenu.allowedNow(true, null).startsWith("Allowed now: no firewall"))
        assertTrue(SettingsMenu.allowedNow(true, "").contains("only the panel itself"))
        assertEquals("Allowed now: 10.0.0.2, fd00::2", SettingsMenu.allowedNow(true, "10.0.0.2 fd00::2"))
    }

    @Test
    fun shortFieldsPairUp() {
        val l = SettingsMenu.layout(SettingsMenu.Facts(deviceSettings = true)).toMap()
        assertEquals(
            listOf(listOf(Entry.URL), listOf(Entry.ZOOM, Entry.SECURE_HOSTS), listOf(Entry.HIDE_HEADER)),
            SettingsMenu.rows(l.getValue(Section.DASHBOARD)),
        )
        assertEquals(
            listOf(
                listOf(Entry.PRESENCE), listOf(Entry.IDLE_OFF, Entry.NIGHT_IDLE_OFF), listOf(Entry.NIGHT_WINDOW, Entry.PRESENCE_RANGE),
                listOf(Entry.AUTO_BRIGHTNESS), listOf(Entry.AQARA_SETTINGS),
            ),
            SettingsMenu.rows(l.getValue(Section.PANEL)),
        )
        // not adjacent: no pair
        assertEquals(listOf(listOf(Entry.ZOOM), listOf(Entry.URL), listOf(Entry.SECURE_HOSTS)), SettingsMenu.rows(listOf(Entry.ZOOM, Entry.URL, Entry.SECURE_HOSTS)))
    }
}
