package sh.edm.s1kiosk.radio.ezsp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The small EZSP v12 subset the Zigbee router host needs (docs/radio.md "Zigbee router"), against
 * zigbeed (EmberZNet 7.3.3, EZSP 12). Frame ids and struct layouts as in bellows' v12 schema.
 */
object Ezsp {
    const val PROTOCOL_VERSION = 12
    const val STACK_TYPE_MESH = 2

    // frame ids
    const val VERSION = 0x0000
    const val ADD_ENDPOINT = 0x0002
    const val NOP = 0x0005
    const val NETWORK_INIT = 0x0017
    const val NETWORK_STATE = 0x0018
    const val STACK_STATUS_HANDLER = 0x0019
    const val START_SCAN = 0x001A
    const val NETWORK_FOUND_HANDLER = 0x001B
    const val SCAN_COMPLETE_HANDLER = 0x001C
    const val JOIN_NETWORK = 0x001F
    const val LEAVE_NETWORK = 0x0020
    const val CHILD_JOIN_HANDLER = 0x0023
    const val GET_EUI64 = 0x0026
    const val GET_NODE_ID = 0x0027
    const val GET_NETWORK_PARAMETERS = 0x0028
    const val SEND_UNICAST = 0x0034
    const val MESSAGE_SENT_HANDLER = 0x003F
    const val INCOMING_MESSAGE_HANDLER = 0x0045
    const val SET_CONFIGURATION_VALUE = 0x0053
    const val INVALID_COMMAND = 0x0058
    const val SET_INITIAL_SECURITY_STATE = 0x0068
    const val UPDATE_TC_LINK_KEY = 0x006C
    const val NEIGHBOR_COUNT = 0x007A

    // EmberStatus
    const val SUCCESS = 0x00
    const val NETWORK_UP = 0x90
    const val NETWORK_DOWN = 0x91
    const val NOT_JOINED = 0x93
    const val JOIN_FAILED = 0x94

    // EmberNodeType
    const val COORDINATOR = 1
    const val ROUTER = 2

    // EmberNetworkStatus
    const val NO_NETWORK = 0
    const val JOINED_NETWORK = 2

    // EzspConfigId
    const val CONFIG_STACK_PROFILE = 0x0C
    const val CONFIG_SECURITY_LEVEL = 0x0D

    // EmberInitialSecurityBitmask, as EmberZNet's network-steering plugin sets it for joining
    const val TRUST_CENTER_GLOBAL_LINK_KEY = 0x0004
    const val HAVE_PRECONFIGURED_KEY = 0x0100
    const val REQUIRE_ENCRYPTED_KEY = 0x0800
    const val NO_FRAME_COUNTER_RESET = 0x1000

    /** Zigbee 3.0 well-known TC link key ("ZigBeeAlliance09"), so the trust center sends the network key. */
    val ZIGBEE_ALLIANCE_09: ByteArray = "ZigBeeAlliance09".toByteArray(Charsets.US_ASCII)

    /** All 2.4 GHz channels, 11-26. */
    const val ALL_CHANNELS = 0x07FFF800

    fun statusName(s: Int): String = when (s) {
        SUCCESS -> "SUCCESS"; NETWORK_UP -> "NETWORK_UP"; NETWORK_DOWN -> "NETWORK_DOWN"
        NOT_JOINED -> "NOT_JOINED"; JOIN_FAILED -> "JOIN_FAILED"; 0x70 -> "INVALID_CALL"
        0x98 -> "CANNOT_JOIN_AS_ROUTER"; 0xAB -> "NO_BEACONS"; 0xAC -> "RECEIVED_KEY_IN_THE_CLEAR"
        0xAD -> "NO_NETWORK_KEY_RECEIVED"; 0xAE -> "NO_LINK_KEY_RECEIVED"; 0xAF -> "PRECONFIGURED_KEY_REQUIRED"
        0xA1 -> "NETWORK_BUSY"; 0x36 -> "MAC_COMMAND_TRANSMIT_FAILURE"
        else -> "0x%02X".format(s)
    }

    // ------------------------------------------------------------------ framing

    /** Legacy frame (only `version` uses it): seq, frame control 0x00, frame id 0x00. */
    fun legacyVersion(seq: Int, desired: Int) = byteArrayOf(seq.toByte(), 0, 0, desired.toByte())

    /** Extended frame (EZSP v8+): seq, frame control 0x0100 (command, frame format 1), frame id LE16. */
    fun command(seq: Int, frameId: Int, params: ByteArray = ByteArray(0)) =
        byteArrayOf(seq.toByte(), 0x00, 0x01, frameId.toByte(), (frameId shr 8).toByte()) + params

