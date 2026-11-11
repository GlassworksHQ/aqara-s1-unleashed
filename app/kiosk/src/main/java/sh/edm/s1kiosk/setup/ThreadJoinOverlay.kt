package sh.edm.s1kiosk.setup

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import sh.edm.s1kiosk.auth.ThreadJoin

/**
 * "Join Home Assistant's Thread network" on the panel's screen (auth/ThreadJoin): a QR code for
 * `http://<panel>:8099/thread` that an administrator scans to let the panel read HA's Thread
 * dataset, and how it went. In the setup screen's look, above everything (the settings menu included,
 * which it is opened from). Shown by MainActivity from the ThreadJoin state; main thread only.
 */
class ThreadJoinOverlay(context: Context) : FrameLayout(context) {

    /** "Close": stop offering the QR code. [mode] says which flow is showing. */
    var onClose: (() -> Unit)? = null

    /** What the overlay shows: the Home Assistant sign-in (ThreadJoin) or Matter pairing. */
    enum class Mode { HA_SIGN_IN, MATTER }

    var mode = Mode.HA_SIGN_IN
        private set

    var isShowing = false
        private set

    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()
    private var qrFor: String? = null
    private val autoHide = Runnable { onClose?.invoke() }

    private val qrImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val qrTile = FrameLayout(context).apply {
        background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(Color.WHITE) }
        setPadding(px(12f), px(12f), px(12f), px(12f))
        addView(qrImage, LayoutParams(-1, -1))
    }
    private val label = text(11.5f, FA, MEDIUM).apply { letterSpacing = 0.14f; isAllCaps = true; text = "Radio · Thread router" }
    private val title = text(28f, TX, LIGHT).apply { letterSpacing = -0.02f; setLineSpacing(0f, 1.05f) }
    private val body = text(16f, MU, REGULAR).apply { setLineSpacing(0f, 1.3f) }
    private val status = text(14f, AMBER, REGULAR).apply { setLineSpacing(0f, 1.25f) }
    private val close = TextView(context).apply {
        text = "Close"
        textSize = 16f
        setTextColor(TX)
        typeface = MEDIUM
        background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(S2); setStroke(px(1f).coerceAtLeast(1), BD) }
        setPadding(px(20f), px(13f), px(20f), px(13f))
        isClickable = true
        setOnClickListener { onClose?.invoke() }
    }

    init {
        setBackgroundColor(Color.argb(235, 10, 11, 15))
        isClickable = true
        isFocusable = true
        elevation = 26 * dp // above the settings menu (24 dp)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
            addView(close, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(20f) })
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 26 * dp; setColor(Color.parseColor("#15161b")); setStroke(px(1f).coerceAtLeast(1), BD) }
            setPadding(px(30f), px(26f), px(30f), px(26f))
            addView(qrTile, LinearLayout.LayoutParams(px(210f), px(210f)).apply { marginEnd = px(30f) })
            addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        }
        addView(card, LayoutParams(px(820f), -2, Gravity.CENTER))
        visibility = View.GONE
    }

    /** Shows (or updates) the overlay for [s]; [url] is what the QR code opens. */
    fun render(s: ThreadJoin.State, url: String, now: Long = System.currentTimeMillis()) {
        removeCallbacks(autoHide)
        mode = Mode.HA_SIGN_IN
        label.text = "Radio · Thread router"
        val armed = s is ThreadJoin.State.NeedsAdmin && now < s.until
        if (armed || s is ThreadJoin.State.Working) {
            if (qrFor != url) {
                qrImage.setImageBitmap(SetupOverlay.qrBitmap(url, px(186f)))
                qrFor = url
            }
            qrTile.visibility = View.VISIBLE
        } else {
            qrTile.visibility = View.GONE
        }
        when (s) {
            is ThreadJoin.State.Joined -> {
                title.text = "Joining ${s.networkName.ifEmpty { "your Thread network" }}"
                body.text = "The panel got the network's settings from Home Assistant and is joining it as a Thread router. " +
                    "Radio status on the panel's device page says when it's in."
                status.setTextColor(GREEN)
                status.text = "Done. The administrator sign-in was used once and revoked."
                postDelayed(autoHide, 8_000)
            }
            is ThreadJoin.State.Failed -> {
                title.text = "Couldn't join the Thread network"
                body.text = s.reason.replaceFirstChar { it.uppercase() } + "."
                status.setTextColor(ROSE)
                status.text = "Try again from the panel's Thread router card, or Home Assistant (“Join Home Assistant's Thread network”)."
            }
            else -> {
                title.text = "Scan to join Home Assistant's Thread network"
                body.text = "Sign in on your phone as a Home Assistant administrator. The panel then reads your preferred " +
                    "Thread network's settings from Home Assistant, signs out again and joins as a Thread router. " +
                    "Or open $url."
                status.setTextColor(if (s is ThreadJoin.State.Working) AMBER else FA)
                status.text = ThreadJoin.describe(s, now)
                if (s is ThreadJoin.State.NeedsAdmin) postDelayed(autoHide, (s.until - now).coerceAtLeast(0))
            }
        }
        reveal()
    }

    /**
     * Matter pairing (matter/PanelMatter): the QR code a phone's home app scans (the Matter
     * onboarding payload, `MT:…`), the manual code, then how it went.
     */
    fun renderMatter(s: sh.edm.s1kiosk.matter.PanelMatter.State) {
        removeCallbacks(autoHide)
        mode = Mode.MATTER
        label.text = "Radio · Matter"
        val pairing = s as? sh.edm.s1kiosk.matter.PanelMatter.State.Pairing
        if (pairing != null && pairing.qr.isNotEmpty()) {
            if (qrFor != pairing.qr) {
                qrImage.setImageBitmap(SetupOverlay.qrBitmap(pairing.qr, px(186f)))
                qrFor = pairing.qr
            }
            qrTile.visibility = View.VISIBLE
        } else {
            qrTile.visibility = View.GONE
        }
        when (s) {
            is sh.edm.s1kiosk.matter.PanelMatter.State.Pairing -> {
                title.text = "Scan with your phone's home app"
                body.text = "Add a Matter device and scan this code, close to the panel (it pairs over Bluetooth). " +
                    "Your phone sends the panel your Thread network, and the panel joins it as a Thread router." +
                    (if (s.code.isNotEmpty()) " Or type ${s.code}." else "")
                status.setTextColor(FA)
                status.text = "Works with Home Assistant (with test-net DCL on in its Matter Server) and Apple Home."
                postDelayed(autoHide, PAIRING_WINDOW_MS)
            }
            is sh.edm.s1kiosk.matter.PanelMatter.State.Paired -> {
                title.text = "Paired" + (s.network?.let { " · joining $it" } ?: "")
                body.text = "The panel is a Matter device in your home now (its screen is an On/Off switch), and joins " +
                    "your Thread network as a Thread router. Radio status on the panel's device page says when it's in."
                status.setTextColor(GREEN)
                status.text = "Done."
                postDelayed(autoHide, 8_000)
            }
            is sh.edm.s1kiosk.matter.PanelMatter.State.Failed -> {
                title.text = "Matter pairing stopped"
                body.text = s.reason.replaceFirstChar { it.uppercase() } + "."
                status.setTextColor(ROSE)
                status.text = "Try again from the panel's Thread router card."
            }
            else -> {
                title.text = "Starting Matter…"
                body.text = "The panel is getting ready to pair over Bluetooth."
                status.setTextColor(AMBER)
                status.text = ""
            }
        }
        reveal()
    }

    private fun reveal() {
        if (!isShowing) {
            isShowing = true
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            bringToFront()
            animate().alpha(1f).setDuration(200).start()
        }
    }

    fun hide() {
        removeCallbacks(autoHide)
        if (!isShowing) return
        isShowing = false
        animate().cancel()
        animate().alpha(0f).setDuration(200).withEndAction { if (!isShowing) visibility = View.GONE }.start()
    }

    private fun text(sp: Float, color: Int, tf: Typeface) = TextView(context).apply {
        textSize = sp; setTextColor(color); typeface = tf
    }

    private companion object {
        /** rs-matter's basic commissioning window (15 minutes). */
        const val PAIRING_WINDOW_MS = 15 * 60_000L
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
