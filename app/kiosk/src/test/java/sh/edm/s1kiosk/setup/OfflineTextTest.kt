package sh.edm.s1kiosk.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.improv.ImprovController

class OfflineTextTest {
    private val grace = ImprovController.BOOT_GRACE_MS
    private val boot = OfflineText.Facts(wifiEnabled = true, savedNetwork = null, joining = false, wasOnlineBefore = true, offlineForMs = 3_000)

    private fun text(f: OfflineText.Facts) = OfflineText.decide(f, grace)

    @Test
    fun graceIsImprovsBootGrace() = assertEquals(30_000L, grace)

    @Test
    fun rebootWithASavedNetworkSaysConnecting() {
        val t = text(boot)
        assertTrue(t.connecting)
        assertEquals("Connecting to Wi-Fi…", t.title)
        assertEquals("Home Assistant loads as soon as it's connected", t.detail)
    }

    @Test
    fun savedNetworkListedCountsEvenIfNeverOnline() {
        assertTrue(text(boot.copy(savedNetwork = true, wasOnlineBefore = false)).connecting)
    }

    @Test
    fun joiningNowCountsEvenWithoutAnyHistory() {
        assertTrue(text(boot.copy(savedNetwork = false, wasOnlineBefore = false, joining = true)).connecting)
    }

    @Test
    fun noSavedNetworkShowsTheSetupHint() {
        val t = text(boot.copy(savedNetwork = false))
        assertFalse(t.connecting)
        assertEquals("No network yet", t.title)
        assertEquals(OfflineText.SETUP_HINT, t.detail)
    }

    @Test
    fun unknownSavedStateAndNeverOnlineShowsTheSetupHint() {
        assertFalse(text(boot.copy(savedNetwork = null, wasOnlineBefore = false)).connecting)
    }

    @Test
    fun wifiOffShowsTheHintWithItsOwnTitle() {
        val t = text(boot.copy(wifiEnabled = false, savedNetwork = true, joining = true))
        assertFalse(t.connecting)
        assertEquals("Wi-Fi is off", t.title)
        assertEquals(OfflineText.SETUP_HINT, t.detail)
    }

    @Test
    fun graceOverShowsTheHint() {
        assertTrue(text(boot.copy(offlineForMs = grace - 1)).connecting)
        val t = text(boot.copy(offlineForMs = grace, joining = true, savedNetwork = true))
        assertFalse(t.connecting)
        assertEquals("No network yet", t.title)
    }

    @Test
    fun neverShowsAnSsid() {
        listOf(boot, boot.copy(savedNetwork = false), boot.copy(wifiEnabled = false), boot.copy(offlineForMs = 99_000)).forEach {
            val t = text(it)
            assertTrue(t.title in setOf("Connecting to Wi-Fi…", "No network yet", "Wi-Fi is off"))
        }
    }

    @Test
    fun joiningFromAndroidStates() {
        assertTrue(OfflineText.joining("ASSOCIATING", null))
        assertTrue(OfflineText.joining("COMPLETED", "IDLE"))  // associated, getting an address
        assertTrue(OfflineText.joining(null, "OBTAINING_IPADDR"))
        assertFalse(OfflineText.joining("SCANNING", "DISCONNECTED"))
        assertFalse(OfflineText.joining("DISCONNECTED", null))
        assertFalse(OfflineText.joining("INACTIVE", "IDLE"))
        assertFalse(OfflineText.joining(null, null))
    }
}
