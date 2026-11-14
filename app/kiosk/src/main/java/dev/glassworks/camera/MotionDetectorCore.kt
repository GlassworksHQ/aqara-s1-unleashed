/* SPDX-License-Identifier: MIT
 *
 * Ported from android-ip-camera: aguilaair/android-ip-camera, branch onvif-rtsp-motion, commit
 * 7b5e6f28; a fork of DigitallyRefined/android-ip-camera. Copyright (c) 2025 DigitallyRefined and
 * contributors. The licence text is in THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt.
 * Changes: package; a frame smaller than the grid ends motion (upstream kept it); cell bounds are
 * spread over the whole frame, so sizes that don't divide by 48x27 are sampled to the edges (the same
 * samples as upstream when they divide); reset() also clears the lighting pause. */

package dev.glassworks.camera

import kotlin.math.abs

/**
 * Pure-JVM frame-difference motion detector working on a coarse luma grid.
 *
 * Each sampled frame is reduced to [GRID_W]×[GRID_H] block averages and compared against a slowly
 * adapting background. A cell counts as changed when it differs by more than the sensitivity's
 * threshold after removing the frame-wide brightness shift (so auto-exposure drift is ignored).
 * Motion starts after [startFrames] consecutive positive frames and ends [holdMs] after the last.
 * Lighting changes re-seed the background instead of counting as motion: a jump in overall
 * brightness of more than [LIGHTING_JUMP] (e.g. an off-frame TV cutting between scenes, a lamp)
 * or a change covering most of the frame. Detection then pauses for [LIGHTING_HOLD_FRAMES].
 */
class MotionDetectorCore(
    private val sensitivity: () -> Int = { 5 },      // 1 (least) .. 10 (most sensitive)
    private val holdMs: Long = 8_000L,
    private val startFrames: Int = 2,
    private val onMotion: (Boolean) -> Unit,
) {
    companion object {
        const val GRID_W = 48
        const val GRID_H = 27
        private const val CELLS = GRID_W * GRID_H
        private const val LIGHTING_CHANGE_FRACTION = 0.5
        /** Relative jump in mean brightness treated as a lighting change (tuned on TV-lit footage). */
        private const val LIGHTING_JUMP = 0.35
        private const val LIGHTING_HOLD_FRAMES = 5
        private const val BG_ALPHA = 0.05
    }

    private val bg = DoubleArray(CELLS)
    private val cur = DoubleArray(CELLS)
    private var seeded = false
    private var positives = 0
    private var lastPositiveMs = 0L
    private var cooldown = 0

    @Volatile var active = false
        private set
    /** Fraction of grid cells that changed on the last evaluated frame (diagnostics / UI). */
    @Volatile var lastChangedFraction = 0.0
        private set

    fun reset() {
        // Glassworks: a new start also drops a lighting pause left from before. A source that kept
        // switching between sizes below and above the grid would clear pauses too; CameraHub keeps one size.
        seeded = false; positives = 0; cooldown = 0
        if (active) { active = false; onMotion(false) }
    }

    /**
     * Evaluate one frame. [luma] is read as unsigned bytes at index `y * rowStride + x * pixelStride`.
     */
    fun process(luma: (Int) -> Int, width: Int, height: Int, rowStride: Int, pixelStride: Int, nowMs: Long) {
        // Glassworks: a frame too small for the grid ends motion rather than keeping it forever.
        if (width < GRID_W || height < GRID_H) { reset(); return }
        // Block averages over a sparse 4×4 sample per cell — plenty for motion and cheap at 1080p.
        // Glassworks: each cell's bounds come from the full size, so a remainder isn't left unsampled
        // (the same samples as upstream when the size divides evenly).
        for (gy in 0 until GRID_H) for (gx in 0 until GRID_W) {
            val y0 = gy * height / GRID_H; val ch = (gy + 1) * height / GRID_H - y0
            val x0 = gx * width / GRID_W; val cw = (gx + 1) * width / GRID_W - x0
            var sum = 0
            for (sy in 0 until 4) {
                val y = y0 + (sy * ch) / 4 + ch / 8
                val row = y * rowStride
                for (sx in 0 until 4) {
                    val x = x0 + (sx * cw) / 4 + cw / 8
                    sum += luma(row + x * pixelStride)
                }
            }
            cur[gy * GRID_W + gx] = sum / 16.0
        }
        evaluate(nowMs)
    }

    /** Variant for tests/callers that already have a grid of [GRID_W]×[GRID_H] luma values. */
    fun processGrid(grid: DoubleArray, nowMs: Long) {
        require(grid.size == CELLS)
        grid.copyInto(cur)
        evaluate(nowMs)
    }

    private fun evaluate(nowMs: Long) {
        if (!seeded) { cur.copyInto(bg); seeded = true; return }
        var shift = 0.0
        var bgMean = 0.0
        for (i in 0 until CELLS) { shift += cur[i] - bg[i]; bgMean += bg[i] }
        shift /= CELLS; bgMean /= CELLS
        val s = sensitivity().coerceIn(1, 10)
        val threshold = 34.0 - s * 2.6            // luma delta per cell: ~31 (s=1) .. 8 (s=10)
        val areaFraction = 0.04 / s               // share of the frame: 4% (s=1) .. 0.4% (s=10)
        var changed = 0
        for (i in 0 until CELLS) if (abs(cur[i] - bg[i] - shift) > threshold) changed++
        val fraction = changed.toDouble() / CELLS
        lastChangedFraction = fraction

        if (fraction > LIGHTING_CHANGE_FRACTION || abs(shift) / maxOf(bgMean, 10.0) > LIGHTING_JUMP) {
            // Lighting change (TV scene cut, lamp, camera moved): re-seed, don't call it motion.
            cur.copyInto(bg); positives = 0; cooldown = LIGHTING_HOLD_FRAMES
            endIfIdle(nowMs)
            return
        }
        // Adapt slowly toward the current frame so gradual light changes fade into the background.
        for (i in 0 until CELLS) bg[i] += (cur[i] - bg[i]) * BG_ALPHA
        if (cooldown > 0) { cooldown--; endIfIdle(nowMs); return }

        if (fraction >= areaFraction) {
            positives++
            if (positives >= startFrames) {
                lastPositiveMs = nowMs
                if (!active) { active = true; onMotion(true) }
            }
        } else {
            positives = 0
            endIfIdle(nowMs)
        }
    }

    private fun endIfIdle(nowMs: Long) {
        if (active && nowMs - lastPositiveMs >= holdMs) { active = false; onMotion(false) }
    }
}
