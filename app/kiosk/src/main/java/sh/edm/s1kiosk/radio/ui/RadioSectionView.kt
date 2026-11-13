package sh.edm.s1kiosk.radio.ui

import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.ui.QrCode
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.radio.ui.RadioFollowUp.Action
import sh.edm.s1kiosk.radio.ui.RadioFollowUp.Health
import sh.edm.s1kiosk.radio.ui.RadioFollowUp.Request
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Wrap

/**
 * The settings menu's Radio section: the modes as chips ([tiles], #63: the one in effect lit; tapping
 * another asks to switch) and under it [panel] (the owner's design "B"): a header strip in the role's colour with a status pill, then what
 * to do next (RadioFollowUp.Card: QR code, settings, buttons, the dataset field behind Advanced, "How does
 * this work?"). Tapping another tile previews that role with "Turn on …"; switching, leaving and the stack
 * change ask first in a centred dialog ([onAsk], drawn by [RadioDialog]). Also shown: Off as an empty
 * state, "Starting …" while a role starts, and a refused switch in plain words.
 *
 * Every request goes through [onRun] to the same RadioController calls as the Home Assistant entities.
 * Views are made once and updated on every [bind] (every couple of seconds while the menu is open), so
 * the dataset field keeps its focus and text. Main thread only.
 */
class RadioSectionView(private val context: Context) {

    var onRun: ((Request, (String?) -> Unit) -> Unit)? = null
    /** `Thread dataset` typed or pasted on the panel: null = handed over, else why not. */
    var onDataset: ((String) -> String?)? = null
    var onFieldFocus: ((View) -> Unit)? = null
    /** Show [RadioFollowUp.Ask] and call back with the answer (true = go ahead). */
    var onAsk: ((RadioFollowUp.Ask, (Boolean) -> Unit) -> Unit)? = null

    val flow = RadioFollowUp.Flow()
    private val s = SetupStyle(context)
    private var facts: RadioFollowUp.Facts? = null
    /** A tile tapped that isn't the active role: the panel previews it. */
    private var selected: RadioMode? = null
    /** The last mode switch that was refused, and why (RadioController's status). */
    private var refused: Pair<RadioMode, String>? = null
    private var moreOpen = false
    private var datasetOpen = false
    private var datasetResult: Pair<Boolean, String>? = null

    // ------------------------------------------------------------------ chips

    /** Off first, then the modes as setup's Panes step names them. */
    private val chipList = listOf(RadioMode.OFF, RadioMode.ZIGBEE_TCP, RadioMode.THREAD, RadioMode.ZIGBEE_ROUTER, RadioMode.THREAD_ROUTER)
        .mapNotNull { m -> RadioStep.OPTIONS.firstOrNull { it.mode == m } }
        .map { o -> o.mode to Glass(context).chip(if (o.mode == RadioMode.OFF) "Off" else o.title).apply { setOnClickListener { tap(o.mode) } } }

    /** The modes as chips (#63): the one in effect is lit; tapping another asks to switch. */
    val tiles = Wrap(context, s.px(10f), s.px(10f)).apply { chipList.forEach { (_, c) -> addView(c) } }

    private fun bindChips(f: RadioFollowUp.Facts) {
        val running = (flow.running as? Request.Mode)?.mode
        chipList.forEach { (m, c) ->
            c.chosen = m == (running ?: f.mode)
            c.isEnabled = flow.running == null
        }
    }

    // ------------------------------------------------------------------ panel: header strip

