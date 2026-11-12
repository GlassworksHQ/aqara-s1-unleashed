package sh.edm.s1kiosk.radio

import android.content.SharedPreferences

/**
 * The radio pane's settings, in the file `PaneContext.prefs("radio")` gives it (`kiosk.xml`, where
 * they were before panes). Written only by [RadioController].
 */
class RadioPrefs(private val sp: SharedPreferences) : AllowStore, StackStore {

    /** A [RadioMode] id (older builds stored the label; RadioMode.parse accepts both). */
    var mode: String?
        get() = sp.getString("radio_mode", null)
        set(v) = sp.edit().putString("radio_mode", v).apply()

    /** A [ZigbeeStack] id, for the coordinator mode (helper v4). */
    override var stack: String?
        get() = sp.getString("radio_zigbee_stack", null)
        set(v) = sp.edit().putString("radio_zigbee_stack", v).apply()

    override var haV4: String?
        get() = sp.getString("radio_ha_ipv4", null)
        set(v) = sp.edit().putString("radio_ha_ipv4", v).apply()

    override var haV6: String?
        get() = sp.getString("radio_ha_ipv6", null)
        set(v) = sp.edit().putString("radio_ha_ipv6", v).apply()

    override var explicit: String
        get() = sp.getString("radio_allow", "").orEmpty()
        set(v) = sp.edit().putString("radio_allow", v).apply()
}
