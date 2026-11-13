package sh.edm.s1kiosk.setup

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import dev.glassworks.pane.PaneChoice
import sh.edm.s1kiosk.auth.OAuth
import sh.edm.s1kiosk.esphome.PanelDevice
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Glass.Companion.AMBER
import sh.edm.s1kiosk.ui.Glass.Companion.FA
import sh.edm.s1kiosk.ui.Glass.Companion.GREEN
import sh.edm.s1kiosk.ui.Glass.Companion.MU
import sh.edm.s1kiosk.ui.Glass.Companion.ROSE
import sh.edm.s1kiosk.ui.Glass.Companion.TX
import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.ui.StackView
import sh.edm.s1kiosk.ui.StackView.Look

/**
 * The first-boot setup screen (docs/onboarding.md, step 4), as drawn in #63: a native, full-screen
 * overlay over the page. Left, the steps (Wi-Fi, Home Assistant, Panes, Sign in) and the stack, whose
 * plates set one per step. Right, a glass card for the current step: one idea, a short heading, one
 * white button. Done is the lit stack on its own, the same picture as boot.
 *
 * Panes asks what the panel should do: one card per pane that offers one ([PaneChoice]), applied
 * together with Continue. The Wi-Fi step shows Improv (Wi-Fi over Bluetooth) inline, Allow included, so
 * no separate ImprovOverlay window covers this screen. Laid out for 960×480 dp (1440×720 px at density
 * 1.5). Main thread only.
 */
