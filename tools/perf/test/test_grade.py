"""python3 -m unittest discover -s tools/perf/test"""
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import grade as G  # noqa: E402
import perflib as P  # noqa: E402

GOOD, NI, POOR = G.GOOD, G.NI, G.POOR


class Thresholds(unittest.TestCase):
    """Each published threshold, on both sides of its edge. docs/perf.md cites the same numbers."""

    def check(self, metric, cases):
        for value, want in cases:
            with self.subTest(metric=metric, value=value):
                self.assertEqual(G.band(value, G.METRICS[metric]), want)

    def test_frame_p95_one_and_two_frames_at_60_hz(self):
        self.check("frame_p95_ms", [(16.67, GOOD), (17.1, GOOD), (17.3, NI), (33.33, NI), (33.8, NI), (34.0, POOR)])

    def test_jank_rate(self):
        self.check("frame_jank_pct", [(0, GOOD), (5, GOOD), (5.01, NI), (50, NI), (50.01, POOR)])

    def test_frozen_frames(self):
        self.check("frame_frozen_pct", [(0, GOOD), (0.05, NI), (0.1, NI), (0.11, POOR)])

    def test_load_is_lcp(self):
        self.check("load_ms", [(2500, GOOD), (2501, NI), (4000, NI), (4001, POOR)])

    def test_input_is_inp(self):
        self.check("inp_ms", [(200, GOOD), (201, NI), (500, NI), (501, POOR)])

    def test_cold_start_poor_at_five_seconds_or_more(self):
        self.check("start_ms", [(2500, GOOD), (2501, NI), (4999, NI), (5000, POOR)])

    def test_missing_value_has_no_band(self):
        self.assertIsNone(G.band(None, G.METRICS["inp_ms"]))


SMOOTH = {"n": 100, "p95_ms": 16.67, "jank_pct": 0, "frozen_pct": 0}      # 60 fps: frames band good
FLUID = {"n": 100, "p95_ms": 33.33, "jank_pct": 20, "frozen_pct": 0}      # 30 fps: needs improvement
CHOPPY = {"n": 100, "p95_ms": 50.0, "jank_pct": 80, "frozen_pct": 0}      # under 30 fps: poor
INP = {"good": 100, "ni": 300, "poor": 600}
LOAD = {"good": 1000, "ni": 3000, "poor": 5000}


def tiers(light=SMOOTH, typical=SMOOTH, busy=SMOOTH, inp=None, load=None, drop=()):
    """All good unless told otherwise; inp/load: {tier: "ni"|"poor"}."""
    inp, load = inp or {}, load or {}
    out = {}
    for t, fr in (("light", light), ("typical", typical), ("busy", busy)):
        if t not in drop:
            out[t] = {"frames": fr, "inp_ms": INP[inp.get(t, "good")], "load_ms": LOAD[load.get(t, "good")]}
    return out


class Letter(unittest.TestCase):
    """Each letter's boundary: the frame that just meets a letter gets it, and moving one requirement
    past its edge drops it. docs/perf.md, "The grade", has the same table."""

    CASES = [
        ("all good", tiers(), "A"),
        ("busy only fluid", tiers(busy=FLUID), "B"),
        ("busy INP needs improvement", tiers(inp={"busy": "ni"}), "C"),
        ("busy load needs improvement", tiers(load={"busy": "ni"}), "C"),
        ("busy choppy, typical smooth", tiers(busy=CHOPPY), "C"),
        ("typical only fluid", tiers(typical=FLUID, busy=FLUID), "C"),
        ("typical fluid, typical load needs improvement", tiers(typical=FLUID, busy=CHOPPY, load={"typical": "ni"}), "C"),
        ("typical fluid, light load needs improvement", tiers(typical=FLUID, busy=CHOPPY, load={"light": "ni"}), "C"),
        ("typical INP needs improvement", tiers(typical=FLUID, busy=CHOPPY, inp={"typical": "ni"}), "D"),
        ("light INP needs improvement", tiers(busy=CHOPPY, inp={"light": "ni"}), "D"),
        ("typical load poor", tiers(busy=CHOPPY, load={"typical": "poor"}), "D"),
        ("typical choppy", tiers(typical=CHOPPY, busy=CHOPPY), "D"),
        ("light only fluid", tiers(light=FLUID, typical=CHOPPY, busy=CHOPPY), "D"),
        ("light choppy", tiers(light=CHOPPY, typical=CHOPPY, busy=CHOPPY), "E"),
        ("light INP poor", tiers(typical=CHOPPY, busy=CHOPPY, inp={"light": "poor"}), "E"),
        ("light load poor", tiers(typical=CHOPPY, busy=CHOPPY, load={"light": "poor"}), "E"),
    ]

    def test_each_letter_boundary(self):
        for name, ts, want in self.CASES:
            with self.subTest(name):
                g = G.grade(ts, 1000)
                self.assertEqual(g["grade"], want)
                self.assertEqual(g["meaning"], G.MEANINGS[want])
                self.assertFalse(g["provisional"])

    def test_a_poor_start_caps_the_letter_at_c(self):
        self.assertEqual(G.grade(tiers(), 4999)["grade"], "A")
        g = G.grade(tiers(), 5000)
        self.assertEqual((g["grade"], g["capped_by_start"]), ("C", True))
        self.assertEqual(G.grade(tiers(busy=FLUID), 6000)["grade"], "C")
        g = G.grade(tiers(typical=CHOPPY, busy=CHOPPY), 6000)
        self.assertEqual((g["grade"], g["capped_by_start"]), ("D", False))   # a cap never raises a letter

    def test_a_missing_dashboard_fails_what_needs_it_and_is_provisional(self):
        g = G.grade(tiers(drop=("busy",)), 1000)
        self.assertEqual(g["grade"], "C")
        self.assertTrue(g["provisional"])
        self.assertEqual(g["missing"], ["busy.frames", "busy.inp", "busy.load"])

    def test_nothing_measured_is_e_and_provisional(self):
        g = G.grade({}, None)
        self.assertEqual(g["grade"], "E")
        self.assertTrue(g["provisional"])
        self.assertIn("start", g["missing"])


