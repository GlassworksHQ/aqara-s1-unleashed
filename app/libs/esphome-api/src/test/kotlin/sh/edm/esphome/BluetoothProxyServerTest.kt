package sh.edm.esphome

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The passive Bluetooth proxy on the wire (#121): DeviceInfo flags, the subscription, raw advertisements. */
class BluetoothProxyServerTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )
    private val calls = CopyOnWriteArrayList<Boolean>()
    private val handler = object : BluetoothProxyHandler {
        override val featureFlags = BluetoothProxyFeature.PASSIVE_SCAN or BluetoothProxyFeature.RAW_ADVERTISEMENTS
        override fun onSubscribed(subscribed: Boolean) { calls += subscribed }
    }
    private val server = ApiServer(0, { identity }, emptyList(), keys = null, bindAddress = InetAddress.getLoopbackAddress())

    @After fun stop() = server.stop()

    private fun start(withProxy: Boolean = true): ApiServer {
        if (withProxy) server.bluetooth = handler
        server.start()
        return server
    }

    private fun PlainClient.subscribe() { send(BluetoothMsg.SUBSCRIBE_LE_ADVERTISEMENTS_REQUEST, ProtoWriter().uint32(1, 1)); roundTrip() }

    private fun advertisement(n: Int) = RawAdvertisement(
        address = 0xC0FFEE000000L + n, rssi = -60 - n, addressType = n % 2, data = byteArrayOf(2, 1, 6, 3, 0xFF.toByte(), n.toByte(), 0),
    )

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!cond()) {
            check(System.nanoTime() < until) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test fun deviceInfoCarriesTheFeatureFlags() {
        start()
        PlainClient(server.localPort).use { c ->
            c.send(Msg.DEVICE_INFO_REQUEST)
            assertEquals(0b100001, c.await(Msg.DEVICE_INFO_RESPONSE).uint32(15))
        }
    }

    @Test fun noProxyNoFlagsAndSubscriptionsAreIgnored() {
        start(withProxy = false)
        PlainClient(server.localPort).use { c ->
            c.send(Msg.DEVICE_INFO_REQUEST)
            assertFalse(c.await(Msg.DEVICE_INFO_RESPONSE).has(15))
            c.subscribe()
            assertFalse(server.sendBluetoothAdvertisements(listOf(advertisement(1))))
        }
    }

    @Test fun advertisementsReachTheSubscriberAsHomeAssistantDecodesThem() {
        start()
        assertFalse("nobody subscribed yet", server.sendBluetoothAdvertisements(listOf(advertisement(1))))
        PlainClient(server.localPort).use { c ->
            c.subscribe()
            assertEquals(listOf(true), calls)
            assertTrue(server.sendBluetoothAdvertisements(listOf(advertisement(1), advertisement(2))))
            val advs = c.await(BluetoothMsg.LE_RAW_ADVERTISEMENTS_RESPONSE).messages(1)
            assertEquals(2, advs.size)
            for ((i, m) in advs.withIndex()) {
                val want = advertisement(i + 1)
                assertEquals(want.address, m.long(1))
                val zigzag = m.uint32(2)
                assertEquals(want.rssi, (zigzag ushr 1) xor -(zigzag and 1))
                assertEquals(want.addressType, m.uint32(3))
                assertArrayEquals(want.data, m.bytes(4))
            }

            c.send(BluetoothMsg.UNSUBSCRIBE_LE_ADVERTISEMENTS_REQUEST); c.roundTrip()
            assertEquals(listOf(true, false), calls)
            assertFalse(server.sendBluetoothAdvertisements(listOf(advertisement(3))))
        }
    }

    @Test fun aReplacedHandlerHearsTheSubscriberLeaveAndTheNewOneHearsItCome() {
        start()
        val other = CopyOnWriteArrayList<Boolean>()
        val b = object : BluetoothProxyHandler {
            override val featureFlags = handler.featureFlags
            override fun onSubscribed(subscribed: Boolean) { other += subscribed }
        }
        PlainClient(server.localPort).use { c ->
            c.subscribe()
            server.bluetooth = b
            assertEquals("the old handler stops", listOf(true, false), calls)
            assertEquals("the new one starts", listOf(true), other)
            assertTrue("the subscriber keeps its advertisements", server.sendBluetoothAdvertisements(listOf(advertisement(1))))
            c.await(BluetoothMsg.LE_RAW_ADVERTISEMENTS_RESPONSE)

            server.bluetooth = null
            assertEquals(listOf(true, false), other)
            assertFalse(server.sendBluetoothAdvertisements(listOf(advertisement(2))))

            server.bluetooth = handler
            c.subscribe()
            assertEquals("a later subscription reaches the handler set now", listOf(true, false, true), calls)
        }
    }

    @Test fun theNewestSubscriberWinsAndTheProxyStopsWhenItLeaves() {
        start()
        val stale = PlainClient(server.localPort)
        stale.subscribe()
        PlainClient(server.localPort).use { fresh ->
            fresh.subscribe()
            assertTrue(server.sendBluetoothAdvertisements(listOf(advertisement(7))))
            assertEquals(0xC0FFEE000007L, fresh.await(BluetoothMsg.LE_RAW_ADVERTISEMENTS_RESPONSE).messages(1)[0].long(1))
            assertTrue("the stale client gets nothing", stale.roundTrip().none { it.type == BluetoothMsg.LE_RAW_ADVERTISEMENTS_RESPONSE })

            stale.close()
            waitFor("the server to drop the stale client") { server.connectionCount == 1 }
            assertEquals("the subscriber didn't change", listOf(true), calls)
        }
        waitFor("the subscriber to leave") { calls.size == 2 }
        assertEquals(listOf(true, false), calls)
    }
}
