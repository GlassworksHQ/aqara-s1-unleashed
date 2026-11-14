/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.brand

import dev.glassworks.manage.ManagePages
import dev.glassworks.web.PageShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #243: one set of tokens for the panel and its pages. [Tokens] holds glassworks.dev's values, pinned here to
 * the brand's own file (`kiln/packages/brand/src/tokens.css`, synced from glassworks.dev), and the pages'
 * CSS is made from it.
 */
class TokensTest {
    private val brand: String by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
        File(root, "kiln/packages/brand/src/tokens.css").readText()
    }

    /** The brand's [name] in daylight (the file's first `:root`) or at night (its dark block), normalised. */
    private fun brand(name: String, night: Boolean = false): String {
        val block = if (night) brand.substringAfter("prefers-color-scheme: dark") else brand.substringBefore("@media")
        val v = Regex("""--${Regex.escape(name)}:\s*([^;]+);""").find(block)?.groupValues?.get(1) ?: error("--$name not in the brand's tokens")
        return norm(v)
    }

    private fun norm(v: String) = v.trim().lowercase().replace(" ", "").replace(Regex("""(?<=[,(])0\."""), ".")

    private fun theme(t: Tokens.Theme) = mapOf(
        "bg" to t.bg, "bg-2" to t.bg2, "glass" to t.glass, "edge" to t.edge, "fg" to t.fg, "muted" to t.muted, "faint" to t.faint,
        "ink-sky" to t.inkSky, "ok-ink" to t.okInk, "work-ink" to t.workInk,
    )

    @Test fun thePagesThemesAreTheBrands() {
        for ((name, c) in theme(Tokens.DAY)) assertEquals("day --$name", brand(name), norm(c.css))
        for ((name, c) in theme(Tokens.NIGHT)) assertEquals("night --$name", brand(name, night = true), norm(c.css))
    }

    @Test fun theScreenStatusAndShapesAreTheBrands() {
        assertEquals(brand("night"), Tokens.Screen.bg.css)
        assertEquals(brand("s-fg"), Tokens.Screen.fg.css)
        assertEquals(brand("s-mu"), Tokens.Screen.muted.css)
        assertEquals(brand("s-fa"), Tokens.Screen.faint.css)
        for ((name, c) in mapOf("sky" to Tokens.SKY, "violet" to Tokens.VIOLET, "green" to Tokens.GREEN, "amber" to Tokens.AMBER, "rose" to Tokens.ROSE)) {
            assertEquals(name, brand(name), c.css)
        }
        assertEquals(brand("r-tile"), "${Tokens.R_TILE}px")
        assertEquals(brand("r-pane"), "${Tokens.R_PANE}px")
    }

    @Test fun androidAndCssReadTheSameColour() {
        val edge = Tokens.NIGHT.edge
        assertEquals(0x14FFFFFF, edge.argb) // .08 of 255 rounds to 20
        assertEquals("rgba(255,255,255,.08)", edge.css)
        assertEquals("#38bdf8", Tokens.SKY.css)
        assertEquals(0xFF38BDF8.toInt(), Tokens.SKY.argb)
    }

    @Test fun thePagesCssIsMadeFromTheTokens() {
        assertTrue(PageShell.TOKENS.startsWith(Tokens.css()))
        val c = Tokens.Chip
        assertTrue("the chip as the panel's rail draws it", ManagePages.MANAGE_CSS.contains(
            ".chip{display:inline-flex;align-items:center;gap:${c.GAP}px;padding:${c.PAD_V}px ${c.PAD_END}px ${c.PAD_V}px ${c.PAD_START}px;"))
        assertTrue(ManagePages.MANAGE_CSS.contains(".chip .dot{width:${c.DOT}px;height:${c.DOT}px}"))
        assertTrue(ManagePages.MANAGE_CSS.contains("width:${Tokens.Toggle.W}px;height:${Tokens.Toggle.H}px"))
    }
}
