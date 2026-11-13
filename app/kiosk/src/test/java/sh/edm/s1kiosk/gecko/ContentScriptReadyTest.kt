package sh.edm.s1kiosk.gecko

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * content.js's "ready" for the connecting screen (#63, round 4 panel run: Home Assistant's white launch
 * screen showed through the fade). The checks are a node:test file next to this class's resources
 * (`extension/content.test.mjs`), run with the stubbed browser and DOM it sets up. Skipped where node
 * isn't installed; GitHub's ubuntu runners have it.
 */
class ContentScriptReadyTest {

    private val module = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "src/main/assets/extension/content.js").isFile }

    private fun node(): String? = listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { n ->
        runCatching { ProcessBuilder(n, "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    @Test
    fun readyWaitsForTheFrontendAndAPaintedFrame() {
        val node = node()
        assumeTrue("node isn't installed", node != null)
        val p = ProcessBuilder(node, "--test", File(module, "src/test/resources/extension/content.test.mjs").path)
            .redirectErrorStream(true)
            .apply { environment()["CONTENT_JS"] = File(module, "src/main/assets/extension/content.js").path }
            .start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        assertEquals(out, 0, p.exitValue())
    }
}
