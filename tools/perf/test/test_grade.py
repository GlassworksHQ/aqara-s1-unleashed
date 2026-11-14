"""python3 -m unittest discover -s tools/perf/test"""
import json
import re
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


def page(tap="good", scroll="premium", updates="premium", load="good", camera="good"):
    """One page's grade inputs: tap and load as values, scroll, live updates and camera as median run
    levels (camera counts only on the pages with streams)."""
    return {"inp_ms": INP[tap] if tap else None, "load_ms": LOAD[load] if load else None,
            "scroll_level": scroll, "updates_level": updates, "camera_level": camera}


def pages(light=None, typical=None, heavy=None):
    return {t: p for t, p in (("light", light or page()), ("typical", typical or page()), ("heavy", heavy or page())) if p != "drop"}


PREMIUM, GOOD_, FLUID = page(), page(scroll="good"), page(scroll="fluid")
NEITHER = page(scroll="slow")


def run(fps, p95, jank=0.0, frozen=0.0, n=200):
    return {"n": n, "fps": fps, "p95_ms": p95, "jank_pct": jank, "frozen_pct": frozen}


class ScrollLevel(unittest.TestCase):
    """Rubric v5: scroll is judged per run on average fps over the swipes and a p95 hitch cap."""

    CASES = [
        ("the 60 fps rule", run(59.0, 16.67, jank=2), "premium"),
        ("60 fps but 6% of frames late: not the 60 fps rule", run(57.0, 16.67, jank=6), "good"),
        ("50 fps, p95 three frames", run(50.0, 50.0, jank=30), "good"),
        ("49.9 fps", run(49.9, 33.33, jank=30), "fluid"),
        ("p95 at 50.5 ms: three frames and the slack, still good", run(55.0, 50.5, jank=20), "good"),
        ("p95 just over 50 ms (three frames and the slack)", run(55.0, 50.51, jank=20), "fluid"),
        ("p95 at 67.17 ms: four frames and the slack, still fluid", run(40.0, 67.17, jank=40), "fluid"),
        ("30 fps, p95 four frames", run(30.0, 66.67, jank=60), "fluid"),
        ("29.9 fps", run(29.9, 50.0, jank=60), "slow"),
        ("p95 just over 67 ms", run(40.0, 67.18, jank=40), "slow"),
        ("not measured", {"n": 0}, None),
    ]

    def test_each_scroll_level_boundary(self):
        for name, st, want in self.CASES:
            with self.subTest(name):
                self.assertEqual(G.scroll_level(st), want)

    def test_median_level_of_runs(self):
        self.assertEqual(G.median_level(["premium", "good", "fluid"]), "good")
        self.assertEqual(G.median_level(["premium", "slow"]), "slow")      # the worse middle
        self.assertEqual(G.median_level([None, "good"]), "good")
        self.assertIsNone(G.median_level([]))


def upd(p95, frames=True, n=26):
    """One run's live updates: change-to-screen p95, and whether its change frames hold 60 fps."""
    return {"latency_n": n, "latency_median_ms": p95 / 2, "latency_p95_ms": p95,
            "frames": run(59.0, 16.67, jank=2) if frames else run(40.0, 50.0, jank=40)}


class UpdateLevel(unittest.TestCase):
    """Rubric v5: live updates are judged on how fast a change reaches the screen, not frame pacing."""

    CASES = [
        ("100 ms and the change frames at 60 fps", upd(100.0), "premium"),
        ("100 ms, change frames at 40 fps: pacing doesn't grade", upd(100.0, frames=False), "good"),
        ("just over 100 ms", upd(100.1), "fluid"),
        ("200 ms", upd(200.0, frames=False), "fluid"),
        ("just over 200 ms", upd(200.1), "slow"),
        ("no change measured", upd(80.0, n=0), None),
        ("no pacing frames: not premium", dict(upd(50.0), frames=None), "good"),
    ]

    def test_each_update_level_boundary(self):
        for name, r, want in self.CASES:
            with self.subTest(name):
                self.assertEqual(G.update_level(r), want)


