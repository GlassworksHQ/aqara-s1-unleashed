package sh.edm.bluetoothproxy

import android.content.Context
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.ApiServer
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.NoiseKeys

/**
 * The README's example, compiled (never run): if the API changes, this stops compiling.
 * Keep in sync with README.md.
 */
@Suppress("unused", "UNUSED_VARIABLE")
private object ReadmeExamples {
    fun use(context: Context, identity: DeviceIdentity, entities: List<ApiEntity>, keys: NoiseKeys) {
        val server = ApiServer(ApiServer.DEFAULT_PORT, { identity }, entities, keys)
        val proxy = BluetoothProxyEngine(server, AndroidBleScanner(context))
        server.bluetooth = proxy
        server.start()

        val s = proxy.status

        proxy.close()

        val why: ScanFailure? = AndroidBleScanner(context).unavailable()
    }
}
