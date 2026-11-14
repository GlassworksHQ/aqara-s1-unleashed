package sh.edm.s1kiosk.frame

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/** The time zone picker (#212): regions, cities, plain UTC offsets, picked and never typed. */
class TimeZoneChoicesTest {
    private val choices = TimeZoneChoices(TimeZone.getAvailableIDs().toList())
    private val winter = Instant.parse("2026-01-15T13:32:00Z")

    @Test fun regionsThenTheirCities() {
        assertEquals("UTC", choices.regions.last())
        assertEquals(true, "Europe" in choices.regions)
        val europe = choices.zones("Europe")
        assertEquals("Europe/Madrid" to "Madrid", europe.first { it.first == "Europe/Madrid" })
        assertEquals(europe.map { it.second }.sorted(), europe.map { it.second })
        assertEquals("Argentina / Salta", choices.zones("America").first { it.first == "America/Argentina/Salta" }.second)
        assertEquals("Europe", choices.regionOf("Europe/Madrid"))
    }

    @Test fun plainOffsetsAreNamedTheWayTheClockShowsThem() {
        val utc = choices.zones("UTC")
        assertEquals("UTC" to "UTC", utc.first())
        // The tz database's sign is backwards: Etc/GMT-1 is an hour ahead of UTC.
        assertEquals("Etc/GMT-1" to "UTC+01:00", utc.first { it.first == "Etc/GMT-1" })
        assertEquals("Etc/GMT+5" to "UTC−05:00", utc.first { it.first == "Etc/GMT+5" })
        assertEquals("UTC+01:00", TimeZoneChoices.offset("Etc/GMT-1", winter))
        assertEquals("UTC", choices.regionOf("Etc/GMT-1"))
    }

    @Test fun thePagesWords() {
        assertEquals("Europe/Madrid · UTC+01:00 · 14:32 now", TimeZoneChoices.line("Europe/Madrid", winter))
        assertEquals("UTC−05:00", TimeZoneChoices.offset("America/New_York", winter))
        assertEquals("Kept by the panel", TimeZoneChoices.kept("Europe/Madrid: kept by the panel's root helper").first)
        assertEquals("Not kept yet", TimeZoneChoices.kept("now UTC: no zone chosen, so Aqara's reset at boot stays (set Time zone)").first)
        assertEquals("Not kept" to "the root helper refused it", TimeZoneChoices.kept("now UTC: the root helper refused it"))
    }
}
