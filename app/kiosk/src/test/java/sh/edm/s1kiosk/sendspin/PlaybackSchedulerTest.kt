package sh.edm.s1kiosk.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Scheduling math against a simulated output: a virtual clock, a DAC whose real rate differs from
 * nominal by [ppm], a fixed output latency, a 100 ms blocking buffer and noisy timestamps. Every
 * source frame carries its index (left = low 15 bits, right = high bits), so the test can compute,
 * for every frame the sink presents, the difference between when it really played and when the
 * time filter says it should have (spec "Sync Accuracy").
 */
class PlaybackSchedulerTest {

    private val rate = 48_000
    private val serverOffsetUs = 5_000_000L // server clock = local + 5 s

    private inner class SimSink(private val ppm: Double, private val latencyNs: Long, private val jitterNs: Long, seed: Long = 7) : AudioSink {
        var now = 1_000_000_000L
        private var startNs = 0L
        var written = 0L
        private val capacity = rate / 10
        private val rnd = Random(seed)
        private val actualRate get() = rate * (1 + ppm * 1e-6)
        val presented = ArrayList<Pair<Long, Int>>() // (presentation time ns, source frame index) for non-silent frames
        var opens = 0

        private fun consumed(t: Long) = maxOf(0.0, (t - startNs) * actualRate / 1e9).toLong().coerceAtMost(written)

        override fun open(sampleRate: Int, channels: Int) { startNs = now; written = 0; opens++ }

        override fun write(buf: ShortArray, frames: Int): Int {
            while (written - consumed(now) + frames > capacity) now += 1_000_000 // block 1 ms at a time
            for (i in 0 until frames) {
                val l = buf[2 * i].toInt() and 0x7FFF
                val r = buf[2 * i + 1].toInt() and 0x7FFF
                val idx = (r shl 15) or l
                if (idx != 0) presented += presentationNs(written + i) to idx
            }
            written += frames
            return frames
        }

        fun presentationNs(frame: Long) = startNs + latencyNs + (frame * 1e9 / actualRate).toLong()

        override fun timestamp(out: LongArray): Boolean {
            val c = consumed(now)
            // The frame presented now left the buffer latencyNs ago.
            val f = maxOf(0.0, (now - startNs - latencyNs) * actualRate / 1e9).toLong()
            if (f <= 0 || c <= 0) return false
            out[0] = f
            out[1] = now + if (jitterNs > 0) (rnd.nextGaussian() * jitterNs).toLong() else 0L
            return true
        }

        override fun setGain(gain: Float) {}
        override fun close() {}
    }

    private val snapshot = TimeFilter.Snapshot(serverOffsetUs.toDouble(), 0.0, 0, false, 10, 50.0)

    private class Feeder(val firstServerTsUs: Long, val chunkFrames: Int = 960) {
        var nextIdx = 1L
        var nextTs = firstServerTsUs
        fun chunk(rate: Int): PcmChunk {
            val pcm = ShortArray(chunkFrames * 2)
            for (i in 0 until chunkFrames) {
                val idx = nextIdx + i
                pcm[2 * i] = (idx and 0x7FFF).toShort()
                pcm[2 * i + 1] = ((idx shr 15) and 0x7FFF).toShort()
            }
            val c = PcmChunk(nextTs, pcm, chunkFrames, rate, 2, chunkFrames * 4 + 13)
            nextIdx += chunkFrames
            nextTs += chunkFrames * 1_000_000L / rate
            return c
        }
    }

    /** Runs [seconds] of playback, keeping ~1 s queued; returns the sync errors (µs) after [warmupS]. */
    private fun run(sink: SimSink, seconds: Int, outputDelayMs: Int = 0, warmupS: Double = 1.5, feeder: Feeder? = null,
                    sched: PlaybackScheduler = PlaybackScheduler(sink, { snapshot }, { sink.now }),
                    owns: () -> Boolean = { true }): Pair<PlaybackScheduler, List<Double>> {
        sched.outputDelayUs = outputDelayMs * 1000L
        val f = feeder ?: Feeder(firstServerTsUs = sink.now / 1000 + serverOffsetUs + 400_000)
        sched.start(SsFormat.PCM_48K_STEREO_16)
        val end = sink.now + seconds * 1_000_000_000L
        while (sink.now < end) {
            while (f.nextTs < sink.now / 1000 + serverOffsetUs + 1_000_000) sched.push(f.chunk(rate))
            sched.step(owns)
        }
        val warm = sink.presented.first().first + (warmupS * 1e9).toLong()
        val errs = sink.presented.filter { it.first > warm }.map { (t, idx) ->
            val serverTs = f.firstServerTsUs + (idx - 1) * 1e6 / rate
            val expectedUs = snapshot.clientTimeExact(serverTs) - outputDelayMs * 1000.0
            t / 1000.0 - expectedUs
        }
        return sched to errs
    }

