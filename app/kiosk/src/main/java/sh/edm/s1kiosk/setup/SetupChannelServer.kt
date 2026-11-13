package sh.edm.s1kiosk.setup

import android.content.Context
import android.content.Intent
import android.util.Log
import sh.edm.improv.AdbSocketServer
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.InstallerGlue
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaAuth

/**
 * The setup channel on `@s1kiosk_setup` (adb `localabstract:`), started by KioskService. Thin glue:
 * the protocol is [SetupChannel]; this reads the app's state and applies changes the same way the
 * panel itself does. The panes' setup steps (GlassworksRuntime.paneDefinitions) bring their own state
 * and requests.
 */
object SetupChannelServer {
    private const val TAG = "S1Kiosk.SetupChannel"

    private var server: AdbSocketServer? = null

    fun start(context: Context, runtime: GlassworksRuntime) {
        if (server != null) return
        val backend = AndroidBackend(context.applicationContext, runtime)
        val steps = runtime.paneDefinitions(context).flatMap { it.setupSteps }
        server = AdbSocketServer(SetupChannel.SOCKET, TAG) { input, output ->
            SetupChannel(backend, steps) { Log.i(TAG, it) }.serve(input, output)
        }.also { it.start() }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private class AndroidBackend(private val ctx: Context, private val runtime: GlassworksRuntime) : SetupChannel.Backend {
        private val prefs = KioskPrefs(ctx)

        init { HaAuth.init(ctx) }

        override fun state(): ChannelState {
            val ip = InstallerGlue.wlanIpv4()
            val haUrl = prefs.haBase.orEmpty()
            return ChannelState(
                ip = ip,
                ha = ChannelState.Ha(
                    discovered = ChannelState.discovered(prefs.haUrl, prefs.haUrlSource),
                    url = haUrl,
                    signedIn = HaAuth.signedIn,
                    signinUrl = ChannelState.signinUrl(ip, haUrl),
                ),
            )
        }

        /**
         * As the `url` intent extra (MainActivity.applyIntent): saves the dashboard URL setting, then
         * reloads the dashboard, or restarts the process when the secure-context hosts changed. The
         * setting is saved first so the reply already has it; the reload/restart runs after the reply.
         */
        override fun setHa(url: String): SetupChannel.HaChange {
            val restart = InstallerGlue.onMain {
                val before = prefs.secureHosts()
                prefs.urlSetting = url
                prefs.secureHosts() != before
            } ?: return SetupChannel.HaChange(error = "The panel didn't save the address; try again.")
            Log.i(TAG, "Home Assistant address set from the installer: $url${if (restart) " (restarting for the secure-context hosts)" else ""}")
            return SetupChannel.HaChange(afterReply = {
                InstallerGlue.post(if (restart) 300L else 0L) {
                    val sink = runtime.commandSink
                    if (sink == null) Log.i(TAG, "dashboard not running; it loads the new address when it starts")
                    else sink(if (restart) Intent().putExtra("restart", true) else Intent().putExtra("url", url))
                }
            })
        }
    }
}
