package sh.edm.s1kiosk.camera

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskApp
import sh.edm.s1kiosk.gecko.GeckoPinHarness
import sh.edm.s1kiosk.gecko.GeckoShadows
import sh.edm.s1kiosk.glassworks

/** The camera's privacy indicator in the real MainActivity (#117): it follows the runtime's "images leaving". */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = KioskApp::class,
    instrumentedPackages = ["org.mozilla.geckoview"],
    shadows = [
        GeckoShadows.ShadowRuntime::class, GeckoShadows.ShadowExtensionController::class,
        GeckoShadows.ShadowSessionController::class, GeckoShadows.ShadowSession::class, GeckoShadows.ShadowView::class,
    ],
)
class CameraPillTest {

    @Test fun thePillFollowsImagesLeavingThePanel() = GeckoPinHarness(prefs = { it.urlSetting = "http://192.0.2.10:8123/lovelace/0" }).use { h ->
        val pill = h.rootChildren().filterIsInstance<CameraPill>().single()
        assertEquals("hidden at start", View.GONE, pill.visibility)
        val runtime = h.activity.glassworks
        runtime.cameraLeaving = true
        runtime.onCameraLeaving!!.invoke()
        assertEquals(View.VISIBLE, pill.visibility)
        runtime.cameraLeaving = false
        runtime.onCameraLeaving!!.invoke()
        assertEquals(View.GONE, pill.visibility)
    }

    @Test fun showFalseKeepsItHidden() = GeckoPinHarness().use { h ->
        val pill = CameraPill(h.activity)
        pill.show(false)
        assertEquals(View.GONE, pill.visibility)
        pill.show(true)
        pill.show(false)
        assertEquals(View.GONE, pill.visibility)
    }
}
