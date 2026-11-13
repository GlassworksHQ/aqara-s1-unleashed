"""Clarity's fixed home (MIT): the entities, their states and history, HA's config, panels and
services, and the three reference dashboards as real Lovelace configs, for the fake Home Assistant
(fakeha.py). Everything is seeded, so every run serves the same home.

The counts are in page/tiers.json, the one source the harness reads too.
"""
import json
import os
import random
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "page", "tiers.json")) as _f:
    TIERS = json.load(_f)
SEED = 89
START = 1_794_500_000.0          # a fixed epoch for every last_changed (2026-11-13)
# The fixed home's size: enough for the heavy dashboard's ~370 entities with room to spare, and a
# realistic count for a large home (HA homes run to hundreds of entities).
N_SENSORS, N_ONOFF = 300, 150

USER = {"id": "clarity", "name": "Clarity", "is_owner": False, "is_admin": False,
        "credentials": [{"auth_provider_type": "homeassistant", "auth_provider_id": None}], "mfa_modules": []}

MANIFEST = {"name": "Home Assistant", "short_name": "Assistant", "start_url": "/?homescreen=1",
            "display": "standalone", "theme_color": "#03a9f4", "background_color": "#FFFFFF", "icons": []}

_ctx = [0]


def context():
    """A context id, as HA's ULIDs look, but counted, so runs don't differ."""
    _ctx[0] += 1
    return f"01JC1ARITY{_ctx[0]:016d}"


# ---- the entities

UNITS = {"power": "W", "energy": "kWh", "temperature": "°C", "humidity": "%"}
NAMES = ["Kitchen", "Hall", "Office", "Bedroom", "Lounge", "Garage", "Garden", "Bath", "Study", "Loft"]


def _sensor(i, rnd):
    dc = ["power", "energy", "temperature", "humidity"][i % 4]
    base = {"power": 300, "energy": 12, "temperature": 21, "humidity": 50}[dc]
    value = round(base * (0.5 + rnd.random()), 1)
    return f"sensor.clarity_{i}", str(value), {
        "friendly_name": f"{NAMES[i % len(NAMES)]} {dc} {i}", "device_class": dc,
        "unit_of_measurement": UNITS[dc], "state_class": "total_increasing" if dc == "energy" else "measurement"}


def _onoff(domain, i, rnd):
    on = rnd.random() < 0.5
    attrs = {"friendly_name": f"{NAMES[i % len(NAMES)]} {domain.replace('_', ' ')} {i}"}
    if domain == "light":
        attrs.update({"supported_color_modes": ["brightness"], "color_mode": "brightness" if on else None,
                      "brightness": 180 if on else None, "supported_features": 0})
    if domain == "binary_sensor":
        attrs["device_class"] = ["door", "motion", "window"][i % 3]
    return f"{domain}.clarity_{i}", "on" if on else "off", attrs


def states():
    """Every entity of the fixed home: {entity_id: state object}."""
    rnd = random.Random(SEED)
    out = {}

    def add(eid, state, attrs):
        out[eid] = {"entity_id": eid, "state": state, "attributes": attrs, "last_changed": START,
                    "last_updated": START, "context_id": context()}

    add("light.living_room", "off", {"friendly_name": "Living room", "supported_color_modes": ["brightness"],
                                     "color_mode": None, "brightness": None, "supported_features": 0})
    for i in range(N_SENSORS):
        add(*_sensor(i, rnd))
    for d in ("light", "switch", "binary_sensor"):
        for i in range(N_ONOFF):
            add(*_onoff(d, i, rnd))
    return out


def state_objects(sts):
    for s in sts.values():
        yield {"entity_id": s["entity_id"], "state": s["state"], "attributes": s["attributes"],
               "last_changed": _iso(s["last_changed"]), "last_updated": _iso(s["last_updated"]),
               "context": {"id": s["context_id"], "parent_id": None, "user_id": None}}


