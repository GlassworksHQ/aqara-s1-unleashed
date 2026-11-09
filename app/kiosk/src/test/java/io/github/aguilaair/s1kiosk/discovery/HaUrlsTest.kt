package io.github.aguilaair.s1kiosk.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class HaUrlsTest {
    private fun txt(vararg kv: Pair<String, String>): Map<String, ByteArray?> = kv.associate { it.first to it.second.toByteArray() }
    private val ip = InetAddress.getByName("192.168.1.100")

    @Test
    fun txtFromThisHa() {
        // What HA 2026.11.0 on the LAN advertises (dns-sd -L Home _home-assistant._tcp local.)
        val t = txt("location_name" to "Home", "uuid" to "x", "version" to "2026.11.0", "external_url" to "",
            "internal_url" to "http://192.168.1.100:8123", "base_url" to "http://192.168.1.100:8123")
        assertEquals("http://192.168.1.100:8123", HaUrls.fromTxt(t, ip, 8123))
        assertEquals(listOf("http://192.168.1.100:8123"), HaUrls.candidates(t, ip, 8123))
    }

    @Test
    fun txtPreferenceOrder() {
        val t = txt("internal_url" to "http://homeassistant.local:8123/", "base_url" to "https://ha.example.com")
        assertEquals(
            listOf("http://homeassistant.local:8123", "https://ha.example.com", "http://192.168.1.100:8123"),
            HaUrls.candidates(t, ip, 8123),
        )
        assertEquals("https://ha.example.com", HaUrls.fromTxt(txt("internal_url" to "", "base_url" to "https://ha.example.com"), null, 0))
        assertEquals("http://192.168.1.100:8124", HaUrls.fromTxt(txt("internal_url" to ""), ip, 8124))
        assertNull(HaUrls.fromTxt(emptyMap(), null, 0))
        assertEquals("http://192.168.1.100:8123", HaUrls.fromTxt(mapOf("internal_url" to null), ip, 0))
    }

    @Test
    fun addresses() {
        assertEquals("http://192.168.1.100:8123", HaUrls.fromAddress(ip))
        assertNull(HaUrls.fromAddress(InetAddress.getByName("127.0.0.1")))
        assertNull(HaUrls.fromAddress(InetAddress.getByName("169.254.1.2")))
        assertEquals("http://[fd00:0:0:0:0:0:0:5]:8123", HaUrls.fromAddress(InetAddress.getByName("fd00::5")))
    }

    @Test
    fun normalize() {
        assertEquals("http://192.168.1.100:8123", HaUrls.normalize(" 192.168.1.100:8123/ "))
        assertEquals("http://homeassistant.local:8123", HaUrls.normalize("http://homeassistant.local:8123/lovelace/0"))
        assertNull(HaUrls.normalize(""))
        assertNull(HaUrls.normalize("ftp://x"))
    }

    @Test
    fun helloAndManifest() {
        assertTrue(HaUrls.isHomeAssistantHello("Home Assistant 2026.11.0"))
        assertFalse(HaUrls.isHomeAssistantHello("aioesphomeapi"))
        assertTrue(HaUrls.looksLikeHaManifest(200, """{"background_color":"#FFFFFF","name":"Home Assistant","short_name":"Assistant"}"""))
        assertFalse(HaUrls.looksLikeHaManifest(200, """{"name":"Grafana"}"""))
        assertFalse(HaUrls.looksLikeHaManifest(404, "{}"))
        assertFalse(HaUrls.looksLikeHaManifest(200, "<html>"))
    }
}
