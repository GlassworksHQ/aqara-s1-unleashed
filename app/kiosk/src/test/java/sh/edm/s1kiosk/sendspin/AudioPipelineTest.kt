package sh.edm.s1kiosk.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.util.Collections

/**
 * [AudioPipeline] with a fake [FlacFrameDecoder] (MediaCodec isn't on the JVM) fed the real
 * frames of src/test/resources/tone48k.flac (4608, 4608 and 2784 samples). The fake fills each
 * frame's PCM with its tag, so the test sees which input every output came from.
 */
class AudioPipelineTest {

    private val file = FlacTest.fixture()
    private val start = FlacTest.firstFrame(file)
    private val header = FlacStreamInfo.parse(file.copyOfRange(0, start))!!.csd0()
    private val frames = FlacFrames.split(file, start, file.size - start)!!.map { file.copyOfRange(it.offset, it.offset + it.length) }
    private val flac = SsFormat.FLAC_48K_STEREO_16
    private val pcm = SsFormat.PCM_48K_STEREO_16

    private class Rec : PcmOutput {
        val events: MutableList<String> = Collections.synchronizedList(ArrayList())
        val chunks: MutableList<PcmChunk> = Collections.synchronizedList(ArrayList())
        override fun start(f: SsFormat) { events += "start ${f.codec}/${f.sampleRate}/${f.channels}/${f.bitDepth}" }
        override fun push(c: PcmChunk) { events += "push ${c.serverTsUs} ${c.frames}"; chunks += c }
        override fun clear(fromUs: Long?) { events += "clear $fromUs" }
        override fun end() { events += "end" }
    }

    /** Outputs come out one call late while more input follows (like a pipelined codec); [hold] blocks all output. */
    private class Fake(private val failQueue: Boolean = false) : FlacFrameDecoder {
        override val name = "fake.flac"
        val inQueue = ArrayDeque<Pair<Long, Int>>()
        val queued: MutableList<Long> = Collections.synchronizedList(ArrayList())
        @Volatile var hold = false
        @Volatile var opened: ByteArray? = null
        @Volatile var closed = false
        var dropTag = -1L

        override fun open(info: FlacStreamInfo, csd0: ByteArray) { opened = csd0 }

        @Synchronized override fun queue(data: ByteArray, offset: Int, length: Int, tag: Long, timeoutUs: Long): Boolean {
            if (failQueue) throw IllegalStateException("codec error")
            val h = FlacFrames.header(data, offset, offset + length)!!
            inQueue.addLast(tag to h.blockSize)
            queued += tag
            return true
        }

        override fun dequeue(timeoutUs: Long): FlacFrameDecoder.Decoded? =
            next(timeoutUs) ?: run { if (timeoutUs > 0) Thread.sleep(1); null }

        @Synchronized private fun next(timeoutUs: Long): FlacFrameDecoder.Decoded? {
            if (hold || inQueue.isEmpty()) return null
            if (timeoutUs == 0L && inQueue.size < 2) return null // latency: one frame behind
            val (tag, n) = inQueue.removeFirst()
            if (tag == dropTag) return null // the decoder silently lost this one
            return FlacFrameDecoder.Decoded(tag, ShortArray(n * 2) { tag.toShort() }, n)
        }

        override fun close() { closed = true }
    }

    private val made: MutableList<Fake> = Collections.synchronizedList(ArrayList())
    private val failures: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var pipeline: AudioPipeline? = null

    private fun pipeline(out: Rec, stallMs: Long = 1_500, factory: () -> Fake = { Fake() }) =
        AudioPipeline(out, { factory().also { made += it } }, { failures += it }, stallMs = stallMs).also { pipeline = it }

    @After fun tearDown() { pipeline?.close() }

    private fun chunk(ts: Long, vararg f: ByteArray) =
        SsMessages.parseAudio(SsMessages.audioMessage(ts, 250_000, f.fold(ByteArray(0)) { a, b -> a + b }))!!