def _iso(t):
    import datetime
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).isoformat()


# ---- HA's config, panels and services

def config():
    return {"latitude": 52.37, "longitude": 4.89, "elevation": 0, "radius": 100,
            "unit_system": {"length": "km", "accumulated_precipitation": "mm", "area": "m²", "mass": "g",
                            "pressure": "Pa", "temperature": "°C", "volume": "L", "wind_speed": "m/s"},
            "location_name": "Clarity", "time_zone": "UTC", "components": ["frontend", "lovelace", "history",
                                                                         "sensor", "light", "switch", "binary_sensor"],
            "config_dir": "/config", "allowlist_external_dirs": [], "allowlist_external_urls": [],
            "version": "2026.11.0", "config_source": "storage", "recovery_mode": False, "safe_mode": False,
            "state": "RUNNING", "external_url": None, "internal_url": None, "currency": "EUR", "country": "NL",
            "language": "en", "debug": False}


def services():
    toggle = {"name": "Toggle", "description": "", "fields": {}, "target": {"entity": [{}]}}
    return {d: {"turn_on": toggle, "turn_off": toggle, "toggle": toggle} for d in ("light", "switch")}


def panels():
    # "home" is this release's default panel (src/data/panel.ts DEFAULT_PANEL); the router falls back
    # to it before the panels have arrived, as a real 2026.11 HA serves it.
    out = {"home": {"component_name": "home", "icon": "mdi:home", "title": "home", "config": None,
                    "url_path": "home", "require_admin": False, "config_panel_domain": None},
           "lovelace": {"component_name": "lovelace", "icon": None, "title": None, "config": {"mode": "storage"},
                        "url_path": "lovelace", "require_admin": False, "config_panel_domain": None}}
    for t in TIERS["ORDER"]:
        out[f"clarity-{t}"] = {"component_name": "lovelace", "icon": "mdi:view-dashboard", "title": f"Clarity {t}",
                               "config": {"mode": "storage"}, "url_path": f"clarity-{t}", "require_admin": False,
                               "show_in_sidebar": False, "config_panel_domain": None}
    return out


def dashboards_list():
    return [{"id": f"clarity_{t}", "url_path": f"clarity-{t}", "title": f"Clarity {t}", "icon": "mdi:view-dashboard",
             "show_in_sidebar": False, "require_admin": False, "mode": "storage"} for t in TIERS["ORDER"]]


def icons(category, integration=None):
    """frontend/get_icons: the icon translations HA core sends for its integrations (none yet)."""
    return {}


# ---- the dashboards

def head():
    """The first screen, the same on every page (page/tiers.json HEAD): the clock, the toggle, the
    sensor and on/off tiles, and an "Energy" entities card of sensor rows. Only these change while
    the page is live."""
    h = TIERS["HEAD"]
    sensors = [f"sensor.clarity_{i}" for i in range(h["sensorTiles"])]
    switches = ["light.clarity_0", "switch.clarity_0", "light.clarity_1"][:h["switchTiles"]]
    rows = [f"sensor.clarity_{i}" for i in range(h["sensorTiles"], h["sensorTiles"] + h["rows"])]
    tiles = []
    for k in range(max(len(sensors), len(switches))):
        if k < len(sensors):
            tiles.append(sensors[k])
        if k < len(switches):
            tiles.append(switches[k])
    return {"sensors": sensors, "switches": switches, "rows": rows, "tiles": tiles}


