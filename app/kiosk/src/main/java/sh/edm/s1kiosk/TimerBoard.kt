package sh.edm.s1kiosk

import android.os.SystemClock

/**
 * Voice timers (set via Home Assistant Assist) as absolute deadlines, for the on-screen countdown, and
 * the timers that have just finished, for the "timer done" screen. Written from the ESPHome thread,
 * read from the UI thread. Times are elapsedRealtime milliseconds; every function takes `now` so the
 * logic is testable on the JVM.
 */
object TimerBoard {
    /** One timer as Home Assistant reports it (VoiceAssistantTimerEventResponse). */
    data class Entry(val id: String, val name: String, val totalSeconds: Int, val secondsLeft: Int, val active: Boolean)

    /** A running or paused timer: [name] as HA sent it ("" when unnamed). */
    data class Shown(
        val id: String,
        val name: String,
        val endsAt: Long,
        val active: Boolean,
        val pausedLeft: Int,
        val totalSeconds: Int = 0,
    ) {
        /** What the screen calls it ("Timer" when unnamed). */
        val label: String get() = name.ifBlank { "Timer" }

        /** Whole seconds left, rounded up while running (so 0:00 shows only at the very end). */
        fun secondsLeft(now: Long): Int =
            if (active) ((endsAt - now + 999) / 1000).toInt().coerceAtLeast(0) else pausedLeft.coerceAtLeast(0)

        /** Fraction of the timer still to run, 0..1 (1 when the total is unknown). */
        fun remaining(now: Long): Float {
            if (totalSeconds <= 0) return 1f
            val ms = if (active) (endsAt - now).toFloat() else pausedLeft * 1000f
            return (ms / (totalSeconds * 1000f)).coerceIn(0f, 1f)
        }

        /** The last 10 seconds of a running timer: the countdown warms up, and hidden timers come back. */
        fun hot(now: Long): Boolean = active && secondsLeft(now) <= HOT_SECONDS
    }

    /** A finished timer: [totalSeconds] is 0 when it wasn't known. */
    data class Done(val name: String, val totalSeconds: Int, val at: Long)

    const val HOT_SECONDS = 10

    /** The done screen closes by itself this long after the last timer finished (as before 0.4.3). */
    const val DONE_HOLD_MS = 60_000L

    /** Soonest first. */
    @Volatile var timers: List<Shown> = emptyList(); private set

    /** Finished timers not yet dismissed, oldest first. */
    @Volatile var done: List<Done> = emptyList(); private set

    /**
     * Called (on the thread that dismissed, the main thread for a tap) when the done screen is
     * dismissed, so the remaining chimes can stop.
     */
    @Volatile var onDismissed: (() -> Unit)? = null

    fun update(list: List<Entry>, now: Long = SystemClock.elapsedRealtime()) {
        timers = list.map {
            Shown(it.id, it.name.trim(), now + it.secondsLeft * 1000L, it.active, it.secondsLeft, it.totalSeconds)
        }.sortedWith(compareBy({ it.secondsLeft(now) }, { it.id }))
    }

    /**
     * A timer finished. HA's FINISHED event removes it from the list after this runs, so its length is
     * still in [timers]; with two timers of the same name, the one closest to its end is the one.
     */
    @Synchronized
    fun finished(name: String, now: Long = SystemClock.elapsedRealtime()) {
        val n = name.trim()
        val match = timers.filter { it.name == n }.minByOrNull { it.secondsLeft(now) }
        done = done + Done(n, match?.totalSeconds ?: 0, now)
    }

    /** Clears the done list (tap, or [expire]); tells [onDismissed] if there was anything to clear. */
    fun dismissFinished() {
        val had = synchronized(this) { done.isNotEmpty().also { done = emptyList() } }
        if (had) onDismissed?.invoke()
    }

    /** Ends the done screen [DONE_HOLD_MS] after the last finish; true if it did. */
    fun expire(now: Long = SystemClock.elapsedRealtime()): Boolean {
        val last = done.lastOrNull() ?: return false
        if (now - last.at < DONE_HOLD_MS) return false
        dismissFinished()
        return true
    }

    fun format(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Test hook: back to the start state. */
    internal fun reset() {
        timers = emptyList()
        done = emptyList()
        onDismissed = null
    }
}
