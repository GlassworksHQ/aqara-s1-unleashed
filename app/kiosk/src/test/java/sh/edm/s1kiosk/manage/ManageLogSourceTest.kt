package sh.edm.s1kiosk.manage

import dev.glassworks.manage.LogEntry
import dev.glassworks.manage.ManageLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.edm.s1kiosk.esphome.PanelDevice

/** Which of the app's logcat lines reach the Manage page's log, and under what name. */
class ManageLogSourceTest {
    private fun line(tag: String, text: String) = "1731456000.123  1234  1250 I $tag: $text"

    @Test fun anEspHomeLineReachesTheLog() {
        val e = ManageLog.fromLogcat(line(PanelDevice.ESPHOME_LOG_TAG, "Home Assistant has added the panel"), S1Manage::logSource)!!
        assertEquals("Home Assistant", e.source)
        assertEquals(LogEntry.Level.INFO, e.level)
        assertEquals("S1ESPHome", PanelDevice.ESPHOME_LOG_TAG)
    }

    @Test fun theAppsOwnTags() {
        assertEquals("Radio", S1Manage.logSource("S1Kiosk.Radio"))
        assertEquals("Voice", S1Manage.logSource("S1Voice"))
        assertEquals("App", S1Manage.logSource("S1Kiosk"))
        assertNull(S1Manage.logSource("GeckoConsole"))
        assertNull("the Manage page logs its own events directly", S1Manage.logSource(S1Manage.TAG))
    }
}
