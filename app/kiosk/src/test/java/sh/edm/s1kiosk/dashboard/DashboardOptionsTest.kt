package sh.edm.s1kiosk.dashboard

import sh.edm.s1kiosk.dashboard.DashboardOptions.CUSTOM
import sh.edm.s1kiosk.dashboard.DashboardOptions.DEFAULT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardOptionsTest {

    private val ha = "http://192.168.1.100:8123"
    private val list = listOf(
        Dashboard("tablet-home", "Tablet"),
        Dashboard("energy-dash", "Energy"),
        Dashboard("map-x", ""),
    )

    @Test
    fun parsesTheFrontendList() {
        // Shape of lovelace/dashboards/list items after content.js reduced them to {url_path, title}.
        val json = """[{"url_path":"tablet-home","title":"Tablet"},{"url_path":"/energy-dash/","title":" Energy "},
            {"url_path":"","title":"x"},{"title":"no path"},{"url_path":"a/b","title":"slash"},
            {"url_path":"tablet-home","title":"dup"},{"url_path":"map-x"}]"""
        assertEquals(list, DashboardOptions.parse(json))
        assertNull(DashboardOptions.parse("{}"))
        assertEquals(list, DashboardOptions.parse(DashboardOptions.toJson(list)))
    }

    @Test
    fun optionsAreDefaultThenDashboards() {
        assertEquals(listOf(DEFAULT, "Tablet", "Energy", "map-x"), DashboardOptions.options(list, DEFAULT))
        assertEquals(listOf(DEFAULT), DashboardOptions.options(emptyList(), DEFAULT))
        // "Custom" only when the current URL needs it.
        assertEquals(listOf(DEFAULT, "Tablet", "Energy", "map-x", CUSTOM), DashboardOptions.options(list, CUSTOM))
    }

    @Test
    fun duplicateAndReservedTitlesAreDisambiguated() {
        val l = listOf(Dashboard("a", "Home"), Dashboard("b", "Home"), Dashboard("c", "Default"), Dashboard("d", "custom"))
        assertEquals(listOf(DEFAULT, "Home", "Home (b)", "Default (c)", "custom (d)"), DashboardOptions.options(l, DEFAULT))
    }

    @Test
    fun currentOptionFromTheSavedUrl() {
        fun cur(setting: String) = DashboardOptions.currentOption(setting, ha, list)
        assertEquals(DEFAULT, cur("auto"))
        assertEquals(DEFAULT, cur("$ha/"))
        assertEquals(DEFAULT, cur(ha))
        assertEquals(DEFAULT, cur("$ha/lovelace/0"))
        assertEquals("Tablet", cur("$ha/tablet-home"))
        assertEquals("Tablet", cur("$ha/tablet-home/0?kiosk=0"))
        assertEquals("map-x", cur("$ha/map-x/"))
        assertEquals(CUSTOM, cur("$ha/unknown-dash"))
        assertEquals(CUSTOM, cur("$ha/config/devices"))
        assertEquals(CUSTOM, cur("http://other.lan:8123/tablet-home"))
        assertEquals(CUSTOM, cur("not a url"))
        // Before the list arrives, a dashboard URL is shown truthfully as Custom.
        assertEquals(CUSTOM, DashboardOptions.currentOption("$ha/tablet-home", ha, emptyList()))
    }

    @Test
    fun urlForAChosenOption() {
        fun url(option: String, setting: String, haUrl: String = ha) =
            DashboardOptions.urlFor(option, setting, ha, haUrl, list)
        assertEquals("$ha/tablet-home", url("Tablet", "auto"))
        assertEquals("$ha/energy-dash", url("Energy", "$ha/tablet-home/0"))
        // Already current (keeps the view), Custom, unknown: nothing to do.
        assertNull(url("Tablet", "$ha/tablet-home/1"))
        assertNull(url(DEFAULT, "auto"))
        assertNull(url(CUSTOM, "$ha/x"))
        assertNull(url("Nope", "auto"))
        // Default goes back to "auto" when discovery found the same HA, else to HA's root.
        assertEquals("auto", url(DEFAULT, "$ha/tablet-home"))
        assertEquals("$ha/", url(DEFAULT, "$ha/tablet-home", haUrl = ""))
        assertEquals("$ha/", url(DEFAULT, "$ha/tablet-home", haUrl = "http://homeassistant.local:8123"))
        // No HA known: nothing.
        assertNull(DashboardOptions.urlFor("Tablet", "auto", null, "", list))
    }

    @Test
    fun roundTrip() {
        for (option in DashboardOptions.options(list, DEFAULT)) {
            val url = DashboardOptions.urlFor(option, "$ha/somewhere-else", ha, ha, list) ?: continue
            assertEquals(option, DashboardOptions.currentOption(url, ha, list))
        }
    }
}
