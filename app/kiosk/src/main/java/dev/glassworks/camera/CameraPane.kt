/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.util.Log
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.BinarySensorEntity
import sh.edm.esphome.EntityCategory
import sh.edm.esphome.SelectEntity
import sh.edm.esphome.SwitchEntity
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The camera pane (#117): presence from the camera, the camera in Home Assistant, and the opt-in ONVIF
 * stream. Runs only on a device with a camera ([CameraDefinition]).
 *
 * What runs follows [CameraPlan], made again from the settings whenever one changes and every
 * [CHECK_S] seconds (Android's permission and the camera can change without telling the pane). Each
 * pass converges on the plan, so a pass that changes nothing does nothing.
 *
 * [hardware] is the camera ([CameraHub] on a device), [stream] the ports ([CameraStream]). [permitted]
 * says whether Android allows the camera, [ownSensor] whether the frame's presence sensor is on this
 * device, and [toScreen] gives the screen policy a reading (main thread).
 */
class CameraPane(
    private val ctx: PaneContext,
    private val prefs: CameraPrefs,
    private val hardware: FrameSource,
    private val stills: StillCamera,
    private val stream: StreamOutput,
    val egress: FrameEgress,
    private val permitted: () -> Boolean,
    private val ownSensor: () -> Boolean,
    private val toScreen: (near: Boolean, raw: Float) -> Unit,
    private val exec: ScheduledExecutorService,
    /** Called when nothing may use the camera any more (privacy, no permission, the pane stopping): [CameraHub.idle]. */
    private val release: () -> Unit = {},
    private val presenceFactory: (FrameSource, onMotion: (Boolean) -> Unit, onProblem: (String) -> Unit) -> CameraPresence =
        { source, onMotion, onProblem -> CameraPresence(source, { prefs.sensitivity }, onProblem = onProblem, onMotion = onMotion) },
) : Pane {
    override val id = ID

    @Volatile var plan = CameraPlan.NOTHING
        private set
    /** Why the camera isn't doing what its settings ask, in words for the person at the panel; null when it is. */
    @Volatile var problem: String? = null
        private set
    @Volatile private var near: Boolean? = null
    @Volatile private var streaming = false

    private var presence: CameraPresence? = null
    private var check: ScheduledFuture<*>? = null
    /** Between [start] and [stop]: a periodic check that was already waiting when [stop] ran does nothing. */
    @Volatile private var running = false

    /**
     * The policy a still is judged by now: the settings, but as privacy while the pane isn't running or
     * Android doesn't allow the camera. The camera entity and the ONVIF snapshot both use it; they are
     * asked on their own threads, also while the pane is off.
     */
    fun stillPolicy(): CameraPolicy = if (running && permitted()) policy else policy.copy(privacy = true)

    private val policy get() = prefs.policy

    override fun entities(): List<ApiEntity> = listOf(
        snapshotCamera(stills, ::stillPolicy, egress),
        BinarySensorEntity("camera_presence", "Camera presence", "mdi:motion-sensor", deviceClass = "occupancy") { near },
        SwitchEntity("camera_privacy", "Camera privacy", "mdi:camera-off", get = { prefs.privacy }, set = { prefs.privacy = it }),
        SelectEntity(
            "camera_use", "Camera use", "mdi:cctv", disabledByDefault = true,
            options = listOf(CameraPrefs.USE_PRESENCE, CameraPrefs.USE_SNAPSHOTS),
            get = { if (prefs.use == CameraUse.PRESENCE) CameraPrefs.USE_PRESENCE else CameraPrefs.USE_SNAPSHOTS },
            set = { prefs.use = if (it == CameraPrefs.USE_SNAPSHOTS) CameraUse.PRESENCE_AND_SNAPSHOTS else CameraUse.PRESENCE },
        ),
        SwitchEntity(
            "onvif_stream", "ONVIF stream", "mdi:video-wireless", EntityCategory.CONFIG, disabledByDefault = true,
            // Refused in Presence only (the approved design): the switch springs back.
            get = { prefs.onvif && policy.stillsMayLeave },
            set = { on -> prefs.onvif = on && policy.stillsMayLeave; ctx.publishStates() },
        ).apply { authOnly = true },
        sensitivityEntity({ prefs.sensitivity }, { prefs.sensitivity = it }),
    )

    @Synchronized override fun start() {
        running = true
        reconcile()
        check = exec.scheduleWithFixedDelay({ reconcile() }, CHECK_S, CHECK_S, TimeUnit.SECONDS)
    }

    @Synchronized override fun stop() {
        running = false
        check?.cancel(false); check = null
        apply(CameraPlan.NOTHING, null)
        release()
        near = null
        problem = null
    }

    override fun onEvent(event: PanelEvent) {
        if (event is PanelEvent.SettingChanged && event.key in CameraPrefs.KEYS) reconcile()
    }

    /** Converges on the plan for the settings and the device as they are now. */
    @Synchronized fun reconcile() {
        if (!running) return
        val allowed = permitted()
        val signIn = prefs.signIn
        val next = CameraPlan.of(policy, allowed, ownSensor(), signIn != null)
        if (!allowed && !policy.privacy) problem = NOT_ALLOWED
        else if (problem == NOT_ALLOWED) problem = null
        apply(next, signIn)
        if (!allowed || policy.privacy) release() // a still's linger mustn't keep the camera open
    }

    @Synchronized private fun apply(next: CameraPlan, signIn: Pair<String, String>?) {
        if (next.detect && presence == null) {
            presence = presenceFactory(hardware, ::onMotion) { why -> problem = why; log("presence: $why") }.also { p ->
                p.start(ctx.context) { isNear, raw -> onReading(isNear, raw) }
            }
        } else if (!next.detect && presence != null) {
            presence?.stop(ctx.context); presence = null
            near = null
            if (plan.feedScreen) toScreen(false, -1f)
        }
        if (next.stream && signIn != null) {
            streaming = stream.start(signIn)
            if (!streaming) problem = STREAM_FAILED else if (problem == STREAM_FAILED) problem = null
        } else if (streaming) {
            stream.stop(); streaming = false
            if (problem == STREAM_FAILED) problem = null
        }
        if (next != plan) log("plan: detect=${next.detect} screen=${next.feedScreen} stream=${next.stream}")
        plan = next
        ctx.publishStates()
    }

    private fun onReading(isNear: Boolean, raw: Float) {
        if (raw >= 0f && problem != null && problem != NOT_ALLOWED && problem != STREAM_FAILED) problem = null
        val changed = near != isNear
        near = isNear
        if (plan.feedScreen) toScreen(isNear, raw)
        if (changed) ctx.publishStates()
    }

    private fun onMotion(active: Boolean) {
        if (streaming) stream.setMotion(active)
    }

    private fun log(s: String) = Log.i(TAG, s)

    companion object {
        const val ID = "camera"
        private const val TAG = "S1Kiosk.Camera"
        const val CHECK_S = 10L
        const val NOT_ALLOWED = "Android hasn't allowed the camera"
        const val STREAM_FAILED = "The stream couldn't open its ports"
    }
}
