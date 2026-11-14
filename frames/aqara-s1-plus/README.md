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

<p align="center">
  <a href="#install">Install</a> ·
  <a href="#what-you-get">Features</a> ·
  <a href="#status">Status</a> ·
  <a href="#the-radio">Radio</a> ·
  <a href="#updates">Updates</a> ·
  <a href="#known-issues">Known issues</a> ·
  <a href="#undo-and-safety">Undo &amp; safety</a> ·
  <a href="#faq">FAQ</a>
</p>

<p align="center">
  <a href="https://github.com/GlassworksHQ/glassworks/releases/latest"><b>Latest release</b></a> ·
  <a href="../../CHANGELOG.md">What's new</a> ·
  <a href="../../docs/research/README.md">How we got here</a>
</p>

## Why

The S1 Plus is good wall hardware: a 1440×720 touchscreen, a microphone and a speaker, a
time-of-flight presence sensor, an ambient light sensor, a Zigbee/Thread radio and a mains relay
base. Out of the box it only runs Aqara's launcher and talks to Aqara's cloud. Its system
WebView is Chromium 74, which is too old for the current Home Assistant frontend.

Glassworks replaces the software layer and leaves the hardware alone. The panel boots into
**S1 Kiosk**, a small open-source app built on GeckoView (Firefox's engine). It shows your
dashboard and appears in Home Assistant as a single ESPHome device. You don't need an Aqara
account, soldering, or a terminal.

## What you get

**Your dashboard, full screen.** A current Home Assistant frontend in a kiosk that is the panel's
home screen. HA's header and sidebar are hidden. You sign in by scanning a QR code with your
phone, so you never type a password on the wall. You pick which dashboard it shows in HA.

**A voice assistant.** On-device wake word (microWakeWord), Assist pipelines, timers and
announcements, with a calm full-screen overlay while you talk.

| | |
|---|---|
| 🏠 **One ESPHome device** | HA discovers the panel and sets up encryption automatically. You get the screen as a light, presence, light level, a speaker, voice settings and the wall base, plus the dashboard and radio controls. [The entities](../../docs/esphome.md) |
| 🎵 **Music Assistant player** | A native Sendspin player that plays in sync with your other speakers. [Sendspin guide](../../docs/sendspin.md) |
| 👋 **Wakes when you walk up** | The screen wakes as you approach, turns off when the room is empty, and dims at night. It is driven by the panel's own sensors. [Sensors guide](../../docs/sensors.md) |
| 📡 **Radio for Home Assistant** | The panel's Zigbee/Thread radio can be a Zigbee coordinator for ZHA or (experimental) Zigbee2MQTT, a Thread border router, or a repeater in your existing network. You switch roles from HA. [How to](../../docs/radio-guide.md) |
| 🔄 **Updates in Home Assistant** | New versions show up under *Settings → Updates*, and one click installs them. Fixes for the radio side come with them. [Updates](#updates) |
| 🔌 **Wall base relays** | A driver for the mains relay base that speaks its UART protocol directly, with no Aqara daemon. Not yet tried on a real base. [Wall bases](research/bases.md) |
| 🔒 **No Aqara cloud** | Aqara's launcher, apps, updater and cloud daemon are switched off (reversibly), so the panel stops talking to Aqara. |
| ✨ **Its own boot screen** | The Glassworks splash and boot animation: glass plates drop into place and the top one lights up. [Boot branding](../../branding/glass-tile/README.md) |

<p align="center">
  <img src="../../branding/glass-tile/preview.webp" alt="The Glassworks boot animation: three glass plates drop onto a dashed outline, the top one lights from sky to violet, and the Glassworks wordmark fades in above &quot;On Aqara S1 Plus&quot;." width="60%">
</p>

## Status

**Status: beta.**

The Clarity badge shows its Clarity grade ([docs/perf.md](../../docs/perf.md)) once one is published; none is yet.

What works on `main`, as of 2026-11-14. The latest release is v0.4.3, and rows marked New come with
the next one. "Verified" means tested on a real panel in a real home.

| Feature | Status |
|---|---|
| Kiosk, HA dashboard, QR sign-in, dashboard picker | ✅ Works |
| ESPHome device with automatic encryption | ✅ Works |
| Voice assistant with on-device wake word, timers, announcements | ✅ Works · far-field tuning ongoing |
| Music Assistant player (Sendspin, synced) | ✅ Works |
| Presence wake, idle-off, night mode, auto-brightness | ✅ Works |
| Aqara cloud and bloat disabled (reversible) | ✅ Works |
| Boot splash and animation | 🧪 The Glassworks redraw has not yet run on a panel. The Glass Tile one before it works |
| Web installer, command-line installer, undo | ✅ Verified end to end (firmware 4.5.80) with the earlier web installer, s1.edm.sh; boot patch tested on two firmware versions. Kiln has not yet done a full install on a panel |
| Manage page in the browser (`:8099`, sign in through Home Assistant): status, log, diagnostics, signed-in browsers ([guide](../../docs/manage.md)) | 🧪 New · not yet checked on a panel; settings are read-only for now |
| Panel health in Home Assistant: CPU, temperature, RAM, storage, Wi-Fi, uptime ([entities](../../docs/esphome.md#diagnostics)) | 🧪 New · not yet checked on a panel |
| Bluetooth proxy for Home Assistant, listening only ([details](../../docs/esphome.md#bluetooth-proxy)) | 🧪 New · its engine met the S1 (HA found it, no Wi-Fi cost, 2026-11-14); the pane itself not yet |
| App updates from Home Assistant | ✅ Works from v0.4.1 on ([one manual update first](#updates) on older versions) |
| Time zone kept across reboots | ✅ Works (needs the boot software from v0.4.0) |
| Radio: Zigbee coordinator for **ZHA** | ✅ Verified · keep the radio's network settings ([known issue](#known-issues)) |
| Radio: Zigbee coordinator for **Zigbee2MQTT** | 🧪 Experimental · `ember` adapter: forming a network, resuming it and opening joining verified; not yet used for long with real devices |
| Radio: **Thread border router** | ✅ Works · as your only border router (a Matter bulb added with the HA app and controlled from HA) or next to an existing one, also after a Wi-Fi drop and a restart. Must be on HA's network |
| Radio: **Zigbee router (repeater), Thread router (repeater)** | 🧪 Experimental · both joined a real home network as a repeater |
| Radio: only Home Assistant can connect | ✅ Verified |
| Radio firmware updates | 🧪 Experimental · re-flashing Aqara's original firmware verified; no other firmware offered yet |
| Wall base relays | 🧪 Driver done and checked against Aqara's own tool, but not yet tried on a real base |
| Wi-Fi setup over Bluetooth (Improv) | 🧪 Implemented; lightly tested |
| A short entity list in HA: settings and troubleshooting entities start turned off ([list](../../docs/esphome.md#categories-and-defaults)) | 🧪 New · the check on a panel HA already has is pending |

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

### Supported hardware

| Device | Status |
|---|---|
| **Aqara Panel Hub S1 Plus, MP-K03D** (wall-mounted; EU, UK, AU and other regions) | ✅ Tested on firmware 4.5.80 |
| S1 Plus for China, JYMKP01LM (86-box) | ❓ Untested. The installer refuses it if the layout doesn't match. |
| S1 Plus Desktop, MP-K05D | ❓ Untested |
| Other Aqara panels and hubs | ❌ Not supported |

Inside: Allwinner A133 (quad Cortex-A53), 2 GiB DDR4, 8 GB eMMC, Android 10, a 1440×720 screen,
a Silicon Labs EFR32 802.15.4 radio, a VI5300 time-of-flight sensor and an ambient light sensor.

## Install

In the browser, over USB. It backs the panel up first, then roots and flashes it.

Install it from Kiln: [kiln.glassworks.dev/aqara-s1-plus](https://kiln.glassworks.dev/aqara-s1-plus). The steps are in [docs/install.md](../../docs/install.md), and Kiln's recipe for it is in [`installer/`](installer/).

Without root, the app can install over adb on its own, but you lose the radio modes.

### What you need

- An **Aqara Panel Hub S1 Plus** ([supported hardware](#supported-hardware)).
- A **USB-C data cable** and a **small screwdriver** to open the back cover once.
- **Chrome or Edge** on Windows, macOS or Linux. The installer uses WebUSB, which Firefox and
  Safari don't support.
- **Home Assistant** on the same network (2026.11 or newer recommended).
- About **10 minutes**.

### The web installer (recommended)

**[Open Kiln at kiln.glassworks.dev/aqara-s1-plus →](https://kiln.glassworks.dev/aqara-s1-plus)**

Kiln runs entirely in your browser and uploads nothing. It walks you through five steps:

1. **Check this browser.** One-time setup on Windows (a Zadig driver) or Linux (a udev rule). On
   macOS there is nothing to set up.
2. **Enter FEL mode.** With the back cover off, hold the small button on the board while you plug
   in USB-C. FEL is the chip's built-in recovery mode. The installer shows a photo of where the
   button is.
3. **Back up and install.** The installer starts its own bootloader in RAM, then backs up the
   panel's partitions to your computer. Only after that does it patch `boot` (Magisk plus the S1
   helper), flash it and read it back to verify.
4. **Set up the app.** S1 Kiosk is installed as the home screen. From the same page you set the
   panel's Wi-Fi (sent over USB), sign it in to Home Assistant, and choose the radio role,
   language and time zone.
5. **Done.** Home Assistant finds the panel as an ESPHome device. Click **Add**.

<table>
<tr>
<td width="50%"><img src="../../docs/img/installer/fel.webp" alt="Installer step 2: a photo of the board with the FEL button circled, and the steps hold, plug in, keep holding for 10 seconds."></td>
<td width="50%"><img src="../../docs/img/installer/install.webp" alt="Installer step 3: a progress ring at 80 percent with the phases back up, patch, flash and verify."></td>
</tr>
<tr>
<td width="50%"><img src="../../docs/img/installer/wifi.webp" alt="Installer step 4: a list of Wi-Fi networks in range of the panel and a password field."></td>
<td width="50%"><img src="../../docs/img/installer/ha.webp" alt="Installer step 4: the Home Assistant address found on the network, signed in, and a prompt to add the discovered panel."></td>
</tr>
</table>

The full walkthrough, per-OS setup and troubleshooting are in **[docs/install.md](../../docs/install.md)**.

> [!NOTE]
> The web installer has been tested end to end on a real S1 Plus (firmware 4.5.80), from FEL mode
> through the backup, the install and the app setup, on the earlier installer page,
> [s1.edm.sh](https://s1.edm.sh/). That page is still online and runs the same recipe as Kiln. Kiln
> itself has not yet done a full install on a real panel. The boot patch has been tested on two firmware
> versions: 4.5.80 and the December 2025 firmware the panel shipped with. So far that is a small
> number of panels. If anything looks wrong, stop and
> [open an issue](https://github.com/GlassworksHQ/glassworks/issues). Your backup is safe.

### The command line

The same steps as shell scripts, for people who would rather run them by hand (macOS or Linux;
needs `sunxi-fel`, `fastboot`, `adb`, Docker and a U-Boot build):

```sh
tools/install/fel-install.sh ~/s1-backup          # FEL → back up → patch → flash → verify
tools/install.sh --url http://homeassistant.local:8123 \
    --timezone America/Los_Angeles --locale en-US # install and configure S1 Kiosk over adb
```

See [docs/install.md](../../docs/install.md#command-line) for the details.

## The radio

The S1 Plus has a Silicon Labs Zigbee/Thread radio inside. Glassworks can give it to Home
Assistant in one role at a time. You pick the role with **Radio mode** on the panel's device page
in Home Assistant (turn it on there first: HA adds it turned off), or in the panel's own settings
menu (*Radio*), which also shows what to do next, with QR codes for your phone:

- **Zigbee coordinator** for ZHA: radio type EZSP, serial path `socket://<panel IP>:6638`.
- **Zigbee coordinator** for Zigbee2MQTT (experimental): set **Zigbee stack** to *Silicon Labs
  8.2.2* first, then use `adapter: ember` and `port: tcp://<panel IP>:6638`. The panel downloads that
  stack from Silicon Labs itself.
- **Thread border router**: runs its own Thread network, for HA's OpenThread Border
  Router integration.
- **Zigbee router (repeater)** or **Thread router (repeater)** (experimental): joins the network you
  already have and extends it.

Only Home Assistant can connect to the radio unless you allow other computers. The step-by-step
guide is **[docs/radio-guide.md](../../docs/radio-guide.md)**; the technical reference is
[docs/radio.md](../../docs/radio.md).

## Updates

New releases appear in Home Assistant under *Settings → Updates* (the panel's **App update**
entity). Press **Install** and the panel installs it by itself; nothing to confirm on the panel.
Each update is checked against its checksum and the project's signing key first.

- **Panels on v0.4.0 or older need one manual update.** Their update installs from Home Assistant
  always failed with "the APK is not signed". Open the installer, choose **Already installed? Finish
  setting it up**, and follow it (or run `adb install -r` with the new APK). From v0.4.1 on, updates
  from Home Assistant work.
- **The radio side updates with the app** (from v0.4.0), once the panel has the matching boot
  software. Home Assistant's **Radio helper** sensor says when it needs that. To get it, run the web
  installer's full install once more (one press of the FEL button). Panels that skip it keep working
  as before.
- **Moving from a development build to a release** (panels set up with the command line) needs a
  one-time reinstall, because the two are signed with different keys. The installers do it for you
  and keep the panel on its Wi-Fi. Without root over adb, which is the normal case, **the app's
  settings are reset**: you sign in again and may need to add the panel to Home Assistant again.
  See [docs/install.md](../../docs/install.md#switching-a-development-build-to-a-release).

## Known issues

- **ZHA can't create a new network or restore a backup onto the panel yet.** It hangs, because of a
  bug in ZHA's radio library. The fix ([zigpy/bellows#774](https://github.com/zigpy/bellows/pull/774))
  was merged on 2026-11-10 and will work once it reaches a Home Assistant release. Until then, choose
  **keep the radio's network settings** when ZHA asks.
- **Zigbee2MQTT has little time to spare at start-up.** The radio answers its reset in about 4.3
  seconds and Zigbee2MQTT waits 5. A slow moment can make a start fail; it works on the next start.
- **Zigbee2MQTT on the Silicon Labs stack** hasn't been used for long with real Zigbee devices yet.
- **Thread border router:** the panel must be on Home Assistant's network (not a separate IoT
  network). Tested as the only border router and as a second one.
- **Switching a development build to a release** without root resets the app's settings (see
  [Updates](#updates)).

## Undo and safety

- **The backup comes first.** Nothing is written until the panel's own partitions are on your
  computer: in the browser and as a `.zip` with a `SHA256SUMS` file, which the CLI's `--undo`
  also accepts. Keep the backup from your **first** install, because it is the only copy of the
  original software.
- **Every write is verified.** The installer reads each partition back after flashing it and
  stops if anything differs. It refuses hardware it doesn't recognise (SoC id, partition layout)
  before it writes anything.
- **Small footprint.** It changes three partitions: `boot`, the `vbmeta` flags and the boot
  splash. Android, `system` and `vendor` are never modified, and everything Aqara ships is
  disabled rather than deleted.
- **Undo is built in.** In the installer, choose *Undo a previous install*. *Back to how it came
  from Aqara* writes your backed-up partitions back over FEL; a factory reset on the panel then
  removes S1 Kiosk and its settings. *Keep the panel's settings* removes S1 Kiosk over USB (or turns it off, if it
  saved the panel's Wi-Fi) and brings Aqara's apps back; the panel keeps its Wi-Fi. From the CLI:
  `tools/install/fel-install.sh --undo <backup-dir>` or `tools/install.sh --undo`.
- **Hard to brick.** FEL lives in the chip's mask ROM, so it can't be erased. As long as you
  have your backup, you can always get back in.

> [!WARNING]
> This modifies a device you own, at your own risk, and probably voids its warranty. It has
> been tested on a small number of panels. Follow the steps as written.

Legal notes (scope, no copy protection bypassed, no Aqara or Silicon Labs firmware shipped, trademarks,
licences): [docs/legal.md](../../docs/legal.md).

## FAQ

<details>
<summary><b>Do I need to solder or use a serial console?</b></summary>

No. You open the back cover once to press a button on the board while you plug in USB-C. A
serial console was only used during development.
</details>

<details>
<summary><b>Can I go back to stock?</b></summary>

Yes. *Undo a previous install* (or `fel-install.sh --undo`) restores `boot`, `vbmeta` and the
splash from your backup, and a factory reset afterwards brings back Aqara's setup. See
[docs/install.md](../../docs/install.md#undo).
</details>

<details>
<summary><b>Do I need an Aqara account or the Aqara app?</b></summary>

No. A panel that was never set up with Aqara works fine. If it was, it keeps its Wi-Fi.
</details>

<details>
<summary><b>Will my Aqara Zigbee devices keep working?</b></summary>

Not through Aqara: its hub software is switched off. The panel's radio can instead be the Zigbee
coordinator for Home Assistant (ZHA, or Zigbee2MQTT experimentally), and you pair your devices
there again.
</details>

<details>
<summary><b>Does the panel still talk to Aqara's cloud?</b></summary>

No. The kiosk stops Aqara's cloud daemon a few seconds after boot and disables Aqara's apps and
updater. It does reach out to GitHub: once to download the wake word models, and about once a
day to check for S1 Kiosk updates. If you choose the Silicon Labs Zigbee stack, it downloads that
from Silicon Labs' GitHub once. Everything else stays on your LAN.
</details>

<details>
<summary><b>Will Aqara firmware updates still arrive?</b></summary>

No. Aqara's updater is disabled while Glassworks is installed. Undo first if you want stock
updates back.
</details>

<details>
<summary><b>Why GeckoView and not the built-in browser?</b></summary>

The panel's system WebView is Chromium 74 and can't run the current Home Assistant frontend.
GeckoView ships its own up-to-date engine inside the app.
</details>

<details>
<summary><b>Which Home Assistant version do I need?</b></summary>

2026.11 or newer is recommended. Older versions don't set up the panel's encryption by themselves,
and then the radio can't tell which address is Home Assistant's. Development and testing use HA 2026.11 and Music
Assistant 2.10. Voice needs an Assist pipeline; music needs Music Assistant with Sendspin.
</details>

<details>
<summary><b>How was this figured out?</b></summary>

FEL bring-up, a DRAM controller fix for mainline U-Boot, a byte-exact Magisk patch in the
browser, the radio and the wall base protocol: it's all written up in
**[How we got here](../../docs/research/README.md)**.
</details>

## Panes

| Pane | Support | Note |
|---|---|---|
| Assist | Beta |  |
| Timers | Beta |  |
| Sendspin | Beta |  |
| Zigbee and Thread radio | Beta | Zigbee coordinator or router, and Thread. |
| Presence and light | Beta | Presence from the time-of-flight sensor. |
| Wall base | Beta | The driver passes against an emulator that Aqara's own `bracket_cli` accepts. It has not yet been tried on a real base ([base-protocol.md](research/base-protocol.md), [bases.md](research/bases.md)). |

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
