package sh.edm.s1kiosk.base

import android.content.SharedPreferences

/**
 * The wall base pane's settings, in the file `PaneContext.prefs("wall-base")` gives it (`kiosk.xml`,
 * where they were before panes). The UART energy counter is the driver's own, in `s1base.xml` ([WallBase]).
 */
class WallBasePrefs(private val sp: SharedPreferences) {

    /** The pane is on ([WallBaseDefinition.switch] writes it). */
    val enabled: Boolean get() = sp.getBoolean(KEY_ENABLED, true)

    /**
     * Stop Aqara's cloud daemon (ha_master). With the UART wall-base backend it is never needed;
     * with the LocalService backend it is kept while a base is attached. See AqaraCloud.
     */
    var blockAqaraCloud: Boolean
        get() = sp.getBoolean(KEY_BLOCK_AQARA_CLOUD, true)
        set(v) = sp.edit().putBoolean(KEY_BLOCK_AQARA_CLOUD, v).apply()

    /** Wall-base backend: `auto` (UART unless cloud blocking is off), `uart`, `localservice`. See BaseBackend. */
    var backend: String
        get() = sp.getString(KEY_BACKEND, BaseBackend.AUTO) ?: BaseBackend.AUTO
        set(v) = sp.edit().putString(KEY_BACKEND, v.trim().lowercase().takeIf { it in BaseBackend.PREF_VALUES } ?: BaseBackend.AUTO).apply()

    /** UART backend port: `/dev/ttyS3` (default, "") or `tcp:host:port` for tools/bracket/base_emulator.py. */
    var transport: String
        get() = sp.getString(KEY_TRANSPORT, "")?.trim()?.takeIf { it.isNotEmpty() } ?: UartBaseLink.SERIAL_PATH
        set(v) = sp.edit().putString(KEY_TRANSPORT, v.trim()).apply()

    /** Last applied wall base layout (BaseLayout.encode), so HA sees the same entities after a restart. */
    var layout: String
        get() = sp.getString("base_layout", "") ?: ""
        set(v) = sp.edit().putString("base_layout", v).apply()

    companion object {
        const val KEY_ENABLED = "wall_base_enabled"
        const val KEY_BLOCK_AQARA_CLOUD = "block_aqara_cloud"
        const val KEY_BACKEND = "base_backend"
        const val KEY_TRANSPORT = "base_transport"
    }
}
