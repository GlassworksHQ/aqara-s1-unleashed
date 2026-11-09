package io.github.aguilaair.s1kiosk.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class TimeFilterTest {

    @Test
    fun measurementFormulasTruncateTowardZero() {
        // T1=0, T2=1005, T3=1010, T4=21: offset ((1005)+(1010-21))/2 = 997, max_error ((21)-(5))/2 = 8
        assertEquals(997L, TimeFilter.measurement(0, 1005, 1010, 21))
        assertEquals(8L, TimeFilter.maxError(0, 1005, 1010, 21))
        assertEquals(-1L, TimeFilter.measurement(0, -1, -2, 0)) // (-1 + -2) / 2 = -1.5 -> -1
    }

    @Test
    fun roundingHalvesAwayFromZero() {
        assertEquals(3L, TimeFilter.roundHalfAway(2.5))
        assertEquals(-3L, TimeFilter.roundHalfAway(-2.5))
        assertEquals(-2L, TimeFilter.roundHalfAway(-2.4))
    }

    @Test
    fun firstTwoUpdatesFollowTheSpec() {
        val f = TimeFilter()
        assertFalse(f.snapshot.converged)
        assertFalse(f.update(100, 0, 10)) // max_error <= 0 discarded
        assertTrue(f.update(1000, 50, 1_000_000))
        assertEquals(1000.0, f.snapshot.offset, 0.0)
        assertEquals(25.0, f.snapshot.offsetStdDevUs, 1e-9) // sqrt((0.5*50)^2)
        assertTrue(f.snapshot.converged)
        assertFalse(f.update(1000, 50, 1_000_000)) // time_added <= last_update
        assertTrue(f.update(1010, 50, 2_000_000))
        assertEquals(1010.0, f.snapshot.offset, 0.0)
        assertEquals(10.0 / 1_000_000, f.snapshot.drift, 1e-15)
    }

    @Test
    fun conversionsAreInverse() {
        val s = TimeFilter.Snapshot(offset = 12_345.6, drift = 50e-6, lastUpdate = 1_000_000_000, useDrift = true, count = 10, offsetStdDevUs = 1.0)
        for (t in listOf(0L, 999_999_999L, 1_000_000_000L, 5_000_000_000L)) {
            val server = s.serverTime(t)
            assertTrue(abs(s.clientTime(server) - t) <= 1)
        }
        val noDrift = s.copy(useDrift = false)
        assertEquals(1_000L + 12_346, noDrift.serverTime(1_000L))
    }

    /** Simulated server clock: offset 3.2 s, drift +40 ppm, Wi-Fi-like asymmetric delays. */
    @Test
    fun tracksOffsetAndDriftThroughBursts() {
        val rnd = Random(1)
        val f = TimeFilter()
        val burst = TimeSyncBurst(f)
        val offset0 = 3_200_000.0
        val drift = 40e-6
        fun server(tc: Long) = (tc + offset0 + drift * tc).toLong()
        var now = 1_000_000L
        repeat(120) { b ->
            var t1 = burst.start(now)!!
            while (true) {
                val up = 1_500 + rnd.nextInt(8_000)   // 1.5-9.5 ms each way
                val down = 1_500 + rnd.nextInt(8_000)
                val t2 = server(t1 + up)
                val t3 = t2 + 50
                val t4 = t1 + up + 50 + down
                now = t4
                val next = burst.onReply(t1, t2, t3, t4, now) ?: break
                t1 = next
            }
            now += 1_000_000 // next burst 1 s later
            assertEquals(b + 1, burst.bursts)
        }
        val s = f.snapshot
        val trueOffset = offset0 + drift * now
        assertTrue("offset error ${s.offset - trueOffset} µs", abs(s.offset - trueOffset) < 1_000)
        assertTrue("drift ${s.drift}", abs(s.drift - drift) < 10e-6)
        // converting a server timestamp back lands within a millisecond of the true local time
        val local = now + 500_000
        assertTrue(abs(s.clientTime(server(local)) - local) < 1_000)
    }

    @Test
    fun burstKeepsLowestMaxErrorAndIgnoresStrayReplies() {
        val f = TimeFilter()
        val b = TimeSyncBurst(f, burstSize = 3)
        val t1 = b.start(0)!!
        assertNull(b.start(1)) // one burst at a time
        assertNull(b.onReply(t1 + 1, 0, 0, 0, 0)) // not the probe in flight
        val p2 = b.onReply(t1, 100 + 500, 100 + 510, 2_000, 2_000)!! // max_error ((2000)-(10))/2 = 995
        val p3 = b.onReply(p2, 100 + 2_100, 100 + 2_110, 2_200, 2_200)!! // rtt 190 -> max_error 95
        assertNull(b.onReply(p3, 100 + 2_400, 100 + 2_410, 3_000, 3_000)) // max_error 295; burst over
        assertEquals(1, b.bursts)
        assertEquals(95L, b.lastBestMaxErrorUs)
        assertEquals(TimeFilter.measurement(p2, 2_200, 2_210, 2_200).toDouble(), f.snapshot.offset, 0.0)
    }

    @Test
    fun probeTimeoutAbortsWithoutFeeding() {
        val f = TimeFilter()
        val b = TimeSyncBurst(f, burstSize = 2, probeTimeoutUs = 1_000)
        val t1 = b.start(0)!!
        b.checkTimeout(5_000)
        assertFalse(b.isActive)
        assertNull(b.onReply(t1, 10, 20, 30, 30))
        assertEquals(0, f.snapshot.count)
    }
}
