<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/banner-core-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Glassworks pane API</h1>

<p align="center">
  <b>Features for an open Home Assistant wall panel, written once and run on any device.</b><br>
  A pane brings its own entities, lifecycle and settings. The panel's core hosts it.
</p>

<p align="center">
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30">
    </picture>
  </a>
  <a href="#licence">
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

Glassworks is the device-neutral core being pulled out of
[S1 Unleashed](../../../../../../../README.md) one step at a time. A **frame** describes a device.
A **pane** is a feature: the Music Assistant player, voice, timers, the radio. This package
(`dev.glassworks.pane`) is the contract between the two.

It lives inside the S1 app for now. It becomes its own Gradle module (`:glassworks-pane-api`) in
[migration step 6](https://github.com/GlassworksHQ/glassworks/issues/21). The API will
change until Glassworks v1.

## What's in it

| Type | What it is |
|---|---|
| `Pane` | A feature: its Home Assistant entities, `start`/`stop`, and what it does when the panel changes. |
| `PaneContext` | What the core gives a pane: the app context, `prefs(namespace)` for its settings, the device identity, `publishStates()`, `emit()`, `entitiesChanged()` and `sendEvent()`. |
| `PanelEvent` | Things that happen on the panel: a setting changed, voice took the speaker, a timer changed or finished, Home Assistant subscribed. A pane raises one with `PaneContext.emit`. |
| `EntityRegistry` | Builds the device's entity list from the core's entities and every pane's, and refuses two entities with the same object id. |
| `PaneDefinition` | What the core knows about a pane for the whole process, also while it isn't running: its on/off, setup steps, settings sections and adb extras. |
| `PaneSwitch` | A pane's on/off (#67): a flag in the pane's own settings file. The shell runs the pane only while it is on. The settings switch, the setup card, the Home Assistant switch and the adb extra all come from it. |
| `SetupStep` | Something first-boot setup waits for from a pane: state plus setup channel requests. |
| `PaneChoice` | A pane's card on setup's Panes step: a title, a line and a switch. |
| `SettingsSection` | A section of the settings: typed items (`SettingsItem`) the panel's menu draws, and the Manage page (#33) will. `settingsOrder` places them. |
| `ScreenPolicy` | (`dev.glassworks.screen`) Wake on approach, idle off, the night window and its timeout, the brightness curve and the screen mode. It reads the frame's `PresenceSource` and `LightSource` and the app's `ScreenSettings`. One per process, on `GlassworksRuntime.screen`. |
| `Platform` | (`dev.glassworks.platform`) What the running Android can do, as named queries (`hasNotificationChannels`, `hasWindowInsetsController`, …) and small shims. The only place that reads `Build.VERSION.SDK_INT`. |
| `NotificationsPane` | (`dev.glassworks.notifications`) Notifications from Home Assistant: the `notify` action, Do not disturb, the rules for each priority, and a tapped button as the bus event `esphome.notification_action`. A core pane, off by default. The panel's overlay draws what its `NotificationsHub` holds. How to use it: [docs/notifications.md](../../../../../../../docs/notifications.md). |
| `DiagnosticsPane` | (`dev.glassworks.diagnostics`) The panel's health as diagnostic entities: CPU, temperature, RAM, storage, Wi-Fi, uptime, the last restart reason. A core pane, so every frame has it. The entities are in [docs/esphome.md](../../../../../../../docs/esphome.md#diagnostics). |
| `BluetoothProxyPane` | (`dev.glassworks.bluetoothproxy`) The panel as an ESPHome Bluetooth proxy (#121). The work is in the [bluetooth-proxy library](../../../../../../libs/bluetooth-proxy/README.md); the pane gives it the device's server and adds two diagnostic entities. Its page is the frame's to draw ([`BluetoothProxyModel`](bluetoothproxy/ProxyPhase.kt)); the S1's is `BluetoothProxyPage`. See [its README](bluetoothproxy/README.md). |
| `Engine` | (`dev.glassworks.engine`) The web engine that shows the dashboard: open a page, load, reload, go back, restart after a crash, and talk to the page's scripts. `GeckoEngine` (`dev.glassworks.engine.gecko`) is the only code that uses GeckoView. One per process, on `GlassworksRuntime.engine`. |

## What makes a pane

Glassworks is native to Home Assistant: a panel joins HA as an ESPHome device and needs nothing
else. Panes keep it that way. The reasons are in
[Glassworks Principles](../../../../../../../docs/principles.md). Before you write a pane, check
it against these four rules.

### 1. It adds something only the panel can do

A pane uses the panel's hardware or its place on the wall: the camera, mics, speaker, radios,
Bluetooth, NFC, its sensors. If a Home Assistant dashboard can already do it, it belongs in a
dashboard, not in a pane.

| A pane | A dashboard |
|---|---|
| Wake on presence, the room's light level | Clock, weather, a photo frame |
| Voice, timers, announcements | An alarm keypad |
| A Zigbee or Thread radio, a Bluetooth proxy | A calendar, a to-do list |
| The panel's camera in HA | Showing other cameras |

### 2. It works through ESPHome alone

Every pane works fully through the panel's ESPHome device. Adopting the device in HA is the only
setup.

- Its **state** is entities.
- The **settings worth changing from HA** are config entities (`EntityCategory.CONFIG`). The rest
  stay on the panel and its Manage page.
- Its **commands** are ESPHome actions (`esphome.<node>_<action>`).
- What it **reports** goes out as events (`EventEntity`). The one exception (#118, approved
  2026-11-13): a report whose name the automation chose, like a notification's button id, goes out
  as a bus event, `ctx.fireEvent(HomeAssistantEvent("esphome.<name>", data))`. HA refuses event types
  an event entity didn't list up front, and a bus event needs no option in HA either.

Settings for the whole house are HA's job: a scene or a script sets the same entity on every panel.

### 3. Other protocols are opt-in outputs

A pane can also speak another protocol when that makes the panel do more. Sendspin (Music
Assistant) and Matter already do. That's allowed when all of these hold:

- **It's an open standard that something else already speaks**, with no code of ours on the other
  end: ONVIF and RTSP (HA's ONVIF integration, Frigate, UniFi Protect), Sendspin, Matter, AirPlay.
- **It's off by default**, with its own switch in the pane. That switch is a config entity too.
- **The pane works without it.** Turn it off, and rule 2 still holds.
- **It has no settings screen of its own.** Its settings are entities, like everything else.

### 4. The panel's HA sign-in only reads

The panel signs in to Home Assistant as a user, to show the dashboard. A pane may use that sign-in
to read from HA what ESPHome can't carry: the preferred Thread network (the radio pane), or a
camera's stream. It never uses it to control HA, or to watch entities in place of ESPHome.

When a read needs an administrator and the panel's user isn't one, ask an admin to sign in once,
use the token for that read and revoke it straight away, as
[`ThreadJoin.kt`](../../sh/edm/s1kiosk/auth/ThreadJoin.kt) does. The panel keeps no admin
credentials.

A *person* signed in on the Manage page is different: they act with their own HA rights, like the
HA app does.

### Never

- A Home Assistant custom integration, or anything installed from the marketplace.
- A cloud service, or an account with us.
- A protocol only our own code speaks.
- Panels coordinating among themselves in place of HA.

### Entities

The ESPHome device is an API, not the settings screen. Keep the list a person sees short:

| Kind | Category | Enabled by default | Example |
|---|---|---|---|
| Something people put on a dashboard or automate on | none | Yes | Presence, screen on/off, the media player |
| A setting someone would change from HA | `CONFIG` | Only the common ones | Night mode, wake word, do not disturb |
| Health and versions | `DIAGNOSTIC` | No, except the few people check | Wi-Fi signal, CPU, radio firmware |

- A pane adds at most a handful of uncategorised entities.
- **Enabled by default only if people reach for it day to day.** "Restart app" is on: people use
  it. "Radio mode" is off: you set it once. Everything else gets `disabledByDefault = true`. HA
  creates it and leaves it off, so it doesn't show on the device's cards. Anyone can turn it on from the device page, and
  the panel's settings menu and Manage page can always change it.
- A pane that's off keeps its entities for now, with no state where it has none to give (#67), so
  the list doesn't change. Whether an off pane should remove them waits on a panel test of what Home
  Assistant then does with them (#67, #132).

How to apply it in code:

1. Pick the category from the table: what people look at or automate on gets none, settings get
   `EntityCategory.CONFIG`, health and versions get `EntityCategory.DIAGNOSTIC`.
2. Pass `disabledByDefault = true` to the entity's constructor unless people use it day to day. A
   setting you change once, a one-off action (join, flash, sign out) and a value only troubleshooting
   needs are off. An update entity stays on: HA shows updates only while it is on.
3. Regenerate the pin goldens. A line ends with `disabledByDefault` when the entity is off, so the
   review shows the choice.

HA uses `disabledByDefault` only when it first registers the entity. Changing it later doesn't
change a panel HA already has; changing the category does
([esphome.md](../../../../../../../docs/esphome.md#a-panel-that-ha-already-has)). Decide it when you
add the entity.

**A pane's own on/off switch (#67)** is a `SwitchEntity` with `EntityCategory.CONFIG` and
`disabledByDefault = true`: you set it once, from the panel's settings or the device page. It stays
in the list while the pane is off, so turning the pane back on doesn't need HA to list the panel's
entities again.

Every S1 entity, with its category, default and reason:
[esphome.md](../../../../../../../docs/esphome.md#categories-and-defaults).

### Example: the camera pane

| Part | Rule | On by default |
|---|---|---|
| Presence from the camera (`binary_sensor`) | 2 | Yes, when the pane is on |
| Snapshots as an ESPHome camera entity | 2 | Yes, when the pane is on |
| ONVIF/RTSP stream for Frigate or UniFi Protect | 3 | No |

## Writing a pane

A pane takes its `PaneContext` in the constructor and overrides only what it needs. This is the
shape of the Sendspin pane, the first one moved out of the S1 app
([`SendspinPane.kt`](../../sh/edm/s1kiosk/sendspin/SendspinPane.kt)):

```kotlin
class MusicPane(ctx: PaneContext) : Pane {
    override val id = "music"

    private val player = MyPlayer(ctx.context, onChange = ctx.publishStates)

    override fun entities() = listOf(
        TextSensorEntity("music_status", "Music status", "mdi:speaker", get = { player.status() }),
    )

    override fun start() = player.start()
    override fun stop() = player.stop()

    override fun onEvent(event: PanelEvent) {
        if (event is PanelEvent.SpeakerClaimed) player.setDucked(event.claimed)
    }
}
```

`PanelEvent` is an open interface. A pane handles the events it cares about and ignores the rest:
a `when` statement (not an expression) may simply leave out the events it does not use, as
`SendspinPane` does. A new event does not touch the panes that do not use it. A pane does not have
to add entities: [`TimersPane.kt`](../../sh/edm/s1kiosk/timers/TimersPane.kt) has none and only
reacts to `TimersChanged` and `TimerFinished`. The voice assistant
([`AssistPane.kt`](../../sh/edm/s1kiosk/voice/AssistPane.kt)) is the pane that raises those: it calls
`ctx.emit(PanelEvent.TimerFinished(name))`, and `SpeakerClaimed` while it listens and talks.

The core raises `HomeAssistantSubscribed(address, authenticated)` each time Home Assistant subscribes
to the states. `authenticated` is true when the connection uses the key Home Assistant provisioned.
Only then is the address proven to be Home Assistant's, so a pane that trusts the address checks it.
The radio ([`RadioPane.kt`](../../sh/edm/s1kiosk/radio/RadioPane.kt)) does: it lets only that
address reach its TCP services.

On an authenticated connection the core also asks Home Assistant for the time, and raises
`HomeAssistantTimeZone(posix)` with HA's zone as the POSIX rule it sends (`CET-1CEST,M3.5.0,M10.5.0/3`,
not an IANA id). The time zone pane offers it on its page.

### Entities that change, and events

A pane's entity list may change while the panel runs. The wall base
([`WallBasePane.kt`](../../sh/edm/s1kiosk/base/WallBasePane.kt)) adds relays, buttons and power
sensors when a switch base is mounted. The pane then calls `ctx.entitiesChanged()`. The core builds
the device's list again from its own entities and every pane's `entities()`, and gives it to Home
Assistant, which reconnects and lists the entities again. `entities()` must return the new list from
then on.

To fire an ESPHome event, a pane calls `ctx.sendEvent(entity, type)` with an `EventEntity` from its
current list. For an event whose name isn't known up front, `ctx.fireEvent(HomeAssistantEvent(...))`
fires it on HA's bus instead ([the exception above](#2-it-works-through-esphome-alone)). Keep the instances: the server only fires events for the entity objects it was given,
so build the list once per state, not on every `entities()` call. A pane does not hold the API
server for this. The core owns it, including its restarts.

Entities come from [esphome-api](../../../../../../libs/esphome-api/README.md). Home Assistant
keys them by object id, so an object id is permanent: renaming one makes a new entity in every
home that already has the panel.

## Settings

A pane gets its settings file with `ctx.prefs(id)`, where the namespace is the pane's own id. It
wraps the file in its own typed class and reads and writes only its own keys there, as the Sendspin
pane does with [`SendspinPrefs`](../../sh/edm/s1kiosk/sendspin/SendspinPrefs.kt):

```kotlin
private val prefs = SendspinPrefs(ctx.prefs(id))
```

The frame decides which file holds a namespace. A namespace is the owner of a set of keys, not a
file or a key prefix. On the S1 every pane's settings stay in `kiosk.xml`, under the key names they
had before panes ([`KioskPrefs.PANE_FILES`](../../sh/edm/s1kiosk/KioskPrefs.kt)), because panels
update in place and no stored value may move. `PrefsPinTest` pins every file, key, type and default.
`ctx.prefs` refuses a namespace the frame does not know.

When a key in a pane's file changes, the core raises `PanelEvent.SettingChanged(key)` to every pane.
A pane matches its own key constants and ignores the rest.

A pane may also keep a file of its own that no other code reads, such as Sendspin's pairing secrets
in `sendspin.xml`. The settings menu, the setup screen and the adb extras reach a pane's settings
through its definition (below), which uses the same typed class.

## Setup steps, settings sections and extras

A running `Pane` exists only while the device runs, but the setup screen decides on its first frame
and the settings menu opens at any time. So a pane also has a `PaneDefinition`, made once per
process. The app makes the core's (`GlassworksRuntime.paneDefinitions`), and the frame adds its own
(`Frame.paneDefinitions`). The definition's `id` is the pane's.

| Member | What it is |
|---|---|
| `switch` | The pane's on/off, a `PaneSwitch`, or null for a pane that always runs or keeps its own (the radio's is its mode). See [On and off](#on-and-off). |
| `setupSteps` | What first-boot setup waits for from the pane, while it is on. Each is its own step under Panes, after the person chose the panes. |
| `setupChoice` | The pane's card on the Panes step, or null. By default the switch's, when it is `inSetup`. A pane with `setupSteps` needs one. |
| `settings()` | The pane's settings sections, as they are now. Called each time the menu opens. |
| `extras` | adb intent extras for its settings (`IntentExtra.bool`, `float`, `string`). `restartsApp` for a setting read only at start. The switch's extra comes on its own (`intentExtras()`). |

**A setup step is state plus requests.** `done()` says whether the step is answered; `channelState()`
is its part of the setup channel's state, under the step's id; `ops` are the requests that answer
it, on the wire as `<step id>.<op>`. That way the installer can finish every step without the
screen, and the setup screen is one more client. `SetupChannelStepsTest` fails if a step has no
requests that finish it. The radio's step is
[`RadioDefinition`](../../sh/edm/s1kiosk/radio/RadioDefinition.kt). Its `radio.set_mode` accepts
only the ids the step offers (`RadioStep.OPTIONS`, the state's `radio.options`). Any other mode is
refused, so a mode only appears to the installer once the step offers it. Asking for the mode in
effect, running or on its way, answers the step and leaves the radio alone, and the state's
`radio.answered` says whether the step is answered. Kiln uses both for a fresh panel kept on Off.

**A step id is the step's own.** `GlassworksRuntime.setupSteps` gathers the steps through
`SetupFlow.usable`. It leaves out, with an error in the `S1Kiosk` log, a step whose id is a core
step (`wifi`, `ha`, `panes`, `signin`, `done`) or a setup-channel state key (`ip`, `ha`), or an earlier step's,
and a step whose request names are already taken. A bad step costs only itself: the app still
starts and the channel still answers. `FramePanesTest` checks a frame's step and section with a
fake frame.

**On the panel, panes are a group.** The setup screen's Panes step only chooses: one card per
`setupChoice` in definition order, a title, a line under it and a switch. Continue calls every card's
`apply(on)` off the main thread, each within 30 s; it returns null or why not, in words. Then each
pane that is on has its steps under Panes, in order. A step draws its own card: `title` names its row in the rail,
and `screen(context, host)` returns a `SetupScreen` that fills a `SetupCard` (title, body, content,
status, primary and secondary buttons) and calls `host.next()` when it is answered. The shell names
no pane. `GlassworksRuntime.setupPanes` pairs each choice with its steps and leaves out a pane with
steps but no card, with an error in the log: the person could never turn it on or off.

**A settings section is data.** Items are `Text`, `Switch`, `Slider`, `Duration`, `TimeRange`, `Choice`,
`Link`, `Note`, `Info` and `Group`, each with its value and a `save(value, done)`, and the read-only
`Status` (the line under a page's heading), `Tiles` (readings with their last hour, #212) and `States`
(entities as Home Assistant shows them). A slider, a duration (picked from its choices, with units), a
choice and a daily window are never typed (#63 round 2), so a browser can draw the same controls.
`Info`, `Status`, `Tiles`, `States` and a `Slider` not being dragged are read again every couple of
seconds while the page is open. If the page itself changes (a base mounted, the pane turned off from
Home Assistant, the device started), the menu draws it again from the definitions. A `Switch` can ask first (`confirm` gives a `Confirm`: the question, what it costs,
the button's word). Each change applies on its own, as the person makes it
(#63): there is no "Save & restart". `done` can come from another thread (the radio's allow-list) and
reports a problem in words. An item that only takes effect when the app starts again sets
`restartsApp`; the menu then says so and offers *Restart now*. A `Text` says its value in words with
`shown` ("After 2 minutes"). A section's `heading` is its page's title when it says more than its
name in the list. `Native` is the one panel-only item: a view for what has no typed item yet (the
radio's modes and card). A browser can't draw it, so new sections should not need it.

**Order.** `settingsOrder` puts the core's Dashboard, Screen and Network first, then the panes'
sections in definition order (Voice, Music, Radio, Wall base, Time zone), then Log, Security and
About: the Manage page's nav (#33). A section without items is left out. The panel's menu draws them
in the same rail as setup (`ui/GroupedRail`): the panes' sections under a Panes group that opens while
one of them is picked. Panes whose `master` is off fold under "Off · N" at the end of the group
(#212); the fold opens while one of them is picked. Every pane's row has a status chip after its name
(#243): Running, On, Off, Can't run or Problem, from `paneState`, which the Manage page uses too. A
section's `status` is the pane's own state and line (the radio's role, Voice's wake word); without
one, its switch says On or Off. Status colours mean status only, and every name starts at the same x.

**A pane's section has its on/off.** `paneSections` gives each pane's section a `master`, the
switch at the top right of its page, made from the pane's setup card (`setupChoice`), else from its
`switch`, unless the pane gives its own (the radio's asks before turning off). `unavailable` says, in
a few words, why the pane can't run on this panel: the nav greys it with "Can't run" and its page says
why, without a switch.

**Every pane's page shows its state (#212).** No page says the pane has no settings.

- A page reads its running pane: override `settings(running: Pane?)` instead of `settings()`. `running`
  is the pane the shell built, also while it is off, or null while the panel's device isn't running
  (`GlassworksRuntime.panes`). Show what Home Assistant has for the pane, and the controls it has there.
- A pane with no section of its own gets one built from its entities (`inHomeAssistant`): each name
  and its state as Home Assistant shows it (`ApiEntity.shownState`). Entities off by default in HA are
  dimmed.
- A page of its own says which of the pane's entities it covers (`SettingsSection.covers`: it shows
  them, or leaves them out on purpose). The core then ends the page with "In Home Assistant" for the
  other entities, or adds nothing when none is left, so no entity is listed twice. A page that leaves
  `covers` null gets nothing added.
- While a pane's `switch` is off, its page is the switch's `offNote` (what being off means, in a
  sentence) and its entities, each `Unavailable`. Its own sections wait until it is on.
- Without a running device, the page says so (`NOT_RUNNING`).

## On and off

Every pane the person may turn off has a `PaneSwitch` on its definition (#67): a flag under its own
key, in its own settings file, saved with `commit()` so a restart right after can't lose it (#185).
The default is on for the S1's panes, as before the switch.

- **The shell runs a pane only while it is on.** It builds every pane, so an off pane's entities
  stay, but starts only the ones that are on. When a switch's key changes, it starts or stops that
  pane on the device thread, then raises `SettingChanged` for the key, so a pane that has work to
  undo outside the app (the time zone, Aqara's daemon) hears its own switch after `stop`. At start,
  once it listens to the settings, the shell applies the switches again and raises `SettingChanged`
  for each pane that is off, so such a pane puts that work right after a restart too.
- **A pane can start again after `stop`.** Make the threads in `start`, not in the constructor, and
  let `stop` free them. `stop` also runs when the app stops, so a pane that must undo something only
  when it is turned off does it on its own `SettingChanged`.
- **From one switch the core makes:** the switch at the top of the pane's settings page, its card on
  setup's Panes step (`inSetup`), a Home Assistant config switch (`objectId`, always off by default
  in HA: people set it once, #132, #208; `authOnly`, since turned on a pane can open the microphone
  or a port), and the adb extra `--ez <key> true|false`.
- **A voice assistant pane** says so on its switch (`voiceAssistant`): the shell offers Home Assistant
  voice features only while that switch is on, so turning it on or off asks Home Assistant to
  reconnect. The shell names no pane for this.

| Pane | Key | HA switch | Setup card |
|---|---|---|---|
| Voice (`assist`) | `voice_enabled` | `voice` | yes |
| Music (`sendspin`) | `sendspin_enabled` | `sendspin` (the entity it always had) | yes |
| Wall base (`wall-base`) | `wall_base_enabled` | `wall_base` | yes |
| Time zone (`time-zone`) | `time_zone_enabled` | `keep_time_zone` | no |
| Diagnostics (`diagnostics`) | `diagnostics_enabled` | `diagnostics` | no |
| Bluetooth proxy (`bluetooth-proxy`) | `bluetooth_proxy_enabled` (off by default) | `bluetooth_proxy` | yes, unless the panel has no Bluetooth LE |

The radio has no `PaneSwitch`: its on/off is its mode (`Off`), and its card and settings switch are
its own. Timers have none either: they come only from voice.

## The Manage page

The panel serves `http://<panel>:8099/` ([docs/manage.md](../../../../../../../docs/manage.md), #33),
on the same port as the QR-code sign-in pages. The core has the whole page; a frame gives it one
`ManageHost`.

| Part | What it does |
|---|---|
| `web.HttpServer`, `web.PageShell` | The small HTTP server, and the one look every page shares, the QR pages included. Change the look in `PageShell` only |
| `manage.ManageServer` | Routes, the session gate, the `Origin` and CSRF checks, the actions. The QR paths it's given go to the QR server untouched, without a session |
| `manage.HaSignIn` | Sign in through HA as an OAuth client of the local HA only: PKCE, `auth/current_user` must say `is_admin`, the refresh token revoked at once |
| `manage.SessionStore` | The page's own sessions: a SHA-256 of the cookie on disk, 30 days when remembered, tied to the HA address |
| `manage.PanelCode` | The 6-digit code on the panel's screen: one purpose and browser, 5 minutes, 5 tries, 10 wrong in 15 min locks entry |
| `manage.ManageLog`, `LogcatTail`, `Redact` | The 7-day log across reboots, fed from the app's own logcat, and the redaction for it and the diagnostics file |
| `manage.ManageHost` | What the frame supplies: `status()` (`PanelStatus`: tiles, `PaneRow`s with a `PaneState`, the settings sections), `actions`, `diagnostics()` |

The settings sections are the ones the panel's menu draws (`paneSections`, `settingsOrder`), and
they show read-only: `Text`, `Switch`, `Info` and `Note` show their values; `Slider`, `Duration` and
`TimeRange` show their value in words; `Link` and `Native` say "On the panel". A pane's row
(`manage.paneRow`) takes its name and on/off from its section: the title, `unavailable` for Can't run,
the `master` switch (#67) for Off. A pane that is off shows its state and nothing else: no settings,
no links. The frame adds only what a running pane is doing. Editing in a browser needs a typed value
and action model instead of closures and views; that is phase 2.

## Android versions

Code outside `dev.glassworks.platform` never reads `Build.VERSION.SDK_INT`. If you need a version
check, add a query to `Platform` that names the capability and says in its KDoc where the boundary
comes from, then call it. `PlatformTest` pins each boundary, and `PlatformConfinementTest` fails
`./gradlew test` with the file and line of any other read. The libraries in `app/libs` do not depend
on Glassworks, so each keeps its own checks in an internal `Compat.kt`, which the same test allows.

## Frames

A frame (`dev.glassworks.frame.Frame`) is what the core asks the device:

| Member | What it is |
|---|---|
| `board` | The `board` TXT record in the mDNS advert. |
| `presence`, `light` | The sensors `ScreenPolicy` reads. Null when the device has none. A `PresenceSource` reports near or not with the raw value, and may support `setRange(metres)`. |
| `cpuThermalZone` | The `type` of the thermal zone that measures the CPU, for the diagnostics pane. Null (the default) means no temperature entity. The S1's is `cpu_thermal_zone`. |
| `systemSettingsIntents()` | The device's own settings screens, best first. `Frame.systemSettings(context)` picks the first that resolves. |
| `wifiSettingsIntents()` | Where to choose a Wi-Fi network, best first. The default is Android's Wi-Fi panel and settings. |
| `paneDefinitions(context)` | The definitions of the frame's panes: their setup steps, settings sections and adb extras. |
| `panes(ctx, device)` | The panes only this device has. The shell builds them with its own, once per device run, and runs them like any other. |

The app makes one frame at start, and `GlassworksRuntime` holds it. The device shell
(`PanelDevice`) reads only these members; `PanelDeviceFrameTest` fails if it reaches past them.

The S1's frame is [`AqaraS1Plus`](../../sh/edm/s1kiosk/frame/AqaraS1Plus.kt) (`aqara-s1-plus`). Its
presence source is the VI5300 time-of-flight sensor, with Lumi's `proximity_threshold` range table,
and its light source is the MK6212 sensor. Its panes are the wall base (with Aqara's cloud daemon,
which holds the wall base UART) and the time zone. It also holds the root update service. The rest of
the planned frame (screen off, installer) comes in the next migration steps.

## Engine

The shell shows the dashboard through `dev.glassworks.engine.Engine`, not through GeckoView.
`engine.open(activity, host, background)` gives a `Page`. The shell adds `page.view` to its layout
and calls `load`, `reload`, `goBack`, `restart` and `post`. The page reports to the shell's
`PageHost`, always on the main thread:

| `PageHost` call | When |
|---|---|
| `onPageStart(url)`, `onPageStop(success)`, `onFirstPaint()`, `onLocationChange(url)` | The page loads. |
| `redirect(url)` | Before each top-level load in the page's own window. Return a URL to load instead, or null. |
| `onContentGone(killed)` | The content process crashed or was killed. The shell calls `page.restart()` and loads again. |
| `onAndroidPermissions(permissions, answer)` | The page needs Android runtime permissions, which only an activity can ask for. |
| `onConnect(port)`, `onPortMessage(port, message)` | A page script opened a native port, or sent a JSON object on one. `PagePort` has the page's `url` and `topLevel`. |

`engine.isContentProcess(name)` says whether one of the app's processes is a web content process.
The diagnostics pane counts them. For GeckoView these are the `:sandboxed_tab…` processes.

It is a kiosk engine. It allows every page permission without a prompt and grants the microphone and
the camera. It reads `EngineSettings` (secure-context hosts, zoom, remote debugging) once, at the
first `open`, so a change needs a process restart. `engine.started` says what it started with.

`GeckoEngine` takes the S1's values: the built-in extension (`assets/extension`, id
`helper@s1kiosk.edm.sh`, native app `s1kiosk`), the log tag and the settings from `KioskPrefs`.
GeckoView allows one runtime per process, so `GlassworksRuntime` holds the one engine.
`EngineConfinementTest` fails `./gradlew test` if code outside `dev.glassworks.engine.gecko` names
`org.mozilla.gecko`.

## Threads

The core calls a pane from more than one thread. `start` runs on the device thread, `stop` runs
from the service when the panel stops and on the device thread when the pane is turned off, and
`onEvent` runs on whichever thread the event happened. A pane guards its own
state. `publishStates()` is safe from any thread, including while the panel stops.

A pane that throws in `bind`, `start`, `stop` or `onEvent` is logged (`pane <id> failed in <step>`)
and skipped. The other panes still run. An interruption during `bind`, `start` or `onEvent` is the
exception: it means the panel is stopping, so it goes back to the caller and the panes after it
don't run. `stop` always reaches every pane: `stopAll()` reports the interruption, and the shell
sets the flag again at the end of its own shutdown.

## Licence

The files in `dev.glassworks` are licensed under the
[Mozilla Public License 2.0](../../../../../../../LICENSES/MPL-2.0.txt). The rest of S1 Unleashed
stays MIT.

The MPL works file by file. You can ship Glassworks inside a closed product, and your own frames
and panes, in their own files, can use any licence. Changes to Glassworks' own files must be
published. The reasoning is in the planning repo's decision log.

Saying "Built on Glassworks" is fine. Naming a product Glassworks or using the mark needs
permission: see the [trademark policy](https://www.glassworks.dev/trademarks).
