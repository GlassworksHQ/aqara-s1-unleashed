package sh.edm.s1kiosk

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * The panel's settings screen. The S1's firmware has no Android Settings app; its own one
 * (com.lumi.settings: Network, Sounds, Display, Updates, Advanced settings incl. factory reset) is
 * normally reached from Aqara's launcher or from the shade's gear, and that gear only shows once the
 * panel went through Aqara's onboarding. So the kiosk offers its own ways in: a "Settings" action on
 * its notification in the shade, and the "Open device settings" button entity in Home Assistant.
 */
object DeviceSettings {
    fun intent(context: Context): Intent? {
        val pm = context.packageManager
        val candidates = listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent("lumi.settings.SETTINGS").setPackage("com.lumi.settings"),
        )
        return candidates.firstOrNull { it.resolveActivity(pm) != null }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens it; false if the panel has no settings app at all. */
    fun open(context: Context): Boolean {
        val i = intent(context) ?: return false
        return runCatching { context.startActivity(i) }.isSuccess
    }
}