    data class Received(val seq: Int, val frameId: Int, val isResponse: Boolean, val isCallback: Boolean, val params: ByteArray) {
        override fun equals(other: Any?) = other is Received && other.seq == seq && other.frameId == frameId &&
            other.params.contentEquals(params)
        override fun hashCode() = seq * 31 + frameId
    }

    /** Parses an extended frame from the NCP; null if too short. */
    fun parse(b: ByteArray): Received? {
        if (b.size < 5) return null
        val fcLo = b[1].toInt() and 0xFF
        val id = (b[3].toInt() and 0xFF) or ((b[4].toInt() and 0xFF) shl 8)
        val cbType = (fcLo shr 3) and 3
        return Received(b[0].toInt() and 0xFF, id, fcLo and 0x80 != 0, cbType != 0, b.copyOfRange(5, b.size))
    }

    // ------------------------------------------------------------------ structs (little endian)

    class Writer {
        private val o = ByteArrayOutputStream()
        fun u8(v: Int) = apply { o.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun u32(v: Int) = apply { u16(v); u16(v ushr 16) }
        fun bytes(b: ByteArray) = apply { o.write(b) }
        fun lv(b: ByteArray) = apply { u8(b.size); bytes(b) }
        fun build(): ByteArray = o.toByteArray()
    }

    class Reader(private val b: ByteArray, private var i: Int = 0) {
        fun u8(): Int = b[i++].toInt() and 0xFF
        fun s8(): Int = b[i++].toInt()
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Int = u16() or (u16() shl 16)
        fun bytes(n: Int): ByteArray = b.copyOfRange(i, i + n).also { i += n }
        fun lv(): ByteArray = bytes(u8())
        val remaining get() = b.size - i
    }

    /** EmberNetworkParameters (20 bytes). [extendedPanId] stays in wire order (opaque). */
    data class NetworkParameters(
        val extendedPanId: ByteArray, val panId: Int, val radioTxPower: Int, val radioChannel: Int,
        val joinMethod: Int = 0, val nwkManagerId: Int = 0, val nwkUpdateId: Int = 0, val channels: Int = 0,
    ) {
        fun write(w: Writer) = w.bytes(extendedPanId).u16(panId).u8(radioTxPower).u8(radioChannel).u8(joinMethod)
            .u16(nwkManagerId).u8(nwkUpdateId).u32(channels)
        companion object {
            fun read(r: Reader) = NetworkParameters(r.bytes(8), r.u16(), r.u8(), r.u8(), r.u8(), r.u16(), r.u8(), r.u32())
        }
        override fun equals(other: Any?) = other is NetworkParameters && other.extendedPanId.contentEquals(extendedPanId) &&
            other.panId == panId && other.radioChannel == radioChannel && other.radioTxPower == radioTxPower &&
            other.joinMethod == joinMethod && other.nwkManagerId == nwkManagerId && other.nwkUpdateId == nwkUpdateId &&
            other.channels == channels
        override fun hashCode() = panId * 31 + radioChannel
    }

    /** networkFoundHandler: EmberZigbeeNetwork + lastHopLqi + lastHopRssi. */
    data class FoundNetwork(
        val channel: Int, val panId: Int, val extendedPanId: ByteArray, val allowingJoin: Boolean,
        val stackProfile: Int, val nwkUpdateId: Int, val lqi: Int, val rssi: Int,
    ) {
        companion object {
            fun read(p: ByteArray): FoundNetwork {
                val r = Reader(p)
                return FoundNetwork(r.u8(), r.u16(), r.bytes(8), r.u8() != 0, r.u8(), r.u8(), r.u8(), r.s8())
            }
        }
        override fun equals(other: Any?) = other is FoundNetwork && other.extendedPanId.contentEquals(extendedPanId) &&
            other.panId == panId && other.channel == channel
        override fun hashCode() = panId * 31 + channel
    }

    /** EmberInitialSecurityState for a joining router: well-known TC link key, no network key. */
    fun joinSecurityState(): ByteArray = Writer()
        .u16(TRUST_CENTER_GLOBAL_LINK_KEY or HAVE_PRECONFIGURED_KEY or REQUIRE_ENCRYPTED_KEY or NO_FRAME_COUNTER_RESET)
        .bytes(ZIGBEE_ALLIANCE_09)
        .bytes(ByteArray(16))   // network key: none (the trust center sends it)
        .u8(0)                  // network key sequence number
        .bytes(ByteArray(8))    // preconfigured trust center EUI64: unknown
        .build()

