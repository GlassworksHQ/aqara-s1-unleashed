package sh.edm.s1kiosk.radio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Inter

/**
 * The radio's mode, technology first (#63 round 2, the maintainer's option C): two cards, Zigbee and
 * Thread, and inside the lit one its two roles as a two-way choice. The same view in setup (the radio's
 * sub-step under Panes) and in Settings → Radio. Off isn't here: it is the pane's switch (on the Panes
 * step in setup, in the section's header in Settings). Main thread.
 */
class RadioTechPicker(context: Context) : LinearLayout(context) {

    /** A role was tapped. The picker shows it lit at once; [chosen] is what it shows. */
    var onPick: ((RadioMode) -> Unit)? = null

    /** The role shown lit; null shows both cards unlit (the radio is off). */
    var chosen: RadioMode? = null
        set(v) { field = v; rebind() }

    /** A status in the lit card's corner (Settings: "Running" in green); null hides it. */
    var status: Pair<String, Int>? = null
        set(v) { field = v; rebind() }

    private val g = Glass(context)

    private class Tech(val name: String, val line: String, val hue: Int, val glyph: RoleStyle.Glyph, val roles: List<Pair<RadioMode, String>>)

    private val techs = listOf(
        Tech("Zigbee", "For ZHA or Zigbee2MQTT", RoleStyle.ZIGBEE, RoleStyle.Glyph.HUB,
            listOf(RadioMode.ZIGBEE_TCP to "Coordinator", RadioMode.ZIGBEE_ROUTER to "Repeater β")),
        Tech("Thread", "For Matter over Thread", RoleStyle.THREAD, RoleStyle.Glyph.GLOBE,
            listOf(RadioMode.THREAD to "Border router", RadioMode.THREAD_ROUTER to "Repeater β")),
    )

    private inner class Card(val tech: Tech) {
        val icon = RoleIcon(context, tech.glyph, tech.hue, 30f)
        val dot = g.dot(Glass.GREEN)
        val statusText = g.text(13f, Glass.GREEN, Inter.MEDIUM)
        val statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LayoutParams(g.px(8f), g.px(8f)).apply { marginEnd = g.px(6f) })
            addView(statusText)
        }
        val name = g.text(20f).apply { text = tech.name }
        val line = g.text(14f, Glass.MU).apply { text = tech.line }
        val segments = tech.roles.map { (m, label) ->
            m to TextView(context).apply {
                text = label; textSize = 13.5f; gravity = Gravity.CENTER; setSingleLine()
                typeface = Inter.typeface(context, Inter.MEDIUM, 13.5f)
                setPadding(g.px(6f), g.px(8f), g.px(6f), g.px(8f))
                isClickable = true
                setOnClickListener { pick(m) }
                contentDescription = "${tech.name} ${label.removeSuffix(" β")}"
            }
        }
        val frame = Lit(context).apply {
            isClickable = true
            setOnClickListener { pick(chosen?.takeIf { c -> tech.roles.any { it.first == c } } ?: tech.roles[0].first) }
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(g.px(16f), g.px(14f), g.px(14f), g.px(14f))
                addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(icon)
                    addView(View(context), LayoutParams(0, 1, 1f))
                    addView(statusRow)
                })
                addView(name, LayoutParams(-2, -2).apply { topMargin = g.px(12f) })
                addView(line, LayoutParams(-2, -2).apply { topMargin = g.px(4f) })
                addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    background = GradientDrawable().apply { cornerRadius = 999f; setColor(Color.argb(28, 0, 0, 0)); setStroke(g.px(1f).coerceAtLeast(1), Glass.BD) }
                    setPadding(g.px(3f), g.px(3f), g.px(3f), g.px(3f))
                    segments.forEach { (_, v) -> addView(v, LayoutParams(0, -2, 1f)) }
                }, LayoutParams(-1, -2).apply { topMargin = g.px(12f) })
            })
        }

        fun bind() {
            val lit = tech.roles.any { it.first == chosen }
            frame.lit = lit
            icon.color = if (lit) tech.hue else RoleStyle.tint(tech.hue, 0.7f); icon.invalidate()
            name.setTextColor(if (lit) Glass.TX else Glass.MU)
            val s = status.takeIf { lit }
            statusRow.visibility = if (s == null) View.GONE else View.VISIBLE
            s?.let { (words, color) -> statusText.text = words; statusText.setTextColor(color); dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }
            segments.forEach { (m, v) ->
                val on = m == chosen
                v.setTextColor(if (on) Glass.BG else if (lit) Glass.TX else Glass.FA)
                v.background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Glass.SKY, Glass.VIOLET)).apply { cornerRadius = 999f } else null
            }
        }
    }

    private val cards = techs.map { Card(it) }

    init {
        orientation = HORIZONTAL
        cards.forEachIndexed { i, c -> addView(c.frame, LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = g.px(12f) }) }
        rebind()
    }

    private fun pick(m: RadioMode) {
        if (!isEnabled) return
        chosen = m
        onPick?.invoke(m)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.55f
    }

    private fun rebind() = cards.forEach { it.bind() }

    /** A card's edge: sky to violet when lit (on), a hairline when not. */
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
