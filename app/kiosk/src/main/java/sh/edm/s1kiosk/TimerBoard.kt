package sh.edm.s1kiosk

import android.os.SystemClock

/**
 * Voice timers (set via Home Assistant Assist) as absolute deadlines, for the on-screen countdown.
 * Written from the ESPHome thread, read from the UI thread.
 */
object TimerBoard {
    data class Entry(val id: String, val name: String, val totalSeconds: Int, val secondsLeft: Int, val active: Boolean)

    data class Shown(val id: String, val label: String, val endsAt: Long, val active: Boolean, val pausedLeft: Int)

    @Volatile var timers: List<Shown> = emptyList(); private set

    /** Name of the last finished timer and when it finished (elapsedRealtime), for the "done" banner. */
    @Volatile var lastFinished: Pair<String, Long>? = null; private set

    fun update(list: List<Entry>) {
        val now = SystemClock.elapsedRealtime()
        timers = list.map {
            Shown(it.id, it.name.ifBlank { "Timer" }, now + it.secondsLeft * 1000L, it.active, it.secondsLeft)
        }
    }

    fun finished(name: String) {
        lastFinished = name.ifBlank { "Timer" } to SystemClock.elapsedRealtime()
    }

    fun dismissFinished() { lastFinished = null }

    fun format(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
