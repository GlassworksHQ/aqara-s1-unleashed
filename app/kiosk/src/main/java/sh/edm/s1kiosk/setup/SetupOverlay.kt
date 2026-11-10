package sh.edm.s1kiosk.setup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import sh.edm.s1kiosk.auth.OAuth
import sh.edm.s1kiosk.radio.RadioMode

/**
 * The first-boot setup screen (docs/onboarding.md, step 4): a native, full-screen overlay over the
 * GeckoView in the dashboard's visual language (tablet-home-card.compact.js tokens: bg #0a0b0f with
 * sky/violet radial glows, glass cards rgba(255,255,255,.045) + .07 border, 22-26 px radii,
 * uppercase letter-spaced labels in #63636b, big light headings, amber / green accents).
 *
 * Left: the steps (Wi-Fi, Add to Home Assistant, Radio, Sign in, Done) with checks. Right: a glass
 * card for the current step. It replaces the earlier SignInOverlay (its QR code and texts are the
 * Sign-in step). The Wi-Fi step shows Improv (Wi-Fi over Bluetooth) inline, Allow included, so no
 * separate ImprovOverlay window covers this screen. Laid out for 960×480 dp (1440×720 px at
 * density 1.5). Main thread only.
 */
class SetupOverlay(context: Context) : FrameLayout(context) {

    data class Model(
        val step: SetupStep,
        val wifiDone: Boolean,
        val haDone: Boolean,
        val signinDone: Boolean,
        val wifi: WifiSetupStatus.Snapshot,
        /** Home Assistant's URL, null while it hasn't been found. */
        val haUrl: String?,
        /** How it was found (`mdns`, `esphome`, `manual`, or "" for a saved dashboard URL). */
        val haSource: String,
        val panelIp: String?,
        val signedIn: Boolean,
        val preview: Boolean = false,
        val radioDone: Boolean = true,
        /** Improv's status block for the Wi-Fi step (SetupDecision.improv). */
        val improv: WifiStep.ImprovLine = WifiStep.improv(ImprovFacts()),
        /** A Wi-Fi join sent from the computer (web installer), if any. */
        val installer: InstallerWifi? = null,
        val radio: Radio = Radio(),
    )

