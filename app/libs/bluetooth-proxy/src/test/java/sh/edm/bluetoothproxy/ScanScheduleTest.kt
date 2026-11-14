package sh.edm.bluetoothproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.bluetoothproxy.ScanSchedule.Action

class ScanScheduleTest {
    private val s = ScanSchedule()

    /** Runs the schedule as the engine does, from [from] to [to]: at every event time and every wake it asks for. */
    private fun run(from: Long, to: Long, events: Map<Long, () -> Unit> = emptyMap()): List<Pair<Long, Action>> {
        val out = ArrayList<Pair<Long, Action>>()
        var wake: Long? = null
        var now = from
        val times = events.keys.sorted().toMutableList()
        while (true) {
            val next = listOfNotNull(times.firstOrNull(), wake).minOrNull() ?: break
            if (next > to) break
            now = maxOf(now, next)
            if (times.firstOrNull() == next) events.getValue(times.removeAt(0)).invoke()
            val step = s.step(now)
            if (step.action != Action.NONE) out += now to step.action
            wake = step.wakeAt
        }
        return out
    }

    @Test fun noScanUntilSomeoneSubscribes() {
        assertEquals(Action.NONE, s.step(0).action)
        assertEquals(null, s.step(0).wakeAt)
        s.setSubscribed(true, 1_000)
        assertEquals(Action.START, s.step(1_000).action)
    }

    @Test fun scanningLingersAfterTheSubscriberLeavesThenStops() {
        val actions = run(0, 120_000, mapOf(0L to { s.setSubscribed(true, 0) }, 10_000L to { s.setSubscribed(false, 10_000) }))
        assertEquals(listOf(0L to Action.START, 25_000L to Action.STOP), actions)
    }

    @Test fun aQuickResubscribeKeepsTheSameScan() {
        val actions = run(
            0, 120_000,
            mapOf(0L to { s.setSubscribed(true, 0) }, 10_000L to { s.setSubscribed(false, 10_000) }, 12_000L to { s.setSubscribed(true, 12_000) }),
        )
        assertEquals(listOf(0L to Action.START), actions)
    }

    @Test fun theScanRestartsBeforeAndroidMakesItOpportunistic() {
        val actions = run(0, 60 * 60_000L, mapOf(0L to { s.setSubscribed(true, 0) }))
        assertEquals(listOf(0L to Action.START, 25 * 60_000L to Action.RESTART, 50 * 60_000L to Action.RESTART), actions)
    }

    @Test fun aFlappingSubscriberNeverMakesAndroidRefuse() {
        val limits = ScanSchedule.Limits(lingerMs = 0)
        val s = ScanSchedule(limits)
        val starts = ArrayList<Long>()
        // Home Assistant reconnecting every second for two minutes.
        for (t in 0L until 120_000L step 500) {
            s.setSubscribed(t % 1_000 == 0L, t)
            val a = s.step(t).action
            if (a == Action.START || a == Action.RESTART) starts += t
        }
        for (t in starts) {
            val inWindow = starts.count { it in (t - limits.windowMs + 1)..t }
            assertTrue("$inWindow starts in 30 s at $t", inWindow <= 4)
        }
        assertTrue("it still scans: ${starts.size} starts", starts.size >= 12)
    }

    @Test fun failuresBackOffAndAWorkingScanResetsThem() {
        s.setSubscribed(true, 0)
        assertEquals(Action.START, s.step(0).action)
        s.onFailed(1_000)
        assertEquals(Step(Action.NONE, 31_000), s.step(1_000))
        assertEquals(Action.START, s.step(31_000).action)
        s.onFailed(32_000)
        assertEquals("doubled", 92_000L, s.step(32_000).wakeAt)
        assertEquals(Action.START, s.step(92_000).action)
        s.onAdvertisement()
        s.onFailed(100_000)
        assertEquals("reset by a working scan", 130_000L, s.step(100_000).wakeAt)

        repeat(10) { s.onFailed(200_000) }
        assertEquals("capped", 500_000L, s.step(200_000).wakeAt)
    }

    private fun Step(action: Action, wakeAt: Long?) = ScanSchedule.Step(action, wakeAt)
}
