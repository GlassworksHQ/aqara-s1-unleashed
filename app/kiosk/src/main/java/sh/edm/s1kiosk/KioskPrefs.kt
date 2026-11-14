package sh.edm.s1kiosk

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import dev.glassworks.bluetoothproxy.BluetoothProxyPrefs
import dev.glassworks.camera.CameraPrefs
import dev.glassworks.diagnostics.DiagnosticsPrefs
import dev.glassworks.screen.ScreenSettings
import sh.edm.esphome.NoiseKeyStore
import sh.edm.s1kiosk.auth.OAuth
import sh.edm.s1kiosk.auth.StoredAuth
import sh.edm.s1kiosk.base.WallBasePrefs
import sh.edm.s1kiosk.frame.TimeZonePrefs
import sh.edm.s1kiosk.radio.RadioPrefs
import sh.edm.s1kiosk.sendspin.SendspinPrefs
import sh.edm.s1kiosk.voice.VoicePrefs

/**
 * The panel's core settings (SharedPreferences, private to the app), and each pane's typed settings
 * for the code that builds the panes and their definitions (GlassworksRuntime.paneDefinitions). Secrets: the ESPHome API encryption key (`kiosk.xml`, which Home Assistant provisions) and
 * the HA refresh token (`auth.xml`, see [auth]). Neither is ever logged.
 */
class KioskPrefs(private val context: Context) : ScreenSettings {
    private val sp = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
    private val authSp = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    /** The file that holds the settings of the pane with id [namespace] ([dev.glassworks.pane.PaneContext.prefs]). */
    fun paneFile(namespace: String): SharedPreferences = context.getSharedPreferences(
        PANE_FILES[namespace] ?: throw IllegalArgumentException("No settings file for pane '$namespace'"),
        Context.MODE_PRIVATE,
    )

    val sendspin = SendspinPrefs(paneFile("sendspin"))
    val voice = VoicePrefs(paneFile("assist"))
    val wallBase = WallBasePrefs(paneFile("wall-base"))
    val radio = RadioPrefs(paneFile("radio"))
    val timeZone = TimeZonePrefs(paneFile("time-zone"))
    val diagnostics = DiagnosticsPrefs(paneFile("diagnostics"))
    val camera = CameraPrefs(paneFile("camera"))
    val bluetoothProxy = BluetoothProxyPrefs(paneFile("bluetooth-proxy"))

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    /**
     * The dashboard URL setting: a URL, or [AUTO] (the default) to use Home Assistant's root on the
     * discovered [haUrl]. A URL saved by an earlier version is kept as is.
     */
    var urlSetting: String
        get() = sp.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: AUTO
        // commit(), not apply(): a URL change can be followed by an app restart (secure-context hosts),
        // and an async write could be lost with the process, leaving the old URL ("auto").
        set(v) { sp.edit().putString(KEY_URL, v.trim().ifEmpty { AUTO }).commit() }

    val isAutoUrl: Boolean get() = urlSetting.equals(AUTO, ignoreCase = true)

    /** The dashboard URL to load, or "" while [isAutoUrl] and Home Assistant hasn't been found. */
    var url: String
        get() = if (isAutoUrl) haUrl.takeIf { it.isNotEmpty() }?.let { "$it/" } ?: "" else urlSetting
        set(v) { urlSetting = v }

    /** Home Assistant base URL found by discovery or entered on the sign-in screen (`http://h:8123`). */
    val haUrl: String get() = sp.getString("ha_url", "") ?: ""

    /** How [haUrl] was learnt: `mdns`, `esphome`, `manual` (HaDiscovery.SOURCE_*), or "". */
    val haUrlSource: String get() = sp.getString("ha_url_source", "") ?: ""

    fun setHaUrl(url: String, source: String) =
        sp.edit().putString("ha_url", url).putString("ha_url_source", source).commit()

    /**
     * The Home Assistant the panel talks to (sign-in, relative media URLs): the origin of a
     * configured dashboard URL, else the discovered [haUrl]. Null if neither is known.
     */
    val haBase: String?
        get() = (if (isAutoUrl) null else OAuth.origin(urlSetting)) ?: haUrl.takeIf { it.isNotEmpty() }

    // ---- first-boot setup screen + dashboard select (docs/onboarding.md, steps 4-5)

    /** Home Assistant has subscribed to the panel's ESPHome states at least once (it added the panel). */
    var haSeen: Boolean
        get() = sp.getBoolean(KEY_HA_SEEN, false)
        set(v) = sp.edit().putBoolean(KEY_HA_SEEN, v).apply()

