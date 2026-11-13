package sh.edm.s1kiosk.setup

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.ui.Mockup
import java.time.Duration

/** The connecting screen (#63 round 2): one continuous screen from the boot, and no flicker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class LoadingOverlayTest {

    private val loading = LoadingOverlay.State.Connecting(LoadingOverlay.CONNECTING, "homeassistant.local")
    private val wifi = LoadingOverlay.State.Connecting("Connecting to Wi-Fi…", "Home Assistant loads as soon as it's connected", host = false)

    private fun overlay(): LoadingOverlay {
        val a = Mockup.activity()
        val o = LoadingOverlay(a, AqaraS1Plus.BOOT_REST)
        // Robolectric here runs without the app's resources: any bitmap stands in for the boot's frame.
        o.loadRest = { BitmapDrawable(a.resources, Bitmap.createBitmap(335, 485, Bitmap.Config.ARGB_8888)) }
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        return o
    }

    private fun after(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Every state the screen showed, recorded each [stepMs] over [ms]. */
    private fun watch(o: LoadingOverlay, ms: Long, stepMs: Long = 50): List<LoadingOverlay.State?> =
        (0 until ms / stepMs).map { after(stepMs); o.state }.fold(emptyList()) { acc, s -> if (acc.lastOrNull() == s) acc else acc + s }

    @Test
    fun theFirstFrameIsTheBootsThenTheState() {
        val o = overlay()
        o.show(loading)
        o.previewBoot(0)
        assertTrue(o.showsBootFrame) // the boot's last frame, its device line in it
        o.previewBoot(LoadingOverlay.BOOT_HOLD_MS)
        assertTrue(o.showsBootFrame) // held
        o.previewBoot(LoadingOverlay.BOOT_END_MS)
        assertFalse(o.showsBootFrame)
        assertEquals("Connecting to Home Assistant · homeassistant.local", o.lineText)
    }

    @Test
    fun onlyTheFirstShowContinuesFromTheBoot() {
        // Home Assistant drops later: the connecting screen comes back, the boot's frame doesn't.
        val o = overlay()
        o.show(loading)
        after(3_000)
        o.hide()
        after(1_000)
        o.show(loading)
        assertFalse(o.showsBootFrame)
        assertEquals("Connecting to Home Assistant · homeassistant.local", o.lineText)
    }

    @Test
    fun aFrameWithoutABootFrameStartsLive() {
        val o = overlay().apply { loadRest = { null } }
        o.show(loading)
        o.previewBoot(0)
        assertFalse(o.showsBootFrame)
        assertEquals("ON AQARA S1 PLUS", o.lineText) // the device line still, as the boot drew it
    }

    @Test
    fun newWordsDuringTheBootsLineWaitForIt() {
        // Home Assistant is already unreachable at power-on: the countdown's next words come while the
        // boot's device line still shows. That line stays; the newest words come after it.
        val o = overlay()
        val first = LoadingOverlay.State.Unreachable("homeassistant.local", SystemClock.elapsedRealtime() + 10_000)
        o.show(first)
        o.previewBoot(0)
        val next = first.copy(retryAt = first.retryAt + 10_000)
        o.show(next)
        assertEquals("ON AQARA S1 PLUS", o.lineText)
        o.previewBoot(LoadingOverlay.BOOT_END_MS)
        assertEquals(LoadingOverlay.lineOf(next), o.lineText)
    }

    @Test
    fun theLastStateAskedForWinsAQuickBackAndForth() {
        val o = overlay()
        o.show(loading)
        after(2_000)
        o.show(LoadingOverlay.State.Unreachable("homeassistant.local", SystemClock.elapsedRealtime() + 10_000))
        o.show(loading) // a new load starts before the cross-fade ends
        after(2_000)
        assertEquals(loading, o.state)
    }

    @Test
    fun aFlickeringStateDuringBootShowsOnce() {
        // "Connecting to Wi-Fi…" came and went several times before HA (the maintainer's power-on).
        val o = overlay()
        o.show(LoadingOverlay.State.Connecting("Starting up", "", host = false))
        val seen = ArrayList<LoadingOverlay.State?>(listOf(o.state))
        repeat(4) {
            o.show(wifi); seen += watch(o, 200)
            o.show(LoadingOverlay.State.Connecting("Starting up", "", host = false)); seen += watch(o, 200)
        }
        o.show(wifi); seen += watch(o, 3_000)
        o.show(loading); seen += watch(o, 3_000)
        val shown = seen.fold(emptyList<LoadingOverlay.State?>()) { acc, s -> if (acc.lastOrNull() == s) acc else acc + s }
        assertEquals(listOf(LoadingOverlay.State.Connecting("Starting up", "", host = false), wifi, loading), shown)
    }

    @Test
    fun theCountdownUpdatesWithoutATransition() {
        val o = overlay()
        val first = LoadingOverlay.State.Unreachable("homeassistant.local", SystemClock.elapsedRealtime() + 10_000)
        o.show(first)
        after(2_000)
        val next = first.copy(retryAt = first.retryAt + 10_000)
        o.show(next)
        assertEquals(next, o.state) // at once: the same state, new words
    }

    @Test
    fun unreachableCountsDown() {
        val s = LoadingOverlay.State.Unreachable("homeassistant.local", retryAt = 10_000)
        assertEquals("Trying again in 4 s · homeassistant.local", LoadingOverlay.unreachableLine(s, 6_000))
        assertEquals("Trying again… · homeassistant.local", LoadingOverlay.unreachableLine(s, 10_500))
        assertEquals("Trying again in 1 s", LoadingOverlay.unreachableLine(s.copy(host = ""), 9_200))
    }
}