    private fun List<Double>.maxAbs() = maxOf { abs(it) }

    @Test
    fun idealOutputIsSampleAccurate() {
        val sink = SimSink(ppm = 0.0, latencyNs = 40_000_000, jitterNs = 0)
        val (s, errs) = run(sink, 10)
        val st = s.stats()
        assertTrue("max error ${errs.maxAbs()} µs", errs.maxAbs() < 100)
        assertTrue(st.snapsEarly <= 1) // at most the one start-up alignment
        assertEquals(0, st.snapsLate)
        assertEquals(0, st.duplicated + st.dropped)
    }

    @Test
    fun dacClockErrorIsCorrectedWithSingleFrames() {
        for (ppm in listOf(-120.0, 80.0)) {
            val sink = SimSink(ppm = ppm, latencyNs = 30_000_000, jitterNs = 0)
            val (s, errs) = run(sink, 30)
            val st = s.stats()
            assertTrue("ppm $ppm: max error ${errs.maxAbs()} µs", errs.maxAbs() < 500)
            // 1 frame per correction: about |ppm| * 48 frames per second of audio.
            val corrections = st.duplicated + st.dropped
            val expected = abs(ppm) * 1e-6 * rate * 30
            assertTrue("ppm $ppm: $corrections corrections, expected ~$expected", corrections in (expected * 0.6).toLong()..(expected * 1.6).toLong())
            assertEquals(0, st.snapsLate)
            // spec: speed within ±0.5 %: one frame per 10 ms block at most
            assertTrue(corrections <= 30 * 100)
        }
    }

    @Test
    fun noisyTimestampsStayWithinAMillisecond() {
        val sink = SimSink(ppm = 50.0, latencyNs = 45_000_000, jitterNs = 400_000)
        val (_, errs) = run(sink, 30)
        assertTrue("max error ${errs.maxAbs()} µs", errs.maxAbs() < 1_000)
        val rms = Math.sqrt(errs.sumOf { it * it } / errs.size)
        assertTrue("rms $rms µs", rms < 300)
    }

    @Test
    fun outputDelayShiftsPlaybackEarlier() {
        val sink = SimSink(ppm = 0.0, latencyNs = 40_000_000, jitterNs = 0)
        val (_, errs) = run(sink, 6, outputDelayMs = 150)
        assertTrue("max error ${errs.maxAbs()} µs", errs.maxAbs() < 100)
    }

    @Test
    fun lateChunksAreDroppedAndGapsAreSilence() {
        val sink = SimSink(ppm = 0.0, latencyNs = 40_000_000, jitterNs = 0)
        val sched = PlaybackScheduler(sink, { snapshot }, { sink.now })
        sched.start(SsFormat.PCM_48K_STEREO_16)
        val nowServer = sink.now / 1000 + serverOffsetUs
        sched.push(Feeder(nowServer - 1_000_000).chunk(rate)) // already over
        assertEquals(1, sched.stats().lateChunks)
        // two chunks 50 ms apart: the gap is played as silence and the second stays aligned
        val f = Feeder(nowServer + 300_000)
        val a = f.chunk(rate)
        f.nextTs += 50_000
        val b = f.chunk(rate)
        sched.push(a); sched.push(b)
        repeat(100) { sched.step() }
        val firstB = sink.presented.first { it.second == 961 }
        val expected = snapshot.clientTimeExact(b.serverTsUs.toDouble())
        assertTrue(abs(firstB.first / 1000.0 - expected) < 100)
    }

    @Test
    fun clearRestartsTheOutputAndRealigns() {
        val sink = SimSink(ppm = 0.0, latencyNs = 40_000_000, jitterNs = 0)
        val (sched, _) = run(sink, 3)
        // seek: clear everything; new audio starts 300 ms ahead on the server timeline
        sched.clear(null)
        val f = Feeder(sink.now / 1000 + serverOffsetUs + 300_000)
        sink.presented.clear()
        val (_, errs) = run(sink, 3, feeder = f, sched = sched, warmupS = 0.2)
        assertEquals(2, sink.opens)
        assertTrue("max error ${errs.maxAbs()} µs", errs.maxAbs() < 100)
    }

