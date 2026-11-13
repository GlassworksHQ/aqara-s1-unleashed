package sh.edm.s1kiosk.radio

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupScreen
import sh.edm.s1kiosk.setup.BoundedScroll
import sh.edm.s1kiosk.setup.RadioFollowUp
import sh.edm.s1kiosk.setup.RadioFollowUpView
import sh.edm.s1kiosk.setup.RadioStep
import sh.edm.s1kiosk.setup.RoleIcon
import sh.edm.s1kiosk.setup.RoleStyle
import sh.edm.s1kiosk.setup.SetupStyle

/**
 * The radio step on one setup screen (docs/radio.md, "App side"): the five roles to choose from, then
 * "Apply" through the same RadioController path as HA's `Radio mode` select, and once a mode other than
 * Off works, its follow-up card (setup/RadioFollowUp, the same card as the settings menu's Radio
 * section): what to do in Home Assistant, the QR code, the role's buttons. "Back" returns to the
 * choice. The last "Apply" belongs to this screen (the activity), as before. Main thread.
 */
internal class RadioSetupScreen(
    context: Context,
    private val host: SetupHost,
    private val def: RadioDefinition,
) : SetupScreen {

    private val s = SetupStyle(context)
    private val main = Handler(Looper.getMainLooper())
    private val readout get() = def.readout

    /** The last "Apply" (this screen) and whether a request is running. */
    private var applied: RadioStep.Applied? = null
    private var busy = false
    private var selected: RadioMode = RadioMode.DEFAULT
    /** The coordinator's pairing view (QR) was left with "Back" for this apply. */
    private var pairingDismissed: RadioStep.Applied? = null

    /** The follow-up card, scrolling when it is taller than the card has room for. */
    private val followUp = RadioFollowUpView(context).apply {
        onRun = { r, done -> run(r, done) }
    }
    // 200 dp: label + title + this + the buttons fit the 480 dp screen's card (RadioMockups 13/14).
    private val followScroll = BoundedScroll(context, s.px(200f)).apply {
        isVerticalScrollBarEnabled = true
        setPadding(0, 0, s.px(10f), 0) // a scrollbar (if it ever needs one) never sits on the text
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(followUp, android.widget.FrameLayout.LayoutParams(-1, -2))
        visibility = View.GONE
    }
    private val tiles = RadioStep.OPTIONS.map { Tile(it) }
    private val choice = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tiles.filter { !it.option.repeater }.forEachIndexed { i, t ->
                addView(t.view, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = s.px(10f) })
            }
        }, LinearLayout.LayoutParams(-1, -2))
        // Repeater roles (someone else's network) in a compact row under the main options, then
        // roles that aren't ready yet (dimmed, not selectable).
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val compact = tiles.filter { it.option.repeater }.map { it.view } + RadioStep.COMING_SOON.map { soonTile(it) }
            compact.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = s.px(10f) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(8f) })
        visibility = View.GONE
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(choice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(12f) })
        addView(followScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(14f) })
    }

    override fun bind(card: SetupCard, entered: Boolean) {
        if (entered) selected = RadioStep.OPTIONS.firstOrNull { it.mode == readout.mode() }?.mode ?: RadioMode.DEFAULT
        card.content(content)
        val ok = applied?.takeIf { it.mode == selected && it.result == RadioStep.Result.OK && !busy }
        if (ok != null && ok.mode != RadioMode.OFF && pairingDismissed != ok) bindFollowUp(card, ok) else bindChoice(card)
    }

    private fun bindChoice(card: SetupCard) {
        choice.visibility = View.VISIBLE
        followScroll.visibility = View.GONE
        card.title("What should the panel's radio do?")
        // No body line here: five options + the note + buttons fill the card (960x480 dp panel).
        // "Change it later" is the note while Off is selected.
        card.body(null)
        card.tightButtons()
        tiles.forEach { t -> t.bind(t.option.mode == selected) }

        val a = applied?.takeIf { it.mode == selected }
        when {
            busy -> card.status("Applying ${titleOf(selected)}…", SetupCard.Tone.AMBER)
            a != null -> when (a.result) {
                RadioStep.Result.OK -> card.status(a.status, SetupCard.Tone.GREEN)
                RadioStep.Result.APPLYING -> card.status("Applying: ${a.status}", SetupCard.Tone.AMBER)
                RadioStep.Result.REFUSED, RadioStep.Result.ERROR -> card.status(a.status, SetupCard.Tone.ROSE)
            }
            else -> card.status(RadioStep.nextStep(selected, readout.panelIp()) ?: RadioStep.LATER)
        }
        if (RadioStep.canContinue(a) && !busy) {
            card.primary("Continue") { finish() }
        } else {
            card.primary(if (busy) "Applying…" else "Apply", enabled = !busy) { if (!busy) apply(selected) }
        }
        card.secondary(if (busy) null else "Skip (Off)") { skip() }
    }

    /** A mode other than Off applied: its follow-up card. "Back" returns to the options for this apply. */
    private fun bindFollowUp(card: SetupCard, ok: RadioStep.Applied) {
        choice.visibility = View.GONE
        followScroll.visibility = View.VISIBLE
        val f = (readout.facts() ?: RadioFollowUp.Facts(
            mode = ok.mode, status = ok.status, helper = HelperFacts(false, 0), stack = ZigbeeStack.DEFAULT,
            threadStored = null, thread = null, haBase = null, panelIp = null,
        )).copy(mode = ok.mode, haBase = readout.haBase(), panelIp = readout.panelIp())
        followUp.bind(f)
        val c = RadioFollowUp.card(f, RadioFollowUp.Place.SETUP)
        card.title(c.title)
        card.body(null)
        card.qr(c.qr?.url)
        card.primary("Continue") { finish() }
        card.secondary("Back") {
            pairingDismissed = ok
            host.redraw()
        }
    }

    override fun tick(current: Boolean) {
        val r = readout.controller()
        // the follow-up card follows the router / Thread state
        if (current) r?.refreshSoon()
        // While an applied mode is still "applying" (root helper), follow RadioController's status.
        val a = applied ?: return
        if (!a.accepted || a.result != RadioStep.Result.APPLYING || def.stepDone) return
        if (r == null) return
        r.refreshSoon()
        val now = r.statusText()
        if (now != a.status) {
            applied = a.copy(status = now)
            host.redraw()
        }
    }

    /**
     * "Apply": the same RadioController path as HA's `Radio mode` select. The result is shown as
     * RadioController reports it (a missing root helper included); only Skip and Continue mark the step done.
     */
    private fun apply(mode: RadioMode) {
        if (host.preview) {
            // Shows what a successful apply looks like (e.g. the coordinator's pairing QR); nothing is applied.
            applied = RadioStep.Applied(mode, true, "ok ${mode.label}: preview, nothing was applied")
            host.redraw()
            return
        }
        val r = readout.controller()
        if (r == null) {
            applied = RadioStep.Applied(mode, false, RadioDefinition.NOT_RUNNING)
            host.redraw()
            return
        }
        Log.i(TAG, "Setup: radio mode ${mode.label}")
        busy = true
        applied = null
        host.redraw()
        r.request(mode.label, "setup screen") { accepted ->
            val status = r.statusText()
            main.post {
                busy = false
                applied = RadioStep.Applied(mode, accepted, status)
                Log.i(TAG, "Setup: radio ${mode.label} -> ${applied?.result}: $status")
                host.redraw()
            }
        }
    }

    /** "Skip": Off. */
    private fun skip() {
        if (!host.preview) {
            Log.i(TAG, "Setup: radio step skipped (Off)")
            val r = readout.controller()
            if (r != null && r.mode != RadioMode.OFF) r.request(RadioMode.OFF.label, "setup screen (skip)")
        }
        finish()
    }

    /** "Continue" (or Skip): the step is answered. */
    private fun finish() {
        if (!host.preview) {
            def.stepDone = true
            applied = null
        }
        host.next()
    }

    /** A request from the follow-up card. */
    private fun run(req: RadioFollowUp.Request, done: (String?) -> Unit) {
        if (host.preview) { done("Preview: nothing was changed."); return }
        def.run(req, "setup screen", done) { host.redraw() }
    }

    private fun titleOf(mode: RadioMode) = RadioStep.OPTIONS.firstOrNull { it.mode == mode }?.title ?: mode.label

    private fun select(mode: RadioMode) {
        if (selected == mode) return
        selected = mode
        host.redraw()
    }

    /** For the mock-ups: the screen as it is after an "Apply". */
    internal fun previewApplied(a: RadioStep.Applied?, busy: Boolean = false) {
        applied = a
        this.busy = busy
    }

    /**
     * One radio option: title (+ "experimental"), one line, amber outline when selected. Repeater
     * roles are compact: title and tag on one line, a single-line description.
     */
    private inner class Tile(val option: RadioStep.Option) {
        private val compact = option.repeater
        // 14 sp: "Thread border router" in Inter needs 141 of the large tile's 143 dp.
        private val title = s.text(14f, SetupStyle.TX, SetupStyle.MEDIUM).apply { text = option.title; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        // neutral: amber is for the one primary action (the settings menu marks these with a small β)
        private val expTag = s.label("Experimental").apply { setTextColor(SetupStyle.FA); visibility = if (option.experimental) View.VISIBLE else View.GONE }
        // The role's icon in its technology's colour (Zigbee sky blue, Thread violet), as in the settings
        // menu. Never on the title's line, which needs the whole width: on the tag's line in the large
        // tiles, at the start of the description in the compact ones.
        private val icon = RoleIcon(s.context, RoleStyle.glyph(option.mode), RoleStyle.hue(option.mode), if (compact) 14f else 18f)
        private val line = s.text(if (compact) 12f else 13f, SetupStyle.MU, SetupStyle.REGULAR).apply {
            text = option.line
            if (compact) { setSingleLine(); ellipsize = TextUtils.TruncateAt.END } else setLineSpacing(0f, 1.2f)
        }
        val view = LinearLayout(s.context).apply {
            orientation = LinearLayout.VERTICAL
            if (compact) {
                // The role's name gets the whole width; the tag ends the (ellipsised) description line.
                setPadding(s.px(14f), s.px(7f), s.px(14f), s.px(7f))
                addView(title)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(icon, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.px(6f) })
                    addView(line, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(expTag, LinearLayout.LayoutParams(-2, -2).apply { marginStart = s.px(8f) })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(2f) })
            } else {
                setPadding(s.px(12f), s.px(10f), s.px(12f), s.px(10f))
                addView(title)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(icon, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.px(8f) })
                    addView(expTag)
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = s.px(4f) })
                addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(4f) })
            }
            isClickable = true
            isFocusable = true
            contentDescription = option.title + if (option.experimental) ", experimental" else ""
            setOnClickListener { select(option.mode) }
        }

        fun bind(sel: Boolean) {
            view.background = s.glass(18f, fill = if (sel) SetupStyle.S2 else SetupStyle.S1, stroke = if (sel) SetupStyle.AMBER else SetupStyle.BD, strokeDp = if (sel) 2f else 1f)
            title.setTextColor(if (sel) SetupStyle.TX else SetupStyle.MU)
        }
    }

    /** A "Coming soon" radio role: title + tag on one line, its one line under it; no click. */
    private fun soonTile(o: RadioStep.Soon): View {
        val title = s.text(14f, SetupStyle.MU, SetupStyle.MEDIUM).apply { text = o.title; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val tag = s.label("Coming soon").apply { setTextColor(SetupStyle.FA) }
        val head = LinearLayout(s.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tag, LinearLayout.LayoutParams(-2, -2).apply { marginStart = s.px(8f) })
        }
        val line = s.text(12f, SetupStyle.FA, SetupStyle.REGULAR).apply { text = o.line; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        return LinearLayout(s.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(s.px(14f), s.px(8f), s.px(14f), s.px(8f))
            addView(head)
            addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.px(2f) })
            isClickable = false
            isFocusable = false
            contentDescription = "${o.title}, coming soon"
            background = GradientDrawable().apply {
                cornerRadius = 14 * s.dp
                setColor(SetupStyle.S1)
                setStroke(s.px(1f).coerceAtLeast(1), SetupStyle.BD)
            }
            alpha = 0.8f
        }
    }

    private companion object {
        const val TAG = "S1Kiosk"
    }
}
