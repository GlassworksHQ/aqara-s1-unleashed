package io.github.aguilaair.s1kiosk.base

import io.github.aguilaair.s1kiosk.base.BracketProtocol.T_READ
import io.github.aguilaair.s1kiosk.base.BracketProtocol.T_WRITE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handshake / retry / report state machine, with a fake base answering in-process. */
class BracketSessionTest {

    private class Rec : BracketSession.Out {
        val sent = ArrayList<ByteArray>()
        val values = ArrayList<Map<String, Any>>()
        override fun send(frame: ByteArray) { sent += frame }
        override fun values(values: Map<String, Any>, why: String) { this.values += values }
        override fun log(info: Boolean, msg: String) {}
        fun merged(): Map<String, Any> = values.fold(HashMap()) { a, m -> a.putAll(m); a }
    }

    /** A minimal base: answers every request it gets, from [vals]. */
    private class FakeBase(val vals: MutableMap<String, Number>) {
        var seq = 0x80
        fun answer(req: ByteArray): ByteArray? {
            val f = BracketProtocol.Framer().feed(req).single()
            val r = BracketProtocol.res(f.payload[0].toInt() and 0xFF, f.payload[1].toInt() and 0xFF, f.payload[2].toInt() and 0xFF)!!
            val id = f.payload.copyOfRange(0, 3)
            return when (f.type) {
                T_READ -> BracketProtocol.frame(BracketProtocol.T_READ_RSP, f.seq, id + BracketProtocol.encodeValue(r.cls, vals[r.name] ?: r.default))
                T_WRITE -> {
                    vals[r.name] = BracketProtocol.decodeValue(r.cls, f.payload, 3)
                    BracketProtocol.frame(BracketProtocol.T_WRITE_ACK, f.seq, byteArrayOf(0))
                }
                else -> null
            }
        }
        fun report(name: String, v: Number): ByteArray {
            val r = BracketProtocol.res(name)!!
            seq++
            return BracketProtocol.frame(BracketProtocol.T_REPORT, seq, byteArrayOf(r.cls.toByte(), r.idx.toByte(), r.dev.toByte()) + BracketProtocol.encodeValue(r.cls, v))
        }
    }

    private fun kinds(frames: List<ByteArray>) = frames.map { f ->
        val p = BracketProtocol.Framer().feed(f).single()
        val r = BracketProtocol.res(p.payload[0].toInt() and 0xFF, p.payload[1].toInt() and 0xFF, p.payload[2].toInt() and 0xFF)!!
        (if (p.type == T_WRITE) "w " else "r ") + r.name
    }

    /** Runs the session against [base] until it is idle; returns the requests in order. */
    private fun pump(s: BracketSession, rec: Rec, base: FakeBase?, clock: LongArray, ms: Long = 5_000): List<ByteArray> {
        val seen = ArrayList<ByteArray>()
        val end = clock[0] + ms
        while (clock[0] < end) {
            s.tick(clock[0])
            while (rec.sent.isNotEmpty()) {
                val f = rec.sent.removeAt(0)
                seen += f
                base?.answer(f)?.let { s.onBytes(it, 0, it.size, clock[0] + 5) }
            }
            clock[0] += 10
        }
        return seen
    }

    @Test
    fun handshakeConfiguresAndReads() {
        val rec = Rec()
        val s = BracketSession(rec)
        val base = FakeBase(mutableMapOf("200.1.159" to 0xF1100002L, "8.0.2022" to 23L, "4.2.85" to 1))
        val clock = longArrayOf(1_000)
        s.read(listOf(BaseRes.DOCK_CLASS), clock[0])
        val req = kinds(pump(s, rec, base, clock))
        assertEquals(
            listOf(
                "r 200.1.159",
                // dev_config: the config resources with libha_bracket's defaults
                "w 200.4.1", "w 4.103.85", "w 200.4.2", "w 200.4.3", "w 200.4.4",
                // attr bit0: firmware and the relays that exist (2 channels)
                "r 8.0.2022", "r 4.1.85", "r 4.2.85",
            ),
            req,
        )
        assertTrue(s.online)
        assertEquals(3L, base.vals["200.4.1"])
        assertEquals(85f, base.vals["200.4.2"])
        assertEquals(2200f, base.vals["200.4.3"])
        val v = rec.merged()
        assertEquals(0x0100F110L, v[BaseRes.DOCK_CLASS])
        assertEquals(2, v[BaseRes.CHANNEL_COUNT])
        assertEquals(3, v[BaseRes.RELAY_MASK])
        assertEquals(23L, v[BaseRes.FIRMWARE])
        assertEquals(1, v["4.2.85"])
        // A later dock class read is answered from state, no traffic.
        rec.values.clear()
        s.read(listOf(BaseRes.DOCK_CLASS), clock[0])
        assertTrue(rec.sent.isEmpty())
        assertEquals(0x0100F110L, rec.merged()[BaseRes.DOCK_CLASS])
    }

    @Test
    fun noBaseMeansNoneAfterThreeTries() {
        val rec = Rec()
        val s = BracketSession(rec)
        val clock = longArrayOf(0)
        s.read(listOf(BaseRes.DOCK_CLASS), 0)
        val sent = pump(s, rec, null, clock, 2_000)
        assertEquals(List(3) { "r 200.1.159" }, kinds(sent))
        // Each retransmission gets a new sequence number (pack_msg_head increments it).
        assertEquals(3, sent.map { it[3] }.distinct().size)
        assertFalse(s.online)
        assertEquals(0, rec.merged()[BaseRes.DOCK_CLASS])
        // Writes are refused until a base answered the handshake.
        assertFalse(s.write("4.1.85", 1, clock[0]))
        assertTrue(rec.sent.isEmpty())
    }

