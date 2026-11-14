package sh.edm.s1kiosk

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Sets the camera stream's sign-in from adb, until the Manage page edits settings (#178 phase 2):
 *
 *   adb shell am start -n sh.edm.s1kiosk/.CameraSignInActivity --es camera_stream_sign_in 'user:password'
 *
 * An empty value clears it. Protected by WRITE_SECURE_SETTINGS, which `adb shell` holds and normal apps
 * don't: with MainActivity's extras, any app on a tablet could set the password and open the camera to
 * the network. The value is never logged.
 */
class CameraSignInActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA)?.let { v ->
            val i = v.indexOf(':')
            KioskPrefs(this).camera.signIn = if (i > 0 && i < v.length - 1) v.substring(0, i) to v.substring(i + 1) else null
            Log.i("S1Kiosk.Camera", if (i > 0 && i < v.length - 1) "stream sign-in set" else "stream sign-in cleared")
        }
        finish()
    }

    companion object {
        const val EXTRA = "camera_stream_sign_in"
    }
}
