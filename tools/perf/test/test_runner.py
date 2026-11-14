"""run.sh and perfrun.py end to end against test/fake_adb.py (no device)."""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(os.path.dirname(HERE), "run.sh")
STUB_FRONTEND = os.path.join(HERE, "stub_frontend")       # the runner tests never fetch HA's frontend
STUB_CAMERA = tempfile.mkdtemp(prefix="clarity-camera-")  # nor make camera segments (no ffmpeg needed)
os.environ.setdefault("GW_PERF_CAMERA", STUB_CAMERA)
RESTORE = "http://192.0.2.10:8123/lovelace/0"
os.environ.setdefault("GW_PERF_FRONTEND", STUB_FRONTEND)
sys.path.insert(0, os.path.dirname(HERE))
import fakeha  # noqa: E402
import perfrun  # noqa: E402
import report  # noqa: E402
import threading  # noqa: E402


class FakeHA:
    """A fake Home Assistant on its own port, for perfrun runs without run.sh: perfrun sends it the
    views to open, and fake_adb takes them from it, as the harness does."""

    def __enter__(self):
        self.srv = fakeha.serve(0)
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        return self

    def __exit__(self, *exc):
        self.srv.shutdown()
        self.srv.server_close()


HA = None          # one fake Home Assistant for the perfrun runs that don't bring their own


def setUpModule():
    global HA
    HA = FakeHA().__enter__()


def tearDownModule():
    HA.__exit__()


class Runner(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        state = os.path.join(cls.tmp, "state")
        os.makedirs(state)
        adb = os.path.join(cls.tmp, "adb")
        with open(adb, "w") as f:
            f.write(f'#!/bin/sh\nexec "{sys.executable}" "{os.path.join(HERE, "fake_adb.py")}" "$@"\n')
        os.chmod(adb, 0o755)
        env = dict(os.environ, ADB=adb, FAKE_ADB_STATE=state, GW_PERF_IDLE_SETTLE="0", GW_PERF_IDLE_WINDOW="2",
                   GW_PERF_COLD_SETTLE="0", GW_PERF_RUN_GAP="0", GW_PERF_BETWEEN_QUIET="0.5", GW_PERF_FRONTEND=STUB_FRONTEND,
                   GW_PERF_SWITCH_SETTLE="0", FAKE_HA_URL="http://127.0.0.1:18089", GW_PERF_CAMERA=STUB_CAMERA)
        env.pop("ANDROID_SERIAL", None)
        cls.out = os.path.join(cls.tmp, "out")
        cls.proc = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", cls.out, "--port", "18089", "--taps", "2",
                                   "--restore-dashboard", RESTORE],
                                  env=env, capture_output=True, text=True, timeout=300)
        with open(os.path.join(state, "commands.log")) as f:
            cls.commands = f.read().splitlines()
        cls.rep = None
        if cls.proc.returncode == 0:
            with open(os.path.join(cls.out, "report.json")) as f:
                cls.rep = json.load(f)

    def test_run_succeeds(self):
        self.assertEqual(self.proc.returncode, 0, self.proc.stderr[-3000:])

    def test_report_has_every_part(self):
        r = self.rep
        self.assertEqual(r["schema"], "glassworks.perf/4")
        self.assertEqual(r["frame"]["device"]["model"], "Test Panel")
        self.assertEqual(r["frame"]["app"]["geckoview"], "159.0")
        g = r["grade"]
        self.assertEqual(g["grade"], "A+")                          # the fake panel holds 60 fps everywhere
        self.assertFalse(g["provisional"], g["missing"])
        self.assertTrue(g["headline"].startswith("test-frame: Clarity A+, premium."),
                        g["headline"])
        self.assertEqual(g["viewport"]["state"], "ok")
        ref = r["graded"]["reference"]
        self.assertEqual(ref["errors"], [])
        self.assertEqual(set(ref["tiers"]), {"light", "typical", "heavy"})
        self.assertEqual(ref["engine_uris"], ["about:blank"])
        self.assertEqual(ref["own_page_states"], ["reference"])        # the kiosk came back to the fake HA
        self.assertGreater(ref["start_ms"]["median"], 0)                  # restart: process start to usable
        self.assertEqual(ref["restart_to"], "usable")
        self.assertEqual(ref["engine_ready_ms"]["n"], 1)                  # kept as detail
        self.assertEqual(ref["orders"], [["light", "typical", "heavy"]])
        self.assertEqual(ref["per_run"][0]["between"], ["still", "still", "still"])   # after the restart page and each page
        for t in ("light", "typical", "heavy"):
            s = ref["tiers"][t]
            self.assertIsNotNone(s["load_ms"], t)
            self.assertIsNotNone(s["inp_ms"], t)
            self.assertEqual(s["scroll_sources"], ["surfaceflinger"], t)
            self.assertEqual(s["sf_valid"], [True], t)
            self.assertEqual(s["scrolled_px"]["median"], 1400, t)   # two swipes of 700 px
            self.assertEqual(s["updates_level"], "premium", t)           # changes on screen within a frame or two
            self.assertEqual(s["swipes_during_updates"], [2], t)
        self.assertEqual(g["start"]["value"], ref["start_ms"]["median"])
        self.assertEqual(r["real_use"]["android_ttid_ms"]["median"], 1714)
        self.assertEqual(r["real_use"]["settle_how"], ["quiet-gap"])
        self.assertEqual(r["real_use"]["memory"]["idle"]["app_pss_kb"]["median"], 570000)
        self.assertEqual(r["real_use"]["idle"]["samples"], 1)        # the reference dashboard's samples aren't real use
        self.assertEqual({p["id"] for p in r["real_use"]["panes"]}, {"radio"})

    def test_ram_on_the_reference_dashboards(self):
        """#68: RAM per process at idle on the typical reference dashboard after the graded run, and the
        peak from the samples between the graded pages (one after each)."""
        ram = self.rep["ram"]
        self.assertEqual(ram["reference_idle"]["samples"], 1)
        self.assertEqual(ram["reference_idle"]["app_pss_kb"]["median"], 570000)
        self.assertEqual(set(ram["reference_idle"]["by_role_pss_kb"]), {"main", "gpu", "tab"})
        self.assertEqual(ram["graded_peak"]["samples"], 3)
        self.assertEqual(ram["graded_peak"]["app_pss_kb"], 570000)
        self.assertEqual(ram["graded_peak"]["app_rss_hwm_kb"], 900000)      # VmHWM of the three processes
        with open(os.path.join(self.out, "refstart-1.json")) as f:
            self.assertEqual([m["after"] for m in json.load(f)["memory"]], ["light", "typical", "heavy"])
        self.assertTrue(ram["budget"]["ok"])
        self.assertEqual((ram["panes"], ram["panes_not_switched"]), ([], []))       # test-frame has no panes
        with open(os.path.join(self.out, "report.md")) as f:
            self.assertIn("## RAM on the reference dashboards", f.read())
        with open(os.path.join(self.tmp, "state", "opened_still.json")) as f:
            self.assertEqual(json.load(f), ["http://127.0.0.1:18089/clarity-ref/typical"])

    def test_it_changes_one_setting_and_puts_it_back(self):
        """Protocol 4: the kiosk's saved dashboard points at the fake HA's start view for the graded
        part, then back at --restore-dashboard (with -W, checked). Nothing else changes."""
        shell = [c[len("shell "):] for c in self.commands if c.startswith("shell ")]
        urls = [re.search(r"--es url '([^']+)'", c).group(1) for c in shell if "--es url" in c]
        self.assertEqual(urls, ["http://127.0.0.1:18089/clarity-ref/start", RESTORE])
        self.assertIn("am start -W -n sh.edm.s1kiosk/.MainActivity --es url '" + RESTORE + "'", shell)
        i_set = next(i for i, c in enumerate(self.commands) if "--es url" in c)
        i_back = max(i for i, c in enumerate(self.commands) if "--es url" in c)
        graded = [i for i, c in enumerate(self.commands) if c.startswith("view ")]
        self.assertLess(i_set, graded[0])
        self.assertGreater(i_back, graded[-1])

    def test_it_changes_no_other_setting(self):
        shell = [c[len("shell "):] for c in self.commands if c.startswith("shell ")]
        inputs = sorted({c for c in shell if c.startswith("input ")})
        # Taps on the toggle and swipes where the page asked, with its coordinates: nothing else.
        self.assertEqual(inputs, ["input keyevent KEYCODE_WAKEUP", "input swipe 720 612 720 108 300", "input tap 720 360"])
        self.assertEqual(sum(1 for c in shell if c == "input tap 720 360"), 6)                 # 2 on each page
        self.assertEqual(sum(1 for c in shell if c.startswith("input swipe ")), 6)            # 2 on each page
        forbidden = re.compile(r"(^|[;&|]\s*)(settings|svc|cmd|setprop|am broadcast|pm (?!path\b))|--ez ")   # no pane is switched without --pane-ram
        self.assertEqual([c for c in shell if forbidden.search(c)], [])
        extras = {m for c in shell for m in re.findall(r"--e\w \w+", c)}
        self.assertEqual(extras, {"--es open", "--es screen", "--es url"})
        opens = [re.search(r"--es open '([^']+)'", c).group(1) for c in shell if "--es open" in c]
        # The animation test page and the idle sample's page load; the graded views don't: they open
        # inside the loaded frontend, by navigation, on the runner's command to the fake HA.
        self.assertEqual(len(opens), 2)
        self.assertTrue(opens[0].startswith("http://127.0.0.1:18089/clarity/page/index.html?"), opens[0])
        self.assertEqual(opens[1], "http://127.0.0.1:18089/clarity-ref/typical")       # the idle sample's page, no nonce
        views = [c.split()[1:4:2] for c in self.commands if c.startswith("view ")]
        self.assertEqual(views, [["light", "taps=2"], ["typical", "taps=2"], ["heavy", "taps=2"]])

    def test_no_gesture_without_the_current_pages_request(self):
        """The fake rejects any tap or swipe that no current *-ready line asked for."""
        with open(os.path.join(self.tmp, "state", "unasked.json")) if os.path.exists(
                os.path.join(self.tmp, "state", "unasked.json")) else open(os.devnull) as f:
            self.assertEqual(json.loads(f.read() or "[]"), [])

    def test_swipes_come_only_after_the_pages_own_request(self):
        """Each swipe follows a swipe-ready from the page being measured: the commands log has no swipe
        before the first reference page opened."""
        first_open = next(i for i, c in enumerate(self.commands) if c.startswith("view "))
        first_swipe = next(i for i, c in enumerate(self.commands) if c.startswith("shell input swipe"))
        self.assertGreater(first_swipe, first_open)

    def test_the_reference_dashboards_open_only_once_the_session_is_up(self):
        """The first view opens only after the restart (the restart page said it was usable)."""
        i_blank = next(i for i, c in enumerate(self.commands) if c.startswith("view "))
        stops = [i for i, c in enumerate(self.commands) if c.startswith("shell am force-stop")]
        self.assertGreater(i_blank, stops[0])

    def test_launch_without_a_system_relaunch_uses_am_start(self):
        self.assertEqual(self.rep["real_use"]["launch"], ["am-start"])

    def test_the_dashboard_comes_back_last(self):
        starts = [c for c in self.commands if c.startswith("shell am start") and "screen on" not in c]
        self.assertTrue(starts[-1].startswith("shell am start -W"), starts[-3:])


