<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-esphome-api-on-dark.svg">
      <img alt="esphome-api, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-esphome-api-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">esphome-api</h1>

<p align="center">
  <b>The device side of the ESPHome native API, in Kotlin.</b><br>
  Home Assistant adds your JVM or Android app as an ESPHome device.
</p>

<p align="center">
  <a href="../../../LICENSE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-beta-on-dark.svg">
    <img alt="Status: beta" src="https://www.glassworks.dev/brand/badge-status-beta-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

The **device side** of the [ESPHome native API](https://esphome.io/components/api.html) in plain
Kotlin. Run it in any JVM or Android app and Home Assistant's ESPHome integration adds that app as
an ESPHome device, with entities, encryption and the voice assistant. Most libraries implement the
*client* side (talking to ESPHome devices). This one implements the device.

It was extracted from [S1 Kiosk](../../README.md), where it runs the Aqara S1 Plus panel's
ESPHome device. Package `sh.edm.esphome`, MIT licensed.

## What it does

- **Server:** TCP on port 6053. One reader thread per connection, and writes are serialized per
  connection. It handles Hello, DeviceInfo, DeviceCapabilities, ListEntities, SubscribeStates,
  commands, Ping and Disconnect, and ignores unknown messages, as ESPHome does. Keepalive follows
  ESPHome's `api_connection.cpp`.
- **Encryption:** Noise `NNpsk0_25519_ChaChaPoly_SHA256`, and Home Assistant can provision the key
  for you. With no key, the device accepts plaintext and zero-PSK clients and lets HA set a key
  (`NoiseEncryptionSetKeyRequest`). Once a key is active, it accepts only Noise with that key and
  drops older sessions. This matches an ESPHome build with `api: encryption:` and no YAML key. You
  can also pass a fixed key, or `null` for plaintext only.
- **Entities:** binary sensor, sensor, text sensor, switch, number, select, text, button, event,
  update, light (brightness) and media player. You can also subclass `ApiEntity`. Any entity can be
  marked `authOnly`, so commands are accepted only over Noise with the provisioned key.
- **Voice assistant:** the messages are wired through `VoiceAssistantHandler`: start and stop, API
  audio, events, timers, announcements and wake word configuration. The audio, wake word and UI
  stay in your app.
- **Sendspin pairing-token hand-off:** for HA → Music Assistant, through `SendspinTokenSource`.
  Leave it unset if you don't have a Sendspin player.
- **Noise primitives:** `NoiseCipher` and `NoiseResponder` are reusable on their own. S1 Kiosk's
  Sendspin client uses them.

Checked against the real `aioesphomeapi` client, the library Home Assistant uses (see
[Tests](#tests)).

## Use

```kotlin
var lampOn = false
val identity = DeviceIdentity(
    name = "my-device", friendlyName = "My Device", macAddress = "02:00:00:00:00:01",
    esphomeVersion = "2026.10.2", compilationTime = "2026-11-10 12:00:00",
    manufacturer = "Me", model = "Kotlin device", voiceFeatures = 0,
)
val lamp = SwitchEntity("lamp", "Lamp", icon = "mdi:lamp", get = { lampOn }, set = { lampOn = it })
val uptime = SensorEntity("uptime", "Uptime", unit = "s", deviceClass = "duration",
    category = EntityCategory.DIAGNOSTIC, get = { 42f })
val keys = NoiseKeys(MemoryKeyStore()) // persist the key in real code: HA provisions it
val server = ApiServer(port = ApiServer.DEFAULT_PORT, identity = { identity },
    entities = listOf(lamp, uptime), keys = keys)
server.start()
// Later, when something changes outside a command: server.publishStates()
```

`ReadmeExampleTest` keeps this example compiling and working.

- **Persist the key.** Implement `NoiseKeyStore` over your own storage (S1 Kiosk uses
  SharedPreferences). `save` must be synchronous, and the key must never be logged.
- **Threads.** Entity getters and setters run on connection threads, so make them thread-safe.
- **Changing entities at runtime.** `server.setEntities(...)`.
- **A web page for the device.** Set `DeviceIdentity(webserverPort = 8080)` and Home Assistant links
  the device page's "Visit" to `http://<device>:8080`. The default, 0, sends nothing.
- **Discovery.** HA finds devices over mDNS `_esphomelib._tcp`. On Android, use `EspHomeMdns` from
  [esphome-api-android](../esphome-api-android/README.md). On other platforms, advertise it
  yourself (Avahi, JmDNS…) with the TXT keys `friendly_name`, `version`, `mac` (`identity.macHex`)
  and `network`, plus `api_encryption` (once encrypted) or `api_encryption_supported` and
  `api_provisioning=zero-psk` (while provisionable). You can also add the device in HA by IP
  address.
- **Logging.** By default it goes to `android.util.Log` on Android (found by reflection) and to
  stdout elsewhere. Set `EspHomeLog.tag`, or replace `EspHomeLog.sink` to route it into your own
  logger.

## Gradle

For now it's a module of this repository. Nothing is published to a Maven repository yet.

```kotlin
implementation(project(":esphome-api"))
```

Dependency: BouncyCastle `bcprov-jdk18on` (X25519 and ChaCha20-Poly1305 through the lightweight
API, not the JCA provider). JVM 17 bytecode.

## Tests

```sh
cd app && ./gradlew :esphome-api:test
```

`EspHomeKeepaliveTest` runs the real `aioesphomeapi` client when `ESPHOME_PYTHON` points at a
Python with it installed:

```sh
python3 -m venv /tmp/esphome-venv && /tmp/esphome-venv/bin/pip install aioesphomeapi==46.25.0
ESPHOME_PYTHON=/tmp/esphome-venv/bin/python ./gradlew :esphome-api:test
```

The app's `EspHomeApiTest` (`:kiosk:testDebugUnitTest`) covers the rest the same way: every entity
type, voice, announcements, timers, and Noise with the right, wrong and missing keys.
