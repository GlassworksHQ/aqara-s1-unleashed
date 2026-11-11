# S1 Unleashed docs

## Guides

| Guide | What it covers |
|---|---|
| [install.md](install.md) | Installing with the web installer or the command line, undo, troubleshooting |
| [onboarding.md](onboarding.md) | Design notes for the first boot: Wi-Fi (Improv), adding it to Home Assistant, QR sign-in, dashboard picker (the steps themselves are in install.md) |
| [esphome.md](esphome.md) | The panel as an ESPHome device: every entity, the voice assistant, encryption |
| [sendspin.md](sendspin.md) | The native Music Assistant player (Sendspin): setup, pairing, synced playback |
| [sensors.md](sensors.md) | Presence (VI5300 time-of-flight), ambient light, and how the screen uses them |
| [radio-guide.md](radio-guide.md) | Using the Zigbee/Thread radio: ZHA, Zigbee2MQTT, Thread, repeaters, who may connect |
| [radio.md](radio.md) | The radio's technical reference: how each mode works, tests, firmware, the root helper |
| [releasing.md](releasing.md) | App releases: the signing key, GitHub secrets, tagging; how panels update from Home Assistant |
| [legal.md](legal.md) | Legal notes: your own device, no copy protection bypassed, what is and isn't shipped, trademarks, licences ([third-party notices](../THIRD-PARTY-NOTICES.md)) |

The app itself is documented in [app/README.md](../app/README.md) and the boot branding in
[branding/glass-tile](../branding/glass-tile/README.md).

## Research

**[How we got here](research/README.md)** tells the story behind the project, from the first UART
log to the browser installer, and links the detailed notes: hardware, boot log, FEL and the DRAM
fix, Magisk root, the installer design, screen policy, de-bloat, and the wall base protocol.

## Images

`img/` holds the pictures these pages use: `fel-button.jpg` (also used by the installer, so keep
its path), `installer/` (web installer screens, captured from its development demo states),
`voice/` (the Ambient Stage voice overlay) and `panel/` (panel screens).
