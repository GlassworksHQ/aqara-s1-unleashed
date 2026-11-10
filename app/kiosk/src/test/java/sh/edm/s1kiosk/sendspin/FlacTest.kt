package sh.edm.s1kiosk.sendspin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FLAC framing against real data: the 42-byte `codec_header` aiosendspin 10.2.1 sends (captured
 * from its `FlacEncoder` at 48 kHz/16-bit/stereo) and an FFmpeg-encoded file
 * (src/test/resources/tone48k.flac: 0.25 s of two noisy tones = frames of 4608, 4608 and 2784
 * samples, with STREAMINFO, VORBIS_COMMENT and PADDING blocks).
 */
class FlacTest {

    companion object {
        /** `FlacEncoder(sample_rate=48000, bit_depth=16, channels=2).get_header()` in aiosendspin 10.2.1. */
        const val AIOSENDSPIN_HEADER = "664c61438000002212001200000000004a580bb802f00000000000000000000000000000000000000000"

        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        fun fixture(): ByteArray = File(FlacTest::class.java.classLoader!!.getResource("tone48k.flac")!!.toURI()).readBytes()

        /** Offset of the first frame: after `fLaC` and every metadata block. */
        fun firstFrame(f: ByteArray): Int {
            var p = 4
            while (true) {
                val last = f[p].toInt() and 0x80 != 0
                val len = ((f[p + 1].toInt() and 0xFF) shl 16) or ((f[p + 2].toInt() and 0xFF) shl 8) or (f[p + 3].toInt() and 0xFF)
                p += 4 + len
                if (last) return p
            }
        }
    }

    @Test
    fun crcCheckValues() {
        val d = "123456789".toByteArray()
        assertEquals(0xF4, FlacFrames.crc8(d, 0, d.size))   // CRC-8, poly 0x07, init 0
        assertEquals(0xFEE8, FlacFrames.crc16(d, 0, d.size)) // CRC-16, poly 0x8005, init 0 (CRC-16/UMTS)
    }

    @Test
    fun aiosendspinCodecHeader() {
        val h = hex(AIOSENDSPIN_HEADER)
        assertEquals(42, h.size)
        val si = FlacStreamInfo.parse(h)!!
        assertEquals(4608, si.minBlockSize)
        assertEquals(4608, si.maxBlockSize)
        assertEquals(0, si.minFrameSize)      // unknown: FFmpeg writes STREAMINFO before encoding
        assertEquals(19_032, si.maxFrameSize) // FFmpeg's worst-case estimate
        assertEquals(48_000, si.sampleRate)
        assertEquals(2, si.channels)
        assertEquals(16, si.bitsPerSample)
        assertEquals(0L, si.totalSamples)
        // csd-0 for MediaCodec is exactly the codec_header: fLaC, last-block STREAMINFO header, STREAMINFO.
        assertArrayEquals(h, si.csd0())
        assertTrue(si.maxInputSize() >= 4608 * 2 * 3)
        // A bare STREAMINFO is accepted and wrapped the same way.
        assertArrayEquals(h, FlacStreamInfo.parse(h.copyOfRange(8, 42))!!.csd0())
    }

    @Test
    fun codecHeaderFromAFileWithMoreBlocks() {
        val f = fixture()
        val si = FlacStreamInfo.parse(f.copyOfRange(0, firstFrame(f)))!!
        assertEquals(12_000L, si.totalSamples)
        assertEquals(48_000, si.sampleRate)
        assertTrue(si.maxFrameSize > 0)
        val csd = si.csd0()
        assertEquals(42, csd.size)
        assertEquals(0x80, csd[4].toInt() and 0xFF) // re-marked as the last block
        assertArrayEquals(f.copyOfRange(8, 42), csd.copyOfRange(8, 42))
    }

    @Test
    fun invalidCodecHeaders() {
        val h = hex(AIOSENDSPIN_HEADER)
        assertNull(FlacStreamInfo.parse(ByteArray(0)))
        assertNull(FlacStreamInfo.parse(h.copyOf(41)))                                        // truncated
        assertNull(FlacStreamInfo.parse(h.copyOf().also { it[0] = 'F'.code.toByte() }))       // magic
        assertNull(FlacStreamInfo.parse(h.copyOf().also { it[4] = 0x84.toByte() }))           // first block not STREAMINFO
        assertNull(FlacStreamInfo.parse(h.copyOf().also { it[7] = 33 }))                      // wrong length
        assertNull(FlacStreamInfo.parse(h.copyOf().also { it[10] = 0; it[11] = 0 }))          // max block size 0
        assertNull(FlacStreamInfo.parse(h.copyOf().also { it[18] = 0; it[19] = 0; it[20] = 0x02 })) // sample rate 0
    }

