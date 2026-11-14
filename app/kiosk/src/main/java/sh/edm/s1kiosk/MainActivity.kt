package sh.edm.s1kiosk

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.net.Uri
import android.widget.Toast
import dev.glassworks.engine.Page
import dev.glassworks.engine.PageHost
import dev.glassworks.engine.PagePort
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.applyFrom
import dev.glassworks.pane.intentExtras
import dev.glassworks.pane.paneSections
import dev.glassworks.pane.settingsOrder
import dev.glassworks.platform.Platform
import sh.edm.s1kiosk.auth.FrontendUrls
import sh.edm.s1kiosk.auth.HaAuth
import dev.glassworks.frame.openSystemSettings
import dev.glassworks.frame.systemSettings
import sh.edm.s1kiosk.dashboard.DashboardOptions
import sh.edm.s1kiosk.dashboard.Dashboards
import sh.edm.s1kiosk.discovery.HaUrls
import sh.edm.s1kiosk.notifications.NotificationOverlay
import sh.edm.s1kiosk.settings.CoreSettings
import sh.edm.s1kiosk.settings.KioskSettingsOverlay
import sh.edm.s1kiosk.settings.SettingsMenu
import sh.edm.s1kiosk.setup.Backdrop
import sh.edm.s1kiosk.setup.LoadingOverlay
import sh.edm.s1kiosk.setup.OfflineText
import sh.edm.s1kiosk.setup.holdingPanes
import sh.edm.s1kiosk.setup.PaneStepFacts
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupInputs
import sh.edm.s1kiosk.setup.SetupOverlay
import sh.edm.s1kiosk.setup.SetupSignals
import sh.edm.s1kiosk.setup.SetupState
import sh.edm.s1kiosk.setup.WifiSetupStatus
import sh.edm.s1kiosk.setup.WifiStep
import sh.edm.s1kiosk.improv.ImprovController
import sh.edm.s1kiosk.improv.ImprovWifiStatus
import org.json.JSONObject
import kotlin.system.exitProcess

class MainActivity : Activity() {

    private lateinit var prefs: KioskPrefs
    private lateinit var loadingOverlay: LoadingOverlay
    private lateinit var settingsOverlay: KioskSettingsOverlay
    private lateinit var voiceOverlay: VoiceOverlay
    private lateinit var timerOverlay: TimerOverlay
    private lateinit var notificationOverlay: NotificationOverlay
    private lateinit var threadJoinOverlay: sh.edm.s1kiosk.setup.ThreadJoinOverlay
    private lateinit var setupOverlay: SetupOverlay
    private lateinit var wifiStatus: WifiSetupStatus
    private lateinit var root: android.widget.FrameLayout
    private var page: Page? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAndroidPermission: ((Boolean) -> Unit)? = null
    private var currentUrl: String? = null

    /** The panes' definitions: their setup steps, settings sections and adb extras. */
    private val panes by lazy { glassworks.paneDefinitions(this) }
    /** The panes on setup's Panes step, each with its steps (only usable ones). */
    private val setupPanes by lazy { glassworks.setupPanes(this) }
    private val paneSteps by lazy { setupPanes.flatMap { it.steps } }

    /** "Sign in on this panel instead" was chosen: don't cover HA's login page again (this process). */
    private var usePanelLogin = false

    /** Signed out on this panel (HA button / frontend logout) and not signed in again (this process). */
    private var signedOutHere = false

