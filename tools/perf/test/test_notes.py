"""#201: the notes paragraph. Real runs (trimmed by notes_fixture.py) give the expected text, and each
cause is said only when its evidence is in the report."""
import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import grade as G  # noqa: E402
import notes as N  # noqa: E402
import report as R  # noqa: E402
import summary as S  # noqa: E402


def run(name):
    with open(os.path.join(HERE, "notes-runs", name + ".json")) as f:
        return json.load(f)


S1CAM3 = (
    "Light dashboards feel good: taps, live updates and opening a view are quick, and scrolling is fluid. Bigger "
    "dashboards slow down: typical scrolls at 25 fps and takes 6.6 s to open, and heavy takes 0.7 s to show a "
    "change. The likely limit is the browser's main thread: it stays busy (97% of a core on typical with a camera "
    "playing), so cameras get video late even though the hardware decoder keeps up. Memory isn't the limit (889 "
    "of 988 MB).")
S1V7 = (
    "Light dashboards feel good: taps, live updates and opening a view are quick, and scrolling is fluid. Bigger "
    "dashboards slow down: typical scrolls unevenly (slowest frames 80 ms) and takes 4.8 s to open. The likely "
    "limit is the browser's main thread: it stays busy (46% of a core on typical with nothing touched), and the "
    "page draws too few frames. Memory isn't the limit (743 of 988 MB). Provisional: measured with an older "
    "version of the test, without cameras.")

SMOKE11 = (
    "Light dashboards feel good: taps and opening a view are quick, and scrolling and live updates are fluid. "
    "Bigger dashboards slow down: typical scrolls unevenly (slowest frames 80 ms) and takes 5.2 s to open, and "
    "heavy takes 230 ms to answer a tap. The likely limit is the browser's main thread: it stays busy (46% of a "
    "core on typical with nothing touched), and the page draws too few frames. Memory isn't the limit (748 of 988 "
    "MB). Provisional: measured with an older version of the test, without cameras.")


def fast():
    """A fast frame: every task good on every page, a quick restart, cool, within its memory."""
    rep = run("s1cam3")
    for p in G.TIERS:
        rep["grade"]["bands"][p] = {"tap": "good", "scroll": "premium", "updates": "good", "load": "good",
                                    "camera": "none" if p == "light" else "good"}
        rep["graded"]["reference"]["tiers"][p]["scroll_limited_by"] = ["main-thread"]
    rep["grade"].update(start={"value": 2500.0, "band": "good"}, missing=[], provisional=False,
                        levels={p: "premium" for p in G.TIERS})
    rep["thermal"] = [{"phase": "refstart-1", "zones": {"cpu_thermal_zone": 41.0}}]
    return rep


class RealRuns(unittest.TestCase):
    def test_s1cam3(self):
        self.assertEqual(N.notes(run("s1cam3"))["text"], S1CAM3)

    def test_s1v7_provisional(self):
        self.assertEqual(N.notes(run("s1v7"))["text"], S1V7)

    def test_smoke11_one_run(self):
        self.assertEqual(N.notes(run("smoke11"))["text"], SMOKE11)

    def test_the_docs_show_what_the_code_writes(self):
        with open(os.path.join(HERE, "..", "..", "..", "docs", "perf.md")) as f:
            docs = f.read()
        for text in (S1CAM3, S1V7, SMOKE11):
            self.assertIn(text, docs)

    def test_the_same_report_gives_the_same_text(self):
        self.assertEqual(N.notes(run("s1cam3")), N.notes(run("s1cam3")))

    def test_never_the_letter_or_its_meaning(self):
        for name in ("s1cam3", "s1v7", "smoke11"):
            rep = run(name)
            text = N.notes(rep)["text"]
            self.assertNotIn(f"{G.NAME} {rep['grade']['grade']}", text)
            self.assertNotIn(rep["grade"]["meaning"], text)


