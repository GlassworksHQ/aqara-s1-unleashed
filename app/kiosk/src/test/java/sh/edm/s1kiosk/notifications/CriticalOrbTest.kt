package sh.edm.s1kiosk.notifications

import android.app.Application
import android.os.Looper
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.ui.Mockup
import sh.edm.s1kiosk.voice.OrbBakes
import java.time.Duration

/** The critical alert's orb (#118): one tick chain, only while it's seen, and its layers ready early (verifier on #228). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class CriticalOrbTest {
    private val root = Mockup.show(FrameLayout(Mockup.activity()))
    private val orb = CriticalOrb(root.context).also { root.addView(it, FrameLayout.LayoutParams(450, 450)) }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** A stop and a start inside one 16 ms frame leave one chain of ticks, not two. */
    @Test fun oneChainAfterAStopAndAStartInOneFrame() {
        orb.start()
        idle(320)
        val oneChain = orb.frames                 // the rate of one chain, as this looper runs it
        orb.stop()
        orb.start()                               // same frame: the old tick is still pending
        idle(320)
        assertTrue("one chain ($oneChain), not two (${orb.frames})", orb.frames <= oneChain + 2)
    }

    @Test fun aDetachStopsTheFrames() {
        orb.start()
        idle(100)
        assertTrue(orb.running)
        root.removeView(orb)
        assertFalse(orb.running)
        val frames = orb.frames
        idle(1_000)
        assertEquals("no frames after the detach", frames, orb.frames)
    }

    /** An alert nobody answers with the screen dark doesn't tick 60 times a second all night. */
    @Test fun noTicksWhileTheScreenIsOff() {
        orb.start()
        orb.screenOn = false
        assertFalse(orb.running)
        val frames = orb.frames
        idle(2_000)
        assertEquals("no frames while off", frames, orb.frames)
        orb.screenOn = true
        idle(200)
        assertTrue("back on: the frames resume", orb.frames > frames + 5)
    }

    /** Bakes once attached, as the timer-done stage does, so the first alert doesn't show an empty orb. */
    @Test fun itsLayersBakeOnAttach() {
        assertFalse(orb.layersIn)
        idle(CriticalOrb.BAKE_AFTER_MS)
        repeat(100) { if (!orb.layersIn) { Thread.sleep(100); idle(16) } }
        assertTrue("baked without an alert", orb.layersIn)
    }
}
