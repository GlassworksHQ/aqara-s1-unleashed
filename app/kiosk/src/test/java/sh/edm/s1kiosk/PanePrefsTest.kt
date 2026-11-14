package sh.edm.s1kiosk

import android.app.Application
import android.content.SharedPreferences
import dev.glassworks.bluetoothproxy.BluetoothProxyPane
import dev.glassworks.bluetoothproxy.BluetoothProxyPrefs
import dev.glassworks.bluetoothproxy.ProxyRadio
import dev.glassworks.camera.CameraPrefs
import dev.glassworks.diagnostics.DiagnosticsPrefs
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.bluetoothproxy.BleScanner
import sh.edm.bluetoothproxy.ScanFailure
import sh.edm.bluetoothproxy.ScanPower
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.NumberEntity
import sh.edm.esphome.ProtoMessage
import sh.edm.esphome.ProtoWriter
import sh.edm.esphome.SwitchEntity
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.base.WallBase
import sh.edm.s1kiosk.base.WallBasePane
import sh.edm.s1kiosk.base.WallBasePrefs
import sh.edm.s1kiosk.esphome.UrlPlayer
import sh.edm.s1kiosk.frame.AqaraCloud
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.frame.TimeZonePrefs
import sh.edm.s1kiosk.radio.RadioPane
import sh.edm.s1kiosk.radio.RadioPrefs
import sh.edm.s1kiosk.sendspin.SendspinPane
import sh.edm.s1kiosk.sendspin.SendspinPrefs
import sh.edm.s1kiosk.timers.TimersPane
import sh.edm.s1kiosk.voice.AssistPane
import sh.edm.s1kiosk.voice.SpeakerMedia
import sh.edm.s1kiosk.voice.VoicePrefs

