"""python3 -m unittest discover -s tools/perf/test

The fake Home Assistant (fakeha.py) against the traffic HA's real frontend sent it
(test/fakeha-traffic.json): every recorded command gets an answer, and the answers the frontend
depends on have the shape it reads. Runs on a stub frontend: no download."""
import base64
import http.client
import json
import os
import socket
import struct
import sys
import threading
import time
import unittest
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
os.environ.setdefault("GW_PERF_FRONTEND", os.path.join(HERE, "stub_frontend"))
sys.path.insert(0, os.path.dirname(HERE))
import fakeha  # noqa: E402


class WS:
    """A minimal websocket client: text frames, masked as a browser sends them."""

    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET /api/websocket HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
                           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            head += self.sock.recv(1)
        assert b" 101 " in head.split(b"\r\n")[0], head
        assert fakeha.ws_accept(key).encode() in head
        self.next_id = 1
        self.held = []          # messages read while waiting for another one

    def send(self, obj):
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        n = len(data)
        head = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n))
        self.sock.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def recv(self, timeout=10):
        if self.held:
            return self.held.pop(0)
        self.sock.settimeout(timeout)
        op, data = fakeha.ws_read(self.sock)
        return json.loads(data)

    def call(self, msg):
        """Send a command; return its result and any events that came with it."""
        mid = self.next_id
        self.next_id += 1
        self.send(dict(msg, id=mid))
        result, events, others = None, [], []
        deadline = time.time() + 5
        try:
            while time.time() < deadline:
                # After the result, a subscription's first event (if it sends one) comes at once.
                m = self.recv(0.5 if result is not None else 5)
                if m.get("id") != mid:
                    others.append(m)
                    continue
                if m["type"] == "pong":
                    return m, events
                if m["type"] == "result":
                    result = m
                    if not m.get("success") or not msg["type"].startswith(
                            ("subscribe", "frontend/subscribe", "persistent_notification/subscribe", "labs/subscribe", "history/stream")):
                        return result, events
                elif m["type"] == "event":
                    events.append(m)
                    return result, events
        except (socket.timeout, TimeoutError):
            pass
        finally:
            self.held = others + self.held
        return result, events


class FakeHA(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = fakeha.serve(0)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        with open(os.path.join(HERE, "fakeha-traffic.json")) as f:
            cls.traffic = json.load(f)

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def http(self, method, path, body=None, headers=None):
        """One request on its own connection: (status, Location, body)."""
        conn = http.client.HTTPConnection("127.0.0.1", self.port)
        try:
            conn.request(method, path, body, headers or {})
            r = conn.getresponse()
            return r.status, r.getheader("Location"), r.read()
        finally:
            conn.close()

    def connect(self):
        ws = WS(self.port)
        self.addCleanup(ws.sock.close)
        self.assertEqual(ws.recv()["type"], "auth_required")
        ws.send({"type": "auth", "access_token": "clarity-access"})
        self.assertEqual(ws.recv(), {"type": "auth_ok", "ha_version": fakeha.HA_VERSION})
        return ws

    def test_every_command_the_frontend_sent_is_answered(self):
        ws = self.connect()
        for msg in self.traffic["messages"]:
            with self.subTest(msg["type"]):
                result, _ = ws.call(msg)
                self.assertIsNotNone(result, msg)
                self.assertNotEqual((result.get("error") or {}).get("code"), "unknown_command", msg)
                if result["type"] == "result":
                    self.assertTrue(result["success"], (msg, result))

    def test_the_answers_the_frontend_reads(self):
        ws = self.connect()
        panels, _ = ws.call({"type": "get_panels"})
        # The router falls back to "home" (this release's default panel) before the panels arrive.
        self.assertIn("home", panels["result"])
        self.assertTrue({"clarity-light", "clarity-typical", "clarity-heavy"} <= set(panels["result"]))
        cfg, _ = ws.call({"type": "lovelace/config", "url_path": "clarity-heavy", "force": False})
        self.assertEqual(len(cfg["result"]["views"][0]["cards"]), 120)
        res, ev = ws.call({"type": "subscribe_entities"})
        self.assertIn("light.living_room", ev[0]["event"]["a"])
        self.assertEqual(set(ev[0]["event"]["a"]["sensor.clarity_0"]), {"s", "a", "c", "lc", "lu"})

    def test_the_toggle_flips_the_light(self):
        ws = self.connect()
        _, ev = ws.call({"type": "subscribe_entities"})
        before = ev[0]["event"]["a"]["light.living_room"]["s"]
        ws.call({"type": "call_service", "domain": "light", "service": "toggle", "service_data": {"entity_id": "light.living_room"}})
        deadline = time.time() + 3
        while time.time() < deadline:
            m = ws.recv()
            if m.get("type") == "event" and "c" in m["event"] and "light.living_room" in m["event"]["c"]:
                self.assertNotEqual(m["event"]["c"]["light.living_room"]["+"]["s"], before)
                return
        self.fail("no state change after the toggle")

    def test_the_live_stream_reaches_subscribers_with_a_context_each(self):
        ws = self.connect()
        ws.call({"type": "subscribe_entities"})
        self.assertEqual(self.http("GET", "/clarity/live?nonce=t")[0], 204)
        got, deadline = [], time.time() + 3
        while time.time() < deadline and len(got) < 4:
            m = ws.recv()
            if m.get("type") == "event" and "c" in m["event"]:
                got.extend((e, d["+"]["c"]) for e, d in m["event"]["c"].items())
        self.http("GET", "/clarity/still?nonce=t")
        self.assertGreaterEqual(len(got), 4)
        self.assertEqual(len({c for _, c in got}), len(got))          # every change its own context

    def test_sign_in_goes_straight_back_with_a_code(self):
        back = f"http://127.0.0.1:{self.port}/clarity-light/0?auth_callback=1"
        status, location, _ = self.http("GET", "/auth/authorize?" + urllib.parse.urlencode(
            {"response_type": "code", "redirect_uri": back, "client_id": "x", "state": "s"}))
        self.assertEqual(status, 302)
        self.assertTrue(location.startswith(back + "&code=clarity&state=s&storeToken=true"))
        tok = json.loads(self.http("POST", "/auth/token", "grant_type=authorization_code&code=clarity",
                                   {"Content-Type": "application/x-www-form-urlencoded"})[2])
        self.assertEqual(tok["token_type"], "Bearer")
        self.assertIn("refresh_token", tok)

    def test_the_app_shell_puts_the_harness_first(self):
        html = self.http("GET", "/clarity-typical/0?nonce=x")[2].decode()
        self.assertTrue(html.split("<head>", 1)[1].startswith('<script src="/clarity/harness.js"></script>'))
        self.assertNotIn("{{", html)
        js = self.http("GET", "/clarity/harness.js")[2].decode()
        self.assertIn("window.GW_CLARITY = ", js)
        self.assertIn('"PROTOCOL": 4', js)

    def test_paths_never_leave_the_frontend(self):
        for path in ("/static/../../etc/passwd", "/frontend_latest/%2e%2e/%2e%2e/x", "/clarity/page/../fakeha.py"):
            self.assertEqual(self.http("GET", path)[0], 404, path)


if __name__ == "__main__":
    unittest.main()
