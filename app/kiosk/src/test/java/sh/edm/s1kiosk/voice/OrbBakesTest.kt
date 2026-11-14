package sh.edm.s1kiosk.voice

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The orb's sphere and shine are baked once and shared by the timer-done stage and the critical alert (#118). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OrbBakesTest {
    @Test fun bakedOnceForEveryOrb() {
        var bakes = 0
        var finish: (() -> Unit)? = null
        val layers = OrbBakes.Layers(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        val orbs = OrbBakes { _, done -> bakes++; finish = { done(layers) } }
        val got = mutableListOf<OrbBakes.Layers>()
        val art = OrbArt(1f)
        orbs.layers(art) { got += it }       // the timer's
        orbs.layers(art) { got += it }       // the critical alert's, while the first bake runs
        finish!!()
        orbs.layers(art) { got += it }       // later: no bake at all
        assertEquals(1, bakes)
        assertEquals(3, got.size)
        got.forEach { assertSame(layers, it) }
    }
}
