package sh.edm.s1kiosk.frame

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.glassworks.frame.BootRest
import dev.glassworks.frame.Capability.BLUETOOTH_LE
import dev.glassworks.frame.Capability.MICROPHONE
import dev.glassworks.frame.Capability.S1_WALL_BASE
import dev.glassworks.frame.Capability.SILABS_RADIO
import dev.glassworks.frame.Capability.SPEAKER
import dev.glassworks.frame.Frame
import dev.glassworks.frame.Hardware
import dev.glassworks.frame.NamedBy
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PaneDefinition
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.base.WallBaseDefinition
import sh.edm.s1kiosk.base.WallBasePane
import sh.edm.s1kiosk.base.WallBasePrefs
import java.util.concurrent.ScheduledExecutorService

/** The Aqara Panel Hub S1 Plus, the frame this app is built for. Chosen on its hardware only ([orElse]). */
class AqaraS1Plus : Frame {
    override val board = "aqara-s1-plus"

    override val namedBy = NamedBy.FRAME

    override val displayName = DISPLAY_NAME

    override val hardware = Hardware("Aqara", "Panel Hub S1 Plus", "Allwinner A133")

    override val provides = setOf(MICROPHONE, SPEAKER, SILABS_RADIO, S1_WALL_BASE, BLUETOOTH_LE)

    override val bootRest = BOOT_REST

    override val presence = Vi5300Presence()

    override val light = Mk6212Light()

    /** The A133's zones are `cpu_thermal_zone`, `gpu_thermal_zone` and `ddr_thermal_zone` (read 2026-11-12). */
    override val cpuThermalZone = "cpu_thermal_zone"

    private val aqaraCloud = AqaraCloud()

    /** The time zone that Aqara resets at every boot and the root helper puts back, and the wall base. */
    override fun panes(ctx: PaneContext, device: ScheduledExecutorService) = listOf(
        TimeZonePane(ctx, TimeZonePrefs(ctx.prefs("time-zone"))),
        WallBasePane(ctx, WallBasePane.wantedConfig(WallBasePrefs(ctx.prefs("wall-base")), aqaraCloud), device, aqaraCloud),
    )

    /** The wall base's on/off and adb extras, and the time zone's on/off. */
    override fun paneDefinitions(context: Context): List<PaneDefinition> {
        val prefs = KioskPrefs(context)
        return listOf(WallBaseDefinition(prefs.paneFile("wall-base")), TimeZoneDefinition(prefs.paneFile("time-zone")))
    }

    val rootUpdates = RootUpdateInstaller()

    /** Its Wi-Fi chip has no PNO scans, so the app scans while the screen is off (#3). One per service run. */
    fun screenOffWifiScanner(context: Context, onForegroundOnly: () -> Unit) =
        ScreenOffWifiScanner(context, onForegroundOnly = onForegroundOnly)

    /**
     * The S1's firmware has no Android Settings app. Its own one (com.lumi.settings: Network, Sounds,
     * Display, Updates, Advanced settings incl. factory reset) is normally reached from Aqara's
     * launcher or from the shade's gear, and that gear only shows once the panel went through Aqara's
     * onboarding. So the kiosk offers its own ways in: a "Settings" action on its notification in the
     * shade, and the "Open device settings" button entity in Home Assistant.
     */
    override fun systemSettingsIntents() = listOf(
        Intent(Settings.ACTION_SETTINGS),
        LUMI_SETTINGS,
    )

    /** No Android Settings app on its firmware: Aqara's own one has Network -> WLAN. */
    override fun wifiSettingsIntents() = super.wifiSettingsIntents() + LUMI_SETTINGS

    companion object {
        /** frame.json's `name` (PanelNameTest checks it). */
        const val DISPLAY_NAME = "Aqara S1 Plus"

        /** `Build.HARDWARE` on the S1's A133 (read 2026-11-14). Its model and maker are Allwinner's dev board's, so they don't tell. */
        const val HARDWARE = "sun50iw10p1"

        /** The frame for the device the app runs on: the S1's on its hardware, else [generic]. */
        fun orElse(hardware: String, generic: () -> Frame): Frame = if (hardware == HARDWARE) AqaraS1Plus() else generic()

        private val LUMI_SETTINGS: Intent get() = Intent("lumi.settings.SETTINGS").setPackage("com.lumi.settings")

        /**
         * branding/glass-tile/bootanimation.zip's last frame (part2) and its trim offset (BootRestTest
         * checks both). The boot splash reads it before the user is unlocked, without the runtime.
         */
        val BOOT_REST = BootRest(sh.edm.s1kiosk.R.drawable.boot_rest, 554, 189, "On Aqara S1 Plus")
    }
}
