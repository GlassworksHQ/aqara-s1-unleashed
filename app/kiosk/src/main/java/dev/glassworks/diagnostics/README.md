<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-pane-on-dark.svg">
      <img alt="Glassworks pane" src="https://www.glassworks.dev/brand/banner-pane-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Diagnostics pane</h1>

<p align="center">
  <b>The panel's health in Home Assistant: CPU, temperature, RAM, storage, Wi-Fi and uptime.</b><br>
  Diagnostic entities only, sampled once a minute, sent only when they change.
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

When a panel is slow, hot or drops off Wi-Fi, this pane shows why in Home Assistant, without adb.
It is a core pane: every frame gets it. The entity list, bands and sources are in
[docs/esphome.md](../../../../../../../../docs/esphome.md#diagnostics).

| File | What it is |
|---|---|
| `DiagnosticsPane.kt` | The pane: one table of metrics (entity, band, value), sampled every 60 s on the device thread. |
| `AndroidProbe.kt` | The readings: `/proc`, `/sys/class/thermal` and a few system services. No shell, no root. |
| `Readings.kt` | Plain Kotlin: the `/proc` parsers, the CPU share, the band and the restart reason in words. |

## What the frame gives

| Frame member | Used for |
|---|---|
| `cpuThermalZone` | The `type` of the CPU's thermal zone. Null: no temperature entity. The S1's is `cpu_thermal_zone`. |

The engine says which of the app's processes hold web content (`Engine.isContentProcess`), so the
pane doesn't know Gecko's process names.

## Cost

Once a minute, the pane reads `/proc/stat`, the CPU zone's `temp`, and `stat` and `smaps_rollup`
for each of the app's processes. That is about a dozen small files on the S1. It also makes three
binder calls: running processes, memory and Wi-Fi. The pane logs its own CPU time under `S1ESPHome`
(`diagnostics: sample N took X ms CPU`) for the first three samples and then once an hour. On the
reference S1 the first three samples took 34, 28 and 28 ms (2026-11-12), about 0.05 % of one core.

## Turning it off

There is no off switch yet. When #67's pane on/off switch lands, `stop()` must also clear `values`:
an off pane keeps its entities, and they must read unknown, not the last value sent.

## Android versions

*Last restart reason* uses `ApplicationExitInfo`, which Android 11 added. On Android 10, the S1's,
it reads `unknown`. The check is `Platform.hasApplicationExitInfo`.