    private fun AudioPipeline.idle() = assertTrue("pipeline idle", awaitIdle(3_000))

    @Test
    fun framesLandOnTheChunkTimelineDespiteDecoderLatency() {
        val out = Rec()
        val p = pipeline(out)
        p.start(flac, header)
        p.audio(chunk(1_000_000, frames[0]))
        p.audio(chunk(1_096_000, frames[1], frames[2])) // two frames in one chunk: split, one per input
        p.idle()
        assertEquals(listOf("start pcm/48000/2/16", "push 1000000 4608", "push 1096000 4608", "push 1192000 2784"), out.events)
        // Each output went to its own frame's timestamp (the fake fills PCM with the tag).
        assertEquals(listOf(1, 2, 3), out.chunks.map { it.pcm[0].toInt() })
        // Contiguous: every chunk starts where the previous ended.
        out.chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.endTsUs, b.serverTsUs.toDouble(), 1.0) }
        assertEquals(header.toList(), made.single().opened!!.toList())
        assertEquals(3L, p.stats().flacFrames)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun codecSwitchKeepsOrderAndTheOutputFormat() {
        val out = Rec()
        val p = pipeline(out)
        val pcmBytes = ByteArray(4800 * 4) { 1 }
        p.start(pcm, null)
        p.audio(chunk(0, pcmBytes))
        p.start(flac, header)
        p.audio(chunk(100_000, frames[0]))
        p.start(pcm, null) // FLAC still inside the decoder must come out first
        p.audio(chunk(196_000, pcmBytes))
        p.idle()
        assertEquals(
            listOf("start pcm/48000/2/16", "push 0 4800", "start pcm/48000/2/16", "push 100000 4608", "start pcm/48000/2/16", "push 196000 4800"),
            out.events,
        )
        assertTrue(made.single().closed) // released when the stream left FLAC
    }

    @Test
    fun clearForgetsFramesStillInTheDecoder() {
        val out = Rec()
        val p = pipeline(out)
        p.start(flac, header)
        p.idle()
        p.audio(chunk(1_000_000, frames[0])) // opens the decoder
        p.idle()
        val d = made.single()
        d.hold = true
        p.audio(chunk(1_096_000, frames[1]))
        p.audio(chunk(1_192_000, frames[2]))
        while (d.queued.size < 3) Thread.sleep(2)
        p.clear(null)
        p.idle()
        d.hold = false
        p.audio(chunk(5_000_000, frames[0]))
        p.idle()
        assertEquals(listOf("start pcm/48000/2/16", "push 1000000 4608", "clear null", "push 5000000 4608"), out.events)
        assertEquals(4, out.chunks.last().pcm[0].toInt())
    }

    @Test
    fun clearFromTimestampKeepsEarlierFrames() {
        val out = Rec()
        val p = pipeline(out)
        p.start(flac, header)
        p.audio(chunk(1_000_000, frames[0]))
        p.idle()
        val d = made.single()
        d.hold = true
        p.audio(chunk(1_096_000, frames[1]))
        p.audio(chunk(1_192_000, frames[2]))
        while (d.queued.size < 3) Thread.sleep(2)
        p.clear(1_192_000)
        while ("clear 1192000" !in out.events) Thread.sleep(2)
        d.hold = false
        p.idle()
        assertEquals(listOf("start pcm/48000/2/16", "push 1000000 4608", "clear 1192000", "push 1096000 4608"), out.events)
    }

    @Test
    fun aLostFrameIsSkippedAndTheRestStayInPlace() {
        val out = Rec()
        val p = pipeline(out, factory = { Fake().apply { dropTag = 2 } })
        p.start(flac, header)
        p.audio(chunk(0, frames[0]))
        p.audio(chunk(96_000, frames[1]))
        p.audio(chunk(192_000, frames[2]))
        p.idle()
        assertEquals(listOf(0L, 192_000L), out.chunks.map { it.serverTsUs })
        assertEquals(1L, p.stats().droppedFrames)
    }

    @Test
    fun oneErrorRecoversWithANewDecoder() {
        val out = Rec()
        var n = 0
        val p = pipeline(out, factory = { Fake(failQueue = n++ == 0) })
        p.start(flac, header)
        p.audio(chunk(0, frames[0]))
        p.audio(chunk(96_000, frames[1]))
        p.audio(chunk(192_000, frames[2]))
        p.idle()
        assertEquals(listOf(96_000L, 192_000L), out.chunks.map { it.serverTsUs })
        assertEquals(2, made.size)
        assertTrue(made[0].closed)
        assertTrue(failures.isEmpty())
        assertNull(p.flacFailedReason)
        assertEquals(1L, p.stats().decodeErrors)
    }

    @Test
    fun repeatedErrorsFallBackOnce() {
        val out = Rec()
        val p = pipeline(out, factory = { Fake(failQueue = true) })
        p.start(flac, header)
        repeat(6) { p.audio(chunk(it * 96_000L, frames[0])) }
        p.idle()
        assertEquals(AudioPipeline.FLAC_FAILURE_LIMIT, made.size) // no decoder after the fallback
        assertEquals(1, failures.size)
        assertTrue(p.flacFailedReason!!.contains("codec error"))
        assertTrue(out.chunks.isEmpty())
        // PCM keeps working, and switching FLAC back on re-arms it.
        p.start(pcm, null)
        p.audio(chunk(1_000_000, ByteArray(480 * 4)))
        p.resetFlacFailure()
        p.idle()
        assertEquals(1, out.chunks.size)
        assertNull(p.flacFailedReason)
    }

    @Test
    fun badCodecHeaderFallsBackWithoutADecoder() {
        val out = Rec()
        val p = pipeline(out)
        p.start(flac, null)
        p.audio(chunk(0, frames[0]))
        p.idle()
        assertEquals(1, failures.size)
        assertTrue(made.isEmpty())
        val p2 = AudioPipeline(out, { Fake() }, { failures += it })
        p2.start(flac, FlacTest.hex(FlacTest.AIOSENDSPIN_HEADER)) // fine
        p2.start(SsFormat("flac", 2, 44_100, 16), FlacTest.hex(FlacTest.AIOSENDSPIN_HEADER)) // STREAMINFO says 48 kHz
        assertTrue(p2.awaitIdle(3_000))
        assertEquals(2, failures.size)
        p2.close()
    }

    @Test
    fun corruptChunkIsDroppedButTheDecoderStays() {
        val out = Rec()
        val p = pipeline(out)
        p.start(flac, header)
        p.audio(chunk(0, frames[0]))
        p.audio(chunk(96_000, frames[1].copyOf(frames[1].size - 3))) // truncated
        p.audio(chunk(192_000, frames[2]))
        p.idle()
        assertEquals(listOf(0L, 192_000L), out.chunks.map { it.serverTsUs })
        assertEquals(1, made.size)
        assertEquals(1L, p.stats().decodeErrors)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun stalledDecoderIsReplaced() {
        val out = Rec()
        var n = 0
        val p = pipeline(out, stallMs = 100, factory = { Fake().apply { hold = n++ == 0 } })
        p.start(flac, header)
        p.audio(chunk(0, frames[0]))
        Thread.sleep(400)
        p.audio(chunk(96_000, frames[1]))
        p.idle()
        assertTrue(made[0].closed)
        assertEquals(listOf(96_000L), out.chunks.map { it.serverTsUs })
        assertEquals(1L, p.stats().decodeErrors)
    }

    @Test
    fun endReleasesTheDecoder() {
        val out = Rec()
        val p = pipeline(out)
        p.start(flac, header)
        p.audio(chunk(0, frames[0]))
        p.end()
        p.audio(chunk(96_000, frames[1])) // after stream/end: no format, ignored
        p.idle()
        assertTrue(made.single().closed)
        assertEquals("end", out.events.last())
    }
}
