<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-principles-on-dark.svg">
      <img alt="Glassworks Principles" src="https://www.glassworks.dev/brand/banner-principles-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Glassworks Principles</h1>

<p align="center">
  <b>An opinionated, simple Home Assistant panel controller.</b><br>
  What Glassworks is, what it isn't, and the rules every part follows.
</p>

<p align="center">
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30">
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

Glassworks turns a wall panel into a Home Assistant device. It is opinionated on purpose. This page
says what we build, what we don't, and the rules each part follows. When a feature, a PR or an issue
goes against it, point here. If the rule is wrong, change this page first, in a `Decide:` issue,
and the code after.

## In one sentence

**Home Assistant is the brain. The panel is a screen, mics, a speaker, sensors and radios on a
wall, and Glassworks hands them to Home Assistant, simply.**

## Principles

1. **Home Assistant is the brain.** Glassworks has no automations, rules, scenes, schedules or
   user accounts. If it decides something about the home, it belongs in HA.
2. **ESPHome is the wire.** The panel is an ESPHome device. Its state, settings and commands go
   over the ESPHome API, and nothing else controls it.
3. **The dashboard is the UI.** The panel shows a HA dashboard. We don't draw our own home screens.
   We only draw what the panel itself does: setup, voice, timers, notifications, calls.
4. **Nothing to install in HA.** No custom integration, nothing from the marketplace, no cloud, no
   account with us. Adopting the ESPHome device is the whole setup.
5. **Open standards out.** When the panel talks to something other than HA, it uses a standard
   that thing already speaks: Sendspin, Matter, ONVIF. Never a protocol only our code speaks.
6. **Local only.** No cloud, no telemetry, no phoning home. It works with the internet down.
7. **Off by default, cheap when on.** A feature you didn't turn on costs nothing. The slowest
   frame we support (today the S1: 32-bit, with its RAM shared with the web engine) sets the budget.
8. **Write it once.** A feature is written for every device. What only one device has stays in
   that device's frame.
9. **Never brick, always a way back.** Read first, write second. Back up, verify, and offer an undo
   for every change to a device.
10. **Simple beats complete.** One feature done well beats ten settings. If a setting exists only
    because we couldn't decide, decide.

## What we're not building

| Not this | Because | Use instead |
|---|---|---|
| A kiosk browser with hundreds of options | Every option is a setting someone has to understand | The HA dashboard, and the few settings a panel needs |
| A dashboard designer, or our own home UI | HA has dashboards | HA dashboards, and the marketplace's cards if you want them |
| An automation engine | HA has automations | HA automations, scripts and blueprints |
| A fleet manager that works around HA | Settings are entities, so HA can already set them on every panel | Scenes and scripts, or the Manage page, which goes through HA |
| A voice assistant | HA's Assist does the thinking | The panel is an Assist satellite |
| A media centre | Music Assistant exists | The panel is a Sendspin player |
| A security system or an NVR | That needs certification and storage we don't have | The panel can be a camera and a sensor for one |
| A cloud service | Principle 6 | Nothing |
| A HA custom integration | Principle 4 | ESPHome |

## The parts

### Core (`dev.glassworks`)

<a href="https://www.glassworks.dev/brand#parts"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg"><img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30"></picture></a>

What runs on every panel: the ESPHome device, the screen policy (presence, light, idle, night), the
web engine that shows the dashboard, setup, the Manage page, and the pane host.

- **Device-neutral.** No device paths, no vendor services. That's the frame's job.
- **Small.** Something goes into the core only when two panes need it, or every panel does. Until
  then it lives in the pane.
- **Confined.** Android versions are read only in `dev.glassworks.platform`. GeckoView is used only
  in `dev.glassworks.engine.gecko`. Tests enforce both.
- **Stable for panes.** After v1, a change to the pane API needs a reason good enough to break
  every pane.
- MPL-2.0, file by file.

### Frames (`frames/<id>`, e.g. `aqara-s1-plus`)

<a href="https://www.glassworks.dev/brand#badge"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-on-dark.svg"><img alt="Built on Glassworks" src="https://www.glassworks.dev/brand/badge-on-light.svg" height="30"></picture></a>

