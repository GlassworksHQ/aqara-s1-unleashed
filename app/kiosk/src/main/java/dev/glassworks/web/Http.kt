/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One HTTP request. Header names are lowercase. */
class HttpRequest(
    val method: String,
    /** The raw path, without the query. */
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0),
    /** The client's IP address, for the log. Null in tests that build requests by hand. */
    val remote: String? = null,
) {
    /** The `Cookie` header's pairs. */
    val cookies: Map<String, String> by lazy { HttpServer.parseCookies(headers["cookie"]) }

    /** An `application/x-www-form-urlencoded` body; empty for anything else. */
    val form: Map<String, String> by lazy {
        if (headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/x-www-form-urlencoded") emptyMap()
        else HttpServer.parseQuery(String(body, Charsets.UTF_8))
    }

    /** The path with the query, as the browser asked for it. */
    val target: String get() = if (query.isEmpty()) path else path + "?" + query.entries.joinToString("&") { (k, v) -> HttpServer.enc(k) + "=" + HttpServer.enc(v) }
}

class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun with(extra: Map<String, String>) = HttpResponse(status, headers + extra, body)

    companion object {
        fun html(status: Int, html: String, extra: Map<String, String> = emptyMap()) =
            HttpResponse(status, mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store") + extra,
                html.toByteArray(Charsets.UTF_8))

        fun text(status: Int, text: String) =
            HttpResponse(status, mapOf("Content-Type" to "text/plain; charset=utf-8", "Cache-Control" to "no-store"), text.toByteArray(Charsets.UTF_8))

        /** `303 See Other`: after a form, the browser GETs [location]. */
        fun seeOther(location: String, extra: Map<String, String> = emptyMap()) =
            HttpResponse(303, mapOf("Location" to location, "Cache-Control" to "no-store", "Content-Type" to "text/plain; charset=utf-8") + extra,
                ByteArray(0))
    }
}

/**
 * A small HTTP/1.1 server: one request per connection, a few worker threads. Enough for a page on the
 * LAN that a handful of browsers open. Bodies are capped at [maxBody] bytes; a bigger one is refused.
 * Pure JVM, so tests run it on loopback.
 */
