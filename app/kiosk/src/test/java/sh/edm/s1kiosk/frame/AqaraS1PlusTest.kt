package sh.edm.s1kiosk.frame

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import dev.glassworks.frame.systemSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AqaraS1PlusTest {
    private val app = RuntimeEnvironment.getApplication()
    private val frame = AqaraS1Plus()

    private fun install(pkg: String, action: String) {
        val c = ComponentName(pkg, "$pkg.Settings")
        shadowOf(app.packageManager).apply {
            addActivityIfNotPresent(c)
            addIntentFilterForActivity(c, IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
    }

    @Test fun theMdnsBoardIdIsAqaraS1Plus() {
        assertEquals("aqara-s1-plus", frame.board)
    }

    @Test fun theSettingsChainIsAndroidSettingsThenLumiSettings() {
        assertEquals(
            listOf("android.settings.SETTINGS" to null, "lumi.settings.SETTINGS" to "com.lumi.settings"),
            frame.systemSettingsIntents().map { it.action to it.`package` },
        )
    }

    @Test fun onTheS1OnlyLumiSettingsResolvesSoItOpens() {
        install("com.lumi.settings", "lumi.settings.SETTINGS")
        val i = frame.systemSettings(app)!!
        assertEquals("lumi.settings.SETTINGS", i.action)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun androidSettingsWinsWhereBothResolve() {
        install("com.lumi.settings", "lumi.settings.SETTINGS")
        install("com.android.settings", "android.settings.SETTINGS")
        assertEquals("android.settings.SETTINGS", frame.systemSettings(app)!!.action)
    }

    @Test fun noSettingsScreenMeansNull() {
        assertNull(frame.systemSettings(app))
    }
}
