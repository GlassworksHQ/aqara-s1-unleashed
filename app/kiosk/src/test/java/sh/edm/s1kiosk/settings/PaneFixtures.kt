package sh.edm.s1kiosk.settings

import android.app.Application
import dev.glassworks.diagnostics.CpuTimes
import dev.glassworks.diagnostics.DiagnosticsPane
import dev.glassworks.diagnostics.Probe
import dev.glassworks.diagnostics.Sample
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.TextSensorEntity
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.base.BaseRes
import sh.edm.s1kiosk.base.WallBase
import sh.edm.s1kiosk.base.WallBasePane
import sh.edm.s1kiosk.frame.AqaraCloud
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.frame.TimeZoneKeeper
import sh.edm.s1kiosk.frame.TimeZonePane
import sh.edm.s1kiosk.frame.TimeZonePrefs
import sh.edm.s1kiosk.radio.SysProps
import dev.glassworks.pane.PanelEvent
import dev.glassworks.pane.PanelTimer
import sh.edm.s1kiosk.sendspin.SendspinPane
import sh.edm.s1kiosk.sendspin.SendspinSession
import sh.edm.s1kiosk.esphome.UrlPlayer
import sh.edm.s1kiosk.voice.AssistPane
import sh.edm.s1kiosk.voice.SpeakerMedia
import java.util.TimeZone
import sh.edm.s1kiosk.radio.FixedReadout
import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.ui.RadioFollowUp
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ZigbeeStack
import sh.edm.s1kiosk.radio.radioDefinition
import java.util.concurrent.Executors
import android.os.SystemClock
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The S1's panes running with illustrative values, and the settings model the panel's menu and the Manage
 * page draw from them (#212, #242): made-up names and RFC 5737 addresses. Call [shutdown] after.
 */
class PaneFixtures(private val app: Application) {
    private val held = ArrayList<java.util.concurrent.ExecutorService>()

    fun shutdown() = held.forEach { it.shutdownNow() }

    private val ctx = PaneContext(
        app, KioskPrefs(app)::paneFile,
        DeviceIdentity("s1-panel", "Glassworks Panel", "02:00:00:00:00:01", "2026.10.2", "x", "Aqara", "Panel Hub S1 Plus", 0),
        publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
    )

    val coordinator = RadioFollowUp.Facts(
        RadioMode.ZIGBEE_TCP, "ok Zigbee coordinator (TCP): listening on :6638 for Home Assistant (192.0.2.20)",
        HelperFacts(true, 4), ZigbeeStack.AQARA, null, null, "http://homeassistant.local:8123", "192.0.2.10",
    )

    /** An hour of made-up readings, a minute apart, ending at [now]. */
    fun diagnostics(): DiagnosticsPane {
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
    fun wallBase(attached: Boolean = true): WallBasePane {
        KioskPrefs(app).wallBase.layout = ""
        val device = Executors.newSingleThreadScheduledExecutor().also { held += it }
        // No getprop or setprop on the host: Aqara's daemon reads as stopped, as on a panel with the UART base.
        val p = WallBasePane(ctx, WallBase.Config(BaseBackend.UART), device, AqaraCloud { cmd -> if (cmd.firstOrNull() == "getprop") "stopped" else "" })
        p.tracker.apply(if (!attached) mapOf(BaseRes.DOCK_CLASS to 0) else mapOf(
            BaseRes.DOCK_CLASS to 0x0100F110, BaseRes.CHANNEL_COUNT to 3, BaseRes.RELAY_MASK to 7,
            BaseRes.relay(1) to 1, BaseRes.relay(2) to 0, BaseRes.relay(3) to 0,
            BaseRes.FIRMWARE to "0.0.0_0034", BaseRes.POWER to 12.4f, BaseRes.PROTECTION to 0,
        ), 0)
        p.baseListener.onLayout(p.tracker.current()!!)
        if (attached) p.baseListener.onButton(1, "double")
        return p
    }

    /** Europe/Madrid, kept by the root helper; Home Assistant's clock as the POSIX rule it sends ([haRule]). */
    fun timeZone(haRule: String = "CET-1CEST,M3.5.0,M10.5.0/3"): TimeZonePane {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
        val props = object : SysProps {
            val values = mutableMapOf(TimeZoneKeeper.PROP to "Europe/Madrid", TimeZoneKeeper.PROP_STATUS to "ok Europe/Madrid")
            override fun get(name: String) = values[name].orEmpty()
            override fun set(name: String, value: String) { values[name] = value }
        }
        val p = TimeZonePane(ctx, TimeZonePrefs(KioskPrefs(app).paneFile("time-zone")), TimeZoneKeeper(props, inline = true))
        p.start()
        p.onEvent(PanelEvent.HomeAssistantTimeZone(haRule))
        return p
    }

    /** Listening for Okay Nabu, with one timer running. */
    fun voice(): AssistPane {
        KioskPrefs(app).voice.apply { wakeWord = "okay_nabu"; micMuted = false; micGain = 1.5f; wakeSound = true; overlay = true }
        val p = AssistPane(
            ctx,
            player = object : UrlPlayer { override fun play(urls: List<String>, onDone: (Boolean) -> Unit) {}; override fun stop() {} },
            speaker = object : SpeakerMedia { override val playing = false; override val announcing = false; override fun pause() {}; override fun resume() {} },
            resolveUrl = { it }, wakeChime = {}, onVoiceEvent = {}, createInput = { _, _, _ -> error("not started in the mock-ups") },
        )
        p.onEvent(PanelEvent.TimersChanged(listOf(PanelTimer("1", "Pasta", 900, 360, true))))
        return p
    }

    /** Playing a public-domain piece from Music Assistant at 192.0.2.30. */
    fun music(): SendspinPane {
        val p = SendspinPane(ctx, haBase = { "http://homeassistant.local:8123" })
        p.player.setVolume(40)
        p.player.status = SendspinSession.Status(SendspinSession.Phase.PLAYING, serverName = "Music Assistant", paired = true, codec = "flac")
        p.player.activeUrl = "ws://192.0.2.30:8927/sendspin"
        p.player.nowPlaying = "Claude Debussy – Clair de lune"
        return p
    }

    /** The radio's two entities the page shows, as Home Assistant has them. */
    fun radio() = object : Pane {
        override val id = "radio"
        override fun entities() = listOf(
            TextSensorEntity("radio_helper", "Radio helper", get = { "v4 · root side ready" }),
            TextSensorEntity("radio_firmware", "Radio firmware", get = { "EmberZNet 7.4.4 · EZSP v13" }),
        )
    }

    /**
     * The menu's model with [running] panes, the S1's own definitions with the radio on [coordinator]; the
     * panes in [off] are turned off. Read again on each call, as the menu and the Manage page do.
     */
    fun model(running: List<Pane>, off: Set<String> = emptySet()): () -> KioskSettingsOverlay.Model {
        KioskPrefs(app).apply { urlSetting = "http://homeassistant.local:8123/lovelace/panel" }
        val radio = radioDefinition(app, FixedReadout(coordinator, running = true, allowedNow = "192.0.2.20"))
        val definitions = GlassworksRuntime(AqaraS1Plus()).paneDefinitions(app).map { if (it.id == "radio") radio else it }
        definitions.forEach { d -> d.switch?.on = d.id !in off }
        val byId = running.associateBy { it.id }
        return { MenuFixture.model(app, radio, status = "screen on · presence near · voice=idle", panes = definitions, running = { byId[it] }) }
    }

    /** Every pane the S1 has, running. */
    fun all(): List<Pane> = listOf(diagnostics(), wallBase(), timeZone(), voice(), music(), radio())
}
