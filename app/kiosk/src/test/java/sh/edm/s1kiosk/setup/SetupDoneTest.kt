package sh.edm.s1kiosk.setup

import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.ui.Mockup
import java.time.Duration

/** Done: the real end of setup fades out; a preview (`--es setup_step done`) holds it until it is closed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class SetupDoneTest {

    private fun overlay(preview: Boolean): SetupOverlay {
        val a = Mockup.activity()
        val s = SetupOverlay(a)
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.finish(SetupOverlay.Model(
            step = SetupFlow.SIGNIN, done = emptySet(), wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
            haUrl = null, haSource = "", panelIp = null, signedIn = false, preview = preview,
        )) {}
        return s
    }

    /** The "Preview · Close" buttons on screen (the rail has one too, hidden while Done shows). */
    private fun closeShown(root: android.view.View): List<android.widget.TextView> = buildList {
        fun walk(v: android.view.View) {
            if (v is android.widget.TextView && v.text.toString() == "Preview · Close" && v.isShown) add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    private fun after(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test
    fun theRealDoneFadesOut() {
        val s = overlay(preview = false)
        after(1)
        assertTrue(Mockup.label(s, "You're all set.").isShown)
        assertEquals(0, closeShown(s).size)
        after(3)
        assertFalse(s.isShowing)
    }

    @Test
    fun aPreviewHoldsDoneUntilItIsClosed() {
        val s = overlay(preview = true)
        var closed = 0
        s.onClosePreview = { closed++ }
        after(10)
        assertTrue(s.isShowing)
        assertTrue(Mockup.label(s, "You're all set.").isShown)
        val chip = closeShown(s).single() // the way out is on the screen
        chip.performClick()
        assertEquals(1, closed)
    }
}
