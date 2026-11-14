<p align="center">
  <a href="https://www.glassworks.dev/frames/lululemon-mirror">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-frame-lululemon-mirror-on-dark.svg">
      <img alt="lululemon Studio Mirror, a Glassworks frame" src="https://www.glassworks.dev/brand/banner-frame-lululemon-mirror-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">lululemon Studio Mirror</h1>

<p align="center">
  <b>A 40-inch mirror with Android behind it.</b><br>
  A full-length mirror with a 40-inch portrait screen behind it. The original version takes a new Android image over fastboot.<br>
  Originally sold as Mirror, by Mirror.co.
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
  <a href="https://www.glassworks.dev/frames/lululemon-mirror">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/frames/lululemon-mirror/clarity-on-dark.svg">
      <img alt="Clarity" src="https://www.glassworks.dev/frames/lululemon-mirror/clarity-on-light.svg" height="30">
    </picture>
  </a>
</p>

A Glassworks **frame**: the device definition for the lululemon Studio Mirror. Its data is in [`design/frame.json`](design/frame.json) (format: [frames/README.md](../README.md)), and [glassworks.dev/frames/lululemon-mirror](https://www.glassworks.dev/frames/lululemon-mirror) shows it.

## Status

**Status: planned.** Nothing installs on it yet. The target is the Qualcomm (MSM8916) board with stock software 1.x. Later revisions use an NXP i.MX8 board, where only hardware-assisted (JTAG) access has been reported; they are not a target. Screen size doesn't tell them apart ([revisions.md](research/revisions.md)).

The original version runs Android 6.0.1, and S1 Kiosk needs Android 8.0 or newer (`minSdk` 26), so today's app doesn't install on it. Why a newer Android is hard on this board: [mirror-android.md](research/mirror-android.md).

The Clarity badge shows its Clarity grade ([docs/perf.md](../../docs/perf.md)) once one is published; none is yet.

## Hardware

| | |
|---|---|
| Size | About 1336 × 536 × 43 mm (40-inch version, from reviews) |
| SoC | Qualcomm MSM8916 |
| Android | 6.0.1 (stock software 1.x) |
| Screen | 40-inch 1080p, portrait, behind a two-way mirror |
| Presence | 5 MP front camera |
| Audio | Microphone and stereo speakers |
| Mount | Leans on the wall on its stand, or mounts flat on the wall |

## Install

Not available yet. As planned, it installs by flashing system and userdata over fastboot from a computer, not from Kiln.

**That erases lululemon's software for good.** There is no way to back up the stock image first, so there is no way back.

Check before flashing: fastboot must report MSM8916 and the expected partition sizes. If anything differs, stop.

## Panes

| Pane | Support | Note |
|---|---|---|
| Assist | Planned | It has a microphone. |
| Timers | Planned | Needs Assist. |
| Sendspin | Planned | It has stereo speakers. |
| Zigbee and Thread radio | No | No Zigbee or Thread radio. |
| Time zone | No | Worked out: only on the S1 (`aqara-s1-plus`). |
| Diagnostics | Planned | Worked out: needs no special hardware. |
| Bluetooth proxy | Planned | Worked out: has Bluetooth LE (its WCN3660B, read from a teardown). |
| Wall base | No | S1 only. |

"Worked out" means `frame.json` doesn't rate the pane; its support comes from what the pane needs and what the frame has ([how](../README.md#the-panes-a-frame-runs)).

## Research notes

What we found on this device, and how ([all notes](../../docs/research/README.md#the-research-notes)):

- [mirror-android.md](research/mirror-android.md): Can the lululemon Mirror run Android 8+ for the current GeckoView? The signed boot image blocks it; the kexec idea, the paths, blobs and read-only probes (desk research, #39)
- [revisions.md](research/revisions.md): Which Mirror revisions are a target: the later NXP i.MX8 revisions, and why screen size doesn't identify it (read, no hardware)
- [mirror-abis.md](research/mirror-abis.md): Which ABIs the Mirror's userspace runs: 64-bit in the community image, stock untested (#98)

## Files

- [`design/frame.json`](design/frame.json): the catalog data and the 3D model, and the boot branding's target
- [`research/`](research/): the notes above
