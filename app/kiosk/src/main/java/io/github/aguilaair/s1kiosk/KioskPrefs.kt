package io.github.aguilaair.s1kiosk

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import io.github.aguilaair.s1kiosk.auth.OAuth
import io.github.aguilaair.s1kiosk.auth.StoredAuth
import io.github.aguilaair.s1kiosk.esphome.NoiseKeyStore

/**
 * Persisted kiosk settings (SharedPreferences, private to the app). Secrets: the ESPHome API
 * encryption key (`kiosk.xml`, which Home Assistant provisions) and the HA refresh token
 * (`auth.xml`, see [auth]). Neither is ever logged.
 */
class KioskPrefs(context: Context) {
    private val sp = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
    private val authSp = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

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
        set(v) = sp.edit().putString(KEY_URL, v.trim().ifEmpty { AUTO }).apply()

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

    /** Fixed brightness (0..1) used while auto brightness is off; -1 = system default. */
    var manualBrightness: Float
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

    /** Last applied wall base layout (BaseLayout.encode), so HA sees the same entities after a restart. */
    var baseLayout: String
        get() = sp.getString("base_layout", "") ?: ""
        set(v) = sp.edit().putString("base_layout", v).apply()

    var speakerVolume: Float
        get() = sp.getFloat("speaker_volume", 1.0f)
        set(v) = sp.edit().putFloat("speaker_volume", v).apply()

    var speakerMuted: Boolean
        get() = sp.getBoolean("speaker_muted", false)
        set(v) = sp.edit().putBoolean("speaker_muted", v).apply()

    /** Zigbee/Thread radio mode (see RadioController.MODES). */
    var radioMode: String
        get() = sp.getString("radio_mode", RadioController.MODES[0]) ?: RadioController.MODES[0]
        set(v) = sp.edit().putString("radio_mode", v).apply()

    // ---- voice assistant (ESPHome API + on-device openWakeWord)

    var voiceEnabled: Boolean
        get() = sp.getBoolean("voice_enabled", true)
        set(v) = sp.edit().putBoolean("voice_enabled", v).apply()

    /** Wake model file in the models dir; "" = no wake word (set from HA's wake word select). */
    var wakeModel: String
        get() = sp.getString("wake_model", "hey_jarvis_v0.1.tflite") ?: ""
        set(v) = sp.edit().putString("wake_model", v.trim()).apply()

    var wakeThreshold: Float
        get() = sp.getFloat("wake_threshold", 0.5f)
        set(v) = sp.edit().putFloat("wake_threshold", v).apply()

    /** Play a short chime when the wake word is detected. */
    var wakeSound: Boolean
        get() = sp.getBoolean("wake_sound", true)
        set(v) = sp.edit().putBoolean("wake_sound", v).apply()

    /** Show the "Listening… / Thinking…" overlay on screen during voice runs. */
    var voiceOverlay: Boolean
        get() = sp.getBoolean("voice_overlay", true)
        set(v) = sp.edit().putBoolean("voice_overlay", v).apply()

    var micGain: Float
        get() = sp.getFloat("mic_gain", 1.0f)
        set(v) = sp.edit().putFloat("mic_gain", v).apply()

    /** Mute: the microphone is released entirely (no wake word, no streaming). */
    var micMuted: Boolean
        get() = sp.getBoolean("mic_muted", false)
        set(v) = sp.edit().putBoolean("mic_muted", v).apply()

    /** Hosts that Gecko should treat as a secure context, so getUserMedia/AudioWorklet work over http. */
    fun secureHosts(): List<String> {
        val hosts = mutableListOf<String>()
        Uri.parse(url).host?.let { hosts += it }
        extraSecureHosts.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { hosts += it }
        return hosts.distinct()
    }

    companion object {
        const val AUTO = "auto"

        /** Decodes a base64 API key; null unless it is exactly 32 bytes. */
        fun decodeApiKey(b64: String): ByteArray? = try {
            Base64.decode(b64.trim(), Base64.DEFAULT).takeIf { it.size == 32 }
        } catch (e: IllegalArgumentException) {
            null
        }

        private const val KEY_URL = "url"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_SECURE_HOSTS = "secure_hosts"
        private const val KEY_REMOTE_DEBUG = "remote_debug"
    }
}