class HttpServer(
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
    /** The address to listen on, or null for every address. Tests pass loopback (#99). */
    private val bindAddress: InetAddress? = null,
    private val log: (String) -> Unit = {},
    private val maxBody: Int = 16 * 1024,
    workers: Int = 4,
    /** Connections that may wait for a worker. Beyond that, a connection gets 503 at once and is closed. */
    queued: Int = 32,
    /**
     * The whole request (line, headers, body) must arrive within this, so a slow client can't hold a worker.
     * It counts from when a worker picks the connection up, not from accept: time in the queue comes first.
     */
    private val requestDeadlineMs: Long = 10_000L,
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val pool = ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queued)) { r ->
        Thread(r, "glassworks-http").apply { isDaemon = true }
    }
    private val ids = AtomicInteger()
    /** Every connection accepted and not yet closed, also those still waiting for a worker, so [stop] can close them. */
    private val open: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    val localPort: Int get() = serverSocket?.localPort ?: -1

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port))
        serverSocket = ss
        running = true
        Thread({
            while (running) {
                val s = try { ss.accept() } catch (e: IOException) { if (running) log("accept failed: ${e.message}"); break }
                open += s
                try {
                    pool.execute { serve(s) }
                } catch (_: RejectedExecutionException) {
                    busy(s)
                }
            }
        }, "glassworks-http-accept").apply { isDaemon = true; start() }
        log("listening on ${ss.localPort}")
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        // Tasks still in the queue never run: close their connections here, and the running ones' too.
        pool.shutdownNow()
        for (s in open) try { s.close() } catch (_: IOException) {}
        open.clear()
    }

    private fun serve(s: Socket) {
        val id = ids.incrementAndGet()
        try { serveOn(s, id) } finally { open -= s }
    }

    private fun serveOn(s: Socket, id: Int) {
        s.use { sock ->
            try {
                val input = BufferedInputStream(DeadlineInput(sock, System.nanoTime() + requestDeadlineMs * 1_000_000))
                val req = try { read(input, maxBody, sock.inetAddress?.hostAddress) } catch (e: TooLarge) {
                    write(sock.getOutputStream(), HttpResponse.text(413, "Too large"), head = false); return
                } ?: return
                val res = try { handler(req) } catch (t: Throwable) {
                    log("[$id] ${req.method} ${req.path} failed: $t")
                    HttpResponse.text(500, "Something went wrong on the panel.")
                }
                log("[$id] ${req.method} ${req.path} -> ${res.status}")
                write(sock.getOutputStream(), res, head = req.method == "HEAD")
            } catch (e: IOException) {
                log("[$id] ${e.message}")
            }
        }
    }

    /** Reads from [sock] until [deadlineNanos], however the bytes trickle in; then a [SocketTimeoutException]. */
    private class DeadlineInput(private val sock: Socket, private val deadlineNanos: Long) : InputStream() {
        private val inp = sock.getInputStream()

        private fun arm() {
            val left = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (left <= 0) throw SocketTimeoutException("request took too long")
            sock.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        override fun read(): Int { arm(); return inp.read() }

        override fun read(b: ByteArray, off: Int, len: Int): Int { arm(); return inp.read(b, off, len) }
    }

    /** Every worker is busy and the queue is full: say so and close, without reading the request. */
    private fun busy(s: Socket) {
        open -= s
        s.use { sock ->
            try {
                sock.soTimeout = 200
                write(sock.getOutputStream(), HttpResponse.text(503, "The panel is busy. Try again in a moment."))
                // Closing with the client's request unread would reset the connection before it reads the 503.
                // This runs on the accept thread: at most 300 ms per refused connection, however slowly its bytes
                // come. Refusals run one after another there, so many at once add up (20 ≈ 5 s, measured).
                sock.shutdownOutput()
                val drain = DeadlineInput(sock, System.nanoTime() + BUSY_DRAIN_MS * 1_000_000)
                val buf = ByteArray(4096)
                var total = 0
                while (total < 16 * 1024 && drain.read(buf, 0, buf.size).also { total += it } >= 0) Unit
            } catch (_: IOException) {}
        }
        log("busy: refused a connection")
    }

    private class TooLarge : IOException("request too large")

    companion object {
        /** How long a refused connection may keep the accept thread reading its request, in all. */
        private const val BUSY_DRAIN_MS = 300L

        private val REASONS = mapOf(
            200 to "OK", 302 to "Found", 303 to "See Other", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict", 413 to "Payload Too Large",
            429 to "Too Many Requests", 500 to "Internal Server Error", 502 to "Bad Gateway", 503 to "Service Unavailable",
        )

        fun reason(status: Int) = REASONS[status] ?: "Status"

        /** Reads one request, or null when the connection closed first. Public for tests. */
        fun read(input: InputStream, maxBody: Int = 16 * 1024, remote: String? = null): HttpRequest? {
            val head = ByteArrayOutputStream()
            var matched = 0
            val end = "\r\n\r\n".toByteArray()
            while (matched < 4) {
                val b = input.read()
                if (b < 0) return null
                head.write(b)
                matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
                if (head.size() > 16 * 1024) throw TooLarge()
            }
            val lines = head.toString("ISO-8859-1").split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size < 2) return null
            val headers = HashMap<String, String>()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > maxBody) throw TooLarge()
            val body = ByteArray(length)
            var got = 0
            while (got < length) {
                val n = input.read(body, got, length - got)
                if (n < 0) return null
                got += n
            }
            val target = try { URI(parts[1]) } catch (_: Exception) { return HttpRequest(parts[0], "?", emptyMap(), headers, body, remote) }
            return HttpRequest(parts[0], target.rawPath ?: "/", parseQuery(target.rawQuery), headers, body, remote)
        }

        fun write(out: OutputStream, r: HttpResponse, head: Boolean = false) {
            val sb = StringBuilder("HTTP/1.1 ${r.status} ${reason(r.status)}\r\n")
            for ((k, v) in r.headers) {
                // Set-Cookie can repeat: the value holds them joined by "\n".
                if (k.equals("Set-Cookie", ignoreCase = true)) v.split('\n').forEach { sb.append(k).append(": ").append(it).append("\r\n") }
                else sb.append(k).append(": ").append(v).append("\r\n")
            }
            sb.append("Content-Length: ").append(r.body.size).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            if (!head) out.write(r.body)
            out.flush()
        }

        /** `a=1&b=x%20y`; repeated keys keep the first value. */
        fun parseQuery(query: String?): Map<String, String> {
            if (query.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val i = part.indexOf('=')
                out.putIfAbsent(dec(if (i < 0) part else part.substring(0, i)), if (i < 0) "" else dec(part.substring(i + 1)))
            }
            return out
        }

        fun parseCookies(header: String?): Map<String, String> {
            if (header.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in header.split(';')) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                out.putIfAbsent(part.substring(0, i).trim(), part.substring(i + 1).trim())
            }
            return out
        }

        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        private fun dec(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    }
}
