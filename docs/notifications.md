# Notifications

Home Assistant sends a notification, and the panel shows it over the dashboard: a title, a message, a
camera's picture, up to three buttons and a sound. A button press goes back to Home Assistant, where
an automation acts on it. A notification can also open a dashboard view full screen, like a doorbell
view with its camera, and go back to the normal dashboard afterwards.

It works through the panel's ESPHome device alone. Nothing to install in Home Assistant.

**Status (2026-11-13): new, not yet run on a panel.** The unit tests and the real `aioesphomeapi`
client pass; the first run on a real panel is pending.

## Turn it on

Notifications are off until you turn them on:

- on the panel's setup screen, the **Notifications** card on the Panes step, or
- in the panel's settings: **Panes → Notifications**, the switch at the top right, or
- in Home Assistant, the panel's `Notifications` switch (it starts disabled in HA; turn it on on the
  device page first).

The settings page shows what is on the panel, has **Do not disturb**, and **Send a test notification**.

## Send one

The panel adds one action to Home Assistant: `esphome.<panel>_notify`. For a panel named `s1-panel`
(the default) it is `esphome.s1_panel_notify`. *Developer tools → Actions* lists it: search for
`_notify`.

**Who can call it.** On a panel without an API key, anyone on your network can call `notify`, so
anyone there could raise a critical alert or open a view of your Home Assistant on the panel. The
key closes that: Home Assistant 2026.11 and later sets it when you add the panel.

It takes the Home Assistant Companion app's fields: `message`, `title` and `data`. Home Assistant
makes every ESPHome action field required, so all three are always there. `title` may be empty, and
`data` is a JSON object, or `{}` for none.

```yaml
action: esphome.s1_panel_notify
data:
  title: Washing machine
  message: Done. 1 h 32 min.
  data: "{}"
```

### The `data` fields

