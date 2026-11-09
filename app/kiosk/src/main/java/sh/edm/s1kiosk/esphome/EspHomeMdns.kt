package sh.edm.s1kiosk.esphome

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Advertises `<name>._esphomelib._tcp` with Android [NsdManager], with the TXT records ESPHome
 * publishes (esphome `mdns/mdns_component.cpp`): `friendly_name`, `version`, `mac` (12 lowercase
 * hex digits), `board`, `network`, and the encryption records of esphome 2026.10.2
 * `mdns_component.cpp`: `api_encryption` = `Noise_NNpsk0_25519_ChaChaPoly_SHA256` when a key is in
 * use; otherwise `api_encryption_supported` = the same value plus `api_provisioning` = `zero-psk`.
 * Home Assistant's ESPHome integration listens for `_esphomelib._tcp.local.`, requires the `mac`
 * record and only treats `api_encryption` as "key required" (config_flow.py `async_step_zeroconf`).
 *
 * The SRV target is the Android hostname, not `s1-panel.local`; HA replaces the discovered name with
 * `DeviceInfoResponse.name` once it connects.
 */
class EspHomeMdns(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start(identity: DeviceIdentity, port: Int, encrypted: Boolean, provisionable: Boolean) {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = identity.name
            serviceType = "_esphomelib._tcp"
            setPort(port)
            setAttribute("friendly_name", identity.friendlyName)
            setAttribute("version", identity.esphomeVersion)
            setAttribute("mac", identity.macHex)
            setAttribute("board", "aqara-s1-plus")
            setAttribute("network", "wifi")
            if (encrypted) {
                setAttribute("api_encryption", NoiseResponder.PROTOCOL_NAME)
            } else if (provisionable) {
                setAttribute("api_encryption_supported", NoiseResponder.PROTOCOL_NAME)
                setAttribute("api_provisioning", "zero-psk")
            }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) =
                ApiLog.i("mDNS: registered ${info.serviceName}._esphomelib._tcp port $port")

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                ApiLog.w("mDNS: registration failed ($errorCode)")

            override fun onServiceUnregistered(info: NsdServiceInfo) =
                ApiLog.i("mDNS: unregistered ${info.serviceName}")

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                ApiLog.w("mDNS: unregistration failed ($errorCode)")
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: Exception) {
            ApiLog.w("mDNS: registerService threw", e)
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try { nsd.unregisterService(l) } catch (e: Exception) { ApiLog.w("mDNS: unregister threw", e) }
    }
}
