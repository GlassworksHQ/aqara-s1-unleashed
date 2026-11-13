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
sys.path.insert(0, os.path.dirname(HERE))
import perfrun  # noqa: E402
import report  # noqa: E402


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
                   GW_PERF_COLD_SETTLE="0", GW_PERF_RUN_GAP="0", GW_PERF_BETWEEN_QUIET="0.5")
        env.pop("ANDROID_SERIAL", None)
        cls.out = os.path.join(cls.tmp, "out")
        cls.proc = subprocess.run([RUN, "--frame", "test-frame", "--runs", "1", "--out", cls.out, "--port", "18089", "--taps", "2"],
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
        self.assertEqual(ref["own_page_states"], ["finished-still"])
        self.assertEqual(ref["orders"], [["light", "typical", "heavy"]])
        self.assertEqual(ref["warmups"], [{"tier": "light", "finished": True}])
        self.assertEqual(ref["per_run"][0]["between"], ["still", "still", "still"])   # after the warm-up and each page
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
        self.assertEqual({p["id"] for p in r["real_use"]["panes"]}, {"radio"})

    def test_it_never_changes_a_setting(self):
        shell = [c[len("shell "):] for c in self.commands if c.startswith("shell ")]
        inputs = sorted({c for c in shell if c.startswith("input ")})
        # Taps on the toggle and swipes where the page asked, with its coordinates: nothing else.
        self.assertEqual(inputs, ["input keyevent KEYCODE_WAKEUP", "input swipe 720 612 720 108 300", "input tap 720 360"])
        self.assertEqual(sum(1 for c in shell if c == "input tap 720 360"), 6)                 # 2 on each page
        self.assertEqual(sum(1 for c in shell if c.startswith("input swipe ")), 6)            # 2 on each page
        forbidden = re.compile(r"(^|[;&|]\s*)(settings|svc|cmd|setprop|am broadcast|pm (?!path\b))|--es url|--ez ")
        self.assertEqual([c for c in shell if forbidden.search(c)], [])
        extras = {m for c in shell for m in re.findall(r"--e\w \w+", c)}
        self.assertEqual(extras, {"--es open", "--es screen"})
        opens = [re.search(r"--es open '([^']+)'", c).group(1) for c in shell if "--es open" in c]
        self.assertEqual(len(opens), 5)
        self.assertTrue(opens[0].startswith("http://127.0.0.1:18089/index.html?"), opens[0])
        # The warm-up page: no taps, no swipes, not graded.
        self.assertTrue(opens[1].startswith("http://127.0.0.1:18089/dashboard.html?tier=light&nonce=") and
                        opens[1].endswith("&taps=0&swipes=0&warmup=1"), opens[1])
        for url, tier in zip(opens[2:], ("light", "typical", "heavy")):
            self.assertTrue(url.startswith(f"http://127.0.0.1:18089/dashboard.html?tier={tier}&nonce=") and "&taps=2" in url, url)

    def test_no_gesture_without_the_current_pages_request(self):
        """The fake rejects any tap or swipe that no current *-ready line asked for."""
        with open(os.path.join(self.tmp, "state", "unasked.json")) if os.path.exists(
                os.path.join(self.tmp, "state", "unasked.json")) else open(os.devnull) as f:
            self.assertEqual(json.loads(f.read() or "[]"), [])

    def test_swipes_come_only_after_the_pages_own_request(self):
        """Each swipe follows a swipe-ready from the page being measured: the commands log has no swipe
        before the first reference page opened."""
        first_open = next(i for i, c in enumerate(self.commands) if "dashboard.html" in c)
        first_swipe = next(i for i, c in enumerate(self.commands) if c.startswith("shell input swipe"))
        self.assertGreater(first_swipe, first_open)

    def test_the_reference_dashboards_open_only_once_the_session_is_up(self):
        """On the S1, `open` before GeckoView's first page is dropped (#103's pins); the fake does the same."""
        i_blank = next(i for i, c in enumerate(self.commands) if "dashboard.html" in c)
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
    env = dict(os.environ, ADB=adb, FAKE_ADB_STATE=state, GW_PERF_IDLE_SETTLE="0", GW_PERF_IDLE_WINDOW="1", **extra)
    env.pop("ANDROID_SERIAL", None)
    return env, state


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
                            "--out", tmp, "--run", "1", "--settle", "3", "--url", "http://127.0.0.1:1/dashboard.html",
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
    env, state = fake_env(tmp, GW_PERF_COLD_SETTLE="0", **env_extra)
    p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                        "--out", tmp, "--run", str(run), "--settle", "0", "--url", "http://127.0.0.1:1/dashboard.html",
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
        with open(os.path.join(os.path.dirname(HERE), "page", "tiers.js")) as f:
            self.assertIn(f"const PROTOCOL = {F.PROTOCOL};", f.read())


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
        self.assertEqual(c["bad_page_lines"], 4)                         # the warm-up's and each page's
        with open(os.path.join(tmp, "device.json"), "w") as f:
            json.dump({"frame": "f", "mem_total_kb": 1, "display": {"density": "240"}, "app": {"package": "sh.edm.s1kiosk"}}, f)
        rep = report.build(tmp)
        self.assertIn("4 page line(s) didn't parse (cut off in logcat?)", rep["grade"]["provisional_reasons"])
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
        self.assertEqual(c["bad_page_lines"], 4)                         # the warm-up's and each page's
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

    def test_run_2_starts_one_page_in_after_a_warm_up(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, run=2)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["order"], ["typical", "heavy", "light"])
        self.assertEqual(self.opened(tmp), ["light-warmup", "typical", "heavy", "light"])
        self.assertTrue(c["warmup"]["done"])
        self.assertNotIn("warmup", c["tiers"])
        self.assertEqual(len(c["between"]), 3)

    def test_run_3(self):
        tmp = tempfile.mkdtemp()
        p, c = refstart(tmp, run=3)
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(self.opened(tmp), ["light-warmup", "heavy", "light", "typical"])


