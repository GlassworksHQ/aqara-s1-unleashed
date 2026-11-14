"""The showcase home (MIT): made-up rooms and states, a day of history, pictures, and the dashboard every
frame's render shows (render.mjs; maintainer, #144: one dashboard for all frames, so they compare).

It is a home for Clarity's fake Home Assistant (tools/perf/fakeha.py `serve(home=...)`): states, config,
panels, the dashboard, history and statistics, the weather forecast, camera stills, a TV still and
album art. What it doesn't set comes from Clarity's dashboards.py. Everything is seeded and anchored to
NOW, which render.mjs also gives the browser's clock, so every run draws the same picture. Names and
states are made up and nothing comes from a real home. The two camera stills are CC0 photos
(photos/README.md: cropped, nothing readable left, no faces); the TV still and the album art are drawn
here.

One dashboard, one view, nine sections. Every section is SECTION_ROWS grid rows tall, so on any screen
each row of sections ends level and no column stops early.
"""
import datetime
import math
import os
import random
import struct
import sys
import zlib

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "perf"))
import dashboards as _clarity  # noqa: E402

DASHBOARD = "showcase"
SEED = 144
# The moment every render shows: an evening (lights on, the day's graphs full). The clock shows its
# time; nothing shows its date.
NOW = datetime.datetime(2026, 11, 12, 19, 30, tzinfo=datetime.timezone.utc).timestamp()
START = NOW - 3 * 3600
USER = dict(_clarity.USER, id="showcase", name="Showcase")


def __getattr__(name):
    """What the showcase doesn't set is Clarity's (the context ids, ...)."""
    return getattr(_clarity, name)


# ---- the home

def _light(name, pct=0, **extra):
    on = pct > 0
    return "on" if on else "off", {"friendly_name": name, "supported_color_modes": ["brightness"],
                                   "color_mode": "brightness" if on else None,
                                   "brightness": round(pct * 255 / 100) if on else None, "supported_features": 0, **extra}


def _sensor(name, value, dc, unit, state_class="measurement"):
    return str(value), {"friendly_name": name, "device_class": dc, "unit_of_measurement": unit, "state_class": state_class}


def _climate(name, state, current, target, action):
    return state, {"friendly_name": name, "hvac_modes": ["off", "heat"], "min_temp": 7, "max_temp": 30,
                   "target_temp_step": 0.5, "current_temperature": current, "temperature": target,
                   "hvac_action": action, "supported_features": 385}