def fake_env(tmp, **extra):
    state = os.path.join(tmp, "state")
    os.makedirs(state, exist_ok=True)
    adb = os.path.join(tmp, "adb")
    with open(adb, "w") as f:
        f.write(f'#!/bin/sh\nexec "{sys.executable}" "{os.path.join(HERE, "fake_adb.py")}" "$@"\n')
    os.chmod(adb, 0o755)
    extra.setdefault("FAKE_HA_URL", HA.url if HA else "")
    env = dict(os.environ, ADB=adb, FAKE_ADB_STATE=state, GW_PERF_IDLE_SETTLE="0", GW_PERF_IDLE_WINDOW="1",
               GW_PERF_FRONTEND=STUB_FRONTEND, GW_PERF_CAMERA=STUB_CAMERA, GW_PERF_SWITCH_SETTLE="0", GW_PERF_PANES_SAVE_S="0", **extra)
    env.pop("ANDROID_SERIAL", None)
    return env, state


class StopsPartway(unittest.TestCase):
    """The graded part changes the kiosk's dashboard; a run that stops partway still puts it back
    (run.sh's exit trap), so the panel is left as it was found."""

    def test_a_restore_the_kiosk_refuses_fails_the_run_and_says_how_to_fix_it(self):
        """F3: am exits 0 even when the intent wasn't taken. The runner reads its answer, tries again
        on exit, and exits 3 with the command to send, instead of assuming the panel is back."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_RESTORE_FAILS="1", GW_PERF_COLD_SETTLE="0", GW_PERF_RUN_GAP="0",
                              GW_PERF_BETWEEN_QUIET="0.3", FAKE_HA_URL="http://127.0.0.1:18097")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", os.path.join(tmp, "out"), "--port", "18097",
                            "--taps", "0", "--skip-synthetic", "--skip-real-use", "--restore-dashboard", RESTORE],
                           env=env, capture_output=True, text=True, timeout=300)
        self.assertEqual(p.returncode, 3, p.stderr[-2000:])
        self.assertIn("COULD NOT point the kiosk back", p.stderr)
        self.assertIn(f"send it yourself: adb shell am start -n sh.edm.s1kiosk/.MainActivity --es url '{RESTORE}'", p.stderr)
        with open(os.path.join(state, "restore_refused.json")) as f:
            self.assertEqual(json.load(f), 3)            # after the graded part, then twice on exit
        with open(os.path.join(state, "commands.log")) as f:
            after = f.read().split(RESTORE, 1)[1]
        self.assertNotIn("am force-stop", after)          # no restart onto the fake, which is going away

    def test_the_dashboard_goes_back(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_IDLE_FAILS="1", GW_PERF_COLD_SETTLE="0", GW_PERF_RUN_GAP="0",
                              GW_PERF_BETWEEN_QUIET="0.3", FAKE_HA_URL="http://127.0.0.1:18095")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "2", "--out", os.path.join(tmp, "out"), "--port", "18095",
                            "--taps", "0", "--skip-synthetic", "--skip-real-use", "--restore-dashboard", RESTORE],
                           env=env, capture_output=True, text=True, timeout=300)
        self.assertNotEqual(p.returncode, 0)                       # the idle sample after graded run 1 failed
        self.assertTrue(os.path.exists(os.path.join(tmp, "out", "refstart-1.json")))
        self.assertFalse(os.path.exists(os.path.join(tmp, "out", "refstart-2.json")))
        with open(os.path.join(state, "urls_set.json")) as f:
            self.assertEqual(json.load(f), ["http://127.0.0.1:18095/clarity-ref/start", RESTORE])
        self.assertIn("pointing the kiosk back at its dashboard", p.stderr)


class HomeRelaunch(unittest.TestCase):
    """The S1's kiosk is the home app: force-stop and the system starts it again at once."""

    def test_the_system_relaunch_is_the_cold_start(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_HOME_RELAUNCH="1")
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "coldstart",
                            "--out", tmp, "--run", "1", "--settle", "3"], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(tmp, "coldstart-1.json")) as f:
            c = json.load(f)
        self.assertEqual(c["launch"], "relaunched-by-system")
        self.assertEqual(c["ttid_ms"], 1714)
        self.assertIsNotNone(c["page_stop_mono"])
        with open(os.path.join(state, "commands.log")) as f:
            self.assertNotIn("am start -W", f.read())

    def test_graded_run_on_a_home_app_kiosk(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_HOME_RELAUNCH="1")
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                            "--out", tmp, "--run", "1", "--settle", "3", "--url", HA.url,
                            "--taps", "0"], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(tmp, "refstart-1.json")) as f:
            c = json.load(f)
        self.assertEqual(c["launch"], "relaunched-by-system")
        self.assertEqual(c["own_page_state"], "finished-still")
        self.assertEqual(sorted(c["tiers"]), ["heavy", "light", "typical"])
        self.assertTrue(all("step" in e and "page" in e for e in c["tiers"].values()))
        self.assertGreater(c["engine_mono"], c["proc_mono"])


