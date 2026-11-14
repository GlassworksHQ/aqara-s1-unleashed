/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.glassworks.screen.PresenceSource

/**
 * One greyscale camera frame: the Y plane of a YUV image. Rows are [rowStride] bytes apart, of which
 * the first [width] are pixels. Unsigned: 0 is black, 255 white.
 */
class LumaFrame(val width: Int, val height: Int, val rowStride: Int, val luma: ByteArray) {
    init {
        require(width > 0 && height > 0 && rowStride >= width) { "bad frame ${width}x$height stride $rowStride" }
        require(luma.size >= rowStride * (height - 1) + width) { "frame needs more than ${luma.size} bytes" }
    }

    /**
     * The centre square, scaled to [size]×[size] by averaging each cell of pixels, row by row, one
     * unsigned byte a pixel. Person and face models take a small square image, and averaging keeps
     * thin edges that dropping pixels would lose.
     */
    fun square(size: Int): ByteArray {
        require(size in 1..minOf(width, height)) { "can't scale ${width}x$height to $size" }
        val side = minOf(width, height)
        val x0 = (width - side) / 2
        val y0 = (height - side) / 2
        val out = ByteArray(size * size)
        for (oy in 0 until size) {
            val ya = y0 + oy * side / size
            val yb = y0 + (oy + 1) * side / size
            for (ox in 0 until size) {
                val xa = x0 + ox * side / size
                val xb = x0 + (ox + 1) * side / size
                var sum = 0
                for (y in ya until yb) {
                    val row = y * rowStride
                    for (x in xa until xb) sum += luma[row + x].toInt() and 0xff
                }
                out[oy * size + ox] = (sum / ((yb - ya) * (xb - xa))).toByte()
            }
        }
        return out
    }
}

/** How likely [LumaFrame] shows a person, 0 to 1. Runs on the camera's thread; one frame at a time. */
fun interface PersonDetector {
    fun score(frame: LumaFrame): Float
}

/**
 * Where frames go. Called on one thread that isn't the main thread. While the source is open, frames
 * keep coming; if none comes for [CameraPresence.STALE_MS], presence counts the camera as gone.
 */
interface FrameSink {
    fun frame(frame: LumaFrame)

    /** The camera stopped delivering (closed by another app, disconnected, an error). No frames follow. */
    fun lost(why: String)
}

/** The camera, as presence sees it. The frame's implementation picks the camera and the size. */
fun interface FrameSource {
    /**
     * Starts delivering about [fps] frames a second to [sink]. Closing stops them and releases the
     * camera; it may be called from any thread, also before the first frame.
     */
    fun open(fps: Float, sink: FrameSink): AutoCloseable
}

/**
 * Turns per-frame scores into near or far. Near after [enterHits] scores in a row of at least
 * [threshold], so one odd frame doesn't wake the screen. Far once [holdMs] pass without such a score,
 * so someone who turns away for a moment doesn't count as gone. Not thread-safe.
 */
class PresenceFilter(
    val threshold: Float = 0.6f,
    val enterHits: Int = 2,
    val holdMs: Long = 5_000,
) {
    init { require(enterHits >= 1 && holdMs >= 0) }

    var near = false
        private set
    private var hits = 0
    private var lastHitMs = 0L

    fun update(score: Float, nowMs: Long): Boolean {
        if (score >= threshold) {
            hits++
            lastHitMs = nowMs
            if (hits >= enterHits) near = true
        } else {
            hits = 0
            if (near && nowMs - lastHitMs >= holdMs) near = false
        }
        return near
    }

    fun reset() {
        near = false
        hits = 0
    }
}

/**
 * A [PresenceSource] from camera frames, for frames with a camera and no presence sensor. It looks at
 * about [fps] frames a second and drops the rest. Frames go to [detector] and nowhere else: none is
 * kept or sent. The raw value of each reading is the detector's score, or -1 when there is none: the
 * camera was lost, went silent for [STALE_MS], or the detector failed. Each of those reads as far, and
 * [onProblem] gets why, once until frames are scored again.
 *
 * [clock] is `SystemClock.elapsedRealtime`; [post] runs a reading on the main thread, and [after] runs
 * one there after a delay.
 */
