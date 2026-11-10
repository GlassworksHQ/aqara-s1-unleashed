package sh.edm.s1kiosk.base

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The wall base ("bracket") UART protocol on `/dev/ttyS3` (115200 8N1, no flow control), pure JVM.
 * Reverse-engineered from Aqara's `/vendor/bin/bracket_cli` and `/vendor/lib/libha_bracket.so`;
 * the full reference with addresses is docs/research/base-protocol.md.
 *
 * Frame (all multi-byte fields big-endian except the CRC):
 * ```
 *  0    1    2    3    4     5..6        7      8 ..            n-2 n-1
 *  55   80   02   seq  type  payload_len hcksum payload ...      crc_lo crc_hi
 * ```
 * - `hcksum` = two's complement of the byte sum of bytes 0..6 (`CheckSum` @0x5fa4).
 * - CRC-16/MODBUS over bytes 0..n-3 (`CRC16` @0x5f30), sent low byte first.
 * - type 1 read, 2 write (panel -> base); 3 write ack, 4 read response, 5 report (base -> panel).
 *   Responses echo the request's `seq`; reports carry the base's own.
 * - Resource id on the wire: 3 bytes `cls idx dev`, from a fixed table (`s_tResMaps` @0x1a018), not
 *   derived from the dotted name. `cls` also selects the value encoding: 1/4 = u8, 0/2/5 = u32 BE,
 *   3/6 = IEEE-754 float BE.
 */
object BracketProtocol {
    const val SYNC = 0x55
    const val MARK1 = 0x80
    const val MARK2 = 0x02
    const val HEADER_LEN = 8
    const val CRC_LEN = 2
    const val MAX_PAYLOAD = 64

    const val T_READ = 1
    const val T_WRITE = 2
    const val T_WRITE_ACK = 3
    const val T_READ_RSP = 4
    const val T_REPORT = 5

    /** `200.1.159 >> 16` of the switch base libha_bracket accepts (0x8450 in handle_read_rsp). */
    const val MODEL_SWITCH = 0xF110

    /** Resource names that exist on the wire (the rest are synthesized by the host). */
    const val IDENTITY = "200.1.159"
    const val OVERHEAT = "200.4.5"
    const val OVERLOAD = "200.4.6"
    const val CURRENT = "0.14.85"
    const val VOLTAGE = "0.11.85"
    const val POWER_ON_STATE = "4.103.85"

    enum class ValueType { U8, U32, F32 }

    /**
     * One wire resource. [attr] is libha_bracket's flag byte: bit0 = read after the handshake,
     * bit1 = written with [default] after the handshake (config), bit2 = in the hourly heartbeat.
     */
    data class Res(val name: String, val attr: Int, val cls: Int, val idx: Int, val dev: Int, val default: Number) {
        val type: ValueType get() = typeOf(cls)
        val id: Int get() = (cls shl 16) or (idx shl 8) or dev
        val isConfig: Boolean get() = attr and 2 != 0
    }

    /** libha_bracket `s_tResMaps` (0x1a018), in order. bracket_cli's table names 05.00/05.01 "8.0.2014". */
    val RES: List<Res> = listOf(
        Res("200.1.159", 0x01, 0x00, 0x00, 0x80, 0L),
        Res("8.0.2022", 0x05, 0x00, 0x01, 0x80, 0L),
        Res("200.4.1", 0x02, 0x00, 0x0F, 0x80, 3L),
        Res("4.1.85", 0x05, 0x01, 0x00, 0x55, 0),
        Res("4.2.85", 0x05, 0x01, 0x01, 0x55, 0),
        Res("4.3.85", 0x05, 0x01, 0x02, 0x55, 0),
        Res("200.4.5", 0x00, 0x05, 0x00, 0x55, 0L),
        Res("200.4.6", 0x00, 0x05, 0x01, 0x55, 0L),
        Res("4.103.85", 0x06, 0x05, 0x05, 0x55, 1L),
        Res("0.11.85", 0x00, 0x06, 0x00, 0x55, 0f),
        Res("0.14.85", 0x04, 0x06, 0x01, 0x55, 0f),
        Res("0.12.85", 0x04, 0x06, 0x02, 0x55, 0f),
        Res("0.13.85", 0x04, 0x06, 0x04, 0x55, 0f),
        Res("0.32.85", 0x04, 0x06, 0x08, 0x55, 0f),
        Res("200.4.2", 0x02, 0x06, 0x06, 0x55, 85f),
        Res("200.4.3", 0x02, 0x06, 0x07, 0x55, 2200f),
        Res("200.4.4", 0x02, 0x06, 0x09, 0x55, 10000f),
    )
    private val BY_NAME = RES.associateBy { it.name }
    private val BY_ID = RES.associateBy { it.id }

