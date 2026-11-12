/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails when app code outside the engine uses GeckoView. Only `dev/glassworks/engine/gecko/` may
 * name `org.mozilla.gecko…`; everything else talks to [Engine]. The scan matches the text, so a
 * fully qualified name or a comment trips it too, as `PlatformConfinementTest` does for `SDK_INT`.
 */
class EngineConfinementTest {
    private val appRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "libs").isDirectory && File(it, "kiosk/src/main").isDirectory }
    private val main = File(appRoot, "kiosk/src/main/java")
    private val geckoDir = File(main, "dev/glassworks/engine/gecko")

    private fun sources(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()

    @Test
    fun `only the Gecko engine package uses GeckoView`() {
        assertTrue("scan found no sources", sources(main).size > 100)
        val found = sources(main).filterNot { it.startsWith(geckoDir) }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if ("org.mozilla.gecko" in line) "${f.relativeTo(appRoot).path}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("GeckoView outside dev.glassworks.engine.gecko; go through Engine:\n${found.joinToString("\n")}", found.isEmpty())
    }

    @Test
    fun `the scan sees the engine that is allowed to use it`() {
        val engine = File(geckoDir, "GeckoEngine.kt")
        assertTrue(engine.readText().contains("import org.mozilla.geckoview.GeckoRuntime"))
    }
}
