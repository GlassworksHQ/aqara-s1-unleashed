package sh.edm.s1kiosk.setup

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.ui.Mockup
import java.io.File
import java.time.Duration
import java.util.zip.ZipFile

/**
 * Boot → kiosk (#63 round 2): the boot animation's last frame next to the connecting screen's frames,
 * from its first on, and how many pixels of the first differ. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*ContinuityMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContinuityMockups {

    @Before fun interFromSources() = Mockup.interFromSources()

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }

    /** The boot's last frame as the screen shows it: part2 at its trim offset on flat #0a0b0f. */
    private fun bootFrame(): Bitmap {
        val zip = ZipFile(File(root, "branding/glass-tile/bootanimation.zip"))
        val png = zip.getInputStream(zip.getEntry("part2/frame_0000.png")).use { it.readBytes() }
        val part = BitmapFactory.decodeByteArray(png, 0, png.size)
        val b = Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF0A0B0F.toInt())
        c.drawBitmap(part, AqaraS1Plus.BOOT_REST.left.toFloat(), AqaraS1Plus.BOOT_REST.top.toFloat(), null)
        return b
    }

    private fun draw(v: View): Bitmap {
        // A real traversal first: a single-line TextView centres its text in its pre-draw pass.
        shadowOf(Looper.getMainLooper()).idle()
        Mockup.layout(v)
        val b = Bitmap.createBitmap(1440, 720, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    private fun save(name: String, b: Bitmap) {
        val out = File(Mockup.dir!!, "continuity").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Pixels that differ by more than [tol] in any channel. */
    private fun differ(a: Bitmap, b: Bitmap, tol: Int = 2): Int {
        var n = 0
        for (y in 0 until 720) for (x in 0 until 1440) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            if (listOf(16, 8, 0).any { s -> kotlin.math.abs((p shr s and 0xFF) - (q shr s and 0xFF)) > tol }) n++
        }
        return n
    }

    @Test
    fun strip() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        val boot = bootFrame()
        save("00-boot-last-frame", boot)
        val a = Mockup.activity()
        val o = LoadingOverlay(a, AqaraS1Plus.BOOT_REST)
        // Robolectric here runs without the app's resources: the PNG the app ships, read from its source.
        val shipped = BitmapFactory.decodeFile(File(root, "app/kiosk/src/main/res/drawable-nodpi/boot_rest.png").path)
        o.loadRest = { android.graphics.drawable.BitmapDrawable(a.resources, shipped).apply { setTargetDensity(shipped.density) } }
        a.setContentView(o, ViewGroup.LayoutParams(-1, -1))
        o.show(LoadingOverlay.State.Connecting("Starting up", "", host = false))
        o.previewBoot(0)
        val first = draw(o)
        save("01-kiosk-0ms", first)
        System.err.println("CONTINUITY first frame: ${differ(boot, first)} of ${1440 * 720} px differ from the boot's last")
        for (at in listOf(200L, 350L, 450L, 550L, 700L, 850L, 1_070L)) {
            o.previewBoot(at)
            val f = draw(o)
            save("02-kiosk-${at}ms", f)
            System.err.println("CONTINUITY ${at} ms: ${differ(boot, f)} px differ")
        }
        // The live stack alone in the boot's pose (no boot frame on top): what the dissolve blends into.
        val a2 = Mockup.activity()
        val bare = LoadingOverlay(a2, AqaraS1Plus.BOOT_REST).apply { loadRest = { null } }
        a2.setContentView(bare, ViewGroup.LayoutParams(-1, -1))
        bare.show(LoadingOverlay.State.Connecting("Starting up", "", host = false))
        bare.previewBoot(0)
        val live = draw(bare)
        save("04-live-stack-in-boot-pose", live)
        System.err.println("CONTINUITY live stack, boot pose: ${differ(boot, live)} px differ")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        o.show(LoadingOverlay.State.Connecting(LoadingOverlay.CONNECTING, "homeassistant.local"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        save("03-kiosk-connecting-ha", draw(o))
    }
}
