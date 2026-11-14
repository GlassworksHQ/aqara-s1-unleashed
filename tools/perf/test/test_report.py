"""Parsers and report assembly on made-up device output (formats copied from Android 10)."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import grade as G  # noqa: E402
import perflib as P  # noqa: E402
import statistics  # noqa: E402
import report as R  # noqa: E402
import summary as S  # noqa: E402

PKG = "sh.edm.s1kiosk"

MEMINFO_PID = """\
** MEMINFO in pid 4242 [sh.edm.s1kiosk] **
        TOTAL   197058   136344    32692       30    41280    33711     7569

 App Summary
                       Pss(KB)
                        ------
           Java Heap:     9032
         Native Heap:    24792
                Code:    21972
               Stack:       28
            Graphics:    29916
       Private Other:    83296
              System:    28022

               TOTAL:   197058       TOTAL SWAP PSS:       30
"""

MEMINFO_ALL = """\
Total PSS by process:
    209,946K: sh.edm.s1kiosk:gpu_disable_art_image_ (pid 4300)
    199,998K: sh.edm.s1kiosk (pid 4242 / activities)
    185,648K: sh.edm.s1kiosk:sandboxed_tab_disable_art_image_24 (pid 4301)
     48,428K: sh.edm.s1kiosk:sandboxed_tab_disable_art_image_31 (pid 4302)
      8,331K: sh.edm.s1kiosk:crashhelper_disable_art_image_ (pid 4303)
      2,508K: otbr-agent (pid 900)

Total PSS by OOM adjustment:
"""

SF = "16666666\n0\t0\t0\n100\t1000000000\t990\n200\t1016666666\t1000\n300\t9223372036854775807\t0\n"


class Parsers(unittest.TestCase):
    def test_app_summary(self):
        m = P.parse_meminfo_app(MEMINFO_PID)
        self.assertEqual(m["java_heap_kb"], 9032)
        self.assertEqual(m["graphics_kb"], 29916)
        self.assertEqual(m["total_pss_kb"], 197058)

    def test_pss_by_process_and_roles(self):
        rows = P.parse_meminfo_by_process(MEMINFO_ALL)
        self.assertEqual(len(rows), 6)
        self.assertEqual(rows[1], ("sh.edm.s1kiosk", 4242, 199998))
        roles = [P.process_role(n, PKG) for n, _, _ in rows]
        self.assertEqual(roles, ["gpu", "main", "tab", "tab", "crashhelper", None])

    def test_surfaceflinger_latency_drops_unpresented_rows(self):
        period, presents = P.parse_sf_latency(SF)
        self.assertAlmostEqual(period, 16.666666)
        self.assertEqual(presents, [1000000000, 1016666666])
        self.assertEqual(P.presents_in(presents, 0.999, 1.01), [1000.0])

    def test_logcat_monotonic_and_ttid(self):
        rec = P.parse_logcat_line("  4945.980  1918  1990 I ActivityTaskManager: Displayed sh.edm.s1kiosk/.MainActivity: +1s714ms")
        self.assertEqual(rec["mono"], 4945.98)
        self.assertEqual(P.parse_displayed(rec["msg"], "sh.edm.s1kiosk/.MainActivity"), 1714)
        self.assertEqual(P.parse_displayed("Displayed sh.edm.s1kiosk/.MainActivity: +812ms", "sh.edm.s1kiosk/.MainActivity"), 812)
        self.assertIsNone(P.parse_displayed("Displayed other/.Main: +812ms", "sh.edm.s1kiosk/.MainActivity"))
        rec = P.parse_logcat_line('  12.5  4242  4250 I GeckoConsole: GWPERF {"t":"done","run":1}')
        self.assertEqual(P.gwperf_payload(rec["msg"]), {"t": "done", "run": 1})

    def test_pid_stat_with_spaces_in_comm(self):
        line = "4242 (Gecko Main) S 1 1 0 0 -1 1 2 3 4 5 470 15 1 1 10 -10"
        self.assertEqual(P.parse_pid_jiffies(line), 485)

    def test_cpu_percent(self):
        before = {"total": (100, 1000), "procs": {"app": 10}}
        after = {"total": (300, 2000), "procs": {"app": 60}}
        cpu = P.cpu_percent(before, after, ncpu=4)
        self.assertEqual(cpu["device_busy_pct"], 20.0)
        self.assertEqual(cpu["procs_pct_of_core"]["app"], 20.0)


def frames(start, n, every):
    return [start + every * i for i in range(n)]


class WorseOfBoth(unittest.TestCase):
    """Shapes from the S1 smoke run (2026-11-12): rAF and SurfaceFlinger disagree both ways."""

    def stepline(self, raf_every, raf_n, sf_every, sf_n, oldest=9_000):
        raf = P.frame_stats([raf_every] * raf_n, 16.667)
        return {"raf": raf, "sf": {"period_ms": 16.667, "window_ms": [10_000, 12_000], "oldest_ms": oldest,
                                   "presents_ms": frames(10_005, sf_n, sf_every)}}

    def test_compositor_drops_frames_the_main_thread_never_sees(self):
        f = R.step_frames(self.stepline(16.667, 118, 31.25, 64))  # css-transform n=128
        self.assertTrue(f["sf_valid"])
        self.assertEqual((f["source"], f["limited_by"]), ("surfaceflinger", "compositor"))
        self.assertEqual(f["stats"]["n"], 64)   # 63 gaps, plus the 26 ms one at the window's end

    def test_main_thread_jank_while_the_compositor_still_animates(self):
        f = R.step_frames(self.stepline(50.0, 40, 33.0, 61))  # reference load
        self.assertEqual((f["source"], f["limited_by"]), ("raf", "main-thread"))

    def test_a_stall_at_the_edge_of_the_window_counts(self):
        f = R.step_frames(self.stepline(16.667, 120, 16.667, 40))  # frames stop 650 ms into a 2 s window
        self.assertTrue(f["sf_valid"])
        self.assertEqual((f["source"], f["limited_by"]), ("surfaceflinger", "compositor"))
        self.assertGreater(f["stats"]["frozen_pct"], 0)   # the 1.35 s with nothing on screen

    def test_a_still_screen_during_an_animation_is_one_long_frame(self):
        f = R.step_frames(self.stepline(16.667, 120, 16.667, 0))
        self.assertTrue(f["sf_valid"])
        self.assertEqual(f["sf"]["max_ms"], 2000)

    def test_evicted_history_falls_back_to_raf(self):
        f = R.step_frames(self.stepline(16.667, 120, 16.667, 110, oldest=10_400))
        self.assertFalse(f["sf_valid"])
        self.assertEqual((f["source"], f["limited_by"]), ("raf", None))

    def test_equal_views_say_so(self):
        f = R.step_frames(self.stepline(16.667, 120, 16.667, 120))
        self.assertEqual((f["source"], f["limited_by"]), ("surfaceflinger", "equal"))


class StartOnTheS1(unittest.TestCase):
    """The S1 smoke run's cold start (2026-11-12): the system relaunched the home-app kiosk."""

    def test_start_and_load_split_the_whole_wait(self):
        stop = 12741.766
        c = {"launch": "relaunched-by-system", "ttid_ms": 111, "stop_mono": stop, "proc_mono": 12741.99,
             "displayed_mono": 12742.161, "page_start_mono": 12746.225, "page_stop_mono": 12752.024, "ended_quiet": True,
             "presents_mono_ms": [stop * 1000 + d for d in (10336, 10353, 10619, 10669, 10819, 10902, 10952, 11202, 11235, 11518, 14115)]}
        r = R.coldstart_run(c)
        self.assertEqual(r["ttid_ms"], 111)              # MainActivity's loading screen
        self.assertEqual(r["app_start_to_dashboard_ms"], 4235)   # process start to the dashboard's PageStart
        self.assertEqual(r["dashboard_load_ms"], 7059)
        self.assertEqual(r["stop_to_dashboard_ms"], 11518)
        self.assertEqual(r["app_start_to_dashboard_ms"] + r["dashboard_load_ms"], round((stop * 1000 + 11518) - 12741.99 * 1000))


class LiveCheck(unittest.TestCase):
    """The updates window counts only when what changes, and the toggle, are on screen."""

    def run_with(self, measure):
        run = refstart(1, 100)
        if measure is None:
            del run["tiers"]["typical"]["measure"]
        else:
            run["tiers"]["typical"]["measure"].update(measure)
        return R.reference_run(run)["tiers"]["typical"]

    def test_without_a_live_check_updates_are_not_measured(self):
        r = self.run_with(None)
        self.assertIsNone(r["updates"])
        self.assertEqual(r["updates_error"], "no live check (a page from before it was added)")
        self.assertEqual(r["inp_ms"], 100)   # the taps still count: nothing says the toggle was off screen

    def test_off_screen_voids_updates_and_taps(self):
        r = self.run_with({"offscreen": 3, "moving": 12})
        self.assertIsNone(r["updates"])
        self.assertIsNone(r["inp_ms"])
        self.assertIn("off screen (3 of 12)", r["updates_error"])

    def test_the_flag_not_the_sentence_voids_the_taps(self):
        self.assertEqual(R.live_check_failed({"offscreen": 1, "moving": 5})[1], True)
        self.assertEqual(R.live_check_failed({})[1], False)
        self.assertEqual(R.live_check_failed({"offscreen": 0, "moving": 5, "targets": 4}), (None, False))

    def test_no_targets_is_not_a_live_page(self):
        self.assertEqual(R.live_check_failed({"offscreen": 0, "moving": 2, "targets": 0}),
                         ("nothing on the page to update (0 targets)", False))

    def test_the_updates_must_have_run(self):
        """1667 ms at one update per 200 ms holds 8; one short is the window's edge, two short isn't."""
        check = {"offscreen": 0, "moving": 12, "targets": 12, "update_every_ms": 200}
        few = [c for c in CHANGES if c[0] not in (400.0, 800.0)]
        self.assertEqual(R.live_check_failed(check, updates_step(30.0, changes=CHANGES)), (None, False))
        self.assertEqual(R.live_check_failed(check, updates_step(30.0, changes=CHANGES[1:])), (None, False))
        self.assertEqual(R.live_check_failed(check, updates_step(30.0, changes=few)),
                         ("only 6 of 8 state updates ran in the window", False))
        clock_only = [c for c in CHANGES if c[3] == 1]
        self.assertEqual(R.live_check_failed(check, updates_step(30.0, changes=clock_only))[0],
                         "only 0 of 8 state updates ran in the window")


class ChangeFrames(unittest.TestCase):
    """Live updates are graded on the frames that had a change to draw."""

    def test_idle_frames_do_not_count(self):
        """smoke5 asked whether a light page's long rAF gaps were 'nothing to draw'. Slow frames before
        the first change don't count; the same number of slow frames during changes does."""
        idle = R.change_frames(updates_step(30.0, idle_slow=6), 16.667)[0]
        busy = R.change_frames(updates_step(30.0, missed=6), 16.667)[0]
        self.assertEqual(idle["jank_pct"], 0)
        self.assertGreater(busy["jank_pct"], 10)
        self.assertEqual(G.frame_band(idle), "good")
        self.assertNotEqual(G.frame_band(busy), "good")

    def test_each_transition_frame_counts(self):
        """A tile's 180 ms transition is about 11 frames, a text change one."""
        st, _ = R.change_frames(updates_step(30.0, changes=[[200.0, 200.5, 180]]), 16.667)
        self.assertIn(st["n"], (11, 12, 13))
        st, _ = R.change_frames(updates_step(30.0, changes=[[200.0, 200.5, 0]]), 16.667)
        self.assertEqual(st["n"], 1)

    def test_a_change_that_ran_late_counts_against_the_page(self):
        """Due at 200 ms, run at 290 ms: a busy main thread held it back. The long frame it was due in counts."""
        ts = [0.0, 16.7, 33.4, 183.4, 300.0, 316.7]
        st, _ = R.change_frames({"raf_times": ts, "changes": [[200.0, 290.0, 0]]}, 16.667)
        self.assertEqual(st["max_ms"], 116.6)
        self.assertEqual(G.frame_band(st), "poor")

    def test_without_a_change_log_updates_are_not_measured(self):
        self.assertEqual(R.change_frames({"raf": {"n": 99}}), (None, "no change log (a page from before it was added)"))
        self.assertEqual(R.change_frames({"raf_times": [0, 16.7], "changes": []})[1], "nothing changed during the window")

    def test_pacing_is_detail_and_change_to_screen_is_graded(self):
        r = R.reference_run(refstart(1, 100))["tiers"]["light"]
        self.assertGreater(r["updates_all"]["n"], 0)
        self.assertEqual(r["latency"]["n"], 9)
        self.assertLess(r["latency"]["p95_ms"], 60)
        self.assertEqual(G.update_level(r["updates_run"]), "premium")


PAGE = {"mono": 31.0, "epoch_ms": 1e12 + 1000, "origin_epoch_ms": 1e12}   # page time x -> mono 30000 + x


def latency_step(changes, presents_rel, raf_rel=None, oldest_rel=-500, window=4000):
    """An updates step at page time t0 = 4000 (mono 34000): changes [due, ran, d, kind, tick] and
    SurfaceFlinger presents, both in ms after t0."""
    w0 = 34000
    return {"t0": 4000.0, "window_ms": window, "changes": changes, "raf_times": raf_rel or [],
            "sf": {"period_ms": 16.667, "window_ms": [w0, w0 + window], "oldest_ms": w0 + oldest_rel,
                   "presents_ms": [w0 + x for x in presents_rel]}}