    @Test
    fun splitsRealFramesWithTheirBlockSizes() {
        val f = fixture()
        val start = firstFrame(f)
        val si = FlacStreamInfo.parse(f.copyOfRange(0, start))!!
        val frames = FlacFrames.split(f, start, f.size - start)!!
        assertEquals(listOf(4608, 4608, 2784), frames.map { it.blockSize })
        assertEquals(listOf(8541, 8390, 5197), frames.map { it.length }) // FFmpeg's packet sizes
        assertEquals(start, frames.first().offset)
        assertEquals(f.size, frames.last().offset + frames.last().length)
        assertEquals(si.totalSamples, frames.sumOf { it.blockSize.toLong() })
        assertTrue(frames.all { FlacFrames.consistent(it.header, si) })
        assertEquals(48_000, frames.first().header.sampleRate) // sample rate code 10
        assertFalse(frames.first().header.variableBlocking)
        // Each frame on its own (one per chunk, what aiosendspin sends) splits to itself.
        for (fr in frames) assertEquals(listOf(fr.copy(offset = 0)), FlacFrames.split(f.copyOfRange(fr.offset, fr.offset + fr.length), 0, fr.length))
    }

    @Test
    fun corruptOrTruncatedChunksAreRejected() {
        val f = fixture()
        val start = firstFrame(f)
        val one = FlacFrames.split(f, start, f.size - start)!!.first()
        val frame = f.copyOfRange(one.offset, one.offset + one.length)
        assertNull(FlacFrames.split(frame, 0, frame.size - 1))                                   // truncated
        assertNull(FlacFrames.split(frame.copyOf().also { it[100] = (it[100] + 1).toByte() }, 0, frame.size)) // payload bit error
        assertNull(FlacFrames.split(frame.copyOf().also { it[2] = (it[2] + 1).toByte() }, 0, frame.size))     // header CRC-8
        assertNull(FlacFrames.split(ByteArray(10), 0, 10))                                       // not FLAC
        assertNull(FlacFrames.split(frame, 0, 0))
    }

    @Test
    fun headerBlockSizeAndRateCodes() {
        fun hdr(b2: Int, b3: Int, vararg tail: Int): FlacFrameHeader? {
            val body = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), b2.toByte(), b3.toByte(), 0x00) + tail.map { it.toByte() }.toByteArray()
            val d = body + byteArrayOf(FlacFrames.crc8(body, 0, body.size).toByte())
            return FlacFrames.header(d, 0, d.size)
        }
        assertEquals(192, hdr(0x10, 0x18)!!.blockSize)
        assertEquals(576, hdr(0x20, 0x18)!!.blockSize)
        assertEquals(4608, hdr(0x5A, 0x18)!!.blockSize)
        assertEquals(256, hdr(0x80, 0x18)!!.blockSize)
        assertEquals(32768, hdr(0xF0, 0x18)!!.blockSize)
        assertEquals(100, hdr(0x60, 0x18, 99)!!.blockSize)                  // 8-bit (n-1) at the end
        assertEquals(2784, hdr(0x70, 0x18, 0x0A, 0xDF)!!.blockSize)        // 16-bit (n-1)
        assertEquals(22_000, hdr(0x1C, 0x18, 22)!!.sampleRate)            // kHz
        assertEquals(44_100, hdr(0x1D, 0x18, 0xAC, 0x44)!!.sampleRate)    // Hz
        assertEquals(48_000, hdr(0x1E, 0x18, 0x12, 0xC0)!!.sampleRate)    // tens of Hz
        assertEquals(2, hdr(0x1A, 0xA8)!!.channels)                       // mid/side
        assertEquals(24, hdr(0x1A, 0x1C)!!.bitsPerSample)
        assertNull(hdr(0x0A, 0x18))  // block size code 0 is reserved
        assertNull(hdr(0x1F, 0x18))  // sample rate code 15 is invalid
        assertNull(hdr(0x1A, 0xB8))  // channel code 11 is reserved
        assertNull(hdr(0x1A, 0x19))  // reserved bit
        val ok = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x5A, 0x18, 0x00)
        assertNull(FlacFrames.header(ok + byteArrayOf((FlacFrames.crc8(ok, 0, 5) xor 1).toByte()), 0, 6)) // CRC-8
        assertNotNull(FlacFrames.header(ok + byteArrayOf(FlacFrames.crc8(ok, 0, 5).toByte()), 0, 6))
    }

    @Test
    fun inconsistentFramesAreDetected() {
        val si = FlacStreamInfo.parse(hex(AIOSENDSPIN_HEADER))!!
        assertTrue(FlacFrames.consistent(FlacFrameHeader(4608, 48_000, 2, 16, 6, false), si))
        assertTrue(FlacFrames.consistent(FlacFrameHeader(1000, 0, 2, 0, 6, false), si))
        assertFalse(FlacFrames.consistent(FlacFrameHeader(8192, 48_000, 2, 16, 6, false), si)) // > max block size
        assertFalse(FlacFrames.consistent(FlacFrameHeader(4608, 44_100, 2, 16, 6, false), si))
        assertFalse(FlacFrames.consistent(FlacFrameHeader(4608, 48_000, 1, 16, 6, false), si))
        assertFalse(FlacFrames.consistent(FlacFrameHeader(4608, 48_000, 2, 24, 6, false), si))
    }
}
