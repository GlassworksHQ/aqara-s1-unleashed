package sh.edm.esphome

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** A plaintext client standing in for Home Assistant: says hello, then sends and reads raw frames. */
internal class PlainClient(port: Int) : AutoCloseable {
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

    /** True if the server closes the connection, with nothing more to read, within [timeoutMs]. */
    fun closedByServer(timeoutMs: Int = 10_000): Boolean {
        socket.soTimeout = timeoutMs
        return try { inp.read() == -1 } catch (_: SocketTimeoutException) { false }
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

/** The server's threads and the test's, with where each one is, for a failure message. */
internal fun serverThreads(): String = Thread.getAllStackTraces().entries
    .filter { (t, _) -> t.name.startsWith("esphome") || t.name.startsWith("test-") }
    .joinToString("\n") { (t, st) -> "  ${t.name} ${t.state} at ${st.take(5).joinToString(" < ")}" }
