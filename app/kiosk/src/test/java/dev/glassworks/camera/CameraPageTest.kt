/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPageTest {

    private val watching = CameraReading(
        use = CameraUse.PRESENCE, privacy = false, onvif = false, signedIn = false, running = true, problem = null,
        detecting = true, near = true, nearSince = 1L, leaving = false, lastStill = null, streaming = false,
        watchers = emptyList(), address = StreamAddress("192.0.2.41", 8554, 8000),
    )
    private val time: (Long) -> String = { "14:31" }

    private fun status(r: CameraReading) = CameraPage.status(r)

    @Test fun presenceOnlySaysNoImageLeaves() {
        assertEquals("Watching for people · no image leaves the panel" to CameraPage.Tone.OK, status(watching))
        val out = CameraPage.tiles(watching, time)[2]
        assertEquals("None" to "Presence only", out.value to out.detail)
    }

    @Test fun imagesLeavingSayWhereTheyGo() {
        val snap = watching.copy(use = CameraUse.PRESENCE_AND_SNAPSHOTS, leaving = true)
        assertEquals("Camera in use · sending images to Home Assistant" to CameraPage.Tone.OK, status(snap))
        assertEquals("Now", CameraPage.tiles(snap, time)[2].value)
        val stream = snap.copy(onvif = true, streaming = true, watchers = listOf("192.0.2.20", "192.0.2.25"))
        assertEquals("Camera in use · streaming to 2 viewers · images leave the panel", status(stream).first)
        assertEquals("192.0.2.20 · 192.0.2.25", CameraPage.watching(stream))
        assertEquals("Stream to 2 viewers", CameraPage.tiles(stream, time)[2].detail)
        // Two sessions from one address are two viewers, listed once.
        val twice = stream.copy(watchers = listOf("192.0.2.20", "192.0.2.20"))
        assertEquals("Camera in use · streaming to 2 viewers · images leave the panel", status(twice).first)
        assertEquals("192.0.2.20", CameraPage.watching(twice))
        assertEquals("Camera in use · streaming to 1 viewer · images leave the panel", status(stream.copy(watchers = listOf("192.0.2.20"))).first)
    }

    @Test fun aRefusedPermissionIsAProblemAndClosesTheCamera() {
        val r = watching.copy(problem = CameraPane.NOT_ALLOWED, detecting = false, near = null)
        assertEquals(CameraPage.Tone.PROBLEM, status(r).second)
        assertTrue(status(r).first.startsWith("Android hasn't allowed the camera"))
        val (presence, camera) = CameraPage.tiles(r, time)
        assertEquals("—" to "Not watching", presence.value to presence.detail)
        assertEquals("—" to "Not in use", camera.value to camera.detail)
    }

    @Test fun privacyIsQuietAndClosed() {
        val r = watching.copy(privacy = true, detecting = false, near = null)
        assertEquals("Camera privacy is on · the camera is closed" to CameraPage.Tone.QUIET, status(r))
        assertEquals("Not in use", CameraPage.tiles(r, time)[1].detail)
    }

    @Test fun theLastStillShowsWhenItLeft() {
        val r = watching.copy(use = CameraUse.PRESENCE_AND_SNAPSHOTS, lastStill = 5L)
        assertEquals("Last 14:31 · snapshot", CameraPage.tiles(r, time)[2].detail)
        assertEquals("Near since 14:31", CameraPage.tiles(r, time)[0].detail)
    }

    @Test fun theAddressesAreThePanelsOwnWithNoSignIn() {
        val r = watching.copy(streaming = true)
        assertEquals("rtsp://192.0.2.41:8554/live · H.264 · 1280 × 720", CameraPage.stream(r))
        assertEquals("http://192.0.2.41:8000/onvif/device_service", CameraPage.device(r))
        assertEquals("192.0.2.41 · RTSP port 8554, ONVIF port 8000", CameraPage.address(r.address))
        assertTrue("closed ports say so", CameraPage.stream(watching).endsWith("closed now"))
        listOf(CameraPage.stream(r), CameraPage.device(r)).forEach { assertFalse("no credentials in $it", it.contains("@")) }
        assertEquals("The stream is off", CameraPage.watching(watching))
    }

    @Test fun theStreamSwitchSaysWhyItCantBeOn() {
        assertEquals(CameraPage.NEEDS_SNAPSHOTS, CameraPage.streamDetail(privacy = false, use = CameraUse.PRESENCE))
        assertEquals("privacy first", CameraPage.PRIVACY_ON, CameraPage.streamDetail(privacy = true, use = CameraUse.PRESENCE_AND_SNAPSHOTS))
        assertEquals(CameraPage.PRIVACY_ON, CameraPage.streamRefusal(privacy = true, use = CameraUse.PRESENCE))
        assertEquals(null, CameraPage.streamRefusal(privacy = false, use = CameraUse.PRESENCE_AND_SNAPSHOTS))
        assertTrue(CameraPage.streamDetail(false, CameraUse.PRESENCE_AND_SNAPSHOTS).contains("UniFi Protect"))
    }

    @Test fun theCameraTileSaysWhichCameraAndItsRate() {
        val front = watching.copy(lens = CameraFacts.Lens.FRONT, frameRate = 5)
        assertEquals("Front" to "5 frames a second", CameraPage.tiles(front, time)[1].let { it.value to it.detail })
        val streaming = front.copy(lens = CameraFacts.Lens.BACK, frameRate = 15, watchers = listOf("192.0.2.20"), streaming = true)
        assertEquals("Back" to "15 frames a second", CameraPage.tiles(streaming, time)[1].let { it.value to it.detail })
        assertEquals("Its own frame rate", CameraPage.tiles(front.copy(frameRate = null), time)[1].detail)
        assertEquals("1 frame a second", CameraPage.tiles(front.copy(frameRate = 1), time)[1].detail)
        assertEquals("Not in use", CameraPage.tiles(front.copy(privacy = true, detecting = false), time)[1].detail)
    }

    @Test fun theSignInNeverShowsAndSaysHowToSetIt() {
        assertEquals("Set", CameraPage.signIn(true))
        assertEquals("Not set. Set a sign-in with adb for now.", CameraPage.signIn(false))
        assertFalse("no repository path on the panel", CameraPage.signIn(false).contains("/"))
    }

    @Test fun beforeThePaneRunsItSaysSo() {
        assertEquals(CameraPage.Tone.QUIET, status(watching.copy(running = false)).second)
        assertEquals("Not watching", CameraPage.tiles(watching.copy(running = false), time)[0].detail)
    }
}