class PageLevel(unittest.TestCase):
    """Each task's pass rule. Premium: every task at 60 fps. Good: scroll at the good rule, the
    other tasks at their 60 fps bars (live updates aren't loosened). Fluid: 30 fps."""

    CASES = [
        ("everything at 60 fps", page(), "premium"),
        ("view loads in up to 4 s: still premium", page(load="ni"), "premium"),
        ("view load over 4 s", page(load="poor"), None),
        ("scroll good", page(scroll="good"), "good"),
        ("scroll good, view loads in up to 4 s", page(scroll="good", load="ni"), "good"),
        ("scroll fluid", page(scroll="fluid"), "fluid"),
        ("scroll slow", page(scroll="slow"), None),
        ("live updates good (within 100 ms), pacing not 60 fps", page(updates="good"), "good"),
        ("live updates fluid (within 200 ms)", page(updates="fluid"), "fluid"),
        ("scroll good, live updates fluid", page(scroll="good", updates="fluid"), "fluid"),
        ("live updates slower than 200 ms", page(updates="slow"), None),
        ("live updates not measured", page(updates=None), None),
        ("a tap over 200 ms", page(tap="ni"), None),
        ("a tap over 500 ms", page(tap="poor"), None),
        ("scroll not measured", page(scroll=None), None),
    ]

    def test_each_task_pass_rule(self):
        for name, p, want in self.CASES:
            with self.subTest(name):
                self.assertEqual(G.page_level(G.tier_bands(p)), want)

    def test_the_camera_task_counts_on_the_pages_with_streams(self):
        self.assertEqual(G.CAMERA_PAGES, ["typical", "heavy"])
        self.assertEqual(G.page_level(G.tier_bands(page(camera="fluid"), "typical")), "fluid")
        self.assertEqual(G.page_level(G.tier_bands(page(camera="slow"), "heavy")), None)
        self.assertEqual(G.page_level(G.tier_bands(page(camera="slow"), "light")), "premium")    # no streams there
        self.assertEqual(G.tier_bands(page(), "light")["camera"], "none")
        self.assertEqual(G.page_level(G.tier_bands(page(camera=None), "heavy")), None)

    def test_limits_names_the_task(self):
        self.assertEqual(G.limits(G.tier_bands(page(scroll="fluid", load="poor")), "good"), ["scroll", "load"])


