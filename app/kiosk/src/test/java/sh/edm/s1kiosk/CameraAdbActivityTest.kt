package sh.edm.s1kiosk

import android.app.Application
import android.content.Intent
import dev.glassworks.camera.CameraUse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraAdbActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val camera get() = KioskPrefs(app).camera

    private fun send(i: Intent) { Robolectric.buildActivity(CameraAdbActivity::class.java, i).setup() }

    @Test fun setsTheCameraSettings() {
        send(Intent().putExtra("camera_use", "snapshots").putExtra("onvif_stream", true).putExtra("camera_privacy", true)
            .putExtra("camera_sensitivity", 8).putExtra(CameraAdbActivity.SIGN_IN, "viewer:pa:ss"))
        assertEquals(CameraUse.PRESENCE_AND_SNAPSHOTS, camera.use)
        assertTrue(camera.onvif)
        assertTrue(camera.privacy)
        assertEquals(8, camera.sensitivity)
        assertEquals("the password may hold a colon", "viewer" to "pa:ss", camera.signIn)
        send(Intent().putExtra("camera_use", "presence").putExtra("camera_privacy", false).putExtra(CameraAdbActivity.SIGN_IN, ""))
        assertEquals(CameraUse.PRESENCE, camera.use)
        assertFalse(camera.privacy)
        assertNull(camera.signIn)
        send(Intent().putExtra(CameraAdbActivity.SIGN_IN, "viewer:"))
        assertNull("a username without a password is no sign-in", camera.signIn)
    }

    @Test fun onlyAdbMayStartIt() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val decl = Regex("""<activity[^>]*android:name="\.CameraAdbActivity"[^>]*/>""").find(manifest)!!.value
        assertTrue(decl, decl.contains("""android:permission="android.permission.WRITE_SECURE_SETTINGS""""))
    }
}
