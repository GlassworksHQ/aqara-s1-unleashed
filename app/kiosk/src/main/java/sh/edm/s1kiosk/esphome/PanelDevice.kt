package sh.edm.s1kiosk.esphome

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dev.glassworks.bluetoothproxy.BluetoothProxyDefinition
import dev.glassworks.camera.CameraDefinition
import dev.glassworks.camera.CameraHub
import dev.glassworks.camera.CameraIdentity
import dev.glassworks.camera.CameraPane
import dev.glassworks.camera.CameraStream
import dev.glassworks.camera.FrameEgress
import dev.glassworks.camera.stillGate
import dev.glassworks.diagnostics.DiagnosticsPane
import dev.glassworks.frame.Frame
import dev.glassworks.pane.EntityRegistry
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.PanelEvent
import dev.glassworks.pane.forEachPane
import dev.glassworks.pane.stopAll
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import sh.edm.esphome.*
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.android.EspHomeMdns
import sh.edm.s1kiosk.BuildConfig
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.MainActivity
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.auth.OAuth
import sh.edm.s1kiosk.dashboard.Dashboard
import sh.edm.s1kiosk.dashboard.DashboardOptions
import sh.edm.s1kiosk.dashboard.Dashboards
import sh.edm.s1kiosk.dashboard.StableChange
import sh.edm.s1kiosk.discovery.HaUrls
import sh.edm.s1kiosk.radio.RadioPane
import sh.edm.s1kiosk.sendspin.SendspinPane
import sh.edm.s1kiosk.setup.SetupSignals
import sh.edm.s1kiosk.timers.TimersPane
import sh.edm.s1kiosk.update.UpdateManager
import sh.edm.s1kiosk.voice.AssistPane
import sh.edm.s1kiosk.voice.ExoAudioPlayer
import sh.edm.s1kiosk.voice.SpeakerMedia
import sh.edm.s1kiosk.voice.TtsPlayer
import sh.edm.s1kiosk.voice.VoiceEngine
import sh.edm.s1kiosk.voice.VoiceEvent
import sh.edm.s1kiosk.voice.VoiceLevels
import sh.edm.s1kiosk.voice.chime
import sh.edm.s1kiosk.voice.wakeChime

/**
 * The panel as one ESPHome device for Home Assistant: native API server (port 6053), mDNS,
 * entities bound to the kiosk settings / the screen policy, a media player, and the panes
 * (Sendspin, the voice assistant, timers, the radio, and the frame's own). Owned by KioskService.
 *
 * Entity values are read from [KioskPrefs] / [screen] (thread-safe); anything touching
 * the screen or the activity is posted to the main thread. Kiosk actions (URL, reload, zoom,
 * header, restart) go to [MainActivity] as intents, exactly like the `adb am start` extras.
 */
