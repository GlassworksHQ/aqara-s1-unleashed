package sh.edm.s1kiosk.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wall base's page in words (#212). */
class WallBasePageTest {
    private val three = BaseLayout(BaseKind.SWITCH, listOf(1, 2, 3))

    @Test fun theStatusLineSaysWhichBaseAndHow() {
        assertEquals("Connected · over the panel's serial port" to true, WallBasePage.status(three, BaseBackend.UART))
        assertEquals("Connected · through Aqara's service" to true, WallBasePage.status(BaseLayout(BaseKind.ETHERNET), BaseBackend.LOCALSERVICE))
        assertEquals("No base connected" to false, WallBasePage.status(BaseLayout.NONE, BaseBackend.UART))
        assertEquals("Looking for a base · over the panel's serial port" to false, WallBasePage.status(null, BaseBackend.UART))
    }

    @Test fun aSwitchBaseHasFourTilesAndNoEnergyYet() {
        val t = WallBasePage.strip(three, "0.0.0_0034", 0, 12.43f)
        assertEquals(listOf("Base", "Firmware", "Protection", "Power"), t.map { it.name })
        assertEquals(listOf("Switch", "0.0.0_0034", "Normal", "12.4"), t.map { it.value })
        assertEquals("energy waits for a real base to settle its unit (#216)", listOf("3 channels", "From the base", "No overload", ""), t.map { it.detail })
        assertEquals(listOf("Base", "Firmware"), WallBasePage.strip(BaseLayout(BaseKind.ETHERNET), null, null, null).map { it.name })
    }

    @Test fun aButtonsLastPress() {
        val now = 1_000_000_000L
        assertEquals("Not pressed yet", WallBasePage.press(null, now))
        assertEquals("Double · just now", WallBasePage.press("double" to now - 20_000, now))
        assertEquals("Long press · 2 min ago", WallBasePage.press("long" to now - 120_000, now))
        val older = WallBasePage.press("single" to now - 7_200_000, now)
        assertTrue("an older press has its time: $older", Regex("""Single · \d\d:\d\d""").matches(older))
        assertEquals("Overload" to "It cut the load", WallBasePage.protection(2))
    }
}
