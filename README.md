<p align="center">
  <img src="branding/social-preview.png" alt="S1 Unleashed: the Glass Tile mark on a dark background with sky and violet glows. Your S1 Plus, unleashed." width="100%">
</p>

<h1 align="center">S1 Unleashed</h1>

<p align="center">
  <b>Turn the Aqara Panel Hub S1 Plus into a Home Assistant wall panel.</b><br>
  Dashboard, voice assistant, music, presence and the built-in Zigbee/Thread radio, all local.<br>
  Installed from your browser over one USB cable, with a backup first and undo built in.
</p>

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
  Current release: <a href="https://github.com/GlassworksHQ/glassworks/releases/latest"><b>v0.4.1</b></a> ·
  <a href="CHANGELOG.md">What's new</a> ·
  <a href="docs/research/README.md">How we got here</a>
</p>

<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-on-dark.svg">
      <img alt="Built on Glassworks" src="https://www.glassworks.dev/brand/badge-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

## Why

The S1 Plus is good wall hardware: a 1440×720 touchscreen, a microphone and a speaker, a
time-of-flight presence sensor, an ambient light sensor, a Zigbee/Thread radio and a mains relay
base. Out of the box it only runs Aqara's launcher and talks to Aqara's cloud. Its system
WebView is Chromium 74, which is too old for the current Home Assistant frontend.