def refstart(tmp, run=1, taps=0, **env_extra):
    with FakeHA() as ha:
        # As run.sh leaves it for the graded part: the kiosk points at the reference dashboard's start view.
        env_extra.setdefault("FAKE_SAVED_URL", ha.url + "/clarity-ref/start")
        env, state = fake_env(tmp, GW_PERF_COLD_SETTLE="0", FAKE_HA_URL=ha.url, **env_extra)
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                            "--out", tmp, "--run", str(run), "--settle", "0", "--url", ha.url,
                            "--taps", str(taps), "--timeout", "90", "--between-quiet", "0.3"], env=env, capture_output=True, text=True, timeout=150)
    with open(os.path.join(tmp, f"refstart-{run}.json")) as f:
        return p, json.load(f)


def unasked(tmp):
    p = os.path.join(tmp, "state", "unasked.json")
    if not os.path.exists(p):
        return []
    with open(p) as f:
        return json.load(f)


class FakeMatchesThePanel(unittest.TestCase):
    """smoke7 failed on the panel while CI was green: the fake logged shorter lines than the page
    does. The fake must log the reference page's lines in the shapes the S1 logged them
    (test/page-lines.json), and as long."""

    def test_every_line_has_the_panels_keys(self):
        with open(os.path.join(HERE, "page-lines.json")) as f:
            want = json.load(f)["shapes"]
        tmp = tempfile.mkdtemp()
        p, _ = refstart(tmp, taps=2, FAKE_SWIPES="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        got = {}
        with open(os.path.join(tmp, "state", "page-lines.jsonl")) as f:
            for l in f:
                d = json.loads(l)
                got.setdefault(d["shape"], set()).add(tuple(d["keys"]))
        self.assertEqual(sorted(got), sorted(want))
        for shape, keys in want.items():
            with self.subTest(shape):
                self.assertEqual(got[shape], {tuple(keys)})


class FakeSplitsLikeThePage(unittest.TestCase):
    """The fake's page_lines must give exactly what the page's consoleLines (stats.js) gives."""

    def test_the_fake_and_the_page_split_alike(self):
        node = shutil.which("node")
        if not node:
            if os.environ.get("CI"):
                raise AssertionError("node is needed")
            raise unittest.SkipTest("node isn't installed (CI has it)")
        sys.path.insert(0, HERE)
        os.environ.setdefault("FAKE_ADB_STATE", tempfile.mkdtemp())
        import fake_adb as F
        texts = ['{"t":"done"}', json.dumps({"x": list(range(500))}, separators=(",", ":")),
                 json.dumps({"names": ["Küche %d 🌡 Wohnzimmer" % i for i in range(200)]}, ensure_ascii=False),
                 "a" * 899 + " " + "b" * 1200]
        js = ("const S=require(process.argv[1]);const ts=JSON.parse(require('fs').readFileSync(0,'utf8'));"
              "process.stdout.write(JSON.stringify(ts.map((t)=>S.consoleLines('GWPERF','k:1',t))))")
        out = subprocess.run([node, "-e", js, os.path.join(os.path.dirname(HERE), "page", "stats.js")],
                             input=json.dumps(texts), capture_output=True, text=True, timeout=30)
        page = json.loads(out.stdout)
        for text, want in zip(texts, page):
            with self.subTest(text[:20]):
                self.assertEqual(F.page_lines("k:1", text), want)
                self.assertTrue(all(len(l.encode()) <= 1023 for l in want))

    def test_the_fakes_protocol_is_the_pages(self):
        sys.path.insert(0, HERE)
        os.environ.setdefault("FAKE_ADB_STATE", tempfile.mkdtemp())
        import fake_adb as F
        with open(os.path.join(os.path.dirname(HERE), "page", "tiers.json")) as f:
            self.assertEqual(json.load(f)["PROTOCOL"], F.PROTOCOL)
        # Equal today also when the fake writes the number itself; it must read it from the pages.
        with open(os.path.join(HERE, "fake_adb.py")) as f:
            self.assertEqual(re.findall(r"PROTOCOL\s*=\s*\d|[\"']protocol[\"']\s*:\s*\d", f.read()), [])


class LongLines(unittest.TestCase):
    """The S1 keeps 1023 bytes of a page's console line (smoke7, 2026-11-12). Pages split longer
    lines into parts (stats.js consoleLines) and the runner joins them (perflib.PageLines)."""

    def test_a_run_with_real_sized_lines_completes(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertIsNone(c["error"])
        self.assertEqual((c["missing_steps"], c["bad_page_lines"]), ([], 0))
        for t, e in c["tiers"].items():
            self.assertEqual(len(e["step"]["changes"]), 48, t)            # about 1.8 KB, joined from parts

    def test_smoke7_unsplit_lines_are_cut_and_said_not_a_failed_run(self):
        """The page lines as d60ec95 logged them: the updates steps are cut at 1023 bytes. The run
        isn't failed: scroll, taps and loads stand, and the report says what didn't parse."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_NO_SPLIT="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertIsNone(c["error"])
        self.assertEqual(c["missing_steps"], ["light", "typical", "heavy"])
        # Each page's updates step, and heavy's camera line (four streams and the HLS fetches: also
        # longer than a logcat line).
        self.assertEqual(c["bad_page_lines"], 4)
        self.assertNotIn("camera", c["tiers"]["heavy"])
        self.assertIn("camera", c["tiers"]["typical"])                   # one stream: short enough
        with open(os.path.join(tmp, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "display": {"density": "240"}, "app": {"package": "sh.edm.s1kiosk"}}, f)
        rep = report.build(tmp)
        self.assertIn("4 page line(s) didn't parse or never arrived whole (cut off in logcat?)", rep["grade"]["provisional_reasons"])
        t = rep["graded"]["reference"]["tiers"]["light"]
        self.assertIsNotNone(t["scroll"])
        self.assertEqual(t["updates_errors"], ["the page's updates step never arrived"])
        self.assertNotIn("protocol ;", rep["grade"]["headline"])

    def test_a_line_whose_parts_never_all_arrive_is_counted_at_done(self):
        """Verifier ce144dd0: a part left pending was dropped silently. At the page's done it counts."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_DROP_LAST_PART="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["missing_steps"], ["light", "typical", "heavy"])
        self.assertEqual(c["bad_page_lines"], 3)                         # each page's
        self.assertEqual([e["lost_lines"] for e in c["tiers"].values()], [1, 1, 1])

    def test_a_lost_scroll_step_is_counted_and_makes_it_provisional(self):
        """A swipe answered with no scroll step back would leave 5 of 6 swipes pooled, unremarked."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_DROP_SCROLL_STEP="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual([e["lost_scroll_steps"] for e in c["tiers"].values()], [1, 1, 1])
        self.assertEqual(c["bad_page_lines"], 0)                         # nothing cut: the line never came
        with open(os.path.join(tmp, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "display": {"density": "240"}, "app": {"package": "sh.edm.s1kiosk"}}, f)
        g = report.build(tmp)["grade"]
        self.assertIn("3 swipe(s) answered with no scroll step back", g["provisional_reasons"])

    def test_page_lines_joins_parts_and_counts_what_doesnt_parse(self):
        pl = perfrun.P.PageLines()
        self.assertEqual(pl.feed('x GWPERF {"t":"done"}'), {"t": "done"})
        self.assertIsNone(pl.feed('GWPERF+ ab:1 0/2 {"t":"st'))
        self.assertIsNone(pl.feed("an unrelated line in between"))
        self.assertEqual(pl.feed('GWPERF+ ab:1 1/2 ep","n":1}'), {"t": "step", "n": 1})
        self.assertIsNone(pl.feed('GWPERF {"t":"step","changes":[[1,2'))     # cut off
        self.assertIsNone(pl.feed("GWPERF+ nonsense"))
        self.assertEqual(pl.bad, 2)
        self.assertIsNone(pl.feed('GWPERF+ cd:9 0/3 {"t":'))               # page cd's line, never finished
        self.assertIsNone(pl.feed('GWPERF+ ef:2 0/2 {"t":'))               # another page's
        self.assertEqual((pl.close("cd"), pl.bad), (1, 3))
        self.assertEqual(sorted(pl.parts), ["ef:2"])

    def test_the_pages_splitter_and_the_runners_joiner_agree(self):
        """stats.js (run in node) splits; perflib joins. Every part fits the panel's 1023 bytes."""
        node = shutil.which("node")
        if not node:
            if os.environ.get("CI"):
                raise AssertionError("node is needed")
            raise unittest.SkipTest("node isn't installed (CI has it)")
        text = json.dumps({"t": "step", "names": ["Entity %d sensor" % i for i in range(120)], "x": list(range(200))},
                          separators=(",", ":"))
        js = ("const S=require(process.argv[1]);const t=require('fs').readFileSync(0,'utf8');"
              "process.stdout.write(JSON.stringify(S.consoleLines('GWPERF','n0:7',t)))")
        out = subprocess.run([node, "-e", js, os.path.join(os.path.dirname(HERE), "page", "stats.js")],
                             input=text, capture_output=True, text=True, timeout=30)
        lines = json.loads(out.stdout)
        self.assertGreater(len(lines), 2)
        self.assertTrue(all(len(l.encode()) <= 1023 for l in lines), [len(l) for l in lines])
        self.assertTrue(all(l == l.strip() for l in lines))                  # logcat may trim
        pl = perfrun.P.PageLines()
        got = [pl.feed("I Isolated Web Content: " + l) for l in lines]
        self.assertEqual(got[-1], json.loads(text))
        self.assertEqual(pl.bad, 0)


class NonceGate(unittest.TestCase):
    """Only the current page's nonce may make the runner tap or swipe."""

    def test_a_stale_request_from_the_last_page_is_ignored(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_STALE_READY="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(unasked(tmp), [])
        with open(os.path.join(tmp, "state", "swipes.json")) as f:
            self.assertEqual(json.load(f), 6)                 # 2 on each graded page, none on the warm-up
        self.assertIsNone(c["error"])


class Gestures(unittest.TestCase):
    """Page-given coordinates are checked before anything reaches `adb shell`."""
    SIZE = (1440, 720)

    def test_good_requests(self):
        self.assertEqual(perfrun.gesture({"t": "input-ready", "x": 720, "y": 360, "taps": 10}, self.SIZE), ("input tap 720 360", 10))
        self.assertEqual(perfrun.gesture({"t": "swipe-ready", "x": 720, "y1": 612, "y2": 108, "ms": 300}, self.SIZE),
                         ("input swipe 720 612 720 108 300", 1))

    def test_bad_requests_are_refused_not_raised(self):
        bad = [{"t": "swipe-ready", "x": 720, "y1": 612, "y2": 108},                     # no ms (a KeyError before)
               {"t": "swipe-ready", "x": "720; reboot", "y1": 612, "y2": 108, "ms": 300},
               {"t": "swipe-ready", "x": 720.5, "y1": 612, "y2": 108, "ms": 300},
               {"t": "swipe-ready", "x": True, "y1": 612, "y2": 108, "ms": 300},
               {"t": "swipe-ready", "x": 720, "y1": 612, "y2": 720, "ms": 300},           # one past the bottom
               {"t": "swipe-ready", "x": 720, "y1": 612, "y2": 108, "ms": 10},
               {"t": "swipe-ready", "x": 720, "y1": 612, "y2": 108, "ms": 60000},
               {"t": "input-ready", "x": -1, "y": 360, "taps": 10},
               {"t": "input-ready", "x": 720, "y": 360, "taps": 0},
               {"t": "input-ready", "x": 720, "y": 360, "taps": 10_000}]
        for pl in bad:
            cmd, why = perfrun.gesture(pl, self.SIZE)
            self.assertIsNone(cmd, pl)
            self.assertIsInstance(why, str)
        self.assertEqual(perfrun.gesture({"t": "input-ready", "x": 1, "y": 1, "taps": 1}, None), (None, "the screen size is unknown"))

    def test_a_bad_swipe_line_is_an_error_on_that_page_and_nothing_is_sent(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_SWIPES="1", FAKE_SWIPE_WAIT="1",
                        FAKE_BAD_SWIPE=json.dumps({"t": "swipe-ready", "n": 0, "x": "720; reboot", "y1": 612, "y2": 108}))
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertTrue(c["tiers"]["light"]["error"].startswith("bad swipe-ready line: a swipe at '720; reboot'"))
        with open(os.path.join(tmp, "state", "commands.log")) as f:
            log = f.read()
        self.assertNotIn("reboot", log)
        self.assertNotIn("input swipe", log)


class OwnPageWait(unittest.TestCase):
    """The A fix's time rule, pinned directly: 2 s after the own page stopped, with no new load."""

    def test_the_tap_cap_holds_whatever_taps_asks(self):
        """--taps 100 doesn't let a page ask for 100 taps."""
        self.assertEqual(perfrun.tap_cap(100), perfrun.MAX_TAPS)
        self.assertEqual((perfrun.tap_cap(10), perfrun.tap_cap(0)), (10, perfrun.MAX_TAPS))
        req = {"t": "input-ready", "x": 720, "y": 360, "taps": 100}
        self.assertIsNone(perfrun.gesture(req, (1440, 720), max_taps=perfrun.tap_cap(100))[0])

    def test_two_seconds_after_the_stop(self):
        self.assertFalse(perfrun.own_page_finished(None, 0.0, 100.0))
        self.assertFalse(perfrun.own_page_finished(26.0, 100.0, 101.99))
        self.assertTrue(perfrun.own_page_finished(26.0, 100.0, 102.0))
        self.assertEqual(perfrun.OWN_PAGE_QUIET_S, 2.0)


class Order(unittest.TestCase):
    """smoke5: light always came first, right after the kiosk's own dashboard was torn down, and read
    worse than heavy. A warm-up page takes that, and the order turns each run."""

    def opened(self, tmp):
        with open(os.path.join(tmp, "state", "opened.json")) as f:
            return json.load(f)

    def test_run_2_starts_one_page_in_after_the_restart_page(self):
        """Protocol 4: the kiosk's own page after the restart (the light reference dashboard, not
        graded as light) is the warm-up; then the order turns."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, run=2)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["order"], ["typical", "heavy", "light"])
        self.assertEqual(self.opened(tmp), ["typical", "heavy", "light"])
        self.assertIsNotNone(c["restart_page"])
        self.assertEqual(len(c["between"]), 3)

    def test_run_3(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, run=3)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(self.opened(tmp), ["heavy", "light", "typical"])


class Restart(unittest.TestCase):
    """Protocol 4: restart = process start to the kiosk's own page (the reference dashboard run.sh
    pointed it at) being usable on screen."""

    def build(self, tmp):
        with open(os.path.join(tmp, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "display": {"density": "240"}, "app": {"package": "sh.edm.s1kiosk"}}, f)
        return report.build(tmp)

    def test_restart_ends_at_the_usable_reference_dashboard(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["restart_page"]["usable"]["first_screen_cards"], 13)
        run = self.build(tmp)["graded"]["reference"]["per_run"][0]
        self.assertIsNone(run["start_error"])
        # Usable comes after the engine is ready: the restart now includes loading HA's frontend.
        self.assertGreater(run["start_ms"], run["engine_ready_ms"])

    def test_the_views_wait_for_the_restart_page_not_its_load_event(self):
        """HA's load event comes seconds before the start view is usable (on the S1, 10 s restarts).
        A page that has finished loading mustn't open the views: the restart's usable line would then
        arrive while a view is current and be dropped (CodeRabbit on 0c620b0)."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_RESTART_SLOW="3")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertIsNotNone(c.get("restart_page"))
        self.assertEqual(c["own_page_state"], "reference")
        self.assertIsNone(self.build(tmp)["graded"]["reference"]["per_run"][0]["start_error"])

    def test_a_lost_restart_done_line_doesnt_stall_the_run(self):
        """F8: only the restart page's `done` line is lost. The usable line is there, so the views
        open after RESTART_DONE_WAIT_S instead of waiting out the run, and the restart still counts."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_NO_RESTART_DONE="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertTrue(c["restart_done_missing"])
        self.assertEqual(sorted(c["tiers"]), ["heavy", "light", "typical"])
        self.assertIsNone(self.build(tmp)["graded"]["reference"]["per_run"][0]["start_error"])

    def run_short(self, timeout, **env):
        with FakeHA() as ha:
            tmp = tempfile.mkdtemp()
            env.setdefault("FAKE_SAVED_URL", ha.url + "/clarity-ref/start")
            e, state = fake_env(tmp, GW_PERF_COLD_SETTLE="0", FAKE_HA_URL=ha.url, **env)
            p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart", "--out", tmp,
                                "--run", "1", "--settle", "0", "--url", ha.url, "--taps", "0", "--timeout", str(timeout),
                                "--between-quiet", "0.3"], env=e, capture_output=True, text=True, timeout=timeout + 60)
            with open(os.path.join(tmp, "refstart-1.json")) as f:
                c = json.load(f)
            with open(os.path.join(state, "commands.log")) as f:
                return p, c, f.read().splitlines()

    def test_a_screen_that_goes_off_fails_the_run(self):
        """smoke10: the kiosk's 20 s night timeout turned the screen off while views opened; nothing timed
        then is the panel's, so the run isn't graded."""
        p, c, _ = self.run_short(90, FAKE_SCREEN_OFF="typical")
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("the screen went off during the graded run", c["error"])
        self.assertEqual(c["screen_off"][0]["during"], "typical")

    def test_a_view_that_never_finishes_fails_the_run(self):
        """smoke10's heavy: usable, then nothing (the screen was off) until the run's limit."""
        p, c, _ = self.run_short(25, FAKE_HANG="heavy")
        self.assertNotEqual(p.returncode, 0)
        self.assertEqual(c["error"], "heavy didn't finish within 25 s")

    def test_the_screen_is_kept_awake_while_views_open_but_not_while_live(self):
        p, c, log = self.run_short(120, FAKE_SLOW_VIEW="2.5", GW_PERF_KEEP_AWAKE_S="0.7")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertGreaterEqual(c["wakes"], 6)                 # about 3 while each slow view opened
        live = False
        for line in log:
            if line.startswith("page warmup"):
                live, during = True, 0
            elif line.startswith("page done"):
                # The runner's own wake at the warm-up, and at most one that came in the moment before
                # it read the warm-up line (that moment is in the warm-up second, before any window).
                # Waking while live would be about five a view here (every 0.7 s for 3.5 s).
                self.assertLessEqual(during, 2, "wakes while the view was live, besides the one at its warm-up")
                live = False
            elif live and "--es screen on" in line:
                during += 1

    def test_a_warm_up_view_that_is_also_graded_is_refused(self):
        with FakeHA() as ha:
            tmp = tempfile.mkdtemp()
            env, _ = fake_env(tmp, FAKE_HA_URL=ha.url)
            p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart", "--out", tmp,
                                "--url", ha.url, "--warmup-tier", "light"], env=env, capture_output=True, text=True, timeout=60)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("cached the second time", p.stderr)

    def test_no_reference_dashboard_after_the_restart_is_not_measured(self):
        """If the kiosk came back to something else, the restart isn't measured (never engine ready instead)."""
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_RESTART_PAGE_LOST="1")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        rep = self.build(tmp)
        run = rep["graded"]["reference"]["per_run"][0]
        self.assertIsNone(run["start_ms"])
        self.assertEqual(run["start_error"], "the kiosk didn't come back to a reference dashboard")
        self.assertIn("start", rep["grade"]["missing"])


class Cameras(unittest.TestCase):
    """#174: the views with streams carry a camera line; a stream that never plays fails the task
    (it was measured: the panel didn't play it), it doesn't make the page "not measured"."""

    def build(self, tmp):
        with open(os.path.join(tmp, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "display": {"density": "240"}, "app": {"package": "sh.edm.s1kiosk"}}, f)
        return report.build(tmp)

    def test_streams_that_play(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["tiers"]["heavy"]["camera"]["expected"], 4)
        self.assertNotIn("camera", c["tiers"]["light"])
        tiers = self.build(tmp)["graded"]["reference"]["tiers"]
        self.assertEqual((tiers["typical"]["camera_level"], tiers["heavy"]["camera_level"]), ("good", "good"))
        self.assertIsNone(tiers["light"]["camera_level"])
        # Heavy's camera line is longer than a logcat line: it went out in parts and came back whole.
        with open(os.path.join(HERE, "page-lines.json")) as f:
            line_max = json.load(f)["max_line_bytes"]
        heavy = c["tiers"]["heavy"]["camera"]
        self.assertGreater(len(json.dumps({k: v for k, v in heavy.items() if k != "mono"}, separators=(",", ":"))), line_max)
        self.assertEqual((heavy["fetches"]["streams"], len(heavy["streams"])), (4, 4))
        self.assertEqual(tiers["heavy"]["camera_runs"][0]["fetches"]["segment"]["n"], 20)
        # Which decoders each view got, from logcat (detail): one hardware decoder per stream.
        self.assertEqual(c["media"]["heavy"], {"codecs": ["OMX.allwinner.video.decoder.avc"] * 4, "flushes": 2, "eos": 1})
        self.assertEqual(c["media"]["typical"]["codecs"], ["OMX.allwinner.video.decoder.avc"])
        self.assertNotIn("light", c["media"])
        self.assertEqual(tiers["heavy"]["camera_runs"][0]["media"]["flushes"], 2)

    def test_a_stream_that_never_plays_fails_the_task(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, FAKE_NO_VIDEO="heavy")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        rep = self.build(tmp)
        self.assertEqual(rep["graded"]["reference"]["tiers"]["heavy"]["camera_level"], "slow")
        self.assertNotIn("heavy.camera", rep["grade"]["missing"])
        self.assertEqual(rep["grade"]["bands"]["heavy"]["camera"], "slow")


class NoisyLogcat(unittest.TestCase):
    """logcat follows the whole device, so its queue is almost never empty. No wait may depend on it."""

    def test_unrelated_lines_twice_a_second(self):
        p, c = refstart(tempfile.mkdtemp(), FAKE_LOGCAT_NOISE="0.5")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(sorted(c["tiers"]), ["heavy", "light", "typical"])

    def test_unrelated_lines_twenty_a_second(self):
        p, c = refstart(tempfile.mkdtemp(), FAKE_LOGCAT_NOISE="0.05")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["own_page_state"], "reference")

    def test_the_own_page_wait_with_noise_when_the_kiosk_shows_its_own_dashboard(self):
        """The fallback, when the kiosk wasn't pointed at the fake HA: wait for its own page."""
        p, c = refstart(tempfile.mkdtemp(), FAKE_LOGCAT_NOISE="0.05", FAKE_SAVED_URL="")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["own_page_state"], "finished-still")

    def test_a_reload_more_than_2_s_later_restarts_the_wait_even_with_noise(self):
        p, c = refstart(tempfile.mkdtemp(), FAKE_OWN_RELOAD="1", FAKE_OWN_RELOAD_DELAY="2.6", FAKE_LOGCAT_NOISE="0.05",
                        FAKE_SAVED_URL="")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["own_page_loads"], 2)
        self.assertIsNone(c["error"])
        # The recorded stop is the second load's, and the first reference page opened 2 s after it.
        self.assertGreater(c["own_page_stop_mono"], c["own_page_start_mono"])
        # The first view opens 2 s after the kiosk's own page stopped (it says so in its refdash line:
        # a view opened by navigation loads no page).
        self.assertGreaterEqual(c["tiers"]["light"]["page"]["mono"] - c["own_page_stop_mono"], 2.0)


