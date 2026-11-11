package sh.edm.s1kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicencesTest {
    @Test
    fun listsNoticesFirstThenTextsBySpdxId() {
        val docs = Licences.documents(listOf("MPL-2.0.txt", "Apache-2.0.txt", "README", "0BSD.txt"))
        assertEquals(
            listOf("licenses/THIRD-PARTY-NOTICES.md", "licenses/LICENSE",
                "licenses/texts/0BSD.txt", "licenses/texts/Apache-2.0.txt", "licenses/texts/MPL-2.0.txt"),
            docs.map { it.path },
        )
        assertEquals(listOf("0BSD", "Apache-2.0", "MPL-2.0"), docs.drop(2).map { it.label })
    }

    /** Every SPDX id the notices name must have its text in LICENSES/, or the app's screen would lack it. */
    @Test
    fun everyLicenceTheNoticesNameHasItsText() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
        val texts = File(root, "LICENSES").list().orEmpty().map { it.removeSuffix(".txt") }.toSet()
        val notices = File(root, "THIRD-PARTY-NOTICES.md").readText()
        val needed = mapOf(
            "MIT" to "MIT", "Apache-2.0" to "Apache-2.0", "MPL-2.0" to "MPL-2.0", "0BSD" to "0BSD",
            "BSD-2-Clause" to "BSD-2-Clause", "BSD-3-Clause" to "BSD-3-Clause", "Zlib" to "Zlib",
            "GPL-2.0-or-later" to "GPL-2.0", "GPL-3.0-only" to "GPL-3.0", "GPL-3.0-or-later" to "GPL-3.0",
            "LGPL-2.1-or-later" to "LGPL-2.1", "GCC-exception-3.1" to "GCC-exception-3.1",
            "LicenseRef-Silabs-MSLA" to "LicenseRef-Silabs-MSLA",
            "OFL-1.1" to "OFL-1.1",
        )
        for ((id, file) in needed) {
            assertTrue("notices should name $id", id in notices)
            assertTrue("LICENSES/$file.txt missing for $id", file in texts)
        }
    }
}
