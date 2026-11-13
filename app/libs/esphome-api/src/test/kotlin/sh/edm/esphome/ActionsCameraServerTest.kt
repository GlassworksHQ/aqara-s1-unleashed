package sh.edm.esphome

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actions and cameras through the server, with a plaintext client standing in for Home Assistant. */
class ActionsCameraServerTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )
    private val servers = ArrayList<ApiServer>()

    @After fun stop() = servers.forEach { it.stop() }

    private fun server(vararg entities: ApiEntity, streamMs: Long = ApiServer.CAMERA_STREAM_MS) =
        ApiServer(0, { identity }, entities.toList(), keys = null, bindAddress = InetAddress.getLoopbackAddress())
            .apply { cameraStreamMs = streamMs; start() }.also { servers += it }

    private class Client(port: Int) : AutoCloseable {
        private val socket = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 10_000 }
        private val out = socket.getOutputStream()
        private val inp = DataInputStream(socket.getInputStream())

        init {
            send(Msg.HELLO_REQUEST, ProtoWriter().string(1, "test").uint32(2, 1).uint32(3, 10))
            await(Msg.HELLO_RESPONSE)
        }

        fun send(type: Int, w: ProtoWriter = ProtoWriter()) {
            val p = w.toByteArray()
            val o = ByteArrayOutputStream()
            o.write(0); varint(o, p.size); varint(o, type); o.write(p)
            out.write(o.toByteArray()); out.flush()
        }

        /** The next frame, or null if none arrives within [timeoutMs]. */
        fun read(timeoutMs: Int = 10_000): Frame? {
            socket.soTimeout = timeoutMs
            return try {
                check(inp.read() == 0) { "not plaintext" }
                val len = readVarint(); val type = readVarint()
                Frame(type, ByteArray(len).also { inp.readFully(it) })
            } catch (_: SocketTimeoutException) { null }
        }

        fun await(type: Int, seen: MutableList<Frame> = ArrayList()): ProtoMessage {
            while (true) {
                val f = read() ?: throw AssertionError("no message $type; got ${seen.map { it.type }}")
                if (f.type == type) return ProtoMessage(f.data)
                seen += f
            }
        }

        /** Every frame until a ping is answered: the server has handled everything sent before it. */
        fun roundTrip(): List<Frame> = ArrayList<Frame>().also { send(Msg.PING_REQUEST); await(Msg.PING_RESPONSE, it) }

        /** Reassembles camera images: chunks until `done`. */
        fun image(seen: MutableList<Frame> = ArrayList()): ByteArray {
            var data = ByteArray(0)
            while (true) {
                val m = await(Msg.CAMERA_IMAGE_RESPONSE, seen)
                data += m.bytes(2)
                if (m.bool(3)) return data
            }
        }

        private fun varint(o: ByteArrayOutputStream, v: Int) {
            var x = v
            while (x >= 0x80) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }
            o.write(x)
        }

        private fun readVarint(): Int {
            var v = 0; var shift = 0
            while (true) {
                val b = inp.readUnsignedByte()
                v = v or ((b and 0x7F) shl shift)
                if (b < 0x80) return v
                shift += 7
            }
        }

        override fun close() = socket.close()
    }

    private fun call(action: ApiAction, callId: Int, returnResponse: Boolean, vararg args: ProtoWriter): ProtoWriter {
        val w = ProtoWriter().fixed32(1, action.key, force = true)
        for (a in args) w.message(2, a)
        return w.uint32(3, callId).bool(4, returnResponse)
    }

    @Test
    fun anActionIsListedAndAnswersWithItsJson() {
        val echo = ApiAction("echo", listOf(ActionArg("text", ActionArgType.STRING)), ActionResponse.OPTIONAL) {
            """{"text":"${it.string("text")}"}"""
        }
        val s = server(echo)
        Client(s.localPort).use { c ->
            c.send(Msg.LIST_ENTITIES_REQUEST)
            val listed = ArrayList<Frame>().also { c.await(Msg.LIST_ENTITIES_DONE, it) }
            assertEquals(listOf(Msg.LIST_ENTITIES_SERVICES), listed.map { it.type })
            assertEquals("echo", ProtoMessage(listed.single().data).string(1))

            c.send(Msg.EXECUTE_SERVICE_REQUEST, call(echo, 3, true, ProtoWriter().string(4, "hi")))
            val r = c.await(Msg.EXECUTE_SERVICE_RESPONSE)
            assertEquals(3, r.uint32(1))
            assertTrue(r.bool(2))
            assertEquals("""{"text":"hi"}""", r.string(4))
        }
    }

    @Test
    fun fireAndForgetCallsGetNoResponse() {
        val calls = AtomicInteger()
        val ring = ApiAction("ring") { calls.incrementAndGet(); null }
        val status = ApiAction("status", supportsResponse = ActionResponse.STATUS) { null }
        val s = server(ring, status)
        Client(s.localPort).use { c ->
            c.send(Msg.EXECUTE_SERVICE_REQUEST, call(ring, 0, false))
            // Actions run in order, so a reply to the first would come before this one's.
            c.send(Msg.EXECUTE_SERVICE_REQUEST, call(status, 9, false))
            val seen = ArrayList<Frame>()
            assertEquals(9, c.await(Msg.EXECUTE_SERVICE_RESPONSE, seen).uint32(1))
            assertEquals(emptyList<Int>(), seen.map { it.type })
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun aSlowActionDoesNotHoldUpTheConnection() {
        val release = CountDownLatch(1)
        val slow = ApiAction("slow", supportsResponse = ActionResponse.STATUS) { release.await(10, TimeUnit.SECONDS); null }
        val s = server(slow)
        Client(s.localPort).use { c ->
            c.send(Msg.EXECUTE_SERVICE_REQUEST, call(slow, 1, false))
            c.roundTrip()
            release.countDown()
            assertTrue(c.await(Msg.EXECUTE_SERVICE_RESPONSE).bool(2))
        }
    }

    @Test
    fun authOnlyActionsAreRefusedOverPlaintext() {
        val calls = AtomicInteger()
        val wipe = ApiAction("wipe", supportsResponse = ActionResponse.STATUS) { calls.incrementAndGet(); null }
            .apply { authOnly = true }
        val s = server(wipe)
        Client(s.localPort).use { c ->
            c.send(Msg.EXECUTE_SERVICE_REQUEST, call(wipe, 4, false))
            val r = c.await(Msg.EXECUTE_SERVICE_RESPONSE)
            assertFalse(r.bool(2))
            assertTrue(r.string(3), r.string(3).contains("encrypted"))
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun aSingleImageArrivesWhole() {
        val jpeg = ByteArray(3 * CameraEntity.CHUNK_BYTES + 7) { (it * 31).toByte() }
        val frames = AtomicInteger()
        val cam = CameraEntity("cam", "Cam") { frames.incrementAndGet(); jpeg }
        val s = server(cam)
        Client(s.localPort).use { c ->
            c.send(Msg.SUBSCRIBE_STATES_REQUEST)
            c.send(Msg.CAMERA_IMAGE_REQUEST, ProtoWriter().bool(1, true))
            assertArrayEquals(jpeg, c.image())
            assertTrue(c.roundTrip().none { it.type == Msg.CAMERA_IMAGE_RESPONSE })
            assertEquals(1, frames.get())
        }
    }

    @Test
    fun noImagesBeforeTheClientSubscribedToStates() {
        val frames = AtomicInteger()
        val cam = CameraEntity("cam", "Cam") { frames.incrementAndGet(); byteArrayOf(1, 2, 3) }
        val s = server(cam)
        Client(s.localPort).use { c ->
            c.send(Msg.CAMERA_IMAGE_REQUEST, ProtoWriter().bool(1, true))
            assertTrue(c.roundTrip().isEmpty())
            assertEquals(null, c.read(300))
            assertEquals(0, frames.get())
        }
    }

    @Test
    fun aStreamRunsUntilRequestsStop() {
        val streamMs = 800L
        val cam = CameraEntity("cam", "Cam", maxFps = 20f) { byteArrayOf(1, 2, 3) }
        val s = server(cam, streamMs = streamMs)
        Client(s.localPort).use { c ->
            c.send(Msg.SUBSCRIBE_STATES_REQUEST)
            c.roundTrip()
            val start = System.nanoTime()
            c.send(Msg.CAMERA_IMAGE_REQUEST, ProtoWriter().bool(2, true))
            var images = 0
            val giveUp = start + (streamMs + 5_000) * 1_000_000
            while (System.nanoTime() < giveUp) {
                val f = c.read(1_500) ?: break
                if (f.type == Msg.CAMERA_IMAGE_RESPONSE && ProtoMessage(f.data).bool(3)) images++
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("images in the stream: $images", images >= 3)
            // 20 fps for 0.8 s is 16 at most (+1 for the frame at the start).
            assertTrue("images in the stream: $images", images <= 17)
            assertTrue("stream stopped after ${elapsedMs - 1_500} ms", elapsedMs < streamMs + 1_500 + 1_000)
        }
    }

    @Test
    fun aBusyCameraDoesNotHoldUpStatesOrPings() {
        val inFrame = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cam = CameraEntity("cam", "Cam") { inFrame.countDown(); release.await(10, TimeUnit.SECONDS); byteArrayOf(9) }
        var level = 1f
        val sensor = SensorEntity("level", "Level") { level }
        val s = server(cam, sensor)
        Client(s.localPort).use { c ->
            c.send(Msg.SUBSCRIBE_STATES_REQUEST)
            c.await(Msg.SENSOR_STATE)
            c.send(Msg.CAMERA_IMAGE_REQUEST, ProtoWriter().bool(1, true))
            assertTrue(inFrame.await(10, TimeUnit.SECONDS))
            c.roundTrip()
            level = 2f; s.publishStates()
            assertEquals(2f, c.await(Msg.SENSOR_STATE).float(2))
            release.countDown()
            assertArrayEquals(byteArrayOf(9), c.image())
        }
    }

    @Test
    fun actionsAndCamerasComeBackAfterAReconnect() {
        val calls = Collections.synchronizedList(ArrayList<Int>())
        val n = ApiAction("n", listOf(ActionArg("v", ActionArgType.INT)), ActionResponse.STATUS) { calls += it.int("v"); null }
        val cam = CameraEntity("cam", "Cam") { byteArrayOf(7) }
        val s = server(n, cam)
        repeat(2) { round ->
            Client(s.localPort).use { c ->
                c.send(Msg.SUBSCRIBE_STATES_REQUEST)
                c.send(Msg.EXECUTE_SERVICE_REQUEST, call(n, 1, false, ProtoWriter().uint32(5, (round + 1) * 2)))
                assertTrue(c.await(Msg.EXECUTE_SERVICE_RESPONSE).bool(2))
                c.send(Msg.CAMERA_IMAGE_REQUEST, ProtoWriter().bool(1, true))
                assertArrayEquals(byteArrayOf(7), c.image())
            }
        }
        assertEquals(listOf(1, 2), calls.toList())
    }
}
