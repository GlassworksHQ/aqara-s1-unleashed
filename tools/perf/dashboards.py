"""Clarity's fixed home (MIT): the entities, their states and history, HA's config, panels and
services, and the reference dashboard as a real Lovelace config, for the fake Home Assistant
(fakeha.py): one dashboard whose views are the start view (where the kiosk comes back to after a
restart) and the three reference tiers. Everything is seeded, so every run serves the same home.

The counts are in page/tiers.json, the one source the harness reads too.
"""
import json
import os
import random
import struct
import time
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "page", "tiers.json")) as _f:
    TIERS = json.load(_f)
DASHBOARD = TIERS["DASHBOARD"]
# The camera entities and the stream each serves (camera.py PROFILES): one main stream, sub-streams.
# clarity_cam_* are HA's cameras with go2rtc (WebRTC and HLS, #202); clarity_hls_* the same streams
# from a camera with HLS only, for the fallback views (tiers.json FALLBACK).
CAMERA_PROFILES = {"camera.clarity_cam_0": "720p", "camera.clarity_cam_1": "360p", "camera.clarity_cam_2": "360p",
                   "camera.clarity_cam_3": "360p", "camera.clarity_hls_0": "720p", "camera.clarity_hls_1": "360p",
                   "camera.clarity_hls_2": "360p", "camera.clarity_hls_3": "360p"}
# What camera/capabilities offers for each (HA core camera_capabilities: a camera with a WebRTC
# provider offers both; one with only the stream integration, HLS).
CAMERA_STREAM_TYPES = {c: ["hls"] if ".clarity_hls_" in c else ["hls", "web_rtc"] for c in CAMERA_PROFILES}
FALLBACK = TIERS["FALLBACK"]                # the HLS copy of a view -> the view
VIEWS = ["start"] + TIERS["ORDER"] + list(FALLBACK)          # view index 0 is the start view
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


def stream_context(kind, gen, k, late_ms=0):
    """The context id of a live stream's k-th change: S for a sensor value, F for an on/off flip, the
    stream's generation (one per /clarity/live), its number, and how late the fake sent it (ms, after
    its fixed time from the stream's start). 26 characters, as HA's ULIDs; HA's frontend treats a
    context id as opaque. The harness reads it, so it knows when the fake actually sent the change,
    and that it is this stream's. (smoke10: one change went out 92 ms late; timed from its fixed
    time, it would have read 92 ms slower than the panel showed it.)"""
    return f"01JC1ARITY{kind}{gen:05d}{k:06d}{max(0, min(9999, round(late_ms))):04d}"


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
    for cam, profile in CAMERA_PROFILES.items():
        # HA's camera entity with the STREAM feature (CameraEntityFeature.STREAM = 2): the frontend
        # then asks camera/capabilities and plays what it offers (CAMERA_STREAM_TYPES).
        add(cam, "streaming", {"friendly_name": f"Camera {profile}", "access_token": f"clarity-{cam.split('.')[1]}",
                               "entity_picture": f"/api/camera_proxy/{cam}?token=clarity-{cam.split('.')[1]}",
                               "frontend_stream_type": "hls", "supported_features": 2})
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
                                                                         "sensor", "light", "switch", "binary_sensor",
                                                                         # ha-hls-player plays only when HA has `stream`
                                                                         "camera", "stream"],
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
    out[DASHBOARD] = {"component_name": "lovelace", "icon": "mdi:view-dashboard", "title": "Clarity",
                      "config": {"mode": "storage"}, "url_path": DASHBOARD, "require_admin": False,
                      "show_in_sidebar": False, "config_panel_domain": None}
    return out


def dashboards_list():
    return [{"id": "clarity_ref", "url_path": DASHBOARD, "title": "Clarity", "icon": "mdi:view-dashboard",
             "show_in_sidebar": False, "require_admin": False, "mode": "storage"}]


