package sh.edm.s1kiosk.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The ABIs are listed in four places: [ApkAbi], `s1Abis` in app/kiosk/build.gradle.kts, and `ABIS`
 * in the release and test-build workflows. An ABI missing from a workflow ships releases without
 * its APK, and devices of that ABI never get an update.
 */
class ApkAbiListsTest {
    private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, ".github/workflows").isDirectory && File(it, "app/kiosk").isDirectory }
    private val ids = ApkAbi.entries.map { it.id }

    private fun listed(file: String, line: Regex): List<String> {
        val m = File(repo, file).readLines().firstNotNullOfOrNull { line.find(it) }
            ?: throw AssertionError("$file has no line matching $line")
        return Regex("[a-z0-9_-]+").findAll(m.groupValues[1]).map { it.value }.toList()
    }

    @Test fun `the build accepts exactly the ABIs the update check knows`() {
        assertEquals(ids, listed("app/kiosk/build.gradle.kts", Regex("""^val s1Abis = listOf\(([^)]*)\)""")))
    }

    @Test fun `both workflows build every ABI`() {
        for (wf in listOf(".github/workflows/release.yml", ".github/workflows/test-build.yml")) {
            assertEquals(wf, ids, listed(wf, Regex("""^\s+ABIS: ([a-z0-9 _-]+?)\s*(#|$)""")))
        }
    }
}
