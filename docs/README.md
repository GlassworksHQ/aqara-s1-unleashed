# S1 Unleashed docs

Start with **[Glassworks Principles](principles.md)**: what Glassworks is, what it isn't, and the
rules every part follows.

## Guides

| Guide | What it covers |
|---|---|
| [install.md](install.md) | Installing with the web installer or the command line, updating, undo, troubleshooting |
| [onboarding.md](onboarding.md) | Design notes for the first boot: Wi-Fi (Improv), adding it to Home Assistant, QR sign-in, dashboard picker (the steps themselves are in install.md) |
| [manage.md](manage.md) | The Manage page at `http://<panel>:8099/`: signing in through Home Assistant or with the code on the panel, the log, diagnostics, and how it keeps you safe |
| [esphome.md](esphome.md) | The panel as an ESPHome device: every entity, the voice assistant, encryption, app updates |
| [notifications.md](notifications.md) | Notifications from Home Assistant: the `notify` action and its fields, button presses, priorities, Do not disturb, the doorbell blueprint |
| [sendspin.md](sendspin.md) | The native Music Assistant player (Sendspin): setup, pairing, synced playback |
| [perf.md](perf.md) | Clarity, the performance test and grade for any frame: how to run it, what it measures, the rubric and its sources, the report format |
| [size.md](size.md) | APK size per frame and per pane in CI: the parts, the assets lint, the size budgets, the PR comment |
| [sensors.md](sensors.md) | Presence (VI5300 time-of-flight), ambient light, and how the screen uses them |
| [radio-guide.md](radio-guide.md) | Using the Zigbee/Thread radio: ZHA, Zigbee2MQTT, Thread, repeaters, who may connect |
| [radio.md](radio.md) | The radio's technical reference: how each mode works, tests, firmware, the root helper |
| [releasing.md](releasing.md) | For maintainers: the signing keys, GitHub secrets, tagging a release, the radio bundle |
| [branding.md](branding.md) | Where the Glassworks brand lives, and which badge or banner each part of this repo uses |
| [legal.md](legal.md) | Legal notes: your own device, no copy protection bypassed, what is and isn't shipped, trademarks, licences ([third-party notices](../THIRD-PARTY-NOTICES.md)) |

What changed in each release: [CHANGELOG.md](../CHANGELOG.md). The app itself is documented in
[app/README.md](../app/README.md) and the boot branding in
[branding/glass-tile](../branding/glass-tile/README.md).

## Design

[design/kiln](design/kiln/README.md) holds the Kiln prototypes: the installer's screens, and the
data-driven animation that shows each frame's way into recovery mode.

## Research

**[How we got here](research/README.md)** tells the story behind the project, from the first UART
log to the browser installer, and links the detailed notes: hardware, boot log, FEL and the DRAM
fix, Magisk root, the installer design, screen policy, de-bloat, and the wall base protocol.

## Images

`img/` holds the pictures these pages use: `fel-button.jpg` (also used by the installer, so keep
its path), `installer/` (web installer screens, captured from its development demo states) and
`panel/` (panel screens).
