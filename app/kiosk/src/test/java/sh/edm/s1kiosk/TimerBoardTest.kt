package sh.edm.s1kiosk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.edm.s1kiosk.TimerBoard.Entry

class TimerBoardTest {
    private val t0 = 1_000_000L

    @Before fun setUp() = TimerBoard.reset()
    @After fun tearDown() = TimerBoard.reset()

    @Test
    fun soonestFirstAndSecondsRoundUp() {
        TimerBoard.update(listOf(Entry("a", "Pasta", 600, 492, true), Entry("b", " Eggs ", 420, 220, true), Entry("c", "", 3600, 2691, false)), t0)
        assertEquals(listOf("b", "a", "c"), TimerBoard.timers.map { it.id })
        val eggs = TimerBoard.timers[0]
        assertEquals("Eggs", eggs.name)
        assertEquals(220, eggs.secondsLeft(t0))
        assertEquals(220, eggs.secondsLeft(t0 + 1)) // 219.999 s shows as 220
        assertEquals(219, eggs.secondsLeft(t0 + 1000))
        assertEquals(0, eggs.secondsLeft(t0 + 999_999))
        assertEquals("Timer", TimerBoard.timers[2].label)
    }

    @Test
    fun pausedTimersKeepTheirTimeAndNeverWarmUp() {
        TimerBoard.update(listOf(Entry("p", "Pasta", 600, 5, false)), t0)
        val p = TimerBoard.timers.single()
        assertEquals(5, p.secondsLeft(t0 + 60_000))
        assertFalse(p.hot(t0))
        assertEquals(5f / 600f, p.remaining(t0 + 60_000), 1e-6f)
    }

    @Test
    fun lastTenSecondsAreHot() {
        TimerBoard.update(listOf(Entry("a", "Eggs", 420, 11, true)), t0)
        val e = TimerBoard.timers.single()
        assertFalse(e.hot(t0))
        assertTrue(e.hot(t0 + 1_000))
        assertTrue(e.hot(t0 + 11_000))
    }

    @Test
    fun remainingIsAFractionOrFullWhenTheTotalIsUnknown() {
        TimerBoard.update(listOf(Entry("a", "", 600, 300, true), Entry("b", "", 0, 30, true)), t0)
        val byId = TimerBoard.timers.associateBy { it.id }
        assertEquals(0.5f, byId.getValue("a").remaining(t0), 1e-6f)
        assertEquals(1f, byId.getValue("b").remaining(t0), 0f)
        assertEquals(0f, byId.getValue("a").remaining(t0 + 400_000), 0f)
    }

    @Test
    fun finishedTakesTheLengthFromTheLastList() {
        TimerBoard.update(listOf(Entry("a", "Pasta", 600, 0, true), Entry("b", "Pasta", 900, 300, true), Entry("c", "Eggs", 420, 100, true)), t0)
        TimerBoard.finished(" Pasta", t0)
        TimerBoard.finished("Rice", t0 + 5) // not in the list: length unknown
        assertEquals(listOf(TimerBoard.Done("Pasta", 600, t0), TimerBoard.Done("Rice", 0, t0 + 5)), TimerBoard.done)
    }

    @Test
    fun dismissClearsAndTellsOnlyWhenSomethingWasShown() {
        var calls = 0
        TimerBoard.onDismissed = { calls++ }
        TimerBoard.dismissFinished()
        assertEquals(0, calls)
        TimerBoard.finished("Eggs", t0)
        TimerBoard.finished("Pasta", t0 + 10)
        TimerBoard.dismissFinished()
        assertEquals(1, calls)
        assertTrue(TimerBoard.done.isEmpty())
    }

    @Test
    fun theDoneScreenEndsAMinuteAfterTheLastFinish() {
        TimerBoard.finished("Eggs", t0)
        TimerBoard.finished("Pasta", t0 + 30_000)
        assertFalse(TimerBoard.expire(t0 + 60_000))
        assertFalse(TimerBoard.expire(t0 + 89_999))
        assertTrue(TimerBoard.expire(t0 + 90_000))
        assertTrue(TimerBoard.done.isEmpty())
        assertFalse(TimerBoard.expire(t0 + 200_000))
    }

    @Test
    fun formats() {
        assertEquals("0:07", TimerBoard.format(7))
        assertEquals("10:00", TimerBoard.format(600))
        assertEquals("1:02:15", TimerBoard.format(3735))
        assertEquals("0:00", TimerBoard.format(-3))
    }
}
