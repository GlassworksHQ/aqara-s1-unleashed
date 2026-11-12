package sh.edm.s1kiosk.frame

import android.os.SystemClock
import android.util.Log
import sh.edm.s1kiosk.radio.ShellProps
import sh.edm.s1kiosk.radio.SysProps
import sh.edm.s1kiosk.update.UpdateHelper
import sh.edm.s1kiosk.update.UpdateManager

/**
 * Installs app updates through the root service in the S1's patched boot image ([UpdateHelper]),
 * silently. Boot images from before it have none ([present] is false), and the app falls back to
 * Android's PackageInstaller.
 */
class RootUpdateInstaller(
    private val props: SysProps = ShellProps,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    fun present(): Boolean = props.get(UpdateHelper.PROP_VERSION).trim().toIntOrNull()?.let { it >= 1 } == true

    /**
     * At start: why the install the service did while this process was replaced failed, or null.
     * Success shows as the new installed version, so it is only logged.
     */
    fun lastFailure(): String? {
        val st = UpdateHelper.parseStatus(props.get(UpdateHelper.PROP_STATUS)) ?: return null
        when (st.state) {
            "ok" -> Log.i(TAG, "last root install: ok ${st.detail}")
            "error" -> { Log.w(TAG, "last root install failed: ${st.detail}"); return st.detail }
        }
        return null
    }

    /**
     * Starts the service for `files/update/pending.apk` and follows its status until it ends or [over]
     * says the attempt is over. [installing] gets each line for the update card. On success `pm`
     * replaces the app, which kills this process: the new version reads the result ([lastFailure]).
     * Returns why it failed, or null.
     */
    fun install(version: String, installing: (String) -> Unit, over: () -> Boolean): String? {
        props.set(UpdateHelper.PROP_STATUS, "requested")
        props.set("ctl.start", UpdateHelper.SERVICE)
        installing("Installing $version (root update service)")
        val started = now()
        var lastDetail = ""
        while (!over()) {
            val st = UpdateHelper.parseStatus(props.get(UpdateHelper.PROP_STATUS))
            val waited = now() - started
            when (st?.state) {
                "ok" -> {
                    // Normally never seen: pm kills us first. Seen if pm didn't (e.g. same process kept).
                    Log.i(TAG, "root update service: ok ${st.detail}")
                    installing("Installed $version; restarting")
                    return null
                }
                "error" -> return "root update service: ${st.detail}"
                "running" -> if (st.detail != lastDetail) {
                    lastDetail = st.detail
                    Log.i(TAG, "root update service: ${st.detail}")
                }
                else -> if (waited > START_TIMEOUT_MS) {
                    return "the root update service did not start (${UpdateHelper.SERVICE})"
                }
            }
            try { props.sleep(1_000) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    private companion object {
        const val TAG = UpdateManager.TAG
        const val START_TIMEOUT_MS = 30_000L
    }
}
