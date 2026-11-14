package sh.edm.s1kiosk.voice

import android.graphics.Bitmap
import sh.edm.s1kiosk.ui.Bakery
import sh.edm.s1kiosk.ui.Dither
import sh.edm.s1kiosk.ui.FloatImage
import java.nio.ByteBuffer

/**
 * The glass orb's untinted layers, the sphere and its shine ([OrbArt.sphere], [OrbArt.shine]), baked
 * once and shared by every orb that uses them: the timer-done stage and the critical alert (#118).
 * Byte for byte the same in both, and about 1.2 MB at the S1's density, so baking them twice only cost
 * memory and time. The tinted layers (glow, core) stay with each orb.
 *
 * One per process, on the app's runtime; a test makes its own. Main thread: [layers] calls back there.
 */
class OrbBakes(
    /** How the layers are made, off the main thread: [Bakery] in the app. */
    private val bake: (work: () -> Layers, done: (Layers) -> Unit) -> Unit = { work, done -> Bakery.bake("orb sphere and shine", work, done) },
) {
    class Layers(val sphere: Bitmap, val shine: Bitmap)

    private var baked: Layers? = null
    private var baking = false
    private val waiting = ArrayList<(Layers) -> Unit>()

    /** Calls [done] with the layers: at once once baked, else when the one bake finishes. */
    fun layers(art: OrbArt, done: (Layers) -> Unit) {
        baked?.let { done(it); return }
        waiting += done
        if (baking) return
        baking = true
        bake({
            val d = Dither(0x51ED270B)
            Layers(art.sphere().bitmap(d), art.shine().bitmap(d))
        }) { l ->
            baked = l
            baking = false
            val each = waiting.toList()
            waiting.clear()
            each.forEach { it(l) }
        }
    }

    companion object {
        fun FloatImage.bitmap(d: Dither): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            .apply { copyPixelsFromBuffer(ByteBuffer.wrap(toRgbaBytes(d))) }
    }
}