class CameraPresence(
    private val camera: FrameSource,
    private val detector: PersonDetector,
    private val fps: Float = DEFAULT_FPS,
    private val filter: PresenceFilter = PresenceFilter(),
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val post: (Runnable) -> Unit = Handler(Looper.getMainLooper())::post,
    private val after: (delayMs: Long, Runnable) -> Unit = { ms, r -> Handler(Looper.getMainLooper()).postDelayed(r, ms) },
    private val onProblem: (why: String) -> Unit = {},
) : PresenceSource {
    init { require(fps > 0f) { "fps must be positive" } }

    private val lock = Any()
    private var session: Session? = null

    override fun start(context: Context, onReading: (near: Boolean, raw: Float) -> Unit) {
        val s = Session(onReading)
        val old = synchronized(lock) { session.also { session = s } }
        old?.close()
        s.begin()
        val h = try {
            camera.open(fps, s)
        } catch (e: Exception) {
            s.fail("couldn't open the camera: ${e.javaClass.simpleName}")
            return
        }
        s.attach(h)
    }

    override fun stop(context: Context) {
        synchronized(lock) { session.also { session = null } }?.close()
    }

    /** A camera has no distance to set: how near counts is how big a person looks to the model. */
    override fun setRange(context: Context, metres: Float): String? = if (metres <= 0f) null else NO_RANGE

    // Every field is guarded by [lock], which the filter shares across sessions.
    private inner class Session(private val onReading: (Boolean, Float) -> Unit) : FrameSink {
        private var handle: AutoCloseable? = null
        private var closed = false
        private var lastScoredMs = Long.MIN_VALUE
        private var lastFrameMs = 0L
        private var watching = false
        private var failing = false
        /** Counts [fail]s, so a score that was running when one happened is dropped. */
        private var fails = 0

        /** Starts the silence watchdog before the camera opens: a source that never sends counts as gone too. */
        fun begin() = synchronized(lock) {
            lastFrameMs = clock()
            watching = true
            after(STALE_MS, ::checkStale)
        }

        /** The camera [open] returned. A [close] that came first closes it at once. */
        fun attach(h: AutoCloseable) {
            val lateClose = synchronized(lock) { if (!closed) handle = h; closed }
            if (lateClose) h.close()
        }

        override fun frame(frame: LumaFrame) {
            val now = clock()
            val epoch = synchronized(lock) {
                if (closed) return
                lastFrameMs = now
                if (!watching) { watching = true; after(STALE_MS, ::checkStale) }
                if (lastScoredMs != Long.MIN_VALUE && now - lastScoredMs < (1000f / fps).toLong()) return
                lastScoredMs = now
                fails
            }
            val score = try {
                detector.score(frame)
            } catch (e: Exception) {
                fail("the detector failed: ${e.javaClass.simpleName}")
                return
            }
            val near = synchronized(lock) {
                if (closed || fails != epoch) return
                failing = false
                filter.update(score, now)
            }
            post { if (isOpen()) onReading(near, score) }
        }

        override fun lost(why: String) = fail("the camera stopped: $why")

        /** Far, and why, once until a frame is scored again. */
        fun fail(why: String) {
            val first = synchronized(lock) {
                if (closed) return
                fails++
                filter.reset()
                (!failing).also { failing = true }
            }
            post {
                if (!isOpen()) return@post
                if (first) onProblem(why)
                onReading(false, -1f)
            }
        }

        private fun checkStale() {
            val idle = synchronized(lock) {
                watching = false
                if (closed) return
                (clock() - lastFrameMs).also { if (it < STALE_MS) { watching = true; after(STALE_MS - it, ::checkStale) } }
            }
            if (idle >= STALE_MS) fail("no frames for ${idle / 1000} s")
        }

        private fun isOpen() = synchronized(lock) { !closed }

        fun close() {
            val h = synchronized(lock) {
                closed = true
                filter.reset()
                handle.also { handle = null }
            }
            h?.close()
        }
    }

    companion object {
        /** Enough to notice someone walking up within a second, cheap enough to run all day. */
        const val DEFAULT_FPS = 2f

        /** A camera that sends nothing this long counts as gone, so a stuck source can't keep the screen on. */
        const val STALE_MS = 5_000L

        const val NO_RANGE = "The camera has no range setting"
    }
}
