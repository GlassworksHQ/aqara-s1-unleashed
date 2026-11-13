"""python3 -m unittest discover -s tools/perf/test

The showcase home (tools/showcase/home.py) served by the fake Home Assistant, as render.mjs serves it
for the device renders: its own dashboards (both variants) and made-up states, the forecast, the
energy statistics, the camera still and the album art, HA's icons and state names, nothing that shows
a date, the same data and pictures every time, and every command the frontend sends still answered.
Runs on a stub frontend."""
import datetime
import http.client
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

    def view(self, ws, variant):
        home.VARIANT = variant
        self.addCleanup(setattr, home, "VARIANT", "rich")
        return self.result(ws, {"type": "lovelace/config", "url_path": "showcase", "force": False})["views"][0]

    @staticmethod
    def cards(view):
        return [c for s in view["sections"] for c in s["cards"]]

    @staticmethod
    def entities(view):
        out = {b["entity"] for b in view.get("badges", [])}
        for c in Showcase.cards(view):
            out |= ({c["entity"]} if "entity" in c else set()) | set(c.get("entities", []))
            out |= {b["entity"] for b in c.get("badges", [])}
        return out

    def test_its_own_dashboards_and_states(self):
        ws = self.connect()
        panels = self.result(ws, {"type": "get_panels"})
        self.assertIn("showcase", panels)
        self.assertNotIn("clarity-ref", panels)
        states = {s["entity_id"] for s in self.result(ws, {"type": "get_states"})}
        self.assertEqual(states, set(home.ENTITIES))
        for variant in home.VARIANTS:
            with self.subTest(variant):
                view = self.view(ws, variant)
                self.assertEqual(self.entities(view) - states, set(), "cards name only showcase entities")
                self.assertEqual(len(self.cards(view)), home.cards(), "what render.mjs waits for")

    def test_the_rich_one_has_graphs_a_camera_and_music(self):
        types = {c["type"] for c in self.cards(self.view(self.connect(), "rich"))}
        self.assertLessEqual({"clock", "weather-forecast", "history-graph", "statistics-graph", "thermostat",
                              "picture-entity", "media-control", "tile", "heading"}, types)

    def test_nothing_shows_a_date(self):
        ws = self.connect()
        evening = datetime.datetime.fromtimestamp(home.NOW, datetime.timezone.utc).hour
        for variant in home.VARIANTS:
            for c in self.cards(self.view(ws, variant)):
                with self.subTest(variant=variant, card=c["type"]):
                    if c["type"] == "history-graph":
                        # its axis names the day only when the window crosses midnight
                        self.assertLessEqual(c["hours_to_show"], evening)
                    if c["type"] == "clock":
                        self.assertNotIn("date", json.dumps(c))

    def test_the_forecast_and_the_energy_week(self):
        ws = self.connect()
        res, _ = ws.call({"type": "weather/subscribe_forecast", "entity_id": "weather.home", "forecast_type": "daily"})
        self.assertTrue(res["success"])
        ev = ws.recv()
        self.assertEqual((ev["id"], len(ev["event"]["forecast"])), (res["id"], 5))
        meta = self.result(ws, {"type": "recorder/get_statistics_metadata", "statistic_ids": ["sensor.energy_today"]})
        self.assertEqual(meta[0]["unit_of_measurement"], "kWh")
        week = home.statistics(["sensor.energy_today"], None, None, "day")["sensor.energy_today"]
        self.assertEqual([p["change"] > 0 for p in week], [True] * 7)

    def test_the_camera_still_and_the_album_art(self):
        port = self.srv.server_address[1]
        for path in ("/api/camera_proxy/camera.front_garden?token=showcase", "/clarity/showcase-art.png"):
            with self.subTest(path):
                conn = http.client.HTTPConnection("127.0.0.1", port)
                conn.request("GET", path)
                r = conn.getresponse()
                body = r.read()
                conn.close()
                self.assertEqual((r.status, r.getheader("Content-Type"), body[:8]), (200, "image/png", b"\x89PNG\r\n\x1a\n"))
                self.assertNotEqual(body, fakeha.CLARITY.asset("picture-0.png")[1], "the showcase's own picture")

    def test_the_same_data_and_pictures_every_time(self):
        ids = ["sensor.home_power", "sensor.outdoor_temperature"]
        first = home.history(ids, None, None)
        self.assertEqual(first, home.history(ids, None, None))
        self.assertGreater(len(first["sensor.home_power"]), 100)
        self.assertEqual(home._png(32, 18, home._garden), home._png(32, 18, home._garden))

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
        fakeha.serve(0).server_close()
        self.assertIs(fakeha.D, fakeha.CLARITY)
        fakeha.serve(0, harness=False, home=home).server_close()  # back for the other tests here


if __name__ == "__main__":
    unittest.main()
