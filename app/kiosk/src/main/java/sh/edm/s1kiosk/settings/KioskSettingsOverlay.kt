package sh.edm.s1kiosk.settings

import sh.edm.s1kiosk.ui.Inter
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import sh.edm.s1kiosk.setup.Backdrop

/**
 * The kiosk settings menu, in the setup screen's look (setup/SetupOverlay): the dithered backdrop,
 * glass cards, amber accent, the same type scale and buttons. Left: title, version, the sections (tap
 * to jump) and Save & restart / Reload / Close. Right: one glass card per [SettingsSection], scrolling,
 * in the order [Model.sections] has them (dev.glassworks.pane.settingsOrder). A full-screen overlay in
 * MainActivity's window, above the setup screen; it edits nothing until "Save & restart" ([onSave] with
 * every field's save, in order).
 *
 * The window is full screen, where Android doesn't resize for the keyboard (adjustResize is ignored
 * with FLAG_FULLSCREEN), so the menu measures how much of it the keyboard covers and shortens the
 * scrolling column by that much: the focused field stays visible above the keyboard. Main thread only.
 */
class KioskSettingsOverlay(context: Context) : FrameLayout(context) {

    data class Model(
        val sections: List<SettingsSection>,
        val version: String,
    )

    /** "Save & restart": each field's save as typed now, in menu order. */
    var onSave: ((List<(done: (String?) -> Unit) -> Unit>) -> Unit)? = null
    var onReload: (() -> Unit)? = null

    var isShowing = false
        private set

    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()

