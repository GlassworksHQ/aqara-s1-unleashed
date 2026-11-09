package io.github.aguilaair.s1kiosk

import android.content.Context
import android.net.Uri

/** Persisted kiosk settings (plain SharedPreferences; nothing secret is stored). */
class KioskPrefs(context: Context) {
    private val sp = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString(KEY_URL, null) ?: BuildConfig.DEFAULT_URL
        set(v) = sp.edit().putString(KEY_URL, v.trim()).apply()

    /**
     * Page zoom as a CSS device-pixel ratio override (Gecko pref `layout.css.devPixelsPerPx`).
     * 0 = automatic (use the display density, 1.5 on the S1 Plus). Never change `wm density`
     * instead: on this panel it breaks GeckoView's surface sizing until reboot.
     */
    var zoom: Float
        get() = sp.getFloat(KEY_ZOOM, 0f)
        set(v) = sp.edit().putFloat(KEY_ZOOM, v).apply()

    /** Extra hosts (comma separated) to treat as secure contexts, besides the dashboard host. */
    var extraSecureHosts: String
        get() = sp.getString(KEY_SECURE_HOSTS, "") ?: ""
        set(v) = sp.edit().putString(KEY_SECURE_HOSTS, v.trim()).apply()

    var remoteDebugging: Boolean
        get() = sp.getBoolean(KEY_REMOTE_DEBUG, BuildConfig.DEBUG)
        set(v) = sp.edit().putBoolean(KEY_REMOTE_DEBUG, v).apply()

    // ---- HA page tweaks (applied by the built-in web extension)

    var hideHeader: Boolean
        get() = sp.getBoolean("hide_header", true)
        set(v) = sp.edit().putBoolean("hide_header", v).apply()

    var hideSidebar: Boolean
        get() = sp.getBoolean("hide_sidebar", true)
        set(v) = sp.edit().putBoolean("hide_sidebar", v).apply()

    // ---- screen policy (see ScreenController)

    /** "presence" = on while someone is near / recently touched, off after idle; "always" = never off. */
    var screenMode: String
        get() = sp.getString("screen_mode", "presence") ?: "presence"
        set(v) = sp.edit().putString("screen_mode", v).apply()

    var idleOffSec: Int
        get() = sp.getInt("idle_off_sec", 120)
        set(v) = sp.edit().putInt("idle_off_sec", v).apply()

    /** Night window "HH:MM-HH:MM" (local time); empty = no night mode. */
    var nightWindow: String
        get() = sp.getString("night_window", "22:00-07:00") ?: ""
        set(v) = sp.edit().putString("night_window", v.trim()).apply()

    var nightIdleOffSec: Int
        get() = sp.getInt("night_idle_off_sec", 20)
        set(v) = sp.edit().putInt("night_idle_off_sec", v).apply()

    var wakeOnApproach: Boolean
        get() = sp.getBoolean("wake_on_approach", true)
        set(v) = sp.edit().putBoolean("wake_on_approach", v).apply()

    /** Presence range in metres for the VI5300 (0 = leave the firmware setting alone). */
    var presenceRangeM: Float
        get() = sp.getFloat("presence_range_m", 0f)
        set(v) = sp.edit().putFloat("presence_range_m", v).apply()

    var autoBrightness: Boolean
        get() = sp.getBoolean("auto_brightness", true)
        set(v) = sp.edit().putBoolean("auto_brightness", v).apply()

    var minBrightness: Float
        get() = sp.getFloat("min_brightness", 0.10f)
        set(v) = sp.edit().putFloat("min_brightness", v).apply()

    var maxBrightness: Float
        get() = sp.getFloat("max_brightness", 1.0f)
        set(v) = sp.edit().putFloat("max_brightness", v).apply()

    var nightMaxBrightness: Float
        get() = sp.getFloat("night_max_brightness", 0.2f)
        set(v) = sp.edit().putFloat("night_max_brightness", v).apply()

    /** Hosts that Gecko should treat as a secure context, so getUserMedia/AudioWorklet work over http. */
    fun secureHosts(): List<String> {
        val hosts = mutableListOf<String>()
        Uri.parse(url).host?.let { hosts += it }
        extraSecureHosts.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { hosts += it }
        return hosts.distinct()
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_SECURE_HOSTS = "secure_hosts"
        private const val KEY_REMOTE_DEBUG = "remote_debug"
    }
}
