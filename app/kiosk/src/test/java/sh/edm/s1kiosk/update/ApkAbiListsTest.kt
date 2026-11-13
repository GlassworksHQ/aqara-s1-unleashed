package sh.edm.s1kiosk.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Each frame declares the ABIs its device runs (`abis` in frames/<id>/design/frame.json, #98). The
 * release builds one APK for each of them (tools/release/frame-abis.sh, which the workflows run, and
 * app/kiosk/build.gradle.kts), and both workflows name the APKs in their own `apk_name`. The app's
 * [ApkAbi] must know exactly those ABIs: an APK the app doesn't know is never installed, and an ABI
 * the app knows but no release builds is looked for in vain.
 */
class ApkAbiListsTest {
    private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, ".github/workflows").isDirectory && File(it, "app/kiosk").isDirectory }
    private val ids = ApkAbi.entries.map { it.id }
    private val workflows = listOf(".github/workflows/release.yml", ".github/workflows/test-build.yml", ".github/workflows/pr.yml")

    /** Every frame's `abis`, in frame-id order, each once. */
    private val frameAbis: List<String> = File(repo, "frames").listFiles().orEmpty()
        .map { File(it, "design/frame.json") }.filter { it.isFile }.sortedBy { it.parentFile.parentFile.name }
        .flatMap { f -> JSONObject(f.readText()).getJSONArray("abis").let { a -> List(a.length()) { a.getString(it) } } }
        .distinct()

    @Test fun `the update check knows exactly the ABIs the frames run`() {
        assertEquals(frameAbis.toSet(), ids.toSet())
    }

    /** The default build (no -Ps1Abi) and the release's `s1-kiosk-<version>.apk`, which S1 panels look for. */
    @Test fun `the S1's first ABI is armeabi-v7a`() {
        val s1 = JSONObject(File(repo, "frames/aqara-s1-plus/design/frame.json").readText()).getJSONArray("abis")
        assertEquals("armeabi-v7a", s1.getString(0))
    }

    @Test fun `the workflows' ABI list is the frames'`() {
        val p = ProcessBuilder("tools/release/frame-abis.sh").directory(repo).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        assertEquals("frame-abis.sh exit: $out", 0, p.waitFor())
        assertEquals(frameAbis.joinToString(" "), out)
    }

    @Test fun `frame-abis sh refuses abis that aren't a list of names`() {
        for (bad in listOf("[]", "\"armeabi-v7a\"", "[1]", null)) {
            val root = createTempDirectory("frame-abis").toFile()
            try {
                File(root, "frames/a/design").mkdirs()
                File(root, "frames/a/design/frame.json").writeText("""{"abis": ["armeabi-v7a"]}""")
                File(root, "frames/b/design").mkdirs()
                File(root, "frames/b/design/frame.json").writeText(if (bad == null) "{}" else """{"abis": $bad}""")
                val p = ProcessBuilder(File(repo, "tools/release/frame-abis.sh").path, root.path).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                assertNotEquals("abis $bad: $out", 0, p.waitFor())
                assertTrue("abis $bad: $out", out.contains("frames/b/design/frame.json: abis must be"))
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test fun `no workflow or build script lists ABIs of its own`() {
        val abiName = Regex(""""(${ApkAbi.entries.joinToString("|") { Regex.escape(it.id) }})"""")
        assertEquals("app/kiosk/build.gradle.kts", null, abiName.find(File(repo, "app/kiosk/build.gradle.kts").readText())?.value)
        for (wf in workflows) {
            val lines = File(repo, wf).readLines().map { it.trim() }
            assertEquals("$wf: ABIS comes from frame-abis.sh", 1, lines.count { it == """abis="${'$'}(tools/release/frame-abis.sh)"""" })
            val own = lines.filter { it.startsWith("for abi in") && !it.startsWith("for abi in ${'$'}ABIS; do") }
            assertEquals("$wf: ABI loops not over ${'$'}ABIS", emptyList<String>(), own)
        }
    }

    @Test fun `test builds upload an artifact for every ABI`() {
        val text = File(repo, ".github/workflows/test-build.yml").readText()
        for (abi in ids) assertEquals(abi, 1, Regex("""path: dist/${Regex.escape(abi)}/\n""").findAll(text).count())
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

    @Test fun `each workflow names every APK through apk_name and nowhere else`() {
        val calls = mapOf(
            ".github/workflows/release.yml" to """name="${'$'}(apk_name "${'$'}abi")"""",
            ".github/workflows/test-build.yml" to """apk="dist/${'$'}abi/${'$'}(apk_name "${'$'}abi")"""",
        )
        val literal = Regex("""\b[A-Za-z_]+="[^"]*s1-kiosk-""")
        for ((wf, call) in calls) {
            val lines = File(repo, wf).readLines().map { it.trim() }
            assertEquals("$wf: the APK name comes from apk_name", 1, lines.count { it == call })
            val stray = lines.filter { !it.startsWith("apk_name() {") && !it.startsWith("#") && literal.containsMatchIn(it) }
            assertEquals("$wf: APK names written outside apk_name", emptyList<String>(), stray)
        }
    }
}
