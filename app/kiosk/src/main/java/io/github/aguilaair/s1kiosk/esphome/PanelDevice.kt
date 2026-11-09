package io.github.aguilaair.s1kiosk.esphome

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.github.aguilaair.s1kiosk.BuildConfig
import io.github.aguilaair.s1kiosk.KioskPrefs
import io.github.aguilaair.s1kiosk.MainActivity
import io.github.aguilaair.s1kiosk.TimerBoard
import io.github.aguilaair.s1kiosk.RadioController
import io.github.aguilaair.s1kiosk.ScreenController
import io.github.aguilaair.s1kiosk.base.BaseEntities
import io.github.aguilaair.s1kiosk.base.BaseLayout
import io.github.aguilaair.s1kiosk.base.WallBase
import io.github.aguilaair.s1kiosk.voice.TfliteWakeWord
import io.github.aguilaair.s1kiosk.voice.TtsPlayer
import io.github.aguilaair.s1kiosk.voice.ExoAudioPlayer
import io.github.aguilaair.s1kiosk.voice.VoiceConfig
import io.github.aguilaair.s1kiosk.voice.VoiceEngine
import io.github.aguilaair.s1kiosk.voice.VoiceEvent
import io.github.aguilaair.s1kiosk.voice.chime
import io.github.aguilaair.s1kiosk.voice.wakeChime
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The panel as one ESPHome device for Home Assistant: native API server (port 6053), mDNS,
 * entities bound to the kiosk settings / [ScreenController], a media player, and the voice
 * assistant (on-device wake word + [VoiceAssistantController]). Owned by KioskService.
 *
 * Entity values are read from [KioskPrefs] / [ScreenController] (thread-safe); anything touching
 * the screen or the activity is posted to the main thread. Kiosk actions (URL, reload, zoom,
 * header, restart) go to [MainActivity] as intents, exactly like the `adb am start` extras.
 */
class PanelDevice(context: Context, private val onVoiceEvent: (VoiceEvent) -> Unit) {

    companion object {
        const val NAME = "s1-panel"
        const val FRIENDLY_NAME = "S1 Panel"
        /** Reported ESPHome version (recent, so HA doesn't treat the device as outdated). */
        const val ESPHOME_VERSION = "2026.10.2"
        /** Same flags as an ESPHome voice_assistant with a media player (Voice PE): no SPEAKER. */
        const val VOICE_FEATURES = VoiceFeature.VOICE_ASSISTANT or VoiceFeature.API_AUDIO or
            VoiceFeature.TIMERS or VoiceFeature.ANNOUNCE or VoiceFeature.START_CONVERSATION
        private val MAC_RE = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    }

