package sh.edm.s1kiosk.base

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.glassworks.pane.SettingsItem
import sh.edm.s1kiosk.ui.Glass

/**
 * The wall base's relays and buttons on its settings page (#212): a card per channel, three to a row,
 * with the relay's switch and the button's last press. A switch applies when tapped, as Home Assistant's
 * does; the toggle then follows what the base reports. Main thread.
 */
internal class RelaysView(context: Context, private val pane: WallBasePane, private val channels: List<Int>) : SettingsItem.NativeView {
    private val g = Glass(context)
    private val toggles = HashMap<Int, Glass.Toggle>()
    private val presses = HashMap<Int, TextView>()

    override val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(g.label("Relays and buttons"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = g.px(12f); bottomMargin = g.px(10f) })
        channels.chunked(3).forEachIndexed { r, row ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                repeat(3) { c ->
                    val v = row.getOrNull(c)?.let { card(context, it) } ?: View(context)
                    addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (c > 0) marginStart = g.px(10f) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = g.px(10f) })
        }
    }

    private fun card(context: Context, ch: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = g.surface(20f)
        setPadding(g.px(16f), g.px(12f), g.px(14f), g.px(12f))
        val toggle = g.toggle(pane.tracker.relay(ch) == true) { on -> pane.setRelay(ch, on) }.apply { contentDescription = "Relay $ch on" }
        toggles[ch] = toggle
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(g.text(16.5f, Glass.TX).apply { text = "Relay $ch" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(toggle)
        })
        addView(View(context).apply { setBackgroundColor(Glass.BD) }, LinearLayout.LayoutParams(-1, g.px(1f).coerceAtLeast(1)).apply { topMargin = g.px(12f); bottomMargin = g.px(10f) })
        addView(g.text(13.5f, Glass.FA).apply { text = "Button $ch" })
        addView(g.text(15f, Glass.MU).apply { setSingleLine() }.also { presses[ch] = it }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = g.px(3f) })
    }

    override fun refresh() {
        val now = System.currentTimeMillis()
        for (ch in channels) {
            pane.tracker.relay(ch)?.let { on -> toggles[ch]?.let { if (it.checked != on) it.checked = on } }
            presses[ch]?.text = WallBasePage.press(pane.lastPress(ch), now)
        }
    }
}

/** "Mount the panel on a wall base": what a base adds, in a card of its own. */
internal class NoBaseView(context: Context) : SettingsItem.NativeView {
    private val g = Glass(context)

    override val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = g.surface(24f)
        setPadding(g.px(26f), g.px(22f), g.px(26f), g.px(22f))
        addView(g.heading(22f).apply { text = WallBasePage.NO_BASE_TITLE })
        addView(g.text(15f, Glass.MU).apply { text = WallBasePage.NO_BASE_TEXT; setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = g.px(8f) })
    }

    override fun refresh() {}
}
