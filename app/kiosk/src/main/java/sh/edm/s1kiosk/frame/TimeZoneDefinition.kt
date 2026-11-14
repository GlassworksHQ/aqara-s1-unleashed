package sh.edm.s1kiosk.frame

import android.content.SharedPreferences
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import java.util.TimeZone

/** The time zone ([TimeZonePane]) for the whole process: its on/off and its page. */
class TimeZoneDefinition(file: SharedPreferences) : PaneDefinition {
    override val id = "time-zone"

    // `time_zone` is the zone's own text entity, so the switch says what it does.
    override val switch = PaneSwitch(
        file, TimeZonePrefs.KEY_ENABLED, default = true, title = "Time zone", detail = "Keeps the zone Aqara resets at boot",
        objectId = "keep_time_zone", entityName = "Keep time zone", icon = "mdi:map-clock-outline",
        offNote = "The panel no longer keeps the time zone. Aqara's settings set their own at the next start.",
    )

    /** The zone, picked from a list or Home Assistant's, and whether the panel keeps it (#212). */
    override fun settings(running: Pane?): List<SettingsSection> {
        val p = running as? TimeZonePane ?: return emptyList()
        return listOf(SettingsSection(id, "Time zone", listOf(
            SettingsItem.Native("time-zone-${System.identityHashCode(p)}") { host ->
                TimeZoneView(host, p, TimeZoneChoices(TimeZone.getAvailableIDs().toList()) { id -> android.icu.util.TimeZone.getCanonicalID(id) ?: id })
            },
        )))
    }
}