ENTITIES = {
    "weather.home": ("partlycloudy", {
        "friendly_name": "Home", "temperature": 9.4, "temperature_unit": "°C", "humidity": 78,
        "pressure": 1016, "pressure_unit": "hPa", "wind_speed": 14, "wind_speed_unit": "km/h",
        "visibility_unit": "km", "precipitation_unit": "mm", "supported_features": 1}),
    "person.alex": ("home", {"friendly_name": "Alex"}),
    "person.sam": ("not_home", {"friendly_name": "Sam"}),
    "climate.living_room": _climate("Living room", "heat", 20.6, 21, "heating"),
    "climate.bedroom": _climate("Bedroom", "heat", 18.2, 18, "idle"),
    "light.living_room": _light("Living room", 70),
    "light.dining_table": _light("Dining table", 45),
    "light.kitchen": _light("Kitchen", 100),
    "light.hallway": _light("Hallway"),
    "light.bedroom": _light("Bedroom", 20),
    "light.porch": _light("Porch", 60),
    "light.floor_lamp": _light("Floor lamp", 30),
    "switch.tv": ("on", {"friendly_name": "TV"}),
    "switch.sprinklers": ("off", {"friendly_name": "Sprinklers"}),
    "cover.garage_door": ("closed", {"friendly_name": "Garage door", "device_class": "garage", "supported_features": 3}),
    "switch.coffee_machine": ("off", {"friendly_name": "Coffee machine"}),
    "switch.dishwasher": ("on", {"friendly_name": "Dishwasher"}),
    "switch.kettle": ("off", {"friendly_name": "Kettle"}),
    "switch.garden_lights": ("on", {"friendly_name": "Garden lights"}),
    "media_player.living_room": ("playing", {
        "friendly_name": "Living room speaker", "device_class": "speaker", "volume_level": 0.3,
        "is_volume_muted": False, "media_content_type": "music", "media_title": "Slow Morning",
        "media_artist": "The Glass Tones", "media_album_name": "Low Light",
        "entity_picture": "/clarity/showcase-art.png", "supported_features": 152463}),
    "media_player.tv": ("playing", {
        "friendly_name": "TV", "device_class": "tv", "volume_level": 0.25, "is_volume_muted": False,
        "media_content_type": "tvshow", "media_title": "Night Tides", "media_series_title": "Coastlines",
        "entity_picture": "/clarity/showcase-tv.png", "supported_features": 152463}),
    "media_player.kitchen_radio": ("playing", {
        "friendly_name": "Kitchen radio", "device_class": "speaker", "volume_level": 0.2, "is_volume_muted": False,
        "media_content_type": "music", "media_title": "Morning Show", "supported_features": 152463}),
    "switch.soundbar": ("on", {"friendly_name": "Soundbar"}),
    "switch.game_console": ("off", {"friendly_name": "Game console"}),
    "light.tv_backlight": _light("TV backlight", 40),
    "switch.record_player": ("off", {"friendly_name": "Record player"}),
    "camera.front_garden": ("idle", {
        "friendly_name": "Front garden", "access_token": "showcase",
        "entity_picture": "/api/camera_proxy/camera.front_garden?token=showcase", "supported_features": 0}),
    "camera.driveway": ("idle", {
        "friendly_name": "Driveway", "access_token": "showcase",
        "entity_picture": "/api/camera_proxy/camera.driveway?token=showcase", "supported_features": 0}),
    "lock.front_door": ("locked", {"friendly_name": "Front door", "supported_features": 0}),
    "cover.bedroom_blinds": ("open", {"friendly_name": "Bedroom blinds", "current_position": 40,
                                      "device_class": "blind", "supported_features": 15}),
    "binary_sensor.back_door": ("off", {"friendly_name": "Back door", "device_class": "door"}),
    "binary_sensor.hall_motion": ("off", {"friendly_name": "Hall motion", "device_class": "motion"}),
    "alarm_control_panel.home": ("armed_home", {"friendly_name": "Alarm", "code_arm_required": False,
                                                "code_format": None, "supported_features": 7}),
    "vacuum.robot": ("docked", {"friendly_name": "Robot vacuum", "battery_level": 100, "supported_features": 12380}),
    "fan.air_purifier": ("on", {"friendly_name": "Air purifier", "percentage": 40, "percentage_step": 20,
                                "supported_features": 1}),
    "todo.shopping_list": ("3", {"friendly_name": "Shopping list", "supported_features": 15}),
    "scene.movie_night": ("2026-11-12T18:02:00+00:00", {"friendly_name": "Movie night"}),
    "scene.good_night": ("2026-11-11T22:40:00+00:00", {"friendly_name": "Good night"}),
    "sensor.living_room_temperature": _sensor("Living room", 20.6, "temperature", "°C"),
    "sensor.living_room_humidity": _sensor("Living room humidity", 46, "humidity", "%"),
    "sensor.bedroom_humidity": _sensor("Bedroom humidity", 52, "humidity", "%"),
    "sensor.living_room_co2": _sensor("CO₂", 612, "carbon_dioxide", "ppm"),
    "sensor.living_room_pm25": _sensor("PM2.5", 6, "pm25", "µg/m³"),
    "sensor.bedroom_temperature": _sensor("Bedroom", 18.2, "temperature", "°C"),
    "sensor.fridge_temperature": _sensor("Fridge", 4.1, "temperature", "°C"),
    "sensor.outdoor_temperature": _sensor("Outside", 9.4, "temperature", "°C"),
    "sensor.home_power": _sensor("Power", 412, "power", "W"),
    "sensor.energy_today": _sensor("Energy today", 7.8, "energy", "kWh", "total_increasing"),
}

SHOPPING = [("Oat milk", "needs_action"), ("Lemons", "needs_action"), ("Coffee beans", "needs_action"),
            ("Bread", "completed")]


def states():
    return {eid: {"entity_id": eid, "state": st, "attributes": attrs, "last_changed": START,
                  "last_updated": START, "context_id": _clarity.context()}
            for eid, (st, attrs) in ENTITIES.items()}


def config():
    return dict(_clarity.config(), location_name="Home", latitude=52.0, longitude=5.0,
                components=["frontend", "lovelace", "history", "recorder", "sensor", "light", "switch", "binary_sensor",
                            "climate", "media_player", "lock", "cover", "camera", "weather", "person",
                            "alarm_control_panel", "vacuum", "fan", "todo", "scene"])