def icons(category, integration=None):
    """frontend/get_icons: the icon translations HA core sends for its integrations (none yet)."""
    return {}


def translations(category):
    """frontend/get_translations: the text HA core sends for its integrations (none)."""
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


def head_cards():
    """How many cards the head is: the clock, the toggle, the head's tiles and the Energy card, first
    in every view's config and all on its first screen. "Usable" waits for all of them (F1)."""
    return 2 + len(head()["tiles"]) + 1


def camera_cards(view):
    """The view's live camera cards (tiers.json CAMERAS), right after the head: a picture-entity card
    per stream, and a list of streams is a grid of them. A fallback view's are the HLS-only cameras."""
    kind = "hls" if view in FALLBACK else "cam"
    used = {"720p": iter([f"camera.clarity_{kind}_0"]),
            "360p": iter([f"camera.clarity_{kind}_{i}" for i in (1, 2, 3)])}

    def card(profile):
        return {"type": "picture-entity", "entity": next(used[profile]), "camera_view": "live",
                "show_name": False, "show_state": False}
    out = []
    for item in TIERS["CAMERAS"][FALLBACK.get(view, view)]:
        if isinstance(item, list):
            out.append({"type": "grid", "columns": len(item), "square": False, "cards": [card(p) for p in item]})
        else:
            out.append(card(item))
    return out


def view_spec(view):
    """A view's counts: a reference tier's, or the start view's (the light view's, plus START's)."""
    if view == "start":
        st = TIERS["START"]
        return dict(TIERS["TIERS"][st["base"]], **{k: v for k, v in st.items() if k != "base"})
    return TIERS["TIERS"][FALLBACK.get(view, view)]


def lovelace(url_path):
    """The one reference dashboard: its views are the start view and the three reference tiers, each
    at its own path (/clarity-ref/<view>). Opening a view is HA's own navigation within it."""
    if url_path != DASHBOARD:
        return {"views": [{"title": "Home", "cards": []}]}
    return {"views": [{"title": f"Clarity {v}", "path": v, "cards": view_cards(v)} for v in VIEWS]}


def view_cards(view):
    spec, rnd, hd = view_spec(view), random.Random(SEED), head()
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
    first += camera_cards(view)
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
    return first + deck


def window(start, end, now=None):
    """The time window a card asked for, in epoch seconds. The frontend sends its start (now less the
    card's hours_to_show, on the panel's clock) and often no end; every reference card shows 24 h,
    so the end is then 24 h after the start, which needs no clock of ours. With neither, the last
    24 h. History and statistics are anchored to it, so a graph shows the same shape on any date (F4:
    anchored to a fixed date, heavy's graphs went empty a day after it)."""
    import datetime
    parse = lambda x: datetime.datetime.fromisoformat(x.replace("Z", "+00:00")).timestamp()  # noqa: E731
    if start:
        t0 = parse(start)
        t1 = parse(end) if end else t0 + 86400
    else:
        t1 = parse(end) if end else (now if now is not None else time.time())
        t0 = t1 - 86400
    return t0, max(t1, t0)


def history(entity_ids, start, end, now=None):
    """96 seeded points across the asked window, for the sensor and history-graph cards
    (history/stream, compressed: s = state, lu = last updated). The values depend only on the entity,
    the times only on the window."""
    t0, t1 = window(start, end, now)
    n = 96
    at = lambda k: round(t0 + (t1 - t0) * k / n, 3)  # noqa: E731
    out = {}
    for e in entity_ids:
        rnd = random.Random(f"{SEED}:{e}")
        if e.startswith("sensor."):
            base = 50 + 400 * rnd.random()
            out[e] = [{"s": str(round(base * (0.7 + 0.6 * rnd.random()), 1)), "lu": at(k),
                       **({"a": {}} if k == 0 else {})} for k in range(n)]
        else:
            out[e] = [{"s": "on" if k % 2 else "off", "lu": at(k), **({"a": {}} if k == 0 else {})}
                      for k in range(n)]
    return out


