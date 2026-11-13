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
    "On light, taps, live updates and opening a view are good and scrolling is fluid; on typical, taps are good and "
    "live updates are fluid. Typical and heavy dashboards are held back by taps (288 ms on heavy), scrolling (25.0 fps "
    "on typical), live updates (739 ms to show on heavy), opening a view (6.6 s on typical) and the cameras (first "
    "frame 4.2 s, 100% of frames shown on typical); a restart takes 12.0 s to a usable dashboard, 9.4 s of it after "
    "the engine was ready. The page's main thread is the likely limit: on typical and heavy the page's own frames (rAF) "
    "come slower than the screen's; on heavy a change takes 739 ms to show; on heavy the camera player gets video up "
    "to 4.3 s apart though a fetch takes 162 ms (p95), while the hardware decoder drops 0 of 2460 frames; untouched on "
    "typical (its camera playing), the app keeps 97% of a core busy. The CPU was at up to 56 °C as a graded run "
    "started; clock speeds aren't recorded, so the run can't show whether it throttled. Memory is not the limit: the "
    "app's peak is 889 MB of 988 MB.")
S1V7 = (
    "Taps are good and live updates are at least fluid on every dashboard; on light, opening a view is good and "
    "scrolling is fluid. Typical and heavy dashboards are held back by scrolling (35.1 fps on typical) and opening a "
    "view (4.8 s on typical); a restart takes 11.7 s to a usable dashboard, 8.9 s of it after the engine was ready; "
    "the cameras weren't measured. The page's main thread is the likely limit: on typical and heavy the page's own "
    "frames (rAF) come slower than the screen's; untouched on typical, the app keeps 46% of a core busy. The CPU was "
    "at up to 62 °C as a graded run started; clock speeds aren't recorded, so the run can't show whether it "
    "throttled. Memory is not the limit: the app's peak is 743 MB of 988 MB. Provisional: the pages ran protocol 4; "
    "this rubric is for protocol 5.")
SMOKE11 = (
    "Live updates are at least fluid on every dashboard; on light, taps and opening a view are good and scrolling is "
    "fluid; on typical, taps are good. Typical and heavy dashboards are held back by taps (232 ms on heavy), scrolling "
    "(33.8 fps on typical) and opening a view (5.2 s on typical); a restart takes 11.8 s to a usable dashboard, 9.0 s "
    "of it after the engine was ready; the cameras weren't measured. The page's main thread is the likely limit: on "
    "typical and heavy the page's own frames (rAF) come slower than the screen's; untouched on typical, the app keeps "
    "46% of a core busy. Memory is not the limit: the app's peak is 748 MB of 988 MB. Provisional: the pages ran "
    "protocol 4; this rubric is for protocol 5.")


def fast():
    """A fast frame: every task good on every page, a quick restart, cool, within its memory."""
    rep = run("s1cam3")
    for p in G.TIERS:
        rep["grade"]["bands"][p] = {"tap": "good", "scroll": "premium", "updates": "good", "load": "good",
                                    "camera": "none" if p == "light" else "good"}
        rep["graded"]["reference"]["tiers"][p]["scroll_limited_by"] = ["main-thread"]
    rep["grade"].update(start={"value": 2500.0, "band": "good"}, missing=[], provisional=False)
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
            self.assertIn(S1CAM3, f.read())

    def test_the_same_report_gives_the_same_text(self):
        self.assertEqual(N.notes(run("s1cam3")), N.notes(run("s1cam3")))

    def test_never_the_letter_or_its_meaning(self):
        for name in ("s1cam3", "s1v7", "smoke11"):
            rep = run(name)
            text = N.notes(rep)["text"]
            self.assertNotIn(f"{G.NAME} {rep['grade']['grade']}", text)
            self.assertNotIn(rep["grade"]["meaning"], text)


class Shapes(unittest.TestCase):
    def test_a_fast_frame_has_strengths_and_no_limits(self):
        n = N.notes(fast())
        self.assertEqual(n["good"], "Taps, scrolling, live updates, opening a view and the cameras are good on every dashboard.")
        self.assertIsNone(n["limits"])
        self.assertEqual(n["causes"], ["Memory is not the limit: the app's peak is 889 MB of 988 MB."])

    def test_a_refused_grade_says_why_and_nothing_else(self):
        rep = run("s1cam3")
        rep["grade"]["refused"] = "the panel ran at another zoom"
        self.assertEqual(N.notes(rep), {"good": None, "limits": None, "causes": [], "rubric": rep["grade"]["rubric_version"],
                                        "text": "Not graded: the panel ran at another zoom."})

    def test_nothing_good(self):
        rep = run("s1cam3")
        for p in G.TIERS:
            rep["grade"]["bands"][p] = {k: "slow" if k in ("scroll", "updates", "camera") else "poor" for k in G.TASKS}
        self.assertIsNone(N.notes(rep)["good"])


