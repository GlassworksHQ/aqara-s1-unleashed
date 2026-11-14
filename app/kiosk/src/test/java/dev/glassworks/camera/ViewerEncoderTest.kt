/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerEncoderTest {

    private val events = mutableListOf<String>()

    private inner class Encoder(val n: Int, val fail: (StreamEncoder) -> Unit) : StreamEncoder {
        override val surface: Surface? = null
        var keyFrames = 0
        var released = false
        override fun requestKeyFrame() { keyFrames++ }
        override fun release() { released = true; events += "release $n" }
        override fun toString() = "encoder $n"
    }

    private inner class Rig {
        var now = 0L
        val posted = mutableListOf<Pair<Long, Runnable>>()
        val made = mutableListOf<Encoder>()
        var watching = true
        val egressSeen = mutableListOf<Boolean>()
        val egress = FrameEgress({ now }, { _, _ -> }) { egressSeen += it }
        val ve = ViewerEncoder(
            input = { e -> events += "input ${e ?: "none"}" },
            encoders = { _, failed -> Encoder(made.size + 1, failed).also { made += it; events += "made ${it.n}" } },
            egress = egress, onAccessUnit = { _, _ -> }, watched = { watching },
            clock = { now }, after = { ms, r -> posted += (now + ms) to r },
        )

        fun runPosted(to: Long) {
            now = to
            posted.filter { it.first <= to }.also { posted.removeAll(it) }.forEach { it.second.run() }
        }
    }

    @Test fun aViewerStartsTheEncoderAndTheLastOneStopsIt() {
        val r = Rig()
        r.ve.viewers(1)
        assertEquals(1, r.made.size)
        assertTrue("the indicator is on while someone watches", r.egress.leaving)
        r.ve.viewers(2)
        assertEquals("a second viewer asks for a keyframe, not a second encoder", 1, r.made.size)
        assertEquals(1, r.made[0].keyFrames)
        r.ve.viewers(0)
        assertTrue(r.made[0].released)
        assertFalse(r.egress.leaving)
        assertEquals("the camera stops drawing into it before it is released",
            listOf("made 1", "input encoder 1", "input none", "release 1"), events)
    }

    @Test fun aReplacementSurvivesTheOldEncodersLateFailure() {
        val r = Rig()
        r.ve.viewers(1)
        val first = r.made[0]
        first.fail(first)                 // fails: replaced at once (the first restart isn't limited)
        assertEquals(2, r.made.size)
        val second = r.made[1]
        first.fail(first)                 // its late report again
        assertFalse("the replacement keeps running", second.released)
        assertTrue(r.egress.leaving)
        assertEquals(2, r.made.size)
    }

    @Test fun aRestartTooSoonAfterTheLastIsPostedNotDropped() {
        val r = Rig()
        r.ve.viewers(1)
        r.made[0].fail(r.made[0])         // t=0: restarted, the limit starts now
        r.now = 1_000
        r.made[1].fail(r.made[1])         // t=1 s: too soon
        assertEquals(2, r.made.size)
        assertEquals(1, r.posted.size)
        assertEquals("when the limit allows", ViewerEncoder.RESTART_MS, r.posted[0].first)
        r.runPosted(ViewerEncoder.RESTART_MS)
        assertEquals("the viewers get frames again", 3, r.made.size)
        assertTrue(r.egress.leaving)
    }

    @Test fun aPostedRestartWithNobodyWatchingDoesNothing() {
        val r = Rig()
        r.ve.viewers(1)
        r.made[0].fail(r.made[0])
        r.now = 1_000
        r.made[1].fail(r.made[1])
        r.watching = false
        r.runPosted(ViewerEncoder.RESTART_MS)
        assertEquals(2, r.made.size)
        assertFalse(r.egress.leaving)
    }

    @Test fun afterCloseNothingHappens() {
        val r = Rig()
        r.ve.viewers(1)
        r.ve.close()
        assertTrue(r.made[0].released)
        r.ve.viewers(1)                   // a stopped server's late report
        r.made[0].fail(r.made[0])
        assertEquals(1, r.made.size)
        assertFalse(r.egress.leaving)
    }
}
