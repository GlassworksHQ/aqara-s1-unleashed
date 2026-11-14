package sh.edm.s1kiosk.frame

import android.content.SharedPreferences

/** The time zone pane's settings, in the file `PaneContext.prefs("time-zone")` gives it (`kiosk.xml` on the S1). */
class TimeZonePrefs(private val sp: SharedPreferences) {

    /** The pane is on ([TimeZoneDefinition.switch] writes it). */
    val enabled: Boolean get() = sp.getBoolean(KEY_ENABLED, true)

    /**
     * The zone the panel kept when the pane was turned off, so turning it on again keeps it again. ""
     * while the pane is on.
     */
    var kept: String
        get() = sp.getString(KEY_KEPT, "") ?: ""
        set(v) { sp.edit().putString(KEY_KEPT, v).commit() }

    companion object {
        const val KEY_ENABLED = "time_zone_enabled"
        const val KEY_KEPT = "time_zone_kept"
    }
}
