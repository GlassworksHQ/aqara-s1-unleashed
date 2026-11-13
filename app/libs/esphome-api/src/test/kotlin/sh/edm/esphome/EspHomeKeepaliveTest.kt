package sh.edm.esphome

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The server's keepalive (ApiServer.KEEPALIVE_MS): Home Assistant's aioesphomeapi doesn't ping a
 * device that keeps sending, so the server must ping quiet clients itself and only drop ones that stay
 * silent after that, as ESPHome does. The old 90 s read timeout dropped HA every 90 s.
 */
class EspHomeKeepaliveTest {
    private val identity = DeviceIdentity(
        name = "s1-panel", friendlyName = "S1 Panel", macAddress = "02:11:22:33:44:55",
        esphomeVersion = "2026.10.2", compilationTime = "S1 Kiosk test", manufacturer = "Aqara",
        model = "Panel Hub S1 Plus (S1 Kiosk)", voiceFeatures = 0,
    )
    private val counter = AtomicInteger()
    private fun server(keepaliveMs: Long, unresponsiveMs: Long) =
        ApiServer(0, { identity }, listOf(SensorEntity("ticks", "Ticks", get = { counter.get().toFloat() })), psk = null).apply {
            this.keepaliveMs = keepaliveMs; this.unresponsiveMs = unresponsiveMs
        }

