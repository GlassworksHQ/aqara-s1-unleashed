"""Clarity's own page files are self-contained (nothing from the network) and seeded (no randomness);
the harness and the fixed home are what docs/perf.md says they are."""
import collections
import json
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PAGE = os.path.join(os.path.dirname(HERE), "page")
FILES = sorted(f for f in os.listdir(PAGE) if f.endswith((".html", ".js")))
SVG_NS = "http://www.w3.org/2000/svg"
sys.path.insert(0, os.path.dirname(HERE))
import dashboards as D  # noqa: E402


class Pages(unittest.TestCase):
    def read(self, f):
        with open(os.path.join(PAGE, f)) as fh:
            return fh.read()

    def test_the_pages_are_there(self):
        for f in ("index.html", "perf.js", "stats.js", "workload.js", "harness.js", "tiers.json", "rubric.json", "package.json"):
            self.assertTrue(os.path.exists(os.path.join(PAGE, f)), f)
        for gone in ("dashboard.html", "refdash.js", "tiers.js"):          # protocol 3's imitation pages
            self.assertFalse(os.path.exists(os.path.join(PAGE, gone)), gone)

    def test_no_url_outside_the_page_directory(self):
        for f in FILES:
            with self.subTest(f):
                text = self.read(f).replace(SVG_NS, "")
                self.assertEqual(re.findall(r"(?:https?:)?//[\w.-]+\.[a-z]{2,}", text), [])

    def test_scripts_styles_and_fetches_are_relative(self):
        for f in FILES:
            with self.subTest(f):
                for ref in re.findall(r'(?:src|href)="([^"]+)"', self.read(f)) + re.findall(r'fetch\("([^"]+)"', self.read(f)):
                    # The harness runs on the fake Home Assistant's origin, whose own API is /clarity/.
                    self.assertRegex(ref, r"^/clarity/[\w.-]+$" if f == "harness.js" else r"^[\w.-]+$")

    def test_no_randomness(self):
        for f in FILES:
            with self.subTest(f):
                self.assertNotIn("Math.random", self.read(f))
                self.assertNotIn("crypto.getRandomValues", self.read(f))

    def test_colours_follow_the_brand(self):
        """glassworks.dev/brand: no ember (Kiln's), no green, amber or rose (status only), night ground,
        and generated hues between sky and violet. (The reference dashboards are HA's own frontend,
        in HA's colours.)"""
        off = re.compile(r"#(ffc107|03a9f4|111318|f43f5e|fdba74|34d399|fbbf24|fb7185)\b|rnd\(\) \* 360", re.I)
        for name in ("index.html", "perf.js"):
            with self.subTest(name):
                self.assertIsNone(off.search(self.read(name)), off.search(self.read(name)))
        self.assertIn("--bg: #0a0b0f;", self.read("index.html"))


class Harness(unittest.TestCase):
    """page/harness.js: what docs/perf.md, "How Clarity measures HA's frontend", says it does."""

    def setUp(self):
        with open(os.path.join(PAGE, "harness.js")) as f:
            self.text = f.read()

    def test_it_sees_each_change_before_ha_does(self):
        self.assertIn("window.WebSocket = Clarity;", self.text)
        self.assertIn('ws.addEventListener("message", onMessage);       // registered first', self.text)

    def test_a_change_counted_twice_is_counted_once(self):
        self.assertIn("if (live.seen.has(key)) continue;", self.text)

    def test_scroll_during_updates_counts_state_changes_not_the_clock(self):
        # live.applied counts the watched entities' state changes only; HA's clock card isn't one.
        self.assertEqual(self.text.count("live.applied++"), 1)
        self.assertLess(self.text.index("live.applied++"), self.text.index("function find("))

    def test_usable_and_free_are_the_tested_rules(self):
        # The rules themselves are in stats.js, with a node test per clause (page.test.mjs).
        self.assertIn("st = S.usableStep({", self.text)
        self.assertIn('launch: !!document.getElementById("ha-launch-screen")', self.text)
        self.assertIn("head: headCards(view),", self.text)
        self.assertIn("st = S.freeStep(posted, ran, st.start, C.PHASES.freeWindowMs);", self.text)

    def test_a_view_opens_by_navigation_as_ha_does(self):
        # src/common/navigate.ts performNavigation: pushState, then location-changed on the window.
        self.assertIn("history.pushState(", self.text)
        self.assertIn('new Event("location-changed"', self.text)
        self.assertIn("window.dispatchEvent(ev);", self.text)
        self.assertNotIn("location.assign", self.text)
        self.assertNotIn("location.href =", self.text)

    def test_changes_are_timed_from_when_the_fake_sent_them(self):
        self.assertIn('from: "sent"', self.text)
        self.assertIn("const SLOT = /^01JC1ARITY([SF])(\\d{5})(\\d{6})(\\d{4})$/;", self.text)
        self.assertIn("const sent = due + (c ? c.late : 0);", self.text)

    def test_a_stream_that_didnt_start_is_an_error_not_a_slow_panel(self):
        self.assertIn("if (j.gen === undefined) throw new Error(`the live stream didn't start", self.text)
        self.assertIn("if (on) throw e;", self.text)

    def test_frames_are_asked_for_only_while_a_view_is_measured(self):
        """F10: no rAF loop between views or at idle."""
        self.assertIn("if (!frames.ticking) return;", self.text)
        self.assertIn("frames.ticking = false;", self.text)
        self.assertNotIn("requestAnimationFrame(function loop", self.text)


