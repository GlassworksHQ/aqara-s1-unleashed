"""python3 -m unittest discover -s tools/perf/test

The showcase home (tools/showcase/home.py) served by the fake Home Assistant, as render.mjs serves it
for the device renders: its own dashboard and made-up states, HA's icons and state names for them, no
clock or date, and every command the frontend sends still answered. Runs on a stub frontend."""
import json
import os
import sys
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
os.environ.setdefault("GW_PERF_FRONTEND", os.path.join(HERE, "stub_frontend"))
sys.path[:0] = [HERE, os.path.dirname(HERE)]
sys.path.insert(0, os.path.join(HERE, "..", "..", "showcase"))
import fakeha  # noqa: E402
import home  # noqa: E402
from test_fakeha import WS  # noqa: E402


class Showcase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = fakeha.serve(0, harness=False, home=home)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        with open(os.path.join(HERE, "fakeha-traffic.json")) as f:
            cls.traffic = json.load(f)

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def connect(self):
        ws = WS(self.srv.server_address[1])
        self.addCleanup(ws.sock.close)
        self.assertEqual(ws.recv()["type"], "auth_required")
        ws.send({"type": "auth", "access_token": "clarity-access"})
        self.assertEqual(ws.recv()["type"], "auth_ok")
        return ws

    def result(self, ws, msg):
        res, _ = ws.call(msg)
        self.assertTrue(res["success"], (msg, res))
        return res["result"]

    def test_its_own_dashboard_and_states(self):
        ws = self.connect()
        panels = self.result(ws, {"type": "get_panels"})
        self.assertIn("showcase", panels)
        self.assertNotIn("clarity-ref", panels)
        view = self.result(ws, {"type": "lovelace/config", "url_path": "showcase", "force": False})["views"][0]
        cards = [c for s in view["sections"] for c in s["cards"]]
        states = {s["entity_id"] for s in self.result(ws, {"type": "get_states"})}
        self.assertEqual(states, set(home.ENTITIES))
        self.assertEqual({c["entity"] for c in cards if c["type"] == "tile"} - states, set(), "tiles name only showcase entities")

    def test_no_clock_or_date_to_go_stale(self):
        ws = self.connect()
        view = self.result(ws, {"type": "lovelace/config", "url_path": "showcase", "force": False})["views"][0]
        self.assertEqual({c["type"] for s in view["sections"] for c in s["cards"]}, {"heading", "tile"})
        self.assertFalse([e for e in home.ENTITIES if e.split(".")[0] in ("calendar", "weather", "sun") or "date" in e or "time" in e])

    def test_has_icons_and_state_names(self):
        ws = self.connect()
        icons = self.result(ws, {"type": "frontend/get_icons", "category": "entity_component"})["resources"]
        self.assertEqual(icons["lock"]["_"]["default"], "mdi:lock")
        text = self.result(ws, {"type": "frontend/get_translations", "language": "en", "category": "entity_component"})["resources"]
        self.assertEqual(text["component.binary_sensor.entity_component.door.state.off"], "Closed")

    def test_every_command_the_frontend_sent_is_answered(self):
        ws = self.connect()
        for msg in self.traffic["messages"]:
            with self.subTest(msg["type"]):
                res, _ = ws.call(msg)
                self.assertIsNotNone(res, msg)
                self.assertNotEqual((res.get("error") or {}).get("code"), "unknown_command", msg)

    def test_a_later_server_without_a_home_is_clarity_again(self):
        srv = fakeha.serve(0)
        srv.server_close()
        self.assertIs(fakeha.D, fakeha.CLARITY)
        fakeha.serve(0, harness=False, home=home).server_close()  # back for the other tests here


if __name__ == "__main__":
    unittest.main()
