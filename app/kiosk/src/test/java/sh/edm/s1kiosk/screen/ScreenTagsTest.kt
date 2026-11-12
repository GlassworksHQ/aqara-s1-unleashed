package sh.edm.s1kiosk.screen

import android.app.Application
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowPowerManager
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.frame.AqaraS1Plus

/** Log filters and `dumpsys power` readers know the screen's logcat and wake-lock tags: both stay as they were. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenTagsTest {
    @Test fun theS1KeepsItsScreenTags() {
        val context = RuntimeEnvironment.getApplication()
        ShadowLog.clear()
        ShadowPowerManager.reset()
        val screen = GlassworksRuntime(AqaraS1Plus()).screen
        screen.start(context)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        screen.wake("test")
        assertEquals("s1kiosk:wake", shadowOf(ShadowPowerManager.getLatestWakeLock()).tag)
        assertTrue(ShadowLog.getLogsForTag("S1Kiosk.Screen").any { it.msg == "wake (test)" })
        screen.stop()
    }
}