class NoisyLogcat(unittest.TestCase):
    """logcat follows the whole device, so its queue is almost never empty. No wait may depend on it."""

    def test_unrelated_lines_twice_a_second(self):
        p, c = refstart(tempfile.mkdtemp(), FAKE_LOGCAT_NOISE="0.5")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(sorted(c["tiers"]), ["heavy", "light", "typical"])

    def test_unrelated_lines_twenty_a_second(self):
        p, c = refstart(tempfile.mkdtemp(), FAKE_LOGCAT_NOISE="0.05")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["own_page_state"], "finished-still")

    def test_a_reload_more_than_2_s_later_restarts_the_wait_even_with_noise(self):
        p, c = refstart(tempfile.mkdtemp(), FAKE_OWN_RELOAD="1", FAKE_OWN_RELOAD_DELAY="2.6", FAKE_LOGCAT_NOISE="0.05")
        self.assertEqual(p.returncode, 0, p.stderr[-1500:])
        self.assertEqual(c["own_page_loads"], 2)
        self.assertIsNone(c["error"])
        # The recorded stop is the second load's, and the first reference page opened 2 s after it.
        self.assertGreater(c["own_page_stop_mono"], c["own_page_start_mono"])
        self.assertGreaterEqual(c["tiers"]["light"]["page_start_mono"] - c["own_page_stop_mono"], 2.0)


class ForeignPage(unittest.TestCase):
    """While the reference dashboards are measured, any other page the kiosk loads, before the
    reference page starts or after, makes the run an error: it isn't graded."""

    def run_with(self, where):
        tmp = tempfile.mkdtemp()
        env, _ = fake_env(tmp, FAKE_FOREIGN_PAGE=where, GW_PERF_COLD_SETTLE="0")
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                            "--out", tmp, "--run", "1", "--settle", "0", "--url", "http://127.0.0.1:1/dashboard.html",
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
                            "--out", tmp, "--run", "1", "--settle", "0", "--url", "http://127.0.0.1:1/dashboard.html",
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


if __name__ == "__main__":
    unittest.main()
