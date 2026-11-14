/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraPresenceTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    /** A 96x54 picture (two pixels a detector cell), [stride] bytes a row with [pad] in the rest. */
    private fun scene(stride: Int = W, pad: (x: Int) -> Int = { 0 }, px: (x: Int, y: Int) -> Double) =
        LumaFrame(W, H, stride, ByteArray(stride * H) { i ->
            val x = i % stride; val y = i / stride
            (if (x >= W) pad(x) else px(x, y).toInt().coerceIn(0, 255)).toByte()
        })

    private fun flat(v: Double) = scene { _, _ -> v }

    /** Someone about a sixth of the frame wide and two thirds tall, at [x0] pixels from the left. */
    private fun person(room: Double, x0: Int) = scene { x, y -> if (x in x0 until x0 + 16 && y in 12 until 48) room + 90 else room }

    private class FakeCamera : FrameSource {
        @Volatile var sink: FrameSink? = null
        var openedAt: Float? = null
        @Volatile var closed = 0
        override fun open(fps: Float, sink: FrameSink): AutoCloseable {
            openedAt = fps
            this.sink = sink
            return AutoCloseable { closed++ }
        }
    }

    /** Delayed callbacks, run by [runDue] when the test's clock passes them. */
    private class Timers {
        val due = mutableListOf<Pair<Long, Runnable>>()
        var now = 0L
        fun after(ms: Long, r: Runnable) { due += (now + ms) to r }
        fun runDue(to: Long) {
            now = to
            while (true) {
                val next = due.filter { it.first <= to }.minByOrNull { it.first } ?: return
                due.remove(next)
                next.second.run()
            }
        }
    }

    private class Run(sensitivity: Int = 5) {
        val cam = FakeCamera()
        val timers = Timers()
        val readings = mutableListOf<Pair<Boolean, Float>>()
        val motion = mutableListOf<Boolean>()
        val problems = mutableListOf<String>()
        var level = sensitivity
        val presence = CameraPresence(
            cam, { level }, clock = { timers.now }, post = { it.run() }, after = timers::after,
            onProblem = { problems += it }, onMotion = { motion += it },
        )
        val near get() = readings.lastOrNull()?.first ?: false

        /** One frame every 200 ms, the detector's own rate. */
        fun feed(frames: List<LumaFrame>) = frames.forEach { f ->
            timers.now += 200
            cam.sink!!.frame(f)
        }
    }

    private fun started(sensitivity: Int = 5) = Run(sensitivity).also { r -> r.presence.start(ctx) { near, raw -> r.readings += near to raw } }

    // ---------------------------------------------------------------- lighting is not presence

    @Test fun aLampSwitchingOnIsNotSomeone() {
        val r = started()
        r.feed(List(5) { flat(40.0) })
        // Most of the room brightens, unevenly: the lamp is on the left.
        r.feed(List(20) { scene { x, _ -> if (x < W * 3 / 4) 200.0 else 60.0 } })
        assertFalse(r.readings.any { it.first })
        assertTrue(r.motion.isEmpty())
    }

    @Test fun aGradualDuskIsNotSomeone() {
        val r = started(sensitivity = 10)
        // Five minutes of dusk through a window on the right, one frame every 200 ms: the light falls
        // from 140 to 30, faster near the window.
        r.feed(List(1_500) { t -> val k = 1.0 - t / 1_500.0; scene { x, _ -> 30 + 110 * k * (0.7 + 0.6 * x / W) } })
        assertFalse(r.readings.any { it.first })
    }

    @Test fun aTvCuttingBetweenScenesIsNotSomeone() {
        val r = started()
        r.feed(listOf(flat(40.0)))
        // The TV is off frame; its light falls off across the room and jumps at every cut.
        val cuts = listOf(40.0, 15.0, 15.0, 95.0, 90.0, 25.0, 40.0, 110.0, 30.0, 40.0, 120.0, 20.0, 60.0, 60.0, 10.0)
        r.feed(cuts.map { v -> scene { x, _ -> v * (0.6 + 0.8 * x / W) } })
        assertFalse(r.readings.any { it.first })
    }

    @Test fun someoneWalkingUpIsSeenEvenRightAfterTheLampWentOn() {
        val r = started()
        r.feed(List(3) { flat(40.0) })
        r.feed(List(8) { flat(90.0) }) // lamp on, then past the detector's 5-frame pause
        assertFalse(r.near)
        r.feed((0 until 6).map { person(90.0, 8 + it * 6) })
        assertTrue(r.near)
        assertEquals(listOf(true), r.motion)
        assertTrue("the raw value is the changed share of the picture", r.readings.last().second > 0.05f)
    }

    @Test fun someoneWhoLeavesIsGoneEightSecondsLater() {
        val r = started()
        r.feed(listOf(flat(60.0)))
        r.feed((0 until 4).map { person(60.0, 10 + it * 8) })
        assertTrue(r.near)
        r.feed(List(39) { flat(60.0) }) // 7.8 s after the last change
        assertTrue(r.near)
        r.feed(List(2) { flat(60.0) })
        assertFalse(r.near)
        assertEquals(listOf(true, false), r.motion)
    }

    @Test fun oneOddFrameIsNotSomeone() {
        val r = started()
        r.feed(listOf(flat(80.0), person(80.0, 30), flat(80.0), flat(80.0)))
        assertFalse(r.readings.any { it.first })
    }

    @Test fun sensitivityDecidesWhetherASmallChangeCounts() {
        // A hand-sized change of 20 levels: below sensitivity 1's threshold, above 10's.
        fun hand(room: Double, x0: Int) = scene { x, y -> if (x in x0 until x0 + 8 && y in 20 until 30) room + 20 else room }
        val dull = started(sensitivity = 1)
        dull.feed(listOf(flat(80.0)) + (0 until 6).map { hand(80.0, 10 + it * 8) })
        assertFalse(dull.near)
        val keen = started(sensitivity = 10)
        keen.feed(listOf(flat(80.0)) + (0 until 6).map { hand(80.0, 10 + it * 8) })
        assertTrue(keen.near)
    }

    @Test fun rowPaddingIsNeverReadAsPicture() {
        val r = started()
        // 128-byte rows for a 96-pixel picture. After the first frame the last bytes of each row's padding
        // turn white: read as picture, that would look like something small that appeared.
        r.feed(List(10) { i -> scene(stride = 128, pad = { x -> if (x >= 124 && i >= 1) 255 else 70 }) { _, _ -> 70.0 } })
        assertFalse(r.readings.any { it.first })
    }

    @Test fun anAutoExposureStepIsNotSomeone() {
        val r = started()
        // A wall at 80 and a bright window on a tenth of the picture. The camera's exposure steps up by
        // a fifth, so the window brightens more than the wall: the frame-wide shift is taken out first.
        fun room(gain: Double) = scene { x, y -> gain * (if (x < 20 && y < 26) 140.0 else 80.0) }
        r.feed(List(4) { room(1.0) } + List(10) { room(1.2) })
        assertFalse(r.readings.any { it.first })
    }

    @Test fun theCameraReExposingAfterTheLampIsNotSomeone() {
        val r = started()
        r.feed(List(3) { flat(40.0) })
        // The lamp comes on, then the camera hunts for two frames, darkening a third of the picture,
        // before it settles.
        r.feed(listOf(flat(100.0), scene { x, _ -> if (x < W / 3) 40.0 else 100.0 }, scene { x, _ -> if (x < W / 3) 40.0 else 100.0 }))
        r.feed(List(10) { flat(100.0) })
        assertFalse(r.readings.any { it.first })
    }

    // ---------------------------------------------------------------- the session

    @Test fun framesAreLookedAtFiveTimesASecondAndTheRestDropped() {
        val r = started()
        assertEquals(5f, r.cam.openedAt)
        r.cam.sink!!.frame(flat(50.0))
        r.timers.now = 100; r.cam.sink!!.frame(flat(50.0)) // too soon: dropped
        r.timers.now = 200; r.cam.sink!!.frame(flat(50.0))
        assertEquals(2, r.readings.size)
    }

    @Test fun aLostCameraReadsFarSaysWhyOnceAndForgetsTheMotion() {
        val r = started()
        r.feed(listOf(flat(60.0)) + (0 until 4).map { person(60.0, 10 + it * 8) })
        assertTrue(r.near)
        r.cam.sink!!.lost("unplugged")
        r.cam.sink!!.lost("again")
        assertEquals(false to -1f, r.readings.last())
        assertEquals(listOf(true, false), r.motion)
        assertEquals(listOf("the camera stopped: unplugged"), r.problems)
        // A source that keeps going after all starts from a fresh background: one frame seeds it.
        r.feed(listOf(person(60.0, 40)))
        assertFalse(r.near)
    }

    @Test fun aCameraThatWontOpenReadsFarAndSaysWhy() {
        val readings = mutableListOf<Pair<Boolean, Float>>()
        val problems = mutableListOf<String>()
        val p = CameraPresence({ _, _ -> throw SecurityException("no permission") }, post = { it.run() },
            after = { _, _ -> }, onProblem = { problems += it })
        p.start(ctx) { near, raw -> readings += near to raw }
        assertEquals(listOf(false to -1f), readings)
        assertEquals(listOf("couldn't open the camera: SecurityException"), problems)
    }

    @Test fun aSourceThatNeverSendsReadsFarAfterTheStaleTime() {
        val r = started()
        r.timers.runDue(CameraPresence.STALE_MS - 1)
        assertTrue(r.readings.isEmpty())
        r.timers.runDue(CameraPresence.STALE_MS)
        assertEquals(listOf(false to -1f), r.readings)
        assertEquals(listOf("no frames for 5 s"), r.problems)
    }

    @Test fun aSourceThatGoesSilentWhileSomeoneIsThereReadsFar() {
        val r = started()
        r.feed(listOf(flat(60.0)) + (0 until 4).map { person(60.0, 10 + it * 8) })
        assertTrue(r.near)
        val last = r.timers.now
        r.timers.runDue(last + CameraPresence.STALE_MS - 1)
        assertTrue(r.near)
        r.timers.runDue(last + CameraPresence.STALE_MS)
        assertEquals(false to -1f, r.readings.last())
        assertEquals(listOf(true, false), r.motion)
        r.timers.runDue(last + 60_000)
        assertEquals("said once, not again every period", 1, r.problems.size)
    }

    @Test fun aSecondSilenceAfterTheCameraCameBackIsCaughtToo() {
        val r = started()
        r.feed(listOf(flat(60.0)))
        r.timers.runDue(r.timers.now + CameraPresence.STALE_MS)
        assertEquals(false to -1f, r.readings.last())
        r.feed(listOf(flat(60.0), flat(60.0))) // back: the watchdog is armed again
        assertEquals(0f, r.readings.last().second)
        r.timers.runDue(r.timers.now + CameraPresence.STALE_MS)
        assertEquals(false to -1f, r.readings.last())
        assertEquals("each silence is reported once", listOf("no frames for 5 s", "no frames for 5 s"), r.problems)
    }

    @Test fun aCameraThatWontOpenReadsFarAgainWhenTheWatchdogFiresButSaysWhyOnce() {
        val timers = Timers()
        val readings = mutableListOf<Pair<Boolean, Float>>()
        val problems = mutableListOf<String>()
        val p = CameraPresence({ _, _ -> throw SecurityException("no permission") }, clock = { timers.now }, post = { it.run() },
            after = timers::after, onProblem = { problems += it })
        p.start(ctx) { near, raw -> readings += near to raw }
        timers.runDue(CameraPresence.STALE_MS)
        assertEquals(listOf(false to -1f, false to -1f), readings)
        assertEquals(listOf("couldn't open the camera: SecurityException"), problems)
    }

    @Test fun aStopDuringOpenClosesTheCameraOnceOpenReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var closed = 0
        val cam = FrameSource { _, _ ->
            entered.countDown()
            release.await()
            AutoCloseable { closed++ }
        }
        val p = CameraPresence(cam, post = { it.run() }, after = { _, _ -> })
        val starter = Thread { p.start(ctx) { _, _ -> } }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        p.stop(ctx) // the handle isn't back yet
        release.countDown()
        starter.join(5_000)
        assertEquals("the camera opened during stop() is released", 1, closed)
    }

    @Test fun stopClosesTheCameraEndsTheMotionAndANewSessionStartsFresh() {
        val r = started()
        r.feed(listOf(flat(60.0)) + (0 until 4).map { person(60.0, 10 + it * 8) })
        assertTrue(r.near)
        r.presence.stop(ctx)
        assertEquals(1, r.cam.closed)
        assertEquals(listOf(true, false), r.motion)
        r.presence.start(ctx) { near, raw -> r.readings += near to raw }
        r.feed(listOf(person(60.0, 50), person(60.0, 58)))
        assertFalse("the first frame of a session only seeds the background", r.near)
    }

    @Test fun nothingIsReportedAfterStop() {
        val r = started()
        val sink = r.cam.sink!!
        r.presence.stop(ctx)
        val before = r.readings.size
        r.timers.now += 200; sink.frame(flat(10.0))
        sink.lost("late")
        r.timers.runDue(60_000)
        assertEquals(before, r.readings.size)
        assertTrue(r.problems.isEmpty())
    }

    @Test fun aReadingQueuedBeforeStopIsDropped() {
        val cam = FakeCamera()
        val queued = mutableListOf<Runnable>()
        val readings = mutableListOf<Boolean>()
        val p = CameraPresence(cam, post = { queued += it }, clock = { 0L }, after = { _, _ -> })
        p.start(ctx) { near, _ -> readings += near }
        cam.sink!!.frame(flat(50.0))
        cam.sink!!.lost("gone")
        p.stop(ctx)
        queued.forEach { it.run() }
        assertTrue(readings.isEmpty())
    }

    @Test fun readingsArriveInTheOrderTheStateChanged() {
        // Posts are queued under the lock, so a failure and a frame can't swap on the way.
        val cam = FakeCamera()
        val queued = mutableListOf<Runnable>()
        val readings = mutableListOf<Float>()
        var now = 0L
        val p = CameraPresence(cam, post = { queued += it }, clock = { now }, after = { _, _ -> })
        p.start(ctx) { _, raw -> readings += raw }
        cam.sink!!.frame(flat(50.0))
        cam.sink!!.lost("glitch")
        now = 200; cam.sink!!.frame(flat(50.0))
        queued.forEach { it.run() }
        assertEquals(listOf(0f, -1f, 0f), readings)
    }

    @Test fun theDefaults() {
        assertEquals(5f, CameraPresence.DEFAULT_FPS)
        assertEquals(8_000L, CameraPresence.HOLD_MS)
        assertEquals(5, CameraPresence.DEFAULT_SENSITIVITY)
        assertEquals(5_000L, CameraPresence.STALE_MS)
        val cam = FakeCamera()
        CameraPresence(cam, post = { it.run() }, after = { _, _ -> }).start(ctx) { _, _ -> }
        assertEquals(CameraPresence.DEFAULT_FPS, cam.openedAt)
    }

    @Test fun aRangeIsRefusedInWordsAndZeroIsFine() {
        val p = CameraPresence(FakeCamera(), post = { it.run() }, after = { _, _ -> })
        assertNull(p.setRange(ctx, 0f))
        assertEquals(CameraPresence.NO_RANGE, p.setRange(ctx, 1.5f))
    }

    private companion object {
        const val W = 96
        const val H = 54
    }
}