def services():
    s = {"name": "", "description": "", "fields": {}, "target": {"entity": [{}]}}
    names = {"light": ["turn_on", "turn_off", "toggle"], "switch": ["turn_on", "turn_off", "toggle"],
             "lock": ["lock", "unlock"], "cover": ["open_cover", "close_cover", "stop_cover"],
             "climate": ["set_temperature", "set_hvac_mode"], "media_player": ["media_play_pause", "media_next_track"],
             "alarm_control_panel": ["alarm_arm_home", "alarm_arm_away", "alarm_arm_night", "alarm_disarm"],
             "vacuum": ["start", "pause", "return_to_base"], "fan": ["set_percentage"], "scene": ["turn_on"],
             "todo": ["add_item", "update_item"]}
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


# ---- the dashboard

SECTION_ROWS = 10      # every section's height in grid rows (56 px each, plus 8 px gaps), its heading included


def _rows(card, rows, columns=12):
    return dict(card, grid_options={"rows": rows, "columns": columns})


def _tile(entity, features=(), **extra):
    card = {"type": "tile", "entity": entity, **extra}
    if features:
        card["features"] = [{"type": f, **o} for f, o in features]
        card["features_position"] = "bottom"
        card["grid_options"] = {"columns": 12, "rows": 2}
    return card


def _heading(title, *badges):
    """A section's title, exactly one grid row tall, so every section is SECTION_ROWS rows."""
    return _rows({"type": "heading", "heading": title, "heading_style": "title",
                  "badges": [{"type": "entity", "entity": b} for b in badges]}, 1)


def _graph(title, entities, rows, hours=12):
    return _rows({"type": "history-graph", "title": title, "hours_to_show": hours, "entities": entities}, rows)


def _camera(entity, rows):
    return _rows({"type": "picture-entity", "entity": entity, "camera_view": "auto", "show_state": False,
                  "fit_mode": "cover"}, rows)


BADGES = ([{"type": "entity", "entity": e, "show_name": True} for e in ("person.alex", "person.sam")]
          + [{"type": "entity", "entity": e} for e in ("alarm_control_panel.home", "lock.front_door", "sensor.home_power")])

SECTIONS = [
    {"type": "grid", "cards": [
        _rows({"type": "clock", "clock_size": "large", "time_format": "24", "no_background": True}, 2),
        _rows({"type": "weather-forecast", "entity": "weather.home", "show_forecast": True, "forecast_type": "daily",
               "forecast_slots": 4}, 4),
        _graph("Temperature", ["sensor.living_room_temperature", "sensor.outdoor_temperature"], 4)]},
    {"type": "grid", "cards": [
        _heading("Living room", "sensor.living_room_temperature", "sensor.living_room_humidity"),
        _rows({"type": "thermostat", "entity": "climate.living_room"}, 5),
        _tile("light.living_room"), _tile("light.dining_table"),
        _tile("light.floor_lamp"), _tile("switch.tv"),
        _tile("media_player.living_room", [("media-player-volume-slider", {})], show_entity_picture=True,
              vertical=False)]},
    {"type": "grid", "cards": [
        _heading("Front garden", "binary_sensor.back_door"),
        _camera("camera.front_garden", 5),
        _tile("lock.front_door"), _tile("light.porch"), _tile("switch.garden_lights"), _tile("binary_sensor.hall_motion"),
        _tile("cover.garage_door"), _tile("switch.sprinklers"),
        _tile("scene.movie_night", hide_state=True), _tile("scene.good_night", hide_state=True)]},
    {"type": "grid", "cards": [
        _heading("Energy", "sensor.home_power"),
        _rows({"type": "statistics-graph", "title": "This week", "entities": ["sensor.energy_today"], "period": "day",
               "days_to_show": 7, "chart_type": "bar", "stat_types": ["change"]}, 5),
        _graph("Power", ["sensor.home_power"], 4)]},
    {"type": "grid", "cards": [
        _heading("Kitchen", "sensor.fridge_temperature"),
        _tile("light.kitchen", [("light-brightness", {})]),
        _tile("switch.coffee_machine"), _tile("switch.dishwasher"),
        _tile("switch.kettle"), _tile("light.hallway"),
        _rows({"type": "todo-list", "entity": "todo.shopping_list", "hide_create": True}, 5)]},
    {"type": "grid", "cards": [
        _heading("Bedroom", "sensor.bedroom_temperature", "sensor.bedroom_humidity"),
        _rows({"type": "thermostat", "entity": "climate.bedroom"}, 5),
        _tile("light.bedroom", [("light-brightness", {})]),
        _tile("cover.bedroom_blinds", [("cover-open-close", {})])]},
    {"type": "grid", "cards": [
        _heading("Air", "sensor.living_room_co2"),
        _graph("Humidity", ["sensor.living_room_humidity", "sensor.bedroom_humidity"], 6),
        _tile("sensor.living_room_co2"), _tile("sensor.living_room_pm25"),
        _tile("fan.air_purifier", [("fan-speed", {"style": "slider"})])]},
    {"type": "grid", "cards": [
        _heading("Security", "lock.front_door"),
        _camera("camera.driveway", 5),
        _tile("alarm_control_panel.home", [("alarm-modes", {"modes": ["armed_home", "armed_away", "armed_night", "disarmed"]})]),
        _tile("vacuum.robot", [("vacuum-commands", {"commands": ["start_pause", "return_home"]})])]},
    {"type": "grid", "cards": [
        _heading("Media", "media_player.tv"),
        _rows({"type": "media-control", "entity": "media_player.tv"}, 5),
        _tile("media_player.kitchen_radio", [("media-player-volume-slider", {})]),
        _tile("switch.soundbar"), _tile("light.tv_backlight"),
        _tile("switch.game_console"), _tile("switch.record_player")]},
]


