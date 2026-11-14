/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.app.Application
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraPresenceTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun frame(w: Int, h: Int, stride: Int = w, px: (Int, Int) -> Int) =
        LumaFrame(w, h, stride, ByteArray(stride * h).also { b -> for (y in 0 until h) for (x in 0 until w) b[y * stride + x] = px(x, y).toByte() })

    @Test fun squareAveragesTheCentreSquareAndSkipsTheStridePadding() {
        // 6x4 with 2 bytes of padding a row: the centre square is columns 1..4. Padding is 255 and must not count.
        val f = LumaFrame(6, 4, 8, ByteArray(32) { i -> if (i % 8 >= 6) 255.toByte() else ((i % 8) * 40).toByte() })
        val s = f.square(2)
        // Each output pixel averages a 2x2 cell: columns 1,2 -> (40+80)/2, columns 3,4 -> (120+160)/2.
        assertArrayEquals(byteArrayOf(60, 140.toByte(), 60, 140.toByte()), s)
    }

    @Test fun squareReadsBytesAsUnsigned() {
        assertEquals(200, frame(4, 4) { _, _ -> 200 }.square(1)[0].toInt() and 0xff)
    }

    @Test fun squareFromARealSizedFrameKeepsTheModelSize() {
        val s = frame(640, 480) { x, _ -> if (x < 320) 0 else 255 }.square(96)
        assertEquals(96 * 96, s.size)
        assertEquals(0, s[0].toInt() and 0xff)
        assertEquals(255, s[95].toInt() and 0xff)
    }

    @Test fun oneGoodFrameDoesNotMakeSomeoneNear() {
        val f = PresenceFilter(threshold = 0.6f, enterHits = 2, holdMs = 5_000)
        assertFalse(f.update(0.9f, 0))
        assertFalse(f.update(0.1f, 500))
        assertFalse(f.update(0.9f, 1_000))
        assertTrue(f.update(0.9f, 1_500))
    }

    @Test fun nearHoldsThroughShortMissesThenGoesFar() {
        val f = PresenceFilter(threshold = 0.6f, enterHits = 1, holdMs = 5_000)
        assertTrue(f.update(0.8f, 0))
        assertTrue(f.update(0.0f, 4_999))
        assertTrue(f.update(0.7f, 5_000))
        assertTrue(f.update(0.0f, 9_999))
        assertFalse(f.update(0.0f, 10_000))
    }

    private class FakeCamera : FrameSource {
        var sink: FrameSink? = null
        var openedAt: Float? = null
        var closed = 0
        override fun open(fps: Float, sink: FrameSink): AutoCloseable {
            openedAt = fps
            this.sink = sink
            return AutoCloseable { closed++ }
        }
    }

    private fun presence(cam: FakeCamera, scores: ArrayDeque<Float>, clock: () -> Long) = CameraPresence(
        cam, { scores.removeFirst() }, fps = 2f, filter = PresenceFilter(enterHits = 2, holdMs = 1_000), clock = clock, post = { it.run() },
    )

    @Test fun framesBecomeReadingsAtTheAskedRateWithTheScoreAsRaw() {
        val cam = FakeCamera()
        var now = 0L
        val scores = ArrayDeque(listOf(0.9f, 0.9f, 0.1f))
        val readings = mutableListOf<Pair<Boolean, Float>>()
        val p = presence(cam, scores, { now })
        p.start(ctx) { near, raw -> readings += near to raw }
        assertEquals(2f, cam.openedAt)
        val f = frame(4, 4) { _, _ -> 0 }

        cam.sink!!.frame(f)
        now = 100; cam.sink!!.frame(f) // too soon after the last: dropped, the detector isn't asked
        now = 500; cam.sink!!.frame(f)
        now = 1_000; cam.sink!!.frame(f)

        assertEquals(listOf(false to 0.9f, true to 0.9f, true to 0.1f), readings)
        assertTrue(scores.isEmpty())
    }

    @Test fun aLostCameraReportsFarAndStopClosesIt() {
        val cam = FakeCamera()
        var now = 0L
        val readings = mutableListOf<Pair<Boolean, Float>>()
        val p = presence(cam, ArrayDeque(listOf(0.9f, 0.9f, 0.9f)), { now })
        p.start(ctx) { near, raw -> readings += near to raw }
        val f = frame(4, 4) { _, _ -> 0 }
        cam.sink!!.frame(f); now = 500; cam.sink!!.frame(f)
        assertEquals(true, readings.last().first)

        cam.sink!!.lost("disconnected")
        assertEquals(false to -1f, readings.last())

        // A new session starts far: the filter forgot the earlier hits.
        p.stop(ctx)
        assertEquals(1, cam.closed)
        p.start(ctx) { near, raw -> readings += near to raw }
        now = 2_000; cam.sink!!.frame(f)
        assertEquals(false to 0.9f, readings.last())
    }

    @Test fun nothingIsReportedAfterStop() {
        val cam = FakeCamera()
        val readings = mutableListOf<Boolean>()
        val p = presence(cam, ArrayDeque(listOf(0.9f)), { 0L })
        p.start(ctx) { near, _ -> readings += near }
        val sink = cam.sink!!
        p.stop(ctx)
        sink.frame(frame(4, 4) { _, _ -> 0 })
        sink.lost("late")
        assertTrue(readings.isEmpty())
    }

    @Test fun aReadingQueuedBeforeStopIsDropped() {
        val cam = FakeCamera()
        val queued = mutableListOf<Runnable>()
        val readings = mutableListOf<Boolean>()
        val p = CameraPresence(cam, { 0.9f }, post = { queued += it }, clock = { 0L })
        p.start(ctx) { near, _ -> readings += near }
        cam.sink!!.frame(frame(4, 4) { _, _ -> 0 })
        cam.sink!!.lost("gone")
        p.stop(ctx)
        queued.forEach { it.run() }
        assertTrue(readings.isEmpty())
    }

    @Test fun aRangeIsRefusedInWordsAndZeroIsFine() {
        val p = CameraPresence(FakeCamera(), { 0f }, post = { it.run() })
        assertNull(p.setRange(ctx, 0f))
        assertEquals(CameraPresence.NO_RANGE, p.setRange(ctx, 1.5f))
    }
}
