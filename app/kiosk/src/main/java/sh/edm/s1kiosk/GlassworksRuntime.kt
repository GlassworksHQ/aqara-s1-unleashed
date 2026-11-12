package sh.edm.s1kiosk

import android.content.Context
import android.content.Intent
import dev.glassworks.engine.Engine
import dev.glassworks.engine.EngineSettings
import dev.glassworks.engine.gecko.GeckoEngine
import dev.glassworks.screen.ScreenPolicy
import sh.edm.s1kiosk.discovery.HaDiscovery
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.radio.RadioController
import sh.edm.s1kiosk.update.UpdateManager
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

    /** Set while KioskService runs; the setup screen and the setup channel apply radio changes through it. */
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
}

val Context.glassworks: GlassworksRuntime get() = (applicationContext as KioskApp).glassworks
