package sh.edm.s1kiosk.manage

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.manage.PanelCode
import sh.edm.s1kiosk.ui.Inter

/**
 * The Manage page's code on the panel's screen (#33): what it's for, the six digits, and Close. Drawn
 * by the service over everything (an overlay window, which the app may draw: SYSTEM_ALERT_WINDOW), so
 * it shows whatever the activity is doing. Main thread.
 */
class CodeOverlay(private val context: Context, private val onClose: () -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show(shown: PanelCode.Shown?) {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        if (shown == null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(S1Manage.TAG, "can't show the Manage code: the app may not draw over other apps")
            return
        }
        val v = build(context, shown, onClose)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        runCatching { wm.addView(v, lp); view = v }.onFailure { Log.w(S1Manage.TAG, "can't show the Manage code", it) }
    }

    companion object {
        /** The overlay's view, also for the mock-ups. */
        fun build(context: Context, shown: PanelCode.Shown, onClose: () -> Unit): View {
            val dp = context.resources.displayMetrics.density
            fun text(s: String, sp: Float, weight: Int, color: Int) = TextView(context).apply {
                text = s
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                typeface = Inter.typeface(context, weight, sp)
                setTextColor(color)
                gravity = Gravity.CENTER
            }
            val wrap = { LinearLayout.LayoutParams(-2, -2) }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(text("MANAGE THIS PANEL", 11f, Inter.MEDIUM, FAINT).apply { letterSpacing = 0.14f }, wrap())
                addView(text(shown.label + ".", 26f, Inter.LIGHT, FG).apply { setPadding(0, (10 * dp).toInt(), 0, (24 * dp).toInt()) }, wrap())
                addView(text(shown.code.chunked(3).joinToString("  "), 64f, Inter.LIGHT, FG).apply { letterSpacing = 0.12f }, wrap())
                addView(text("Type it in the browser. It works once, for 5 minutes.", 15f, Inter.REGULAR, MUTED).apply {
                    setPadding(0, (22 * dp).toInt(), 0, (26 * dp).toInt())
                }, wrap())
                addView(text("Close", 15f, Inter.MEDIUM, FG).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = 999 * dp; setColor(0x14FFFFFF); setStroke((1 * dp).toInt(), 0x1FFFFFFF)
                    }
                    setPadding((26 * dp).toInt(), (11 * dp).toInt(), (26 * dp).toInt(), (11 * dp).toInt())
                    isClickable = true
                    setOnClickListener { onClose() }
                }, LinearLayout.LayoutParams(-2, -2))
            }
            return FrameLayout(context).apply {
                // Flat night, fully opaque: nothing behind (the night clock) may show through the digits.
                setBackgroundColor(0xFF0A0B0F.toInt())
                addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
        }

        private val FG = Color.parseColor("#F4F4F5")
        private val MUTED = Color.parseColor("#A1A1AA")
        private val FAINT = Color.parseColor("#6B6B74")
    }
}
