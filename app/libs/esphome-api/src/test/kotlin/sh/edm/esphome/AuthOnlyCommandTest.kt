package sh.edm.esphome

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.Collections

/**
 * [ApiEntity.authOnly]: on a panel without a provisioned key anyone on the LAN can talk plaintext to
 * the API. Commands for auth-only entities (radio firmware, Zigbee stack, Thread leave) are refused
 * then; ordinary entities still work.
 */
class AuthOnlyCommandTest {
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
    fun plaintextClientsCannotUseAuthOnlyEntities() {
        val identity = DeviceIdentity(
            name = "s1-panel", friendlyName = "S1 Panel", macAddress = "02:11:22:33:44:55",
            esphomeVersion = "2026.10.2", compilationTime = "S1 Kiosk test", manufacturer = "Aqara",
            model = "Panel Hub S1 Plus (S1 Kiosk)", voiceFeatures = 0,
        )
        val calls = Collections.synchronizedList(ArrayList<String>())
        val open = SelectEntity("open_select", "Open", options = listOf("A", "B"), get = { "A" }, set = { calls += "open $it" })
        val guarded = SelectEntity("radio_firmware_image", "Radio firmware image", options = listOf("A", "B"), get = { "A" },
            set = { calls += "guarded $it" }).apply { authOnly = true }
        val button = ButtonEntity("radio_firmware_flash", "Flash radio firmware", press = { calls += "flash" }).apply { authOnly = true }
        val server = ApiServer(0, { identity }, listOf(open, guarded, button), psk = null) // plaintext: never authenticated
        server.start()
        try {
            Socket("127.0.0.1", server.localPort).use { s ->
                val out = s.getOutputStream()
                out.write(frame(Msg.HELLO_REQUEST, ProtoWriter().string(1, "lan stranger").uint32(2, 1).uint32(3, 10).toByteArray()))
                out.write(frame(Msg.SELECT_COMMAND, ProtoWriter().fixed32(1, guarded.key, force = true).string(2, "B").toByteArray()))
                out.write(frame(Msg.BUTTON_COMMAND, ProtoWriter().fixed32(1, button.key, force = true).toByteArray()))
                out.write(frame(Msg.SELECT_COMMAND, ProtoWriter().fixed32(1, open.key, force = true).string(2, "B").toByteArray()))
                out.flush()
                val until = System.currentTimeMillis() + 5_000
                while (calls.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
                Thread.sleep(200)
            }
        } finally { server.stop() }
        assertEquals(listOf("open B"), calls.toList())
    }
}
