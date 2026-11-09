package io.github.aguilaair.s1kiosk

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.json.JSONObject
import kotlin.system.exitProcess

class MainActivity : Activity() {

    private lateinit var prefs: KioskPrefs
    private lateinit var geckoView: GeckoView
    private var session: GeckoSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAndroidPermission: PermissionDelegate.Callback? = null
    private var canGoBack = false

    // Hidden settings gesture: 5 taps in the top-left corner within 3 s.
    private var cornerTaps = 0
    private var firstCornerTapAt = 0L

    private val retryLoad = Runnable {
        Log.i(TAG, "Retrying dashboard load")
        session?.loadUri(prefs.url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KioskPrefs(this)
        applyIntent(intent, fromNewIntent = false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        geckoView = GeckoView(this)
        setContentView(geckoView)
        KioskService.start(this)
        ScreenController.start(this)
        ScreenController.onKeepScreenOn = { on ->
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        ScreenController.onBrightness = { b ->
            window.attributes = window.attributes.apply {
                screenBrightness = if (b < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else b
            }
        }
        ScreenController.onStateChanged = { pushState() }
        startSession()
        requestRuntimePermissions()
        hideSystemUi()
    }

    private fun startSession() {
        val s = GeckoSession(
            GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .useTrackingProtection(false)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .allowJavascript(true)
                .build()
        )
        s.permissionDelegate = permissionDelegate
        s.contentDelegate = contentDelegate
        s.navigationDelegate = navigationDelegate
        s.progressDelegate = progressDelegate
        s.open(KioskApp.runtime(this))
        KioskApp.helperExtension(this).accept({ ext ->
            if (ext != null) s.webExtensionController.setMessageDelegate(ext, messageDelegate, "s1kiosk")
        }, { e -> Log.e(TAG, "helper extension failed", e) })
        geckoView.setSession(s)
        session = s
        s.loadUri(prefs.url)
    }

    private fun restartSession() {
        session?.let {
            geckoView.releaseSession()
            it.close()
        }
        session = null
        startSession()
    }

    // ---- intents (see app/README.md):
    //   --es url <url>          set + persist the dashboard URL
    //   --es open <url>         navigate once, without persisting
    //   --ef zoom 1.25          CSS px ratio (0 = auto); restarts the process
    //   --es secure_hosts a,b   extra secure-context hosts; restarts the process
    //   --ez remote_debug true  Gecko remote debugging; restarts the process
    //   --ez reload true        reload the page

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent, fromNewIntent = true)
    }

    private fun applyIntent(intent: Intent?, fromNewIntent: Boolean) {
        intent ?: return
        var needsRestart = false
        intent.getStringExtra("url")?.takeIf { it.isNotBlank() }?.let {
            val oldHosts = prefs.secureHosts()
            prefs.url = it
            needsRestart = needsRestart || prefs.secureHosts() != oldHosts
            if (fromNewIntent) session?.loadUri(it)
        }
        if (intent.hasExtra("zoom")) {
            prefs.zoom = intent.getFloatExtra("zoom", 0f)
            needsRestart = true
        }
        intent.getStringExtra("secure_hosts")?.let {
            prefs.extraSecureHosts = it
            needsRestart = true
        }
        if (intent.hasExtra("remote_debug")) {
            prefs.remoteDebugging = intent.getBooleanExtra("remote_debug", false)
            needsRestart = true
        }
        if (intent.hasExtra("hide_header")) prefs.hideHeader = intent.getBooleanExtra("hide_header", true)
        if (intent.hasExtra("hide_sidebar")) prefs.hideSidebar = intent.getBooleanExtra("hide_sidebar", true)
        intent.getStringExtra("screen_mode")?.let { prefs.screenMode = it }
        if (intent.hasExtra("idle_off_sec")) prefs.idleOffSec = intent.getIntExtra("idle_off_sec", 120)
        if (intent.hasExtra("night_idle_off_sec")) prefs.nightIdleOffSec = intent.getIntExtra("night_idle_off_sec", 20)
        intent.getStringExtra("night_window")?.let { prefs.nightWindow = it }
        if (intent.hasExtra("presence_range_m")) {
            prefs.presenceRangeM = intent.getFloatExtra("presence_range_m", 0f)
            ScreenController.applyPresenceRange()
        }
        if (intent.hasExtra("auto_brightness")) prefs.autoBrightness = intent.getBooleanExtra("auto_brightness", true)
        when (intent.getStringExtra("screen")) {
            "off" -> ScreenController.sleep("intent")
            "on" -> ScreenController.wake("intent")
        }
        if (intent.getBooleanExtra("remove_admin", false)) ScreenController.removeAdmin()
        if (fromNewIntent && intent.getBooleanExtra("settings", false)) showSettings()
        if (fromNewIntent && needsRestart) {
            restartProcess()
            return
        }
        if (fromNewIntent && intent.getBooleanExtra("reload", false)) session?.reload()
        // One-off navigation that does NOT change the saved dashboard URL.
        if (fromNewIntent) intent.getStringExtra("open")?.takeIf { it.isNotBlank() }?.let { session?.loadUri(it) }
    }

    // ---- immersive full screen

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    // ---- hidden settings gesture (doesn't consume the touch)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            ScreenController.userActivity()
            val corner = 80 * resources.displayMetrics.density
            if (ev.x < corner && ev.y < corner) {
                val now = SystemClock.uptimeMillis()
                if (cornerTaps == 0 || now - firstCornerTapAt > 3000) {
                    cornerTaps = 0
                    firstCornerTapAt = now
                }
                if (++cornerTaps >= 5) {
                    cornerTaps = 0
                    showSettings()
                }
            } else {
                cornerTaps = 0
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val urlField = EditText(this).apply {
            setText(prefs.url)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val zoomField = EditText(this).apply {
            setText(if (prefs.zoom > 0f) prefs.zoom.toString() else "")
            hint = "auto (device density)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
        }
        val hostsField = EditText(this).apply {
            setText(prefs.extraSecureHosts)
            hint = "e.g. homeassistant.local,192.168.1.100"
            setSingleLine()
        }
        val hideHeader = CheckBox(this).apply { text = "Hide HA header & sidebar"; isChecked = prefs.hideHeader }
        val presence = CheckBox(this).apply {
            text = "Screen follows presence (off when idle)"; isChecked = prefs.screenMode == "presence"
        }
        val idleField = EditText(this).apply {
            setText(prefs.idleOffSec.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
        }
        val nightField = EditText(this).apply { setText(prefs.nightWindow); hint = "22:00-07:00 (empty = off)"; setSingleLine() }
        val nightIdleField = EditText(this).apply {
            setText(prefs.nightIdleOffSec.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
        }
        val rangeField = EditText(this).apply {
            setText(if (prefs.presenceRangeM > 0f) prefs.presenceRangeM.toString() else "")
            hint = "firmware default (1.5 m)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine()
        }
        val autoBright = CheckBox(this).apply { text = "Auto brightness (light sensor)"; isChecked = prefs.autoBrightness }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(TextView(context).apply { text = ScreenController.statusLine(); textSize = 12f })
            addView(TextView(context).apply { text = "Dashboard URL" })
            addView(urlField)
            addView(TextView(context).apply { text = "Zoom (CSS px ratio, e.g. 1.0 – 2.0)" })
            addView(zoomField)
            addView(TextView(context).apply { text = "Extra secure-context hosts" })
            addView(hostsField)
            addView(hideHeader)
            addView(presence)
            addView(TextView(context).apply { text = "Screen off after idle (s, 0 = never)" })
            addView(idleField)
            addView(TextView(context).apply { text = "Night window" })
            addView(nightField)
            addView(TextView(context).apply { text = "Night: screen off after idle (s)" })
            addView(nightIdleField)
            addView(TextView(context).apply { text = "Presence range (m, 0.1 – 2.5)" })
            addView(rangeField)
            addView(autoBright)
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        AlertDialog.Builder(this)
            .setTitle("S1 Kiosk ${BuildConfig.VERSION_NAME}")
            .setView(scroll)
            .setPositiveButton("Save & restart") { _, _ ->
                prefs.url = urlField.text.toString().ifBlank { BuildConfig.DEFAULT_URL }
                prefs.zoom = zoomField.text.toString().toFloatOrNull() ?: 0f
                prefs.extraSecureHosts = hostsField.text.toString()
                prefs.hideHeader = hideHeader.isChecked
                prefs.hideSidebar = hideHeader.isChecked
                prefs.screenMode = if (presence.isChecked) "presence" else "always"
                prefs.idleOffSec = idleField.text.toString().toIntOrNull() ?: 120
                prefs.nightWindow = nightField.text.toString()
                prefs.nightIdleOffSec = nightIdleField.text.toString().toIntOrNull() ?: 20
                prefs.autoBrightness = autoBright.isChecked
                prefs.presenceRangeM = rangeField.text.toString().toFloatOrNull() ?: 0f
                restartProcess()
            }
            .setNeutralButton("Android settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(this, "No settings app: $e", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Reload") { _, _ -> session?.loadUri(prefs.url) }
            .show()
    }

    /** Gecko prefs are read once per process, so restart the process to apply them. */
    private fun restartProcess() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.RTC, System.currentTimeMillis() + 600, pi)
        finishAffinity()
        exitProcess(0)
    }

    // ---- back: navigate back inside the page; never leave the kiosk

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (canGoBack) session?.goBack()
    }

    // ---- Android runtime permissions (mic/camera) for Gecko

    private fun requestRuntimePermissions() {
        val wanted = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQ_STARTUP)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_GECKO) {
            val cb = pendingAndroidPermission
            pendingAndroidPermission = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) cb?.grant()
            else cb?.reject()
        }
    }

    private val permissionDelegate = object : PermissionDelegate {
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: PermissionDelegate.Callback
        ) {
            val missing = permissions.orEmpty()
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                callback.grant()
            } else {
                pendingAndroidPermission?.reject()
                pendingAndroidPermission = callback
                requestPermissions(missing.toTypedArray(), REQ_GECKO)
            }
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: ContentPermission
        ): GeckoResult<Int> {
            Log.i(TAG, "Granting content permission ${perm.permission} for ${perm.uri}")
            return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out MediaSource>?,
            audio: Array<out MediaSource>?,
            callback: PermissionDelegate.MediaCallback
        ) {
            val mic = audio?.firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE } ?: audio?.firstOrNull()
            val cam = video?.firstOrNull { it.source == MediaSource.SOURCE_CAMERA } ?: video?.firstOrNull()
            Log.i(TAG, "Granting media for $uri: audio=${mic?.name} video=${cam?.name}")
            callback.grant(cam, mic)
        }
    }

    // ---- content / navigation / progress

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onCrash(session: GeckoSession) {
            Log.e(TAG, "Content process crashed; restarting session")
            handler.post { restartSession() }
        }

        override fun onKill(session: GeckoSession) {
            Log.e(TAG, "Content process killed; restarting session")
            handler.post { restartSession() }
        }
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@MainActivity.canGoBack = canGoBack
        }

        // target=_blank / window.open: keep it in the single kiosk session.
        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            session.loadUri(uri)
            return null
        }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            handler.removeCallbacks(retryLoad)
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (!success) {
                // HA restarting / Wi-Fi not up yet after boot: keep retrying.
                handler.removeCallbacks(retryLoad)
                handler.postDelayed(retryLoad, RETRY_MS)
            }
        }
    }

    // ---- page <-> app messaging (assets/extension/content.js)

    private val ports = mutableSetOf<WebExtension.Port>()

    private fun configJson() = JSONObject()
        .put("hideHeader", prefs.hideHeader)
        .put("hideSidebar", prefs.hideSidebar)

    private fun stateJson() = JSONObject()
        .put("presence", ScreenController.near)
        .put("proximityRaw", ScreenController.proximityRaw.let { if (it.isNaN()) JSONObject.NULL else it.toDouble() })
        .put("lux", ScreenController.lux.let { if (it.isNaN()) JSONObject.NULL else it.toDouble() })
        .put("screenOn", ScreenController.screenOn)
        .put("night", ScreenController.isNight())

    private fun pushState() {
        val msg = JSONObject().put("type", "state").put("state", stateJson())
        ports.toList().forEach { runCatching { it.postMessage(msg) } }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val type = (message as? JSONObject)?.optString("type")
            return when (type) {
                "config" -> GeckoResult.fromValue(configJson())
                "state" -> GeckoResult.fromValue(stateJson())
                "activity" -> { ScreenController.userActivity(); null }
                else -> null
            }
        }

        override fun onConnect(port: WebExtension.Port) {
            ports += port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onDisconnect(port: WebExtension.Port) { ports -= port }
            })
            port.postMessage(JSONObject().put("type", "state").put("state", stateJson()))
        }
    }

    override fun onDestroy() {
        ScreenController.onKeepScreenOn = null
        ScreenController.onBrightness = null
        ScreenController.onStateChanged = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "S1Kiosk"
        private const val REQ_STARTUP = 1
        private const val REQ_GECKO = 2
        private const val RETRY_MS = 10_000L
    }
}