class Letter(unittest.TestCase):
    """Each letter's boundary: the frame that just meets a letter gets it, and moving one page one
    level down drops it. docs/perf.md, "The grade", has the same table."""

    CASES = [
        ("heavy premium: every task at 60 fps", pages(), "A+"),
        ("heavy premium, light and typical slow: A+ needs only heavy", pages(light=NEITHER, typical=NEITHER), "A+"),
        ("heavy good", pages(heavy=GOOD_), "A"),
        ("heavy premium scroll, live updates only good: A", pages(heavy=page(updates="good")), "A"),
        ("heavy premium but a slow tap: heavy fails every level, typical decides", pages(heavy=page(tap="ni")), "C"),
        ("heavy fluid, typical good", pages(typical=GOOD_, heavy=FLUID), "B"),
        ("heavy below fluid, typical good", pages(typical=GOOD_, heavy=NEITHER), "C"),
        ("typical fluid, heavy fluid", pages(typical=FLUID, heavy=FLUID), "C"),
        ("typical fluid", pages(typical=FLUID, heavy=NEITHER), "C"),
        ("typical below fluid, light fluid", pages(light=FLUID, typical=NEITHER, heavy=NEITHER), "D"),
        ("typical below fluid, light premium", pages(typical=NEITHER, heavy=NEITHER), "D"),
        ("light below fluid", pages(light=NEITHER, typical=NEITHER, heavy=NEITHER), "E"),
        ("only heavy good: it is the letter's one condition", pages(light=NEITHER, typical=NEITHER, heavy=GOOD_), "A"),
        ("heavy's live updates fluid: heavy fluid, not good", pages(heavy=page(updates="fluid")), "B"),
        ("heavy good on both: A", pages(heavy=page(scroll="good", updates="good")), "A"),
        ("a slow tap on typical", pages(typical=page(tap="ni"), heavy=NEITHER), "D"),
        ("a slow view on light", pages(light=page(load="poor"), typical=NEITHER, heavy=NEITHER), "E"),
    ]

    def test_each_letter_boundary(self):
        for name, ts, want in self.CASES:
            with self.subTest(name):
                g = G.grade(ts, 1000)
                self.assertEqual(g["grade"], want)
                self.assertEqual(g["meaning"], G.MEANINGS[want])

    def test_a_plus_is_the_premium_letter(self):
        self.assertEqual((G.grade(pages(), 1000)["grade"], G.MEANINGS["A+"]), ("A+", "premium"))
        g = G.grade(pages(), 5000)
        self.assertEqual((g["grade"], g["capped_by_start"]), ("C", True))       # the restart cap reaches A+ too
        self.assertEqual(G.next_limits(G.grade(pages(heavy=GOOD_), 1000)),
                         [{"page": "heavy", "needs": "premium", "tasks": ["scroll"]}])
        self.assertEqual(G.next_limits(G.grade(pages(), 1000)), [])
        self.assertEqual(G.LETTER_ORDER, ["A+", "A", "B", "C", "D", "E"])

    def test_a_poor_restart_caps_the_letter_at_c(self):
        self.assertEqual(G.grade(pages(), 4999)["grade"], "A+")
        g = G.grade(pages(), 5000)
        self.assertEqual((g["grade"], g["capped_by_start"]), ("C", True))
        g = G.grade(pages(typical=NEITHER, heavy=NEITHER), 6000)
        self.assertEqual((g["grade"], g["capped_by_start"]), ("D", False))   # a cap never raises a letter

    def test_a_missing_page_fails_what_needs_it_and_is_provisional(self):
        g = G.grade(pages(heavy="drop"), 1000)
        self.assertEqual(g["grade"], "C")
        self.assertTrue(g["provisional"])
        self.assertEqual(g["missing"], ["heavy.tap", "heavy.scroll", "heavy.updates", "heavy.load", "heavy.camera"])

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
        default = {"ram": {"app_pss_max_pct_of_ram": 50, "device_available_min_pct": 15}, "panes": {}}
        mirror = {"ram": {"app_pss_max_pct_of_ram": 60}, "panes": {"assist": {"ram_pss_max_kb": 1000}}}
        self.assertEqual(G.frame_budget(default, mirror),
                         {"ram": {"app_pss_max_pct_of_ram": 60, "device_available_min_pct": 15}, "panes": mirror["panes"]})
        self.assertEqual(G.frame_budget(default), default)

    def test_the_s1_owns_its_budgets(self):
        """#68: the RAM budgets per frame and per pane are in the frame's own config."""
        root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..")
        with open(os.path.join(root, "frames", "aqara-s1-plus", "budgets.json")) as f:
            s1 = json.load(f)
        self.assertEqual(set(s1["ram"]), {"app_pss_max_pct_of_ram", "device_available_min_pct"})
        switchable = {k for k, v in s1["panes"].items() if v.get("switch")}
        self.assertEqual(switchable, {"assist", "sendspin", "wall-base", "time-zone", "diagnostics"})
        for k in switchable:
            self.assertIsInstance(s1["panes"][k]["ram_pss_max_kb"], int)
        # Every pane in the app's code is in the frame's file, so a new one can't be left out of RAM.
        ids = set()
        for d, _, fs in os.walk(os.path.join(root, "app", "kiosk", "src", "main", "java")):
            for f in fs:
                if f.endswith(".kt"):
                    with open(os.path.join(d, f)) as fh:
                        ids |= set(re.findall(r'override val id = "([a-z0-9-]+)"', fh.read()))
        self.assertEqual(set(s1["panes"]), ids)
        # #67 gave every pane a switch: the radio's is its mode, and timers come with voice. The Bluetooth
        # proxy has one, but only scans while HA listens, which the reference run doesn't (#121).
        # Notifications is off by default and idle until a notify call (#118).
        self.assertEqual({k for k, v in s1["panes"].items() if v.get("not_switched")}, {"radio", "timers", "bluetooth-proxy", "notifications"})
        self.assertEqual(set(s1["panes"]), switchable | {"radio", "timers", "bluetooth-proxy", "notifications"})

    def test_a_pane_over_its_budget(self):
        n3 = {"pss_tolerance_kb": 4_000, "samples_on": 3, "samples_off": 3}
        r = G.pane_budgets([dict(n3, id="assist", pss_kb=60_000), dict(n3, id="sendspin", pss_kb=20_000), dict(n3, id="x", pss_kb=1)],
                           {"assist": {"ram_pss_max_kb": 49152}, "sendspin": {"ram_pss_max_kb": 49152}})
        self.assertEqual((r["assist"]["ok"], r["sendspin"]["ok"], r["x"]["ok"]), (False, True, None))

    def test_a_tolerance_wider_than_a_quarter_of_the_budget_is_not_measurable(self):
        """#186: s1p3's ±31.6 MB against 48 MB said "ok" for a share it couldn't place."""
        b = {"assist": {"ram_pss_max_kb": 49152}}
        def check(pss, tol, n=3):
            r = G.pane_budgets([{"id": "assist", "pss_kb": pss, "pss_tolerance_kb": tol, "samples_on": n, "samples_off": n}], b)
            return r["assist"]["measurable"], r["assist"]["ok"]
        self.assertEqual(check(5_300, 32_400), (False, None))
        self.assertEqual(check(5_300, 12_288), (True, True))
        self.assertEqual(check(60_000, 8_700), (True, False))

    def test_a_budget_verdict_needs_three_samples_a_side(self):
        r = G.pane_budgets([{"id": "assist", "pss_kb": 5_300, "pss_tolerance_kb": 0, "samples_on": 3, "samples_off": 2}],
                           {"assist": {"ram_pss_max_kb": 49152}})["assist"]
        self.assertEqual((r["measurable"], r["ok"]), (False, None))
        r = G.pane_budgets([{"id": "assist", "pss_kb": 5_300, "pss_tolerance_kb": None, "samples_on": 1, "samples_off": 1}],
                           {"assist": {"ram_pss_max_kb": 49152}})["assist"]
        self.assertEqual((r["measurable"], r["ok"]), (False, None))


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




