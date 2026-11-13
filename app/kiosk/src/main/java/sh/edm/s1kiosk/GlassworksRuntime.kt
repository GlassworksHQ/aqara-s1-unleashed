package sh.edm.s1kiosk

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.frame.Frame
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SetupStep
import dev.glassworks.screen.ScreenPolicy
import sh.edm.s1kiosk.discovery.HaDiscovery
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.radio.RadioController
import sh.edm.s1kiosk.radio.RadioDefinition
import sh.edm.s1kiosk.sendspin.SendspinDefinition
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupState
import sh.edm.s1kiosk.update.UpdateManager
import sh.edm.s1kiosk.voice.AssistDefinition
import sh.edm.s1kiosk.voice.VoiceUi

/**
 * What the app's components hand each other while the process runs. [KioskApp] owns the one
 * instance; Android entry points get it with [glassworks], everything else is given it.
 */
class GlassworksRuntime(
    /** The device this app runs on, made once with the runtime. */
    val frame: AqaraS1Plus,
) {
    /** The screen's on/off and brightness, from the frame's sensors. MainActivity and KioskService start it. */
    val screen = ScreenPolicy(
        frame.presence, frame.light, AdminReceiver::class.java, settings = ::KioskPrefs,
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
     * Every pane's definition, made once: their setup steps, settings sections and adb extras. The
     * core's panes in the order the settings list them (voice, music, radio), then the frame's.
     * MainActivity and KioskService ask for them first thing, before discovery can save an HA URL in
     * this process (the radio step's one-time check needs that).
     */
    fun paneDefinitions(context: Context): List<PaneDefinition> = synchronized(this) {
        definitions ?: paneDefinitions(context, frame).also { definitions = it }
    }

    /** The core's definitions, then [frame]'s. Made anew on each call: [paneDefinitions] keeps the app's. Tests pass a fake frame. */
    internal fun paneDefinitions(context: Context, frame: Frame): List<PaneDefinition> {
        val app = context.applicationContext
        val prefs = KioskPrefs(app)
        val radio = RadioDefinition(
            prefs.radio,
            setUpBefore = { SetupState.setUpBefore(prefs.haBase != null, prefs.haSeen, prefs.apiEncryption, prefs.haSetupSkipped) },
            readout = RadioDefinition.AppReadout(this, prefs.radio) { prefs.haBase },
        )
        return listOf(AssistDefinition(prefs.voice), SendspinDefinition(prefs.sendspin), radio) + frame.paneDefinitions(app)
    }

    /**
     * The panes' setup steps, in order, for the setup screen and the setup channel. A step setup can't
     * take (SetupFlow.usable: a clashing id or request name) is left out with an error in the log, so a
     * bad pane never stops the app from starting. Tests pass [definitions] with a fake frame's.
     */
    fun setupSteps(context: Context, definitions: List<PaneDefinition> = paneDefinitions(context)): List<SetupStep> =
        SetupFlow.usable(definitions.flatMap { it.setupSteps }) { Log.e(TAG, it) }

    /** Set while KioskService runs; the radio's setup step and settings section apply radio changes through it. */
    @Volatile var radio: RadioController? = null

    /** Set while KioskService runs; the sign-in screen uses it for manual HA URL entry. */
    @Volatile var discovery: HaDiscovery? = null

    /**
     * Applies an intent's extras in the running activity (main thread), like onNewIntent. Used by the
     * ESPHome device so Home Assistant can drive the kiosk without starting activities from the background.
     */
    @Volatile var commandSink: ((Intent) -> Unit)? = null

    /** The running update manager, for UpdateInstallReceiver. */
    @Volatile var updates: UpdateManager? = null

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
