<p align="center">
  <a href="https://www.glassworks.dev/frames/aqara-s1-plus">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-frame-aqara-s1-plus-on-dark.svg">
      <img alt="Aqara S1 Plus, a Glassworks frame" src="https://www.glassworks.dev/brand/banner-frame-aqara-s1-plus-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Aqara S1 Plus</h1>

<p align="center">
  <b>A133 wall panel, rooted. The reference frame.</b><br>
  Every pane runs on it, including the Zigbee/Thread radio and the wall base.
</p>

<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-on-dark.svg">
      <img alt="Built on Glassworks" src="https://www.glassworks.dev/brand/badge-on-light.svg" height="30">
    </picture>
  </a>
  <a href="https://kiln.glassworks.dev/aqara-s1-plus">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
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
  <a href="https://www.glassworks.dev/frames/aqara-s1-plus">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/frames/aqara-s1-plus/clarity-on-dark.svg">
      <img alt="Clarity" src="https://www.glassworks.dev/frames/aqara-s1-plus/clarity-on-light.svg" height="30">
    </picture>
  </a>
</p>

A Glassworks **frame**: the device definition for the Aqara S1 Plus. Its data is in [`design/frame.json`](design/frame.json) (format: [frames/README.md](../README.md)), and [glassworks.dev/frames/aqara-s1-plus](https://www.glassworks.dev/frames/aqara-s1-plus) shows it.

## Status

**Status: beta.**

The Clarity badge shows its Clarity grade ([docs/perf.md](../../docs/perf.md)) once one is published; none is yet.

## Hardware

| | |
|---|---|
| SoC | Allwinner A133 |
| Size | 172 × 86 mm; 42 mm deep with the in-wall box |
| Screen | 1440 × 720 |
| Radio | EFR32MG21 on SPI, RCP firmware |
| Presence | VI5300 time-of-flight sensor |
| Audio | Microphone and speaker |
| Extras | Wired wall base on a UART |
| Root | FEL, then a patched boot image |

## Install

In the browser, over USB. It backs the panel up first, then roots and flashes it.

Install it from Kiln: [kiln.glassworks.dev/aqara-s1-plus](https://kiln.glassworks.dev/aqara-s1-plus). The steps are in [docs/install.md](../../docs/install.md), and Kiln's recipe for it is in [`installer/`](installer/).

Without root, the app can install over adb on its own, but you lose the radio modes.

## Panes

| Pane | Support | Note |
|---|---|---|
| Assist | Beta |  |
| Timers | Beta |  |
| Sendspin | Beta |  |
| Zigbee and Thread radio | Beta | Zigbee coordinator or router, and Thread. |
| Time zone | Beta | Worked out: needs no special hardware. |
| Diagnostics | Alpha | Worked out: needs no special hardware. Not yet checked on a panel. |
| Bluetooth proxy | Alpha | Worked out: has Bluetooth LE. Its engine met the S1 (2026-11-14); the pane itself not yet. |
| Wall base | Alpha | The driver passes against an emulator that Aqara's own `bracket_cli` accepts. It has not yet been tried on a real base ([base-protocol.md](research/base-protocol.md), [bases.md](research/bases.md)). |

"Worked out" means `frame.json` doesn't rate the pane; its support comes from what the pane needs and what the frame has ([how](../README.md#the-panes-a-frame-runs)).

## Research notes

What we found on this device, and how ([all notes](../../docs/research/README.md#the-research-notes)):

- [hardware.md](research/hardware.md): Board, SoC, FEL button, the 3-pin UART console and an ESP32-S3 as the adapter
- [boot-log.md](research/boot-log.md): Annotated stock boot log: BOOT0 DRAM bring-up, secure-boot state, partitions
- [findings.md](research/findings.md): Root, SELinux, AVB, partitions, USB/adb defaults; why there is no button-free USB entry
- [fel.md](research/fel.md): Mainline U-Boot from FEL: the A133 DDR4 fix, CPU clock, chunked uploads, fastboot
- [root.md](research/root.md): Persistent root with Magisk, the root helper, first-boot adb provisioning, `fel-install.sh`
- [web-installer.md](research/web-installer.md): The WebUSB installer's design spec: FEL port, fastboot, byte-exact boot patch, per-OS setup
- [screen-policy.md](research/screen-policy.md): Why the panel turned its screen off (Aqara's DND night rule) and what replaced it
- [debloat.md](research/debloat.md): Aqara's packages, the reversible de-bloat, and the cloud daemon and its Wi-Fi watchdog
- [bases.md](research/bases.md): Which wall bases exist, how the panel talks to them, the kiosk's driver
- [base-protocol.md](research/base-protocol.md): The wall base UART protocol, with the emulator proof against Aqara's own tool
- [zigbee2mqtt.md](research/zigbee2mqtt.md): Zigbee2MQTT on the stock radio: Silicon Labs' zigbeed 8.2.2 on Aqara's RCP, panel tests, licences
- [rcp-firmware.md](research/rcp-firmware.md): Could a newer multiprotocol RCP be built for this MG21? CPC protocol, tooling, board config
- [framework-restart.md](research/framework-restart.md): Android 10's ActivityManager breaking during fast kiosk restarts, and `system_server` restarting; the likely (untested) cause of the panel "dropping off USB"
- [boot-animation.md](research/boot-animation.md): What Android 10's boot animation and the 32 MiB boot image allow: texture caching, trim, the size budget, and what was cut from the #63 design
- [idle-cpu.md](research/idle-cpu.md): Why the kiosk uses ~40% of a core at idle: the wake word's embedding model, per-thread numbers, and the options (#105)
- [bluetooth.md](research/bluetooth.md): The panel's Bluetooth (RTL8821CS, shared with Wi-Fi), Android 10's scan rules from AOSP, and what HA needs from an ESPHome Bluetooth proxy (#121)

## Files

- [`design/frame.json`](design/frame.json): the catalog data and the 3D model, and the boot branding's target
- [`design/recovery.json`](design/recovery.json): the steps into recovery mode, as drawing data
- [`installer/`](installer/): Kiln's recipe for this frame
- [`research/`](research/): the notes above
