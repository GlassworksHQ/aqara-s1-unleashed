package sh.edm.s1kiosk.setup

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.setup.RadioFollowUp.Action
import sh.edm.s1kiosk.setup.RadioFollowUp.Request

/**
 * Draws a [RadioFollowUp.Card]: in the setup screen's radio step (no title, the QR in the screen's own
 * tile: [ownQr] false) and in the settings menu's Radio section. Views are made once and updated on
 * every [bind] (every couple of seconds while shown), so the dataset field keeps its focus and text.
 * Main thread only.
 */
class RadioFollowUpView(
    context: Context,
    private val place: RadioFollowUp.Place,
    private val ownQr: Boolean = place == RadioFollowUp.Place.SETTINGS,
) : LinearLayout(context) {

    /** Runs [Request] against RadioController; `done` gets the answer (null = accepted). Join/Leave: only on failure. */
    var onRun: ((Request, (String?) -> Unit) -> Unit)? = null
    /** `Thread dataset` from the panel: null = handed over, else why not. */
    var onDataset: ((String) -> String?)? = null
    /** A field got focus (the settings menu scrolls it above the keyboard). */
    var onFieldFocus: ((View) -> Unit)? = null

    val flow = RadioFollowUp.Flow()
    private var facts: RadioFollowUp.Facts? = null
    private var card: RadioFollowUp.Card? = null
    private var shownContent: List<Any?>? = null
    private var datasetOpen = false
    private var datasetResult: Pair<Boolean, String>? = null

    private val s = SetupStyle(context)

    private val qrImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private var qrFor: String? = null
    private val qrCaption = s.text(12.5f, SetupStyle.MU, SetupStyle.REGULAR).apply { gravity = Gravity.CENTER }
    private val qrBox = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = 18 * s.dp; setColor(Color.WHITE) }
            setPadding(s.px(10f), s.px(10f), s.px(10f), s.px(10f))
            addView(qrImage, FrameLayout.LayoutParams(-1, -1))
        }, LayoutParams(s.px(QR_DP), s.px(QR_DP)))
        addView(qrCaption, LayoutParams(s.px(QR_DP + 10f), -2).apply { topMargin = s.px(8f) })
    }
    private val title = s.text(22f, SetupStyle.TX, SetupStyle.LIGHT).apply { letterSpacing = -0.01f }
    private val lines = LinearLayout(context).apply { orientation = VERTICAL }
    private val kv = LinearLayout(context).apply { orientation = VERTICAL }
    private val stateLabel = s.label("Status")
    private val stateText = s.text(15f, SetupStyle.TX, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f) }
    private val stateBox = LinearLayout(context).apply {
        orientation = VERTICAL
        background = s.glass(14f)
        setPadding(s.px(14f), s.px(10f), s.px(14f), s.px(11f))
        addView(stateLabel)
        addView(stateText, LayoutParams(-1, -2).apply { topMargin = s.px(3f) })
    }
    private val stackChips = ZigbeeStack.entries.associateWith { z ->
        s.button(RadioFollowUp.stackTitle(z), accent = false).apply { textSize = 14.5f; setOnClickListener { handle(Request.Stack(z)) } }
    }
    private val stackRow = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(s.label("Zigbee stack"))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            stackChips.values.forEachIndexed { i, b -> addView(b, LayoutParams(-2, -2).apply { if (i > 0) marginStart = s.px(8f) }) }
        }, LayoutParams(-1, -2).apply { topMargin = s.px(6f) })
    }

    // Thread dataset, write-only, behind "Advanced" (collapsed by default).
    private val datasetToggle = s.text(14.5f, SetupStyle.MU, SetupStyle.MEDIUM).apply {
        isClickable = true
        setPadding(0, s.px(6f), 0, s.px(6f))
        setOnClickListener { datasetOpen = !datasetOpen; rebind() }
    }
    private val datasetEdit = EditText(context).apply {
        textSize = 15f
        setTextColor(SetupStyle.TX)
        setHintTextColor(SetupStyle.FA)
        hint = "Paste the active dataset (hex)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = SetupStyle.REGULAR // password fields default to monospace
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setSingleLine()
        background = s.glass(14f)
        setPadding(s.px(14f), s.px(10f), s.px(14f), s.px(10f))
        setOnFocusChangeListener { v, has -> if (has) onFieldFocus?.invoke(v) }
        setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { submitDataset(); true } else false }
    }
    private val datasetSet = s.button("Set dataset", accent = false).apply { setOnClickListener { submitDataset() } }
    private val datasetMsg = s.text(13.5f, SetupStyle.MU, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f) }
    private val datasetBody = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(s.text(13.5f, SetupStyle.MU, SetupStyle.REGULAR).apply {
            text = RadioFollowUp.DATASET_HINT + " " + RadioFollowUp.DATASET_KEY
            setLineSpacing(0f, 1.2f)
        })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(datasetEdit, LayoutParams(0, -2, 1f).apply { marginEnd = s.px(10f) })
            addView(datasetSet, LayoutParams(-2, -2))
        }, LayoutParams(-1, -2).apply { topMargin = s.px(8f) })
        addView(datasetMsg, LayoutParams(-1, -2).apply { topMargin = s.px(6f) })
    }
    private val datasetBox = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(datasetToggle)
        addView(datasetBody, LayoutParams(-1, -2).apply { topMargin = s.px(4f) })
    }

    private val actionRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val actionButtons = Action.entries.associateWith { a ->
        s.button(a.label, accent = a == Action.ROUTER_JOIN).apply { setOnClickListener { handle(Request.Act(a)) } }
    }
    private val confirm = ConfirmBox(s) { ok -> if (ok) flow.confirm()?.let(::run) else flow.cancel(); rebind() }
    private val result = s.text(14f, SetupStyle.MU, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f) }
    private val note = s.text(13.5f, SetupStyle.FA, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.25f) }

    private val textColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(title, LayoutParams(-1, -2).apply { bottomMargin = s.px(8f) })
        addView(lines, LayoutParams(-1, -2))
        addView(kv, LayoutParams(-1, -2).apply { topMargin = s.px(10f) })
    }

    init {
        orientation = VERTICAL
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(qrBox, LayoutParams(-2, -2).apply { marginEnd = s.px(22f) })
            addView(textColumn, LayoutParams(0, -2, 1f))
        }, LayoutParams(-1, -2))
        addView(stateBox, LayoutParams(-1, -2).apply { topMargin = s.px(12f) })
        addView(stackRow, LayoutParams(-1, -2).apply { topMargin = s.px(12f) })
        addView(datasetBox, LayoutParams(-1, -2).apply { topMargin = s.px(10f) })
        addView(actionRow, LayoutParams(-1, -2).apply { topMargin = s.px(12f) })
        addView(confirm.view, LayoutParams(-1, -2).apply { topMargin = s.px(10f) })
        addView(result, LayoutParams(-1, -2).apply { topMargin = s.px(8f) })
        addView(note, LayoutParams(-1, -2).apply { topMargin = s.px(10f) })
        datasetSet.isEnabled = true
    }

    /** Previews (RadioMockups): open or close the "Advanced" dataset disclosure. */
    internal fun previewDatasetOpen(open: Boolean) { datasetOpen = open; rebind() }

    /** The card's QR (for the setup screen's own tile when [ownQr] is false). */
    val qr: RadioFollowUp.Qr? get() = card?.qr

    fun bind(f: RadioFollowUp.Facts) {
        facts = f
        flow.tick(f.status)
        val c = RadioFollowUp.card(f, place)
        if (card?.mode != c.mode) { datasetOpen = false; datasetResult = null; datasetEdit.setText("") }
        card = c
        rebind()
    }

    private fun rebind() {
        val c = card ?: return
        val f = facts ?: return
        // Static content: only rebuilt when it changed.
        val content = listOf(c.title, c.lines, c.kv, c.qr, c.note)
        if (content != shownContent) {
            shownContent = content
            title.text = c.title
            title.visibility = if (place == RadioFollowUp.Place.SETTINGS) View.VISIBLE else View.GONE
            lines.removeAllViews()
            c.lines.forEachIndexed { i, l ->
                lines.addView(s.text(15f, SetupStyle.MU, SetupStyle.REGULAR).apply { text = l; setLineSpacing(0f, 1.25f) },
                    LayoutParams(-1, -2).apply { if (i > 0) topMargin = s.px(6f) })
            }
            kv.removeAllViews()
            c.kv.forEachIndexed { i, (k, v, big) ->
                kv.addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    background = s.glass(14f)
                    setPadding(s.px(14f), s.px(9f), s.px(14f), s.px(10f))
                    addView(s.label(k))
                    // big (the OTBR URL, to read and type): smaller in the setup screen's narrower column
                    val size = if (!big) 16.5f else if (place == RadioFollowUp.Place.SETTINGS) 26f else 20f
                    addView(s.text(size, SetupStyle.TX, if (big) SetupStyle.LIGHT else SetupStyle.REGULAR).apply {
                        text = v
                        setTextIsSelectable(false)
                        if (!big) { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
                    }, LayoutParams(-1, -2).apply { topMargin = s.px(3f) })
                }, LayoutParams(-1, -2).apply { if (i > 0) topMargin = s.px(8f) })
            }
            kv.visibility = if (c.kv.isEmpty()) View.GONE else View.VISIBLE
            val q = c.qr
            if (ownQr && q != null) {
                if (qrFor != q.url) { qrImage.setImageBitmap(SetupOverlay.qrBitmap(q.url, s.px(QR_DP))); qrFor = q.url }
                qrCaption.text = q.caption
                qrBox.visibility = View.VISIBLE
            } else qrBox.visibility = View.GONE
            note.text = c.note.orEmpty()
            note.visibility = if (c.note == null) View.GONE else View.VISIBLE
        }

        stateText.text = c.state.orEmpty()
        stateBox.visibility = if (c.state == null) View.GONE else View.VISIBLE
        stateLabel.text = if (c.mode == RadioMode.THREAD_ROUTER) "Thread network" else "Status"

        val running = flow.running
        stackRow.visibility = if (c.stackChoice) View.VISIBLE else View.GONE
        stackChips.forEach { (z, b) ->
            val on = f.stack == z
            s.restyle(b, accent = on)
            b.isEnabled = running == null
            b.alpha = if (running != null && !on) 0.5f else 1f
        }

        datasetBox.visibility = if (c.datasetField) View.VISIBLE else View.GONE
        datasetToggle.text = (if (datasetOpen) "▾  " else "▸  ") + RadioFollowUp.DATASET_DISCLOSURE
        datasetBody.visibility = if (datasetOpen) View.VISIBLE else View.GONE
        datasetResult.let { r ->
            datasetMsg.visibility = if (r == null) View.GONE else View.VISIBLE
            if (r != null) { datasetMsg.text = r.second; datasetMsg.setTextColor(if (r.first) SetupStyle.GREEN else SetupStyle.ROSE) }
        }

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

        confirm.bind(flow.asking)
        val msg = when {
            running is Request.Stack -> "Switching the Zigbee stack…"
            else -> flow.result
        }
        result.text = msg.orEmpty()
        result.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun handle(r: Request) {
        flow.press(r, facts)?.let(::run)
        rebind()
    }

    private fun run(r: Request) {
        val f = facts ?: return
        flow.started(r, f.status)
        val host = onRun
        if (host == null) { flow.finish("Not available here."); rebind(); return }
        host(r) { msg ->
            if (r is Request.Act && msg == null) return@host // ends with the status (Flow.tick)
            flow.finish(msg)
            rebind()
        }
    }

    private fun submitDataset() {
        val text = datasetEdit.text?.toString().orEmpty()
        if (text.isBlank()) return
        val err = onDataset?.invoke(text) ?: if (onDataset == null) "Not available here." else null
        datasetResult = if (err == null) {
            datasetEdit.setText("") // write-only: never kept or shown again
            true to "Dataset handed to the radio. The Thread network line shows when the panel has joined."
        } else false to err
        rebind()
    }

    private companion object {
        const val QR_DP = 150f
    }
}

