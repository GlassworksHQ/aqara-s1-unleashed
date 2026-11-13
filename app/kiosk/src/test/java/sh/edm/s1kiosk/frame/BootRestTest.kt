package sh.edm.s1kiosk.frame

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * The app's first frame is the boot animation's last (#63): res/drawable-nodpi/boot_rest.png and its
 * offset must be the boot zip's part2 frame and trim, or the hand-off jumps. Regenerate the boot
 * (branding/boot/generate.py) and this fails until the app's copy follows.
 */
class BootRestTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
    private val zip = ZipFile(File(root, "branding/glass-tile/bootanimation.zip"))

    private fun entry(name: String) = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }

    @Test
    fun theFrameIsTheBootsLast() {
        val desc = String(entry("desc.txt")).lines()
        assertEquals("the boot ends on part2, played until the app is up", "p 0 0 part2 #0a0b0f", desc.last { it.isNotBlank() })
        assertArrayEquals(entry("part2/frame_0000.png"), File(root, "app/kiosk/src/main/res/drawable-nodpi/boot_rest.png").readBytes())
    }

    @Test
    fun itSitsWhereTheBootDrewIt() {
        val trim = String(entry("part2/trim.txt")).trim() // WxH+X+Y
        val (x, y) = trim.substringAfter('+').split('+').map { it.toInt() }
        assertEquals(x, AqaraS1Plus.BOOT_REST.left)
        assertEquals(y, AqaraS1Plus.BOOT_REST.top)
        // The launch window draws it too, in dp at the S1's density 1.5 (insets are truncated to px).
        val xml = File(root, "app/kiosk/src/main/res/drawable/launch_backdrop.xml").readText()
        val left = Regex("""android:left="([\d.]+)dp"""").find(xml)!!.groupValues[1].toFloat()
        val top = Regex("""android:top="([\d.]+)dp"""").find(xml)!!.groupValues[1].toFloat()
        assertEquals(x, (left * 1.5f).toInt())
        assertEquals(y, (top * 1.5f).toInt())
    }
}
