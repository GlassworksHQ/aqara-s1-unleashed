<p align="center">
  <a href="https://www.glassworks.dev/frames/generic-android">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-frame-generic-android-on-dark.svg">
      <img alt="Generic Android, a Glassworks frame" src="https://www.glassworks.dev/brand/banner-frame-generic-android-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Generic Android</h1>

<p align="center">
  <b>Any Android device, as an app. No root.</b><br>
  Core plus the panes that need no special hardware.<br>
  Tablets, old phones, other Android panels.
</p>

<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-on-dark.svg">
      <img alt="Built on Glassworks" src="https://www.glassworks.dev/brand/badge-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-planned-on-dark.svg">
    <img alt="Status: planned" src="https://www.glassworks.dev/brand/badge-status-planned-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
  <a href="https://www.glassworks.dev/frames/generic-android">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/frames/generic-android/clarity-on-dark.svg">
      <img alt="Clarity" src="https://www.glassworks.dev/frames/generic-android/clarity-on-light.svg" height="30">
    </picture>
  </a>
</p>

A Glassworks **frame**: the device definition for the Generic Android. Its data is in [`design/frame.json`](design/frame.json) (format: [frames/README.md](../README.md)), and [glassworks.dev/frames/generic-android](https://www.glassworks.dev/frames/generic-android) shows it.

## Status

**Status: planned.** It needs Android 8.0 or newer: S1 Kiosk's `minSdk` is 26.

The Clarity badge shows its Clarity grade ([docs/perf.md](../../docs/perf.md)) once one is published; none is yet.

## Hardware

| | |
|---|---|
| Radio | None |
| Presence | Whatever sensors the device has |
| Audio | Whatever the device has |
| Root | Not needed |

## Install

It will install as a normal app. It doesn't install from Kiln yet.

## Panes

| Pane | Support | Note |
|---|---|---|
| Assist | Depends on the device | Needs a microphone. |
| Timers | Depends on the device | Needs Assist. |
| Sendspin | Planned |  |
| Zigbee and Thread radio | No | No supported radio. |
| Time zone | No | Worked out: only on the S1 (`aqara-s1-plus`). |
| Diagnostics | Planned | Worked out: needs no special hardware. |
| Bluetooth proxy | Depends on the device | Worked out: needs Bluetooth LE. |
| Wall base | No | S1 only. |

"Worked out" means `frame.json` doesn't rate the pane; its support comes from what the pane needs and what the frame has ([how](../README.md#the-panes-a-frame-runs)).

## Research notes

None yet. A note about this device goes in `research/` ([how](../../docs/research/README.md#the-research-notes)).

## Files

- [`design/frame.json`](design/frame.json): the catalog data and the 3D model
