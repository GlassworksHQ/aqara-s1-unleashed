/* SPDX-License-Identifier: MIT
 *
 * Ported from android-ip-camera: aguilaair/android-ip-camera, branch onvif-rtsp-motion, commit
 * 7b5e6f28; a fork of DigitallyRefined/android-ip-camera. Copyright (c) 2025 DigitallyRefined and
 * contributors. The licence text is in THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt.
 * Changes: package; tests for frames smaller than the grid and for sizes that don't divide by it. */

package dev.glassworks.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorCoreTest {
    private val w = MotionDetectorCore.GRID_W
    private val h = MotionDetectorCore.GRID_H

    private fun flat(v: Double) = DoubleArray(w * h) { v }
    private fun withBlob(base: Double, x0: Int, y0: Int, size: Int, v: Double) = flat(base).also { g ->
        for (y in y0 until y0 + size) for (x in x0 until x0 + size) g[y * w + x] = v
    }

    @Test fun staticSceneNeverTriggers() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        repeat(50) { d.processGrid(flat(100.0), it * 200L) }
        assertTrue(events.isEmpty())
    }

    @Test fun movingBlobTriggersThenClearsAfterHold() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(holdMs = 1_000, onMotion = { events += it })
        d.processGrid(flat(100.0), 0)
        // A person-sized blob moving across the frame for a few frames.
        for (i in 1..5) d.processGrid(withBlob(100.0, i * 4, 8, 6, 200.0), i * 200L)
        assertEquals(listOf(true), events)
        assertTrue(d.active)
        for (i in 6..20) d.processGrid(flat(100.0), i * 200L)
        assertEquals(listOf(true, false), events)
        assertFalse(d.active)
    }

    @Test fun globalBrightnessShiftIsIgnored() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(100.0), 0)
        for (i in 1..10) d.processGrid(flat(100.0 + i * 8), i * 200L)   // auto-exposure ramp
        assertTrue(events.isEmpty())
    }

    @Test fun lightSwitchReseedsInsteadOfTriggering() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(40.0), 0)
        // Lamp on: most of the frame brightens unevenly (left much more than right).
        val lit = DoubleArray(w * h) { i -> if (i % w < w * 3 / 4) 200.0 else 60.0 }
        for (i in 1..5) d.processGrid(lit, i * 200L)
        assertTrue(events.isEmpty())
    }

    @Test fun offFrameTvScenesAreIgnored() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(40.0), 0)
        // TV light flickering across the room: big, uneven brightness jumps frame to frame.
        val levels = listOf(40.0, 15.0, 15.0, 95.0, 90.0, 25.0, 40.0, 110.0, 30.0, 40.0)
        levels.forEachIndexed { i, v ->
            val g = DoubleArray(w * h) { c -> v * (0.6 + 0.8 * (c % w) / w) }   // falls off across the room
            d.processGrid(g, (i + 1) * 200L)
        }
        assertTrue(events.isEmpty())
    }

    @Test fun motionStillDetectedAfterLightingChange() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(40.0), 0)
        d.processGrid(flat(90.0), 200)                       // lamp on
        for (i in 2..8) d.processGrid(flat(90.0), i * 200L)  // settle past the hold-off
        for (i in 9..12) d.processGrid(withBlob(90.0, i * 3, 8, 6, 200.0), i * 200L)
        assertEquals(listOf(true), events)
    }

    @Test fun singleNoisyFrameIsNotMotion() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(100.0), 0)
        d.processGrid(withBlob(100.0, 10, 10, 6, 220.0), 200)
        d.processGrid(flat(100.0), 400)
        assertTrue(events.isEmpty())
    }

    /** A [w]x[h] luma frame, row stride [w], [px] at each pixel. */
    private fun run(d: MotionDetectorCore, w: Int, h: Int, t: Long, px: (Int, Int) -> Int) {
        val f = ByteArray(w * h) { i -> px(i % w, i / w).toByte() }
        d.process({ i -> f[i].toInt() and 0xff }, w, h, w, 1, t)
    }

    @Test fun framesTooSmallForTheGridEndMotion() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(100.0), 0)
        for (i in 1..3) d.processGrid(withBlob(100.0, i * 4, 8, 6, 200.0), i * 200L)
        assertTrue(d.active)
        run(d, 40, 20, 1_000) { _, _ -> 100 }
        assertFalse("a 40x20 frame can't be judged: motion ends rather than staying on", d.active)
        assertEquals(listOf(true, false), events)
    }

    @Test fun theEdgesOfASizeThatDoesntDivideAreSampled() {
        // 95x53: integer cells of 1x1 would only cover the top-left 48x27 pixels.
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        run(d, 95, 53, 0) { _, _ -> 100 }
        for (i in 1..3) run(d, 95, 53, i * 200L) { x, y -> if (x >= 70 && y >= 36) 220 else 100 }
        assertEquals("motion in the bottom-right corner is seen", listOf(true), events)
    }

    @Test fun aSizeThatDividesIsSampledExactlyAsUpstreamDid() {
        // Upstream's sampling, written out: cells of width/48 by height/27, four points each way.
        fun upstreamGrid(f: ByteArray, width: Int, height: Int): DoubleArray {
            val cw = width / w; val ch = height / h
            return DoubleArray(w * h) { c ->
                val gx = c % w; val gy = c / w
                var sum = 0
                for (sy in 0 until 4) for (sx in 0 until 4) {
                    val y = gy * ch + (sy * ch) / 4 + ch / 8; val x = gx * cw + (sx * cw) / 4 + cw / 8
                    sum += f[y * width + x].toInt() and 0xff
                }
                sum / 16.0
            }
        }
        val rnd = java.util.Random(117)
        val ours = MotionDetectorCore(onMotion = {})
        val theirs = MotionDetectorCore(onMotion = {})
        for (size in listOf(48 to 27, 480 to 270, 1920 to 1080)) {
            val (fw, fh) = size
            repeat(6) { t ->
                val f = ByteArray(fw * fh) { (60 + rnd.nextInt(40) + if (t % 2 == 1 && it % fw < fw / 5) 120 else 0).toByte() }
                ours.process({ i -> f[i].toInt() and 0xff }, fw, fh, fw, 1, t * 200L)
                theirs.processGrid(upstreamGrid(f, fw, fh), t * 200L)
                assertEquals("${fw}x$fh frame $t", theirs.lastChangedFraction, ours.lastChangedFraction, 0.0)
                assertEquals(theirs.active, ours.active)
            }
        }
    }

    @Test fun aResetDropsTheLightingPause() {
        val events = mutableListOf<Boolean>()
        val d = MotionDetectorCore(onMotion = { events += it })
        d.processGrid(flat(40.0), 0)
        d.processGrid(flat(120.0), 200) // a lamp: re-seed and a 5-frame pause
        d.reset()                       // the camera is lost and comes back
        d.processGrid(flat(120.0), 400) // seeds
        for (i in 3..4) d.processGrid(withBlob(120.0, i * 4, 8, 6, 220.0), i * 200L)
        assertEquals("someone in the first frames after a restart is seen", listOf(true), events)
    }
}