class ChangeToScreen(unittest.TestCase):
    """Rubric v5: from when the page applies a change to the first frame on screen that can show it."""

    def lat(self, *a, **k):
        return R.change_to_screen(latency_step(*a, **k), PAGE)

    def test_on_a_still_screen_the_next_frame_shows_it(self):
        """smoke6/s1v4: a tile fade from a still screen reached the screen 59 to 92 ms later."""
        st, why = self.lat([[100.0, 101.0, 180, 2, 108.0]], [175.0, 192.0])
        self.assertIsNone(why)
        self.assertEqual((st["n"], st["p95_ms"]), (1, 74.0))

    def test_a_frame_already_on_its_way_doesnt_count(self):
        """During a fade the next present can come 2 to 10 ms after the change: a frame drawn before
        it. The change is drawn at the next tick (+39) and can show one refresh later at the earliest."""
        st, _ = self.lat([[100.0, 100.0, 0, 0, 139.0]], [105.0, 139.0, 155.0, 172.0])
        self.assertEqual(st["p95_ms"], 55.0)

    def test_a_frame_started_before_the_change_ran_can_carry_it(self):
        """The timer ran inside the frame begun at +98, before its paint: that frame shows it."""
        st, _ = self.lat([[100.0, 101.0, 0, 0, 98.0]], [116.0, 133.0])
        self.assertEqual(st["p95_ms"], 15.0)

    def test_no_frame_for_250_ms_counts_as_at_least_that_late(self):
        st, _ = self.lat([[100.0, 100.0, 0, 0, 110.0], [3000.0, 3000.0, 0, 0, 3010.0]], [130.0], window=4000)
        self.assertEqual(sorted([st["median_ms"], st["p95_ms"]]), [30.0, 1000.0])
        self.assertEqual(G.update_level({"latency_n": st["n"], "latency_p95_ms": st["p95_ms"]}), "slow")

    def test_a_change_too_close_to_the_end_is_not_counted(self):
        st, _ = self.lat([[100.0, 100.0, 0, 0, 110.0], [3900.0, 3900.0, 0, 0, 3910.0]], [130.0], window=4000)
        self.assertEqual((st["n"], st["skipped"]), (1, 1))

    def test_a_change_older_than_surfaceflingers_history_is_not_counted(self):
        why = self.lat([[100.0, 100.0, 0, 0, 110.0], [200.0, 200.0, 0, 0, 210.0], [300.0, 300.0, 0, 0, 310.0]],
                       [330.0], oldest_rel=250)[1]
        self.assertEqual(why, "SurfaceFlinger covered only 1 of 3 changes")

    def test_protocol_1_pages_take_the_frame_from_the_raf_times(self):
        """Their changes have no tick; the next rAF frame after the change is the one it's drawn in."""
        st, _ = self.lat([[100.0, 101.0, 0, 0]], [140.0, 156.0], raf_rel=[90.0, 106.7, 123.4])
        self.assertEqual(st["p95_ms"], 39.0)

    def test_not_measured_without_a_change_log(self):
        self.assertEqual(R.change_to_screen({"raf": {}}, PAGE)[1], "no change log (a page from before it was added)")


class ReferenceLoad(unittest.TestCase):
    """The graded load is a number only when it could be measured; never 0 by default."""

    def test_settles_on_the_last_paint_before_a_quiet_gap(self):
        self.assertEqual(R.settle_before([100, 200, 400], 150, 3000), (400, None))

    def test_not_before_the_load_event(self):
        self.assertEqual(R.settle_before([100, 200], 1200, 3000), (1200, None))

    def test_a_pause_before_the_load_event_is_mid_load(self):
        self.assertEqual(R.settle_before([100, 1000, 1100], 1050, 3000), (1100, None))

    def test_no_frames_is_not_measured(self):
        self.assertEqual(R.settle_before([], 120, 3000), (None, "no frames on screen while it loaded"))

    def test_never_quiet_is_not_measured(self):
        self.assertEqual(R.settle_before([100 * i for i in range(30)], 120, 3000)[0], None)

    def run_with(self, **page):
        run = refstart(1, 100)
        run["tiers"]["light"]["page"].update(page)
        return R.reference_run(run)["tiers"]["light"]

    def test_no_load_event_is_not_measured(self):
        r = self.run_with(load_ms=None)
        self.assertNotIn("load_ms", r)
        self.assertEqual(r["load_error"], "no load event or first paint reported")

    def test_no_frames_on_screen_is_not_measured(self):
        run = refstart(1, 100)
        run["presents_mono_ms"] = []
        r = R.reference_run(run)["tiers"]["light"]
        self.assertNotIn("load_ms", r)

    def test_no_frame_after_the_first_paint_is_not_measured(self):
        r = self.run_with(fcp_ms=900)   # first paint after the last frame (400 ms)
        self.assertEqual(r["load_error"], "no frame on screen after its first paint")

    def with_frames(self, fcp_ms, load_ms, rel_frames):
        run = refstart(1, 100)
        run["tiers"]["light"]["page"].update(fcp_ms=fcp_ms, load_ms=load_ms)
        run["presents_mono_ms"] = [NAV["light"] * 1000 + d for d in rel_frames]
        return R.reference_run(run)["tiers"]["light"]

    def test_a_quiet_stretch_before_the_first_paint_reaches_the_screen_is_mid_load(self):
        """smoke5's light page, first after the kiosk's own dashboard: two frames of the old page, a
        second of nothing, then its first paint on screen 350 ms after the page's own first paint
        (907 ms), and frames until 1.75 s. The old rule found the quiet before the first paint and
        failed; it may not settle there."""
        burst = [1255 + 16.7 * i for i in range(30)]          # 1255 .. 1739
        r = self.with_frames(907, 896, [156, 256] + burst)
        self.assertEqual(r["load_ms"], 1739)
        self.assertEqual(r["paint_to_screen_ms"], 348)
        self.assertNotIn("load_error", r)

    def test_it_never_settles_at_the_load_event_before_the_first_paint_is_on_screen(self):
        """smoke5's heavy page: the old rule settled at its load event (1357 ms) while its first paint
        (1279 ms) reached the screen at 1360 ms and painted on until 2126 ms."""
        burst = [1360, 1410, 1427, 1444, 1477] + [1760 + 16.7 * i for i in range(23)]   # .. 2127
        r = self.with_frames(1279, 1357, [262] + burst)
        self.assertEqual(r["load_ms"], 2127)

    def test_the_first_paint_frame_may_be_stamped_a_little_early(self):
        """The clock mapping is a few ms off; a frame 30 ms before the mapped first paint shows it,
        but the load is never earlier than the first paint."""
        r = self.with_frames(400, 300, [370])
        self.assertEqual(r["load_ms"], 400)
        self.assertEqual(r["paint_to_screen_ms"], -30)
        self.assertEqual(self.with_frames(400, 300, [340])["load_error"], "no frame on screen after its first paint")

    def test_not_measured_load_leaves_the_grade_provisional(self):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "app": {"package": PKG}}, f)
        run = refstart(1, 100)
        run["presents_mono_ms"] = []
        with open(os.path.join(d, "refstart-1.json"), "w") as f:
            json.dump(run, f)
        g = R.build(d)["grade"]
        self.assertIn("light.load", g["missing"])
        self.assertTrue(g["provisional"])


class Settle(unittest.TestCase):
    def test_last_frame_before_a_quiet_gap(self):
        frames = [900, 1000, 1016, 1033, 1050, 2000]
        self.assertEqual(R.visual_settle(frames, 1000, ended_quiet=True), (1050, "quiet-gap"))

    def test_burst_that_ends_when_sampling_ends(self):
        self.assertEqual(R.visual_settle([1000, 1500, 2100], 1000, ended_quiet=True), (2100, "quiet-gap"))

    def test_constant_animation_falls_back_to_page_stop(self):
        frames = [1000 + 16.7 * i for i in range(600)]
        self.assertEqual(R.visual_settle(frames, 1000, ended_quiet=False), (1000, "no-quiet-gap"))

    def test_no_paint_after_page_stop(self):
        self.assertEqual(R.visual_settle([500], 1000, ended_quiet=True), (1000, "page-stop"))


def step(stage, n, p95_frames, run=1, sf=True):
    """A step line: 120 rAF frames, of which p95_frames missed one vsync."""
    ts = [0.0]
    for i in range(120):
        ts.append(ts[-1] + (33.333 if i < p95_frames else 16.667))
    raf = P.frame_stats(P.intervals(ts), 16.667)
    pl = {"t": "step", "stage": stage, "n": n, "run": run, "raf": raf}
    if sf:
        pl["sf"] = {"period_ms": 16.667, "window_ms": [5000, 5000 + ts[-1]], "oldest_ms": 3000,
                    "presents_ms": [5000 + t for t in ts]}
    return pl


def synthetic_lines(run, js_ms):
    lines = [{"t": "hello", "run": run, "ua": "Mozilla/5.0 (Android 10; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0",
              "load": {"lcp_ms": None, "fcp_ms": 250, "lcp_from": "fcp"} if run == 2 else {"lcp_ms": 300, "fcp_ms": 250, "lcp_from": "lcp"}}]
    lines.append({"t": "js", "run": run, "median_ms": js_ms, "p95_ms": js_ms * 1.5, "checksum": 7, "expected": 7})
    for n, missed in [(1, 0), (2, 0), (4, 2), (8, 20), (16, 90)]:
        lines.append(step("css-transform", n, missed, run))
    for n in (1, 2):
        lines.append(step("svg", n, 0, run))
    sc = step("scroll", 240, 0, run, sf=False)
    sc["scrolled_px"] = 0 if run == 3 else 720
    lines.append(sc)
    lines.append({"t": "done", "run": run})
    return lines


# When each reference dashboard starts, s; typical without its camera (rubric v10) after heavy, so no
# two views' frames meet.
NAV = {"light": 30.0, "typical": 40.0, "heavy": 50.0, "typical-nocam": 70.0}
CARDS = {"light": 20, "typical": 50, "heavy": 120, "typical-nocam": 50}


def swipe(nav, i, intervals, moved=True, sf=True):
    """One swipe's step line: its window starts 5 + i s after navigation."""
    w0 = (nav + 5.0 + i) * 1000
    ts = [0.0]
    for x in intervals:
        ts.append(ts[-1] + x)
    pl = {"t": "step", "stage": "scroll", "n": i, "window_ms": round(ts[-1]), "scrolled_px": 700 if moved else 0,
          "first_scroll_ms": 40, "raf": P.frame_stats(intervals, 16.667), "raf_intervals": [round(x, 1) for x in intervals]}
    pl["sf"] = {"period_ms": 16.667, "window_ms": [w0, w0 + ts[-1]], "oldest_ms": (w0 - 500) if sf else (w0 + 1),
                "presents_ms": [w0 + t for t in ts]}
    return pl


def late(n, missed, slow=33.333):
    """n frame intervals, the first `missed` of them slow."""
    return [slow if k < missed else 16.667 for k in range(n)]


# The live updates as the page logs them: [due, ran, transition ms, kind (0 state, 1 clock)], every 200 ms; a tile (180 ms)
# and a row (0) in turn, and the clock at 1000 ms.
CHANGES = [[200.0 * k, 200.0 * k + 1.0, 180 if k % 2 else 0, 0] for k in range(1, 9)] + [[1000.0, 1000.5, 0, 1]]


def updates_step(nav, missed=0, slow=33.333, idle_slow=0, changes=CHANGES, window=1667, present_after=None):
    """The live-updates step: rAF frames over the window, the first `missed` frames that had a change
    to draw are `slow`; `idle_slow` frames with nothing to draw (at the window's start, before the
    first change) are slow too. It goes live 3 s after navigation and measures 1 s later."""
    ts, k_miss, k_idle = [0.0], 0, 0
    while ts[-1] < window:
        a = ts[-1]
        busy = any(c[0] < a + 16.667 and c[1] + c[2] >= a for c in changes)
        if busy and k_miss < missed:
            x, k_miss = slow, k_miss + 1
        elif not busy and a < 150 and k_idle < idle_slow:
            x, k_idle = slow, k_idle + 1
        else:
            x = 16.667
        ts.append(a + x)
    w0 = (nav + 4.0) * 1000
    return {"t": "step", "stage": "updates", "n": 1, "window_ms": window, "t0": 4000.0,
            "raf": P.frame_stats(P.intervals(ts), 16.667), "raf_times": [round(t, 1) for t in ts],
            "changes": changes,
            "sf": {"period_ms": 16.667, "window_ms": [w0, w0 + window], "oldest_ms": w0 - 500,
                   "presents_ms": ([w0 + t + 33.3 for t in ts] if present_after is None
                                   else sorted(w0 + c[1] + present_after for c in changes))}}


def tier_entry(tier, inp, scroll_missed=0, updates_missed=0, load_ms=200, fcp_ms=150, dpr=1.5):
    """One reference dashboard in a graded run. Its page logs 1 s after navigation and goes live 3 s
    after it; three swipes of 60 frames follow."""
    nav = NAV[tier]
    return {"nonce": tier,
            "page": {"t": "refdash", "tier": tier, "protocol": G.PROTOCOL_VERSION, "clarity": G.CLARITY, "nodes": 1458, "cards": CARDS[tier], "css": [960, 480], "dpr": dpr,
                     "fcp_ms": fcp_ms, "load_ms": load_ms,
                     "origin_epoch_ms": 1e12 + nav * 1000, "epoch_ms": 1e12 + nav * 1000 + 1000, "mono": nav + 1.0},
            "live_mono": nav + 3.0,
            "measure": {"t": "measure", "stage": "updates", "moving": 12, "offscreen": 0, "targets": 10, "update_every_ms": 200},
            "step": updates_step(nav, updates_missed),
            "input": {"t": "input", "method": "event-timing", "taps": 10, "inp_ms": inp},
            "camera": camera_line(tier),
            "swipes": [swipe(nav, i, late(60, scroll_missed)) for i in range(3)]}