    /** The panel has been online over Wi-Fi at least once (the boot screen's "Connecting to Wi-Fi…" hint). */
    var wifiSeen: Boolean
        get() = sp.getBoolean(KEY_WIFI_SEEN, false)
        set(v) = sp.edit().putBoolean(KEY_WIFI_SEEN, v).apply()

    /** "Skip" on the setup screen's Add-to-Home-Assistant step. */
    var haSetupSkipped: Boolean
        get() = sp.getBoolean("setup_ha_skipped", false)
        set(v) = sp.edit().putBoolean("setup_ha_skipped", v).apply()

    /** Lovelace dashboards the "Dashboard" select was last built from (`[{url_path,title}]`). */
    var dashboardsJson: String
        get() = sp.getString("dashboards", "") ?: ""
        set(v) = sp.edit().putString("dashboards", v).apply()

    // ---- Home Assistant sign-in (QR + OAuth, see auth/HaAuth.kt). Never log these values.

    var auth: StoredAuth?
        get() {
            val rt = authSp.getString("refresh_token", null) ?: return null
            val base = authSp.getString("ha_base", null) ?: return null
            val cid = authSp.getString("client_id", null) ?: return null
            return StoredAuth(base, cid, rt)
        }
        set(v) {
            val e = authSp.edit().clear()
            if (v != null) e.putString("refresh_token", v.refreshToken).putString("ha_base", v.haBase).putString("client_id", v.clientId)
            e.commit()
        }

    val hasAuth: Boolean get() = authSp.contains("refresh_token")

    /**
     * Page zoom as a CSS device-pixel ratio override (Gecko pref `layout.css.devPixelsPerPx`).
     * 0 = automatic (use the display density, 1.5 on the S1 Plus). Never change `wm density`
     * instead: on this panel it breaks GeckoView's surface sizing until reboot.
     */
    // commit(), not apply(), for the three settings the app restarts for (#185): an async write can be
    // lost with the process, as for urlSetting.
    var zoom: Float
        get() = sp.getFloat(KEY_ZOOM, 0f)
        set(v) { sp.edit().putFloat(KEY_ZOOM, v).commit() }

    /** Extra hosts (comma separated) to treat as secure contexts, besides the dashboard host. */
    var extraSecureHosts: String
        get() = sp.getString(KEY_SECURE_HOSTS, "") ?: ""
        set(v) { sp.edit().putString(KEY_SECURE_HOSTS, v.trim()).commit() }

    var remoteDebugging: Boolean
        get() = sp.getBoolean(KEY_REMOTE_DEBUG, BuildConfig.DEBUG)
        set(v) { sp.edit().putBoolean(KEY_REMOTE_DEBUG, v).commit() }

    // ---- HA page tweaks (applied by the built-in web extension)

    var hideHeader: Boolean
        get() = sp.getBoolean("hide_header", true)
        set(v) = sp.edit().putBoolean("hide_header", v).apply()

    var hideSidebar: Boolean
        get() = sp.getBoolean("hide_sidebar", true)
        set(v) = sp.edit().putBoolean("hide_sidebar", v).apply()

    // ---- screen policy (see dev.glassworks.screen.ScreenPolicy)

    /** "presence" = on while someone is near / recently touched, off after idle; "always" = never off. */
    override var screenMode: String
        get() = sp.getString("screen_mode", "presence") ?: "presence"
        set(v) = sp.edit().putString("screen_mode", v).apply()

    override var idleOffSec: Int
        get() = sp.getInt("idle_off_sec", 120)
        set(v) = sp.edit().putInt("idle_off_sec", v).apply()

    /** Night window "HH:MM-HH:MM" (local time); empty = no night mode. */
    override var nightWindow: String
        get() = sp.getString("night_window", "22:00-07:00") ?: ""
        set(v) = sp.edit().putString("night_window", v.trim()).apply()

    override var nightIdleOffSec: Int
        get() = sp.getInt("night_idle_off_sec", 20)
        set(v) = sp.edit().putInt("night_idle_off_sec", v).apply()

    override var wakeOnApproach: Boolean
        get() = sp.getBoolean("wake_on_approach", true)
        set(v) = sp.edit().putBoolean("wake_on_approach", v).apply()

    /** Presence range in metres for the VI5300 (0 = leave the firmware setting alone). */
    override var presenceRangeM: Float
        get() = sp.getFloat("presence_range_m", 0f)
        set(v) = sp.edit().putFloat("presence_range_m", v).apply()

    override var autoBrightness: Boolean
        get() = sp.getBoolean("auto_brightness", true)
        set(v) = sp.edit().putBoolean("auto_brightness", v).apply()

