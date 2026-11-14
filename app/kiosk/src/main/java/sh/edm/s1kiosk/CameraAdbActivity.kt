package sh.edm.s1kiosk

import android.app.Activity
import android.os.Bundle
import android.util.Log
import dev.glassworks.camera.CameraPrefs
import dev.glassworks.camera.CameraUse

/**
 * The camera pane's settings from adb (#117), until the Manage page edits settings (#178 phase 2):
 *
 *   adb shell am start -n sh.edm.s1kiosk/.CameraAdbActivity --es camera_use snapshots --ez onvif_stream true \
 *       --ez camera_privacy false --ei camera_sensitivity 6 --es camera_stream_sign_in 'user:password'
 *
 * `camera_use` is `presence` or `snapshots`; an empty sign-in clears it. Protected by
 * WRITE_SECURE_SETTINGS, which `adb shell` holds and normal apps don't: MainActivity takes extras from
 * any app, and on a tablet another app could otherwise turn privacy off, or set the stream's password
 * and open the camera to the network. The password is never logged.
 */
class CameraAdbActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        if (i != null) {
            val c = KioskPrefs(this).camera
            i.getStringExtra(CameraPrefs.KEY_USE)?.let { c.use = if (it == "snapshots" || it == CameraPrefs.USE_SNAPSHOTS) CameraUse.PRESENCE_AND_SNAPSHOTS else CameraUse.PRESENCE }
            if (i.hasExtra(CameraPrefs.KEY_PRIVACY)) c.privacy = i.getBooleanExtra(CameraPrefs.KEY_PRIVACY, true)
            if (i.hasExtra(CameraPrefs.KEY_ONVIF)) c.onvif = i.getBooleanExtra(CameraPrefs.KEY_ONVIF, false)
            if (i.hasExtra(CameraPrefs.KEY_SENSITIVITY)) c.sensitivity = i.getIntExtra(CameraPrefs.KEY_SENSITIVITY, c.sensitivity)
            i.getStringExtra(SIGN_IN)?.let { v ->
                val at = v.indexOf(':')
                val ok = at > 0 && at < v.length - 1
                c.signIn = if (ok) v.substring(0, at) to v.substring(at + 1) else null
                Log.i("S1Kiosk.Camera", if (ok) "stream sign-in set" else "stream sign-in cleared")
            }
        }
        finish()
    }

    companion object {
        const val SIGN_IN = "camera_stream_sign_in"
    }
}
