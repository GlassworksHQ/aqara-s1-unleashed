package io.github.aguilaair.s1kiosk

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
import io.github.aguilaair.s1kiosk.auth.HaAuth
import io.github.aguilaair.s1kiosk.auth.UrlConnectionPoster
import io.github.aguilaair.s1kiosk.discovery.HaDiscovery
import io.github.aguilaair.s1kiosk.esphome.PanelDevice
import io.github.aguilaair.s1kiosk.voice.VoiceEvent

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
        HaAuth.startServer(this)
        val d = HaDiscovery(this, UrlConnectionPoster(timeoutMs = 5_000)) { url, source ->
            // Applied by the activity (dashboard URL "auto": load it, or restart for secure-context hosts).
            main.post { MainActivity.commandSink?.invoke(Intent().putExtra("ha_discovered", url).putExtra("ha_source", source)) }
        }
        discovery = d
        discoveryRef = d
        d.start()
        device = PanelDevice(this, { e -> main.post { onVoiceEvent(e) } }, d::onApiHello).also { it.start() }
        Log.i("S1Kiosk", "KioskService started")
    }

    private fun onVoiceEvent(e: VoiceEvent) {
        voiceState = when (e) {
            is VoiceEvent.WakeDetected -> "wake"
            is VoiceEvent.Listening -> "listening"
            is VoiceEvent.Thinking -> "thinking"
            is VoiceEvent.Speaking -> "speaking"
            is VoiceEvent.Idle -> "idle"
            is VoiceEvent.Error -> "error: ${e.message}"
        }
        Log.i("S1Kiosk.Voice", "voice: $voiceState")
        // Talking to the panel counts as activity: wake the screen and keep it on.
        if (e !is VoiceEvent.Idle && e !is VoiceEvent.Error) ScreenController.wake("voice")
        ScreenController.onStateChanged?.invoke()
    }

    override fun onDestroy() {
        device?.stop()
        device = null
        discovery?.stop()
        discovery = null
        discoveryRef = null
        HaAuth.stopServer()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
        return b.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("S1 Kiosk")
            .setContentText("Screen, sensors & ESPHome API running")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "kiosk"
        private const val NOTIFICATION_ID = 1

        /** Last voice assistant state, exposed to the page via MainActivity.stateJson(). */
        @Volatile var voiceState: String = "off"

        /** Set while the service runs; the sign-in screen uses it for manual HA URL entry. */
        @Volatile var discoveryRef: HaDiscovery? = null

        fun start(context: Context) {
            val i = Intent(context, KioskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
