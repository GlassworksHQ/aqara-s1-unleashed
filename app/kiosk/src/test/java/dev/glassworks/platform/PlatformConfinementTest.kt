/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails when `SDK_INT` is read outside its one place. In the app that is `dev/glassworks/platform/`.
 * Each library under `app/libs` may read it only in its own `Compat.kt`, because libraries do not
 * depend on Glassworks. The scan matches the text, so an alias import or a comment trips it too.
 *
 * A source scan, not a Lint rule: it needs no extra module, it runs in `./gradlew test` where CI
 * already runs, and its failure names the file and line.
 */
class PlatformConfinementTest {
    private val appRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "libs").isDirectory && File(it, "kiosk/src/main").isDirectory }

    private fun sources(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()

    private fun violations(root: File, allowed: (File) -> Boolean): List<String> =
        sources(root).filterNot(allowed).flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if ("SDK_INT" in line) "${f.relativeTo(appRoot).path}:${i + 1}: ${line.trim()}" else null
            }
        }

    @Test
    fun `the app reads SDK_INT only in dev glassworks platform`() {
        val main = File(appRoot, "kiosk/src/main")
        val platformDir = File(main, "java/dev/glassworks/platform")
        assertTrue("scan found no sources", sources(main).size > 100)
        val found = violations(main) { it.startsWith(platformDir) }
        assertTrue("SDK_INT outside dev.glassworks.platform; add a query to Platform:\n${found.joinToString("\n")}", found.isEmpty())
    }

    @Test
    fun `each library reads SDK_INT only in its own Compat file`() {
        val libs = File(appRoot, "libs").listFiles { f -> File(f, "src/main").isDirectory }.orEmpty()
        assertTrue("scan found no libraries", libs.isNotEmpty())
        val found = libs.flatMap { lib -> violations(File(lib, "src/main")) { it.name == "Compat.kt" } }
        assertTrue("SDK_INT outside a library's Compat.kt:\n${found.joinToString("\n")}", found.isEmpty())
    }

    @Test
    fun `the scan sees the places that are allowed to read it`() {
        val platform = File(appRoot, "kiosk/src/main/java/dev/glassworks/platform/Platform.kt")
        assertTrue(platform.readText().contains("SDK_INT"))
        assertTrue(File(appRoot, "libs/improv/src/main/java/sh/edm/improv/Compat.kt").readText().contains("SDK_INT"))
    }
}
