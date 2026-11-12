package sh.edm.s1kiosk.esphome

import dev.glassworks.frame.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The device shell is meant to become device-neutral, and the core already is. Source scans, like
 * PlatformConfinementTest:
 *  - `PanelDevice` reaches the frame only through `dev.glassworks.frame.Frame`: every `runtime.frame`
 *    read is a member of [Frame], or the one field typed as it. It imports nothing from the S1 frame.
 *  - Nothing under `dev/glassworks` imports app code. The libraries in `app/libs` are allowed: they
 *    are plain libraries with no Glassworks dependency.
 */
class PanelDeviceFrameTest {
    private val appRoot = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "libs").isDirectory && File(it, "kiosk/src/main").isDirectory }

    @Test fun panelDeviceSeesOnlyTheFrameInterface() {
        val frameMembers = Frame::class.java.methods.map { m ->
            m.name.removePrefix("get").replaceFirstChar { it.lowercase() }
        }.toSet()
        val lines = File(appRoot, "kiosk/src/main/java/sh/edm/s1kiosk/esphome/PanelDevice.kt").readLines()
        val bad = lines.withIndex().flatMap { (i, line) ->
            val reads = Regex("""runtime\.frame(?:\.(\w+))?""").findAll(line).filterNot { m ->
                val member = m.groupValues[1]
                if (member.isEmpty()) ": Frame = runtime.frame" in line else member in frameMembers
            }.map { "${i + 1}: ${line.trim()}" }.toList()
            val s1 = if ("sh.edm.s1kiosk.frame" in line || "AqaraS1Plus" in line) listOf("${i + 1}: ${line.trim()}") else emptyList()
            reads + s1
        }.distinct()
        assertTrue("scan found the frame field", lines.any { "private val frame: Frame = runtime.frame" in it })
        assertEquals("PanelDevice reaches past Frame:\n${bad.joinToString("\n")}", emptyList<String>(), bad)
    }

    @Test fun theCoreImportsNoAppCode() {
        val libPackages = File(appRoot, "libs").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f -> f.useLines { ls -> ls.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim() } }
            .toSet()
        val core = File(appRoot, "kiosk/src/main/java/dev/glassworks")
        val sources = core.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("scan found the core", sources.size >= 5)
        // Imports, and fully qualified names anywhere else (`sh.edm.s1kiosk.X(...)` needs no import).
        val qualified = Regex("""\bsh\.edm\.[a-z0-9_.]*[a-z0-9_]""")
        val bad = sources.flatMap { f ->
            f.readLines().withIndex().filter { (_, line) ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@filter false // KDoc and comments
                val imp = code.takeIf { it.startsWith("import sh.edm.") }?.removePrefix("import ")?.substringBefore(" as ")
                if (imp != null) imp.substringBeforeLast('.') !in libPackages
                else qualified.findAll(code).any { m -> libPackages.none { m.value == it || m.value.startsWith("$it.") } }
            }.map { (i, line) -> "${f.relativeTo(appRoot).path}:${i + 1}: ${line.trim()}" }
        }
        assertEquals("dev.glassworks imports app code:\n${bad.joinToString("\n")}", emptyList<String>(), bad)
    }
}
