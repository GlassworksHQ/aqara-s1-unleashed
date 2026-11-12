package sh.edm.s1kiosk.sendspin

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** PCM output (AudioTrack on the panel, a simulation in tests). Interleaved signed 16-bit frames. */
interface AudioSink {
    /** (Re)creates the output for this format; any queued audio is dropped and frame counting restarts at 0. */
    fun open(sampleRate: Int, channels: Int)

    /** Blocking write; returns the number of frames written. */
    fun write(buf: ShortArray, frames: Int): Int

    /**
     * The frame being presented at the output and the [System.nanoTime] when it was, like
     * `AudioTrack.getTimestamp()`. Returns false until the output reports one.
     */
    fun timestamp(out: LongArray): Boolean

    fun setGain(gain: Float)

    fun close()
}

/** One received chunk, decoded to 16-bit PCM. */
class PcmChunk(val serverTsUs: Long, val pcm: ShortArray, val frames: Int, val sampleRate: Int, val channels: Int, val wireBytes: Int) {
    val durationUs: Double get() = frames * 1e6 / sampleRate
    val endTsUs: Double get() = serverTsUs + durationUs
}

/**
 * Received audio ordered by server timestamp, with a read cursor. Not thread-safe; the scheduler
 * guards it with its lock.
 */
class JitterBuffer {
    private val q = ArrayDeque<PcmChunk>()
    /** Frames of the head chunk already consumed. */
    private var pos = 0
    var bytes = 0L; private set

    val isEmpty get() = q.isEmpty()
    val size get() = q.size

    fun add(c: PcmChunk) {
        // Chunks normally arrive in order; keep the queue sorted if one doesn't.
        if (q.isEmpty() || q.last().serverTsUs <= c.serverTsUs) q.addLast(c)
        else {
            val i = q.indexOfFirst { it.serverTsUs > c.serverTsUs }
            if (i == 0 && pos > 0) return // would land before audio already playing: late, drop
            q.add(i, c)
        }
        bytes += c.wireBytes
    }

    fun clear() { q.clear(); pos = 0; bytes = 0 }

    /** `stream/clear` with `from_timestamp`: drop the chunks whose timestamp is at or after [fromUs]. */
    fun clearFrom(fromUs: Long) {
        while (q.isNotEmpty() && q.last().serverTsUs >= fromUs) {
            val c = q.removeLast()
            bytes -= c.wireBytes
            if (q.isEmpty()) pos = 0
        }
    }

    /** Server time of the next unread frame. */
    fun nextTsUs(): Double? = q.firstOrNull()?.let { it.serverTsUs + pos * 1e6 / it.sampleRate }

    fun head(): PcmChunk? = q.firstOrNull()

    /** Server time where the buffered audio ends. */
    fun endTsUs(): Double? = q.lastOrNull()?.endTsUs

    /** Copies up to [maxFrames] frames from the head chunk only (so a gap is re-timed by the caller). */
    fun read(dst: ShortArray, dstFrame: Int, maxFrames: Int): Int {
        val c = q.firstOrNull() ?: return 0
        val n = minOf(maxFrames, c.frames - pos)
        System.arraycopy(c.pcm, pos * c.channels, dst, dstFrame * c.channels, n * c.channels)
        advance(n)
        return n
    }

    /** Drops every buffered frame scheduled before server time [tsUs]. */
    fun dropBefore(tsUs: Double) {
        while (true) {
            val c = q.firstOrNull() ?: return
            if (c.endTsUs <= tsUs) { pop(); continue }
            val f = ((tsUs - c.serverTsUs) * c.sampleRate / 1e6).toInt()
            if (f > pos) pos = minOf(f, c.frames)
            if (pos >= c.frames) pop()
            return
        }
    }

    private fun advance(n: Int) {
        pos += n
        val c = q.first()
        if (pos >= c.frames) pop()
    }

    private fun pop() {
        val c = q.removeFirst()
        bytes -= c.wireBytes
        pos = 0
    }
}

/**
 * Maps output frame numbers to local time from `AudioTrack.getTimestamp()`-style reports: the
 * first report anchors a nominal-rate line, later reports nudge a correction term (EMA), and a jump
 * above 20 ms (an underrun or a reset) re-anchors.
 */
class OutputClock(private val sampleRate: Int, private val alpha: Double = 0.05) {
    private var anchorFrame = 0L
    private var anchorNs = 0L
    private var corrNs = 0.0
    var valid = false; private set
    var reports = 0; private set
    var lastResidualNs = 0.0; private set

    fun reset() { valid = false; corrNs = 0.0; reports = 0 }

    fun onTimestamp(framePos: Long, nanoTime: Long) {
        reports++
        if (!valid) { anchorFrame = framePos; anchorNs = nanoTime; corrNs = 0.0; valid = true; return }
        val predicted = anchorNs + (framePos - anchorFrame) * 1e9 / sampleRate + corrNs
        val d = nanoTime - predicted
        lastResidualNs = d
        if (abs(d) > 20e6) { anchorFrame = framePos; anchorNs = nanoTime; corrNs = 0.0; return }
        corrNs += alpha * d
    }

