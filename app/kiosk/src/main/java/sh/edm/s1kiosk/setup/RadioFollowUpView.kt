package sh.edm.s1kiosk.setup

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.setup.RadioFollowUp.Action
import sh.edm.s1kiosk.setup.RadioFollowUp.Request

/**
 * The setup screen's short follow-up card ([RadioFollowUp.Place.SETUP]) under the radio step's title:
 * the lines, the serial path / URL, a one-line state in the role's health colour, and Join. The QR
 * code goes in the setup screen's own tile ([qr]). The settings menu draws its own (settings/RadioSectionView).
 * Views are made once and updated on every [bind]. Main thread only.
 */
class RadioFollowUpView(context: Context) : LinearLayout(context) {

    /** Runs [Request] against RadioController; `done` gets the answer (null = accepted). Join: only on failure. */
    var onRun: ((Request, (String?) -> Unit) -> Unit)? = null

    val flow = RadioFollowUp.Flow()
    private var facts: RadioFollowUp.Facts? = null
    private var card: RadioFollowUp.Card? = null
    private var shownContent: List<Any?>? = null

    private val s = SetupStyle(context)

    private val lines = LinearLayout(context).apply { orientation = VERTICAL }
    private val kv = LinearLayout(context).apply { orientation = VERTICAL }
    private val state = s.text(14.5f, SetupStyle.AMBER, SetupStyle.REGULAR).apply {
        setLineSpacing(0f, 1.2f); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val actionRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    // Not amber: in the setup screen, Continue is the one accent button.
    private val actionButtons = Action.entries.associateWith { a ->
        s.button(a.label, accent = false).apply { setOnClickListener { handle(Request.Act(a)) } }
    }
    private val result = s.text(14f, SetupStyle.MU, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f) }
    private val note = s.text(13.5f, SetupStyle.FA, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.25f) }

    init {
        orientation = VERTICAL
        addView(lines, LayoutParams(-1, -2))
        addView(kv, LayoutParams(-1, -2).apply { topMargin = s.px(10f) })
        addView(state, LayoutParams(-1, -2).apply { topMargin = s.px(12f) })
        addView(actionRow, LayoutParams(-1, -2).apply { topMargin = s.px(12f) })
        addView(result, LayoutParams(-1, -2).apply { topMargin = s.px(8f) })
        addView(note, LayoutParams(-1, -2).apply { topMargin = s.px(10f) })
    }

    /** The card's QR (shown in the setup screen's own tile). */
    val qr: RadioFollowUp.Qr? get() = card?.qr

    fun bind(f: RadioFollowUp.Facts) {
        facts = f
        flow.tick(f.status)
        card = RadioFollowUp.card(f, RadioFollowUp.Place.SETUP)
        rebind()
    }

    private fun rebind() {
        val c = card ?: return
        val f = facts ?: return
        val content = listOf(c.lines, c.kv, c.note)
        if (content != shownContent) {
            shownContent = content
            lines.removeAllViews()
            c.lines.forEachIndexed { i, l ->
                lines.addView(s.text(15f, SetupStyle.MU, SetupStyle.REGULAR).apply { text = l; setLineSpacing(0f, 1.25f) },
                    LayoutParams(-1, -2).apply { if (i > 0) topMargin = s.px(6f) })
            }
            kv.removeAllViews()
            c.kv.forEachIndexed { i, (k, v, big) ->
                // plain label + value (calm, and it fits without scrolling); the URL, to read and type, gets a box
                kv.addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    if (big) {
                        background = s.glass(14f)
                        setPadding(s.px(14f), s.px(9f), s.px(14f), s.px(10f))
                    }
                    addView(s.label(k))
                    addView(s.text(if (big) 20f else 16.5f, SetupStyle.TX, if (big) SetupStyle.LIGHT else SetupStyle.REGULAR).apply {
                        text = v
                        if (!big) { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
                    }, LayoutParams(-1, -2).apply { topMargin = s.px(3f) })
                }, LayoutParams(-1, -2).apply { if (i > 0) topMargin = s.px(8f) })
            }
            kv.visibility = if (c.kv.isEmpty()) View.GONE else View.VISIBLE
            note.text = c.note.orEmpty()
            note.visibility = if (c.note == null) View.GONE else View.VISIBLE
        }

        val stateName = if (c.mode == RadioMode.THREAD_ROUTER) "Thread network" else "Status"
        state.text = "$stateName: ${c.state.orEmpty()}"
        state.setTextColor(healthColor(RadioFollowUp.health(f)))
        state.visibility = if (c.state == null) View.GONE else View.VISIBLE

        val running = flow.running
        if (actionRow.childCount != c.actions.size || c.actions.withIndex().any { (i, a) -> actionRow.getChildAt(i) !== actionButtons[a] }) {
            actionRow.removeAllViews()
            c.actions.forEachIndexed { i, a ->
                actionRow.addView(actionButtons.getValue(a), LayoutParams(-2, -2).apply { if (i > 0) marginStart = s.px(10f) })
            }
        }
        actionRow.visibility = if (c.actions.isEmpty()) View.GONE else View.VISIBLE
        c.actions.forEach { a ->
            val b = actionButtons.getValue(a)
            val busy = running == Request.Act(a)
            b.text = if (busy) a.busyLabel else a.label
            b.isEnabled = running == null
            b.alpha = if (running != null && !busy) 0.5f else 1f
        }
        result.text = flow.result.orEmpty()
        result.visibility = if (flow.result == null) View.GONE else View.VISIBLE
    }

    private fun handle(r: Request) {
        // The setup card's only button (Join) needs no confirmation.
        val now = flow.press(r, facts) ?: run { flow.cancel(); return }
        val f = facts ?: return
        flow.started(now, f.status)
        val host = onRun
        if (host == null) { flow.finish("Not available here."); rebind(); return }
        host(now) { msg ->
            if (now is Request.Act && msg == null) return@host // ends with the status (Flow.tick)
            flow.finish(msg)
            rebind()
        }
        rebind()
    }

    companion object {
        /** The role's health as a colour: green working, amber on its way, rose wrong, grey off. */
        fun healthColor(h: RadioFollowUp.Health): Int = when (h) {
            RadioFollowUp.Health.RUNNING, RadioFollowUp.Health.JOINED -> SetupStyle.GREEN
            RadioFollowUp.Health.WAITING, RadioFollowUp.Health.STARTING -> SetupStyle.AMBER
            RadioFollowUp.Health.ERROR -> SetupStyle.ROSE
            RadioFollowUp.Health.OFF -> SetupStyle.MU
        }
    }
}

/** A ScrollView no taller than [maxPx] (the setup screen's follow-up card). */
class BoundedScroll(context: Context, var maxPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (mode == MeasureSpec.UNSPECIFIED) maxPx else minOf(size, maxPx)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

/** The setup screen's look (tablet-home-card.compact.js tokens), for the radio views. */
class SetupStyle(val context: Context) {
    val dp = context.resources.displayMetrics.density
    fun px(v: Float) = (v * dp).toInt()

    fun text(sp: Float, color: Int, tf: Typeface) = TextView(context).apply { textSize = sp; setTextColor(color); typeface = tf }

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
        typeface = MEDIUM
        setPadding(px(18f), px(11f), px(18f), px(11f))
        isClickable = true
        isFocusable = true
        restyle(this, accent)
    }

    fun restyle(b: TextView, accent: Boolean) {
        b.setTextColor(if (accent) BG else TX)
        b.background = GradientDrawable().apply {
            cornerRadius = 16 * dp
            if (accent) setColor(AMBER) else { setColor(S2); setStroke(px(1f).coerceAtLeast(1), BD) }
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
        val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