def lovelace(url_path):
    tier = (url_path or "").replace("clarity-", "")
    if tier not in TIERS["TIERS"]:
        return {"views": [{"title": "Home", "cards": []}]}
    spec, rnd, hd = TIERS["TIERS"][tier], random.Random(SEED), head()
    taken = set(hd["sensors"] + hd["switches"] + hd["rows"] + ["light.living_room"])
    pool = {d: [f"{d}.clarity_{i}" for i in range(N_SENSORS if d == "sensor" else N_ONOFF)]
            for d in ("sensor", "light", "switch", "binary_sensor")}

    def take(domains, n):
        out = []
        while len(out) < n:
            d = domains[len(out) % len(domains)]
            e = next((x for x in pool[d] if x not in taken), None)
            if e is None:
                raise ValueError(f"the fixed home has too few {d} entities for this dashboard")
            taken.add(e)
            out.append(e)
        return out

    first = [{"type": "clock", "clock_size": "large", "show_seconds": True},
             {"type": "tile", "entity": "light.living_room", "tap_action": {"action": "toggle"}}]
    first += [{"type": "tile", "entity": e} for e in hd["tiles"]]
    first.append({"type": "entities", "title": "Energy", "entities": hd["rows"]})
    deck = []
    for e in take(["sensor", "light", "switch", "binary_sensor"], spec["tiles"] - 1 - len(hd["tiles"])):
        deck.append({"type": "tile", "entity": e})
    for i in range(spec["entities"] - 1):
        deck.append({"type": "entities", "title": f"{NAMES[i % len(NAMES)]} {i + 2}",
                     "entities": take(["sensor", "light", "switch", "binary_sensor"], spec["rows"])})
    for i in range(spec["glances"]):
        deck.append({"type": "glance", "title": f"Glance {i + 1}",
                     "entities": take(["sensor", "binary_sensor", "light"], TIERS["GLANCE_ENTITIES"])})
    for i in range(spec["longLists"]):
        deck.append({"type": "entities", "title": f"All lights {i + 1}", "entities": take(["light", "switch"], spec["longRows"])})
    for e in take(["sensor"], spec["sensors"]):
        deck.append({"type": "sensor", "entity": e, "graph": "line", "hours_to_show": 24})
    for i in range(spec["histories"]):
        deck.append({"type": "history-graph", "title": f"History {i + 1}", "hours_to_show": 24,
                     "entities": take(["sensor"], 2)})
    for i in range(spec["pictures"]):
        deck.append({"type": "picture", "image": f"/clarity/picture-{i}.png"})
    rnd.shuffle(deck)
    return {"views": [{"title": f"Clarity {tier}", "path": "0", "cards": first + deck}]}


def history(entity_ids, start, end):
    """24 h of seeded history for the sensor and history-graph cards (history/stream, compressed:
    s = state, lu = last updated)."""
    out = {}
    for e in entity_ids:
        rnd = random.Random(f"{SEED}:{e}")
        n = 96
        if e.startswith("sensor."):
            base = 50 + 400 * rnd.random()
            out[e] = [{"s": str(round(base * (0.7 + 0.6 * rnd.random()), 1)), "lu": START - (n - k) * 900,
                       **({"a": {}} if k == 0 else {})} for k in range(n)]
        else:
            out[e] = [{"s": "on" if k % 2 else "off", "lu": START - (n - k) * 900, **({"a": {}} if k == 0 else {})}
                      for k in range(n)]
    return out


def statistics(statistic_ids, start_time, end_time, period):
    """recorder/statistics_during_period (the sensor card's graph for sensors with a state class):
    seeded hourly points, {id: [{start, end, mean, min, max, state}]}, times in ms."""
    import datetime
    t0 = datetime.datetime.fromisoformat(start_time.replace("Z", "+00:00")).timestamp() if start_time else START - 86400
    t1 = datetime.datetime.fromisoformat(end_time.replace("Z", "+00:00")).timestamp() if end_time else START
    step = {"5minute": 300, "hour": 3600, "day": 86400}.get(period or "hour", 3600)
    out = {}
    for sid in statistic_ids or []:
        rnd = random.Random(f"{SEED}:stat:{sid}")
        base, pts, t = 50 + 400 * rnd.random(), [], t0 - t0 % step
        while t < t1 and len(pts) < 500:
            mean = round(base * (0.7 + 0.6 * rnd.random()), 2)
            pts.append({"start": int(t * 1000), "end": int((t + step) * 1000), "mean": mean,
                        "min": round(mean * 0.9, 2), "max": round(mean * 1.1, 2), "state": mean})
            t += step
        out[sid] = pts
    return out