class CausesNeedEvidence(unittest.TestCase):
    """Each cause sentence, and what makes it go away."""

    def causes(self, rep):
        return " ".join(N.notes(rep)["causes"])

    def test_main_thread_only_where_a_slow_scroll_says_so(self):
        rep = run("smoke11")
        self.assertIn("main thread", self.causes(rep))
        for p in G.TIERS:
            rep["graded"]["reference"]["tiers"][p]["scroll_limited_by"] = ["equal"]
        self.assertNotIn("main thread", self.causes(rep))
        self.assertNotIn("main thread", self.causes(fast()))          # main-thread, but scroll isn't a limit
        self.assertNotIn("% of a core", self.causes(rep))             # idle CPU alone isn't a cause

    def test_compositor_only_from_surfaceflinger(self):
        rep = run("smoke11")
        self.assertNotIn("compositor", self.causes(rep))
        rep["graded"]["reference"]["tiers"]["heavy"]["scroll_limited_by"] = ["compositor"]
        self.assertIn("Scrolling on heavy is limited by the compositor", self.causes(rep))

    def test_cameras_starve_only_with_long_gaps_fast_fetches_and_a_decoder_that_keeps_up(self):
        def with_heavy(**seg):
            rep = run("s1cam3")
            for r in rep["graded"]["reference"]["tiers"]["heavy"]["camera_runs"]:
                r["fetches"]["segment"].update(seg)
            return rep
        self.assertIn("camera player gets video", self.causes(run("s1cam3")))
        self.assertNotIn("camera player", self.causes(with_heavy(arrival_gap_max_ms=1200)))
        self.assertNotIn("camera player", self.causes(with_heavy(total_ms={"median": 300, "p95": 600, "max": 700})))
        rep = run("s1cam3")
        for r in rep["graded"]["reference"]["tiers"]["heavy"]["camera_runs"]:
            for s in r["detail"]:
                s["dropped"] = s["decoded"] // 10
        self.assertNotIn("camera player", self.causes(rep))
        rep = run("s1cam3")
        for r in rep["graded"]["reference"]["tiers"]["heavy"]["camera_runs"]:
            r["media"]["codecs"] = ["c2.android.avc.decoder"]
        self.assertIn("while the decoder drops", self.causes(rep))      # not "hardware"

    def test_slow_fetches_point_at_the_link(self):
        self.assertNotIn("link or the fake", self.causes(run("s1cam3")))
        rep = run("s1cam3")
        for r in rep["graded"]["reference"]["tiers"]["heavy"]["camera_runs"]:
            r["fetches"]["segment"]["total_ms"] = {"median": 800, "p95": 1500, "max": 2000}
        self.assertIn("Camera segments took up to 1.5 s to fetch on heavy", self.causes(rep))
        self.assertNotIn("camera player", self.causes(rep))

    def test_fake_lateness_only_over_its_limit(self):
        rep = run("s1cam3")
        self.assertNotIn("fake Home Assistant", self.causes(rep))      # 192 ms, under 200
        rep["fake_ha"]["stream_late_max_ms"] = 250
        self.assertIn("sent changes up to 250 ms late", self.causes(rep))

    def test_heat_only_when_a_graded_run_started_hot(self):
        self.assertIn("56 °C", self.causes(run("s1cam3")))
        self.assertNotIn("°C", self.causes(run("smoke11")))            # its graded run started at 48 °C
        rep = run("s1cam3")
        rep["thermal"] = [ph for ph in rep["thermal"] if not ph["phase"].startswith("refstart-")]
        self.assertNotIn("°C", self.causes(rep))                       # a hot end isn't a run starting hot
        rep["thermal"] = []
        self.assertNotIn("°C", self.causes(rep))

    def test_memory(self):
        rep = run("s1cam3")
        rep["ram"]["budget"]["checks"]["app_pss_peak"].update(value_kb=1_100_000, ok=False)
        self.assertIn("Memory is a limit: the app's peak is 1074 MB, over its 988 MB budget.", self.causes(rep))
        rep = run("s1cam3")
        rep["ram"]["budget"]["checks"]["device_available_min"].update(value_kb=200_000, ok=False)
        self.assertIn("available memory fell to 195 MB", self.causes(rep))
        rep["ram"] = None
        self.assertNotIn("Memory", self.causes(rep))

    def test_a_restart_that_wasnt_measured_says_so(self):
        rep = run("s1cam3")
        rep["grade"].update(start={"value": None, "band": None}, missing=["start"])
        self.assertTrue(N.notes(rep)["limits"].endswith("the restart wasn't measured."))

    def test_restart_only_when_it_isnt_good(self):
        self.assertIn("a restart takes 12.0 s", N.notes(run("s1cam3"))["limits"])
        self.assertIsNone(N.notes(fast())["limits"])


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

    def test_under_the_headline_and_in_the_summary(self):
        rep = run("s1cam3")
        md = R.markdown(dict(rep, detail={"synthetic": None}, real_use=None, apk=[]))
        self.assertIn(f"**{rep['grade']['headline']}**\n\n{S1CAM3}\n", md)
        n = S.notes_summary(rep)
        self.assertEqual(set(n), {"good", "limits", "causes", "text", "rubric"})
        self.assertEqual(n["text"], S1CAM3)


if __name__ == "__main__":
    unittest.main()
