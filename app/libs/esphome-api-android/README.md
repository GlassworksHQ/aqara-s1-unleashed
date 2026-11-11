# esphome-api-android

Android helpers for [esphome-api](../esphome-api/README.md). For now that's `EspHomeMdns`, which
advertises the device over mDNS with Android's `NsdManager`, so Home Assistant discovers it on its
own. Package `sh.edm.esphome.android`, MIT licensed. Depending on this module also brings in
`esphome-api`.

```kotlin
val mdns = EspHomeMdns(context, board = "my-tablet") // board: optional `board` TXT record
server.start()
mdns.start(identity, server)
// Encryption state changed (HA set a key): re-advertise so HA knows a key is now required.
server.onKeyActivated = { mdns.stop(); mdns.start(identity, server) }
```

It publishes `<name>._esphomelib._tcp` with the TXT records ESPHome uses: `friendly_name`,
`version`, `mac`, `network`, optionally `board`, and the encryption records. Those are
`api_encryption` when a key is set, or `api_encryption_supported` plus `api_provisioning=zero-psk`
while HA can still provision one.

```kotlin
implementation(project(":esphome-api-android"))
```

The module's manifest adds `INTERNET`, which the API server's socket needs. minSdk 26.
