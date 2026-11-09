package io.github.aguilaair.s1kiosk

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.security.SecureRandom

/**
 * Persisted kiosk settings (plain SharedPreferences, private to the app). The only secret is the
 * ESPHome API encryption key, which Home Assistant needs anyway.
 */
class KioskPrefs(context: Context) {
    private val sp = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

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

    /** Fixed brightness (0..1) used while auto brightness is off; -1 = system default. */
    var manualBrightness: Float
        get() = sp.getFloat("manual_brightness", -1f)
        set(v) = sp.edit().putFloat("manual_brightness", v).apply()

    // ---- ESPHome native API (see docs/esphome.md)

    /** Noise encryption for the API; off by default (plaintext). */
    var apiEncryption: Boolean
        get() = sp.getBoolean("api_encryption", false)
        set(v) = sp.edit().putBoolean("api_encryption", v).apply()

    /** 32-byte Noise PSK, base64. Generated on first use; see [ensureApiKey]. */
    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    /** Returns the API key, generating (and logging, once) a random one if none is set. */
    fun ensureApiKey(): String {
        apiKey.takeIf { decodeApiKey(it) != null }?.let { return it }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val b64 = Base64.encodeToString(key, Base64.NO_WRAP)
        apiKey = b64
        Log.i("S1ESPHome", "Generated ESPHome API encryption key: $b64")
        return b64
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
