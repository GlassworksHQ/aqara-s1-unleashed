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

- **Server:** TCP on port 6053. Each connection has a reader thread, and a writer thread with its
  own queue, so a client that stops reading holds up only itself. Its states wait as the newest
  value per entity. Once 512 KiB of its other messages wait, the server drops it. It handles Hello,
  DeviceInfo, DeviceCapabilities, ListEntities, SubscribeStates, commands, Ping and Disconnect, and
  ignores unknown messages, as ESPHome does. Keepalive follows ESPHome's `api_connection.cpp`.
- **Encryption:** Noise `NNpsk0_25519_ChaChaPoly_SHA256`, and Home Assistant can provision the key
  for you. With no key, the device accepts plaintext and zero-PSK clients and lets HA set a key
  (`NoiseEncryptionSetKeyRequest`). Once a key is active, it accepts only Noise with that key and
  drops older sessions. This matches an ESPHome build with `api: encryption:` and no YAML key. You
  can also pass a fixed key, or `null` for plaintext only.
- **Entities:** binary sensor, sensor, text sensor, switch, number, select, text, button, event,
  update, light (brightness) and media player. You can also subclass `ApiEntity`. Any entity can be
  marked `authOnly`, so commands are accepted only over Noise with the provisioned key.
- **Actions:** user-defined actions that Home Assistant calls as `esphome.<node>_<action>`, with
  bool, int, float and string arguments and their lists. An action can return JSON to the caller
  or fail with a message.
- **Events on Home Assistant's bus:** `sendHomeAssistantEvent(HomeAssistantEvent("esphome.<name>", data))`
  fires an event, as ESPHome's `homeassistant.event` does. HA allows only the `esphome.` domain and
  needs no option for it. Use it when an event entity can't: HA refuses event types an event entity
  didn't list up front.
- **Camera:** a camera entity that serves JPEG frames, one at a time or as the stream HA's
  more-info dialog shows. Images go out in chunks, so states and pings keep flowing meanwhile.
- **Voice assistant:** the messages are wired through `VoiceAssistantHandler`: start and stop, API
  audio, events, timers, announcements and wake word configuration. The audio, wake word and UI
  stay in your app.
- **Bluetooth proxy (passive):** the feature flags in DeviceInfo, the advertisement subscription
  (one client at a time; a newer one replaces it, as in ESPHome) and batched raw advertisements,
  through `BluetoothProxyHandler` and `sendBluetoothAdvertisements`. No GATT connections yet. The
  Android scanner is [bluetooth-proxy](../bluetooth-proxy/README.md).
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
- **Categories and defaults.** The entity types take `category` (`CONFIG`, `DIAGNOSTIC`) and
  `disabledByDefault`, all but the light and the media player (those two always send it as `false`). HA reads `disabledByDefault` only
  when it first sees an entity, so pick it when you add the entity. The category follows the
  device on every connection.
- **A web page for the device.** Set `DeviceIdentity(webserverPort = 8080)` and Home Assistant links
  the device page's "Visit" to `http://<device>:8080`. The default, 0, sends nothing.
- **Discovery.** HA finds devices over mDNS `_esphomelib._tcp`. On Android, use `EspHomeMdns` from
  [esphome-api-android](../esphome-api-android/README.md). On other platforms, advertise it
  yourself (Avahi, JmDNS…) with the TXT keys `friendly_name`, `version`, `mac` (`identity.macHex`)
  and `network`, plus `api_encryption` (once encrypted) or `api_encryption_supported` and
  `api_provisioning=zero-psk` (while provisionable). You can also add the device in HA by IP
  address.
- **Actions and cameras.** See [below](#actions-and-cameras).
- **Logging.** By default it goes to `android.util.Log` on Android (found by reflection) and to
  stdout elsewhere. Set `EspHomeLog.tag`, or replace `EspHomeLog.sink` to route it into your own
  logger.

## Actions and cameras

```kotlin
val notify = ApiAction(
    "notify",
    args = listOf(ActionArg("title", ActionArgType.STRING), ActionArg("seconds", ActionArgType.INT)),
    supportsResponse = ActionResponse.STATUS,
) { call ->
    showBanner(call.string("title"), call.int("seconds"))
    null // a JSON object instead, for ActionResponse.OPTIONAL or ONLY
}
val porch = CameraEntity("porch", "Porch", maxFps = 5f) { latestJpeg() }
server.setEntities(listOf(lamp, uptime, notify, porch))
```

`ReadmeExampleTest` keeps this one working too. Home Assistant then has an action
`esphome.my_device_notify` with the fields `title` and `seconds` (every field is required), and a
camera entity `camera.my_device_porch`.

- **Action handlers** run on the server's action thread, one call at a time and in order, so a slow
  one never holds up the connection. A handler returns JSON as text, or null. To fail the call,
  throw: HA shows the exception's message. With `ActionResponse.NONE` (the default) HA doesn't wait
  for the result at all.
- **The camera's frame function** runs only while a client wants images. A single request takes one
  frame. A stream takes frames at up to `maxFps` until 5 s pass without another stream request, as
  ESPHome does. HA's more-info dialog asks again after every frame. Each client has its own camera
  thread, and the frame function is called when that thread is ready to send, so a slow client gets
  fewer frames, not a queue. Return null when there is no image: HA then reports it at once instead
  of timing out. Images go only to clients that have subscribed to states, as in ESPHome.
- **`authOnly`** works for both: the action runs, or the camera sends images, only over Noise with
  the provisioned key.
- **Not in yet:** the camera's idle image every few seconds (ESPHome's `idle_update_interval`), and
  argument descriptions (HA doesn't show them).

## Gradle

For now it's a module of this repository. Nothing is published to a Maven repository yet.

```kotlin
implementation(project(":esphome-api"))
```

Dependency: BouncyCastle `bcprov-jdk18on` (X25519 and ChaCha20-Poly1305 through the lightweight
API, not the JCA provider). JVM 17 bytecode.

On Android it runs from API 23 (Android 6). `./gradlew :esphome-api:test` runs Expediter first, which
checks the library's classes, and the BouncyCastle classes they reach, against API 23.

## Tests

```sh
cd app && ./gradlew :esphome-api:test
```

`EspHomeKeepaliveTest`, `EspHomeActionsCameraTest` and `EspHomeHaEventTest` run the real `aioesphomeapi` client when
`ESPHOME_PYTHON` points at a Python with it installed. They are skipped otherwise. PR CI installs it from
[`test-requirements.txt`](test-requirements.txt) (locked with hashes) and fails if one of them skipped:

```sh
python3 -m venv /tmp/esphome-venv && /tmp/esphome-venv/bin/pip install aioesphomeapi==46.25.0
ESPHOME_PYTHON=/tmp/esphome-venv/bin/python ./gradlew :esphome-api:test --rerun
```

The app's `EspHomeApiTest` (`:kiosk:testDebugUnitTest`) covers the rest the same way: every entity
type, voice, announcements, timers, and Noise with the right, wrong and missing keys.
