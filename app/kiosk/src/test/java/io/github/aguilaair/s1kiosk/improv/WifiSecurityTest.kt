package io.github.aguilaair.s1kiosk.improv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WifiSecurityTest {
    private val R = WifiSecurityRules

    @Test fun improvAuthLabels() {
        assertEquals("WPA2", R.improvAuth("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]"))
        assertEquals("WPA/WPA2", R.improvAuth("[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]"))
        assertEquals("WPA2/WPA3", R.improvAuth("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals("WPA3", R.improvAuth("[RSN-SAE-CCMP][ESS]"))
        assertEquals("WPA2 EAP", R.improvAuth("[WPA2-EAP-CCMP][ESS]"))
        assertEquals("WEP", R.improvAuth("[WEP][ESS]"))
        assertEquals("NO", R.improvAuth("[ESS]"))
        assertEquals("NO", R.improvAuth(""))
    }

    @Test fun chooseSecurity() {
        assertEquals(WifiSecurity.PSK, R.choose("[WPA2-PSK-CCMP][ESS]", "x"))
        assertEquals(WifiSecurity.PSK, R.choose("[RSN-PSK+SAE-CCMP][ESS]", "x")) // transition -> PSK
        assertEquals(WifiSecurity.SAE, R.choose("[RSN-SAE-CCMP][ESS]", "x"))
        assertEquals(WifiSecurity.EAP, R.choose("[WPA2-EAP-CCMP][ESS]", "x"))
        assertEquals(WifiSecurity.WEP, R.choose("[WEP][ESS]", "x"))
        assertEquals(WifiSecurity.OWE, R.choose("[RSN-OWE-CCMP][ESS]", ""))
        assertEquals(WifiSecurity.OPEN, R.choose("[ESS]", ""))
        // not in scan results
        assertEquals(WifiSecurity.PSK, R.choose(null, "password1"))
        assertEquals(WifiSecurity.OPEN, R.choose(null, ""))
    }

    @Test fun validatePasswords() {
        assertNull(R.validate(WifiSecurity.PSK, "12345678"))
        assertNotNull(R.validate(WifiSecurity.PSK, "1234567"))
        assertNotNull(R.validate(WifiSecurity.PSK, "x".repeat(64)))
        assertNull(R.validate(WifiSecurity.PSK, "a".repeat(64)))  // raw hex PSK
        assertNotNull(R.validate(WifiSecurity.PSK, "pässwörd1"))  // non-ASCII passphrase
        assertNull(R.validate(WifiSecurity.SAE, "pässwörd1"))
        assertNotNull(R.validate(WifiSecurity.SAE, ""))
        assertNull(R.validate(WifiSecurity.WEP, "abcde"))
        assertNull(R.validate(WifiSecurity.WEP, "0123456789"))
        assertNotNull(R.validate(WifiSecurity.WEP, "abc"))
        assertNotNull(R.validate(WifiSecurity.EAP, "x"))
        assertNull(R.validate(WifiSecurity.OPEN, ""))
    }

    @Test fun configValues() {
        assertEquals("\"password1\"", R.pskValue("password1"))
        assertEquals("ab".repeat(32), R.pskValue("ab".repeat(32)))
        assertEquals("\"abcde\"", R.wepKey("abcde"))
        assertEquals("0123456789", R.wepKey("0123456789"))
        assertEquals("Home", R.unquote("\"Home\""))
        assertEquals("486f6d65", R.unquote("486f6d65"))
        assertNull(R.unquote("<unknown ssid>"))
        assertNull(R.unquote(null))
    }

    @Test fun ipAndRedirect() {
        // 192.168.3.39 little-endian
        val ip = 192 or (168 shl 8) or (3 shl 16) or (39 shl 24)
        assertEquals("192.168.3.39", R.ipv4(ip))
        assertNull(R.ipv4(0))
        assertEquals("http://192.168.3.39:8099/", R.redirectUrl("192.168.3.39"))
        assertNull(R.redirectUrl(null))
    }
}