class SharedCases(unittest.TestCase):
    """test/grade-cases.json: the same boundaries grade.ts must meet (grade.test.mjs)."""

    @classmethod
    def setUpClass(cls):
        with open(os.path.join(HERE, "grade-cases.json")) as f:
            cls.data = json.load(f)

    def test_bands(self):
        for c in self.data["bands"]:
            with self.subTest(**c):
                self.assertEqual(G.band(c["value"], G.METRICS[c["metric"]]), c["band"])

    def test_letters_and_levels(self):
        for c in self.data["cases"]:
            with self.subTest(c["name"]):
                g = G.grade(c["tiers"], c["start_ms"])
                got = {k: g[k] for k in c["expect"] if k != "limits"}
                got["limits"] = G.next_limits(g)
                self.assertEqual(got, c["expect"])

    def test_the_name_lives_in_rubric_json(self):
        self.assertEqual((G.NAME, G.RUBRIC["name"]), ("Clarity", "Clarity"))

    def test_the_rules_come_from_rubric_json(self):
        self.assertEqual(self.data["rubric_version"], G.RUBRIC_VERSION)
        self.assertEqual([l for l, _, _ in G.LETTERS], [l["letter"] for l in G.RUBRIC["grade"]["letters"]])
        self.assertEqual(G.START_CAP, G.RUBRIC["grade"]["start_cap"]["letter"])

    def test_scroll_levels(self):
        for c in self.data["scroll_levels"]:
            self.assertEqual(G.scroll_level(c["stats"]), c["level"], c["name"])
        for c in self.data["update_levels"]:
            self.assertEqual(G.update_level(c["run"]), c["level"], c["name"])
        for c in self.data["median_levels"]:
            self.assertEqual(G.median_level(c["levels"]), c["level"], c)
        for c in self.data["camera_levels"]:
            self.assertEqual(G.camera_level(c["run"]), c["level"], c["name"])

    def test_frames_and_median_bands(self):
        for c in self.data["frames"]:
            self.assertEqual(G.frame_band(c["stats"]), c["band"], c)
        for c in self.data["median_bands"]:
            self.assertEqual(G.median_band(c["bands"]), c["band"], c)


if __name__ == "__main__":
    unittest.main()
