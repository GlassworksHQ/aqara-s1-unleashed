package sh.edm.s1kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.radio.RadioHelper
import sh.edm.s1kiosk.radio.SysProps

class TimeZoneKeeperTest {
    private class Props(version: Int = 4) : SysProps {
        val p = HashMap<String, String>().apply { put(RadioHelper.PROP_VERSION, version.toString()); put("persist.sys.timezone", "Antarctica/Casey") }
        override fun get(name: String) = p[name] ?: ""
        override fun set(name: String, value: String) { p[name] = value }
        override fun sleep(ms: Long) {}
    }

    private val zones = setOf("Europe/Berlin", "America/Argentina/Buenos_Aires", "UTC", "Antarctica/Casey")

    @Test
    fun validZonesAreHandedToTheHelperOthersRefused() {
        val props = Props()
        var changes = 0
        val k = TimeZoneKeeper(props, zones = { zones }, onChange = { changes++ }, inline = true, log = {})
        assertNotNull(k.set("Europe/Berlin; reboot"))
        assertNotNull(k.set("Mars/Olympus_Mons"))
        assertNotNull(k.set(""))
        assertEquals(null, props.p[TimeZoneKeeper.PROP])
        assertNull(k.set(" America/Argentina/Buenos_Aires "))
        assertEquals("America/Argentina/Buenos_Aires", props.p[TimeZoneKeeper.PROP])
        assertEquals("America/Argentina/Buenos_Aires", k.text())
        assertTrue(changes > 0)
    }

    @Test
    fun statusFollowsTheHelper() {
        val props = Props()
        val k = TimeZoneKeeper(props, zones = { zones }, inline = true, log = {})
        k.refresh()
        assertEquals("now Antarctica/Casey: waiting for the root helper", k.statusText())
        props.p[TimeZoneKeeper.PROP_STATUS] = "none: no time zone chosen (Time zone in Home Assistant, or the installer)"
        k.refresh()
        assertTrue(k.statusText(), k.statusText().startsWith("now Antarctica/Casey: no zone chosen"))
        props.p[TimeZoneKeeper.PROP_STATUS] = "ok Europe/Berlin"
        k.refresh()
        assertEquals("Europe/Berlin: kept by the panel's root helper", k.statusText())
        props.p[TimeZoneKeeper.PROP_STATUS] = "error Mars/X: Android did not accept it (now GMT)"
        k.refresh()
        assertTrue(k.statusText(), k.statusText().endsWith("Mars/X: Android did not accept it (now GMT)"))
    }

    @Test
    fun oldHelperIsExplained() {
        val k = TimeZoneKeeper(Props(version = 3), zones = { zones }, inline = true, log = {})
        k.refresh()
        assertTrue(k.statusText(), k.statusText().contains("needs the root helper v4"))
    }
}