class ForeignPage(unittest.TestCase):
    """While the reference dashboards are measured, any other page the kiosk loads, before the
    reference page starts or after, makes the run an error: it isn't graded."""

    def run_with(self, where):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp, FAKE_FOREIGN_PAGE=where, GW_PERF_COLD_SETTLE="0")
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                            "--out", tmp, "--run", "1", "--settle", "0", "--url", HA.url,
                            "--taps", "0"], env=env, capture_output=True, text=True, timeout=120)
        with open(os.path.join(tmp, "refstart-1.json")) as f:
            return p.returncode, json.load(f)

    def test_a_page_before_the_reference_page(self):
        code, c = self.run_with("before")
        self.assertNotEqual(code, 0)
        self.assertEqual(c["error"], "the kiosk loaded another page while a reference dashboard was measured")

    def test_a_page_after_the_last_reference_page(self):
        code, c = self.run_with("end")
        self.assertNotEqual(code, 0)
        self.assertEqual(c["error"], "the kiosk loaded another page while a reference dashboard was measured")

    def test_a_second_load_of_the_kiosks_own_page_restarts_the_wait(self):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp, FAKE_OWN_RELOAD="1", GW_PERF_COLD_SETTLE="0")
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                            "--out", tmp, "--run", "1", "--settle", "0", "--url", HA.url,
                            "--taps", "0"], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(tmp, "refstart-1.json")) as f:
            c = json.load(f)
        self.assertEqual(c["own_page_loads"], 2)
        self.assertEqual(c["engine_uri"], "about:blank")
        self.assertIsNone(c["error"])

    def test_a_page_after_the_reference_page(self):
        code, c = self.run_with("after")
        self.assertNotEqual(code, 0)
        self.assertEqual(c["error"], "the kiosk loaded another page while a reference dashboard was measured")


