package sh.edm.s1kiosk.timers

import sh.edm.s1kiosk.TimerBoard

/** Words of the timer views (0.4.3, direction B). Pure. */
object TimerTexts {

    /** The four lines of the done screen; "" means the line is not shown. */
    data class DoneTexts(val label: String, val main: String, val sub: String, val rest: String)

    /**
     * A timer's length as an adjective: "7-minute", "1-hour", "45-second", or "1 h 30 min" / "2 min 30 s"
     * when it isn't a whole unit; "" when unknown.
     */
    fun length(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600; val m = seconds / 60 % 60; val s = seconds % 60
        return when {
            seconds < 60 -> "$seconds-second"
            seconds % 3600 == 0 -> "$h-hour"
            seconds < 3600 && s == 0 -> "$m-minute"
            s == 0 -> "$h h $m min"
            h == 0 -> "$m min $s s"
            else -> "$h h $m min $s s"
        }
    }

    /** "A", "A and B", "A, B and C". */
    fun join(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /**
     * The done screen for [done] (oldest first; not empty) while [running] are still going.
     *
     * One timer: "TIMER DONE" / its name ("Timer" if unnamed) / "7-minute timer".
     * Several: "2 TIMERS DONE" / "Eggs and Pasta" / "7-minute and 10-minute timers" (the lengths only
     * for two, and only if both are known). The last line lists up to two running timers.
     */
    fun done(done: List<TimerBoard.Done>, running: List<TimerBoard.Shown>, now: Long): DoneTexts {
        val n = done.size
        val label = if (n <= 1) "Timer done" else "$n timers done"
        val names = done.map { it.name }
        val main = when {
            n <= 1 -> names.firstOrNull()?.ifBlank { "Timer" } ?: "Timer"
            names.all { it.isBlank() } -> "$n timers"
            else -> join(names.map { it.ifBlank { "Timer" } })
        }
        val lengths = done.map { length(it.totalSeconds) }
        val sub = when {
            n == 1 && lengths[0].isNotEmpty() -> "${lengths[0]} timer"
            n == 2 && lengths.all { it.isNotEmpty() } -> "${join(lengths)} timers"
            else -> ""
        }
        return DoneTexts(label, main, sub, rest(running, now))
    }

    /** "Pasta 4:33", "Pasta 4:33  ·  Rice 12:10", "… +2 more"; "" when nothing runs. */
    fun rest(running: List<TimerBoard.Shown>, now: Long): String {
        if (running.isEmpty()) return ""
        val shown = running.take(2).joinToString("  ·  ") { t ->
            "${t.label} ${TimerBoard.format(t.secondsLeft(now))}" + if (t.active) "" else " paused"
        }
        val more = running.size - 2
        return if (more > 0) "$shown  ·  +$more more" else shown
    }

    /** What the ring shows: "8:12", "1:02:15", and in the last 10 s just the seconds ("7"). */
    fun digits(t: TimerBoard.Shown, now: Long): String {
        val left = t.secondsLeft(now)
        return if (t.hot(now)) left.toString() else TimerBoard.format(left)
    }

    /** The tag under a ring: "PASTA", "TIMER", "PASTA · PAUSED" (upper-cased by the view). */
    fun tag(t: TimerBoard.Shown): String = t.label + if (t.active) "" else " · paused"
}
