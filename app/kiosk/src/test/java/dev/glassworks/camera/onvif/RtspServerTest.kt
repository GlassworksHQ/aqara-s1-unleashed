/* SPDX-License-Identifier: MIT
 *
 * Ported from android-ip-camera: aguilaair/android-ip-camera, branch onvif-rtsp-motion, commit
 * 7b5e6f28; a fork of DigitallyRefined/android-ip-camera. Copyright (c) 2025 DigitallyRefined and
 * contributors. The licence text is in THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt.
 * Changes: package; the client signs in by default, since Glassworks has no open
 * access; new tests for no sign-in set and for no port before start(). */

package dev.glassworks.camera.onvif

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class RtspServerTest {
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0xDA.toByte(), 0x01, 0x40, 0x16, 0xE8.toByte())
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val idr = H264Bitstream.joinNals(listOf(sps, pps, ByteArray(3000) { 0x22 }.also { it[0] = 0x65 }))
    private val pFrame = H264Bitstream.joinNals(listOf(byteArrayOf(0x41, 0x9A.toByte(), 1, 2, 3)))

    private lateinit var server: RtspServer
    private val counts = CopyOnWriteArrayList<Int>()
    private val feeding = AtomicBoolean(true)
    private var port = 0

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun startServer(creds: Pair<String, String>? = USER) {
        port = freePort()
        server = RtspServer(port, { creds }, { counts += it })
        server.start()
        // Simulated encoder: keyframe every 10 frames at ~30 fps
        Thread {
            var i = 0
            while (feeding.get()) {
                server.onAccessUnit(if (i % 10 == 0) idr else pFrame, i % 10 == 0)
                i++
                Thread.sleep(33)
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun tearDown() { feeding.set(false); server.stop() }

    /** [auth]: sent as Basic on every request, as ffmpeg does with credentials in the URL. */
    private class Client(port: Int, private val auth: Pair<String, String>? = USER) {
        val sock = Socket("127.0.0.1", port).apply { soTimeout = 8000 }
        val input = DataInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        var cseq = 0

        fun request(method: String, uri: String, headers: List<String> = emptyList()): Pair<String, String> {
            val sb = StringBuilder("$method $uri RTSP/1.0\r\nCSeq: ${++cseq}\r\n")
            headers.forEach { sb.append(it).append("\r\n") }
            if (auth != null && headers.none { it.startsWith("Authorization:") }) {
                sb.append("Authorization: Basic ${Base64Lite.encode("${auth.first}:${auth.second}".toByteArray())}\r\n")
            }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray()); out.flush()
            return readResponse()
        }

        fun readResponse(): Pair<String, String> {
            // Skip any interleaved RTP that may precede the response.
            while (true) {
                input.mark(1)
                val b = input.read()
                if (b < 0) throw java.io.EOFException("closed before a response")
                if (b == '$'.code) { skipInterleavedBody(); continue }
                val head = StringBuilder().append(b.toChar())
                while (!head.endsWith("\r\n\r\n")) {
                    val c = input.read()
                    if (c < 0) throw java.io.EOFException("closed inside a response head: $head")
                    head.append(c.toChar())
                }
                val len = Regex("Content-Length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(len).also { input.readFully(it) }
                return head.toString() to String(body)
            }
        }

        private fun skipInterleavedBody() { input.read(); val len = input.readUnsignedShort(); input.skipBytes(len) }

        fun readInterleaved(): Pair<Int, ByteArray> {
            val b = input.read()
            require(b == '$'.code) { "expected interleaved frame, got $b" }
            val ch = input.read(); val len = input.readUnsignedShort()
            return ch to ByteArray(len).also { input.readFully(it) }
        }
    }

    @Test fun tcpInterleavedSessionReceivesRtp() {
        startServer()
        Thread.sleep(150) // let a keyframe cache SPS/PPS
        val c = Client(port)
        val url = "rtsp://127.0.0.1:$port/live"
        val (opt, _) = c.request("OPTIONS", url)
        assertTrue(opt.startsWith("RTSP/1.0 200 OK")); assertTrue(opt.contains("CSeq: 1")); assertTrue(opt.contains("DESCRIBE"))

        val (desc, sdp) = c.request("DESCRIBE", url, listOf("Accept: application/sdp"))
        assertTrue(desc.startsWith("RTSP/1.0 200"))
        assertTrue(desc.contains("Content-Base: $url/"))
        assertTrue(desc.contains("Content-Type: application/sdp"))
        assertTrue(sdp.contains("sprop-parameter-sets=${Base64Lite.encode(sps)},${Base64Lite.encode(pps)}"))
        assertTrue(sdp.contains("profile-level-id=42c01f"))

        val (setup, _) = c.request("SETUP", "$url/trackID=0", listOf("Transport: RTP/AVP/TCP;unicast;interleaved=0-1"))
        assertTrue(setup, setup.startsWith("RTSP/1.0 200"))
        assertTrue(setup.contains("interleaved=0-1")); assertTrue(setup.contains(";ssrc="))
        val session = Regex("Session: ([0-9A-F]+);timeout=60").find(setup)!!.groupValues[1]
        assertEquals(0, server.let { if (it.hasClients()) 1 else 0 }) // SETUP alone is not playing

        val (play, _) = c.request("PLAY", url, listOf("Session: $session", "Range: npt=0.000-"))
        assertTrue(play.startsWith("RTSP/1.0 200")); assertTrue(play.contains("RTP-Info: url=$url/trackID=0;seq="))
        // The server answers PLAY before it marks the session playing: wait for it, bounded.
        assertTrue(waitFor { server.hasClients() })

        // First packets must start with SPS (keyframe sync), share timestamp, have increasing seq.
        val all = (1..14).map { c.readInterleaved() }
        // RTCP sender reports arrive on the odd channel; RTP on channel 0.
        val rtcp = all.filter { it.first == 1 }
        assertTrue(rtcp.isNotEmpty())
        assertEquals(200, rtcp.first().second[1].toInt() and 0xff)   // SR
        val pkts = all.filter { it.first == 0 }.take(12)
        assertTrue(pkts.size >= 10)
        val first = pkts[0].second
        assertEquals(0x80, first[0].toInt() and 0xff)
        assertEquals(96, first[1].toInt() and 0x7f)
        assertEquals(7, first[12].toInt() and 0x1f)
        val seqs = pkts.map { ((it.second[2].toInt() and 0xff) shl 8) or (it.second[3].toInt() and 0xff) }
        for (i in 1 until seqs.size) assertEquals((seqs[i - 1] + 1) and 0xffff, seqs[i])
        assertTrue(pkts.any { it.second[1].toInt() and 0x80 != 0 })   // marker seen

        val (td, _) = c.request("TEARDOWN", url, listOf("Session: $session"))
        assertTrue(td.startsWith("RTSP/1.0 200"))
        assertTrue(waitFor { !server.hasClients() && counts.lastOrNull() == 0 })
        assertTrue(counts.contains(1))
        c.sock.close()
    }

    @Test fun digestAuthIsRequired() {
        startServer("admin" to "pw")
        val c = Client(port, auth = null)
        val url = "rtsp://127.0.0.1:$port/live"
        assertTrue(c.request("OPTIONS", url).first.startsWith("RTSP/1.0 200"))
        val (r401, _) = c.request("DESCRIBE", url)
        assertTrue(r401.startsWith("RTSP/1.0 401"))
        val nonce = Regex("nonce=\"([0-9a-f]+)\"").find(r401)!!.groupValues[1]
        val ha1 = RtspAuth.md5Hex("admin:Glassworks:pw")
        val resp = RtspAuth.md5Hex("$ha1:$nonce:${RtspAuth.md5Hex("DESCRIBE:$url")}")
        val (ok, _) = c.request("DESCRIBE", url, listOf(
            "Authorization: Digest username=\"admin\", realm=\"Glassworks\", nonce=\"$nonce\", uri=\"$url\", response=\"$resp\""))
        assertTrue(ok, ok.startsWith("RTSP/1.0 200"))
        c.sock.close()
    }

    @Test fun describeWaitsForFirstKeyframeAndReportsDemand() {
        port = freePort()
        server = RtspServer(port, { USER }, { counts += it })
        server.start()
        feeding.set(false)
        Thread {
            // Encoder only starts once a client signals demand.
            while (counts.isEmpty()) Thread.sleep(10)
            server.onAccessUnit(idr, true)
        }.apply { isDaemon = true; start() }
        val c = Client(port)
        val (desc, sdp) = c.request("DESCRIBE", "rtsp://127.0.0.1:$port/live")
        assertTrue(desc.startsWith("RTSP/1.0 200"))
        assertTrue(sdp.contains("sprop-parameter-sets="))
        assertEquals(1, counts.first())
        c.sock.close()
    }

    @Test fun unknownSessionAndBadTransport() {
        startServer()
        val c = Client(port)
        val url = "rtsp://127.0.0.1:$port/live"
        assertTrue(c.request("PLAY", url, listOf("Session: DEADBEEF")).first.startsWith("RTSP/1.0 454"))
        assertTrue(c.request("SETUP", "$url/trackID=0", listOf("Transport: RTP/AVP;multicast")).first.startsWith("RTSP/1.0 461"))
        assertTrue(c.request("RECORD", url).first.startsWith("RTSP/1.0 501"))
        c.sock.close()
    }

    @Test fun withNoSignInSetNobodyGetsTheStream() {
        startServer(null)
        val c = Client(port, auth = "admin" to "")
        val url = "rtsp://127.0.0.1:$port/live"
        assertTrue(c.request("OPTIONS", url).first.startsWith("RTSP/1.0 200"))
        assertTrue(c.request("DESCRIBE", url).first.startsWith("RTSP/1.0 401"))
        assertTrue(c.request("SETUP", "$url/trackID=0", listOf("Transport: RTP/AVP/TCP;unicast;interleaved=0-1")).first.startsWith("RTSP/1.0 401"))
        c.sock.close()
    }

    @Test fun aUsernameWithABlankPasswordIsNoSignIn() {
        startServer("admin" to "")
        val c = Client(port, auth = "admin" to "")
        val url = "rtsp://127.0.0.1:$port/live"
        assertTrue(c.request("DESCRIBE", url).first.startsWith("RTSP/1.0 401"))
        assertTrue(c.request("PLAY", url, listOf("Session: DEADBEEF")).first.startsWith("RTSP/1.0 401"))
        c.sock.close()
    }

    @Test fun aPortClashThrowsAndALaterStartBinds() {
        port = freePort()
        server = RtspServer(port, { USER }, { counts += it })
        val squatter = ServerSocket(port)
        assertTrue(runCatching { server.start() }.isFailure)
        squatter.close()
        assertTrue(bindsWithin(port))
        server.start()
        val c = Client(port)
        assertTrue(c.request("OPTIONS", "rtsp://127.0.0.1:$port/live").first.startsWith("RTSP/1.0 200"))
        c.sock.close()
    }

    @Test fun clientCountsReachTheHostInOrderEvenWhenItsCallbackIsSlow() {
        port = freePort()
        val firstIn = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server = RtspServer(port, { USER }, { n ->
            if (n == 1 && firstIn.count > 0) { firstIn.countDown(); release.await() } // a host that takes its time
            counts += n
        })
        server.start()
        Thread { var i = 0; while (feeding.get()) { server.onAccessUnit(if (i++ % 10 == 0) idr else pFrame, i % 10 == 1); Thread.sleep(33) } }
            .apply { isDaemon = true; start() }
        Thread.sleep(150)
        fun play(c: Client): String {
            val url = "rtsp://127.0.0.1:$port/live"
            c.request("DESCRIBE", url, listOf("Accept: application/sdp"))
            val setup = c.request("SETUP", "$url/trackID=0", listOf("Transport: RTP/AVP/TCP;unicast;interleaved=0-1")).first
            val session = Regex("Session: ([0-9A-F]+)").find(setup)!!.groupValues[1]
            return c.request("PLAY", url, listOf("Session: $session")).first
        }
        val a = Client(port); val b = Client(port)
        assertTrue(play(a).startsWith("RTSP/1.0 200"))
        assertTrue(firstIn.await(5, java.util.concurrent.TimeUnit.SECONDS)) // the host is busy with "1"
        assertTrue(play(b).startsWith("RTSP/1.0 200"))
        Thread.sleep(200)
        release.countDown()
        assertTrue(waitFor { counts.size >= 2 })
        Thread.sleep(100)
        assertEquals("the host ends on the true count", listOf(1, 2), counts.toList())
        a.sock.close(); b.sock.close()
    }

    private fun playOn(c: Client): Pair<String, String> {
        val url = "rtsp://127.0.0.1:$port/live"
        val (_, sdp) = c.request("DESCRIBE", url, listOf("Accept: application/sdp"))
        val setup = c.request("SETUP", "$url/trackID=0", listOf("Transport: RTP/AVP/TCP;unicast;interleaved=0-1")).first
        val session = Regex("Session: ([0-9A-F]+)").find(setup)!!.groupValues[1]
        return c.request("PLAY", url, listOf("Session: $session")).first to sdp
    }

    @Test fun connectionsOverTheCapAreClosedAtOnce() {
        port = freePort()
        server = RtspServer(port, { USER }, { counts += it }, maxConnections = 2)
        server.start()
        val a = Client(port); val b = Client(port)
        assertTrue(a.request("OPTIONS", "rtsp://127.0.0.1:$port/live").first.startsWith("RTSP/1.0 200"))
        assertTrue(b.request("OPTIONS", "rtsp://127.0.0.1:$port/live").first.startsWith("RTSP/1.0 200"))
        val third = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
        assertEquals("closed without a word", -1, third.getInputStream().read())
        a.sock.close(); b.sock.close(); third.close()
    }

    @Test fun aSilentConnectionIsClosedButAPlayingOneIsNot() {
        port = freePort()
        server = RtspServer(port, { USER }, { counts += it }, handshakeTimeoutMs = 300)
        server.start()
        Thread { var i = 0; while (feeding.get()) { server.onAccessUnit(if (i++ % 10 == 0) idr else pFrame, i % 10 == 1); Thread.sleep(33) } }
            .apply { isDaemon = true; start() }
        Thread.sleep(150)
        val idle = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
        assertEquals("nothing sent: closed after the handshake timeout", -1, idle.getInputStream().read())
        idle.close()
        val c = Client(port)
        val play = playOn(c).first
        assertTrue(play.startsWith("RTSP/1.0 200"))
        val session = Regex("Session: ([0-9A-F]+)").find(play)!!.groupValues[1]
        Thread.sleep(900) // three timeouts long, sending nothing
        // Still open: a request now gets an answer (the reader skips the RTP in between).
        val (alive, _) = c.request("GET_PARAMETER", "rtsp://127.0.0.1:$port/live", listOf("Session: $session"))
        assertTrue(alive, alive.startsWith("RTSP/1.0 200"))
        c.sock.close()
    }

    @Test fun optionsCantKeepAConnectionOpenWithoutSigningIn() {
        port = freePort()
        server = RtspServer(port, { USER }, { counts += it }, handshakeTimeoutMs = 400)
        server.start()
        val c = Client(port, auth = null)
        val url = "rtsp://127.0.0.1:$port/live"
        val closed = runCatching { repeat(20) { c.request("OPTIONS", url); Thread.sleep(100) } }.isFailure
        assertTrue("closed within the handshake time although OPTIONS kept coming", closed)
        c.sock.close()
    }

    /** Sends [bytes] every 100 ms for 3 s; true if the server closed the connection meanwhile (a write failed). */
    private fun closedWhileSending(bytes: ByteArray): Boolean {
        val sock = Socket("127.0.0.1", port)
        val out = sock.getOutputStream()
        val closed = runCatching { repeat(30) { out.write(bytes); out.flush(); Thread.sleep(100) } }.isFailure
        sock.close()
        return closed
    }

    @Test fun interleavedFramesCantKeepAConnectionOpenWithoutSigningIn() {
        port = freePort()
        server = RtspServer(port, { USER }, { counts += it }, handshakeTimeoutMs = 400)
        server.start()
        // Kept busy the whole time, so the read timeout never fires: only the deadline can close it.
        assertTrue(closedWhileSending(byteArrayOf('$'.code.toByte(), 1, 0, 4, 0x80.toByte(), 201.toByte(), 0, 1)))
    }

    @Test fun strayLineBreaksCantKeepAConnectionOpenWithoutSigningIn() {
        port = freePort()
        server = RtspServer(port, { USER }, { counts += it }, handshakeTimeoutMs = 400)
        server.start()
        // The reader skips these without returning: the reaper has to close it.
        assertTrue(closedWhileSending(byteArrayOf('\r'.code.toByte())))
    }

    @Test fun theSdpCarriesTheFrameRateWhenKnown() {
        port = freePort()
        server = RtspServer(port, { USER }, { counts += it }, fps = 15)
        server.start()
        server.onAccessUnit(idr, true)
        val (_, sdp) = Client(port).request("DESCRIBE", "rtsp://127.0.0.1:$port/live", listOf("Accept: application/sdp"))
        assertTrue(sdp, sdp.contains("a=framerate:15\r\n"))
        assertTrue(!RtspSdp.build(null, null, "192.0.2.41", 1).contains("a=framerate"))
    }

    @Test fun theStreamCarriesThePanelsName() {
        port = freePort()
        server = RtspServer(port, { USER }, { counts += it }, name = "Hall Panel")
        server.start()
        server.onAccessUnit(idr, true)
        val c = Client(port, auth = null)
        val url = "rtsp://127.0.0.1:$port/live"
        val (r401, _) = c.request("DESCRIBE", url)
        assertTrue(r401.contains("realm=\"Hall Panel\""))
        assertTrue(r401.contains("Server: Hall Panel"))
        val (_, sdp) = Client(port).request("DESCRIBE", url, listOf("Accept: application/sdp"))
        assertTrue(sdp.contains("s=Hall Panel"))
        assertTrue(!sdp.contains("Android") && !r401.contains("Android"))
        c.sock.close()
    }

    @Test fun nothingListensUntilStart() {
        port = freePort()
        server = RtspServer(port, { USER }, { counts += it })
        assertTrue("free: the constructor opened nothing", bindsWithin(port))
        server.start()
        assertTrue("taken while it runs", runCatching { ServerSocket(port).close() }.isFailure)
        server.stop()
        assertTrue("and stop() let it go", bindsWithin(port))
    }

    /** Binds [port] and lets it go, retrying until [deadlineMs]: a just-closed socket can take a moment to free on a busy machine. */
    private fun bindsWithin(port: Int, deadlineMs: Long = 5_000): Boolean {
        val until = System.currentTimeMillis() + deadlineMs
        while (true) {
            val ok = runCatching { java.net.ServerSocket(port).close() }.isSuccess
            if (ok || System.currentTimeMillis() > until) return ok
            Thread.sleep(50)
        }
    }

    private fun waitFor(ms: Long = 3_000, ok: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) { if (ok()) return true; Thread.sleep(10) }
        return ok()
    }

    private companion object {
        val USER = "admin" to "pw"
    }

    @Suppress("unused") private fun InputStream.drain() { while (available() > 0) read() }
}
