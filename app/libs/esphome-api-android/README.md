<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-library-esphome-api-android-on-dark.svg">
      <img alt="esphome-api-android, a Glassworks library" src="https://www.glassworks.dev/brand/banner-library-esphome-api-android-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">esphome-api-android</h1>

<p align="center">
  <b>Android helpers for esphome-api: mDNS discovery.</b><br>
  Home Assistant finds the device on its own.
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