class CameraStreamsAll(unittest.TestCase):
    """The camera task counts every stream the view shows: heavy with 3 of its 4 streams playing (one
    never showed a frame, or wasn't found at all) fails it, though the 3 played well (#182's verifier)."""

    def test_three_of_four_fail(self):
        played = R.camera_run(camera_line("heavy"), "heavy")
        self.assertEqual(G.camera_level(played), "good")
        dead = camera_line("heavy")
        dead["streams"][3].update(first_frame_ms=None, shown_pct=None, frames=0, decoded=0)
        run = R.camera_run(dead, "heavy")
        self.assertIsNone(run["first_frame_ms"])
        self.assertEqual(G.camera_level(run), G.BELOW)
        missing = camera_line("heavy")
        missing["streams"] = missing["streams"][:3]
        missing["found"] = 3
        run = R.camera_run(missing, "heavy")
        self.assertEqual((run["streams"], run["expected"]), (3, 4))
        self.assertEqual(G.camera_level(run), G.BELOW)


class CameraFailedRuns(unittest.TestCase):
    """CodeRabbit on cbc2be6: the numbers are from the runs whose streams all played, and a run with a
    stream that showed no frame was hidden whenever another run played. Both outputs say how many."""

    def test_a_failed_run_is_said(self):
        ok = R.camera_run(camera_line("typical"), "typical")
        dead = camera_line("typical")
        dead["streams"][0].update(first_frame_ms=None, shown_pct=None)
        s_ = {"camera_runs": [ok, R.camera_run(dead, "typical"), ok]}
        self.assertIn("1 of 3 run(s) had a stream that showed no frame", R.camera_cell(s_, {"camera": "slow"}))
        self.assertIn("(1 of 3 run(s) had a stream that showed no frame)", R.task_phrase("camera", s_))
        self.assertNotIn("showed no frame", R.camera_cell({"camera_runs": [ok, ok]}, {"camera": "good"}))


class TimedOut(unittest.TestCase):
    """#202, s1rtc3: a view that didn't finish within the runner's deadline fails its own unmeasured
    tasks, measured: open and tap at the deadline, the rest below the lowest bar. Not missing."""

    def test_a_view_that_never_became_usable_fails_every_task(self):
        run = R.tier_run("heavy", {"nonce": "ab12", "swipes": [], "timed_out_s": 300}, [])
        self.assertNotIn("error", run)
        self.assertEqual((run["load_ms"], run["inp_ms"]), (300000, 300000))
        self.assertEqual(run["timed_out_tasks"], ["open", "tap", "scroll", "updates", "camera"])
        self.assertEqual(G.scroll_level(run["scroll"]), G.BELOW)
        self.assertEqual(G.update_level(run["updates_run"]), G.BELOW)
        self.assertEqual(G.camera_level(run["camera_run"]), G.BELOW)
        self.assertIsNone(R.tier_run("light", {"nonce": "ab12", "swipes": [], "timed_out_s": 300}, [])["camera_run"])  # no streams

    def test_report_md_says_the_view_didnt_finish(self):
        ok = R.camera_run(camera_line("typical"), "typical")
        late = R.tier_run("typical", {"nonce": "ab12", "swipes": [], "timed_out_s": 300}, [])["camera_run"]
        cell = R.camera_cell({"camera_runs": [ok, late, ok]}, {"camera": "slow"})
        self.assertIn("the view didn't finish in 1 of 3 run(s)", cell)
        self.assertNotIn("showed no frame", cell)
        self.assertEqual(R.camera_cell({"camera_runs": [late]}, {"camera": "slow"}), "the view didn't finish in 1 of 1 run(s), slow")


class CpuClock(unittest.TestCase):
    """#244: the CPU clock each view ran at, as detail (the S1's power HAL held 1.2 of 1.608 GHz)."""

    def test_per_view_clock_and_the_top(self):
        runs = []
        for k in (1, 2, 3):
            r = refstart(k, 100)
            r["cpu_max_khz"] = [1608000] * 4
            r["cpu_khz"] = [["light", 1.0, [1200000] * 4], ["heavy", 2.0, [1200000, 1200000, 1008000, 1608000]]]
            runs.append(R.reference_run(r))
        ref = R.reference_summary(runs)
        self.assertEqual(ref["tiers"]["light"]["cpu_mhz"], {"median": 1200.0, "min": 1200.0, "max": 1200.0, "n": 12})
        self.assertEqual((ref["tiers"]["heavy"]["cpu_mhz"]["min"], ref["tiers"]["heavy"]["cpu_mhz"]["max"]), (1008.0, 1608.0))
        self.assertIsNone(ref["tiers"]["typical"]["cpu_mhz"])
        self.assertEqual(ref["cpu_max_mhz"], 1608.0)
        # And without samples (a run from before): nothing.
        self.assertIsNone(R.reference_summary([R.reference_run(refstart(1, 100))])["cpu_max_mhz"])


class StallsNotReported(unittest.TestCase):
    """#205's verifier: a receiver that reports no freezeCount has stalls n/a, never 0 (which would
    pass the bars), and that run's camera level is not measured."""

    def test_na_not_zero(self):
        line = camera_line("typical")
        for st in line["streams"]:
            st.update(stall_total_ms=None, max_gap_ms=None, gaps_source="webrtc (no freeze counts: n/a)")
        run = R.camera_run(line, "typical")
        self.assertEqual((run["stall_total_ms"], run["max_gap_ms"], run["stalls_reported"]), (None, None, False))
        self.assertIsNone(G.camera_level(run))
        ok = R.camera_run(camera_line("typical"), "typical")
        self.assertNotIn("stalls_reported", ok)
        self.assertEqual(G.camera_level(ok), "good")
        # No span to measure gaps over (one sample, nothing decoded): max_gap null, also n/a.
        line = camera_line("typical")
        line["streams"][0].update(max_gap_ms=None)
        self.assertEqual(R.camera_run(line, "typical")["stalls_reported"], False)


class CameraDetail(unittest.TestCase):
    """Detail for the camera task (smoke-cam1, 2026-11-13): which decoders a view got and where its
    segments were late, so a slow camera can be told apart: the decoder, the link or the page."""

    def test_the_decoders_and_fetches_reach_report_md(self):
        fetches = {"segment": {"n": 120, "queued_ms": {"median": 2, "p95": 900, "max": 1500},
                               "wait_ms": {"median": 10, "p95": 40, "max": 80},
                               "total_ms": {"median": 30, "p95": 1100, "max": 2000}, "kb": 4000, "arrival_gap_max_ms": 5200},
                   "playlist": {"n": 130, "queued_ms": None, "wait_ms": None, "total_ms": None}, "streams": 4}
        line = dict(camera_line("heavy"), fetches=fetches)
        c = {"proc_mono": 20.0, "tiers": {"heavy": dict(tier_entry("heavy", 100), camera=line)},
             "media": {"heavy": {"codecs": ["OMX.allwinner.video.decoder.avc"] * 4, "flushes": 117, "eos": 43}}}
        run = R.reference_run(c)["tiers"]["heavy"]["camera_run"]
        self.assertEqual((run["fetches"]["segment"]["n"], run["media"]["flushes"]), (120, 117))
        ref = {"tiers": {"heavy": {"camera_runs": [run]}}}
        md = R.camera_detail(ref)
        self.assertEqual(md, ["heavy cameras, run 1 (detail): decoders 4 (OMX.allwinner.video.decoder.avc), 117 flushes, "
                              "43 drains; 120 segments, fetch p95 1100 ms (queued p95 900 ms), longest gap between a "
                              "stream's segments 5200 ms."])
        self.assertEqual(R.camera_detail({"tiers": {"heavy": {"camera_runs": [dict(run, media=None, fetches=None)]}}}), [])

    def test_frames_presented_inside_the_stalls_are_said(self):
        line = camera_line("heavy", stall=2500.0, max_gap=1200.0)
        for k, st in enumerate(line["streams"]):
            st["stall_presented"] = 30 if k == 0 else 0
        run = R.camera_run(line, "heavy")
        self.assertEqual(run["detail"][0]["stall_presented"], 30)
        self.assertEqual(run["stall_total_ms"], 2500.0)               # graded as before
        self.assertEqual(R.camera_detail({"tiers": {"heavy": {"camera_runs": [run]}}}),
                         ["heavy cameras, run 1 (detail): 30 frames presented inside the stalls (the callbacks', not the stream's)."])

    def test_the_headline_names_the_camera_not_the_view_again(self):
        """smoke-cam1's headline read "the view takes 6.6 s to open" twice: camera had no phrase of its
        own and fell through to the open-the-view one."""
        s_ = {"load_ms": {"median": 6620}, "camera_runs": [{"first_frame_ms": 4405.1, "shown_pct": 100.0}]}
        self.assertEqual(R.task_phrase("camera", s_), "a camera takes 4.4 s to show its first frame and shows 100% of its frames")
        self.assertEqual(R.task_phrase("camera", {"camera_runs": [{"first_frame_ms": None, "shown_pct": None}]}),
                         "a camera never showed a frame")


class Transport(unittest.TestCase):
    """Protocol 7 (#202, option B): report.json carries how the camera video went, and report.md says
    it beside the camera results; over adb, heavy's camera result is marked as possibly the link's."""

    def write(self, transport, chosen_by="auto", reason="r"):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "transport.json"), "w") as f:
            json.dump({"transport": transport, "chosen_by": chosen_by, "reason": reason, "checks": {}}, f)
        return d

    def test_the_record_and_its_words(self):
        heavy = {"tiers": {"heavy": {"camera_runs": [{"first_frame_ms": 600}]}}}
        adb = R.webrtc_transport(self.write("adb", reason="the panel couldn't reach this computer over the LAN"), heavy)
        self.assertEqual((adb["transport"], adb["chosen_by"]), ("adb", "auto"))
        self.assertIn("may be the link's, not the panel's", adb["limited"])
        line = R.transport_line({"webrtc_transport": adb})[0]
        self.assertTrue(line.startswith("Camera video through the test's adb link (USB): the panel couldn't reach"))
        self.assertIn("**Possibly limited by the test:**", line)
        lan = R.webrtc_transport(self.write("lan-udp", reason="the panel reached this computer over UDP"), heavy)
        self.assertIsNone(lan["limited"])
        self.assertEqual(R.transport_line({"webrtc_transport": lan}),
                         ["Camera video over the LAN (UDP), as in a real home: the panel reached this computer over UDP."])
        flag = R.webrtc_transport(self.write("lan-turn", "flag", "set with --webrtc-transport"), heavy)
        self.assertEqual(R.transport_line({"webrtc_transport": flag}),
                         ["Camera video over the LAN (TCP, through the test's relay): set with --webrtc-transport."])
        # Before protocol 7 (no transport.json), or no reference part: nothing.
        self.assertIsNone(R.webrtc_transport(tempfile.mkdtemp(), heavy))
        self.assertIsNone(R.webrtc_transport(self.write("adb"), None))
        self.assertEqual(R.transport_line({"webrtc_transport": None}), [])
        # Over adb without heavy cameras measured: nothing to caveat.
        self.assertIsNone(R.webrtc_transport(self.write("adb"), {"tiers": {"heavy": {}}})["limited"])