    /** EmberApsFrame (11 bytes). */
    data class ApsFrame(val profileId: Int, val clusterId: Int, val srcEp: Int, val dstEp: Int, val options: Int, val groupId: Int, val sequence: Int) {
        fun write(w: Writer) = w.u16(profileId).u16(clusterId).u8(srcEp).u8(dstEp).u16(options).u16(groupId).u8(sequence)
        companion object { fun read(r: Reader) = ApsFrame(r.u16(), r.u16(), r.u8(), r.u8(), r.u16(), r.u16(), r.u8()) }
    }

    /** incomingMessageHandler (v12). */
    data class Incoming(val type: Int, val aps: ApsFrame, val lqi: Int, val rssi: Int, val sender: Int, val message: ByteArray) {
        companion object {
            fun read(p: ByteArray): Incoming {
                val r = Reader(p)
                val type = r.u8(); val aps = ApsFrame.read(r); val lqi = r.u8(); val rssi = r.s8(); val sender = r.u16()
                r.u8(); r.u8() // binding index, address index
                return Incoming(type, aps, lqi, rssi, sender, r.lv())
            }
        }
        override fun equals(other: Any?) = other is Incoming && other.aps == aps && other.sender == sender && other.message.contentEquals(message)
        override fun hashCode() = sender
    }
}

/** A byte pipe to the NCP (the helper's TCP bridge in the app, a fake NCP in tests). */
interface EzspTransport {
    val input: InputStream
    val output: OutputStream
    fun close()
}

class EzspException(msg: String) : IOException(msg)

/**
 * ASH link layer over a [EzspTransport]: window size 1 (like bellows' TX_K = 1), DATA frames ACKed
 * by the NCP (directly or piggybacked) and retransmitted on timeout, every NCP DATA frame ACKed.
 * A reader thread decodes and ACKs; [receive] hands out the EZSP payloads in order.
 */
class AshLink(private val t: EzspTransport, private val log: (String) -> Unit = {}) {
    private val lock = Object()
    private val rx = LinkedBlockingQueue<ByteArray>()
    private var txSeq = 0           // our next frmNum
    private var rxSeq = 0           // NCP's next expected frmNum (what we ACK)
    private var peerAck = 0         // NCP's ackNum: the next of our frames it expects
    private var rstAck: Ash.Frame.RstAck? = null
    @Volatile private var closed: Throwable? = null
    private val reader = Thread({ readLoop() }, "s1-ezsp-ash").apply { isDaemon = true; start() }

    private fun write(b: ByteArray) = synchronized(t.output) { t.output.write(b); t.output.flush() }

    private fun readLoop() {
        val dec = Ash.Decoder()
        val buf = ByteArray(512)
        try {
            while (true) {
                val n = t.input.read(buf)
                if (n < 0) throw EzspException("connection closed by the NCP bridge")
                dec.feed(buf, n) { onFrame(it) }
            }
        } catch (e: Throwable) {
            synchronized(lock) { closed = e; lock.notifyAll() }
            rx.offer(ByteArray(0)) // wake receive()
        }
    }

    private fun onFrame(f: Ash.Frame) {
        when (f) {
            is Ash.Frame.Data -> {
                synchronized(lock) { peerAck = f.ack; lock.notifyAll() }
                val expected = synchronized(lock) { rxSeq }
                if (f.frm == expected) {
                    synchronized(lock) { rxSeq = (rxSeq + 1) and 7 }
                    rx.offer(f.data)
                } else if (!f.reTx) {
                    // out of sequence: ask for a retransmission of what we expect
                    write(Ash.encode(Ash.Frame.Nak(expected)))
                    return
                }
                write(Ash.encode(Ash.Frame.Ack(synchronized(lock) { rxSeq })))
            }
            is Ash.Frame.Ack -> synchronized(lock) { peerAck = f.ack; lock.notifyAll() }
            is Ash.Frame.Nak -> synchronized(lock) { peerAck = f.ack; lock.notifyAll() }
            is Ash.Frame.RstAck -> synchronized(lock) {
                rstAck = f; txSeq = 0; rxSeq = 0; peerAck = 0; rx.clear(); lock.notifyAll()
            }
            is Ash.Frame.Error -> log("NCP reports ASH error 0x%02X".format(f.code))
            Ash.Frame.Rst -> Unit
        }
    }

    private fun checkOpen() { closed?.let { throw EzspException("link down: ${it.message}") } }