class Shapes(unittest.TestCase):
    """The maintainer's rules (2026-11-13): at most 4 sentences and about 85 words (5 with the
    provisional note), strengths in one sentence, at most 3 limits with one number each, one cause."""

    def test_short(self):
        for name in ("s1cam3", "s1v7", "smoke11"):
            n = N.notes(run(name))
            body = [x for x in [n["good"], n["limits"]] + n["causes"] if x]
            with self.subTest(name):
                self.assertLessEqual(len(body), 4)
                self.assertLessEqual(len(" ".join(body).split()), 85)    # the maintainer's own example is 83

    def test_the_worst_three_limits_each_with_one_number(self):
        """s1cam3 fails five tasks. Each is quoted once, on the lightest page it fails on, and ranked by
        that number: live updates (heavy 0.7 s against 0.2 s), scroll (typical's slowest frames 117 ms
        against 67 ms) and open the view (typical 6.6 s against 4 s) beat the tap (heavy 290 ms
        against 200 ms) and the camera (typical 4.5 s against 4 s)."""
        limits = N.notes(run("s1cam3"))["limits"]
        self.assertEqual(limits, "Bigger dashboards slow down: typical scrolls at 25 fps and takes 6.6 s to open, "
                                 "and heavy takes 0.7 s to show a change.")
        self.assertNotIn("tap", limits)
        self.assertNotIn("camera", limits)

    def test_a_camera_limit_quotes_only_the_number_that_fails(self):
        """The verifier on #206: "first frame 4.2 s, 100% of frames shown" quoted a strength."""
        t = run("s1cam3")["graded"]["reference"]["tiers"]
        # Typical's failing runs (two of three) took 4.2 and 4.7 s; 100% shown passes, so it isn't quoted.
        self.assertEqual(N._limit("camera", t["typical"])[1], "takes 4.5 s to show its camera")
        heavy = N._limit("camera", t["heavy"])[1]
        self.assertTrue(heavy.startswith("stalls its camera for"), heavy)                          # 3.7 s first frame passes
        for r in t["heavy"]["camera_runs"]:
            r["stall_total_ms"] = 0
        self.assertEqual(N._limit("camera", t["heavy"])[1], "shows 35% of its camera's frames")

    def test_a_camera_limit_comes_from_the_failing_runs(self):
        """CodeRabbit on #206: separate medians can pass while the per-run levels fail."""
        t = {"camera_runs": [{"first_frame_ms": 4500, "shown_pct": 99, "stall_total_ms": 0},
                             {"first_frame_ms": 3000, "shown_pct": 80, "stall_total_ms": 0},
                             {"first_frame_ms": 3000, "shown_pct": 99, "stall_total_ms": 0}],
             "camera_levels": ["slow", "slow", "fluid"]}
        how_far, phrase = N._limit("camera", t)
        self.assertGreater(how_far, 1)
        self.assertEqual(phrase, "takes 4.5 s to show its camera")      # the worst failing number of one run

    def test_a_camera_at_its_stall_bar_quotes_the_stall(self):
        """CodeRabbit on #206: at 4 s, 85% and 1 s every ratio is 1.0, but only the stall fails (>=)."""
        t = {"camera_runs": [{"first_frame_ms": 4000, "shown_pct": 85, "stall_total_ms": 1000}] * 3,
             "camera_levels": ["slow"] * 3}
        self.assertEqual(N._limit("camera", t)[1], "stalls its camera for 1.0 s")

    def test_cameras_late_needs_all_the_evidence_in_one_run(self):
        """CodeRabbit on #206: slow arrivals from one run and decoder counts from another aren't evidence."""
        rep = run("s1cam3")
        runs = rep["graded"]["reference"]["tiers"]["heavy"]["camera_runs"]
        runs[0]["detail"] = []
        for r in runs[1:]:
            r["fetches"] = {}
        self.assertNotIn("cameras get video late", N.cause_sentence(rep) or "")
        rep = run("s1cam3")
        rep["graded"]["reference"]["tiers"]["heavy"]["camera_runs"][1]["fetches"]["segment"]["arrival_gap_max_ms"] = 900
        self.assertNotIn("cameras get video late", N.cause_sentence(rep) or "")   # one run with full evidence doesn't starve

    def test_no_camera_credit_on_a_page_without_one(self):
        rep = fast()
        rep["grade"]["levels"] = {"light": "premium", "typical": None, "heavy": None}
        good = N.notes(rep)["good"]
        self.assertTrue(good.startswith("Light dashboards feel good"))
        self.assertNotIn("camera", good)                                 # light has none

    def test_a_scroll_with_enough_frames_says_what_failed(self):
        self.assertIn("typical scrolls unevenly (slowest frames 80 ms)", N.notes(run("s1v7"))["limits"])

    def test_a_fast_frame_has_strengths_and_no_limits(self):
        n = N.notes(fast())
        self.assertEqual(n["good"], "Heavy dashboards feel good: taps, live updates, opening a view and the cameras are quick, "
                                    "and scrolling is smooth.")
        self.assertIsNone(n["limits"])
        self.assertEqual(n["causes"], ["Memory isn't the limit (889 of 988 MB)."])

    def test_without_a_level_the_closest_page(self):
        rep = run("s1cam3")
        rep["grade"]["levels"] = {p: None for p in G.TIERS}
        self.assertTrue(N.notes(rep)["good"].startswith("Light dashboards come closest:"))

    def test_a_refused_grade_says_why_and_nothing_else(self):
        rep = run("s1cam3")
        rep["grade"]["refused"] = "the panel ran at another zoom"
        self.assertEqual(N.notes(rep), {"good": None, "limits": None, "causes": [], "rubric": rep["grade"]["rubric_version"],
                                        "text": "Not graded: the panel ran at another zoom."})

    def test_nothing_good(self):
        rep = run("s1cam3")
        rep["grade"]["levels"] = {p: None for p in G.TIERS}
        for p in G.TIERS:
            rep["grade"]["bands"][p] = {k: "slow" if k in ("scroll", "updates", "camera") else "poor" for k in G.TASKS}
        n = N.notes(rep)
        self.assertIsNone(n["good"])
        self.assertTrue(n["limits"].startswith("It slows down:"))