class LiveLayer(unittest.TestCase):
    """After a restart SurfaceFlinger can still list the old layer; frames come from the live one."""

    def test_the_layer_with_the_newest_frame_wins(self):
        sys.path.insert(0, os.path.dirname(HERE))
        import perfrun
        dev = perfrun.Device(None, "sh.edm.s1kiosk", ".MainActivity")
        old = "SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#0"
        live = "SurfaceView - sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity#2"
        lat = {old: "16666666\n1\t5000\t1\n", live: "16666666\n1\t9000\t1\n1\t9016\t1\n"}
        dev.sh = lambda cmd, timeout=60: (f"{old}\nBackground for -{live}\n{live}\n" if cmd.endswith("--list")
                                          else lat[cmd.split("'")[1]])
        self.assertEqual(dev.sf(), (16.666666, [9000, 9016]))


class FailedColdStart(unittest.TestCase):
    def test_the_run_goes_on_and_takes_no_idle_sample_for_it(self):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp, FAKE_NO_DASHBOARD="1", GW_PERF_COLD_SETTLE="0", GW_PERF_COLD_TIMEOUT="3")
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", out, "--skip-synthetic", "--skip-reference"],
                           env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        self.assertIn("cold start 1 failed", p.stderr)
        self.assertFalse(os.path.exists(os.path.join(out, "idle-all-on-1.json")))
        with open(os.path.join(out, "report.json")) as f:
            r = json.load(f)["real_use"]
        self.assertEqual(len(r["errors"]), 1)
        self.assertIsNone(r["app_start_to_dashboard_ms"])
        with open(os.path.join(tmp, "state", "commands.log")) as f:
            shell = [c for c in f.read().splitlines() if c.startswith("shell am ")]
        self.assertEqual(shell[-2:], ["shell am force-stop sh.edm.s1kiosk", "shell am start -n sh.edm.s1kiosk/.MainActivity"])


