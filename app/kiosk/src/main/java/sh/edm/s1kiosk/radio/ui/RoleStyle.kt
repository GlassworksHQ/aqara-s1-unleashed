package sh.edm.s1kiosk.radio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import sh.edm.s1kiosk.radio.RadioMode

/**
 * How the radio roles look in the settings menu's Radio panel: a hue
 * per technology (Zigbee sky blue, Thread violet; Off neutral) and an icon per role (coordinator hub,
 * repeater waves, border router globe, power). Amber stays the accent for the one primary action;
 * green means running, rose an error or a destructive action.
 */
object RoleStyle {
    val ZIGBEE = Color.rgb(56, 189, 248)  // sky-400, the brand's --sky
    val THREAD = Color.rgb(167, 139, 250) // violet-400, the brand's --violet

    fun hue(m: RadioMode): Int = when (m) {
        RadioMode.OFF -> SetupStyle.MU
        RadioMode.ZIGBEE_TCP, RadioMode.ZIGBEE_ROUTER -> ZIGBEE
        RadioMode.THREAD, RadioMode.THREAD_ROUTER -> THREAD
    }

    enum class Glyph { POWER, HUB, REPEATER, GLOBE }

    fun glyph(m: RadioMode): Glyph = when (m) {
        RadioMode.OFF -> Glyph.POWER
        RadioMode.ZIGBEE_TCP -> Glyph.HUB
        RadioMode.ZIGBEE_ROUTER, RadioMode.THREAD_ROUTER -> Glyph.REPEATER
        RadioMode.THREAD -> Glyph.GLOBE
    }

    /** The tile's two lines. */
    fun tileLines(m: RadioMode): Pair<String, String> = when (m) {
        RadioMode.OFF -> "Off" to ""
        RadioMode.ZIGBEE_TCP -> "Zigbee" to "coordinator"
        RadioMode.ZIGBEE_ROUTER -> "Zigbee" to "repeater"
        RadioMode.THREAD -> "Thread" to "border router"
        RadioMode.THREAD_ROUTER -> "Thread" to "repeater"
    }

    /** The panel's header strip, e.g. "ZIGBEE · COORDINATOR". */
    fun header(m: RadioMode): String = when (m) {
        RadioMode.OFF -> "RADIO · OFF"
        RadioMode.ZIGBEE_TCP -> "ZIGBEE · COORDINATOR"
        RadioMode.ZIGBEE_ROUTER -> "ZIGBEE · ROUTER (REPEATER)"
        RadioMode.THREAD -> "THREAD · BORDER ROUTER"
        RadioMode.THREAD_ROUTER -> "THREAD · ROUTER (REPEATER)"
    }

    fun tint(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}

/** A role's icon, stroked in [color]. */
class RoleIcon(context: Context, var glyph: RoleStyle.Glyph, var color: Int, private val sizeDp: Float) : View(context) {
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((sizeDp * dp).toInt(), (sizeDp * dp).toInt())

    override fun onDraw(cv: Canvas) {
        val s = width.toFloat()
        p.color = color; p.strokeWidth = s * 0.075f; p.style = Paint.Style.STROKE
        when (glyph) {
            RoleStyle.Glyph.POWER -> {
                cv.drawArc(RectF(s * .2f, s * .22f, s * .8f, s * .82f), -60f, 300f, false, p)
                cv.drawLine(s * .5f, s * .12f, s * .5f, s * .48f, p)
            }
            RoleStyle.Glyph.HUB -> {
                val cx = s * .5f; val cy = s * .52f
                val pts = listOf(s * .5f to s * .14f, s * .16f to s * .78f, s * .84f to s * .78f)
                pts.forEach { (x, y) -> cv.drawLine(cx, cy, x, y, p) }
                p.style = Paint.Style.FILL
                cv.drawCircle(cx, cy, s * .14f, p)
                pts.forEach { (x, y) -> cv.drawCircle(x, y, s * .09f, p) }
            }
            RoleStyle.Glyph.REPEATER -> {
                p.style = Paint.Style.FILL; cv.drawCircle(s * .5f, s * .5f, s * .1f, p); p.style = Paint.Style.STROKE
                for (r in listOf(RectF(s * .26f, s * .26f, s * .74f, s * .74f), RectF(s * .08f, s * .08f, s * .92f, s * .92f))) {
                    cv.drawArc(r, 140f, 80f, false, p)
                    cv.drawArc(r, -40f, 80f, false, p)
                }
            }
            RoleStyle.Glyph.GLOBE -> {
                cv.drawCircle(s * .5f, s * .5f, s * .36f, p)
                cv.drawOval(RectF(s * .34f, s * .14f, s * .66f, s * .86f), p)
                cv.drawLine(s * .14f, s * .5f, s * .86f, s * .5f, p)
            }
        }
    }
}

/** A filled dot, optionally with a soft halo (status). */
class StatusDot(context: Context, var color: Int, private val sizeDp: Float, private val halo: Boolean) : View(context) {
    private val dp = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) {
        val d = (sizeDp * dp * (if (halo) 2f else 1f)).toInt()
        setMeasuredDimension(d, d)
    }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = sizeDp * dp / 2f
        if (halo) {
            p.color = RoleStyle.tint(color, 0.18f); cv.drawCircle(cx, cy, r * 2f, p)
            p.color = RoleStyle.tint(color, 0.30f); cv.drawCircle(cx, cy, r * 1.45f, p)
        }
        p.color = color; cv.drawCircle(cx, cy, r, p)
    }
}
