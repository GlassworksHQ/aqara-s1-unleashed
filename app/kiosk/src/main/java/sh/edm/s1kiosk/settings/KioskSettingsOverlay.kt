package sh.edm.s1kiosk.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.chipWord
import dev.glassworks.pane.paneState
import sh.edm.s1kiosk.setup.Backdrop
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Glass.Companion.AMBER
import sh.edm.s1kiosk.ui.Glass.Companion.BD
import sh.edm.s1kiosk.ui.Glass.Companion.FA
import sh.edm.s1kiosk.ui.Glass.Companion.MU
import sh.edm.s1kiosk.ui.Glass.Companion.ROSE
import sh.edm.s1kiosk.ui.Glass.Companion.TX
import sh.edm.s1kiosk.ui.GlassSlider
import sh.edm.s1kiosk.ui.GroupedRail
import sh.edm.s1kiosk.ui.Inter
import sh.edm.s1kiosk.ui.StackView
import sh.edm.s1kiosk.ui.TileView
import sh.edm.s1kiosk.ui.Wrap
import java.util.Locale

/**
 * The kiosk settings menu, as drawn in #63. Left: the mark, "Settings" and the rail ([GroupedRail], the
 * same as setup's): the core's Dashboard, Screen and Network, then a Panes group with each pane's
 * section, then Log, Security and About, in the order [Model.sections] has them
 * (dev.glassworks.pane.settingsOrder, #33's nav), and Done. A pane that can't run is greyed; panes that
 * are off fold under "Off · N" at the end of the group (#212). A pane's status dot follows its name, so
 * every name starts at the same x. Right: the section that is picked, in a glass card: a pane's on/off
 * at top right
 * ([SettingsSection.master]), then its rows, each a name with its value under it. A switch applies when
 * tapped, a slider when let go, a duration or a time window when picked, a typed value on Save. A change
 * that only takes effect at start shows #33's banner with Restart now.
 *
 * A full-screen overlay in MainActivity's window, above the setup screen. Main thread only.
 */
class KioskSettingsOverlay(context: Context) : FrameLayout(context) {

    data class Model(
        val sections: List<SettingsSection>,
        val version: String,
        /** The ids of the panes' sections: the rail's Panes group, and "Panes ›" before the heading. */
        val panes: Set<String> = emptySet(),
    )

    /** "Restart now": a change waits for the app to start again. */
    var onRestart: (() -> Unit)? = null

    /**
     * The model as it is now. After a pane is turned on or off its page changes (#212: an off pane's page
     * says what that means), so the menu asks again.
     */
    var reload: (() -> Model)? = null

    var isShowing = false
        private set

    private val g = Glass(context)
    private fun px(v: Float) = g.px(v)

    private var model: Model? = null
    private var current: String? = null
    /** A change that needs a restart was made since the app started. */
    private var restartPending = false

