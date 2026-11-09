package io.github.aguilaair.s1kiosk.improv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests. Expected bytes come from home-assistant-libs py-improv-ble-client 2.0.1
 * (`improv_ble_client.protocol`, the library HA's improv_ble uses), e.g.
 * `WiFiSettingsCmd(b'MyWirelessAP', b'mysecurepassword').as_bytes().hex()`, and from the spec's
 * example `01 1E 0C {MyWirelessAP} 10 {mysecurepassword} CS`.
 */
class ImprovProtocolTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun checksumIsLowByteOfSum() {
        assertEquals(0x02.toByte(), Improv.checksum(byteArrayOf(0x02, 0x00)))
        assertEquals(0x01.toByte(), Improv.checksum(byteArrayOf(0xFF.toByte(), 0x02)))
        assertEquals(0x00.toByte(), Improv.checksum(ByteArray(0)))
    }

    @Test fun parsesSpecWifiSettingsExample() {
        val pkt = hex("011e0c4d79576972656c6573734150106d7973656375726570617373776f7264c0")
        assertEquals(0x1E, pkt[1].toInt())
        assertEquals(RpcCommand.WifiSettings("MyWirelessAP", "mysecurepassword"), Improv.parse(pkt))
        // Encoding the same strings yields the same bytes.
        assertArrayEquals(pkt, Improv.encode(Improv.CMD_WIFI_SETTINGS, listOf("MyWirelessAP", "mysecurepassword")))
    }

    @Test fun parsesEmptyCommandsFromHaClient() {
        assertEquals(RpcCommand.Identify, Improv.parse(hex("020002")))
        assertEquals(RpcCommand.DeviceInfo, Improv.parse(hex("030003")))
        assertEquals(RpcCommand.ScanWifi, Improv.parse(hex("040004")))
        assertEquals(RpcCommand.Unknown(Improv.CMD_HOSTNAME), Improv.parse(hex("050005")))
        assertEquals(RpcCommand.Unknown(Improv.CMD_DEVICE_NAME), Improv.parse(hex("060006")))
    }

    @Test fun rejectsBadChecksumAndLength() {
        assertTrue(Improv.parse(hex("020003")) is RpcCommand.Invalid)          // checksum
        assertTrue(Improv.parse(hex("02010002")) is RpcCommand.Invalid)        // length byte 1, but string len 0 + junk
        assertTrue(Improv.parse(hex("0205")) is RpcCommand.Invalid)            // too short
        // Extra (well-formed) data on a no-data command is tolerated, like sdk-cpp parse_improv_data.
        assertEquals(RpcCommand.Identify, Improv.parse(hex("0202000004")))
    }

    @Test fun wifiSettingsNeedsTwoStrings() {
        val one = Improv.encode(Improv.CMD_WIFI_SETTINGS, listOf("onlyssid"))
        assertTrue(Improv.parse(one) is RpcCommand.Invalid)
        // ssid length running past the end of the data
        val bad = byteArrayOf(0x01, 0x03, 0x09, 0x41, 0x00).let { it + Improv.checksum(it) }
        assertTrue(Improv.parse(bad) is RpcCommand.Invalid)
        // empty password is fine (open network)
        assertEquals(RpcCommand.WifiSettings("Cafe", ""), Improv.parse(Improv.encode(1, listOf("Cafe", ""))))
    }

    @Test fun utf8Strings() {
        val pkt = Improv.encode(1, listOf("Café ☕", "pässwörd"))
        assertEquals(RpcCommand.WifiSettings("Café ☕", "pässwörd"), Improv.parse(pkt))
    }

    @Test fun encodesResultsLikeHaClient() {
        assertEquals("011a19687474703a2f2f3139322e3136382e332e33393a383039392f33",
            Improv.encode(Improv.CMD_WIFI_SETTINGS, listOf("http://192.168.3.39:8099/")).hex())
        assertEquals(
            "033c085331204b696f736b05302e312e302341716172612050616e656c2048756220533120506c75732f61726d656162692d7637610853312050616e656c75",
            Improv.encode(Improv.CMD_DEVICE_INFO, listOf("S1 Kiosk", "0.1.0", "Aqara Panel Hub S1 Plus/armeabi-v7a", "S1 Panel")).hex())
        // Wi-Fi settings result without a URL: no strings.
        assertEquals("010001", Improv.encode(Improv.CMD_WIFI_SETTINGS, emptyList()).hex())
    }

    @Test fun encodesScanLikeHaClient() {
        val nets = listOf(ScannedNetwork("Cafe", -80, "NO"), ScannedNetwork("Home", -52, "WPA2"))
        // strongest first
        assertEquals("041a04486f6d65032d353204575041320443616665032d3830024e4f0a", Improv.encodeScan(nets).hex())
        assertEquals("040004", Improv.encodeScan(emptyList()).hex())
    }

    @Test fun scanDedupesAndFitsTheBudget() {
        val nets = (1..40).map { ScannedNetwork("Network-%02d".format(it), -30 - it, "WPA2") } +
            ScannedNetwork("Network-01", -99, "WPA2") + ScannedNetwork("", -10, "NO")
        val full = Improv.encodeScan(nets)
        assertTrue(full.size <= Improv.MAX_PACKET)
        val strings = Improv.strings(full)!!
        assertEquals(0, strings.size % 3)
        assertEquals("Network-01", String(strings[0]))
        assertEquals(1, strings.count { String(it) == "Network-01" })
        // MTU 23 -> 20-byte notifications: still a valid (shorter) packet.
        val small = Improv.encodeScan(nets, maxPacket = 20)
        assertTrue(small.size <= 20)
        assertEquals(small[1].toInt() and 0xFF, small.size - 3)
        assertEquals(small.last(), Improv.checksum(small, small.size - 1))
    }

    @Test fun serviceDataIsSixBytes() {
        assertArrayEquals(byteArrayOf(1, 7, 0, 0, 0, 0), Improv.serviceData(ImprovState.AUTHORIZATION_REQUIRED, 7))
        assertArrayEquals(byteArrayOf(4, 3, 0, 0, 0, 0), Improv.serviceData(ImprovState.PROVISIONED, 3))
    }

    @Test fun assemblerJoinsFragments() {
        val pkt = Improv.encode(1, listOf("MyWirelessAP", "mysecurepassword"))
        val a = RpcAssembler()
        assertNull(a.add(pkt.copyOfRange(0, 1)))
        assertNull(a.add(pkt.copyOfRange(1, 20)))
        assertArrayEquals(pkt, a.add(pkt.copyOfRange(20, pkt.size)))
        assertEquals(0, a.pending)
        // overshoot -> reset, reported as an (invalid) empty packet
        val over = a.add(pkt + byteArrayOf(9))
        assertEquals(0, over!!.size)
        assertTrue(Improv.parse(over) is RpcCommand.Invalid)
        assertEquals(0, a.pending)
    }

    @Test fun passwordIsNotInToString() {
        val s = RpcCommand.WifiSettings("Home", "hunter22").toString()
        assertTrue("hunter22" !in s)
    }
}