class CameraFallback(unittest.TestCase):
    """#202: the graded camera task is the WebRTC path's; HLS, on the camera pages' copies, is
    measured as detail and never moves the level or the letter."""

    def test_the_fallback_is_detail_beside_the_graded_path(self):
        runs = [R.reference_run(refstart(k, 100)) for k in (1, 2, 3)]
        ref = R.reference_summary(runs)
        heavy = ref["tiers"]["heavy"]
        self.assertEqual({r["path"] for r in heavy["camera_runs"]}, {"web_rtc"})
        self.assertEqual(heavy["camera_level"], "good")
        fb = heavy["camera_fallback"]
        self.assertEqual((fb["path"], fb["level"], fb["levels"]), ("hls", G.BELOW, [G.BELOW] * 3))
        self.assertEqual([(r["view"], r["shown_pct"]) for r in fb["runs"]], [("heavy-hls", 35.0)] * 3)
        self.assertEqual(ref["tiers"]["typical"]["camera_fallback"]["level"], "good")
        self.assertIsNone(ref["tiers"]["light"]["camera_fallback"])
        # The same runs without the copies grade the same: the fallback is never in the grade.
        bare = [R.reference_run(dict(refstart(k, 100), fallback={})) for k in (1, 2, 3)]
        self.assertEqual(G.grade(*R.grade_inputs({"reference": ref})),
                         G.grade(*R.grade_inputs({"reference": R.reference_summary(bare)})))
        md = R.camera_detail(ref)
        self.assertIn("heavy cameras over HLS, the fallback (detail, not graded; slow by the camera bars): run 1: first frame "
                      "3700 ms, 35 % shown, stalls 26600 ms, slow", "\n".join(md))

    def test_a_failed_copy_fails_only_itself(self):
        c = dict(refstart(1, 100), fallback={"heavy-hls": {"error": "heavy-hls didn't finish within 900 s"}},
                 fallback_error="heavy-hls didn't finish within 900 s")
        run = R.reference_run(c)
        self.assertIsNone(run["error"])
        self.assertEqual(run["camera_fallback"]["heavy"]["error"], "heavy-hls didn't finish within 900 s")
        self.assertIsNone(run["camera_fallback"]["heavy"]["first_frame_ms"])

    def test_webrtc_detail_names_the_decoder_and_the_relay(self):
        line = camera_line("typical")
        line["streams"][0]["webrtc"] = {"inbound": True, "frames_decoded": 290, "frames_dropped": 3, "freezes": 1, "freeze_s": 0.6,
                                        "decoder": "libvpx", "power_efficient": False,
                                        "candidate": {"local": "relay", "relay": "tcp", "protocol": "udp"}}
        run = R.camera_run(line, "typical")
        self.assertEqual(R.webrtc_detail(run), "decoder libvpx (software), 290 frames decoded, 3 dropped, 1 freezes (0.6 s), "
                                               "candidate relay/tcp")
        self.assertEqual(R.webrtc_detail(R.camera_run(camera_line("typical", path="hls"), "typical")), "")
        # A camera that needed another session (the S1's heavy, smoke3): said, with its time without video.
        again = camera_line("typical")
        again["streams"][0]["webrtc"] = {"inbound": True, "frames_decoded": 200, "sessions": 1, "no_video_ms": 31000,
                                         "connections": [{"n": 3, "made_ms": 900, "offers": 2, "ice": [[2000, "connected"], [33000, "failed"]]}]}
        self.assertTrue(R.webrtc_detail(R.camera_run(again, "typical")).endswith(
            "1 of 1 streams needed another WebRTC session (no video for 31.0 s in all)"))
        # Counters the browser didn't report read n/a, never 0; a partial sum says so.
        bare = camera_line("heavy")
        for k, st in enumerate(bare["streams"]):
            st["webrtc"] = {"inbound": True, "frames_decoded": 100, "frames_dropped": None if k else 2,
                            "freezes": None, "freeze_s": None, "decoder": None, "power_efficient": None, "candidate": None}
        self.assertEqual(R.webrtc_detail(R.camera_run(bare, "heavy")),
                         "decoder not reported, 400 frames decoded, 2 (from 1 of 4 streams) dropped, n/a freezes (n/a s), candidate n/a")
        # Protocol 5's lines don't name a path: HLS was the only one.
        old = camera_line("typical")
        del old["path"]
        self.assertEqual(R.camera_run(old, "typical")["path"], "hls")


def camera_line(tier, first_ms=600.0, shown=100.0, max_gap=70.0, stall=0.0, path="web_rtc"):
    """The harness's camera line (#174) for a view with streams: each plays at once and in full, over
    WebRTC (protocol 6) unless `path` says otherwise."""
    n = {"typical": 1, "heavy": 4}.get(tier, 0)
    if not n:
        return None
    return {"t": "camera", "stage": tier, "path": path, "window_ms": 20000, "fps": 15, "expected": n, "found": n,
            "streams": [{"found": True, "w": 1280 if i == 0 else 640, "h": 720 if i == 0 else 360, "first_frame_ms": first_ms,
                         "shown_pct": shown, "max_gap_ms": max_gap, "stall_total_ms": stall, "frames": 300, "decoded": 300,
                         "dropped": 0, "waiting": 0, "source": "quality+rvfc"} for i in range(n)]}


def load_frames():
    """Each reference dashboard paints at 0.2, 0.3 and 0.4 s after navigation, then stays still."""
    return [NAV[t] * 1000 + d for t in NAV for d in (200, 300, 400)]


def refstart(k, inp, heavy_missed=20):
    """A graded run: process at 20.0 s, engine ready 0.6 + 0.1k s later. Light and typical scroll
    smoothly; heavy misses `heavy_missed` of every 60 frames (20: about 45 fps, fluid, not smooth)."""
    return {"run": k, "ttid_ms": 111, "launch": "relaunched-by-system", "proc_mono": 20.0, "engine_mono": 20.6 + 0.1 * k,
            "engine_uri": "about:blank", "own_page_start_mono": 23.0, "own_page_stop_mono": 26.0, "own_page_loads": 1,
            "own_page_state": "finished-still",
            "tiers": {"light": tier_entry("light", inp), "typical": tier_entry("typical", inp),
                      "typical-nocam": tier_entry("typical-nocam", inp),
                      "heavy": tier_entry("heavy", inp, scroll_missed=heavy_missed)},
            # Protocol 6: the camera pages' HLS copies after them (#202, detail); heavy's starves.
            "fallback": {"typical-hls": {"done": True, "camera": camera_line("typical", path="hls")},
                         "heavy-hls": {"done": True, "camera": camera_line("heavy", first_ms=3700.0, shown=35.0,
                                                                            max_gap=4600.0, stall=26600.0, path="hls")}},
            "presents_mono_ms": load_frames()}


def idle(label, run, app_pss, cpu_app):
    return {"label": label, "run": run, "window_s": 15, "ncpu": 4,
            "cpu": {"device_busy_pct": 20, "procs_pct_of_core": {PKG: cpu_app, PKG + ":gpu": 1.0, "otbr-agent": 0.5}},
            "frames": {"period_ms": 16.667, "presents_mono_ms": [1000, 2000, 3000]},
            "processes": {
                PKG: {"pid": 1, "role": "main", "pss_kb": app_pss - 100_000, "rss_peak_kb": 300_000, "summary": {"java_heap_kb": 9000}},
                PKG + ":gpu": {"pid": 2, "role": "gpu", "pss_kb": 100_000, "rss_peak_kb": 150_000},
                "otbr-agent": {"pid": 3, "role": "radio", "pss_kb": 2500, "rss_peak_kb": 2500},
            },
            "device_mem": {"mem_total_kb": 2_000_000, "mem_available_kb": 900_000}}


def write_fake_log(d, extra=()):
    """fakeha.jsonl as run.sh leaves it: the frontend's commands, the stream sent on time."""
    with open(os.path.join(d, "fakeha.jsonl"), "w") as f:
        for e in [{"t": 1.0, "type": "auth/current_user", "keys": ["id", "type"], "msg": {"id": 1, "type": "auth/current_user"}},
                  {"t": 1.1, "stream": "S", "k": 1, "late_ms": 0.4}, *extra]:
            f.write(json.dumps(e) + "\n")


