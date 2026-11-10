package sh.edm.s1kiosk.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

/** Documentation addresses only (RFC 5737 / RFC 3849): never real ones. */
class RadioAllowTest {

    private fun ok(text: String): String {
        val p = RadioAllow.parse(text)
        assertNull("error for '$text': ${p.error}", p.error)
        return p.value!!
    }

    private fun bad(text: String): String {
        val p = RadioAllow.parse(text)
        assertNull("accepted '$text' as '${p.value}'", p.value)
        return p.error!!
    }

    @Test
    fun emptyAndAutoMeanAutomatic() {
        assertEquals("", ok(""))
        assertEquals("", ok("   "))
        assertEquals("", ok("auto"))
        assertEquals("", ok("AUTO"))
    }

    @Test
    fun anyAloneOnly() {
        assertEquals("any", ok("any"))
        assertEquals("any", ok(" Any "))
        assertNotNull(bad("any 192.0.2.10"))
    }

    @Test
    fun addressesAndCidrsAreNormalized() {
        assertEquals("192.0.2.10", ok("192.0.2.10"))
        assertEquals("192.0.2.10 198.51.100.0/24", ok("192.0.2.10, 198.51.100.7/24"))
        assertEquals("192.0.2.10", ok("192.0.2.10/32"))
        assertEquals("10.0.0.0/8", ok("10.1.2.3/8"))
        assertEquals("0.0.0.0/0", ok("0.0.0.0/0"))
        // duplicates (also after normalization) are dropped, order kept
        assertEquals("192.0.2.10 203.0.113.0/24", ok("192.0.2.10 203.0.113.5/24 192.0.2.10/32,203.0.113.0/24"))
        // IPv6: RFC 5952 (lowercase, longest zero run compressed), zone id dropped, mapped IPv4 as IPv4
        assertEquals("2001:db8::1", ok("2001:DB8:0:0:0:0:0:1"))
        assertEquals("2001:db8::1", ok("2001:db8::1/128"))
        assertEquals("2001:db8::/32", ok("2001:db8:1234::5/32"))
        assertEquals("2001:db8:0:1:1:1:1:1", ok("2001:db8:0:1:1:1:1:1")) // a single zero group isn't compressed
        assertEquals("2001:0:0:1::1", ok("2001:0:0:1:0:0:0:1"))         // the longer run wins
        assertEquals("fe80::1", ok("fe80::1%wlan0"))
        assertEquals("192.0.2.10", ok("::ffff:192.0.2.10"))
        assertEquals("::", ok("::"))
    }

    @Test
    fun junkIsRefused() {
        for (t in listOf(
            "192.0.2", "192.0.2.256", "192.0.2.010", "192.0.2.1.5", "192.0.2.10/33", "192.0.2.10/", "192.0.2.10/x",
            "2001:db8::1/129", "2001:db8:::1", "ha.local", "localhost", "*", "192.0.2.10;reboot", "\$(id)", "-j ACCEPT",
            "2001:db8::g",
        )) {
            assertNull(t, RadioAllow.normalizeEntry(t))
            bad(t)
        }
    }

    @Test
    fun tooLongForTheProperty() {
        val many = (1..8).joinToString(" ") { "2001:db8:aaaa:bbbb:cccc:dddd:eeee:$it" }
        bad(many)
        // the prop's limit: what is accepted always fits
        val fits = (1..5).joinToString(" ") { "198.51.100.$it" }
        assertEquals(fits, ok(fits))
    }

    @Test
    fun clientAddressFromTheSocket() {
        assertEquals("192.0.2.10", RadioAllow.clientAddress(InetAddress.getByName("192.0.2.10")))
        assertEquals("2001:db8::10", RadioAllow.clientAddress(InetAddress.getByName("2001:db8:0:0:0:0:0:10")))
        assertEquals("fe80::10", RadioAllow.clientAddress(InetAddress.getByName("fe80::10%1")))
        assertEquals("192.0.2.10", RadioAllow.clientAddress(InetAddress.getByAddress(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -64, 0, 2, 10))))
        // loopback is always allowed by the helper; it must never replace HA's address
        assertNull(RadioAllow.clientAddress(InetAddress.getByName("127.0.0.1")))
        assertNull(RadioAllow.clientAddress(InetAddress.getByName("::1")))
        assertNull(RadioAllow.clientAddress(InetAddress.getByName("0.0.0.0")))
        assertNull(RadioAllow.clientAddress(null))
    }

    @Test
    fun decideWhoIsAllowed() {
        // nothing known yet: nobody (the helper allows loopback only)
        assertEquals("", RadioAllow.decide("", null, null))
        // Home Assistant's last addresses
        assertEquals("192.0.2.10", RadioAllow.decide("", "192.0.2.10", null))
        assertEquals("192.0.2.10 2001:db8::10", RadioAllow.decide("", "192.0.2.10", "2001:db8::10"))
        assertEquals("2001:db8::10", RadioAllow.decide(null, " ", "2001:db8::10"))
        // the owner's list wins
        assertEquals("198.51.100.0/24", RadioAllow.decide("198.51.100.0/24", "192.0.2.10", null))
        assertEquals("any", RadioAllow.decide("any", "192.0.2.10", null))
    }

    @Test
    fun describe() {
        fun a(raw: String) = RadioHelper.parseAllowed(raw)!!
        assertEquals("Home Assistant (192.0.2.10)", RadioAllow.describe(a("app 192.0.2.10"), false, "192.0.2.10", null))
        assertEquals("nobody yet (waiting for Home Assistant to connect over ESPHome)",
            RadioAllow.describe(a("app none"), false, null, null))
        assertEquals("198.51.100.0/24 (Radio allowed clients)", RadioAllow.describe(a("app 198.51.100.0/24"), true, "192.0.2.10", null))
        assertEquals("any client (no firewall)", RadioAllow.describe(a("app any"), true, null, null))
        assertEquals("any client (no firewall, radio.conf)", RadioAllow.describe(a("conf any"), false, null, null))
        assertEquals("192.0.2.99 (radio.conf)", RadioAllow.describe(a("conf 192.0.2.99"), false, "192.0.2.10", null))
        assertEquals("nobody but the panel itself (radio.conf)", RadioAllow.describe(a("conf none"), false, null, null))
        // the helper still enforces an older list (HA just moved): show it plainly
        assertEquals("192.0.2.10", RadioAllow.describe(a("app 192.0.2.10"), false, "192.0.2.11", null))
    }

    @Test
    fun parseAllowedProp() {
        assertNull(RadioHelper.parseAllowed(""))
        assertNull(RadioHelper.parseAllowed(null))
        assertNull(RadioHelper.parseAllowed("app"))
        assertNull(RadioHelper.parseAllowed("x 192.0.2.10"))
        assertEquals(RadioHelper.Allowed("app", false, listOf("192.0.2.10", "2001:db8::1")),
            RadioHelper.parseAllowed("app 192.0.2.10 2001:db8::1"))
        assertEquals(RadioHelper.Allowed("conf", true, emptyList()), RadioHelper.parseAllowed("conf any"))
        assertEquals(RadioHelper.Allowed("app", false, emptyList()), RadioHelper.parseAllowed("app none"))
        // a radio.conf list too long for the prop: the helper ends it with "+<n more>"
        assertEquals("192.0.2.1, +3 (radio.conf)",
            RadioAllow.describe(RadioHelper.parseAllowed("conf 192.0.2.1 +3")!!, false, null, null))
    }
}