    private val headerText = s.text(13f, SetupStyle.MU, SetupStyle.MEDIUM).apply { letterSpacing = 0.12f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val headerPill = s.pill("", SetupStyle.GREEN)
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(s.px(20f), s.px(12f), s.px(16f), s.px(12f))
        addView(headerText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = s.px(10f) })
        addView(headerPill)
    }

    // ------------------------------------------------------------------ panel: Off (empty state)

    private val offLeave = s.button(Action.THREAD_LEAVE.label, accent = false).apply { danger(this); setOnClickListener { handle(Request.Act(Action.THREAD_LEAVE)) } }
    private val offNote = s.text(14f, SetupStyle.FA, SetupStyle.REGULAR).apply { gravity = Gravity.CENTER }
    private val offBlock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(s.px(24f), s.px(22f), s.px(24f), s.px(22f))
        addView(s.text(26f, SetupStyle.TX, SetupStyle.REGULAR).apply { text = "The radio is off"; gravity = Gravity.CENTER })
        addView(s.text(17f, SetupStyle.MU, SetupStyle.REGULAR).apply { text = "Want to turn it on? Tap a role above."; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = s.px(4f) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(legend(RoleStyle.ZIGBEE, "Zigbee"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.px(22f) })
            addView(legend(RoleStyle.THREAD, "Thread"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.px(22f) })
            addView(s.text(14f, SetupStyle.FA, SetupStyle.MEDIUM).apply { text = "β  experimental" })
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = s.px(14f) })
        addView(offNote, LinearLayout.LayoutParams(-2, -2).apply { topMargin = s.px(16f) })
        addView(offLeave, LinearLayout.LayoutParams(-2, -2).apply { topMargin = s.px(10f) })
    }

    // ------------------------------------------------------------------ panel: preview of a tapped role

    private val previewPitch = s.text(19f, SetupStyle.TX, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f) }
    private val previewSub = s.text(15.5f, SetupStyle.MU, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f) }
    private val previewGo = s.button("", accent = true).apply { setOnClickListener { selected?.let { handle(Request.Mode(it)) } } }
    private val previewBlock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(s.px(20f), s.px(16f), s.px(20f), s.px(18f))
        addView(previewPitch)
        addView(previewSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(6f) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(previewGo, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.px(10f) })
            addView(s.button("Cancel", accent = false).apply { setOnClickListener { selected = null; rebind() } })
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = s.px(16f) })
    }

    // ------------------------------------------------------------------ panel: starting / refused

    private val startTitle = s.text(20f, SetupStyle.TX, SetupStyle.REGULAR)
    private val startSub = s.text(15.5f, SetupStyle.MU, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f); maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
    private val startBar = Indeterminate(context)
    private val startBlock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(s.px(20f), s.px(16f), s.px(20f), s.px(20f))
        addView(startTitle)
        addView(startSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(4f) })
        addView(startBar, LinearLayout.LayoutParams(-1, s.px(6f)).apply { topMargin = s.px(16f) })
    }

    private val refusedText = s.text(18f, SetupStyle.TX, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.25f) }
    private val stillDot = StatusDot(context, SetupStyle.GREEN, 9f, halo = false)
    private val stillText = s.text(15f, SetupStyle.MU, SetupStyle.REGULAR)
    private val refusedBlock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(s.px(20f), s.px(16f), s.px(20f), s.px(18f))
        addView(refusedText)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stillDot, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.px(8f) })
            addView(stillText)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(10f) })
        addView(s.button("Dismiss", accent = false).apply { setOnClickListener { refused = null; rebind() } },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = s.px(14f) })
    }

    // ------------------------------------------------------------------ panel: the active role

    private val qrImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private var qrFor: String? = null
    private val qrBox = FrameLayout(context).apply {
        background = GradientDrawable().apply { cornerRadius = 18 * s.dp; setColor(Color.WHITE) }
        setPadding(s.px(10f), s.px(10f), s.px(10f), s.px(10f))
        addView(qrImage, FrameLayout.LayoutParams(-1, -1))
    }
    private val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var infoFor: List<Any?>? = null
    private val stateText = s.text(15.5f, SetupStyle.TX, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f) }
    private val badge = s.pill("", SetupStyle.MU)
    private val stateRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(stateText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = s.px(10f) })
        addView(badge)
    }
    private val stackSegments = ZigbeeStack.entries.associateWith { z ->
        s.text(14.5f, SetupStyle.MU, SetupStyle.MEDIUM).apply {
            text = RadioFollowUp.stackTitle(z)
            setPadding(s.px(14f), s.px(8f), s.px(14f), s.px(8f))
            isClickable = true
            setOnClickListener { handle(Request.Stack(z)) }
        }
    }
    private val stackRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(s.text(15f, SetupStyle.MU, SetupStyle.MEDIUM).apply { text = "Zigbee stack" }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.px(12f) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = s.glass(14f)
            setPadding(s.px(3f), s.px(3f), s.px(3f), s.px(3f))
            stackSegments.values.forEach { addView(it) }
        })
    }
    private val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    /** Joining is the way forward (accent); the Leave actions are red. */
    private val primaryActions = setOf(Action.ROUTER_JOIN, Action.THREAD_JOIN_HA)
    private val actionButtons = Action.entries.associateWith { a ->
        s.button(a.label, accent = a in primaryActions).apply {
            if (a !in primaryActions) danger(this)
            setOnClickListener { handle(Request.Act(a)) }
        }
    }
    private val result = s.text(14.5f, SetupStyle.MU, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f) }

    // Thread dataset, write-only, behind "Advanced" (collapsed by default).
    private val datasetToggle = s.text(15f, SetupStyle.MU, SetupStyle.MEDIUM).apply {
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
        typeface = Inter.typeface(context, SetupStyle.REGULAR, 15f) // password fields default to monospace
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setSingleLine()
        background = s.glass(14f)
        setPadding(s.px(14f), s.px(10f), s.px(14f), s.px(10f))
        setOnFocusChangeListener { v, has -> if (has) onFieldFocus?.invoke(v) }
        setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { submitDataset(); true } else false }
    }
    private val datasetMsg = s.text(13.5f, SetupStyle.MU, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.2f) }
    private val datasetBody = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(s.text(13.5f, SetupStyle.MU, SetupStyle.REGULAR).apply {
            text = RadioFollowUp.DATASET_HINT + " " + RadioFollowUp.DATASET_KEY
            setLineSpacing(0f, 1.2f)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(datasetEdit, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = s.px(10f) })
            addView(s.button("Set dataset", accent = false).apply { setOnClickListener { submitDataset() } })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(8f) })
        addView(datasetMsg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(6f) })
    }
    private val datasetBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(datasetToggle)
        addView(datasetBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(4f) })
    }
    private val note = s.text(14f, SetupStyle.FA, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.25f) }
    private val moreToggle = s.text(15f, SetupStyle.MU, SetupStyle.MEDIUM).apply {
        isClickable = true
        setPadding(0, s.px(6f), 0, s.px(6f))
        setOnClickListener { moreOpen = !moreOpen; rebind() }
    }
    private val moreBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var moreFor: List<String>? = null

    private val activeBlock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(s.px(20f), s.px(16f), s.px(20f), s.px(16f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(qrBox, LinearLayout.LayoutParams(s.px(QR_DP), s.px(QR_DP)).apply { marginEnd = s.px(20f) })
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(stateRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(12f) })
        addView(stackRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(12f) })
        addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(14f) })
        addView(result, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(8f) })
        addView(datasetBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(8f) })
        addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(8f) })
        addView(moreToggle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = s.px(6f) })
        addView(moreBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(2f) })
    }

    val panel: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(header, LinearLayout.LayoutParams(-1, -2))
        addView(offBlock, LinearLayout.LayoutParams(-1, -2))
        addView(previewBlock, LinearLayout.LayoutParams(-1, -2))
        addView(startBlock, LinearLayout.LayoutParams(-1, -2))
        addView(refusedBlock, LinearLayout.LayoutParams(-1, -2))
        addView(activeBlock, LinearLayout.LayoutParams(-1, -2))
        background = s.glass(20f)
        clipToOutline = true
    }

    // ------------------------------------------------------------------ binding

    fun bind(f: RadioFollowUp.Facts) {
        val modeChanged = facts?.mode != f.mode
        facts = f
        flow.tick(f.status)
        if (modeChanged) {
            if (selected == f.mode) selected = null
            moreOpen = false; datasetOpen = false; datasetResult = null; datasetEdit.setText("")
        }
        rebind()
    }

    /** Previews (RadioMockups): a tapped tile, an open disclosure, a refused switch. */
    internal fun previewSelect(m: RadioMode?) { selected = m; rebind() }
    internal fun previewOpen(more: Boolean = false, dataset: Boolean = false) { moreOpen = more; datasetOpen = dataset; rebind() }
    internal fun previewRefused(m: RadioMode, status: String) { refused = m to status; rebind() }

    private fun rebind() {
        val f = facts ?: return
        val health = RadioFollowUp.health(f)
        bindChips(f)
        val runningMode = (flow.running as? Request.Mode)?.mode
        val ref = refused
        val sel = selected?.takeIf { it != f.mode && runningMode == null }
        val view = when {
            runningMode != null -> Show.STARTING
            ref != null -> Show.REFUSED
            sel != null -> Show.PREVIEW
            f.mode == RadioMode.OFF -> Show.OFF
            health == Health.STARTING -> Show.STARTING
            else -> Show.ACTIVE
        }
        offBlock.visibility = if (view == Show.OFF) VISIBLE else GONE
        previewBlock.visibility = if (view == Show.PREVIEW) VISIBLE else GONE
        startBlock.visibility = if (view == Show.STARTING) VISIBLE else GONE
        refusedBlock.visibility = if (view == Show.REFUSED) VISIBLE else GONE
        activeBlock.visibility = if (view == Show.ACTIVE) VISIBLE else GONE
        startBar.running = view == Show.STARTING

        val role = when (view) {
            Show.PREVIEW -> sel!!
            Show.REFUSED -> ref!!.first
            Show.STARTING -> runningMode ?: f.mode
            else -> f.mode
        }
        val hue = RoleStyle.hue(role)
        header.visibility = if (view == Show.OFF) GONE else VISIBLE
        header.background = GradientDrawable().apply {
            val r = 20 * s.dp
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(RoleStyle.tint(hue, 0.14f))
        }
        headerText.text = RoleStyle.header(role)
        headerText.setTextColor(hue)
        val (pillText, pillColor) = when (view) {
            Show.PREVIEW -> (if (RadioStep.OPTIONS.any { it.mode == role && it.experimental }) "Experimental" else "Off now") to SetupStyle.FA
            Show.STARTING -> "Starting" to SetupStyle.AMBER
            Show.REFUSED -> "Not started" to SetupStyle.ROSE
            else -> health.label to healthColor(health)
        }
        setPill(headerPill, pillText, pillColor)
        if (view == Show.PREVIEW && f.mode == role) setPill(headerPill, "On", SetupStyle.GREEN)

        when (view) {
            Show.OFF -> bindOff(f)
            Show.PREVIEW -> bindPreview(f, role)
            Show.STARTING -> bindStarting(f, role)
            Show.REFUSED -> bindRefused(f, ref!!)
            Show.ACTIVE -> bindActive(f, health)
        }
    }

    private enum class Show { OFF, PREVIEW, STARTING, REFUSED, ACTIVE }

    private fun bindOff(f: RadioFollowUp.Facts) {
        val c = RadioFollowUp.card(f, RadioFollowUp.Place.SETTINGS)
        val leave = Action.THREAD_LEAVE in c.actions
        offNote.text = c.note.orEmpty()
        offNote.visibility = if (leave && c.note != null) VISIBLE else GONE
        offLeave.visibility = if (leave) VISIBLE else GONE
        busy(offLeave, Action.THREAD_LEAVE)
    }

    private fun bindPreview(f: RadioFollowUp.Facts, m: RadioMode) {
        previewPitch.text = RadioFollowUp.pitch(m)
        val sameTech = RoleStyle.hue(m) == RoleStyle.hue(f.mode)
        previewSub.text = when {
            f.mode == RadioMode.OFF || m == RadioMode.OFF -> ""
            !sameTech -> "Zigbee and Thread can't run at the same time: ${titleOf(f.mode)} stops."
            else -> "${titleOf(f.mode)} stops."
        }
        previewSub.visibility = if (previewSub.text.isEmpty()) GONE else VISIBLE
        previewGo.text = if (m == RadioMode.OFF) "Turn the radio off" else "Turn on ${titleOf(m).substringBefore(" (")}"
    }

    private fun bindStarting(f: RadioFollowUp.Facts, m: RadioMode) {
        val from = if ((flow.running as? Request.Mode) != null && f.mode != m) " from ${titleOf(f.mode)}" else ""
        startTitle.text = if (m == RadioMode.OFF) "Turning the radio off…" else "Starting ${titleOf(m)}$from…"
        // "applying <mode>: waiting for zigbeed" -> "Waiting for zigbeed"
        val detail = f.status.substringAfter(": ", "").ifEmpty { "This takes about 10 seconds." }.replaceFirstChar { it.uppercase() }
        startSub.text = if (flow.running != null) "The radio restarts. This takes about 10 seconds." else detail
    }

    private fun bindRefused(f: RadioFollowUp.Facts, r: Pair<RadioMode, String>) {
        refusedText.text = RadioFollowUp.refusedText(r.second)
        stillText.text = if (f.mode == RadioMode.OFF) "The radio stays off." else "${titleOf(f.mode)} is still running."
        stillDot.visibility = if (f.mode == RadioMode.OFF) GONE else VISIBLE
    }

    private fun bindActive(f: RadioFollowUp.Facts, health: Health) {
        val c = RadioFollowUp.card(f, RadioFollowUp.Place.SETTINGS)
        // what to do (rebuilt only when it changes)
        val key = listOf(c.title, c.headline, c.subline, c.lines, c.kv)
        if (key != infoFor) {
            infoFor = key
            info.removeAllViews()
            c.headline?.let {
                info.addView(s.text(28f, SetupStyle.TX, SetupStyle.REGULAR).apply { text = it })
                c.subline?.let { sub -> info.addView(s.text(16f, SetupStyle.MU, SetupStyle.REGULAR).apply { text = sub; setLineSpacing(0f, 1.2f) }, lp(top = 2f)) }
            }
            if (c.title.isNotEmpty()) info.addView(s.text(18.5f, SetupStyle.TX, SetupStyle.MEDIUM).apply { text = c.title })
            c.lines.forEach { l -> info.addView(s.text(15f, SetupStyle.MU, SetupStyle.REGULAR).apply { text = l; setLineSpacing(0f, 1.25f) }, lp(top = 6f)) }
            c.kv.forEach { (k, v, big) ->
                info.addView(s.label(k), lp(top = 10f))
                info.addView(s.text(if (big) 26f else 18.5f, SetupStyle.TX, if (big) SetupStyle.LIGHT else SetupStyle.MEDIUM).apply {
                    text = v
                    if (!big) { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
                })
            }
        }
        val q = c.qr
        if (q != null) {
            if (qrFor != q.url) { qrImage.setImageBitmap(QrCode.bitmap(q.url, s.px(QR_DP))); qrFor = q.url }
            qrBox.visibility = VISIBLE
            qrBox.contentDescription = q.caption
        } else qrBox.visibility = GONE

        // live state: the role's own line, or what went wrong
        val state = if (health == Health.ERROR) RadioFollowUp.statusDetail(f) else c.state
        stateText.text = state.orEmpty()
        stateText.setTextColor(if (health == Health.ERROR) SetupStyle.ROSE else SetupStyle.TX)
        stateText.visibility = if (state == null) GONE else VISIBLE
        badge.visibility = if (c.badge == null) GONE else VISIBLE
        c.badge?.let { setPill(badge, it, SetupStyle.MU) }
        stateRow.visibility = if (state == null && c.badge == null) GONE else VISIBLE

        val running = flow.running
        stackRow.visibility = if (c.stackChoice) VISIBLE else GONE
        stackSegments.forEach { (z, b) ->
            val on = f.stack == z
            b.setTextColor(if (on) SetupStyle.BG else SetupStyle.MU)
            b.background = if (on) GradientDrawable().apply { cornerRadius = 11 * s.dp; setColor(SetupStyle.TX) } else null
            b.isEnabled = running == null
        }

        if (actionRow.childCount != c.actions.size || c.actions.withIndex().any { (i, a) -> actionRow.getChildAt(i) !== actionButtons[a] }) {
            actionRow.removeAllViews()
            c.actions.forEachIndexed { i, a ->
                (actionButtons.getValue(a).parent as? LinearLayout)?.removeView(actionButtons.getValue(a))
                actionRow.addView(actionButtons.getValue(a), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = s.px(10f) })
            }
        }
        actionRow.visibility = if (c.actions.isEmpty()) GONE else VISIBLE
        c.actions.forEach { busy(actionButtons.getValue(it), it) }
        val msg = if (running is Request.Stack) "Switching the Zigbee stack…" else flow.result
        result.text = msg.orEmpty()
        result.visibility = if (msg == null) GONE else VISIBLE

        datasetBox.visibility = if (c.datasetField) VISIBLE else GONE
        datasetToggle.text = RadioFollowUp.DATASET_DISCLOSURE + if (datasetOpen) "  ˅" else "  ›"
        datasetBody.visibility = if (datasetOpen) VISIBLE else GONE
        datasetResult.let { r ->
            datasetMsg.visibility = if (r == null) GONE else VISIBLE
            if (r != null) { datasetMsg.text = r.second; datasetMsg.setTextColor(if (r.first) SetupStyle.GREEN else SetupStyle.ROSE) }
        }
        note.text = c.note.orEmpty()
        note.visibility = if (c.note == null) GONE else VISIBLE

        moreToggle.visibility = if (c.more.isEmpty()) GONE else VISIBLE
        moreToggle.text = "How does this work?" + if (moreOpen) "  ˅" else "  ›"
        if (c.more != moreFor) {
            moreFor = c.more
            moreBox.removeAllViews()
            c.more.forEach { l -> moreBox.addView(s.text(14.5f, SetupStyle.MU, SetupStyle.REGULAR).apply { text = l; setLineSpacing(0f, 1.3f) }, lp(top = 6f)) }
        }
        moreBox.visibility = if (moreOpen) VISIBLE else GONE
    }

    // ------------------------------------------------------------------ actions

    private fun tap(m: RadioMode) {
        val f = facts ?: return
        if (flow.running != null || m == f.mode) return
        refused = null
        selected = null
        handle(Request.Mode(m))
    }

    private fun handle(r: Request) {
        val now = flow.press(r, facts)
        if (now != null) { run(now); return }
        val a = flow.asking ?: return
        val ask = onAsk
        if (ask == null) { flow.cancel(); return }
        ask(a) { yes ->
            if (yes) flow.confirm()?.let(::run) else flow.cancel()
            rebind()
        }
    }

    private fun run(r: Request) {
        val f = facts ?: return
        flow.started(r, f.status)
        if (r is Request.Mode) { refused = null; moreOpen = false }
        rebind()
        val host = onRun
        if (host == null) { flow.finish("Not available here."); rebind(); return }
        host(r) { msg ->
            if (r is Request.Act && msg == null) return@host // ends with the status (Flow.tick)
            if (r is Request.Mode) {
                flow.finish(null)
                if (msg != null) refused = r.mode to msg
                selected = null
            } else flow.finish(msg)
            rebind()
        }
    }

    private fun submitDataset() {
        val text = datasetEdit.text?.toString().orEmpty()
        if (text.isBlank()) return
        val err = onDataset?.invoke(text) ?: if (onDataset == null) "Not available here." else null
        datasetResult = if (err == null) {
            datasetEdit.setText("") // write-only: never kept or shown again
            true to "Dataset handed to the radio. The panel shows when it has joined."
        } else false to err
        rebind()
    }

    // ------------------------------------------------------------------ pieces

    private fun busy(b: TextView, a: Action) {
        val running = flow.running
        val mine = running == Request.Act(a)
        b.text = if (mine) a.busyLabel else a.label
        b.isEnabled = running == null
        b.alpha = if (running != null && !mine) 0.5f else 1f
    }

    private fun danger(b: TextView) {
        b.setTextColor(SetupStyle.ROSE)
        b.background = GradientDrawable().apply {
            cornerRadius = 16 * s.dp
            setColor(RoleStyle.tint(SetupStyle.ROSE, 0.10f)); setStroke(s.px(1f).coerceAtLeast(1), RoleStyle.tint(SetupStyle.ROSE, 0.40f))
        }
    }

    private fun setPill(p: TextView, text: String, c: Int) {
        p.text = text
        p.setTextColor(c)
        p.background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(RoleStyle.tint(c, 0.12f)); setStroke(s.px(1f).coerceAtLeast(1), RoleStyle.tint(c, 0.40f))
        }
    }

    private fun legend(c: Int, label: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(StatusDot(context, c, 10f, halo = false), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.px(8f) })
        addView(s.text(14f, SetupStyle.MU, SetupStyle.MEDIUM).apply { text = label })
    }

    private fun lp(top: Float) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(top) }

    private fun titleOf(m: RadioMode) = RadioStep.OPTIONS.firstOrNull { it.mode == m }?.title ?: m.label

    private companion object {
        const val QR_DP = 136f
        const val VISIBLE = android.view.View.VISIBLE
        const val GONE = android.view.View.GONE
    }
}

