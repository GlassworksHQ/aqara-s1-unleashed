package sh.edm.s1kiosk.setup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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

/**
 * The first-boot setup screen (docs/onboarding.md, step 4): a native, full-screen overlay over the
 * GeckoView in the dashboard's visual language (tablet-home-card.compact.js tokens: bg #0a0b0f with
 * sky/violet radial glows, glass cards rgba(255,255,255,.045) + .07 border, 22-26 px radii,
 * uppercase letter-spaced labels in #63636b, big light headings, amber / green accents).
 *
 * Left: the four steps (Wi-Fi, Add to Home Assistant, Sign in, Done) with checks. Right: a glass
 * card for the current step. It replaces the earlier SignInOverlay (its QR code and texts are the
 * Sign-in step). Laid out for 960×480 dp (1440×720 px at density 1.5). Main thread only.
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
    )

    var onChooseNetwork: (() -> Unit)? = null
    var onSkipHa: (() -> Unit)? = null
    /** "Sign in on this panel instead". */
    var onUsePanel: (() -> Unit)? = null
    /** Manual URL submitted; call [manualResult] with the outcome. */
    var onManualUrl: ((String) -> Unit)? = null
    var onClosePreview: (() -> Unit)? = null

    /** True while visible (including the Done fade-out). */
    var isShowing = false
        private set

    var model: Model? = null
        private set

    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private var finishing: Runnable? = null
    private var qrFor: String? = null

    // ---- left: the steps
    private val brand = label("S1 Panel · Setup")
    private val railTitle = text(34f, TX, LIGHT).apply { letterSpacing = -0.02f; text = "Let's set up\nyour panel"; setLineSpacing(0f, 1.0f) }
    private val rows = listOf(
        StepRow("Wi-Fi"), StepRow("Add to Home Assistant"), StepRow("Sign in"), StepRow("Done"),
    )
    private val previewChip = button("Preview · Close", Kind.GHOST).apply { setOnClickListener { onClosePreview?.invoke() } }

    private val rail = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(brand)
        addView(railTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { i, r -> list.addView(r.view, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = px(14f) }) }
        addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(28f) })
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

    private val cardColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(bigCheck, LinearLayout.LayoutParams(px(64f), px(64f)).apply { bottomMargin = px(18f) })
        addView(cardLabel)
        addView(cardTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        addView(cardBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        addView(cardNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        addView(tiles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18f) })
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
        val m = (from ?: return).copy(step = SetupStep.DONE, wifiDone = true, haDone = true, signinDone = true)
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
            else -> "Not connected"
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
        val done = listOf(m.wifiDone, m.haDone, m.signinDone, m.step == SetupStep.DONE)
        val details = listOf(wifiDetail, haDetail, signinDetail, if (m.step == SetupStep.DONE) "Loading your dashboard" else "")
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
        // The URL field is never reset to GONE and back: a view that goes GONE (even for one frame)
        // loses focus, and this runs on every discovery/Wi-Fi refresh, i.e. while the user types.
        var showUrl = false
        buttons.visibility = View.VISIBLE
        primary.visibility = View.GONE
        secondary.visibility = View.GONE
        primary.setOnClickListener(null)
        secondary.setOnClickListener(null)
        if (stepChanged) status.visibility = View.GONE
        cardTitle.textSize = if (m.step == SetupStep.SIGNIN) 26f else 30f // the QR takes ~240 dp of the card

        when (m.step) {
            SetupStep.WIFI -> {
                cardLabel.text = "Step 1 · Wi-Fi"
                val w = m.wifi
                if (w.online) {
                    cardTitle.text = if (w.ethernet) "Connected over Ethernet" else "Connected to ${w.ssid ?: "Wi-Fi"}"
                    cardBody.text = "The panel is on the network."
                } else {
                    cardTitle.text = if (w.state == WifiSetupStatus.State.CONNECTING) "Connecting to ${w.ssid ?: "Wi-Fi"}…" else "Not connected"
                    cardBody.text = "Set up Wi-Fi over Bluetooth from Home Assistant (it shows the panel as an " +
                        "Improv device), or choose a network here."
                }
                w.message?.let { cardNote.text = it; cardNote.visibility = View.VISIBLE }
                primary.text = "Choose network"
                primary.visibility = View.VISIBLE
                primary.setOnClickListener { onChooseNetwork?.invoke() }
            }
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
                cardLabel.text = "Step 3 · Sign in"
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

    /** #0a0b0f with the dashboard's two soft glows (sky top-left, violet bottom-right). */
    internal class Backdrop : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawColor(BG)
            val w = b.width().toFloat(); val h = b.height().toFloat()
            paint.shader = RadialGradient(-0.1f * w, -0.2f * h, 0.85f * w, Color.argb(33, 56, 189, 248), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(b, paint)
            paint.shader = RadialGradient(1.1f * w, 1.2f * h, 0.65f * w, Color.argb(36, 167, 139, 250), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(b, paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
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