class FixedHome(unittest.TestCase):
    """dashboards.py: real Lovelace configs with HA's own card types, the counts of page/tiers.json."""

    def test_counts_and_card_types(self):
        want = {"start": 23, "light": 20, "typical": 50, "heavy": 120}
        views = {v["path"]: v["cards"] for v in D.lovelace(D.DASHBOARD)["views"]}
        self.assertEqual(list(views), ["start", "light", "typical", "heavy"])
        for tier, n in want.items():
            with self.subTest(tier):
                self.assertEqual(len(views[tier]), n)
                self.assertLessEqual({c["type"] for c in views[tier]},
                                     {"clock", "tile", "entities", "glance", "sensor", "history-graph", "picture", "picture-entity", "grid"})
        heavy = collections.Counter(c["type"] for c in views["heavy"])
        self.assertEqual(heavy, {"tile": 50, "entities": 26, "sensor": 16, "picture": 13, "history-graph": 8, "glance": 4, "clock": 1,
                                 "picture-entity": 1, "grid": 1})
        # #174: the cameras come right after the head, live; heavy's sub-streams in a grid of three.
        self.assertEqual(views["typical"][D.head_cards()], {"type": "picture-entity", "entity": "camera.clarity_cam_0",
                                                            "camera_view": "live", "show_name": False, "show_state": False})
        grid = views["heavy"][D.head_cards() + 1]
        self.assertEqual((grid["type"], grid["columns"], [c["entity"] for c in grid["cards"]]),
                         ("grid", 3, ["camera.clarity_cam_1", "camera.clarity_cam_2", "camera.clarity_cam_3"]))
        self.assertNotIn("picture-entity", {c["type"] for c in views["light"]})

    def test_the_start_view_loads_every_lazy_card_type(self):
        """HA loads history-graph, picture and clock lazily (create-card-element.ts L86, L94, L100).
        The start view has each type any reference view has, so the cold load (the restart) pays for
        their code and opening a view never does (smoke9's typical did, first to show a history graph)."""
        # Always loaded (create-card-element.ts ALWAYS_LOADED_TYPES): the rest is fetched on first use.
        always = {"entity", "entities", "button", "entity-button", "glance", "grid", "section", "light", "sensor",
                  "thermostat", "weather-forecast", "tile", "heading"}
        views = {v["path"]: v["cards"] for v in D.lovelace(D.DASHBOARD)["views"]}
        start = {c["type"] for c in views["start"]}
        for tier in D.TIERS["ORDER"]:
            self.assertLessEqual({c["type"] for c in views[tier]} - always, start, tier)
        self.assertIn("picture-entity", start)          # and the camera player's code with it (#174)

    def test_the_first_screen_is_the_same_on_every_view(self):
        heads = [v["cards"][:D.head_cards()] for v in D.lovelace(D.DASHBOARD)["views"]]
        self.assertEqual(D.head_cards(), 10)
        for h in heads[1:]:
            self.assertEqual(h, heads[0])
        self.assertEqual(heads[0][1], {"type": "tile", "entity": "light.living_room", "tap_action": {"action": "toggle"}})

    def test_history_is_the_same_on_any_date(self):
        """F4: the graphs follow the asked window, so heavy's 8 history graphs are as full a week on."""
        for start, nxt in (("2026-11-12T08:00:00Z", "2026-11-19T08:00:00Z"), ("2026-11-12T08:00:00+00:00", "2027-03-01T08:00:00Z")):
            a, b = D.history(["sensor.clarity_20", "light.clarity_3"], start, None), D.history(["sensor.clarity_20", "light.clarity_3"], nxt, None)
            for e in a:
                self.assertEqual([p["s"] for p in a[e]], [p["s"] for p in b[e]])
                t0 = D.window(nxt, None)[0]
                self.assertTrue(all(t0 <= p["lu"] <= t0 + 86400 for p in b[e]), e)
            sa = D.statistics(["sensor.clarity_20"], start, None, "hour")["sensor.clarity_20"]
            sb = D.statistics(["sensor.clarity_20"], nxt, None, "hour")["sensor.clarity_20"]
            self.assertEqual(len(sa), 24)
            self.assertEqual([p["mean"] for p in sa], [p["mean"] for p in sb])
            self.assertEqual(sb[-1]["end"] / 1000, D.window(nxt, None)[1])

    def test_it_is_the_same_every_time(self):
        self.assertEqual(json.dumps(D.lovelace(D.DASHBOARD)), json.dumps(D.lovelace(D.DASHBOARD)))
        self.assertEqual(D.states()["sensor.clarity_7"]["state"], D.states()["sensor.clarity_7"]["state"])

    def test_the_stream_never_sends_a_change_that_isnt_one(self):
        """A zero step re-sends the same value: no change for HA, so nothing would be drawn."""
        st = D.states()
        s = D.Stream(None, st)
        for k in range(1, 300):          # a minute of changes: more than any window
            e, v = s.sensor_change(k)
            self.assertIn(e, s.targets)
            self.assertNotEqual(v, st[e]["state"], k)
            st[e]["state"] = v


if __name__ == "__main__":
    unittest.main()