    /** Local time (µs) at which output frame [frame] is presented. */
    fun timeUsOfFrame(frame: Long): Double = (anchorNs + (frame - anchorFrame) * 1e9 / sampleRate + corrNs) / 1000.0
}

/**
 * Plays [JitterBuffer] audio so each sample leaves the output at its server timestamp (via the
 * time filter) minus the output delay (spec "Playback Synchronization", "Suggested correction
 * strategy").
 *
 * Every [step] writes one block (10 ms). Before writing it compares where the next buffered frame is
 * due (`clientTime(ts) - output_delay`) with when the next written frame will be presented
 * ([OutputClock]); `err > 0` means early:
 * - `err ≥ block`: write silence (start of stream, or a gap between chunks).
 * - `|err| > hardUs`: one-shot snap: write `err` of silence, or drop `-err` of audio.
 * - otherwise soft correction on the smoothed error: duplicate or drop one frame in this block
 *   (≤ 1/480 = 0.21 % speed at 48 kHz, within the spec's ±0.5 %), dead band [deadUs].
 *
 * Thread model: [push]/[clear]/[start]/[end] from the network thread, [step] from the audio thread.
 */
class PlaybackScheduler(
    private val sink: AudioSink,
    private val timeFilter: () -> TimeFilter.Snapshot,
    private val nowNanos: () -> Long = System::nanoTime,
    private val hardUs: Double = 3_000.0,
    private val deadUs: Double = 150.0,
    private val smoothAlpha: Double = 0.05,
    private val timestampEveryBlocks: Int = 5,
) : PcmOutput {
    class Stats {
        var duplicated = 0L; var dropped = 0L; var snapsEarly = 0L; var snapsLate = 0L
        var silenceBlocks = 0L; var underrunBlocks = 0L; var lateChunks = 0L; var chunks = 0L
        var lastErrUs = 0.0; var smoothErrUs = 0.0; var bufferedMs = 0.0
        var framesPlayed = 0L
        fun copy() = Stats().also {
            it.duplicated = duplicated; it.dropped = dropped; it.snapsEarly = snapsEarly; it.snapsLate = snapsLate
            it.silenceBlocks = silenceBlocks; it.underrunBlocks = underrunBlocks; it.lateChunks = lateChunks; it.chunks = chunks
            it.lastErrUs = lastErrUs; it.smoothErrUs = smoothErrUs; it.bufferedMs = bufferedMs; it.framesPlayed = framesPlayed
        }
    }

    private val lock = Object()
    private val jb = JitterBuffer()
    private var stats = Stats()

    // Guarded by lock; consumed by the audio thread.
    private var active = false
    private var format: SsFormat? = null
    private var reopen = false
    @Volatile var outputDelayUs = 0L

    // Audio thread only.
    private var openFormat: SsFormat? = null
    private var clock: OutputClock? = null
    private var framesWritten = 0L
    private var blocks = 0L
    private var smooth = 0.0
    private var buf = ShortArray(0)
    private val ts = LongArray(2)

    val isActive: Boolean get() = synchronized(lock) { active }

    fun stats(): Stats = synchronized(lock) { stats.copy() }

    /** `stream/start`: begin (or, with the same format, continue) a stream. */
    override fun start(f: SsFormat): Unit = synchronized(lock) {
        if (!active || f != format) {
            if (active && f != format) jb.clear() // format change we can't splice: restart cleanly
            format = f
            reopen = true
        }
        active = true
        lock.notifyAll()
    }

    /** `stream/end`: stop output and clear buffers. */
    override fun end(): Unit = synchronized(lock) {
        active = false
        jb.clear()
        reopen = true
        lock.notifyAll()
    }

    /** `stream/clear`: without [fromUs] drop everything (and what the output already holds); with it, only audio at or after it. */
    override fun clear(fromUs: Long?): Unit = synchronized(lock) {
        if (fromUs == null) { jb.clear(); reopen = true } else jb.clearFrom(fromUs)
        lock.notifyAll()
    }

    /** Adds a decoded chunk; drops it if it has already ended (late). */
    override fun push(c: PcmChunk): Unit = synchronized(lock) {
        if (!active) return@synchronized
        stats.chunks++
        val snap = timeFilter()
        if (snap.converged) {
            val endLocal = snap.clientTimeExact(c.endTsUs) - outputDelayUs
            if (endLocal < nowNanos() / 1000.0) { stats.lateChunks++; return@synchronized }
        }
        jb.add(c)
        lock.notifyAll()
    }

    /** Waits up to [ms] for work (used by the audio thread while idle). */
    fun awaitWork(ms: Long) = synchronized(lock) { if (!active) lock.wait(ms) }

    /**
     * Writes one block to the sink (audio thread). Returns false when idle (nothing written).
     *
     * [owns] is read after each sink call that can block (open, write). Once it is false the step
     * returns false at once and leaves the audio-thread state alone: an audio thread that was
     * stuck there past its run's stop can't write into, or reset, the next run's output.
     */
    fun step(owns: () -> Boolean = { true }): Boolean {
        val f: SsFormat?
        var close = false
        synchronized(lock) {
            if (reopen) {
                // Re-created below if still active: drops whatever the output still holds.
                reopen = false
                close = openFormat != null
                openFormat = null
            }
            f = if (active) format else null
        }
        if (close) sink.close()
        if (f == null) return false
        if (openFormat != f) {
            sink.open(f.sampleRate, f.channels)
            if (!owns()) return false
            openFormat = f
            clock = OutputClock(f.sampleRate)
            framesWritten = 0; blocks = 0; smooth = 0.0
        }
        val ch = f.channels
        val block = f.sampleRate / 100
        if (buf.size < (block + 2) * ch) buf = ShortArray((block + 2) * ch)
        val oc = clock!!
        if (blocks++ % timestampEveryBlocks == 0L && sink.timestamp(ts)) {
            // A report beyond what was written is stale (e.g. from before a reset): ignore it.
            if (ts[0] in 0..framesWritten) oc.onTimestamp(ts[0], ts[1])
        }
        val framePeriodUs = 1e6 / f.sampleRate
        var silence = 0
        var n = 0
        synchronized(lock) {
            val snap = timeFilter()
            val next = jb.nextTsUs()
            stats.bufferedMs = next?.let { s -> jb.endTsUs()!!.minus(s) / 1000.0 } ?: 0.0
            if (!oc.valid || !snap.converged || next == null) {
                if (next == null && oc.valid) stats.underrunBlocks++
                silence = block
                return@synchronized
            }
            val outUs = oc.timeUsOfFrame(framesWritten)
            val playUs = snap.clientTimeExact(next) - outputDelayUs
            val err = playUs - outUs
            stats.lastErrUs = err
            when {
                err >= block * framePeriodUs -> { silence = block; stats.silenceBlocks++ }
                err > hardUs -> { silence = (err / framePeriodUs).roundToInt().coerceAtLeast(1); stats.snapsEarly++; smooth = 0.0 }
                err < -hardUs -> {
                    // Late: drop the audio that should already have played (in server time).
                    val target = next + (-err) * (1 + if (snap.useDrift) snap.drift else 0.0)
                    jb.dropBefore(target)
                    stats.snapsLate++
                    smooth = 0.0
                    n = -1 // nothing written this step; re-evaluate
                }
                else -> {
                    smooth += smoothAlpha * (err - smooth)
                    n = jb.read(buf, 0, block)
                    if (smooth > deadUs && n > 0) {
                        // Early: repeat the last frame once (wait one frame).
                        System.arraycopy(buf, (n - 1) * ch, buf, n * ch, ch)
                        n++
                        smooth -= framePeriodUs
                        stats.duplicated++
                    } else if (smooth < -deadUs && n > 1) {
                        // Late: drop the last frame once.
                        n--
                        smooth += framePeriodUs
                        stats.dropped++
                    }
                }
            }
            stats.smoothErrUs = smooth
        }
        if (n < 0) return true
        if (silence > 0) {
            if (buf.size < silence * ch) buf = ShortArray(silence * ch)
            java.util.Arrays.fill(buf, 0, silence * ch, 0)
            n = silence
        }
        var off = 0
        while (off < n) {
            val w = sink.write(if (off == 0) buf else buf.copyOfRange(off * ch, n * ch), n - off)
            if (!owns()) return false
            if (w <= 0) break
            off += w
        }
        framesWritten += off
        synchronized(lock) { stats.framesPlayed += off }
        return true
    }

    companion object {
        /** Little-endian signed PCM bytes → 16-bit samples (24/32-bit keep the top 16 bits). */
        fun decodePcm(data: ByteArray, offset: Int, length: Int, bitDepth: Int): ShortArray {
            val bps = max(1, bitDepth / 8)
            val n = length / bps
            val out = ShortArray(n)
            var i = offset
            for (k in 0 until n) {
                out[k] = when (bps) {
                    2 -> ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort()
                    1 -> (data[i].toInt() shl 8).toShort() // Sendspin PCM is signed at every depth
                    else -> ((data[i + bps - 2].toInt() and 0xFF) or (data[i + bps - 1].toInt() shl 8)).toShort()
                }
                i += bps
            }
            return out
        }

        /** Spec: `amplitude = (volume / 100)^1.5`. */
        fun amplitude(volume: Int): Float = Math.pow(volume.coerceIn(0, 100) / 100.0, 1.5).toFloat()
    }
}
