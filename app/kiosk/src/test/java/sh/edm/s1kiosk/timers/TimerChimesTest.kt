package sh.edm.s1kiosk.timers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

class TimerChimesTest {
    /** A fake executor: records what's scheduled; [runAll] fires whatever wasn't cancelled. */
    private class Fake {
        val jobs = ArrayList<Triple<Long, () -> Unit, CompletableFuture<Unit>>>()
        var played = 0
        var silenced = 0
        val chimes = TimerChimes(
            schedule = { ms, action -> CompletableFuture<Unit>().also { jobs += Triple(ms, action, it) } as Future<*> },
            play = { played++ },
            silence = { silenced++ },
        )
        fun runAll() = jobs.filter { !it.third.isCancelled }.forEach { it.second() }
    }

    @Test
    fun threeChimesThreeSecondsApart() {
        val f = Fake()
        f.chimes.ring()
        assertEquals(1, f.played)
        assertEquals(listOf(3_000L, 6_000L), f.jobs.map { it.first })
        f.runAll()
        assertEquals(3, f.played)
    }

    @Test
    fun dismissingStopsTheChimesStillToCome() {
        val f = Fake()
        f.chimes.ring()
        f.chimes.stop()
        assertTrue(f.jobs.all { it.third.isCancelled })
        assertEquals(1, f.silenced)
        f.runAll()
        assertEquals(1, f.played)
    }

    @Test
    fun aSecondTimerRestartsTheSeries() {
        val f = Fake()
        f.chimes.ring()
        f.chimes.ring()
        assertEquals(2, f.played)
        assertEquals(2, f.jobs.count { !it.third.isCancelled })
        f.runAll()
        assertEquals(4, f.played)
    }
}
