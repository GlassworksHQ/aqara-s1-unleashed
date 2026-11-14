package sh.edm.s1kiosk.ui

import dev.glassworks.brand.Tokens
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.glassworks.pane.SettingsItem

/**
 * One reading on a pane's page (#212): its name, the value large, a line under it, and its last hour
 * as a line or how full it is as a meter. The line and the meter use the brand's sky to violet, never
 * a status colour. [big]: the value's size; [graph]: room for a line or a meter under the text.
 */
class TileView(context: Context, big: Float, private val graph: Boolean) : LinearLayout(context) {
    private val g = Glass(context)
    private val name = g.label()
    private val value = g.heading(big).apply { setSingleLine() }
    private val unit = g.text(14f, Glass.MU).apply { setPadding(g.px(5f), 0, 0, g.px(4f)) }
    private val detail = g.text(13.5f, Glass.MU).apply { setSingleLine() }
    private val spark = Spark(context)
    private val meter = Meter(context)

    init {
        orientation = VERTICAL
        background = g.surface(Tokens.R_TILE.toFloat())
        setPadding(g.px(16f), g.px(13f), g.px(16f), g.px(12f))
        addView(name)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(value)
            addView(unit)
        }, LayoutParams(-1, -2).apply { topMargin = g.px(4f) })
        addView(detail, LayoutParams(-1, -2).apply { topMargin = g.px(1f) })
        if (graph) {
            addView(spark, LayoutParams(-1, g.px(22f)).apply { topMargin = g.px(8f) })
            addView(meter, LayoutParams(-1, g.px(22f)).apply { topMargin = g.px(8f) })
        }
    }

    fun bind(t: SettingsItem.Tile) {
        name.text = t.name
        value.text = t.value
        unit.text = t.unit
        unit.visibility = if (t.unit.isEmpty()) GONE else VISIBLE
        detail.text = t.detail
        if (!graph) return
        spark.values = t.history.orEmpty()
        meter.used = t.meter
        // The space stays when a tile has neither, so the row's tiles line up.
        spark.visibility = if (t.meter == null) VISIBLE else GONE
        meter.visibility = if (t.meter != null) VISIBLE else GONE
    }

    /** The last hour as a line, with a faint fill under it. Fewer than two points: nothing yet. */
    private class Spark(context: Context) : View(context) {
        private val dp = resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * dp; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val path = Path()
        private val area = Path()

        var values: List<Float> = emptyList()
            set(v) { if (v != field) { field = v; invalidate() } }

        override fun onDraw(c: Canvas) {
            val v = values
            if (v.size < 2) return
            val w = width.toFloat(); val h = height.toFloat()
            val lo = v.min(); val hi = v.max().coerceAtLeast(lo + 1e-3f)
            val pad = 2f * dp
            path.reset()
            v.forEachIndexed { i, x ->
                val px = i * w / (v.size - 1)
                val py = pad + (h - 2 * pad) * (1f - (x - lo) / (hi - lo))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            area.set(path); area.lineTo(w, h); area.lineTo(0f, h); area.close()
            fill.shader = LinearGradient(0f, 0f, 0f, h, Color.argb(38, 0x81, 0x8c, 0xf8), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawPath(area, fill)
            line.shader = LinearGradient(0f, 0f, w, 0f, alpha(Glass.SKY, 200), alpha(Glass.VIOLET, 200), Shader.TileMode.CLAMP)
            c.drawPath(path, line)
        }
    }

    /** How full: a thin track, the used part in the brand's gradient. */
    private class Meter(context: Context) : View(context) {
        private val dp = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        var used: Float? = null
            set(v) { if (v != field) { field = v; invalidate() } }

        override fun onDraw(c: Canvas) {
            val u = used ?: return
            val h = 5f * dp; val y = height - h - 4f * dp; val w = width.toFloat()
            p.shader = null; p.color = Color.argb(26, 255, 255, 255)
            c.drawRoundRect(0f, y, w, y + h, h / 2, h / 2, p)
            p.shader = LinearGradient(0f, 0f, w, 0f, Glass.SKY, Glass.VIOLET, Shader.TileMode.CLAMP); p.alpha = 210
            c.drawRoundRect(0f, y, w * u.coerceIn(0f, 1f), y + h, h / 2, h / 2, p)
        }
    }

    private companion object {
        fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
    }
}
