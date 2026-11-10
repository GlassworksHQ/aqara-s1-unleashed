package sh.edm.s1kiosk.radio.ezsp

import java.io.ByteArrayOutputStream

/**
 * ASH, the UART framing EZSP runs on (Silicon Labs UG101), as zigbeed speaks it on its pty and the
 * radio helper's TCP bridge forwards it. Pure codec, no I/O: [encode] turns a frame into wire bytes,
 * [Decoder] turns wire bytes back into frames. Byte-for-byte compatible with bellows (AshTest).
 */
object Ash {
    const val FLAG = 0x7E
    const val ESCAPE = 0x7D
    const val XON = 0x11
    const val XOFF = 0x13
    const val SUBSTITUTE = 0x18
    const val CANCEL = 0x1A
    private val RESERVED = intArrayOf(FLAG, ESCAPE, XON, XOFF, SUBSTITUTE, CANCEL)

    /** NCP reset codes in RSTACK/ERROR frames (EZSP UG100): 0x0B = software reset. */
    const val RESET_SOFTWARE = 0x0B

    sealed class Frame {
        /** [frm]/[ack] are 3-bit sequence numbers; [data] is the EZSP frame (not randomized). */
        data class Data(val frm: Int, val ack: Int, val reTx: Boolean, val data: ByteArray) : Frame() {
            override fun equals(other: Any?) = other is Data && other.frm == frm && other.ack == ack &&
                other.reTx == reTx && other.data.contentEquals(data)
            override fun hashCode() = (frm * 31 + ack) * 31 + data.contentHashCode()
        }
        data class Ack(val ack: Int, val notReady: Boolean = false) : Frame()
        data class Nak(val ack: Int, val notReady: Boolean = false) : Frame()
        object Rst : Frame() { override fun toString() = "Rst" }
        data class RstAck(val version: Int, val resetCode: Int) : Frame()
        data class Error(val version: Int, val code: Int) : Frame()
    }

    /** The pseudo-random sequence DATA payloads are XORed with (seed 0x42, LFSR 0xB8). */
    private val RANDOM: ByteArray = ByteArray(256).also { out ->
        var r = 0x42
        for (i in out.indices) {
            out[i] = r.toByte()
            r = if (r and 1 == 0) r shr 1 else (r shr 1) xor 0xB8
        }
    }

    fun randomize(data: ByteArray): ByteArray = ByteArray(data.size) { (data[it].toInt() xor RANDOM[it % 256].toInt()).toByte() }

    /** CRC-CCITT (poly 0x1021, init 0xFFFF), big-endian on the wire. */
    fun crc(data: ByteArray, len: Int = data.size): Int {
        var c = 0xFFFF
        for (i in 0 until len) {
            c = c xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
        }
        return c and 0xFFFF
    }

    /** Control byte + payload, before CRC and stuffing. */
    fun body(f: Frame): ByteArray = when (f) {
        is Frame.Data -> byteArrayOf(((f.frm and 7) shl 4 or (if (f.reTx) 0x08 else 0) or (f.ack and 7)).toByte()) + randomize(f.data)
        is Frame.Ack -> byteArrayOf((0x80 or (if (f.notReady) 0x08 else 0) or (f.ack and 7)).toByte())
        is Frame.Nak -> byteArrayOf((0xA0 or (if (f.notReady) 0x08 else 0) or (f.ack and 7)).toByte())
        Frame.Rst -> byteArrayOf(0xC0.toByte())
        is Frame.RstAck -> byteArrayOf(0xC1.toByte(), f.version.toByte(), f.resetCode.toByte())
        is Frame.Error -> byteArrayOf(0xC2.toByte(), f.version.toByte(), f.code.toByte())
    }

    /** Wire bytes: stuffed body + CRC, terminated by FLAG. */
    fun encode(f: Frame): ByteArray {
        val b = body(f)
        val c = crc(b)
        val raw = b + byteArrayOf((c shr 8).toByte(), c.toByte())
        val out = ByteArrayOutputStream(raw.size + 4)
        for (x in raw) {
            val v = x.toInt() and 0xFF
            if (v in RESERVED) { out.write(ESCAPE); out.write(v xor 0x20) } else out.write(v)
        }
        out.write(FLAG)
        return out.toByteArray()
    }

    /** A reset as hosts send it: CANCEL first, so the NCP drops any partial frame. */
    fun resetBytes(): ByteArray = byteArrayOf(CANCEL.toByte()) + encode(Frame.Rst)

    /** Parses an unstuffed frame (body + CRC); null if the CRC or the shape is wrong. */
    fun parse(raw: ByteArray): Frame? {
        if (raw.size < 3) return null
        val n = raw.size - 2
        val want = ((raw[n].toInt() and 0xFF) shl 8) or (raw[n + 1].toInt() and 0xFF)
        if (crc(raw, n) != want) return null
        val ctl = raw[0].toInt() and 0xFF
        val payload = raw.copyOfRange(1, n)
        return when {
            ctl and 0x80 == 0 -> Frame.Data((ctl shr 4) and 7, ctl and 7, ctl and 0x08 != 0, randomize(payload))
            ctl and 0xE0 == 0x80 -> Frame.Ack(ctl and 7, ctl and 0x08 != 0)
            ctl and 0xE0 == 0xA0 -> Frame.Nak(ctl and 7, ctl and 0x08 != 0)
            ctl == 0xC0 -> Frame.Rst
            ctl == 0xC1 && payload.size >= 2 -> Frame.RstAck(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF)
            ctl == 0xC2 && payload.size >= 2 -> Frame.Error(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF)
            else -> null
        }
    }

    /**
     * Incremental decoder: feed wire bytes, get complete frames. CANCEL discards the frame in
     * progress, SUBSTITUTE marks it bad, XON/XOFF are ignored, frames with a bad CRC are counted in
     * [badFrames] and dropped (the link layer NAKs/retransmits as needed).
     */
    class Decoder(private val maxFrame: Int = 512) {
        private val buf = ByteArrayOutputStream()
        private var escaped = false
        private var bad = false
        var badFrames = 0
            private set

        fun feed(bytes: ByteArray, len: Int = bytes.size, out: (Frame) -> Unit) {
            for (i in 0 until len) {
                val v = bytes[i].toInt() and 0xFF
                when (v) {
                    FLAG -> {
                        if (buf.size() > 0) {
                            val f = if (bad || escaped) null else parse(buf.toByteArray())
                            if (f != null) out(f) else badFrames++
                        }
                        reset()
                    }
                    CANCEL -> reset()
                    SUBSTITUTE -> bad = true
                    XON, XOFF -> Unit
                    ESCAPE -> escaped = true
                    else -> {
                        buf.write(if (escaped) v xor 0x20 else v)
                        escaped = false
                        if (buf.size() > maxFrame) { bad = true; buf.reset() }
                    }
                }
            }
        }

        private fun reset() { buf.reset(); escaped = false; bad = false }
    }
}
