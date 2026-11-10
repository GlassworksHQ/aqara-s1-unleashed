package sh.edm.s1kiosk.esphome

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import sh.edm.s1kiosk.BuildConfig
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.base.BaseKind
import sh.edm.s1kiosk.AqaraCloud
import sh.edm.s1kiosk.MainActivity
import sh.edm.s1kiosk.TimerBoard
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.radio.RadioController
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.ScreenController
import sh.edm.s1kiosk.base.BaseEntities
import sh.edm.s1kiosk.base.BaseLayout
import sh.edm.s1kiosk.base.UartBaseLink
import sh.edm.s1kiosk.base.WallBase
import sh.edm.s1kiosk.dashboard.Dashboard
import sh.edm.s1kiosk.dashboard.DashboardOptions
import sh.edm.s1kiosk.dashboard.Dashboards
import sh.edm.s1kiosk.dashboard.StableChange
import sh.edm.s1kiosk.discovery.HaUrls
import sh.edm.s1kiosk.sendspin.SendspinPlayer
import sh.edm.s1kiosk.setup.SetupSignals
import sh.edm.s1kiosk.update.UpdateManager
import sh.edm.s1kiosk.voice.TfliteWakeWord
import sh.edm.s1kiosk.voice.TtsPlayer
import sh.edm.s1kiosk.voice.ExoAudioPlayer
import sh.edm.s1kiosk.voice.VoiceConfig
import sh.edm.s1kiosk.voice.VoiceEngine
import sh.edm.s1kiosk.voice.VoiceEvent
import sh.edm.s1kiosk.voice.VoiceLevels
import sh.edm.s1kiosk.voice.chime
import sh.edm.s1kiosk.voice.wakeChime
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
class PanelDevice(
    context: Context,
    private val onVoiceEvent: (VoiceEvent) -> Unit,
    /** HelloRequest from an API client (Home Assistant discovery, HaDiscovery.onApiHello). */
    private val onApiHello: (String, java.net.InetAddress?) -> Unit = { _, _ -> },
) {

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
    private val noiseKeys = NoiseKeys(prefs.noiseKeyStore)
    private var voice: VoiceEngine? = null
    private var controller: VoiceAssistantController? = null
    private lateinit var identity: DeviceIdentity
    private lateinit var entities: List<ApiEntity>
    private lateinit var coreEntities: List<ApiEntity>
    private var wallBase: WallBase? = null
    private val voicePlayer = ExoAudioPlayer(context, "voice", AudioAttributes.USAGE_ASSISTANT)
    private val mediaPlayer = ExoAudioPlayer(context, "media", AudioAttributes.USAGE_MEDIA)
    private var chimePlayer: TtsPlayer? = null
    /** Native Music Assistant player (Sendspin, docs/sendspin.md); created in startInternal. */
    @Volatile private var sendspin: SendspinPlayer? = null
    /** Zigbee/Thread radio modes (docs/radio.md); applies the stored mode after every start. */
    val radio = RadioController(
        load = { prefs.radioMode },
        store = { prefs.radioMode = it },
        onChange = { exec.execute { server?.publishStates() } },
    )
    /**
     * App updates from GitHub Releases (docs/releasing.md); the "App update" entity. runCatching:
     * the update thread may still report while [stop] has already shut [exec] down.
     */
    private val updates = UpdateManager(ctx, onChange = { runCatching { exec.execute { server?.publishStates() } } })
    @Volatile private var announcing = false
    @Volatile private var mediaPausedForVoice = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        exec.execute { onPrefChanged(key) }
    }
    private val screenListener: () -> Unit = { exec.execute { server?.publishStates() } }
    private val authListener: (Boolean) -> Unit = { exec.execute { server?.publishStates() } }
    private val dashboardsListener: (List<Dashboard>) -> Unit = { exec.execute { checkDashboards() } }

    // "Dashboard" select (docs/onboarding.md step 5): built from [appliedDashboards]; a changed
    // option list rebuilds the entity set (debounced, like the wall base) so HA re-lists it.
    @Volatile private var appliedDashboards: List<Dashboard> = emptyList()
    private lateinit var dashboardRebuild: StableChange<List<String>>

    fun start() {
        exec.execute { startInternal() }
    }

    fun stop() {
        prefs.unregisterListener(prefListener)
        ScreenController.removeListener(screenListener)
        HaAuth.removeListener(authListener)
        Dashboards.removeListener(dashboardsListener)
        wallBase?.stop()
        wallBase = null
        exec.shutdownNow()
        mdns.stop()
        server?.stop()
        server = null
        voice?.stop()
        voice = null
        sendspin?.stop("shutdown")
        sendspin = null
        radio.stop()
        updates.stop()
        voicePlayer.release()
        mediaPlayer.release()
        chimePlayer?.release()
        releaseLocks()
    }

    // ------------------------------------------------------------------ setup

    private fun startInternal() {
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
        sendspin = SendspinPlayer(ctx, prefs, identity.macAddress) { exec.execute { server?.publishStates() } }
        coreEntities = buildEntities()
        appliedDashboards = DashboardOptions.parse(prefs.dashboardsJson).orEmpty()
        dashboardRebuild = StableChange(dashboardOptions(appliedDashboards))
        val baseConfig = wantedBaseConfig()
        if (baseConfig.backend == BaseBackend.UART) {
            // ha_master grabbed /dev/ttyS3 at boot: stop it *before* the UART link opens the port
            // (the link's port guard also refuses to open it while init reports it running).
            applyAqaraCloud("startup, before opening the wall base port", baseConfig.backend)
        }
        val base = WallBase(ctx, BaseLayout.decode(prefs.baseLayout) ?: BaseLayout.NONE, baseListener, baseConfig)
        wallBase = base
        entities = assemble(base, base.tracker.applied())
        acquireLocks()
        applyVolume()
        mediaPlayer.onStateChanged = { exec.execute { server?.publishStates() } }
        voicePlayer.onStateChanged = {
            VoiceLevels.ttsPlaying = voicePlayer.isPlaying // drives the voice overlay's speaking pulse
            exec.execute { server?.publishStates() }
        }

        if (voiceEnabled) startVoice()
        if (prefs.sendspinEnabled) sendspin?.start()
        radio.start()
        updates.start()
        startServer()
        prefs.registerListener(prefListener)
        ScreenController.addListener(screenListener)
        HaAuth.addListener(authListener)
        Dashboards.addListener(dashboardsListener)
        exec.scheduleWithFixedDelay({ controller?.tick() }, 1, 1, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ checkDashboards() }, 2, 2, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({ server?.publishStates() }, 5, 5, TimeUnit.SECONDS)
        base.start()
        if (baseConfig.backend == BaseBackend.LOCALSERVICE) {
            // Give the wall-base check time to read the dock class (it needs ha_master), then apply.
            exec.schedule({ applyAqaraCloud("startup") }, 20, TimeUnit.SECONDS)
        }
    }

    // ------------------------------------------------------------------ wall base backend

    /** One instance, so WallBase.Config equality works across re-applies. */
    private val ttyGuard: () -> String? = {
        when (val st = AqaraCloud.daemonState()) {
            "running", "restarting" -> "ha_master is $st and owns ${UartBaseLink.SERIAL_PATH}"
            else -> null
        }
    }

    private fun wantedBaseConfig(): WallBase.Config {
        val backend = BaseBackend.resolve(prefs.baseBackend, prefs.blockAqaraCloud)
        return WallBase.Config(backend, prefs.baseTransport, ttyGuard)
    }

    /**
     * esphome-main: apply backend/transport/cloud-blocking changes so that ha_master and the UART
     * link never hold /dev/ttyS3 at the same time: to UART, stop ha_master first, then open; away
     * from UART, close the port first, then let ha_master run.
     */
    private fun reconfigureBase(why: String) {
        val base = wallBase ?: return
        val c = wantedBaseConfig()
        if (c.backend == BaseBackend.UART) {
            applyAqaraCloud(why, c.backend)
            base.reconfigure(c)
        } else {
            base.reconfigure(c)
            applyAqaraCloud(why, c.backend)
        }
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
                entities = assemble(base, layout)
                prefs.baseLayout = layout.encode()
                server?.setEntities(entities)
                applyAqaraCloud("wall base ${layout.kind}")
            }
        }

        override fun onPortBlocked(reason: String) {
            // Something (re)started ha_master while the UART backend should own the port.
            exec.execute { applyAqaraCloud("wall base port blocked: $reason") }
        }

        override fun onButton(channel: Int, type: String) {
            exec.execute {
                val e = entities.firstOrNull { it.objectId == BaseEntities.buttonId(channel) } as? EventEntity
                if (e == null) ApiLog.w("base button $channel ($type): no event entity in the current set")
                else server?.sendEvent(e, type)
            }
        }
    }

    // ------------------------------------------------------------------ dashboard select

    private fun assemble(base: WallBase, layout: BaseLayout): List<ApiEntity> =
        coreEntities + dashboardSelect(dashboardRebuild.applied()) + baseEntities(base, layout)

    private fun currentDashboardOption(list: List<Dashboard>) =
        DashboardOptions.currentOption(prefs.urlSetting, prefs.haBase, list)

    private fun dashboardOptions(list: List<Dashboard>) = DashboardOptions.options(list, currentDashboardOption(list))

    /** Fixed object_id (so the key never changes); only the options change between rebuilds. */
    private fun dashboardSelect(options: List<String>) = SelectEntity(
        "dashboard", "Dashboard", "mdi:view-dashboard", options = options,
        get = { currentDashboardOption(appliedDashboards) },
        set = { option ->
            val url = DashboardOptions.urlFor(option, prefs.urlSetting, prefs.haBase, prefs.haUrl, appliedDashboards)
            if (url != null) {
                ApiLog.i("Dashboard select: '$option'")
                toActivity { putExtra("url", url) } // saves the setting and reloads
            }
        },
    )

    /**
     * esphome-main, every 2 s and on new lists: rebuild the entity set when the option list really
     * changed and has been stable for 10 s (at most once a minute); a list change that keeps the
     * same options only updates the option → url_path mapping.
     */
    @Volatile private var aqaraCloudStatus = "unknown"

    /**
     * esphome-main: stop Aqara's cloud daemon when blocking is on and no wall base needs it, else
     * make sure it runs. Re-applied after every start (init restarts it on boot) and on changes.
     */
    private fun applyAqaraCloud(why: String, backend: BaseBackend = wallBase?.config?.backend ?: wantedBaseConfig().backend) {
        val run = when {
            // The UART link owns /dev/ttyS3: ha_master is never needed (and must not run).
            backend == BaseBackend.UART -> false
            !prefs.blockAqaraCloud -> true
            else -> {
                // LocalService backend: the base link goes through ha_master.
                val layout = wallBase?.tracker?.current() ?: BaseLayout.decode(prefs.baseLayout) ?: BaseLayout.NONE
                layout.kind == BaseKind.SWITCH || layout.kind == BaseKind.UNKNOWN
            }
        }
        val ok = AqaraCloud.setDaemon(run)
        aqaraCloudStatus = when {
            !ok -> "error (${AqaraCloud.daemonState().ifBlank { "unknown" }})"
            !run && !prefs.blockAqaraCloud -> "stopped (UART wall base backend owns ${UartBaseLink.SERIAL_PATH})"
            !run -> "blocked"
            prefs.blockAqaraCloud -> "running (needed by the wall base, LocalService backend)"
            else -> "running"
        }
        ApiLog.i("Aqara cloud ($why): $aqaraCloudStatus")
        server?.publishStates()
    }

    private fun checkDashboards() {
        val base = wallBase ?: return
        val list = Dashboards.latest ?: appliedDashboards
        val wanted = dashboardOptions(list)
        val now = android.os.SystemClock.elapsedRealtime()
        if (wanted == dashboardRebuild.applied()) {
            if (list != appliedDashboards) adoptDashboards(list)
            dashboardRebuild.offer(wanted, now)
            return
        }
        val due = dashboardRebuild.offer(wanted, now) ?: return
        adoptDashboards(list)
        dashboardRebuild.markApplied(due, now)
        ApiLog.i("Dashboard options changed (${due.size}); rebuilding the entity list")
        entities = assemble(base, base.tracker.applied())
        server?.setEntities(entities)
    }

    private fun adoptDashboards(list: List<Dashboard>) {
        appliedDashboards = list
        prefs.dashboardsJson = DashboardOptions.toJson(list)
    }

    private fun startVoice() {
        val cfg = VoiceConfig(threshold = prefs.wakeThreshold, micGain = prefs.micGain)
        val engine = VoiceEngine(ctx, cfg, prefs.wakeModel) { model -> prefs.wakeModel = model }
        engine.onError = { msg -> dispatchVoice(VoiceEvent.Error(msg, local = true)) }
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
        val s = ApiServer(ApiServer.DEFAULT_PORT, { identity }, entities, noiseKeys)
        s.voice = controller
        s.sendspin = object : SendspinTokenSource {
            override fun clientIdIfStarted() = sendspin?.takeIf { it.started }?.clientId ?: ""
            override fun pairingToken(): Pair<Int, String?> {
                val p = sendspin
                return when {
                    p == null || !p.started ->
                        (if (prefs.sendspinEnabled) SendspinApi.STATUS_NOT_READY else SendspinApi.STATUS_DISABLED) to null
                    else -> SendspinApi.STATUS_OK to p.pairingToken()
                }
            }
        }
        s.onCommand = { exec.execute { s.publishStates() } }
        s.onClientHello = { info, addr ->
            if (HaUrls.isHomeAssistantHello(info)) SetupSignals.onHaHello()
            onApiHello(info, addr)
        }
        // HA subscribes only after it has added the panel: remember that (setup screen, step 2).
        s.onStatesSubscribed = { info ->
            if (HaUrls.isHomeAssistantHello(info)) {
                if (!prefs.haSeen) { prefs.haSeen = true; ApiLog.i("Home Assistant has added the panel") }
                SetupSignals.onHaSubscribed()
            }
        }
        // A provisioned (or cleared) key changes the mDNS TXT records; re-register after the
        // unregistration has gone out so the name doesn't collide with our own old record.
        s.onKeyActivated = { exec.execute { readvertise() } }
        try {
            s.start()
        } catch (e: IOException) {
            ApiLog.w("Cannot listen on ${ApiServer.DEFAULT_PORT}: ${e.message}; retrying in 10 s")
            exec.schedule({ if (server == null) startServer() }, 10, TimeUnit.SECONDS)
            return
        }
        server = s
        mdns.start(identity, s.localPort, encrypted = s.encrypted, provisionable = s.provisionable)
    }

    private fun readvertise() {
        val s = server ?: return
        mdns.stop()
        exec.schedule({
            if (server === s) mdns.start(identity, s.localPort, encrypted = s.encrypted, provisionable = s.provisionable)
        }, 1500, TimeUnit.MILLISECONDS)
    }

    /** Key changed in the panel settings (adb extras): apply it unless it is the one HA just provisioned. */
    private fun onApiKeyPrefChanged() {
        val k = prefs.effectiveApiKey()
        val latest = noiseKeys.latest
        val same = if (k == null || latest == null) k == null && latest == null else k.contentEquals(latest)
        if (same) return
        ApiLog.i(if (k == null) "API key removed in the panel settings" else "API key set in the panel settings (fingerprint ${NoiseKeys.fingerprint(k)})")
        server?.replaceKey(k)
    }

    /** esphome-main thread. */
    private fun onPrefChanged(key: String?) {
        val v = voice
        when (key) {
            "api_encryption", "api_key" -> onApiKeyPrefChanged()
            "mic_muted" -> v?.setMuted(prefs.micMuted)
            "mic_gain" -> v?.setGain(prefs.micGain)
            "wake_threshold" -> v?.threshold = prefs.wakeThreshold
            "wake_model" -> v?.setActive(listOfNotNull(prefs.wakeModel.takeIf { it.isNotEmpty() }?.let(TfliteWakeWord::modelName)))
            "speaker_volume", "speaker_muted" -> applyVolume()
            KioskPrefs.KEY_BLOCK_AQARA_CLOUD, KioskPrefs.KEY_BASE_BACKEND, KioskPrefs.KEY_BASE_TRANSPORT ->
                reconfigureBase("setting $key changed")
            KioskPrefs.KEY_SENDSPIN_ENABLED -> if (prefs.sendspinEnabled) sendspin?.start() else sendspin?.stop("user_request")
            // A new client/hello must carry the changed setting: reconnect.
            KioskPrefs.KEY_SENDSPIN_UNPAIRED, KioskPrefs.KEY_SENDSPIN_SERVER -> sendspin?.restartSession()
            KioskPrefs.KEY_SENDSPIN_FLAC -> sendspin?.onFlacSettingChanged()
        }
        server?.publishStates()
    }

    /** The user cancelled the current voice run (tap on the overlay): stop streaming and playback. */
    fun cancelVoice() {
        exec.execute { controller?.cancel() }
    }

    private fun dispatchVoice(e: VoiceEvent) {
        // Pause music while the assistant listens/talks, resume afterwards.
        if (e is VoiceEvent.WakeDetected && prefs.wakeSound) {
            (chimePlayer ?: TtsPlayer().also { chimePlayer = it }).wakeChime()
        }
        when (e) {
            is VoiceEvent.WakeDetected, is VoiceEvent.Listening, is VoiceEvent.Speaking -> {
                if (mediaPlayer.isPlaying && !announcing) { mediaPausedForVoice = true; mediaPlayer.pause() }
                // Sendspin keeps its timeline (the group plays on); the panel just goes quiet locally.
                sendspin?.setDucked(true)
            }
            is VoiceEvent.Idle, is VoiceEvent.Error -> {
                if (mediaPausedForVoice) { mediaPausedForVoice = false; mediaPlayer.resume() }
                if (!announcing) sendspin?.setDucked(false)
            }
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
            get = { prefs.urlSetting }, set = { url -> toActivity { putExtra("url", url) } },
        ),
        NumberEntity(
            "zoom", "Zoom", "mdi:magnify-plus-outline", min = 0f, max = 3f, step = 0.05f, mode = 1,
            get = { prefs.zoom },
            set = { z -> if (kotlin.math.abs(z - prefs.zoom) > 0.001f) toActivity { putExtra("zoom", z) } },
        ),
        ButtonEntity("reload_dashboard", "Reload dashboard", "mdi:refresh", press = { toActivity { putExtra("reload", true) } }),
        ButtonEntity("open_settings", "Open device settings", "mdi:cog", EntityCategory.CONFIG,
            press = { toActivity { putExtra("open_settings", true) } }),
        ButtonEntity(
            "restart_app", "Restart app", "mdi:restart", EntityCategory.CONFIG, deviceClass = "restart",
            press = { toActivity { putExtra("restart", true) } },
        ),
        ButtonEntity(
            "improv_ble", "Start Wi-Fi setup (Bluetooth)", "mdi:bluetooth-settings", EntityCategory.CONFIG,
            press = { ui { sh.edm.s1kiosk.improv.ImprovController.startOnDemand("Home Assistant button") } },
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
        // --- Music Assistant (Sendspin): native, synced player (docs/sendspin.md)
        SwitchEntity(
            "sendspin", "Music Assistant player (Sendspin)", "mdi:speaker-wireless", EntityCategory.CONFIG,
            get = { prefs.sendspinEnabled }, set = { prefs.sendspinEnabled = it },
        ),
        TextSensorEntity(
            "sendspin_status", "Music Assistant player status", "mdi:speaker-wireless", EntityCategory.DIAGNOSTIC,
            get = { sendspin?.statusText() ?: "off" },
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
            "radio_mode", "Radio mode", "mdi:zigbee", options = RadioMode.LABELS,
            get = { radio.mode.label }, set = { radio.request(it) },
        ),
        TextSensorEntity(
            "radio_status", "Radio status", "mdi:radio-tower", EntityCategory.DIAGNOSTIC,
            get = { radio.statusText() },
        ),
        // --- diagnostics
        TextSensorEntity("ip_address", "IP address", "mdi:ip-network", EntityCategory.DIAGNOSTIC, get = ::ipAddress),
        SwitchEntity(
            "block_aqara_cloud", "Block Aqara cloud", "mdi:cloud-off-outline", EntityCategory.CONFIG,
            get = { prefs.blockAqaraCloud }, set = { prefs.blockAqaraCloud = it },
        ),
        TextSensorEntity(
            "aqara_cloud", "Aqara cloud daemon", "mdi:cloud-outline", EntityCategory.DIAGNOSTIC,
            get = { aqaraCloudStatus },
        ),
        TextSensorEntity(
            "app_version", "App version", "mdi:new-box", EntityCategory.DIAGNOSTIC,
            get = { BuildConfig.VERSION_NAME },
        ),
        // --- app updates (GitHub Releases; update/UpdateManager.kt)
        UpdateEntity(
            "app_update", "App update", "mdi:cellphone-arrow-down",
            get = { updates.model.view() }, onCommand = updates::onCommand,
        ),
        TextSensorEntity(
            "update_status", "Update status", "mdi:update", EntityCategory.DIAGNOSTIC,
            get = { updates.statusText() },
        ),
        // --- Home Assistant sign-in (docs/onboarding.md)
        TextSensorEntity(
            "home_assistant_url", "Home Assistant URL", "mdi:home-assistant", EntityCategory.DIAGNOSTIC,
            get = { prefs.haBase ?: "" },
        ),
        TextSensorEntity(
            "signed_in", "Signed in", "mdi:account-key", EntityCategory.DIAGNOSTIC,
            get = { if (HaAuth.signedIn) "yes" else "no" },
        ),
        ButtonEntity(
            "sign_out", "Sign out", "mdi:logout", EntityCategory.CONFIG,
            press = {
                // The activity shows the sign-in screen through its HaAuth listener.
                HaAuth.signOut("Home Assistant button") { exec.execute { server?.publishStates() } }
            },
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
        sendspin?.setDucked(true)
        voicePlayer.play(listOf(url)) {
            announcing = false
            sendspin?.setDucked(false)
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
        val base = prefs.url.ifEmpty { prefs.haBase?.let { "$it/" } ?: return url }
        return try { URI(base).resolve(url).toString() } catch (e: Exception) { url }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The Home Assistant device identity (ESPHome "MAC"). Must survive a reinstall or factory reset, or
     * HA sees a new device (merge prompt, duplicate entities). Order: the saved value; the Wi-Fi MAC
     * if readable; else one derived from the chip's serial (ro.serialno, from the SoC's SID), so it no
     * longer depends on Wi-Fi being up at first start (factory-fresh panels boot with Wi-Fi off).
     * Random only if neither exists.
     */
    private fun stableMac(): String {
        prefs.deviceMac.takeIf { MAC_RE.matches(it) }?.let { return it }
        val hw = try { NetworkInterface.getByName("wlan0")?.hardwareAddress } catch (_: Exception) { null }
        val usable = hw?.takeIf { a ->
            a.size == 6 && a.any { it != 0.toByte() } && !(a[0] == 2.toByte() && a.drop(1).all { it == 0.toByte() })
        }
        val serial = systemProperty("ro.serialno").takeIf { it.length >= 8 && it != "unknown" }
        val (bytes, source) = when {
            usable != null -> usable to "wlan0"
            serial != null -> java.security.MessageDigest.getInstance("SHA-256")
                .digest("s1kiosk-device-mac:$serial".toByteArray()).copyOf(6).also {
                    it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte() // unicast, locally administered
                } to "serial"
            else -> ByteArray(6).also {
                SecureRandom().nextBytes(it)
                it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte()
            } to "random"
        }
        val mac = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        prefs.deviceMac = mac
        ApiLog.i("Device MAC $mac ($source)")
        return mac
    }

    private fun systemProperty(name: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
    } catch (_: Exception) { "" }

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
