package sh.edm.s1kiosk.frame

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.glassworks.frame.BootRest
import dev.glassworks.frame.Frame
import dev.glassworks.pane.PaneContext
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.base.WallBaseDefinition
import sh.edm.s1kiosk.base.WallBasePane
import sh.edm.s1kiosk.base.WallBasePrefs
import java.util.concurrent.ScheduledExecutorService

/** The Aqara Panel Hub S1 Plus, the frame this app is built for. */
class AqaraS1Plus : Frame {
    override val board = "aqara-s1-plus"

    override val bootRest = BOOT_REST

    override val presence = Vi5300Presence()

    override val light = Mk6212Light()

    /** The A133's zones are `cpu_thermal_zone`, `gpu_thermal_zone` and `ddr_thermal_zone` (read 2026-11-12). */
    override val cpuThermalZone = "cpu_thermal_zone"

    private val aqaraCloud = AqaraCloud()

    /** The time zone that Aqara resets at every boot and the root helper puts back, and the wall base. */
    override fun panes(ctx: PaneContext, device: ScheduledExecutorService) = listOf(
        TimeZonePane(ctx),
        WallBasePane(ctx, WallBasePane.wantedConfig(WallBasePrefs(ctx.prefs("wall-base")), aqaraCloud), device, aqaraCloud),
    )

    /** The wall base's adb extras. The time zone has nothing to set. */
    override fun paneDefinitions(context: Context) = listOf(WallBaseDefinition(WallBasePrefs(KioskPrefs(context).paneFile("wall-base"))))

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
        private val LUMI_SETTINGS: Intent get() = Intent("lumi.settings.SETTINGS").setPackage("com.lumi.settings")

        /**
         * branding/glass-tile/bootanimation.zip's last frame (part2) and its trim offset (BootRestTest
         * checks both). The boot splash reads it before the user is unlocked, without the runtime.
         */
        val BOOT_REST = BootRest(sh.edm.s1kiosk.R.drawable.boot_rest, 554, 189, "On Aqara S1 Plus")
    }
}
