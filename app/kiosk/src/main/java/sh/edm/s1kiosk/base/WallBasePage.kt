package sh.edm.s1kiosk.base

import dev.glassworks.pane.SettingsItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The wall base's settings page in words (#212): which base, how it is connected, its strip of readings
 * and each button's last press. Pure, for tests. The energy reading waits until a real base settles its
 * unit (#216).
 */
object WallBasePage {
    /** The line under the heading, and whether a base works (its dot). [layout] null: not known yet. */
    fun status(layout: BaseLayout?, backend: BaseBackend): Pair<String, Boolean> {
        val via = when (backend) {
            BaseBackend.UART -> "over the panel's serial port"
            BaseBackend.LOCALSERVICE -> "through Aqara's service"
        }
        return when {
            layout == null || layout.kind == BaseKind.UNAVAILABLE -> "Looking for a base · $via" to false
            !layout.attached -> "No base connected" to false
            else -> "Connected · $via" to true
        }
    }

    /** Base, firmware, and on a switch base its protection and power. */
    fun strip(layout: BaseLayout, firmware: String?, protection: Int?, power: Float?): List<SettingsItem.Tile> {
        val base = when (layout.kind) {
            BaseKind.SWITCH -> SettingsItem.Tile("Base", "Switch", detail = channels(layout.channels.size))
            BaseKind.ETHERNET -> SettingsItem.Tile("Base", "Ethernet")
            else -> SettingsItem.Tile("Base", "Unknown", detail = layout.describe())
        }
        val tiles = listOf(base, SettingsItem.Tile("Firmware", firmware ?: NONE, detail = "From the base"))
        if (layout.kind != BaseKind.SWITCH) return tiles
        val (word, line) = protection(protection)
        return tiles + listOf(
            SettingsItem.Tile("Protection", word, detail = line),
            SettingsItem.Tile("Power", power?.let { String.format(Locale.ROOT, "%.1f", it) } ?: NONE, "W"),
        )
    }

    /** The base's protection state ([BaseProtocol.protectionText]) as a word and a line. */
    fun protection(v: Int?): Pair<String, String> = when (v) {
        null -> NONE to ""
        0 -> "Normal" to "No overload"
        1 -> "Too hot" to "It cut the load"
        2 -> "Overload" to "It cut the load"
        3 -> "Too hot" to "It needs a restart"
        4 -> "Overload" to "It needs a restart"
        5, 6 -> "Normal" to "Recovered"
        else -> "Unknown" to "Code $v"
    }

    /** A button's last press: `Double · 2 min ago`, `Single · 14:02`, or `Not pressed yet`. */
    fun press(last: Pair<String, Long>?, nowMs: Long): String {
        val (type, at) = last ?: return "Not pressed yet"
        val ago = (nowMs - at) / 60_000
        val word = when (type) { "long" -> "Long press"; else -> type.replaceFirstChar { it.uppercase() } }
        val whenText = when {
            ago < 1 -> "just now"
            ago < 60 -> "$ago min ago"
            else -> SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(at))
        }
        return "$word · $whenText"
    }

    private fun channels(n: Int) = if (n == 1) "1 channel" else "$n channels"

    const val NO_BASE_TITLE = "Mount the panel on a wall base"
    const val NO_BASE_TEXT = "An Aqara switch base adds relays and buttons. They show up here, and in Home Assistant as Base relay and " +
        "Base button. An Ethernet base shows up as its type only."
    private const val NONE = "—"
}