def lovelace(url_path):
    if url_path != DASHBOARD:
        return {"views": [{"title": "Home", "cards": []}]}
    return {"views": [{"title": "Home", "path": "home", "type": "sections", "max_columns": 4, "badges": BADGES,
                       "sections": SECTIONS}]}


def cards():
    """How many cards a view draws (render.mjs waits for all of them)."""
    return sum(len(s["cards"]) for s in SECTIONS)


# ---- a day of history: smooth, plausible curves, the same on every run

def _bump(hour, at, width):
    return math.exp(-((hour - at) / width) ** 2)


def _curve(entity_id, t):
    """The entity's value at epoch t: a smooth day, with a little seeded noise."""
    hour = (t % 86400) / 3600
    noise = random.Random(f"{SEED}:{entity_id}:{int(t // 300)}").random() - 0.5
    if entity_id == "sensor.outdoor_temperature":
        return 6.5 + 4.5 * math.sin((hour - 9.5) / 24 * 2 * math.pi) + 0.1 * noise
    if entity_id == "sensor.living_room_temperature":
        # heating from 06:30 eases the room from 18 to 21, the sun adds a little after lunch
        warm = 1 / (1 + math.exp(-(hour - 7.2) * 2.2))
        return 18.1 + 2.5 * warm + 0.4 * _bump(hour, 14.5, 2.0) + 0.03 * noise
    if entity_id == "sensor.bedroom_temperature":
        return 17.6 + 0.7 * math.sin((hour - 14) / 24 * 2 * math.pi) + 0.03 * noise
    if entity_id == "sensor.living_room_humidity":
        # dry with the heating on, a bump while cooking
        return 51 - 5 / (1 + math.exp(-(hour - 7.5) * 1.5)) + 6 * _bump(hour, 18.6, 0.8) + 0.3 * noise
    if entity_id == "sensor.bedroom_humidity":
        return 55 - 4 * _bump(hour, 13, 3.5) + 0.3 * noise
    if entity_id == "sensor.home_power":
        return (210 + 30 * noise + 850 * _bump(hour, 7.6, 0.6) + 380 * _bump(hour, 12.6, 0.5)
                + 1150 * _bump(hour, 18.4, 0.9))
    return 0


def history(entity_ids, start, end, now=None):
    """history/stream: a point every 5 minutes over the window the card asked for."""
    t0, t1 = _clarity.window(start, end, NOW if now is None else now)
    t1 = min(t1, NOW)
    out = {}
    for e in entity_ids:
        if e not in ENTITIES:
            continue
        pts, t = [], t0 - t0 % 300
        while t <= t1:
            pts.append({"s": str(round(_curve(e, t), 2 if "temperature" in e or "humidity" in e else 0)), "lu": t})
            t += 300
        if pts:
            pts[0]["a"] = {}
            pts.append({"s": ENTITIES[e][0], "lu": t1})
        out[e] = pts
    return out


