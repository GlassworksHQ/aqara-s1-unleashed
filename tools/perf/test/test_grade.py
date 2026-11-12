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


INP = {"good": 100, "ni": 300, "poor": 600}
LOAD = {"good": 1000, "ni": 3000, "poor": 5000}


def page(tap="good", scroll="good", updates="good", load="good"):
    """One page's grade inputs: tap and load as values, frames as the median run band."""
    band = {"good": GOOD, "ni": NI, "poor": POOR, None: None}
    return {"inp_ms": INP[tap] if tap else None, "load_ms": LOAD[load] if load else None,
            "scroll_band": band[scroll], "updates_band": band[updates]}


def pages(light=None, typical=None, heavy=None):
    return {t: p for t, p in (("light", light or page()), ("typical", typical or page()), ("heavy", heavy or page())) if p != "drop"}


SMOOTH, FLUID, NEITHER = page(), page(scroll="ni", updates="ni"), page(scroll="poor")


class PageLevel(unittest.TestCase):
    """Each task's pass rule: the page is smooth only if every task passes at 60 fps, fluid at 30."""

    CASES = [
        ("everything good", page(), "smooth"),
        ("view loads in up to 4 s: still smooth", page(load="ni"), "smooth"),
        ("view load over 4 s", page(load="poor"), None),
        ("scroll at 30 fps", page(scroll="ni"), "fluid"),
        ("scroll at 30 fps, view loads in up to 4 s", page(scroll="ni", load="ni"), "fluid"),
        ("live updates at 30 fps", page(updates="ni"), "fluid"),
        ("scroll under 30 fps", page(scroll="poor"), None),
        ("live updates under 30 fps", page(updates="poor"), None),
        ("a tap over 200 ms", page(tap="ni"), None),
        ("a tap over 500 ms", page(tap="poor"), None),
        ("scroll not measured", page(scroll=None), None),
    ]

    def test_each_task_pass_rule(self):
        for name, p, want in self.CASES:
            with self.subTest(name):
                self.assertEqual(G.page_level(G.tier_bands(p)), want)

    def test_limits_names_the_task(self):
        self.assertEqual(G.limits(G.tier_bands(page(scroll="ni", load="poor")), "smooth"), ["scroll", "load"])


class Letter(unittest.TestCase):
    """Each letter's boundary: the frame that just meets a letter gets it, and moving one page one
    level down drops it. docs/perf.md, "The grade", has the same table."""

    CASES = [
        ("heavy smooth", pages(), "A"),
        ("heavy fluid, typical smooth", pages(heavy=FLUID), "B"),
        ("heavy below fluid, typical smooth", pages(heavy=NEITHER), "C"),
        ("typical fluid, heavy fluid", pages(typical=FLUID, heavy=FLUID), "C"),
        ("typical fluid", pages(typical=FLUID, heavy=NEITHER), "C"),
        ("typical below fluid, light fluid", pages(light=FLUID, typical=NEITHER, heavy=NEITHER), "D"),
        ("typical below fluid, light smooth", pages(typical=NEITHER, heavy=NEITHER), "D"),
        ("light below fluid", pages(light=NEITHER, typical=NEITHER, heavy=NEITHER), "E"),
        ("only heavy smooth: it is the letter's one condition", pages(light=NEITHER, typical=NEITHER), "A"),
        ("a slow tap on typical", pages(typical=page(tap="ni"), heavy=NEITHER), "D"),
        ("a slow view on light", pages(light=page(load="poor"), typical=NEITHER, heavy=NEITHER), "E"),
    ]

    def test_each_letter_boundary(self):
        for name, ts, want in self.CASES:
            with self.subTest(name):
                g = G.grade(ts, 1000)
                self.assertEqual(g["grade"], want)
                self.assertEqual(g["meaning"], G.MEANINGS[want])

    def test_a_poor_restart_caps_the_letter_at_c(self):
        self.assertEqual(G.grade(pages(), 4999)["grade"], "A")
        g = G.grade(pages(), 5000)
        self.assertEqual((g["grade"], g["capped_by_start"]), ("C", True))
        g = G.grade(pages(typical=NEITHER, heavy=NEITHER), 6000)
        self.assertEqual((g["grade"], g["capped_by_start"]), ("D", False))   # a cap never raises a letter

    def test_a_missing_page_fails_what_needs_it_and_is_provisional(self):
        g = G.grade(pages(heavy="drop"), 1000)
        self.assertEqual(g["grade"], "C")
        self.assertTrue(g["provisional"])
        self.assertEqual(g["missing"], ["heavy.tap", "heavy.scroll", "heavy.updates", "heavy.load"])

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
