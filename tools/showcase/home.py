"""The showcase home (MIT): made-up rooms, states, pictures and a day of history, and the dashboard every
frame's render shows (render.mjs; maintainer, #144: one dashboard for all frames, so they compare).

It is a home for Clarity's fake Home Assistant (tools/perf/fakeha.py `serve(home=...)`): states, config,
panels, the dashboard, history and statistics, the weather forecast, a camera still and album art.
What it doesn't set comes from Clarity's dashboards.py. Everything is seeded and anchored to NOW, which
render.mjs also gives the browser's clock, so every run draws the same picture. Names are made up, the
pictures are drawn here (no camera, no real place, no faces), and nothing comes from a real home.

Two variants, for the maintainer to pick from (VARIANT): "rich" (graphs, camera, media) and "calm"
(tiles and one graph).
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
VARIANTS = ("rich", "calm")
VARIANT = "rich"
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
    "switch.coffee_machine": ("off", {"friendly_name": "Coffee machine"}),
    "switch.garden_lights": ("on", {"friendly_name": "Garden lights"}),
    "media_player.living_room": ("playing", {
        "friendly_name": "Living room speaker", "device_class": "speaker", "volume_level": 0.3,
        "is_volume_muted": False, "media_content_type": "music", "media_title": "Slow Morning",
        "media_artist": "The Glass Tones", "media_album_name": "Low Light",
        "entity_picture": "/clarity/showcase-art.png", "supported_features": 152463}),
    "camera.front_garden": ("idle", {
        "friendly_name": "Front garden", "access_token": "showcase",
        "entity_picture": "/api/camera_proxy/camera.front_garden?token=showcase", "supported_features": 0}),
    "lock.front_door": ("locked", {"friendly_name": "Front door", "supported_features": 0}),
    "cover.bedroom_blinds": ("closed", {"friendly_name": "Bedroom blinds", "current_position": 0,
                                        "device_class": "blind", "supported_features": 15}),
    "binary_sensor.back_door": ("off", {"friendly_name": "Back door", "device_class": "door"}),
    "binary_sensor.hall_motion": ("off", {"friendly_name": "Hall motion", "device_class": "motion"}),
    "sensor.living_room_temperature": _sensor("Living room", 20.6, "temperature", "°C"),
    "sensor.living_room_humidity": _sensor("Living room humidity", 46, "humidity", "%"),
    "sensor.bedroom_temperature": _sensor("Bedroom", 18.2, "temperature", "°C"),
    "sensor.outdoor_temperature": _sensor("Outside", 9.4, "temperature", "°C"),
    "sensor.home_power": _sensor("Power", 412, "power", "W"),
    "sensor.energy_today": _sensor("Energy today", 7.8, "energy", "kWh", "total_increasing"),
}


def states():
    return {eid: {"entity_id": eid, "state": st, "attributes": attrs, "last_changed": START,
                  "last_updated": START, "context_id": _clarity.context()}
            for eid, (st, attrs) in ENTITIES.items()}


def config():
    return dict(_clarity.config(), location_name="Home", latitude=52.0, longitude=5.0,
                components=["frontend", "lovelace", "history", "recorder", "sensor", "light", "switch", "binary_sensor",
                            "climate", "media_player", "lock", "cover", "camera", "weather", "person"])


def services():
    s = {"name": "", "description": "", "fields": {}, "target": {"entity": [{}]}}
    names = {"light": ["turn_on", "turn_off", "toggle"], "switch": ["turn_on", "turn_off", "toggle"],
             "lock": ["lock", "unlock"], "cover": ["open_cover", "close_cover", "stop_cover"],
             "climate": ["set_temperature", "set_hvac_mode"], "media_player": ["media_play_pause", "media_next_track"]}
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

def _tile(entity, **extra):
    return {"type": "tile", "entity": entity, **extra}


def _heading(title, *badges):
    return {"type": "heading", "heading": title, "heading_style": "title",
            "badges": [{"type": "entity", "entity": b} for b in badges]}


def _graph(title, entities, hours=12):
    return {"type": "history-graph", "title": title, "hours_to_show": hours, "entities": entities}


CLOCK = {"type": "clock", "clock_size": "large", "time_format": "24", "no_background": True}
WEATHER = {"type": "weather-forecast", "entity": "weather.home", "show_forecast": True, "forecast_type": "daily",
           "forecast_slots": 4}
BADGES = ([{"type": "entity", "entity": e, "show_name": True} for e in ("person.alex", "person.sam")]
          + [{"type": "entity", "entity": e} for e in ("lock.front_door", "sensor.home_power")])


def _rich():
    return [
        {"type": "grid", "cards": [CLOCK, WEATHER,
                                   _graph("Temperature", ["sensor.living_room_temperature", "sensor.outdoor_temperature"])]},
        {"type": "grid", "cards": [_heading("Living room", "sensor.living_room_temperature", "sensor.living_room_humidity"),
                                   {"type": "thermostat", "entity": "climate.living_room"},
                                   _tile("light.living_room"), _tile("light.dining_table"),
                                   {"type": "media-control", "entity": "media_player.living_room"}]},
        {"type": "grid", "cards": [_heading("Front garden", "binary_sensor.back_door"),
                                   {"type": "picture-entity", "entity": "camera.front_garden", "camera_view": "auto",
                                    "show_state": False},
                                   _tile("lock.front_door"), _tile("light.porch"), _tile("switch.garden_lights"),
                                   _tile("binary_sensor.hall_motion")]},
        {"type": "grid", "cards": [_heading("Energy", "sensor.home_power"),
                                   {"type": "statistics-graph", "title": "This week", "entities": ["sensor.energy_today"],
                                    "period": "day", "days_to_show": 7, "chart_type": "bar", "stat_types": ["change"]},
                                   _graph("Power", ["sensor.home_power"])]},
        {"type": "grid", "cards": [_heading("Kitchen and bedroom", "sensor.bedroom_temperature"),
                                   _tile("light.kitchen"), _tile("switch.coffee_machine"),
                                   _tile("climate.bedroom"), _tile("light.bedroom"),
                                   _tile("cover.bedroom_blinds", grid_options={"columns": 12})]},
    ]


def _calm():
    return [
        {"type": "grid", "cards": [CLOCK, WEATHER]},
        {"type": "grid", "cards": [_heading("Living room", "sensor.living_room_temperature", "sensor.living_room_humidity"),
                                   _tile("climate.living_room", grid_options={"columns": 12}),
                                   _tile("light.living_room"), _tile("light.dining_table"),
                                   _tile("media_player.living_room", grid_options={"columns": 12})]},
        {"type": "grid", "cards": [_heading("Entrance", "binary_sensor.back_door"),
                                   _tile("lock.front_door"), _tile("light.porch"),
                                   _tile("light.hallway"), _tile("binary_sensor.hall_motion")]},
        {"type": "grid", "cards": [_heading("Climate"),
                                   _graph("Temperature", ["sensor.living_room_temperature", "sensor.outdoor_temperature"])]},
        {"type": "grid", "cards": [_heading("Kitchen and bedroom", "sensor.bedroom_temperature"),
                                   _tile("light.kitchen"), _tile("switch.coffee_machine"),
                                   _tile("climate.bedroom"), _tile("light.bedroom")]},
    ]


def lovelace(url_path):
    if url_path != DASHBOARD:
        return {"views": [{"title": "Home", "cards": []}]}
    sections = _rich() if VARIANT == "rich" else _calm()
    return {"views": [{"title": "Home", "path": "home", "type": "sections", "max_columns": 4,
                       "dense_section_placement": True, "badges": BADGES, "sections": sections}]}


def cards():
    """How many cards the dashboard draws (render.mjs waits for all of them)."""
    return sum(len(s["cards"]) for s in lovelace(DASHBOARD)["views"][0]["sections"])


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
            pts.append({"s": str(round(_curve(e, t), 2 if "temperature" in e else 0)), "lu": t})
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


HANDLERS = {
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
                     "speaker": {"default": "mdi:speaker", "state": {"playing": "mdi:speaker-play", "off": "mdi:speaker-off"}}},
    "cover": {"_": {"default": "mdi:window-open", "state": {"closed": "mdi:window-closed"}},
              "blind": {"default": "mdi:blinds-horizontal", "state": {"closed": "mdi:blinds-horizontal-closed"}}},
    "climate": {"_": {"default": "mdi:thermostat"}},
    "camera": {"_": {"default": "mdi:video", "state": {"off": "mdi:video-off"}}},
    "person": {"_": {"default": "mdi:account", "state": {"not_home": "mdi:account-arrow-right"}}},
    "weather": {"_": {"default": "mdi:weather-partly-cloudy"}},
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
    "camera._": {"idle": "Idle", "streaming": "Streaming"},
    "person._": {"home": "Home", "not_home": "Away"},
    "binary_sensor.door": {"on": "Open", "off": "Closed"},
    "binary_sensor.motion": {"on": "Detected", "off": "Clear"},
    "weather._": {"partlycloudy": "Partly cloudy", "rainy": "Rainy", "sunny": "Sunny", "cloudy": "Cloudy"},
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


# ---- the pictures: drawn here, so nothing in them is real

def _png(w, h, pixel):
    rows = b"".join(b"\0" + bytes(c for x in range(w) for c in pixel(x / w, y / h)) for y in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def _mix(a, b, f):
    f = max(0.0, min(1.0, f))
    return tuple(a[i] + (b[i] - a[i]) * f for i in range(3))


def _garden(u, v):
    """A garden at dusk as a camera sees it: sky, hills, a lawn, a path, a lit porch on the left."""
    horizon = 0.46 + 0.035 * math.sin(u * 7.0) + 0.02 * math.sin(u * 17.0 + 1)
    if v < horizon:
        c = _mix((22, 34, 66), (196, 120, 92), (v / horizon) ** 2.2)
    elif v < 0.58:
        c = _mix((30, 40, 44), (24, 34, 30), (v - horizon) / 0.12)
    else:
        c = _mix((30, 52, 34), (46, 74, 44), (v - 0.58) / 0.42)
        half = 0.03 + (v - 0.58) * 0.55
        if abs(u - (0.58 - (v - 0.58) * 0.35)) < half:
            c = _mix((112, 104, 92), (150, 140, 124), (v - 0.58) / 0.42)
    if 0.7 < u and 0.5 < v < 0.62 and (u * 40) % 1 < 0.18:
        c = (58, 50, 42)
    if u < 0.22 and v > 0.14 - 0.25 * u:
        c = (40, 36, 36)
        if 0.07 < u < 0.15 and v > 0.5:
            c = (34, 28, 26) if (0.072 < u < 0.148 and v > 0.515) else (70, 60, 52)
            if 0.09 < u < 0.13 and 0.54 < v < 0.68:
                c = (230, 186, 120)
        if 0.02 < u < 0.055 and 0.3 < v < 0.42:
            c = (236, 196, 132)
    for bx, by, br in ((0.3, 0.6, 0.05), (0.36, 0.63, 0.04), (0.9, 0.66, 0.06), (0.82, 0.7, 0.045)):
        if (u - bx) ** 2 * 3.2 + (v - by) ** 2 < br * br:
            c = (22, 40, 26)
    glow = math.exp(-((u - 0.11) ** 2 * 120 + (v - 0.44) ** 2 * 90))
    c = _mix(c, (255, 206, 140), glow * 0.35)
    vig = 1 - 0.35 * ((u - 0.5) ** 2 + (v - 0.5) ** 2) * 2
    return tuple(int(max(0, min(255, x * vig))) for x in c)


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


def asset(name):
    if name not in _assets:
        if name == "still-camera.front_garden.png":
            _assets[name] = ("image/png", _png(640, 360, _garden))
        elif name == "showcase-art.png":
            _assets[name] = ("image/png", _png(300, 300, _art))
        else:
            return _clarity.asset(name)
    return _assets[name]
