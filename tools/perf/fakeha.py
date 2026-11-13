#!/usr/bin/env python3
"""A fake Home Assistant for Clarity (MIT): serves HA's real frontend (hafrontend.py, the pinned
build) with HA's HTTP endpoints and websocket API, from fixed states and Lovelace configs
(dashboards.py), so a panel runs the real frontend without anyone's real HA.

It is deterministic: the same build, the same states and the same update stream every run. It
answers the commands the pinned frontend sends (recorded in test/fakeha-traffic.json), and logs any
other one so a newer frontend can't pass unnoticed.

Its cameras play over WebRTC (webrtc.py: an aiortc peer behind a TURN relay on --port + 1) when
aiortc is here (run.sh runs it from webrtcenv.py's venv), and over HLS.

Only for the perf test: it listens on 127.0.0.1 (the panel reaches both ports through `adb reverse`),
and its sign-in accepts anyone, because there is nothing behind it.

  tools/perf/fakeha.py --port 8089 [--log traffic.jsonl] [--turn-port 8090]
"""
import argparse
import base64
import hashlib
import json
import mimetypes
import os
import re
import socket
import struct
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import camera  # noqa: E402
import dashboards as D  # noqa: E402
import hafrontend  # noqa: E402
import webrtc  # noqa: E402

HA_VERSION = "2026.11.0"
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
HERE = os.path.dirname(os.path.abspath(__file__))


# ---- websocket (RFC 6455): text frames, server side, no extensions

def ws_accept(key):
    return base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()


def ws_read(sock):
    """One message from the client: (opcode, payload bytes). Joins fragments, unmasks."""
    data, op0 = b"", None
    while True:
        h = _recv(sock, 2)
        fin, op, masked, n = h[0] & 0x80, h[0] & 0x0F, h[1] & 0x80, h[1] & 0x7F
        if n == 126:
            n = struct.unpack(">H", _recv(sock, 2))[0]
        elif n == 127:
            n = struct.unpack(">Q", _recv(sock, 8))[0]
        mask = _recv(sock, 4) if masked else b"\0\0\0\0"
        payload = bytes(b ^ mask[i % 4] for i, b in enumerate(_recv(sock, n)))
        if op in (8, 9, 10):              # control frames come whole, between fragments
            return op, payload
        op0 = op0 if op == 0 else op
        data += payload
        if fin:
            return op0, data


