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
    private val hub: EncoderInput,
    private val egress: FrameEgress,
    private val identity: () -> CameraIdentity,
    private val host: () -> String?,
    private val snapshot: () -> ByteArray?,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val encoders: EncoderFactory = EncoderFactory { au, failed ->
        H264Encoder(CameraHub.WIDTH, CameraHub.HEIGHT, onAccessUnit = au, onFailure = failed)
    },
) : StreamOutput {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val lock = Any()
    private var rtsp: RtspServer? = null
    private var onvif: OnvifServer? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var signIn: Pair<String, String>? = null
    /** The encoder for the current RTSP server; a new one per start, so a stopped server's reports reach a closed one. */
    private var viewers: ViewerEncoder? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun start(signIn: Pair<String, String>): Boolean = synchronized(lock) {
        if (rtsp != null && this.signIn == signIn) return true
        stopLocked()
        this.signIn = signIn
        val name = identity().name
        var ve: ViewerEncoder? = null
        val r = RtspServer(
            RTSP_PORT, { this.signIn }, { n -> ve?.viewers(n); if (n > 0) log("streaming to $n") },
            onLog = { log("RTSP: $it") }, name = name, fps = H264Encoder.FPS,
        )
        ve = ViewerEncoder(
            hub, encoders, egress, onAccessUnit = { au, key -> r.onAccessUnit(au, key) }, watched = { r.hasClients() },
            clock = android.os.SystemClock::elapsedRealtime, after = { ms, go -> main.postDelayed(go, ms) }, log = log,
        )
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
            viewers = ve
            r.start()
            o.start()
            rtsp = r; onvif = o
            log("stream on: RTSP :$RTSP_PORT, ONVIF :$ONVIF_PORT")
            true
        } catch (e: Exception) {
            log("stream failed to start: ${e.javaClass.simpleName}: ${e.message}")
            try { r.stop() } catch (_: Exception) {}
            try { o.stop() } catch (_: Exception) {}
            ve.close(); viewers = null
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
        viewers?.close(); viewers = null
        rtsp?.let { r -> try { r.stop() } catch (_: Exception) {} ; log("stream off") }
        try { onvif?.stop() } catch (_: Exception) {}
        rtsp = null; onvif = null; signIn = null
        releaseMulticast()
    }

    private fun releaseMulticast() {
        try { multicast?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicast = null
    }

    companion object {
        private const val TAG = "S1Kiosk.Camera"
        const val RTSP_PORT = 8554
        const val ONVIF_PORT = 8000
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