    /**
     * Passes calls on to [sim], except the one call [stuckAt] names ("open" or "write"). That call
     * returns only after [handOver] has run, and what it wrote went to the old output, so not to [sim].
     */
    private class StuckOnce(private val sim: SimSink, private val staleFrames: (Int) -> Int) : AudioSink {
        var stuckAt: String? = null
        var handOver: () -> Unit = {}
        /** From the stuck call's return to the end of the old owner's step. */
        var stale = false
        var staleWrites = 0
        override fun open(sampleRate: Int, channels: Int) {
            if (stuckAt == "open") { stuckAt = null; handOver(); stale = true; return }
            sim.open(sampleRate, channels)
        }
        override fun write(buf: ShortArray, frames: Int): Int {
            if (stuckAt == "write") { stuckAt = null; handOver(); stale = true; return staleFrames(frames) }
            if (stale) staleWrites++
            return sim.write(buf, frames)
        }
        override fun timestamp(out: LongArray) = sim.timestamp(out)
        override fun setGain(gain: Float) {}
        override fun close() {}
    }

    /**
     * The old owner's step is stuck in [at] while its run stops and the next run starts and plays
     * for a second (#96). Then the old step returns, and the next run plays on for 2 s.
     */
    private fun staleStepDuringAHandOver(at: String, staleFrames: (Int) -> Int = { it }) {
        val sim = SimSink(ppm = 0.0, latencyNs = 40_000_000, jitterNs = 0)
        val out = StuckOnce(sim, staleFrames)
        val sched = PlaybackScheduler(out, { snapshot }, { sim.now })
        var owner = 1
        val oldOwns = { owner == 1 }
        val newOwns = { owner == 2 }
        if (at == "write") run(sim, 2, sched = sched, owns = oldOwns) else sched.start(SsFormat.PCM_48K_STEREO_16)
        val next = Feeder(sim.now / 1000 + serverOffsetUs + 400_000)
        out.handOver = {
            owner = 2
            sched.end()
            sim.presented.clear()
            run(sim, 1, warmupS = 0.0, feeder = next, sched = sched, owns = newOwns)
        }
        out.stuckAt = at
        sched.step(oldOwns)
        out.stale = false
        val playedBefore = sim.presented.size
        val (_, errs) = run(sim, 2, warmupS = 0.5, feeder = next, sched = sched, owns = newOwns)
        assertEquals("$at: the old step wrote nothing more", 0, out.staleWrites)
        assertTrue("$at: the next run kept playing", sim.presented.size - playedBefore > rate * 3 / 2)
        assertTrue("$at: the next run stayed in sync: max error ${errs.maxAbs()} µs", errs.maxAbs() < 100)
    }

    @Test
    fun aStepStuckInOpenLeavesTheNextOwnersOutputAlone() = staleStepDuringAHandOver("open")

    @Test
    fun aStepStuckInWriteLeavesTheNextOwnersOutputAlone() {
        staleStepDuringAHandOver("write")
        staleStepDuringAHandOver("write") { it / 2 } // cut short: the rest of the block must not follow
    }

    @Test
    fun jitterBufferClearFromAndDropBefore() {
        val jb = JitterBuffer()
        val f = Feeder(1_000_000, chunkFrames = 480) // 10 ms chunks
        repeat(5) { jb.add(f.chunk(rate)) }
        jb.clearFrom(1_030_000)
        assertEquals(3, jb.size)
        assertEquals(1_030_000.0, jb.endTsUs()!!, 0.0)
        jb.dropBefore(1_015_000.0)
        assertEquals(1_015_000.0, jb.nextTsUs()!!, 1.0)
        val buf = ShortArray(2 * 480)
        assertEquals(240, jb.read(buf, 0, 480)) // rest of the head chunk only
        assertEquals(1_020_000.0, jb.nextTsUs()!!, 0.0)
        jb.clear()
        assertNull(jb.nextTsUs())
        assertEquals(0L, jb.bytes)
    }

    @Test
    fun pcmDecodingAndVolumeCurve() {
        val pcm = byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte())
        val s = PlaybackScheduler.decodePcm(pcm, 0, 4, 16)
        assertEquals(0x1234.toShort(), s[0])
        assertEquals((-1).toShort(), s[1])
        val s24 = PlaybackScheduler.decodePcm(byteArrayOf(0x00, 0x34, 0x12), 0, 3, 24)
        assertEquals(0x1234.toShort(), s24[0])
        assertEquals(1f, PlaybackScheduler.amplitude(100), 0f)
        assertEquals(0.3536f, PlaybackScheduler.amplitude(50), 1e-4f)
        assertEquals(0f, PlaybackScheduler.amplitude(0), 0f)
    }
}
