package sh.edm.s1kiosk.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HttpPoster
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds Home Assistant without asking (docs/onboarding.md, step 3):
 *
 * 1. mDNS browse for `_home-assistant._tcp` with [NsdManager]; TXT `internal_url` / `base_url`, then
 *    the resolved address ([HaUrls.candidates]).
 * 2. The address of the first ESPHome API client whose `HelloRequest.client_info` starts with
 *    "Home Assistant" (HA's `CLIENT_INFO`), port 8123.
 *
 * Every candidate is confirmed with `GET <url>/manifest.json` before it is stored in
 * [KioskPrefs.haUrl]. A manually entered URL is never overwritten; an mDNS result replaces one
 * learnt from the ESPHome connection.
 */
class HaDiscovery(
    context: Context,
    private val http: HttpPoster,
    private val onChanged: (url: String, source: String) -> Unit,
) {
    companion object {
        private const val TAG = "S1Discovery"
        const val SOURCE_MDNS = "mdns"
        const val SOURCE_ESPHOME = "esphome"
        const val SOURCE_MANUAL = "manual"
        private const val BROWSE_MS = 60_000L
    }

    private val ctx = context.applicationContext
    private val prefs = KioskPrefs(ctx)
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ha-discovery").apply { isDaemon = true } }
    private var browse: NsdManager.DiscoveryListener? = null
    @Volatile private var esphomeTried = false

    fun start() {
        exec.execute { startBrowse() }
    }

    fun stop() {
        exec.execute { stopBrowse() }
        exec.shutdown()
    }

    /** From the ESPHome API server (any thread). */
    fun onApiHello(clientInfo: String, remote: InetAddress?) {
        if (!HaUrls.isHomeAssistantHello(clientInfo) || remote == null || esphomeTried) return
        esphomeTried = true
        exec.execute {
            if (prefs.haUrlSource == SOURCE_MANUAL || prefs.haUrlSource == SOURCE_MDNS) return@execute
            val url = HaUrls.fromAddress(remote) ?: return@execute
            if (probe(url)) store(url, SOURCE_ESPHOME)
            else Log.i(TAG, "ESPHome client $remote is Home Assistant but $url/manifest.json didn't answer")
        }
    }

    /** Manual entry from the sign-in screen (main thread); probed off-thread, [done] on that thread. */
    fun setManual(raw: String, done: (ok: Boolean, url: String?) -> Unit) {
        val url = HaUrls.normalize(raw)
        if (url == null) { done(false, null); return }
        exec.execute {
            val ok = probe(url)
            if (ok) store(url, SOURCE_MANUAL)
            done(ok, url)
        }
    }

    private fun probe(url: String): Boolean {
        val (status, body) = http.get("$url/manifest.json", 32 * 1024)
        val ok = HaUrls.looksLikeHaManifest(status, body)
        Log.i(TAG, "probe $url/manifest.json -> $status ${if (ok) "(Home Assistant)" else ""}")
        return ok
    }

    private fun store(url: String, source: String) {
        val changed = prefs.haUrl != url || prefs.haUrlSource != source
        if (!changed) return
        Log.i(TAG, "Home Assistant at $url (via $source)")
        prefs.setHaUrl(url, source)
        onChanged(url, source)
    }

    private fun startBrowse() {
        if (browse != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { Log.i(TAG, "browsing $serviceType") }
            override fun onDiscoveryStopped(serviceType: String) { Log.i(TAG, "browse stopped") }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "browse failed ($errorCode)")
                exec.execute { browse = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                Log.i(TAG, "found ${info.serviceName}")
                exec.execute { resolve(info, 0) }
            }
        }
        try {
            nsd.discoverServices(HaUrls.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            browse = l
            exec.schedule({ stopBrowse() }, BROWSE_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw", e)
        }
    }

    private fun stopBrowse() {
        val l = browse ?: return
        browse = null
        try { nsd.stopServiceDiscovery(l) } catch (_: Exception) {}
    }

    private fun resolve(info: NsdServiceInfo, attempt: Int) {
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    // Only one resolve may run at a time on Android 10 (FAILURE_ALREADY_ACTIVE = 3).
                    if (attempt < 5) exec.schedule({ resolve(info, attempt + 1) }, 1, TimeUnit.SECONDS)
                    else Log.w(TAG, "resolve ${si.serviceName} failed ($errorCode)")
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    exec.execute { onResolved(si) }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "resolveService threw", e)
        }
    }

    private fun onResolved(si: NsdServiceInfo) {
        if (prefs.haUrlSource == SOURCE_MANUAL) return
        val cands = HaUrls.candidates(si.attributes ?: emptyMap(), si.host, si.port)
        Log.i(TAG, "resolved ${si.serviceName}: candidates $cands")
        for (c in cands) {
            if (probe(c)) { store(c, SOURCE_MDNS); return }
        }
    }
}
