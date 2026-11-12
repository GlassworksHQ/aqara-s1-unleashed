package sh.edm.s1kiosk.esphome

import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.android.EspHomeMdns

/**
 * What [PanelDevice] asks of mDNS: advertise the API server, and stop. [EspHomeMdns] on the panel;
 * a test passes one that records the calls, so it can see when the panel would be advertised.
 */
internal interface MdnsAdvertiser {
    fun start(identity: DeviceIdentity, port: Int, encrypted: Boolean, provisionable: Boolean)
    fun stop()
}

internal class EspHomeMdnsAdvertiser(private val mdns: EspHomeMdns) : MdnsAdvertiser {
    override fun start(identity: DeviceIdentity, port: Int, encrypted: Boolean, provisionable: Boolean) =
        mdns.start(identity, port, encrypted, provisionable)

    override fun stop() = mdns.stop()
}
