"""The showcase home (MIT): made-up rooms and states, and one Lovelace dashboard, for the device
renders on Kiln's cards and glassworks.dev (render.mjs). Every frame shows this same dashboard
(maintainer, #144), so the renders are comparable.

It is a home for Clarity's fake Home Assistant (tools/perf/fakeha.py `serve(home=...)`): it gives the
states, the config, the panels and the dashboard; everything else (history, statistics, the
frontend's other requests) comes from Clarity's dashboards.py. No clock, no dates, no times: the
image must never show a stale day. Names are made up; nothing comes from a real home.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "perf"))
import dashboards as _clarity  # noqa: E402

DASHBOARD = "showcase"
START = _clarity.START
USER = dict(_clarity.USER, id="showcase", name="Showcase")


def __getattr__(name):
    """What the showcase doesn't set is Clarity's (history, statistics, the context ids, ...)."""
    return getattr(_clarity, name)


def _light(name, on, pct=0):
    return "on" if on else "off", {"friendly_name": name, "supported_color_modes": ["brightness"],
                                   "color_mode": "brightness" if on else None,
                                   "brightness": round(pct * 255 / 100) if on else None, "supported_features": 0}


ENTITIES = {
    "light.living_room": _light("Living room", True, 70),
    "light.dining_table": _light("Dining table", True, 45),
    "light.kitchen": _light("Kitchen", False),
    "light.hallway": _light("Hallway", False),
    "light.bedroom": _light("Bedroom", True, 20),
    "climate.living_room": ("heat", {
        "friendly_name": "Thermostat", "hvac_modes": ["off", "heat"], "min_temp": 7, "max_temp": 30,
        "target_temp_step": 0.5, "current_temperature": 20.5, "temperature": 21, "hvac_action": "heating",
        "supported_features": 385}),
    "media_player.kitchen_speaker": ("playing", {
        "friendly_name": "Kitchen speaker", "device_class": "speaker", "volume_level": 0.3, "is_volume_muted": False,
        "media_content_type": "music", "media_title": "Slow Morning", "media_artist": "The Glass Tones",
        "supported_features": 152463}),
    "lock.front_door": ("locked", {"friendly_name": "Front door", "supported_features": 0}),
    "cover.bedroom_blinds": ("open", {"friendly_name": "Bedroom blinds", "current_position": 60,
                                      "device_class": "blind", "supported_features": 15}),
    "binary_sensor.back_door": ("off", {"friendly_name": "Back door", "device_class": "door"}),
    "binary_sensor.hall_motion": ("off", {"friendly_name": "Hall motion", "device_class": "motion"}),
    "switch.coffee_machine": ("off", {"friendly_name": "Coffee machine"}),
    "sensor.outdoor_temperature": ("12.4", {"friendly_name": "Outside", "device_class": "temperature",
                                            "unit_of_measurement": "°C", "state_class": "measurement"}),
    "sensor.living_room_humidity": ("46", {"friendly_name": "Humidity", "device_class": "humidity",
                                           "unit_of_measurement": "%", "state_class": "measurement"}),
    "sensor.home_power": ("412", {"friendly_name": "Power", "device_class": "power",
                                  "unit_of_measurement": "W", "state_class": "measurement"}),
}


def states():
    return {eid: {"entity_id": eid, "state": st, "attributes": attrs, "last_changed": START,
                  "last_updated": START, "context_id": _clarity.context()}
            for eid, (st, attrs) in ENTITIES.items()}


def config():
    return dict(_clarity.config(), location_name="Home", latitude=52.0, longitude=5.0,
                components=["frontend", "lovelace", "history", "sensor", "light", "switch", "binary_sensor",
                            "climate", "media_player", "lock", "cover"])


def services():
    s = {"name": "", "description": "", "fields": {}, "target": {"entity": [{}]}}
    names = {"light": ["turn_on", "turn_off", "toggle"], "switch": ["turn_on", "turn_off", "toggle"],
             "lock": ["lock", "unlock"], "cover": ["open_cover", "close_cover", "stop_cover"],
             "climate": ["set_temperature", "set_hvac_mode"], "media_player": ["media_play_pause"]}
    return {d: {n: s for n in ns} for d, ns in names.items()}


def panels():
    out = _clarity.panels()
    del out[_clarity.DASHBOARD]
    out[DASHBOARD] = {"component_name": "lovelace", "icon": "mdi:view-dashboard", "title": "Home",
                      "config": {"mode": "storage"}, "url_path": DASHBOARD, "require_admin": False,
                      "show_in_sidebar": False, "config_panel_domain": None}
    return out


def dashboards_list():
    return [{"id": DASHBOARD, "url_path": DASHBOARD, "title": "Home", "icon": "mdi:view-dashboard",
             "show_in_sidebar": False, "require_admin": False, "mode": "storage"}]


def _section(title, *cards):
    return {"type": "grid", "cards": [{"type": "heading", "heading": title}, *cards]}


def _tile(entity, **extra):
    return {"type": "tile", "entity": entity, **extra}


def lovelace(url_path):
    if url_path != DASHBOARD:
        return {"views": [{"title": "Home", "cards": []}]}
    return {"views": [{"title": "Home", "path": "home", "type": "sections", "max_columns": 4, "sections": [
        _section("Living room", _tile("light.living_room"), _tile("light.dining_table"),
                 _tile("climate.living_room"), _tile("sensor.living_room_humidity")),
        _section("Entrance", _tile("lock.front_door"), _tile("light.hallway"),
                 _tile("binary_sensor.back_door"), _tile("binary_sensor.hall_motion")),
        _section("Kitchen", _tile("light.kitchen"), _tile("switch.coffee_machine"),
                 _tile("media_player.kitchen_speaker"), _tile("sensor.home_power")),
        _section("Bedroom", _tile("light.bedroom"), _tile("cover.bedroom_blinds"),
                 _tile("sensor.outdoor_temperature", name="Outside")),
    ]}]}


# HA core's entity icons (homeassistant/components/<domain>/icons.json, `entity_component`) for the
# domains above: frontend/get_icons answers them as a real HA does, so the tiles show HA's own icons.
ICONS = {
    "lock": {"_": {"default": "mdi:lock", "state": {"unlocked": "mdi:lock-open-variant", "open": "mdi:lock-open-variant"}}},
    "binary_sensor": {"_": {"default": "mdi:radiobox-blank", "state": {"on": "mdi:checkbox-marked-circle"}},
                      "door": {"default": "mdi:door-closed", "state": {"on": "mdi:door-open"}},
                      "motion": {"default": "mdi:motion-sensor-off", "state": {"on": "mdi:motion-sensor"}}},
    "sensor": {"_": {"default": "mdi:eye"}, "humidity": {"default": "mdi:water-percent"},
               "power": {"default": "mdi:flash"}, "temperature": {"default": "mdi:thermometer"}},
    "switch": {"_": {"default": "mdi:toggle-switch-variant", "state": {"off": "mdi:toggle-switch-variant-off"}}},
    "media_player": {"_": {"default": "mdi:cast", "state": {"playing": "mdi:cast-connected", "off": "mdi:cast-off"}},
                     "speaker": {"default": "mdi:speaker", "state": {"playing": "mdi:speaker-play", "off": "mdi:speaker-off"}}},
    "cover": {"_": {"default": "mdi:window-open", "state": {"closed": "mdi:window-closed"}},
              "blind": {"default": "mdi:blinds-horizontal", "state": {"closed": "mdi:blinds-horizontal-closed"}}},
    "climate": {"_": {"default": "mdi:thermostat"}},
}


def icons(category, integration=None):
    return ICONS if category == "entity_component" else {}


# HA core's English state names (homeassistant/components/<domain>/strings.json), as
# frontend/get_translations sends them: "Closed", not "off".
STATES = {
    "light._": {"on": "On", "off": "Off"},
    "switch._": {"on": "On", "off": "Off"},
    "lock._": {"locked": "Locked", "unlocked": "Unlocked"},
    "climate._": {"heat": "Heat", "off": "Off"},
    "media_player._": {"playing": "Playing", "paused": "Paused", "off": "Off"},
    "cover._": {"open": "Open", "closed": "Closed"},
    "binary_sensor.door": {"on": "Open", "off": "Closed"},
    "binary_sensor.motion": {"on": "Detected", "off": "Clear"},
}


def translations(category):
    if category != "entity_component":
        return {}
    out = {}
    for key, names in STATES.items():
        domain, dc = key.split(".")
        for state, text in names.items():
            out[f"component.{domain}.entity_component.{dc}.state.{state}"] = text
    return out