def statistics(statistic_ids, start_time, end_time, period, now=None):
    """recorder/statistics_during_period: the energy sensor's daily use as a week of bars."""
    out = {}
    if "sensor.energy_today" not in (statistic_ids or []):
        return out
    day = 86400
    today = NOW - NOW % day
    rnd = random.Random(f"{SEED}:energy")
    pts, total = [], 1000.0
    for k in range(6, -1, -1):
        t = today - k * day
        change = 7.8 if k == 0 else round(9 + 4 * rnd.random(), 1)
        total += change
        pts.append({"start": int(t * 1000), "end": int((t + day) * 1000), "change": change, "sum": round(total, 1),
                    "state": round(total, 1)})
    out["sensor.energy_today"] = pts
    return out


def forecast():
    """weather/subscribe_forecast: five days, from NOW."""
    conds = ["rainy", "partlycloudy", "sunny", "cloudy", "partlycloudy"]
    day = 86400
    out = []
    for k, c in enumerate(conds):
        t = NOW - NOW % day + (k + 1) * day + 12 * 3600
        out.append({"datetime": datetime.datetime.fromtimestamp(t, datetime.timezone.utc).isoformat(),
                    "condition": c, "temperature": [10, 11, 12, 9, 8][k], "templow": [5, 6, 4, 3, 2][k],
                    "precipitation": [4.2, 0.6, 0, 0.2, 0.4][k]})
    return {"type": "daily", "forecast": out}


def shopping():
    """todo/item/subscribe: the shopping list."""
    return {"items": [{"uid": f"showcase-{k}", "summary": text, "status": status} for k, (text, status) in enumerate(SHOPPING)]}


HANDLERS = {
    "todo/item/subscribe": lambda c, m: (c.result(m["id"]), c.event(m["id"], shopping())),
    "weather/subscribe_forecast": lambda c, m: (c.result(m["id"]), c.event(m["id"], forecast())),
    "recorder/get_statistics_metadata": lambda c, m: c.result(m["id"], [{
        "statistic_id": "sensor.energy_today", "source": "recorder", "name": None, "has_mean": False, "has_sum": True,
        "unit_of_measurement": "kWh", "statistics_unit_of_measurement": "kWh", "unit_class": "energy",
        "mean_type": 0}]),
}


# ---- HA's icons and words for these domains

# HA core's entity icons (homeassistant/components/<domain>/icons.json, `entity_component`): get_icons
# answers them as a real HA does, so the cards show HA's own icons.
ICONS = {
    "lock": {"_": {"default": "mdi:lock", "state": {"unlocked": "mdi:lock-open-variant", "open": "mdi:lock-open-variant"}}},
    "binary_sensor": {"_": {"default": "mdi:radiobox-blank", "state": {"on": "mdi:checkbox-marked-circle"}},
                      "door": {"default": "mdi:door-closed", "state": {"on": "mdi:door-open"}},
                      "motion": {"default": "mdi:motion-sensor-off", "state": {"on": "mdi:motion-sensor"}}},
    "sensor": {"_": {"default": "mdi:eye"}, "humidity": {"default": "mdi:water-percent"},
               "power": {"default": "mdi:flash"}, "energy": {"default": "mdi:lightning-bolt"},
               "temperature": {"default": "mdi:thermometer"}},
    "switch": {"_": {"default": "mdi:toggle-switch-variant", "state": {"off": "mdi:toggle-switch-variant-off"}}},
    "media_player": {"_": {"default": "mdi:cast", "state": {"playing": "mdi:cast-connected", "off": "mdi:cast-off"}},
                     "speaker": {"default": "mdi:speaker", "state": {"playing": "mdi:speaker-play", "off": "mdi:speaker-off"}},
                     "tv": {"default": "mdi:television", "state": {"playing": "mdi:television-play", "off": "mdi:television-off"}}},
    "cover": {"_": {"default": "mdi:window-open", "state": {"closed": "mdi:window-closed"}},
              "garage": {"default": "mdi:garage", "state": {"open": "mdi:garage-open"}},
              "blind": {"default": "mdi:blinds-horizontal", "state": {"closed": "mdi:blinds-horizontal-closed"}}},
    "climate": {"_": {"default": "mdi:thermostat"}},
    "camera": {"_": {"default": "mdi:video", "state": {"off": "mdi:video-off"}}},
    "person": {"_": {"default": "mdi:account", "state": {"not_home": "mdi:account-arrow-right"}}},
    "weather": {"_": {"default": "mdi:weather-partly-cloudy"}},
    "alarm_control_panel": {"_": {"default": "mdi:shield", "state": {"armed_home": "mdi:shield-home",
                                                                     "armed_away": "mdi:shield-lock", "disarmed": "mdi:shield-off"}}},
    "vacuum": {"_": {"default": "mdi:robot-vacuum"}},
    "fan": {"_": {"default": "mdi:fan", "state": {"off": "mdi:fan-off"}}},
    "todo": {"_": {"default": "mdi:clipboard-list"}},
    "scene": {"_": {"default": "mdi:palette"}},
}
ICONS["sensor"].update({"carbon_dioxide": {"default": "mdi:molecule-co2"}, "pm25": {"default": "mdi:blur"}})


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
    "camera._": {"idle": "Idle", "streaming": "Streaming"},
    "person._": {"home": "Home", "not_home": "Away"},
    "binary_sensor.door": {"on": "Open", "off": "Closed"},
    "binary_sensor.motion": {"on": "Detected", "off": "Clear"},
    "weather._": {"partlycloudy": "Partly cloudy", "rainy": "Rainy", "sunny": "Sunny", "cloudy": "Cloudy"},
    "alarm_control_panel._": {"armed_home": "Armed home", "armed_away": "Armed away", "armed_night": "Armed night",
                              "disarmed": "Disarmed"},
    "vacuum._": {"docked": "Docked", "cleaning": "Cleaning", "returning": "Returning"},
    "fan._": {"on": "On", "off": "Off"},
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