class CausesNeedEvidence(unittest.TestCase):
    """The one cause sentence, and what makes each go away."""

    def cause(self, rep):
        return N.cause_sentence(rep) or ""

    def no_main(self, rep):
        for p in G.TIERS:
            rep["graded"]["reference"]["tiers"][p]["scroll_limited_by"] = ["equal"]
        return rep

    def test_main_thread_only_where_a_slow_scroll_says_so(self):
        self.assertIn("main thread", self.cause(run("smoke11")))
        self.assertEqual(self.cause(self.no_main(run("smoke11"))), "")
        self.assertEqual(self.cause(fast()), "")                        # main-thread, but scroll isn't a limit

    def test_busy_only_from_the_idle_cpu(self):
        rep = run("smoke11")
        rep["ram"]["reference_idle"]["cpu_app_pct_of_core"]["median"] = 12.0
        self.assertEqual(self.cause(rep), "The likely limit is the browser's main thread: the page draws too few frames.")

    def test_compositor_only_from_surfaceflinger(self):
        rep = self.no_main(run("smoke11"))
        rep["graded"]["reference"]["tiers"]["heavy"]["scroll_limited_by"] = ["compositor"]
        self.assertEqual(self.cause(rep), "The likely limit is the GPU: the screen drops frames the page drew.")

    def test_cameras_late_only_with_long_gaps_fast_fetches_and_a_decoder_that_keeps_up(self):
        def with_heavy(**seg):
            rep = run("s1cam3")
            for r in rep["graded"]["reference"]["tiers"]["heavy"]["camera_runs"]:
                r["fetches"]["segment"].update(seg)
            return rep
        self.assertIn("cameras get video late even though the hardware decoder keeps up", self.cause(run("s1cam3")))
        self.assertNotIn("cameras", self.cause(with_heavy(arrival_gap_max_ms=1200)))
        self.assertNotIn("cameras", self.cause(with_heavy(total_ms={"median": 300, "p95": 600, "max": 700})))
        rep = run("s1cam3")
        for r in rep["graded"]["reference"]["tiers"]["heavy"]["camera_runs"]:
            for s in r["detail"]:
                s["dropped"] = s["decoded"] // 10
        self.assertNotIn("cameras", self.cause(rep))
        rep = run("s1cam3")
        for r in rep["graded"]["reference"]["tiers"]["heavy"]["camera_runs"]:
            r["media"]["codecs"] = ["c2.android.avc.decoder"]
        self.assertIn("even though the decoder keeps up", self.cause(rep))   # not "hardware"

    def test_slow_fetches_point_at_the_link(self):
        rep = self.no_main(run("s1cam3"))
        self.assertNotIn("link", self.cause(rep))
        for r in rep["graded"]["reference"]["tiers"]["heavy"]["camera_runs"]:
            r["fetches"]["segment"]["total_ms"] = {"median": 800, "p95": 1500, "max": 2000}
        self.assertEqual(self.cause(rep), "The likely limit is the link: camera video takes a second or more to fetch.")

    def test_fake_lateness_only_over_its_limit(self):
        rep = self.no_main(run("smoke11"))
        self.assertEqual(self.cause(rep), "")                          # 20 ms late, under 200
        rep["fake_ha"]["stream_late_max_ms"] = 250
        self.assertIn("sent changes late", self.cause(rep))

    def test_no_sentence_for_heat_without_throttling_evidence(self):
        rep = run("s1cam3")
        rep["thermal"] = [{"phase": "refstart-1", "zones": {"cpu_thermal_zone": 70.0}}]
        self.assertNotIn("°C", N.notes(rep)["text"])

    def test_memory(self):
        rep = run("s1cam3")
        rep["ram"]["budget"]["checks"]["app_pss_peak"].update(value_kb=1_100_000, ok=False)
        self.assertEqual(N.memory_sentence(rep), "Memory is a limit: the app peaks at 1074 MB, over its 988 MB budget.")
        rep = run("s1cam3")
        rep["ram"]["budget"]["checks"]["device_available_min"].update(value_kb=200_000, ok=False)
        self.assertIn("free memory falls to 195 MB", N.memory_sentence(rep))
        rep = run("s1cam3")
        rep["ram"]["budget"]["checks"]["device_available_min"].update(value_kb=None, ok=None)
        self.assertEqual(N.memory_sentence(rep), "The app's memory peaks at 889 of 988 MB.")   # not "isn't the limit"
        rep["ram"] = None
        self.assertIsNone(N.memory_sentence(rep))

    def test_a_restart_only_when_it_caps_the_letter(self):
        self.assertNotIn("restart", N.notes(run("s1cam3"))["text"])    # poor, but it doesn't cap a D
        rep = run("s1cam3")
        rep["grade"]["capped_by_start"] = True
        self.assertTrue(N.notes(rep)["limits"].startswith("Bigger dashboards slow down: a restart takes 12 s, typical scrolls at 25 fps, and heavy"))

    def test_a_restart_that_wasnt_measured_is_counted(self):
        rep = run("s1cam3")
        rep["grade"].update(start={"value": None, "band": None}, missing=["start"], provisional=True)
        self.assertTrue(N.notes(rep)["text"].endswith("Provisional: 1 thing wasn't measured fully."))


