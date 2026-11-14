package sh.edm.s1kiosk

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dev.glassworks.bluetoothproxy.AndroidProxyRadio
import dev.glassworks.bluetoothproxy.BluetoothProxyDefinition
import dev.glassworks.camera.CameraDefinition
import dev.glassworks.diagnostics.DiagnosticsDefinition
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.frame.Frame
import dev.glassworks.manage.PanelCode
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SetupStep
import dev.glassworks.screen.PresenceRelay
import dev.glassworks.screen.ScreenPolicy
import sh.edm.s1kiosk.discovery.HaDiscovery
import sh.edm.s1kiosk.esphome.PanelDevice
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.radio.RadioController
import sh.edm.s1kiosk.radio.RadioDefinition
import sh.edm.s1kiosk.sendspin.SendspinDefinition
import sh.edm.s1kiosk.settings.BluetoothProxyPage
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupPane
import sh.edm.s1kiosk.setup.SetupState
import sh.edm.s1kiosk.update.UpdateManager
import sh.edm.s1kiosk.voice.AssistDefinition
import sh.edm.s1kiosk.voice.VoiceUi
import java.util.concurrent.Executors

/**
 * What the app's components hand each other while the process runs. [KioskApp] owns the one
 * instance; Android entry points get it with [glassworks], everything else is given it.
 */