class Build(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        dev = {"frame": "test-frame", "model": "Panel", "android": "10", "api": 29, "abi": "armeabi-v7a",
               "cpu": {"cores": 4}, "mem_total_kb": 2_000_000, "display": {"vsync_ms": 16.667, "density": "240"},
               "app": {"package": PKG, "version_name": "0.4.3"}}
        self.write("device.json", dev)
        for k, (inp, js) in enumerate([(120, 9.0), (180, 10.0), (150, 12.0)], 1):
            with open(os.path.join(self.dir, f"synthetic-{k}.jsonl"), "w") as f:
                f.write("\n".join(json.dumps(l) for l in synthetic_lines(k, js)))
            self.write(f"coldstart-{k}.json", {"run": k, "ttid_ms": 1700 + 100 * k, "am_total_ms": None,
                                                "launch": "relaunched-by-system", "stop_mono": 9.5, "proc_mono": 9.6 + 0.1 * k,
                                                "page_start_mono": 10.0, "page_stop_mono": 13.0, "ended_quiet": True,
                                                "presents_mono_ms": [13000, 13100, 13200 + 100 * k, 15000]})
            self.write(f"refstart-{k}.json", refstart(k, inp))
        # Panes alternate on, off, on, off, ...
        for k, (on, off) in enumerate([(600_000, 560_000), (602_000, 559_000), (601_000, 561_000)], 1):
            self.write(f"idle-all-on-{k}.json", idle("all-on", k, on, 10.0 + k * 0.1))
            self.write(f"idle-sendspin-off-{k}.json", idle("sendspin-off", k, off, 8.0))
        self.write("idle-assist-off-1.json", idle("assist-off", 1, 600_500, 10.1))
        write_fake_log(self.dir)
        self.rep = R.build(self.dir)

    def write(self, name, obj):
        with open(os.path.join(self.dir, name), "w") as f:
            json.dump(obj, f)

    def test_schema_and_frame(self):
        self.assertEqual(self.rep["schema"], "glassworks.perf/4")
        self.assertEqual(self.rep["frame"]["id"], "test-frame")
        self.assertEqual(self.rep["frame"]["app"]["geckoview"], "159.0")

    def test_animation_capacity(self):
        lad = self.rep["detail"]["synthetic"]["ladder"]["css-transform"]
        self.assertEqual(lad["held_60fps"]["median"], 4)   # 2 of 120 over budget is 1.7 %: still good
        self.assertEqual(lad["held_30fps"]["median"], 8)
        self.assertFalse(lad["held_60fps_at_top"])
        steps = self.rep["detail"]["synthetic"]["per_run"][0]["ladder"]["css-transform"]["steps"]
        self.assertEqual([st["n"] for st in steps], [1, 2, 4, 8, 16])   # element counts, not frame counts
        self.assertEqual(steps[0]["frames"]["n"], 120)
        svg = self.rep["detail"]["synthetic"]["ladder"]["svg"]
        self.assertEqual(svg["held_60fps"]["median"], 2)
        self.assertTrue(svg["held_60fps_at_top"])   # never missed: a lower bound
        self.assertNotIn("canvas2d", self.rep["detail"]["synthetic"]["ladder"])

    def test_summary_carries_the_engine_uri_and_sf_valid(self):
        ref = self.rep["graded"]["reference"]
        self.assertEqual(ref["engine_uris"], ["about:blank"])
        self.assertEqual(ref["tiers"]["heavy"]["sf_valid"], [True, True, True])

    def test_tasks_per_page(self):
        t = self.rep["graded"]["reference"]["tiers"]
        self.assertEqual(t["heavy"]["scroll_sources"], ["surfaceflinger"])
        self.assertEqual(t["heavy"]["scroll"]["p95_ms"]["median"], 33.33)
        self.assertEqual(t["heavy"]["scroll"]["fps"]["median"], 45.0)
        self.assertEqual(t["heavy"]["scroll_bands"], ["needs-improvement"] * 3)
        self.assertEqual(t["light"]["scroll_band"], "good")
        self.assertEqual(t["light"]["updates_band"], "good")
        self.assertEqual(t["light"]["scrolled_px"]["median"], 2100)   # three swipes of 700 px
        self.assertEqual(t["light"]["cards"], [20])
        self.assertEqual(t["light"]["load_ms"]["median"], 400)        # navigation -> last paint at 0.4 s

    def test_headline_grade(self):
        g = self.rep["grade"]
        self.assertEqual(g["grade"], "B")
        self.assertEqual(g["levels"], {"light": "premium", "typical-nocam": "premium", "typical": "premium", "heavy": "fluid"})
        self.assertEqual(g["bands"]["heavy"], {"tap": "good", "scroll": "fluid", "updates": "premium", "load": "good", "camera": "good"})
        self.assertEqual(g["bands"]["light"]["camera"], "none")              # no streams on light (#174)
        heavy = self.rep["graded"]["reference"]["tiers"]["heavy"]
        self.assertEqual(heavy["camera_levels"], ["good", "good", "good"])
        self.assertEqual(heavy["camera_runs"][0]["detail"][1]["w"], 640)
        self.assertEqual(g["start"], {"value": 800, "band": "good"})   # process 20.0 s -> engine 20.7 to 20.9 s
        self.assertEqual(g["viewport"], {"state": "ok", "reason": None})
        self.assertFalse(g["provisional"])
        self.assertEqual(g["headline"], "test-frame: Clarity B, a good wall panel for typical dashboards, with a camera. "
                                        "Held back by large dashboards: scrolling averages 45.0 fps (p95 33 ms).")
        self.assertEqual(self.rep["graded"]["reference"]["android_ttid_ms"]["median"], 111)   # reported, not graded
        self.assertEqual(self.rep["real_use"]["dashboard_load_ms"]["median"], 3400)            # not graded either

    def test_the_headline_names_what_limits_the_letter(self):
        """smoke5: light scrolled poorly and its load wasn't measured, while large dashboards scrolled at
        about 53 fps. Next to an E, '53 fps' read against the letter; the headline names the limit."""
        for k in (1, 2, 3):
            run = refstart(k, 100)                              # large: about 45 fps, fluid
            run["tiers"]["light"]["swipes"] = [swipe(30.0, i, late(60, 45, 66.67)) for i in range(3)]
            run["tiers"]["typical"]["swipes"] = [swipe(40.0, i, late(60, 45, 66.67)) for i in range(3)]
            run["tiers"]["typical-nocam"]["swipes"] = [swipe(70.0, i, late(60, 45, 66.67)) for i in range(3)]
            run["tiers"]["light"]["page"]["fcp_ms"] = 900      # its first paint never reached the screen
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["grade"], "E")
        self.assertEqual(g["headline"], "test-frame: Clarity E, not a good wall panel, even for light dashboards. Held back by "
                                        "light dashboards: scrolling averages 18.5 fps (p95 67 ms); opening the "
                                        "view wasn't measured. Provisional: not everything was measured.")
        self.assertNotIn("53 fps", g["headline"])

    def test_an_a_says_every_task_holds(self):
        for k in (1, 2, 3):
            self.write(f"refstart-{k}.json", refstart(k, 100, heavy_missed=0))
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["headline"], "test-frame: Clarity A+, premium. Every task holds 60 fps on large dashboards.")

    def test_an_a_short_of_premium_says_what_is_short(self):
        """Heavy scrolls at the good rule (54.5 fps, p95 33 ms) but 10% of its frames are late: an A,
        not premium."""
        for k in (1, 2, 3):
            self.write(f"refstart-{k}.json", refstart(k, 100, heavy_missed=6))
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["grade"], "A")
        self.assertEqual(g["headline"], "test-frame: Clarity A, a good wall panel even for large dashboards. Short of A+ "
                                        "on large dashboards: scrolling averages 54.5 fps (p95 33 ms).")

    def test_the_measured_update_level_reaches_the_grade(self):
        """Typical's changes take about 150 ms to show: its live updates are fluid, so typical is
        fluid and the letter drops from B to C."""
        for k in (1, 2, 3):
            run = refstart(k, 100)
            run["tiers"]["typical"]["step"] = updates_step(NAV["typical"], present_after=150.0)
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        self.assertEqual((g["bands"]["typical"]["updates"], g["levels"]["typical"], g["grade"]), ("fluid", "fluid", "C"))
        self.assertIn("a change takes 150 ms to show (p95)", g["headline"])

    def test_each_page_is_reported_at_each_place_in_the_order(self):
        for k in (1, 2, 3):
            run = refstart(k, 100)
            run["order"] = (["light", "typical", "heavy"] * 2)[k - 1:k + 2]
            run["warmup"] = {"tier": "light", "done": True}
            self.write(f"refstart-{k}.json", run)
        ref = R.build(self.dir)["graded"]["reference"]
        self.assertEqual(ref["orders"], [["light", "typical", "heavy"], ["typical", "heavy", "light"], ["heavy", "light", "typical"]])
        self.assertEqual(ref["warmups"], [{"tier": "light", "finished": True}] * 3)
        self.assertEqual(ref["tiers"]["light"]["positions"], [1, 3, 2])
        self.assertEqual(sorted(ref["tiers"]["heavy"]["by_position"]), ["1", "2", "3"])
        self.assertEqual(ref["tiers"]["heavy"]["by_position"]["1"][0]["scroll_level"], "fluid")

    def test_four_runs_put_two_in_one_place_of_the_order(self):
        for k in (1, 2, 3, 4):
            run = refstart(k, 100)
            run["order"] = (["light", "typical", "heavy"] * 3)[(k - 1) % 3:(k - 1) % 3 + 3]
            self.write(f"refstart-{k}.json", run)
        rep = R.build(self.dir)
        self.assertEqual(len(rep["graded"]["reference"]["tiers"]["light"]["by_position"]["1"]), 2)
        md = R.markdown(rep)
        self.assertIn("| Page | 1st | 2nd | 3rd |", md)
        light = next(l for l in md.splitlines() if l.startswith("| light | "))
        self.assertEqual(light.split(" | ")[1].count("scroll "), 2)              # runs 1 and 4 both opened light first

    def test_a_failed_run_doesnt_say_an_empty_protocol(self):
        """smoke7's report printed "the pages ran protocol ;" when its only run had failed."""
        for k in (1, 2, 3):
            run = refstart(k, 100)
            run["error"] = "the reference dashboards did not finish within 600 s"
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        self.assertFalse(any("protocol" in r for r in g["provisional_reasons"]), g["provisional_reasons"])
        self.assertIn("3 of 3 graded runs failed or are missing", g["provisional_reasons"])

    def test_the_grade_does_not_move_with_your_home_assistant(self):
        """Every grade input comes from the reference dashboards; nothing from your own dashboard,
        your panes or your network reaches it, on the real-use side or inside a graded run."""
        before = self.rep["grade"]
        for k in (1, 2, 3):
            self.write(f"coldstart-{k}.json", {"run": k, "ttid_ms": 9000, "launch": "am-start", "proc_mono": 1.0,
                                                "page_start_mono": 2.0, "page_stop_mono": 30.0, "ended_quiet": False,
                                                "presents_mono_ms": [30000 + 16.7 * i for i in range(600)]})
            self.write(f"idle-all-on-{k}.json", idle("all-on", k, 1_900_000, 95.0))
            # Inside the graded run: your dashboard loaded slowly first, reloaded, kept painting (a clock card).
            run = refstart(k, [120, 180, 150][k - 1])
            run.update(own_page_start_mono=21.0, own_page_stop_mono=29.9, own_page_loads=2,
                       own_page_state="finished-moving", ttid_ms=4000)
            run["presents_mono_ms"] = run["presents_mono_ms"] + [21000 + 16.7 * i for i in range(500)]
            self.write(f"refstart-{k}.json", run)
        rep = R.build(self.dir)
        self.assertEqual(rep["real_use"]["dashboard_load_ms"]["median"], 28000)
        self.assertFalse(rep["real_use"]["memory"]["budget"]["ok"])   # the 1.9 GB samples did arrive
        self.assertEqual(rep["grade"], before)                        # a clock card isn't a reason to doubt it

    def test_a_kiosk_page_that_never_finished_keeps_the_letter_and_flags_it(self):
        before = self.rep["grade"]
        for k in (1, 2, 3):
            run = refstart(k, [120, 180, 150][k - 1])
            run.update(own_page_stop_mono=None, own_page_state="never-finished")
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        for key in ("grade", "meaning", "bands", "levels", "start", "capped_by_start", "missing"):
            self.assertEqual(g[key], before[key], key)
        self.assertTrue(g["provisional"])
        self.assertEqual(g["provisional_reasons"], ["the kiosk's own page never finished loading in a graded run (Home Assistant down?)"])

    def test_a_non_default_zoom_is_not_graded(self):
        """At another zoom each screen holds a different amount of the same dashboard."""
        for k in (1, 2, 3):
            run = refstart(k, 100)
            for t in run["tiers"].values():
                t["page"]["dpr"] = 2.25
            self.write(f"refstart-{k}.json", run)
        rep = R.build(self.dir)
        g = rep["grade"]
        self.assertIsNone(g["grade"])
        self.assertEqual(g["viewport"]["state"], "refused")
        self.assertIn("expected 1.5 (density 240 / 160)", g["refused"])
        self.assertTrue(g["headline"].startswith("test-frame: no Clarity grade."))

    def test_an_unknown_density_makes_it_provisional(self):
        with open(os.path.join(self.dir, "device.json")) as f:
            dev = json.load(f)
        dev["display"].pop("density")
        self.write("device.json", dev)
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["viewport"]["state"], "unknown")
        self.assertTrue(g["provisional"])

    def test_a_page_that_did_not_scroll_is_not_measured(self):
        run = refstart(1, 100)
        run["tiers"]["typical"]["swipes"] = [swipe(40.0, i, late(60, 0), moved=False) for i in range(3)]
        r = R.reference_run(run)["tiers"]["typical"]
        self.assertIsNone(r["scroll"])
        self.assertEqual(r["scroll_error"], "the page didn't scroll")

    def test_an_evicted_swipe_uses_raf_and_the_others_keep_surfaceflinger(self):
        """SurfaceFlinger is decided swipe by swipe, not for the whole run."""
        run = refstart(1, 100)
        run["tiers"]["heavy"]["swipes"][1] = swipe(50.0, 1, late(60, 20), sf=False)
        r = R.reference_run(run)["tiers"]["heavy"]
        self.assertEqual((r["sf_swipes"], r["swipes_moved"], r["raf_only"], r["sf_valid"]), (2, 3, False, False))
        self.assertEqual(r["scroll_source"], "surfaceflinger")

    def test_a_swipe_that_reached_the_end_of_the_page_is_left_out(self):
        """smoke6 and s1v4 (protocol 1): light's page could scroll 380 px, so every swipe ended at the
        edge after about 380 ms, mostly the gesture's slow start. Such a swipe isn't the same flick."""
        run = refstart(1, 100)
        sw = run["tiers"]["light"]["swipes"]
        sw[0] = dict(swipe(30.0, 0, late(20, 12, 50.0)), hit_edge=True)
        r = R.reference_run(run)["tiers"]["light"]
        self.assertEqual((r["swipes_moved"], r["scroll"]["p95_ms"]), (2, 16.67))
        for i in range(3):
            sw[i] = dict(sw[i], hit_edge=True)
        r = R.reference_run(run)["tiers"]["light"]
        self.assertEqual((r["scroll"], r["scroll_error"]), (None, "every swipe reached the end of the page"))

    def test_updates_must_not_break_scroll(self):
        """The live updates keep running while it scrolls, so the scroll level is scroll during the
        updates. A run where no swipe overlapped a change says so and is provisional."""
        run = refstart(1, 100)
        for t in run["tiers"].values():
            t["swipes"] = [dict(sw, changes_during=4) for sw in t["swipes"]]
        r = R.reference_run(run)["tiers"]["heavy"]
        self.assertEqual(r["swipes_during_updates"], 3)
        for k in (1, 2, 3):
            run = refstart(k, 100)
            for t in run["tiers"].values():
                t["swipes"] = [dict(sw, changes_during=0) for sw in t["swipes"]]
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        self.assertIn("heavy: no swipe ran while the live updates did, so scroll wasn't measured during updates",
                      g["provisional_reasons"])

    def test_scroll_on_raf_only_is_provisional_and_said(self):
        """No swipe had SurfaceFlinger's data: the band can't see frames the compositor dropped."""
        before = self.rep["grade"]
        for k in (1, 2, 3):
            run = refstart(k, 100)
            run["tiers"]["heavy"]["swipes"] = [swipe(50.0, i, late(60, 20), sf=False) for i in range(3)]
            self.write(f"refstart-{k}.json", run)
        rep = R.build(self.dir)
        g = rep["grade"]
        self.assertEqual(g["grade"], before["grade"])
        self.assertTrue(g["provisional"])
        self.assertIn("heavy: scrolling rests on rAF only in 3 run(s); SurfaceFlinger's history didn't cover its swipes",
                      g["provisional_reasons"])
        self.assertIn("heavy: scrolling from rAF only in 3 run(s)", R.markdown(rep))

    def test_bands_are_over_the_runs_attempted(self):
        """Three runs planned, two left a file: the grade is provisional, and says so."""
        self.write("plan.json", {"runs": 3})
        os.remove(os.path.join(self.dir, "refstart-3.json"))
        g = R.build(self.dir)["grade"]
        self.assertTrue(g["provisional"])
        self.assertIn("1 of 3 graded runs failed or are missing", g["provisional_reasons"])

    def test_a_task_measured_in_fewer_runs_is_provisional(self):
        run = refstart(2, 100)
        run["tiers"]["typical"]["swipes"] = [swipe(40.0, i, late(60, 0), moved=False) for i in range(3)]
        self.write("refstart-2.json", run)
        g = R.build(self.dir)["grade"]
        self.assertIn("typical: measured in fewer runs than attempted (scroll 2 of 3)", g["provisional_reasons"])

    def test_a_run_that_ended_in_an_error_is_not_graded(self):
        before = self.rep["grade"]
        bad = refstart(4, 900, heavy_missed=50)
        bad.update(error="the kiosk loaded another page while a reference dashboard was measured", engine_mono=29.0)
        self.write("refstart-4.json", bad)
        rep = R.build(self.dir)
        ref = rep["graded"]["reference"]
        self.assertEqual((ref["runs"], ref["graded_runs"], len(ref["errors"])), (4, 3, 1))
        self.assertEqual(rep["grade"]["bands"], before["bands"])
        self.assertEqual(rep["grade"]["start"], before["start"])

    def test_scroll_is_banded_per_run_then_the_median_band(self):
        """Three runs, each poor for a different reason. Per-measure medians would read needs-improvement."""
        runs = []
        for k, intervals in enumerate([late(60, 6, 50.0), late(60, 36), late(60, 1, 800.0)], 1):
            run = refstart(k, 100)
            run["tiers"]["heavy"]["swipes"] = [swipe(50.0, 0, intervals, sf=False)]
            runs.append(R.reference_run(run))
        ref = R.reference_summary(runs)
        self.assertEqual(ref["tiers"]["heavy"]["scroll_bands"], ["poor", "poor", "poor"])
        self.assertEqual(ref["tiers"]["heavy"]["scroll_band"], "poor")
        medians = {k: ref["tiers"]["heavy"]["scroll"][k]["median"] for k in ("p95_ms", "jank_pct", "frozen_pct")}
        self.assertEqual(G.frame_band(dict(medians, n=60)), "needs-improvement")   # what per-measure medians would say

    def test_median_band_of_an_even_count_is_the_worse_middle(self):
        self.assertEqual(R.median_band(["good", "poor"]), "poor")
        self.assertEqual(R.median_band(["good", "good", "needs-improvement", "poor"]), "needs-improvement")

    def test_start_needs_about_blank_as_the_engines_first_page(self):
        run = refstart(1, 100)
        run["engine_uri"] = "http://192.0.2.10:8123/lovelace"
        r = R.reference_run(run)
        self.assertIsNone(r["start_ms"])
        self.assertIn("not about:blank", r["start_error"])
        run["engine_uri"] = "about:blank"
        self.assertEqual(R.reference_run(run)["start_ms"], 700)

    def test_grade_inputs_come_only_from_the_reference_dashboards(self):
        tiers, start = R.grade_inputs(self.rep["graded"])
        self.assertEqual(G.grade(tiers, start)["grade"], self.rep["grade"]["grade"])
        self.assertEqual(set(self.rep["graded"]), {"reference"})
        self.assertNotIn("real_use", R.grade_inputs.__code__.co_names)

    def test_no_reference_dashboards_is_not_graded(self):
        """--skip-reference: nothing was measured, so no letter (not a machine-readable E), and the
        summary says it isn't comparable."""
        for k in (1, 2, 3):
            os.remove(os.path.join(self.dir, f"refstart-{k}.json"))
        rep = R.build(self.dir)
        g = rep["grade"]
        self.assertIsNone(g["grade"])
        self.assertEqual(g["refused"], "the reference dashboards weren't measured")
        self.assertTrue(g["headline"].startswith("test-frame: no Clarity grade."))
        s = S.summarise(rep, allow_provisional=True)
        self.assertEqual(s["not_comparable"], "the reference dashboards weren't measured")
        self.assertEqual(G.grade_summary(s)["status"], "not-comparable")

    def test_one_lost_page_doesnt_make_the_protocol_unknown(self):
        """A lost page has no protocol; it mustn't turn a measured frame into "re-measure"."""
        run = refstart(2, 180)
        run["tiers"]["typical"] = {"nonce": "typical", "swipes": [], "error": "the page didn't finish"}   # nothing arrived
        self.write("refstart-2.json", run)
        rep = R.build(self.dir)
        self.assertEqual(rep["graded"]["reference"]["protocols"], [G.PROTOCOL_VERSION])
        self.assertEqual(rep["protocol_version"], G.PROTOCOL_VERSION)
        s = S.summarise(rep, allow_provisional=True)
        self.assertEqual(G.grade_summary(s)["status"], "graded")

    def test_an_a_plus_with_pages_missing_doesnt_claim_every_task(self):
        for k in (1, 2, 3):
            run = refstart(k, 100, heavy_missed=0)
            del run["tiers"]["light"], run["tiers"]["typical"]
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["grade"], "A+")                     # the rule: A+ needs only heavy
        self.assertNotIn("Every task holds", g["headline"])
        self.assertIn("Provisional", g["headline"])

    def build_with(self, **page):
        for k in (1, 2, 3):
            run = refstart(k, 100)
            for t in run["tiers"].values():
                t["page"].update(page)
                if page.get("clarity", 0) is None:
                    del t["page"]["clarity"]
            self.write(f"refstart-{k}.json", run)
        return R.build(self.dir)

    def test_pre_release_data_says_re_measure_and_is_never_v1(self):
        """Clarity v1 (2026-11-14) starts its numbers again, so a page line without `clarity` is
        pre-release whatever its protocol: provisional here, "re-measure" on the site."""
        for proto in (7, 4, 1):                       # 1: pre-release protocol 1, which v1's number repeats
            with self.subTest(protocol=proto):
                rep = self.build_with(protocol=proto, clarity=None)
                g = rep["grade"]
                self.assertEqual(g["grade"], "B")                                   # kept for the record
                self.assertTrue(g["provisional"])
                self.assertIn(f"measured with a pre-release version of Clarity (protocol {proto}): re-measure", g["provisional_reasons"])
                self.assertIn("Re-measure to publish: the site shows no letter for these pages.", g["headline"])
                self.assertIsNone(rep["clarity"])
                s = S.summarise(rep, allow_provisional=True)
                self.assertIsNone(s["clarity"])
                self.assertEqual(G.grade_summary(s)["status"], "re-measure")

    def test_v1s_release_candidate_reads_as_v1(self):
        """Pre-release protocol 8 (#205's s1rtc5, before the lines carried `clarity`) is v1's measurement,
        but only its own lines, which carry the round's usable.busy_ms (the one bypass of the marker)."""
        self.assertEqual(R.page_version({"protocol": 8, "usable": {"busy_ms": [12, 0]}}), (G.PROTOCOL_VERSION, G.CLARITY))
        self.assertEqual(R.page_version({"protocol": 8, "usable": {"interactive_ms": 1}}), (8, None))
        self.assertEqual(R.page_version({"protocol": 8}), (8, None))
        self.assertEqual(R.page_version({"protocol": 1, "clarity": 1}), (1, 1))
        self.assertEqual(R.page_version({"protocol": 1}), (1, None))          # pre-release protocol 1
        self.assertTrue(R.is_v1({"protocols": [1], "clarity": [1]}))
        self.assertFalse(R.is_v1({"protocols": [1], "clarity": [None]}))
        rep = self.build_with(protocol=8, clarity=None)                       # the fixture's lines have no busy_ms
        self.assertIsNone(rep["clarity"])
        self.assertIn("measured with a pre-release version of Clarity (protocol 8): re-measure", rep["grade"]["provisional_reasons"])

    def test_v1_with_another_protocol_number_is_another_protocol(self):
        rep = self.build_with(protocol=G.PROTOCOL_VERSION + 1, clarity=G.CLARITY)
        self.assertIn(f"the pages ran protocol {G.PROTOCOL_VERSION + 1}; this rubric is for protocol {G.PROTOCOL_VERSION}",
                      rep["grade"]["provisional_reasons"])
        self.assertIsNone(rep["clarity"])

    def test_v1_is_v1(self):
        self.assertEqual((self.rep["clarity"], self.rep["protocol_version"]), (1, 1))

    def test_js_band_is_reported_but_not_graded(self):
        self.assertEqual(self.rep["detail"]["synthetic"]["js_burst_ms"]["median"], 10.0)
        self.assertEqual(self.rep["detail"]["synthetic"]["js_band"], "good")
        self.assertTrue(self.rep["detail"]["synthetic"]["js_checksum_ok"])
        self.assertNotIn("js", self.rep["grade"]["bands"])

    def test_memory_and_budget(self):
        m = self.rep["real_use"]["memory"]
        self.assertEqual(m["idle"]["app_pss_kb"]["median"], 601_000)
        self.assertEqual(m["idle"]["by_role_pss_kb"]["gpu"]["median"], 100_000)
        self.assertEqual(m["peak"]["app_pss_kb"], 602_000)
        self.assertTrue(m["budget"]["ok"])

    def test_pane_diffs_against_all_on(self):
        panes = {p["id"]: p for p in self.rep["real_use"]["panes"]}
        self.assertEqual(panes["sendspin"]["pss_kb"], 41_000)
        self.assertTrue(panes["sendspin"]["pss_measurable"])
        self.assertEqual(panes["assist"]["pss_kb"], 500)
        self.assertFalse(panes["assist"]["pss_measurable"])   # inside the 2 MB spread of the all-on runs
        self.assertEqual(panes["radio"]["method"], "process")
        self.assertEqual(panes["radio"]["pss_kb"], 2500)

    def test_pane_diffs_leave_out_a_sample_the_screen_went_off_in(self):
        self.write("idle-all-on-9.json", dict(idle("all-on", 9, 900_000, 1.0), screen_off=[12.0]))
        self.write("idle-sendspin-off-9.json", dict(idle("sendspin-off", 9, 100_000, 1.0), screen_off=[12.0]))
        panes = {p["id"]: p for p in R.build(self.dir)["real_use"]["panes"]}
        self.assertEqual((panes["sendspin"]["pss_kb"], panes["sendspin"]["samples_on"], panes["sendspin"]["samples_off"]),
                         (41_000, 3, 3))

    def test_a_sample_without_app_processes_is_not_zero_memory(self):
        empty = idle("all-on", 9, 600_000, 10.0)
        empty["processes"] = {}
        empty["device_mem"] = {"mem_total_kb": 2_000_000, "mem_available_kb": None}
        self.write("idle-all-on-9.json", empty)
        self.write("idle-sendspin-off-9.json", dict(empty, label="sendspin-off"))
        rep = R.build(self.dir)
        self.assertEqual(rep["real_use"]["memory"]["idle"]["app_pss_kb"]["n"], 3)
        self.assertEqual(rep["real_use"]["memory"]["idle"]["device_used_pct"]["n"], 3)
        self.assertEqual(rep["real_use"]["memory"]["peak"]["app_pss_kb"], 602_000)
        self.assertEqual({p["id"]: p for p in rep["real_use"]["panes"]}["sendspin"]["samples_off"], 3)

    def test_a_measured_zero_is_the_lowest_available_memory(self):
        zero = idle("all-on", 9, 600_000, 10.0)
        zero["device_mem"] = {"mem_total_kb": 2_000_000, "mem_available_kb": 0}
        self.write("idle-all-on-9.json", zero)
        rep = R.build(self.dir)
        self.assertEqual(rep["real_use"]["memory"]["peak"]["mem_available_min_kb"], 0)
        self.assertFalse(rep["real_use"]["memory"]["budget"]["checks"]["device_available_min"]["ok"])

    def test_a_ladder_step_without_surfaceflinger_is_counted(self):
        lines = synthetic_lines(1, 9.0)
        lines[2]["sf"]["oldest_ms"] = 99_999
        self.assertEqual(R.synthetic_summary([R.synthetic_run(lines)])["steps_without_sf"], 1)

    def test_no_scroll_step_is_not_a_moved_scroll(self):
        lines = [l for l in synthetic_lines(1, 9.0) if l.get("stage") != "scroll"]
        self.assertIsNone(R.synthetic_summary([R.synthetic_run(lines)])["scroll_moved"])

    def test_a_scroll_that_did_not_move_is_flagged(self):
        s = self.rep["detail"]["synthetic"]
        self.assertFalse(s["scroll_moved"])
        self.assertEqual(s["per_run"][0]["scroll"]["scrolled_px"], 720)
        self.assertIn("did not move", R.markdown(self.rep))

    def test_load_that_fell_back_to_fcp_says_so(self):
        self.assertEqual(self.rep["detail"]["synthetic"]["page_lcp_from"], ["fcp", "lcp"])
        self.assertIn("FCP/LCP", R.markdown(self.rep))

    def test_cold_start_records_the_launch_and_the_whole_restart(self):
        r = self.rep["real_use"]
        self.assertEqual(r["launch"], ["relaunched-by-system"])
        self.assertEqual(r["stop_to_dashboard_ms"]["median"], 3900)   # stopped at 9.5 s, settled at 13.4 s

    def test_a_failed_cold_start_is_counted_not_graded(self):
        self.write("coldstart-4.json", {"run": 4, "ttid_ms": 900, "launch": "relaunched-by-system",
                                        "error": "dashboard did not finish loading within 120 s (no GeckoView:PageStop)"})
        r = R.build(self.dir)["real_use"]
        self.assertEqual(len(r["errors"]), 1)
        self.assertEqual(r["dashboard_load_ms"]["n"], 3)
        self.assertEqual(r["android_ttid_ms"], {"median": 1900, "min": 1800, "max": 2000, "n": 3})
        self.assertEqual(r["app_start_to_dashboard_ms"]["n"], 3)
        self.assertEqual(r["per_run"][-1]["ttid_ms"], 900)   # kept for diagnosis

    def test_apk_sizes_are_kept_only_when_asked(self):
        with open(os.path.join(self.dir, "report.json"), "w") as f:
            json.dump({"apk": [{"file": "old.apk"}]}, f)
        for keep, want in ((False, []), (True, [{"file": "old.apk"}])):
            with open(os.path.join(self.dir, "report.json"), "w") as f:
                json.dump({"apk": [{"file": "old.apk"}]}, f)
            subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "report.py"), self.dir]
                           + (["--keep-apk"] if keep else []), check=True, capture_output=True)
            with open(os.path.join(self.dir, "report.json")) as f:
                self.assertEqual(json.load(f)["apk"], want)

    def test_no_real_use_section_without_real_use_samples(self):
        d = tempfile.mkdtemp()
        for name in ("device.json", "synthetic-1.jsonl"):
            with open(os.path.join(self.dir, name)) as f, open(os.path.join(d, name), "w") as g:
                g.write(f.read())
        self.write_to(d, "idle-after-synthetic-1.json", idle("after-synthetic", 1, 500_000, 10.0))
        md = R.markdown(R.build(d))
        self.assertNotIn("## Real use", md)
        self.assertIn("## Memory budget", md)

    def write_to(self, d, name, obj):
        with open(os.path.join(d, name), "w") as f:
            json.dump(obj, f)

    def test_markdown_renders(self):
        md = R.markdown(self.rep)
        self.assertIn("**test-frame: Clarity B, a good wall panel for typical dashboards, with a camera.", md)
        self.assertIn("| heavy (120 cards) | fluid |", md)
        self.assertIn("| scroll |", md)                                 # what holds heavy back from smooth
        self.assertIn("Restart, app process to engine ready: 800 ms (700 to 900, n=3)", md)
        self.assertIn("zoom check: ok", md)
        self.assertIn("## Real use on this dashboard (not graded)", md)
        self.assertLess(md.index("| light ("), md.index("## Detail: animations"))
        self.assertLess(md.index("## Detail: animations"), md.index("## Real use on this dashboard"))
        self.assertIn("| css-transform | 4 (4 to 4, n=3) |", md)

    def test_apk_sizes_per_abi(self):
        apk = os.path.join(self.dir, "app.apk")
        with zipfile.ZipFile(apk, "w") as z:
            z.writestr("classes.dex", b"x" * 100)
            z.writestr("lib/armeabi-v7a/libxul.so", b"y" * 300)
            z.writestr("assets/extension/content.js", b"z" * 50)
        out = R.apk_sizes([apk])
        self.assertEqual(out[0]["abis"], ["armeabi-v7a"])
        self.assertEqual(out[0]["parts_bytes"]["native"], 300)