class Provisional(unittest.TestCase):
    """One plain clause, from the run's protocols and what is missing; the report keeps the reasons."""

    def test_an_older_protocol_without_cameras(self):
        rep = run("s1v7")
        self.assertEqual(N.provisional_sentence(rep), "Provisional: measured with an older version of the test, without cameras.")
        self.assertNotIn("rubric is for protocol", N.notes(rep)["text"])
        self.assertIn("the pages ran protocol 4; this rubric is for protocol 5", rep["grade"]["provisional_reasons"])

    def test_other_reasons_and_missing_tasks_are_counted(self):
        rep = run("s1cam3")
        rep["grade"].update(provisional=True, missing=["heavy.updates"],
                            provisional_reasons=["1 of 3 graded runs failed or are missing", "the fake Home Assistant sent a live change 250 ms late"])
        self.assertEqual(N.provisional_sentence(rep), "Provisional: 3 things weren't measured fully.")
        rep = run("s1v7")
        rep["grade"]["provisional_reasons"].append("1 of 3 graded runs failed or are missing")
        self.assertEqual(N.provisional_sentence(rep),
                         "Provisional: measured with an older version of the test, without cameras, and 1 thing wasn't measured fully.")

    def test_a_newer_protocol_is_another_version(self):
        rep = run("s1v7")
        rep["graded"]["reference"]["protocols"] = [G.PROTOCOL_VERSION + 1]
        self.assertEqual(N.provisional_sentence(rep), "Provisional: measured with another version of the test, and 2 things weren't measured fully.")

    def test_not_provisional_says_nothing(self):
        self.assertIsNone(N.provisional_sentence(run("s1cam3")))


