package sh.edm.s1kiosk.sendspin

import android.content.SharedPreferences

/**
 * The Sendspin pane's settings, in the file `PaneContext.prefs("sendspin")` gives it (`kiosk.xml` on
 * the S1, where they were before panes). Its secrets live in `sendspin.xml` ([PrefsSsStore]).
 */
class SendspinPrefs(private val sp: SharedPreferences) {

    /** Run the native Sendspin player (ESPHome switch "Music Assistant player (Sendspin)"). */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** `auto` (mDNS `_sendspin-server._tcp`, else `ws://<HA host>:8927/sendspin`) or a host[:port][/path] / ws:// URL. */
    var server: String
        get() = sp.getString(KEY_SERVER, AUTO)?.takeIf { it.isNotBlank() } ?: AUTO
        set(v) = sp.edit().putString(KEY_SERVER, v.trim().ifEmpty { AUTO }).apply()

    /**
     * Sendspin "unpaired access" (guest mode): a server may play without pairing. Off by default:
     * Home Assistant hands Music Assistant the pairing token over the ESPHome API instead.
     */
    var unpairedAccess: Boolean
        get() = sp.getBoolean(KEY_UNPAIRED, false)
        set(v) = sp.edit().putBoolean(KEY_UNPAIRED, v).apply()

    /** Offer FLAC (decoded with MediaCodec) before PCM to the Sendspin server. PCM only when off. */
    var flac: Boolean
        get() = sp.getBoolean(KEY_FLAC, true)
        set(v) = sp.edit().putBoolean(KEY_FLAC, v).apply()

    companion object {
        const val AUTO = "auto"
        const val KEY_ENABLED = "sendspin_enabled"
        const val KEY_SERVER = "sendspin_server"
        const val KEY_UNPAIRED = "sendspin_unpaired_access"
        const val KEY_FLAC = "sendspin_flac"
    }
}