class Capacity(unittest.TestCase):
    def stats(self, p95, jank=0):
        return {"n": 120, "p95_ms": p95, "jank_pct": jank, "frozen_pct": 0}

    def test_largest_step_before_the_first_miss(self):
        steps = [(1, self.stats(16.67)), (2, self.stats(16.67)), (4, self.stats(33.33, 20)),
                 (8, self.stats(16.67)), (16, self.stats(50, 80))]
        self.assertEqual(G.capacity(steps, {GOOD}), 2)
        self.assertEqual(G.capacity(steps, {GOOD, NI}), 8)

    def test_no_step_held(self):
        self.assertEqual(G.capacity([(1, self.stats(50, 90))], {GOOD}), 0)


class MemoryBudget(unittest.TestCase):
    def test_budget_is_a_share_of_ram(self):
        b = {"app_pss_max_pct_of_ram": 50, "device_available_min_pct": 15}
        mem = {"mem_total_kb": 2_000_000, "app_pss_peak_kb": 900_000, "mem_available_min_kb": 400_000}
        r = G.memory_budget(mem, b)
        self.assertTrue(r["ok"])
        self.assertEqual(r["checks"]["app_pss_peak"]["budget_kb"], 1_000_000)
        r = G.memory_budget(dict(mem, app_pss_peak_kb=1_100_000), b)
        self.assertFalse(r["ok"])
        r = G.memory_budget(dict(mem, mem_available_min_kb=250_000), b)
        self.assertFalse(r["checks"]["device_available_min"]["ok"])

    def test_not_ok_when_a_budgeted_value_is_missing(self):
        b = {"app_pss_max_pct_of_ram": 50, "device_available_min_pct": 15}
        r = G.memory_budget({"mem_total_kb": 2_000_000, "app_pss_peak_kb": 900_000, "mem_available_min_kb": None}, b)
        self.assertIsNone(r["ok"])
        r = G.memory_budget({"mem_total_kb": 2_000_000, "app_pss_peak_kb": 1_500_000, "mem_available_min_kb": None}, b)
        self.assertFalse(r["ok"])

    def test_a_frame_override_may_name_one_key(self):
        budgets = {"default": {"app_pss_max_pct_of_ram": 50, "device_available_min_pct": 15},
                   "frames": {"mirror": {"app_pss_max_pct_of_ram": 60}}}
        self.assertEqual(G.frame_budget(budgets, "mirror"), {"app_pss_max_pct_of_ram": 60, "device_available_min_pct": 15})
        self.assertEqual(G.frame_budget(budgets, "other"), budgets["default"])


class SharedRules(unittest.TestCase):
    def test_band_cases_the_page_also_checks(self):
        with open(os.path.join(HERE, "band-cases.json")) as f:
            for c in json.load(f):
                with self.subTest(c["name"]):
                    self.assertEqual(G.frame_band(c["stats"]), c["band"])

    def test_frame_bounds_are_one_and_two_frames_plus_slack(self):
        r = G.RUBRIC
        self.assertAlmostEqual(r["metrics"]["frame_p95_ms"]["good_max"], r["one_frame_ms"] + r["vsync_slack_ms"], places=2)
        self.assertAlmostEqual(r["metrics"]["frame_p95_ms"]["poor_above"], 2 * r["one_frame_ms"] + r["vsync_slack_ms"], places=2)


class FrameStats(unittest.TestCase):
    def test_matches_the_shared_cases(self):
        with open(os.path.join(HERE, "stats-cases.json")) as f:
            for c in json.load(f):
                with self.subTest(c["name"]):
                    self.assertEqual(P.frame_stats(c["intervals"], c["vsync_ms"]), c["expect"])


if __name__ == "__main__":
    unittest.main()