/** A thin bar with a segment sliding across, while something starts (no made-up percentage). */
private class Indeterminate(context: Context) : android.view.View(context) {
    var running = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        p.color = SetupStyle.S2
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
        val t = (SystemClock.uptimeMillis() % 1600L) / 1600f
        val seg = w * 0.35f
        val x = -seg + (w + seg) * t
        p.color = SetupStyle.AMBER
        c.drawRoundRect(RectF(x.coerceAtLeast(0f), 0f, (x + seg).coerceAtMost(w), h), r, r, p)
        if (running && isShown) postInvalidateDelayed(32)
    }
}

/**
 * The centred confirmation dialog over the dimmed menu: the roles a switch goes between (icons), the
 * question, the consequence in plain words, Cancel and the action: white for Switch, red for Leave.
 */
class RadioDialog(context: Context) : FrameLayout(context) {
    private val s = SetupStyle(context)
    private var answer: ((Boolean) -> Unit)? = null
    private val fromIcon = RoleIcon(context, RoleStyle.Glyph.HUB, RoleStyle.ZIGBEE, 28f)
    private val toIcon = RoleIcon(context, RoleStyle.Glyph.REPEATER, RoleStyle.THREAD, 28f)
    private val arrow = s.text(20f, SetupStyle.FA, SetupStyle.REGULAR).apply { text = "→" }
    private val icons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(fromIcon)
        addView(arrow, LinearLayout.LayoutParams(-2, -2).apply { marginStart = s.px(12f); marginEnd = s.px(12f) })
        addView(toIcon)
    }
    private val title = s.text(23f, SetupStyle.TX, SetupStyle.MEDIUM)
    private val body = s.text(16f, SetupStyle.MU, SetupStyle.REGULAR).apply { setLineSpacing(0f, 1.3f) }
    private val yes = s.button("", accent = false).apply { setOnClickListener { close(true) } }
    private val no = Glass(context).plain("Cancel").apply { setOnClickListener { close(false) } }

    init {
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        isClickable = true // nothing behind it reacts while it's up
        visibility = GONE
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 24 * s.dp; setColor(SetupStyle.SHEET); setStroke(s.px(1f), SetupStyle.BD) }
            setPadding(s.px(26f), s.px(24f), s.px(26f), s.px(22f))
            isClickable = true
            addView(icons, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = s.px(14f) })
            addView(title)
            addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(8f) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(no, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.px(10f) })
                addView(yes)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(20f) })
        }, LayoutParams(s.px(480f), -2, Gravity.CENTER))
    }

    val isOpen: Boolean get() = visibility == VISIBLE

    fun show(a: RadioFollowUp.Ask, onAnswer: (Boolean) -> Unit) {
        answer?.invoke(false) // a previous one, unanswered: that's a no
        answer = onAnswer
        title.text = a.title
        body.text = a.body
        yes.text = a.button
        if (a.destructive) {
            yes.setTextColor(SetupStyle.BG)
            yes.background = GradientDrawable().apply { cornerRadius = 16 * s.dp; setColor(SetupStyle.ROSE) }
        } else {
            yes.setTextColor(SetupStyle.BG)
            yes.background = GradientDrawable().apply { cornerRadius = 16 * s.dp; setColor(SetupStyle.TX) }
        }
        val from = a.from; val to = a.to
        if (to != null) {
            toIcon.glyph = RoleStyle.glyph(to); toIcon.color = RoleStyle.hue(to); toIcon.invalidate()
            fromIcon.visibility = if (from != null) VISIBLE else GONE
            arrow.visibility = fromIcon.visibility
            from?.let { fromIcon.glyph = RoleStyle.glyph(it); fromIcon.color = RoleStyle.hue(it); fromIcon.invalidate() }
            icons.visibility = VISIBLE
        } else icons.visibility = GONE
        visibility = VISIBLE
        bringToFront()
    }

    /** Closes without an answer being a yes (menu closed, Back). */
    fun dismiss() { if (isOpen) close(false) }

    private fun close(ok: Boolean) {
        visibility = GONE
        val a = answer; answer = null
        a?.invoke(ok)
    }
}
