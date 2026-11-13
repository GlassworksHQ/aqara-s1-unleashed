package sh.edm.s1kiosk.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
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
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Glass.Companion.AMBER
import sh.edm.s1kiosk.ui.Glass.Companion.BD
import sh.edm.s1kiosk.ui.Glass.Companion.FA
import sh.edm.s1kiosk.ui.Glass.Companion.MU
import sh.edm.s1kiosk.ui.Glass.Companion.ROSE
import sh.edm.s1kiosk.ui.Glass.Companion.TX
import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.ui.StackView

/**
 * The kiosk settings menu, as drawn in #63. Left: the mark, "Settings", one entry per section in the
 * order [Model.sections] has them (dev.glassworks.pane.settingsOrder, #33's nav) and Done. Right: the
 * section that is picked, in a glass card: its rows, each a name with its value under it. A switch
 * applies when it is tapped; a typed value opens an editor and applies on Save. A change that only
 * takes effect at start ([SettingsItem.Text.restartsApp]) shows #33's banner with Restart now.
 *
 * A full-screen overlay in MainActivity's window, above the setup screen. Main thread only.
 */
class KioskSettingsOverlay(context: Context) : FrameLayout(context) {

    data class Model(
        val sections: List<SettingsSection>,
        val version: String,
        /** The ids of the panes' sections: their page says "Panes ›" before the heading. */
        val panes: Set<String> = emptySet(),
    )

    /** "Restart now": a change waits for the app to start again. */
    var onRestart: (() -> Unit)? = null

    var isShowing = false
        private set

    private val g = Glass(context)
    private fun px(v: Float) = g.px(v)

    private var model: Model? = null
    private var current: String? = null
    /** A change that needs a restart was made since the app started. */
    private var restartPending = false