    override var minBrightness: Float
        get() = sp.getFloat("min_brightness", 0.10f)
        set(v) = sp.edit().putFloat("min_brightness", v).apply()

    override var maxBrightness: Float
        get() = sp.getFloat("max_brightness", 1.0f)
        set(v) = sp.edit().putFloat("max_brightness", v).apply()

    override var nightMaxBrightness: Float
        get() = sp.getFloat("night_max_brightness", 0.2f)
        set(v) = sp.edit().putFloat("night_max_brightness", v).apply()

    /** Fixed brightness (0..1) used while auto brightness is off; -1 = system default. */
    override var manualBrightness: Float
        get() = sp.getFloat("manual_brightness", -1f)
        set(v) = sp.edit().putFloat("manual_brightness", v).apply()

    // ---- ESPHome native API (see docs/esphome.md)

    /**
     * Noise encryption for the API. True once a key is set (by Home Assistant's provisioning or
     * `--es api_key`); false = no key yet: plaintext + zero-PSK Noise, key provisionable.
     */
    var apiEncryption: Boolean
        get() = sp.getBoolean("api_encryption", false)
        set(v) = sp.edit().putBoolean("api_encryption", v).apply()

    /** 32-byte Noise PSK, base64. */
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    /** The active Noise key: [apiKey] while [apiEncryption] is on, else null (unprovisioned). */
    fun effectiveApiKey(): ByteArray? = if (apiEncryption) decodeApiKey(apiKey) else null

    /** Persists the key HA provisions over the API (synchronously, like ESPHome's pref sync). */
    val noiseKeyStore: NoiseKeyStore = object : NoiseKeyStore {
        override fun load(): ByteArray? = effectiveApiKey()
        override fun save(key: ByteArray?): Boolean =
            if (key == null) sp.edit().remove("api_key").putBoolean("api_encryption", false).commit()
            else sp.edit().putString("api_key", Base64.encodeToString(key, Base64.NO_WRAP))
                .putBoolean("api_encryption", true).commit()
    }

    /** Stable MAC reported to Home Assistant (its config entry is keyed by it). */
    var deviceMac: String
        get() = sp.getString("device_mac", "") ?: ""
        set(v) = sp.edit().putString("device_mac", v).apply()

    var speakerVolume: Float
        get() = sp.getFloat("speaker_volume", 1.0f)
        set(v) = sp.edit().putFloat("speaker_volume", v).apply()

    var speakerMuted: Boolean
        get() = sp.getBoolean("speaker_muted", false)
        set(v) = sp.edit().putBoolean("speaker_muted", v).apply()

    // ---- Improv Wi-Fi via BLE (see improv/ImprovController)

    /** Start Improv provisioning automatically when there's no network 30 s after boot. */
    var improvAuto: Boolean
        get() = sp.getBoolean("improv_auto", true)
        set(v) = sp.edit().putBoolean("improv_auto", v).apply()

    /** Also accept a proximity approach (VI5300 far -> near) as the Improv authorization. */
    var improvProximityAuth: Boolean
        get() = sp.getBoolean("improv_proximity_auth", false)
        set(v) = sp.edit().putBoolean("improv_proximity_auth", v).apply()

    /** Hosts that Gecko should treat as a secure context, so getUserMedia/AudioWorklet work over http. */
    fun secureHosts(): List<String> {
        val hosts = mutableListOf<String>()
        Uri.parse(url).host?.let { hosts += it }
        extraSecureHosts.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { hosts += it }
        return hosts.distinct()
    }

    companion object {
        const val AUTO = "auto"

        /**
         * The settings file of each pane, by pane id. Every pane's settings stay in `kiosk.xml`, where
         * they were before panes: panels update in place, so no stored value may move.
         */
        val PANE_FILES = mapOf(
            "sendspin" to "kiosk", "assist" to "kiosk", "wall-base" to "kiosk", "radio" to "kiosk",
            "time-zone" to "kiosk", "diagnostics" to "kiosk", "bluetooth-proxy" to "kiosk", "camera" to "kiosk",
        )

        /** Decodes a base64 API key; null unless it is exactly 32 bytes. */
        fun decodeApiKey(b64: String): ByteArray? = try {
            Base64.decode(b64.trim(), Base64.DEFAULT).takeIf { it.size == 32 }
        } catch (e: IllegalArgumentException) {
            null
        }

        const val KEY_URL = "url"
        const val KEY_HA_SEEN = "ha_seen"
        const val KEY_WIFI_SEEN = "wifi_seen"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_SECURE_HOSTS = "secure_hosts"
        private const val KEY_REMOTE_DEBUG = "remote_debug"
    }
}
