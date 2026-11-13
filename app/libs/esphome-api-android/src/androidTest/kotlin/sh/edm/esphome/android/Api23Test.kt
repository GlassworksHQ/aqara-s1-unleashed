package sh.edm.esphome.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import sh.edm.esphome.ApiServer
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.MemoryKeyStore
import sh.edm.esphome.Msg
import sh.edm.esphome.NoiseCipher
import sh.edm.esphome.NoiseKeys
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** esphome-api and this module on the device they run on; CI runs it on an API 23 emulator (#151). */
@RunWith(AndroidJUnit4::class)
class Api23Test {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val identity = DeviceIdentity("t", "T", "02:00:00:00:00:01", "2026.10.2", "", "Me", "Test", 0)

    @Test fun aKeyFromHomeAssistantIsAccepted() {
        val store = MemoryKeyStore()
        val r = NoiseKeys(store).handleSetKey("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=".toByteArray())
        assertTrue(r.message, r.success)
        assertArrayEquals(key, store.key)
    }

    @Test fun noiseCipherRoundTrips() {
        val sealed = NoiseCipher(key).encrypt(byteArrayOf(), "hello".toByteArray())
        assertEquals("hello", String(NoiseCipher(key).decrypt(byteArrayOf(), sealed)))
    }

    @Test fun theServerAnswersHelloAndIsAdvertised() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ApiServer(0, { identity }, emptyList(), NoiseKeys(MemoryKeyStore()), loopback)
        val mdns = EspHomeMdns(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            server.start()
            mdns.start(identity, server)
            Socket().use { s ->
                s.connect(InetSocketAddress(loopback, server.localPort), 2_000)
                s.soTimeout = 5_000
                s.getOutputStream().write(byteArrayOf(0, 4, Msg.HELLO_REQUEST.toByte(), 0x0a, 2, 't'.code.toByte(), 't'.code.toByte()))
                assertEquals("Hello is answered with a plaintext frame", 0, s.getInputStream().read())
            }
        } finally {
            mdns.stop()
            server.stop()
        }
    }
}
