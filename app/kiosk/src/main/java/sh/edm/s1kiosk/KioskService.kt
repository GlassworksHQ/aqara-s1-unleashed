package sh.edm.s1kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.auth.UrlConnectionPoster
import sh.edm.s1kiosk.discovery.HaDiscovery
import sh.edm.s1kiosk.esphome.PanelDevice
import sh.edm.s1kiosk.improv.ImprovController
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

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        ScreenController.start(this)
        voiceState = if (KioskPrefs(this).voiceEnabled) "idle" else "off"
        voiceUi = VoiceUi(voiceState)
        voiceCancel = { device?.cancelVoice() }
        HaAuth.startServer(this)
        val d = HaDiscovery(this, UrlConnectionPoster(timeoutMs = 5_000)) { url, source ->
            // Applied by the activity (dashboard URL "auto": load it, or restart for secure-context hosts).
            main.post { MainActivity.commandSink?.invoke(Intent().putExtra("ha_discovered", url).putExtra("ha_source", source)) }
        }
        discovery = d
        discoveryRef = d
        d.start()
        device = PanelDevice(this, { e -> main.post { onVoiceEvent(e) } }, d::onApiHello).also { it.start() }
        ImprovController.init(this) // Improv Wi-Fi via BLE (provisioning mode; see improv/)
        Log.i("S1Kiosk", "KioskService started")
    }

    private fun onVoiceEvent(e: VoiceEvent) {
        voiceUi = voiceUi.on(e)
        try { onVoiceUi?.invoke() } catch (t: Throwable) { Log.w("S1Kiosk.Voice", "voice overlay failed", t) }
        if (e is VoiceEvent.Heard || e is VoiceEvent.Reply) return // texts only, no state change
        voiceState = voiceUi.state
        Log.i("S1Kiosk.Voice", "voice: $voiceState")
        // Talking to the panel counts as activity: wake the screen and keep it on.
        if (e !is VoiceEvent.Idle && e !is VoiceEvent.Error) ScreenController.wake("voice")
        ScreenController.onStateChanged?.invoke()
    }

    override fun onDestroy() {
        voiceCancel = null
        ImprovController.shutdown()
        device?.stop()
        device = null
        discovery?.stop()
        discovery = null
        discoveryRef = null
        HaAuth.stopServer()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ImprovController.handleIntent(intent) // --ez improv / release_device_owner (via ImprovActivity)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Kiosk", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("S1 Kiosk")
            .setContentText("Screen, sensors & ESPHome API running")
            .setContentIntent(open)
            .setOngoing(true)
        // The shade has no settings gear until Aqara's own onboarding ran: offer the way in here.
        DeviceSettings.intent(this)?.let { settings ->
            val pi = PendingIntent.getActivity(this, 1, settings, PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_launcher), "Settings", pi).build())
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL = "kiosk"
        private const val NOTIFICATION_ID = 1

        /** Last voice assistant state, exposed to the page via MainActivity.stateJson(). */
        @Volatile var voiceState: String = "off"

        /** What the voice overlay shows (state, heard text, reply). Main thread. */
        var voiceUi = VoiceUi()
            private set

        /** Called on the main thread after every voice event (the activity's voice overlay). */
        var onVoiceUi: (() -> Unit)? = null

        /** Cancels the current voice run (stops streaming and playback); null while the service isn't running. */
        @Volatile var voiceCancel: (() -> Unit)? = null
            private set

        /** Set while the service runs; the sign-in screen uses it for manual HA URL entry. */
        @Volatile var discoveryRef: HaDiscovery? = null

        fun start(context: Context) {
            val i = Intent(context, KioskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