class PanelDevice internal constructor(
    context: Context,
    private val runtime: GlassworksRuntime,
    private val onVoiceEvent: (VoiceEvent) -> Unit,
    /** HelloRequest from an API client (Home Assistant discovery, HaDiscovery.onApiHello). */
    private val onApiHello: (String, java.net.InetAddress?) -> Unit,
    /** esphome-main. A test passes one it runs by hand, to choose when queued work happens. */
    private val exec: ScheduledExecutorService,
    private val mdns: MdnsAdvertiser,
    /** The API server's port. A test passes 0 for any free one. */
    private val apiPort: Int,
    /** Where the API server listens: null for every address. A test passes the loopback address it connects to (#113). */
    private val apiBindAddress: java.net.InetAddress?,
) {

    constructor(
        context: Context,
        runtime: GlassworksRuntime,
        onVoiceEvent: (VoiceEvent) -> Unit,
        onApiHello: (String, java.net.InetAddress?) -> Unit = { _, _ -> },
    ) : this(
        context, runtime, onVoiceEvent, onApiHello,
        exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "esphome-main").apply { isDaemon = true } },
        mdns = EspHomeMdnsAdvertiser(EspHomeMdns(context.applicationContext, board = runtime.frame.board)),
        apiPort = ApiServer.DEFAULT_PORT,
        apiBindAddress = null,
    )

    companion object {
        /** The library's logs go under the tag the docs use (`adb logcat -s S1ESPHome`). */
        const val ESPHOME_LOG_TAG = "S1ESPHome"

        init { EspHomeLog.tag = ESPHOME_LOG_TAG }

        /**
         * The ESPHome node name. Home Assistant's config entry keeps it (`device_name`), the mDNS service
         * is named after it, and HA up to 2025.11 makes new entity ids from it (`s1_panel_*`). Never
         * change it. From HA 2025.12 new entity ids take the device's name as HA shows it
         * ([FRIENDLY_NAME], or the user's own), and from 2026.8 the area's name can come first.
         */
        const val NAME = "s1-panel"

        /**
         * What Home Assistant calls the panel (#63), from DeviceInfo and the mDNS `friendly_name`. HA's
         * device registry takes it on every connect unless someone renamed the device there. Entity ids
         * already registered never change: their unique ids are `<mac>/<device_id>/<domain>/<entity name>`
         * (aioesphomeapi `build_unique_id` v3), with no device name in them.
         */
        const val FRIENDLY_NAME = "Glassworks Panel"
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
    /** Typed as the interface. The device-only parts come in through [Frame.panes]. */
    private val frame: Frame = runtime.frame
    private val screen = runtime.screen
    /** Each pane's on/off, by pane id ([dev.glassworks.pane.PaneDefinition.switch]). A pane with none always runs. */
    private val switches: Map<String, PaneSwitch> =
        runtime.paneDefinitions(ctx).mapNotNull { d -> d.switch?.let { d.id to it } }.toMap()
    /** The ids of the panes started now. esphome-main. */
    private val started = HashSet<String>()

    private var server: ApiServer? = null
    /** Guards [stopped] and the hand-over of [server] between [startServer] and [stop]. */
    private val lifecycle = Any()
    private var stopped = false
    private val noiseKeys = NoiseKeys(prefs.noiseKeyStore)
    /** Read by the API server on its connection threads; replaced when voice is turned on or off. */
    @Volatile private lateinit var identity: DeviceIdentity
    private lateinit var entities: List<ApiEntity>
    private lateinit var coreEntities: List<ApiEntity>
    private val voicePlayer = ExoAudioPlayer(context, "voice", AudioAttributes.USAGE_ASSISTANT)
    private val mediaPlayer = ExoAudioPlayer(context, "media", AudioAttributes.USAGE_MEDIA)
    private var chimePlayer: TtsPlayer? = null
    /** The features that add entities and react to the panel (Sendspin, voice, timers, ...); built in [initEntities]. */
    @Volatile private var panes: List<Pane> = emptyList()
    private var assist: AssistPane? = null
    /**
     * App updates from GitHub Releases (docs/releasing.md); the "App update" entity. runCatching:
     * the update thread may still report while [stop] has already shut [exec] down.
     */
    private val updates = UpdateManager(ctx, runtime, onChange = { runCatching { exec.execute { server?.publishStates() } } })
    @Volatile private var announcing = false
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
        synchronized(lifecycle) { stopped = true }
        prefs.unregisterListener(prefListener)
        screen.removeListener(screenListener)
        HaAuth.removeListener(authListener)
        Dashboards.removeListener(dashboardsListener)
        // Panes first, while esphome-main still runs: the wall base's thread can still post to it.
        val gone = panes
        val interrupted = gone.stopAll()
        panes = emptyList()
        // A newer device may have published its own already.
        if (runtime.panes === gone) runtime.panes = emptyList()
        cameraHub?.release()
        cameraHub = null
        assist = null
        exec.shutdownNow()
        mdns.stop()
        synchronized(lifecycle) { server.also { server = null } }?.stop()
        updates.stop()
        voicePlayer.release()
        mediaPlayer.release()
        chimePlayer?.release()
        releaseLocks()
        // Passed on only now, so no step of the shutdown above is cut short by it.
        if (interrupted) Thread.currentThread().interrupt()
    }

    // ------------------------------------------------------------------ setup

    /**
     * Runs [block] unless [stop] has begun, and returns whether it ran. [stop] takes the same lock to
     * set [stopped], so it waits for a step that is running and then stops what that step started.
     */
    private fun unlessStopped(block: () -> Unit): Boolean = synchronized(lifecycle) {
        if (stopped) false else { block(); true }
    }

    /** esphome-main. Each step checks [stopped], so a [stop] during startup leaves nothing running. */
    internal fun startInternal() {
        if (synchronized(lifecycle) { stopped }) return
        val id = DeviceIdentity(
            name = NAME,
            friendlyName = FRIENDLY_NAME,
            macAddress = stableMac(),
            esphomeVersion = ESPHOME_VERSION,
            compilationTime = "S1 Kiosk ${BuildConfig.VERSION_NAME}",
            manufacturer = "Aqara",
            model = "Panel Hub S1 Plus (S1 Kiosk)",
            voiceFeatures = voiceFeatures(),
            // Home Assistant's "Visit" link on the device page opens the Manage page (#33).
            webserverPort = OAuth.SIGNIN_PORT,
        )
        EspHomeLog.i("ESPHome device '${id.name}' mac ${id.macAddress}, voice=${id.voiceFeatures != 0}")
        if (!unlessStopped { initEntities(id); acquireLocks() }) return
        applyVolume()
        mediaPlayer.onStateChanged = { exec.execute { server?.publishStates() } }
        voicePlayer.onStateChanged = {
            VoiceLevels.ttsPlaying = voicePlayer.isPlaying // drives the voice overlay's speaking pulse
            exec.execute { server?.publishStates() }
        }

        for ((pane, s) in switches) EspHomeLog.i("Pane $pane ${if (s.on) "on" else "off"}")
        panes.forEachPane("start") { pane -> unlessStopped { if (isOn(pane)) { pane.start(); started += pane.id } } }
        if (!unlessStopped { updates.start() }) return
        startServer()
        unlessStopped {
            prefs.registerListener(prefListener)
            screen.addListener(screenListener)
            HaAuth.addListener(authListener)
            Dashboards.addListener(dashboardsListener)
            exec.scheduleWithFixedDelay({ checkDashboards() }, 2, 2, TimeUnit.SECONDS)
            exec.scheduleWithFixedDelay({ server?.publishStates() }, 5, 5, TimeUnit.SECONDS)
        }
        // A switch changed after its pane started and before the listener above: no event came for it. And
        // each pane that is off hears its own switch once, so it puts back what it undoes outside the app
        // (Aqara's daemon, the time zone the root helper keeps) after a restart too.
        val off = panes.filter { !isOn(it) }.mapNotNull { switches[it.id]?.key }
        for (key in (applySwitches() + off).distinct()) emit(PanelEvent.SettingChanged(key))
    }

    /**
     * The entity list Home Assistant gets at start, built without starting anything (the entity pin
     * test calls it too). esphome-main.
     */
    internal fun initEntities(identity: DeviceIdentity) {
        this.identity = identity
        val paneContext = PaneContext(
            ctx, prefs::paneFile, identity,
            publishStates = { runCatching { exec.execute { server?.publishStates() } } },
            emit = ::emit,
            entitiesChanged = { runCatching { exec.execute { rebuildEntities() } } },
            sendEvent = { entity, type -> runCatching { exec.execute { server?.sendEvent(entity, type) } } },
        )
        val assistPane = AssistPane(
            paneContext,
            player = voicePlayer,
            speaker = speakerMedia,
            resolveUrl = ::resolveUrl,
            wakeChime = { chimes().wakeChime() },
            onVoiceEvent = onVoiceEvent,
            createInput = { cfg, model, onModelChanged -> VoiceEngine(ctx, cfg, model, onModelChanged) },
        )
        assist = assistPane
        panes = listOf(
            SendspinPane(paneContext, haBase = { prefs.haBase }),
            assistPane,
            // Timers only come from the voice assistant: without it the pane has nothing to do.
            TimersPane(
                paneContext,
                screen,
                schedule = { ms, action -> exec.schedule({ action() }, ms, TimeUnit.MILLISECONDS) },
                play = { chimes().chime() },
                silence = { synchronized(this) { chimePlayer }?.cancel() },
            ),
        ) + frame.panes(paneContext, exec) + RadioPane(paneContext, runtime, panelIp = ::ipAddress) +
            runtime.paneDefinitions(ctx).filterIsInstance<BluetoothProxyDefinition>().map { it.pane(paneContext, exec) } +
            listOfNotNull(cameraPane(paneContext, identity)) +
            DiagnosticsPane(paneContext, frame.cpuThermalZone, runtime.engine::isContentProcess, exec)
        runtime.panes = panes
        // Every pane's on/off, also while the pane is off.
        coreEntities = buildEntities() + switches.values.map { it.entity() }
        appliedDashboards = DashboardOptions.parse(prefs.dashboardsJson).orEmpty()
        dashboardRebuild = StableChange(dashboardOptions(appliedDashboards))
        entities = assemble()
    }

    internal fun entityList(): List<ApiEntity> = entities

    /** The camera, while the camera pane exists. Released in [stop]. */
    private var cameraHub: CameraHub? = null

    /** The camera pane (#117), on a device with a camera only: elsewhere only its on/off exists, and its page says it can't run. */
    private fun cameraPane(paneContext: PaneContext, identity: DeviceIdentity): CameraPane? {
        val def = runtime.paneDefinitions(ctx).filterIsInstance<CameraDefinition>().firstOrNull() ?: return null
        if (!def.hasCamera) return null
        val hub = CameraHub(ctx).also { cameraHub = it }
        val egress = FrameEgress(SystemClock::elapsedRealtime, { ms, r -> main.postDelayed(r, ms) }) { leaving ->
            EspHomeLog.i("Camera frames ${if (leaving) "leaving the panel" else "no longer leaving"}")
            runtime.cameraLeaving = leaving
            main.post { runtime.onCameraLeaving?.invoke() }
        }
        var pane: CameraPane? = null
        // The pane's own judgement: no still while it is off or Android doesn't allow the camera.
        val gate = stillGate(hub, { pane?.stillPolicy() ?: def.prefs.policy.copy(privacy = true) }, egress)
        val stream = CameraStream(
            ctx, hub, egress,
            identity = { CameraIdentity(identity.friendlyName, "Glassworks ${BuildConfig.VERSION_NAME}", identity.macAddress) },
            host = ::ipAddress,
            snapshot = gate,
        )
        return CameraPane(
            paneContext, def.prefs, hub, hub, stream, egress,
            permitted = { ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED },
            ownSensor = { runtime.presence.ownSensor(ctx) },
            toScreen = runtime.presence::offer,
            exec = exec,
            release = hub::idle,
        ).also { pane = it }
    }

    internal fun paneList(): List<Pane> = panes

    // ------------------------------------------------------------------ dashboard select

    private fun assemble(): List<ApiEntity> =
        EntityRegistry(panes).assemble(coreEntities + dashboardSelect(dashboardRebuild.applied()))

    private fun currentDashboardOption(list: List<Dashboard>) =
        DashboardOptions.currentOption(prefs.urlSetting, prefs.haBase, list)

    private fun dashboardOptions(list: List<Dashboard>) = DashboardOptions.options(list, currentDashboardOption(list))

    /** Fixed object_id (so the key never changes); only the options change between rebuilds. */
    private fun dashboardSelect(options: List<String>) = SelectEntity(
        "dashboard", "Dashboard", "mdi:view-dashboard", disabledByDefault = true, options = options,
        get = { currentDashboardOption(appliedDashboards) },
        set = { option ->
            val url = DashboardOptions.urlFor(option, prefs.urlSetting, prefs.haBase, prefs.haUrl, appliedDashboards)
            if (url != null) {
                EspHomeLog.i("Dashboard select: '$option'")
                toActivity { putExtra("url", url) } // saves the setting and reloads
            }
        },
    )

    /**
     * esphome-main, every 2 s and on new lists: rebuild the entity set when the option list really
     * changed and has been stable for 10 s (at most once a minute); a list change that keeps the
     * same options only updates the option → url_path mapping.
     */
    private fun checkDashboards() {
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
        EspHomeLog.i("Dashboard options changed (${due.size}); rebuilding the entity list")
        rebuildEntities()
    }

    /** esphome-main: the entity list from the core and every pane again, given to Home Assistant. */
    private fun rebuildEntities() {
        entities = assemble()
        server?.setEntities(entities)
    }

    private fun adoptDashboards(list: List<Dashboard>) {
        appliedDashboards = list
        prefs.dashboardsJson = DashboardOptions.toJson(list)
    }

    private fun startServer() {
        val s = ApiServer(apiPort, { identity }, entities, noiseKeys, apiBindAddress)
        panes.forEachPane("bind") { it.bind(s) }
        s.onCommand = { exec.execute { s.publishStates() } }
        s.onClientHello = { info, addr ->
            if (HaUrls.isHomeAssistantHello(info)) SetupSignals.onHaHello()
            onApiHello(info, addr)
        }
        // HA subscribes only after it has added the panel: remember that (setup screen, step 2).
        s.onStatesSubscribed = { info, addr, authenticated ->
            if (HaUrls.isHomeAssistantHello(info)) {
                if (!prefs.haSeen) { prefs.haSeen = true; EspHomeLog.i("Home Assistant has added the panel") }
                SetupSignals.onHaSubscribed()
                emit(PanelEvent.HomeAssistantSubscribed(addr, authenticated))
            }
        }
        s.onTimeZone = { zone -> emit(PanelEvent.HomeAssistantTimeZone(zone)) }
        // A provisioned (or cleared) key changes the mDNS TXT records; re-register after the
        // unregistration has gone out so the name doesn't collide with our own old record.
        s.onKeyActivated = { exec.execute { readvertise() } }
        try {
            s.start()
        } catch (e: IOException) {
            EspHomeLog.w("Cannot listen on $apiPort: ${e.message}; retrying in 10 s")
            exec.schedule({ if (server == null) startServer() }, 10, TimeUnit.SECONDS)
            return
        }
        // stop() may have run while this start was in flight: then the server must not outlive it.
        val kept = unlessStopped {
            server = s
            mdns.start(identity, s.localPort, encrypted = s.encrypted, provisionable = s.provisionable)
        }
        if (!kept) s.stop()
    }

    private fun readvertise() {
        val s = server ?: return
        mdns.stop()
        exec.schedule({
            // Like the first registration in startServer(): never once stop() has begun.
            unlessStopped { if (server === s) mdns.start(identity, s.localPort, encrypted = s.encrypted, provisionable = s.provisionable) }
        }, 1500, TimeUnit.MILLISECONDS)
    }

    /** Key changed in the panel settings (adb extras): apply it unless it is the one HA just provisioned. */
    private fun onApiKeyPrefChanged() {
        val k = prefs.effectiveApiKey()
        val latest = noiseKeys.latest
        val same = if (k == null || latest == null) k == null && latest == null else k.contentEquals(latest)
        if (same) return
        EspHomeLog.i(if (k == null) "API key removed in the panel settings" else "API key set in the panel settings (fingerprint ${NoiseKeys.fingerprint(k)})")
        server?.replaceKey(k)
    }

    private fun emit(event: PanelEvent) = panes.forEachPane("onEvent") { it.onEvent(event) }

    /** esphome-main thread. */
    private fun onPrefChanged(key: String?) {
        when (key) {
            "api_encryption", "api_key" -> onApiKeyPrefChanged()
            "speaker_volume", "speaker_muted" -> applyVolume()
        }
        // Before the event, so a pane that hears its own switch is already started or stopped.
        if (switches.values.any { it.key == key }) applySwitches()
        emit(PanelEvent.SettingChanged(key))
        server?.publishStates()
    }

    private fun isOn(pane: Pane) = switches[pane.id]?.on != false

    /** The voice assistant's features for Home Assistant: none while its pane ([PaneSwitch.voiceAssistant]) is off. */
    private fun voiceFeatures() = if (switches.values.firstOrNull { it.voiceAssistant }?.on != false) VOICE_FEATURES else 0

    /**
     * esphome-main: starts each pane that was turned on and stops each that was turned off (#67), and
     * returns their switches' keys. When voice changed, the device info changes too, and Home Assistant
     * reconnects to read it.
     */
    private fun applySwitches(): List<String> {
        val changed = ArrayList<String>()
        for (pane in panes) {
            val on = isOn(pane)
            if (on == (pane.id in started)) continue
            if (on) {
                // Recorded only once start() returned: a pane that failed to start is tried again on the next change.
                if (!unlessStopped { listOf(pane).forEachPane("start") { it.start(); started += pane.id } }) return changed
            } else {
                started -= pane.id
                listOf(pane).forEachPane("stop") { it.stop() }
            }
            switches[pane.id]?.let { changed += it.key }
            EspHomeLog.i("Pane ${pane.id} ${if (on) "on" else "off"}")
        }
        val features = voiceFeatures()
        if (features != identity.voiceFeatures) {
            identity = identity.copy(voiceFeatures = features)
            runtime.voiceState = if (features != 0) "idle" else "off"
            EspHomeLog.i("voice=${features != 0}: Home Assistant reconnects to read the voice features")
            rebuildEntities()
        }
        return changed
    }

    /** The chime player, made on first use (from the ESPHome, voice or main thread). */
    private fun chimes(): TtsPlayer = synchronized(this) { chimePlayer ?: TtsPlayer().also { chimePlayer = it } }

    /** Home Assistant is connected and listening to the panel's states now. */
    fun homeAssistantConnected(): Boolean = server?.hasSubscriber == true

    /** The user cancelled the current voice run (tap on the overlay). */
    fun cancelVoice() {
        exec.execute { assist?.cancel() }
    }

    /** The speaker's music, which the assistant pauses while it listens and talks. */
    private val speakerMedia = object : SpeakerMedia {
        override val playing get() = mediaPlayer.isPlaying
        override val announcing get() = this@PanelDevice.announcing
        override fun pause() = mediaPlayer.pause()
        override fun resume() = mediaPlayer.resume()
    }

    // ------------------------------------------------------------------ entities

    private fun ui(block: () -> Unit) { main.post(block) }

    /** Sends extras to MainActivity (same as `adb shell am start ... --e* key value`). */
    private fun toActivity(build: Intent.() -> Unit) {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(build)
        ui {
            val sink = runtime.commandSink
            if (sink != null) sink(i) else try { ctx.startActivity(i) } catch (e: Exception) {
                EspHomeLog.w("Cannot start MainActivity", e)
            }
        }
    }

    /** Before the screen starts, the manual value it will apply; never while auto brightness is on (#129). */
    private fun currentBrightness(): Float =
        screen.brightness.takeIf { it >= 0f }
            ?: prefs.manualBrightness.takeIf { !prefs.autoBrightness && it > 0f }
            ?: 1f

    private fun buildEntities(): List<ApiEntity> = listOf(
        // --- screen & sensors
        BrightnessLightEntity(
            "screen", "Screen", "mdi:tablet-dashboard",
            get = { screen.screenOn to currentBrightness() },
            set = { on, b ->
                ui {
                    if (b != null) screen.setManualBrightness(b)
                    when (on) {
                        true -> screen.wake("home assistant")
                        false -> screen.sleep("home assistant")
                        null -> Unit
                    }
                }
            },
        ),
        SwitchEntity(
            "auto_brightness", "Auto brightness", "mdi:brightness-auto", EntityCategory.CONFIG,
            disabledByDefault = true, get = { prefs.autoBrightness },
            set = { prefs.autoBrightness = it; ui { screen.refreshBrightness() } },
        ),
        BinarySensorEntity("presence", "Presence", deviceClass = "occupancy", get = { screen.near }),
        SensorEntity(
            "illuminance", "Illuminance", unit = "lx", accuracy = 0, deviceClass = "illuminance",
            get = { screen.lux },
        ),
        SelectEntity(
            "screen_mode", "Screen mode", "mdi:monitor-eye", options = listOf("presence", "always"),
            get = { prefs.screenMode }, set = { prefs.screenMode = it },
        ),
        NumberEntity(
            "idle_timeout", "Idle timeout", "mdi:timer-outline", disabledByDefault = true, min = 0f, max = 3600f, step = 5f,
            unit = "s", mode = 1, deviceClass = "duration",
            get = { prefs.idleOffSec.toFloat() }, set = { prefs.idleOffSec = it.toInt() },
        ),
        NumberEntity(
            "night_idle_timeout", "Night idle timeout", "mdi:timer-sand", disabledByDefault = true, min = 0f, max = 3600f, step = 5f,
            unit = "s", mode = 1, deviceClass = "duration",
            get = { prefs.nightIdleOffSec.toFloat() }, set = { prefs.nightIdleOffSec = it.toInt() },
        ),
        TextEntity(
            "night_window", "Night window", "mdi:weather-night", disabledByDefault = true, maxLength = 11,
            pattern = "^$|^[0-9]{1,2}:[0-9]{2}-[0-9]{1,2}:[0-9]{2}$",
            get = { prefs.nightWindow }, set = { prefs.nightWindow = it },
        ),
        NumberEntity(
            "presence_range", "Presence range", "mdi:signal-distance-variant", disabledByDefault = true, min = 0f, max = 2.5f, step = 0.1f,
            unit = "m", mode = 2, deviceClass = "distance",
            get = { prefs.presenceRangeM }, set = { prefs.presenceRangeM = it; ui { screen.applyPresenceRange() } },
        ),
        // --- kiosk
        TextEntity(
            "dashboard_url", "Dashboard URL", "mdi:web", disabledByDefault = true, minLength = 1, maxLength = 255,
            get = { prefs.urlSetting }, set = { url -> toActivity { putExtra("url", url) } },
        ),
        NumberEntity(
            "zoom", "Zoom", "mdi:magnify-plus-outline", disabledByDefault = true, min = 0f, max = 3f, step = 0.05f, mode = 1,
            get = { prefs.zoom },
            set = { z -> if (kotlin.math.abs(z - prefs.zoom) > 0.001f) toActivity { putExtra("zoom", z) } },
        ),
        ButtonEntity("reload_dashboard", "Reload dashboard", "mdi:refresh", press = { toActivity { putExtra("reload", true) } }),
        ButtonEntity("open_settings", "Open device settings", "mdi:cog", EntityCategory.CONFIG,
            disabledByDefault = true, press = { toActivity { putExtra("open_settings", true) } }),
        ButtonEntity(
            "restart_app", "Restart app", "mdi:restart", EntityCategory.CONFIG, deviceClass = "restart",
            press = { toActivity { putExtra("restart", true) } },
        ),
        ButtonEntity(
            "improv_ble", "Start Wi-Fi setup (Bluetooth)", "mdi:bluetooth-settings", EntityCategory.CONFIG,
            disabledByDefault = true, press = { ui { sh.edm.s1kiosk.improv.ImprovController.startOnDemand("Home Assistant button") } },
        ),
        SwitchEntity(
            "hide_ha_header", "Hide HA header", "mdi:page-layout-header", EntityCategory.CONFIG,
            disabledByDefault = true, get = { prefs.hideHeader },
            set = { v -> toActivity { putExtra("hide_header", v); putExtra("hide_sidebar", v) } },
        ),
        // --- audio
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
        // --- diagnostics
        TextSensorEntity("ip_address", "IP address", "mdi:ip-network", EntityCategory.DIAGNOSTIC, get = ::ipAddress),
        TextSensorEntity(
            "app_version", "App version", "mdi:new-box", EntityCategory.DIAGNOSTIC,
            disabledByDefault = true, get = { BuildConfig.VERSION_NAME },
        ),
        // --- app updates (GitHub Releases; update/UpdateManager.kt)
        UpdateEntity(
            "app_update", "App update", "mdi:cellphone-arrow-down",
            get = { updates.model.view() }, onCommand = updates::onCommand,
        ),
        TextSensorEntity(
            "update_status", "Update status", "mdi:update", EntityCategory.DIAGNOSTIC,
            disabledByDefault = true, get = { updates.statusText() },
        ),
        // --- Home Assistant sign-in (docs/onboarding.md)
        TextSensorEntity(
            "home_assistant_url", "Home Assistant URL", "mdi:home-assistant", EntityCategory.DIAGNOSTIC,
            disabledByDefault = true, get = { prefs.haBase ?: "" },
        ),
        TextSensorEntity(
            "signed_in", "Signed in", "mdi:account-key", EntityCategory.DIAGNOSTIC,
            disabledByDefault = true, get = { if (HaAuth.signedIn) "yes" else "no" },
        ),
        ButtonEntity(
            "sign_out", "Sign out", "mdi:logout", EntityCategory.CONFIG,
            disabledByDefault = true, press = {
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
        emit(PanelEvent.SpeakerClaimed(true))
        voicePlayer.play(listOf(url)) {
            announcing = false
            emit(PanelEvent.SpeakerClaimed(false))
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
        EspHomeLog.i("Device MAC $mac ($source)")
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
            EspHomeLog.w("Could not acquire wake/wifi lock", e)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }
}
