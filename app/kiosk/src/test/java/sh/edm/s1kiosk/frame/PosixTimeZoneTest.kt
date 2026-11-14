package sh.edm.s1kiosk.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Home Assistant's zone arrives as a POSIX rule (aioesphomeapi 46.25.0 sent `CET-1CEST,M3.5.0,M10.5.0/3`
 * for Europe/Madrid, 2026-11-13). The panel finds the zones it knows that keep the same clock.
 */
class PosixTimeZoneTest {
    private val ids = TimeZone.getAvailableIDs().toList()
    private val from = Instant.parse("2026-11-13T12:00:00Z")

    @Test fun summerTimeStartsAndEndsWhenTheRuleSays() {
        val cet = PosixTimeZone.parse("CET-1CEST,M3.5.0,M10.5.0/3")!!
        assertEquals(ZoneOffset.ofHours(1), cet.offsetAt(Instant.parse("2026-03-29T00:59:59Z")))
        assertEquals("last Sunday of March, 02:00 local", ZoneOffset.ofHours(2), cet.offsetAt(Instant.parse("2026-03-29T01:00:00Z")))
        assertEquals(ZoneOffset.ofHours(2), cet.offsetAt(Instant.parse("2026-10-25T00:59:59Z")))
        assertEquals("last Sunday of October, 03:00 summer time", ZoneOffset.ofHours(1), cet.offsetAt(Instant.parse("2026-10-25T01:00:00Z")))
        // The southern hemisphere: summer time spans the new year.
        val sydney = PosixTimeZone.parse("AEST-10AEDT,M10.1.0,M4.1.0/3")!!
        assertEquals(ZoneOffset.ofHours(11), sydney.offsetAt(Instant.parse("2026-01-15T00:00:00Z")))
        assertEquals(ZoneOffset.ofHours(10), sydney.offsetAt(Instant.parse("2026-06-15T00:00:00Z")))
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), PosixTimeZone.parse("<+0530>-5:30")!!.offsetAt(from))
    }

    @Test fun theZonesThatKeepTheSameClock() {
        val like = PosixTimeZone.parse("CET-1CEST,M3.5.0,M10.5.0/3")!!.zonesLike(ids, from)
        assertTrue(like.containsAll(listOf("Europe/Madrid", "Europe/Paris", "Europe/Berlin")))
        assertTrue("no summer time there", "Africa/Lagos" !in like)
        assertTrue("America/New_York" in PosixTimeZone.parse("EST5EDT,M3.2.0,M11.1.0")!!.zonesLike(ids, from))
        assertTrue("UTC" in PosixTimeZone.parse("UTC0")!!.zonesLike(ids, from))
        // CodeRabbit on #235: the days are compared at noon UTC, whatever time [from] has. At 01:30 UTC on the
        // last Sunday of March this rule (summer from 03:00) and Madrid's (from 02:00) differ; at noon they agree.
        val lateChange = PosixTimeZone.parse("CET-1CEST,M3.5.0/3,M10.5.0/3")!!
        assertTrue("Europe/Madrid" in lateChange.zonesLike(listOf("Europe/Madrid"), Instant.parse("2026-03-29T01:30:00Z")))
    }

    @Test fun whatIsntARuleIsNull() {
        assertNull(PosixTimeZone.parse("Europe/Madrid"))
        assertNull(PosixTimeZone.parse(""))
        assertNull(PosixTimeZone.parse("CET-1CEST,M13.5.0,M10.5.0"))
        // CodeRabbit on #235: these parsed, then threw when matched. No clock is 25 h off UTC.
        assertNull(PosixTimeZone.parse("UTC25"))
        assertNull(PosixTimeZone.parse("CET-1CEST-20,M3.5.0,M10.5.0/3"))
        assertNull(TimeZoneChoices(ids).homeAssistant("UTC25", "Europe/Madrid", from))
    }

    @Test fun usingItKeepsAZoneInThePanelsRegion() {
        val choices = TimeZoneChoices(ids)
        val same = choices.homeAssistant("CET-1CEST,M3.5.0,M10.5.0/3", "Europe/Madrid", from)!!
        assertEquals(TimeZoneChoices.HomeAssistantZone.Same("Europe/Madrid"), same)
        val other = choices.homeAssistant("GMT0BST,M3.5.0/1,M10.5.0", "Europe/Madrid", from) as TimeZoneChoices.HomeAssistantZone.Like
        assertEquals("Europe", choices.regionOf(other.zone))
        assertNull(choices.homeAssistant("not a rule", "Europe/Madrid", from))
        // CodeRabbit on #235: the line's offset is read when shown, so it follows summer time while the page stays open.
        assertEquals("UTC+01:00 · the same as the panel's", same.line(from))
        assertEquals("UTC+02:00 · the same as the panel's", same.line(Instant.parse("2027-07-01T12:00:00Z")))
        // An old alias is named by its current zone.
        val aliases = TimeZoneChoices(listOf("Europe/Belfast", "Europe/London")) { if (it == "Europe/Belfast") "Europe/London" else it }
        assertEquals(TimeZoneChoices.HomeAssistantZone.Like("Europe/London"), aliases.homeAssistant("GMT0BST,M3.5.0/1,M10.5.0", "Europe/Madrid", from))
        // CodeRabbit on #235: a panel kept on the alias has Home Assistant's clock already.
        assertEquals(TimeZoneChoices.HomeAssistantZone.Same("Europe/London"), aliases.homeAssistant("GMT0BST,M3.5.0/1,M10.5.0", "Europe/Belfast", from))
    }

    @Test fun aFixedOffsetNeverOffersAZoneThePanelDoesntKeep() {
        // #235 verifier N3: HA sends Casablanca as <+00>0, and Africa/Abidjan keeps +00 all year, an hour off
        // Casablanca most of it. So a fixed rule offers no zone: HA's clock, and the panel keeps its zone.
        // Lisbon stands in for Casablanca here: +00 in November like the rule, +01 in summer, and every tz
        // data agrees on it (JDKs encode Morocco differently; CI's and a Mac's disagreed).
        val choices = TimeZoneChoices(ids)
        val lisbon = choices.homeAssistant("<+00>0", "Europe/Lisbon", from)
        assertEquals("the same offset today is not the same clock", TimeZoneChoices.HomeAssistantZone.Elsewhere(0), lisbon)
        assertEquals("UTC+00:00 · Home Assistant's clock; the panel keeps its zone", lisbon!!.line(from))
        // A fixed offset the panel's zone keeps all year is the same; another one is never offered either.
        assertEquals(TimeZoneChoices.HomeAssistantZone.Same("Asia/Kolkata"), choices.homeAssistant("<+0530>-5:30", "Asia/Kolkata", from))
        assertEquals(TimeZoneChoices.HomeAssistantZone.Elsewhere(19_800), choices.homeAssistant("<+0530>-5:30", "Europe/Madrid", from))
    }

    @Test fun anOldAliasIsListedOnceByItsCurrentName() {
        val icu = mapOf("Asia/Calcutta" to "Asia/Kolkata")
        val choices = TimeZoneChoices(listOf("Asia/Calcutta", "Asia/Kolkata", "Asia/Tokyo")) { icu[it] ?: it }
        assertEquals(listOf("Kolkata", "Tokyo"), choices.zones("Asia").map { it.second })
        assertEquals("Asia/Kolkata", choices.name("Asia/Calcutta"))
        // A panel kept on the alias has India's clock.
        assertEquals(TimeZoneChoices.HomeAssistantZone.Same("Asia/Kolkata"), choices.homeAssistant("IST-5:30", "Asia/Calcutta", from))
    }
}
