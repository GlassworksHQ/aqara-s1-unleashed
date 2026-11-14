/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotsTest {

    private var now = 0L
    private val timers = mutableListOf<Pair<Long, Runnable>>()
    private val changes = mutableListOf<Boolean>()
    private val egress = FrameEgress({ now }, { delay, r -> timers += (now + delay) to r }, holdMs = 6_000) { changes += it }

    private fun advance(to: Long) {
        now = to
        timers.filter { it.first <= to }.also { timers.removeAll(it) }.forEach { it.second.run() }
    }

    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2)

    @Test fun aStillTurnsTheIndicatorOnUntilTheHoldRunsOut() {
        egress.still()
        assertTrue(egress.leaving)
        advance(3_000); egress.still() // a card refreshing: stays on, no blink
        advance(6_000)
        assertTrue(egress.leaving)
        advance(9_000)
        assertFalse(egress.leaving)
        assertEquals(listOf(true, false), changes)
    }

    @Test fun anOpenStreamKeepsItOnPastTheHold() {
        val s = egress.stream()
        egress.still()
        advance(10_000)
        assertTrue(egress.leaving)
        s.close(); s.close()
        assertFalse(egress.leaving)
        val a = egress.stream(); val b = egress.stream()
        a.close()
        assertTrue("one stream still open", egress.leaving)
        b.close()
        assertEquals(listOf(true, false, true, false), changes)
    }

    @Test fun presenceOnlySendsNothingAndDoesNotOpenTheCamera() {
        var asked = 0
        val gate = stillGate({ asked++; jpeg }, { CameraPolicy(privacy = false, use = CameraUse.PRESENCE) }, egress)
        assertNull(gate())
        assertEquals(0, asked)
        assertFalse(egress.leaving)
    }

    @Test fun privacyWinsOverSnapshots() {
        val gate = stillGate({ jpeg }, { CameraPolicy(privacy = true, use = CameraUse.PRESENCE_AND_SNAPSHOTS) }, egress)
        assertNull(gate())
        assertFalse(egress.leaving)
    }

    @Test fun snapshotsSendTheStillAndLightTheIndicator() {
        var policy = CameraPolicy(privacy = false, use = CameraUse.PRESENCE_AND_SNAPSHOTS)
        val gate = stillGate({ jpeg }, { policy }, egress)
        assertArrayEquals(jpeg, gate())
        assertTrue(egress.leaving)
        policy = policy.copy(use = CameraUse.PRESENCE) // read on every request
        assertNull(gate())
    }

    @Test fun privacyTurnedOnDuringTheCaptureStillWins() {
        var policy = CameraPolicy(privacy = false, use = CameraUse.PRESENCE_AND_SNAPSHOTS)
        val gate = stillGate({ policy = policy.copy(privacy = true); jpeg }, { policy }, egress)
        assertNull(gate())
        assertFalse(egress.leaving)
    }

    @Test fun noStillMeansNoIndicator() {
        val gate = stillGate({ null }, { CameraPolicy(false, CameraUse.PRESENCE_AND_SNAPSHOTS) }, egress)
        assertNull(gate())
        assertFalse(egress.leaving)
    }

    @Test fun theEntityGoesOnlyToEncryptedConnections() {
        val cam = snapshotCamera({ jpeg }, { CameraPolicy(false, CameraUse.PRESENCE_AND_SNAPSHOTS) }, egress)
        assertTrue(cam.authOnly)
        assertEquals("camera", cam.objectId)
        assertEquals(2f, cam.maxFps)
    }

    @Test fun theDefaultHoldIsSixSeconds() {
        val seen = mutableListOf<Boolean>()
        val e = FrameEgress({ now }, { delay, r -> timers += (now + delay) to r }) { seen += it }
        e.still()
        advance(5_999)
        assertTrue(e.leaving)
        advance(6_000)
        assertFalse(e.leaving)
    }
}
