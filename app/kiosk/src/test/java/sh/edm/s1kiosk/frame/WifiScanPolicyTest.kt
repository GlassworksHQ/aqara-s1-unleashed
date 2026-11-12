package sh.edm.s1kiosk.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.frame.WifiScanPolicy.Next
import sh.edm.s1kiosk.frame.WifiScanPolicy.Wifi

class WifiScanPolicyTest {
    private val dropped = Wifi(enabled = true, connecting = false, screenOn = false)
    private val policy = WifiScanPolicy()

    @Test fun wifiDroppedWithTheScreenOffScansAfterTenSeconds() {
        assertEquals(Next.ScanIn(10_000), policy.onChange(dropped))
    }

    @Test fun nothingScansWhileConnectedOrScreenOnOrWifiOff() {
        for (w in listOf(dropped.copy(connecting = true), dropped.copy(screenOn = true), dropped.copy(enabled = false))) {
            assertEquals(w.toString(), Next.Stop, WifiScanPolicy().onChange(w))
        }
    }

    @Test fun scansBackOffTo120sAndStayThere() {
        policy.onChange(dropped)
        val delays = List(6) { (policy.onDue(dropped) as Next.ScanIn).delayMs }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 120_000L, 120_000L, 120_000L), delays)
    }

    /** Android 10 throttles a foreground app to 4 scans in 120 s. */
    @Test fun neverMoreThanThreeScansInAny120s() {
        var t = (policy.onChange(dropped) as Next.ScanIn).delayMs
        val scanTimes = mutableListOf<Long>()
        repeat(20) {
            scanTimes += t
            t += (policy.onDue(dropped) as Next.ScanIn).delayMs
        }
        for (start in scanTimes) assertTrue(scanTimes.count { it in start until start + 120_000 } <= 3)
    }

    @Test fun moreEventsWhileScanningKeepTheSchedule() {
        policy.onChange(dropped)
        policy.onDue(dropped)
        assertEquals(Next.Keep, policy.onChange(dropped))
        assertEquals(Next.ScanIn(60_000), policy.onDue(dropped))
    }

    @Test fun aDueScanStopsInsteadWhenWifiConnected() {
        policy.onChange(dropped)
        assertEquals(Next.Stop, policy.onDue(dropped.copy(connecting = true)))
    }

    @Test fun theScreenTurningOnStopsScansAndTheNextDropStartsOver() {
        policy.onChange(dropped)
        repeat(4) { policy.onDue(dropped) }
        assertEquals(Next.Stop, policy.onChange(dropped.copy(screenOn = true)))
        assertEquals(Next.ScanIn(10_000), policy.onChange(dropped))
        assertEquals(Next.ScanIn(30_000), policy.onDue(dropped))
    }
}
