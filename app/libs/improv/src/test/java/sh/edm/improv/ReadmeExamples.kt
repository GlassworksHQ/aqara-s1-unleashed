package sh.edm.improv

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * The README's examples, compiled (never run): if the API changes, this stops compiling.
 * Keep in sync with README.md.
 */
@Suppress("unused", "UNUSED_VARIABLE", "UNUSED_ANONYMOUS_PARAMETER")
private object ReadmeExamples {
    class MyAdminReceiver : DeviceAdminReceiver()

    fun ble(context: Context) {
        val main = Handler(Looper.getMainLooper())
        val wifi = WifiProvisioner(context, main)
        lateinit var ble: ImprovBleServer
        lateinit var session: ImprovSession
        val now = { System.currentTimeMillis() }

        session = ImprovSession(object : ImprovSession.Effects {
            override fun publishState(state: ImprovState) = ble.setState(state)
            override fun publishError(error: ImprovError) = ble.setError(error)
            override fun sendResult(packet: ByteArray) = ble.sendResult(packet)
            override fun identify() { /* blink, beep… */ }
            override fun startWifi(ssid: String, password: String) = wifi.connect(ssid, password) { o ->
                main.post { session.onWifiResult(o is WifiProvisioner.Outcome.Connected, redirectUrl = null, now()) }
            }
            override fun scanNetworks(): List<ScannedNetwork> = wifi.scan()
            override fun deviceInfo() = listOf("My App", "1.0", "Android", "Living room tablet")
            override fun maxResultSize() = ble.maxNotify()
        })

        ble = ImprovBleServer(context, main, object : ImprovBleServer.Listener {
            override fun onRpcWrite(chunk: ByteArray) = session.onRpcWrite(chunk, now())
            override fun onClientConnected(address: String) {}
            override fun onClientDisconnected(address: String) = session.onDisconnect()
            override fun onAdvertising(ok: Boolean, detail: String) {}
        })

        session.start(requireAuthorization = true, now = now())
        ble.start(session.state, session.capabilities)
        // When the user physically authorizes (a button press): session.authorize(now())
        // Call session.tick(now()) every second or so, to let authorization expire.
    }

    fun joinWifi(context: Context, main: Handler, ssid: String, password: String) {
        val wifi = WifiProvisioner(context, main, admin = ComponentName(context, MyAdminReceiver::class.java))
        wifi.connect(ssid, password) { outcome -> /* any thread */ }
    }

    fun serialOverAdb(main: Handler, wifi: WifiProvisioner) {
        val server = ImprovAdbServer("myapp_improv") { output ->
            object : ImprovSerialSession.Effects {
                override fun send(packet: ByteArray) = synchronized(output) { output.write(packet); output.flush() }
                override fun online() = false // true when already on Wi-Fi
                override fun redirectUrl() = ""
                override fun info() = ImprovSerial.info("My App", "1.0", "Android", "Living room tablet")
                override fun scan(): List<SerialNetwork> = emptyList() // e.g. wifi.scanFresh(...) mapped to SerialNetwork
                override fun connect(ssid: String, password: String, done: (String?) -> Unit) {
                    main.post { wifi.connect(ssid, password) { o -> done(if (o is WifiProvisioner.Outcome.Connected) "" else null) } }
                }
            }
        }
        server.start()
    }
}
