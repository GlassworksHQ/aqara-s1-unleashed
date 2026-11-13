<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-improv-on-dark.svg">
      <img alt="improv, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-improv-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">improv</h1>

<p align="center">
  <b>Improv Wi-Fi for Android, over BLE, serial or adb.</b><br>
  The device side: it takes the credentials and joins the network.
</p>

<p align="center">
  <a href="../../../LICENSE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-alpha-on-dark.svg">
    <img alt="Status: alpha" src="https://www.glassworks.dev/brand/badge-status-alpha-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

The device side of [Improv Wi-Fi](https://www.improv-wifi.com/) for Android. It lets a phone,
Home Assistant's `improv_ble` integration or improv-wifi.com send Wi-Fi credentials to an Android
device over **Bluetooth LE**, over a **serial** stream, or over **adb on a USB cable** from a web
page. It also joins the network, which is the hard part on Android 10 and later. Few Android
implementations of the device side exist.

It was extracted from [S1 Kiosk](../../README.md), where it puts the Aqara S1 Plus panel on Wi-Fi.
Package `sh.edm.improv`, MIT licensed.

## What's in it

| | |
|---|---|
| `Improv`, `RpcAssembler`, `RpcCommand` | [Improv BLE](https://www.improv-wifi.com/ble/) packet format: RPC encode and decode, checksums, chunk reassembly, UUIDs and constants. Pure Kotlin. |
| `ImprovSession` | The Improv BLE state machine (authorization, provisioning, errors, auth timeout). Pure Kotlin: Bluetooth and Wi-Fi sit behind `ImprovSession.Effects`, and time is passed in, so it is unit-testable. Behaviour follows the spec and ESPHome's `improv_ble`. |
| `ImprovBleServer` | The Android GATT server and advertiser: service, characteristics, 0x4677 service data, queued notifications, MTU tracking, legacy or extended advertising (only legacy below Android 8). Turns Bluetooth on if needed. |
| `ImprovSerial`, `SerialPacketReader`, `ImprovSerialSession` | [Improv Serial](https://www.improv-wifi.com/serial/): framing and its session. Transport-agnostic: feed it bytes from a UART, a socket or an adb stream. Pure Kotlin. |
| `ImprovAdbServer`, `AdbSocketServer`, `AdbPeer` | Improv Serial over adb: an abstract Unix socket that only adb may open, with one serial session per connection. The browser client is the [`improv-serial`](../../../kiln/packages/improv-serial/README.md) TypeScript package. `AdbSocketServer` is also usable on its own, for any browser-to-app channel over USB. |
| `WifiProvisioner` | Joins the network and verifies the connection, putting the previous network back on failure. Also scans. See [Joining Wi-Fi](#joining-wi-fi). |
| `WifiSecurity`, `WifiSecurityRules` | Maps Android scan capabilities to Improv's auth labels, and picks the security type to configure (open, OWE, WEP, PSK, SAE, EAP), including for hidden networks. Pure Kotlin. |

The UI is yours. That covers how the user physically authorizes (a button or a prompt, then
`session.authorize(now)`) and what the screen shows while provisioning.

## Joining Wi-Fi

From Android 10 (API 29), `WifiManager.addNetwork` and `enableNetwork` fail for ordinary apps
that target API 29 or later. `WifiProvisioner` uses whichever of these routes the app has:

1. **Device owner** (`adb shell dpm set-device-owner <pkg>/<YourAdminReceiver>`): the legacy API
   works. The app can also grant itself location access (`ensureLocationIfDeviceOwner()`), which it
   needs to see scan results and the current SSID.
2. **The `SYSTEM_ALERT_WINDOW` app-op** (`adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow`):
   AOSP still allows the legacy API for apps that hold it.
3. Otherwise, a **network suggestion**. Android 10 shows the user a notification to approve, and
   prefers an already-saved network that works.

Below Android 10 every app may use the legacy API, so that is the route there. WPA3 (`SAE`) and
Enhanced Open (`OWE`) networks need Android 10 or later; older versions fail to join them.

It turns Wi-Fi on if needed. It waits for the network to show up in a scan, picks the security type
(`WifiSecurity`), watches for authentication errors, and reports `Outcome.Connected(ssid, ip,
method)` or `Outcome.Failed(reason, restored)`. On failure it removes what it added and selects the
previous network again.

```kotlin
val wifi = WifiProvisioner(context, main, admin = ComponentName(context, MyAdminReceiver::class.java))
wifi.connect(ssid, password) { outcome -> /* any thread */ }
```

`admin` is optional: when it's null and the app is device owner, the provisioner finds the app's
active admin itself. Call it from the main thread. Logs go under `WifiProvisioner.logTag`.

## Use (BLE)

```kotlin
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
```

Call `ImprovBleServer`, `ImprovSession` and `WifiProvisioner` only from the main thread. GATT callbacks are posted to
the handler you pass in.

**Permissions.** The library's manifest declares `BLUETOOTH` and `BLUETOOTH_ADMIN` (API ≤ 30) and
`BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT` (API 31+, runtime permissions there, so request them
yourself), and marks `bluetooth_le` as not required. Logs go to logcat under
`ImprovBleServer.logTag` (default `ImprovBle`).

## Use (serial, and serial over adb)

Implement `ImprovSerialSession.Effects` (`send`, `online`, `redirectUrl`, `info`, `scan`,
`connect`), then pass every chunk you read to `session.onBytes(chunk, len)`, from a UART or any
other stream.

Over adb, `ImprovAdbServer` does the plumbing:

```kotlin
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
```

A web page then opens `localabstract:myapp_improv` over WebUSB adb with the
[`improv-serial`](../../../kiln/packages/improv-serial/README.md) package. From a computer you can
also run `adb forward tcp:7000 localabstract:myapp_improv` and use any Improv Serial client on
`localhost:7000`. Only adb (uid 2000, or 0 after `adb root`) may connect, so USB access with an
authorized adb key is the physical authorization. That means it needs adb turned on, which suits
kiosks, signage and other single-purpose devices. S1 Kiosk's `ImprovSerialServer.kt` is the full
example.

## Gradle

For now it's a module of this repository (an Android library, minSdk 23, Android 6). Nothing is published to a
Maven repository yet.

```kotlin
implementation(project(":improv"))
```

## Tests

```sh
cd app && ./gradlew :improv:testDebugUnitTest
```

The protocol, both sessions, the adb peer check and the Wi-Fi security rules have JVM unit tests.
`ImprovBleServer` and `WifiProvisioner` have no automated tests. S1 Kiosk has only lightly tested them end to end on the panel (see
[onboarding.md](../../../docs/onboarding.md)).
