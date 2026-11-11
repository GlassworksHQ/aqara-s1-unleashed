package sh.edm.esphome

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun quietClientsArePingedAndKeptWhileTheyAnswer() {
        val s = server(keepaliveMs = 300, unresponsiveMs = 1_000)
        s.start()
        try {
            Socket("127.0.0.1", s.localPort).use { sock ->
                sock.soTimeout = 3_000
                val out = sock.getOutputStream(); val inp = sock.getInputStream()
                out.write(frame(Msg.HELLO_REQUEST, ProtoWriter().string(1, "Home Assistant test").uint32(2, 1).uint32(3, 10).toByteArray()))
                out.write(frame(Msg.SUBSCRIBE_STATES_REQUEST)); out.flush()
                // Say nothing but answer pings for 2.5 s (well past unresponsiveMs) while states stream.
                val until = System.currentTimeMillis() + 2_500
                var pings = 0
                while (System.currentTimeMillis() < until) {
                    counter.incrementAndGet(); s.publishStates()
                    sock.soTimeout = 100
                    val t = try { readType(inp) } catch (e: SocketTimeoutException) { continue }
                    assertTrue("server closed a client that answers pings", t != -1)
                    if (t == Msg.PING_REQUEST) { pings++; out.write(frame(Msg.PING_RESPONSE)); out.flush() }
                }
                assertTrue("no keepalive ping from the server", pings > 0)
                // Now stop answering: the server drops us after unresponsiveMs.
                sock.soTimeout = 3_000
                val t0 = System.currentTimeMillis()
                while (true) { if (readType(inp) == -1) break }
                val after = System.currentTimeMillis() - t0
                // unresponsiveMs counts from our last reply, a little before t0
                assertTrue("closed after $after ms", after in 500..2_500)
            }
        } finally { s.stop() }
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
            val proc = ProcessBuilder(python, "-I", script.absolutePath, s.localPort.toString(), "8")
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            assertTrue("client timed out:\n$out", proc.waitFor(60, TimeUnit.SECONDS))
            assertEquals("client failed:\n$out", 0, proc.exitValue())
            val r = JSONObject(out.lines().first { it.startsWith("RESULT ") }.removePrefix("RESULT "))
            assertTrue("dropped mid-way:\n$out", r.getBoolean("connected_throughout"))
            assertTrue("states streamed: $r", r.getInt("states") > 10)
            assertEquals("reconnects: $hellos", 1, hellos.size)
        } finally { ticker.interrupt(); s.stop() }
    }
}
