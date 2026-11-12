package sh.edm.s1kiosk.sendspin

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal RFC 6455 WebSocket client for `ws://` (Sendspin uses plain ws; Noise protects the
 * payloads) and `wss://` (Home Assistant's websocket API, ThreadJoin). Handles fragmented messages, ping/pong and close; masks every client frame. No
 * `Origin` header is sent. [receive] returns whole messages with the [System.nanoTime] at which the
 * last byte was read (spec "Receive timestamps").
 */
class WsClient private constructor(private val socket: Socket, private val input: InputStream) {

    class Message(val text: Boolean, val data: ByteArray, val receivedNanos: Long)

    private val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
    private val writeLock = Any()
    private val rng = SecureRandom()
    @Volatile var closed = false; private set

    /** Sends one complete text message. */
    fun sendText(s: String) = sendFrame(0x1, s.toByteArray(Charsets.UTF_8))

    /** Sends one complete binary message. */
    fun sendBinary(b: ByteArray) = sendFrame(0x2, b)

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        synchronized(writeLock) {
            if (closed) throw IOException("WebSocket closed")
            val n = payload.size
            out.write(0x80 or opcode)
            when {
                n < 126 -> out.write(0x80 or n)
                n < 65536 -> { out.write(0x80 or 126); out.write(n ushr 8); out.write(n and 0xFF) }
                else -> { out.write(0x80 or 127); for (i in 7 downTo 0) out.write(((n.toLong() ushr (8 * i)) and 0xFF).toInt()) }
            }
            val mask = ByteArray(4).also { rng.nextBytes(it) }
            out.write(mask)
            val masked = ByteArray(n)
            for (i in 0 until n) masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            out.write(masked)
            out.flush()
        }
    }

    /** Next complete text/binary message, or null when the peer closed the connection. */
    fun receive(): Message? {
        var msgOpcode = -1
        val buf = ByteArrayOutputStream()
        while (true) {
            val b0 = input.read()
            if (b0 < 0) { closed = true; return null }
            val b1 = readByte()
            val fin = b0 and 0x80 != 0
            if (b0 and 0x70 != 0) throw IOException("WebSocket RSV bits set (no extension negotiated)")
            val opcode = b0 and 0x0F
            if (b1 and 0x80 != 0) throw IOException("masked server frame")
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte().toLong() } }
            if (len < 0 || len > MAX_MESSAGE) throw IOException("WebSocket frame too large: $len")
            val payload = readFully(len.toInt())
            when (opcode) {
                0x8 -> { // close: echo and stop
                    try { sendFrame(0x8, payload.copyOfRange(0, minOf(2, payload.size))) } catch (_: IOException) {}
                    closed = true
                    return null
                }
                0x9 -> { try { sendFrame(0xA, payload) } catch (_: IOException) {}; continue }
                0xA -> continue
                0x0 -> { if (msgOpcode < 0) throw IOException("continuation without start"); buf.write(payload) }
                0x1, 0x2 -> {
                    if (msgOpcode >= 0) throw IOException("new message inside a fragmented one")
                    msgOpcode = opcode
                    buf.write(payload)
                }
                else -> throw IOException("unknown WebSocket opcode $opcode")
            }
            if (buf.size() > MAX_MESSAGE) throw IOException("WebSocket message too large")
            if (fin && msgOpcode >= 0) return Message(msgOpcode == 0x1, buf.toByteArray(), System.nanoTime())
        }
    }

    /**
     * Closes the socket at once, without a close frame and without the write lock: this unblocks a
     * write that is stuck (the socket has no write timeout).
     */
    fun abort() {
        closed = true
        try { socket.close() } catch (_: IOException) {}
    }

    /** Sends a close frame (best effort) and closes the socket. */
    fun close(code: Int = 1000) {
        if (!closed) {
            try { sendFrame(0x8, byteArrayOf((code ushr 8).toByte(), code.toByte())) } catch (_: IOException) {}
        }
        closed = true
        try { socket.close() } catch (_: IOException) {}
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("WebSocket closed mid-frame")
        return b
    }

    private fun readFully(n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw EOFException("WebSocket closed mid-frame")
            off += r
        }
        return b
    }

    companion object {
        /** Sendspin messages are ≤ 64 KiB per Noise frame; generous cap for one WebSocket message. */
        const val MAX_MESSAGE = 4L * 1024 * 1024

        /**
         * Opens `ws://host:port/path` (or `wss://`, TLS with the system's trusted CAs and hostname
         * check) and completes the upgrade.
         */
        @Throws(IOException::class)
        fun connect(url: String, connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 30_000): WsClient {
            val uri = URI(url)
            val tls = when (uri.scheme) {
                "ws" -> false
                "wss" -> true
                else -> throw IOException("only ws:// and wss:// are supported: $url")
            }
            val host = uri.host ?: throw IOException("no host in $url")
            val port = if (uri.port > 0) uri.port else if (tls) 443 else 80
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val plain = Socket()
            var s: Socket = plain
            try {
                plain.tcpNoDelay = true
                plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
                if (tls) {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(plain, host, port, true) as SSLSocket
                    s = ssl
                    ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    ssl.soTimeout = connectTimeoutMs
                    ssl.startHandshake()
                }
                s.soTimeout = readTimeoutMs
                val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
                val hostHeader = if (host.contains(':')) "[$host]" else host
                val req = "GET $path HTTP/1.1\r\n" +
                    "Host: $hostHeader:$port\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"
                s.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
                val status = readLine(input)
                if (!status.startsWith("HTTP/1.1 101")) throw IOException("WebSocket upgrade refused: $status")
                var accept: String? = null
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0 && line.substring(0, i).trim().equals("Sec-WebSocket-Accept", true)) accept = line.substring(i + 1).trim()
                }
                val expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                if (accept != expected) throw IOException("bad Sec-WebSocket-Accept")
                return WsClient(s, input)
            } catch (e: Exception) {
                try { s.close() } catch (_: IOException) {}
                try { plain.close() } catch (_: IOException) {}
                throw if (e is IOException) e else IOException(e.message, e)
            }
        }

        private fun readLine(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) throw EOFException("connection closed during upgrade")
                if (c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
                if (sb.length > 8192) throw IOException("header line too long")
            }
            return sb.toString()
        }
    }
}