class Usable(unittest.TestCase):
    """Protocol 4: open the view and restart end at the first frame on screen that can carry the
    render in which every first-screen card was drawn, and never before the main thread was free."""
    # The page's clock: navigation start is at 98 000 ms on logcat's.
    page = {"mono": 100.0, "epoch_ms": 1_000_000.0, "origin_epoch_ms": 998_000.0,
            "usable": {"rendered_tick_ms": 1500.0, "first_screen_cards": 13, "interactive_ms": 1510.0}}

    def test_the_first_frame_that_can_carry_the_render(self):
        # Rendered at 99 500: a frame presented sooner than a refresh later (less 3 ms) was already on its way.
        when, why = R.usable_on_screen(self.page, [99_505, 99_510, 99_520, 99_540])
        self.assertEqual((when, why), (99_520, None))

    def test_never_before_the_main_thread_is_free(self):
        page = dict(self.page, usable=dict(self.page["usable"], interactive_ms=1600.0))
        self.assertEqual(R.usable_on_screen(page, [99_520]), (99_600, None))

    def test_the_restart_ends_on_the_start_view(self):
        """The kiosk was pointed at the start view; coming back to any other view isn't the restart."""
        run = lambda tier: R.reference_run({"proc_mono": 90.0, "restart_page": dict(self.page, tier=tier),  # noqa: E731
                                            "presents_mono_ms": [99_520]})
        self.assertEqual((run("start")["start_ms"], run("start")["start_error"]), (9520, None))
        self.assertIsNone(run("light")["start_ms"])
        self.assertIn("the 'light' view, not 'start'", run("light")["start_error"])

    def test_a_restart_measured_in_fewer_runs_is_provisional(self):
        ref = {"graded_runs": 3, "start_ms": {"n": 2}, "tiers": {}}
        self.assertIn("restart: measured in fewer runs than attempted (2 of 3)", R.coverage_reasons(ref, 3))
        self.assertEqual(R.coverage_reasons(dict(ref, start_ms={"n": 3}), 3), [])

    def test_not_measured_without_a_frame_or_a_usable_line(self):
        self.assertEqual(R.usable_on_screen(self.page, [99_505])[1], "no frame on screen after the dashboard rendered")
        self.assertEqual(R.usable_on_screen(dict(self.page, usable=None), [99_520])[1], "the page didn't report when it was usable")


