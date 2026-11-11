package sh.edm.s1kiosk.timers

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.edm.s1kiosk.TimerBoard
import sh.edm.s1kiosk.timers.TimerTexts.DoneTexts

class TimerTextsTest {
    private val now = 50_000L
    private fun done(name: String, total: Int, at: Long = now) = TimerBoard.Done(name, total, at)
    private fun running(id: String, name: String, left: Int, active: Boolean = true) =
        TimerBoard.Shown(id, name, now + left * 1000L, active, left, 600)

    @Test
    fun lengths() {
        assertEquals("", TimerTexts.length(0))
        assertEquals("45-second", TimerTexts.length(45))
        assertEquals("1-minute", TimerTexts.length(60))
        assertEquals("7-minute", TimerTexts.length(420))
        assertEquals("1-hour", TimerTexts.length(3600))
        assertEquals("2-hour", TimerTexts.length(7200))
        assertEquals("1 h 30 min", TimerTexts.length(5400))
        assertEquals("2 min 30 s", TimerTexts.length(150))
        assertEquals("1 h 0 min 5 s", TimerTexts.length(3605))
    }

    @Test
    fun joins() {
        assertEquals("", TimerTexts.join(emptyList()))
        assertEquals("Eggs", TimerTexts.join(listOf("Eggs")))
        assertEquals("Eggs and Pasta", TimerTexts.join(listOf("Eggs", "Pasta")))
        assertEquals("Eggs, Pasta and Rice", TimerTexts.join(listOf("Eggs", "Pasta", "Rice")))
    }

    @Test
    fun oneTimerDone() {
        assertEquals(DoneTexts("Timer done", "Eggs", "7-minute timer", "Pasta 4:33"),
            TimerTexts.done(listOf(done("Eggs", 420)), listOf(running("p", "Pasta", 273)), now))
        assertEquals(DoneTexts("Timer done", "Timer", "10-minute timer", ""), TimerTexts.done(listOf(done("", 600)), emptyList(), now))
        assertEquals(DoneTexts("Timer done", "Rice", "", ""), TimerTexts.done(listOf(done("Rice", 0)), emptyList(), now))
    }

    @Test
    fun severalTimersDone() {
        assertEquals(DoneTexts("2 timers done", "Eggs and Pasta", "7-minute and 10-minute timers", ""),
            TimerTexts.done(listOf(done("Eggs", 420), done("Pasta", 600)), emptyList(), now))
        // A length unknown: no lengths line.
        assertEquals("", TimerTexts.done(listOf(done("Eggs", 420), done("Pasta", 0)), emptyList(), now).sub)
        // Three: names only.
        assertEquals(DoneTexts("3 timers done", "Eggs, Timer and Rice", "", ""),
            TimerTexts.done(listOf(done("Eggs", 420), done("", 60), done("Rice", 900)), emptyList(), now))
        // None named.
        assertEquals("2 timers", TimerTexts.done(listOf(done("", 60), done("", 120)), emptyList(), now).main)
    }

    @Test
    fun stillRunningLine() {
        assertEquals("", TimerTexts.rest(emptyList(), now))
        assertEquals("Pasta 4:33  ·  Timer 1:00:00 paused",
            TimerTexts.rest(listOf(running("p", "Pasta", 273), running("t", "", 3600, active = false)), now))
        assertEquals("A 0:10  ·  B 0:20  ·  +2 more",
            TimerTexts.rest(listOf(running("a", "A", 10), running("b", "B", 20), running("c", "C", 30), running("d", "D", 40)), now))
    }

    @Test
    fun ringDigitsAndTags() {
        assertEquals("8:12", TimerTexts.digits(running("p", "Pasta", 492), now))
        assertEquals("7", TimerTexts.digits(running("e", "Eggs", 7), now))
        assertEquals("0:07", TimerTexts.digits(running("e", "Eggs", 7, active = false), now))
        assertEquals("Pasta", TimerTexts.tag(running("p", "Pasta", 492)))
        assertEquals("Timer · paused", TimerTexts.tag(running("t", "", 30, active = false)))
    }
}