/** The settings menu's mode picker: the setup step's options, the active one tagged, Switch with confirmation. */
class RadioModePicker(context: Context) : LinearLayout(context) {

    var onRun: ((Request, (String?) -> Unit) -> Unit)? = null

    val flow = RadioFollowUp.Flow()
    private val s = SetupStyle(context)
    private var facts: RadioFollowUp.Facts? = null
    private var selected: RadioMode? = null

    private inner class Row(val option: RadioStep.Option) {
        val title = s.text(16f, SetupStyle.TX, SetupStyle.MEDIUM).apply { text = option.title }
        val badge = s.label("")
        val line = s.text(13.5f, SetupStyle.MU, SetupStyle.REGULAR).apply { text = option.line }
        val view = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(s.px(14f), s.px(10f), s.px(14f), s.px(11f))
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LayoutParams(0, -2, 1f))
                addView(badge, LayoutParams(-2, -2).apply { marginStart = s.px(8f) })
            })
            addView(line, LayoutParams(-1, -2).apply { topMargin = s.px(3f) })
            isClickable = true
            contentDescription = option.title
            setOnClickListener { if (flow.running == null) { selected = option.mode; flow.cancel(); rebind() } }
        }
    }

    private val rows = RadioStep.OPTIONS.map { Row(it) }
    private val status = s.text(14f, SetupStyle.MU, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f) }
    private val apply = s.button("", accent = true).apply {
        setOnClickListener { val m = selected ?: return@setOnClickListener; flow.press(Request.Mode(m), facts)?.let(::run); rebind() }
    }
    private val confirm = ConfirmBox(s) { ok -> if (ok) flow.confirm()?.let(::run) else flow.cancel(); rebind() }
    private val result = s.text(14f, SetupStyle.ROSE, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f) }

    init {
        orientation = VERTICAL
        rows.forEachIndexed { i, r -> addView(r.view, LayoutParams(-1, -2).apply { if (i > 0) topMargin = s.px(6f) }) }
        addView(status, LayoutParams(-1, -2).apply { topMargin = s.px(10f) })
        addView(apply, LayoutParams(-2, -2).apply { topMargin = s.px(10f) })
        addView(confirm.view, LayoutParams(-1, -2).apply { topMargin = s.px(10f) })
        addView(result, LayoutParams(-1, -2).apply { topMargin = s.px(8f) })
    }

    /** The highlighted (not yet applied) mode, for previews. */
    fun select(m: RadioMode) { selected = m; rebind() }

    fun bind(f: RadioFollowUp.Facts) {
        val changed = facts?.mode != f.mode
        facts = f
        if (selected == null || (changed && flow.running == null)) selected = f.mode
        rebind()
    }

    private fun rebind() {
        val f = facts ?: return
        val running = flow.running
        rows.forEach { r ->
            val active = r.option.mode == f.mode
            val sel = r.option.mode == selected
            r.view.background = GradientDrawable().apply {
                cornerRadius = 16 * s.dp
                setColor(if (sel) SetupStyle.S2 else SetupStyle.S1)
                setStroke(s.px(if (sel) 2f else 1f).coerceAtLeast(1), if (sel) SetupStyle.AMBER else SetupStyle.BD)
            }
            r.title.setTextColor(if (sel || active) SetupStyle.TX else SetupStyle.MU)
            r.badge.text = when {
                active -> "Active"
                r.option.experimental -> "Experimental"
                else -> ""
            }
            r.badge.setTextColor(if (active) SetupStyle.GREEN else SetupStyle.AMBER)
            r.view.alpha = if (running != null && !sel) 0.6f else 1f
        }
        status.text = "Status: ${f.status}"
        val m = selected ?: f.mode
        apply.visibility = if (m != f.mode || running != null) View.VISIBLE else View.GONE
        apply.text = if (running != null) "Switching…" else "Switch to ${RadioStep.OPTIONS.firstOrNull { it.mode == m }?.title ?: m.label}"
        apply.isEnabled = running == null && flow.asking == null
        apply.alpha = if (apply.isEnabled) 1f else 0.5f
        confirm.bind(flow.asking)
        result.text = flow.result.orEmpty()
        result.visibility = if (flow.result == null) View.GONE else View.VISIBLE
    }

    private fun run(r: Request) {
        val f = facts ?: return
        flow.started(r, f.status)
        val host = onRun
        if (host == null) { flow.finish("Not available here."); rebind(); return }
        host(r) { msg -> flow.finish(msg); if (msg != null) selected = facts?.mode; rebind() }
        rebind()
    }
}

/** An inline confirmation: the question, the action (amber outline) and Cancel. */
internal class ConfirmBox(private val s: SetupStyle, private val answer: (Boolean) -> Unit) {
    private val text = s.text(15f, SetupStyle.TX, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.25f) }
    private val yes = s.button("", accent = true).apply { setOnClickListener { answer(true) } }
    private val no = s.button("Cancel", accent = false).apply { setOnClickListener { answer(false) } }
    val view = LinearLayout(s.context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 16 * s.dp
            setColor(SetupStyle.S2)
            setStroke(s.px(1.5f).coerceAtLeast(1), SetupStyle.AMBER)
        }
        setPadding(s.px(16f), s.px(13f), s.px(16f), s.px(14f))
        addView(text)
        addView(LinearLayout(s.context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(yes, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.px(10f) })
            addView(no, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = s.px(10f) })
        visibility = View.GONE
    }

    fun bind(a: RadioFollowUp.Ask?) {
        view.visibility = if (a == null) View.GONE else View.VISIBLE
        if (a != null) { text.text = a.text; yes.text = a.button }
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

    fun glass(radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(S1)
        setStroke(px(1f).coerceAtLeast(1), BD)
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
        val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