    // Edited state for the current show(): each editable item's save with its value as shown now, in menu order
    private val edits = ArrayList<() -> (done: (String?) -> Unit) -> Unit>()
    private val cards = HashMap<String, View>()

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(0, px(30f), 0, px(30f))
        clipToPadding = false
        addView(column, LayoutParams(-1, -2))
    }
    private val rail = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var keyboardInset = 0

    private val keyboardWatch = ViewTreeObserver.OnGlobalLayoutListener { followKeyboard() }

    /** Native items' views ([SettingsItem.Native], e.g. the radio's tiles): made once, kept across show()s. */
    private val natives = LinkedHashMap<String, SettingsItem.NativeView>()
    private val nativeHost = object : SettingsItem.NativeHost {
        override val context: Context get() = this@KioskSettingsOverlay.context
        override fun addLayer(layer: View) { addView(layer, LayoutParams(-1, -1)) }
        override fun reveal(field: View) = this@KioskSettingsOverlay.reveal(field)
        override fun hideKeyboard() = this@KioskSettingsOverlay.hideKeyboard()
    }

    init {
        background = Backdrop()
        isClickable = true // nothing reaches the page underneath
        isFocusable = true
        isFocusableInTouchMode = true // takes focus from the page, so its keyboard closes
        elevation = 24 * dp // above the setup screen (20 dp) and the voice overlay (16 dp)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(36f), 0, px(30f), 0)
            addView(rail, LinearLayout.LayoutParams(px(270f), -1).apply {
                marginEnd = px(24f); topMargin = px(30f); bottomMargin = px(30f)
            })
            addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        }
        addView(content, LayoutParams(-1, -1))
        visibility = View.GONE
    }

    /** Shows the menu for [m] (fresh fields every time: what's saved is what's shown). */
    fun show(m: Model) {
        build(m)
        scroll.scrollTo(0, 0)
        if (!isShowing) {
            isShowing = true
            animate().cancel()
            if (visibility != View.VISIBLE) alpha = 0f
            visibility = View.VISIBLE
            bringToFront()
            animate().alpha(1f).setDuration(220).start()
            viewTreeObserver.addOnGlobalLayoutListener(keyboardWatch)
        }
        requestFocus()
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        natives.values.forEach { it.reset() }
        hideKeyboard()
        findFocus()?.clearFocus()
        viewTreeObserver.removeOnGlobalLayoutListener(keyboardWatch)
        setKeyboardInset(0)
        animate().alpha(0f).setDuration(200).withEndAction { if (!isShowing) visibility = View.GONE }.start()
    }

    /** While the menu is open (MainActivity, every couple of seconds): the native views read their state again. */
    fun refresh() {
        if (!isShowing) return
        natives.values.forEach { it.refresh() }
    }

    /** Previews (the mock-ups): a native item's view, and scrolling a view to the top of the column. */
    internal fun previewNative(key: String): SettingsItem.NativeView? = natives[key]
    internal fun previewScrollTo(v: View, alignBottom: Boolean = false) {
        val r = Rect()
        v.getDrawingRect(r)
        scroll.offsetDescendantRectToMyCoords(v, r)
        val y = if (alignBottom) r.bottom + px(16f) + scroll.paddingBottom - scroll.height else r.top - px(16f) - scroll.paddingTop
        scroll.scrollTo(0, y.coerceAtLeast(0))
    }

    /** Every field's save with its value as typed now, in menu order. */
    fun current(): List<(done: (String?) -> Unit) -> Unit> = edits.map { it() }

    // ------------------------------------------------------------------ building

    private fun build(m: Model) {
        edits.clear(); cards.clear()
        column.removeAllViews()
        natives.values.forEach { n ->
            (n.view.parent as? android.view.ViewGroup)?.removeView(n.view)
            n.reset()
        }
        m.sections.forEachIndexed { i, section ->
            val card = card(section)
            cards[section.id] = card
            column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = px(14f) })
        }
        buildRail(m)
    }

    private fun buildRail(m: Model) {
        rail.removeAllViews()
        rail.addView(label("S1 Unleashed · Kiosk"))
        rail.addView(text(30f, TX, LIGHT).apply { letterSpacing = -0.02f; text = "Settings" },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        rail.addView(text(15f, MU, REGULAR).apply { text = "S1 Kiosk ${m.version}" },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        val nav = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        m.sections.forEach { s ->
            nav.addView(navRow(s), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        }
        rail.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20f) })
        rail.addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f))
        rail.addView(button("Save & restart", accent = true).apply {
            setOnClickListener { hideKeyboard(); onSave?.invoke(current()) }
        }, LinearLayout.LayoutParams(-1, -2))
        rail.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Reload", accent = false).apply { setOnClickListener { onReload?.invoke() } },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(10f) })
            addView(button("Close", accent = false).apply { setOnClickListener { hide() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
    }

    private fun navRow(s: SettingsSection) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(12f), px(9f), px(12f), px(9f))
        background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(Color.TRANSPARENT) }
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AMBER) }
            alpha = 0.85f
        }, LinearLayout.LayoutParams(px(6f), px(6f)).apply { marginEnd = px(12f) })
        addView(text(16f, TX, MEDIUM).apply { text = s.title; setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        isClickable = true
        setOnClickListener { cards[s.id]?.let { c -> scroll.smoothScrollTo(0, c.top) } }
    }

    /**
     * A section's card: its label, then its items, two narrow fields to a row. A section with a native
     * view has no card of its own: the native views sit in the column with their own surfaces, and its
     * groups get cards.
     */
    private fun card(section: SettingsSection): View =
        if (section.items.any { it is SettingsItem.Native }) unframed(section) else LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(22f)
        setPadding(px(24f), px(20f), px(24f), px(22f))
        addView(label(section.title))
        SettingsMenu.rows(section.items).forEach { row ->
            val v = if (row.size == 2) pair(item(row[0]), item(row[1])) else item(row[0])
            addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
        }
    }

    private fun unframed(section: SettingsSection) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, px(4f), 0, 0)
        addView(label(section.title), LinearLayout.LayoutParams(-1, -2).apply { marginStart = px(4f) })
        section.items.forEachIndexed { i, it ->
            val gap = when {
                it is SettingsItem.Group -> 14f
                i == 0 -> 10f
                else -> 12f
            }
            addView(item(it), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(gap) })
        }
    }

    private fun group(g: SettingsItem.Group) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(22f)
        setPadding(px(24f), px(18f), px(24f), px(20f))
        addView(label(g.title))
        g.items.forEach { addView(item(it), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) }) }
    }

    private fun pair(a: View, b: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(12f) })
        addView(b, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun item(i: SettingsItem): View = when (i) {
        is SettingsItem.Text -> field(i)
        is SettingsItem.Switch -> toggle(i)
        is SettingsItem.Link -> link(i.title, i.detail) { i.open(context) }
        is SettingsItem.Note -> text(13.5f, MU, REGULAR).apply { text = i.text(); setLineSpacing(0f, 1.2f) }
        is SettingsItem.Info -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(16f, TX, MEDIUM).apply { text = i.title() })
            addView(text(13f, FA, REGULAR).apply { text = i.detail(); setLineSpacing(0f, 1.2f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        }
        is SettingsItem.Group -> group(i)
        is SettingsItem.Native -> natives.getOrPut(i.key) { i.create(nativeHost) }.also { it.refresh() }.view
    }

    private fun field(i: SettingsItem.Text): View {
        val edit = EditText(context).apply {
            textSize = 16f
            typeface = Inter.typeface(context, REGULAR, 16f)
            setTextColor(TX)
            setHintTextColor(FA)
            hint = i.hint
            inputType = when (i.kind) {
                SettingsItem.TextKind.PLAIN -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                SettingsItem.TextKind.ADDRESS -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                SettingsItem.TextKind.NUMBER -> InputType.TYPE_CLASS_NUMBER
                SettingsItem.TextKind.DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            // No full-screen "extract" editor in landscape: the field itself stays visible above the keyboard.
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
            setText(i.value())
            background = glass(14f)
            setPadding(px(16f), px(11f), px(16f), px(11f))
            setOnFocusChangeListener { f, has -> if (has) f.post { reveal(f) } }
            isEnabled = i.enabled()
        }
        edits += { val v = edit.text?.toString().orEmpty(); { done -> i.save(v, done) } }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(13.5f, MU, REGULAR).apply { text = i.title; setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
            addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
        }
    }

    private fun toggle(i: SettingsItem.Switch): View {
        val sw = Toggle(context).apply { checked = i.value() }
        edits += { val v = sw.checked; { done -> i.save(v, done) } }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts(i.title, i.detail), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
            addView(sw, LinearLayout.LayoutParams(px(52f), px(30f)))
            isClickable = true
            setOnClickListener { sw.checked = !sw.checked }
        }
    }

    private fun link(title: String, detail: String, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(S2); setStroke(px(1f).coerceAtLeast(1), BD) }
        setPadding(px(16f), px(13f), px(16f), px(13f))
        addView(texts(title, detail), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(12f) })
        addView(text(24f, MU, LIGHT).apply { text = "›" })
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun texts(title: String, detail: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(16f, TX, MEDIUM).apply { text = title })
        addView(text(13.5f, MU, REGULAR).apply { text = detail }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2f) })
    }

    // ------------------------------------------------------------------ keyboard

    /** How much of this view the keyboard covers; the scrolling column gets that much shorter. */
    private fun followKeyboard() {
        if (!isShowing) return
        val visible = Rect().also { getWindowVisibleDisplayFrame(it) }
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val covered = (loc[1] + height - visible.bottom).coerceIn(0, (height - px(120f)).coerceAtLeast(0))
        if (covered != keyboardInset) {
            setKeyboardInset(covered)
            findFocus()?.let { f -> post { reveal(f) } }
        }
    }

    private fun setKeyboardInset(inset: Int) {
        keyboardInset = inset
        val lp = scroll.layoutParams as LinearLayout.LayoutParams
        val margin = if (inset > 0) (inset + px(12f) - scroll.paddingBottom).coerceAtLeast(0) else 0
        if (lp.bottomMargin != margin) { lp.bottomMargin = margin; scroll.layoutParams = lp }
    }

    /** Scrolls [v] (a field) fully into the visible part of the column. */
    internal fun reveal(v: View) {
        if (!isShowing) return
        val r = Rect()
        v.getDrawingRect(r)
        runCatching { scroll.offsetDescendantRectToMyCoords(v, r) }.onFailure { return }
        val top = r.top - px(40f) // its label too
        val bottom = r.bottom + px(12f)
        val viewTop = scroll.scrollY + scroll.paddingTop
        val viewBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
        when {
            top < viewTop -> scroll.smoothScrollTo(0, (top - scroll.paddingTop).coerceAtLeast(0))
            bottom > viewBottom -> scroll.smoothScrollTo(0, bottom - viewBottom + scroll.scrollY)
        }
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0)
    }

    // ------------------------------------------------------------------ pieces

    /** A pill switch in the dashboard's style: amber track when on. */
    private inner class Toggle(context: Context) : FrameLayout(context) {
        private val knob = View(context)
        var checked = false
            set(v) { field = v; restyle() }

        init {
            addView(knob, LayoutParams(px(22f), px(22f), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = px(4f); marginEnd = px(4f) })
            restyle()
        }

        private fun restyle() {
            background = GradientDrawable().apply {
                cornerRadius = 15 * dp
                if (checked) setColor(AMBER) else { setColor(S2); setStroke(px(1f).coerceAtLeast(1), BD) }
            }
            knob.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (checked) BG else MU) }
            (knob.layoutParams as LayoutParams).gravity = Gravity.CENTER_VERTICAL or (if (checked) Gravity.END else Gravity.START)
            knob.requestLayout()
            contentDescription = if (checked) "on" else "off"
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

    private fun button(label: String, accent: Boolean) = TextView(context).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(if (accent) BG else TX)
        typeface = Inter.typeface(context, MEDIUM, 16f)
        background = GradientDrawable().apply {
            cornerRadius = 16 * dp
            if (accent) setColor(AMBER) else { setColor(S2); setStroke(px(1f).coerceAtLeast(1), BD) }
        }
        setPadding(px(18f), px(13f), px(18f), px(13f))
        isClickable = true
        isFocusable = true
    }

    private companion object {
        // Same tokens as SetupOverlay (tablet-home-card.compact.js).
        val BG = Color.parseColor("#0a0b0f")
        val S1 = Color.argb((0.045f * 255).toInt(), 255, 255, 255)
        val S2 = Color.argb((0.08f * 255).toInt(), 255, 255, 255)
        val BD = Color.argb((0.07f * 255).toInt(), 255, 255, 255)
        val TX = Color.parseColor("#f4f4f5")
        val MU = Color.parseColor("#a1a1aa")
        val FA = Color.parseColor("#63636b")
        val AMBER = Color.parseColor("#fbbf24")
        val LIGHT = Inter.LIGHT
        val REGULAR = Inter.REGULAR
        val MEDIUM = Inter.MEDIUM
    }
}
