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
