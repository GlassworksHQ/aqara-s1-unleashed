"""python3 -m unittest discover -s tools/perf/test

The showcase home (tools/showcase/home.py) served by the fake Home Assistant, as render.mjs serves it
for the device renders: its own dashboard (nine sections, each the same height) and made-up states, the forecast, the energy statistics, the shopping list, the CC0 camera photos, the drawn
TV still and album art, HA's icons and state names, nothing that shows a date, the same data and pictures every time, and
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
        fakeha.D = fakeha.CLARITY      # the next test module's servers are Clarity's

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

    def test_one_view_of_nine_sections(self):
        """Nine fill three columns of three (the Mirror) and two rows of four (the tablet, the ninth below its fold)."""
        (view,) = self.views(self.connect())
        self.assertEqual((view["path"], view["max_columns"], len(view["sections"])), ("home", 4, 9))

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
        self.assertIn("media-control", types)

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

    def test_the_camera_stills_are_the_cc0_photos(self):
        """The two cameras show the committed photos (photos/README.md), small JPEGs at 16:9."""
        port = self.srv.server_address[1]
        for cam, name in (("camera.front_garden", "front-garden.jpg"), ("camera.driveway", "driveway.jpg")):
            with self.subTest(cam):
                with open(os.path.join(home.HERE, "photos", name), "rb") as f:
                    photo = f.read()
                self.assertLess(len(photo), 100 * 1024)
                self.assertEqual(self._jpeg_size(photo), (800, 450))
                status, ctype, body = self._get(port, f"/api/camera_proxy/{cam}?token=showcase")
                self.assertEqual((status, ctype, body), (200, "image/jpeg", photo))
        # every photo is listed with its licence
        with open(os.path.join(home.HERE, "photos", "README.md")) as f:
            readme = f.read()
        for name in home.PHOTOS.values():
            self.assertIn(f"`{name}`", readme)
        self.assertEqual(readme.count("CC0 1.0"), len(home.PHOTOS))

    def test_the_drawn_pictures(self):
        port = self.srv.server_address[1]
        for path in ("/clarity/showcase-art.png", "/clarity/showcase-tv.png"):
            with self.subTest(path):
                status, ctype, body = self._get(port, path)
                self.assertEqual((status, ctype, body[:8]), (200, "image/png", b"\x89PNG\r\n\x1a\n"))
                self.assertNotEqual(body, fakeha.CLARITY.asset("picture-0.png")[1], "the showcase's own picture")

    def test_a_camera_without_a_still_gets_clarity_picture(self):
        status, ctype, body = self._get(self.srv.server_address[1], "/api/camera_proxy/camera.nowhere?token=x")
        self.assertEqual((status, body), (200, fakeha.CLARITY.asset("picture-0.png")[1]))

    @staticmethod
    def _get(port, path):
        conn = http.client.HTTPConnection("127.0.0.1", port)
        try:
            conn.request("GET", path)
            r = conn.getresponse()
            return r.status, r.getheader("Content-Type"), r.read()
        finally:
            conn.close()

    @staticmethod
    def _jpeg_size(b):
        """(width, height) from a JPEG's SOF marker."""
        i = 2
        while i < len(b):
            marker, length = b[i + 1], int.from_bytes(b[i + 2:i + 4], "big")
            if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                return int.from_bytes(b[i + 7:i + 9], "big"), int.from_bytes(b[i + 5:i + 7], "big")
            i += 2 + length
        return None

    def test_the_same_data_and_pictures_every_time(self):
        ids = ["sensor.home_power", "sensor.outdoor_temperature"]
        first = home.history(ids, None, None)
        self.assertEqual(first, home.history(ids, None, None))
        self.assertGreater(len(first["sensor.home_power"]), 100)
        self.assertEqual(home._png(32, 18, home._poster), home._png(32, 18, home._poster))
        self.assertNotEqual(home._png(32, 18, home._poster), home._png(32, 18, home._art))

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

    def test_clarity_is_back_after_this_module(self):
        """tearDownClass hands the module back with Clarity's home, as test_fakeha expects."""
        type(self).tearDownClass()
        try:
            self.assertIs(fakeha.D, fakeha.CLARITY)
        finally:
            type(self).setUpClass()


if __name__ == "__main__":
    unittest.main()
