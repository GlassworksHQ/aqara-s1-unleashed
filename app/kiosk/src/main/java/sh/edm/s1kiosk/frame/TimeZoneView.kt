package sh.edm.s1kiosk.frame

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.pane.SettingsItem
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Inter
import java.time.Instant
import java.util.TimeZone

/**
 * The time zone's page (#212): the zone with a chevron that opens the picker, whether the panel keeps
 * it, and Home Assistant's zone once it said. The picker is a sheet: Home Assistant's zone to use in
 * one tap, then regions on the left and their cities on the right, each with its offset and time now.
 * Nothing is typed, so no keyboard hides the list. Main thread.
 */
internal class TimeZoneView(
    private val host: SettingsItem.NativeHost,
    private val pane: TimeZonePane,
    private val choices: TimeZoneChoices,
    /**
     * Runs the match of Home Assistant's zone, which reads a year of offsets: never on the main thread (the
     * pane's own thread). False when it couldn't take the work.
     */
    private val background: (Runnable) -> Boolean,
) : SettingsItem.NativeView {
    private val context: Context = host.context
    private val g = Glass(context)
    private fun px(v: Float) = g.px(v)

    private val zoneValue = g.text(15f, Glass.MU)
    private val keptTitle = g.text(16.5f, Glass.TX)
    private val keptValue = g.text(15f, Glass.MU)
    private val haValue = g.text(15f, Glass.MU)
    private val haRow: View

    override val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(row(g.text(16.5f, Glass.TX).apply { text = "Time zone" }, zoneValue, chevron = true).apply {
            isClickable = true
            setOnClickListener { open() }
        })
        addView(row(keptTitle, keptValue, chevron = false))
        haRow = row(g.text(16.5f, Glass.TX).apply { text = "Home Assistant's time zone" }, haValue, chevron = false)
        addView(haRow)
    }

    /** The zone the panel shows now, by its current name: the one it keeps, else the system's. */
    private fun current() = choices.name(pane.kept.ifEmpty { TimeZone.getDefault().id })

    private var haCache: Pair<Pair<String, String>, TimeZoneChoices.HomeAssistantZone?>? = null
    /** The rule and zone being matched now, on [background]. */
    private var matching: Pair<String, String>? = null

    /**
     * Home Assistant's zone matched to the panel's zones, once per rule and zone. Null until the match,
     * which runs on [background] and posts its answer back: the row shows then.
     */
    private fun homeAssistant(): TimeZoneChoices.HomeAssistantZone? {
        val rule = pane.homeAssistantRule ?: return null
        val zone = current()
        val key = rule to zone
        haCache?.let { (k, v) -> if (k == key) return v }
        if (matching != key) {
            matching = key
            val queued = background {
                val started = SystemClock.elapsedRealtimeNanos()
                val found = runCatching { choices.homeAssistant(rule, zone, Instant.now()) }.getOrNull()
                // The panel run records this (#235).
                Log.i(TimeZoneKeeper.TAG, "matched Home Assistant's zone in ${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000} ms: $found")
                view.post {
                    if (matching != key) return@post
                    matching = null
                    haCache = key to found
                    refresh()
                    // An open picker gets its card now too, keeping what the person picked (CodeRabbit on #235).
                    fillHomeAssistant?.invoke()
                }
            }
            // Refused (the pane's thread had stopped): the next refresh tries again (#235 verifier).
            if (!queued) matching = null
        }
        return null
    }

    override fun refresh() {
        val now = Instant.now()
        zoneValue.text = TimeZoneChoices.line(current(), now)
        val (title, line) = TimeZoneChoices.kept(pane.status)
        keptTitle.text = title
        keptValue.text = line
        val ha = homeAssistant()
        haRow.visibility = if (ha == null) View.GONE else View.VISIBLE
        haValue.text = ha?.line(now).orEmpty()
    }

    private fun row(title: TextView, value: TextView, chevron: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(context).apply { setBackgroundColor(Glass.BD) }, LinearLayout.LayoutParams(-1, px(1f).coerceAtLeast(1)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(64f)
            setPadding(0, px(12f), 0, px(12f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(16f) })
            if (chevron) addView(g.text(26f, Glass.MU, Inter.LIGHT).apply { text = "›" })
        }, LinearLayout.LayoutParams(-1, -2))
    }

    // ------------------------------------------------------------------ the picker

    private var layer: View? = null

    override fun back(): Boolean {
        if (layer == null) return false
        close()
        return true
    }

    override fun reset() = close()

    private fun close() {
        layer?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        layer = null
        fillHomeAssistant = null
    }

    /** Fills the open picker's Home Assistant card; null while the picker is closed. */
    private var fillHomeAssistant: (() -> Unit)? = null

    /** Opens the picker on the current zone's region. Internal for the mock-ups. */
    internal fun open() {
        close()
        val now = Instant.now()
        var chosen = current()
        var region = choices.regionOf(chosen)
        val why = g.text(14.5f, Glass.ROSE).apply { visibility = View.GONE }
        val regionList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val zoneList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val zoneScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(px(28f))
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(zoneList, FrameLayout.LayoutParams(-1, -2))
        }

        fun keep(zone: String) {
            val problem = runCatching { pane.keep(zone) }.getOrElse { it.message ?: "It didn't work." }
            if (problem != null) { why.text = problem; why.visibility = View.VISIBLE; return }
            close()
            refresh()
        }

        lateinit var bindZones: () -> Unit
        fun bindRegions() {
            regionList.removeAllViews()
            choices.regions.forEach { r ->
                regionList.addView(g.text(16f, if (r == region) Glass.TX else Glass.MU).apply {
                    text = r
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(16f), 0, px(12f), 0)
                    background = if (r == region) g.surface(12f, Glass.S2) else null
                    isClickable = true
                    setOnClickListener { region = r; bindRegions(); bindZones(); zoneScroll.scrollTo(0, 0) }
                }, LinearLayout.LayoutParams(-1, px(36f)).apply { topMargin = px(2f) })
            }
        }
        bindZones = {
            zoneList.removeAllViews()
            choices.zones(region).forEachIndexed { i, (id, name) ->
                if (i > 0) zoneList.addView(View(context).apply { setBackgroundColor(Glass.BD) }, LinearLayout.LayoutParams(-1, px(1f).coerceAtLeast(1)))
                zoneList.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(14f), 0, px(14f), 0)
                    if (id == chosen) background = g.surface(12f, Glass.S2)
                    addView(g.text(16.5f, Glass.TX).apply { text = name; setSingleLine() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(12f) })
                    addView(g.text(14.5f, Glass.MU).apply { text = "${TimeZoneChoices.offset(id, now)} · ${TimeZoneChoices.clock(id, now)}" })
                    if (id == current()) addView(View(context).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Glass.SKY, Glass.VIOLET)).apply { shape = GradientDrawable.OVAL }
                    }, LinearLayout.LayoutParams(px(9f), px(9f)).apply { marginStart = px(12f) })
                    contentDescription = id
                    isClickable = true
                    setOnClickListener { chosen = id; bindZones() }
                }, LinearLayout.LayoutParams(-1, px(46f)))
            }
        }
        bindRegions()
        bindZones()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = g.surface(28f, Color.parseColor("#17181e"))
            setPadding(px(26f), px(20f), px(26f), px(18f))
            isClickable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(g.heading(26f).apply { text = "Time zone" }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(g.plain("Cancel").apply { setOnClickListener { close() } }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(14f) })
                addView(g.primary("Save").apply { setPadding(px(26f), px(10f), px(26f), px(10f)); setOnClickListener { keep(chosen) } })
            })
            // Home Assistant sends a rule, not a name: "Use it" keeps a zone here with the same clock, and says which.
            val haSlot = FrameLayout(context)
            addView(haSlot, LinearLayout.LayoutParams(-1, -2))
            fillHomeAssistant = { haSlot.removeAllViews(); homeAssistant()?.let { ha ->
                val (clock, line, use) = when (ha) {
                    is TimeZoneChoices.HomeAssistantZone.Same ->
                        Triple(TimeZoneChoices.offset(ha.zone, now), "The same as the panel's · ${TimeZoneChoices.clock(ha.zone, now)} now", null)
                    is TimeZoneChoices.HomeAssistantZone.Like ->
                        Triple(TimeZoneChoices.offset(ha.zone, now), "Keeps ${ha.zone} · ${TimeZoneChoices.clock(ha.zone, now)} now", ha.zone)
                    is TimeZoneChoices.HomeAssistantZone.Elsewhere ->
                        Triple(ha.offset(now), "The panel keeps its own zone", null)
                }
                haSlot.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = g.surface(16f)
                    setPadding(px(16f), px(10f), px(10f), px(10f))
                    minimumHeight = px(62f)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(g.text(15.5f, Glass.TX).apply { text = "Home Assistant's clock: $clock" })
                        addView(g.text(13.5f, Glass.MU).apply { text = line })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    use?.let { z -> addView(g.ghost("Use it").apply { setPadding(px(18f), px(8f), px(18f), px(8f)); setOnClickListener { keep(z) } }) }
                }, FrameLayout.LayoutParams(-1, -2).apply { topMargin = px(14f) })
            } }
            fillHomeAssistant?.invoke()
            addView(why, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(regionList, FrameLayout.LayoutParams(-1, -2))
                }, LinearLayout.LayoutParams(px(170f), -1).apply { marginEnd = px(14f) })
                addView(View(context).apply { setBackgroundColor(Glass.BD) }, LinearLayout.LayoutParams(px(1f).coerceAtLeast(1), -1).apply { marginEnd = px(14f) })
                addView(zoneScroll, LinearLayout.LayoutParams(0, -1, 1f))
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(6f) })
        }
        val l = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(170, 10, 11, 15))
            isClickable = true
            setOnClickListener { close() }
            addView(card, FrameLayout.LayoutParams(px(720f), px(436f), Gravity.CENTER))
        }
        layer = l
        host.addLayer(l)
        // The chosen zone in sight: its row's top, less a row, once laid out.
        zoneScroll.post {
            val i = choices.zones(region).indexOfFirst { it.first == chosen }
            if (i > 0) zoneScroll.scrollTo(0, (i - 1) * (px(46f) + px(1f).coerceAtLeast(1)))
        }
    }

}
