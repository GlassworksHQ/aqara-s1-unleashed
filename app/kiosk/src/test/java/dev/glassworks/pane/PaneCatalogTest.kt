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

/**
 * The pane catalog (#231): each pane has a `pane.json` and an `icon.svg` next to its code, which
 * `packages/frame-card` and glassworks.dev read. Every pane the app registers has exactly one of each,
 * and every file belongs to a pane, so a new pane can't ship without them.
 *
 * "Registered" is every [PaneDefinition] the runtime makes (the S1's), plus every class that implements
 * [Pane] (found in the sources). Timers is a pane without a definition of its own: Assist's on/off covers it.
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

    /** The id of each class that implements [Pane]: a file with such a class ([implementsPane]) and its `override val id = "…"`. */
    private val paneClassIds by lazy {
        val idLine = Regex("""override val id = "([^"]+)"""")
        main.walkTopDown().filter { it.isFile && it.extension == "kt" }.mapNotNull { f ->
            val text = f.readText()
            if (!implementsPane(text)) return@mapNotNull null
            val ids = idLine.findAll(text).map { it.groupValues[1] }.toList()
            assertEquals("${f.name}: one `override val id` per Pane file", 1, ids.size)
            ids.single()
        }.toList()
    }

    private fun rel(f: File) = f.relativeTo(repo).path

    @Test
    fun theScanSeesPaneAnywhereInTheSupertypes() {
        // a pane can have other supertypes, before or after Pane, and a constructor over several lines
        assertTrue(implementsPane("class A(ctx: PaneContext) : Pane {"))
        assertTrue(implementsPane("class B : Pane, AutoCloseable {"))
        assertTrue(implementsPane("class C internal constructor(\n    x: Int,\n    f: (Int) -> Unit = {},\n) : AutoCloseable, Pane {"))
        assertTrue(implementsPane("class D<T>(x: T) : dev.glassworks.pane.Pane by delegate {"))
        // a Pane as a parameter, or another type whose name starts with Pane, isn't one
        assertTrue(!implementsPane("class E(val pane: Pane) : AutoCloseable {"))
        assertTrue(!implementsPane("class F(file: SharedPreferences) : PaneDefinition {"))
        assertTrue(!implementsPane("data class G(val p: Pane)"))
        // a bracket inside a string, char or comment doesn't end the constructor
        assertTrue(implementsPane("class H(val label: String = \")\", val c: Char = ')') : Pane {"))
        assertTrue(implementsPane("class I(\n    x: Int, // a ) in a comment\n) : AutoCloseable, Pane {"))
        // Pane as a type argument isn't a supertype
        assertTrue(!implementsPane("class Holder : Container<Foo, Pane>() {"))
        assertTrue(implementsPane("class J : Container<Foo, Bar>(), Pane {"))
        // a function type's arrow inside the type arguments or the constructor isn't a bracket
        assertTrue(implementsPane("class K : Container<(Int) -> Unit>(), Pane {"))
        assertTrue(implementsPane("class L(val f: (Int) -> Unit) : Pane {"))
    }

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

/**
 * Whether [text] declares a class that has `Pane` among its supertypes: after the class name, any type
 * parameters, a constructor modifier and the constructor parameters, the list after `:` up to the class
 * body, split at its top-level commas. `Pane` counts anywhere in it, by simple or qualified name. String
 * and char literals and comments don't count when balancing brackets or splitting.
 */
internal fun implementsPane(text: String): Boolean {
    /** The index just past the literal or comment that starts at [at], or [at] if none does. */
    fun skipLiteral(at: Int): Int {
        if (text.startsWith("\"\"\"", at)) return text.indexOf("\"\"\"", at + 3).let { if (it < 0) text.length else it + 3 }
        if (text.startsWith("//", at)) return text.indexOf('\n', at).let { if (it < 0) text.length else it + 1 }
        if (text.startsWith("/*", at)) return text.indexOf("*/", at + 2).let { if (it < 0) text.length else it + 2 }
        val q = text[at]
        if (q != '"' && q != '\'') return at
        var i = at + 1
        while (i < text.length && text[i] != q) i += if (text[i] == '\\') 2 else 1
        return i + 1
    }
    /** Past the bracket that closes the one at [at]. */
    fun skipBalanced(at: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = at
        while (i < text.length) {
            val past = skipLiteral(i)
            if (past != i) { i = past; continue }
            if (text.startsWith("->", i)) { i += 2; continue } // a function type's arrow isn't a bracket
            when (text[i]) { open -> depth++; close -> if (--depth == 0) return i + 1 }
            i++
        }
        return i
    }
    fun skipSpace(at: Int): Int { var i = at; while (i < text.length && text[i].isWhitespace()) i++; return i }
    /** The supertypes from [from] to the class body: split at commas outside <>, () and literals. */
    fun supertypes(from: Int): List<String> {
        val parts = mutableListOf<String>()
        var start = from
        var depth = 0
        var i = from
        while (i < text.length) {
            val past = skipLiteral(i)
            if (past != i) { i = past; continue }
            if (text.startsWith("->", i)) { i += 2; continue } // a function type's arrow isn't a bracket
            val c = text[i]
            if (depth == 0 && c == '{') break
            when (c) { '<', '(' -> depth++; '>', ')' -> depth--; ',' -> if (depth == 0) { parts += text.substring(start, i); start = i + 1 } }
            i++
        }
        parts += text.substring(start, i)
        return parts
    }
    val modifier = Regex("""\G(?:(?:public|internal|private|protected)\s+)?constructor\b""")
    return Regex("""\bclass\s+\w+""").findAll(text).any { m ->
        var i = skipSpace(m.range.last + 1)
        if (i < text.length && text[i] == '<') i = skipSpace(skipBalanced(i, '<', '>'))
        modifier.find(text, i)?.let { i = skipSpace(it.range.last + 1) }
        if (i < text.length && text[i] == '(') i = skipSpace(skipBalanced(i, '(', ')'))
        if (i >= text.length || text[i] != ':') return@any false
        supertypes(i + 1).any { type -> Regex("""^\s*([\w.]+)""").find(type)?.groupValues?.get(1)?.substringAfterLast('.') == "Pane" }
    }
}
