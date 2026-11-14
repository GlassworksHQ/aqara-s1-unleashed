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

    /** A region's zones as (id, name), by name: `Europe/Madrid` is `Madrid`, `America/Argentina/Salta` is `Argentina / Salta`. */
    fun zones(region: String): List<Pair<String, String>> =
        if (region == UTC) offsets()
        else known.filter { it.startsWith("$region/") }.map { it to it.removePrefix("$region/").replace('_', ' ').replace("/", " / ") }.sortedBy { it.second }

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
     * Home Assistant's time zone, from the POSIX rule it sends ([PosixTimeZone]): whether the panel's
     * [current] zone keeps the same clock all year, and the zone "Use it" keeps, one in [current]'s region
     * first. Null when the rule can't be read or no zone here keeps its clock. Its offset changes with
     * summer time, so it is read from [HomeAssistantZone.zone] when it is shown ([offset]).
     */
    fun homeAssistant(rule: String, current: String, now: Instant): HomeAssistantZone? {
        val like = PosixTimeZone.parse(rule)?.zonesLike(known, now).orEmpty().map(canonical).distinct()
        if (like.isEmpty()) return null
        val pick = if (current in like) current else like.minWith(
            compareBy<String>({ regionOf(it) != regionOf(current) }, { REGIONS.indexOf(regionOf(it)).let { i -> if (i < 0) REGIONS.size else i } }, { it }),
        )
        return HomeAssistantZone(current in like, pick)
    }

    /** See [homeAssistant]. */
    data class HomeAssistantZone(val same: Boolean, val zone: String) {
        /** The page's line at [now]: `UTC+01:00 · the same as the panel's`. The offset is read then, so it follows summer time. */
        fun line(now: Instant): String {
            val o = offset(zone, now)
            return if (same) "$o · the same as the panel's" else "$o · like $zone, not the panel's"
        }
    }

    companion object {
        const val UTC = "UTC"
        val REGIONS = listOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

        /** `UTC+01:00` at [now]. */
        fun offset(zone: String, now: Instant): String {
            val s = runCatching { ZoneId.of(zone).rules.getOffset(now).totalSeconds }.getOrDefault(0)
            val m = kotlin.math.abs(s) / 60
            return "UTC${if (s < 0) "−" else "+"}${pad(m / 60)}:${pad(m % 60)}"
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