class SetupOverlay(
    context: Context,
    /** The panes' cards on the Panes step (PaneDefinition.setupChoice), in order. None: no Panes step. */
    private val choices: List<PaneChoice> = emptyList(),
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
    /** Panes applied with Continue. [restart]: a card that only takes effect at start changed. */
    var onPanesDone: ((restart: Boolean) -> Unit)? = null

    /** True while visible (including the Done fade-out). */
    var isShowing = false
        private set

    var model: Model? = null
        private set

    private val g = Glass(context)
    private fun px(v: Float) = g.px(v)
    private var finishing: Runnable? = null
    private var qrFor: String? = null
    private val main = Handler(Looper.getMainLooper())

    /** The rail's steps, in order (Done is a screen of its own, not a row). */
    private val flow = SetupFlow.ids(choices.isNotEmpty()).filter { it != SetupFlow.DONE }

    // ---- left: the brand, the steps, the stack
    private val rows = flow.map { StepRow(TITLES.getValue(it)) }
    private val railStack = StackView(context)
    private val previewChip = g.plain("Preview · Close").apply { textSize = 14f; setOnClickListener { onClosePreview?.invoke() } }

    private val rail = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4f), 0, 0, 0)
            addView(StackView(context), LinearLayout.LayoutParams(px(24f), px(23f)).apply { marginEnd = px(14f) })
            addView(g.text(19f, TX, Inter.REGULAR).apply { text = "Glassworks" })
        })
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { i, r -> list.addView(r.view, LinearLayout.LayoutParams(-1, px(39f)).apply { if (i > 0) topMargin = px(5f) }) }
        addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(37f) })
        addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(railStack, LinearLayout.LayoutParams(px(78f), px(76f)).apply { marginStart = px(6f); bottomMargin = px(12f) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(previewChip)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    // ---- right: the current step's card
    private val cardLabel = g.label()
    private val cardTitle = g.heading(45f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val cardBody = g.text(16f, MU).apply { setLineSpacing(0f, 1.3f) }
    /** The step's own view: the Bluetooth tile, the waiting line, the URL field, the pane cards. */
    private val slot = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val slotLp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(26f) }
    private val status = g.text(15f, MU).apply { setLineSpacing(0f, 1.2f) }
    private val foot = g.text(15f, FA)
    private val primary = g.primary("").apply { setPadding(px(30f), px(15f), px(30f), px(15f)) }
    private val ghost = g.ghost("").apply { setPadding(px(28f), px(15f), px(28f), px(15f)) }
    private val plainLeft = g.plain("").apply { setPadding(0, px(14f), px(12f), px(14f)) }
    private val plain = g.plain("")
    private val buttonRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ghost, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(22f) })
        addView(plainLeft, LinearLayout.LayoutParams(-2, -2))
        addView(foot, LinearLayout.LayoutParams(-2, -2))
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(plain, LinearLayout.LayoutParams(-2, -2))
        addView(primary, LinearLayout.LayoutParams(-2, -2))
    }
    private val qrImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val qrTile = FrameLayout(context).apply {
        background = GradientDrawable().apply { cornerRadius = 20 * g.dp; setColor(Color.WHITE) }
        setPadding(px(14f), px(14f), px(14f), px(14f))
        addView(qrImage, LayoutParams(-1, -1))
    }
    private val qrGlow = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 125 * g.dp
            colors = intArrayOf(Color.argb(70, 56, 189, 248), Color.TRANSPARENT)
        }
        addView(qrTile, LayoutParams(px(226f), px(226f), Gravity.CENTER))
        visibility = View.GONE
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(cardLabel)
        addView(cardTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
        addView(cardBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
        addView(slot, slotLp)
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
        addView(buttonRow, LinearLayout.LayoutParams(-1, -2))
    }
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = g.surface(34f)
        setPadding(px(41f), px(36f), px(28f), px(30f))
        addView(column, LinearLayout.LayoutParams(0, -1, 1f))
        addView(qrGlow, LinearLayout.LayoutParams(px(238f), -1))
    }
    private val wizard = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(px(49f), px(44f), px(45f), px(40f))
        addView(rail, LinearLayout.LayoutParams(px(227f), -1).apply { marginEnd = px(26f) })
        addView(card, LinearLayout.LayoutParams(0, -1, 1f))
    }

    // ---- Done: the lit stack on its own
    private val doneView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(StackView(context), LinearLayout.LayoutParams(px(108f), px(103f)))
        addView(g.heading(44f).apply { text = "You're all set." }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(40f) })
        addView(g.text(17f, MU).apply { text = "Opening your dashboard…" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(18f) })
        visibility = View.GONE
    }

    // ---- the steps' own views
    private val improvTile = ImprovTile()
    private val waiting = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Pulse(), LinearLayout.LayoutParams(px(44f), px(44f)).apply { marginEnd = px(19f) })
        addView(g.text(17f, MU).apply { text = "Waiting for Home Assistant…" })
    }
    private val urlField = EditText(context).apply {
        textSize = 18f
        typeface = Inter.typeface(context, Inter.REGULAR, 18f)
        setTextColor(TX)
        setHintTextColor(FA)
        hint = "http://homeassistant.local:8123"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setSingleLine()
        background = g.surface(16f, Glass.S2)
        setPadding(px(18f), px(14f), px(18f), px(14f))
        setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
        }
    }
    private val panes = PanesView()

    init {
        background = Backdrop()
        isClickable = true // swallow touches meant for the page underneath
        isFocusable = true
        elevation = 20 * g.dp
        addView(wizard, LayoutParams(-1, -1))
        addView(doneView, LayoutParams(-1, -1))
        visibility = View.GONE
    }

    /** Shows (or updates) the screen for [m]. */
    fun render(m: Model) {
        cancelFinish()
        val stepChanged = model?.step != m.step
        model = m
        bind(m, stepChanged)
        reveal()
    }

    /** Shows Done, calls [loaded] (load the dashboard behind it), then fades out. */
    fun finish(from: Model? = model, holdMs: Long = 1_800, loaded: () -> Unit) {
        val m = (from ?: return).copy(step = SetupFlow.DONE, done = flow.toSet())
        cancelFinish()
        model = m
        bind(m, true)
        reveal()
        loaded()
        val r = Runnable { finishing = null; hide() }
        finishing = r
        postDelayed(r, holdMs)
    }

    fun manualResult(ok: Boolean, message: String) {
        primary.isEnabled = true
        primary.alpha = 1f
        showStatus(message, if (ok) GREEN else ROSE)
    }

    /**
     * Panes is applying, or its last Continue failed: the step stays up whatever the rules say. A pane's
     * step can look done mid-way (the radio stores its mode before the helper answers), and a failure
     * must stay where it can be retried.
     */
    val holdsPanes: Boolean get() = isShowing && model?.step == SetupFlow.PANES && (panes.applying || panes.problem != null)

    /** Improv identify: pulse the Bluetooth tile a few times. */
    fun flashImprov() {
        val v = improvTile.view
        if (!isShowing || v.parent == null) return
        v.animate().cancel()
        android.animation.ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.03f).apply {
            duration = 220; repeatMode = ValueAnimator.REVERSE; repeatCount = 7
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

    private fun bind(m: Model, stepChanged: Boolean) {
        val done = m.step == SetupFlow.DONE
        doneView.visibility = if (done) View.VISIBLE else View.GONE
        wizard.visibility = if (done) View.GONE else View.VISIBLE
        if (done) { hideKeyboard(); return }
        bindRail(m)
        bindCard(m, stepChanged)
    }

    private fun bindRail(m: Model) {
        val current = flow.indexOf(m.step).coerceAtLeast(0)
        rows.forEachIndexed { i, r ->
            val id = flow[i]
            r.bind(if (id == m.step) Dot.CURRENT else if (id in m.done) Dot.DONE else Dot.PENDING)
        }
        // The plates set one per step: none on the first, all three on the last.
        val set = if (flow.size < 2) 3 else 3 * current / (flow.size - 1)
        railStack.looks = List(3) { plate -> if (2 - plate < set) Look.OUTLINE else Look.GHOST }
        previewChip.visibility = if (m.preview) View.VISIBLE else View.GONE
    }

    private fun bindCard(m: Model, stepChanged: Boolean) {
        qrGlow.visibility = View.GONE
        cardBody.visibility = View.VISIBLE
        cardTitle.textSize = 45f
        slotLp.topMargin = px(26f)
        primary.visibility = View.GONE; ghost.visibility = View.GONE
        plain.visibility = View.GONE; plainLeft.visibility = View.GONE; foot.visibility = View.GONE
        primary.isEnabled = true; primary.alpha = 1f
        listOf(primary, ghost, plain, plainLeft).forEach { it.setOnClickListener(null) }
        if (stepChanged) status.visibility = View.GONE
        cardLabel.text = "Step ${flow.indexOf(m.step) + 1} of ${flow.size}"
        // The URL field never goes GONE and back while shown: a view that goes GONE (even for one
        // frame) loses focus, and this runs on every discovery/Wi-Fi refresh, i.e. while the user types.
        var content: View? = null
        when (m.step) {
            SetupFlow.WIFI -> content = bindWifi(m)
            SetupFlow.HA -> content = bindHa(m)
            SetupFlow.PANES -> content = bindPanes(m, stepChanged)
            SetupFlow.SIGNIN -> bindSignin(m)
        }
        setContent(content)
        if (content !== urlField) hideKeyboard()
    }

    private fun setContent(v: View?) {
        if (v == null) { if (slot.childCount > 0) slot.removeAllViews(); slot.visibility = View.GONE; return }
        slot.visibility = View.VISIBLE
        if (slot.childCount == 1 && slot.getChildAt(0) === v) return
        slot.removeAllViews()
        (v.parent as? ViewGroup)?.removeView(v)
        slot.addView(v, LinearLayout.LayoutParams(-1, -2))
    }

    private fun bindWifi(m: Model): View? {
        val w = m.wifi
        val inst = m.installer
        var tile = false
        when {
            w.online -> {
                cardTitle.text = if (w.ethernet) "Connected over Ethernet." else "Connected to ${w.ssid ?: "Wi-Fi"}."
                cardBody.text = "The panel is on the network."
            }
            inst?.phase == InstallerWifi.Phase.CONNECTING -> {
                cardTitle.text = "Connecting to ${inst.ssid}…"
                cardBody.text = "Wi-Fi details sent from your computer." + (inst.detail?.let { " $it" } ?: " This takes up to 30 seconds.")
            }
            else -> {
                cardTitle.text = if (w.state == WifiSetupStatus.State.CONNECTING) "Connecting to ${w.ssid ?: "Wi-Fi"}…" else "Connect to Wi-Fi."
                cardBody.text = "Send it from Home Assistant: it finds this panel over Bluetooth."
                tile = true
            }
        }
        if (!w.online && inst?.phase == InstallerWifi.Phase.FAILED) showStatus("Couldn't join ${inst.ssid}: ${inst.detail ?: "unknown error"}", ROSE)
        else w.message?.let { showStatus(it, AMBER) }
        ghost.text = "Choose a network"
        ghost.visibility = View.VISIBLE
        ghost.setOnClickListener { onChooseNetwork?.invoke() }
        if (!tile) return null
        improvTile.bind(m.improv)
        // Not now and Stop end the Bluetooth window: quiet words next to Choose a network, not a button.
        m.improv.actions.firstOrNull { it == WifiStep.Action.NOT_NOW || it == WifiStep.Action.STOP }?.let { a ->
            plainLeft.text = a.label
            plainLeft.visibility = View.VISIBLE
            plainLeft.setOnClickListener { onImprovAction?.invoke(a) }
        }
        return improvTile.view
    }

    private fun bindHa(m: Model): View {
        if (m.haUrl == null) {
            cardTitle.text = "Find Home Assistant."
            cardBody.text = "The panel is looking for it on the network. If it doesn't show up, enter its address."
            primary.text = "Connect"
            primary.visibility = View.VISIBLE
            primary.setOnClickListener { submit() }
            return urlField
        }
        cardTitle.text = "Add it in Home Assistant."
        cardBody.text = SpannableStringBuilder().apply {
            append("Settings → Devices & services → ")
            val start = length
            append(PanelDevice.FRIENDLY_NAME)
            setSpan(ForegroundColorSpan(TX), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" → Add")
        }
        // Manual add: Add integration → ESPHome → host + port.
        foot.text = "Adding by hand? ${m.panelIp ?: "<panel IP>"} · port 6053"
        foot.visibility = View.VISIBLE
        plain.text = "Skip"
        plain.visibility = View.VISIBLE
        plain.setOnClickListener { onSkipHa?.invoke() }
        return waiting
    }

    private fun bindPanes(m: Model, stepChanged: Boolean): View {
        cardTitle.text = "What should it do?"
        // Four radio modes take two lines of chips: a little less room around them, so all fits.
        cardTitle.textSize = 40f
        slotLp.topMargin = px(16f)
        cardBody.visibility = View.GONE
        if (stepChanged) panes.load()
        panes.next.isEnabled = !panes.applying
        panes.next.alpha = if (panes.applying) 0.5f else 1f
        panes.next.setOnClickListener { panes.apply(m.preview) }
        panes.problem?.let { showStatus(it, ROSE) }
        if (panes.applying) showStatus("Setting up…", AMBER)
        return panes.view
    }

    private fun bindSignin(m: Model) {
        val ip = m.panelIp ?: "<panel-ip>"
        val url = "http://$ip:${OAuth.SIGNIN_PORT}/signin"
        if (qrFor != url) {
            qrImage.setImageBitmap(qrBitmap(url, px(200f)))
            qrFor = url
        }
        qrGlow.visibility = View.VISIBLE
        cardTitle.text = "Scan to sign in."
        cardBody.text = "Sign in to Home Assistant on your phone. Nothing to type here."
        plainLeft.text = "Sign in on this panel instead"
        plainLeft.visibility = View.VISIBLE
        plainLeft.setOnClickListener { onUsePanel?.invoke() }
    }

    private fun showStatus(text: String, color: Int) {
        status.text = text
        status.setTextColor(color)
        status.visibility = View.VISIBLE
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
        primary.alpha = 0.5f
        showStatus("Checking…", AMBER)
        hideKeyboard()
        onManualUrl?.invoke(v)
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    // ------------------------------------------------------------------ pieces

    private enum class Dot { DONE, CURRENT, PENDING }

    private inner class StepRow(name: String) {
        private val dot = RowDot()
        private val title = g.text(16.5f, TX).apply { text = name; setSingleLine() }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(15f), 0, px(15f), 0)
            addView(dot, LinearLayout.LayoutParams(px(9f), px(9f)).apply { marginEnd = px(13f) })
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        }

        fun bind(state: Dot) {
            dot.state = state
            title.setTextColor(when (state) { Dot.CURRENT -> TX; Dot.DONE -> MU; Dot.PENDING -> FA })
            view.background = if (state == Dot.CURRENT) g.surface(12f) else null
        }
    }

    /** Done: a green dot. Current: sky to violet. Not yet: a dashed ring. */
    private inner class RowDot : View(context) {
        var state = Dot.PENDING
            set(v) { if (field != v) { field = v; invalidate() } }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            paint.shader = null; paint.pathEffect = null
            when (state) {
                Dot.DONE -> { paint.style = Paint.Style.FILL; paint.color = GREEN; c.drawCircle(r, r, r, paint) }
                Dot.CURRENT -> {
                    paint.style = Paint.Style.FILL
                    paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Glass.SKY, Glass.VIOLET, Shader.TileMode.CLAMP)
                    c.drawCircle(r, r, r, paint)
                }
                Dot.PENDING -> {
                    paint.style = Paint.Style.STROKE; paint.color = FA; paint.strokeWidth = 1.5f * g.dp
                    paint.pathEffect = DashPathEffect(floatArrayOf(2f * g.dp, 1.6f * g.dp), 0f)
                    c.drawCircle(r, r, r - g.dp, paint)
                }
            }
        }
    }

    /** "Waiting for Home Assistant": a ring with a sky-to-violet dot that breathes. */
    private inner class Pulse : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val r = width / 2f
            paint.shader = null
            paint.style = Paint.Style.FILL; paint.color = Glass.S1
            c.drawCircle(r, r, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * g.dp; paint.color = Color.WHITE
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.argb(90, 56, 189, 248), Color.argb(90, 167, 139, 250), Shader.TileMode.CLAMP)
            c.drawCircle(r, r, r - g.dp, paint)
            paint.shader = null
            paint.color = Color.argb((140 + 80 * kotlin.math.sin(t * Math.PI * 2).toFloat()).toInt().coerceIn(0, 255), 56, 189, 248)
            c.drawCircle(r, r, r * 0.6f, paint)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(r * 0.7f, r * 0.7f, r * 1.3f, r * 1.3f, Glass.SKY, Glass.VIOLET, Shader.TileMode.CLAMP)
            c.drawCircle(r, r, r * 0.3f, paint)
        }
    }

    /** Improv inline: a status dot with its halo, what is happening, and the one thing to do. */
    private inner class ImprovTile {
        private val dot = View(context)
        private val halo = FrameLayout(context).apply { addView(dot, LayoutParams(px(9f), px(9f), Gravity.CENTER)) }
        private val title = g.text(16.5f, TX, Inter.MEDIUM).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        private val detail = g.text(15f, MU).apply { setLineSpacing(0f, 1.2f); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val action = g.primary("").apply { setPadding(px(26f), px(12f), px(26f), px(12f)) }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = g.surface(19f, Glass.S1)
            setPadding(px(13f), px(15f), px(18f), px(15f))
            addView(halo, LinearLayout.LayoutParams(px(18f), px(18f)).apply { marginEnd = px(14f) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(5f) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(14f) })
            addView(action, LinearLayout.LayoutParams(-2, -2))
        }

        fun bind(line: WifiStep.ImprovLine) {
            val color = when (line.tone) {
                WifiStep.Tone.MUTED -> FA; WifiStep.Tone.AMBER -> AMBER; WifiStep.Tone.GREEN -> GREEN; WifiStep.Tone.ROSE -> ROSE
            }
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            halo.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(46, Color.red(color), Color.green(color), Color.blue(color))) }
            title.text = line.title
            detail.text = line.detail
            val a = line.actions.firstOrNull { it != WifiStep.Action.NOT_NOW && it != WifiStep.Action.STOP }
            if (a == null) {
                action.visibility = View.GONE
                action.setOnClickListener(null)
            } else {
                action.text = a.label
                action.visibility = View.VISIBLE
                action.setOnClickListener { onImprovAction?.invoke(a) }
            }
        }
    }

    /**
     * The Panes step: one card per [PaneChoice], two to a row, and the chips of the cards that have
     * options. What the person sets stays here until Continue applies it.
     */
    private inner class PanesView {
        private val on = BooleanArray(choices.size)
        private val option = arrayOfNulls<String>(choices.size)
        private val toggles = ArrayList<Glass.Toggle>()
        private val chipRows = ArrayList<Pair<Int, List<Pair<String, Glass.Chip>>>>()
        var applying = false
            private set
        var problem: String? = null
            private set

        /** A card that only applies at start changed in an earlier attempt that then failed: still owed. */
        private var restartOwed = false

        /**
         * Continue sits at the end of the last row of chips: the radio's four modes take two lines, and
         * the step has no room for a row of its own under them.
         */
        val next = g.primary("Continue").apply { setPadding(px(30f), px(15f), px(30f), px(15f)) }

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            choices.chunked(2).forEachIndexed { r, pair ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEachIndexed { k, c ->
                        addView(card(r * 2 + k, c), LinearLayout.LayoutParams(0, px(60f), 1f).apply { if (k > 0) marginStart = px(11f) })
                    }
                    if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = px(11f) })
                }, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = px(9f) })
            }
            var last: Wrap? = null
            choices.forEachIndexed { i, c ->
                if (c.options.isEmpty()) return@forEachIndexed
                val chips = c.options.map { o -> o.id to g.chip(o.label).apply { setOnClickListener { if (!applying) option[i] = o.id; sync() } } }
                chipRows += i to chips
                last = Wrap(px(10f), px(6f)).apply {
                    addView(g.text(15.5f, MU).apply { text = "${c.optionsLabel}:"; setPadding(0, px(10f), px(2f), px(10f)) })
                    chips.forEach { (_, chip) -> addView(chip) }
                }.also { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(11f) }) }
            }
            val row = last ?: Wrap(0, 0).also { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) }) }
            row.addView(next)
            row.end = next
        }

        private fun card(i: Int, c: PaneChoice): View {
            val t = g.toggle(false) { v -> if (!applying) on[i] = v; sync() } // locked while Continue applies
            toggles += t
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = g.surface(19f, Glass.S1)
                setPadding(px(17f), 0, px(16f), 0)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(g.text(16f, TX, Inter.MEDIUM).apply { text = c.title; setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
                    addView(g.text(14.5f, MU).apply { text = c.detail; setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(5f) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(10f) })
                addView(t)
                isClickable = true
                setOnClickListener { t.performClick() }
            }
        }

        /** Reads every card as it is now (the step was just entered). */
        fun load() {
            choices.forEachIndexed { i, c -> on[i] = c.on(); option[i] = c.option() }
            problem = null
            sync()
        }

        private fun sync() {
            toggles.forEachIndexed { i, t -> t.checked = on[i] }
            for ((i, chips) in chipRows) chips.forEach { (id, chip) ->
                chip.chosen = on[i] && option[i] == id
                chip.isEnabled = on[i] && !applying
            }
        }

        /** Continue: every card, in order, off the main thread; then on, or why not. */
        fun apply(preview: Boolean) {
            if (applying) return
            if (preview) { onPanesDone?.invoke(false); return }
            applying = true
            problem = null
            val wanted = choices.indices.map { on[it] to option[it] }
            restartOwed = restartOwed || choices.indices.any { choices[it].restartsApp && choices[it].on() != on[it] }
            redraw()
            Thread({
                val problems = choices.mapIndexedNotNull { i, c ->
                    runCatching { c.apply(wanted[i].first, wanted[i].second) }.getOrElse { it.message ?: "failed" }?.let { "${c.title}: $it" }
                }
                main.post {
                    applying = false
                    problem = problems.firstOrNull()
                    if (problems.isEmpty()) onPanesDone?.invoke(restartOwed.also { restartOwed = false }) else redraw()
                }
            }, "s1-setup-panes").apply { isDaemon = true }.start()
        }

        private fun redraw() { sync(); model?.let { bindCard(it, stepChanged = false) } }
    }

    /**
     * Lays its children out left to right and wraps to a new line when one doesn't fit; each line's
     * children are centred on it. [end] sits at the right end of the last line.
     */
    private inner class Wrap(private val gapX: Int, private val gapY: Int) : ViewGroup(this@SetupOverlay.context) {
        var end: View? = null
        private val lines = ArrayList<List<View>>()

        override fun onMeasure(w: Int, h: Int) {
            val max = MeasureSpec.getSize(w)
            lines.clear()
            var line = ArrayList<View>(); var x = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == View.GONE) continue
                c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (line.isNotEmpty() && x + c.measuredWidth > max) { lines += line; line = ArrayList(); x = 0 }
                line += c
                x += c.measuredWidth + gapX
            }
            if (line.isNotEmpty()) lines += line
            val height = lines.sumOf { l -> l.maxOf { it.measuredHeight } } + gapY * (lines.size - 1).coerceAtLeast(0)
            setMeasuredDimension(max, height)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var y = 0
            for (line in lines) {
                val lh = line.maxOf { it.measuredHeight }
                var x = 0
                for (c in line) {
                    val cx = if (c === end) r - l - c.measuredWidth else x
                    val cy = y + (lh - c.measuredHeight) / 2
                    c.layout(cx, cy, cx + c.measuredWidth, cy + c.measuredHeight)
                    x += c.measuredWidth + gapX
                }
                y += lh + gapY
            }
        }
    }

    companion object {
        private val TITLES = mapOf(
            SetupFlow.WIFI to "Wi-Fi", SetupFlow.HA to "Home Assistant", SetupFlow.PANES to "Panes", SetupFlow.SIGNIN to "Sign in",
        )

        fun qrBitmap(text: String, size: Int): Bitmap {
            val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val px = IntArray(m.width * m.height)
            for (y in 0 until m.height) for (x in 0 until m.width) {
                px[y * m.width + x] = if (m[x, y]) Glass.BG else Color.WHITE
            }
            return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
        }
    }
}
