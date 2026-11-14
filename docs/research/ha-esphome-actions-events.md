# ESPHome actions and events in Home Assistant: what a notification can carry each way

**Status (2026-11-13): read from source; the bus event verified against the real aioesphomeapi
client (`EspHomeHaEventTest`), not yet against a running Home Assistant.** Home Assistant makes every
ESPHome action argument required, and an ESPHome event entity can only fire the event types it
listed up front. So the notifications pane (#118) takes one JSON `data` argument, and reports a
tapped button as a bus event, not as an event entity.

Device: none (desk research). Tools: Home Assistant core 2026.11.0 source, aioesphomeapi 46.25.0.

## Question

The notifications pane needs two things from the ESPHome API:

1. HA calls `esphome.<panel>_notify` with a title, a message and some optional fields, like the
   Companion app's `notify.mobile_app_*`.
2. When someone taps a button on the panel, HA automations get the button's id (`open_gate`),
   like `mobile_app_notification_action`.

Can both go over ESPHome alone, with nothing installed in HA?

## What we tried

1. **Optional action arguments** (2026-11-13, read). `homeassistant/components/esphome/manager.py`,
   `_async_register_service`: every argument becomes `probatio.Required(arg.name)`, and the action's
   field list says `"required": True` (`manager.py:1699`). The protocol could say more:
   aioesphomeapi 46.25.0's `ListEntitiesServicesArgument` has `optional` (5) and `default_value`
   (6), behind `USE_API_USER_DEFINED_ACTION_OPTIONAL_ARGS`. HA 2026.11 ignores both and makes the
   argument required anyway. So a `notify` action with ten typed arguments would make every script
   pass all ten, until HA reads those fields.
   A `STRING` argument is checked with `cv.string`, which refuses a YAML mapping
   (`value should be a string`). A template's result reaches it as a `ResultWrapper`, which
   `cv.string` returns as the rendered text, so `data: "{{ {...} | to_json }}"` and a YAML block
   string both work. (Read in `helpers/config_validation.py`; not yet run against a real HA.)
2. **An event entity with the action id as the event type** (2026-11-13, read).
   `homeassistant/components/event/entity.py:106`, `_trigger_event`:
   `if event_type not in self.event_types: raise ValueError(...)`. The ESPHome event platform
   (`components/esphome/event.py`) passes the device's `event_type` straight to it, and the event
   types come only from `ListEntitiesEventResponse`. An id the script chose at run time can't be
   one of them unless the panel lists its entities again, which makes HA reconnect.
   `EventResponse` (108) carries the entity's `key`, the `event_type` and a `device_id` (3, for
   sub-devices), and no attributes, so there is nowhere else to put the id either.
3. **A bus event, as ESPHome's `homeassistant.event` sends** (2026-11-13, read).
   `manager.py:351`, `async_on_service_call`: a `HomeassistantActionRequest` with `is_event` fires
   `hass.bus.async_fire(service, {device_id, **data})`. Only the `esphome.` domain is allowed. The
   "Allow the device to perform Home Assistant actions" option is checked only in the `elif` for
   actions, so events need no option. HA subscribes to these on every connection
   (`subscribe_home_assistant_states_and_services`, `manager.py:854`).
   The wire form, from aioesphomeapi 46.25.0's `api_pb2`: message 35, `service` = 1,
   `data` = 2 (repeated `HomeassistantServiceMap {key = 1, value = 2}`), `is_event` = 5.

## Result

| Need | Over ESPHome | How |
|---|---|---|
| Optional fields on `notify` | Not as separate arguments | `notify(message, title, data)`, `data` a JSON object |
| A free-form button id back to HA | Not on an event entity | Bus event `esphome.notification_action` with `action` and `tag` (HA adds `device_id`) |

`HomeAssistantEvent` in `esphome-api` encodes message 35; `HomeAssistantEventTest` checks it byte
for byte against aioesphomeapi. `ApiServer.sendHomeAssistantEvent` sends it to every connection that
subscribed (#118), and `EspHomeHaEventTest` checks that aioesphomeapi 46.25.0 decodes it as an event
with its data (2026-11-13, verified).

## How to reproduce

Read-only: the files and lines above, at the tags named. To print the wire bytes,
`python -I app/libs/esphome-api/src/test/resources/esphome_golden.py` with aioesphomeapi 46.25.0.

## Open questions

- Run it on a real HA: a script with `data` as a template and as a block string, and an automation
  triggered by `esphome.notification_action`.
- A bus event isn't an entity, so it doesn't show on the device page or in the logbook. If HA's
  ESPHome integration ever passes event attributes, an event entity could carry the id instead.
- If HA starts reading `optional` and `default_value`, `notify` could take its fields as separate
  arguments. `data` would have to stay, for the scripts already written.
