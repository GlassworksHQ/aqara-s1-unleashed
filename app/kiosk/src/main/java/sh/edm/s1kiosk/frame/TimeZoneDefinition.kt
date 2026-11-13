package sh.edm.s1kiosk.frame

import android.content.SharedPreferences
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch

/** The time zone ([TimeZonePane]) for the whole process: its on/off. It has no other setting on the panel. */
class TimeZoneDefinition(file: SharedPreferences) : PaneDefinition {
    override val id = "time-zone"

    // `time_zone` is the zone's own text entity, so the switch says what it does.
    override val switch = PaneSwitch(
        file, TimeZonePrefs.KEY_ENABLED, default = true, title = "Time zone", detail = "Keeps the zone Aqara resets at boot",
        objectId = "keep_time_zone", entityName = "Keep time zone", icon = "mdi:map-clock-outline",
    )
}