    // --- a minimal plaintext client
    private fun varint(o: ByteArrayOutputStream, v: Int) {
        var x = v
        while (x >= 0x80) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }
        o.write(x)
    }
    private fun frame(type: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteArrayOutputStream().also { o -> o.write(0); varint(o, payload.size); varint(o, type); o.write(payload) }.toByteArray()
    private fun readVarint(i: InputStream): Int {
        var v = 0; var shift = 0
        while (true) { val b = i.read(); if (b < 0) throw java.io.EOFException(); v = v or ((b and 0x7F) shl shift); if (b < 0x80) return v; shift += 7 }
    }
    /** Next frame type, or -1 at EOF. */
    private fun readType(i: InputStream): Int {
        val z = i.read(); if (z < 0) return -1
        val len = readVarint(i); val type = readVarint(i)
        var left = len; while (left > 0) { val n = i.skip(left.toLong()).toInt(); if (n <= 0) { if (i.read() < 0) return -1; left-- } else left -= n }
        return type
    }

    /** On a fake clock that only the test moves, so a slow machine can't change the outcome (#162). */
    @Test
    fun quietClientsArePingedAndKeptWhileTheyAnswer() {
        val keepalive = 600L; val unresponsive = 1_500L
        val now = AtomicLong(1_000_000)
        val s = server(keepalive, unresponsive).apply { clock = now::get }
        s.start()
        try {
            Socket(InetAddress.getLoopbackAddress(), s.localPort).use { sock ->
                sock.soTimeout = 10_000
                val out = sock.getOutputStream(); val inp = sock.getInputStream()
                fun send(type: Int, payload: ByteArray = ByteArray(0)) { out.write(frame(type, payload)); out.flush() }
                fun await(type: Int) {
                    while (true) {
                        val t = readType(inp)
                        assertTrue("server closed a client that answers pings", t != -1)
                        if (t == type) return
                    }
                }
                // The server stamps traffic with the clock when it reads it, so the clock only moves
                // after a round trip shows that the server has read everything we sent.
                fun roundTrip() { send(Msg.PING_REQUEST); await(Msg.PING_RESPONSE) }
                fun at(t: Long) { now.set(t); s.keepaliveTick() }
                send(Msg.HELLO_REQUEST, ProtoWriter().string(1, "Home Assistant test").uint32(2, 1).uint32(3, 10).toByteArray())
                send(Msg.SUBSCRIBE_STATES_REQUEST)
                roundTrip()
                var answered = now.get()

                // Quiet but answering for 5 keepalive periods (twice unresponsiveMs) while states stream.
                repeat(5) {
                    counter.incrementAndGet(); s.publishStates()
                    at(answered + keepalive + 1); await(Msg.PING_REQUEST)
                    send(Msg.PING_RESPONSE); roundTrip()
                    answered = now.get()
                }

                // Now stop answering: pinged, then dropped once unresponsiveMs have passed.
                at(answered + keepalive + 1); await(Msg.PING_REQUEST)
                counter.incrementAndGet(); s.publishStates()
                at(answered + unresponsive)
                assertEquals("dropped before unresponsiveMs had passed", 1, s.connectionCount)
                // Checked on the socket: the server's own timer may be closing it while connectionCount still counts it.
                at(answered + unresponsive + 1)
                while (readType(inp) != -1) Unit
            }
        } finally { s.stop() }
    }

    /**
     * Holds the server's write of its keepalive ping, after the bytes have left, until the reader thread
     * has read and handled the client's answer. The ping is told apart by its bytes, not by a flag the
     * test sets: a flag could be taken by the flush of an earlier reply still in progress (#204).
     */
    private class PingHold {
        @Volatile private var pingWritten = false
        @Volatile private var readAfterPing = 0
        private val handled = CountDownLatch(1)
        /** Counted down once the held write returns, so the answer was handled before it did. */
        val released = CountDownLatch(1)

        /** Accepts into a socket whose streams are held; it is the accepted socket, so close() closes it. */
        fun serverSocket() = object : ServerSocket() {
            override fun accept(): Socket = HeldSocket().also { implAccept(it) }
        }

        fun giveUp() = handled.countDown()

        inner class HeldSocket : Socket() {
            private val inp by lazy { HeldIn(super.getInputStream()) }
            private val out by lazy { HeldOut(super.getOutputStream()) }
            override fun getInputStream(): InputStream = inp
            override fun getOutputStream(): OutputStream = out
        }

        inner class HeldOut(o: OutputStream) : FilterOutputStream(o) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                // A write of just a plaintext PingRequest: 00, length 0, type 7.
                val ping = !pingWritten && len == 3 && b[off].toInt() == 0 && b[off + 1].toInt() == 0 &&
                    b[off + 2].toInt() == Msg.PING_REQUEST
                if (ping) pingWritten = true
                out.write(b, off, len)
                if (ping) { handled.await(); released.countDown() }
            }
        }

        /** The reader asks for more only once it has handled the last frame (lastRead, pingSent). */
        inner class HeldIn(i: InputStream) : FilterInputStream(i) {
            private fun entered() { if (pingWritten && readAfterPing >= 3) handled.countDown() }
            private fun got(n: Int) = n.also { if (pingWritten && it > 0) readAfterPing += it }
            override fun read(): Int { entered(); return `in`.read().also { if (it >= 0) got(1) } }
            override fun read(b: ByteArray, off: Int, len: Int): Int { entered(); return got(`in`.read(b, off, len)) }
        }
    }

    /**
     * #171: the answer to a keepalive ping can be read before the ping's write returns. Here it always
     * is, and the client must not be dropped at unresponsiveMs for a ping it answered. Without real
     * traffic after the answer: the test above sends its own ping, which hides this. Only latches order
     * it (#204); the waits with a bound fail with the threads' state instead of letting the write through.
     */
    @Test
    fun anAnswerReadBeforeThePingsSendReturnsKeepsTheClient() {
        val keepalive = 600L; val unresponsive = 1_500L
        val start = 1_000_000L
        val now = AtomicLong(start)
        val hold = PingHold()
        val s = server(keepalive, unresponsive).apply {
            // The server's own timer sees a clock that never moves, so only this test's ticks act.
            clock = { if (Thread.currentThread().name == "esphome-keepalive") start else now.get() }
            newServerSocket = hold::serverSocket
        }
        s.start()
        try {
            Socket(InetAddress.getLoopbackAddress(), s.localPort).use { sock ->
                sock.soTimeout = 10_000
                val out = sock.getOutputStream(); val inp = sock.getInputStream()
                fun send(type: Int, payload: ByteArray = ByteArray(0)) { out.write(frame(type, payload)); out.flush() }
                fun await(type: Int, what: String) {
                    while (true) {
                        val t = try { readType(inp) } catch (e: java.net.SocketTimeoutException) {
                            throw AssertionError("no $what within 10 s\n${serverThreads()}", e)
                        }
                        assertTrue("server closed a client that answered its ping", t != -1)
                        if (t == type) return
                    }
                }
                send(Msg.HELLO_REQUEST, ProtoWriter().string(1, "Home Assistant test").uint32(2, 1).uint32(3, 10).toByteArray())
                send(Msg.PING_REQUEST); await(Msg.PING_RESPONSE, "answer to the client's ping")

                val pinged = now.get() + keepalive + 1
                now.set(pinged)
                // On its own thread: before #213 the tick itself wrote the ping, and the hold held it.
                val tick = Thread({ s.keepaliveTick() }, "test-tick").apply { isDaemon = true; start() }
                await(Msg.PING_REQUEST, "keepalive ping")
                send(Msg.PING_RESPONSE)
                assertTrue("the ping's write never returned\n${serverThreads()}", hold.released.await(10, TimeUnit.SECONDS))
                tick.join(10_000)
                assertFalse("keepaliveTick still running\n${serverThreads()}", tick.isAlive)

                now.set(pinged + unresponsive + 1); s.keepaliveTick()
                assertEquals("dropped a client that answered the ping", 1, s.connectionCount)
                await(Msg.PING_REQUEST, "next keepalive ping")
            }
        } finally { hold.giveUp(); s.stop() }
    }

    /** Against the real client Home Assistant 2026.11 pins (aioesphomeapi 46.25.0), via ESPHOME_PYTHON. */
    @Test
    fun homeAssistantStaysConnectedWhileStatesStream() {
        val python = System.getenv("ESPHOME_PYTHON")
        assumeTrue("set ESPHOME_PYTHON to a python with aioesphomeapi installed", !python.isNullOrBlank())
        val s = server(keepaliveMs = 1_000, unresponsiveMs = 2_500)
        val hellos = Collections.synchronizedList(ArrayList<String>())
        s.onClientHello = { info, _ -> hellos += info }
        s.start()
        val ticker = Thread {
            try { while (true) { counter.incrementAndGet(); s.publishStates(); Thread.sleep(250) } } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
        try {
            val script = File(javaClass.classLoader!!.getResource("esphome_keepalive.py")!!.toURI())
            // 8 s: several keepalive periods and three times the "unresponsive" limit
            // Output to a file, so a client that hangs with stdout open can't block the timed wait.
            val log = File.createTempFile("esphome_keepalive", ".log").apply { deleteOnExit() }
            val proc = ProcessBuilder(python, "-I", script.absolutePath, s.localPort.toString(), "8")
                .redirectErrorStream(true).redirectOutput(log).start()
            val finished = try {
                proc.waitFor(60, TimeUnit.SECONDS)
            } finally {
                if (proc.isAlive) proc.destroyForcibly()
            }
            val out = log.readText()
            assertTrue("client timed out:\n$out", finished)
            assertEquals("client failed:\n$out", 0, proc.exitValue())
            val r = JSONObject(out.lines().first { it.startsWith("RESULT ") }.removePrefix("RESULT "))
            assertTrue("dropped mid-way:\n$out", r.getBoolean("connected_throughout"))
            assertTrue("states streamed: $r", r.getInt("states") > 10)
            assertEquals("reconnects: $hellos", 1, hellos.size)
        } finally { ticker.interrupt(); s.stop() }
    }
}
