package sh.edm.s1kiosk.improv

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import sh.edm.s1kiosk.AdminReceiver

/**
 * Joins the Wi-Fi network received over Improv, and puts the previous one back on failure.
 *
 * Android 10 (API 29) with targetSdk >= 29: WifiManager.addNetwork/enableNetwork/removeNetwork/
 * reconnect return -1/false for normal apps. AOSP android10-release WifiServiceImpl
 * `isTargetSdkLessThanQOrPrivileged()` still allows them for (a) a device-owner/profile-owner app
 * and (b) an app holding the SYSTEM_ALERT_WINDOW app-op (`checkSystemAlertWindowPermission`). This
 * app has (b) on the panel (install.sh sets it), and can be made device owner for (a), which also
 * lets it grant itself the location permission. Both use the "legacy" path below. Otherwise it falls
 * back to a WifiNetworkSuggestion, which on Android 10 needs the user to approve a notification and
 * is never preferred over an already-saved network that works.
 *
 * Seeing the current SSID/networkId and the scan results needs ACCESS_FINE_LOCATION *and* location
 * mode on (WifiPermissionsUtil.enforceCanAccessScanResults); without it WifiInfo is redacted.
 *
 * Main thread only.
 */
@SuppressLint("MissingPermission")
class WifiProvisioner(private val ctx: Context, private val main: Handler) {

    sealed class Outcome {
        data class Connected(val ssid: String, val ip: String?, val method: String) : Outcome()
        data class Failed(val reason: String, val restored: String?) : Outcome()
    }

    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    private var job: Attempt? = null

    /** Progress text for the overlay (e.g. "approve the notification"). */
    var onProgress: ((String) -> Unit)? = null