| Key | What it does |
|---|---|
| `tag` | A name for the notification. A new one with the same tag replaces it, and `clear_notification` closes it |
| `priority` | `low`, `normal` (the default), `high` or `critical`. See [Priorities](#priorities) |
| `image` | A picture: a path on this Home Assistant, like `/api/camera_proxy/camera.porch`, `/local/…` or `/media/…` |
| `entity_id` | A camera, like `camera.porch`, when there is no `image` |
| `actions` | Up to 3 buttons: `[{"action": "open_gate", "title": "Open the gate"}]`. Add `"uri": "/lovelace/garage"` to also open that view |
| `sound` | `chime`, `doorbell`, `alert` or `none`. Without it: none for low, a chime for normal and high, the alert for critical |
| `timeout` | Seconds until it closes by itself |
| `url` | A dashboard view on this Home Assistant, opened full screen at once, like `/dashboard-door/0` |
| `sticky` | `true`: a button press leaves it open |

Write `data` as a JSON string. A block string is the easiest to read:

```yaml
action: esphome.s1_panel_notify
data:
  title: Parcel at the door
  message: The porch camera saw a delivery.
  data: >
    {"tag": "parcel", "priority": "high", "image": "/api/camera_proxy/camera.porch",
     "actions": [{"action": "open_gate", "title": "Open the gate"},
                 {"action": "snooze", "title": "Snooze 10 min"}]}
```

With values from a template, build it with `to_json`:

```yaml
data:
  title: "{{ trigger.to_state.name }} is open"
  message: "Open since {{ now().strftime('%H:%M') }}"
  data: "{{ {'tag': 'door', 'priority': 'high', 'timeout': 600} | to_json }}"
```

A plain YAML mapping under `data` doesn't work: Home Assistant refuses it ("value should be a string").

### Coming from the Companion app

Change the action's name and put `data` in a string. Keys the panel doesn't use (`channel`, `color`,
`ttl`, `group`, …) are ignored. iOS's `push: {interruption-level: …}` works as a priority when there
is no `priority`. The call fails, with the reason in the script's trace, when:

- `sound` isn't one of the panel's four,
- there are more than 3 actions, or one is `REPLY` (the panel has no keyboard),
- `url`, `image` or an action's `uri` points anywhere but this Home Assistant,
- `timeout` isn't a number of seconds between 1 and a day.

One difference: on a phone, `url` opens when you tap the notification. On the panel it opens at once,
which is what a doorbell wants. Use an action's `uri` for "open it when I tap".

### Clear one

```yaml
action: esphome.s1_panel_notify
data:
  message: clear_notification
  title: ""
  data: '{"tag": "parcel"}'
```

## When someone presses a button

The panel fires the event `esphome.notification_action` in Home Assistant, with the button's `action`,
the notification's `tag` (if it has one), and the panel's `device_id`. This is the shape of the
Companion app's `mobile_app_notification_action`.

```yaml
triggers:
  - trigger: event
    event_type: esphome.notification_action
    event_data:
      action: open_gate
actions:
  - action: cover.open_cover
    target:
      entity_id: cover.gate
```

It is an event on Home Assistant's bus, not an event entity, so it doesn't show on the device page.
An event entity can only fire the names it listed up front, and a button's name is whatever your
script chose ([why](research/ha-esphome-actions-events.md)). It needs no "Allow the device to perform
Home Assistant actions" option. Listen for it in *Developer tools → Events* to see what arrives.

## Priorities

| Priority | On the panel | Sound | Screen |
|---|---|---|---|
| `low` | A card when the screen is on; folds into the list after 10 s | none | Waits for the screen to wake |
| `normal` | A card; folds into the list after 15 s | chime | Waits for the screen to wake |
| `high` | A card marked *Important*, until answered | chime | Wakes it, at full brightness also at night |
| `critical` | The alert screen, until answered or dismissed | the alert, every 4 s for about 2 minutes (the last one may end 1.45 s past) | Wakes it, at full brightness also at night |

The sounds are made by the app from sine tones
([`NotificationSounds.kt`](../app/kiosk/src/main/java/dev/glassworks/notifications/NotificationSounds.kt),
MPL-2.0 like the rest of the core). There are no recordings, so no other licence applies.

The screen stays on for a high or critical one while it is up, for at most 10 minutes, so one nobody
answers doesn't keep a room lit all night. A sound pauses the music, as the voice assistant does.

## Do not disturb

The panel's `Do not disturb` switch in Home Assistant (`switch.glassworks_panel_do_not_disturb`, or
`switch.s1_panel_do_not_disturb` on a panel added before #63: [Names](esphome.md#names)), the same
switch in the panel's list, and on the settings page. While it is on, every notification but a critical one goes to the list without a
sound or a card, and the screen stays as it is. A pill at the top right says how many are held. When
it turns off, the held ones stay in the list. Bedtime is one automation:

```yaml
triggers:
  - trigger: time
    at: "22:30:00"
actions:
  - action: switch.turn_on
    target:
      entity_id: switch.glassworks_panel_do_not_disturb
```

## The list

**N notifications · Show all** under the cards (or the pill) opens the list. Tap one to see it again.
The list is kept until the panel restarts: Home Assistant holds the facts and can send again.

## The doorbell

No doorbell pane: your doorbell is in Home Assistant already, and a dashboard does the rest.

1. Make a dashboard view for the door: the doorbell's camera, an *Open the gate* button, and a *Done*
   button. The panel gives the page its microphone, so a camera card with two-way audio works.
2. Import the blueprint
   [`blueprints/automation/glassworks/doorbell-panels.yaml`](../blueprints/automation/glassworks/doorbell-panels.yaml)
   (*Settings → Automations & scenes → Blueprints → Import*, with the file's GitHub address). Pick the
   doorbell, the camera and the view, and type each panel's notify action: `esphome.` plus the
   panel's ESPHome name with `-` as `_`, plus `_notify`. The S1's name is `s1-panel`, so it is
   `esphome.s1_panel_notify`; a second panel you named `hall-panel` is `esphome.hall_panel_notify`.
   Home Assistant has no selector that picks one action, so they are typed. When it rings, every panel rings,
   shows the camera's picture and opens the view; each goes back to its dashboard after the timeout.
3. Make *Done* run a script that clears `doorbell` on every panel, so the others stop too. The
   blueprint's file has that script at the end, to copy.

Not yet run with a real doorbell (2026-11-13).

## When notifications are off

`esphome.<panel>_notify` stays in Home Assistant, like every pane's entities (#67), and answers
"Notifications are off on this panel". Turn them on, and the same scripts work.

## What it can't do yet

- Pictures need the panel's dashboard sign-in (the QR code) on the same Home Assistant. Without it,
  the card says "No picture" and why.
- Pictures from other sites: only this Home Assistant's. Put a camera or a file in Home Assistant
  instead.
- A sound from Home Assistant (`media_id`): only the three built-in sounds so far.
- A live camera view on the card: open a dashboard view with `url` instead.
