package sh.edm.s1kiosk.frame

import android.util.Log
import sh.edm.s1kiosk.base.UartBaseLink
import java.util.concurrent.TimeUnit

/**
 * Aqara's cloud link on the stock firmware is the native gateway daemon `ha_master` (an init
 * service, so `pm disable-user` can't touch it). It is the only process that talks to the internet
 * (Aqara AIoT cloud, incl. location-based weather) — see docs/research/debloat.md.
 *
 * Stopping it is just `setprop ctl.stop ha_master`. This firmware runs SELinux permissive, so the
 * app's own uid may control init services (verified on device); no root needed. Not persistent by
 * itself: init starts it again on every boot, so the wall base pane ([sh.edm.s1kiosk.base.WallBasePane]) re-applies the choice after each start.
 *
 * `ha_master` also holds `/dev/ttyS3`, the wall-base UART. With the UART base backend (default) the
 * app speaks that protocol itself (base/UartBaseLink), so ha_master is never needed and is stopped
 * before the port is opened; only the LocalService backend keeps it running while a base is attached.
 * Stopping it does not trip com.lumi.systemmanager's cloud watchdog (Wi-Fi reassociate): that only
 * fires on a cloud-state *change* event, which the dead daemon never sends.
 *
 * The S1 frame holds the one instance and hands it to the wall base pane it builds ([AqaraS1Plus.panes]).
 */
class AqaraCloud {
    private companion object {
        const val TAG = "S1Kiosk.AqaraCloud"
        const val SERVICE = "ha_master"
    }

    /** init's view of the daemon: "running", "stopped", "restarting", or "" if unknown. */
    fun daemonState(): String = run("getprop", "init.svc.$SERVICE")

    /**
     * Why the wall base UART is not free (ha_master holds it while it runs), or null when it is.
     * One lambda per instance, so WallBase.Config equality holds across re-applies.
     */
    val uartHolder: () -> String? = {
        when (val st = daemonState()) {
            "running", "restarting" -> "$SERVICE is $st and owns ${UartBaseLink.SERIAL_PATH}"
            else -> null
        }
    }

    /** Starts or stops the daemon; returns true if init reports the wanted state afterwards. */
    fun setDaemon(running: Boolean): Boolean {
        val want = if (running) "running" else "stopped"
        if (daemonState() == want) return true
        run("setprop", if (running) "ctl.start" else "ctl.stop", SERVICE)
        for (i in 0 until 20) {
            if (daemonState() == want) {
                Log.i(TAG, "$SERVICE -> $want")
                return true
            }
            Thread.sleep(100)
        }
        Log.w(TAG, "$SERVICE did not reach '$want' (now '${daemonState()}')")
        return false
    }

    private fun run(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(3, TimeUnit.SECONDS)
        out
    } catch (e: Exception) {
        Log.w(TAG, "${cmd.joinToString(" ")} failed: ${e.message}")
        ""
    }
}
