package io.github.aguilaair.s1kiosk.voice

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Advertises `_wyoming._tcp` over mDNS with [NsdManager] so Home Assistant's Wyoming
 * integration discovers the satellite (HA's wyoming manifest listens for `_wyoming._tcp.local.`;
 * wyoming-satellite registers the same type in `wyoming/zeroconf.py`).
 */
class ZeroconfAdvertiser(context: Context, private val serviceName: String, private val port: Int) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start() {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = this@ZeroconfAdvertiser.serviceName
            serviceType = "_wyoming._tcp"
            port = this@ZeroconfAdvertiser.port
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) =
                VoiceLog.i("mDNS: registered ${info.serviceName}._wyoming._tcp port $port")

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                VoiceLog.w("mDNS: registration failed ($errorCode)")

            override fun onServiceUnregistered(info: NsdServiceInfo) =
                VoiceLog.i("mDNS: unregistered ${info.serviceName}")

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                VoiceLog.w("mDNS: unregistration failed ($errorCode)")
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: Exception) {
            VoiceLog.w("mDNS: registerService threw", e)
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try { nsd.unregisterService(l) } catch (e: Exception) { VoiceLog.w("mDNS: unregister threw", e) }
    }
}