class InTheReport(unittest.TestCase):
    def test_thermal_tsv_is_read_in_order(self):
        with tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False) as f:
            f.write("start\tcpu_thermal_zone=41.8 gpu_thermal_zone=40.7 \nrefstart-1\tcpu_thermal_zone=48.9 \nend\t\n")
        self.assertEqual(R.read_thermal(f.name), [{"phase": "start", "zones": {"cpu_thermal_zone": 41.8, "gpu_thermal_zone": 40.7}},
                                                  {"phase": "refstart-1", "zones": {"cpu_thermal_zone": 48.9}},
                                                  {"phase": "end", "zones": {}}])
        self.assertEqual(R.read_thermal(f.name + ".missing"), [])

    def test_the_reference_idle_has_the_apps_cpu(self):
        import test_report as TR
        budgets = {"ram": {"app_pss_max_pct_of_ram": 50, "device_available_min_pct": 15}, "panes": {}}
        ram = R.reference_ram({"ref-idle": [TR.ram_idle("ref-idle", 300_000, cpu=c) for c in (40.0, 46.0, 50.0)]},
                              [], "sh.edm.s1kiosk", budgets, 2_000_000)
        self.assertEqual(ram["reference_idle"]["cpu_app_pct_of_core"]["median"], 46.0)

    def test_report_md_says_provisional_once(self):
        """The headline already gives the reasons; the notes under it leave their clause out."""
        rep = run("s1v7")
        md = R.markdown(dict(rep, detail={"synthetic": None}, real_use=None, apk=[]))
        self.assertEqual(md.count("Provisional"), 1, md[:1500])
        self.assertIn(S1V7.split(" Provisional:")[0], md)
        self.assertIn("Provisional: measured with an older version", N.notes(rep)["text"])     # the summary keeps it

    def test_under_the_headline_and_in_the_summary(self):
        rep = run("s1cam3")
        md = R.markdown(dict(rep, detail={"synthetic": None}, real_use=None, apk=[]))
        self.assertIn(f"**{rep['grade']['headline']}**\n\n{S1CAM3}\n", md)
        n = S.notes_summary(rep)
        self.assertEqual(set(n), {"good", "limits", "causes", "text", "rubric"})
        self.assertEqual(n["text"], S1CAM3)


if __name__ == "__main__":
    unittest.main()
