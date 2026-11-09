package sh.edm.s1kiosk.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableChangeTest {

    private val a = listOf("Default")
    private val b = listOf("Default", "Tablet")
    private val c = listOf("Default", "Tablet", "Energy")

    @Test
    fun sameValueNeverRebuilds() {
        val s = StableChange(a, debounceMs = 10_000, minIntervalMs = 60_000)
        for (t in 0L..100_000L step 2_000) assertNull(s.offer(listOf("Default"), t))
    }

    @Test
    fun changeIsDueOnlyAfterItWasStable() {
        val s = StableChange(a, debounceMs = 10_000, minIntervalMs = 60_000)
        assertNull(s.offer(b, 0))
        assertNull(s.offer(b, 9_999))
        assertEquals(b, s.offer(b, 10_000))
        s.markApplied(b, 10_000)
        assertEquals(b, s.applied())
        assertNull(s.offer(b, 12_000))
    }

    @Test
    fun flappingRestartsTheDebounce() {
        val s = StableChange(a, debounceMs = 10_000, minIntervalMs = 0)
        assertNull(s.offer(b, 0))
        assertNull(s.offer(c, 6_000))
        assertNull(s.offer(b, 12_000)) // changed again: starts over
        assertNull(s.offer(b, 21_000))
        assertEquals(b, s.offer(b, 22_000))
    }

    @Test
    fun revertingToTheAppliedValueCancels() {
        val s = StableChange(a, debounceMs = 10_000, minIntervalMs = 0)
        assertNull(s.offer(b, 0))
        assertNull(s.offer(a, 5_000))
        assertNull(s.offer(b, 8_000))
        assertNull(s.offer(b, 17_000)) // the debounce restarted at 8 s
        assertEquals(b, s.offer(b, 18_000))
    }

    @Test
    fun rebuildsAreRateLimited() {
        val s = StableChange(a, debounceMs = 10_000, minIntervalMs = 60_000)
        assertNull(s.offer(b, 0))
        assertEquals(b, s.offer(b, 10_000))
        s.markApplied(b, 10_000)
        assertNull(s.offer(c, 11_000))
        assertNull(s.offer(c, 30_000)) // stable, but < 60 s since the last rebuild
        assertNull(s.offer(c, 69_999))
        assertEquals(c, s.offer(c, 70_000))
    }
}
