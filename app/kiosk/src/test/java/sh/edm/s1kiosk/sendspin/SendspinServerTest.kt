package sh.edm.s1kiosk.sendspin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The JVM client against a real, strict aiosendspin 10.2.1 server (the library and version Music
 * Assistant 2.10.9 uses; src/test/resources/sendspin_server.py):
 *
 * - pair: unpaired client is held → Pairing PSK flow with the client's token on the open
 *   connection → re-handshakes → player role → clock sync + client/state → audio chunks →
 *   volume / mute commands → stream end → reconnect on the long-term PSK.
 * - pair uses the PCM-only hello (what the panel falls back to when FLAC fails); guest offers FLAC
 *   first, then PCM (the panel's default): the server must pick FLAC, send its codec_header, and
 *   send whole FLAC frames on a contiguous timeline that decode (PyAV) back to the tone.
 * - guest: unpaired access enabled + operator approval → player role → audio.
 *
 *     python3 -m venv /tmp/sendspin-venv && /tmp/sendspin-venv/bin/pip install 'aiosendspin[server]==10.2.1'
 *     SENDSPIN_PYTHON=/tmp/sendspin-venv/bin/python ./gradlew :kiosk:testDebugUnitTest
 *
 * Skipped without SENDSPIN_PYTHON.
 */
class SendspinServerTest {

    private class Hooks : SsPlayerHooks {
        val starts = Collections.synchronizedList(ArrayList<SsFormat>())
        val chunks = Collections.synchronizedList(ArrayList<SsAudioChunk>())
        @Volatile var ends = 0
        @Volatile var volume = 55
        @Volatile var muted = false
        val headers = Collections.synchronizedList(ArrayList<ByteArray?>())
        override fun onStreamStart(format: SsFormat, codecHeader: ByteArray?) { starts += format; headers += codecHeader }
        override fun onStreamClear(fromTimestampUs: Long?) {}
        override fun onStreamEnd() { ends++ }
        override fun onAudio(chunk: SsAudioChunk, receivedNanos: Long) { chunks += chunk }
        override fun onVolume(volume: Int) { this.volume = volume }
        override fun onMute(muted: Boolean) { this.muted = muted }
        override fun onOutputDelay(ms: Int) {}
        override fun playerState() = SsPlayerState(volume, muted, 0, 300, 200)
    }

    private val hello = SsHelloInfo(
        name = "S1 Panel", productName = "Panel Hub S1 Plus (S1 Kiosk)", manufacturer = "Aqara",
        softwareVersion = "S1 Kiosk test", macAddress = "02:11:22:33:44:55",
        formats = listOf(SsFormat.PCM_48K_STEREO_16), bufferCapacity = 1_500_000,
    )

    private class Py(val proc: Process) {
        val events = LinkedBlockingQueue<JSONObject>()
        val stderr = Collections.synchronizedList(ArrayList<String>())
        init {
            Thread {
                BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { l ->
                    println("PY> $l"); runCatching { events.put(JSONObject(l)) }
                }
            }.apply { isDaemon = true; start() }
            Thread {
                BufferedReader(InputStreamReader(proc.errorStream)).forEachLine { l -> println("PY! $l"); stderr += l }
            }.apply { isDaemon = true; start() }
        }

        fun expect(event: String, timeoutS: Long = 20): JSONObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutS)
            while (true) {
                val left = deadline - System.nanoTime()
                val e = events.poll(maxOf(left, 0), TimeUnit.NANOSECONDS) ?: throw AssertionError("timed out waiting for '$event'")
                if (e.optString("event") == event) return e
            }
        }
    }

    private val helloFlac = hello.copy(formats = listOf(SsFormat.FLAC_48K_STEREO_16, SsFormat.PCM_48K_STEREO_16))

    private fun python(): String? = System.getenv("SENDSPIN_PYTHON")?.takeIf { File(it).canExecute() }

    private fun start(vararg args: String): Py {
        val script = File(javaClass.classLoader!!.getResource("sendspin_server.py")!!.toURI())
        return Py(ProcessBuilder(listOf(python()!!, "-I", script.path) + args).start())
    }

    private fun runSession(url: String, store: SsStore, hooks: Hooks, unpaired: Boolean, info: SsHelloInfo = hello): Pair<SendspinSession, Thread> {
        val s = SendspinSession(url, store, info, { unpaired }, hooks)
        val t = Thread { s.run() }.apply { isDaemon = true; start() }
        return s to t
    }

    private fun assertCompliant(py: Py) {
        val bad = py.stderr.filter { it.contains("non-compliant", ignoreCase = true) || it.contains("noncompliance", ignoreCase = true) }
        assertTrue("server flagged the client: $bad", bad.isEmpty())
    }

    /** stream/start FLAC + codec_header, whole frames per chunk, a contiguous timeline, decodable audio. */
    private fun checkFlacStream(hooks: Hooks, py: Py) {
        assertEquals(SsFormat.FLAC_48K_STEREO_16, hooks.starts.first())
        val header = hooks.headers.first()!!
        assertEquals(42, header.size) // fLaC + 0x80 + 24-bit length 34 + STREAMINFO
        assertEquals("fLaC", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(0x80, header[4].toInt() and 0xFF)
        val si = FlacStreamInfo.parse(header)!!
        assertEquals(48_000, si.sampleRate); assertEquals(2, si.channels); assertEquals(16, si.bitsPerSample)
        assertTrue(header.contentEquals(si.csd0()))
        val chunks = synchronized(hooks.chunks) { hooks.chunks.toList() }
        var expectTs: Long? = null
        var samples = 0L
        val file = java.io.ByteArrayOutputStream().apply { write(header) }
        for (c in chunks) {
            val frames = FlacFrames.split(c.data, c.dataOffset, c.dataLength)
            assertTrue("chunk at ${c.timestampUs} is whole FLAC frames", frames != null && frames.isNotEmpty())
            assertTrue(frames!!.all { FlacFrames.consistent(it.header, si) })
            val n = frames.sumOf { it.blockSize }
            // The chunk's timestamp is its first sample; the next chunk starts where this one ends (±1 µs rounding).
            expectTs?.let { assertEquals("contiguous timeline", it.toDouble(), c.timestampUs.toDouble(), 1.0) }
            expectTs = c.timestampUs + n * 1_000_000L / 48_000
            samples += n
            file.write(c.data, c.dataOffset, c.dataLength)
        }
        assertTrue("got $samples samples", samples in 48_000L * 14 / 10..48_000L * 16 / 10)
        val bytes = chunks.sumOf { it.dataLength.toLong() }
        println("FLAC: ${chunks.size} chunks, ${chunks.first().let { FlacFrames.split(it.data, it.dataOffset, it.dataLength)!!.first().blockSize }} samples/frame, " +
            "${bytes * 8 * 48_000 / samples / 1000} kbit/s vs PCM 1536 kbit/s (440 Hz test tone)")
        val tmp = File.createTempFile("sendspin", ".flac").apply { deleteOnExit(); writeBytes(file.toByteArray()) }
        val script = File(javaClass.classLoader!!.getResource("sendspin_server.py")!!.toURI())
        val dec = Py(ProcessBuilder(python()!!, "-I", script.path, "decode", tmp.path).start()).expect("decoded")
        assertEquals(samples, dec.getLong("frames"))
        assertEquals(48_000, dec.getInt("sample_rate"))
        assertEquals(2, dec.getInt("channels"))
        assertEquals(8000.0, dec.getDouble("peak"), 30.0) // the server's 440 Hz tone at ±8000, lossless
        assertEquals(440.0 * samples / 48_000, dec.getDouble("crossings"), 3.0)
    }

    @Test
    fun pairingPskFlowAudioAndCommandsAgainstAiosendspin() {
        assumeTrue("SENDSPIN_PYTHON not set", python() != null)
        val store = MemoryStore()
        val token = SsCrypto.pairingToken(store.identity().publicKey, store.pairingPsk())
        val py = start("pair", token)
        try {
            val listening = py.expect("listening")
            val port = listening.getInt("port")
            val serverId = listening.getString("server_id")
            val url = "ws://127.0.0.1:$port/sendspin"
            val hooks = Hooks()
            val (s1, t1) = runSession(url, store, hooks, unpaired = false)

            assertFalse("unpaired, unapproved client must be held without roles", py.expect("connected").getBoolean("has_player"))
            py.expect("paired")
            val ready = py.expect("player_ready")
            assertEquals(55, ready.getInt("volume"))
            assertEquals(false, ready.getBoolean("muted"))
            assertEquals(300, ready.getInt("required_lead_time_ms"))
            assertEquals(200, ready.getInt("min_buffer_ms"))
            assertEquals(listOf("mute", "set_output_delay", "volume"), ready.getJSONArray("commands").let { a -> (0 until a.length()).map { a.getString(it) } })
            assertEquals("long_term", ready.getString("psk"))
            assertEquals("pcm", ready.getString("codec"))
            assertEquals(listOf(serverId), store.records().map { it.serverId })
            assertEquals(PskCategory.LONG_TERM, s1.matched)

            py.expect("stream_pushed")
            val cmd = py.expect("commands")
            assertEquals(30, cmd.getInt("volume"))
            assertTrue(cmd.getBoolean("muted"))
            assertEquals(30, hooks.volume)
            assertTrue(hooks.muted)
            py.expect("stopped")

            // Audio: PCM 48 kHz stereo 16-bit, ~1.5 s, increasing timestamps, real samples.
            assertEquals(SsFormat.PCM_48K_STEREO_16, hooks.starts.first())
            assertEquals(null, hooks.headers.first())
            val chunks = synchronized(hooks.chunks) { hooks.chunks.toList() }
            val frames = chunks.sumOf { it.dataLength / 4 }
            assertTrue("got $frames frames in ${chunks.size} chunks", frames in 48_000 * 14 / 10..48_000 * 16 / 10)
            assertTrue(chunks.zipWithNext().all { (a, b) -> b.timestampUs > a.timestampUs })
            assertTrue(chunks.all { it.sendAheadUs > 0 })
            assertTrue(chunks.any { c -> (c.dataOffset until c.data.size).any { c.data[it].toInt() != 0 } })
            Thread.sleep(500)
            assertTrue("stream/end", hooks.ends >= 1)

            val f = s1.timeFilter.snapshot
            assertTrue("time filter converged", f.converged && f.count >= 2)
            assertTrue("offset std dev ${f.offsetStdDevUs} µs", f.offsetStdDevUs < 2_000)

            // Reconnect: the stored long-term PSK admits the client directly.
            s1.close("restart")
            t1.join(5_000)
            val (s2, t2) = runSession(url, store, hooks, unpaired = false)
            val again = py.expect("reconnected")
            assertEquals("long_term", again.getString("psk"))
            assertEquals(30, again.getInt("volume"))
            py.expect("done")
            assertEquals(PskCategory.LONG_TERM, s2.matched)
            s2.close("shutdown")
            t2.join(5_000)
            assertCompliant(py)
        } finally {
            py.proc.destroy()
            py.proc.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun guestAccessWithApprovalStreamsFlac() {
        assumeTrue("SENDSPIN_PYTHON not set", python() != null)
        val store = MemoryStore()
        val py = start("guest", store.identity().peerId)
        try {
            val port = py.expect("listening").getInt("port")
            val hooks = Hooks()
            val (s, t) = runSession("ws://127.0.0.1:$port/sendspin", store, hooks, unpaired = true, info = helloFlac)
            assertFalse(py.expect("connected").getBoolean("has_player"))
            py.expect("approved")
            val ready = py.expect("player_ready")
            assertEquals("sentinel", ready.getString("psk"))
            assertEquals("flac", ready.getString("codec")) // the hello's first format
            py.expect("stream_pushed")
            py.expect("commands")
            py.expect("done")
            assertTrue(hooks.chunks.size > 10)
            checkFlacStream(hooks, py)
            assertEquals(PskCategory.SENTINEL, s.matched)
            assertTrue(store.records().isEmpty())
            s.close("shutdown")
            t.join(5_000)
            assertCompliant(py)
        } finally {
            py.proc.destroy()
            py.proc.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
