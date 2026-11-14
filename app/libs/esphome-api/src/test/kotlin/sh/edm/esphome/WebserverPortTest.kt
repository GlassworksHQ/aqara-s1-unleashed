package sh.edm.esphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket

/**
 * `DeviceInfoResponse.webserver_port` (api.proto field 10): Home Assistant turns it into the device
 * page's "Visit" link. Absent unless the device sets it, so other users of the library see no change.
 */
class WebserverPortTest {
    private fun identity(port: Int = 0) = DeviceIdentity(
        name = "t", friendlyName = "T", macAddress = "02:00:00:00:00:01", esphomeVersion = "2026.10.2",
        compilationTime = "", manufacturer = "Me", model = "Test", voiceFeatures = 0, webserverPort = port,
    )

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

    /** Asks the server for its device info over plaintext and returns the response. */
    private fun deviceInfo(id: DeviceIdentity): ProtoMessage {
        // On loopback, where the test connects (#113: a wildcard listener can share a port with another process on macOS).
        val server = ApiServer(0, { id }, emptyList(), keys = null, bindAddress = java.net.InetAddress.getByName("127.0.0.1"))
        server.start()
        try {
            Socket("127.0.0.1", server.localPort).use { s ->
                s.soTimeout = 5_000
                val out = s.getOutputStream()
                out.write(frame(Msg.HELLO_REQUEST, ProtoWriter().string(1, "test").uint32(2, 1).uint32(3, 10).toByteArray()))
                out.write(frame(Msg.DEVICE_INFO_REQUEST, ByteArray(0)))
                out.flush()
                val t = PlaintextTransport(s.getInputStream(), out)
                while (true) {
                    val f = t.read() ?: error("connection closed before the device info")
                    if (f.type == Msg.DEVICE_INFO_RESPONSE) return ProtoMessage(f.data)
                }
            }
        } finally { server.stop() }
        error("unreachable")
    }

    @Test fun sentWhenSet() = assertEquals(8099, deviceInfo(identity(8099)).uint32(10))

    @Test fun absentByDefault() = assertFalse(deviceInfo(identity()).has(10))
}