    /**
     * Resets the NCP (CANCEL + RST) and waits for RSTACK. zigbeed over CPC takes ~4.2 s to answer
     * (measured), longer than bellows' 2.5 s, so the default wait is generous.
     */
    fun reset(timeoutMs: Long = 10_000): Ash.Frame.RstAck {
        synchronized(lock) { rstAck = null }
        write(Ash.resetBytes())
        val until = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (rstAck == null) {
                checkOpen()
                val left = until - System.currentTimeMillis()
                if (left <= 0) throw EzspException("no RSTACK from the NCP within ${timeoutMs / 1000} s")
                lock.wait(left)
            }
            return rstAck!!
        }
    }

    /** Sends one EZSP frame and waits until the NCP acknowledged it (retransmitting up to [tries]). */
    fun send(ezsp: ByteArray, ackTimeoutMs: Long = 1_600, tries: Int = 4) {
        val frm = synchronized(lock) { txSeq }
        val next = (frm + 1) and 7
        for (attempt in 0 until tries) {
            write(Ash.encode(Ash.Frame.Data(frm, synchronized(lock) { rxSeq }, attempt > 0, ezsp)))
            val until = System.currentTimeMillis() + ackTimeoutMs
            synchronized(lock) {
                while (peerAck != next) {
                    checkOpen()
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) break
                    lock.wait(left)
                }
                if (peerAck == next) { txSeq = next; return }
            }
            log("no ASH ACK for frame $frm (attempt ${attempt + 1})")
        }
        throw EzspException("NCP did not acknowledge frame $frm")
    }

    /** Next EZSP frame from the NCP, or null after [timeoutMs]. */
    fun receive(timeoutMs: Long): ByteArray? {
        checkOpen()
        val b = rx.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (b.isEmpty()) { checkOpen(); return null }
        return b
    }

    fun close() { runCatching { t.close() }; reader.interrupt() }
}

/**
 * Blocking EZSP client on one thread: [command] sends a command and returns the response parameters,
 * dispatching callbacks that arrive in between to [onCallback]; [pollCallbacks] waits for callbacks.
 */
class EzspClient(private val link: AshLink, private val log: (String) -> Unit = {}) {
    var onCallback: (frameId: Int, params: ByteArray) -> Unit = { _, _ -> }
    private var seq = 0

    /** `version` (legacy frame). Returns (protocolVersion, stackType, stackVersion). */
    fun version(desired: Int = Ezsp.PROTOCOL_VERSION, timeoutMs: Long = 5_000): Triple<Int, Int, Int> {
        val s = nextSeq()
        // First frame after a reset: zigbeed answers RSTACK at once but only ACKs data ~5 s later
        // (measured 4.8 s), so this one gets a longer retransmission budget (8 x 1.6 s).
        link.send(Ezsp.legacyVersion(s, desired), tries = 8)
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) throw EzspException("no reply to version")
            val f = link.receive(left) ?: continue
            // legacy reply: seq, 0x80, 0x00, protocolVersion, stackType, stackVersion LE16
            if (f.size >= 7 && (f[0].toInt() and 0xFF) == s && f[2].toInt() == 0) {
                return Triple(f[3].toInt() and 0xFF, f[4].toInt() and 0xFF, (f[5].toInt() and 0xFF) or ((f[6].toInt() and 0xFF) shl 8))
            }
        }
    }

    fun command(frameId: Int, params: ByteArray = ByteArray(0), timeoutMs: Long = 5_000): ByteArray {
        val s = nextSeq()
        link.send(Ezsp.command(s, frameId, params))
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) throw EzspException("no reply to frame 0x%04X".format(frameId))
            val raw = link.receive(left) ?: continue
            val f = Ezsp.parse(raw) ?: continue
            when {
                f.isResponse && !f.isCallback && f.seq == s && f.frameId == frameId -> return f.params
                f.isResponse && !f.isCallback && f.seq == s && f.frameId == Ezsp.INVALID_COMMAND ->
                    throw EzspException("NCP rejected frame 0x%04X (invalidCommand %s)".format(frameId, f.params.firstOrNull()))
                else -> dispatch(f)
            }
        }
    }

    /** Waits up to [timeoutMs] for callbacks and dispatches them; returns after the first batch or the timeout. */
    fun pollCallbacks(timeoutMs: Long) {
        val raw = link.receive(timeoutMs) ?: return
        Ezsp.parse(raw)?.let(::dispatch)
        while (true) { val more = link.receive(0) ?: break; Ezsp.parse(more)?.let(::dispatch) }
    }

    private fun dispatch(f: Ezsp.Received) {
        try { onCallback(f.frameId, f.params) } catch (e: Exception) { log("callback 0x%04X failed: %s".format(f.frameId, e)) }
    }

    private fun nextSeq(): Int { val s = seq; seq = (seq + 1) and 0xFF; return s }
}
