package sh.edm.s1kiosk.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The ABIs are listed in four places: [ApkAbi], `s1Abis` in app/kiosk/build.gradle.kts, and `ABIS`
 * in the release and test-build workflows, and both workflows name the APKs in their own `apk_name`.
 * An ABI missing from a workflow, or an APK published under another name, ships releases whose APK
 * devices of that ABI never find.
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

    /** Runs the workflow's one-line `apk_name` shell function for [abi], as the workflow does. */
    private fun workflowApkName(wf: String, abi: String): String {
        val fn = File(repo, wf).readLines().map { it.trim() }.singleOrNull { it.startsWith("apk_name() {") }
            ?: throw AssertionError("$wf has no one-line apk_name() function")
        val p = ProcessBuilder("bash", "-c", "$fn\napk_name \"$1\"", "bash", abi)
            .apply { environment().putAll(mapOf("VERSION" to "1.3.0", "SHORT_SHA" to "abc1234")) }
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        assertEquals("bash exit for $wf: $out", 0, p.waitFor())
        return out
    }

    @Test fun `the release publishes each APK under the name the update check looks for`() {
        for (abi in ApkAbi.entries) {
            assertEquals(abi.id, abi.apkName(AppVersion(1, 3, 0)), workflowApkName(".github/workflows/release.yml", abi.id))
        }
    }

    @Test fun `test builds use the release names with test and the commit`() {
        for (abi in ApkAbi.entries) {
            val release = abi.apkName(AppVersion(1, 3, 0))
            val expected = "s1-kiosk-test-" + release.removePrefix("s1-kiosk-").removeSuffix(".apk") + "-abc1234.apk"
            assertEquals(abi.id, expected, workflowApkName(".github/workflows/test-build.yml", abi.id))
        }
    }

    @Test fun `both workflows build every ABI`() {
        for (wf in listOf(".github/workflows/release.yml", ".github/workflows/test-build.yml")) {
            assertEquals(wf, ids, listed(wf, Regex("""^\s+ABIS: ([a-z0-9 _-]+?)\s*(#|$)""")))
        }
    }
}