/**
 * Each pane reaches its settings only through `PaneContext.prefs(its id)` and its own prefs class,
 * and no key has two owners. [PrefsPinTest] pins the keys and files themselves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanePrefsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val kiosk = KioskPrefs(app)
    private val device = Executors.newSingleThreadScheduledExecutor()
    private val built = ArrayList<Pane>()

    /** The prefs class of each namespace, built over a file. */
    private val owners: Map<String, (SharedPreferences) -> Any> = mapOf(
        "sendspin" to ::SendspinPrefs, "assist" to ::VoicePrefs, "wall-base" to ::WallBasePrefs, "radio" to ::RadioPrefs,
        "time-zone" to ::TimeZonePrefs, "diagnostics" to ::DiagnosticsPrefs, "bluetooth-proxy" to ::BluetoothProxyPrefs, "camera" to ::CameraPrefs,
    )

    @After fun tearDown() {
        built.forEach { it.stop() }
        device.shutdownNow()
    }

    @Test fun everyNamespaceHasAPrefsClassAndAFile() {
        assertEquals(KioskPrefs.PANE_FILES.keys, owners.keys)
    }

    @Test fun anUnknownNamespaceIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { kiosk.paneFile("music") }
    }

    @Test fun noKeyHasTwoOwners() {
        val keys = owners.mapValues { (ns, make) -> ownedKeys(ns, make) }
        val all = keys.values.flatten()
        assertEquals("keys owned by two panes", all.toSet().size, all.size)
        keys.forEach { (ns, k) -> assertTrue("$ns owns no keys", k.isNotEmpty()) }
    }

    @Test fun eachPaneUsesOnlyItsOwnNamespaceAndKeys() {
        kiosk.paneFile("sendspin").edit().putBoolean(SendspinPrefs.KEY_ENABLED, false).commit()
        for (pane in panes()) {
            val ns = pane.id
            val asked = HashMap(asked)
            // Timers have no settings; the Bluetooth proxy's one key, its on/off, is the core's (PaneSwitch).
            if (ns == "timers" || ns == "bluetooth-proxy") {
                assertTrue("$ns asked for settings: ${asked.keys}", asked.isEmpty())
            } else {
                assertEquals("${pane.id} asked for other namespaces", setOf(ns), asked.keys)
                assertTrue("${pane.id} read no settings: the recorder sees nothing", asked.getValue(ns).isNotEmpty())
                val foreign = asked.getValue(ns) - ownedKeys(ns, owners.getValue(ns))
                assertTrue("${pane.id} touched keys it does not own: $foreign", foreign.isEmpty())
            }
            this.asked.clear()
        }
    }

    /** What each pane asked for since the last clear: namespace -> keys read or written. */
    private val asked = HashMap<String, MutableSet<String>>()

    private val ctx = PaneContext(
        app,
        prefs = { ns -> recording(kiosk.paneFile(ns), asked.getOrPut(ns) { sortedSetOf() }) },
        DeviceIdentity(
            name = "test", friendlyName = "Test", macAddress = "02:00:00:00:00:01", esphomeVersion = "x",
            compilationTime = "x", manufacturer = "x", model = "x", voiceFeatures = 0,
        ),
        publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
    )

    /** Builds each pane and drives it through what reads and writes settings; yields it before the next. */
    private fun panes() = sequence {
        yield(drive(SendspinPane(ctx, haBase = { null }), SendspinPrefs.KEY_FLAC))
        yield(drive(assist(), VoicePrefs.KEY_MIC_GAIN, start = false))
        yield(drive(WallBasePane(ctx, WallBase.Config(BaseBackend.LOCALSERVICE), device, AqaraCloud()), start = false))
        yield(drive(RadioPane(ctx, GlassworksRuntime(AqaraS1Plus()), panelIp = { null }), start = false))
        yield(drive(TimersPane(ctx, GlassworksRuntime(AqaraS1Plus()).screen, schedule = { _, _ -> device.submit {} }, play = {}, silence = {})))
        yield(drive(BluetoothProxyPane(ctx, NoRadio, device)))
    }

    /** No Bluetooth to scan with: the pane runs its lifecycle and entities, and reads what it reads. */
    private object NoRadio : ProxyRadio {
        override fun newScanner() = object : BleScanner {
            override fun start(power: ScanPower, listener: BleScanner.Listener) {}
            override fun stop() {}
        }
        override fun unavailable(): ScanFailure? = null
        override fun turnBluetoothOn() = false
        override fun turnLocationOn() = false
    }

    private fun assist() = AssistPane(
        ctx,
        player = object : UrlPlayer {
            override fun play(urls: List<String>, onDone: (Boolean) -> Unit) {}
            override fun stop() {}
        },
        speaker = object : SpeakerMedia {
            override val playing = false
            override val announcing = false
            override fun pause() {}
            override fun resume() {}
        },
        resolveUrl = { it }, wakeChime = {}, onVoiceEvent = {},
        createInput = { _, _, _ -> error("not started here") },
    )

    private fun drive(pane: Pane, changed: String? = null, start: Boolean = true): Pane {
        built += pane
        for (e in pane.entities()) {
            e.state()
            when (e) {
                is SwitchEntity -> e.command(ProtoMessage(ProtoWriter().bool(2, false).toByteArray()))
                is NumberEntity -> e.command(ProtoMessage(ProtoWriter().float(2, 1f).toByteArray()))
            }
        }
        if (start) pane.start()
        changed?.let { pane.onEvent(PanelEvent.SettingChanged(it)) }
        return pane
    }

    /** The keys [make]'s class reads or writes when every getter and setter runs. */
    private fun ownedKeys(ns: String, make: (SharedPreferences) -> Any): Set<String> {
        val keys = sortedSetOf<String>()
        val obj = make(recording(kiosk.paneFile(ns), keys))
        for (m in obj.javaClass.methods) {
            if (m.declaringClass == Any::class.java) continue
            when {
                m.parameterCount == 0 -> m.invoke(obj)
                m.name.startsWith("set") && m.parameterCount == 1 -> SAMPLES[m.parameterTypes[0]]?.let { m.invoke(obj, it) }
            }
        }
        return keys
    }

    /** [real], noting in [keys] every key read, written or removed. */
    private fun recording(real: SharedPreferences, keys: MutableSet<String>): SharedPreferences =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, m, args ->
            val a = args ?: emptyArray()
            if (m.name == "edit") return@newProxyInstance recordingEditor(real.edit(), keys)
            if (m.name == "contains" || (m.name.startsWith("get") && m.name != "getAll")) keys += a[0] as String
            m.invoke(real, *a)
        } as SharedPreferences

    private fun recordingEditor(real: SharedPreferences.Editor, keys: MutableSet<String>): SharedPreferences.Editor {
        lateinit var self: SharedPreferences.Editor
        self = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, m, args ->
            val a = args ?: emptyArray()
            if (m.name == "remove" || m.name.startsWith("put")) keys += a[0] as String
            val r = m.invoke(real, *a)
            if (r === real) self else r
        } as SharedPreferences.Editor
        return self
    }

    /** The packages of the panes: none of them may reach the core's settings. */
    @Test fun panePackagesDoNotUseTheCoreFacade() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
        val src = File(root, "app/kiosk/src/main/java/sh/edm/s1kiosk")
        val users = listOf("sendspin", "voice", "base", "radio", "timers")
            .flatMap { File(src, it).walk().filter { f -> f.extension == "kt" }.toList() }
            .filter { it.readText().contains("KioskPrefs") }
            .map { it.relativeTo(src).path }
        assertTrue("pane code uses KioskPrefs: $users", users.isEmpty())
    }

    private companion object {
        val SAMPLES: Map<Class<*>, Any> = mapOf(
            java.lang.Boolean.TYPE to true, Integer.TYPE to 7, java.lang.Float.TYPE to 0.5f, String::class.java to "pin",
        )
    }
}
