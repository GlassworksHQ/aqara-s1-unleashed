package sh.edm.s1kiosk.base

import android.content.SharedPreferences
import dev.glassworks.pane.IntentExtra
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection

/** The wall base ([WallBasePane]) for the whole process: its on/off and adb extras (frames/aqara-s1-plus/research/bases.md). */
class WallBaseDefinition(file: SharedPreferences) : PaneDefinition {
    override val id = "wall-base"

    private val prefs = WallBasePrefs(file)

    override val switch = PaneSwitch(
        file, WallBasePrefs.KEY_ENABLED, default = true, title = "Wall base", detail = "The S1's wired base",
        objectId = "wall_base", icon = "mdi:light-switch", inSetup = true,
        offNote = "The wall base is off. The panel closes its port, and the base's relays and buttons are unavailable in Home Assistant until you turn it on.",
    )

    /**
     * The base the panel has (#212): how it is connected, its readings, and on a switch base its relays
     * and buttons. Without a base: what one adds, and the entities Home Assistant has.
     */
    override fun settings(running: Pane?): List<SettingsSection> {
        val p = running as? WallBasePane ?: return emptyList()
        val layout = p.tracker.current()
        val status = SettingsItem.Status(
            text = { WallBasePage.status(p.tracker.current(), p.backend).first },
            ok = { WallBasePage.status(p.tracker.current(), p.backend).second },
        )
        val items = if (layout == null || !layout.attached) listOfNotNull(
            status,
            SettingsItem.Native("wall-base-none", WallBasePage.NO_BASE_TITLE) { host -> NoBaseView(host.context) },
        ) else listOfNotNull(
            status,
            SettingsItem.Tiles(4) { p.tracker.current()?.takeIf { it.attached }?.let { l -> WallBasePage.strip(l, p.tracker.firmware(), p.tracker.protection(), p.tracker.power()) }.orEmpty() },
            // A view per base and pane: another base or a new device run makes its own.
            if (layout.kind == BaseKind.SWITCH && layout.channels.isNotEmpty()) {
                SettingsItem.Native("wall-base-relays-${System.identityHashCode(p)}-${layout.channels}", "Relays and buttons") { host -> RelaysView(host.context, p, layout.channels) }
            } else null,
        )
        // A mounted base's tiles and cards cover its entities, energy too: it stays hidden until #216 settles
        // its unit. The core lists the rest (Aqara's cloud), so the page has all Home Assistant has.
        val covers = if (layout == null || !layout.attached) emptySet() else BaseEntities.objectIds(layout).toSet()
        return listOf(SettingsSection(id, "Wall base", items, covers = covers))
    }

    override val extras = listOf(
        // auto|uart|localservice
        IntentExtra.string("base_backend") { prefs.backend = it },
        // "" (/dev/ttyS3) or tcp:host:port (tools/bracket/base_emulator.py)
        IntentExtra.string("base_transport") { prefs.transport = it },
    )
}
