package sh.edm.s1kiosk.frame

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * A POSIX `TZ` rule, as Home Assistant sends its time zone to an ESPHome device
 * (`GetTimeResponse.timezone`, e.g. `CET-1CEST,M3.5.0,M10.5.0/3`). aioesphomeapi turns HA's IANA zone
 * into this before it sends it (verified 2026-11-13 with aioesphomeapi 46.25.0: `Europe/Madrid` arrived
 * as `CET-1CEST,M3.5.0,M10.5.0/3`). The panel keeps IANA zones, so [zonesLike] finds the ones whose
 * clocks agree with it.
 */
class PosixTimeZone private constructor(
    /** Seconds east of UTC, in standard time. */
    private val std: Int,
    /** Seconds east of UTC in summer time, or null for none. */
    private val dst: Int?,
    private val start: Rule?,
    private val end: Rule?,
) {
    /** When summer time starts or ends: a day of the year and the local time, in seconds after midnight. */
    private class Rule(val day: (Int) -> LocalDate, val seconds: Int)

    /** The offset the rule's clocks show at [t]. */
    fun offsetAt(t: Instant): ZoneOffset {
        if (dst == null || start == null || end == null) return ZoneOffset.ofTotalSeconds(std)
        val year = t.atOffset(ZoneOffset.ofTotalSeconds(std)).year
        val on = start.day(year).atStartOfDay().toEpochSecond(ZoneOffset.ofTotalSeconds(std)) + start.seconds
        val off = end.day(year).atStartOfDay().toEpochSecond(ZoneOffset.ofTotalSeconds(dst)) + end.seconds
        val s = t.epochSecond
        val summer = if (on < off) s in on until off else s >= on || s < off
        return ZoneOffset.ofTotalSeconds(if (summer) dst else std)
    }

    /**
     * The zones in [ids] whose clocks show what this rule's do at noon UTC on every day of the year from
     * [from]. Zones that differ only in the hour of a change are alike enough to pick from.
     */
    fun zonesLike(ids: Collection<String>, from: Instant): List<String> {
        val noon = from.atOffset(ZoneOffset.UTC).toLocalDate().atTime(12, 0).toInstant(ZoneOffset.UTC)
        val days = (0 until 366).map { noon.plusSeconds(it * 86_400L) }
        val want = days.map { offsetAt(it) }
        // Today's offset first: it leaves a few dozen zones of the panel's ~600 for the whole year.
        return ids.mapNotNull { id -> runCatching { ZoneId.of(id).rules }.getOrNull()?.let { id to it } }
            .filter { (_, rules) -> rules.getOffset(days[0]) == want[0] }
            .filter { (_, rules) -> days.indices.all { rules.getOffset(days[it]) == want[it] } }
            .map { it.first }
    }

    companion object {
        /** The widest offset a clock can have (`ZoneOffset`'s range): a rule past it is not a zone. */
        private const val MAX_OFFSET_S = 18 * 3600

        /** [tz] parsed, or null when it isn't a rule this understands. */
        fun parse(tz: String): PosixTimeZone? = runCatching { Parser(tz.trim()).zone() }.getOrNull()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun zone(): PosixTimeZone {
            name()
            val std = -offset()
            require(kotlin.math.abs(std) <= MAX_OFFSET_S)
            if (i == s.length) return PosixTimeZone(std, null, null, null)
            name()
            val dst = if (i < s.length && s[i] != ',') -offset() else std + 3600
            require(kotlin.math.abs(dst) <= MAX_OFFSET_S)
            expect(',')
            val start = rule()
            expect(',')
            val end = rule()
            require(i == s.length)
            return PosixTimeZone(std, dst, start, end)
        }

        /** `CET`, or `<+0530>` quoted. */
        private fun name() {
            if (s.getOrNull(i) == '<') { i = s.indexOf('>', i) + 1; require(i > 0); return }
            val from = i
            while (i < s.length && s[i].isLetter()) i++
            require(i - from >= 3)
        }

        /** `[+-]hh[:mm[:ss]]` in seconds, as written (POSIX counts west of UTC as positive). */
        private fun offset(): Int {
            val sign = when (s.getOrNull(i)) { '-' -> { i++; -1 }; '+' -> { i++; 1 }; else -> 1 }
            var total = number() * 3600
            if (s.getOrNull(i) == ':') { i++; total += number() * 60 }
            if (s.getOrNull(i) == ':') { i++; total += number() }
            return sign * total
        }

        /** `Mm.w.d`, `Jn` or `n`, then `/time` (02:00 when left out). */
        private fun rule(): Rule {
            val day: (Int) -> LocalDate = when (s.getOrNull(i)) {
                'M' -> { i++; monthWeekDay() }
                'J' -> {
                    i++
                    val n = number()
                    require(n in 1..365)
                    // Day n of 365: February 29 never counts.
                    val f = { y: Int -> LocalDate.of(y, 1, 1).plusDays((n - 1).toLong()).let { if (it.isLeapYear && n >= 60) it.plusDays(1) else it } }
                    f
                }
                else -> {
                    val n = number()
                    require(n in 0..365)
                    val f = { y: Int -> LocalDate.of(y, 1, 1).plusDays(n.toLong()) }
                    f
                }
            }
            val seconds = if (s.getOrNull(i) == '/') { i++; offset() } else 7200
            return Rule(day, seconds)
        }

        /** `m.w.d`: day `d` (0 is Sunday) of week `w` (5 is the last) of month `m`. */
        private fun monthWeekDay(): (Int) -> LocalDate {
            val m = number(); expect('.'); val w = number(); expect('.'); val d = number()
            require(m in 1..12 && w in 1..5 && d in 0..6)
            val dow = DayOfWeek.of(if (d == 0) 7 else d)
            return { y ->
                val first = LocalDate.of(y, m, 1)
                if (w == 5) first.with(TemporalAdjusters.lastInMonth(dow)) else first.with(TemporalAdjusters.dayOfWeekInMonth(w, dow))
            }
        }

        private fun number(): Int {
            val from = i
            while (i < s.length && s[i].isDigit()) i++
            require(i > from)
            return s.substring(from, i).toInt()
        }

        private fun expect(c: Char) { require(s.getOrNull(i) == c); i++ }
    }
}
