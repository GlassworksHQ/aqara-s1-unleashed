package sh.edm.s1kiosk.manage

import android.app.Application
import dev.glassworks.manage.PanelCode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.ui.Mockup

/**
 * The Manage page's code on the panel's screen (#33), at 1440x720. Illustrative code only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*CodeOverlayMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodeOverlayMockups {
    @Before fun interFromSources() = Mockup.interFromSources()

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val a = Mockup.activity()
        Mockup.save("manage-code-sign-in", Mockup.show(CodeOverlay.build(a, PanelCode.Shown("482913", "signin", "Sign in to manage this panel", 0), {})))
        Mockup.save("manage-code-reboot", Mockup.show(CodeOverlay.build(a, PanelCode.Shown("482913", "reboot", "Reboot the panel", 0), {})))
    }
}
