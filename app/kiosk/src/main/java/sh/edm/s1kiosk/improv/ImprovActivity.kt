package sh.edm.s1kiosk.improv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import sh.edm.s1kiosk.KioskService

/**
 * Invisible trampoline for the Improv intent extras (see [ImprovController.handleIntent]):
 *
 *   adb shell am start -n sh.edm.s1kiosk/.improv.ImprovActivity --ez improv true
 *   adb shell am start -n sh.edm.s1kiosk/.improv.ImprovActivity --ez release_device_owner true
 *
 * Forwards the extras to KioskService (which owns ImprovController) and finishes. It is protected by
 * WRITE_SECURE_SETTINGS (held by `adb shell`, not by normal apps), so other apps can't start
 * provisioning mode or strip device owner. Kept out of MainActivity to stay independent of it.
 */
class ImprovActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent?.extras
        Log.i("S1Kiosk.Improv", "ImprovActivity: ${extras?.keySet()?.joinToString()}")
        try {
            // An activity in the foreground may start the (already foreground) service directly.
            startService(Intent(this, KioskService::class.java).apply { extras?.let { putExtras(it) } })
        } catch (e: Exception) {
            Log.w("S1Kiosk.Improv", "could not reach KioskService", e)
        }
        finish()
    }
}