# ---- assets: the picture cards' images, and the harness

def _png(w, h, rnd):
    """A generated 480x270 picture (PNG), like a camera snapshot: a gradient and circles, in the
    brand's sky-to-violet hues."""
    import colorsys
    h0, h1 = (199 + rnd.random() * 60) / 360, (199 + rnd.random() * 60) / 360
    circles = [(rnd.random() * w, rnd.random() * h, 8 + rnd.random() * 40, (199 + rnd.random() * 60) / 360) for _ in range(40)]
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            f = (x / w + y / h) / 2
            r, g, b = colorsys.hls_to_rgb(h0 + (h1 - h0) * f, 0.35 - 0.2 * f, 0.45)
            for cx, cy, cr, ch in circles[:12]:
                if (x - cx) ** 2 + (y - cy) ** 2 < cr * cr:
                    r2, g2, b2 = colorsys.hls_to_rgb(ch, 0.6, 0.6)
                    r, g, b = (r + r2) / 2, (g + g2) / 2, (b + b2) / 2
            row += bytes([int(r * 255), int(g * 255), int(b * 255)])
        rows.append(bytes(row))
    raw = zlib.compress(b"".join(rows), 6)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", raw) + chunk(b"IEND", b"")


_assets = {}


def asset(name):
    """(content type, bytes) for /clarity/<name>, or None."""
    if name in _assets:
        return _assets[name]
    if name.startswith("picture-") and name.endswith(".png"):
        k = int(name[len("picture-"):-4])
        _assets[name] = ("image/png", _png(480, 270, random.Random(f"{SEED}:picture:{k}")))
        return _assets[name]
    if name == "harness.js":
        # One script, first in HA's index.html: the shared statistics, the page config, the harness.
        def read(n):
            with open(os.path.join(HERE, "page", n), "rb") as f:
                return f.read()
        import hafrontend
        cfg = dict(TIERS, FRONTEND=hafrontend.VERSION, HEAD_ENTITIES=head())
        body = read("stats.js") + b"\nwindow.GW_CLARITY = " + json.dumps(cfg).encode() + b";\n" + read("harness.js")
        return ("application/javascript", body)
    return None


class Stream:
    """The live updates (page/tiers.json LIVE), as HA would send them: `updatesPerS` sensor value
    changes a second over the head's sensor tiles and rows, and an on/off change every
    `flipEveryMs` over its on/off tiles. Fixed times and fixed values, so every run is the same."""

    def __init__(self, set_state, states):
        import threading
        self.set_state, self.states, self.stop = set_state, states, threading.Event()
        h = head()
        self.targets, self.switches = h["sensors"] + h["rows"], h["switches"]

    def run(self):
        import time
        start, every, flip = time.monotonic(), 1 / TIERS["LIVE"]["updatesPerS"], TIERS["LIVE"]["flipEveryMs"] / 1000
        k = f = 0
        while not self.stop.is_set():
            nxt_k, nxt_f = start + (k + 1) * every, start + (f + 1) * flip
            wait = min(nxt_k, nxt_f) - time.monotonic()
            if wait > 0 and self.stop.wait(wait):
                return
            if time.monotonic() >= nxt_k:
                k += 1
                e = self.targets[(k * 7) % len(self.targets)]
                # Never a zero step: the same value again is no change for HA, so nothing would be drawn.
                v = float(self.states[e]["state"]) + (((k * 13) % 6) + 1) / 10 * (1 if k % 2 else -1)
                self.set_state(e, f"{v:.1f}")
            if time.monotonic() >= nxt_f:
                f += 1
                e = self.switches[f % len(self.switches)]
                self.set_state(e, "off" if self.states[e]["state"] == "on" else "on")
