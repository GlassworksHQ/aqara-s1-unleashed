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

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((52 * dp).toInt(), (30 * dp).toInt())

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
            val knob = h / 2 - 4 * dp
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
        val BG = Color.parseColor("#0a0b0f")
        /** Glass on night: cards (rgba 255 .045), tiles and buttons (.08), hairlines (.07). */
        val S1 = Color.argb((0.045f * 255).toInt(), 255, 255, 255)
        val S2 = Color.argb((0.08f * 255).toInt(), 255, 255, 255)
        val BD = Color.argb((0.07f * 255).toInt(), 255, 255, 255)
        val TX = Color.parseColor("#f4f4f5")
        val MU = Color.parseColor("#a1a1aa")
        val FA = Color.parseColor("#63636b")
        val PRIMARY = Color.parseColor("#f4f4f5")
        val SKY = Color.parseColor("#38bdf8")
        val INDIGO = Color.parseColor("#818cf8")
        val VIOLET = Color.parseColor("#a78bfa")
        val GREEN = Color.parseColor("#34d399")
        val AMBER = Color.parseColor("#fbbf24")
        val ROSE = Color.parseColor("#fb7185")
    }
}
