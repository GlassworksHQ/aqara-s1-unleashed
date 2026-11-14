<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-pane-on-dark.svg">
      <img alt="Glassworks pane" src="https://www.glassworks.dev/brand/banner-pane-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Bluetooth proxy pane</h1>

<p align="center">
  <b>The panel as an ESPHome Bluetooth proxy: Home Assistant hears the BLE sensors near it.</b><br>
  Listening only, off by default, found by Home Assistant with no setup there.
</p>

<p align="center">
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-pane-on-dark.svg">
      <img alt="Glassworks pane" src="https://www.glassworks.dev/brand/badge-pane-on-light.svg" height="30">
    </picture>
  </a>
  <a href="../README.md#licence">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-on-dark.svg">
      <img alt="License: MPL-2.0" src="https://www.glassworks.dev/brand/badge-license-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-under-development-on-dark.svg">
    <img alt="Status: under development" src="https://www.glassworks.dev/brand/badge-status-under-development-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

While it is on, the panel answers Home Assistant as an ESPHome Bluetooth proxy (#121). HA adds it as
a Bluetooth scanner by itself, and hears the thermometers, plant sensors and tags near the panel
through it. The work is the [bluetooth-proxy library](../../../../../../../libs/bluetooth-proxy/README.md):
one long-running scan, only while HA listens, within Android's limits. This pane is the glue.

| | |
|---|---|
| `BluetoothProxyDefinition` | The on/off (`bluetooth_proxy_enabled`, off by default), the setup card (none without Bluetooth LE), the settings page, and the model the page reads. |
| `BluetoothProxyPane` | Gives the engine the device's ESPHome server while the pane is on, and takes it back when it goes off. Both change what DeviceInfo says, so Home Assistant reconnects. Adds *Bluetooth advertisements* and *Bluetooth proxy status* (diagnostic). |
| `ProxyPhase` | What the proxy is doing, from the switch, Android's state and the engine's status. The page's line and card, and the status entity, all read it. |
| `ProxyRadio`, `AndroidProxyRadio` | The device's Bluetooth: the scanner, why it can't scan, and turning Bluetooth and location on. |
| `BluetoothProxyPrefs` | The pane's one key. |

The page is the frame's to draw: the definition takes a factory and hands it a
`BluetoothProxyModel`. On the S1 that is `sh.edm.s1kiosk.settings.BluetoothProxyPage`, as approved on
#121: a status line, a card when Bluetooth or location is off, the advertisements and scanner
tiles, then the pane's entities as Home Assistant shows them.

**Not yet:** GATT connections (locks, devices that need one), the "Listen only / Listen and connect"
choice that comes with them, and the connections tile. The entities are in
[docs/esphome.md](../../../../../../../../docs/esphome.md#bluetooth-proxy).
