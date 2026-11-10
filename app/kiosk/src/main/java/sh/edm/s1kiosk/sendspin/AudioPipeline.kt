package sh.edm.s1kiosk.sendspin

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Where decoded audio goes: [PlaybackScheduler] on the panel. Always 16-bit PCM. */
interface PcmOutput {
    fun start(f: SsFormat)
    fun push(c: PcmChunk)
    fun clear(fromUs: Long?)
    fun end()
}

/**
 * A FLAC decoder fed one complete frame per [queue] (MediaCodec `audio/flac` on the panel; a fake
 * in JVM tests). Errors are thrown; the pipeline then discards the instance.
 */
interface FlacFrameDecoder {
    /** The underlying codec's name, for the log. */
    val name: String

    /** Configures and starts the decoder for [info]; [csd0] is [FlacStreamInfo.csd0]. */
    fun open(info: FlacStreamInfo, csd0: ByteArray)

    /** Queues one frame tagged [tag]; false if no input buffer became free within [timeoutUs]. */
    fun queue(data: ByteArray, offset: Int, length: Int, tag: Long, timeoutUs: Long): Boolean

    /** The next decoded frame (interleaved 16-bit), or null if none within [timeoutUs]. */
    fun dequeue(timeoutUs: Long): Decoded?

    fun close()

    class Decoded(val tag: Long, val pcm: ShortArray, val frames: Int)
}

/**
 * Turns the session's stream events and audio chunks into PCM for the [PcmOutput], in arrival
 * order, on its own thread (`ss-decode`), so neither the network reader nor the audio writer
 * decodes.
 *
 * PCM chunks are converted inline. FLAC chunks are split into frames ([FlacFrames.split]); each
 * frame is queued to the decoder with a serial tag, and its output is matched back by that tag.
 * A frame's server timestamp is the chunk's timestamp plus the block sizes of the frames before it
 * in the chunk (spec: the timestamp is that of the chunk's first sample; frames are contiguous), so
 * decoded PCM lands on the timeline exactly where the PCM stream's samples would. FLAC has no
 * encoder or decoder delay (aiosendspin's `FlacEncoder.lookahead_us` is 0, and libFLAC returns a
 * frame's samples for that frame), so no offset is applied.
 *
 * - `stream/start`, same format: continue. Other format (or a new `codec_header`): first decode
 *   everything already received in the old format (spec: chunks keep the format they were sent
 *   in), then switch. The output format is PCM at the stream's rate and channels, so a codec change
 *   at the same rate does not restart the output.
 * - `stream/clear`: frames still inside the decoder are forgotten (their outputs are dropped by
 *   tag); with `from_timestamp`, only those at or after it. The decoder is not flushed: FLAC frames
 *   decode independently and one is queued at a time, so the decoder holds no partial state (and
 *   `flush()` on Android 10's C2 FLAC decoder discards the STREAMINFO).
 * - `stream/end`: forget everything, release the decoder.
 * - Errors (an exception, a frame inconsistent with STREAMINFO, a stalled decoder) release the
 *   decoder; the next frame opens a new one. [FLAC_FAILURE_LIMIT] errors within
 *   [FLAC_FAILURE_WINDOW_MS], or a decoder that can't be created, call [onFlacFailed] once and
 *   FLAC audio is dropped from then on (the player reconnects advertising PCM only).
 */
