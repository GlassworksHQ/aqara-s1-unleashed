package sh.edm.s1kiosk

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.net.Uri
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import sh.edm.s1kiosk.auth.FrontendUrls
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.dashboard.DashboardOptions
import sh.edm.s1kiosk.dashboard.Dashboards
import sh.edm.s1kiosk.discovery.HaUrls
import sh.edm.s1kiosk.setup.Backdrop
import sh.edm.s1kiosk.setup.LoadingOverlay
import sh.edm.s1kiosk.setup.OfflineText
import sh.edm.s1kiosk.setup.SetupInputs
import sh.edm.s1kiosk.setup.SetupOverlay
import sh.edm.s1kiosk.setup.SetupSignals
import sh.edm.s1kiosk.setup.SetupState
import sh.edm.s1kiosk.setup.SetupStep
import sh.edm.s1kiosk.setup.WifiSetupStatus
import sh.edm.s1kiosk.setup.WifiStep
import sh.edm.s1kiosk.setup.RadioStep
import sh.edm.s1kiosk.improv.ImprovController
import sh.edm.s1kiosk.improv.ImprovWifiStatus
import sh.edm.s1kiosk.radio.RadioMode
import org.mozilla.geckoview.AllowOrDeny
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
    private lateinit var loadingOverlay: LoadingOverlay
    private lateinit var voiceOverlay: VoiceOverlay
    private lateinit var timerOverlay: TimerOverlay
    private lateinit var setupOverlay: SetupOverlay
    private lateinit var wifiStatus: WifiSetupStatus
    private var session: GeckoSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAndroidPermission: PermissionDelegate.Callback? = null
    private var canGoBack = false
    private var currentUrl: String? = null

    /** "Sign in on this panel instead" was chosen: don't cover HA's login page again (this process). */
    private var usePanelLogin = false

    /** Signed out on this panel (HA button / frontend logout) and not signed in again (this process). */
    private var signedOutHere = false

    /** Setup screen preview (`--ez show_setup true`, `--es setup_step …`): never changes state. */
    private var setupPreview = false
    private var setupPreviewStep: SetupStep? = null
    private var offlineSince = 0L
    private var lastSetupReasons: List<String> = emptyList()

    /** The radio step's last "Apply" (this activity) and whether a request is running. */
    private var radioApplied: RadioStep.Applied? = null
    private var radioBusy = false

    private var lastImprovInline = false

    /** Improv shows its Allow prompt in the setup screen's Wi-Fi step while that is visible. */
    private val improvHost = object : ImprovController.InlineHost {
        override fun showsImprovInline() = improvInline()
        override fun onImprovChanged() = refreshSetup()
        override fun onImprovIdentify() = setupOverlay.flashImprov()
    }

    private val authListener: (Boolean) -> Unit = { signedIn ->
        if (signedIn) {
            Log.i(TAG, "Signed in with the QR code; loading the dashboard with external auth")
            usePanelLogin = false
            signedOutHere = false
            if (setupPreview) closeSetupPreview()
            loadDashboard()
        } else {
            Log.i(TAG, "Signed out; showing the sign-in screen")
            usePanelLogin = false
            signedOutHere = true
            prefs.url.takeIf { it.isNotEmpty() }?.let { session?.loadUri(it) }
        }
        refreshSetup()
    }

    private val setupSignalListener: () -> Unit = { refreshSetup() }

    private val setupTick = object : Runnable {
        override fun run() {
            refreshSetup()
            pollRadioStatus()
            refreshOffline()
            handler.postDelayed(this, SETUP_TICK_MS)
        }
    }

    // Hidden settings gesture: 5 taps in the top-left corner within 3 s.
    private var cornerTaps = 0
    private var firstCornerTapAt = 0L

    private val retryLoad = Runnable {
        Log.i(TAG, "Retrying dashboard load")
        loadDashboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KioskPrefs(this)
        prefs.migrateRadioStep() // an already set-up panel never sees the radio step
        HaAuth.init(this)
        applyIntent(intent, fromNewIntent = false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 8 bits per channel: the dithered backdrop/voice glows would band again on an RGB565 surface.
        window.setFormat(PixelFormat.RGBA_8888)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Bake the dithered backdrop now, before the first frame (the overlays fill this landscape,
        // full-screen window); until it is ready the loading screen draws 8-bit gradients.
        val real = android.graphics.Point().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(it) }
        Backdrop.prewarm(maxOf(real.x, real.y), minOf(real.x, real.y))
        geckoView = GeckoView(this)
        loadingOverlay = LoadingOverlay(this)
        voiceOverlay = VoiceOverlay(this).apply { onCancel = { KioskService.voiceCancel?.invoke() } }
        timerOverlay = TimerOverlay(this)
        setupOverlay = SetupOverlay(this).apply {
            onUsePanel = ::onUsePanelLogin
            onManualUrl = ::onManualHaUrl
            onChooseNetwork = ::openWifiSettings
            onSkipHa = ::onSkipHaSetup
            onClosePreview = ::closeSetupPreview
            onImprovAction = ::onImprovAction
            onRadioApply = { m -> applyRadio(m, skip = false) }
            onRadioSkip = { applyRadio(RadioMode.OFF, skip = true) }
            onRadioContinue = ::finishRadioStep
        }
        // Improv-aware from the start: KioskService (which registers the provider) starts after this.
        wifiStatus = ImprovWifiStatus(this)
        setContentView(android.widget.FrameLayout(this).apply {
            addView(geckoView, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(loadingOverlay, android.widget.FrameLayout.LayoutParams(-1, -1)) // under the setup screen
            addView(setupOverlay, android.widget.FrameLayout.LayoutParams(-1, -1))
            // Z order (elevation): setup 20 dp > voice 16 dp > timers 12 dp > page.
            addView(timerOverlay, timerOverlay.layoutParams())
            addView(voiceOverlay, android.widget.FrameLayout.LayoutParams(-1, -1))
        })
        // The launch backdrop (res/drawable/launch_backdrop.xml) covers the cold start; once our own
        // views have drawn, a plain colour behind them costs the GPU nothing on every later frame.
        window.decorView.post { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF0A0B0F.toInt())) }
        // Visible from the first frame: the dashboard's first paint or the setup screen replaces it.
        loadingOverlay.show("Starting up", if (hasNetwork()) "Connecting to Home Assistant…" else "Reconnecting to Wi-Fi…")
        HaAuth.addListener(authListener)
        SetupSignals.addListener(setupSignalListener)
        wifiStatus.observe { refreshSetup() }
        ImprovController.inlineHost = improvHost
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
        ScreenController.onStateChanged = {
            pushState()
            timerOverlay.refresh()
        }
        KioskService.onVoiceUi = { voiceOverlay.update(KioskService.voiceUi, prefs.voiceOverlay) }
        commandSink = { applyIntent(it, fromNewIntent = true) }
        startSession()
        intent?.let { i -> handler.post { applySetupPreview(i) } }
        handler.post(setupTick)
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
        geckoView.coverUntilFirstPaint(BACKGROUND) // dark, not white, until the page paints
        session = s
        loadDashboard()
    }

    // ---- dashboard, discovery, sign-in screen (docs/onboarding.md)

    /**
     * Loads the dashboard. Signed in with the QR code → with `external_auth=1` (the frontend then
     * gets tokens from the app); otherwise as before (the GeckoView's own login session). No URL
     * yet (setting "auto", HA not found) → the "Where is Home Assistant?" screen.
     */
    private fun loadDashboard() {
        val url = prefs.url
        if (url.isEmpty()) {
            Log.i(TAG, "No dashboard URL yet (auto, Home Assistant not found)")
            refreshSetup()
            return
        }
        dashboardLoadAt = SystemClock.elapsedRealtime()
        session?.loadUri(externalize(url))
    }

    private var dashboardLoadAt = 0L

    private fun externalize(url: String): String =
        if (HaAuth.signedIn && FrontendUrls.sameOrigin(url, HaAuth.signedInBase)) FrontendUrls.withFlag(url) else url

    // ---- first-boot setup screen (docs/onboarding.md step 4; rules in setup/SetupState.kt)

    private fun setupInputs(): SetupInputs {
        val w = wifiStatus.snapshot()
        val now = SystemClock.elapsedRealtime()
        if (w.online) offlineSince = 0L else if (offlineSince == 0L) offlineSince = now
        if (w.online && !w.ethernet && !prefs.wifiSeen) prefs.wifiSeen = true
        return SetupInputs(
            online = w.online,
            offlineForMs = if (w.online) 0L else now - offlineSince,
            haKnown = prefs.haBase != null,
            haSeen = prefs.haSeen || SetupSignals.haSubscribed,
            apiKeySet = prefs.apiEncryption,
            haSkipped = prefs.haSetupSkipped,
            haHelloThisProcess = SetupSignals.haHello,
            uptimeMs = now - android.os.Process.getStartElapsedRealtime(),
            signedIn = HaAuth.signedIn,
            onAuthPage = FrontendUrls.isAuthorizePage(currentUrl, prefs.haBase),
            signedOut = signedOutHere,
            usePanelLogin = usePanelLogin,
            showing = setupOverlay.isShowing && !setupPreview,
            radioStepDone = prefs.radioStepDone,
            radioModeStored = prefs.radioMode != null,
            improv = ImprovController.setupFacts,
            installer = ImprovController.installerWifi,
            neverSetUp = SetupState.neverSetUp(
                haKnown = prefs.haBase != null, haSeen = prefs.haSeen || SetupSignals.haSubscribed,
                apiKeySet = prefs.apiEncryption, haSkipped = prefs.haSetupSkipped, radioStepDone = prefs.radioStepDone,
                radioModeStored = prefs.radioMode != null, signedIn = HaAuth.signedIn,
            ),
        )
    }

    /** Re-evaluates the setup screen; cheap, called on every relevant change and every 2 s. */
    private fun refreshSetup() {
        if (!::setupOverlay.isInitialized) return
        val d = SetupState.decide(setupInputs())
        if (setupPreview) {
            val forced = setupPreviewStep
            val m = if (forced == null) setupModel(d, d.step, d.wifiDone, d.haDone, d.radioDone, d.signinDone, preview = true)
            else setupModel(d, forced, forced > SetupStep.WIFI, forced > SetupStep.HA, forced > SetupStep.RADIO, forced > SetupStep.SIGNIN, preview = true)
            if (m.step == SetupStep.DONE) {
                if (setupOverlay.model?.step != SetupStep.DONE) setupOverlay.finish(m) { setupPreview = false; setupPreviewStep = null }
            } else {
                setupOverlay.render(m)
            }
            syncImprovInline()
            return
        }
        if (d.reasons != lastSetupReasons) {
            if (d.reasons.isNotEmpty()) Log.i(TAG, "Setup screen: ${d.reasons.joinToString()} (step ${d.step.id})")
            lastSetupReasons = d.reasons
        }
        if (d.show) {
            setupOverlay.render(setupModel(d, d.step, d.wifiDone, d.haDone, d.radioDone, d.signinDone, preview = false))
        } else if (setupOverlay.isShowing && setupOverlay.model?.step != SetupStep.DONE) {
            Log.i(TAG, "Setup complete; loading the dashboard")
            setupOverlay.finish { loadDashboardIfNeeded() }
        }
        syncImprovInline()
    }

    /**
     * While the setup screen is up, Improv lives in its Wi-Fi step and the overlay window stays
     * hidden, also while "Choose network" has Wi-Fi settings on top (the full-screen overlay would
     * cover the network list); the inline prompt is there again when the user comes back.
     */
    private fun improvInline(): Boolean =
        ::setupOverlay.isInitialized && setupOverlay.isShowing && !isFinishing && !isDestroyed

    /** The setup screen appeared or went away: Improv's overlay hides or comes back accordingly. */
    private fun syncImprovInline() {
        val now = improvInline()
        if (now == lastImprovInline) return
        lastImprovInline = now
        ImprovController.refreshUi()
    }

    private fun setupModel(
        d: sh.edm.s1kiosk.setup.SetupDecision,
        step: SetupStep, wifiDone: Boolean, haDone: Boolean, radioDone: Boolean, signinDone: Boolean, preview: Boolean,
    ) =
        SetupOverlay.Model(
            step = step,
            wifiDone = wifiDone,
            haDone = haDone,
            radioDone = radioDone,
            improv = d.improv,
            installer = ImprovController.installerWifi,
            radio = SetupOverlay.Radio(
                current = KioskService.radioRef?.mode ?: RadioMode.parse(prefs.radioMode),
                applied = radioApplied,
                busy = radioBusy,
            ),
            signinDone = signinDone,
            wifi = wifiStatus.snapshot(),
            haUrl = prefs.haBase,
            haSource = if (prefs.isAutoUrl) prefs.haUrlSource else "",
            panelIp = HaUrls.localIpv4(),
            signedIn = HaAuth.signedIn,
            preview = preview,
        )

    /** After setup: (re)load the dashboard unless it is already showing. */
    private fun loadDashboardIfNeeded() {
        val cur = currentUrl
        val onDashboard = cur != null && FrontendUrls.sameOrigin(cur, prefs.haBase) &&
            !FrontendUrls.isAuthorizePage(cur, prefs.haBase)
        // (just signed in: the auth listener already started the load)
        val justLoaded = SystemClock.elapsedRealtime() - dashboardLoadAt < 5_000
        if (!onDashboard && !justLoaded) loadDashboard()
    }

    /** `--ez show_setup true [--es setup_step wifi|ha|radio|signin|done]`, `--ez show_signin true` (= signin). */
    private fun applySetupPreview(intent: Intent) {
        val step = SetupStep.parse(intent.getStringExtra("setup_step"))
        when {
            intent.getBooleanExtra("show_signin", false) -> startSetupPreview(SetupStep.SIGNIN)
            intent.hasExtra("show_setup") && !intent.getBooleanExtra("show_setup", false) -> closeSetupPreview()
            intent.getBooleanExtra("show_setup", false) || step != null -> startSetupPreview(step)
        }
    }

    private fun startSetupPreview(step: SetupStep?) {
        Log.i(TAG, "Setup screen preview (step ${step?.id ?: "live"})")
        setupPreview = true
        setupPreviewStep = step
        refreshSetup()
    }

    private fun closeSetupPreview() {
        if (!setupPreview) return
        setupPreview = false
        setupPreviewStep = null
        setupOverlay.hide()
        refreshSetup() // shows the real screen again if the panel isn't set up
    }

    private val hideLoading = Runnable { loadingOverlay.hide() }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        return cm.activeNetwork != null
    }

    /** The loading screen shows the no-network text (OfflineText) for a failed load. */
    private var offlineShown = false

    /** A failed load without a network: "Connecting to Wi-Fi…" while a join is underway or expected. */
    private fun showOffline() {
        val t = OfflineText.decide(offlineFacts(), ImprovController.BOOT_GRACE_MS)
        offlineShown = true
        loadingOverlay.show(t.title, t.detail)
    }

    /** Every setup tick while the no-network text is up: network back -> load now; else keep the text current. */
    private fun refreshOffline() {
        if (!offlineShown || !loadingOverlay.isShowing) return
        if (hasNetwork()) {
            offlineShown = false
            handler.removeCallbacks(retryLoad)
            loadDashboard()
        } else {
            showOffline()
        }
    }

    @Suppress("DEPRECATION")
    private fun offlineFacts(): OfflineText.Facts {
        val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val now = SystemClock.elapsedRealtime()
        // Android 10 lists saved networks only to a device owner (and needs location): an empty list from
        // anyone else means "can't tell", not "none".
        val saved = runCatching { wm?.configuredNetworks }.getOrNull()?.let { list ->
            when {
                list.isNotEmpty() -> true
                isDeviceOwner() -> false
                else -> null
            }
        }
        val supplicant = runCatching { wm?.connectionInfo?.supplicantState?.name }.getOrNull()
        val detailed = runCatching { cm?.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI)?.detailedState?.name }.getOrNull()
        return OfflineText.Facts(
            wifiEnabled = runCatching { wm?.isWifiEnabled == true }.getOrDefault(false),
            savedNetwork = saved,
            joining = OfflineText.joining(supplicant, detailed),
            wasOnlineBefore = prefs.wifiSeen || prefs.haSeen,
            offlineForMs = if (offlineSince == 0L) 0L else now - offlineSince,
        )
    }

    private fun isDeviceOwner(): Boolean = runCatching {
        getSystemService(android.app.admin.DevicePolicyManager::class.java).isDeviceOwnerApp(packageName)
    }.getOrDefault(false)

    private fun openWifiSettings() {
        val intents = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else null,
            Intent(Settings.ACTION_WIFI_SETTINGS),
            // The S1's firmware has no Android Settings app; its own settings app has Network -> WLAN.
            Intent("lumi.settings.SETTINGS").setPackage("com.lumi.settings"),
        )
        for (i in intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open ${i.action}: $e")
            }
        }
        Toast.makeText(this, "No Wi-Fi settings on this device", Toast.LENGTH_LONG).show()
    }

    // ---- setup: Improv inline (Wi-Fi step) and the radio step

    private fun onImprovAction(a: WifiStep.Action) {
        if (setupPreview) return // the preview never changes state
        when (a) {
            WifiStep.Action.ALLOW -> ImprovController.authorize("button on the setup screen")
            WifiStep.Action.NOT_NOW -> ImprovController.stop("dismissed on panel")
            WifiStep.Action.STOP -> ImprovController.stop("stopped on panel")
            WifiStep.Action.START, WifiStep.Action.RETRY -> ImprovController.startOnDemand("setup screen")
        }
        refreshSetup()
    }

    /**
     * "Apply" (or "Skip", which means Off) on the radio step: the same RadioController path as HA's
     * `Radio mode` select. The result is shown as RadioController reports it (a missing root helper
     * included); only Skip and Continue mark the step done.
     */
    private fun applyRadio(mode: RadioMode, skip: Boolean) {
        if (setupPreview && skip) { closeSetupPreview(); return }
        if (setupPreview) {
            // Shows what a successful apply looks like (e.g. the coordinator's pairing QR); nothing is applied.
            radioApplied = RadioStep.Applied(mode, true, "ok ${mode.label}: preview, nothing was applied")
            refreshSetup()
            return
        }
        val r = KioskService.radioRef
        if (skip) {
            Log.i(TAG, "Setup: radio step skipped (Off)")
            if (r != null && r.mode != RadioMode.OFF) r.request(RadioMode.OFF.label, "setup screen (skip)")
            finishRadioStep()
            return
        }
        if (r == null) {
            radioApplied = RadioStep.Applied(mode, false, "The kiosk service isn't running yet; try again in a moment.")
            refreshSetup()
            return
        }
        Log.i(TAG, "Setup: radio mode ${mode.label}")
        radioBusy = true
        radioApplied = null
        refreshSetup()
        r.request(mode.label, "setup screen") { accepted ->
            val status = r.statusText()
            handler.post {
                radioBusy = false
                radioApplied = RadioStep.Applied(mode, accepted, status)
                Log.i(TAG, "Setup: radio ${mode.label} -> ${radioApplied?.result}: $status")
                refreshSetup()
            }
        }
    }

    private fun finishRadioStep() {
        if (setupPreview) { closeSetupPreview(); return }
        prefs.radioStepDone = true
        radioApplied = null
        refreshSetup()
    }

    /** While an applied mode is still "applying" (root helper), follow RadioController's status. */
    private fun pollRadioStatus() {
        val a = radioApplied ?: return
        if (!a.accepted || a.result != RadioStep.Result.APPLYING || prefs.radioStepDone) return
        val r = KioskService.radioRef ?: return
        r.refreshSoon()
        val now = r.statusText()
        if (now != a.status) {
            radioApplied = a.copy(status = now)
            refreshSetup()
        }
    }

    private fun onSkipHaSetup() {
        if (setupPreview) { closeSetupPreview(); return }
        Log.i(TAG, "Setup: adding to Home Assistant skipped")
        prefs.haSetupSkipped = true
        refreshSetup()
    }

    private fun onUsePanelLogin() {
        if (setupPreview) closeSetupPreview()
        usePanelLogin = true
        setupOverlay.hide()
        // Show HA's own login page (or the dashboard if the GeckoView session is still logged in).
        if (!HaAuth.signedIn && !FrontendUrls.isAuthorizePage(currentUrl, prefs.haBase)) {
            prefs.url.takeIf { it.isNotEmpty() }?.let { session?.loadUri(it) }
        }
        refreshSetup()
    }

    private fun onManualHaUrl(raw: String) {
        if (setupPreview) {
            setupOverlay.manualResult(false, "Preview: nothing was saved.")
            return
        }
        val d = KioskService.discoveryRef
        if (d == null) {
            setupOverlay.manualResult(false, "The kiosk service isn't running yet; try again in a moment.")
            return
        }
        d.setManual(raw) { ok, url ->
            handler.post {
                if (ok && url != null) {
                    setupOverlay.manualResult(true, "Found Home Assistant at $url")
                    onHaFound(url)
                } else {
                    setupOverlay.manualResult(false, "No Home Assistant answered at ${url ?: raw}.")
                }
            }
        }
    }

    /** Discovery (or manual entry) found Home Assistant. */
    private fun onHaFound(url: String) {
        Log.i(TAG, "Home Assistant at $url (dashboard setting: ${prefs.urlSetting})")
        refreshSetup()
        if (!prefs.isAutoUrl) return
        if (prefs.secureHosts() != KioskApp.startupSecureHosts) {
            // The HA host must be in dom.securecontext.allowlist (mic), which Gecko reads at startup.
            restartProcess()
            return
        }
        loadDashboard()
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
    //   --ez restart true       restart the app process
    //   --es api_key <base64>   ESPHome API encryption key (32 bytes); --ez api_encryption true|false
    //   --ez show_setup true    preview the setup screen (--es setup_step wifi|ha|radio|signin|done); false closes it
    // (Wi-Fi, Home Assistant and radio from the web installer go over adb sockets instead:
    //  improv/ImprovSerialServer, setup/SetupChannelServer.)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent, fromNewIntent = true)
    }

    private fun applyIntent(intent: Intent?, fromNewIntent: Boolean) {
        intent ?: return
        var needsRestart = false
        intent.getStringExtra("url")?.takeIf { it.isNotBlank() }?.let {
            val oldHosts = prefs.secureHosts()
            prefs.urlSetting = it // a URL, or "auto"
            needsRestart = needsRestart || prefs.secureHosts() != oldHosts
            if (fromNewIntent) loadDashboard()
        }
        intent.getStringExtra("ha_discovered")?.let { if (fromNewIntent) onHaFound(it) }
        if (intent.getBooleanExtra("open_settings", false)) DeviceSettings.open(this)
        // From the web installer: the panel's language (it recreates this activity if it changes).
        intent.getStringExtra("set_locale")?.let { tag ->
            intent.removeExtra("set_locale")
            SystemLocale.set(tag)
        }
        if (fromNewIntent) applySetupPreview(intent)
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
        if (intent.hasExtra("hide_header") || intent.hasExtra("hide_sidebar")) {
            if (intent.hasExtra("hide_header")) prefs.hideHeader = intent.getBooleanExtra("hide_header", true)
            if (intent.hasExtra("hide_sidebar")) prefs.hideSidebar = intent.getBooleanExtra("hide_sidebar", true)
            pushConfig()
        }
        intent.getStringExtra("screen_mode")?.let { prefs.screenMode = it }
        if (intent.hasExtra("idle_off_sec")) prefs.idleOffSec = intent.getIntExtra("idle_off_sec", 120)
        if (intent.hasExtra("night_idle_off_sec")) prefs.nightIdleOffSec = intent.getIntExtra("night_idle_off_sec", 20)
        intent.getStringExtra("night_window")?.let { prefs.nightWindow = it }
        if (intent.hasExtra("presence_range_m")) {
            prefs.presenceRangeM = intent.getFloatExtra("presence_range_m", 0f)
            ScreenController.applyPresenceRange()
        }
        if (intent.hasExtra("voice_enabled")) { prefs.voiceEnabled = intent.getBooleanExtra("voice_enabled", true); needsRestart = true }
        // Applied live by PanelDevice (it listens to the prefs).
        intent.getStringExtra("wake_model")?.let { prefs.wakeModel = it }
        if (intent.hasExtra("wake_threshold")) prefs.wakeThreshold = intent.getFloatExtra("wake_threshold", 0.5f)
        if (intent.hasExtra("mic_gain")) prefs.micGain = intent.getFloatExtra("mic_gain", 1f)
        if (intent.hasExtra("mic_muted")) prefs.micMuted = intent.getBooleanExtra("mic_muted", false)
        if (intent.hasExtra("sendspin_enabled")) prefs.sendspinEnabled = intent.getBooleanExtra("sendspin_enabled", true)
        intent.getStringExtra("sendspin_server")?.let { prefs.sendspinServer = it }
        if (intent.hasExtra("sendspin_unpaired_access")) prefs.sendspinUnpairedAccess = intent.getBooleanExtra("sendspin_unpaired_access", false)
        if (intent.hasExtra("sendspin_flac")) prefs.sendspinFlac = intent.getBooleanExtra("sendspin_flac", true)
        // Wall base (docs/research/bases.md): auto|uart|localservice, and "" (/dev/ttyS3) or tcp:host:port (emulator).
        intent.getStringExtra("base_backend")?.let { prefs.baseBackend = it }
        intent.getStringExtra("base_transport")?.let { prefs.baseTransport = it }
        intent.getStringExtra("api_key")?.let { key ->
            if (KioskPrefs.decodeApiKey(key) != null) prefs.apiKey = key
            else Log.w(TAG, "api_key must be base64 of exactly 32 bytes; ignored")
        }
        if (intent.hasExtra("api_encryption")) prefs.apiEncryption = intent.getBooleanExtra("api_encryption", false)
        if (intent.hasExtra("auto_brightness")) {
            prefs.autoBrightness = intent.getBooleanExtra("auto_brightness", true)
            ScreenController.refreshBrightness()
        }
        when (intent.getStringExtra("screen")) {
            "off" -> ScreenController.sleep("intent")
            "on" -> ScreenController.wake("intent")
        }
        if (intent.getBooleanExtra("remove_admin", false)) ScreenController.removeAdmin()
        if (fromNewIntent && intent.getBooleanExtra("settings", false)) showSettings()
        if (fromNewIntent && (needsRestart || intent.getBooleanExtra("restart", false))) {
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
            setText(prefs.urlSetting)
            hint = "auto (find Home Assistant)"
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
        // Same setting as HA's "Radio allowed clients" (docs/radio.md "Firewall"): empty = Home Assistant only.
        val radio = KioskService.radioRef
        val allowNow = radio?.allowedNow()
        val allowField = EditText(this).apply {
            setText(radio?.allowedClients().orEmpty())
            hint = "empty = Home Assistant only"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            isEnabled = radio != null
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(TextView(context).apply {
                text = ScreenController.statusLine() + " · voice=" + KioskService.voiceState; textSize = 12f
            })
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
            addView(TextView(context).apply {
                text = "Radio: who may connect (IP addresses or ranges, or any)"
            })
            addView(allowField)
            addView(TextView(context).apply {
                textSize = 12f
                text = when {
                    radio == null -> "Kiosk service not running"
                    allowNow == null -> "Allowed now: no firewall active (radio off or old root helper)"
                    allowNow.isEmpty() -> "Allowed now: only the panel itself (Home Assistant hasn't connected yet)"
                    else -> "Allowed now: ${allowNow.replace(" ", ", ")}"
                }
            })
            // The dialog's three buttons are taken; a borderless one at the end, over the settings dialog.
            addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Open source licences"
                isAllCaps = false
                setOnClickListener { Licences.show(this@MainActivity) }
            })
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        AlertDialog.Builder(this)
            .setTitle("S1 Kiosk ${BuildConfig.VERSION_NAME}")
            .setView(scroll)
            .setPositiveButton("Save & restart") { _, _ ->
                prefs.urlSetting = urlField.text.toString().ifBlank { KioskPrefs.AUTO }
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
                val allowText = allowField.text.toString().trim()
                if (radio != null && allowText != radio.allowedClients()) {
                    val err = radio.setAllowedClients(allowText)
                    if (err != null) {
                        // the old list stays; give the message time to be read before the restart
                        Toast.makeText(this, "Radio allow-list not changed: $err", Toast.LENGTH_LONG).show()
                        handler.postDelayed({ restartProcess() }, 3500)
                    } else {
                        // let the radio thread hand the new list to the helper before the process restarts
                        radio.afterPending { handler.post { restartProcess() } }
                    }
                } else restartProcess()
            }
            .setNeutralButton("Android settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(this, "No settings app: $e", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Reload") { _, _ -> loadDashboard() }
            .show()
    }

    private var restartWaitingSince = 0L

    /**
     * Gecko prefs are read once per process, so restart the process to apply them. Not while a Wi-Fi
     * join is in flight (installer or Bluetooth): joining is when HA gets discovered, and restarting
     * then would cut the Improv connection before its result arrives. Waits up to a minute for that.
     */
    private fun restartProcess() {
        val now = SystemClock.elapsedRealtime()
        if (ImprovController.busy && (restartWaitingSince == 0L || now - restartWaitingSince < 60_000)) {
            if (restartWaitingSince == 0L) {
                restartWaitingSince = now
                Log.i(TAG, "restart deferred: Wi-Fi setup in progress")
            }
            window.decorView.postDelayed({ restartProcess() }, 1000)
            return
        }
        restartWaitingSince = 0L
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
        override fun onFirstContentfulPaint(session: GeckoSession) {
            handler.post { loadingOverlay.hide() }
        }

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

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            currentUrl = url
            // An unauthenticated HA frontend redirects to /auth/authorize: the setup screen covers it
            // with the QR code (unless the user asked for the panel's own login page).
            handler.post { refreshSetup() }
        }

        // Once signed in, every top-level HA page gets external_auth=1, so the content script
        // installs window.externalApp before the frontend starts (also after reloads and redirects).
        override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            if (!HaAuth.signedIn || request.target != GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT) return null
            val base = HaAuth.signedInBase
            val target = when {
                FrontendUrls.isAuthorizePage(request.uri, base) -> prefs.url.takeIf { it.isNotEmpty() }?.let(::externalize)
                FrontendUrls.needsFlag(request.uri, base) -> FrontendUrls.withFlag(request.uri)
                else -> null
            } ?: return null
            handler.post { session.loadUri(target) }
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            handler.removeCallbacks(retryLoad)
            handler.removeCallbacks(hideLoading)
            offlineShown = false
            if (url.startsWith("http")) loadingOverlay.show("Connecting to Home Assistant", Uri.parse(url).host ?: "")
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (success) {
                handler.postDelayed(hideLoading, 1500) // in case first-contentful-paint was missed
            } else {
                if (hasNetwork()) {
                    offlineShown = false
                    loadingOverlay.show("Can't reach Home Assistant", "Retrying every few seconds…")
                } else {
                    showOffline() // "Connecting to Wi-Fi…" after a reboot, the setup hint when there's nothing to wait for
                }
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
        .put("voice", KioskService.voiceState)

    private fun pushConfig() {
        val msg = JSONObject().put("type", "config").put("config", configJson())
        ports.toList().forEach { runCatching { it.postMessage(msg) } }
    }

    private fun pushState() {
        val msg = JSONObject().put("type", "state").put("state", stateJson())
        ports.toList().forEach { runCatching { it.postMessage(msg) } }
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val type = (message as? JSONObject)?.optString("type")
            if (type != "config" && type != "state" && type != "activity")
                Log.d(TAG, "ext message '$type' from ${sender.url} topLevel=${sender.isTopLevel}")
            return when (type) {
                "config" -> GeckoResult.fromValue(configJson())
                "state" -> GeckoResult.fromValue(stateJson())
                "activity" -> { ScreenController.userActivity(); null }
                "getExternalAuth" -> externalAuth(message as JSONObject, sender)
                "revokeExternalAuth" -> revokeExternalAuth(sender)
                else -> null
            }
        }

        override fun onConnect(port: WebExtension.Port) {
            ports += port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onDisconnect(port: WebExtension.Port) { ports -= port }
                override fun onPortMessage(message: Any, port: WebExtension.Port) = onPortRequest(message, port)
            })
            port.postMessage(JSONObject().put("type", "state").put("state", stateJson()))
        }
    }

    /**
     * Request/response over the native port (content.js `request()`): `{type, id, ...}` in,
     * `{type: "reply", id, ...}` out. Used instead of sendNativeMessage replies, which don't reach
     * the page in this GeckoView build ("Invalid event data for callback").
     */
    private fun onPortRequest(message: Any, port: WebExtension.Port) {
        val m = message as? JSONObject ?: return
        if (m.optString("type") == "dashboards") { onDashboards(m, port.sender); return }
        val id = m.optInt("id", -1)
        if (id < 0) return
        fun reply(o: Any?) {
            val out = (o as? JSONObject) ?: JSONObject().put("success", false)
            out.put("type", "reply").put("id", id)
            handler.post { runCatching { port.postMessage(out) } }
        }
        when (m.optString("type")) {
            "config" -> reply(JSONObject().put("config", configJson()))
            "state" -> reply(JSONObject().put("state", stateJson()))
            "getExternalAuth" -> externalAuth(m, port.sender).accept { r -> reply(r) }
            "revokeExternalAuth" -> revokeExternalAuth(port.sender).accept { r -> reply(r) }
            else -> reply(null)
        }
    }

    /**
     * `{type: "dashboards", json: "[{url_path,title}]"}` from the HA frontend (content.js, the page's
     * own `lovelace/dashboards/list`), for the ESPHome "Dashboard" select. Only the top-level page on
     * the Home Assistant the panel uses is believed.
     */
    private fun onDashboards(m: JSONObject, sender: WebExtension.MessageSender) {
        if (!sender.isTopLevel || !FrontendUrls.sameOrigin(sender.url, prefs.haBase)) {
            Log.w(TAG, "dashboard list from ${sender.url} ignored (not the Home Assistant in use)")
            return
        }
        val list = DashboardOptions.parse(m.optString("json")) ?: return
        Log.d(TAG, "${list.size} dashboard(s) from the frontend")
        Dashboards.report(list)
    }

    /** Only the signed-in HA origin may ask for tokens. */
    private fun senderAllowed(sender: WebExtension.MessageSender): Boolean =
        sender.isTopLevel && FrontendUrls.sameOrigin(sender.url, HaAuth.signedInBase)

    /** `externalApp.getExternalAuth` → `{success, access_token, expires_in}` (never logged). */
    private fun externalAuth(msg: JSONObject, sender: WebExtension.MessageSender): GeckoResult<Any> {
        if (!senderAllowed(sender)) {
            Log.w(TAG, "getExternalAuth from ${sender.url} refused (not the signed-in Home Assistant)")
            return GeckoResult.fromValue(JSONObject().put("success", false))
        }
        val result = GeckoResult<Any>()
        HaAuth.accessToken(force = msg.optBoolean("force")) { a ->
            val reply = when (a) {
                is HaAuth.Access.Ok -> JSONObject().put("success", true)
                    .put("access_token", a.accessToken)
                    // GeckoView native-message replies go through GeckoBundle, which has no Long:
                    // a Long here fails with "Invalid event data for callback" and the page never hears back.
                    .put("expires_in", a.expiresIn.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                is HaAuth.Access.Failed -> {
                    Log.w(TAG, "getExternalAuth failed: ${a.reason}")
                    JSONObject().put("success", false)
                }
            }
            Log.d(TAG, "getExternalAuth -> success=${reply.optBoolean("success")}")
            handler.post { result.complete(reply) }
        }
        return result
    }

    /** `externalApp.revokeExternalAuth`: the user logged out in the HA frontend. */
    private fun revokeExternalAuth(sender: WebExtension.MessageSender): GeckoResult<Any> {
        if (!senderAllowed(sender)) return GeckoResult.fromValue(JSONObject().put("success", false))
        val result = GeckoResult<Any>()
        HaAuth.signOut("logged out in the frontend") {
            handler.post { result.complete(JSONObject().put("success", true)) }
        }
        return result
    }

    override fun onDestroy() {
        HaAuth.removeListener(authListener)
        SetupSignals.removeListener(setupSignalListener)
        wifiStatus.observe(null)
        if (ImprovController.inlineHost === improvHost) {
            lastImprovInline = false
            ImprovController.inlineHost = null // the overlay takes over if Improv is still running
        }
        ScreenController.onKeepScreenOn = null
        ScreenController.onBrightness = null
        ScreenController.onStateChanged = null
        KioskService.onVoiceUi = null
        commandSink = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** Onboarding/dashboard background (#0a0b0f), also used to cover the page until first paint. */
        private const val BACKGROUND = 0xFF0A0B0F.toInt()
        private const val TAG = "S1Kiosk"

        /**
         * Applies an intent's extras in the running activity (main thread), like onNewIntent.
         * Used by the ESPHome device so Home Assistant can drive the kiosk without starting
         * activities from the background.
         */
        @Volatile var commandSink: ((Intent) -> Unit)? = null
        private const val REQ_STARTUP = 1
        private const val REQ_GECKO = 2
        private const val RETRY_MS = 10_000L
        private const val SETUP_TICK_MS = 2_000L
    }
}
