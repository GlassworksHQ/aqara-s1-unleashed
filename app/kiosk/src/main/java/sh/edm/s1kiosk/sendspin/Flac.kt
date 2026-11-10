package sh.edm.s1kiosk.sendspin

/**
 * FLAC framing for the Sendspin player (RFC 9639), pure JVM:
 *
 * - [FlacStreamInfo]: the stream/start `codec_header` (`fLaC` + the STREAMINFO metadata block, spec
 *   "Codec framing"; aiosendspin 10.2.1 sends exactly `fLaC 80 00 00 22` + 34 bytes) and the
 *   `csd-0` Android's FLAC decoder wants (the same bytes: `fLaC` + metadata blocks, last-block flag
 *   set, which `FLACDecoder::parseMetadata` feeds to libFLAC's `process_until_end_of_metadata`).
 * - [FlacFrames]: frame-header parsing and splitting a chunk into its frames. A Sendspin FLAC chunk
 *   carries "one or more complete FLAC frames"; Android 10's `C2SoftFlacDec` decodes exactly one
 *   frame per input buffer (`FLACDecoder::decodeOneFrame` → `process_single`), so every frame is
 *   queued on its own. Boundaries are where the next frame header (sync + CRC-8) starts *and* the
 *   bytes before it end in a matching CRC-16 footer.
 */
data class FlacStreamInfo(
    val minBlockSize: Int,
    val maxBlockSize: Int,
    val minFrameSize: Int,
    val maxFrameSize: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    /** The 34 STREAMINFO bytes as received. */
    val raw: ByteArray,
) {
    /** `csd-0` for MediaCodec `audio/flac`: `fLaC`, a last-block STREAMINFO header, STREAMINFO. */
    fun csd0(): ByteArray = MAGIC + byteArrayOf(0x80.toByte(), 0, 0, STREAMINFO_LENGTH.toByte()) + raw

    /** Upper bound of one encoded frame (verbatim subframes + headers), for `max-input-size`. */
    fun maxInputSize(): Int {
        val verbatim = maxBlockSize.toLong() * channels * ((bitsPerSample + 7) / 8 + 1) + 64
        return maxOf(maxFrameSize.toLong(), verbatim, 16_384L).coerceAtMost(4L * 1024 * 1024).toInt()
    }

    override fun equals(other: Any?) = other is FlacStreamInfo && raw.contentEquals(other.raw)
    override fun hashCode() = raw.contentHashCode()

    companion object {
        val MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
        const val STREAMINFO_LENGTH = 34

        /**
         * Parses a `codec_header`: `fLaC` followed by metadata blocks, the first of which must be
         * STREAMINFO (RFC 9639 §8.1); a bare 34-byte STREAMINFO is accepted too. Null if invalid.
         */
        fun parse(header: ByteArray): FlacStreamInfo? {
            val si: ByteArray = when {
                header.size >= 8 && header.copyOfRange(0, 4).contentEquals(MAGIC) -> {
                    val type = header[4].toInt() and 0x7F
                    val len = ((header[5].toInt() and 0xFF) shl 16) or ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                    if (type != 0 || len != STREAMINFO_LENGTH || header.size < 8 + len) return null
                    header.copyOfRange(8, 8 + len)
                }
                header.size == STREAMINFO_LENGTH -> header.copyOf()
                else -> return null
            }
            fun u(i: Int) = si[i].toInt() and 0xFF
            val minBlock = (u(0) shl 8) or u(1)
            val maxBlock = (u(2) shl 8) or u(3)
            val minFrame = (u(4) shl 16) or (u(5) shl 8) or u(6)
            val maxFrame = (u(7) shl 16) or (u(8) shl 8) or u(9)
            val rate = (u(10) shl 12) or (u(11) shl 4) or (u(12) ushr 4)
            val channels = ((u(12) ushr 1) and 0x07) + 1
            val bps = (((u(12) and 0x01) shl 4) or (u(13) ushr 4)) + 1
            val total = ((u(13) and 0x0F).toLong() shl 32) or (u(14).toLong() shl 24) or (u(15).toLong() shl 16) or (u(16).toLong() shl 8) or u(17).toLong()
            // RFC 9639: block sizes 16..65535 (min ≤ max), a non-zero sample rate, 4..32 bits.
            if (maxBlock < 16 || minBlock < 16 || minBlock > maxBlock || rate == 0 || bps < 4) return null
            return FlacStreamInfo(minBlock, maxBlock, minFrame, maxFrame, rate, channels, bps, total, si)
        }
    }
}

/** One parsed FLAC frame header. [sampleRate]/[bitsPerSample] are 0 when "from STREAMINFO". */
data class FlacFrameHeader(val blockSize: Int, val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val headerLength: Int, val variableBlocking: Boolean)

/** A frame inside a chunk. */
data class FlacFrame(val offset: Int, val length: Int, val header: FlacFrameHeader) {
    val blockSize: Int get() = header.blockSize
}

