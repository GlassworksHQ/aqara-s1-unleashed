package sh.edm.s1kiosk

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.glassworks.platform.Platform
import sh.edm.s1kiosk.settings.SettingsMenu
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.auth.UrlConnectionPoster
import dev.glassworks.frame.systemSettings
import sh.edm.s1kiosk.discovery.HaDiscovery
import sh.edm.s1kiosk.esphome.PanelDevice
import sh.edm.s1kiosk.frame.ScreenOffWifiScanner
import sh.edm.s1kiosk.improv.ImprovController
import sh.edm.s1kiosk.improv.ImprovSerialServer
import sh.edm.s1kiosk.manage.CodeOverlay
import sh.edm.s1kiosk.setup.SetupChannelServer
import sh.edm.s1kiosk.voice.VoiceEvent
import sh.edm.s1kiosk.voice.VoiceUi

/**
 * Long-lived foreground service that owns the sensor/screen policy and the ESPHome device
 * (native API server + voice assistant), so they keep running while the screen is off and the
 * activity is stopped.
 */
class KioskService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var device: PanelDevice? = null
    private var discovery: HaDiscovery? = null
    private var wifiScanner: ScreenOffWifiScanner? = null
    private var codeOverlay: CodeOverlay? = null
    private lateinit var foreground: KioskForeground

    override fun onCreate() {
        super.onCreate()
        glassworks.paneDefinitions(this) // first: a step's one-time check runs before discovery can save an HA URL
        val notification = buildNotification()
        foreground = KioskForeground(
            Platform,
            onRefused = { t, e -> Log.w("S1Kiosk", "foreground service types $t (${KioskForeground.names(t)}) refused", e) },
            startWith = { Platform.startForeground(this, NOTIFICATION_ID, notification, it) },
        )
        promote()
        glassworks.screen.start(this)
        wifiScanner = glassworks.frame.screenOffWifiScanner(this) { main.post { if (wifiScanner != null) promote() } }.also { it.start() }
        glassworks.voiceState = if (KioskPrefs(this).voice.enabled) "idle" else "off"
        glassworks.voiceUi = VoiceUi(glassworks.voiceState)
        glassworks.voiceCancel = { device?.cancelVoice() }
        HaAuth.startServer(this)
        codeOverlay = CodeOverlay(this) { glassworks.panelCode.dismiss() }
        // Without "draw over other apps" the code can't show: the Manage page says so instead of waiting for it.
        glassworks.panelCode.canDisplay = { android.provider.Settings.canDrawOverlays(this) }
        glassworks.panelCode.display = { shown ->
            main.post {
                if (shown != null) glassworks.screen.wake("panel code")
                codeOverlay?.show(shown)
                // Off the screen when it runs out (expire() leaves a newer code alone), so it doesn't keep the screen on.
                if (shown != null) main.postDelayed({ glassworks.panelCode.expire() }, (shown.untilMs - System.currentTimeMillis()).coerceAtLeast(0) + 500)
            }
        }
        val d = HaDiscovery(this, UrlConnectionPoster(timeoutMs = 5_000)) { url, source ->
            // Applied by the activity (dashboard URL "auto": load it, or restart for secure-context hosts).
            main.post { glassworks.commandSink?.invoke(Intent().putExtra("ha_discovered", url).putExtra("ha_source", source)) }
        }
        discovery = d
        glassworks.discovery = d
        d.start()
        device = PanelDevice(this, glassworks, { e -> main.post { onVoiceEvent(e) } }, d::onApiHello).also {
            it.start()
            glassworks.device = it
        }
        ImprovController.init(this) // Improv Wi-Fi via BLE (provisioning mode; see improv/)
        // The web installer, over adb (docs/onboarding.md): Wi-Fi via Improv Serial, then HA + radio.
        ImprovSerialServer.start()
        SetupChannelServer.start(this, glassworks)
        Log.i("S1Kiosk", "KioskService started")
    }

    private fun onVoiceEvent(e: VoiceEvent) {
        glassworks.voiceUi = glassworks.voiceUi.on(e)
        try { glassworks.onVoiceUi?.invoke() } catch (t: Throwable) { Log.w("S1Kiosk.Voice", "voice overlay failed", t) }
        if (e is VoiceEvent.Heard || e is VoiceEvent.Reply) return // texts only, no state change
        glassworks.voiceState = glassworks.voiceUi.state
        Log.i("S1Kiosk.Voice", "voice: ${glassworks.voiceState}")
        // Talking to the panel counts as activity: wake the screen and keep it on.
        if (e !is VoiceEvent.Idle && e !is VoiceEvent.Error) glassworks.screen.wake("voice")
        glassworks.screen.onStateChanged?.invoke()
    }

    override fun onDestroy() {
        glassworks.voiceCancel = null
        SetupChannelServer.stop()
        ImprovSerialServer.stop()
        ImprovController.shutdown()
        glassworks.device = null
        device?.stop()
        device = null
        discovery?.stop()
        discovery = null
        glassworks.discovery = null
        HaAuth.stopServer()
        glassworks.panelCode.display = {}
        glassworks.panelCode.canDisplay = { false }
        codeOverlay?.show(null)
        codeOverlay = null
        wifiScanner?.stop()
        wifiScanner = null
        super.onDestroy()
    }

    /**
     * Runs the service with every foreground type it may have now. Called on each start, so
     * [start] after a runtime grant adds the type without a restart (voice keeps the mic with the
     * screen off). Also called on the Wi-Fi scanner's first refusal for the location app-op, which
     * covers a grant from outside the app. Does nothing while the types are unchanged.
     */
    private fun promote() {
        val granted = { p: String -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        val before = foreground.types
        val types = foreground.promote(
            micGranted = granted(Manifest.permission.RECORD_AUDIO),
            locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            // Only on a device with a camera: the S1 has none, and its service stays as it was.
            cameraGranted = granted(Manifest.permission.CAMERA) && packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
        )
        if (types != before) {
            Log.i("S1Kiosk", "foreground service types $types (${KioskForeground.names(types)}; location ${if (types and KioskForeground.LOCATION != 0) "on" else "off"})")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        ImprovController.handleIntent(intent) // --ez improv / release_device_owner (via ImprovActivity)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Platform.hasNotificationChannels) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Kiosk", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        // Tapping it opens the kiosk settings menu (a second way in besides the 5 taps; anyone at the panel
        // can pull the shade down). MainActivity is singleTask: the running one gets it in onNewIntent.
        val menu = PendingIntent.getActivity(
            this, REQ_KIOSK_SETTINGS,
            Intent(this, MainActivity::class.java)
                .putExtra(SettingsMenu.EXTRA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        @Suppress("DEPRECATION")
        val b = if (Platform.hasNotificationChannels) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("S1 Kiosk")
            .setContentText("Tap for kiosk settings · screen, sensors & ESPHome API running")
            .setContentIntent(menu)
            .setOngoing(true)
        // The shade has no settings gear until Aqara's own onboarding ran: Aqara's settings app from here too.
        glassworks.frame.systemSettings(this)?.let { settings ->
            val pi = PendingIntent.getActivity(this, REQ_PANEL_SETTINGS, settings, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            @Suppress("DEPRECATION")
            b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_launcher), "Panel settings", pi).build())
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL = "kiosk"
        private const val NOTIFICATION_ID = 1
        private const val REQ_KIOSK_SETTINGS = 10  // distinct PendingIntent request codes
        private const val REQ_PANEL_SETTINGS = 11

        fun start(context: Context) {
            val i = Intent(context, KioskService::class.java)
            if (Platform.hasForegroundServiceStart) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
