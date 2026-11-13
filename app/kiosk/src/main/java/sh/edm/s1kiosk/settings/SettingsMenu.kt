package sh.edm.s1kiosk.settings

import dev.glassworks.pane.SettingsItem

/**
 * How the kiosk settings menu ([KioskSettingsOverlay]) is asked for, and texts it shows. Pure, for tests.
 * What is in it: [CoreSettings] and the panes' definitions.
 *
 * Ways in: five taps in the top-left corner, the kiosk's notification in the shade (an intent extra,
 * [EXTRA]), or `adb shell am start -n sh.edm.s1kiosk/.MainActivity --ez kiosk_settings true` (the older
 * `--ez settings true` still works). Anyone at the panel can pull the shade down, so the menu is as
 * "hidden" as the corner gesture.
 */
object SettingsMenu {
    /** Boolean intent extra: open MainActivity with the menu shown (the notification sets it). */
    const val EXTRA = "kiosk_settings"
    /** The earlier name (`--ez settings true`), kept working. */
    const val LEGACY_EXTRA = "settings"

    /** True when [boolExtra] (name -> value, null = absent) asks for the menu. */
    fun requested(boolExtra: (String) -> Boolean?): Boolean =
        boolExtra(EXTRA) == true || boolExtra(LEGACY_EXTRA) == true

    const val AQARA_TITLE = "Panel settings (Aqara)"
    const val AQARA_DETAIL = "Wi-Fi, sound, display, updates, factory reset"

    /** [items] grouped into rows: two adjacent narrow fields share one, everything else has its own. */
    fun rows(items: List<SettingsItem>): List<List<SettingsItem>> {
        fun narrow(i: SettingsItem?) = i is SettingsItem.Text && i.narrow
        val out = ArrayList<List<SettingsItem>>()
        var i = 0
        while (i < items.size) {
            if (narrow(items[i]) && narrow(items.getOrNull(i + 1))) { out += listOf(items[i], items[i + 1]); i += 2 } else { out += listOf(items[i]); i++ }
        }
        return out
    }
}
