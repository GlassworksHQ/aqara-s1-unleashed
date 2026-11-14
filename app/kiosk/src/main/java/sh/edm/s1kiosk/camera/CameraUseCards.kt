/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package sh.edm.s1kiosk.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.glassworks.camera.CameraUse
import dev.glassworks.camera.CameraUsePicker
import sh.edm.s1kiosk.ui.Glass

/**
 * "What may the camera do?" (#117): two cards, Presence only and Presence + snapshots, the lit one
 * edged sky to violet as the radio's cards are. The same view on the camera's page and on setup's
 * Camera step. Main thread.
 */
class CameraUseCards(context: Context) : LinearLayout(context), CameraUsePicker {

    override val view: View get() = this
    override var onPick: ((CameraUse) -> Unit)? = null

    private val g = Glass(context)
    private var chosen = CameraUse.PRESENCE
    private var on = true

    private inner class Card(val use: CameraUse, title: String, line: String, glyph: CameraGlyph.Kind) {
        val icon = CameraGlyph(context, glyph, Glass.MU)
        val name = g.text(19f).apply { text = title }
        val sub = g.text(14f, Glass.MU).apply { text = line; setLineSpacing(0f, 1.2f) }
        val frame = Lit(context).apply {
            isClickable = true
            contentDescription = title
            setOnClickListener { pick(use) }
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(g.px(16f), g.px(14f), g.px(16f), g.px(16f))
                addView(icon, LayoutParams(g.px(30f), g.px(30f)))
                addView(name, LayoutParams(-2, -2).apply { topMargin = g.px(10f) })
                addView(sub, LayoutParams(-1, -2).apply { topMargin = g.px(4f) })
            })
        }

        fun bind() {
            val lit = use == chosen
            frame.lit = lit
            frame.isSelected = lit
            icon.color = if (lit) Glass.SKY else Glass.MU
            name.setTextColor(if (lit) Glass.TX else Glass.MU)
        }
    }

    private val cards = listOf(
        Card(CameraUse.PRESENCE, "Presence only", "Wakes the screen when someone walks up. No image leaves the panel.", CameraGlyph.Kind.PERSON),
        Card(CameraUse.PRESENCE_AND_SNAPSHOTS, "Presence + snapshots", "Also a camera in Home Assistant, for cards and snapshots.", CameraGlyph.Kind.CAMERA),
    )

    init {
        orientation = HORIZONTAL
        cards.forEachIndexed { i, c -> addView(c.frame, LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = g.px(12f) }) }
        bind(chosen, true)
    }

    override fun bind(chosen: CameraUse, enabled: Boolean) {
        this.chosen = chosen
        on = enabled
        alpha = if (enabled) 1f else 0.45f
        cards.forEach { it.bind() }
    }

    private fun pick(use: CameraUse) {
        if (!on) return
        chosen = use
        cards.forEach { it.bind() }
        onPick?.invoke(use)
    }

    /** A card's edge: sky to violet when lit, a hairline when not. */
    private class Lit(context: Context) : FrameLayout(context) {
        private val dp = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        var lit = false
            set(v) { field = v; invalidate() }

        init { setWillNotDraw(false) }

        override fun onDraw(c: Canvas) {
            val rad = 22 * dp
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            p.shader = null
            p.style = Paint.Style.FILL
            p.color = if (lit) LIT_FILL else Color.argb(14, 255, 255, 255)
            c.drawRoundRect(r, rad, rad, p)
            p.style = Paint.Style.STROKE
            if (lit) {
                p.strokeWidth = 1.6f * dp
                p.color = Color.WHITE // the shader's colour shows only through an opaque paint
                p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Glass.SKY, Glass.VIOLET, Shader.TileMode.CLAMP)
            } else {
                p.strokeWidth = dp
                p.color = Glass.BD
            }
            r.inset(p.strokeWidth / 2, p.strokeWidth / 2)
            c.drawRoundRect(r, rad, rad, p)
            p.shader = null
        }

        private companion object {
            val LIT_FILL = Color.parseColor("#141c26")
        }
    }
}