    val isDeviceOwner: Boolean get() = runCatching { dpm.isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

    val canDrawOverlays: Boolean get() = Settings.canDrawOverlays(ctx)

    /** True when addNetwork/enableNetwork are expected to work (see class comment). */
    val legacyApiAllowed: Boolean
        get() = Build.VERSION.SDK_INT < 29 || isDeviceOwner || (Build.VERSION.SDK_INT == 29 && canDrawOverlays)

    val hasLocationPermission: Boolean
        get() = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val locationEnabled: Boolean
        get() = runCatching { ctx.getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(false)

    /** Needed to read scan results and the current SSID/networkId on API 29. */
    val hasLocationAccess: Boolean get() = hasLocationPermission && locationEnabled

    fun status(): String = "deviceOwner=$isDeviceOwner saw=$canDrawOverlays legacyApi=$legacyApiAllowed " +
        "fineLocation=$hasLocationPermission locationOn=$locationEnabled wifiOn=${wm.isWifiEnabled}"

    /** A device owner can grant itself the location permission (needed for SSIDs and scans). */
    fun ensureLocationIfDeviceOwner() {
        if (hasLocationPermission || !isDeviceOwner) return
        val admin = ComponentName(ctx, AdminReceiver::class.java)
        for (p in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            val ok = runCatching {
                dpm.setPermissionGrantState(admin, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }.getOrElse { Log.w(TAG, "setPermissionGrantState($p) failed", it); false }
            Log.i(TAG, "device owner: grant $p -> $ok")
        }
    }

    fun requestScan() {
        @Suppress("DEPRECATION")
        runCatching { wm.startScan() }
    }

    /** Cached scan results as Improv triples; empty without location access. */
    fun scan(): List<ScannedNetwork> {
        ensureLocationIfDeviceOwner()
        if (!hasLocationAccess) {
            Log.w(TAG, "scan: no location access (${status()}); returning no networks")
            return emptyList()
        }
        return runCatching {
            wm.scanResults.filter { !it.SSID.isNullOrEmpty() }
                .map { ScannedNetwork(it.SSID, it.level, WifiSecurityRules.improvAuth(it.capabilities ?: "")) }
        }.getOrElse { Log.w(TAG, "getScanResults failed", it); emptyList() }
    }

    fun cancel() {
        preparing?.let { finishPreparing(it, Outcome.Failed("cancelled", null)) }
        job?.finish(Outcome.Failed("cancelled", null))
    }

    /** A connect waiting for Wi-Fi to turn on (and for a first scan) before [connectNow]. */
    private inner class Preparing(
        val ssid: String,
        val password: String,
        val hidden: Boolean?,
        val cb: (Outcome) -> Unit,
    ) : Runnable {
        val enableDeadline = SystemClock.elapsedRealtime() + WIFI_ENABLE_TIMEOUT_MS
        var scanDeadline = 0L

        override fun run() {
            if (preparing !== this) return
            val now = SystemClock.elapsedRealtime()
            if (!wm.isWifiEnabled) {
                if (now > enableDeadline) return finishPreparing(this, Outcome.Failed("Wi-Fi is turned off and could not be turned on", null))
                main.postDelayed(this, 500)
                return
            }
            if (scanDeadline == 0L) {
                // Just turned on: give the first scan a moment, so the network's security type is known.
                Log.i(TAG, "Wi-Fi is on")
                scanDeadline = now + FIRST_SCAN_WAIT_MS
                requestScan()
            }
            val seen = hasLocationAccess && runCatching { wm.scanResults.any { it.SSID == ssid } }.getOrDefault(false)
            if (!seen && now < scanDeadline) {
                main.postDelayed(this, 500)
                return
            }
            preparing = null
            connectNow(ssid, password, hidden, cb)
        }
    }

    private var preparing: Preparing? = null

    private fun finishPreparing(p: Preparing, o: Outcome) {
        if (preparing !== p) return
        preparing = null
        main.removeCallbacks(p)
        Log.i(TAG, "outcome: $o")
        p.cb(o)
    }

    /**
     * Joins [ssid]. Turns Wi-Fi on first if it is off (allowed on API 29 for the same callers as
     * addNetwork, see the class comment). [hidden]: null = decide from the scan (not seen = hidden),
     * else as given. [password] is never logged, only its length.
     */
    fun connect(ssid: String, password: String, hidden: Boolean? = null, cb: (Outcome) -> Unit) {
        preparing?.let { finishPreparing(it, Outcome.Failed("superseded", null)) }
        job?.finish(Outcome.Failed("superseded", null))
        if (!wm.isWifiEnabled) {
            @Suppress("DEPRECATION")
            val ok = runCatching { wm.setWifiEnabled(true) }.getOrDefault(false)
            Log.i(TAG, "Wi-Fi is off: setWifiEnabled(true) -> $ok")
            if (!ok) return cb(Outcome.Failed("Wi-Fi is turned off and could not be turned on", null))
            onProgress?.invoke("Turning Wi-Fi on…")
            val p = Preparing(ssid, password, hidden, cb)
            preparing = p
            main.postDelayed(p, 500)
            return
        }
        connectNow(ssid, password, hidden, cb)
    }

    private fun connectNow(ssid: String, password: String, hidden: Boolean?, cb: (Outcome) -> Unit) {
        ensureLocationIfDeviceOwner()
        Log.i(TAG, "connect '$ssid' (${password.length} char password); ${status()}")
        if (!wm.isWifiEnabled) return cb(Outcome.Failed("Wi-Fi is turned off", null))

        val loc = hasLocationAccess
        val info = wm.connectionInfo
        val wifiUpAtStart = wifiNetworkUp() && info?.supplicantState == SupplicantState.COMPLETED
        val prevNetId = if (loc) info?.networkId ?: -1 else -1
        val prevSsid = if (loc) WifiSecurityRules.unquote(info?.ssid) else null

        if (loc && wifiUpAtStart && prevSsid == ssid) {
            // Already there: don't touch the saved config (we couldn't restore its password).
            Log.i(TAG, "already connected to '$ssid'; reporting success without changes")
            return cb(Outcome.Connected(ssid, WifiSecurityRules.ipv4(info.ipAddress), "already-connected"))
        }
        if (!loc && wifiUpAtStart) {
            // Without location access WifiInfo is redacted, so we couldn't tell our network from the
            // old one. Refuse rather than risk reporting a false success.
            return cb(Outcome.Failed(
                "Panel is on Wi-Fi but can't verify networks without the location permission", null))
        }

        val scan = if (loc) runCatching { wm.scanResults.filter { it.SSID == ssid }.maxByOrNull { it.level } }.getOrNull() else null
        val security = WifiSecurityRules.choose(scan?.capabilities, password)
        WifiSecurityRules.validate(security, password)?.let { return cb(Outcome.Failed(it, null)) }
        Log.i(TAG, "security=$security inScan=${scan != null} prevNetId=$prevNetId prevSsid=$prevSsid wifiUpAtStart=$wifiUpAtStart")

        val attempt = Attempt(ssid, loc, wifiUpAtStart, prevNetId, prevSsid, cb)
        job = attempt
        val isHidden = hidden ?: (scan == null)
        if (legacyApiAllowed && attempt.startLegacy(security, password, hidden = isHidden)) return
        attempt.startSuggestion(security, password, hidden = isHidden)
    }

    /**
     * SAE/OWE need PMF. API 30+: setSecurityParams(type). API 29: the field `requirePMF` is hidden
     * (@UnsupportedAppUsage, greylisted), so set it reflectively.
     */
    @SuppressLint("DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    private fun requirePmf(c: WifiConfiguration, securityType: Int) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) c.setSecurityParams(securityType)
            else WifiConfiguration::class.java.getField("requirePMF").setBoolean(c, true)
        }.onFailure { Log.w(TAG, "could not require PMF", it) }
    }

    private fun wifiNetworkUp(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private inner class Attempt(
        val ssid: String,
        val loc: Boolean,
        val wifiUpAtStart: Boolean,
        val prevNetId: Int,
        val prevSsid: String?,
        val cb: (Outcome) -> Unit,
    ) {
        var netId = -1
        var addedNew = false
        var suggestion: WifiNetworkSuggestion? = null
        var method = ""
        var deadline = 0L
        var authErrors = 0
        var done = false

        private val poll = object : Runnable {
            override fun run() {
                if (done) return
                check()?.let { finish(it); return }
                main.postDelayed(this, 500)
            }
        }

        private val supplicant = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1) == WifiManager.ERROR_AUTHENTICATING) {
                    authErrors++
                    Log.i(TAG, "supplicant: authentication error #$authErrors")
                }
            }
        }