    /** Setup screen preview (`--ez show_setup true`, `--es setup_step …`): never changes state. */
    private var setupPreview = false
    private var setupPreviewStep: String? = null
    private var offlineSince = 0L
    private var lastSetupReasons: List<String> = emptyList()

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
            prefs.url.takeIf { it.isNotEmpty() }?.let { page?.load(it) }
        }
        refreshSetup()
    }

    private val setupSignalListener: () -> Unit = { refreshSetup() }

    /**
     * "Join Home Assistant's Thread network" (auth/ThreadJoin), from the settings menu, the setup
     * screen or Home Assistant's button: the QR code shows whenever an administrator's sign-in is
     * needed; how it ends shows only if the overlay is already up.
     */
    private val threadJoinListener: (sh.edm.s1kiosk.auth.ThreadJoin.State) -> Unit = { s ->
        handler.post { if (!isDestroyed) showThreadJoin(s) }
    }

    private fun showThreadJoin(s: sh.edm.s1kiosk.auth.ThreadJoin.State) {
        val url = sh.edm.s1kiosk.auth.ThreadJoin.url(HaUrls.localIpv4())
        when {
            s is sh.edm.s1kiosk.auth.ThreadJoin.State.NeedsAdmin -> {
                if (settingsOverlay.isShowing) settingsOverlay.hide()
                threadJoinOverlay.render(s, url)
            }
            s is sh.edm.s1kiosk.auth.ThreadJoin.State.Idle -> threadJoinOverlay.hide()
            // How it ended, also when the panel's own sign-in did it (no QR code was up).
            s is sh.edm.s1kiosk.auth.ThreadJoin.State.Joined || s is sh.edm.s1kiosk.auth.ThreadJoin.State.Failed -> {
                if (settingsOverlay.isShowing) settingsOverlay.hide()
                threadJoinOverlay.render(s, url)
            }
            threadJoinOverlay.isShowing -> threadJoinOverlay.render(s, url)
        }
    }

    private val setupTick = object : Runnable {
        override fun run() {
            refreshSetup()
            setupOverlay.tick() // the panes' steps follow their state
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
        glassworks.paneDefinitions(this) // first: a step's one-time check runs before discovery can save an HA URL
        HaAuth.init(this)
        applyIntent(intent, fromNewIntent = false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 8 bits per channel: the dithered backdrop/voice glows would band again on an RGB565 surface.
        window.setFormat(PixelFormat.RGBA_8888)
        if (Platform.hasDisplayCutoutMode) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Bake the dithered backdrop now, before the first frame (the overlays fill this landscape,
        // full-screen window); until it is ready the loading screen draws 8-bit gradients.
        val real = android.graphics.Point().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(it) }
        Backdrop.prewarm(maxOf(real.x, real.y), minOf(real.x, real.y))
        loadingOverlay = LoadingOverlay(this, glassworks.frame.bootRest).apply { onAction = ::openWifiSettings }
        voiceOverlay = VoiceOverlay(this).apply {
            onCancel = { glassworks.voiceCancel?.invoke() }
            onShowingChanged = { timerOverlay.voiceShowing(it) } // a timer set by voice flies out of the orb
        }
        timerOverlay = TimerOverlay(this)
        notificationOverlay = NotificationOverlay(this, glassworks.notifications, notificationImages()).apply {
            screenOn = { glassworks.screen.screenOn }
            onPage = { url -> if (url != null) openInPage(url) else backToDashboard() }
            onDoNotDisturb = { on -> prefs.notifications.doNotDisturb = on }
        }
        setupOverlay = SetupOverlay(this, setupPanes).apply {
            onUsePanel = ::onUsePanelLogin
            onManualUrl = ::onManualHaUrl
            onChooseNetwork = ::openWifiSettings
            onSkipHa = ::onSkipHaSetup
            onClosePreview = ::closeSetupPreview
            onImprovAction = ::onImprovAction
            onPanesDone = { if (!setupPreview) refreshSetup() }
            onStepDone = { refreshSetup() }
        }
        threadJoinOverlay = sh.edm.s1kiosk.setup.ThreadJoinOverlay(this).apply {
            onClose = { HaAuth.threadJoin.disarm(); hide() }
        }
        settingsOverlay = KioskSettingsOverlay(this).apply { onRestart = ::restartProcess }
        // Improv-aware from the start: KioskService (which registers the provider) starts after this.
        wifiStatus = ImprovWifiStatus(this)
        root = android.widget.FrameLayout(this)
        setContentView(root.apply {
            // startSession adds the page at index 0, under everything.
            addView(loadingOverlay, android.widget.FrameLayout.LayoutParams(-1, -1)) // under the setup screen
            addView(setupOverlay, android.widget.FrameLayout.LayoutParams(-1, -1))
            // Z order (elevation): setup 20 dp > voice 16 dp > timers 12 dp > page.
            addView(timerOverlay, timerOverlay.layoutParams())
            addView(notificationOverlay, android.widget.FrameLayout.LayoutParams(-1, -1)) // 14 dp: over timers, under voice
            addView(voiceOverlay, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(settingsOverlay, android.widget.FrameLayout.LayoutParams(-1, -1)) // 24 dp
            addView(threadJoinOverlay, android.widget.FrameLayout.LayoutParams(-1, -1)) // 26 dp: above everything
        })
        // The launch backdrop (res/drawable/launch_backdrop.xml) covers the cold start; once our own
        // views have drawn, a plain colour behind them costs the GPU nothing on every later frame.
        window.decorView.post { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF0A0B0F.toInt())) }
        // Visible from the first frame: the page saying it is drawn ("ready", content.js) or the setup screen replaces it.
        loadingOverlay.show(
            if (hasNetwork()) LoadingOverlay.State.Connecting(LoadingOverlay.CONNECTING, dashboardHost())
            else LoadingOverlay.State.Connecting(OfflineText.CONNECTING_TITLE, OfflineText.CONNECTING_DETAIL, host = false),
        )
        HaAuth.addListener(authListener)
        HaAuth.threadJoin.addListener(threadJoinListener)
        // Recreated while the QR code was up: show it again (posted, so a newer update still wins).
        (HaAuth.threadJoin.state as? sh.edm.s1kiosk.auth.ThreadJoin.State.NeedsAdmin)?.let(threadJoinListener)
        SetupSignals.addListener(setupSignalListener)
        wifiStatus.observe { refreshSetup() }
        ImprovController.inlineHost = improvHost
        KioskService.start(this)
        glassworks.screen.start(this)
        glassworks.screen.onKeepScreenOn = { on ->
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        glassworks.screen.onBrightness = { b ->
            window.attributes = window.attributes.apply {
                screenBrightness = if (b < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else b
            }
        }
        glassworks.notifications.addListener(notificationListener)
        glassworks.notifications.onOpenList = {
            handler.post {
                if (settingsOverlay.isShowing) settingsOverlay.hide()
                notificationOverlay.openList()
            }
        }
        glassworks.notifications.onReveal = { handler.post { if (settingsOverlay.isShowing) settingsOverlay.hide() } }
        glassworks.screen.onStateChanged = {
            pushState()
            timerOverlay.refresh()
            notificationOverlay.update(glassworks.notifications.state)
        }
        glassworks.onVoiceUi = { voiceOverlay.update(glassworks.voiceUi, prefs.voice.overlay) }
        glassworks.commandSink = { applyIntent(it, fromNewIntent = true) }
        startSession()
        intent?.let { i -> handler.post { applySetupPreview(i) } }
        handler.post(setupTick)
        // The direct-boot splash (our look before unlock at the next boot): on only while it's safe.
        Thread({ BootSplashActivity.sync(applicationContext) }, "s1-boot-splash").start()
        requestRuntimePermissions()
        hideSystemUi()
    }

    private fun startSession() {
        val p = glassworks.engine.open(this, pageHost, BACKGROUND) // dark, not white, until the page paints
        root.addView(p.view, 0, android.widget.FrameLayout.LayoutParams(-1, -1))
        page = p
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
        page?.load(externalize(url))
    }

    private var dashboardLoadAt = 0L

    // ---- notifications (#118): the overlay, a view full screen and the way back

    private val notificationListener: (dev.glassworks.notifications.NotificationsHub.State) -> Unit = { s ->
        handler.post { if (::notificationOverlay.isInitialized) notificationOverlay.update(s) }
    }

    /** Where the page was before a notification opened a view full screen. */
    private var beforeNotificationPage: String? = null

    /** A notification's view, on the panel's own HA: in place when the frontend is already up, else loaded. */
    private fun openInPage(url: String) {
        if (beforeNotificationPage == null) beforeNotificationPage = currentUrl
        Log.i(TAG, "Notification: opening a view full screen")
        if (FrontendUrls.sameOrigin(currentUrl, url)) page?.post(navigate(url))
        else page?.load(externalize(url))
    }

    /** The view closed (timeout, Back now, clear_notification): back where the panel was. */
    private fun backToDashboard() {
        val back = beforeNotificationPage ?: return
        beforeNotificationPage = null
        if (FrontendUrls.sameOrigin(currentUrl, back)) page?.post(navigate(back))
        else loadDashboard()
    }

    /** In place, replacing the history entry: dashboard → view → dashboard leaves one entry, not three. */
    private fun navigate(url: String) = JSONObject().put("type", "navigate").put("url", url).put("replace", true)

    /**
     * Pictures from the panel's own HA, with the dashboard's sign-in. Only when that sign-in belongs to
     * the same HA, so the token never goes to another host.
     */
    private fun notificationImages() = dev.glassworks.notifications.HaImages(
        haBase = { prefs.haBase },
        token = token@{
            if (!HaAuth.signedIn || !FrontendUrls.sameOrigin(HaAuth.signedInBase, prefs.haBase)) return@token null
            val done = java.util.concurrent.CountDownLatch(1)
            var token: String? = null
            HaAuth.accessToken(force = false) { a -> token = (a as? HaAuth.Access.Ok)?.accessToken; done.countDown() }
            done.await(10, java.util.concurrent.TimeUnit.SECONDS)
            token
        },
    )

    private fun externalize(url: String): String =
        if (HaAuth.signedIn && FrontendUrls.sameOrigin(url, HaAuth.signedInBase)) FrontendUrls.withFlag(url) else url

    // ---- first-boot setup screen (docs/onboarding.md step 4; rules in setup/SetupState.kt)

    private fun setupInputs(): SetupInputs {
        val w = wifiStatus.snapshot()
        val now = SystemClock.elapsedRealtime()
        if (w.online) offlineSince = 0L else if (offlineSince == 0L) offlineSince = now
        if (w.online && !w.ethernet && !prefs.wifiSeen) prefs.wifiSeen = true
        val steps = paneSteps.map { PaneStepFacts(it.id, it.done(), it.reason, wanted = setupOverlay.wanted(it.id)) }
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
            paneSteps = steps,
            panesChosen = setupOverlay.panesChosen,
            dashboardLoaded = currentUrl.let { u -> u != null && FrontendUrls.sameOrigin(u, prefs.haBase) && !FrontendUrls.isAuthorizePage(u, prefs.haBase) },
            improv = ImprovController.setupFacts,
            installer = ImprovController.installerWifi,
            neverSetUp = SetupState.neverSetUp(
                haKnown = prefs.haBase != null, haSeen = prefs.haSeen || SetupSignals.haSubscribed,
                apiKeySet = prefs.apiEncryption, haSkipped = prefs.haSetupSkipped, paneStepDone = steps.any { it.done },
                signedIn = HaAuth.signedIn,
            ),
        )
    }

    /** Re-evaluates the setup screen; cheap, called on every relevant change and every 2 s. */
    private fun refreshSetup() {
        if (!::setupOverlay.isInitialized) return
        // Panes applying or refused: keep it up until Continue works (a pane's step can look done mid-way).
        // Panes holds while it applies or after a refusal, but never over a lost Wi-Fi: that step comes
        // first, and Panes again once the panel is back on.
        val d = SetupState.decide(setupInputs()).holdingPanes(setupOverlay.holdsPanes)
        if (setupPreview) {
            val forced = setupPreviewStep
            // A forced step: every step before it shows as done.
            val m = if (forced == null) setupModel(d, d.step, d.done, preview = true)
            // A forced step: every step before it shows as done, and so does any step that really is.
            else setupModel(d, forced, previewFlow().takeWhile { it != forced }.toSet() + d.done, preview = true)
            if (m.step == SetupFlow.DONE) {
                // A preview holds Done until it is closed (closeSetupPreview); nothing loads behind it.
                if (setupOverlay.model?.step != SetupFlow.DONE) setupOverlay.finish(m) {}
            } else {
                setupOverlay.render(m)
            }
            syncImprovInline()
            return
        }
        if (d.reasons != lastSetupReasons) {
            if (d.reasons.isNotEmpty()) Log.i(TAG, "Setup screen: ${d.reasons.joinToString()} (step ${d.step})")
            lastSetupReasons = d.reasons
        }
        if (d.show) {
            setupOverlay.render(setupModel(d, d.step, d.done, preview = false))
        } else if (setupOverlay.isShowing && setupOverlay.model?.step != SetupFlow.DONE) {
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

    /** Every step a preview can show: all the panes' steps, as if every pane were on. */
    private fun previewFlow() = SetupFlow.ids(setupPanes.isNotEmpty(), paneSteps.map { it.id })

    private fun setupModel(d: sh.edm.s1kiosk.setup.SetupDecision, step: String, done: Set<String>, preview: Boolean) =
        SetupOverlay.Model(
            step = step,
            done = done,
            improv = d.improv,
            installer = ImprovController.installerWifi,
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

    /** `--ez show_setup true [--es setup_step wifi|ha|panes|signin|done]`, `--ez show_signin true` (= signin). */
    private fun applySetupPreview(intent: Intent) {
        val asked = intent.getStringExtra("setup_step")
        val step = SetupFlow.parse(asked, setupPanes.isNotEmpty(), paneSteps.map { it.id })
        if (asked != null && step == null) Log.e(TAG, "setup_step '$asked' is not a step: ${previewFlow()}")
        when {
            intent.getBooleanExtra("show_signin", false) -> startSetupPreview(SetupFlow.SIGNIN)
            intent.hasExtra("show_setup") && !intent.getBooleanExtra("show_setup", false) -> closeSetupPreview()
            intent.getBooleanExtra("show_setup", false) || step != null -> startSetupPreview(step)
        }
    }

    private fun startSetupPreview(step: String?) {
        Log.i(TAG, "Setup screen preview (step ${step ?: "live"})")
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

    /**
     * The fallback when the page never says it's drawn: hide the connecting screen 15 s after the page
     * loaded, or up to 45 s when Home Assistant's frontend said it is still loading (a cold load on the
     * S1), so its white launch screen doesn't show early.
     */
    private val hideLoading: Runnable = object : Runnable {
        override fun run() {
            val waited = SystemClock.uptimeMillis() - pageStoppedAt
            if (frontendLoading && waited < LAUNCH_FALLBACK_MS) handler.postDelayed(this, LAUNCH_FALLBACK_MS - waited)
            else hideConnecting(if (frontendLoading) "fallback 45 s, the frontend still loading" else "fallback 15 s, no word from the page")
        }
    }

    /** When the current page started loading, for the log. */
    private var pageStartedAt = 0L

    /** Hides the connecting screen, and says why in the log (one line per reason, for panel runs). */
    private fun hideConnecting(reason: String) {
        if (!loadingOverlay.isShowing) return
        val since = if (pageStartedAt > 0) " (${SystemClock.uptimeMillis() - pageStartedAt} ms after the page started)" else ""
        Log.i(TAG, "Connecting screen hidden: $reason$since")
        loadingOverlay.hide()
    }
    /** Home Assistant's launch screen is up on this page (content.js "launch"), and no "ready" yet. */
    private var frontendLoading = false
    /** When the fallback was last armed (first paint or page stop). */
    private var pageStoppedAt = 0L

    /** The host the dashboard loads from, for the connecting screen ("" before one is known). */
    private fun dashboardHost(): String = (currentUrl ?: prefs.url).takeIf { it.startsWith("http") }?.let { Uri.parse(it).host }.orEmpty()

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
        loadingOverlay.show(
            if (t.connecting) LoadingOverlay.State.Connecting(t.title, t.detail, host = false)
            else LoadingOverlay.State.Offline(t.title, t.detail, OfflineText.SETUP_ACTION),
        )
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
        for (i in glassworks.frame.wifiSettingsIntents()) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open ${i.action}: $e")
            }
        }
        Toast.makeText(this, "No Wi-Fi settings on this device", Toast.LENGTH_LONG).show()
    }

    // ---- setup: Improv inline (Wi-Fi step)

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
            prefs.url.takeIf { it.isNotEmpty() }?.let { page?.load(it) }
        }
        refreshSetup()
    }

    private fun onManualHaUrl(raw: String) {
        if (setupPreview) {
            setupOverlay.manualResult(false, "Preview: nothing was saved.")
            return
        }
        val d = glassworks.discovery
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
        if (prefs.secureHosts() != glassworks.engine.started?.secureHosts.orEmpty()) {
            // The HA host must be in dom.securecontext.allowlist (mic), which Gecko reads at startup.
            restartProcess()
            return
        }
        loadDashboard()
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
        if (intent.getBooleanExtra("open_settings", false)) glassworks.frame.openSystemSettings(this)
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
            glassworks.screen.applyPresenceRange()
        }
        // The panes' extras (their on/off, then their own settings): their definitions.
        if (panes.intentExtras().applyFrom(intent)) needsRestart = true
        intent.getStringExtra("api_key")?.let { key ->
            if (KioskPrefs.decodeApiKey(key) != null) prefs.apiKey = key
            else Log.w(TAG, "api_key must be base64 of exactly 32 bytes; ignored")
        }
        if (intent.hasExtra("api_encryption")) prefs.apiEncryption = intent.getBooleanExtra("api_encryption", false)
        if (intent.hasExtra("auto_brightness")) {
            prefs.autoBrightness = intent.getBooleanExtra("auto_brightness", true)
            glassworks.screen.refreshBrightness()
        }
        when (intent.getStringExtra("screen")) {
            "off" -> glassworks.screen.sleep("intent")
            "on" -> glassworks.screen.wake("intent")
        }
        if (intent.getBooleanExtra("remove_admin", false)) glassworks.screen.removeAdmin()
        // The kiosk settings menu: the notification, or `--ez kiosk_settings true` (also from a cold start).
        if (SettingsMenu.requested { k -> if (intent.hasExtra(k)) intent.getBooleanExtra(k, false) else null }) {
            intent.removeExtra(SettingsMenu.EXTRA)
            intent.removeExtra(SettingsMenu.LEGACY_EXTRA) // not again when the activity is recreated
            handler.post { if (::settingsOverlay.isInitialized) showSettings() }
        }
        if (fromNewIntent && (needsRestart || intent.getBooleanExtra("restart", false))) {
            restartProcess()
            return
        }
        if (fromNewIntent && intent.getBooleanExtra("reload", false)) page?.reload()
        // One-off navigation that does NOT change the saved dashboard URL.
        if (fromNewIntent) intent.getStringExtra("open")?.takeIf { it.isNotBlank() }?.let { page?.load(it) }
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
        if (Platform.hasWindowInsetsController) {
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
            glassworks.screen.userActivity()
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

    /** While the settings menu is open: its live views follow what they show (the radio's status, ...). */
    private val settingsTick = object : Runnable {
        override fun run() {
            if (!::settingsOverlay.isInitialized || !settingsOverlay.isShowing) return
            settingsOverlay.refresh()
            handler.postDelayed(this, SETTINGS_TICK_MS)
        }
    }

    /**
     * The kiosk settings menu (settings/KioskSettingsOverlay): 5 taps top-left, or the notification. The
     * core's sections and the panes', in the order every settings surface uses.
     */
    private fun showSettings() {
        // Resolved once per opening: the model is made again on each refresh tick (#235 N4).
        val frame = glassworks.frame
        deviceSettingsLink = if (frame.systemSettings(this) == null) null else SettingsItem.Link(SettingsMenu.AQARA_TITLE, SettingsMenu.AQARA_DETAIL) { c ->
            if (!frame.openSystemSettings(c)) Toast.makeText(c, "No settings app on this panel", Toast.LENGTH_LONG).show()
        }
        settingsOverlay.reload = ::settingsModel
        settingsOverlay.show(settingsModel())
        handler.removeCallbacks(settingsTick)
        handler.postDelayed(settingsTick, 500)
    }

    /** The device's own settings app, resolved when the menu opens ([showSettings]). */
    private var deviceSettingsLink: SettingsItem.Link? = null

    /** The menu's sections as they are now: each pane's page reads its running pane (#212). */
    private fun settingsModel(): KioskSettingsOverlay.Model {
        val core = CoreSettings(
            prefs, BuildConfig.VERSION_NAME,
            status = { glassworks.screen.statusLine() + " · voice=" + glassworks.voiceState },
            deviceSettings = deviceSettingsLink,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) { Licences.show(this) },
            live = CoreSettings.Live(
                fullScreen = ::pushConfig,
                presenceRange = { glassworks.screen.applyPresenceRange() },
                brightness = { glassworks.screen.refreshBrightness() },
            ),
        )
        val paneSections = paneSections(panes, glassworks::runningPane)
        return KioskSettingsOverlay.Model(settingsOrder(core.sections(), paneSections), BuildConfig.VERSION_NAME, paneSections.map { it.id }.toSet())
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
        // The topmost layer only: the menu's own (a picker, a dialog, then the menu), then a setup preview.
        if (::settingsOverlay.isInitialized && settingsOverlay.isShowing) { settingsOverlay.back(); return }
        if (::notificationOverlay.isInitialized && notificationOverlay.back()) return
        if (setupPreview) { closeSetupPreview(); return }
        page?.goBack()
    }

    // ---- Android runtime permissions (mic/camera) for Gecko

    private fun requestRuntimePermissions() {
        val wanted = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQ_STARTUP)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // A new grant can add a foreground type (microphone on Android 14+): KioskService takes it on start.
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) KioskService.start(this)
        if (requestCode == REQ_GECKO) {
            val cb = pendingAndroidPermission
            pendingAndroidPermission = null
            cb?.invoke(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
        }
    }

    // ---- what the page reports (dev.glassworks.engine)

    private val pageHost = object : PageHost {
        override fun onAndroidPermissions(permissions: List<String>, answer: (Boolean) -> Unit) {
            val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                answer(true)
            } else {
                pendingAndroidPermission?.invoke(false)
                pendingAndroidPermission = answer
                requestPermissions(missing.toTypedArray(), REQ_GECKO)
            }
        }

        // Not the end of the connecting screen: Home Assistant's first paint is its own white launch
        // screen. The page says when it's drawn ("ready", content.js); this only arms the fallback.
        override fun onFirstPaint() {
            handler.post {
                handler.removeCallbacks(hideLoading)
                pageStoppedAt = SystemClock.uptimeMillis()
                handler.postDelayed(hideLoading, READY_FALLBACK_MS)
            }
        }

        override fun onContentGone(killed: Boolean) {
            Log.e(TAG, "Content process ${if (killed) "killed" else "crashed"}; restarting session")
            handler.post {
                page?.restart()
                loadDashboard()
            }
        }

        override fun onLocationChange(url: String?) {
            currentUrl = url
            // An unauthenticated HA frontend redirects to /auth/authorize: the setup screen covers it
            // with the QR code (unless the user asked for the panel's own login page).
            handler.post { refreshSetup() }
        }

        // Once signed in, every top-level HA page gets external_auth=1, so the content script
        // installs window.externalApp before the frontend starts (also after reloads and redirects).
        override fun redirect(url: String): String? {
            if (!HaAuth.signedIn) return null
            val base = HaAuth.signedInBase
            return when {
                FrontendUrls.isAuthorizePage(url, base) -> prefs.url.takeIf { it.isNotEmpty() }?.let(::externalize)
                FrontendUrls.needsFlag(url, base) -> FrontendUrls.withFlag(url)
                else -> null
            }
        }

        override fun onPageStart(url: String) {
            handler.removeCallbacks(retryLoad)
            handler.removeCallbacks(hideLoading)
            frontendLoading = false
            pageStartedAt = SystemClock.uptimeMillis()
            offlineShown = false
            if (url.startsWith("http")) loadingOverlay.show(LoadingOverlay.State.Connecting(LoadingOverlay.CONNECTING, Uri.parse(url).host ?: ""))
        }

        override fun onPageStop(success: Boolean) {
            if (success) {
                // In case the page never says it's ready (no web extension, a frontend that fails).
                handler.removeCallbacks(hideLoading)
                pageStoppedAt = SystemClock.uptimeMillis()
                handler.postDelayed(hideLoading, READY_FALLBACK_MS)
            } else {
                if (hasNetwork()) {
                    offlineShown = false
                    loadingOverlay.show(LoadingOverlay.State.Unreachable(dashboardHost(), SystemClock.elapsedRealtime() + RETRY_MS))
                } else {
                    showOffline() // "Connecting to Wi-Fi…" after a reboot, the setup hint when there's nothing to wait for
                }
                // HA restarting / Wi-Fi not up yet after boot: keep retrying.
                handler.removeCallbacks(retryLoad)
                handler.postDelayed(retryLoad, RETRY_MS)
            }
        }

        // ---- page <-> app messaging (assets/extension/content.js)

        override fun onConnect(port: PagePort) {
            port.post(JSONObject().put("type", "state").put("state", stateJson()))
        }

        override fun onPortMessage(port: PagePort, message: JSONObject) = onPortRequest(message, port)
    }

    private fun configJson() = JSONObject()
        .put("hideHeader", prefs.hideHeader)
        .put("hideSidebar", prefs.hideSidebar)

    private fun stateJson() = JSONObject()
        .put("presence", glassworks.screen.near)
        .put("proximityRaw", glassworks.screen.proximityRaw.let { if (it.isNaN()) JSONObject.NULL else it.toDouble() })
        .put("lux", glassworks.screen.lux.let { if (it.isNaN()) JSONObject.NULL else it.toDouble() })
        .put("screenOn", glassworks.screen.screenOn)
        .put("night", glassworks.screen.isNight())
        .put("voice", glassworks.voiceState)

    private fun pushConfig() {
        page?.post(JSONObject().put("type", "config").put("config", configJson()))
    }

    private fun pushState() {
        page?.post(JSONObject().put("type", "state").put("state", stateJson()))
    }

    /**
     * Request/response over the native port (content.js `request()`): `{type, id, ...}` in,
     * `{type: "reply", id, ...}` out. Used instead of sendNativeMessage replies, which don't reach
     * the page in this GeckoView build ("Invalid event data for callback").
     */
    private fun onPortRequest(m: JSONObject, port: PagePort) {
        if (m.optString("type") == "dashboards") { onDashboards(m, port); return }
        if (m.optString("type") == "ready") { onPageReady(port, m.optString("why")); return }
        if (m.optString("type") == "launch") {
            if (port.topLevel) handler.post { frontendLoading = true; Log.i(TAG, "Home Assistant's launch screen is up: waiting for it to go") }
            return
        }
        val id = m.optInt("id", -1)
        if (id < 0) return
        fun reply(o: JSONObject?) {
            val out = o ?: JSONObject().put("success", false)
            out.put("type", "reply").put("id", id)
            handler.post { runCatching { port.post(out) } }
        }
        when (m.optString("type")) {
            "config" -> reply(JSONObject().put("config", configJson()))
            "state" -> reply(JSONObject().put("state", stateJson()))
            "getExternalAuth" -> externalAuth(m, port, ::reply)
            "revokeExternalAuth" -> revokeExternalAuth(port, ::reply)
            else -> reply(null)
        }
    }

    /**
     * `{type: "ready"}` from content.js: the top-level page is drawn (Home Assistant removed its own
     * launch screen), so the connecting screen cross-fades into it and the frontend's white splash never
     * shows (#63 round 2). A frame's word doesn't count.
     */
    private fun onPageReady(sender: PagePort, why: String) {
        if (!sender.topLevel) { Log.i(TAG, "A frame said ready ($why): ignored"); return }
        handler.post {
            frontendLoading = false
            handler.removeCallbacks(hideLoading)
            hideConnecting("ready, ${why.ifEmpty { "page" }}")
        }
    }

    /**
     * `{type: "dashboards", json: "[{url_path,title}]"}` from the HA frontend (content.js, the page's
     * own `lovelace/dashboards/list`), for the ESPHome "Dashboard" select. Only the top-level page on
     * the Home Assistant the panel uses is believed.
     */
    private fun onDashboards(m: JSONObject, sender: PagePort) {
        if (!sender.topLevel || !FrontendUrls.sameOrigin(sender.url, prefs.haBase)) {
            Log.w(TAG, "dashboard list from ${sender.url} ignored (not the Home Assistant in use)")
            return
        }
        val list = DashboardOptions.parse(m.optString("json")) ?: return
        Log.d(TAG, "${list.size} dashboard(s) from the frontend")
        Dashboards.report(list)
    }

    /** Only the signed-in HA origin may ask for tokens. */
    private fun senderAllowed(sender: PagePort): Boolean =
        sender.topLevel && FrontendUrls.sameOrigin(sender.url, HaAuth.signedInBase)

    /** `externalApp.getExternalAuth` → `{success, access_token, expires_in}` (never logged). */
    private fun externalAuth(msg: JSONObject, sender: PagePort, reply: (JSONObject) -> Unit) {
        if (!senderAllowed(sender)) {
            Log.w(TAG, "getExternalAuth from ${sender.url} refused (not the signed-in Home Assistant)")
            reply(JSONObject().put("success", false))
            return
        }
        HaAuth.accessToken(force = msg.optBoolean("force")) { a ->
            val r = when (a) {
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
            Log.d(TAG, "getExternalAuth -> success=${r.optBoolean("success")}")
            reply(r)
        }
    }

    /** `externalApp.revokeExternalAuth`: the user logged out in the HA frontend. */
    private fun revokeExternalAuth(sender: PagePort, reply: (JSONObject) -> Unit) {
        if (!senderAllowed(sender)) { reply(JSONObject().put("success", false)); return }
        HaAuth.signOut("logged out in the frontend") { reply(JSONObject().put("success", true)) }
    }

    override fun onDestroy() {
        HaAuth.removeListener(authListener)
        glassworks.notifications.removeListener(notificationListener)
        glassworks.notifications.onOpenList = null
        glassworks.notifications.onReveal = null
        HaAuth.threadJoin.removeListener(threadJoinListener)
        SetupSignals.removeListener(setupSignalListener)
        wifiStatus.observe(null)
        if (ImprovController.inlineHost === improvHost) {
            lastImprovInline = false
            ImprovController.inlineHost = null // the overlay takes over if Improv is still running
        }
        glassworks.screen.onKeepScreenOn = null
        glassworks.screen.onBrightness = null
        glassworks.screen.onStateChanged = null
        glassworks.onVoiceUi = null
        glassworks.commandSink = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** Onboarding/dashboard background (#0a0b0f), also used to cover the page until first paint. */
        private const val BACKGROUND = 0xFF0A0B0F.toInt()
        private const val TAG = "S1Kiosk"
        private const val REQ_STARTUP = 1
        private const val REQ_GECKO = 2
        private const val RETRY_MS = 10_000L
        /** The connecting screen waits this long after the page loaded for the page to say it's drawn. */
        internal const val READY_FALLBACK_MS = 15_000L
        /** ... and this long while Home Assistant's frontend says it is still loading. */
        internal const val LAUNCH_FALLBACK_MS = 45_000L
        private const val SETUP_TICK_MS = 2_000L
        private const val SETTINGS_TICK_MS = 2_000L
    }
}
