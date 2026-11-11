package sh.edm.s1kiosk.settings

/**
 * What the kiosk settings menu ([KioskSettingsOverlay]) shows, and how it is asked for. Pure, for tests.
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

    enum class Section(val title: String) {
        DASHBOARD("Home Assistant & dashboard"),
        RADIO("Radio"),
        PANEL("Panel"),
        ABOUT("About"),
    }

    enum class Entry {
        // Home Assistant & dashboard
        URL, ZOOM, SECURE_HOSTS, HIDE_HEADER,
        // Radio: the mode picker, the active mode's follow-up card (setup/RadioFollowUp), who may connect
        RADIO_MODE, RADIO_FOLLOW_UP, RADIO_ALLOW, RADIO_ALLOWED_NOW,
        // Panel (the kiosk's screen behaviour, then Aqara's own settings app)
        PRESENCE, IDLE_OFF, NIGHT_IDLE_OFF, NIGHT_WINDOW, PRESENCE_RANGE, AUTO_BRIGHTNESS, AQARA_SETTINGS,
        // About
        VERSION, LICENCES,
    }

    data class Facts(
        /** Aqara's device settings app resolves (DeviceSettings.intent != null). */
        val deviceSettings: Boolean,
        /** The kiosk service (and so RadioController) runs: the mode picker and follow-up card can work. */
        val radio: Boolean = true,
    )

    /** The sections in order, each with its entries; empty sections are left out. */
    fun layout(f: Facts): List<Pair<Section, List<Entry>>> = listOf(
        Section.DASHBOARD to listOf(Entry.URL, Entry.ZOOM, Entry.SECURE_HOSTS, Entry.HIDE_HEADER),
        Section.RADIO to listOfNotNull(
            Entry.RADIO_MODE.takeIf { f.radio }, Entry.RADIO_FOLLOW_UP.takeIf { f.radio }, Entry.RADIO_ALLOW, Entry.RADIO_ALLOWED_NOW,
        ),
        Section.PANEL to listOfNotNull(
            Entry.PRESENCE, Entry.IDLE_OFF, Entry.NIGHT_IDLE_OFF, Entry.NIGHT_WINDOW, Entry.PRESENCE_RANGE,
            Entry.AUTO_BRIGHTNESS, Entry.AQARA_SETTINGS.takeIf { f.deviceSettings },
        ),
        Section.ABOUT to listOf(Entry.VERSION, Entry.LICENCES),
    ).filter { it.second.isNotEmpty() }

    /** Short fields shown side by side (first -> second), when both are there and in this order. */
    val PAIRS = mapOf(
        Entry.ZOOM to Entry.SECURE_HOSTS,
        Entry.IDLE_OFF to Entry.NIGHT_IDLE_OFF,
        Entry.NIGHT_WINDOW to Entry.PRESENCE_RANGE,
    )

    /** [entries] grouped into rows: a pair from [PAIRS] when its two fields are adjacent, else one. */
    fun rows(entries: List<Entry>): List<List<Entry>> {
        val out = ArrayList<List<Entry>>()
        var i = 0
        while (i < entries.size) {
            val e = entries[i]
            val next = entries.getOrNull(i + 1)
            if (next != null && PAIRS[e] == next) { out += listOf(e, next); i += 2 } else { out += listOf(e); i++ }
        }
        return out
    }

    const val AQARA_TITLE = "Panel settings (Aqara)"
    const val AQARA_DETAIL = "Wi-Fi, sound, display, updates, factory reset"

    /** The line under the radio allow-list: who may connect right now. */
    fun allowedNow(radioRunning: Boolean, allowNow: String?): String = when {
        !radioRunning -> "Kiosk service not running"
        allowNow == null -> "Allowed now: no firewall active (radio off or old root helper)"
        allowNow.isEmpty() -> "Allowed now: only the panel itself (Home Assistant hasn't connected yet)"
        else -> "Allowed now: ${allowNow.replace(" ", ", ")}"
    }
}