class SentToScreen(unittest.TestCase):
    """Protocol 4: change to screen runs from when the fake sent the change, so a main thread too busy
    to read it counts; one it never read in the window counts as not shown by the window's end."""
    page = {"mono": 100.0, "epoch_ms": 1_000_000.0, "origin_epoch_ms": 998_000.0}     # page t -> 98 000 + t

    def step(self, changes):
        # Window t0 = 1000 on the page's clock: 99 000 to 103 000 on logcat's; a frame every 16.7 ms.
        pres = [99_000 + 16.7 * i for i in range(240)]
        return {"t0": 1000.0, "window_ms": 4000, "from": "sent", "changes": changes, "raf_times": [16.7 * i for i in range(240)],
                "sf": {"presents_ms": pres, "period_ms": 16.7, "window_ms": [99_000, 103_000], "oldest_ms": 98_000}}

    def test_waiting_for_the_main_thread_counts(self):
        # Sent at 100, read at 900 (the main thread was busy), drawn in the frame at 910.
        lat, why = R.change_to_screen(self.step([[100.0, 900.0, 0, 0, 910.0]]), self.page)
        self.assertIsNone(why)
        self.assertGreaterEqual(lat["max_ms"], 800 + 16.7 - 3)

    def test_one_never_read_counts_to_the_windows_end(self):
        lat, _ = R.change_to_screen(self.step([[100.0, 110.0, 0, 0, 120.0], [1000.0, None, 0, 0, None]]), self.page)
        self.assertEqual(lat["n"], 2)
        self.assertEqual(lat["max_ms"], 3000.0)                         # 103 000 - 100 000
        a = R.arrivals(self.step([[100.0, 110.0, 0, 0, 120.0], [1000.0, None, 0, 0, None]]))
        self.assertEqual((a["sent"], a["arrived"], a["wait_max_ms"]), (2, 1, 10.0))

    def test_it_is_never_skipped_for_updates_that_didnt_arrive(self):
        """smoke9's heavy: "only 17 of 20 state updates ran" made the page n/a. In protocol 4 the
        schedule says what was sent; the rest is the panel's to answer for."""
        why, _ = R.live_check_failed({"offscreen": 0, "moving": 8, "targets": 12, "update_every_ms": 200},
                                     self.step([[100.0 * k, None, 0, 0, None] for k in range(1, 20)]))
        self.assertIsNone(why)


class FakeLog(unittest.TestCase):
    """F5: the report reads the fake Home Assistant's log and is provisional on anything wrong."""

    def log(self, *entries):
        d = tempfile.mkdtemp()
        write_fake_log(d, entries)
        return R.fake_ha_log(os.path.join(d, "fakeha.jsonl"))

    def test_a_clean_log(self):
        log = self.log()
        self.assertEqual((log["commands"], log["unknown"], log["failed"]), (1, {}, {}))
        self.assertEqual(R.fake_ha_reasons(log, True), [])

    def test_unknown_failed_or_late(self):
        log = self.log({"t": 2, "unknown": "energy/get_prefs", "msg": {}}, {"t": 3, "failed": "history/stream", "error": "x"},
                       {"t": 4, "stream": "S", "k": 2, "late_ms": 250.0})
        reasons = R.fake_ha_reasons(log, True)
        self.assertEqual(len(reasons), 3)
        self.assertIn("energy/get_prefs (1)", reasons[0])
        self.assertIn("history/stream (1)", reasons[1])
        self.assertIn("250 ms late", reasons[2])
        # smoke10's 92 ms isn't a reason: each change is timed from when it was actually sent.
        self.assertEqual(R.fake_ha_reasons(self.log({"t": 4, "stream": "S", "k": 2, "late_ms": 92.0}), True), [])

    def test_only_a_graded_views_late_stream_makes_it_provisional(self):
        """s1rtc8: one 2 s burst of late sends, all on typical-hls (an HLS copy, not graded), made the
        graded result provisional. Each send belongs to the view open then; a copy's or a warm-up's
        lateness is detail. Only which streams the check reads changes, not its 200 ms."""
        log = self.log({"t": 2, "open": {"view": "light", "nonce": "a1"}}, {"t": 3, "stream": "S", "k": 1, "late_ms": 40.0},
                       {"t": 4, "open": {"view": "typical-hls", "nonce": "a2"}}, {"t": 5, "stream": "S", "k": 1, "late_ms": 1952.0},
                       {"t": 6, "open": {"view": "light", "nonce": "a3", "warmup": "1"}}, {"t": 7, "stream": "F", "k": 1, "late_ms": 900.0})
        self.assertEqual((log["stream_late_max_ms"], log["stream_late_detail_max_ms"], log["stream_sent"]), (40.0, 1952.0, 4))  # and the fixture's own
        self.assertEqual(R.fake_ha_reasons(log, True), [])
        # A whole run: the copy's burst is a line in report.md, and the grade isn't provisional for it.
        b = Build()
        b.setUp()
        write_fake_log(b.dir, [{"t": 2, "open": {"view": "typical-hls", "nonce": "a2"}},
                               {"t": 5, "stream": "S", "k": 1, "late_ms": 1952.0}])
        rep = R.build(b.dir)
        self.assertFalse(any("late" in r for r in rep["grade"].get("provisional_reasons") or []))
        self.assertIn("The fake Home Assistant sent a live change 1952 ms late during a view that isn't graded", R.markdown(rep))
        # The same burst on a graded view is a reason, as before.
        log = self.log({"t": 2, "open": {"view": "heavy", "nonce": "b1"}}, {"t": 3, "stream": "S", "k": 1, "late_ms": 1952.0})
        self.assertIn("the fake Home Assistant sent a live change 1952 ms late (more than 200 ms)", R.fake_ha_reasons(log, True))

    def test_protocol_4_without_the_log_is_provisional(self):
        self.assertEqual(R.fake_ha_reasons(None, True), ["the fake Home Assistant's log (fakeha.jsonl) is missing"])
        self.assertEqual(R.fake_ha_reasons(None, False), [])

    def test_a_budgets_file_that_isnt_there_is_an_error(self):
        """F9: --budgets naming a missing file must not fall back to the defaults silently."""
        with self.assertRaises(SystemExit):
            R.load_budgets("aqara-s1-plus", "/nonexistent/budgets.json")
        self.assertIn("assist", R.load_budgets("aqara-s1-plus")["panes"])       # the frame's own file

    def test_the_head_must_be_on_the_first_screen(self):
        page = dict(Usable.page, usable=dict(Usable.page["usable"], first_screen_cards=8, head_cards=10))
        self.assertIn("fewer than the 10 head cards", R.usable_on_screen(page, [99_520])[1])


def ram_idle(label, main_kb, cpu=1.0):
    pkg = "sh.edm.s1kiosk"
    return {"label": label, "window_s": 15,
            "processes": {pkg: {"pid": 1, "role": "main", "pss_kb": main_kb, "rss_peak_kb": main_kb},
                          pkg + ":gpu_process": {"pid": 2, "role": "gpu", "pss_kb": 200_000, "rss_peak_kb": 250_000}},
            "device_mem": {"mem_total_kb": 2_000_000, "mem_available_kb": 900_000},
            "cpu": {"procs_pct_of_core": {pkg: cpu}, "device_busy_pct": 10}}


def with_boot(sample, boot_kb=29_000):
    s = dict(sample, processes=dict(sample["processes"]))
    s["processes"]["sh.edm.s1kiosk:boot"] = {"pid": 3, "role": "boot", "pss_kb": boot_kb, "rss_peak_kb": boot_kb}
    return s


class NoiseBand(unittest.TestCase):
    """docs/perf.md's claims for perflib.noise_band, by simulation (seeded, normal noise, σ = 1)."""

    def sim(self, n, draws=4000):
        import random
        rnd = random.Random(186)
        bands, low, false = [], 0, 0
        for _ in range(draws):
            a, b = [rnd.gauss(0, 1) for _ in range(n)], [rnd.gauss(0, 1) for _ in range(n)]
            ba, bb = P.noise_band(a), P.noise_band(b)
            bands.append(ba)
            low += ba < 0.2
            false += abs(statistics.median(a) - statistics.median(b)) > max(ba, bb)
        return statistics.mean(bands), low / draws, false / draws

    def test_three_samples(self):
        mean, low, false = self.sim(3)
        self.assertAlmostEqual(mean, 2.0, delta=0.1)       # about 2 σ of one sample
        self.assertLess(low, 0.02)                         # it rarely collapses (the MAD: 11 %)
        self.assertLess(false, 0.06)                       # a zero difference reads measurable about 3.5 % of the time

    def test_five_samples(self):
        """The verifier on #196: the MAD at five averaged 1.64 σ (5% false "measurable");
        range / d2 runs through seven."""
        mean, low, false = self.sim(5)
        self.assertAlmostEqual(mean, 2.0, delta=0.1)
        self.assertLess(false, 0.03)

    def test_it_doesnt_grow_with_more_samples(self):
        self.assertLess(self.sim(9)[0], self.sim(3)[0])

    def test_under_two_samples_there_is_none(self):
        self.assertIsNone(P.noise_band([1.0]))
        self.assertGreater(P.noise_band([100, 100, 130]), 0)


