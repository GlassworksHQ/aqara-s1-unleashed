package sh.edm.s1kiosk.frame

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The presence range reaches the sensor through a secure setting; a write that didn't take is said (CodeRabbit on #163). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Vi5300PresenceTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun aWriteThatTookIsNoProblem() = assertEquals(null, Vi5300Presence { _, _, _ -> true }.setRange(app, 1.5f))

    @Test
    fun aWriteTheProviderRefusedIsSaid() = assertEquals(Vi5300Presence.NOT_WRITTEN, Vi5300Presence { _, _, _ -> false }.setRange(app, 1.5f))

    @Test
    fun noPermissionIsSaid() = assertEquals(Vi5300Presence.NO_RANGE, Vi5300Presence { _, _, _ -> throw SecurityException("no") }.setRange(app, 1.5f))

    @Test
    fun zeroLeavesTheSensorAlone() {
        var writes = 0
        assertEquals(null, Vi5300Presence { _, _, _ -> writes++; true }.setRange(app, 0f))
        assertEquals(0, writes)
    }

    @Test fun onlyTheS1sOwnSensorCounts() {
        assertTrue(Vi5300Presence.isVi5300("vi5300 Proximity Sensor"))
        assertFalse("a phone's proximity sensor reports a distance, not 1.0", Vi5300Presence.isVi5300("TMD2725 Proximity"))
        assertFalse(Vi5300Presence.isVi5300(null))
    }
}
