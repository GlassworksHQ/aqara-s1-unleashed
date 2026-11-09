package io.github.aguilaair.s1kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
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

/**
 * Full-screen sign-in screen over the GeckoView, in the dashboard's visual language
 * (tablet-home-card tokens: bg #0a0b0f with sky/violet glows, glass cards, uppercase letter-spaced
 * labels, large light text). Two modes:
 *
 * - **QR**: a QR code for `http://<panel-ip>:8099/signin`, the HA URL it will use, and
 *   "Sign in on this panel instead".
 * - **Find HA**: Home Assistant wasn't found; a field for its URL.
 *
 * Main thread only.
 */
class SignInOverlay(context: Context) : FrameLayout(context) {

    enum class Mode { HIDDEN, QR, MANUAL }

    /** "Sign in on this panel instead". */
    var onUsePanel: (() -> Unit)? = null

    /** Manual URL submitted; call [manualResult] with the outcome. */
    var onManualUrl: ((String) -> Unit)? = null

    var mode = Mode.HIDDEN
        private set

    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()

    private val qrImage = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val qrTile = FrameLayout(context).apply {
        background = GradientDrawable().apply { cornerRadius = 26 * dp; setColor(Color.WHITE) }
        setPadding(px(16f), px(16f), px(16f), px(16f))
        addView(qrImage, LayoutParams(-1, -1))
    }
    private val label = text(12f, FA, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
        letterSpacing = 0.14f; isAllCaps = true
    }
    private val title = text(36f, TX, Typeface.create("sans-serif-light", Typeface.NORMAL)).apply {
        letterSpacing = -0.02f; setLineSpacing(0f, 1.05f)
    }
    private val body = text(16f, MU, Typeface.create("sans-serif", Typeface.NORMAL)).apply { setLineSpacing(0f, 1.25f) }
    private val haLabel = text(12f, FA, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
        letterSpacing = 0.14f; isAllCaps = true; text = "Home Assistant"
    }
    private val haValue = text(18f, TX, Typeface.create("sans-serif", Typeface.NORMAL)).apply { setSingleLine() }
    private val haCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(22f)
        setPadding(px(18f), px(14f), px(18f), px(14f))
        addView(haLabel)
        addView(haValue, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
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
        setPadding(px(18f), px(14f), px(18f), px(14f))
        setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
        }
    }
    private val status = text(14f, MU, Typeface.create("sans-serif", Typeface.NORMAL))
    private val primary = button("Connect", accent = true).apply { setOnClickListener { submit() } }
    private val secondary = button("Sign in on this panel instead", accent = false).apply {
        setOnClickListener { onUsePanel?.invoke() }
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label)
        addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
        addView(haCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22f) })
        addView(urlField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22f) })
        addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primary, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(10f) })
            addView(secondary, LinearLayout.LayoutParams(-2, -2))
        }
        addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(22f) })
    }

    init {
        background = Backdrop()
        isClickable = true // swallow touches meant for the page underneath
        isFocusable = true
        elevation = 20 * dp
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(qrTile, LinearLayout.LayoutParams(px(300f), px(300f)).apply { marginEnd = px(48f) })
            addView(column, LinearLayout.LayoutParams(px(470f), -2))
        }
        addView(content, LayoutParams(-2, -2, Gravity.CENTER))
        visibility = View.GONE
    }

    /** Shows the QR code for [signinUrl]; [haUrl] is the instance the phone will sign in to. */
    fun showQr(signinUrl: String, haUrl: String) {
        mode = Mode.QR
        qrImage.setImageBitmap(qrBitmap(signinUrl, px(268f)))
        qrTile.visibility = View.VISIBLE
        label.text = "Sign in"
        title.text = "Scan with your phone to sign in to Home Assistant"
        body.text = "Or open $signinUrl on a phone or computer on the same network."
        haCard.visibility = View.VISIBLE
        haValue.text = haUrl
        urlField.visibility = View.GONE
        status.visibility = View.GONE
        primary.visibility = View.GONE
        secondary.visibility = View.VISIBLE
        reveal()
    }

    /** Home Assistant wasn't found: ask for its URL. */
    fun showManual(prefill: String) {
        mode = Mode.MANUAL
        qrTile.visibility = View.GONE
        label.text = "Set up"
        title.text = "Where is Home Assistant?"
        body.text = "The panel couldn't find it on the network. Enter its address; the panel keeps looking in the background."
        haCard.visibility = View.GONE
        urlField.visibility = View.VISIBLE
        if (urlField.text.isEmpty()) urlField.setText(prefill)
        status.visibility = View.GONE
        primary.visibility = View.VISIBLE
        primary.isEnabled = true
        secondary.visibility = View.GONE
        reveal()
    }

    fun manualResult(ok: Boolean, message: String) {
        primary.isEnabled = true
        status.visibility = View.VISIBLE
        status.setTextColor(if (ok) GREEN else ROSE)
        status.text = message
    }

    fun hide() {
        if (mode == Mode.HIDDEN) return
        mode = Mode.HIDDEN
        hideKeyboard()
        animate().alpha(0f).setDuration(200).withEndAction { if (mode == Mode.HIDDEN) visibility = View.GONE }.start()
    }

    private fun reveal() {
        if (visibility == View.VISIBLE && alpha == 1f) return
        animate().cancel()
        alpha = 0f
        visibility = View.VISIBLE
        bringToFront()
        animate().alpha(1f).setDuration(200).start()
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

    private fun text(sp: Float, color: Int, tf: Typeface) = TextView(context).apply {
        textSize = sp; setTextColor(color); typeface = tf
    }

    private fun glass(radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(S1)
        setStroke(px(1f).coerceAtLeast(1), BD)
    }

    private fun button(label: String, accent: Boolean) = TextView(context).apply {
        text = label
        textSize = 16f
        setTextColor(if (accent) BG else TX)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply {
            cornerRadius = 16 * dp
            setColor(if (accent) SKY else S2)
        }
        setPadding(px(20f), px(13f), px(20f), px(13f))
        isClickable = true
        isFocusable = true
    }

    /** #0a0b0f with the two soft glows of the dashboard background. */
    private inner class Backdrop : Drawable() {
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
        private val SKY = Color.parseColor("#38bdf8")
        private val GREEN = Color.parseColor("#34d399")
        private val ROSE = Color.parseColor("#fb7185")

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
