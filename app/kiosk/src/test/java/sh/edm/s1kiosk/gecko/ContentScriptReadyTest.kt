package sh.edm.s1kiosk.gecko

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * content.js and the connecting screen (#63, round 4 and 5 panel runs: Home Assistant's white launch
 * screen showed through the fade): the launch screen draws at night from the start, and "ready" waits for
 * the frontend and a painted frame. The checks are a node:test file in this module's test resources
 * (`extension/content.test.mjs`), with the browser and the DOM stubbed. Without node the test is skipped
 * on a desk and fails on CI (`CI` set), where pr.yml installs node.
 */
class ContentScriptReadyTest {

    private val contentJs = "src/main/assets/extension/content.js"
    private val harness = "src/test/resources/extension/content.test.mjs"

    private val module: File = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, contentJs).isFile }
        ?: error("$contentJs not found from ${File("").absolutePath} or any folder above it")

    private fun node(): String? = listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { n ->
        runCatching { ProcessBuilder(n, "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    @Test
    fun theLaunchScreenStaysDarkAndReadyWaitsForAPaintedFrame() {
        val node = node()
        if (node == null) {
            if (System.getenv("CI") != null) fail("node isn't installed: pr.yml's app job sets it up (actions/setup-node)")
            assumeTrue("node isn't installed", false)
        }
        val script = File(module, harness)
        check(script.isFile) { "${script.path} is missing" }
        val p = ProcessBuilder(node, "--test", script.path)
            .redirectErrorStream(true)
            .apply { environment()["CONTENT_JS"] = File(module, contentJs).path }
            .start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { isDaemon = true; start() }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            fail("node --test didn't finish within 60 s:\n$out")
        }
        reader.join(5_000)
        assertEquals(out.toString(), 0, p.exitValue())
    }
}
