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
                    self.assertRegex(ref, r"^[\w.-]+$")

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

    def test_usable_means_rendered_and_free(self):
        self.assertIn('document.getElementById("ha-launch-screen")', self.text)
        self.assertIn("if (ran - posted <= 50) interactive = ran;", self.text)


class FixedHome(unittest.TestCase):
    """dashboards.py: real Lovelace configs with HA's own card types, the counts of page/tiers.json."""

    def test_counts_and_card_types(self):
        want = {"light": 20, "typical": 50, "heavy": 120}
        for tier, n in want.items():
            with self.subTest(tier):
                cards = D.lovelace(f"clarity-{tier}")["views"][0]["cards"]
                self.assertEqual(len(cards), n)
                self.assertLessEqual({c["type"] for c in cards},
                                     {"clock", "tile", "entities", "glance", "sensor", "history-graph", "picture"})
        heavy = collections.Counter(c["type"] for c in D.lovelace("clarity-heavy")["views"][0]["cards"])
        self.assertEqual(heavy, {"tile": 50, "entities": 26, "sensor": 16, "picture": 15, "history-graph": 8, "glance": 4, "clock": 1})

    def test_the_first_screen_is_the_same_on_every_page(self):
        heads = [D.lovelace(f"clarity-{t}")["views"][0]["cards"][:2 + len(D.head()["tiles"]) + 1] for t in D.TIERS["ORDER"]]
        self.assertEqual(heads[0], heads[1])
        self.assertEqual(heads[0], heads[2])
        self.assertEqual(heads[0][1], {"type": "tile", "entity": "light.living_room", "tap_action": {"action": "toggle"}})

    def test_it_is_the_same_every_time(self):
        self.assertEqual(json.dumps(D.lovelace("clarity-heavy")), json.dumps(D.lovelace("clarity-heavy")))
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
