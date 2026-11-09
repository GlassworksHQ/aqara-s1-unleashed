package io.github.aguilaair.s1kiosk.voice

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Tiny log facade: android.util.Log on the device, stdout in JVM unit tests. */
internal object VoiceLog {
    private const val TAG = "S1Voice"
    private val onAndroid = System.getProperty("java.vendor")?.contains("Android") == true

    fun d(msg: String) = if (onAndroid) { android.util.Log.d(TAG, msg); Unit } else println("D/$TAG: $msg")
    fun i(msg: String) = if (onAndroid) { android.util.Log.i(TAG, msg); Unit } else println("I/$TAG: $msg")
    fun w(msg: String, t: Throwable? = null) =
        if (onAndroid) { android.util.Log.w(TAG, msg, t); Unit } else println("W/$TAG: $msg ${t ?: ""}")
}

/**
 * Wyoming TCP server. One reader thread per connection; writes are serialized per connection.
 * Several connections may be open at once (Home Assistant opens short-lived ones to `describe`).
 */
class WyomingServer(
    private val port: Int,
    private val handler: Handler,
) {
    interface Handler {
        fun onEvent(conn: Connection, event: WyomingEvent)
        fun onClosed(conn: Connection)
    }

    class Connection internal constructor(val id: Int, private val socket: Socket) {
        val remote: String = socket.remoteSocketAddress?.toString() ?: "?"
        private val out: OutputStream = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
        private val writeLock = Any()
        private val closed = AtomicBoolean(false)

        /** Monotonic ms of the last event received on this connection. */
        @Volatile var lastReceivedAt: Long = nowMs()
            internal set

        val isOpen: Boolean get() = !closed.get()

        /** Sends an event; returns false (and closes the connection) on I/O failure. */
        fun send(event: WyomingEvent): Boolean {
            if (closed.get()) return false
            return try {
                val bytes = WyomingCodec.encode(event)
                synchronized(writeLock) {
                    out.write(bytes)
                    out.flush()
                }
                true
            } catch (e: IOException) {
                VoiceLog.w("conn#$id send ${event.type} failed: ${e.message}")
                close()
                false
            }
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                try { socket.close() } catch (_: IOException) {}
            }
        }

        override fun toString() = "conn#$id($remote)"
    }

    private val running = AtomicBoolean(false)
    private val nextId = AtomicInteger(1)
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<Connection>()
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** The bound port (useful when constructed with port 0), or -1 if not bound. */
    val localPort: Int get() = serverSocket?.localPort ?: -1

    /** Binds synchronously (throws on failure) and starts accepting in the background. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            running.set(false)
            try { ss.close() } catch (_: IOException) {}
            throw e
        }
        serverSocket = ss
        VoiceLog.i("Wyoming server listening on port ${ss.localPort}")
        acceptThread = Thread({ acceptLoop(ss) }, "wyoming-accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        for (c in connections) c.close()
        connections.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (running.get()) VoiceLog.w("accept failed", e)
                break
            }
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
            } catch (_: SocketException) {}
            val conn = Connection(nextId.getAndIncrement(), socket)
            connections += conn
            VoiceLog.i("Wyoming client connected: $conn")
            Thread({ readLoop(conn, socket) }, "wyoming-$conn").apply { isDaemon = true; start() }
        }
    }

    private fun readLoop(conn: Connection, socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            while (running.get() && conn.isOpen) {
                val event = WyomingCodec.read(input) ?: break
                conn.lastReceivedAt = nowMs()
                try {
                    handler.onEvent(conn, event)
                } catch (e: Exception) {
                    VoiceLog.w("handler failed on ${event.type}", e)
                }
            }
        } catch (e: Exception) {
            if (conn.isOpen && running.get()) VoiceLog.w("$conn read failed: ${e.message}")
        } finally {
            conn.close()
            connections -= conn
            VoiceLog.i("Wyoming client disconnected: $conn")
            try { handler.onClosed(conn) } catch (e: Exception) { VoiceLog.w("onClosed failed", e) }
        }
    }
}

internal fun nowMs(): Long = System.nanoTime() / 1_000_000L