A description of one device: its sensors, its ABIs, how Kiln installs it, and the panes only it
can have (the S1's wall base).

- **A frame adds no features.** If a feature would make sense on another device, it's a pane, and
  the frame provides the hardware it needs (a `PresenceSource`, a radio backend).
- **A frame hides its vendor.** Vendor daemons, binder calls and serial ports stay behind the
  frame's interfaces.
- **No vendor bytes in the repo.** Describe offsets and symbols, never commit firmware.

### Panes

<a href="https://www.glassworks.dev/brand#parts"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-pane-on-dark.svg"><img alt="Glassworks Pane" src="https://www.glassworks.dev/brand/badge-pane-on-light.svg" height="30"></picture></a>

A feature the panel can have or leave out. The rules are in
[What makes a pane](../app/kiosk/src/main/java/dev/glassworks/README.md#what-makes-a-pane). In short:

1. **It adds something only the panel can do.** If a HA dashboard can do it, it's a dashboard.
2. **It works through ESPHome alone.** State as entities, the settings worth changing from HA as
   config entities, commands as ESPHome actions, reports as events. One approved exception
   (2026-11-13, #118): a report whose name the automation chose, like a notification's button,
   goes out as a bus event (`esphome.<name>`), because HA's event entity only fires names it listed
   up front. It is still ESPHome alone and needs no option in HA.
3. **Other protocols are opt-in outputs.** An open standard something else already speaks, off by
   default, with the pane still working without it.
4. **The panel's sign-in only reads.** The panel may read from HA what ESPHome can't carry (the
   dashboard, the Thread network, a camera stream), and never controls HA with it. A *person*
   signed in on the Manage page acts with their own HA rights, like the HA app does.

Also:

- **Off by default** unless the frame says otherwise. It declares what it `requires`, and frames
  without that hardware show it greyed out.
- **One pane, one feature.** A pane owns its own entities and settings keys, and talks to other
  panes only through `PanelEvent` and services in `PaneContext`.

#### Entities

The ESPHome device is an API, not the settings screen. People set panels up on the Manage page,
and HA's device page stays short. So every entity has a place:

| Kind | Category | Enabled by default | Example |
|---|---|---|---|
| Something people put on a dashboard or automate on | none | Yes | Presence, screen on/off, the media player |
| A setting someone would change from HA, for one panel or the whole house | `CONFIG` | Only the common ones | Night mode, wake word, do not disturb |
| Health and versions | `DIAGNOSTIC` | No, except the few people check | Wi-Fi signal, CPU, radio firmware |

- **A pane adds at most a handful of uncategorised entities.** Everything else is config or
  diagnostic.
- **Enabled by default only if people reach for it day to day.** Restarting the app, yes. Changing
  the radio mode, no: that's set once, on the panel or the Manage page. Anyone can turn a disabled
  entity on from HA's device page.
- **Not every setting is an entity.** Hardware calibration, one-off setup and anything nobody would
  change from an automation stay on the panel and its Manage page.
- **A pane that's off adds nothing to the list a person sees.**

### Libraries (`app/libs/*`)

<a href="https://www.glassworks.dev/brand#parts"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-library-on-dark.svg"><img alt="Glassworks Library" src="https://www.glassworks.dev/brand/badge-library-on-light.svg" height="30"></picture></a>

Protocols, written so anyone can use them: `esphome-api`, `esphome-api-android`, `improv`,
`bluetooth-proxy`.

- **One protocol per library**, with no Glassworks dependency. Someone building something else
  should be able to take it as it is.
- **Plain Kotlin where possible.** JVM libraries have no Android imports, so a JVM test can drive
  them.
- **Tested against the reference.** `esphome-api` is checked against `aioesphomeapi`, and Sendspin
  against a real server. If the reference disagrees, the reference wins.
- MIT.

### Kiln (`kiln/`)

<a href="https://www.glassworks.dev/brand#parts"><picture><source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg"><img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30"></picture></a>

The installer. A web page that installs Glassworks on a frame over USB, with nothing to install
on your computer.

- **RAM first.** Boot from RAM (FEL, `fastboot boot`) before writing anything.
- **Backup, hash, verify.** Every partition we write is backed up and read back.
- **Always an undo.** The restore puts the device back as it was.
- **One recipe per frame.** The core of Kiln doesn't know about any one device.

## Who does what

| Job | Home Assistant | The panel |
|---|---|---|
| Decide what happens | Automations, scripts, scenes | Nothing |
| Show the home | Dashboards | Shows the dashboard |
| Settings for every panel | Scenes and scripts, on config entities | Applies its own |
| Who may change what | HA users and permissions | Follows them |
| Voice | Assist (wake word in HA or on the panel, STT, intent, TTS) | Mics, speaker, wake word |
| Music | Music Assistant | A Sendspin player |
| Hardware: presence, light, radio, Bluetooth, camera, NFC | Uses it as entities | Provides it |
| Interrupt the user (ring, alert, wake the screen) | Says when, through an ESPHome action | Does it |

## Before you add something

Ask, in this order. A "no" to any of them means it doesn't ship as it is.

1. **Can HA already do it?** With an automation, a script, a dashboard or a card? Then document the
   recipe instead.
2. **Does it need something installed in HA?** Then rethink it.
3. **Does it work over ESPHome alone, with no setup beyond adopting the device?**
4. **If it speaks another protocol: is that an open standard, opt-in, and is the feature still
   whole without it?**
5. **Is it off by default, and cheap on the slowest frame when it's on?**
6. **Does it work on every frame that has the hardware, not only the one you're testing on?**
7. **Is there a way back?** For anything that writes to a device.

When the answer is unclear, open a `Decide:` issue rather than building it.
