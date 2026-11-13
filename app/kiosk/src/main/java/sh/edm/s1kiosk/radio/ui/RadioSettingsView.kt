package sh.edm.s1kiosk.radio.ui

import android.widget.LinearLayout
import dev.glassworks.pane.SettingsItem
import sh.edm.s1kiosk.radio.RadioDefinition

/**
 * The settings menu's live radio views: the modes as chips, then the panel (radio/ui/RadioSectionView),
 * made once per menu and kept across openings, so the dataset field keeps its focus and text. Their
 * confirmations open [dialog], centred over the whole menu. Main thread.
 */
internal class RadioSettingsView(private val host: SettingsItem.NativeHost, private val def: RadioDefinition) : SettingsItem.NativeView {

    private val dialog = RadioDialog(host.context).also { host.addLayer(it) }

    internal val section = RadioSectionView(host.context).apply {
        onRun = { r, done -> def.run(r, "settings menu", done, ::refresh) }
        // Same path as HA's `Thread dataset` text entity; never logged.
        onDataset = def::setDataset
        onFieldFocus = { v -> v.post { host.reveal(v) } }
        onAsk = { a, answer -> host.hideKeyboard(); dialog.show(a, answer) }
    }

    override val view = LinearLayout(host.context).apply {
        orientation = LinearLayout.VERTICAL
        addView(section.tiles, LinearLayout.LayoutParams(-1, -2))
        addView(section.panel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * resources.displayMetrics.density).toInt() })
    }

    /** Follows the radio: status, router state, Thread role. */
    override fun refresh() {
        def.readout.controller()?.refreshSoon()
        def.readout.facts()?.let(section::bind)
    }

    override fun reset() = dialog.dismiss()

    /** For the mock-ups. */
    internal val previewDialog: RadioDialog get() = dialog
}
