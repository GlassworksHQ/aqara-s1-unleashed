package sh.edm.s1kiosk

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import sh.edm.s1kiosk.auth.OAuth
import sh.edm.s1kiosk.auth.StoredAuth
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.base.UartBaseLink
import sh.edm.s1kiosk.esphome.NoiseKeyStore

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

    /**
     * Zigbee/Thread radio mode, a [sh.edm.s1kiosk.radio.RadioMode] id (older builds stored the label;
     * RadioMode.parse accepts both). Written only by RadioController.
     */
    var radioMode: String?
        get() = sp.getString("radio_mode", null)
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

    // ---- Music Assistant player (Sendspin, see sendspin/ and docs/sendspin.md). Its secrets
    // (identity, pairing PSK, pairing records) live in `sendspin.xml` (PrefsSsStore).

    /** Run the native Sendspin player (ESPHome switch "Music Assistant player (Sendspin)"). */
    /**
     * Stop Aqara's cloud daemon (ha_master). With the UART wall-base backend it is never needed;
     * with the LocalService backend it is kept while a base is attached. See AqaraCloud.
     */
    var blockAqaraCloud: Boolean
        get() = sp.getBoolean(KEY_BLOCK_AQARA_CLOUD, true)
        set(v) = sp.edit().putBoolean(KEY_BLOCK_AQARA_CLOUD, v).apply()

    /** Wall-base backend: `auto` (UART unless cloud blocking is off), `uart`, `localservice`. See BaseBackend. */
    var baseBackend: String
        get() = sp.getString(KEY_BASE_BACKEND, BaseBackend.AUTO) ?: BaseBackend.AUTO
        set(v) = sp.edit().putString(KEY_BASE_BACKEND, v.trim().lowercase().takeIf { it in BaseBackend.PREF_VALUES } ?: BaseBackend.AUTO).apply()

    /** UART backend port: `/dev/ttyS3` (default, "") or `tcp:host:port` for tools/bracket/base_emulator.py. */
    var baseTransport: String
        get() = sp.getString(KEY_BASE_TRANSPORT, "")?.trim()?.takeIf { it.isNotEmpty() } ?: UartBaseLink.SERIAL_PATH
        set(v) = sp.edit().putString(KEY_BASE_TRANSPORT, v.trim()).apply()

    var sendspinEnabled: Boolean
        get() = sp.getBoolean(KEY_SENDSPIN_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_SENDSPIN_ENABLED, v).apply()

    /** `auto` (mDNS `_sendspin-server._tcp`, else `ws://<HA host>:8927/sendspin`) or a host[:port][/path] / ws:// URL. */
    var sendspinServer: String
        get() = sp.getString(KEY_SENDSPIN_SERVER, AUTO)?.takeIf { it.isNotBlank() } ?: AUTO
        set(v) = sp.edit().putString(KEY_SENDSPIN_SERVER, v.trim().ifEmpty { AUTO }).apply()

    /**
     * Sendspin "unpaired access" (guest mode): a server may play without pairing. Off by default:
     * Home Assistant hands Music Assistant the pairing token over the ESPHome API instead.
     */
    var sendspinUnpairedAccess: Boolean
        get() = sp.getBoolean(KEY_SENDSPIN_UNPAIRED, false)
        set(v) = sp.edit().putBoolean(KEY_SENDSPIN_UNPAIRED, v).apply()

    /** Offer FLAC (decoded with MediaCodec) before PCM to the Sendspin server. PCM only when off. */
    var sendspinFlac: Boolean
        get() = sp.getBoolean(KEY_SENDSPIN_FLAC, true)
        set(v) = sp.edit().putBoolean(KEY_SENDSPIN_FLAC, v).apply()

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

        /** Decodes a base64 API key; null unless it is exactly 32 bytes. */
        fun decodeApiKey(b64: String): ByteArray? = try {
            Base64.decode(b64.trim(), Base64.DEFAULT).takeIf { it.size == 32 }
        } catch (e: IllegalArgumentException) {
            null
        }

        const val KEY_URL = "url"
        const val KEY_HA_SEEN = "ha_seen"
        const val KEY_SENDSPIN_ENABLED = "sendspin_enabled"
        const val KEY_BLOCK_AQARA_CLOUD = "block_aqara_cloud"
        const val KEY_BASE_BACKEND = "base_backend"
        const val KEY_BASE_TRANSPORT = "base_transport"
        const val KEY_SENDSPIN_SERVER = "sendspin_server"
        const val KEY_SENDSPIN_UNPAIRED = "sendspin_unpaired_access"
        const val KEY_SENDSPIN_FLAC = "sendspin_flac"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_SECURE_HOSTS = "secure_hosts"
        private const val KEY_REMOTE_DEBUG = "remote_debug"
    }
}
