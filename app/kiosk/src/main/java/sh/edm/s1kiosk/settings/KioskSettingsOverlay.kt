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
import sh.edm.s1kiosk.settings.SettingsMenu.Entry
import sh.edm.s1kiosk.setup.Backdrop
import sh.edm.s1kiosk.setup.RadioFollowUp

/**
 * The kiosk settings menu, in the setup screen's look (setup/SetupOverlay): the dithered backdrop,
 * glass cards, amber accent, the same type scale and buttons. Left: title, version, the sections (tap
 * to jump) and Save & restart / Reload / Close. Right: one glass card per section, scrolling
 * ([SettingsMenu.layout] decides what is in them). A full-screen overlay in MainActivity's window, above
 * the setup screen; it edits nothing until "Save & restart" ([onSave] with the [Values]).
 *
 * The window is full screen, where Android doesn't resize for the keyboard (adjustResize is ignored
 * with FLAG_FULLSCREEN), so the menu measures how much of it the keyboard covers and shortens the
 * scrolling column by that much: the focused field stays visible above the keyboard. Main thread only.
 */
class KioskSettingsOverlay(context: Context) : FrameLayout(context) {

    /** What the menu edits, as typed (MainActivity parses and saves them as before). */
    data class Values(
        val url: String,
        val zoom: String,
        val secureHosts: String,
        val hideHeader: Boolean,
        val radioAllow: String,
        val presence: Boolean,
        val idleOff: String,
        val nightIdleOff: String,
        val nightWindow: String,
        val presenceRange: String,
        val autoBrightness: Boolean,
    )

    data class Model(
        val values: Values,
        val facts: SettingsMenu.Facts,
        /** The kiosk service (and so the radio) runs: the allow-list can be edited. */
        val radioRunning: Boolean,
        /** [SettingsMenu.allowedNow]. */
        val allowedNow: String,
        val version: String,
        /** Screen/sensor status line. */
        val status: String,
        /** The radio as the picker and follow-up card show it; null when the kiosk service isn't running. */
        val radio: RadioFollowUp.Facts? = null,
    )

    var onSave: ((Values) -> Unit)? = null
    var onReload: (() -> Unit)? = null
    var onAqaraSettings: (() -> Unit)? = null
    var onLicences: (() -> Unit)? = null
    /** Radio requests from the picker and the follow-up card (same RadioController calls as the HA entities). */
    var onRadioRun: ((RadioFollowUp.Request, (String?) -> Unit) -> Unit)? = null
    /** `Thread dataset` typed or pasted on the panel: null = handed over, else why not. */
    var onThreadDataset: ((String) -> String?)? = null

    var isShowing = false
        private set