def _recv(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf


def ws_frame(payload, op=1):
    n = len(payload)
    head = bytes([0x80 | op])
    if n < 126:
        head += bytes([n])
    elif n < 1 << 16:
        head += bytes([126]) + struct.pack(">H", n)
    else:
        head += bytes([127]) + struct.pack(">Q", n)
    return head + payload


# ---- Home Assistant's websocket API

class Hub:
    """The fixed home: states, dashboards, the update stream, and every open connection."""

    def __init__(self, log=None):
        self.states = D.states()
        self.lock = threading.Lock()
        self.conns = set()
        self.log = log
        self.unknown = set()
        self._stream = None
        self.gen = 0
        # One start or stop at a time (a cancel can meet a page's still): the join below runs outside
        # self.lock, which the worker's set_state needs, but inside this one.
        self.stream_lock = threading.Lock()
        # The runner's current command for the harness (open a view). It stays until that view's
        # stream stops (/clarity/still): a page asks for one other than the last it ran, so a request
        # left behind by a page that died (a force-stopped frontend's long poll) can't use it up.
        self.cmd, self.cmd_cv = None, threading.Condition()
        # The WebRTC peer and its TURN relay (webrtc.py), when aiortc is here (serve() starts it).
        self.webrtc, self.turn_port, self.webrtc_n = None, None, 0

    def new_webrtc_session(self):
        """A WebRTC session id, as HA's ULIDs look, but counted."""
        with self.lock:
            self.webrtc_n += 1
            return f"01JC1ARITYW{self.webrtc_n:015d}"

    def new_stream(self, cam):
        """A new HLS stream for a camera: its token, and when its segment 0 began (monotonic)."""
        with self.lock:
            self.stream_n = getattr(self, "stream_n", 0) + 1
            token = f"clarity{self.stream_n:06d}"
            self.cam_streams = getattr(self, "cam_streams", {})
            self.cam_streams[token] = {"camera": cam, "profile": D.CAMERA_PROFILES[cam],
                                       "t0": time.monotonic() - D.TIERS["CAMERA"]["preRollS"]}
        self.record({"t": time.time(), "camera_stream": cam, "token": token})
        return token

    def reset(self):
        """Put the home back as it starts (dashboards.states): every entity the stream or a tap
        changed goes back, told to the frontend as HA tells any change. Called when a view's stream
        stops and before the next view opens, while nothing is measured, so every view starts from
        the same states and its stream sends the same values. Returns how many changed back."""
        first = D.states()
        with self.lock:
            back = [e for e, st in first.items() if self.states[e]["state"] != st["state"]]
        for e in back:
            self.set_state(e, first[e]["state"])
        self.record({"t": time.time(), "reset": len(back)})
        return len(back)

    def open_view(self, cmd):
        with self.cmd_cv:
            self.cmd = cmd
            self.cmd_cv.notify_all()
        self.record({"t": time.time(), "open": cmd})

    def done_view(self):
        with self.cmd_cv:
            self.cmd = None

    def next_cmd(self, after, timeout):
        """The current command, if it isn't `after` (the nonce the asking page ran last); waits up to
        `timeout` s for one. None if there is none."""
        end = time.monotonic() + timeout
        with self.cmd_cv:
            while self.cmd is None or self.cmd.get("nonce") == after:
                left = end - time.monotonic()
                if left <= 0:
                    return None
                self.cmd_cv.wait(left)
            return dict(self.cmd)

    def stream(self, on):
        """Start (a new generation of) the live stream, or stop it. Returns the generation and, for a
        start, the schedule's zero on the fake's monotonic clock (ms). A stopped stream's worker is
        waited for (outside the lock its set_state needs), so it sends nothing after this returns."""
        with self.stream_lock:
            return self._stream_locked(on)

    def _stream_locked(self, on):
        with self.lock:
            old, self._stream = self._stream, None
            if old:
                old.stop.set()
        if old and old.thread:
            old.thread.join(timeout=5)
        with self.lock:
            if on:
                self.gen += 1
                gen = self.gen
                self._stream = D.Stream(self.set_state, self.states, gen=gen,
                                        record=lambda e: self.record(dict(e, t=time.time(), gen=gen)))
                self._stream.thread = threading.Thread(target=self._stream.run, daemon=True)
                self._stream.thread.start()
                return {"gen": gen, "start_ms": round(self._stream.start * 1000, 3)}
            return {"gen": self.gen}

    def record(self, entry):
        if self.log:
            with self.lock:
                self.log.write(json.dumps(entry) + "\n")
                self.log.flush()

    def set_state(self, entity_id, state, attributes=None, context=None):
        """Change one entity, as HA does, and tell every subscriber (subscribe_entities' diff)."""
        now = time.time()
        with self.lock:
            s = self.states[entity_id]
            s["state"] = state
            if attributes:
                s["attributes"].update(attributes)
            s["last_changed"] = s["last_updated"] = now
            diff = {"+": {"s": state, "lc": now, "c": context or D.context(), "lu": now}}
            if attributes:
                diff["+"]["a"] = attributes
            conns = list(self.conns)
        for c in conns:
            c.entities_changed({entity_id: diff})


def compressed(state):
    """An entity as subscribe_entities sends it (home-assistant-js-websocket's compressed form)."""
    return {"s": state["state"], "a": state["attributes"], "c": state.get("context_id", D.context()),
            "lc": state["last_changed"], "lu": state["last_updated"]}


class Conn:
    """One frontend connection."""

    def __init__(self, hub, sock):
        self.hub, self.sock, self.send_lock = hub, sock, threading.Lock()
        self.entity_subs = []
        self.webrtc = {}            # camera/webrtc/offer subscription id -> its session

    def send(self, msg):
        data = ws_frame(json.dumps(msg, separators=(",", ":")).encode())
        with self.send_lock:
            self.sock.sendall(data)

    def result(self, mid, result=None, success=True, error=None):
        msg = {"id": mid, "type": "result", "success": success}
        msg["result" if success else "error"] = result if success else error
        self.send(msg)

    def event(self, mid, ev):
        self.send({"id": mid, "type": "event", "event": ev})

    def entities_changed(self, changes):
        for mid in list(self.entity_subs):
            try:
                self.event(mid, {"c": changes})
            except OSError:
                pass

    def run(self):
        self.send({"type": "auth_required", "ha_version": HA_VERSION})
        op, data = ws_read(self.sock)
        msg = json.loads(data)
        if msg.get("type") != "auth":
            return
        self.send({"type": "auth_ok", "ha_version": HA_VERSION})
        self.hub.conns.add(self)
        try:
            while True:
                op, data = ws_read(self.sock)
                if op == 8:
                    return
                if op == 9:
                    with self.send_lock:
                        self.sock.sendall(ws_frame(data, 10))
                    continue
                if op != 1:
                    continue
                msgs = json.loads(data)
                for m in msgs if isinstance(msgs, list) else [msgs]:
                    self.handle(m)
        finally:
            self.hub.conns.discard(self)
            for sid in self.webrtc.values():
                self.hub.webrtc and self.hub.webrtc.close(sid)

    def handle(self, m):
        mid, t = m.get("id"), m.get("type")
        self.hub.record({"t": time.time(), "type": t, "keys": sorted(m), "msg": m})
        h = HANDLERS.get(t)
        if h is None:
            self.hub.unknown.add(t)
            self.hub.record({"t": time.time(), "unknown": t, "msg": m})
            self.result(mid, None, success=False, error={"code": "unknown_command", "message": "Unknown command."})
            return
        try:
            h(self, m)
        except Exception as e:   # answer, never drop the connection: the frontend would reconnect forever
            self.hub.record({"t": time.time(), "failed": t, "error": repr(e)})
            self.result(mid, None, success=False, error={"code": "home_assistant_error", "message": repr(e)})


def _sub_events(c, m):
    c.result(m["id"])           # no events of these kinds happen in the fixed home


def _sub_entities(c, m):
    c.result(m["id"])
    with c.hub.lock:
        everything = {e: compressed(s) for e, s in c.hub.states.items()}
    c.event(m["id"], {"a": everything})
    c.entity_subs.append(m["id"])


def _unsub(c, m):
    sub = m.get("subscription")
    if sub in c.entity_subs:
        c.entity_subs.remove(sub)
    sid = c.webrtc.pop(sub, None)
    if sid and c.hub.webrtc:
        c.hub.webrtc.close(sid)             # HA closes the camera's WebRTC session with its subscription
    c.result(m["id"])


def _call_service(c, m):
    """The tile's toggle: flip a light or switch as HA would, then answer."""
    target = (m.get("service_data") or {}).get("entity_id") or (m.get("target") or {}).get("entity_id")
    for e in ([target] if isinstance(target, str) else target or []):
        s = c.hub.states.get(e)
        if s and m.get("service") in ("toggle", "turn_on", "turn_off"):
            on = {"toggle": s["state"] != "on", "turn_on": True, "turn_off": False}[m["service"]]
            c.hub.set_state(e, "on" if on else "off")
    c.result(m["id"], {"context": {"id": D.context(), "parent_id": None, "user_id": D.USER["id"]}})


def _history_stream(c, m):
    c.result(m["id"])
    ids = m.get("entity_ids") or []
    c.event(m["id"], {"states": D.history(ids, m.get("start_time"), m.get("end_time")),
                      "start_time": m.get("start_time"), "end_time": m.get("end_time")})


def _camera_stream(c, m):
    """camera/stream: a fresh HLS stream for the camera (homeassistant/components/camera, the stream
    integration's URL shape). Each request is its own stream, started as if it had been running
    preRollS already (a camera that has been on), so a view's first frame doesn't wait on segments."""
    cam = m.get("entity_id")
    if cam not in D.CAMERA_PROFILES:
        return c.result(m["id"], None, success=False, error={"code": "not_found", "message": "Camera not found"})
    token = c.hub.new_stream(cam)
    c.result(m["id"], {"url": f"/api/hls/{token}/master_playlist.m3u8"})


def _camera_capabilities(c, m):
    types = D.CAMERA_STREAM_TYPES.get(m.get("entity_id"))
    if types is None:
        return c.result(m["id"], None, success=False, error={"code": "not_found", "message": "Camera not found"})
    c.result(m["id"], {"frontend_stream_types": types, "two_way_audio": False})


def _webrtc(error_code):
    """HA's require_webrtc_support (homeassistant/components/camera/webrtc.py): a camera that doesn't
    offer web_rtc gets error_code."""
    def wrap(f):
        def handler(c, m):
            types = D.CAMERA_STREAM_TYPES.get(m.get("entity_id"))
            if types is None:
                return c.result(m["id"], None, success=False, error={"code": "not_found", "message": "Camera not found"})
            if "web_rtc" not in types:
                return c.result(m["id"], None, success=False, error={
                    "code": error_code, "message": f"Camera does not support WebRTC, frontend_stream_types={set(types)}"})
            f(c, m)
        return handler
    return wrap


@_webrtc("webrtc_get_client_config_failed")
def _webrtc_client_config(c, m):
    c.result(m["id"], webrtc.client_config(c.hub.turn_port))


@_webrtc("webrtc_offer_failed")
def _webrtc_offer(c, m):
    """camera/webrtc/offer, as HA answers it with go2rtc: a subscription whose result comes first,
    then the session's id, then the answer (or an error) from the peer."""
    mid, cam = m["id"], m["entity_id"]
    c.result(mid)
    sid = c.hub.new_webrtc_session()
    c.webrtc[mid] = sid
    c.event(mid, {"type": "session", "session_id": sid})
    c.hub.record({"t": time.time(), "webrtc_offer": cam, "session": sid})

    def send(ev):
        if c.webrtc.get(mid) == sid:        # not after the frontend has unsubscribed
            try:
                c.event(mid, ev)
            except OSError:
                pass
    if c.hub.webrtc is None:
        c.hub.record({"t": time.time(), "webrtc_error": cam, "session": sid, "error": "no aiortc"})
        return send({"type": "error", "code": "webrtc_offer_failed",
                     "message": "the fake's WebRTC peer needs aiortc (tools/perf/webrtc-requirements.txt)"})
    c.hub.webrtc.offer(sid, cam, D.CAMERA_PROFILES[cam], m["offer"], send)


@_webrtc("webrtc_candidate_failed")
def _webrtc_candidate(c, m):
    if c.hub.webrtc and m.get("session_id") in c.webrtc.values():
        c.hub.webrtc.candidate(m["session_id"], m.get("candidate"))
    c.result(m["id"])


def _const(value):
    return lambda c, m: c.result(m["id"], value() if callable(value) else value)


HANDLERS = {
    "supported_features": _const(None),
    "subscribe_events": _sub_events,
    "subscribe_entities": _sub_entities,
    "unsubscribe_events": _unsub,
    "get_config": _const(D.config),
    "get_services": _const(D.services),
    "get_panels": _const(D.panels),
    "get_states": lambda c, m: c.result(m["id"], list(D.state_objects(c.hub.states))),
    "auth/current_user": _const(D.USER),
    "frontend/get_themes": _const({"themes": {}, "default_theme": "default", "default_dark_theme": None}),
    "frontend/subscribe_user_data": lambda c, m: (c.result(m["id"]), c.event(m["id"], {"value": None})),
    "frontend/subscribe_system_data": lambda c, m: (c.result(m["id"]), c.event(m["id"], {"value": None})),
    "frontend/get_user_data": _const({"value": None}),
    "frontend/get_translations": _const({"resources": {}}),
    "lovelace/config": lambda c, m: c.result(m["id"], D.lovelace(m.get("url_path"))),
    "lovelace/resources": _const([]),
    "lovelace/dashboards/list": _const(D.dashboards_list),
    "config/entity_registry/list_for_display": _const(lambda: {"entity_categories": {"0": "config", "1": "diagnostic"},
                                                                "entities": []}),
    "config/device_registry/list": _const([]),
    "config/area_registry/list": _const([]),
    "config/floor_registry/list": _const([]),
    "config/label_registry/list": _const([]),
    "call_service": _call_service,
    "history/stream": _history_stream,
    "recorder/statistics_during_period": lambda c, m: c.result(m["id"], D.statistics(
        m.get("statistic_ids"), m.get("start_time"), m.get("end_time"), m.get("period"))),
    "ping": lambda c, m: c.send({"id": m["id"], "type": "pong"}),
    # Cameras (src/data/camera.ts at the pinned tag): WebRTC and HLS for go2rtc's, HLS for the rest.
    "camera/capabilities": _camera_capabilities,
    "camera/webrtc/get_client_config": _webrtc_client_config,
    "camera/webrtc/offer": _webrtc_offer,
    "camera/webrtc/candidate": _webrtc_candidate,
    "camera/stream": _camera_stream,
    "camera/get_prefs": _const({"preload_stream": True, "orientation": 1}),
    "auth/sign_path": lambda c, m: c.result(m["id"], {"path": f"{m.get('path', '')}{'&' if '?' in m.get('path', '') else '?'}authSig=clarity"}),
    # Read from the frontend's source at the pinned tag (src/data/*.ts): what each one answers.
    "repairs/list_issues": _const({"issues": []}),
    "recorder/info": _const({"backlog": 0, "db_in_default_location": True, "max_backlog": 40000,
                             "migration_in_progress": False, "migration_is_live": False, "recording": True,
                             "thread_running": True}),
    "brands/access_token": _const({"token": "clarity"}),
    "lovelace/info": _const({"resource_mode": "storage"}),
    "frontend/get_icons": lambda c, m: c.result(m["id"], {"resources": D.icons(m.get("category"), m.get("integration"))}),
    "persistent_notification/subscribe": lambda c, m: (c.result(m["id"]),
                                                       c.event(m["id"], {"type": "current", "notifications": {}})),
    "labs/subscribe": lambda c, m: (c.result(m["id"]), c.event(m["id"], {
        "preview_feature": m.get("preview_feature"), "domain": m.get("domain"), "enabled": False, "is_built_in": True,
        "feedback_url": None, "learn_more_url": None, "report_issue_url": None})),
}


# ---- HTTP

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    hub = None
    front = None
    index = None

    def log_message(self, *a):
        pass

    def send_bytes(self, code, body, ctype, cache="no-store", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        path = url.path
        if path == "/api/websocket":
            return self.websocket()
        if path == "/auth/authorize":
            # HA's sign-in, without its login page: straight back to the frontend with a code, and
            # storeToken=true as a ticked "keep me signed in" sends.
            q = urllib.parse.parse_qs(url.query)
            back = q["redirect_uri"][0]
            Handler.hub.record({"t": time.time(), "http": "/auth/authorize", "query": url.query})
            sep = "&" if "?" in back else "?"
            loc = f"{back}{sep}code=clarity&state={urllib.parse.quote(q.get('state', [''])[0])}&storeToken=true"
            self.send_response(302)
            self.send_header("Location", loc)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path == "/manifest.json":
            return self.send_bytes(200, json.dumps(D.MANIFEST).encode(), "application/manifest+json")
        if path in ("/clarity/live", "/clarity/still"):
            # The harness asks for the live updates while it measures them, and stops them when done.
            out = Handler.hub.stream(path.endswith("live"))
            if path.endswith("still"):
                Handler.hub.done_view()
                Handler.hub.reset()
            return self.send_bytes(200, json.dumps(out).encode(), "application/json")
        if path == "/clarity/time":
            # The fake's monotonic clock, for the harness to tie its own clock to (round trips).
            return self.send_bytes(200, json.dumps({"ms": round(time.monotonic() * 1000, 3)}).encode(), "application/json")
        if path == "/clarity/open":
            # The runner (perfrun.py) opens the next reference view: the harness, already running in
            # the loaded frontend, picks it up from /clarity/next and navigates there.
            q = {k: v[0] for k, v in urllib.parse.parse_qs(url.query).items()}
            ok = (q.get("view") in D.VIEWS and re.fullmatch(r"[0-9a-f]{4,32}", q.get("nonce", ""))
                  and all(re.fullmatch(r"[0-9.]{1,12}", q.get(k, "0")) for k in ("run", "taps", "swipes", "vsync", "warmup")))
            if not ok:
                return self.send_bytes(400, b"bad command", "text/plain")
            Handler.hub.reset()          # a no-op unless something changed after the last view
            Handler.hub.open_view(q)
            return self.send_bytes(204, b"", "text/plain")
        if path.startswith("/api/hls/"):
            return self.hls(path)
        if path.startswith("/api/camera_proxy/"):
            # The camera's still (the card's poster): a generated picture.
            return self.send_bytes(200, D.asset("picture-0.png")[1], "image/png")
        if path == "/clarity/cancel":
            # The runner, before each restart: a view that was cut short leaves no command, no stream
            # and no changed state behind, as /clarity/still would have.
            Handler.hub.done_view()
            Handler.hub.stream(False)
            Handler.hub.reset()
            return self.send_bytes(204, b"", "text/plain")
        if path == "/clarity/next":
            after = urllib.parse.parse_qs(url.query).get("after", [""])[0]
            cmd = Handler.hub.next_cmd(after, 20)
            try:
                if cmd is None:
                    return self.send_bytes(204, b"", "text/plain")
                return self.send_bytes(200, json.dumps(cmd).encode(), "application/json")
            except (BrokenPipeError, ConnectionResetError):
                return None                  # that page is gone; the command stays for the live one
        if path.startswith("/clarity/page/"):
            # The animation test page (detail), from tools/perf/page.
            base = os.path.realpath(os.path.join(HERE, "page"))
            full = os.path.realpath(os.path.join(base, path[len("/clarity/page/"):]))
            if not full.startswith(base + os.sep) or not os.path.isfile(full):
                return self.send_bytes(404, b"", "text/plain")
            with open(full, "rb") as f:
                body = f.read()
            ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
            return self.send_bytes(200, body, "application/javascript" if full.endswith(".js") else ctype)
        if path.startswith("/clarity/"):
            name = path[len("/clarity/"):]
            if name.startswith("harness"):
                # Only the current harness, at its hashed URL; the old fixed URL is gone.
                name = "harness.js" if path == Handler.harness else ""
            body = D.asset(name)
            if body is None:
                return self.send_bytes(404, b"", "text/plain")
            # The pictures are cached as a dashboard's images are; the harness never is, so a changed
            # test never meets an old copy on the panel.
            cache = "public, max-age=31536000" if path.endswith(".png") else "no-store"
            return self.send_bytes(200, body[1], body[0], cache=cache)
        if path.startswith(("/frontend_latest/", "/frontend_es5/", "/static/")) or path in ("/service_worker.js", "/sw-modern.js", "/sw-legacy.js"):
            return self.static(path)
        # Every other path is a frontend route (a dashboard, a view): the app shell, as HA serves it.
        return self.send_bytes(200, Handler.index, "text/html; charset=utf-8")

    def hls(self, path):
        """The stream integration's HLS, live: a master playlist, then a sliding window of the last
        windowSegments whole segments of a loop made by camera.py (EXT-X-DISCONTINUITY where it wraps)."""
        m = re.fullmatch(r"/api/hls/(clarity\d{6})/(master_playlist\.m3u8|playlist\.m3u8|init\.mp4|segment/(\d{1,7})\.m4s)", path)
        st = m and getattr(Handler.hub, "cam_streams", {}).get(m.group(1))
        if not st:
            return self.send_bytes(404, b"", "text/plain")
        cfg, prof = D.TIERS["CAMERA"], camera.PROFILES[st["profile"]]
        loop = camera.segments(st["profile"])
        name = m.group(2)
        if name == "master_playlist.m3u8":
            w, h = prof["size"].split("x")
            body = (f"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH={int(prof['bitrate'][:-1]) * 1000},CODECS=\"avc1.4d401f\","
                    f"RESOLUTION={w}x{h},FRAME-RATE={cfg['fps']}\nplaylist.m3u8\n")
            return self.send_bytes(200, body.encode(), "application/vnd.apple.mpegurl")
        if name == "playlist.m3u8":
            done = int((time.monotonic() - st["t0"]) // cfg["segmentS"])          # whole segments so far
            first = max(0, done - cfg["windowSegments"])
            lines = ["#EXTM3U", "#EXT-X-VERSION:7", f"#EXT-X-TARGETDURATION:{cfg['segmentS']}",
                     f"#EXT-X-MEDIA-SEQUENCE:{first}", f"#EXT-X-DISCONTINUITY-SEQUENCE:{first // loop}",
                     '#EXT-X-MAP:URI="init.mp4"']
            for n in range(first, done):
                if n % loop == 0 and n > first:
                    lines += ["#EXT-X-DISCONTINUITY", '#EXT-X-MAP:URI="init.mp4"']
                lines += [f"#EXTINF:{cfg['segmentS']:.6f},", f"segment/{n}.m4s"]
            return self.send_bytes(200, ("\n".join(lines) + "\n").encode(), "application/vnd.apple.mpegurl")
        try:
            d = os.path.join(camera.root(), st["profile"])
        except camera.Unavailable as e:
            # No ffmpeg (only the fake runs; run.sh checks first): say why, don't end the request thread.
            Handler.hub.record({"t": time.time(), "camera_error": str(e)})
            return self.send_bytes(500, str(e).encode(), "text/plain")
        f = "init.mp4" if name == "init.mp4" else f"seg{int(m.group(3)) % loop:03d}.m4s"
        if name != "init.mp4" and int(m.group(3)) >= int((time.monotonic() - st["t0"]) // cfg["segmentS"]):
            return self.send_bytes(404, b"", "text/plain")                        # not made yet
        with open(os.path.join(d, f), "rb") as fh:
            return self.send_bytes(200, fh.read(), "video/mp4" if f.endswith(".mp4") else "video/iso.segment")

    def static(self, path):
        full = os.path.normpath(os.path.join(Handler.front, path.lstrip("/")))
        if not full.startswith(Handler.front) or not os.path.isfile(full):
            return self.send_bytes(404, b"", "text/plain")
        with open(full, "rb") as f:
            body = f.read()
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if ctype == "text/javascript" or full.endswith(".js"):
            ctype = "application/javascript"
        # HA caches its hashed build files for a year (homeassistant/components/frontend), so a
        # panel loads them from its cache after the first time, as in real use.
        cache = "public, max-age=31536000" if path.startswith(("/frontend_", "/static/")) else "no-cache"
        return self.send_bytes(200, body, ctype, cache=cache)

    def do_POST(self):
        url = urllib.parse.urlsplit(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        body = urllib.parse.parse_qs(self.rfile.read(n).decode()) if n else {}
        if url.path == "/auth/token":
            tok = {"access_token": "clarity-access", "token_type": "Bearer", "expires_in": 86400 * 365}
            if (body.get("grant_type") or [""])[0] == "authorization_code":
                tok["refresh_token"] = "clarity-refresh"
            return self.send_bytes(200, json.dumps(tok).encode(), "application/json")
        if url.path == "/auth/revoke":
            return self.send_bytes(200, b"", "text/plain")
        return self.send_bytes(404, b"", "text/plain")

    def websocket(self):
        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            return self.send_bytes(400, b"", "text/plain")
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", ws_accept(key))
        self.end_headers()
        self.wfile.flush()
        sock = self.connection
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            Conn(Handler.hub, sock).run()
        except (ConnectionError, OSError, ValueError):
            pass
        self.close_connection = True


def render_index(front, harness_url):
    """HA core's index.html, rendered as HA renders it (homeassistant/components/frontend), with one
    addition: Clarity's harness as the first script, so it sees the websocket from the start."""
    with open(os.path.join(front, "index.html")) as f:
        html = f.read()
    html = html.replace("{{ theme_color }}", "#03a9f4")
    html = re.sub(r"{%-? for extra_module in extra_modules -?%}.*?{%-? endfor -?%}", "", html, flags=re.S)
    html = re.sub(r"{%-? for extra_script in extra_js_es5 -?%}.*?{%-? endfor -?%}", "", html, flags=re.S)
    if harness_url:
        html = html.replace("<head>", f'<head><script src="{harness_url}"></script>', 1)
    if "{{" in html or "{%" in html:
        raise SystemExit("index.html has template tags fakeha.py doesn't know; the frontend changed")
    return html.encode()


def harness_url():
    """The harness's URL names a hash of its content. A panel that cached an older harness (before
    2026-11-13 it was served at a fixed URL with a year's max-age) never asks for that URL again, so
    it can't run an old harness with a new runner."""
    return f"/clarity/harness-{hashlib.sha256(D.asset('harness.js')[1]).hexdigest()[:16]}.js"


def serve(port, log_path=None, harness=True, turn_port=None):
    """turn_port: where the WebRTC relay listens (0: any free port; None: port + 1). The panel reaches
    it through adb reverse, as it reaches `port`."""
    front = hafrontend.root()
    Handler.front = os.path.realpath(front)
    Handler.harness = harness_url()
    Handler.index = render_index(front, Handler.harness if harness else None)
    Handler.hub = hub = Hub(open(log_path, "a") if log_path else None)
    if webrtc.available():
        hub.webrtc = webrtc.WebRTC((port + 1 if port else 0) if turn_port is None else turn_port, camera.annexb,
                                   D.TIERS["CAMERA"]["fps"], record=hub.record)
        hub.turn_port = hub.webrtc.turn_port
    # The frontend fetches dozens of modules at once; the default backlog (5) resets connections.
    ThreadingHTTPServer.request_queue_size = 256
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    srv.daemon_threads = True
    return srv


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8089)
    ap.add_argument("--log", help="append every websocket command to this file (JSON lines)")
    ap.add_argument("--no-harness", action="store_true", help="serve HA's index.html without Clarity's harness")
    ap.add_argument("--turn-port", type=int, help="the WebRTC relay's port (default: --port + 1)")
    a = ap.parse_args()
    srv = serve(a.port, a.log, not a.no_harness, a.turn_port)
    turn = (f"WebRTC relay on 127.0.0.1:{Handler.hub.turn_port}" if Handler.hub.webrtc
            else "no WebRTC (aiortc isn't here: tools/perf/webrtc-requirements.txt)")
    print(f"fake Home Assistant {HA_VERSION}, frontend {hafrontend.VERSION}, on http://127.0.0.1:{a.port}; {turn}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