object FlacFrames {
    private val CRC8 = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
            t[i] = c
        }
    }
    private val CRC16 = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 8
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
            t[i] = c
        }
    }

    fun crc8(d: ByteArray, off: Int, len: Int): Int {
        var c = 0
        for (i in off until off + len) c = CRC8[c xor (d[i].toInt() and 0xFF)]
        return c
    }

    fun crc16(d: ByteArray, off: Int, len: Int): Int {
        var c = 0
        for (i in off until off + len) c = ((c shl 8) and 0xFFFF) xor CRC16[(c ushr 8) xor (d[i].toInt() and 0xFF)]
        return c
    }

    private fun crc16Step(c: Int, b: Byte) = ((c shl 8) and 0xFFFF) xor CRC16[(c ushr 8) xor (b.toInt() and 0xFF)]

    /** Parses and CRC-8-checks the frame header at [off] (RFC 9639 §9.1); null if there is none. */
    fun header(d: ByteArray, off: Int, end: Int): FlacFrameHeader? {
        if (end - off < 6) return null
        if (d[off].toInt() and 0xFF != 0xFF || (d[off + 1].toInt() and 0xFE) != 0xF8) return null
        val variable = d[off + 1].toInt() and 0x01 != 0
        val b2 = d[off + 2].toInt() and 0xFF
        val b3 = d[off + 3].toInt() and 0xFF
        val bsCode = b2 ushr 4
        val srCode = b2 and 0x0F
        val chCode = b3 ushr 4
        val ssCode = (b3 ushr 1) and 0x07
        if (bsCode == 0 || srCode == 15 || chCode > 10 || ssCode == 3 || b3 and 0x01 != 0) return null
        // Coded frame/sample number: UTF-8-like, 1..7 bytes.
        var p = off + 4
        val first = d[p].toInt() and 0xFF
        val extra = when {
            first and 0x80 == 0 -> 0
            first and 0xE0 == 0xC0 -> 1
            first and 0xF0 == 0xE0 -> 2
            first and 0xF8 == 0xF0 -> 3
            first and 0xFC == 0xF8 -> 4
            first and 0xFE == 0xFC -> 5
            first == 0xFE -> 6
            else -> return null
        }
        if (!variable && extra > 5) return null // frame numbers are at most 31 bits
        p++
        repeat(extra) {
            if (p >= end || (d[p].toInt() and 0xC0) != 0x80) return null
            p++
        }
        fun take(n: Int): Int? {
            if (p + n > end) return null
            var v = 0
            repeat(n) { v = (v shl 8) or (d[p].toInt() and 0xFF); p++ }
            return v
        }
        val blockSize = when (bsCode) {
            1 -> 192
            in 2..5 -> 144 shl bsCode
            6 -> (take(1) ?: return null) + 1
            7 -> (take(2) ?: return null) + 1
            else -> 1 shl bsCode
        }
        val rate = when (srCode) {
            0 -> 0
            1 -> 88_200; 2 -> 176_400; 3 -> 192_000; 4 -> 8_000; 5 -> 16_000; 6 -> 22_050
            7 -> 24_000; 8 -> 32_000; 9 -> 44_100; 10 -> 48_000; 11 -> 96_000
            12 -> (take(1) ?: return null) * 1000
            13 -> take(2) ?: return null
            else -> (take(2) ?: return null) * 10
        }
        if (p >= end) return null
        if (crc8(d, off, p - off) != (d[p].toInt() and 0xFF)) return null
        val channels = if (chCode <= 7) chCode + 1 else 2
        val bps = when (ssCode) { 0 -> 0; 1 -> 8; 2 -> 12; 4 -> 16; 5 -> 20; 6 -> 24; else -> 32 }
        return FlacFrameHeader(blockSize, rate, channels, bps, p + 1 - off, variable)
    }

    /**
     * Splits a chunk payload into complete frames. Returns null when it does not start with a valid
     * frame header, or a frame's CRC-16 footer never matches (corrupt or truncated chunk).
     */
    fun split(d: ByteArray, off: Int, len: Int): List<FlacFrame>? {
        val end = off + len
        val out = ArrayList<FlacFrame>(1)
        var start = off
        var hdr = header(d, start, end) ?: return null
        while (start < end) {
            // CRC-16 over [start, p - 2) must equal the 16 bits at [p - 2, p).
            var crc = 0
            val minEnd = start + hdr.headerLength + 3
            if (minEnd > end) return null
            for (i in start until minEnd - 2) crc = crc16Step(crc, d[i])
            var p = minEnd
            var next: FlacFrameHeader? = null
            var found = false
            while (p <= end) {
                val footer = ((d[p - 2].toInt() and 0xFF) shl 8) or (d[p - 1].toInt() and 0xFF)
                if (crc == footer) {
                    if (p == end) { found = true; break }
                    if (d[p].toInt() and 0xFF == 0xFF) {
                        val h = header(d, p, end)
                        if (h != null && h.variableBlocking == hdr.variableBlocking) { next = h; found = true; break }
                    }
                }
                if (p == end) break
                crc = crc16Step(crc, d[p - 2])
                p++
            }
            if (!found) return null
            out += FlacFrame(start, p - start, hdr)
            start = p
            if (next != null) hdr = next
        }
        return out
    }

    /** True if a frame header agrees with the stream's STREAMINFO (what Android's decoder enforces). */
    fun consistent(h: FlacFrameHeader, si: FlacStreamInfo): Boolean =
        h.blockSize <= si.maxBlockSize &&
            (h.sampleRate == 0 || h.sampleRate == si.sampleRate) &&
            h.channels == si.channels &&
            (h.bitsPerSample == 0 || h.bitsPerSample == si.bitsPerSample)
}