    fun res(name: String): Res? = BY_NAME[name]

    /** Exact `cls idx dev`, else (like msg_parse @0x7650, which ignores `dev`) the first `cls idx` match. */
    fun res(cls: Int, idx: Int, dev: Int): Res? =
        BY_ID[(cls shl 16) or (idx shl 8) or dev] ?: RES.firstOrNull { it.cls == cls && it.idx == idx }

    fun typeOf(cls: Int): ValueType = when (cls) {
        1, 4 -> ValueType.U8
        3, 6 -> ValueType.F32
        else -> ValueType.U32
    }

    fun valueLen(cls: Int): Int = if (typeOf(cls) == ValueType.U8) 1 else 4

    // ------------------------------------------------------------------ checksums

    /** CRC-16/MODBUS (poly 0x8005 reflected, init 0xFFFF, no xorout); check("123456789") = 0x4B37. */
    fun crc16(b: ByteArray, off: Int = 0, len: Int = b.size - off): Int {
        var crc = 0xFFFF
        for (i in off until off + len) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /** Two's complement of the sum of header bytes 0..6. */
    fun headerChecksum(b: ByteArray, off: Int = 0): Int {
        var s = 0
        for (i in off until off + 7) s += b[i].toInt() and 0xFF
        return (-s) and 0xFF
    }

    // ------------------------------------------------------------------ encoding

    fun frame(type: Int, seq: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 0xFFFF)
        val f = ByteArray(HEADER_LEN + payload.size + CRC_LEN)
        f[0] = SYNC.toByte(); f[1] = MARK1.toByte(); f[2] = MARK2.toByte()
        f[3] = seq.toByte(); f[4] = type.toByte()
        f[5] = (payload.size ushr 8).toByte(); f[6] = payload.size.toByte()
        f[7] = headerChecksum(f).toByte()
        payload.copyInto(f, HEADER_LEN)
        val crc = crc16(f, 0, f.size - CRC_LEN)
        f[f.size - 2] = crc.toByte()
        f[f.size - 1] = (crc ushr 8).toByte()
        return f
    }