class ReusedOut(unittest.TestCase):
    def test_a_full_run_refuses_a_directory_with_an_earlier_run(self):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp)
        out = os.path.join(tmp, "out")
        os.makedirs(out)
        with open(os.path.join(out, "idle-all-on-1.json"), "w") as f:
            f.write("{}")
        p = subprocess.run([RUN, "--frame", "test-frame", "--out", out, "--skip-synthetic"],
                           env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 2)
        self.assertIn("use a new --out", p.stderr)


class PortInUse(unittest.TestCase):
    def test_a_port_another_fake_holds_is_refused(self):
        """An earlier run's fake left on the port would answer for this run's, with its own code."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp)
        port = HA.url.rsplit(":", 1)[1]
        p = subprocess.run([RUN, "--frame", "test-frame", "--out", os.path.join(tmp, "out"), "--port", port, "--skip-synthetic",
                            "--skip-real-use", "--restore-dashboard", RESTORE], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 2)
        self.assertIn(f"port {port} is already in use", p.stderr)
        with open(os.path.join(state, "commands.log")) as f:
            self.assertNotIn("--es url", f.read())           # the kiosk was never pointed anywhere


class CamerasOnlyWhenNeeded(unittest.TestCase):
    """CodeRabbit on cbc2be6: the camera segments (ffmpeg with libx264) are made only when the
    reference dashboards run; the animation test page alone doesn't need them."""

    def test_without_libx264_only_the_reference_part_refuses(self):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp)
        env.pop("GW_PERF_CAMERA", None)
        env["GW_PERF_CACHE"] = os.path.join(tmp, "cache")
        bindir = os.path.join(tmp, "bin")
        os.makedirs(bindir)
        with open(os.path.join(bindir, "ffmpeg"), "w") as f:
            f.write("#!/bin/sh\necho ' V..... mpeg4  MPEG-4 part 2'\n")   # an ffmpeg without libx264
        os.chmod(os.path.join(bindir, "ffmpeg"), 0o755)
        env["PATH"] = bindir + os.pathsep + env["PATH"]
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", os.path.join(tmp, "a"), "--port", "18081",
                            "--skip-reference", "--skip-real-use"], env=env, capture_output=True, text=True, timeout=180)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", os.path.join(tmp, "b"), "--port", "18081",
                            "--skip-synthetic", "--skip-real-use", "--restore-dashboard", RESTORE],
                           env=env, capture_output=True, text=True, timeout=180)
        self.assertEqual(p.returncode, 2)
        self.assertIn("need ffmpeg with libx264", p.stderr)


