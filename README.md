<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-glassworks-on-dark.svg">
      <img alt="Glassworks: the open core for Home Assistant wall panels" src="https://www.glassworks.dev/brand/banner-glassworks-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Glassworks</h1>

<p align="center">
  <b>The open core for Home Assistant wall panels.</b><br>
  It makes a wall panel one ESPHome device: your dashboard full screen, voice, music, sensors and radios, all local.
</p>

<p align="center">
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

## Have an Aqara Panel Hub S1 Plus? Start here

This repository used to be **S1 Unleashed**. The S1 Plus is now Glassworks' reference frame, and
everything about it is on its frame page.

- **[Install it from your browser with Kiln →](https://kiln.glassworks.dev/aqara-s1-plus)** You
  need one USB cable and Chrome or Edge. Kiln backs the panel up first, and undo is built in.
  Kiln hasn't yet done a full install on a real panel. The tested path is the earlier installer,
  [s1.edm.sh](https://s1.edm.sh/), which runs the same recipe.
- **[The Aqara S1 Plus frame page](frames/aqara-s1-plus/README.md)**: what you get, what works
  today, the radio, updates, known issues, undo and the FAQ.

Already running S1 Kiosk? New versions still arrive in Home Assistant under *Settings → Updates*.

Jump to:
<a id="install"></a><a id="what-you-need"></a><a id="the-web-installer-recommended"></a><a id="the-command-line"></a>[Install](frames/aqara-s1-plus/README.md#install) ·
<a id="why"></a><a id="what-you-get"></a>[Features](frames/aqara-s1-plus/README.md#what-you-get) ·
<a id="status"></a>[Status](frames/aqara-s1-plus/README.md#status) ·
<a id="the-radio"></a>[Radio](frames/aqara-s1-plus/README.md#the-radio) ·
<a id="updates"></a>[Updates](frames/aqara-s1-plus/README.md#updates) ·
<a id="known-issues"></a>[Known issues](frames/aqara-s1-plus/README.md#known-issues) ·
<a id="undo-and-safety"></a>[Undo &amp; safety](frames/aqara-s1-plus/README.md#undo-and-safety) ·
<a id="supported-hardware"></a>[Supported hardware](frames/aqara-s1-plus/README.md#supported-hardware) ·
<a id="faq"></a>[FAQ](frames/aqara-s1-plus/README.md#faq)

## What Glassworks is

Glassworks turns a wall panel into a Home Assistant device. Home Assistant is the brain. The panel
is a screen, microphones, a speaker, sensors and radios on a wall, and Glassworks gives them to
Home Assistant as one ESPHome device. The panel shows a Home Assistant dashboard full screen. You
install nothing in Home Assistant: no custom integration, no cloud, no account with us.

It is opinionated on purpose. [Glassworks Principles](docs/principles.md) says what it builds,
what it doesn't, and the rules every part follows.

A **frame** describes one device. A **pane** is a feature that a frame can run. **Kiln** installs
Glassworks from the browser.

## Frames

| Frame | Status | What it is |
|---|---|---|
| [Aqara S1 Plus](frames/aqara-s1-plus/README.md) | Beta | A133 wall panel, rooted. The reference frame. |
| [Generic Android](frames/generic-android/README.md) | Planned | Any Android device, as an app. No root. |
| [lululemon Studio Mirror](frames/lululemon-mirror/README.md) | Planned | A 40-inch mirror with Android behind it. |

Each row comes from the frame's `design/frame.json` ([the format](frames/README.md)).
[glassworks.dev/frames](https://www.glassworks.dev/frames) shows the same frames with their 3D
models.

## Panes

A pane is a feature the panel can run or leave out. These are the panes today:

- **Assist**: the voice assistant, with an on-device wake word.
- **Timers**: voice timers on the screen, and a chime when one finishes.
- **Sendspin**: a Music Assistant player that plays in sync with your other speakers.
- **Radio**: the panel's Zigbee/Thread radio as a Zigbee coordinator, a Thread border router or a
  repeater.
- **Wall base**: the S1 Plus's mains relay base.
- **Time zone**: the panel's time zone as an entity, kept across reboots.
- **Diagnostics**: CPU, temperature, RAM, storage, Wi-Fi and uptime as entities.
- **Bluetooth proxy**: the panel listens for Bluetooth devices and passes them to Home Assistant.

Each frame's README rates the panes its `frame.json` lists. The [pane API](app/kiosk/src/main/java/dev/glassworks/README.md)
says how to write one, and [What makes a pane](app/kiosk/src/main/java/dev/glassworks/README.md#what-makes-a-pane)
says which features qualify.

## Kiln

Kiln installs Glassworks on a panel from the browser, over WebUSB. It backs the panel up first and
can put it back. Open it at [kiln.glassworks.dev](https://kiln.glassworks.dev). Its code and
design are in [`kiln/`](kiln/apps/kiln/README.md). The S1 Plus also has a
[command-line installer](frames/aqara-s1-plus/README.md#the-command-line).

## Clarity

Clarity is the performance test that every frame runs ([docs/perf.md](docs/perf.md)). It loads
real Home Assistant dashboards on the device and gives the frame a letter grade. The letter names
the biggest dashboard on which every wall-panel task still feels good. No frame has a published grade yet.

## Docs

- [docs/](docs/README.md): the guides, such as install, the ESPHome entities, Sendspin, the
  sensors and the radio.
- [Glassworks Principles](docs/principles.md): what Glassworks is and isn't.
- [How we got here](docs/research/README.md): the research notes, from the first UART log to the
  browser installer.
- [CHANGELOG.md](CHANGELOG.md): what changed in each release.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first, and [Glassworks Principles](docs/principles.md)
before you propose a feature. Questions, ideas and bring-up of a new device go to
[Discussions](https://github.com/GlassworksHQ/glassworks/discussions). Bugs and hardware reports
are issues. Report security problems privately, as [SECURITY.md](SECURITY.md) says. For anything
else private, write to [hi@glassworks.dev](mailto:hi@glassworks.dev). Everyone here follows the
[code of conduct](CODE_OF_CONDUCT.md).

## Repository

| Path | What |
|---|---|
| [`kiln/`](kiln/apps/kiln/README.md) | Kiln, the browser (WebUSB) installer |
| [`frames/`](frames/README.md) | One folder per device: its README, its Kiln recipe (`installer/`) and its data (`design/frame.json`) |
| [`packages/`](packages/frame-card/README.md) | Code shared with glassworks.dev: the frame card and 3D renderer |
| [`app/`](app/README.md) | S1 Kiosk, the Android app (GeckoView, ESPHome API, voice, Sendspin), with the Glassworks core in `dev.glassworks` |
| `tools/` | Command-line installer, U-Boot/FEL build, boot patch, radio helper, Clarity, dev tools |
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

The code in this repository is **MIT** ([LICENSE](LICENSE)), except the Glassworks core in `dev.glassworks`, which is **MPL-2.0** ([its README](app/kiosk/src/main/java/dev/glassworks/README.md#licence)). Third-party parts keep their licences. The main
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

Legal notes (scope, no copy protection bypassed, no Aqara or Silicon Labs firmware shipped,
trademarks, licences): [docs/legal.md](docs/legal.md).

**Not affiliated with Aqara, Lumi United or lululemon.** Aqara, lululemon and the product names
are trademarks of their owners and are used here only to say which device this works with. No
Aqara software is included in this repository.
