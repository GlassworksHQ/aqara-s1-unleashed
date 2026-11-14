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
        // Over the night clock, as on the panel: nothing behind may show through.
        val behind = android.widget.FrameLayout(a).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(android.widget.TextView(a).apply {
                text = "1:22"; textSize = 160f; setTextColor(android.graphics.Color.parseColor("#F4F4F5"))
                gravity = android.view.Gravity.CENTER
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(CodeOverlay.build(a, PanelCode.Shown("122202", "signin", "Sign in to manage this panel", 0), {}),
                android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        Mockup.save("manage-code-over-clock", Mockup.show(behind))
    }

    /** The overlay hides what's behind it completely (the night clock showed through, panel run 2026-11-13). */
    @Test
    fun theOverlayIsOpaque() {
        val v = CodeOverlay.build(Mockup.activity(), PanelCode.Shown("482913", "signin", "Sign in", 0), {})
        val bg = v.background as android.graphics.drawable.ColorDrawable
        org.junit.Assert.assertEquals(0xFF0A0B0F.toInt(), bg.color)
    }
}
