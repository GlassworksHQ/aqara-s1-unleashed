<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-bluetooth-proxy-on-dark.svg">
      <img alt="bluetooth-proxy, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-bluetooth-proxy-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">bluetooth-proxy</h1>

<p align="center">
  <b>An ESPHome Bluetooth proxy on Android.</b><br>
  Home Assistant hears the BLE sensors near the device, as through an ESP32 proxy.
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

The Android side of an [ESPHome Bluetooth proxy](https://esphome.io/components/bluetooth_proxy/):
BLE scanning, batching and the scan schedule. The protocol messages are in
[`esphome-api`](../esphome-api/README.md); this library plugs into its `ApiServer`. Home
Assistant's `esphome` and `bluetooth` integrations then take the device as a Bluetooth scanner,
with no setup in HA.

It has no Pane API and no S1 classes (#152). The Glassworks `bluetooth-proxy` pane wraps it with
settings and entities. Package `sh.edm.bluetoothproxy`, MIT licensed, minSdk 23.

**Passive only, so far.** It forwards advertisements, which covers most sensors (thermometers,
plant sensors, presence tags). GATT connections (locks, devices that need one) come later.

## Use it in your own app

```kotlin
val server = ApiServer(ApiServer.DEFAULT_PORT, { identity }, entities, keys)
val proxy = BluetoothProxyEngine(AndroidBleScanner(context), server::sendBluetoothAdvertisements)
server.bluetooth = proxy // DeviceInfo now says "passive scan, raw advertisements"
server.start()

// later, e.g. on a settings page or for a diagnostic entity:
val s = proxy.status // subscribed, scanning, failure, advertisementsPerMinute

// to turn it off: take it out, then reconnect clients so they reread DeviceInfo
server.bluetooth = null
proxy.close()
```

Before you start it, check `AndroidBleScanner(context).unavailable()`: it says why a scan can't run
(no BLE, Bluetooth off, a missing permission, location off), or null. The app asks for the runtime
permissions; the manifest entries come with the library.

## What's in it

| | |
|---|---|
| `BluetoothProxyEngine` | The proxy. Scans only while a client is subscribed to advertisements, batches up to 16 per message or for 200 ms (as ESPHome does on Wi-Fi), and reports a `ProxyStatus`. Runs on one thread of its own. |
| `ScanSchedule` (internal) | When to start, stop and restart the one scan. Pure Kotlin, time passed in. |
| `BleScanner`, `ScanPower`, `ScanFailure` | The scanner seam. Tests use a fake. |
| `AndroidBleScanner` | `BluetoothLeScanner`, with one empty `ScanFilter` (see below). |

## Android's scan rules

Read from Android 10's Bluetooth service (`packages/apps/Bluetooth`, `android10-release`):

| Rule | Where | What the library does |
|---|---|---|
| A 6th scan start within 30 s fails (`SCAN_FAILED_SCANNING_TOO_FREQUENTLY`) | `GattService.registerScanner`, `AppScanStats` | One long-running scan. At most 4 starts per 30 s; more wait. Home Assistant leaving and coming back within 15 s keeps the same scan. |
| A scan without filters is suspended while the screen is off, and while location is off | `ScanManager.handleStartScan` | Passes one empty `ScanFilter`: it matches everything, but counts as filtered. |
| Results need location on and the location permission (FINE from Android 10) | `GattService.hasScanResultPermission` | `unavailable()` reports `LOCATION_OFF` or `NO_PERMISSION`. |
| A scan becomes opportunistic after 30 min unless its filters fit in the controller | `ScanManager.regularScanTimeout` | Restarts the scan every 25 min. |
| A background app's scan drops to `BLE_SCAN_BACKGROUND_MODE` (low power) | `ScanManager.handleImportanceChange` | Nothing: a kiosk app is in the foreground. |

Android can't switch to a passive scan (no scan requests), so the proxy doesn't offer ESPHome's
scanner mode switch (`FEATURE_STATE_AND_MODE`).

## Privacy

Nothing here logs an address. Addresses go only to the subscribed client, as with an ESPHome
proxy.

## Tests

`./gradlew :bluetooth-proxy:testDebugUnitTest`. `BluetoothProxyHomeAssistantTest` runs Home
Assistant's client (aioesphomeapi, the version HA pins) against `ApiServer` and the engine with a
fake radio. It skips unless `ESPHOME_PYTHON` points at a Python with it installed
(`../esphome-api/test-requirements.txt`).
