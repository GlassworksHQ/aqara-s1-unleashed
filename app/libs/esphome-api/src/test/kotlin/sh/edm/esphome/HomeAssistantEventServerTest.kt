package sh.edm.esphome

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Bus events through the server, with a plaintext client standing in for Home Assistant. */
class HomeAssistantEventServerTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )
    private val server = ApiServer(0, { identity }, emptyList(), keys = null, bindAddress = InetAddress.getLoopbackAddress()).apply { start() }

    @After fun stop() = server.stop()

    private val event = HomeAssistantEvent("esphome.notification_action", linkedMapOf("action" to "open_gate", "tag" to "doorbell"))

    @Test
    fun onlyASubscribedConnectionGetsIt() {
        PlainClient(server.localPort).use { c ->
            c.roundTrip()
            assertFalse("nobody asked for events", server.sendHomeAssistantEvent(event))
            assertTrue(c.roundTrip().none { it.type == Msg.HOMEASSISTANT_ACTION_REQUEST })

            c.send(Msg.SUBSCRIBE_HA_SERVICES_REQUEST)
            c.roundTrip()
            assertTrue(server.sendHomeAssistantEvent(event))
            val m = c.await(Msg.HOMEASSISTANT_ACTION_REQUEST)
            assertEquals("esphome.notification_action", m.string(1))
            assertTrue("is_event", m.bool(5))
            val data = m.messages(2).associate { it.string(1) to it.string(2) }
            assertEquals(mapOf("action" to "open_gate", "tag" to "doorbell"), data)
        }
    }

    @Test
    fun aSecondConnectionGetsItToo() {
        PlainClient(server.localPort).use { a ->
            PlainClient(server.localPort).use { b ->
                a.send(Msg.SUBSCRIBE_HA_SERVICES_REQUEST); a.roundTrip()
                b.send(Msg.SUBSCRIBE_HA_SERVICES_REQUEST); b.roundTrip()
                assertTrue(server.sendHomeAssistantEvent(event))
                a.await(Msg.HOMEASSISTANT_ACTION_REQUEST)
                b.await(Msg.HOMEASSISTANT_ACTION_REQUEST)
            }
        }
    }
}
