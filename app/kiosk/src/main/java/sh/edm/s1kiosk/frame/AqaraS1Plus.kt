package sh.edm.s1kiosk.frame

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.glassworks.frame.Frame

/** The Aqara Panel Hub S1 Plus, the frame this app is built for. */
class AqaraS1Plus : Frame {
    override val board = "aqara-s1-plus"

    val aqaraCloud = AqaraCloud()

    /** The time zone to keep (Aqara resets it at every boot; the root helper puts it back). One per device run. */
    fun timeZoneKeeper(onChange: () -> Unit) = TimeZoneKeeper(onChange = onChange)

    val rootUpdates = RootUpdateInstaller()

    /** Its Wi-Fi chip has no PNO scans, so the app scans while the screen is off (#3). One per service run. */
    fun screenOffWifiScanner(context: Context) = ScreenOffWifiScanner(context)

    /**
     * The S1's firmware has no Android Settings app. Its own one (com.lumi.settings: Network, Sounds,
     * Display, Updates, Advanced settings incl. factory reset) is normally reached from Aqara's
     * launcher or from the shade's gear, and that gear only shows once the panel went through Aqara's
     * onboarding. So the kiosk offers its own ways in: a "Settings" action on its notification in the
     * shade, and the "Open device settings" button entity in Home Assistant.
     */
    override fun systemSettingsIntents() = listOf(
        Intent(Settings.ACTION_SETTINGS),
        Intent("lumi.settings.SETTINGS").setPackage("com.lumi.settings"),
    )
}