    private val ctx = context.applicationContext
    private val prefs = KioskPrefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val exec: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "esphome-main").apply { isDaemon = true } }
    private val mdns = EspHomeMdns(ctx)
    private val voiceEnabled = prefs.voiceEnabled

    private var server: ApiServer? = null
    private var voice: VoiceEngine? = null
    private var controller: VoiceAssistantController? = null
    private lateinit var identity: DeviceIdentity
    private lateinit var entities: List<ApiEntity>
    private lateinit var coreEntities: List<ApiEntity>
    private var wallBase: WallBase? = null
    private val voicePlayer = ExoAudioPlayer(context, "voice", AudioAttributes.USAGE_ASSISTANT)
    private val mediaPlayer = ExoAudioPlayer(context, "media", AudioAttributes.USAGE_MEDIA)
    private var chimePlayer: TtsPlayer? = null
    @Volatile private var announcing = false
    @Volatile private var mediaPausedForVoice = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        exec.execute { onPrefChanged(key) }
    }
    private val screenListener: () -> Unit = { exec.execute { server?.publishStates() } }

    fun start() {
        exec.execute { startInternal() }
    }

    fun stop() {
        prefs.unregisterListener(prefListener)
        ScreenController.removeListener(screenListener)
        wallBase?.stop()
        wallBase = null
        exec.shutdownNow()
        mdns.stop()
        server?.stop()
        server = null
        voice?.stop()
        voice = null
        voicePlayer.release()
        mediaPlayer.release()
        chimePlayer?.release()
        releaseLocks()
    }

    // ------------------------------------------------------------------ setup

    private fun startInternal() {
        prefs.ensureApiKey() // generated (and logged) on first run so it can be copied into HA later
        identity = DeviceIdentity(
            name = NAME,
            friendlyName = FRIENDLY_NAME,
            macAddress = stableMac(),
            esphomeVersion = ESPHOME_VERSION,
            compilationTime = "S1 Kiosk ${BuildConfig.VERSION_NAME}",
            manufacturer = "Aqara",
            model = "Panel Hub S1 Plus (S1 Kiosk)",
            voiceFeatures = if (voiceEnabled) VOICE_FEATURES else 0,
        )
        ApiLog.i("ESPHome device '${identity.name}' mac ${identity.macAddress}, voice=${voiceEnabled}")
        coreEntities = buildEntities()
        val base = WallBase(ctx, BaseLayout.decode(prefs.baseLayout) ?: BaseLayout.NONE, baseListener)
        wallBase = base
        entities = coreEntities + baseEntities(base, base.tracker.applied())
        acquireLocks()
        applyVolume()
        mediaPlayer.onStateChanged = { exec.execute { server?.publishStates() } }
        voicePlayer.onStateChanged = mediaPlayer.onStateChanged

        if (voiceEnabled) startVoice()
        startServer()
        prefs.registerListener(prefListener)
        ScreenController.addListener(screenListener)
        exec.scheduleWithFixedDelay({ controller?.tick() }, 1, 1, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ server?.publishStates() }, 5, 5, TimeUnit.SECONDS)
        base.start()
    }

    // ------------------------------------------------------------------ wall base

    private fun baseEntities(base: WallBase, layout: BaseLayout): List<ApiEntity> =
        BaseEntities.build(layout, base.tracker, base::setRelay)

    /** Called on the "s1-base" thread; everything is handed to esphome-main. */
    private val baseListener = object : WallBase.Listener {
        override fun onStates() { exec.execute { server?.publishStates() } }

        override fun onLayout(layout: BaseLayout) {
            exec.execute {
                val base = wallBase ?: return@execute
                entities = coreEntities + baseEntities(base, layout)
                prefs.baseLayout = layout.encode()
                server?.setEntities(entities)
            }
        }

        override fun onButton(channel: Int, type: String) {
            exec.execute {
                val e = entities.firstOrNull { it.objectId == BaseEntities.buttonId(channel) } as? EventEntity
                if (e == null) ApiLog.w("base button $channel ($type): no event entity in the current set")
                else server?.sendEvent(e, type)
            }
        }
    }

    private fun startVoice() {
        val cfg = VoiceConfig(threshold = prefs.wakeThreshold, micGain = prefs.micGain)
        val engine = VoiceEngine(ctx, cfg, prefs.wakeModel) { model -> prefs.wakeModel = model }
        engine.onError = { msg -> dispatchVoice(VoiceEvent.Error(msg)) }
        val c = VoiceAssistantController(
            send = { type, payload -> server?.sendVoice(type, payload) ?: false },
            player = voicePlayer,
            wakeWords = engine,
            emit = ::dispatchVoice,
            resolveUrl = ::resolveUrl,
            onTimerFinished = { name ->
                ApiLog.i("Timer '$name' finished")
                TimerBoard.finished(name)
                ui { ScreenController.wake("timer"); ScreenController.onStateChanged?.invoke() }
                val p = chimePlayer ?: TtsPlayer().also { chimePlayer = it }
                // Three chimes ~3 s apart so it's noticeable across the room.
                p.chime()
                exec.schedule({ p.chime() }, 3, TimeUnit.SECONDS)
                exec.schedule({ p.chime() }, 6, TimeUnit.SECONDS)
            },
            onTimersChanged = { list ->
                TimerBoard.update(list.map { TimerBoard.Entry(it.id, it.name, it.totalSeconds, it.secondsLeft, it.active) })
                ui { ScreenController.onStateChanged?.invoke() }
            },
            maxStreamMs = cfg.maxStreamMs,
        )
        engine.controller = c
        controller = c
        voice = engine
        engine.start(muted = prefs.micMuted)
    }

    private fun startServer() {
        val psk = if (prefs.apiEncryption) KioskPrefs.decodeApiKey(prefs.ensureApiKey()) else null
        val s = ApiServer(ApiServer.DEFAULT_PORT, { identity }, entities, psk)
        s.voice = controller
        s.onCommand = { exec.execute { s.publishStates() } }
        try {
            s.start()
        } catch (e: IOException) {
            ApiLog.w("Cannot listen on ${ApiServer.DEFAULT_PORT}: ${e.message}; retrying in 10 s")
            exec.schedule({ if (server == null) startServer() }, 10, TimeUnit.SECONDS)
            return
        }
        server = s
        mdns.start(identity, s.localPort, encrypted = psk != null)
    }

    private fun restartServer() {
        ApiLog.i("API encryption settings changed; restarting the API server")
        mdns.stop()
        server?.stop()
        server = null
        startServer()
    }

    /** esphome-main thread. */
    private fun onPrefChanged(key: String?) {
        val v = voice
        when (key) {
            "api_encryption" -> restartServer()
            "api_key" -> if (prefs.apiEncryption) restartServer()
            "mic_muted" -> v?.setMuted(prefs.micMuted)
            "mic_gain" -> v?.setGain(prefs.micGain)
            "wake_threshold" -> v?.threshold = prefs.wakeThreshold
            "wake_model" -> v?.setActive(listOfNotNull(prefs.wakeModel.takeIf { it.isNotEmpty() }?.let(TfliteWakeWord::modelName)))
            "speaker_volume", "speaker_muted" -> applyVolume()
            "radio_mode" -> RadioController.apply(prefs.radioMode)
        }
        server?.publishStates()
    }

    private fun dispatchVoice(e: VoiceEvent) {
        // Pause music while the assistant listens/talks, resume afterwards.
        if (e is VoiceEvent.WakeDetected && prefs.wakeSound) {
            (chimePlayer ?: TtsPlayer().also { chimePlayer = it }).wakeChime()
        }
        when (e) {
            is VoiceEvent.WakeDetected, is VoiceEvent.Listening, is VoiceEvent.Speaking ->
                if (mediaPlayer.isPlaying && !announcing) { mediaPausedForVoice = true; mediaPlayer.pause() }
            is VoiceEvent.Idle, is VoiceEvent.Error ->
                if (mediaPausedForVoice) { mediaPausedForVoice = false; mediaPlayer.resume() }
            else -> Unit
        }
        try { onVoiceEvent(e) } catch (t: Throwable) { ApiLog.w("voice listener failed", t) }
    }

    // ------------------------------------------------------------------ entities

    private fun ui(block: () -> Unit) { main.post(block) }

    /** Sends extras to MainActivity (same as `adb shell am start ... --e* key value`). */
    private fun toActivity(build: Intent.() -> Unit) {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(build)
        ui {
            val sink = MainActivity.commandSink
            if (sink != null) sink(i) else try { ctx.startActivity(i) } catch (e: Exception) {
                ApiLog.w("Cannot start MainActivity", e)
            }
        }
    }

    private fun currentBrightness(): Float =
        ScreenController.brightness.takeIf { it >= 0f } ?: prefs.manualBrightness.takeIf { it > 0f } ?: 1f

    private fun buildEntities(): List<ApiEntity> = listOf(
        // --- screen & sensors
        BrightnessLightEntity(
            "screen", "Screen", "mdi:tablet-dashboard",
            get = { ScreenController.screenOn to currentBrightness() },
            set = { on, b ->
                ui {
                    if (b != null) ScreenController.setManualBrightness(b)
                    when (on) {
                        true -> ScreenController.wake("home assistant")
                        false -> ScreenController.sleep("home assistant")
                        null -> Unit
                    }
                }
            },
        ),
        SwitchEntity(
            "auto_brightness", "Auto brightness", "mdi:brightness-auto", EntityCategory.CONFIG,
            get = { prefs.autoBrightness },
            set = { prefs.autoBrightness = it; ui { ScreenController.refreshBrightness() } },
        ),
        BinarySensorEntity("presence", "Presence", deviceClass = "occupancy", get = { ScreenController.near }),
        SensorEntity(
            "illuminance", "Illuminance", unit = "lx", accuracy = 0, deviceClass = "illuminance",
            get = { ScreenController.lux },
        ),
        SelectEntity(
            "screen_mode", "Screen mode", "mdi:monitor-eye", options = listOf("presence", "always"),
            get = { prefs.screenMode }, set = { prefs.screenMode = it },
        ),
        NumberEntity(
            "idle_timeout", "Idle timeout", "mdi:timer-outline", min = 0f, max = 3600f, step = 5f,
            unit = "s", mode = 1, deviceClass = "duration",
            get = { prefs.idleOffSec.toFloat() }, set = { prefs.idleOffSec = it.toInt() },
        ),
        NumberEntity(
            "night_idle_timeout", "Night idle timeout", "mdi:timer-sand", min = 0f, max = 3600f, step = 5f,
            unit = "s", mode = 1, deviceClass = "duration",
            get = { prefs.nightIdleOffSec.toFloat() }, set = { prefs.nightIdleOffSec = it.toInt() },
        ),
        TextEntity(
            "night_window", "Night window", "mdi:weather-night", maxLength = 11,
            pattern = "^$|^[0-9]{1,2}:[0-9]{2}-[0-9]{1,2}:[0-9]{2}$",
            get = { prefs.nightWindow }, set = { prefs.nightWindow = it },
        ),
        NumberEntity(
            "presence_range", "Presence range", "mdi:signal-distance-variant", min = 0f, max = 2.5f, step = 0.1f,
            unit = "m", mode = 2, deviceClass = "distance",
            get = { prefs.presenceRangeM }, set = { prefs.presenceRangeM = it; ui { ScreenController.applyPresenceRange() } },
        ),
        // --- kiosk
        TextEntity(
            "dashboard_url", "Dashboard URL", "mdi:web", minLength = 1, maxLength = 255,
            get = { prefs.url }, set = { url -> toActivity { putExtra("url", url) } },
        ),
        NumberEntity(
            "zoom", "Zoom", "mdi:magnify-plus-outline", min = 0f, max = 3f, step = 0.05f, mode = 1,
            get = { prefs.zoom },
            set = { z -> if (kotlin.math.abs(z - prefs.zoom) > 0.001f) toActivity { putExtra("zoom", z) } },
        ),
        ButtonEntity("reload_dashboard", "Reload dashboard", "mdi:refresh", press = { toActivity { putExtra("reload", true) } }),
        ButtonEntity(
            "restart_app", "Restart app", "mdi:restart", EntityCategory.CONFIG, deviceClass = "restart",
            press = { toActivity { putExtra("restart", true) } },
        ),
        SwitchEntity(
            "hide_ha_header", "Hide HA header", "mdi:page-layout-header", EntityCategory.CONFIG,
            get = { prefs.hideHeader },
            set = { v -> toActivity { putExtra("hide_header", v); putExtra("hide_sidebar", v) } },
        ),
        // --- audio / voice
        MediaPlayerEntity(
            "speaker", "Speaker", "mdi:speaker",
            // mp3 for everything: HA's ffmpeg proxy converts media/announcements, and the assist
            // satellite asks the TTS engine for this format (assist_satellite.py _update_tts_format).
            formats = listOf("mp3" to 0, "mp3" to 1),
            featureFlags = MediaPlayerEntity.FEATURE_PAUSE or MediaPlayerEntity.FEATURE_VOLUME_SET or
                MediaPlayerEntity.FEATURE_VOLUME_MUTE or MediaPlayerEntity.FEATURE_PLAY_MEDIA or
                MediaPlayerEntity.FEATURE_VOLUME_STEP or MediaPlayerEntity.FEATURE_STOP or
                MediaPlayerEntity.FEATURE_PLAY or MediaPlayerEntity.FEATURE_BROWSE_MEDIA or
                MediaPlayerEntity.FEATURE_MEDIA_ANNOUNCE,
            get = {
                MediaPlayerStateData(
                    state = when {
                        mediaPlayer.isPlaying || (announcing && voicePlayer.isPlaying) -> MediaState.PLAYING
                        mediaPlayer.isPaused -> MediaState.PAUSED
                        else -> MediaState.IDLE
                    },
                    volume = prefs.speakerVolume,
                    muted = prefs.speakerMuted,
                )
            },
            handle = ::onMediaCommand,
        ),
        SwitchEntity(
            "mute_microphone", "Mute microphone", "mdi:microphone-off", EntityCategory.CONFIG,
            get = { prefs.micMuted }, set = { prefs.micMuted = it },
        ),
        SwitchEntity(
            "wake_sound", "Wake sound", "mdi:bell-ring-outline", EntityCategory.CONFIG,
            get = { prefs.wakeSound }, set = { prefs.wakeSound = it },
        ),
        SwitchEntity(
            "voice_overlay", "Voice overlay", "mdi:message-badge-outline", EntityCategory.CONFIG,
            get = { prefs.voiceOverlay }, set = { prefs.voiceOverlay = it },
        ),
        NumberEntity(
            "mic_gain", "Mic gain", "mdi:microphone-plus", min = 0.5f, max = 4f, step = 0.1f, unit = "x",
            get = { prefs.micGain }, set = { prefs.micGain = it },
        ),
        NumberEntity(
            "wake_word_threshold", "Wake word threshold", "mdi:account-voice", min = 0.1f, max = 0.95f, step = 0.05f,
            get = { prefs.wakeThreshold }, set = { prefs.wakeThreshold = it },
        ),
        // --- radio
        SelectEntity(
            "radio_mode", "Radio mode", "mdi:zigbee", options = RadioController.MODES,
            get = { prefs.radioMode }, set = { prefs.radioMode = it },
        ),
        // --- diagnostics
        TextSensorEntity("ip_address", "IP address", "mdi:ip-network", EntityCategory.DIAGNOSTIC, get = ::ipAddress),
        TextSensorEntity(
            "app_version", "App version", "mdi:new-box", EntityCategory.DIAGNOSTIC,
            get = { BuildConfig.VERSION_NAME },
        ),
    )

    private fun onMediaCommand(c: MediaPlayerCommandData) {
        c.volume?.let { prefs.speakerVolume = it.coerceIn(0f, 1f) }
        c.mediaUrl?.takeIf { it.isNotEmpty() }?.let { raw ->
            val url = resolveUrl(raw)
            when {
                c.announcement -> announce(url)
                c.command == MediaCommand.ENQUEUE -> mediaPlayer.enqueue(url)
                else -> mediaPlayer.play(listOf(url)) { exec.execute { server?.publishStates() } }
            }
            return
        }
        when (c.command) {
            MediaCommand.PLAY -> mediaPlayer.resume()
            MediaCommand.PAUSE -> mediaPlayer.pause()
            MediaCommand.STOP, MediaCommand.CLEAR_PLAYLIST -> mediaPlayer.stop()
            MediaCommand.TOGGLE -> if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.resume()
            MediaCommand.MUTE -> prefs.speakerMuted = true
            MediaCommand.UNMUTE -> prefs.speakerMuted = false
            MediaCommand.VOLUME_UP -> prefs.speakerVolume = (prefs.speakerVolume + 0.1f).coerceAtMost(1f)
            MediaCommand.VOLUME_DOWN -> prefs.speakerVolume = (prefs.speakerVolume - 0.1f).coerceAtLeast(0f)
        }
    }

    /** media_player announce: pause music, play on the voice channel, resume. */
    private fun announce(url: String) {
        val resume = mediaPlayer.isPlaying
        if (resume) mediaPlayer.pause()
        announcing = true
        voicePlayer.play(listOf(url)) {
            announcing = false
            if (resume) mediaPlayer.resume()
            exec.execute { server?.publishStates() }
        }
    }

    private fun applyVolume() {
        val v = if (prefs.speakerMuted) 0f else prefs.speakerVolume
        voicePlayer.setVolume(v)
        mediaPlayer.setVolume(v)
    }

    /**
     * Home Assistant normally sends absolute URLs (its internal URL); relative ones (`/api/...`) are
     * resolved against the dashboard URL, which points at Home Assistant.
     */
    private fun resolveUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return try { URI(prefs.url).resolve(url).toString() } catch (e: Exception) { url }
    }

    // ------------------------------------------------------------------ helpers

    /** The Wi-Fi MAC if readable, else a random locally administered one; persisted either way. */
    private fun stableMac(): String {
        prefs.deviceMac.takeIf { MAC_RE.matches(it) }?.let { return it }
        val hw = try { NetworkInterface.getByName("wlan0")?.hardwareAddress } catch (_: Exception) { null }
        val usable = hw?.takeIf { a ->
            a.size == 6 && a.any { it != 0.toByte() } && !(a[0] == 2.toByte() && a.drop(1).all { it == 0.toByte() })
        }
        val bytes = usable ?: ByteArray(6).also {
            SecureRandom().nextBytes(it)
            it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte() // unicast, locally administered
        }
        val mac = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        prefs.deviceMac = mac
        ApiLog.i("Device MAC $mac (${if (usable != null) "wlan0" else "random"})")
        return mac
    }

    private fun ipAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name == "wlan0" }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }?.hostAddress
    } catch (_: Exception) {
        null
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "s1kiosk:esphome").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "s1kiosk:esphome").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            ApiLog.w("Could not acquire wake/wifi lock", e)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }
}