    /** The radio step: the mode now in effect, and what the last "Apply" did (null = not yet). */
    data class Radio(
        val current: RadioMode = RadioMode.DEFAULT,
        val applied: RadioStep.Applied? = null,
        /** A request is running on the radio thread. */
        val busy: Boolean = false,
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
    /** Radio step: apply this mode (RadioController path); re-render with [Model.radio] to show the result. */
    var onRadioApply: ((RadioMode) -> Unit)? = null
    /** Radio step: skip (= Off). */
    var onRadioSkip: (() -> Unit)? = null
    /** Radio step: the applied mode is fine, go on. */
    var onRadioContinue: (() -> Unit)? = null

    /** True while visible (including the Done fade-out). */
    var isShowing = false
        private set

    var model: Model? = null
        private set

    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private var finishing: Runnable? = null
    private var qrFor: String? = null
    private var radioSelected: RadioMode = RadioMode.DEFAULT

    // ---- left: the steps
    private val brand = label("S1 Panel · Setup")
    private val railTitle = text(30f, TX, LIGHT).apply { letterSpacing = -0.02f; text = "Let's set up\nyour panel"; setLineSpacing(0f, 1.0f) }
    /** One row per [SetupStep], in its order (index = ordinal). */
    private val rows = listOf(
        StepRow("Wi-Fi"), StepRow("Add to Home Assistant"), StepRow("Radio"), StepRow("Sign in"), StepRow("Done"),
    )
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
    private val qrImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val qrTile = FrameLayout(context).apply {
        background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(Color.WHITE) }
        setPadding(px(12f), px(12f), px(12f), px(12f))
        addView(qrImage, LayoutParams(-1, -1))
    }
    private val bigCheck = CheckDot(filled = true).apply { visibility = View.GONE }
    private val improvTile = ImprovTile()
    private val radioTiles = RadioStep.OPTIONS.map { RadioTile(it) }
    private val radioRow = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            radioTiles.forEachIndexed { i, t ->
                addView(t.view, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = px(10f) })
            }
        }, LinearLayout.LayoutParams(-1, -2))
        // Roles that aren't ready yet: a compact, dimmed row under the real options, not selectable.
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            RadioStep.COMING_SOON.forEachIndexed { i, o ->
                addView(soonTile(o), LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = px(10f) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
    }

    private val cardColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(bigCheck, LinearLayout.LayoutParams(px(64f), px(64f)).apply { bottomMargin = px(18f) })
        addView(cardLabel)
        addView(cardTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        addView(cardBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        addView(cardNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        addView(tiles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18f) })
        addView(improvTile.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
        addView(radioRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
        addView(urlField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18f) })
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(20f) })
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = glass(26f)
        setPadding(px(30f), px(26f), px(30f), px(26f))
        addView(qrTile, LinearLayout.LayoutParams(px(210f), px(210f)).apply { marginEnd = px(30f) })
        addView(cardColumn, LinearLayout.LayoutParams(0, -2, 1f))
    }

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
        val m = (from ?: return).copy(step = SetupStep.DONE, wifiDone = true, haDone = true, radioDone = true, signinDone = true)
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
        val wifiDetail = when {
            m.wifiDone && w.ethernet -> "Ethernet"
            m.wifiDone -> w.ssid ?: "Connected"
            w.state == WifiSetupStatus.State.CONNECTING -> w.ssid?.let { "Joining $it…" } ?: "Connecting…"
            m.improv.phase == WifiStep.Phase.ASKING -> "Allow Bluetooth setup"
            m.improv.phase in setOf(WifiStep.Phase.READY, WifiStep.Phase.AUTHORIZED) -> "Bluetooth setup is on"
            else -> "Not connected"
        }
        val radioDetail = when {
            m.radioDone -> RadioStep.OPTIONS.firstOrNull { it.mode == m.radio.current }?.title ?: m.radio.current.label
            else -> "Zigbee, Thread or off"
        }
        val haDetail = when {
            m.haDone -> "Added"
            m.haUrl == null -> "Looking for Home Assistant…"
            else -> "Waiting to be added"
        }
        val signinDetail = when {
            m.signedIn -> "Signed in"
            m.signinDone -> "Session active"
            else -> "Scan the QR code"
        }
        val done = listOf(m.wifiDone, m.haDone, m.radioDone, m.signinDone, m.step == SetupStep.DONE)
        val details = listOf(wifiDetail, haDetail, radioDetail, signinDetail, if (m.step == SetupStep.DONE) "Loading your dashboard" else "")
        rows.forEachIndexed { i, r ->
            val current = m.step.ordinal == i
            r.bind(i + 1, if (done[i]) Dot.DONE else if (current) Dot.CURRENT else Dot.PENDING, details[i])
        }
        previewChip.visibility = if (m.preview) View.VISIBLE else View.GONE
    }

    private fun bindCard(m: Model, stepChanged: Boolean) {
        // Defaults: everything optional hidden.
        qrTile.visibility = View.GONE
        bigCheck.visibility = View.GONE
        cardNote.visibility = View.GONE
        cardNote.setTextColor(AMBER)
        tiles.visibility = View.GONE
        tileB.view.visibility = View.VISIBLE
        improvTile.view.visibility = View.GONE
        radioRow.visibility = View.GONE
        primary.isEnabled = true
        primary.alpha = 1f
        // The URL field is never reset to GONE and back: a view that goes GONE (even for one frame)
        // loses focus, and this runs on every discovery/Wi-Fi refresh, i.e. while the user types.
        var showUrl = false
        buttons.visibility = View.VISIBLE
        primary.visibility = View.GONE
        secondary.visibility = View.GONE
        primary.setOnClickListener(null)
        secondary.setOnClickListener(null)
        if (stepChanged) status.visibility = View.GONE
        cardTitle.textSize = when (m.step) {
            SetupStep.SIGNIN -> 26f // the QR takes ~240 dp of the card
            SetupStep.WIFI, SetupStep.RADIO -> 26f // room for the Bluetooth block / the three options
            else -> 30f
        }

        when (m.step) {
            SetupStep.WIFI -> bindWifi(m)
            SetupStep.RADIO -> bindRadio(m, stepChanged)
            SetupStep.HA -> {
                cardLabel.text = "Step 2 · Home Assistant"
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
            SetupStep.SIGNIN -> {
                val ip = m.panelIp ?: "<panel-ip>"
                val url = "http://$ip:${OAuth.SIGNIN_PORT}/signin"
                if (qrFor != url) {
                    qrImage.setImageBitmap(qrBitmap(url, px(186f)))
                    qrFor = url
                }
                qrTile.visibility = View.VISIBLE
                cardLabel.text = "Step 4 · Sign in"
                cardTitle.text = "Scan with your phone to sign in to Home Assistant"
                cardBody.text = "Or open $url on a phone or computer on the same network."
                tiles.visibility = View.VISIBLE
                tileA.bind("Home Assistant", m.haUrl ?: "")
                tileB.view.visibility = View.GONE
                secondary.text = "Sign in on this panel instead"
                secondary.visibility = View.VISIBLE
                secondary.setOnClickListener { onUsePanel?.invoke() }
            }
            SetupStep.DONE -> {
                bigCheck.visibility = View.VISIBLE
                cardLabel.text = "Done"
                cardTitle.text = "You're all set"
                cardBody.text = "Loading your dashboard…"
                buttons.visibility = View.GONE
            }
        }
        val urlVis = if (showUrl) View.VISIBLE else View.GONE
        if (urlField.visibility != urlVis) urlField.visibility = urlVis
        if (!showUrl) hideKeyboard()
    }

    private fun bindWifi(m: Model) {
        cardLabel.text = "Step 1 · Wi-Fi"
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

    private fun bindRadio(m: Model, stepChanged: Boolean) {
        val r = m.radio
        if (stepChanged) {
            radioSelected = RadioStep.OPTIONS.firstOrNull { it.mode == r.current }?.mode ?: RadioMode.DEFAULT
        }
        cardLabel.text = "Step 3 · Radio"
        cardTitle.text = "What should the panel's radio do?"
        cardBody.text = "The panel has a Zigbee and Thread radio. ${RadioStep.LATER}"
        radioRow.visibility = View.VISIBLE
        status.visibility = View.GONE
        radioTiles.forEach { t -> t.bind(t.option.mode == radioSelected) }

        val applied = r.applied?.takeIf { it.mode == radioSelected }
        when {
            r.busy -> showRadioNote("Applying ${titleOf(radioSelected)}…", AMBER)
            applied != null -> when (applied.result) {
                RadioStep.Result.OK -> showRadioNote(applied.status, GREEN)
                RadioStep.Result.APPLYING -> showRadioNote("Applying: ${applied.status}", AMBER)
                RadioStep.Result.REFUSED, RadioStep.Result.ERROR -> showRadioNote(applied.status, ROSE)
            }
            radioSelected == RadioMode.ZIGBEE_TCP -> showRadioNote(
                "Then in Home Assistant: add ZHA, radio type EZSP, serial path ${RadioStep.zhaPath(m.panelIp)}", FA,
            )
            else -> {}
        }
        if (RadioStep.canContinue(applied) && !r.busy) {
            primary.text = "Continue"
            primary.setOnClickListener { onRadioContinue?.invoke() }
        } else {
            primary.text = if (r.busy) "Applying…" else "Apply"
            primary.isEnabled = !r.busy
            primary.alpha = if (r.busy) 0.5f else 1f
            primary.setOnClickListener { if (!r.busy) onRadioApply?.invoke(radioSelected) }
        }
        primary.visibility = View.VISIBLE
        secondary.text = "Skip (Off)"
        secondary.visibility = if (r.busy) View.GONE else View.VISIBLE
        secondary.setOnClickListener { onRadioSkip?.invoke() }
    }

    /** The line under the options (the `status` view, which only the HA step uses otherwise). */
    private fun showRadioNote(text: String, color: Int) {
        status.text = text
        status.setTextColor(color)
        status.visibility = View.VISIBLE
    }

    private fun titleOf(mode: RadioMode) = RadioStep.OPTIONS.firstOrNull { it.mode == mode }?.title ?: mode.label

    private fun selectRadio(mode: RadioMode) {
        if (radioSelected == mode) return
        radioSelected = mode
        model?.let { bindCard(it, stepChanged = false) }
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
        private val l = label("").apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
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

    /** One radio option: title (+ "experimental"), one line, amber outline when selected. */
    private inner class RadioTile(val option: RadioStep.Option) {
        private val title = text(16f, TX, MEDIUM).apply { text = option.title; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        private val expTag = label("Experimental").apply { setTextColor(AMBER); visibility = if (option.experimental) View.VISIBLE else View.GONE }
        private val line = text(13f, MU, REGULAR).apply { text = option.line; setLineSpacing(0f, 1.2f) }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14f), px(12f), px(14f), px(12f))
            addView(title)
            addView(expTag, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(2f) })
            addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
            isClickable = true
            isFocusable = true
            setOnClickListener { selectRadio(option.mode) }
        }

        fun bind(selected: Boolean) {
            view.background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                setColor(if (selected) S2 else S1)
                setStroke(px(if (selected) 2f else 1f).coerceAtLeast(1), if (selected) AMBER else BD)
            }
            title.setTextColor(if (selected) TX else MU)
        }
    }

    /** A "Coming soon" radio role: title + tag on one line, its one line under it; no click. */
    private fun soonTile(o: RadioStep.Soon): View {
        val title = text(14f, MU, MEDIUM).apply { text = o.title; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val tag = label("Coming soon").apply { setTextColor(FA) }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tag, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8f) })
        }
        val line = text(12f, FA, REGULAR).apply { text = o.line; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14f), px(8f), px(14f), px(8f))
            addView(head)
            addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2f) })
            isClickable = false
            isFocusable = false
            contentDescription = "${o.title}, coming soon"
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(S1)
                setStroke(px(1f).coerceAtLeast(1), BD)
            }
            alpha = 0.8f
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
        private val l = label("").apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
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
                    paint.typeface = MEDIUM
                    paint.textAlign = Paint.Align.CENTER
                    c.drawText(n.toString(), cx, cy - (paint.descent() + paint.ascent()) / 2f, paint)
                }
            }
        }
    }

    private fun label(s: String) = text(11.5f, FA, MEDIUM).apply { letterSpacing = 0.14f; isAllCaps = true; text = s }

    private fun text(sp: Float, color: Int, tf: Typeface) = TextView(context).apply {
        textSize = sp; setTextColor(color); typeface = tf
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
        typeface = MEDIUM
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
        private val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        private val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        fun qrBitmap(text: String, size: Int): Bitmap {
            val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val px = IntArray(m.width * m.height)
            for (y in 0 until m.height) for (x in 0 until m.width) {
                px[y * m.width + x] = if (m[x, y]) BG else Color.WHITE
            }
            return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
        }
    }
}
