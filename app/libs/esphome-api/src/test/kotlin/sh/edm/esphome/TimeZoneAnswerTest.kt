package sh.edm.esphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.Collections

/**
 * [ApiServer.onTimeZone] only takes Home Assistant's zone from an authenticated client. A plaintext
 * client on the LAN that sends a `GetTimeResponse` nobody asked for is ignored (#235 verifier, N2).
 */
class TimeZoneAnswerTest {
    private fun varint(o: ByteArrayOutputStream, v: Int) {
        var x = v
        while (x >= 0x80) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }
        o.write(x)
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0); varint(o, payload.size); varint(o, type); o.write(payload)
        return o.toByteArray()
    }

    @Test
    fun aPlaintextClientsUnaskedTimeAnswerIsIgnored() {
        val identity = DeviceIdentity(
            name = "s1-panel", friendlyName = "S1 Panel", macAddress = "02:11:22:33:44:55",
            esphomeVersion = "2026.10.2", compilationTime = "S1 Kiosk test", manufacturer = "Aqara",
            model = "Panel Hub S1 Plus (S1 Kiosk)", voiceFeatures = 0,
        )
        val zones = Collections.synchronizedList(ArrayList<String>())
        val pinged = Collections.synchronizedList(ArrayList<String>())
        val server = ApiServer(0, { identity }, emptyList(), psk = null) // plaintext: never authenticated
        server.onTimeZone = { zones += it }
        server.start()
        try {
            Socket("127.0.0.1", server.localPort).use { s ->
                val out = s.getOutputStream()
                out.write(frame(Msg.HELLO_REQUEST, ProtoWriter().string(1, "lan stranger").uint32(2, 1).uint32(3, 10).toByteArray()))
                out.write(frame(Msg.SUBSCRIBE_STATES_REQUEST, ByteArray(0)))
                out.write(frame(Msg.GET_TIME_RESPONSE, ProtoWriter().fixed32(1, 1_800_000_000, force = true).string(2, "UTC-14").toByteArray()))
                // Anything after it is handled too: a ping answered means the time answer was read.
                out.write(frame(Msg.PING_REQUEST, ByteArray(0)))
                out.flush()
                s.soTimeout = 3_000
                val input = s.getInputStream()
                val until = System.currentTimeMillis() + 3_000
                val seen = ByteArrayOutputStream()
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < until) {
                    val n = runCatching { input.read(buf) }.getOrDefault(-1)
                    if (n <= 0) break
                    seen.write(buf, 0, n)
                    // PING_RESPONSE (8) frames as 00 00 08.
                    if (seen.toByteArray().toList().windowed(3).any { it == listOf<Byte>(0, 0, Msg.PING_RESPONSE.toByte()) }) { pinged += "pong"; break }
                }
            }
        } finally { server.stop() }
        assertTrue("the server read on past the answer", pinged.isNotEmpty())
        assertEquals("a plaintext client's zone is not Home Assistant's", emptyList<String>(), zones.toList())
    }
}
