package sh.edm.s1kiosk.setup

import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.ui.QrCode
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupScreen
import dev.glassworks.pane.SetupStep
import sh.edm.s1kiosk.auth.OAuth

/**
 * The first-boot setup screen (docs/onboarding.md, step 4): a native, full-screen overlay over the
 * GeckoView in the dashboard's visual language (tablet-home-card.compact.js tokens: bg #0a0b0f with
 * sky/violet radial glows, glass cards rgba(255,255,255,.045) + .07 border, 22-26 px radii,
 * uppercase letter-spaced labels in #63636b, big light headings, amber / green accents).
 *
 * Left: the steps (Wi-Fi, Add to Home Assistant, the panes' steps such as Radio, Sign in, Done) with
 * checks. Right: a glass card for the current step; a pane's step fills it through [SetupCard]. It replaces the earlier SignInOverlay (its QR code and texts are the
 * Sign-in step). The Wi-Fi step shows Improv (Wi-Fi over Bluetooth) inline, Allow included, so no
 * separate ImprovOverlay window covers this screen. Laid out for 960×480 dp (1440×720 px at
 * density 1.5). Main thread only.
 */
class SetupOverlay(
    context: Context,
    /** The panes' steps, in order (PaneDefinition.setupSteps). They go between Home Assistant and Sign in. */
    paneSteps: List<SetupStep> = emptyList(),
) : FrameLayout(context) {

    data class Model(
        /** The current step's id (SetupFlow). */
        val step: String,
        /** The ids of the steps that are done. */
        val done: Set<String>,
        val wifi: WifiSetupStatus.Snapshot,
        /** Home Assistant's URL, null while it hasn't been found. */
        val haUrl: String?,
        /** How it was found (`mdns`, `esphome`, `manual`, or "" for a saved dashboard URL). */
        val haSource: String,
        val panelIp: String?,
        val signedIn: Boolean,
        val preview: Boolean = false,
        /** Improv's status block for the Wi-Fi step (SetupDecision.improv). */
        val improv: WifiStep.ImprovLine = WifiStep.improv(ImprovFacts()),
        /** A Wi-Fi join sent from the computer (web installer), if any. */
        val installer: InstallerWifi? = null,
    )

    var onChooseNetwork: (() -> Unit)? = null
    var onSkipHa: (() -> Unit)? = null
    /** "Sign in on this panel instead". */
    var onUsePanel: (() -> Unit)? = null
    /** Manual URL submitted; call [manualResult] with the outcome. */
    var onManualUrl: ((String) -> Unit)? = null
    var onClosePreview: (() -> Unit)? = null
    /** An inline Improv button (Allow / Not now / Stop / Use Bluetooth / Try again). */
    var onImprovAction: ((WifiStep.Action) -> Unit)? = null
    /** A pane's step was answered on the screen ([SetupHost.next]). */
    var onStepDone: (() -> Unit)? = null

    /** True while visible (including the Done fade-out). */
    var isShowing = false
        private set

    var model: Model? = null
        private set

    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private var finishing: Runnable? = null
    private var qrFor: String? = null

    private val paneSteps = paneSteps.associateBy { it.id }
    /** Every step's id in order (SetupFlow.ids). */
    private val flow = SetupFlow.ids(paneSteps.map { it.id })

    // ---- left: the steps
    private val brand = label("S1 Panel · Setup")
    private val railTitle = text(30f, TX, LIGHT).apply { letterSpacing = -0.02f; text = "Let's set up\nyour panel"; setLineSpacing(0f, 1.0f) }
    /** One row per step, in [flow] order. */
    private val rows = flow.map { id ->
        StepRow(when (id) {
            SetupFlow.WIFI -> "Wi-Fi"
            SetupFlow.HA -> "Add to Home Assistant"
            SetupFlow.SIGNIN -> "Sign in"
            SetupFlow.DONE -> "Done"
            else -> this.paneSteps.getValue(id).title
        })
    }
    private val previewChip = button("Preview · Close", Kind.GHOST).apply { setOnClickListener { onClosePreview?.invoke() } }

    private val rail = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(brand)
        addView(railTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { i, r -> list.addView(r.view, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = px(10f) }) }
        addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22f) })
        addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
        addView(previewChip, LinearLayout.LayoutParams(-2, -2))
    }

    // ---- right: the current step's card
    private val cardLabel = label("")
    private val cardTitle = text(30f, TX, LIGHT).apply { letterSpacing = -0.02f; setLineSpacing(0f, 1.05f) }
    private val cardBody = text(16f, MU, REGULAR).apply { setLineSpacing(0f, 1.3f) }
    private val cardNote = text(14f, AMBER, REGULAR).apply { setLineSpacing(0f, 1.25f) }
    private val tileA = InfoTile()
    private val tileB = InfoTile()
    private val tiles = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(tileA.view, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(10f) })
        addView(tileB.view, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private val urlField = EditText(context).apply {
        textSize = 18f
        typeface = Inter.typeface(context, REGULAR, 18f)
        setTextColor(TX)
        setHintTextColor(FA)
        hint = "http://homeassistant.local:8123"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setSingleLine()
        background = glass(18f)
        setPadding(px(18f), px(13f), px(18f), px(13f))
        setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
        }
    }
    private val status = text(14f, MU, REGULAR)
    private val primary = button("", Kind.ACCENT)
    private val secondary = button("", Kind.GHOST)
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(primary, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(10f) })
        addView(secondary, LinearLayout.LayoutParams(-2, -2))
    }
    /** 20 dp above the buttons; 12 on the radio choice, whose five tiles fill the card (Inter is taller than Roboto). */
    private val buttonsLp = LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(20f) }
    private val qrImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val qrTile = FrameLayout(context).apply {
        background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(Color.WHITE) }
        setPadding(px(12f), px(12f), px(12f), px(12f))
        addView(qrImage, LayoutParams(-1, -1))
    }
    private val bigCheck = CheckDot(filled = true).apply { visibility = View.GONE }
    private val improvTile = ImprovTile()
    /** A pane's step's own view ([SetupCard.content]). */
    private val slot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

    private val cardColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(bigCheck, LinearLayout.LayoutParams(px(64f), px(64f)).apply { bottomMargin = px(18f) })
        addView(cardLabel)
        addView(cardTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        addView(cardBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        addView(cardNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        addView(tiles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18f) })
        addView(improvTile.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
        addView(slot, LinearLayout.LayoutParams(-1, -2))
        addView(urlField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18f) })
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        addView(buttons, buttonsLp)
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = glass(26f)
        setPadding(px(30f), px(26f), px(30f), px(26f))
        addView(qrTile, LinearLayout.LayoutParams(px(210f), px(210f)).apply { marginEnd = px(30f) })
        addView(cardColumn, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** What the screen offers a pane's step. */
    private val stepHost = object : SetupHost {
        override val preview: Boolean get() = model?.preview == true
        override fun redraw() { model?.let { bindCard(it, stepChanged = false) } }
        override fun next() { onStepDone?.invoke() }
    }

    /** Each pane step's part of this screen. */
    private val screens: Map<String, SetupScreen> = paneSteps.associate { it.id to it.screen(context, stepHost) }

    init {
        background = Backdrop()
        isClickable = true // swallow touches meant for the page underneath
        isFocusable = true
        elevation = 20 * dp
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(36f), px(30f), px(30f), px(30f))
            addView(rail, LinearLayout.LayoutParams(px(290f), -1).apply { marginEnd = px(24f) })
            addView(card, LinearLayout.LayoutParams(0, -1, 1f))
        }
        addView(content, LayoutParams(-1, -1))
        visibility = View.GONE
    }

    /** Shows (or updates) the screen for [m]. */
    fun render(m: Model) {
        cancelFinish()
        val stepChanged = model?.step != m.step
        model = m
        bindRail(m)
        bindCard(m, stepChanged)
        reveal()
    }

    /**
     * Shows the Done step, calls [loaded] (load the dashboard behind it), then fades out.
     */
    fun finish(from: Model? = model, holdMs: Long = 1_800, loaded: () -> Unit) {
        val m = (from ?: return).copy(step = SetupFlow.DONE, done = flow.toSet())
        cancelFinish()
        model = m
        bindRail(m)
        bindCard(m, true)
        reveal()
        loaded()
        val r = Runnable { finishing = null; hide() }
        finishing = r
        postDelayed(r, holdMs)
    }

    fun manualResult(ok: Boolean, message: String) {
        primary.isEnabled = true
        status.visibility = View.VISIBLE
        status.setTextColor(if (ok) GREEN else ROSE)
        status.text = message
    }

    /** For the mock-ups: a pane step's part of this screen. */
    internal fun previewScreen(id: String): SetupScreen? = screens[id]

    /** Every couple of seconds while the app runs: the panes' steps follow their state. */
    fun tick() {
        screens.forEach { (id, screen) -> screen.tick(current = isShowing && model?.step == id) }
    }

    /** Improv identify: pulse the Bluetooth block a few times. */
    fun flashImprov() {
        val v = improvTile.view
        if (!isShowing || v.visibility != View.VISIBLE) return
        v.animate().cancel()
        android.animation.ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.04f).apply {
            duration = 220; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = 7
            addUpdateListener { v.scaleY = v.scaleX }
            start()
        }
    }

    fun hide() {
        cancelFinish()
        if (!isShowing) return
        isShowing = false
        model = null
        hideKeyboard()
        animate().cancel()
        animate().alpha(0f).setDuration(300).withEndAction { if (!isShowing) visibility = View.GONE }.start()
    }

    private fun cancelFinish() {
        finishing?.let { removeCallbacks(it) }
        finishing = null
    }

    // ------------------------------------------------------------------ binding

    private fun bindRail(m: Model) {
        val w = m.wifi
        val finished = m.step == SetupFlow.DONE
        rows.forEachIndexed { i, r ->
            val id = flow[i]
            val done = id in m.done || (id == SetupFlow.DONE && finished)
            val detail = when (id) {
                SetupFlow.WIFI -> when {
                    done && w.ethernet -> "Ethernet"
                    done -> w.ssid ?: "Connected"
                    w.state == WifiSetupStatus.State.CONNECTING -> w.ssid?.let { "Joining $it…" } ?: "Connecting…"
                    m.improv.phase == WifiStep.Phase.ASKING -> "Allow Bluetooth setup"
                    m.improv.phase in setOf(WifiStep.Phase.READY, WifiStep.Phase.AUTHORIZED) -> "Bluetooth setup is on"
                    else -> "Not connected"
                }
                SetupFlow.HA -> when {
                    done -> "Added"
                    m.haUrl == null -> "Looking for Home Assistant…"
                    else -> "Waiting to be added"
                }
                SetupFlow.SIGNIN -> when {
                    m.signedIn -> "Signed in"
                    done -> "Session active"
                    else -> "Scan the QR code"
                }
                SetupFlow.DONE -> if (finished) "Loading your dashboard" else ""
                else -> paneSteps.getValue(id).detail(done)
            }
            r.bind(i + 1, if (done) Dot.DONE else if (m.step == id) Dot.CURRENT else Dot.PENDING, detail)
        }
        previewChip.visibility = if (m.preview) View.VISIBLE else View.GONE
    }

    private fun bindCard(m: Model, stepChanged: Boolean) {
        // Defaults: everything optional hidden.
        qrTile.visibility = View.GONE
        bigCheck.visibility = View.GONE
        cardNote.visibility = View.GONE
        cardNote.setTextColor(AMBER)
        cardBody.visibility = View.VISIBLE
        tiles.visibility = View.GONE
        tileB.view.visibility = View.VISIBLE
        improvTile.view.visibility = View.GONE
        slot.visibility = View.GONE
        primary.isEnabled = true
        primary.alpha = 1f
        // The URL field is never reset to GONE and back: a view that goes GONE (even for one frame)
        // loses focus, and this runs on every discovery/Wi-Fi refresh, i.e. while the user types.
        var showUrl = false
        buttonsLp.topMargin = px(20f)
        buttons.visibility = View.VISIBLE
        primary.visibility = View.GONE
        secondary.visibility = View.GONE
        primary.setOnClickListener(null)
        secondary.setOnClickListener(null)
        if (stepChanged) status.visibility = View.GONE
        cardTitle.textSize = when (m.step) {
            SetupFlow.HA, SetupFlow.DONE -> 30f
            // The QR takes ~240 dp of the card; room for the Bluetooth block / a pane's own view.
            else -> 26f
        }

        when (m.step) {
            SetupFlow.WIFI -> bindWifi(m)
            SetupFlow.HA -> {
                cardLabel.text = "Step ${number(SetupFlow.HA)} · Home Assistant"
                if (m.haUrl == null) {
                    cardTitle.text = "Where is Home Assistant?"
                    cardBody.text = "The panel is looking for it on the network. If it doesn't show up, enter its address; " +
                        "the panel keeps looking in the background."
                    showUrl = true
                    primary.text = "Connect"
                    primary.isEnabled = true
                    primary.visibility = View.VISIBLE
                    primary.setOnClickListener { submit() }
                } else {
                    cardTitle.text = "Add the panel to Home Assistant"
                    cardBody.text = "In Home Assistant: Settings → Devices & services → S1 Panel discovered → Add."
                    tiles.visibility = View.VISIBLE
                    // Manual add: Add integration → ESPHome → host + port.
                    tileA.bind("Manual add · ESPHome", "${m.panelIp ?: "<panel IP>"} · port 6053")
                    tileB.bind(
                        "Home Assistant" + when (m.haSource) { "mdns" -> " · mDNS"; "esphome" -> " · ESPHome"; "manual" -> " · entered"; else -> "" },
                        m.haUrl,
                    )
                    cardNote.text = "This screen moves on by itself once Home Assistant has added the panel."
                    cardNote.setTextColor(FA)
                    cardNote.visibility = View.VISIBLE
                    secondary.text = "Skip for now"
                    secondary.visibility = View.VISIBLE
                    secondary.setOnClickListener { onSkipHa?.invoke() }
                }
            }
            SetupFlow.SIGNIN -> {
                val ip = m.panelIp ?: "<panel-ip>"
                val url = "http://$ip:${OAuth.SIGNIN_PORT}/signin"
                if (qrFor != url) {
                    qrImage.setImageBitmap(QrCode.bitmap(url, px(186f)))
                    qrFor = url
                }
                qrTile.visibility = View.VISIBLE
                cardLabel.text = "Step ${number(SetupFlow.SIGNIN)} · Sign in"
                cardTitle.text = "Scan with your phone to sign in to Home Assistant"
                cardBody.text = "Or open $url on a phone or computer on the same network."
                tiles.visibility = View.VISIBLE
                tileA.bind("Home Assistant", m.haUrl ?: "")
                tileB.view.visibility = View.GONE
                secondary.text = "Sign in on this panel instead"
                secondary.visibility = View.VISIBLE
                secondary.setOnClickListener { onUsePanel?.invoke() }
            }
            SetupFlow.DONE -> {
                bigCheck.visibility = View.VISIBLE
                cardLabel.text = "Done"
                cardTitle.text = "You're all set"
                cardBody.text = "Loading your dashboard…"
                buttons.visibility = View.GONE
            }
            else -> paneSteps[m.step]?.let { bindPaneStep(it, stepChanged) }
        }
        val urlVis = if (showUrl) View.VISIBLE else View.GONE
        if (urlField.visibility != urlVis) urlField.visibility = urlVis
        if (!showUrl) hideKeyboard()
    }

    private fun bindWifi(m: Model) {
        cardLabel.text = "Step ${number(SetupFlow.WIFI)} · Wi-Fi"
        val w = m.wifi
        val inst = m.installer
        if (w.online) {
            cardTitle.text = if (w.ethernet) "Connected over Ethernet" else "Connected to ${w.ssid ?: "Wi-Fi"}"
            cardBody.text = "The panel is on the network."
        } else if (inst?.phase == InstallerWifi.Phase.CONNECTING) {
            cardTitle.text = "Connecting to ${inst.ssid}…"
            cardBody.text = "Wi-Fi details sent from your computer." + (inst.detail?.let { " $it" } ?: " This takes up to 30 seconds.")
        } else {
            cardTitle.text = if (w.state == WifiSetupStatus.State.CONNECTING) "Connecting to ${w.ssid ?: "Wi-Fi"}…" else "Not connected"
            cardBody.text = "Send the Wi-Fi details over Bluetooth from Home Assistant, or choose a network here."
        }
        if (!w.online && inst?.phase == InstallerWifi.Phase.FAILED) {
            cardNote.text = "Couldn't join ${inst.ssid}: ${inst.detail ?: "unknown error"}"
            cardNote.setTextColor(ROSE)
            cardNote.visibility = View.VISIBLE
        } else {
            w.message?.let { cardNote.text = it; cardNote.visibility = View.VISIBLE }
        }
        if (!w.online) {
            improvTile.bind(m.improv) { a -> onImprovAction?.invoke(a) }
        }
        // The alternative to Bluetooth: Android's Wi-Fi picker. Ghost style, so it doesn't compete
        // with the Bluetooth block's Allow.
        secondary.text = "Choose network"
        secondary.visibility = View.VISIBLE
        secondary.setOnClickListener { onChooseNetwork?.invoke() }
    }

    private fun number(id: String) = flow.indexOf(id) + 1

    /** A pane's step: the screen sets the label, the step fills the rest ([paneCard]). */
    private fun bindPaneStep(step: SetupStep, stepChanged: Boolean) {
        cardLabel.text = "Step ${number(step.id)} · ${step.title}"
        status.visibility = View.GONE
        screens.getValue(step.id).bind(paneCard, stepChanged)
    }

    private val paneCard = object : SetupCard {
        override fun title(text: String) { cardTitle.text = text }

        override fun body(text: String?) {
            if (text == null) cardBody.visibility = View.GONE
            else { cardBody.text = text; cardBody.visibility = View.VISIBLE }
        }

        override fun status(text: String?, tone: SetupCard.Tone) {
            if (text == null) { status.visibility = View.GONE; return }
            status.text = text
            status.setTextColor(when (tone) { SetupCard.Tone.MUTED -> FA; SetupCard.Tone.AMBER -> AMBER; SetupCard.Tone.GREEN -> GREEN; SetupCard.Tone.ROSE -> ROSE })
            status.visibility = View.VISIBLE
        }

        override fun qr(url: String?) {
            if (url == null) { qrTile.visibility = View.GONE; return }
            if (qrFor != url) {
                qrImage.setImageBitmap(QrCode.bitmap(url, px(186f)))
                qrFor = url
            }
            qrTile.visibility = View.VISIBLE
        }

        override fun content(view: View?) {
            if (view == null) { slot.visibility = View.GONE; return }
            if (view.parent !== slot) {
                slot.removeAllViews()
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                slot.addView(view, LinearLayout.LayoutParams(-1, -2))
            }
            slot.visibility = View.VISIBLE
        }

        override fun primary(text: String, enabled: Boolean, onClick: () -> Unit) {
            primary.text = text
            primary.isEnabled = enabled
            primary.alpha = if (enabled) 1f else 0.5f
            primary.visibility = View.VISIBLE
            primary.setOnClickListener { onClick() }
        }

        override fun secondary(text: String?, onClick: () -> Unit) {
            if (text == null) { secondary.visibility = View.GONE; return }
            secondary.text = text
            secondary.visibility = View.VISIBLE
            secondary.setOnClickListener { onClick() }
        }

        override fun tightButtons() { buttonsLp.topMargin = px(12f) }
    }

    private fun reveal() {
        if (isShowing && visibility == View.VISIBLE && alpha == 1f) return
        isShowing = true
        animate().cancel()
        if (visibility != View.VISIBLE) alpha = 0f
        visibility = View.VISIBLE
        bringToFront()
        animate().alpha(1f).setDuration(250).start()
    }

    private fun submit() {
        val v = urlField.text.toString().trim()
        if (v.isEmpty()) return
        primary.isEnabled = false
        status.visibility = View.VISIBLE
        status.setTextColor(MU)
        status.text = "Checking…"
        hideKeyboard()
        onManualUrl?.invoke(v)
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    // ------------------------------------------------------------------ pieces

    private enum class Dot { DONE, CURRENT, PENDING }

    /** Improv inline: a toned dot + label, a title, one detail line and up to two buttons. */
    private inner class ImprovTile {
        private val dot = View(context)
        private val l = label("").apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; isAllCaps = true } // caps after setSingleLine, which replaces them
        private val title = text(17f, TX, MEDIUM).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        private val detail = text(14f, MU, REGULAR).apply { setLineSpacing(0f, 1.2f); maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        private val b1 = button("", Kind.ACCENT).apply { compact() }
        private val b2 = button("", Kind.GHOST).apply { compact() }
        private val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(b1, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8f) })
            addView(b2, LinearLayout.LayoutParams(-2, -2))
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(18f)
            setPadding(px(16f), px(12f), px(16f), px(14f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dot, LinearLayout.LayoutParams(px(8f), px(8f)).apply { marginEnd = px(8f) })
                addView(l, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
            addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(3f) })
            addView(actions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(10f) })
        }

        private fun TextView.compact() {
            textSize = 15f
            setPadding(px(18f), px(9f), px(18f), px(9f))
        }

        fun bind(line: WifiStep.ImprovLine, onAction: (WifiStep.Action) -> Unit) {
            val color = when (line.tone) {
                WifiStep.Tone.MUTED -> FA; WifiStep.Tone.AMBER -> AMBER; WifiStep.Tone.GREEN -> GREEN; WifiStep.Tone.ROSE -> ROSE
            }
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            l.text = line.label
            title.text = line.title
            detail.text = line.detail
            val btns = listOf(b1, b2)
            btns.forEachIndexed { i, b ->
                val a = line.actions.getOrNull(i)
                if (a == null) {
                    b.visibility = View.GONE
                    b.setOnClickListener(null)
                } else {
                    b.text = a.label
                    b.visibility = View.VISIBLE
                    b.setOnClickListener { onAction(a) }
                }
            }
            // Allow is the one accent button; Use Bluetooth / Try again are accent when they're alone.
            restyle(b1, accent = line.actions.firstOrNull() != WifiStep.Action.NOT_NOW && line.actions.firstOrNull() != WifiStep.Action.STOP)
            actions.visibility = if (line.actions.isEmpty()) View.GONE else View.VISIBLE
            view.visibility = View.VISIBLE
        }
    }

    private fun restyle(b: TextView, accent: Boolean) {
        b.setTextColor(if (accent) BG else TX)
        b.background = GradientDrawable().apply {
            cornerRadius = 16 * dp
            if (accent) setColor(AMBER) else { setColor(S2); setStroke(px(1f).coerceAtLeast(1), BD) }
        }
    }

    private inner class StepRow(name: String) {
        private val dot = CheckDot(filled = false)
        private val title = text(17f, TX, MEDIUM).apply { text = name; setSingleLine() }
        private val detail = text(13.5f, MU, REGULAR).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(px(30f), px(30f)).apply { marginEnd = px(14f) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2f) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }

        fun bind(n: Int, state: Dot, detailText: String) {
            dot.set(n, state)
            title.setTextColor(if (state == Dot.PENDING) FA else TX)
            detail.text = detailText
            detail.visibility = if (detailText.isEmpty()) View.GONE else View.VISIBLE
            detail.setTextColor(when (state) { Dot.DONE -> MU; Dot.CURRENT -> AMBER; Dot.PENDING -> FA })
        }
    }

    private inner class InfoTile {
        private val l = label("").apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; isAllCaps = true } // caps after setSingleLine, which replaces them
        private val v = text(17f, TX, REGULAR).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(18f)
            setPadding(px(16f), px(12f), px(16f), px(12f))
            addView(l)
            addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        }

        fun bind(label: String, value: String) {
            l.text = label
            v.text = value
            view.visibility = View.VISIBLE
        }
    }

    /** Step marker: green disc with a check (done), amber ring + number (current), faint ring (pending). */
    private inner class CheckDot(private val filled: Boolean) : View(context) {
        private var n = 0
        private var state = if (filled) Dot.DONE else Dot.PENDING
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        fun set(number: Int, s: Dot) {
            if (n == number && state == s) return
            n = number; state = s
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = minOf(w, h) / 2f
            val cx = w / 2f; val cy = h / 2f
            when (state) {
                Dot.DONE -> {
                    paint.style = Paint.Style.FILL; paint.color = GREEN
                    c.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.STROKE; paint.color = BG
                    paint.strokeWidth = r * 0.22f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
                    path.reset()
                    path.moveTo(cx - r * 0.42f, cy + r * 0.02f)
                    path.lineTo(cx - r * 0.12f, cy + r * 0.32f)
                    path.lineTo(cx + r * 0.45f, cy - r * 0.30f)
                    c.drawPath(path, paint)
                }
                else -> {
                    val color = if (state == Dot.CURRENT) AMBER else Color.argb(46, 255, 255, 255)
                    paint.style = Paint.Style.STROKE; paint.color = color; paint.strokeWidth = 2 * dp
                    c.drawCircle(cx, cy, r - dp, paint)
                    paint.style = Paint.Style.FILL
                    paint.color = if (state == Dot.CURRENT) AMBER else FA
                    paint.textSize = r * 0.95f
                    paint.typeface = Inter.typeface(context, MEDIUM, 14f)
                    paint.textAlign = Paint.Align.CENTER
                    c.drawText(n.toString(), cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
                }
            }
        }
    }

    private fun label(s: String) = text(11.5f, FA, MEDIUM).apply { letterSpacing = 0.14f; isAllCaps = true; text = s }

    private fun text(sp: Float, color: Int, weight: Int) = TextView(context).apply {
        textSize = sp; setTextColor(color); typeface = Inter.typeface(context, weight, sp)
    }

    private fun glass(radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(S1)
        setStroke(px(1f).coerceAtLeast(1), BD)
    }

    private enum class Kind { ACCENT, GHOST }

    private fun button(label: String, kind: Kind) = TextView(context).apply {
        text = label
        textSize = 16f
        setTextColor(if (kind == Kind.ACCENT) BG else TX)
        typeface = Inter.typeface(context, MEDIUM, 16f)
        background = GradientDrawable().apply {
            cornerRadius = 16 * dp
            if (kind == Kind.ACCENT) setColor(AMBER) else { setColor(S2); setStroke(px(1f).coerceAtLeast(1), BD) }
        }
        setPadding(px(20f), px(13f), px(20f), px(13f))
        isClickable = true
        isFocusable = true
    }

    companion object {
        // tablet-home-card.compact.js design tokens
        private val BG = Color.parseColor("#0a0b0f")
        private val S1 = Color.argb((0.045f * 255).toInt(), 255, 255, 255)
        private val S2 = Color.argb((0.08f * 255).toInt(), 255, 255, 255)
        private val BD = Color.argb((0.07f * 255).toInt(), 255, 255, 255)
        private val TX = Color.parseColor("#f4f4f5")
        private val MU = Color.parseColor("#a1a1aa")
        private val FA = Color.parseColor("#63636b")
        private val AMBER = Color.parseColor("#fbbf24")
        private val GREEN = Color.parseColor("#34d399")
        private val ROSE = Color.parseColor("#fb7185")
        private val LIGHT = Inter.LIGHT
        private val REGULAR = Inter.REGULAR
        private val MEDIUM = Inter.MEDIUM
    }
}
