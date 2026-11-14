package sh.edm.s1kiosk.settings

import android.app.Application
import dev.glassworks.diagnostics.CpuTimes
import dev.glassworks.diagnostics.DiagnosticsPane
import dev.glassworks.diagnostics.Probe
import dev.glassworks.diagnostics.Sample
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.esphome.DeviceIdentity
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.base.BaseRes
import sh.edm.s1kiosk.base.WallBase
import sh.edm.s1kiosk.base.WallBasePane
import sh.edm.s1kiosk.frame.AqaraCloud
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.ui.RadioFollowUp
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.radio.radioDefinition
import sh.edm.s1kiosk.ui.Mockup
import java.util.concurrent.Executors
import android.os.SystemClock
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Every pane's settings page (#212) at the panel's size, from the S1's own definitions and running panes.
 * Illustrative values only: made-up names and RFC 5737 addresses.
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*PanePagesMockups*' -Ps1MockupDir=<dir>
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PanePagesMockups {
    private val app = RuntimeEnvironment.getApplication()
    private val held = ArrayList<java.util.concurrent.ExecutorService>()

    @Before fun interFromSources() = Mockup.interFromSources()
    @After fun shutdown() { held.forEach { it.shutdownNow() } }

    private val ctx = PaneContext(
        app, KioskPrefs(app)::paneFile,
        DeviceIdentity("s1-panel", "Glassworks Panel", "02:00:00:00:00:01", "2026.10.2", "x", "Aqara", "Panel Hub S1 Plus", 0),
        publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
    )

    private val coordinator = RadioFollowUp.Facts(
        RadioMode.ZIGBEE_TCP, "ok Zigbee coordinator (TCP): listening on :6638 for Home Assistant (192.0.2.20)",
        HelperFacts(true, 4), ZigbeeStack.AQARA, null, null, "http://homeassistant.local:8123", "192.0.2.10",
    )

    /** An hour of made-up readings, a minute apart, ending at [now]. */
    private fun diagnostics(): DiagnosticsPane {
        val now = (3L * 24 + 4) * 3_600_000L
        val samples = ArrayDeque((0 until 60).map { n ->
            val t = now - (59 - n) * 60_000L
            val wave = kotlin.math.sin(n / 5.0).toFloat()
            Sample(
                elapsedMs = t, wallMs = 1_762_765_920_000L + t, cpu = CpuTimes(n * 6000L, n * 6000L - n * 1300L - (wave * 300).toLong()),
                appTicks = mapOf(1 to n * 330L), ticksPerSecond = 100, cpus = 4, contentProcesses = 3,
                cpuTemperature = 50f + wave * 1.4f + n / 60f, memoryAvailableBytes = (700L - n * 1.5f.toLong() - (wave * 20).toLong()) shl 20,
                appPssBytes = 214L shl 20, storageFreeBytes = 4_424_000_000L, wifiRssi = -58 + (wave * 2).toInt(), wifiLinkSpeed = 72,
                appStartElapsedMs = now - (2 * 60 + 10) * 60_000L, storageTotalBytes = 6_013_000_000L,
            )
        })
        val probe = object : Probe {
            override fun sample() = samples.removeFirst()
            override fun lastExitReason() = android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED
        }
        ShadowSystemClock.advanceBy(Duration.ofMillis(now - SystemClock.elapsedRealtime()))
        // Started on a device thread that never gets to run: start() reads the restart reason, the ticks are ours.
        val device = Executors.newSingleThreadScheduledExecutor().also { held += it; it.execute { Thread.sleep(Long.MAX_VALUE) } }
        return DiagnosticsPane(ctx, true, device, probe).also { p -> p.start(); repeat(60) { p.tick() } }
    }

    /** A three-channel switch base, as the tracker hears it, with relay 1 on and button 1 just pressed; or none. */
    private fun wallBase(attached: Boolean = true): WallBasePane {
        KioskPrefs(app).wallBase.layout = ""
        val device = Executors.newSingleThreadScheduledExecutor().also { held += it }
        val p = WallBasePane(ctx, WallBase.Config(BaseBackend.UART), device, AqaraCloud())
        p.tracker.apply(if (!attached) mapOf(BaseRes.DOCK_CLASS to 0) else mapOf(
            BaseRes.DOCK_CLASS to 0x0100F110, BaseRes.CHANNEL_COUNT to 3, BaseRes.RELAY_MASK to 7,
            BaseRes.relay(1) to 1, BaseRes.relay(2) to 0, BaseRes.relay(3) to 0,
            BaseRes.FIRMWARE to "0.0.0_0034", BaseRes.POWER to 12.4f, BaseRes.PROTECTION to 0,
        ), 0)
        p.baseListener.onLayout(p.tracker.current()!!)
        if (attached) p.baseListener.onButton(1, "double")
        return p
    }

    private fun menu(running: List<Pane>): KioskSettingsOverlay {
        val o = Mockup.show(KioskSettingsOverlay(Mockup.activity()))
        KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel" }
        val radio = radioDefinition(app, FixedReadout(coordinator, running = true, allowedNow = "192.0.2.20"))
        val definitions = GlassworksRuntime(AqaraS1Plus()).paneDefinitions(app).map { if (it.id == "radio") radio else it }
        val byId = running.associateBy { it.id }
        val model = { MenuFixture.model(app, radio, status = "screen on · presence near · voice=idle", panes = definitions, running = { byId[it] }) }
        o.reload = model
        o.show(model())
        Mockup.idle()
        return o
    }

    private fun shot(name: String, o: KioskSettingsOverlay, id: String, then: (KioskSettingsOverlay) -> Unit = {}) {
        o.previewSection(id)
        then(o)
        Mockup.idle()
        Mockup.layout(o)
        Mockup.save(name, o)
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", Mockup.dir != null)
        shot("212-01-diagnostics", menu(listOf(diagnostics())), "diagnostics")
        shot("212-02-wall-base", menu(listOf(wallBase())), "wall-base")
        shot("212-03-wall-base-none", menu(listOf(wallBase(attached = false))), "wall-base")
    }
}
