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
import perflib as P  # noqa: E402
import report as R  # noqa: E402

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


def synthetic_lines(run, inp, js_ms):
    lines = [{"t": "hello", "run": run, "ua": "Mozilla/5.0 (Android 10; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0",
              "load": {"lcp_ms": None, "fcp_ms": 250, "lcp_from": "fcp"} if run == 2 else {"lcp_ms": 300, "fcp_ms": 250, "lcp_from": "lcp"}}]
    lines.append({"t": "js", "run": run, "median_ms": js_ms, "p95_ms": js_ms * 1.5, "checksum": 7, "expected": 7})
    for n, missed in [(1, 0), (2, 0), (4, 2), (8, 20), (16, 90)]:
        lines.append(step("css-transform", n, missed, run))
    for n in (1, 2):
        lines.append(step("svg", n, 0, run))
    lines.append(step("reference", 1, 3, run))
    sc = step("scroll", 240, 0, run, sf=False)
    sc["scrolled_px"] = 0 if run == 3 else 720
    lines.append(sc)
    lines.append({"t": "input", "run": run, "method": "event-timing", "taps": 10, "values": [16] * 9 + [inp], "inp_ms": inp})
    lines.append({"t": "done", "run": run})
    return lines


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
               "cpu": {"cores": 4}, "mem_total_kb": 2_000_000, "display": {"vsync_ms": 16.667},
               "app": {"package": PKG, "version_name": "0.4.3"}}
        self.write("device.json", dev)
        for k, (inp, js) in enumerate([(120, 9.0), (180, 10.0), (150, 12.0)], 1):
            with open(os.path.join(self.dir, f"synthetic-{k}.jsonl"), "w") as f:
                f.write("\n".join(json.dumps(l) for l in synthetic_lines(k, inp, js)))
            self.write(f"coldstart-{k}.json", {"run": k, "ttid_ms": 1700 + 100 * k, "am_total_ms": None,
                                                "launch": "relaunched-by-system", "stop_mono": 9.5, "proc_mono": 9.6 + 0.1 * k,
                                                "page_start_mono": 10.0, "page_stop_mono": 13.0, "ended_quiet": True,
                                                "presents_mono_ms": [13000, 13100, 13200 + 100 * k, 15000]})
            # The reference dashboard: process at 20.0 s; navigation at 20.3 s (the page's line is
            # logged 1 s after its timeOrigin, at 21.3 s); first paint 300 + 100k ms later.
            self.write(f"refstart-{k}.json", {"run": k, "ttid_ms": 111, "launch": "relaunched-by-system",
                                              "proc_mono": 20.0, "ended_quiet": True,
                                              "page": {"t": "refdash", "nodes": 1134, "fcp_ms": 300 + 100 * k, "load_ms": 200,
                                                       "origin_epoch_ms": 1e12, "epoch_ms": 1e12 + 1000, "mono": 21.3},
                                              "presents_mono_ms": [20400, 20600, 20700 + 100 * k, 25000]})
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
        self.assertEqual(self.rep["schema"], "glassworks.perf/2")
        self.assertEqual(self.rep["frame"]["id"], "test-frame")
        self.assertEqual(self.rep["frame"]["app"]["geckoview"], "159.0")

    def test_animation_capacity(self):
        lad = self.rep["graded"]["synthetic"]["ladder"]["css-transform"]
        self.assertEqual(lad["held_60fps"]["median"], 4)   # 2 of 120 over budget is 1.7 %: still good
        self.assertEqual(lad["held_30fps"]["median"], 8)
        self.assertFalse(lad["held_60fps_at_top"])
        steps = self.rep["graded"]["synthetic"]["per_run"][0]["ladder"]["css-transform"]["steps"]
        self.assertEqual([st["n"] for st in steps], [1, 2, 4, 8, 16])   # element counts, not frame counts
        self.assertEqual(steps[0]["frames"]["n"], 120)
        svg = self.rep["graded"]["synthetic"]["ladder"]["svg"]
        self.assertEqual(svg["held_60fps"]["median"], 2)
        self.assertTrue(svg["held_60fps_at_top"])   # never missed: a lower bound
        self.assertNotIn("canvas2d", self.rep["graded"]["synthetic"]["ladder"])

    def test_frames_come_from_surfaceflinger_when_it_covers_the_window(self):
        s = self.rep["graded"]["synthetic"]
        self.assertEqual(s["reference_sources"], ["surfaceflinger"])
        self.assertEqual(s["reference_limited_by"], ["equal"])
        self.assertEqual(s["steps_without_sf"], 0)
        self.assertEqual(s["per_run"][0]["scroll"]["source"], "raf")

    def test_headline_grade(self):
        g = self.rep["grade"]
        self.assertEqual(g["metrics"]["inp_ms"]["value"], 150)
        self.assertEqual(g["metrics"]["start_ms"]["value"], 800)   # process 20.0 s -> first paint 20.6 to 20.9 s
        self.assertEqual(g["metrics"]["load_ms"]["value"], 600)    # navigation 20.3 s -> settled 20.8 to 21.0 s
        self.assertEqual(self.rep["graded"]["reference_dashboard"]["android_ttid_ms"]["median"], 111)   # not graded
        self.assertEqual(self.rep["real_use"]["dashboard_load_ms"]["median"], 3400)   # not graded either
        self.assertEqual(g["components"], {"frames": "good", "load": "good", "input": "good", "start": "good"})
        self.assertEqual(g["grade"], "A")
        self.assertFalse(g["provisional"])

    def test_the_grade_does_not_move_with_your_home_assistant(self):
        """Every grade input is in `graded`; nothing from the user's own dashboard reaches it."""
        before = self.rep["grade"]
        for k in (1, 2, 3):
            self.write(f"coldstart-{k}.json", {"run": k, "ttid_ms": 9000, "launch": "am-start", "proc_mono": 1.0,
                                                "page_start_mono": 2.0, "page_stop_mono": 30.0, "ended_quiet": False,
                                                "presents_mono_ms": [30000 + 16.7 * i for i in range(600)]})
            self.write(f"idle-all-on-{k}.json", idle("all-on", k, 1_900_000, 95.0))
        rep = R.build(self.dir)
        self.assertEqual(rep["real_use"]["dashboard_load_ms"]["median"], 28000)
        self.assertEqual(rep["grade"], before)

    def test_grade_inputs_come_only_from_the_graded_part(self):
        values = R.graded_values(self.rep["graded"])
        self.assertEqual(values, {k: v["value"] for k, v in self.rep["grade"]["metrics"].items()})
        self.assertNotIn("real_use", R.graded_values.__code__.co_names)

    def test_no_reference_dashboard_means_load_and_start_are_missing(self):
        for k in (1, 2, 3):
            os.remove(os.path.join(self.dir, f"refstart-{k}.json"))
        g = R.build(self.dir)["grade"]
        self.assertEqual(g["missing"], ["load", "start"])
        self.assertTrue(g["provisional"])

    def test_js_band_is_reported_but_not_graded(self):
        self.assertEqual(self.rep["graded"]["synthetic"]["js_burst_ms"]["median"], 10.0)
        self.assertEqual(self.rep["graded"]["synthetic"]["js_band"], "good")
        self.assertTrue(self.rep["graded"]["synthetic"]["js_checksum_ok"])
        self.assertNotIn("js", self.rep["grade"]["components"])

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

    def test_reference_without_surfaceflinger_is_counted(self):
        lines = synthetic_lines(1, 100, 9.0)
        for l in lines:
            if l.get("stage") == "reference":
                l["sf"]["oldest_ms"] = 99_999
        run = R.synthetic_run(lines)
        self.assertEqual(R.synthetic_summary([run])["steps_without_sf"], 1)

    def test_no_scroll_step_is_not_a_moved_scroll(self):
        lines = [l for l in synthetic_lines(1, 100, 9.0) if l.get("stage") != "scroll"]
        self.assertIsNone(R.synthetic_summary([R.synthetic_run(lines)])["scroll_moved"])

    def test_a_scroll_that_did_not_move_is_flagged(self):
        s = self.rep["graded"]["synthetic"]
        self.assertFalse(s["scroll_moved"])
        self.assertEqual(s["per_run"][0]["scroll"]["scrolled_px"], 720)
        self.assertIn("did not move", R.markdown(self.rep))

    def test_load_that_fell_back_to_fcp_says_so(self):
        self.assertEqual(self.rep["graded"]["synthetic"]["page_lcp_from"], ["fcp", "lcp"])
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
        self.assertIn("**Grade: A**", md)
        self.assertIn("## Real use on this dashboard (not graded)", md)
        self.assertIn("both views equal", md)
        self.assertLess(md.index("## Reference dashboard (graded)"), md.index("## Real use on this dashboard"))
        self.assertIn("| css-transform | 4 (4 to 4, n=3) |", md)
        self.assertIn("| svg | 2+ (2 to 2, n=3) |", md)

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