    @Test
    fun retryTimingFollowsLibhaBracket() {
        val rec = Rec()
        val s = BracketSession(rec)
        s.handshake(0)
        assertEquals(1, rec.sent.size)
        s.tick(150); assertEquals(1, rec.sent.size)   // < 200 ms: wait
        s.tick(200); assertEquals(2, rec.sent.size)   // timeout -> retransmit
        s.tick(400); assertEquals(3, rec.sent.size)
        s.tick(600); assertEquals(3, rec.sent.size)   // 3 sends, then give up
        assertEquals(0, s.pending)
    }

    @Test
    fun unsupportedModelIsUnknown() {
        val rec = Rec()
        val s = BracketSession(rec)
        val base = FakeBase(mutableMapOf("200.1.159" to 0xF0100003L))
        val clock = longArrayOf(0)
        s.read(listOf(BaseRes.DOCK_CLASS), 0)
        assertEquals(listOf("r 200.1.159"), kinds(pump(s, rec, base, clock)))
        assertFalse(s.online)
        val (kind, raw) = BaseProtocol.decodeDockClass((rec.merged()[BaseRes.DOCK_CLASS] as Long).toInt())
        assertEquals(BaseKind.UNKNOWN, kind)
        assertEquals(0x7f, raw)
    }

    @Test
    fun reportsWritesAndEnergy() {
        val rec = Rec()
        val s = BracketSession(rec, initialEnergyWh = 10f)
        val base = FakeBase(mutableMapOf("200.1.159" to 0xF1100002L))
        val clock = longArrayOf(0)
        // A power report while offline starts the handshake (handle_report).
        val p = base.report("0.12.85", 100.004f)
        s.onBytes(p, 0, p.size, 0)
        assertEquals("r 200.1.159", kinds(pump(s, rec, base, clock, 2_000)).first())
        assertTrue(s.online)
        assertEquals(100.0f, rec.merged()["0.12.85"])

        // One hour later at 100 W: +100 Wh.
        clock[0] += 3_600_000
        val p2 = base.report("0.12.85", 0f)
        s.onBytes(p2, 0, p2.size, clock[0])
        assertEquals(110f, s.energyWh, 0.1f) // + the ~2 s the handshake took
        assertEquals(s.energyWh, rec.merged()["0.13.85"] as Float, 0.001f)

        // Relay write: acked -> the new value is delivered without a read.
        rec.values.clear()
        assertTrue(s.write("4.1.85", 1, clock[0]))
        assertEquals(listOf("w 4.1.85"), kinds(pump(s, rec, base, clock, 1_000)))
        assertEquals(1, rec.merged()["4.1.85"])

        // Over-load report -> 8.0.2014 = 2; cleared -> 6.
        val o = base.report("200.4.6", 1L)
        s.onBytes(o, 0, o.size, clock[0])
        assertEquals(2, rec.merged()[BaseRes.PROTECTION])
        val oc = base.report("200.4.6", 0L)
        s.onBytes(oc, 0, oc.size, clock[0])
        assertEquals(6, rec.merged()[BaseRes.PROTECTION])

        // Physical toggle on the base: relay report.
        val t = base.report("4.2.85", 1)
        s.onBytes(t, 0, t.size, clock[0])
        assertEquals(1, rec.merged()["4.2.85"])
    }

    @Test
    fun unackedWriteIsFollowedByRead() {
        val rec = Rec()
        val s = BracketSession(rec)
        val base = FakeBase(mutableMapOf("200.1.159" to 0xF1100001L))
        val clock = longArrayOf(0)
        s.handshake(0)
        pump(s, rec, base, clock, 3_000)
        assertTrue(s.write("4.1.85", 1, clock[0]))
        // The base stops answering writes.
        val mute = object { fun answer(f: ByteArray) = if (f[4].toInt() == T_WRITE) null else base.answer(f) }
        val seen = ArrayList<String>()
        val end = clock[0] + 2_000
        while (clock[0] < end) {
            s.tick(clock[0])
            while (rec.sent.isNotEmpty()) {
                val f = rec.sent.removeAt(0)
                seen += kinds(listOf(f))
                mute.answer(f)?.let { s.onBytes(it, 0, it.size, clock[0] + 5) }
            }
            clock[0] += 10
        }
        assertEquals(listOf("w 4.1.85", "w 4.1.85", "w 4.1.85", "r 4.1.85"), seen)
        assertEquals(0, rec.merged()["4.1.85"])
    }

    @Test
    fun silenceTriggersProbeAndRemovalIsDetected() {
        val rec = Rec()
        val s = BracketSession(rec)
        val base = FakeBase(mutableMapOf("200.1.159" to 0xF1100002L))
        val clock = longArrayOf(0)
        s.handshake(0)
        pump(s, rec, base, clock, 3_000)
        assertTrue(s.online)
        rec.values.clear()
        // Base removed: no frames for > 61 s -> probe -> no answer -> dock class 0.
        val probes = pump(s, rec, null, clock, 65_000)
        assertEquals(List(3) { "r 200.1.159" }, kinds(probes))
        assertFalse(s.online)
        assertEquals(0, rec.merged()[BaseRes.DOCK_CLASS])
    }
}