    /** Tighter than setup's: with the Panes group open the whole list fits above Done at 960x480 dp (#63 round 2). */
    private val nav = GroupedRail(context).apply { rowDp = 35f; childDp = 30f; gapDp = 2f; onTap = { id -> select(id) } }
    private val navScroll = ScrollView(context)
    private val rail = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8f), 0, 0, 0)
            addView(StackView(context), LinearLayout.LayoutParams(px(22f), px(21f)).apply { marginEnd = px(13f) })
            addView(g.heading(24f).apply { text = "Settings" })
        }, LinearLayout.LayoutParams(-1, px(40f)))
        addView(navScroll.apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true // with the Panes group open the rail can run past Done
            setFadingEdgeLength(px(28f))
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(nav, LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(16f) })
        addView(g.ghost("Done").apply { setPadding(0, px(12f), 0, px(12f)); setOnClickListener { hide() } },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
    }

    private val crumb = g.text(17f, FA).apply { text = "Panes ›" }
    private val title = g.heading(28f).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    /** The pane's on/off, at top right ([SettingsSection.master]). */
    private val masterSlot = FrameLayout(context)
    private val unavailable = g.text(15f, MU).apply { setLineSpacing(0f, 1.2f) }
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
            addView(masterSlot, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(16f) })
        }, LinearLayout.LayoutParams(-1, -2))
        addView(unavailable, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        addView(problem, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        addView(banner.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16f) })
        addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
    }
    /** A page taller than the card fades at the edge it continues past, and shows its scroll bar (#63 D5). */
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(px(36f))
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(page, LayoutParams(-1, -2))
    }
    private val sheet = Sheet()

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
        addView(sheet.view, LayoutParams(-1, -1))
        visibility = View.GONE
    }

    /** Shows the menu for [m], on the section it was on, else the first. Values are read again. */
    fun show(m: Model) {
        model = m
        if (m.sections.none { it.id == current }) current = m.sections.firstOrNull()?.id
        sheet.close()
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

    /**
     * Back: closes the topmost layer only, an open sheet (a picker, an editor, a question), then a pane's
     * own dialog, then the menu (#63 round 2: Back on a picker closed the whole menu).
     */
    fun back() {
        when {
            sheet.isOpen -> sheet.close()
            natives.values.any { it.back() } -> {}
            else -> hide()
        }
    }

    /** The rail fits without scrolling (tests). */
    internal val railFits: Boolean get() = nav.height <= navScroll.height

    fun hide() {
        if (!isShowing) return
        isShowing = false
        natives.values.forEach { it.reset() }
        sheet.close()
        hideKeyboard()
        findFocus()?.clearFocus()
        animate().alpha(0f).setDuration(200).withEndAction { if (!isShowing) visibility = View.GONE }.start()
    }

    /** While the menu is open (MainActivity, every couple of seconds): native views, live rows and the rail read their state again. */
    fun refresh() {
        if (!isShowing) return
        if (!sheet.isOpen && !masterBusy) reload?.invoke()?.let { next ->
            model = next
            val s = next.sections.firstOrNull { it.id == current }
            // The page changed under the person (a base mounted, the pane turned off from Home Assistant,
            // the device started): draw it again from the new model. Otherwise only its values move.
            if (s == null || shape(s) != builtShape) {
                if (s == null) current = next.sections.firstOrNull()?.id
                build()
                return
            }
        }
        // Only the page on screen: the others' views wait until they are shown again.
        natives.values.forEach { if (it.view.parent != null) it.refresh() }
        live.forEach { it() }
        bindRail()
        bindMaster()
    }

    /** The shape the open page was drawn from ([shape]). */
    private var builtShape: List<Any?> = emptyList()

    /** What a page is made of: what it reads ([SettingsSection.source]), its master's value, whether it can run, and its items' kinds and names. */
    private fun shape(s: SettingsSection): List<Any?> {
        fun of(items: List<SettingsItem>): List<Any?> = items.flatMap { i ->
            when (i) {
                is SettingsItem.Group -> listOf("group ${i.title}") + of(i.items)
                is SettingsItem.Native -> listOf("native ${i.key}")
                else -> listOf("${i::class.simpleName} ${titleOf(i).orEmpty()}")
            }
        }
        return listOf(s.source, s.master?.value(), s.unavailable() != null) + of(s.items)
    }

    /** The page's read-only rows ([SettingsItem.States]) that read their values again on [refresh]. */
    private val live = ArrayList<() -> Unit>()

    /** Previews (the mock-ups): a section's page, an open editor or picker, a native view, the restart banner. */
    internal fun previewSection(id: String) { current = id; build() }
    internal fun previewEdit(title: String, typed: String? = null) {
        when (val i = model?.sections?.flatMap { flat(it.items) }?.firstOrNull { titleOf(it) == title } ?: return) {
            is SettingsItem.Text -> { sheet.text(i) {}; typed?.let { sheet.field.setText(it) } }
            is SettingsItem.Duration -> sheet.duration(i) {}
            is SettingsItem.TimeRange -> sheet.range(i) {}
            is SettingsItem.Choice -> sheet.choice(i) {}
            else -> {}
        }
    }
    internal fun previewConfirm(id: String) {
        val s = model?.sections?.firstOrNull { it.id == id } ?: return
        val m = s.master ?: return
        m.confirm(!m.value())?.let { sheet.confirm(it) {} }
    }
    internal fun previewRestart() { restartPending = true; banner.bind() }
    internal fun previewNative(key: String): SettingsItem.NativeView? = natives[key]

    /** The native views kept now, by key (tests). */
    internal val keptNatives: Set<String> get() = natives.keys.toSet()
    internal fun previewScrollTo(v: View, alignBottom: Boolean = false) {
        val r = Rect()
        v.getDrawingRect(r)
        scroll.offsetDescendantRectToMyCoords(v, r)
        val y = if (alignBottom) r.bottom + px(16f) - scroll.height else r.top - px(16f)
        scroll.scrollTo(0, y.coerceAtLeast(0))
    }

    // ------------------------------------------------------------------ building

    private fun select(id: String) {
        if (current == id) return
        sheet.close(); hideKeyboard(); current = id; build()
    }

    private fun build() {
        val m = model ?: return
        bindRail()
        val s = m.sections.firstOrNull { it.id == current } ?: return
        builtShape = shape(s)
        crumb.visibility = if (s.id in m.panes) View.VISIBLE else View.GONE
        title.text = s.heading
        val why = s.unavailable()
        unavailable.text = why?.let { "This pane can't run on this panel: $it." }
        unavailable.visibility = if (why != null) View.VISIBLE else View.GONE
        problem.visibility = View.GONE
        bindMaster()
        banner.bind()
        rows.removeAllViews()
        live.clear()
        natives.values.forEach { n -> (n.view.parent as? android.view.ViewGroup)?.removeView(n.view); n.reset() }
        // A key no section has now (a pane from before a device restart, another base) is let go of.
        val keys = m.sections.flatMap { nativeKeys(it.items) }.toSet()
        natives.keys.retainAll(keys)
        addItems(s.items, divided = s.items.none { it is SettingsItem.Native })
        scroll.scrollTo(0, 0)
    }

    /** The rail: core sections on their own, the panes' under Panes; a pane that can't run is greyed, one that is off folds. */
    private fun bindRail() {
        val m = model ?: return
        nav.bind(m.sections.map { s ->
            val why = s.unavailable()
            val pane = s.id in m.panes
            // Every pane's chip after its name, the Manage page's state and word (#243). Status colours mean status only.
            val state = if (pane) paneState(s) else null
            GroupedRail.Row(
                s.id, s.title, group = if (pane) PANES else null,
                dim = why != null,
                off = why == null && s.master?.value() == false,
                chip = state?.chipWord?.let { word ->
                    GroupedRail.Chip(word, when (state.kind) { PaneState.Kind.RUNNING -> Glass.GREEN; PaneState.Kind.PROBLEM -> ROSE; else -> FA })
                },
            )
        }, mapOf(PANES to "Panes"), current)
    }

    private var masterToggle: Glass.Toggle? = null
    private var masterBusy = false

    /** The pane's on/off at top right. Asks first when the pane says so; locked while it applies. */
    private fun bindMaster() {
        val s = model?.sections?.firstOrNull { it.id == current }
        val m = s?.master
        if (m == null || s.unavailable() != null) { masterSlot.removeAllViews(); masterToggle = null; return }
        val t = masterToggle?.takeIf { it.tag === m } ?: g.toggle(m.value()) { on -> masterChanged(m, on) }.also {
            it.tag = m
            it.contentDescription = "${s.title} on"
            masterSlot.removeAllViews()
            masterSlot.addView(it)
            masterToggle = it
        }
        if (!masterBusy) t.checked = m.value()
    }

    private fun masterChanged(m: SettingsItem.Switch, on: Boolean) {
        val t = masterToggle ?: return
        if (masterBusy) { t.checked = !on; return }
        val ask = m.confirm(on)
        if (ask == null) { applyMaster(m, on); return }
        t.checked = !on // not until it's answered
        sheet.confirm(ask) { t.checked = on; applyMaster(m, on) }
    }

    private fun applyMaster(m: SettingsItem.Switch, on: Boolean) {
        masterBusy = true
        save({ done -> m.save(on, done) }) { why ->
            masterBusy = false
            if (why != null) { masterToggle?.checked = !on; showProblem(why) }
            else {
                problem.visibility = View.GONE
                if (m.restartsApp) restartPending = true
                reload?.invoke()?.let { model = it }
                build()
            }
        }
    }

    private fun nativeKeys(items: List<SettingsItem>): List<String> = items.flatMap { i ->
        when (i) {
            is SettingsItem.Native -> listOf(i.key)
            is SettingsItem.Group -> nativeKeys(i.items)
            else -> emptyList()
        }
    }

    private fun flat(items: List<SettingsItem>): List<SettingsItem> =
        items.flatMap { if (it is SettingsItem.Group) flat(it.items) else listOf(it) }

    private fun titleOf(i: SettingsItem): String? = when (i) {
        is SettingsItem.Text -> i.title
        is SettingsItem.Switch -> i.title
        is SettingsItem.Slider -> i.title
        is SettingsItem.Duration -> i.title
        is SettingsItem.TimeRange -> i.title
        is SettingsItem.Link -> i.title
        is SettingsItem.Choice -> i.title
        else -> null
    }

    /** [items] as rows; [divided]: a hairline above each row, as drawn, but not right under a status line or tiles. */
    private fun addItems(items: List<SettingsItem>, divided: Boolean) {
        var after: SettingsItem? = null
        for (i in items) {
            val under = after
            after = i
            if (i is SettingsItem.Group) {
                rows.addView(g.label(i.title), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(26f); bottomMargin = px(4f) })
                addItems(i.items, divided = true)
                continue
            }
            when (i) {
                is SettingsItem.States -> { rows.addView(states(i), LinearLayout.LayoutParams(-1, -2)); continue }
                is SettingsItem.Status -> { rows.addView(status(i), LinearLayout.LayoutParams(-1, -2)); continue }
                is SettingsItem.Tiles -> { rows.addView(tiles(i), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f); bottomMargin = px(6f) }); continue }
                else -> {}
            }
            if (divided && under !is SettingsItem.Status && under !is SettingsItem.Tiles) {
                rows.addView(View(context).apply { setBackgroundColor(BD) }, LinearLayout.LayoutParams(-1, px(1f).coerceAtLeast(1)))
            }
            rows.addView(item(i), LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun item(i: SettingsItem): View = when (i) {
        is SettingsItem.Text -> row(i.title, shownOf(i), chevron = i.enabled(), dim = !i.enabled()) { if (i.enabled()) sheet.text(i) { build() } }
        is SettingsItem.Switch -> toggleRow(i)
        is SettingsItem.Slider -> sliderRow(i)
        is SettingsItem.Duration -> row(i.title, i.shown(i.value()), chevron = true, dim = false) { sheet.duration(i) { build() } }
        is SettingsItem.TimeRange -> row(i.title, i.shown(i.value()), chevron = true, dim = false) { sheet.range(i) { build() } }
        is SettingsItem.Link -> row(i.title, i.detail, chevron = true, dim = i.detail.isEmpty()) { i.open(context) }
        is SettingsItem.Note -> g.text(14.5f, MU).apply { text = i.text(); setLineSpacing(0f, 1.25f); setPadding(0, px(14f), 0, px(14f)) }
        is SettingsItem.Info -> infoRow(i)
        is SettingsItem.Choice -> row(i.title, choiceName(i), chevron = true, dim = false) { sheet.choice(i) { build() } }.also { r ->
            // The picked one's name follows a change made elsewhere (Home Assistant's wake word select). The
            // value view is always there today (a choice always has a name); the null branch is a guard only.
            ((r.getChildAt(0) as? ViewGroup)?.getChildAt(1) as? TextView)?.let { value ->
                live += { val n = choiceName(i); if (value.text != n) value.text = n }
            }
        }
        is SettingsItem.Group, is SettingsItem.States, is SettingsItem.Status, is SettingsItem.Tiles -> error("laid out by addItems")
        is SettingsItem.Native -> natives.getOrPut(i.key) { i.create(nativeHost) }.also { it.refresh() }.view
    }

    /** A name and a status line under it, read again on [refresh] (#212: what plays now). */
    private fun infoRow(i: SettingsItem.Info): View {
        val name = g.text(16.5f, TX)
        val value = g.text(15f, MU).apply { setLineSpacing(0f, 1.15f) }
        fun bind() {
            name.text = i.title()
            val v = i.detail()
            if (value.text != v) value.text = v
            value.visibility = if (v.isEmpty()) View.GONE else View.VISIBLE
        }
        bind()
        live += ::bind
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(64f)
            setPadding(0, px(12f), 0, px(12f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
        }
    }

    private fun choiceName(i: SettingsItem.Choice): String { val v = i.value(); return i.options().firstOrNull { it.first == v }?.second ?: v }

    /**
     * "In Home Assistant" (#212): its label, then a name on the left and its value on the right, one line
     * each. Rows off by default in Home Assistant are dimmed, with a line that says why. Read again on [refresh].
     */
    private fun states(i: SettingsItem.States): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var names: List<String>? = null
        val values = ArrayList<TextView>()
        fun bind() {
            val list = i.rows()
            if (list.map { it.name } == names) {
                list.forEachIndexed { n, r -> if (values[n].text != r.value) values[n].text = r.value }
                return
            }
            names = list.map { it.name }
            values.clear()
            box.removeAllViews()
            box.addView(g.label(i.title), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22f); bottomMargin = px(6f) })
            list.forEach { r ->
                box.addView(View(context).apply { setBackgroundColor(BD) }, LinearLayout.LayoutParams(-1, px(1f).coerceAtLeast(1)))
                box.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = px(46f)
                    addView(g.text(15.5f, if (r.offByDefault) MU else TX).apply { text = r.name; setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
                    addView(g.text(15f, if (r.offByDefault) FA else MU).apply { text = r.value; setSingleLine() }.also { values += it })
                }, LinearLayout.LayoutParams(-1, -2))
            }
            if (list.any { it.offByDefault }) box.addView(g.text(13.5f, FA).apply { text = OFF_BY_DEFAULT; setLineSpacing(0f, 1.2f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12f) })
        }
        bind()
        live += ::bind
        return box
    }

    /** The line under the heading: a green dot while the pane works, a grey one otherwise, and its words. */
    private fun status(i: SettingsItem.Status): View {
        val dot = View(context)
        val words = g.text(15f, MU)
        fun bind() {
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i.ok()) Glass.GREEN else FA) }
            words.text = i.text()
        }
        bind()
        live += ::bind
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(2f), 0, px(6f))
            addView(dot, LinearLayout.LayoutParams(px(8f), px(8f)).apply { marginEnd = px(10f) })
            addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /** Readings as tiles, [SettingsItem.Tiles.columns] to a row; values read again on [refresh]. */
    private fun tiles(i: SettingsItem.Tiles): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val views = ArrayList<TileView>()
        val gap = px(if (i.columns >= 4) 10f else 12f)
        fun bind() {
            val list = i.tiles()
            if (list.size != views.size) {
                views.clear()
                box.removeAllViews()
                val graph = list.any { it.history != null || it.meter != null }
                list.chunked(i.columns).forEachIndexed { r, row ->
                    box.addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        repeat(i.columns) { c ->
                            val v = if (c < row.size) TileView(context, if (i.columns >= 4) 22f else 27f, graph).also { views += it } else View(context)
                            addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (c > 0) marginStart = gap })
                        }
                    }, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = gap })
                }
            }
            list.forEachIndexed { n, t -> views[n].bind(t) }
        }
        bind()
        live += ::bind
        return box
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
        fun apply(on: Boolean) {
            save({ done -> i.save(on, done) }) { why ->
                if (why != null) { t.checked = !on; showProblem(why) }
                else {
                    problem.visibility = View.GONE // what went wrong before is over
                    if (i.restartsApp) { restartPending = true; banner.bind() }
                }
            }
        }
        t = g.toggle(i.value()) { on ->
            val ask = i.confirm(on)
            if (ask == null) apply(on)
            else { t.checked = !on; sheet.confirm(ask) { t.checked = on; apply(on) } }
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

    /** The name with the value on its right, the slider under them; saved when let go. */
    private fun sliderRow(i: SettingsItem.Slider): View {
        val shown = g.text(15f, MU).apply { text = i.shown(i.value()) }
        val slider = GlassSlider(context, i.min, i.max, i.step).apply {
            value = i.value()
            contentDescription = i.title
            onMove = { v -> shown.text = i.shown(v) }
            onSet = { v ->
                if (v != i.value()) save({ done -> i.save(v, done) }) { why ->
                    if (why != null) { showProblem(why); value = i.value(); shown.text = i.shown(value) }
                    else problem.visibility = View.GONE
                }
            }
        }
        // A value changed elsewhere (Home Assistant set the volume) moves the slider too, unless it is held.
        live += {
            val v = i.value()
            if (!slider.dragging && v != slider.value) { slider.value = v; shown.text = i.shown(v) }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(14f), 0, px(8f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(g.text(16.5f, TX).apply { text = i.title }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(shown)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(slider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4f) })
        }
    }

    private fun texts(name: String, value: String, color: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(g.text(16.5f, color).apply { text = name })
        if (value.isNotEmpty()) addView(g.text(15f, MU).apply { text = value; setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
    }

    /**
     * Runs a save and hands its answer to [after] on the main thread. A save that throws is an answer
     * too (#63 F8): the menu says what went wrong instead of closing the app.
     */
    private fun save(run: ((String?) -> Unit) -> Unit, after: (String?) -> Unit) {
        val answer: (String?) -> Unit = { why -> post { after(why) } }
        runCatching { run(answer) }.onFailure { answer(couldNot(it)) }
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
     * The sheet a row opens (not in the design: the rows' chevrons open it): a typed value, a duration's
     * choices, a time window, or a question before a change. It sits near the top, so the keyboard
     * never covers it.
     */
    private inner class Sheet {
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
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { onSave?.invoke(); true } else false }
        }
        /** What the sheet shows under its name: the field, the choices or the window. */
        private val body = FrameLayout(context)
        private val why = g.text(15f, ROSE)
        private val cancel = g.plain("Cancel").apply { setOnClickListener { close() } }
        private val saveButton = g.primary("Save").apply { setOnClickListener { onSave?.invoke() } }
        private var onSave: (() -> Unit)? = null
        private val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(14f) })
            addView(saveButton)
        }
        private val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = g.surface(28f, Color.parseColor("#17181e"))
            setPadding(px(30f), px(26f), px(30f), px(22f))
            isClickable = true
            addView(name)
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18f) })
            addView(why, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
        }
        val view = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(170, 10, 11, 15))
            isClickable = true
            setOnClickListener { close() }
            addView(card, LayoutParams(px(560f), -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = px(40f) })
            visibility = View.GONE
        }

        private fun open(title: String, hintText: String, content: View?, action: String?) {
            name.text = title
            hint.text = hintText
            hint.visibility = if (hintText.isEmpty()) View.GONE else View.VISIBLE
            (field.parent as? android.view.ViewGroup)?.removeView(field)
            body.removeAllViews()
            content?.let { body.addView(it, LayoutParams(-1, -2)) }
            body.visibility = if (content != null) View.VISIBLE else View.GONE
            why.visibility = View.GONE
            saveButton.visibility = if (action != null) View.VISIBLE else View.GONE
            saveButton.text = action.orEmpty()
            busy(false)
            view.visibility = View.VISIBLE
            view.bringToFront()
        }

        private fun busy(on: Boolean) { saveButton.isEnabled = !on; saveButton.alpha = if (on) 0.5f else 1f }

        /** Runs [run]; closes the sheet and calls [after] once it applied, or says why not. */
        private fun commit(restarts: Boolean, after: () -> Unit, run: ((String?) -> Unit) -> Unit) {
            busy(true)
            val open = onSave
            save(run) { problem ->
                // A change that saved needs its restart, also when the sheet was closed meanwhile.
                if (problem == null && restarts) { restartPending = true; banner.bind() }
                if (onSave !== open || view.visibility != View.VISIBLE) return@save
                if (problem != null) { why.text = problem; why.visibility = View.VISIBLE; busy(false) }
                else {
                    this@KioskSettingsOverlay.problem.visibility = View.GONE
                    close()
                    after()
                }
            }
        }

        fun text(i: SettingsItem.Text, after: () -> Unit) {
            field.inputType = when (i.kind) {
                SettingsItem.TextKind.PLAIN -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                SettingsItem.TextKind.ADDRESS -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                SettingsItem.TextKind.NUMBER -> InputType.TYPE_CLASS_NUMBER
                SettingsItem.TextKind.DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            onSave = { commit(i.restartsApp, after) { done -> i.save(field.text?.toString().orEmpty(), done) } }
            open(i.title, i.hint, field, "Save")
            field.setText(i.value())
            field.hint = i.hint
            field.setSelection(field.text.length)
            // The keyboard once the field has focus and is laid out: asked at once, it didn't come up on
            // the panel (#63 round 2).
            field.requestFocus()
            field.post {
                field.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        /** The choices as chips; picking one applies it. */
        fun duration(i: SettingsItem.Duration, after: () -> Unit) {
            val now = i.value()
            val choices = (i.choices + now).distinct().sortedWith(compareBy<Int> { it == 0 }.thenBy { it })
            val chips = Wrap(context, px(10f), px(10f))
            onSave = null
            choices.forEach { sec ->
                chips.addView(g.chip(i.shown(sec).removePrefix("After ")).apply {
                    chosen = sec == now
                    setOnClickListener { onSave = {}; commit(false, after) { done -> i.save(sec, done) } }
                })
            }
            open(i.title, "", chips, null)
        }

        /** On or off, then From and To, each a step back or forward; Save applies both. */
        fun range(i: SettingsItem.TimeRange, after: () -> Unit) {
            val (from0, to0) = parseRange(i.value()) ?: (22 * 60 to 7 * 60)
            var from = from0; var to = to0
            var on = i.value().isNotBlank()
            val fromText = g.text(22f, TX).apply { gravity = Gravity.CENTER }
            val toText = g.text(22f, TX).apply { gravity = Gravity.CENTER }
            val times = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fun bind() { fromText.text = hhmm(from); toText.text = hhmm(to); times.alpha = if (on) 1f else 0.4f }
            fun stepper(label: String, shown: TextView, change: (Int) -> Unit) = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(g.text(16.5f, MU).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
                fun step(glyph: String, desc: String, d: Int) = g.ghost(glyph).apply {
                    setPadding(0, 0, 0, 0); gravity = Gravity.CENTER; textSize = 22f
                    contentDescription = "$label $desc"
                    setOnClickListener { if (on) { change(d); bind() } }
                }
                addView(step("−", "earlier", -i.stepMin), LinearLayout.LayoutParams(px(56f), px(44f)))
                addView(shown, LinearLayout.LayoutParams(px(100f), -2))
                addView(step("+", "later", i.stepMin), LinearLayout.LayoutParams(px(56f), px(44f)))
            }
            times.addView(stepper("From", fromText) { d -> from = Math.floorMod(from + d, 24 * 60) }, LinearLayout.LayoutParams(-1, -2))
            times.addView(stepper("To", toText) { d -> to = Math.floorMod(to + d, 24 * 60) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(g.text(16.5f, TX).apply { text = "On" }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(g.toggle(on) { v -> on = v; bind() })
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(14f) })
                addView(times, LinearLayout.LayoutParams(-1, -2))
            }
            bind()
            onSave = { commit(false, after) { done -> i.save(if (on) "${hhmm(from)}-${hhmm(to)}" else "", done) } }
            open(i.title, "", content, "Save")
        }

        /** The options as chips; picking one applies it. */
        fun choice(i: SettingsItem.Choice, after: () -> Unit) {
            val now = i.value()
            val chips = Wrap(context, px(10f), px(10f))
            onSave = null
            i.options().forEach { (id, name) ->
                chips.addView(g.chip(name).apply {
                    chosen = id == now
                    setOnClickListener { onSave = {}; commit(false, after) { done -> i.save(id, done) } }
                })
            }
            open(i.title, "", chips, null)
        }

        /** A question before a change: its words, Cancel and the action. */
        fun confirm(c: SettingsItem.Confirm, yes: () -> Unit) {
            onSave = { close(); yes() }
            open(c.title, c.detail, null, c.action)
        }

        val isOpen: Boolean get() = view.visibility == View.VISIBLE

        fun close() {
            if (view.visibility != View.VISIBLE) return
            onSave = null
            hideKeyboard()
            view.visibility = View.GONE
        }
    }

    companion object {
        const val RESTART_TEXT = "This takes effect after the app restarts. It takes about 5 seconds."
        const val OFF_BY_DEFAULT = "Dimmed: off by default in Home Assistant. Turn them on there to keep their history."
        private const val PANES = "panes"

        /** What a save that threw says. */
        fun couldNot(e: Throwable): String = "That didn't save: ${e.message ?: e.javaClass.simpleName}."

        fun hhmm(min: Int): String = String.format(Locale.ROOT, "%02d:%02d", min / 60, min % 60)

        /** `22:00-07:00` as minutes after midnight, or null. */
        fun parseRange(w: String): Pair<Int, Int>? {
            val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\s*$""").find(w) ?: return null
            val (h1, m1, h2, m2) = m.destructured
            val a = h1.toInt() * 60 + m1.toInt(); val b = h2.toInt() * 60 + m2.toInt()
            return if (a < 24 * 60 && b < 24 * 60) a to b else null
        }
    }
}
