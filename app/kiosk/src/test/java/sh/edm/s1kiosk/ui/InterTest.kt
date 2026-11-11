package sh.edm.s1kiosk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InterTest {
    @Test
    fun opticalSizeFollowsTheTextSizeWithinInterRange() {
        assertEquals(14, Inter.opticalSize(9.5f))
        assertEquals(14, Inter.opticalSize(11.5f))
        assertEquals(17, Inter.opticalSize(17f))
        assertEquals(21, Inter.opticalSize(21f))
        assertEquals(32, Inter.opticalSize(36f))
    }

    @Test
    fun variationSettings() {
        assertEquals("'wght' 300, 'opsz' 32", Inter.variation(Inter.LIGHT, 36f))
        assertEquals("'wght' 500, 'opsz' 14", Inter.variation(Inter.MEDIUM, 11.5f))
    }

    /** The bundled file is the variable Inter, and its licence ships with the app's licence texts. */
    @Test
    fun fontAndLicenceAreInTheTree() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
        val font = File(root, "app/kiosk/src/main/res/font/inter.ttf")
        assertTrue(font.isFile && font.length() > 100_000)
        val ofl = File(root, "LICENSES/OFL-1.1.txt").readText()
        assertTrue("SIL OPEN FONT LICENSE Version 1.1" in ofl)
        assertTrue("OFL-1.1" in File(root, "THIRD-PARTY-NOTICES.md").readText())
    }
}
