<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-core-on-dark.svg">
      <img alt="Glassworks core" src="https://www.glassworks.dev/brand/banner-core-on-light.svg" width="640">
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
      <img alt="Glassworks core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30">
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
[migration step 6](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/21). The API will
change until Glassworks v1.

## What's in it

| Type | What it is |
|---|---|
| `Pane` | A feature: its Home Assistant entities, `start`/`stop`, and what it does when the panel changes. |
| `PaneContext` | What the core gives a pane: the app context, settings, the device identity, `publishStates()`, `emit()`, `entitiesChanged()` and `sendEvent()`. |
| `PanelEvent` | Things that happen on the panel: a setting changed, voice took the speaker, a timer changed or finished, Home Assistant subscribed. A pane raises one with `PaneContext.emit`. |
| `EntityRegistry` | Builds the device's entity list from the core's entities and every pane's, and refuses two entities with the same object id. |

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

### Entities that change, and events

A pane's entity list may change while the panel runs. The wall base
([`WallBasePane.kt`](../../sh/edm/s1kiosk/base/WallBasePane.kt)) adds relays, buttons and power
sensors when a switch base is mounted. The pane then calls `ctx.entitiesChanged()`. The core builds
the device's list again from its own entities and every pane's `entities()`, and gives it to Home
Assistant, which reconnects and lists the entities again. `entities()` must return the new list from
then on.

To fire an ESPHome event, a pane calls `ctx.sendEvent(entity, type)` with an `EventEntity` from its
current list. Keep the instances: the server only fires events for the entity objects it was given,
so build the list once per state, not on every `entities()` call. A pane does not hold the API
server for this. The core owns it, including its restarts.

Entities come from [esphome-api](../../../../../../libs/esphome-api/README.md). Home Assistant
keys them by object id, so an object id is permanent: renaming one makes a new entity in every
home that already has the panel.

## Threads

The core calls a pane from more than one thread. `start` runs on the device thread, `stop` runs
from the service, and `onEvent` runs on whichever thread the event happened. A pane guards its own
state. `publishStates()` is safe from any thread, including while the panel stops.

A pane that throws in `bind`, `start`, `stop` or `onEvent` is logged (`pane <id> failed in <step>`)
and skipped. The other panes still run. An interruption during `bind`, `start` or `onEvent` is the
exception: it means the panel is stopping, so it goes back to the caller and the panes after it
don't run. `stop` always reaches every pane, and the interruption is passed on after the last one.

## Licence

The files in `dev.glassworks` are licensed under the
[Mozilla Public License 2.0](../../../../../../../LICENSES/MPL-2.0.txt). The rest of S1 Unleashed
stays MIT.

The MPL works file by file. You can ship Glassworks inside a closed product, and your own frames
and panes, in their own files, can use any licence. Changes to Glassworks' own files must be
published. The reasoning is in the planning repo's decision log.

Saying "Built on Glassworks" is fine. Naming a product Glassworks or using the mark needs
permission: see the [trademark policy](https://www.glassworks.dev/trademarks).