class SyntheticScreenOff(unittest.TestCase):
    def test_a_run_the_screen_went_off_in_is_recorded_and_left_out(self):
        """The test page isn't graded, but a run with the screen off isn't the panel's frames: the
        runner writes the screen-off into the run's log and the report leaves the run out."""
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp, FAKE_SYNTH_SCREEN_OFF="1")
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", out, "--port", "18087",
                            "--skip-reference", "--skip-real-use"], env=env, capture_output=True, text=True, timeout=180)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(out, "synthetic-1.jsonl")) as f:
            self.assertIn("screen_off", [json.loads(l)["t"] for l in f if l.strip()])
        with open(os.path.join(out, "report.json")) as f:
            s = json.load(f)["detail"]["synthetic"]
        self.assertEqual((s["runs"], s["screen_off_runs"]), (0, 1))
        with open(os.path.join(out, "report.md")) as f:
            self.assertIn("The screen went off in 1 run(s)", f.read())


class IdleOnly(unittest.TestCase):
    """The pane-diff steps in docs/perf.md, starting from an empty directory."""

    def test_samples_count_up_and_the_pane_diff_appears(self):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp)
        out = os.path.join(tmp, "panes")
        apk = os.path.join(tmp, "app.apk")
        with zipfile.ZipFile(apk, "w") as z:
            z.writestr("lib/armeabi-v7a/libxul.so", b"y" * 300)
        for label, more in (("all-on", ["--apk", apk]), ("all-on", []), ("sendspin-off", [])):
            p = subprocess.run([RUN, "--frame", "test-frame", "--out", out, "--idle-only", label] + more,
                               env=env, capture_output=True, text=True, timeout=120)
            self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        self.assertTrue(os.path.exists(os.path.join(out, "idle-all-on-0.json")))
        self.assertTrue(os.path.exists(os.path.join(out, "idle-all-on-1.json")))
        with open(os.path.join(out, "report.json")) as f:
            rep = json.load(f)
        panes = {p["id"]: p for p in rep["real_use"]["panes"]}
        self.assertEqual(panes["sendspin"]["samples_on"], 2)
        self.assertEqual(rep["apk"][0]["abis"], ["armeabi-v7a"])   # kept from the first call

    def test_a_sample_the_screen_went_off_in_is_recorded_and_left_out(self):
        """s1v7: the screen went off at the end of an idle window. The sample says so (from logcat,
        only lines after the window began), and the report leaves it out."""
        tmp = tempfile.mkdtemp()
        out = os.path.join(tmp, "panes")
        for label, extra in (("all-on", {}), ("all-on", {"FAKE_IDLE_SCREEN_OFF": "1"})):
            env, _ = fake_env(tmp, **extra)
            p = subprocess.run([RUN, "--frame", "test-frame", "--out", out, "--idle-only", label],
                               env=env, capture_output=True, text=True, timeout=120)
            self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(out, "idle-all-on-0.json")) as f:
            self.assertEqual(json.load(f)["screen_off"], [])
        with open(os.path.join(out, "idle-all-on-1.json")) as f:
            self.assertEqual(len(json.load(f)["screen_off"]), 1)
        with open(os.path.join(out, "report.json")) as f:
            r = json.load(f)["real_use"]
        self.assertEqual((r["idle"]["samples"], r["screen_off_samples"]), (1, ["all-on-1"]))



