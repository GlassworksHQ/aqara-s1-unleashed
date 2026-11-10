package sh.edm.s1kiosk.radio.ezsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Wire vectors produced with bellows 1.2.1's ash.py (AshProtocol._stuff_bytes(frame.to_bytes()) + FLAG). */
class AshTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun decode(wire: ByteArray): List<Ash.Frame> {
        val out = ArrayList<Ash.Frame>()
        Ash.Decoder().feed(wire) { out += it }
        return out
    }

    @Test
    fun encodesLikeBellows() {
        // version(12) in the legacy format, frame 0 / ack 0: also seen on the wire against zigbeed
        assertEquals("004221a8586c247e", Ash.encode(Ash.Frame.Data(0, 0, false, hex("0000000c"))).hex())
        assertEquals("3d4521a9432a15b2b8257e", Ash.encode(Ash.Frame.Data(3, 5, true, hex("07000117000000"))).hex())
        assertEquals("82503a7e", Ash.encode(Ash.Frame.Ack(2)).hex())
        assertEquals("a4149e7e", Ash.encode(Ash.Frame.Nak(4)).hex())
        assertEquals("c038bc7e", Ash.encode(Ash.Frame.Rst).hex())
        assertEquals("c1020b0a527e", Ash.encode(Ash.Frame.RstAck(2, Ash.RESET_SOFTWARE)).hex())
        // payload bytes that need escaping after randomization
        assertEquals("7d317d5e7d5db947320f8f947e",
            Ash.encode(Ash.Frame.Data(1, 1, false, byteArrayOf((0x7e xor 0x42).toByte(), (0x7d xor 0x21).toByte(), 0x11, 0x13, 0x18, 0x1a))).hex())
        assertEquals("1ac038bc7e", Ash.resetBytes().hex())
    }

    @Test
    fun decodesWhatItEncodes() {
        val frames = listOf(
            Ash.Frame.Data(0, 0, false, hex("0000000c")),
            Ash.Frame.Data(7, 3, true, byteArrayOf(0x7e, 0x7d, 0x11, 0x13, 0x18, 0x1a, 0)),
            Ash.Frame.Ack(5), Ash.Frame.Nak(1, notReady = true), Ash.Frame.RstAck(2, 0x0b), Ash.Frame.Error(2, 0x51),
        )
        val wire = frames.map { Ash.encode(it) }.reduce { a, b -> a + b }
        assertEquals(frames, decode(wire))
    }

    @Test
    fun decodesZigbeedRstAckAfterCancel() {
        // as received from zigbeed through the helper's bridge: CANCEL, RSTACK v2 software reset
        assertEquals(listOf(Ash.Frame.RstAck(2, 0x0b)), decode(hex("1ac1020b0a527e")))
    }

    @Test
    fun dropsBadCrcAndCancelledFrames() {
        val d = Ash.Decoder()
        val out = ArrayList<Ash.Frame>()
        d.feed(hex("82503b7e")) { out += it }                  // CRC off by one
        d.feed(hex("c1021a82503a7e")) { out += it }            // CANCEL in the middle: only the ACK survives
        assertEquals(listOf(Ash.Frame.Ack(2)), out)
        assertEquals(1, d.badFrames)
    }

    @Test
    fun feedsAcrossChunkBoundaries() {
        val wire = Ash.encode(Ash.Frame.Data(2, 6, false, hex("0180011900" + "90")))
        val out = ArrayList<Ash.Frame>()
        val d = Ash.Decoder()
        for (b in wire) d.feed(byteArrayOf(b)) { out += it }
        assertEquals(1, out.size)
        assertArrayEquals(hex("018001190090"), (out[0] as Ash.Frame.Data).data)
    }
}
