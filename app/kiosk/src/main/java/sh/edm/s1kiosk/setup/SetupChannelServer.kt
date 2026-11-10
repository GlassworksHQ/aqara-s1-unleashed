package sh.edm.s1kiosk.setup

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import sh.edm.s1kiosk.AdbSocketServer
import sh.edm.s1kiosk.InstallerGlue
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.KioskService
import sh.edm.s1kiosk.MainActivity
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.radio.RadioMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The setup channel on `@s1kiosk_setup` (adb `localabstract:`), started by KioskService. Thin glue:
 * the protocol is [SetupChannel]; this reads the app's state and applies changes the same way the
 * panel itself does.
 */
object SetupChannelServer {
    private const val TAG = "S1Kiosk.SetupChannel"
    /** Wait for RadioController to take the request, then for it to report how it went (client waits 30 s). */
    private const val RADIO_ACCEPT_MS = 15_000L
    private const val RADIO_FOLLOW_MS = 10_000L
    /** helperInstalled() spawns getprop: don't do it on every 1.5 s poll. */
    private const val HELPER_CACHE_MS = 10_000L

    private var server: AdbSocketServer? = null

    fun start(context: Context) {
        if (server != null) return
        val backend = AndroidBackend(context.applicationContext)
        server = AdbSocketServer(SetupChannel.SOCKET, TAG) { input, output ->
            SetupChannel(backend) { Log.i(TAG, it) }.serve(input, output)
        }.also { it.start() }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private class AndroidBackend(private val ctx: Context) : SetupChannel.Backend {
        private val prefs = KioskPrefs(ctx)
        @Volatile private var helper = false
        @Volatile private var helperAt = 0L

        init { HaAuth.init(ctx) }

        private fun helperInstalled(): Boolean {
            val r = KioskService.radioRef ?: return false
            val now = SystemClock.elapsedRealtime()
            if (helperAt == 0L || now - helperAt > HELPER_CACHE_MS) {
                helper = r.helperInstalled()
                helperAt = now
            }
            return helper
        }

        override fun state(): ChannelState {
            val ip = InstallerGlue.wlanIpv4()
            val haUrl = prefs.haBase.orEmpty()
            val r = KioskService.radioRef
            return ChannelState(
                ip = ip,
                ha = ChannelState.Ha(
                    discovered = ChannelState.discovered(prefs.haUrl, prefs.haUrlSource),
                    url = haUrl,
                    signedIn = HaAuth.signedIn,
                    signinUrl = ChannelState.signinUrl(ip, haUrl),
                ),
                radio = ChannelState.Radio(
                    mode = (r?.mode ?: RadioMode.parse(prefs.radioMode)).id,
                    status = r?.statusText() ?: "The radio controller isn't running yet",
                    options = ChannelState.OPTIONS,
                    helper = helperInstalled(),
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
                    val sink = MainActivity.commandSink
                    if (sink == null) Log.i(TAG, "dashboard not running; it loads the new address when it starts")
                    else sink(if (restart) Intent().putExtra("restart", true) else Intent().putExtra("url", url))
                }
            })
        }

        /** Through RadioController.request, like the panel's radio step; marks that step answered on success. */
        override fun setRadio(id: String): String? {
            val r = KioskService.radioRef ?: return "The radio controller isn't running yet; try again in a moment."
            val mode = RadioMode.fromId(id) ?: return "Unknown radio mode '$id'."
            val accepted = AtomicBoolean(false)
            val handled = CountDownLatch(1)
            Log.i(TAG, "radio mode ${mode.label} from the installer")
            r.request(mode.label, "installer") { ok -> accepted.set(ok); handled.countDown() }
            if (!handled.await(RADIO_ACCEPT_MS, TimeUnit.MILLISECONDS)) return "The radio didn't answer in time."
            if (!accepted.get()) return r.statusText()
            val until = SystemClock.elapsedRealtime() + RADIO_FOLLOW_MS
            while (true) {
                val status = r.statusText()
                when (RadioStep.classify(mode, true, status)) {
                    RadioStep.Result.OK -> break
                    RadioStep.Result.REFUSED, RadioStep.Result.ERROR -> return status
                    // Still applying (root helper): the panel's step lets you go on too; the status follows.
                    RadioStep.Result.APPLYING -> if (SystemClock.elapsedRealtime() > until) break
                }
                r.refreshSoon()
                Thread.sleep(500)
            }
            prefs.radioStepDone = true // the panel's own radio step doesn't ask again
            return null
        }
    }
}
