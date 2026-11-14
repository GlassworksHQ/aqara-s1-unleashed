/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.frame.AqaraS1Plus
import java.io.File
import java.lang.reflect.Modifier
import java.net.URI
import java.util.jar.JarFile

/**
 * The pane catalog (#231): each pane has a `pane.json` and an `icon.svg` next to its code, which
 * `packages/frame-card` and glassworks.dev read. Every pane the app registers has exactly one of each,
 * and every file belongs to a pane, so a new pane can't ship without them.
 *
 * "Registered" is every [PaneDefinition] the runtime makes (the S1's), plus every concrete class that
 * implements [Pane] (found in the compiled classes). Timers is a pane without a definition of its own:
 * Assist's on/off covers it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PaneCatalogTest {

    private val appRoot = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "kiosk/src/main").isDirectory }
    private val main = File(appRoot, "kiosk/src/main/java")
    private val repo = appRoot.parentFile

    private class Entry(val json: File, val data: JSONObject) {
        val id: String = data.getString("id")
        val icon = File(json.parentFile, "icon.svg")
    }

    private val entries by lazy {
        main.walkTopDown().filter { it.isFile && it.name == "pane.json" }.sortedBy { it.path }.map { Entry(it, JSONObject(it.readText())) }.toList()
    }

    private val definitionIds by lazy {
        GlassworksRuntime(AqaraS1Plus()).paneDefinitions(RuntimeEnvironment.getApplication()).map { it.id }
    }

    /**
     * Every concrete class in the app that implements [Pane], found in the compiled classes (not by
     * parsing source), with the id from its source file's `override val id = "…"`.
     */
    private val paneClassIds by lazy {
        val loader = Pane::class.java.classLoader!!
        val url = loader.getResource("dev/glassworks/pane/Pane.class")!!
        // the app's classes are a jar or a directory on the test classpath, depending on the build
        val paths: Sequence<String> = when (url.protocol) {
            "jar" -> JarFile(File(URI(url.path.substringBefore("!/")))).use { jar -> jar.entries().asSequence().map { it.name }.toList() }.asSequence()
            "file" -> File(url.toURI()).parentFile.parentFile.parentFile.parentFile.let { root ->
                root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            }
            else -> error("the app's classes come from $url, neither a jar nor a directory")
        }
        paths.filter { it.endsWith(".class") && !Regex("""\$\d""").containsMatchIn(it) }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .mapNotNull { name -> runCatching { Class.forName(name, false, loader) }.getOrNull() }
            .filter { Pane::class.java.isAssignableFrom(it) && !it.isInterface && !Modifier.isAbstract(it.modifiers) }
            .map { idOf(it) }
            .toList()
    }

    /** The id in [c]'s source file: the one `override val id = "…"` of the file that declares it. */
    private fun idOf(c: Class<*>): String {
        val simple = c.name.substringAfterLast('.').substringAfterLast('$')
        val dir = File(main, c.name.substringBeforeLast('.').replace('.', File.separatorChar))
        val file = dir.listFiles().orEmpty().filter { it.extension == "kt" }.singleOrNull { Regex("""\bclass\s+$simple\b""").containsMatchIn(it.readText()) }
            ?: error("${c.name}: no single source file in $dir declares it")
        val ids = Regex("""override val id = "([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] }.toList()
        assertEquals("${file.name}: one `override val id` per Pane file", 1, ids.size)
        return ids.single()
    }

    private fun rel(f: File) = f.relativeTo(repo).path

    @Test
    fun theScanFindsTheKnownPanes() {
        // the scans must keep working: if a refactor hides the panes from them, this fails before the real checks pass vacuously
        assertTrue(paneClassIds.toString(), paneClassIds.containsAll(listOf("assist", "timers", "sendspin", "radio", "wall-base", "time-zone", "diagnostics")))
        assertTrue(definitionIds.toString(), definitionIds.containsAll(listOf("assist", "sendspin", "radio", "wall-base", "time-zone", "diagnostics")))
    }

    @Test
    fun everyRegisteredPaneHasExactlyOneFileAndEveryFileARegisteredPane() {
        val registered = (definitionIds + paneClassIds).toSortedSet()
        val byId = entries.groupBy { it.id }
        val missing = registered.filter { it !in byId }
        assertTrue("panes without a pane.json (add one next to the pane, see #231): $missing", missing.isEmpty())
        val twice = byId.filterValues { it.size > 1 }.mapValues { (_, v) -> v.map { rel(it.json) } }
        assertTrue("a pane id in more than one pane.json: $twice", twice.isEmpty())
        val orphans = entries.filter { it.id !in registered }.map { "${it.id} (${rel(it.json)})" }
        assertTrue("pane.json for no registered pane: $orphans", orphans.isEmpty())
        val withoutIcon = entries.filter { !it.icon.isFile }.map { rel(it.json) }
        assertTrue("pane.json without an icon.svg beside it: $withoutIcon", withoutIcon.isEmpty())
        val strayIcons = main.walkTopDown().filter { it.isFile && it.name == "icon.svg" && !File(it.parentFile, "pane.json").isFile }.map(::rel).toList()
        assertTrue("icon.svg without a pane.json beside it: $strayIcons", strayIcons.isEmpty())
    }

    @Test
    fun eachFileHasTheCatalogFields() {
        val stages = setOf("planned", "development", "alpha", "beta", "stable")
        val known = setOf("id", "name", "does", "requires", "status", "frame", "docs")
        for (e in entries) {
            val where = rel(e.json)
            val d = e.data
            assertTrue("$where: unknown field(s) ${d.keys().asSequence().toSet() - known}", known.containsAll(d.keys().asSequence().toSet()))
            assertTrue("$where: id must be kebab-case", Regex("^[a-z][a-z0-9-]*$").matches(e.id))
            for (k in listOf("name", "does")) assertTrue("$where: $k", d.optString(k).isNotBlank())
            assertTrue("$where: `does` is one line", "\n" !in d.getString("does"))
            val requires = d.getJSONArray("requires")
            for (i in 0 until requires.length()) {
                assertTrue("$where: requires[$i] must be a kebab-case capability", Regex("^[a-z][a-z0-9-]*$").matches(requires.getString(i)))
            }
            assertTrue("$where: status ${d.optString("status")} is not one of $stages", d.getString("status") in stages)
            if (d.has("frame")) assertTrue("$where: frame ${d.getString("frame")} has no frames/<id>/", File(repo, "frames/${d.getString("frame")}/design/frame.json").isFile)
            if (d.has("docs")) {
                val doc = d.getString("docs").substringBefore('#')
                assertTrue("$where: docs $doc is not a file in the repo", File(repo, doc).isFile)
            }
        }
    }

    @Test
    fun everyIconPassesTheStaticChecks() {
        for (e in entries) {
            val where = rel(e.icon)
            val bytes = e.icon.readBytes()
            assertTrue("$where: ${bytes.size} bytes, must be under 2 KB", bytes.size < 2048)
            val svg = String(bytes, Charsets.UTF_8)
            assertTrue("$where: viewBox must be 0 0 24 24", Regex("""<svg\b[^>]*\bviewBox="0 0 24 24"""").containsMatchIn(svg))
            // no script, no external references, no CSS (which could carry colours or URLs)
            for (bad in listOf("<script", "href", "url(", "<image", "<foreignobject", "<style", "style=", "@import")) {
                assertTrue("$where: must not contain $bad", bad !in svg.lowercase())
            }
            // colour: fill and stroke are none or currentColor, nothing else; no colour-carrying attributes at all
            val paint = Regex("""\b(fill|stroke|color|stop-color|flood-color|lighting-color)="([^"]*)"""")
            val bad = paint.findAll(svg).map { it.groupValues[1] to it.groupValues[2] }.filter { (_, v) -> v != "none" && v != "currentColor" }.toList()
            assertTrue("$where: colours other than none/currentColor: $bad", bad.isEmpty())
            assertTrue("$where: must stroke with currentColor", Regex("""\bstroke="currentColor"""").containsMatchIn(svg))
        }
    }
}