class GlassworksRuntime(
    /** The device this app runs on, made once with the runtime. */
    val frame: AqaraS1Plus,
    /** The frame whose panes join the core's ([paneDefinitions]): [frame] in the app. Tests pass a fake one. */
    private val paneFrame: Frame = frame,
) {
    /** The screen's on/off and brightness, from the frame's sensors. MainActivity and KioskService start it. */
    /** The frame's presence sensor, plus the camera pane's readings on a device without one (#117). */
    val presence = PresenceRelay(frame.presence)

    val screen = ScreenPolicy(
        presence, frame.light, AdminReceiver::class.java, settings = ::KioskPrefs,
        logTag = "S1Kiosk.Screen", wakeLockTag = "s1kiosk:wake",
    )

    /**
     * The web engine that shows the dashboard: GeckoView, with the kiosk's helper extension
     * (assets/extension). Its settings are read once, when MainActivity first opens a page.
     */
    val engine: Engine = GeckoEngine(
        settings = { context -> KioskPrefs(context).run { EngineSettings(secureHosts(), zoom, remoteDebugging) } },
        extension = GeckoEngine.Extension("resource://android/assets/extension/", "helper@s1kiosk.edm.sh", nativeApp = "s1kiosk"),
        logTag = "S1Kiosk",
        generatedBy = "S1 Kiosk",
    )

    private var definitions: List<PaneDefinition>? = null

    /**
     * One thread for the settings pages' work that must not run on the main thread and must keep its order
     * (Music's volume, sent to Music Assistant). Made on first use; idle, it is one parked daemon thread.
     */
    private val settingsWorker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "s1-settings").apply { isDaemon = true } } }

    /**
     * Every pane's definition, made once: their on/off, setup steps, settings sections and adb extras. The
     * core's panes in the order the settings list them (voice, music, radio, Bluetooth proxy), then the frame's, then diagnostics.
     * MainActivity and KioskService ask for them first thing, before discovery can save an HA URL in
     * this process (the radio step's one-time check needs that).
     */
    fun paneDefinitions(context: Context): List<PaneDefinition> = synchronized(this) {
        definitions ?: run {
            val app = context.applicationContext
            val prefs = KioskPrefs(app)
            val radio = RadioDefinition(
                prefs.radio,
                setUpBefore = { SetupState.setUpBefore(prefs.haBase != null, prefs.haSeen, prefs.apiEncryption, prefs.haSetupSkipped) },
                readout = RadioDefinition.AppReadout(this, prefs.radio) { prefs.haBase },
            )
            val camera = CameraDefinition(prefs.paneFile("camera"), app.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
            val bluetoothProxy = BluetoothProxyDefinition(prefs.paneFile("bluetooth-proxy"), AndroidProxyRadio(app), ::BluetoothProxyPage)
            listOf(AssistDefinition(prefs.paneFile("assist")), SendspinDefinition(prefs.paneFile("sendspin"), settingsWorker::execute), radio, bluetoothProxy) +
                paneFrame.paneDefinitions(app) + camera + DiagnosticsDefinition(prefs.paneFile("diagnostics"))
        }.also { definitions = it }
    }

    /**
     * The panes' setup steps, in order, for the setup screen and the setup channel. The only place that
     * reads PaneDefinition.setupSteps (FramePanesTest checks). A step setup can't take
     * (SetupFlow.usable: a clashing id or request name) is left out with an error in the log, so a bad
     * pane never stops the app from starting.
     */
    fun setupSteps(context: Context): List<SetupStep> = setupPanes(context).flatMap { it.steps }

    /**
     * The panes on setup's Panes step, in definition order: each card with its pane's usable steps
     * (SetupFlow.usable). A pane whose steps have no card (PaneDefinition.setupChoice) is left out with an
     * error in the log: its steps sit under Panes only while its card is on, so without a card they could
     * never be answered on the panel.
     */
    fun setupPanes(context: Context): List<SetupPane> {
        val definitions = paneDefinitions(context)
        val kept = SetupFlow.usable(definitions.flatMap { it.setupSteps }) { Log.e(TAG, it) }.toSet()
        return definitions.mapNotNull { d ->
            val steps = d.setupSteps.filter { it in kept }
            val card = d.setupChoice
            if (card == null) {
                if (steps.isNotEmpty()) Log.e(TAG, "Setup steps of pane '${d.id}' left out: it has no card on the Panes step (setupChoice)")
                null
            } else SetupPane(card, steps)
        }
    }

    /**
     * Whether camera frames are leaving the panel now (a still to Home Assistant, a stream), for the
     * privacy indicator (#117). Set by the camera pane; [onCameraLeaving] hears each change.
     */
    @Volatile var cameraLeaving = false
    @Volatile var onCameraLeaving: (() -> Unit)? = null

    /** Set while KioskService runs; the radio's setup step and settings section apply radio changes through it. */
    @Volatile var radio: RadioController? = null

    /**
     * The panes the device shell built, also the ones that are off; empty while it isn't running. Each
     * pane's settings page reads its own (#212).
     */
    @Volatile var panes: List<Pane> = emptyList()

    fun runningPane(id: String): Pane? = panes.firstOrNull { it.id == id }

    /** Set while KioskService runs; the sign-in screen uses it for manual HA URL entry. */
    @Volatile var discovery: HaDiscovery? = null

    /**
     * Applies an intent's extras in the running activity (main thread), like onNewIntent. Used by the
     * ESPHome device so Home Assistant can drive the kiosk without starting activities from the background.
     */
    @Volatile var commandSink: ((Intent) -> Unit)? = null

    /** The running update manager, for UpdateInstallReceiver. */
    @Volatile var updates: UpdateManager? = null

    /** Set while KioskService runs: the ESPHome device, for the Manage page's status. */
    @Volatile var device: PanelDevice? = null

    /** The code the panel shows for the Manage page (sign-in without HA, dangerous actions). KioskService draws it. */
    val panelCode = PanelCode()

    /** Last voice assistant state, exposed to the page via MainActivity.stateJson(). */
    @Volatile var voiceState: String = "off"

    /** What the voice overlay shows (state, heard text, reply). Main thread. */
    var voiceUi = VoiceUi()

    /** Called on the main thread after every voice event (the activity's voice overlay). */
    var onVoiceUi: (() -> Unit)? = null

    /** Cancels the current voice run (stops streaming and playback); null while the service isn't running. */
    @Volatile var voiceCancel: (() -> Unit)? = null

    private companion object {
        const val TAG = "S1Kiosk"
    }
}

val Context.glassworks: GlassworksRuntime get() = (applicationContext as KioskApp).glassworks
