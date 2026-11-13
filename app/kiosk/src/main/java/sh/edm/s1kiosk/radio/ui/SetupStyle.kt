package sh.edm.s1kiosk.radio.ui

import sh.edm.s1kiosk.ui.Inter
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

/** The role's health as a colour: green working, amber on its way, rose wrong, grey off. */
fun healthColor(h: RadioFollowUp.Health): Int = when (h) {
    RadioFollowUp.Health.RUNNING, RadioFollowUp.Health.JOINED -> SetupStyle.GREEN
    RadioFollowUp.Health.WAITING, RadioFollowUp.Health.STARTING -> SetupStyle.AMBER
    RadioFollowUp.Health.ERROR -> SetupStyle.ROSE
    RadioFollowUp.Health.OFF -> SetupStyle.MU
}

/** The radio views' look (tablet-home-card.compact.js tokens). */
class SetupStyle(val context: Context) {
    val dp = context.resources.displayMetrics.density
    fun px(v: Float) = (v * dp).toInt()

    fun text(sp: Float, color: Int, weight: Int) = TextView(context).apply { textSize = sp; setTextColor(color); typeface = Inter.typeface(context, weight, sp) }

    fun label(str: String) = text(11.5f, FA, MEDIUM).apply { letterSpacing = 0.14f; isAllCaps = true; text = str }

    fun glass(radiusDp: Float, fill: Int = S1, stroke: Int = BD, strokeDp: Float = 1f) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(fill)
        setStroke(px(strokeDp).coerceAtLeast(1), stroke)
    }

    fun button(str: String, accent: Boolean) = TextView(context).apply {
        text = str
        textSize = 15.5f
        gravity = Gravity.CENTER
        typeface = Inter.typeface(context, MEDIUM, 15.5f)
        setPadding(px(18f), px(11f), px(18f), px(11f))
        isClickable = true
        isFocusable = true
        restyle(this, accent)
    }

    /** The one white button, or a dark pill with a hairline (#63: amber is status, never a button). */
    fun restyle(b: TextView, accent: Boolean) {
        b.setTextColor(if (accent) BG else TX)
        b.background = GradientDrawable().apply {
            cornerRadius = 999f
            if (accent) setColor(TX) else { setColor(S2); setStroke(px(1f).coerceAtLeast(1), BD) }
        }
    }

    /** A rounded tag tinted with [c] (status pills). */
    fun pill(str: String, c: Int, small: Boolean = false) = text(if (small) 12.5f else 14f, c, MEDIUM).apply {
        text = str
        setPadding(px(if (small) 9f else 12f), px(if (small) 3f else 5f), px(if (small) 9f else 12f), px(if (small) 3f else 5f))
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(RoleStyle.tint(c, 0.12f)); setStroke(px(1f).coerceAtLeast(1), RoleStyle.tint(c, 0.40f))
        }
    }

    companion object {
        val BG = Color.parseColor("#0a0b0f")
        val S1 = Color.argb((0.045f * 255).toInt(), 255, 255, 255)
        val S2 = Color.argb((0.08f * 255).toInt(), 255, 255, 255)
        val BD = Color.argb((0.07f * 255).toInt(), 255, 255, 255)
        val TX = Color.parseColor("#f4f4f5")
        val MU = Color.parseColor("#a1a1aa")
        val FA = Color.parseColor("#63636b")
        val AMBER = Color.parseColor("#fbbf24")
        val GREEN = Color.parseColor("#34d399")
        val ROSE = Color.parseColor("#fb7185")
        /** The dialog's surface (neutral, opaque). */
        val SHEET = Color.parseColor("#17181e")
        val LIGHT = Inter.LIGHT
        val REGULAR = Inter.REGULAR
        val MEDIUM = Inter.MEDIUM
    }
}
