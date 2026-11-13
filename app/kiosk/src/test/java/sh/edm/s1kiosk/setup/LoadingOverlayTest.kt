package sh.edm.s1kiosk.setup

import android.app.Application
import android.os.SystemClock
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.ui.Mockup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class LoadingOverlayTest {

    @Test
    fun theLastStateAskedForWinsAQuickBackAndForth() {
        val a = Mockup.activity()
        val o = LoadingOverlay(a)
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        val loading = LoadingOverlay.State.Connecting(LoadingOverlay.CONNECTING, "homeassistant.local")
        o.show(loading)
        Mockup.idle()
        o.show(LoadingOverlay.State.Unreachable("homeassistant.local", SystemClock.elapsedRealtime() + 10_000))
        o.show(loading) // a new load starts before the cross-fade ends
        Mockup.idle()
        assertEquals(loading, o.state)
    }

    @Test
    fun unreachableCountsDown() {
        val s = LoadingOverlay.State.Unreachable("homeassistant.local", retryAt = 10_000)
        assertEquals("Trying again in 4 s · homeassistant.local", LoadingOverlay.unreachableLine(s, 6_000))
        assertEquals("Trying again… · homeassistant.local", LoadingOverlay.unreachableLine(s, 10_500))
        assertEquals("Trying again in 1 s", LoadingOverlay.unreachableLine(s.copy(host = ""), 9_200))
    }
}