class AudioPipeline(
    private val out: PcmOutput,
    private val flacDecoderFactory: () -> FlacFrameDecoder,
    private val onFlacFailed: (reason: String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val stallMs: Long = 1_500,
) {
    class Stats {
        var flacFrames = 0L; var pcmChunks = 0L; var wireBytes = 0L; var decodeErrors = 0L
        var droppedFrames = 0L; var decodeNs = 0L; var maxDecodeNs = 0L; var decodedFrames = 0L
        fun copy() = Stats().also {
            it.flacFrames = flacFrames; it.pcmChunks = pcmChunks; it.wireBytes = wireBytes; it.decodeErrors = decodeErrors
            it.droppedFrames = droppedFrames; it.decodeNs = decodeNs; it.maxDecodeNs = maxDecodeNs; it.decodedFrames = decodedFrames
        }
    }

    private sealed class Ev {
        class Start(val format: SsFormat, val header: ByteArray?) : Ev()
        class Audio(val chunk: SsAudioChunk) : Ev()
        class Clear(val fromUs: Long?) : Ev()
        object End : Ev()
        object ResetFlac : Ev()
        object Stop : Ev()
    }

    private class Pending(val serverTsUs: Long, val blockSize: Int, val wireBytes: Int, val queuedNs: Long, val queuedMs: Long)

    private val inbox = LinkedBlockingQueue<Ev>()
    private val statsLock = Any()
    private val stats = Stats()
    /** Events queued and not yet handled (tests: [awaitIdle]). */
    private val outstanding = AtomicInteger()
    @Volatile private var pendingCount = 0
    @Volatile var flacFailedReason: String? = null; private set
    @Volatile var decoderName: String? = null; private set

    // Decode thread only.
    private var format: SsFormat? = null
    private var header: ByteArray? = null
    private var info: FlacStreamInfo? = null
    private var decoder: FlacFrameDecoder? = null
    private val pending = LinkedHashMap<Long, Pending>()
    private var nextTag = 1L
    private var lastTag = 0L
    private var lastEndUs = 0.0
    private val failures = ArrayDeque<Long>()
    private var mismatchLogged = false

    private val thread = Thread({ loop() }, "ss-decode").apply { isDaemon = true; start() }

    fun start(format: SsFormat, codecHeader: ByteArray?) = post(Ev.Start(format, codecHeader))
    fun audio(chunk: SsAudioChunk) = post(Ev.Audio(chunk))
    fun clear(fromUs: Long?) = post(Ev.Clear(fromUs))
    fun end() = post(Ev.End)

    /** Allows FLAC again after [onFlacFailed] (the setting was switched back on). */
    fun resetFlacFailure() = post(Ev.ResetFlac)

    private fun post(ev: Ev) { outstanding.incrementAndGet(); inbox.put(ev) }

    /** Stops the thread after the events already queued; releases the decoder. */
    fun close() {
        post(Ev.Stop)
        try { thread.join(2_000) } catch (_: InterruptedException) {}
    }

    /** A snapshot; [resetMax] restarts [Stats.maxDecodeNs] (per logging interval). */
    fun stats(resetMax: Boolean = false): Stats = synchronized(statsLock) { stats.copy().also { if (resetMax) stats.maxDecodeNs = 0 } }

    /** Tests: waits until every queued event is handled and no frame is inside the decoder. */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (outstanding.get() == 0 && pendingCount == 0) return true
            Thread.sleep(2)
        }
        return false
    }

    // ------------------------------------------------------------------ decode thread

    private fun loop() {
        try {
            while (true) {
                // Waiting for decoder output: poll the inbox without blocking, then wait on the codec.
                val ev = if (pending.isEmpty()) inbox.poll(200, TimeUnit.MILLISECONDS) else inbox.poll()
                if (ev != null) {
                    if (ev is Ev.Stop) break
                    try { handle(ev) } catch (t: Throwable) { SsLog.w("decode: event failed", t) }
                }
                if (pending.isNotEmpty()) drainOutputs(if (ev == null) 5_000L else 0L)
                if (ev != null) outstanding.decrementAndGet()
            }
        } catch (_: InterruptedException) {
        } finally {
            releaseDecoder()
            pending.clear(); pendingCount = 0
            outstanding.set(0)
        }
    }

    private fun handle(ev: Ev) {
        when (ev) {
            is Ev.Start -> onStart(ev.format, ev.header)
            is Ev.Audio -> onAudio(ev.chunk)
            is Ev.Clear -> {
                if (ev.fromUs == null) pending.clear()
                else pending.entries.removeIf { it.value.serverTsUs >= ev.fromUs }
                pendingCount = pending.size
                out.clear(ev.fromUs)
            }
            Ev.End -> {
                pending.clear(); pendingCount = 0
                releaseDecoder()
                format = null; header = null; info = null
                out.end()
            }
            Ev.ResetFlac -> {
                failures.clear()
                if (flacFailedReason != null) {
                    flacFailedReason = null
                    // Re-arm the current stream's header, if it is FLAC.
                    val f = format; val h = header
                    if (f != null && f.codec == CODEC_FLAC && h != null) info = FlacStreamInfo.parse(h)
                }
            }
            Ev.Stop -> Unit
        }
    }

    private fun onStart(f: SsFormat, h: ByteArray?) {
        val same = f == format && (f.codec != CODEC_FLAC || h.contentEqualsNullable(header))
        if (!same && format != null) {
            // Keep and decode what arrived in the previous format before switching.
            drainAll(500)
            if (decoder != null) releaseDecoder()
        }
        format = f
        header = h
        info = null
        if (f.codec == CODEC_FLAC && flacFailedReason == null) {
            val si = h?.let { FlacStreamInfo.parse(it) }
            when {
                si == null -> fail("stream/start codec_header is not fLaC + STREAMINFO", fatal = true)
                si.sampleRate != f.sampleRate || si.channels != f.channels || si.bitsPerSample != f.bitDepth ->
                    fail("codec_header STREAMINFO ${si.sampleRate} Hz ${si.channels} ch ${si.bitsPerSample}-bit does not match stream/start", fatal = true)
                else -> info = si
            }
            if (!same) info?.let { SsLog.i("FLAC stream: block size ${it.minBlockSize}..${it.maxBlockSize}, ${it.sampleRate} Hz ${it.channels} ch ${it.bitsPerSample}-bit") }
        }
        out.start(SsFormat("pcm", f.channels, f.sampleRate, 16))
    }

    private fun onAudio(c: SsAudioChunk) {
        val f = format ?: return
        synchronized(statsLock) { stats.wireBytes += c.data.size }
        when (f.codec) {
            "pcm" -> {
                val fb = f.frameBytes
                val len = c.dataLength - c.dataLength % fb
                val pcm = PlaybackScheduler.decodePcm(c.data, c.dataOffset, len, f.bitDepth)
                synchronized(statsLock) { stats.pcmChunks++ }
                out.push(PcmChunk(c.timestampUs, pcm, pcm.size / f.channels, f.sampleRate, f.channels, c.data.size))
            }
            CODEC_FLAC -> onFlacChunk(c, f)
            else -> Unit
        }
    }

    private fun onFlacChunk(c: SsAudioChunk, f: SsFormat) {
        val si = info ?: return // FLAC failed or no usable header: dropped
        val frames = FlacFrames.split(c.data, c.dataOffset, c.dataLength)
        if (frames == null) {
            countDrop(1)
            fail("chunk at ${c.timestampUs} is not whole FLAC frames (${c.dataLength} bytes)", fatal = false, release = false)
            return
        }
        var samples = 0L
        for (fr in frames) {
            val tsUs = c.timestampUs + (samples * 1_000_000 + f.sampleRate / 2) / f.sampleRate
            samples += fr.blockSize
            if (!FlacFrames.consistent(fr.header, si)) {
                countDrop(1)
                fail("frame at $tsUs (${fr.header}) disagrees with STREAMINFO", fatal = false, release = false)
                continue
            }
            val d = ensureDecoder(si) ?: run { countDrop(1); return }
            val tag = nextTag++
            val wire = fr.length + if (fr === frames.first()) SsMessages.AUDIO_HEADER else 0
            try {
                var ok = d.queue(c.data, fr.offset, fr.length, tag, 0)
                // All input buffers busy: collect outputs (that frees them) and retry, up to ~200 ms.
                var tries = 0
                while (!ok && tries++ < 40) {
                    drainOutputs(5_000)
                    if (decoder !== d) { countDrop(1); return } // failed meanwhile (already counted)
                    ok = d.queue(c.data, fr.offset, fr.length, tag, 0)
                }
                if (!ok) throw IllegalStateException("no decoder input buffer for 200 ms")
                pending[tag] = Pending(tsUs, fr.blockSize, wire, System.nanoTime(), nowMs())
                pendingCount = pending.size
                synchronized(statsLock) { stats.flacFrames++ }
            } catch (t: Throwable) {
                countDrop(1)
                fail("queue: ${t.javaClass.simpleName}: ${t.message}", fatal = false)
                return
            }
            drainOutputs(0)
        }
    }

    private fun ensureDecoder(si: FlacStreamInfo): FlacFrameDecoder? {
        decoder?.let { return it }
        if (flacFailedReason != null) return null
        val d = try {
            flacDecoderFactory().also { it.open(si, si.csd0()) }
        } catch (t: Throwable) {
            fail("decoder open: ${t.javaClass.simpleName}: ${t.message}", fatal = false)
            return null
        }
        decoder = d
        if (decoderName != d.name) SsLog.i("FLAC decoder ${d.name} opened")
        decoderName = d.name
        return d
    }

    /** Collects decoder outputs; waits up to [timeoutUs] for the first one. */
    private fun drainOutputs(timeoutUs: Long) {
        val d = decoder ?: run { if (pending.isNotEmpty()) { countDrop(pending.size); pending.clear(); pendingCount = 0 }; return }
        var wait = timeoutUs
        while (true) {
            val o = try {
                d.dequeue(wait)
            } catch (t: Throwable) {
                fail("dequeue: ${t.javaClass.simpleName}: ${t.message}", fatal = false)
                return
            } ?: break
            wait = 0
            onOutput(o)
        }
        // A frame that never comes out: the decoder is stuck (e.g. libFLAC hit end-of-stream).
        val oldest = pending.values.firstOrNull()
        if (oldest != null && nowMs() - oldest.queuedMs > stallMs) {
            fail("decoder stalled (${pending.size} frames in, none out for ${nowMs() - oldest.queuedMs} ms)", fatal = false)
        }
    }

    private fun onOutput(o: FlacFrameDecoder.Decoded) {
        val f = format
        val p = pending.remove(o.tag)
        if (p == null) {
            // Cleared, or a second output for the frame before (placed right after it).
            if (o.tag == lastTag && f != null && o.frames > 0) {
                out.push(PcmChunk(Math.round(lastEndUs), o.pcm, o.frames, f.sampleRate, f.channels, 0))
                lastEndUs += o.frames * 1e6 / f.sampleRate
            }
            pendingCount = pending.size
            return
        }
        // Frames queued before this one that produced nothing were dropped by the decoder.
        val it = pending.entries.iterator()
        var lost = 0
        while (it.hasNext()) { val e = it.next(); if (e.key < o.tag) { it.remove(); lost++ } else break }
        if (lost > 0) countDrop(lost)
        pendingCount = pending.size
        val ns = System.nanoTime() - p.queuedNs
        synchronized(statsLock) {
            stats.decodeNs += ns
            if (ns > stats.maxDecodeNs) stats.maxDecodeNs = ns
            stats.decodedFrames++
        }
        if (f == null || o.frames <= 0) return
        if (o.frames != p.blockSize && !mismatchLogged) {
            mismatchLogged = true
            SsLog.w("FLAC frame at ${p.serverTsUs} decoded to ${o.frames} samples, header says ${p.blockSize}")
        }
        lastTag = o.tag
        lastEndUs = p.serverTsUs + o.frames * 1e6 / f.sampleRate
        out.push(PcmChunk(p.serverTsUs, o.pcm, o.frames, f.sampleRate, f.channels, p.wireBytes))
    }

    /** Waits up to [ms] for the decoder to return every frame queued so far. */
    private fun drainAll(ms: Long) {
        val deadline = nowMs() + ms
        while (pending.isNotEmpty() && decoder != null && nowMs() < deadline) drainOutputs(10_000)
        if (pending.isNotEmpty()) { countDrop(pending.size); pending.clear(); pendingCount = 0 }
    }

    private fun countDrop(n: Int) = synchronized(statsLock) { stats.droppedFrames += n }

    private fun releaseDecoder() {
        val d = decoder ?: return
        decoder = null
        try { d.close() } catch (t: Throwable) { SsLog.w("decoder close failed", t) }
    }

    /**
     * A decoding problem: log it, release the decoder (unless [release] is false: the bad input was
     * skipped before reaching it) and fall back for good after repeated failures or a [fatal] one.
     */
    private fun fail(reason: String, fatal: Boolean, release: Boolean = true) {
        synchronized(statsLock) { stats.decodeErrors++ }
        SsLog.w("FLAC: $reason")
        if (release) {
            if (pending.isNotEmpty()) { countDrop(pending.size); pending.clear(); pendingCount = 0 }
            releaseDecoder()
        }
        val now = nowMs()
        failures.addLast(now)
        while (failures.isNotEmpty() && now - failures.first() > FLAC_FAILURE_WINDOW_MS) failures.removeFirst()
        if ((fatal || failures.size >= FLAC_FAILURE_LIMIT) && flacFailedReason == null) {
            flacFailedReason = reason
            info = null
            releaseDecoder()
            SsLog.w("FLAC disabled after ${failures.size} error(s): $reason")
            try { onFlacFailed(reason) } catch (t: Throwable) { SsLog.w("onFlacFailed failed", t) }
        }
    }

    private fun ByteArray?.contentEqualsNullable(o: ByteArray?) = if (this == null) o == null else o != null && contentEquals(o)

    companion object {
        const val CODEC_FLAC = "flac"
        const val FLAC_FAILURE_LIMIT = 3
        const val FLAC_FAILURE_WINDOW_MS = 60_000L
    }
}
