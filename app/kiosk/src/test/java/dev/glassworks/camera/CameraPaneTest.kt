/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.app.Application
import android.content.Context
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.BinarySensorEntity
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.ProtoMessage
import sh.edm.esphome.ProtoWriter
import sh.edm.esphome.SwitchEntity
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraPaneTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val file get() = app.getSharedPreferences("camera-test", Context.MODE_PRIVATE)
    private val exec = Executors.newSingleThreadScheduledExecutor()

    @After fun tearDown() { exec.shutdownNow(); file.edit().clear().commit() }

    private class Camera : FrameSource {
        var sink: FrameSink? = null
        var opens = 0
        var closes = 0
        override fun open(fps: Float, sink: FrameSink): AutoCloseable {
            opens++; this.sink = sink
            return AutoCloseable { closes++; this.sink = null }
        }
    }

    private class Stream : StreamOutput {
        var running: Pair<String, String>? = null
        var starts = 0
        val motion = mutableListOf<Boolean>()
        var refuse = false
        override fun start(signIn: Pair<String, String>): Boolean {
            if (refuse) return false
            starts++; running = signIn; return true
        }
        override fun stop() { running = null }
        override fun setMotion(active: Boolean) { motion += active }
    }

    private inner class Rig(permitted: Boolean = true, ownSensor: Boolean = false, random: java.security.SecureRandom = java.security.SecureRandom()) {
        val prefs = CameraPrefs(file) { random }
        val camera = Camera()
        val stream = Stream()
        val screen = mutableListOf<Pair<Boolean, Float>>()
        var allowed = permitted
        var publishes = 0
        var releases = 0
        var now = 0L
        val pane = CameraPane(
            PaneContext(
                app, prefs = { file },
                DeviceIdentity("test", "Test", "02:00:00:00:00:01", "x", "x", "x", "x", 0),
                publishStates = { publishes++ }, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
            ),
            prefs, camera, { byteArrayOf(1) }, stream,
            FrameEgress({ now }, { _, _ -> }) {},
            permitted = { allowed }, ownSensor = { ownSensor }, toScreen = { n, r -> screen += n to r }, exec = exec,
            release = { releases++ },
            presenceFactory = { source, onMotion, onProblem ->
                CameraPresence(source, { prefs.sensitivity }, clock = { now }, post = { it.run() }, after = { _, _ -> },
                    onProblem = onProblem, onMotion = onMotion)
            },
        )

        fun changed(key: String) = pane.onEvent(PanelEvent.SettingChanged(key))

        /** Someone walks in: a seed frame, then four with a person-sized change. */
        fun someoneWalksIn() {
            repeat(5) { i ->
                now += 200
                camera.sink!!.frame(LumaFrame(96, 54, 96, ByteArray(96 * 54) { p -> if (i > 0 && p % 96 in (i * 8) until (i * 8 + 16)) 120 else 60 }))
            }
        }
    }

    @Test fun presenceOnlyWatchesForPeopleAndFeedsTheScreen() {
        val r = Rig()
        r.pane.start()
        assertEquals(1, r.camera.opens)
        assertNull(r.stream.running)
        r.someoneWalksIn()
        assertEquals(true, r.screen.last().first)
        val sensor = r.pane.entities().filterIsInstance<BinarySensorEntity>().single()
        assertEquals("camera_presence", sensor.objectId)
        assertEquals(true, ProtoMessage(sensor.state().toByteArray()).bool(2))
    }

    @Test fun privacyClosesTheCameraAndTellsTheScreenNobodyIsThere() {
        val r = Rig()
        r.pane.start()
        r.someoneWalksIn()
        r.prefs.privacy = true
        r.changed(CameraPrefs.KEY_PRIVACY)
        assertEquals(1, r.camera.closes)
        assertEquals(false to -1f, r.screen.last())
        r.prefs.privacy = false
        r.changed(CameraPrefs.KEY_PRIVACY)
        assertEquals(2, r.camera.opens)
    }

    @Test fun aDeviceWithItsOwnSensorDoesntOpenTheCameraForPresence() {
        val r = Rig(ownSensor = true)
        r.pane.start()
        assertEquals(0, r.camera.opens)
        assertEquals(CameraPlan.NOTHING, r.pane.plan)
    }

    @Test fun withoutAndroidsPermissionNothingOpensAndThePaneSaysWhy() {
        val r = Rig(permitted = false)
        r.pane.start()
        assertEquals(0, r.camera.opens)
        assertEquals(CameraPane.NOT_ALLOWED, r.pane.problem)
        r.allowed = true
        r.pane.reconcile() // the periodic check: someone allowed it meanwhile
        assertEquals(1, r.camera.opens)
        assertNull(r.pane.problem)
    }

    @Test fun theStreamRunsOnlyWithItsSwitchAndSnapshotsAndAlwaysWithASignIn() {
        val r = Rig()
        r.pane.start()
        r.prefs.onvif = true; r.changed(CameraPrefs.KEY_ONVIF)
        assertNull("refused in Presence only", r.stream.running)
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.changed(CameraPrefs.KEY_USE)
        // Never without a sign-in: this panel's own is made as the stream turns on (maintainer, 2026-11-14).
        assertEquals(StreamSignIn.USERNAME, r.stream.running?.first)
        r.prefs.signIn = "viewer" to "s3cret"; r.changed(CameraPrefs.KEY_STREAM_PASSWORD)
        assertEquals("a sign-in set by hand replaces it", "viewer" to "s3cret", r.stream.running)
        r.someoneWalksIn()
        assertEquals("motion reaches the ONVIF events", listOf(true), r.stream.motion)
        r.prefs.use = CameraUse.PRESENCE; r.changed(CameraPrefs.KEY_USE)
        assertNull(r.stream.running)
    }

    @Test fun aStreamThatCantOpenItsPortsSaysSo() {
        val r = Rig()
        r.stream.refuse = true
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.prefs.onvif = true; r.prefs.signIn = "viewer" to "s3cret"
        r.pane.start()
        assertEquals(CameraPane.STREAM_FAILED, r.pane.problem)
    }

    @Test fun theOnvifSwitchSpringsBackInPresenceOnly() {
        val r = Rig()
        val onvif = r.pane.entities().filterIsInstance<SwitchEntity>().single { it.objectId == "onvif_stream" }
        onvif.command(ProtoMessage(ProtoWriter().bool(2, true).toByteArray()))
        assertFalse(r.prefs.onvif)
        assertEquals(false, ProtoMessage(onvif.state().toByteArray()).bool(2))
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        onvif.command(ProtoMessage(ProtoWriter().bool(2, true).toByteArray()))
        assertTrue(r.prefs.onvif)
        assertTrue("only an encrypted connection may open a port", onvif.authOnly)
    }

    @Test fun theEntitiesAreTheApprovedOnes() {
        val e = Rig().pane.entities().associateBy { it.objectId }
        assertEquals(listOf("camera", "camera_presence", "camera_privacy", "camera_use", "onvif_stream", "camera_sensitivity"), e.keys.toList())
        assertEquals(EntityCategory.NONE, e.getValue("camera_privacy").category)
        assertFalse(e.getValue("camera_privacy").disabledByDefault)
        assertTrue(e.getValue("camera_use").disabledByDefault)
        assertEquals(EntityCategory.CONFIG, e.getValue("onvif_stream").category)
        assertTrue(e.getValue("camera").authOnly)
    }

    @Test fun stopClosesEverything() {
        val r = Rig()
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.prefs.onvif = true; r.prefs.signIn = "viewer" to "s3cret"
        r.pane.start()
        r.pane.stop()
        assertEquals(1, r.camera.closes)
        assertNull(r.stream.running)
        assertEquals(CameraPlan.NOTHING, r.pane.plan)
    }

    @Test fun aCheckThatWasAlreadyWaitingWhenThePaneStoppedDoesNothing() {
        val r = Rig()
        r.pane.start()
        r.pane.stop()
        r.pane.reconcile() // the periodic check, run after stop() released the pane
        assertEquals(1, r.camera.opens)
        assertEquals(CameraPlan.NOTHING, r.pane.plan)
    }

    @Test fun privacyAndStopLetTheCameraCloseAtOnce() {
        val r = Rig()
        r.pane.start()
        assertEquals(0, r.releases)
        r.prefs.privacy = true; r.changed(CameraPrefs.KEY_PRIVACY)
        assertTrue("a still's linger is dropped too", r.releases >= 1)
        val before = r.releases
        r.pane.stop()
        assertTrue(r.releases > before)
    }

    @Test fun noStillWhileThePaneIsOff() {
        val r = Rig()
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        assertTrue("before start: judged as privacy", r.pane.stillPolicy().privacy)
        r.pane.start()
        assertFalse(r.pane.stillPolicy().privacy)
        assertTrue(r.pane.stillPolicy().stillsMayLeave)
        r.pane.stop()
        assertFalse("stopped: no still leaves", r.pane.stillPolicy().stillsMayLeave)
        r.pane.start(); r.allowed = false
        assertFalse("Android took the camera back: no still", r.pane.stillPolicy().stillsMayLeave)
    }

    @Test fun aSettingOfAnotherPaneChangesNothing() {
        val r = Rig()
        r.pane.start()
        r.changed("speaker_volume")
        assertEquals(1, r.camera.opens)
    }

    @Test fun theOnvifMotionGoesOffWhenPresenceClears() {
        val r = Rig()
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS
        r.prefs.onvif = true
        r.prefs.signIn = "viewer" to "s3cret"
        r.pane.start()
        r.someoneWalksIn()
        val presence = r.pane.entities().filterIsInstance<BinarySensorEntity>().single()
        assertEquals(true, ProtoMessage(presence.state().toByteArray()).bool(2))
        // Nobody moves for longer than presence waits (8 s): still frames, as an empty room gives.
        repeat(50) {
            r.now += 200
            r.camera.sink!!.frame(LumaFrame(96, 54, 96, ByteArray(96 * 54) { 60 }))
        }
        assertEquals("camera_presence clears", false, ProtoMessage(presence.state().toByteArray()).bool(2))
        assertEquals("the ONVIF motion follows it, on then off", listOf(true, false), r.stream.motion)
    }

    /** A SecureRandom whose numbers the test chooses, counting what it was asked. */
    private class CountingRandom(private val pick: (Int) -> Int = { it }) : java.security.SecureRandom() {
        var asked = 0
        override fun nextInt(bound: Int): Int = pick(asked++) % bound
    }

    @Test fun turningTheStreamOnMakesThisPanelsSignInOnceFromSecureRandom() {
        val random = CountingRandom()
        val r = Rig(random = random)
        r.pane.start()
        assertNull("nothing made while the stream is off", r.prefs.signIn)
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.changed(CameraPrefs.KEY_USE)
        r.prefs.onvif = true; r.changed(CameraPrefs.KEY_ONVIF)
        val made = r.prefs.signIn!!
        assertEquals(StreamSignIn.USERNAME, made.first)
        assertTrue("at least 16 characters", made.second.length >= 16)
        assertTrue("from the unambiguous alphabet", made.second.all { it in StreamSignIn.ALPHABET })
        assertEquals("every character from the injected SecureRandom", made.second.length, random.asked)
        assertEquals("the stream starts with it", made, r.stream.running)
        // Stable: more checks, a restart of the pane, the stream off and on, never make another.
        r.pane.reconcile()
        r.pane.stop(); r.pane.start()
        r.prefs.onvif = false; r.changed(CameraPrefs.KEY_ONVIF)
        r.prefs.onvif = true; r.changed(CameraPrefs.KEY_ONVIF)
        assertEquals(made, r.prefs.signIn)
        assertEquals(made.second.length, random.asked)
    }

    @Test fun clearingItAndTurningTheStreamOnMakesANewOne() {
        val r = Rig(random = java.security.SecureRandom())
        r.pane.start()
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.prefs.onvif = true; r.changed(CameraPrefs.KEY_ONVIF)
        val first = r.prefs.signIn!!
        r.prefs.onvif = false; r.changed(CameraPrefs.KEY_ONVIF)
        r.prefs.signIn = null; r.changed(CameraPrefs.KEY_STREAM_PASSWORD)
        assertNull("cleared, and the stream is off: none", r.prefs.signIn)
        assertNull(r.stream.running)
        r.prefs.onvif = true; r.changed(CameraPrefs.KEY_ONVIF)
        assertNotEquals(first.second, r.prefs.signIn!!.second)
    }

    @Test fun thePasswordsAlphabetLeavesOutWhatReadsWrong() {
        assertTrue("no 0 O o 1 I i l", "0Oo1Iil".none { it in StreamSignIn.ALPHABET })
        assertEquals("each character once", StreamSignIn.ALPHABET.length, StreamSignIn.ALPHABET.toSet().size)
        assertTrue("enough symbols for over 100 bits in 20 characters", StreamSignIn.ALPHABET.length >= 50)
    }

    @Test fun twoPanelsGetDifferentPasswords() {
        val a = CameraPrefs(app.getSharedPreferences("camera-a", Context.MODE_PRIVATE)).also { it.signIn = null }
        val b = CameraPrefs(app.getSharedPreferences("camera-b", Context.MODE_PRIVATE)).also { it.signIn = null }
        assertTrue(a.ensureSignIn()); assertTrue(b.ensureSignIn())
        assertNotEquals(a.signIn!!.second, b.signIn!!.second)
        assertEquals("never made again by itself", false, a.ensureSignIn())
    }

    @Test fun inPresenceOnlyNoSignInIsMadeAndNoStreamRuns() {
        val r = Rig()
        r.pane.start()
        r.prefs.onvif = true; r.changed(CameraPrefs.KEY_ONVIF)
        assertNull(r.prefs.signIn)
        assertNull(r.stream.running)
    }

    @Test fun theSignInIsNeverLogged() {
        org.robolectric.shadows.ShadowLog.clear()
        val r = Rig(random = java.security.SecureRandom())
        r.pane.start()
        r.prefs.use = CameraUse.PRESENCE_AND_SNAPSHOTS; r.prefs.onvif = true; r.changed(CameraPrefs.KEY_ONVIF)
        val (user, password) = r.prefs.signIn!!
        val logs = org.robolectric.shadows.ShadowLog.getLogs().map { it.msg.orEmpty() }
        assertTrue("it says one was made", logs.any { "sign-in made" in it })
        assertTrue(logs.toString(), logs.none { password in it || user in it })
        assertTrue("no entity carries it", r.pane.entities().none { e -> e.name.contains(password) || e.objectId.contains(password) })
    }
}