# ---- the pictures: the TV still and the album art are drawn here; the camera stills are CC0 photos

def _png(w, h, pixel):
    rows = b"".join(b"\0" + bytes(c for x in range(w) for c in pixel(x / w, y / h)) for y in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def _mix(a, b, f):
    f = max(0.0, min(1.0, f))
    return tuple(a[i] + (b[i] - a[i]) * f for i in range(3))


def _poster(u, v):
    """The TV show's still: a moonlit sea with a lighthouse beam."""
    if v < 0.55:
        c = _mix((12, 22, 54), (40, 70, 120), v / 0.55)
    else:
        c = _mix((18, 42, 70), (6, 16, 34), (v - 0.55) / 0.45)
        c = _mix(c, (200, 210, 230), max(0.0, 0.5 - abs(u - 0.7) * 6) * (0.5 + 0.5 * math.sin(v * 90)))
    if (u - 0.7) ** 2 + (v - 0.22) ** 2 < 0.004:
        c = (236, 236, 222)
    if 0.16 < u < 0.2 and 0.34 < v < 0.56:
        c = (30, 32, 40)
    beam = max(0.0, 1 - abs((v - 0.35) - (u - 0.18) * 0.25) * 18) * (u > 0.18)
    return tuple(int(x) for x in _mix(c, (255, 236, 180), beam * 0.35))


def _art(u, v):
    """Album art: a soft sky-to-violet field with a low sun."""
    c = _mix((40, 60, 130), (140, 80, 170), v * 0.8 + u * 0.2)
    d = math.hypot(u - 0.62, v - 0.58)
    if d < 0.2:
        c = _mix((250, 200, 150), c, (d / 0.2) ** 3)
    if v > 0.72:
        c = _mix(c, (20, 24, 52), (v - 0.72) / 0.28 + 0.2 * math.sin(u * 20) * (v - 0.72))
    return tuple(int(x) for x in c)


_assets = {}
HERE = os.path.dirname(os.path.abspath(__file__))
# The cameras' stills, by the name fakeha asks for (its camera_proxy): CC0 photos, photos/README.md.
PHOTOS = {"still-camera.front_garden.png": "front-garden.jpg", "still-camera.driveway.png": "driveway.jpg"}


def asset(name):
    if name not in _assets:
        if name in PHOTOS:
            with open(os.path.join(HERE, "photos", PHOTOS[name]), "rb") as f:
                _assets[name] = ("image/jpeg", f.read())
        elif name == "showcase-tv.png":
            _assets[name] = ("image/png", _png(320, 180, _poster))
        elif name == "showcase-art.png":
            _assets[name] = ("image/png", _png(300, 300, _art))
        else:
            return _clarity.asset(name)
    return _assets[name]
