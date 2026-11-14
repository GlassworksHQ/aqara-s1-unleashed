/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.brand

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Glassworks look in numbers: the one source for the panel's own screens and the pages it serves
 * (the Manage page, the QR pages), so both look the same (#243). Colours are glassworks.dev's
 * (`kiln/packages/brand/src/tokens.css`, which `TokensTest` pins these to): night and daylight for the
 * pages, and the screen's own ink, always night. Shapes are dp on the panel and px on a page.
 */
object Tokens {

    /** A colour and its opacity, as the brand writes it (`rgba(255, 255, 255, 0.045)`). */
    class Colour(val rgb: Int, val alpha: Float = 1f) {
        /** For Android: `0xAARRGGBB`. */
        val argb: Int get() = ((alpha * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)

        /** For CSS: `#38bdf8`, or `rgba(255,255,255,.045)` when it is see-through. */
        val css: String get() = if (alpha >= 1f) String.format(Locale.ROOT, "#%06x", rgb and 0xFFFFFF)
            else "rgba(${rgb shr 16 and 255},${rgb shr 8 and 255},${rgb and 255},${String.format(Locale.ROOT, "%.3f", alpha).trimEnd('0').removePrefix("0")})"
    }

    /** A page's ground and ink in one theme. */
    class Theme(
        val bg: Colour, val bg2: Colour, val glass: Colour, val edge: Colour,
        val fg: Colour, val muted: Colour, val faint: Colour,
        val inkSky: Colour, val okInk: Colour, val workInk: Colour,
    )

    val NIGHT = Theme(
        bg = Colour(0x0a0b0f), bg2 = Colour(0x111319), glass = Colour(0xffffff, 0.045f), edge = Colour(0xffffff, 0.08f),
        fg = Colour(0xf4f4f5), muted = Colour(0xa1a1aa), faint = Colour(0x6b6b74),
        inkSky = Colour(0x7dd3fc), okInk = Colour(0x34d399), workInk = Colour(0xfbbf24),
    )

    val DAY = Theme(
        bg = Colour(0xeceef2), bg2 = Colour(0xe2e5eb), glass = Colour(0xffffff, 0.66f), edge = Colour(0x101420, 0.1f),
        fg = Colour(0x0c0e13), muted = Colour(0x4f525c), faint = Colour(0x7d808b),
        inkSky = Colour(0x0369a1), okInk = Colour(0x047857), workInk = Colour(0xb45309),
    )

    /** The panel's screen: always night, with its own ink (the brand's `--night`, `--s-fg`, `--s-mu`, `--s-fa`). */
    object Screen {
        val bg = Colour(0x0a0b0f)
        val fg = Colour(0xf4f4f5)
        val muted = Colour(0xa1a1aa)
        val faint = Colour(0x63636b)
        /** Cards and hairlines: the night page's glass and edge. */
        val glass = NIGHT.glass
        val edge = NIGHT.edge
        /** Tiles and buttons, a step above a card. */
        val raised = Colour(0xffffff, 0.08f)
    }

    // Light and heat, either theme. Status colours mean status only.
    val SKY = Colour(0x38bdf8)
    val INDIGO = Colour(0x818cf8)
    val VIOLET = Colour(0xa78bfa)
    val GREEN = Colour(0x34d399)
    val AMBER = Colour(0xfbbf24)
    val ROSE = Colour(0xfb7185)

    /** Corners: a tile or card (`--r-tile`), a picked row in a list (`--r-pane`), a dialog. */
    const val R_TILE = 22
    const val R_PANE = 12
    const val R_DIALOG = 28
    const val DIALOG_WIDTH = 560

    /** A settings row's least height. */
    const val ROW_MIN = 64

    /** A pane's status chip (#243): a pill, a dot in the status colour, the word in the muted ink. */
    object Chip {
        const val PAD_V = 2
        const val PAD_START = 8
        const val PAD_END = 9
        const val DOT = 6
        const val GAP = 6
        const val TEXT = 12
    }

    /** The on/off switch: a pill, lit sky to violet when on, its knob [KNOB_INSET] in from the edge. */
    object Toggle {
        const val W = 52
        const val H = 30
        const val KNOB_INSET = 4
    }

    /** The pages' colours as CSS custom properties: night, then daylight when the computer or phone is in light mode. */
    fun css(): String {
        fun vars(t: Theme) = "--bg:${t.bg.css};--bg-2:${t.bg2.css};--glass:${t.glass.css};--edge:${t.edge.css};--fg:${t.fg.css};--muted:${t.muted.css};" +
            "--faint:${t.faint.css};--ink-sky:${t.inkSky.css};--ok-ink:${t.okInk.css};--work-ink:${t.workInk.css};"
        return ":root{${vars(NIGHT)}--sky:${SKY.css};--violet:${VIOLET.css};--green:${GREEN.css};--amber:${AMBER.css};--rose:${ROSE.css};" +
            "--r-tile:${R_TILE}px;--r-pane:${R_PANE}px;color-scheme:dark}\n" +
            "@media (prefers-color-scheme:light){:root{${vars(DAY)}color-scheme:light}}"
    }
}
