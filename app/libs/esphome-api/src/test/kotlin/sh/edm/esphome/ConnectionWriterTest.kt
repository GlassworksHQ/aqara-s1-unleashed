package sh.edm.esphome

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Each connection's writer and queue (#213): a client that stops reading (a stuck Home Assistant, a
 * half-open connection) holds up only itself. Its socket is one whose writes the test stops, as if
 * every buffer on the way were full.
 */
class ConnectionWriterTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )
    private val sockets = CopyOnWriteArrayList<StoppableSocket>()
    private val servers = ArrayList<ApiServer>()
    /** Runs server calls that blocked on a stuck socket before #213, so such a hang fails a read instead of the test. */
    private val driver = Executors.newSingleThreadExecutor { r -> Thread(r, "test-driver").apply { isDaemon = true } }

    @After fun stop() {
        sockets.forEach { it.resume() }
        servers.forEach { it.stop() }
        driver.shutdownNow()
    }

    private val level = AtomicInteger()
    private val sensor = SensorEntity("level", "Level") { level.get().toFloat() }
    private val start = 1_000_000L
    private val now = AtomicLong(start)

    private fun server(vararg entities: ApiEntity, unresponsiveMs: Long = 1_500) =
        ApiServer(0, { identity }, listOf(sensor) + entities, keys = null, bindAddress = InetAddress.getLoopbackAddress()).apply {
            keepaliveMs = 600; this.unresponsiveMs = unresponsiveMs
            // The server's own timer sees a clock that never moves, so only the test's ticks ping.
            clock = { if (Thread.currentThread().name == "esphome-keepalive") start else now.get() }
            newServerSocket = {
                object : ServerSocket() {
                    override fun accept(): Socket = StoppableSocket().also { implAccept(it); sockets += it }
                }
            }
            start()
        }.also { servers += it }

    /** A subscribed client that has read its first states and then stops reading; returns its socket on the server. */
    private fun stuckClient(s: ApiServer): Pair<PlainClient, StoppableSocket> {
        val c = PlainClient(s.localPort)
        c.send(Msg.SUBSCRIBE_STATES_REQUEST)
        c.roundTrip()
        val sock = sockets.last()
        sock.stop()
        return c to sock
    }

    private fun awaitBlocked(sock: StoppableSocket) =
        assertTrue("the stuck client's socket never blocked a write\n${serverThreads()}", sock.blocked.await(10, TimeUnit.SECONDS))

    /** Waits until no thread named [name] runs: each one waits on a lock or has ended. */
    private fun awaitIdle(name: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (Thread.getAllStackTraces().keys.any { it.name == name && it.state in setOf(Thread.State.RUNNABLE, Thread.State.BLOCKED) }) {
            assertTrue("$name never stopped running\n${serverThreads()}", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    @Test
    fun aClientThatStopsReadingDelaysNothingForTheOthers() {
        val jpeg = ByteArray(5 * CameraEntity.CHUNK_BYTES) { it.toByte() }
        val cam = CameraEntity("cam", "Cam") { jpeg }
        val echo = ApiAction("echo", listOf(ActionArg("n", ActionArgType.INT)), ActionResponse.OPTIONAL) {
            """{"n":${it.int("n")}}"""
        }
        // The stuck client stays within the keepalive for the whole test (it is pinged, never dropped).
        val s = server(cam, echo, unresponsiveMs = Long.MAX_VALUE / 2)
        fun call(id: Int) = ProtoWriter().fixed32(1, echo.key, force = true)
            .message(2, ProtoWriter().uint32(5, id * 2)).uint32(3, id).bool(4, true) // sint32: zigzag

        val (stuck, stuckSock) = stuckClient(s)
        stuck.use {
            // Everything that wrote to it from a shared thread before: states, the camera, an action reply, a ping.
            stuck.send(Msg.CAMERA_IMAGE_REQUEST, ProtoWriter().bool(2, true))
            awaitBlocked(stuckSock)
            stuck.send(Msg.EXECUTE_SERVICE_REQUEST, call(1))

            PlainClient(s.localPort).use { c ->
                c.send(Msg.SUBSCRIBE_STATES_REQUEST)
                c.await(Msg.SENSOR_STATE)
                c.roundTrip()
                for (round in 1..20) {
                    level.set(round)
                    driver.execute { s.publishStates() }
                    assertEquals("state $round", round.toFloat(), c.await(Msg.SENSOR_STATE).float(2))

                    c.send(Msg.EXECUTE_SERVICE_REQUEST, call(100 + round))
                    val reply = c.await(Msg.EXECUTE_SERVICE_RESPONSE)
                    assertEquals(100 + round, reply.uint32(1))
                    assertEquals("""{"n":${100 + round}}""", reply.string(4))

                    now.addAndGet(601)
                    driver.execute { s.keepaliveTick() }
                    c.await(Msg.PING_REQUEST)
                    c.send(Msg.PING_RESPONSE)
                    c.roundTrip()
                }
                c.send(Msg.CAMERA_IMAGE_REQUEST, ProtoWriter().bool(1, true))
                assertArrayEquals(jpeg, c.image())
                assertEquals("the stuck client was dropped", 2, s.connectionCount)
            }
        }
    }

    @Test
    fun statesForAClientThatStopsReadingWaitAsTheNewestValue() {
        val s = server()
        val (stuck, stuckSock) = stuckClient(s)
        stuck.use {
            level.set(1); s.publishStates()
            awaitBlocked(stuckSock)
            val last = ApiServer.MAX_QUEUED_BYTES / 4
            for (v in 2..last) { level.set(v); s.publishStates() }
            assertEquals("dropped a client whose only waiting messages were states", 1, s.connectionCount)

            stuckSock.resume()
            val seen = ArrayList<Float>()
            while (seen.lastOrNull() != last.toFloat()) seen += stuck.await(Msg.SENSOR_STATE).float(2)
            assertEquals("states after the client stopped reading", listOf(1f, last.toFloat()), seen)
            assertTrue(stuck.roundTrip().isEmpty())
        }
    }

    @Test
    fun aClientThatStopsReadingIsDroppedOnceItsQueueIsFull() {
        val bell = EventEntity("bell", "Bell", eventTypes = listOf("press"))
        val s = server(bell)
        val (stuck, stuckSock) = stuckClient(s)
        stuck.use {
            s.sendEvent(bell, "press")
            awaitBlocked(stuckSock)
            // Each event is more than 16 bytes in the queue, so this many can't all fit.
            val limit = ApiServer.MAX_QUEUED_BYTES / 16
            var sent = 1
            while (sent < limit && s.connectionCount > 0) { s.sendEvent(bell, "press"); sent++ }
            assertEquals("still connected after $sent events nobody read", 0, s.connectionCount)
            assertTrue("dropped after $sent events: before the queue was full", sent > ApiServer.MAX_QUEUED_BYTES / 64)

            PlainClient(s.localPort).use { c ->
                c.send(Msg.SUBSCRIBE_STATES_REQUEST)
                c.await(Msg.SENSOR_STATE)
            }
        }
    }

    @Test
    fun aCameraWaitsForAClientThatStopsReadingInsteadOfQueueingItsImage() {
        val jpeg = ByteArray(2 * ApiServer.MAX_QUEUED_BYTES) { (it * 7).toByte() }
        val s = server(CameraEntity("cam", "Cam") { jpeg })
        val (stuck, stuckSock) = stuckClient(s)
        stuck.use {
            stuck.send(Msg.CAMERA_IMAGE_REQUEST, ProtoWriter().bool(1, true))
            awaitBlocked(stuckSock)
            awaitIdle("esphome-camera-${sockets.size}")
            assertEquals("dropped while the camera had an image for it", 1, s.connectionCount)
            stuckSock.resume()
            assertArrayEquals(jpeg, stuck.image())
        }
    }

    @Test
    fun aDisconnectRequestIsAnsweredBeforeTheSocketCloses() {
        val s = server()
        val (c, sock) = stuckClient(s)
        c.use {
            c.send(Msg.DISCONNECT_REQUEST)
            awaitBlocked(sock)
            awaitIdle("esphome-conn-${sockets.size}")
            sock.resume()
            assertEquals(Msg.DISCONNECT_RESPONSE, c.read()?.type)
            assertTrue("the socket is closed after the answer", c.closedByServer())
        }
    }

    /** Writes go through until [stop]; after it they block until [resume]. */
    private class StoppableSocket : Socket() {
        @Volatile private var stopped = false
        private val resumed = CountDownLatch(1)
        val blocked = CountDownLatch(1)

        fun stop() { stopped = true }
        fun resume() { stopped = false; resumed.countDown() }

        private val out by lazy {
            object : FilterOutputStream(super.getOutputStream()) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (stopped) { blocked.countDown(); resumed.await() }
                    out.write(b, off, len)
                }
            }
        }
        override fun getOutputStream(): OutputStream = out
    }
}
