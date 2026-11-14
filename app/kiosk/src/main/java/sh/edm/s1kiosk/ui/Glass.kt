package sh.edm.s1kiosk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import dev.glassworks.brand.Tokens

/**
 * The panel's own screens on the Glassworks brand (glassworks.dev/brand, #63): always night, sky to
 * violet for "on" and "lit", green, amber and rose for status only, one white primary button per
 * screen. The connecting, setup and settings screens build from these pieces. Main thread.
 */
class Glass(val context: Context) {
    val dp = context.resources.displayMetrics.density
    fun px(v: Float) = (v * dp).toInt()

    fun text(sp: Float, color: Int = TX, weight: Int = Inter.REGULAR) = TextView(context).apply {
        textSize = sp
        setTextColor(color)
        typeface = Inter.typeface(context, weight, sp)
        includeFontPadding = false
    }

    /** The big light heading of a screen or step. */
    fun heading(sp: Float) = text(sp, TX, Inter.LIGHT).apply { letterSpacing = -0.02f }

    /** "STEP 1 OF 4": small, spaced capitals. */
    fun label(s: String = "") = text(11f, FA, Inter.MEDIUM).apply { letterSpacing = 0.2f; isAllCaps = true; text = s }

    /** An address or host name, in the system's monospace face. */
    fun mono(sp: Float, color: Int = MU) = TextView(context).apply {
        textSize = sp
        setTextColor(color)
        typeface = Typeface.MONOSPACE
        includeFontPadding = false
    }

    fun surface(radiusDp: Float, color: Int = S1, stroke: Int = BD) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(color)
        if (stroke != Color.TRANSPARENT) setStroke(px(1f).coerceAtLeast(1), stroke)
    }

    /** The screen's one white button. */
    fun primary(label: String) = pill(label, PRIMARY, BG, Color.TRANSPARENT)

    /** A dark pill with a hairline: every other button. */
    fun ghost(label: String) = pill(label, S2, TX, BD)

    /** A button that is only its words (Not now, Skip). */
    fun plain(label: String) = text(16f, MU, Inter.MEDIUM).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(px(12f), px(14f), px(12f), px(14f))
        isClickable = true
        isFocusable = true
    }

    private fun pill(label: String, fill: Int, color: Int, stroke: Int) = text(16f, color, Inter.MEDIUM).apply {
        text = label
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(px(1f).coerceAtLeast(1), stroke)
        }
        setPadding(px(26f), px(15f), px(26f), px(15f))
        isClickable = true
        isFocusable = true
    }

    /** A dot in a status colour. */
    fun dot(color: Int) = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }

    /** A sky-to-violet pill switch. [onChange] runs when it is tapped. */
    fun toggle(on: Boolean, onChange: (Boolean) -> Unit = {}) = Toggle(context, on, onChange)

    /** One choice in a row of chips: lit (sky to violet) when it is the one chosen. */
    fun chip(label: String) = Chip(context, label)

    class Toggle(context: Context, on: Boolean, private val onChange: (Boolean) -> Unit) : View(context) {
        private val dp = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        var checked = on
            set(v) { if (field != v) { field = v; contentDescription = if (v) "on" else "off"; invalidate() } }

        init {
            contentDescription = if (on) "on" else "off"
            isClickable = true
            setOnClickListener { checked = !checked; onChange(checked) }
        }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((Tokens.Toggle.W * dp).toInt(), (Tokens.Toggle.H * dp).toInt())

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            r.set(0f, 0f, w, h)
            if (checked) {
                paint.shader = LinearGradient(0f, 0f, w, 0f, SKY, VIOLET, Shader.TileMode.CLAMP)
                paint.style = Paint.Style.FILL
                c.drawRoundRect(r, h / 2, h / 2, paint)
                paint.shader = null
            } else {
                paint.style = Paint.Style.FILL; paint.color = S2
                c.drawRoundRect(r, h / 2, h / 2, paint)
                paint.style = Paint.Style.STROKE; paint.color = BD; paint.strokeWidth = dp
                r.inset(dp / 2, dp / 2)
                c.drawRoundRect(r, h / 2, h / 2, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = if (checked) Color.WHITE else MU
            val knob = h / 2 - Tokens.Toggle.KNOB_INSET * dp
            c.drawCircle(if (checked) w - h / 2 else h / 2, h / 2, knob, paint)
        }
    }

    class Chip(context: Context, label: String) : TextView(context) {
        private val dp = resources.displayMetrics.density
        var chosen = false
            set(v) { field = v; restyle() }

        init {
            text = label
            textSize = 15.5f
            typeface = Inter.typeface(context, Inter.MEDIUM, 15.5f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setSingleLine()
            setPadding((17 * dp).toInt(), (10 * dp).toInt(), (17 * dp).toInt(), (10 * dp).toInt())
            isClickable = true
            restyle()
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.4f
        }

        private fun restyle() {
            setTextColor(if (chosen) BG else MU)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, if (chosen) intArrayOf(SKY, VIOLET) else intArrayOf(S1, S1)).apply {
                cornerRadius = 999f
                if (!chosen) setStroke((dp).toInt().coerceAtLeast(1), BD)
            }
        }
    }

    companion object {
        // The brand's screen ink and glass, from the one source the Manage page reads too ([Tokens], #243).
        val BG = Tokens.Screen.bg.argb
        /** Glass on night: cards ([Tokens.Screen.glass]), tiles and buttons (raised), hairlines (edge). */
        val S1 = Tokens.Screen.glass.argb
        val S2 = Tokens.Screen.raised.argb
        val BD = Tokens.Screen.edge.argb
        val TX = Tokens.Screen.fg.argb
        val MU = Tokens.Screen.muted.argb
        val FA = Tokens.Screen.faint.argb
        val PRIMARY = Tokens.Screen.fg.argb
        val SKY = Tokens.SKY.argb
        val INDIGO = Tokens.INDIGO.argb
        val VIOLET = Tokens.VIOLET.argb
        val GREEN = Tokens.GREEN.argb
        val AMBER = Tokens.AMBER.argb
        val ROSE = Tokens.ROSE.argb
    }
}
