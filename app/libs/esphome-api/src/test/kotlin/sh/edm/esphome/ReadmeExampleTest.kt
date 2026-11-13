package sh.edm.esphome

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket

/** Keeps the README's examples compiling and working: a switch and an action, used by a plaintext API client. */
class ReadmeExampleTest {

    @Test
    fun readmeExample() {
        // --- README example (keep in sync with README.md) ---
        var lampOn = false
        val identity = DeviceIdentity(
            name = "my-device", friendlyName = "My Device", macAddress = "02:00:00:00:00:01",
            esphomeVersion = "2026.10.2", compilationTime = "2026-11-10 12:00:00",
            manufacturer = "Me", model = "Kotlin device", voiceFeatures = 0,
        )
        val lamp = SwitchEntity("lamp", "Lamp", icon = "mdi:lamp", get = { lampOn }, set = { lampOn = it })
        val uptime = SensorEntity("uptime", "Uptime", unit = "s", deviceClass = "duration",
            category = EntityCategory.DIAGNOSTIC, get = { 42f })
        val keys = NoiseKeys(MemoryKeyStore()) // persist the key in real code: HA provisions it
        val server = ApiServer(port = 0 /* ApiServer.DEFAULT_PORT */, identity = { identity },
            entities = listOf(lamp, uptime), keys = keys)
        server.start()
        // Later, when something changes outside a command: server.publishStates()
        // --- end of README example ---

        val banners = java.util.Collections.synchronizedList(ArrayList<String>())
        fun showBanner(title: String, seconds: Int) { banners += "$title/$seconds" }
        fun latestJpeg(): ByteArray? = null
        // --- README example: actions and cameras (keep in sync with README.md) ---
        val notify = ApiAction(
            "notify",
            args = listOf(ActionArg("title", ActionArgType.STRING), ActionArg("seconds", ActionArgType.INT)),
            supportsResponse = ActionResponse.STATUS,
        ) { call ->
            showBanner(call.string("title"), call.int("seconds"))
            null // a JSON object instead, for ActionResponse.OPTIONAL or ONLY
        }
        val porch = CameraEntity("porch", "Porch", maxFps = 5f) { latestJpeg() }
        server.setEntities(listOf(lamp, uptime, notify, porch))
        // --- end of README example ---

        try {
            Socket("127.0.0.1", server.localPort).use { s ->
                val out = s.getOutputStream()
                out.write(frame(Msg.HELLO_REQUEST, ProtoWriter().string(1, "test").uint32(2, 1).uint32(3, 10).toByteArray()))
                out.write(frame(Msg.SWITCH_COMMAND, ProtoWriter().fixed32(1, lamp.key, force = true).bool(2, true).toByteArray()))
                val args = ProtoWriter().fixed32(1, notify.key, force = true)
                    .message(2, ProtoWriter().string(4, "Door")).message(2, ProtoWriter().uint32(5, 20))
                out.write(frame(Msg.EXECUTE_SERVICE_REQUEST, args.toByteArray()))
                out.flush()
                val until = System.currentTimeMillis() + 5_000
                while ((!lampOn || banners.isEmpty()) && System.currentTimeMillis() < until) Thread.sleep(20)
            }
        } finally { server.stop() }
        assertEquals(true, lampOn)
        assertEquals(listOf("Door/10"), banners.toList())
    }

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
}
