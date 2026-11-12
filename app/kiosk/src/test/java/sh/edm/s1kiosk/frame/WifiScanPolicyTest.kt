package sh.edm.s1kiosk.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.frame.WifiScanPolicy.Decision
import sh.edm.s1kiosk.frame.WifiScanPolicy.Link
import sh.edm.s1kiosk.frame.WifiScanPolicy.Wifi

class WifiScanPolicyTest {
    private val down = Wifi(enabled = true, link = Link.DOWN, screenOn = false)
    private val connecting = down.copy(link = Link.CONNECTING)
    private val policy = WifiScanPolicy()

    /**
     * Runs the policy as the scanner does: [decide][WifiScanPolicy.decide] at every change of
     * [wifiAt] (sampled every second) and when the last wake is up. Returns the scan times in s.
     */
    private fun scanTimes(untilS: Long, wifiAt: (Long) -> Wifi): List<Long> {
        val scans = mutableListOf<Long>()
        var wakeAt: Long? = null
        var last: Wifi? = null
        for (ms in 0..untilS * 1000) {
            val w = wifiAt(ms / 1000)
            if (w == last && ms != wakeAt) continue
            last = w
            val d = policy.decide(w, ms)
            if (d.scanNow) scans += ms / 1000
            wakeAt = d.wakeInMs?.let { ms + it }
        }
        return scans
    }

    /** Android 10's `ScanRequestProxy`: a scan is refused when 4 earlier scans are at most 120 s old. */
    private fun assertNoneThrottled(times: List<Long>) {
        for ((i, t) in times.withIndex()) assertTrue("$times", times.take(i).count { t - it <= 120 } < 4)
    }

    @Test fun wifiDroppedWithTheScreenOffScansAfterTenSeconds() {
        assertEquals(Decision(false, 10_000), policy.decide(down, 0))
        assertEquals(listOf(10L), scanTimes(15) { down })
    }

    @Test fun nothingScansWhileConnectedOrScreenOnOrWifiOff() {
        for (w in listOf(down.copy(link = Link.CONNECTED), down.copy(screenOn = true), down.copy(enabled = false))) {
            assertEquals(w.toString(), emptyList<Long>(), WifiScanPolicy().run { (0L..600).filter { decide(w, it * 1000).scanNow } })
            assertEquals(w.toString(), Decision(false, null), WifiScanPolicy().decide(w, 0))
        }
    }

    @Test fun scansBackOffTo120sAndStayThere() {
        assertEquals(listOf(10L, 50, 110, 230, 350, 470), scanTimes(500) { down })
    }

    @Test fun eventsWhileWaitingKeepTheSchedule() {
        policy.decide(down, 0)
        assertEquals(Decision(false, 5_000), policy.decide(down, 5_000))
        assertEquals(Decision(true, 40_000), policy.decide(down, 10_000))
    }

    @Test fun scansStopWhenWifiConnects() {
        assertEquals(listOf(10L), scanTimes(300) { if (it < 20) down else down.copy(link = Link.CONNECTED) })
    }

    @Test fun theScreenTurningOnStopsScansAndTheNextDropStartsOver() {
        val times = scanTimes(400) { down.copy(screenOn = it in 115..199) }
        assertEquals(listOf(10L, 50, 110, 210, 250, 310), times)
    }

    /** Someone walks past every 15 s: each wake resets the back-off, the 40 s gap still holds. */
    @Test fun resetsOverAndOverNeverGetThrottled() {
        val times = scanTimes(600) { down.copy(screenOn = it % 15 == 14L) }
        assertTrue(times.size >= 10)
        assertTrue("$times", times.zipWithNext().all { (a, b) -> b - a >= 40 })
        assertNoneThrottled(times)
    }

    @Test fun theNormalScheduleStaysUnderTheThrottle() {
        assertNoneThrottled(scanTimes(1200) { down })
    }

    @Test fun aSupplicantStuckConnectingFor60sGetsScans() {
        assertEquals(Decision(false, 60_000), policy.decide(connecting, 0))
        assertEquals(listOf(70L, 110), scanTimes(150) { connecting })
    }

    @Test fun aConnectThatFinishesBeforeTheDeadlineGetsNoScan() {
        assertEquals(emptyList<Long>(), scanTimes(300) { if (it < 50) connecting else down.copy(link = Link.CONNECTED) })
    }

    @Test fun theThrottleCheckCountsAScanExactly120sOld() {
        assertThrows(AssertionError::class.java) { assertNoneThrottled(listOf(0L, 30, 60, 90, 120)) }
        assertNoneThrottled(listOf(0L, 30, 60, 90, 121))
    }

    /** Connecting 20 s, not connected 5 s, over and over: the 5 s drops do not restart the attempt. */
    @Test fun aConnectThatKeepsFailingStillReachesTheDeadline() {
        val times = scanTimes(600) { if (it % 25 < 20) connecting else down }
        assertEquals(listOf(70L, 110, 170, 290, 410, 530), times)
    }

    @Test fun aDropLongerThanABlipStartsTheAttemptOver() {
        val times = scanTimes(200) { if (it in 40..59) down else connecting }
        assertEquals(listOf(50L, 130, 170), times)
    }

    @Test fun theConnectingDeadlineStartsOverAfterAConnect() {
        val times = scanTimes(200) { if (it in 40..44) down.copy(link = Link.CONNECTED) else connecting }
        assertEquals(listOf(115L, 155), times)
    }

    /** The panel run of b47ce54: permission granted, location on, app-op foreground-only, screen off. */
    @Test fun aRefusedScanNamesTheFirstCauseAndroidChecks() {
        val r = WifiScanPolicy::refusal
        assertEquals("no location permission", r(false, false, false, true))
        assertEquals("location off", r(true, false, false, true))
        assertEquals("location not allowed in the background (app-op foreground-only)", r(true, true, false, false))
        assertEquals("device idle (Doze)", r(true, true, true, true))
        assertEquals("throttled", r(true, true, true, false))
    }
}