    private val nav = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rail = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8f), 0, 0, 0)
            addView(StackView(context), LinearLayout.LayoutParams(px(22f), px(21f)).apply { marginEnd = px(13f) })
            addView(g.heading(24f).apply { text = "Settings" })
        }, LinearLayout.LayoutParams(-1, px(40f)))
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(nav, LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(20f) })
        addView(g.ghost("Done").apply { setPadding(0, px(12f), 0, px(12f)); setOnClickListener { hide() } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
    }

    private val crumb = g.text(17f, FA).apply { text = "Panes ›" }
    private val title = g.heading(28f).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val problem = g.text(15f, ROSE).apply { setLineSpacing(0f, 1.2f) }
    private val banner = Banner()
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val page = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(34f), px(26f), px(34f), px(24f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(crumb, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(12f) })
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2))
        addView(problem, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        addView(banner.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
        addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
    }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(page, LayoutParams(-1, -2))
    }
    private val editor = Editor()

    /** Native items' views ([SettingsItem.Native], e.g. the radio's): made once, kept across show()s. */
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
        elevation = 24 * g.dp // above the setup screen (20 dp) and the voice overlay (16 dp)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(36f), px(36f), px(35f), px(30f))
            addView(rail, LinearLayout.LayoutParams(px(216f), -1).apply { marginEnd = px(22f) })
            addView(FrameLayout(context).apply {
                background = g.surface(34f)
                addView(scroll, LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }, LayoutParams(-1, -1))
        addView(editor.view, LayoutParams(-1, -1))
        visibility = View.GONE
    }

    /** Shows the menu for [m], on the section it was on, else the first. Values are read again. */
    fun show(m: Model) {
        model = m
        if (m.sections.none { it.id == current }) current = m.sections.firstOrNull()?.id
        editor.close()
        build()
        if (!isShowing) {
            isShowing = true
            animate().cancel()
            if (visibility != View.VISIBLE) alpha = 0f
            visibility = View.VISIBLE
            bringToFront()
            animate().alpha(1f).setDuration(220).start()
        }
        requestFocus()
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        natives.values.forEach { it.reset() }
        editor.close()
        hideKeyboard()
        findFocus()?.clearFocus()
        animate().alpha(0f).setDuration(200).withEndAction { if (!isShowing) visibility = View.GONE }.start()
    }

    /** While the menu is open (MainActivity, every couple of seconds): the native views read their state again. */
    fun refresh() {
        if (!isShowing) return
        natives.values.forEach { it.refresh() }
    }

    /** Previews (the mock-ups): a section's page, an open editor, a native view, the restart banner. */
    internal fun previewSection(id: String) { current = id; build() }
    internal fun previewEdit(title: String, typed: String? = null) {
        val i = model?.sections?.flatMap { flat(it.items) }?.filterIsInstance<SettingsItem.Text>()?.first { it.title == title } ?: return
        editor.open(i) {}
        typed?.let { editor.field.setText(it) }
    }
    internal fun previewRestart() { restartPending = true; banner.bind() }
    internal fun previewNative(key: String): SettingsItem.NativeView? = natives[key]
    internal fun previewScrollTo(v: View, alignBottom: Boolean = false) {
        val r = Rect()
        v.getDrawingRect(r)
        scroll.offsetDescendantRectToMyCoords(v, r)
        val y = if (alignBottom) r.bottom + px(16f) - scroll.height else r.top - px(16f)
        scroll.scrollTo(0, y.coerceAtLeast(0))
    }

    // ------------------------------------------------------------------ building

    private fun build() {
        val m = model ?: return
        nav.removeAllViews()
        m.sections.forEachIndexed { i, s ->
            nav.addView(navRow(s), LinearLayout.LayoutParams(-1, px(45f)).apply { if (i > 0) topMargin = px(5f) })
        }
        val s = m.sections.firstOrNull { it.id == current } ?: return
        crumb.visibility = if (s.id in m.panes) View.VISIBLE else View.GONE
        title.text = s.heading
        problem.visibility = View.GONE
        banner.bind()
        rows.removeAllViews()
        natives.values.forEach { n -> (n.view.parent as? android.view.ViewGroup)?.removeView(n.view); n.reset() }
        addItems(s.items, divided = s.items.none { it is SettingsItem.Native })
        scroll.scrollTo(0, 0)
    }

    private fun flat(items: List<SettingsItem>): List<SettingsItem> =
        items.flatMap { if (it is SettingsItem.Group) flat(it.items) else listOf(it) }

    /** [items] as rows; [divided]: a hairline above each row, as drawn. */
    private fun addItems(items: List<SettingsItem>, divided: Boolean) {
        for (i in items) {
            if (i is SettingsItem.Group) {
                rows.addView(g.label(i.title), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(26f); bottomMargin = px(4f) })
                addItems(i.items, divided = true)
                continue
            }
            if (divided) rows.addView(View(context).apply { setBackgroundColor(BD) }, LinearLayout.LayoutParams(-1, px(1f).coerceAtLeast(1)))
            rows.addView(item(i), LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun navRow(s: SettingsSection) = g.text(17f, if (s.id == current) TX else MU).apply {
        text = s.title
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(14f), 0, px(14f), 0)
        background = if (s.id == current) g.surface(14f) else null
        isClickable = true
        setOnClickListener { if (current != s.id) { editor.close(); hideKeyboard(); current = s.id; build() } }
    }

    private fun item(i: SettingsItem): View = when (i) {
        is SettingsItem.Text -> row(i.title, shownOf(i), chevron = i.enabled(), dim = !i.enabled()) { if (i.enabled()) editor.open(i) { build() } }
        is SettingsItem.Switch -> toggleRow(i)
        is SettingsItem.Link -> row(i.title, i.detail, chevron = true, dim = i.detail.isEmpty()) { i.open(context) }
        is SettingsItem.Note -> g.text(14.5f, MU).apply { text = i.text(); setLineSpacing(0f, 1.25f); setPadding(0, px(14f), 0, px(14f)) }
        is SettingsItem.Info -> row(i.title(), i.detail(), chevron = false, dim = false, onClick = null)
        is SettingsItem.Group -> error("groups are laid out by addItems")
        is SettingsItem.Native -> natives.getOrPut(i.key) { i.create(nativeHost) }.also { it.refresh() }.view
    }

    private fun shownOf(i: SettingsItem.Text): String {
        val v = i.value()
        return if (v.isBlank()) i.shown(v).ifBlank { i.hint } else i.shown(v)
    }

    /** A name, its value under it and, when it opens something, a chevron. [dim]: a quieter row (Aqara's own settings). */
    private fun row(name: String, value: String, chevron: Boolean, dim: Boolean, onClick: (() -> Unit)?) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(64f)
        setPadding(0, px(12f), 0, px(12f))
        addView(texts(name, value, if (dim) MU else TX), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
        if (chevron) addView(g.text(26f, MU, Inter.LIGHT).apply { text = "›" })
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
    }

    private fun toggleRow(i: SettingsItem.Switch): View {
        lateinit var t: Glass.Toggle
        t = g.toggle(i.value()) { on ->
            i.save(on) { why ->
                post {
                    if (why != null) { t.checked = !on; showProblem(why) }
                    else if (i.restartsApp) { restartPending = true; banner.bind() }
                }
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(64f)
            setPadding(0, px(12f), 0, px(12f))
            addView(texts(i.title, i.detail, TX), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
            addView(t)
            isClickable = true
            setOnClickListener { t.performClick() }
        }
    }

    private fun texts(name: String, value: String, color: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(g.text(16.5f, color).apply { text = name })
        if (value.isNotEmpty()) addView(g.text(15f, MU).apply { text = value; setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
    }

    private fun showProblem(why: String) {
        problem.text = why
        problem.visibility = View.VISIBLE
    }

    /** Scrolls [v] (a native view's field) near the top of the page, above where the keyboard comes up. */
    internal fun reveal(v: View) {
        if (!isShowing) return
        val r = Rect()
        v.getDrawingRect(r)
        runCatching { scroll.offsetDescendantRectToMyCoords(v, r) }.onFailure { return }
        scroll.smoothScrollTo(0, (r.top - px(60f)).coerceAtLeast(0))
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0)
    }

    // ------------------------------------------------------------------ pieces

    /** #33's "restart needed": an amber line with the one white button, while a change waits for it. */
    private inner class Banner {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 20 * g.dp
                setColor(Color.argb(20, 251, 191, 36))
                setStroke(px(1f).coerceAtLeast(1), Color.argb(90, 251, 191, 36))
            }
            setPadding(px(18f), px(10f), px(10f), px(10f))
            addView(g.dot(AMBER), LinearLayout.LayoutParams(px(9f), px(9f)).apply { marginEnd = px(13f) })
            addView(g.text(15.5f, TX).apply { text = RESTART_TEXT; setLineSpacing(0f, 1.2f) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(12f) })
            addView(g.primary("Restart now").apply { setPadding(px(22f), px(11f), px(22f), px(11f)); setOnClickListener { onRestart?.invoke() } })
        }

        fun bind() { view.visibility = if (restartPending) View.VISIBLE else View.GONE }
    }

    /**
     * A typed value, edited on its own (not in the design: the rows' chevrons open it). The sheet sits
     * near the top, so the keyboard never covers it.
     */
    private inner class Editor {
        private var item: SettingsItem.Text? = null
        private val name = g.heading(26f)
        private val hint = g.text(15f, MU).apply { setLineSpacing(0f, 1.2f) }
        val field = EditText(context).apply {
            textSize = 18f
            typeface = Inter.typeface(context, Inter.REGULAR, 18f)
            setTextColor(TX)
            setHintTextColor(FA)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
            background = g.surface(16f, Glass.S2)
            setPadding(px(18f), px(14f), px(18f), px(14f))
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { save(); true } else false }
        }
        private val why = g.text(15f, ROSE)
        private val saveButton = g.primary("Save").apply { setOnClickListener { save() } }
        private var after: () -> Unit = {}
        private val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = g.surface(28f, Color.parseColor("#17181e"))
            setPadding(px(30f), px(26f), px(30f), px(22f))
            isClickable = true
            addView(name)
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18f) })
            addView(why, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                addView(g.plain("Cancel").apply { setOnClickListener { close() } }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(14f) })
                addView(saveButton)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
        }
        val view = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(170, 10, 11, 15))
            isClickable = true
            setOnClickListener { close() }
            addView(sheet, LayoutParams(px(560f), -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = px(40f) })
            visibility = View.GONE
        }

        fun open(i: SettingsItem.Text, after: () -> Unit) {
            item = i
            this.after = after
            name.text = i.title
            hint.text = i.hint
            field.inputType = when (i.kind) {
                SettingsItem.TextKind.PLAIN -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                SettingsItem.TextKind.ADDRESS -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                SettingsItem.TextKind.NUMBER -> InputType.TYPE_CLASS_NUMBER
                SettingsItem.TextKind.DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            field.setText(i.value())
            field.hint = i.hint
            field.setSelection(field.text.length)
            why.visibility = View.GONE
            saveButton.isEnabled = true; saveButton.alpha = 1f
            view.visibility = View.VISIBLE
            view.bringToFront()
            field.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(field, 0)
        }

        fun close() {
            if (view.visibility != View.VISIBLE) return
            item = null
            hideKeyboard()
            view.visibility = View.GONE
        }

        private fun save() {
            val i = item ?: return
            saveButton.isEnabled = false; saveButton.alpha = 0.5f
            i.save(field.text?.toString().orEmpty()) { problem ->
                post {
                    if (item !== i) return@post
                    if (problem != null) {
                        why.text = problem
                        why.visibility = View.VISIBLE
                        saveButton.isEnabled = true; saveButton.alpha = 1f
                    } else {
                        if (i.restartsApp) restartPending = true
                        close()
                        after()
                    }
                }
            }
        }
    }

    companion object {
        const val RESTART_TEXT = "This takes effect after the app restarts. It takes about 5 seconds."
    }
}
