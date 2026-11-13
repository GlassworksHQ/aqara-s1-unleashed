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


NAV = {"light": 30.0, "typical": 40.0, "heavy": 50.0}   # when each reference dashboard starts, s
CARDS = {"light": 20, "typical": 50, "heavy": 120}


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
            "page": {"t": "refdash", "tier": tier, "protocol": G.PROTOCOL_VERSION, "nodes": 1458, "cards": CARDS[tier], "css": [960, 480], "dpr": dpr,
                     "fcp_ms": fcp_ms, "load_ms": load_ms,
                     "origin_epoch_ms": 1e12 + nav * 1000, "epoch_ms": 1e12 + nav * 1000 + 1000, "mono": nav + 1.0},
            "live_mono": nav + 3.0,
            "measure": {"t": "measure", "stage": "updates", "moving": 12, "offscreen": 0, "targets": 10, "update_every_ms": 200},
            "step": updates_step(nav, updates_missed),
            "input": {"t": "input", "method": "event-timing", "taps": 10, "inp_ms": inp},
            "swipes": [swipe(nav, i, late(60, scroll_missed)) for i in range(3)]}


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
                      "heavy": tier_entry("heavy", inp, scroll_missed=heavy_missed)},
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
        self.assertEqual(g["levels"], {"light": "premium", "typical": "premium", "heavy": "fluid"})
        self.assertEqual(g["bands"]["heavy"], {"tap": "good", "scroll": "fluid", "updates": "premium", "load": "good"})
        self.assertEqual(g["start"], {"value": 800, "band": "good"})   # process 20.0 s -> engine 20.7 to 20.9 s
        self.assertEqual(g["viewport"], {"state": "ok", "reason": None})
        self.assertFalse(g["provisional"])
        self.assertEqual(g["headline"], "test-frame: Clarity B, a good wall panel for typical dashboards; large ones stay fluid. "
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

    def test_old_protocol_data_says_re_measure_to_publish(self):
        for k in (1, 2, 3):
            run = refstart(k, 100)
            for t in run["tiers"].values():
                t["page"]["protocol"] = 1
            self.write(f"refstart-{k}.json", run)
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["grade"], "B")
        self.assertIn("Re-measure to publish: the site shows no letter for these pages.", g["headline"])

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
        self.assertIn("**test-frame: Clarity B, a good wall panel for typical dashboards; large ones stay fluid.", md)
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


if __name__ == "__main__":
    unittest.main()
