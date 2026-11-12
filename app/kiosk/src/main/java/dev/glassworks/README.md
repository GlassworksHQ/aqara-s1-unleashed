<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/lockup-on-dark.svg">
      <img alt="Glassworks" src="https://www.glassworks.dev/brand/lockup-on-light.svg" height="64">
    </picture>
  </a>
</p>

<h1 align="center">Glassworks pane API</h1>

<p align="center">
  <b>Features for an open Home Assistant wall panel, written once and run on any device.</b><br>
  A pane brings its own entities, lifecycle and settings. The panel's core hosts it.
</p>

<p align="center">
  <a href="#licence"><img alt="Licence: MPL-2.0" src="https://img.shields.io/badge/licence-MPL--2.0-blue"></a>
  <img alt="Status: experimental" src="https://img.shields.io/badge/status-experimental-orange">
  <a href="https://github.com/orgs/GlassworksHQ/projects/1"><img alt="Roadmap" src="https://img.shields.io/badge/roadmap-Glassworks%20v1-informational"></a>
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
| `PaneContext` | What the core gives a pane: the app context, settings, the device identity, `publishStates()` and `emit()`. |
| `PanelEvent` | Things that happen on the panel: a setting changed, voice took the speaker, a timer changed or finished. A pane raises one with `PaneContext.emit`. |
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

`PanelEvent` is an open interface. A pane handles the events it cares about and ignores the rest
(`else -> Unit`), so a new event does not touch the panes that do not use it. A pane does not have
to add entities: [`TimersPane.kt`](../../sh/edm/s1kiosk/timers/TimersPane.kt) has none and only
reacts to `TimersChanged` and `TimerFinished`. The voice assistant
([`AssistPane.kt`](../../sh/edm/s1kiosk/voice/AssistPane.kt)) is the pane that raises those: it calls
`ctx.emit(PanelEvent.TimerFinished(name))`, and `SpeakerClaimed` while it listens and talks.

Entities come from [esphome-api](../../../../../../libs/esphome-api/README.md). Home Assistant
keys them by object id, so an object id is permanent: renaming one makes a new entity in every
home that already has the panel.

## Threads

The core calls a pane from more than one thread. `start` runs on the device thread, `stop` runs
from the service, and `onEvent` runs on whichever thread the event happened. A pane guards its own
state. `publishStates()` is safe from any thread, including while the panel stops.

## Licence

The files in `dev.glassworks` are licensed under the
[Mozilla Public License 2.0](../../../../../../../LICENSES/MPL-2.0.txt). The rest of S1 Unleashed
stays MIT.

The MPL works file by file. You can ship Glassworks inside a closed product, and your own frames
and panes, in their own files, can use any licence. Changes to Glassworks' own files must be
published. The reasoning is in the planning repo's decision log.

Saying "Built on Glassworks" is fine. Naming a product Glassworks or using the mark needs
permission: see the [trademark policy](https://www.glassworks.dev/trademarks).
