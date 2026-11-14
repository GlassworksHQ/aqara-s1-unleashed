package sh.edm.s1kiosk

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
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
class CameraSignInActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun send(value: String) {
        Robolectric.buildActivity(CameraSignInActivity::class.java, Intent().putExtra(CameraSignInActivity.EXTRA, value)).setup()
    }

    @Test fun setsAndClearsTheStreamSignIn() {
        send("viewer:pa:ss")
        assertEquals("the password may hold a colon", "viewer" to "pa:ss", KioskPrefs(app).camera.signIn)
        send("")
        assertNull(KioskPrefs(app).camera.signIn)
        send("viewer:")
        assertNull("a username without a password is no sign-in", KioskPrefs(app).camera.signIn)
    }

    @Test fun onlyAdbMayStartIt() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val decl = Regex("""<activity[^>]*android:name="\.CameraSignInActivity"[^>]*/>""").find(manifest)!!.value
        assertTrue(decl, decl.contains("""android:permission="android.permission.WRITE_SECURE_SETTINGS""""))
    }
}