    fun encodeValue(cls: Int, v: Number): ByteArray = when (typeOf(cls)) {
        ValueType.U8 -> byteArrayOf(v.toInt().toByte())
        ValueType.U32 -> ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v.toLong().toInt()).array()
        ValueType.F32 -> ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(v.toFloat()).array()
    }

    /** U8 -> Int, U32 -> Long (unsigned), F32 -> Float. */
    fun decodeValue(cls: Int, b: ByteArray, off: Int): Number = when (typeOf(cls)) {
        ValueType.U8 -> b[off].toInt() and 0xFF
        ValueType.U32 -> ByteBuffer.wrap(b, off, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        ValueType.F32 -> ByteBuffer.wrap(b, off, 4).order(ByteOrder.BIG_ENDIAN).float
    }

    /** 13-byte read request (pack_msg_read @0x789c). */
    fun encodeRead(seq: Int, r: Res): ByteArray = frame(T_READ, seq, byteArrayOf(r.cls.toByte(), r.idx.toByte(), r.dev.toByte()))

    /** Write request (pack_msg_write @0x7990): 14 bytes for u8 resources, 17 otherwise. */
    fun encodeWrite(seq: Int, r: Res, value: Number): ByteArray =
        frame(T_WRITE, seq, byteArrayOf(r.cls.toByte(), r.idx.toByte(), r.dev.toByte()) + encodeValue(r.cls, value))

    // ------------------------------------------------------------------ decoding

    class Frame(val type: Int, val seq: Int, val payload: ByteArray, val raw: ByteArray)

    sealed class Msg {
        abstract val seq: Int
        /** [res] is null for an id that isn't in the table (raw [id] kept for logging). */
        data class ReadRsp(override val seq: Int, val res: Res?, val id: Int, val value: Number?) : Msg()
        data class Report(override val seq: Int, val res: Res?, val id: Int, val value: Number?) : Msg()
        /** status 0 = success (handle_write_ack @0x883c; anything else leaves the write pending). */
        data class WriteAck(override val seq: Int, val status: Int) : Msg()
        /** A request (type 1/2) or an unknown type: never sent by a base. */
        data class Other(override val seq: Int, val type: Int, val payload: ByteArray) : Msg()
    }

    fun decode(f: Frame): Msg {
        val p = f.payload
        return when (f.type) {
            T_WRITE_ACK -> Msg.WriteAck(f.seq, if (p.isNotEmpty()) p[0].toInt() and 0xFF else -1)
            T_READ_RSP, T_REPORT -> {
                if (p.size < 3) return Msg.Other(f.seq, f.type, p)
                val cls = p[0].toInt() and 0xFF
                val id = (cls shl 16) or ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
                val r = res(cls, p[1].toInt() and 0xFF, p[2].toInt() and 0xFF)
                val v = if (p.size >= 3 + valueLen(cls)) decodeValue(cls, p, 3) else null
                if (f.type == T_READ_RSP) Msg.ReadRsp(f.seq, r, id, v) else Msg.Report(f.seq, r, id, v)
            }
            else -> Msg.Other(f.seq, f.type, p)
        }
    }

    /**
     * Byte-stream framer. ha_master hands libha_bracket whole read() chunks and parses them from
     * offset 0 (bracket_serial_recv @0x9010); this one is stricter and resynchronizes on
     * `55 80 02` + a valid header checksum, so a partial or noisy read never stalls the link.
     */
    class Framer {
        private var buf = ByteArray(512)
        private var len = 0
        var junkBytes = 0L
            private set
        var badCrc = 0L
            private set

        fun feed(data: ByteArray, off: Int = 0, n: Int = data.size - off): List<Frame> {
            if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
            data.copyInto(buf, len, off, off + n)
            len += n
            val out = ArrayList<Frame>()
            var i = 0
            while (true) {
                // find sync
                while (i < len && !(buf[i] == SYNC.toByte() && (i + 1 >= len || buf[i + 1] == MARK1.toByte()) &&
                        (i + 2 >= len || buf[i + 2] == MARK2.toByte()))) { i++; junkBytes++ }
                if (len - i < HEADER_LEN) break
                if (headerChecksum(buf, i) != (buf[i + 7].toInt() and 0xFF)) { i++; junkBytes++; continue }
                val plen = ((buf[i + 5].toInt() and 0xFF) shl 8) or (buf[i + 6].toInt() and 0xFF)
                if (plen > MAX_PAYLOAD) { i++; junkBytes++; continue }
                val total = HEADER_LEN + plen + CRC_LEN
                if (len - i < total) break
                val crc = crc16(buf, i, total - CRC_LEN)
                val lo = buf[i + total - 2].toInt() and 0xFF
                val hi = buf[i + total - 1].toInt() and 0xFF
                if (lo != (crc and 0xFF) || hi != (crc ushr 8)) { badCrc++; i++; junkBytes++; continue }
                val raw = buf.copyOfRange(i, i + total)
                out += Frame(raw[4].toInt() and 0xFF, raw[3].toInt() and 0xFF, raw.copyOfRange(HEADER_LEN, HEADER_LEN + plen), raw)
                i += total
            }
            if (i > 0) {
                buf.copyInto(buf, 0, i, len)
                len -= i
            }
            return out
        }
    }

    // ------------------------------------------------------------------ host-side semantics

    /**
     * `200.1.159` read response (the handshake, handle_read_rsp @0x8434): bits 31..16 = model,
     * bits 7..0 = relay channel count; bits 15..8 are not used by the host.
     */
    data class Identity(val raw: Long) {
        val model: Int get() = ((raw ushr 16) and 0xFFFF).toInt()
        val channels: Int get() = (raw and 0xFF).toInt()
        val supported: Boolean get() = model == MODEL_SWITCH
        /** `14.160.85` as ha_master reports it: 0x01000000 | model (0x0100F110 for the switch base). */
        val dockClass: Long get() = 0x01000000L or model.toLong()
        /** `14.21.85`: one bit per relay channel (3 channels -> 7). */
        val relayMask: Int get() = (1 shl channels.coerceIn(0, 8)) - 1
    }

    /**
     * `8.0.2014` (protection) from an over-temperature (`200.4.5`) or over-load (`200.4.6`) report,
     * as dev_overload_overheat @0x7ef0: state 1 -> 1/2 (alarm), 2 -> 3/4 (error), 0 -> 5/6 (cleared).
     */
    fun protection(name: String, state: Int): Int? {
        val heat = when (name) {
            OVERHEAT -> true
            OVERLOAD -> false
            else -> return null
        }
        return when (state) {
            1 -> if (heat) 1 else 2
            2 -> if (heat) 3 else 4
            0 -> if (heat) 5 else 6
            else -> null
        }
    }

    /** handle_report rounds power/current to 2 decimals (round(v * 100) / 100). */
    fun round2(v: Float): Float = (Math.round(v * 100.0) / 100.0).toFloat()

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}
