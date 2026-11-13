"""python3 -m unittest discover -s tools/perf/test

The showcase home (tools/showcase/home.py) served by the fake Home Assistant, as render.mjs serves it
for the device renders: its own dashboard (two views, the same sections, each the same height) and
made-up states, the forecast, the energy statistics, the shopping list, the camera stills and the album
art, HA's icons and state names, nothing that shows a date, the same data and pictures every time, and
every command the frontend sends still answered.
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

    def views(self, ws):
        return self.result(ws, {"type": "lovelace/config", "url_path": "showcase", "force": False})["views"]

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

    def test_its_own_dashboard_and_states(self):
        ws = self.connect()
        panels = self.result(ws, {"type": "get_panels"})
        self.assertIn("showcase", panels)
        self.assertNotIn("clarity-ref", panels)
        states = {s["entity_id"] for s in self.result(ws, {"type": "get_states"})}
        self.assertEqual(states, set(home.ENTITIES))
        for view in self.views(ws):
            self.assertEqual(self.entities(view) - states, set(), "cards name only showcase entities")
            self.assertEqual(len(self.cards(view)), home.cards(), "what render.mjs waits for")

    def test_landscape_and_portrait_show_the_same_sections(self):
        home_view, portrait = self.views(self.connect())
        self.assertEqual((home_view["path"], home_view["max_columns"], portrait["path"], portrait["max_columns"]),
                         ("home", 4, "portrait", 2))
        self.assertEqual(home_view["sections"], portrait["sections"])

    def test_every_section_is_as_tall_as_the_others(self):
        """In grid rows, so a row of sections ends level on any screen and no column stops early."""
        def rows(card):
            opts = card.get("grid_options", {})
            if "rows" in opts:
                return opts["rows"], opts.get("columns", 12)
            return 1, opts.get("columns", 6)          # a plain tile: one row, half the width
        for k, section in enumerate(home.SECTIONS):
            with self.subTest(section=k):
                used = 0
                for card in section["cards"]:
                    r, cols = rows(card)
                    used += r * cols
                self.assertEqual(used, home.SECTION_ROWS * 12, "grid cells: rows x 12 columns")

    def test_a_full_home(self):
        types = {c["type"] for c in self.cards(self.views(self.connect())[0])}
        self.assertLessEqual({"clock", "weather-forecast", "history-graph", "statistics-graph", "thermostat",
                              "picture-entity", "todo-list", "tile", "heading"}, types)
        cameras = [c for c in self.cards(self.views(self.connect())[0]) if c["type"] == "picture-entity"]
        self.assertEqual(len(cameras), 2)

    def test_nothing_shows_a_date(self):
        evening = datetime.datetime.fromtimestamp(home.NOW, datetime.timezone.utc).hour
        for c in self.cards(self.views(self.connect())[0]):
            with self.subTest(card=c["type"]):
                if c["type"] == "history-graph":
                    # its axis names the day only when the window crosses midnight
                    self.assertLessEqual(c["hours_to_show"], evening)
                if c["type"] == "clock":
                    self.assertNotIn("date", json.dumps(c))
                if c.get("entity", "").startswith("scene."):
                    self.assertTrue(c.get("hide_state"), "a scene's state is when it last ran")

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

    def test_the_shopping_list(self):
        ws = self.connect()
        res, _ = ws.call({"type": "todo/item/subscribe", "entity_id": "todo.shopping_list"})
        self.assertTrue(res["success"])
        ev = ws.recv()
        self.assertEqual([i["summary"] for i in ev["event"]["items"]], [t for t, _ in home.SHOPPING])

    def test_the_camera_stills_and_the_album_art(self):
        port = self.srv.server_address[1]
        for path in ("/api/camera_proxy/camera.front_garden?token=showcase", "/api/camera_proxy/camera.driveway?token=showcase",
                     "/clarity/showcase-art.png"):
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
        self.assertNotEqual(home._png(32, 18, home._garden), home._png(32, 18, home._driveway))

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