class PaneOnAlone(unittest.TestCase):
    """#68: a pane's share is the median of its on-alone samples less the median of the all-off ones;
    the tolerance is the larger noise band (2 × 1.4826 × MAD) of the two sets (#186)."""

    def test_share_and_tolerance(self):
        idles = {"ref-panes-off": [ram_idle("ref-panes-off", v) for v in (100_000, 104_000, 101_000)],
                 "ref-assist-on": [ram_idle("ref-assist-on", v) for v in (131_000, 130_000, 136_000)],
                 "ref-sendspin-on": [ram_idle("ref-sendspin-on", v) for v in (102_000, 103_000, 101_000)]}
        panes = {p["id"]: p for p in R.pane_on_alone(idles, "sh.edm.s1kiosk")}
        self.assertEqual(panes["assist"]["pss_kb"], 30_000)                  # 331 000 - 301 000
        self.assertEqual(panes["assist"]["pss_tolerance_kb"], 7_088)         # 2 × 6 000 / d2(3); the on set is the noisier
        self.assertTrue(panes["assist"]["pss_measurable"])
        self.assertEqual(panes["sendspin"]["pss_kb"], 1_000)
        self.assertFalse(panes["sendspin"]["pss_measurable"])                # inside the off set's 4 725
        self.assertEqual(panes["assist"]["by_role_pss_kb"], {"gpu": 0, "main": 30_000})
        self.assertEqual((panes["assist"]["boot_samples_on"], panes["assist"]["boot_pss_kb"]), (0, None))

    def test_one_odd_sample_doesnt_set_the_tolerance_of_many(self):
        """#186: max - min only grows with n. From five samples the band is the MAD's, so ten samples
        with one 28 MB off stay near three samples' band, not the 30 MB range."""
        three = [100_000, 101_000, 102_000]
        ten = three * 3 + [130_000]
        def tol(off):
            idles = {"ref-panes-off": [ram_idle("ref-panes-off", v) for v in off],
                     "ref-assist-on": [ram_idle("ref-assist-on", v + 30_000) for v in three]}
            return R.pane_on_alone(idles, "sh.edm.s1kiosk")[0]["pss_tolerance_kb"]
        self.assertEqual(tol(three), 2_363)                                  # 2 × 2 000 / d2(3)
        self.assertEqual(tol(ten), 2_965)                                    # 2 × 1.4826 × MAD 1 000

    def test_two_agreeing_samples_and_an_odd_one_dont_give_zero(self):
        """The verifier on #196: the MAD of three samples is the smaller gap, so 100, 100, 130 gave 0."""
        idles = {"ref-panes-off": [ram_idle("ref-panes-off", v) for v in (100_000, 100_000, 130_000)],
                 "ref-assist-on": [ram_idle("ref-assist-on", v) for v in (110_000, 110_000, 110_000)]}
        p = R.pane_on_alone(idles, "sh.edm.s1kiosk")[0]
        self.assertEqual(p["pss_tolerance_kb"], 35_440)                      # 2 × 30 000 / d2(3)
        self.assertFalse(p["pss_measurable"])

    def test_one_sample_a_side_has_no_tolerance(self):
        """s1p1 (n=1) read ±0.0 MB, measurable, budget ok: no evidence for any of it."""
        idles = {"ref-panes-off": [ram_idle("ref-panes-off", 100_000)], "ref-assist-on": [ram_idle("ref-assist-on", 130_000)] * 3}
        p = R.pane_on_alone(idles, "sh.edm.s1kiosk")[0]
        self.assertEqual((p["pss_tolerance_kb"], p["pss_measurable"], p["cpu_tolerance_pct"], p["cpu_measurable"]),
                         (None, False, None, False))
        b = G.pane_budgets([p], {"assist": {"ram_pss_max_kb": 49152}})["assist"]
        self.assertEqual((b["measurable"], b["ok"]), (False, None))
        md = "\n".join(R.ram_markdown({"panes": [p], "pane_budget": {"assist": b | {"budget_kb": 49152}}}))
        row = next(l for l in md.splitlines() if l.startswith("| assist |"))
        # The verifier on #196: the row said "not measurable" three times, and "one sample" at 3 on.
        self.assertEqual(row, "| assist | +29.3 MB | none: under 2 samples a side (n=3 on, 1 off) | 48 MB, not measurable "
                              "| +0.0 % of a core |")
        self.assertNotIn("within tolerance", md)

    def test_boot_is_reported_apart_from_the_share(self):
        """#186: `:boot` (29 MB) ran in 1 of 3 Sendspin samples in s1p3 and made its tolerance ±22 MB."""
        off = [ram_idle("ref-panes-off", v) for v in (100_000, 101_000, 102_000)]
        on = [ram_idle("ref-sendspin-on", 110_000)] + [with_boot(ram_idle("ref-sendspin-on", v)) for v in (111_000, 112_000)]
        p = R.pane_on_alone({"ref-panes-off": off, "ref-sendspin-on": on}, "sh.edm.s1kiosk")[0]
        # With :boot in two of three, it moves the median: counted, the share would read +39 MB.
        self.assertEqual((p["pss_kb"], p["pss_tolerance_kb"]), (10_000, 2_363))
        self.assertEqual((p["boot_samples_on"], p["boot_samples_off"], p["boot_pss_kb"]), (2, 0, 29_000))
        self.assertNotIn("boot", p["by_role_pss_kb"])
        md = "\n".join(R.ram_markdown({"panes": [p], "pane_budget": {}}))
        self.assertIn("`:boot` (28.3 MB) ran in 2 of 3 on and 0 of 3 off sample(s)", md)

    def test_nothing_without_the_all_off_samples(self):
        self.assertEqual(R.pane_on_alone({"ref-assist-on": [ram_idle("ref-assist-on", 1)]}, "sh.edm.s1kiosk"), [])

    def test_the_reference_samples_arent_real_use_and_real_use_labels_arent_panes(self):
        budgets = {"ram": {"app_pss_max_pct_of_ram": 50, "device_available_min_pct": 15},
                   "panes": {"assist": {"switch": "voice_enabled", "ram_pss_max_kb": 20_000}, "radio": {"not_switched": "its mode"}}}
        ram = R.reference_ram({"ref-idle": [ram_idle("ref-idle", 300_000)],
                               "ref-panes-off": [ram_idle("ref-panes-off", v) for v in (100_000, 100_500, 101_000)],
                               "ref-assist-on": [ram_idle("ref-assist-on", v) for v in (130_000, 130_500, 131_000)]},
                              [{"memory": [ram_idle("x", 320_000), ram_idle("x", 340_000)]}], "sh.edm.s1kiosk", budgets, 2_000_000)
        self.assertEqual(ram["reference_idle"]["app_pss_kb"]["median"], 500_000)
        self.assertEqual(ram["graded_peak"]["app_pss_kb"], 540_000)
        self.assertEqual(ram["graded_peak"]["by_role_pss_kb"], {"gpu": 200_000, "main": 340_000})
        self.assertEqual(ram["pane_budget"]["assist"]["ok"], False)          # 30 MB against 20 MB, three samples each
        self.assertEqual(ram["panes_not_switched"], [{"id": "radio", "why": "its mode"}])
        self.assertEqual(ram["panes_waiting"], [])                            # kept in glassworks.perf/4, empty since #67
        self.assertIn("Not measured on their own: radio (its mode).", "\n".join(R.ram_markdown(ram)))
        self.assertTrue(ram["budget"]["ok"])
        self.assertIsNone(R.reference_ram({}, [], "sh.edm.s1kiosk", budgets, 2_000_000))
        # F6: a run that failed may have stopped mid-page; its samples aren't the peak.
        ram = R.reference_ram({}, [{"memory": [ram_idle("x", 320_000)]}, {"error": "x", "memory": [ram_idle("x", 900_000)]}],
                              "sh.edm.s1kiosk", budgets, 2_000_000)
        self.assertEqual((ram["graded_peak"]["samples"], ram["graded_peak"]["app_pss_kb"]), (1, 520_000))


S1_PANES = {"assist": {"switch": "voice_enabled", "mute": "mic_muted", "state": "mic"},
            "sendspin": {"switch": "sendspin_enabled", "state": "sendspin"}, "radio": {"not_switched": "its mode"}}


def ran(sample, voice, sendspin, mic, screen_off=None):
    return dict(sample, panes_state={"voice": voice, "sendspin": sendspin, "mic": mic}, screen_off=screen_off)


class PanesAsAsked(unittest.TestCase):
    """s1v7: S1 Kiosk 0.4.3 lost the voice and Sendspin switches, so the "panes off" samples ran
    both, and Assist read -0.4 % of a core. Only samples whose kiosk log shows what their label says
    count, and the report says when Assist was off by its microphone mute alone."""

    LOG = ("11-13 00:00:01.000  100  100 I S1ESPHome: ESPHome device 'panel' mac 00:00:00:00:00:00, voice=true\n"
           "11-13 00:00:02.000  100  100 I S1Sendspin: Sendspin player starting\n"
           "11-13 00:00:03.000  100  100 I S1Voice: Microphone open: source=VOICE_RECOGNITION\n")

    def test_the_kiosk_log_gives_what_ran(self):
        self.assertEqual(P.pane_state(self.LOG), {"voice": True, "sendspin": True, "mic": True})
        muted = self.LOG + "11-13 00:00:04.000  100  100 I S1Voice: Microphone muted (released)\n"
        self.assertEqual(P.pane_state(muted), {"voice": True, "sendspin": True, "mic": False})
        # A later start (the app restarted) is the one that counts: nothing after it ran yet.
        restarted = self.LOG + "11-13 00:00:09.000  200  200 I S1ESPHome: ESPHome device 'panel' mac 00:00:00:00:00:00, voice=false\n"
        self.assertEqual(P.pane_state(restarted), {"voice": False, "sendspin": False, "mic": False})
        self.assertIsNone(P.pane_state("nothing from the kiosk")["voice"])

    def test_a_sample_counts_when_it_ran_its_label(self):
        x = ram_idle("ref-assist-on", 1)
        self.assertTrue(R.sample_matches(ran(x, True, False, True), "ref-assist-on", S1_PANES))
        self.assertFalse(R.sample_matches(ran(x, True, True, True), "ref-assist-on", S1_PANES))      # Sendspin too
        self.assertTrue(R.sample_matches(ran(x, True, False, False), "ref-panes-off", S1_PANES))     # off by its mute
        self.assertFalse(R.sample_matches(ran(x, True, False, True), "ref-panes-off", S1_PANES))
        self.assertFalse(R.sample_matches(ran(x, False, False, False, screen_off=[12.5]), "ref-panes-off", S1_PANES))
        self.assertIsNone(R.sample_matches(x, "ref-panes-off", S1_PANES))                            # not recorded

    def test_a_pane_the_sample_didnt_record_leaves_it_unverified(self):
        """#210 review: a run from before a pane's switch (#67) has no `pane:<id>` in its log. Its
        samples still count, but the share isn't marked verified."""
        x = ram_idle("ref-panes-off", 100_000)
        panes = dict(S1_PANES, **{"wall-base": {"switch": "wall_base_enabled", "state": "pane:wall-base"}})
        self.assertIsNone(R.sample_matches(ran(x, True, False, False), "ref-panes-off", panes))
        recorded = dict(ran(x, True, False, False)["panes_state"], **{"pane:wall-base": False})
        self.assertTrue(R.sample_matches(dict(x, panes_state=recorded), "ref-panes-off", panes))
        self.assertFalse(R.sample_matches(dict(x, panes_state=dict(recorded, **{"pane:wall-base": True})), "ref-panes-off", panes))

    def test_samples_that_ran_something_else_are_left_out(self):
        idles = {"ref-panes-off": [ran(ram_idle("ref-panes-off", 100_000), False, False, False),
                                   ran(ram_idle("ref-panes-off", 150_000), True, True, True)],   # s1v7's off
                 "ref-assist-on": [ran(ram_idle("ref-assist-on", 130_000), True, False, True)],
                 "ref-sendspin-on": [ran(ram_idle("ref-sendspin-on", 110_000), True, True, True)]}
        panes = {p["id"]: p for p in R.pane_on_alone(idles, PKG, S1_PANES)}
        self.assertEqual((panes["assist"]["pss_kb"], panes["assist"]["excluded"]), (30_000, 1))
        self.assertTrue(panes["assist"]["verified"])
        self.assertNotIn("off_by", panes["assist"])
        self.assertIn("didn't run sendspin on by itself", panes["sendspin"]["error"])
        self.assertNotIn("pss_kb", panes["sendspin"])

    def test_every_off_sample_wrong_is_an_error_not_a_number(self):
        idles = {"ref-panes-off": [ran(ram_idle("ref-panes-off", 150_000), True, True, True)] * 2,
                 "ref-assist-on": [ran(ram_idle("ref-assist-on", 150_000), True, False, True)]}
        panes = R.pane_on_alone(idles, PKG, S1_PANES)
        self.assertEqual([p["id"] for p in panes], ["assist", "sendspin"])
        self.assertTrue(all("panes-off" in p["error"] for p in panes))
        md = "\n".join(R.ram_markdown({"panes": panes, "pane_budget": {}}))
        self.assertIn("| assist | not measured:", md)
        # The export leaves it out instead of failing on it.
        self.assertEqual(S.ram_summary({"panes": panes})["panes"], [])

    def test_assist_off_by_its_mute_is_labelled(self):
        idles = {"ref-panes-off": [ran(ram_idle("ref-panes-off", 100_000), True, False, False)],
                 "ref-assist-on": [ran(ram_idle("ref-assist-on", 101_000), True, False, True)]}
        panes = R.pane_on_alone(idles, PKG, S1_PANES)
        self.assertEqual(panes[0]["off_by"], "mute")
        self.assertIn("off by its microphone mute only", "\n".join(R.ram_markdown({"panes": panes, "pane_budget": {}})))
        self.assertEqual(S.ram_summary({"panes": panes})["panes"][0]["off_by"], "mute")

    def test_samples_from_before_the_log_was_read_say_so(self):
        idles = {"ref-panes-off": [ram_idle("ref-panes-off", 100_000)], "ref-assist-on": [ram_idle("ref-assist-on", 130_000)]}
        panes = R.pane_on_alone(idles, PKG, S1_PANES)
        self.assertFalse(panes[0]["verified"])
        self.assertIn("not checked", "\n".join(R.ram_markdown({"panes": panes, "pane_budget": {}})))


class ScreenOff(unittest.TestCase):
    """s1v7: the screen went off for 0.14 s at the end of a reference idle window. A sample, or a
    synthetic run, the screen went off in isn't the panel at work: it is listed and left out."""

    def test_an_idle_sample_with_the_screen_off_is_left_out(self):
        budgets = {"ram": {"app_pss_max_pct_of_ram": 50, "device_available_min_pct": 15}, "panes": {}}
        off = dict(ram_idle("ref-idle", 900_000), run=3, screen_off=[14.86])
        ram = R.reference_ram({"ref-idle": [dict(ram_idle("ref-idle", 300_000), run=1), off]}, [], PKG, budgets, 2_000_000)
        self.assertEqual(ram["reference_idle"]["samples"], 1)
        self.assertEqual(ram["screen_off_samples"], ["ref-idle-3"])
        self.assertIn("The screen went off in ref-idle-3", "\n".join(R.ram_markdown(ram)))

    def test_a_real_use_sample_with_the_screen_off_is_left_out(self):
        on = dict(ram_idle("all-on", 300_000), run=1)
        r = R.real_use_summary([], {"all-on": [on, dict(on, run=2, screen_off=[3.0])]}, PKG)
        self.assertEqual((r["idle"]["samples"], r["screen_off_samples"]), (1, ["all-on-2"]))

    def test_a_synthetic_run_with_the_screen_off_is_left_out(self):
        good, bad = synthetic_lines(1, 9.0), synthetic_lines(2, 90.0) + [{"t": "screen_off", "mono": 12.0}]
        runs = [R.synthetic_run(good), R.synthetic_run(bad)]
        self.assertEqual([r["screen_off"] for r in runs], [False, True])
        s = R.synthetic_summary(runs)
        self.assertEqual((s["runs"], s["screen_off_runs"]), (1, 1))
        self.assertEqual(s["js_burst_ms"]["median"], 9.0)


if __name__ == "__main__":
    unittest.main()