def statistics(statistic_ids, start_time, end_time, period, now=None):
    """recorder/statistics_during_period (the sensor card's graph for sensors with a state class):
    seeded points ending at the asked window's end, {id: [{start, end, mean, min, max, state}]}, times
    in ms. The same count and values on any date."""
    t0, t1 = window(start_time, end_time, now)
    step = {"5minute": 300, "hour": 3600, "day": 86400}.get(period or "hour", 3600)
    n = min(500, max(1, round((t1 - t0) / step)))
    out = {}
    for sid in statistic_ids or []:
        rnd = random.Random(f"{SEED}:stat:{sid}")
        base, pts = 50 + 400 * rnd.random(), []
        for i in range(n):
            t = t1 - (n - i) * step
            mean = round(base * (0.7 + 0.6 * rnd.random()), 2)
            pts.append({"start": int(t * 1000), "end": int((t + step) * 1000), "mean": mean,
                        "min": round(mean * 0.9, 2), "max": round(mean * 1.1, 2), "state": mean})
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
        cfg = dict(TIERS, FRONTEND=hafrontend.VERSION, HEAD_ENTITIES=head(), VIEWS=VIEWS, HEAD_CARDS=head_cards(),
                   CARDS={v: len(view_cards(v)) for v in VIEWS})
        body = read("stats.js") + b"\nwindow.GW_CLARITY = " + json.dumps(cfg).encode() + b";\n" + read("harness.js")
        return ("application/javascript", body)
    return None


class Stream:
    """The live updates (page/tiers.json LIVE), as HA would send them: `updatesPerS` sensor value
    changes a second over the head's sensor tiles and rows, and an on/off change every
    `flipEveryMs` over its on/off tiles. Fixed times and fixed values, so every run is the same."""

    def __init__(self, set_state, states, record=None, gen=0):
        import threading
        self.set_state, self.states, self.stop, self.gen = set_state, states, threading.Event(), gen
        # The schedule's zero, on the fake's monotonic clock: change k goes out at start + k / rate.
        self.start = time.monotonic()
        self.thread = None
        self.record = record or (lambda entry: None)
        h = head()
        self.targets, self.switches = h["sensors"] + h["rows"], h["switches"]

    def sensor_change(self, k):
        """The k-th sensor change: (entity, new value). Never a zero step: the same value again is
        no change for HA, so nothing would be drawn."""
        e = self.targets[(k * 7) % len(self.targets)]
        v = float(self.states[e]["state"]) + (((k * 13) % 6) + 1) / 10 * (1 if k % 2 else -1)
        return e, f"{v:.1f}"

    def run(self):
        import time
        start, every, flip = self.start, 1 / TIERS["LIVE"]["updatesPerS"], TIERS["LIVE"]["flipEveryMs"] / 1000
        k = f = 0
        while not self.stop.is_set():
            nxt_k, nxt_f = start + (k + 1) * every, start + (f + 1) * flip
            wait = min(nxt_k, nxt_f) - time.monotonic()
            if wait > 0 and self.stop.wait(wait):
                return
            if time.monotonic() >= nxt_k:
                k += 1
                e, v = self.sensor_change(k)
                late = (time.monotonic() - nxt_k) * 1000
                self.set_state(e, v, context=stream_context("S", self.gen, k, late))
                # How late the fake itself sent it (also in the context): the report shows the rhythm.
                self.record({"stream": "S", "k": k, "late_ms": round(late, 1)})
            if time.monotonic() >= nxt_f:
                f += 1
                e = self.switches[f % len(self.switches)]
                late = (time.monotonic() - nxt_f) * 1000
                self.set_state(e, "off" if self.states[e]["state"] == "on" else "on", context=stream_context("F", self.gen, f, late))
                self.record({"stream": "F", "k": f, "late_ms": round(late, 1)})
