"""run.sh and perfrun.py end to end against test/fake_adb.py (no device)."""
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(os.path.dirname(HERE), "run.sh")
sys.path.insert(0, os.path.dirname(HERE))
import perfrun  # noqa: E402


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
        self.assertEqual(r["schema"], "glassworks.perf/3")
        self.assertEqual(r["frame"]["device"]["model"], "Test Panel")
        self.assertEqual(r["frame"]["app"]["geckoview"], "159.0")
        g = r["grade"]
        self.assertEqual(g["grade"], "A")
        self.assertFalse(g["provisional"], g["missing"])
        self.assertTrue(g["headline"].startswith("test-frame: A, a good wall panel even for large dashboards."))
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
            self.assertEqual(s["updates_band"], "good", t)
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


def refstart(tmp, run=1, **env_extra):
    env, state = fake_env(tmp, GW_PERF_COLD_SETTLE="0", **env_extra)
    p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "perfrun.py"), "refstart",
                        "--out", tmp, "--run", str(run), "--settle", "0", "--url", "http://127.0.0.1:1/dashboard.html",
                        "--taps", "0", "--timeout", "90", "--between-quiet", "0.3"], env=env, capture_output=True, text=True, timeout=150)
    with open(os.path.join(tmp, f"refstart-{run}.json")) as f:
        return p, json.load(f)


def unasked(tmp):
    p = os.path.join(tmp, "state", "unasked.json")
    return json.load(open(p)) if os.path.exists(p) else []


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