class PaneRam(unittest.TestCase):
    """#68, --pane-ram: each switchable pane on by itself against every pane off, on the typical
    reference dashboard, with the kiosk's own extras; the panes and the dashboard go back after. The
    fake's Assist adds 30 MB, Sendspin 10 MB, the wall base 3 MB, the time zone 1 MB and diagnostics 2 MB."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        env, cls.state = fake_env(cls.tmp)
        cls.out = os.path.join(cls.tmp, "out")
        cls.proc = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "2", "--out", cls.out, "--port", "18091",
                                   "--skip-synthetic", "--skip-reference", "--skip-real-use",
                                   "--pane-ram", "assist=on,sendspin=off,mic=on,wall-base=on,time-zone=on,diagnostics=on", "--restore-dashboard", RESTORE],
                                  env=env, capture_output=True, text=True, timeout=300)

    def read(self, name):
        with open(os.path.join(self.state, name)) as f:
            return json.load(f)

    def test_run_succeeds(self):
        self.assertEqual(self.proc.returncode, 0, self.proc.stderr[-3000:])

    def test_each_pane_on_by_itself_then_all_back(self):
        """Per state: the live settings (the other panes' switches, the microphone mute) in an intent
        that doesn't restart the app, then voice_enabled and a restart (s1v7: in one intent, the
        writes were lost; since #67 voice applies live, so the restart is asked for)."""
        def st(on=None, mic_muted="true"):
            flag = lambda pid: "true" if pid == on else "false"
            return [{"mic_muted": mic_muted, "sendspin_enabled": flag("sendspin"), "wall_base_enabled": flag("wall-base"),
                     "time_zone_enabled": flag("time-zone"), "diagnostics_enabled": flag("diagnostics")},
                    {"voice_enabled": flag("assist"), "restart": "true"}]
        cycle = st() + st("assist", mic_muted="false") + st("diagnostics") + st("sendspin") + st("time-zone") + st("wall-base")
        restore = [{"mic_muted": "false", "sendspin_enabled": "false", "wall_base_enabled": "true", "time_zone_enabled": "true",
                    "diagnostics_enabled": "true"}, {"voice_enabled": "true", "restart": "true"}]
        # A warm-up cycle first (#186), then the two recorded ones.
        self.assertEqual(self.read("intents.json"), cycle * 3 + restore)
        self.assertEqual(self.read("settings.json"), {"voice_enabled": "true", "sendspin_enabled": "false", "mic_muted": "false",
                                                      "wall_base_enabled": "true", "time_zone_enabled": "true", "diagnostics_enabled": "true"})   # as given
        self.assertEqual(self.read("saved_url.json"), RESTORE)
        self.assertEqual(self.read("urls_set.json"), ["http://127.0.0.1:18091/clarity-ref/typical"] * 18 + [RESTORE])

    def test_the_warm_up_cycle_is_sampled_but_not_reported(self):
        """#186: s1p3's first sample had the GPU process 15 MB low. Cycle 0 runs every state and keeps
        its files in warmup/, which the report doesn't read."""
        self.assertEqual(sorted(os.listdir(os.path.join(self.out, "warmup"))),
                         sorted([f"{k}-ref-{s}-0.json" for k in ("idle", "panes")
                                 for s in ("panes-off", "assist-on", "sendspin-on", "wall-base-on", "time-zone-on", "diagnostics-on")]
                                + ["thermal.tsv"]))
        self.assertFalse([f for f in os.listdir(self.out) if f.endswith("-0.json")])
        with open(os.path.join(self.out, "thermal.tsv")) as f:
            self.assertFalse([l for l in f if l.split("\t")[0].endswith("-0")])         # its heat rows stay in warmup/
        with open(os.path.join(self.out, "report.json")) as f:
            ram = json.load(f)["ram"]
        self.assertEqual({(p["samples_on"], p["samples_off"]) for p in ram["panes"]}, {(2, 2)})

    def test_the_report_gives_each_panes_share(self):
        with open(os.path.join(self.out, "report.json")) as f:
            ram = json.load(f)["ram"]
        panes = {p["id"]: p for p in ram["panes"]}
        self.assertEqual(set(panes), {"assist", "sendspin", "wall-base", "time-zone", "diagnostics"})
        self.assertEqual([panes[k]["pss_kb"] for k in ("assist", "sendspin", "wall-base", "time-zone", "diagnostics")],
                         [30000, 10000, 3000, 1000, 2000])
        self.assertTrue(panes["wall-base"]["verified"])                   # checked against the kiosk's `Pane wall-base on` line
        self.assertEqual(panes["assist"]["by_role_pss_kb"]["main"], 30000)
        self.assertEqual((panes["assist"]["samples_on"], panes["assist"]["samples_off"]), (2, 2))
        self.assertEqual(panes["assist"]["pss_tolerance_kb"], 0)
        self.assertTrue(panes["assist"]["pss_measurable"])
        self.assertEqual(panes["assist"]["method"], "on-alone")
        self.assertTrue(panes["assist"]["verified"])                      # every sample ran what its label says
        self.assertNotIn("off_by", panes["assist"])
        with open(os.path.join(self.out, "panes-ref-assist-on-1.json")) as f:
            self.assertEqual(json.load(f)["state"], {"voice": True, "sendspin": False, "mic": True,
                                                     "pane:wall-base": False, "pane:time-zone": False, "pane:diagnostics": False})
        # Two runs: a share, but no budget verdict (that needs three samples a side).
        self.assertEqual(ram["pane_budget"]["assist"], {"value_kb": 30000, "budget_kb": 49152, "tolerance_kb": 0,
                                                        "measurable": False, "ok": None})
        self.assertEqual({p["id"] for p in ram["panes_not_switched"]}, {"radio", "timers", "bluetooth-proxy", "notifications"})
        self.assertIsNone(ram["reference_idle"])          # no graded runs here

    def test_a_pane_setting_it_cant_put_back_is_refused_before_the_device_is_touched(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp)
        for spec in ("assist=on", "assist=yes,sendspin=on,mic=on", "radio=on,assist=on,sendspin=on,mic=on", "assist=on,sendspin=on"):
            with self.subTest(spec):
                p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--out", os.path.join(tmp, "out"), "--skip-synthetic",
                                    "--skip-reference", "--skip-real-use", "--pane-ram", spec, "--restore-dashboard", RESTORE],
                                   env=env, capture_output=True, text=True, timeout=60)
                self.assertEqual(p.returncode, 2, p.stderr)
                self.assertIn("--pane-ram", p.stderr)
                self.assertFalse(os.path.exists(os.path.join(state, "intents.json")))

    def test_a_run_that_stops_partway_puts_the_panes_and_the_dashboard_back(self):
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_IDLE_FAILS="1")
        p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "1", "--out", os.path.join(tmp, "out"), "--port", "18093",
                            "--skip-synthetic", "--skip-reference", "--skip-real-use",
                            "--pane-ram", "assist=off,sendspin=on,mic=on,wall-base=on,time-zone=on,diagnostics=on", "--restore-dashboard", RESTORE],
                           env=env, capture_output=True, text=True, timeout=120)
        self.assertNotEqual(p.returncode, 0)                       # the first idle sample failed
        with open(os.path.join(state, "intents.json")) as f:
            intents = json.load(f)
        self.assertEqual(len(intents), 4)                          # all off (two intents), then the exit trap's two
        self.assertEqual(intents[-2:], [{"mic_muted": "false", "sendspin_enabled": "true", "wall_base_enabled": "true",
                                         "time_zone_enabled": "true", "diagnostics_enabled": "true"},
                                        {"voice_enabled": "false", "restart": "true"}])
        with open(os.path.join(state, "saved_url.json")) as f:
            self.assertEqual(json.load(f), RESTORE)

    def test_a_kiosk_that_loses_voice_enabled_is_measured_by_its_mute_and_says_so(self):
        """S1 Kiosk 0.4.3 saves voice_enabled asynchronously and exits to restart: the write is lost
        (s1v7). Assist is then off by its microphone mute, which stops the wake word: its running
        cost, labelled as such; the runner tries voice_enabled twice and goes on."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_VOICE_LOST="1")
        with open(os.path.join(state, "settings.json"), "w") as f:
            json.dump({"voice_enabled": "true"}, f)                # on, as on the panel in s1v7
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "1", "--out", out, "--port", "18099",
                            "--skip-synthetic", "--skip-reference", "--skip-real-use",
                            "--pane-ram", "assist=on,sendspin=on,mic=on,wall-base=on,time-zone=on,diagnostics=on", "--restore-dashboard", RESTORE],
                           env=env, capture_output=True, text=True, timeout=300)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        with open(os.path.join(out, "panes-ref-panes-off-1.json")) as f:
            off = json.load(f)
        self.assertEqual((off["ok"], off["voice_applied"], off["attempts"]), (True, False, 2))
        self.assertEqual(off["state"], {"voice": True, "sendspin": False, "mic": False,
                                        "pane:wall-base": False, "pane:time-zone": False, "pane:diagnostics": False})
        with open(os.path.join(out, "report.json")) as f:
            panes = {x["id"]: x for x in json.load(f)["ram"]["panes"]}
        self.assertEqual(panes["assist"]["off_by"], "mute")
        self.assertEqual(panes["assist"]["pss_kb"], 30000)
        self.assertIn("voice_enabled didn't take", p.stderr)

    def test_a_refused_restore_url_fails_even_when_the_panes_match(self):
        """The last sample (Sendspin on by itself) already has the panes to put back, so only the
        URL intent's answer can tell that the kiosk is still on the fake: am exits 0 either way."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_RESTORE_FAILS="1")
        p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "1", "--out", os.path.join(tmp, "out"), "--port", "18085",
                            "--skip-synthetic", "--skip-reference", "--skip-real-use",
                            "--pane-ram", "assist=off,sendspin=on,mic=off,wall-base=on,time-zone=on,diagnostics=on", "--restore-dashboard", RESTORE],
                           env=env, capture_output=True, text=True, timeout=300)
        self.assertEqual(p.returncode, 3, p.stderr[-2000:])
        self.assertIn("COULD NOT put the panes back", p.stderr)
        self.assertIn('"url_taken": false', p.stderr)
        with open(os.path.join(state, "saved_url.json")) as f:
            self.assertIn("127.0.0.1", json.load(f))          # still the fake's: what the check caught

    def test_a_refused_sample_dashboard_takes_no_sample(self):
        """The verifier's note 15: when the kiosk refuses the reference dashboard's intent, the panes
        may still match, but the idle sample wouldn't be on that dashboard. No sample is taken."""
        tmp = tempfile.mkdtemp()
        env, state = fake_env(tmp, FAKE_REF_URL_FAILS="1")
        out = os.path.join(tmp, "out")
        p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--runs", "1", "--out", out, "--port", "18083",
                            "--skip-synthetic", "--skip-reference", "--skip-real-use",
                            "--pane-ram", "assist=on,sendspin=on,mic=on,wall-base=on,time-zone=on,diagnostics=on", "--restore-dashboard", RESTORE],
                           env=env, capture_output=True, text=True, timeout=300)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])                 # the restore isn't refused
        self.assertIn("no sample taken", p.stderr)
        self.assertEqual([f for f in os.listdir(out) if f.startswith("idle-ref-")], [])
        with open(os.path.join(out, "panes-ref-panes-off-1.json")) as f:
            self.assertFalse(json.load(f)["url_taken"])

    def test_it_needs_the_dashboard_to_put_back(self):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp)
        p = subprocess.run([RUN, "--frame", "aqara-s1-plus", "--out", os.path.join(tmp, "out"), "--skip-synthetic",
                            "--skip-reference", "--skip-real-use", "--pane-ram", "assist=on,sendspin=on,mic=on,wall-base=on,time-zone=on,diagnostics=on"],
                           env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 2)
        self.assertIn("--restore-dashboard", p.stderr)


if __name__ == "__main__":
    unittest.main()