    private val dp = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).toInt()

    // Edited state for the current show()
    private val fields = HashMap<Entry, EditText>()
    private val toggles = HashMap<Entry, Toggle>()
    private val cards = HashMap<SettingsMenu.Section, View>()

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(column, LayoutParams(-1, -2))
    }
    private val rail = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var keyboardInset = 0

    private val keyboardWatch = ViewTreeObserver.OnGlobalLayoutListener { followKeyboard() }

    // The Radio section's live parts (role tiles + panel): made once, kept across show()s, updated by
    // [updateRadio]. Its confirmations open [radioDialog], centred over the whole menu.
    private val radioDialog = RadioDialog(context)
    private val radio = RadioSectionView(context).apply {
        onRun = { r, done -> onRadioRun?.invoke(r, done) }
        onDataset = { v -> onThreadDataset?.invoke(v) ?: "The kiosk service isn't running." }
        onFieldFocus = { v -> v.post { reveal(v) } }
        onAsk = { a, answer -> hideKeyboard(); radioDialog.show(a, answer) }
    }

    init {
        background = Backdrop()
        isClickable = true // nothing reaches the page underneath
        isFocusable = true
        isFocusableInTouchMode = true // takes focus from the page, so its keyboard closes
        elevation = 24 * dp // above the setup screen (20 dp) and the voice overlay (16 dp)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(36f), px(30f), px(30f), px(30f))
            addView(rail, LinearLayout.LayoutParams(px(270f), -1).apply { marginEnd = px(24f) })
            addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        }
        addView(content, LayoutParams(-1, -1))
        addView(radioDialog, LayoutParams(-1, -1))
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
        radioDialog.dismiss()
        hideKeyboard()
        findFocus()?.clearFocus()
        viewTreeObserver.removeOnGlobalLayoutListener(keyboardWatch)
        setKeyboardInset(0)
        animate().alpha(0f).setDuration(200).withEndAction { if (!isShowing) visibility = View.GONE }.start()
    }

    /** New radio facts while the menu is open (MainActivity, every couple of seconds and on changes). */
    fun updateRadio(f: RadioFollowUp.Facts?) {
        if (!isShowing || f == null) return
        radio.bind(f)
    }

    /** Previews (RadioMockups): the radio views, and scrolling one of them to the top of the column. */
    internal val previewRadio: RadioSectionView get() = radio
    internal val previewDialog: RadioDialog get() = radioDialog
    internal fun previewScrollTo(v: View, alignBottom: Boolean = false) {
        val r = Rect()
        v.getDrawingRect(r)
        scroll.offsetDescendantRectToMyCoords(v, r)
        val y = if (alignBottom) r.bottom + px(16f) - scroll.height else r.top - px(16f)
        scroll.scrollTo(0, y.coerceAtLeast(0))
    }

    /** The values as currently typed. */
    fun current(): Values {
        fun f(e: Entry) = fields[e]?.text?.toString().orEmpty()
        fun t(e: Entry) = toggles[e]?.checked ?: false
        return Values(
            url = f(Entry.URL), zoom = f(Entry.ZOOM), secureHosts = f(Entry.SECURE_HOSTS), hideHeader = t(Entry.HIDE_HEADER),
            radioAllow = f(Entry.RADIO_ALLOW),
            presence = t(Entry.PRESENCE), idleOff = f(Entry.IDLE_OFF), nightIdleOff = f(Entry.NIGHT_IDLE_OFF),
            nightWindow = f(Entry.NIGHT_WINDOW), presenceRange = f(Entry.PRESENCE_RANGE), autoBrightness = t(Entry.AUTO_BRIGHTNESS),
        )
    }

    // ------------------------------------------------------------------ building

    private fun build(m: Model) {
        fields.clear(); toggles.clear(); cards.clear()
        column.removeAllViews()
        (radio.tiles.parent as? android.view.ViewGroup)?.removeView(radio.tiles)
        (radio.panel.parent as? android.view.ViewGroup)?.removeView(radio.panel)
        radioDialog.dismiss()
        m.radio?.let { radio.bind(it) }
        val layout = SettingsMenu.layout(m.facts)
        layout.forEachIndexed { i, (section, entries) ->
            val card = card(section, entries, m)
            cards[section] = card
            column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = px(14f) })
        }
        buildRail(m, layout.map { it.first })
    }

    private fun buildRail(m: Model, sections: List<SettingsMenu.Section>) {
        rail.removeAllViews()
        rail.addView(label("S1 Unleashed · Kiosk"))
        rail.addView(text(30f, TX, LIGHT).apply { letterSpacing = -0.02f; text = "Settings" },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        rail.addView(text(15f, MU, REGULAR).apply { text = "S1 Kiosk ${m.version}" },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        val nav = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        sections.forEach { s ->
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

    private fun navRow(s: SettingsMenu.Section) = LinearLayout(context).apply {
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
        setOnClickListener { cards[s]?.let { c -> scroll.smoothScrollTo(0, c.top) } }
    }

    private fun card(section: SettingsMenu.Section, entries: List<Entry>, m: Model): View =
        if (section == SettingsMenu.Section.RADIO && Entry.RADIO_MODE in entries) radioCard(entries, m) else LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(22f)
        setPadding(px(24f), px(20f), px(24f), px(22f))
        addView(label(section.title))
        SettingsMenu.rows(entries).forEach { row ->
            val v = if (row.size == 2) pair(entry(row[0], m), entry(row[1], m)) else entry(row[0], m)
            addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
        }
    }

    /**
     * The Radio section without an outer card: the role tiles and the panel sit in the column (their
     * own surfaces), then "who may connect" in a card of its own.
     */
    private fun radioCard(entries: List<Entry>, m: Model) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, px(4f), 0, 0)
        addView(label(SettingsMenu.Section.RADIO.title), LinearLayout.LayoutParams(-1, -2).apply { marginStart = px(4f) })
        addView(entry(Entry.RADIO_MODE, m), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        addView(entry(Entry.RADIO_FOLLOW_UP, m), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
        val allow = entries.filter { it == Entry.RADIO_ALLOW || it == Entry.RADIO_ALLOWED_NOW }
        if (allow.isNotEmpty()) addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(22f)
            setPadding(px(24f), px(18f), px(24f), px(20f))
            addView(label("Who may connect"))
            allow.forEach { addView(entry(it, m), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) }) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
    }

    private fun pair(a: View, b: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(12f) })
        addView(b, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun entry(e: Entry, m: Model): View {
        val v = m.values
        val number = InputType.TYPE_CLASS_NUMBER
        val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val plain = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        return when (e) {
            Entry.URL -> field(e, "Dashboard URL", v.url, "auto (find Home Assistant)", uri)
            Entry.ZOOM -> field(e, "Zoom (1.0 – 2.0)", v.zoom, "auto (device density)", decimal)
            Entry.SECURE_HOSTS -> field(e, "Extra secure-context hosts", v.secureHosts, "e.g. homeassistant.local,192.168.1.100", uri)
            Entry.HIDE_HEADER -> toggle(e, "Hide Home Assistant's header & sidebar", "The dashboard fills the screen", v.hideHeader)
            Entry.RADIO_MODE -> radio.tiles
            Entry.RADIO_FOLLOW_UP -> radio.panel
            Entry.RADIO_ALLOW -> field(e, "IP addresses or ranges, or any", v.radioAllow, "empty = Home Assistant only", uri).also {
                fields[e]?.isEnabled = m.radioRunning
            }
            Entry.RADIO_ALLOWED_NOW -> text(13.5f, MU, REGULAR).apply { text = m.allowedNow; setLineSpacing(0f, 1.2f) }
            Entry.PRESENCE -> toggle(e, "Screen follows presence", "Off when idle, on when someone comes close", v.presence)
            Entry.IDLE_OFF -> field(e, "Screen off after idle (s, 0 = never)", v.idleOff, "120", number)
            Entry.NIGHT_IDLE_OFF -> field(e, "At night: off after (s)", v.nightIdleOff, "20", number)
            Entry.NIGHT_WINDOW -> field(e, "Night window", v.nightWindow, "22:00-07:00 (empty = off)", plain)
            Entry.PRESENCE_RANGE -> field(e, "Presence range (m, 0.1 – 2.5)", v.presenceRange, "firmware default (1.5 m)", decimal)
            Entry.AUTO_BRIGHTNESS -> toggle(e, "Auto brightness", "Follows the light sensor", v.autoBrightness)
            Entry.AQARA_SETTINGS -> link(SettingsMenu.AQARA_TITLE, SettingsMenu.AQARA_DETAIL) { onAqaraSettings?.invoke() }
            Entry.VERSION -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(16f, TX, MEDIUM).apply { text = "S1 Kiosk ${m.version}" })
                addView(text(13f, FA, REGULAR).apply { text = m.status; setLineSpacing(0f, 1.2f) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
            }
            Entry.LICENCES -> link("Open source licences", "The open-source software in the app and the radio package") { onLicences?.invoke() }
        }
    }

    private fun field(e: Entry, title: String, value: String, hintText: String, type: Int): View {
        val edit = EditText(context).apply {
            textSize = 16f
            typeface = Inter.typeface(context, REGULAR, 16f)
            setTextColor(TX)
            setHintTextColor(FA)
            hint = hintText
            inputType = type
            // No full-screen "extract" editor in landscape: the field itself stays visible above the keyboard.
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
            setText(value)
            background = glass(14f)
            setPadding(px(16f), px(11f), px(16f), px(11f))
            setOnFocusChangeListener { f, has -> if (has) f.post { reveal(f) } }
        }
        fields[e] = edit
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(13.5f, MU, REGULAR).apply { text = title; setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
            addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
        }
    }

    private fun toggle(e: Entry, title: String, detail: String, on: Boolean): View {
        val sw = Toggle(context).apply { checked = on }
        toggles[e] = sw
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts(title, detail), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
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
        // The content's 30 dp bottom padding already sits between the column and the screen edge.
        val lp = scroll.layoutParams as LinearLayout.LayoutParams
        val margin = (inset - px(30f)).coerceAtLeast(0) + if (inset > 0) px(12f) else 0
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
        val viewTop = scroll.scrollY
        val viewBottom = viewTop + scroll.height
        when {
            top < viewTop -> scroll.smoothScrollTo(0, top.coerceAtLeast(0))
            bottom > viewBottom -> scroll.smoothScrollTo(0, bottom - scroll.height)
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
