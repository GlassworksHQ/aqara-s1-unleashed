package sh.edm.s1kiosk.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import dev.glassworks.platform.Platform
import sh.edm.s1kiosk.glassworks

/**
 * Status of the PackageInstaller fallback session ([UpdateManager.installWithPackageInstaller]):
 * shows Android's confirmation when it asks for one, and reports failures to the update entity.
 * Success is never seen here: installing the new version kills this process.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = Platform.intentExtra(intent, Intent.EXTRA_INTENT)
                if (confirm == null) {
                    context.glassworks.updates?.onSessionFailed("no confirmation screen")
                    return
                }
                Log.i(UpdateManager.TAG, "package installer asks for confirmation on the panel")
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    context.glassworks.updates?.onSessionFailed("cannot show the confirmation (${e.javaClass.simpleName})")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(UpdateManager.TAG, "package installer: success")
            else -> context.glassworks.updates?.onSessionFailed(describe(status, message))
                ?: Log.w(UpdateManager.TAG, "package installer: ${describe(status, message)}")
        }
    }

    private fun describe(status: Int, message: String): String {
        val what = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict with the installed app"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
            PackageInstaller.STATUS_FAILURE_INVALID -> "invalid APK"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
            else -> "failed ($status)"
        }
        return if (message.isBlank()) what else "$what: ${message.take(120)}"
    }
}
