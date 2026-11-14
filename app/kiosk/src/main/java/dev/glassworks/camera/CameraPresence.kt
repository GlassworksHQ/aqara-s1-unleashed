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

/** Where frames go. Called on one thread that isn't the main thread. */
interface FrameSink {
    fun frame(frame: LumaFrame)

    /** The camera stopped delivering (closed by another app, disconnected, an error). No frames follow. */
    fun lost(why: String)
}

/** The camera, as presence sees it. The frame's implementation picks the camera and the size. */
fun interface FrameSource {
    /** Starts delivering about [fps] frames a second to [sink]. Closing stops them and releases the camera. */
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
 * kept or sent. The raw value of each reading is the detector's score, or -1 when the camera was lost.
 *
 * [clock] is `SystemClock.elapsedRealtime`; [post] runs a reading on the main thread.
 */
class CameraPresence(
    private val camera: FrameSource,
    private val detector: PersonDetector,
    private val fps: Float = DEFAULT_FPS,
    private val filter: PresenceFilter = PresenceFilter(),
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val post: (Runnable) -> Unit = Handler(Looper.getMainLooper())::post,
) : PresenceSource {
    init { require(fps > 0f) { "fps must be positive" } }

    private val lock = Any()
    private var session: Session? = null

    override fun start(context: Context, onReading: (near: Boolean, raw: Float) -> Unit) {
        val s = Session(onReading)
        synchronized(lock) {
            session?.close()
            session = s
        }
        s.handle = camera.open(fps, s)
    }

    override fun stop(context: Context) {
        synchronized(lock) { session.also { session = null } }?.close()
    }

    /** A camera has no distance to set: how near counts is how big a person looks to the model. */
    override fun setRange(context: Context, metres: Float): String? = if (metres <= 0f) null else NO_RANGE

    private inner class Session(private val onReading: (Boolean, Float) -> Unit) : FrameSink {
        @Volatile var handle: AutoCloseable? = null
        @Volatile private var closed = false
        private var lastMs = Long.MIN_VALUE

        override fun frame(frame: LumaFrame) {
            if (closed) return
            val now = clock()
            if (lastMs != Long.MIN_VALUE && now - lastMs < (1000f / fps).toLong()) return
            lastMs = now
            val score = detector.score(frame)
            val near = synchronized(lock) { if (closed) return else filter.update(score, now) }
            post { if (!closed) onReading(near, score) }
        }

        override fun lost(why: String) {
            if (!closed) post { if (!closed) onReading(false, -1f) }
        }

        fun close() {
            synchronized(lock) {
                closed = true
                filter.reset()
            }
            handle?.close()
        }
    }

    companion object {
        /** Enough to notice someone walking up within a second, cheap enough to run all day. */
        const val DEFAULT_FPS = 2f
        const val NO_RANGE = "The camera has no range setting"
    }
}
