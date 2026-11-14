package sh.edm.s1kiosk.frame

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The time zone picker's lists and words (#212): regions, then their cities, and plain UTC offsets.
 * Picked, never typed. Pure, for tests: [ids] are the zones the panel knows.
 */
class TimeZoneChoices(
    ids: Collection<String>,
    /** A zone's current name for an old alias (`Europe/Belfast` is `Europe/London`); the app's is ICU's. */
    private val canonical: (String) -> String = { it },
) {
    private val known = ids.toSet()

    /** The regions that have zones here, in [REGIONS] order, then [UTC] for the plain offsets. */
    val regions: List<String> = REGIONS.filter { r -> known.any { it.startsWith("$r/") } } + UTC

    /**
     * A region's zones as (id, name), by name: `Europe/Madrid` is `Madrid`, `America/Argentina/Salta` is
     * `Argentina / Salta`. An old alias of a zone listed here is left out (Asia/Calcutta for Asia/Kolkata).
     */
    fun zones(region: String): List<Pair<String, String>> =
        if (region == UTC) offsets()
        else known.filter { it.startsWith("$region/") && (canonical(it) == it || canonical(it) !in known) }
            .map { it to it.removePrefix("$region/").replace('_', ' ').replace("/", " / ") }.sortedBy { it.second }

    /** A zone by its current name, when Android knows one (`Asia/Calcutta` is `Asia/Kolkata`). */
    fun name(zone: String): String = canonical(zone)

    /**
     * UTC, then whole-hour offsets from −12 to +14. The tz database names them backwards (`Etc/GMT-1` is
     * UTC+01:00), so the name says the offset the clock shows.
     */
    private fun offsets(): List<Pair<String, String>> = listOfNotNull("UTC".takeIf { it in known }?.let { it to "UTC" }) +
        (-12..14).filter { it != 0 }.map { h -> "Etc/GMT${if (h > 0) "-" else "+"}${kotlin.math.abs(h)}" to "UTC${sign(h)}${pad(kotlin.math.abs(h))}:00" }
            .filter { it.first in known }

    /** The region a zone is listed under: its first part, or [UTC] for the plain ones. */
    fun regionOf(zone: String): String = regions.firstOrNull { zone.startsWith("$it/") } ?: UTC

    /**
     * Home Assistant's time zone, from the POSIX rule it sends ([PosixTimeZone]), against the panel's
     * [current] zone. Null when the rule can't be read. Slow (a year of offsets for each candidate): call
     * it off the main thread.
     *
     * - [HomeAssistantZone.Same]: the panel's zone keeps the same clock all year.
     * - [HomeAssistantZone.Like]: another zone does; "Use it" keeps it, one in the panel's region first.
     * - [HomeAssistantZone.Elsewhere]: HA's rule is one fixed offset that the panel's zone doesn't keep.
     *   HA sends such a rule also for a zone the rule can't describe (Casablanca's comes as `<+00>0`, an hour
     *   off most of the year), so no zone is offered: the panel keeps its own (#235 verifier, N3).
     */
    fun homeAssistant(rule: String, current: String, now: Instant): HomeAssistantZone? {
        val tz = PosixTimeZone.parse(rule) ?: return null
        val like = tz.zonesLike(known, now).map(canonical).distinct()
        // The panel's zone may be an old alias of one in the list (Europe/Belfast is Europe/London).
        val here = canonical(current)
        if (here in like) return HomeAssistantZone.Same(here)
        if (tz.fixed || like.isEmpty()) return HomeAssistantZone.Elsewhere(rule)
        val pick = like.minWith(
            compareBy<String>({ regionOf(it) != regionOf(here) }, { REGIONS.indexOf(regionOf(it)).let { i -> if (i < 0) REGIONS.size else i } }, { it }),
        )
        return HomeAssistantZone.Like(pick)
    }

    /** See [homeAssistant]. Each says its line at a given time, so the offset follows summer time. */
    sealed interface HomeAssistantZone {
        fun line(now: Instant): String

        data class Same(val zone: String) : HomeAssistantZone {
            override fun line(now: Instant) = "${offset(zone, now)} · the same as the panel's"
        }

        data class Like(val zone: String) : HomeAssistantZone {
            override fun line(now: Instant) = "${offset(zone, now)} · like $zone, not the panel's"
        }

        /** [rule] is kept, not one offset: a rule with summer time no zone here matches still changes its offset. */
        data class Elsewhere(val rule: String) : HomeAssistantZone {
            fun offset(now: Instant): String = offsetText(PosixTimeZone.parse(rule)?.offsetAt(now)?.totalSeconds ?: 0)
            override fun line(now: Instant) = "${offset(now)} · Home Assistant's clock; the panel keeps its zone"
        }
    }

    companion object {
        const val UTC = "UTC"
        val REGIONS = listOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

        /** `UTC+01:00` at [now]. */
        fun offset(zone: String, now: Instant): String =
            offsetText(runCatching { ZoneId.of(zone).rules.getOffset(now).totalSeconds }.getOrDefault(0))

        /** [seconds] east of UTC as `UTC+05:30`. */
        fun offsetText(seconds: Int): String {
            val m = kotlin.math.abs(seconds) / 60
            return "UTC${if (seconds < 0) "−" else "+"}${pad(m / 60)}:${pad(m % 60)}"
        }

        /** The time on the zone's clocks at [now]: `14:32`. */
        fun clock(zone: String, now: Instant): String = runCatching {
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(now.atZone(ZoneId.of(zone)))
        }.getOrDefault("")

        /** The page's line for a zone: `Europe/Madrid · UTC+01:00 · 14:32 now`. */
        fun line(zone: String, now: Instant) = "$zone · ${offset(zone, now)} · ${clock(zone, now)} now"

        /**
         * "Kept by the panel" and the line under it, from the keeper's status ([TimeZoneKeeper.statusText]),
         * said for a person.
         */
        fun kept(status: String): Pair<String, String> = when {
            status.endsWith("kept by the panel's root helper") -> "Kept by the panel" to "Aqara's settings set it back at every start. The panel puts it back."
            "no zone chosen" in status -> "Not kept yet" to "Pick a zone, and the panel puts it back each time Aqara's settings reset it."
            "needs the root helper" in status -> "Not kept" to "Keeping a zone needs the root helper. Update the panel's boot image once."
            status.startsWith("now ") && "waiting" in status -> "Waiting for the root helper" to "The panel keeps the zone once its root helper answers."
            status.isBlank() -> "Starting" to "The panel reads the zone it keeps."
            else -> "Not kept" to status.substringAfter(": ")
        }

        private fun sign(h: Int) = if (h < 0) "−" else "+"
        private fun pad(n: Int) = n.toString().padStart(2, '0')
    }
}