        fun startLegacy(security: WifiSecurity, password: String, hidden: Boolean): Boolean {
            val conf = WifiConfiguration().apply {
                SSID = WifiSecurityRules.quote(ssid)
                hiddenSSID = hidden
                when (security) {
                    WifiSecurity.OPEN -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    WifiSecurity.OWE -> { allowedKeyManagement.set(WifiConfiguration.KeyMgmt.OWE); requirePmf(this, 6 /* SECURITY_TYPE_OWE */) }
                    WifiSecurity.WEP -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                        allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                        wepKeys[0] = WifiSecurityRules.wepKey(password)
                        wepTxKeyIndex = 0
                    }
                    WifiSecurity.PSK -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                        preSharedKey = WifiSecurityRules.pskValue(password)
                    }
                    WifiSecurity.SAE -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE)
                        requirePmf(this, 4 /* SECURITY_TYPE_SAE */)
                        preSharedKey = WifiSecurityRules.quote(password)
                    }
                    WifiSecurity.EAP -> return false
                }
            }
            val existing = if (loc) runCatching {
                wm.configuredNetworks.orEmpty().firstOrNull { WifiSecurityRules.unquote(it.SSID) == ssid }
            }.getOrNull() else null
            netId = wm.addNetwork(conf)
            if (netId == -1) {
                Log.w(TAG, "addNetwork refused (not device owner / no SYSTEM_ALERT_WINDOW?); trying a suggestion")
                return false
            }
            addedNew = existing == null || existing.networkId != netId
            val enabled = wm.enableNetwork(netId, true)
            wm.reconnect()
            method = if (isDeviceOwner) "device-owner" else "addNetwork"
            Log.i(TAG, "addNetwork -> $netId (new=$addedNew), enableNetwork -> $enabled")
            if (!enabled) {
                finish(Outcome.Failed("enableNetwork refused", null))
                return true
            }
            begin(CONNECT_TIMEOUT_MS)
            return true
        }

        fun startSuggestion(security: WifiSecurity, password: String, hidden: Boolean) {
            if (Build.VERSION.SDK_INT < 29) return finish(Outcome.Failed("cannot add networks on this Android version", null))
            val b = WifiNetworkSuggestion.Builder().setSsid(ssid).setIsHiddenSsid(hidden)
            when (security) {
                WifiSecurity.PSK -> b.setWpa2Passphrase(password)
                WifiSecurity.SAE -> b.setWpa3Passphrase(password)
                WifiSecurity.OWE -> b.setIsEnhancedOpen(true)
                WifiSecurity.OPEN -> {}
                WifiSecurity.WEP, WifiSecurity.EAP ->
                    return finish(Outcome.Failed("$security networks can't be suggested; make the app device owner", null))
            }
            val s = b.build()
            runCatching { wm.removeNetworkSuggestions(listOf(s)) }
            val status = wm.addNetworkSuggestions(listOf(s))
            Log.i(TAG, "addNetworkSuggestions -> $status")
            if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                return finish(Outcome.Failed("network suggestion refused (status $status)", null))
            }
            suggestion = s
            method = "suggestion"
            onProgress?.invoke("Approve “Allow suggested Wi-Fi networks?” in the notification shade")
            begin(SUGGESTION_TIMEOUT_MS)
        }

        private fun begin(timeoutMs: Long) {
            deadline = SystemClock.elapsedRealtime() + timeoutMs
            runCatching { ctx.registerReceiver(supplicant, IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)) }
            main.postDelayed(poll, 1000)
        }

        /** Non-null once the attempt has an outcome. */
        private fun check(): Outcome? {
            val i = wm.connectionInfo
            val ip = WifiSecurityRules.ipv4(i?.ipAddress ?: 0)
            val onTarget = if (loc) {
                (netId != -1 && i?.networkId == netId) || WifiSecurityRules.unquote(i?.ssid) == ssid
            } else {
                !wifiUpAtStart // fresh panel: whatever Wi-Fi comes up was ours
            }
            if (i?.supplicantState == SupplicantState.COMPLETED && onTarget && ip != null && wifiNetworkUp()) {
                return Outcome.Connected(ssid, ip, method)
            }
            if (authErrors >= 2) return failAndRestore("wrong password (authentication failed)")
            if (SystemClock.elapsedRealtime() > deadline) return failAndRestore("timed out connecting to '$ssid'")
            return null
        }

        private fun failAndRestore(reason: String): Outcome.Failed {
            var restored: String? = null
            if (netId != -1 && addedNew) Log.i(TAG, "removeNetwork($netId) -> ${wm.removeNetwork(netId)}")
            suggestion?.let { Log.i(TAG, "removeNetworkSuggestions -> ${wm.removeNetworkSuggestions(listOf(it))}") }
            if (netId != -1) {
                if (prevNetId != -1) {
                    Log.i(TAG, "re-select previous network $prevNetId -> ${wm.enableNetwork(prevNetId, true)}")
                    restored = prevSsid ?: "previous network"
                } else if (wifiUpAtStart) {
                    restored = "previous network (auto-join)"
                }
                wm.reconnect()
            } else if (wifiUpAtStart) {
                restored = prevSsid ?: "previous network" // suggestions never displace saved networks
            }
            return Outcome.Failed(reason, restored)
        }

        fun finish(o: Outcome) {
            if (done) return
            done = true
            main.removeCallbacks(poll)
            runCatching { ctx.unregisterReceiver(supplicant) }
            if (job === this) job = null
            Log.i(TAG, "outcome: $o")
            if (o is Outcome.Failed && (o.reason == "cancelled" || o.reason == "superseded")) {
                // Undo a half-done attempt the same way as a failure.
                failAndRestore(o.reason)
            }
            cb(o)
        }
    }

    companion object {
        private const val TAG = "S1Kiosk.Improv"
        /** Same as ESPHome's improv_ble connect timeout. */
        const val CONNECT_TIMEOUT_MS = 30_000L
        /** Suggestions wait for the user to approve a notification. */
        const val SUGGESTION_TIMEOUT_MS = 120_000L
        /** setWifiEnabled(true) → WIFI_STATE_ENABLED takes a few seconds on the panel. */
        const val WIFI_ENABLE_TIMEOUT_MS = 15_000L
        /** After turning Wi-Fi on: wait this long at most for the network to show up in a scan. */
        const val FIRST_SCAN_WAIT_MS = 6_000L
    }
}
