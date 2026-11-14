/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import dev.glassworks.camera.onvif.OnvifDeviceInfo
import dev.glassworks.camera.onvif.OnvifServer
import dev.glassworks.camera.onvif.OnvifStreamInfo
import dev.glassworks.camera.onvif.RtspServer
import java.security.MessageDigest

/** The opt-in stream, as the camera pane drives it. [CameraStream] on a device; a fake in tests. */
interface StreamOutput {
    /** Opens the ports with [signIn]. A second call with another sign-in reopens them. False when it couldn't. */
    fun start(signIn: Pair<String, String>): Boolean
    fun stop()
    /** Motion from the panel's detector, for the ONVIF events. */
    fun setMotion(active: Boolean)
}

/** Who the camera says it is to ONVIF clients. */
data class CameraIdentity(val name: String, val firmware: String, val panelMac: String)

/**
 * The RTSP server (port [RTSP_PORT]) and the ONVIF server with WS-Discovery (port [ONVIF_PORT]), ported
 * from the maintainer's android-ip-camera. Nothing listens until [start]. The H.264 encoder runs only
 * while someone watches, and each viewer holds the privacy indicator on through [egress]. Snapshots
 * come through [snapshot], the same gate as the Home Assistant camera entity.
 */
class CameraStream(
    context: Context,
    private val hub: CameraHub,
    private val egress: FrameEgress,
    private val identity: () -> CameraIdentity,
    private val host: () -> String?,
    private val snapshot: () -> ByteArray?,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : StreamOutput {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val lock = Any()
    private var rtsp: RtspServer? = null
    private var onvif: OnvifServer? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var signIn: Pair<String, String>? = null
    private var encoder: H264Encoder? = null
    private var viewing: AutoCloseable? = null
    /** Bumped on every start and stop: a viewer report from an older RTSP server is ignored. */
    private var generation = 0
    private var lastRestartMs = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun start(signIn: Pair<String, String>): Boolean = synchronized(lock) {
        if (rtsp != null && this.signIn == signIn) return true
        stopLocked()
        this.signIn = signIn
        val name = identity().name
        val gen = generation
        val r = RtspServer(RTSP_PORT, { this.signIn }, { n -> onViewers(gen, n) }, onLog = { log("RTSP: $it") }, name = name)
        val o = OnvifServer(
            port = ONVIF_PORT,
            host = { host() ?: "0.0.0.0" },
            info = { identity().let { OnvifDeviceInfo(Build.MANUFACTURER, Build.MODEL, it.firmware, onvifMac(it.panelMac).replace(":", ""), Build.DEVICE, onvifMac(it.panelMac), it.name) } },
            stream = { OnvifStreamInfo("rtsp://${host() ?: "0.0.0.0"}:$RTSP_PORT/live", CameraHub.WIDTH, CameraHub.HEIGHT, H264Encoder.FPS, H264Encoder.bitrateFor(CameraHub.WIDTH, CameraHub.HEIGHT) / 1000) },
            credentials = { this.signIn },
            snapshot = snapshot,
            onLog = { log("ONVIF: $it") },
            name = name,
        )
        return try {
            multicast = wifi.createMulticastLock("glassworks-onvif").apply { setReferenceCounted(false); acquire() }
            r.start()
            o.start()
            rtsp = r; onvif = o
            log("stream on: RTSP :$RTSP_PORT, ONVIF :$ONVIF_PORT")
            true
        } catch (e: Exception) {
            log("stream failed to start: ${e.javaClass.simpleName}: ${e.message}")
            try { r.stop() } catch (_: Exception) {}
            try { o.stop() } catch (_: Exception) {}
            releaseMulticast()
            this.signIn = null
            false
        }
    }

    override fun stop() = synchronized(lock) { stopLocked() }

    override fun setMotion(active: Boolean) {
        synchronized(lock) { onvif }?.setMotion(active)
    }

    private fun stopLocked() {
        generation++
        rtsp?.let { r -> try { r.stop() } catch (_: Exception) {} ; log("stream off") }
        try { onvif?.stop() } catch (_: Exception) {}
        rtsp = null; onvif = null; signIn = null
        stopEncoder()
        releaseMulticast()
    }

    /** RTSP's count of viewers, waiting ones included: the encoder and the indicator follow it. */
    private fun onViewers(gen: Int, count: Int) = synchronized(lock) {
        if (gen != generation) return@synchronized // from a server that was stopped or replaced
        if (count > 0 && encoder == null) startEncoder()
        else if (count == 0) stopEncoder()
        else encoder?.requestKeyFrame()
        if (count > 0) log("streaming to $count")
    }

    private fun startEncoder() {
        val server = rtsp ?: return
        var self: H264Encoder? = null
        val e = try {
            H264Encoder(CameraHub.WIDTH, CameraHub.HEIGHT, onAccessUnit = { au, key -> server.onAccessUnit(au, key) }) {
                // The codec failed. Only if it is still the one in use: a replaced encoder's late
                // callback must not stop its replacement.
                synchronized(lock) {
                    if (encoder !== self) return@synchronized
                    stopEncoder()
                    restartWhenAllowed()
                }
            }
        } catch (ex: Exception) {
            log("H.264 encoder failed: ${ex.javaClass.simpleName}")
            return
        }
        self = e
        encoder = e
        hub.setEncoder(e.surface)
        viewing = egress.stream()
    }

    /** A new encoder for the viewers still watching, at most once every [RESTART_MS]; later if sooner. */
    private fun restartWhenAllowed() {
        val gen = generation
        val wait = lastRestartMs + RESTART_MS - android.os.SystemClock.elapsedRealtime()
        val go = Runnable {
            synchronized(lock) {
                if (gen != generation || encoder != null || rtsp?.hasClients() != true) return@synchronized
                lastRestartMs = android.os.SystemClock.elapsedRealtime()
                startEncoder()
            }
        }
        if (wait <= 0) go.run() else main.postDelayed(go, wait)
    }

    private fun stopEncoder() {
        val e = encoder ?: return
        encoder = null
        hub.setEncoder(null)
        e.release()
        viewing?.close(); viewing = null
    }

    private fun releaseMulticast() {
        try { multicast?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicast = null
    }

    companion object {
        private const val TAG = "S1Kiosk.Camera"
        const val RTSP_PORT = 8554
        const val ONVIF_PORT = 8000
        const val RESTART_MS = 5_000L
    }
}

/**
 * The camera's own MAC for ONVIF clients, made from the panel's (already made-up) ESPHome MAC: Home
 * Assistant and UniFi Protect key a camera by it, and the panel's own would merge the camera into the
 * panel's ESPHome device. Stable, locally administered, unicast. Never logged.
 */
fun onvifMac(panelMac: String): String {
    val h = MessageDigest.getInstance("SHA-256").digest("glassworks-onvif:${panelMac.lowercase()}".toByteArray())
    h[0] = ((h[0].toInt() and 0xFC) or 0x02).toByte()
    return (0 until 6).joinToString(":") { "%02x".format(h[it]) }
}