S1 Unleashed replaces the software layer and leaves the hardware alone. The panel boots into
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
| 🏠 **One ESPHome device** | HA discovers the panel and sets up encryption automatically. You get the screen as a light, presence, light level, a speaker, voice settings and the wall base, plus the dashboard and radio controls. [More](docs/esphome.md) |
| 🎵 **Music Assistant player** | A native Sendspin player that plays in sync with your other speakers. [More](docs/sendspin.md) |
| 👋 **Wakes when you walk up** | The screen wakes as you approach, turns off when the room is empty, and dims at night. It is driven by the panel's own sensors. [More](docs/sensors.md) |
| 📡 **Radio for Home Assistant** | The panel's Zigbee/Thread radio can be a Zigbee coordinator for ZHA or (experimental) Zigbee2MQTT, a Thread border router, or a repeater in your existing network. You switch roles from HA. [How to](docs/radio-guide.md) |
| 🔄 **Updates in Home Assistant** | New versions show up under *Settings → Updates*, and one click installs them. Fixes for the radio side come with them. [More](#updates) |
| 🔌 **Wall base relays** | A driver for the mains relay base that speaks its UART protocol directly, with no Aqara daemon. Not yet tried on a real base. [More](frames/aqara-s1-plus/research/bases.md) |
| 🔒 **No Aqara cloud** | Aqara's launcher, apps, updater and cloud daemon are switched off (reversibly), so the panel stops talking to Aqara. |
| ✨ **Its own boot screen** | The Glassworks splash and boot animation: glass plates drop into place and the top one lights up. [More](branding/glass-tile/README.md) |

<p align="center">
  <img src="branding/glass-tile/preview.webp" alt="The Glassworks boot animation: three glass plates drop onto a dashed outline, the top one lights from sky to violet, and the Glassworks wordmark fades in above &quot;On Aqara S1 Plus&quot;." width="60%">
</p>

## Install

### What you need

- An **Aqara Panel Hub S1 Plus** ([supported hardware](#supported-hardware)).
- A **USB-C data cable** and a **small screwdriver** to open the back cover once.
- **Chrome or Edge** on Windows, macOS or Linux. The installer uses WebUSB, which Firefox and
  Safari don't support.
- **Home Assistant** on the same network (2026.11 or newer recommended).
- About **10 minutes**.

### The web installer (recommended)

**[Open the installer at s1.edm.sh →](https://s1.edm.sh/)**

The installer runs entirely in your browser and uploads nothing. It walks you through five steps:

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
<td width="50%"><img src="docs/img/installer/fel.webp" alt="Installer step 2: a photo of the board with the FEL button circled, and the steps hold, plug in, keep holding for 10 seconds."></td>
<td width="50%"><img src="docs/img/installer/install.webp" alt="Installer step 3: a progress ring at 80 percent with the phases back up, patch, flash and verify."></td>
</tr>
<tr>
<td width="50%"><img src="docs/img/installer/wifi.webp" alt="Installer step 4: a list of Wi-Fi networks in range of the panel and a password field."></td>
<td width="50%"><img src="docs/img/installer/ha.webp" alt="Installer step 4: the Home Assistant address found on the network, signed in, and a prompt to add the discovered panel."></td>
</tr>
</table>

The full walkthrough, per-OS setup and troubleshooting are in **[docs/install.md](docs/install.md)**.

> [!NOTE]
> The web installer has been tested end to end on a real S1 Plus (firmware 4.5.80), from FEL mode
> through the backup, the install and the app setup. The boot patch has been tested on two firmware
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

See [docs/install.md](docs/install.md#command-line) for the details.

## Status

What works today, on v0.4.1. "Verified" means tested on a real panel in a real home.

| Feature | Status |
|---|---|
| Kiosk, HA dashboard, QR sign-in, dashboard picker | ✅ Works |
| ESPHome device with automatic encryption | ✅ Works |
| Voice assistant with on-device wake word, timers, announcements | ✅ Works · far-field tuning ongoing |
| Music Assistant player (Sendspin, synced) | ✅ Works |
| Presence wake, idle-off, night mode, auto-brightness | ✅ Works |
| Aqara cloud and bloat disabled (reversible) | ✅ Works |
| Boot splash and animation | 🧪 The Glassworks redraw has not yet run on a panel. The Glass Tile one before it works |
| Web installer, command-line installer, undo | ✅ Verified end to end (firmware 4.5.80); boot patch tested on two firmware versions |
| Manage page in the browser (`:8099`, sign in through Home Assistant): status, log, diagnostics, signed-in browsers ([guide](docs/manage.md)) | 🧪 New · not yet checked on a panel; settings are read-only for now |
| Panel health in Home Assistant: CPU, temperature, RAM, storage, Wi-Fi, uptime ([entities](docs/esphome.md#diagnostics)) | 🧪 New · not yet checked on a panel |
| Bluetooth proxy for Home Assistant, listening only ([details](docs/esphome.md#bluetooth-proxy)) | 🧪 New · its engine met the S1 (HA found it, no Wi-Fi cost, 2026-11-14); the pane itself not yet |
| App updates from Home Assistant | ✅ Works from v0.4.1 on ([one manual update first](#updates) on older versions) |
| Time zone kept across reboots | ✅ Works (needs the boot software from v0.4.0) |
| Radio: Zigbee coordinator for **ZHA** | ✅ Verified · keep the radio's network settings ([known issue](#known-issues)) |
| Radio: Zigbee coordinator for **Zigbee2MQTT** | 🧪 Experimental · `ember` adapter: forming a network, resuming it and opening joining verified; not yet used for long with real devices |
| Radio: **Thread border router** | ✅ Works · as your only border router (a Matter bulb added with the HA app and controlled from HA) or next to an existing one, also after a Wi-Fi drop and a restart. Must be on HA's network |
| Radio: **Zigbee router (repeater), Thread router (repeater)** | 🧪 Experimental · both joined a real home network as a repeater |
| Radio: only Home Assistant can connect | ✅ Verified |
| Radio firmware updates | 🧪 Experimental · re-flashing Aqara's original firmware verified; no other firmware offered yet |
| Wall base relays | 🧪 Driver done and checked against Aqara's own tool, but not yet tried on a real base |
| Camera pane on a tablet: presence from motion, snapshots, ONVIF/RTSP stream ([entities](docs/esphome.md#camera-entities)) | 🧪 New · not yet run on a device; the S1 has no camera |
| Wi-Fi setup over Bluetooth (Improv) | 🧪 Implemented; lightly tested |
| A short entity list in HA: settings and troubleshooting entities start turned off ([list](docs/esphome.md#categories-and-defaults)) | 🧪 New · the check on a panel HA already has is pending |

## The radio

The S1 Plus has a Silicon Labs Zigbee/Thread radio inside. S1 Unleashed can give it to Home
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
guide is **[docs/radio-guide.md](docs/radio-guide.md)**; the technical reference is
[docs/radio.md](docs/radio.md).

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
  See [docs/install.md](docs/install.md#switching-a-development-build-to-a-release).

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
licences): [docs/legal.md](docs/legal.md).

## Supported hardware

| Device | Status |
|---|---|
| **Aqara Panel Hub S1 Plus, MP-K03D** (wall-mounted; EU, UK, AU and other regions) | ✅ Tested on firmware 4.5.80 |
| S1 Plus for China, JYMKP01LM (86-box) | ❓ Untested. The installer refuses it if the layout doesn't match. |
| S1 Plus Desktop, MP-K05D | ❓ Untested |
| Other Aqara panels and hubs | ❌ Not supported |

Inside: Allwinner A133 (quad Cortex-A53), 2 GiB DDR4, 8 GB eMMC, Android 10, a 1440×720 screen,
a Silicon Labs EFR32 802.15.4 radio, a VI5300 time-of-flight sensor and an ambient light sensor.

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
[docs/install.md](docs/install.md#undo).
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

No. Aqara's updater is disabled while S1 Unleashed is installed. Undo first if you want stock
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
**[How we got here](docs/research/README.md)**.
</details>

## Repository

| Path | What |
|---|---|
| [`kiln/`](kiln/src/README.md) | The browser (WebUSB) installer |
| `frames/` | One folder per device: its Kiln recipe (`installer/`) and its data (`design/frame.json`) |
| [`packages/`](packages/frame-card/README.md) | Code shared with glassworks.dev: the frame card and 3D renderer |
| [`app/`](app/README.md) | S1 Kiosk, the Android app (GeckoView, ESPHome API, voice, Sendspin) |
| `tools/` | Command-line installer, U-Boot/FEL build, boot patch, radio helper, dev tools |
| [`branding/`](branding/glass-tile/README.md) | Boot splash and animation, social preview |
| [`docs/`](docs/README.md) | User guides, plus [research](docs/research/README.md) |
| [`CHANGELOG.md`](CHANGELOG.md) | What changed in each release |

## Credits and licences

Built by [@aguilaair](https://github.com/aguilaair). It stands on the work of
[linux-sunxi](https://linux-sunxi.org/) (sunxi-tools, U-Boot), [Magisk](https://github.com/topjohnwu/Magisk),
[Home Assistant](https://www.home-assistant.io/), [ESPHome](https://esphome.io/),
[Music Assistant](https://music-assistant.io/), [OpenThread](https://openthread.io/),
[microWakeWord](https://github.com/kahrendt/microWakeWord), [openWakeWord](https://github.com/dscripka/openWakeWord) and
[Tango / ya-webadb](https://github.com/yume-chan/ya-webadb).

S1 Unleashed's own code is **MIT** ([LICENSE](LICENSE)), except the Glassworks core in `dev.glassworks`, which is **MPL-2.0** ([its README](app/kiosk/src/main/java/dev/glassworks/README.md#licence)). Third-party parts keep their licences. The main
ones are below. [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) lists everything that ships, with versions
and sources. On the panel, *Open source licences* in the hidden settings (tap the top-left corner five
times) shows the same list with each licence's full text.

| Component | Licence | How it's used |
|---|---|---|
| U-Boot (with our A133 patches) | GPL-2.0-or-later | Built from source in [`tools/fel/`](tools/fel); runs from RAM only |
| Magisk v31.1 | GPL-3.0 | The official release APK, pinned by SHA-256 (the CLI downloads it, the web installer serves the same file); [source](https://github.com/topjohnwu/Magisk/tree/v31.1) |
| ot-br-posix (`otbr-agent`, `ot-ctl`) | BSD-3-Clause | Built by [`tools/radio/`](tools/radio) |
| cJSON, cpp-httplib (in the border-router `otbr-agent`) | MIT | Bundled by ot-br-posix's REST server |
| Silicon Labs `libcpc` and CPC interface | Silicon Labs MSLA, Zlib | Built from Silicon Labs' public source into `otbr-agent` and the radio package, to talk to the panel's own radio |
| GNU C library, GCC runtime | LGPL-2.1-or-later, GPL-3.0 with the GCC exception | Runtime for the radio tools, unmodified from Ubuntu 22.04 |
| Simple Keyboard | Apache-2.0 | Optional on-screen keyboard |
| GeckoView | MPL-2.0 | Maven dependency of S1 Kiosk |
| TensorFlow Lite, Media3 ExoPlayer, ZXing | Apache-2.0 | Wake word inference, audio, QR codes |
| TFLite Micro audio frontend, kissfft | Apache-2.0, BSD-3-Clause | Ported to Kotlin for the wake word's audio features |
| microWakeWord models | Apache-2.0 | **Not shipped.** The panel downloads them itself. |
| Bouncy Castle | MIT-style | ESPHome API encryption |
| Tango (ya-webadb) | MIT | adb over WebUSB in the installer |
| Inter | SIL OFL 1.1 | Boot branding, fetched at build time |
| openWakeWord models (legacy, this release only) | **CC BY-NC-SA 4.0** | **Not shipped.** Only used if you pick a legacy word. Non-commercial. |

**Not affiliated with Aqara or Lumi United.** Aqara and the product names are trademarks of their
owners and are used here only to say which device this works with. No Aqara software is
included in this repository.
