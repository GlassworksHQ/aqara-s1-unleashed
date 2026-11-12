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
                   GW_PERF_COLD_SETTLE="0", GW_PERF_RUN_GAP="0")
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
        self.assertTrue(g["headline"].startswith("test-frame: A, handles busy, animated dashboards smoothly."))
        ref = r["graded"]["reference"]
        self.assertEqual(ref["errors"], [])
        self.assertEqual(set(ref["tiers"]), {"light", "typical", "busy"})
        self.assertEqual(ref["tiers"]["light"]["inp_ms"]["median"], 48)
        self.assertEqual(ref["tiers"]["light"]["sources"], ["raf"])
        self.assertEqual(ref["tiers"]["typical"]["sources"], ["surfaceflinger"])
        self.assertEqual(ref["tiers"]["light"]["load_errors"], [])
        self.assertEqual(g["start"]["value"], ref["start_ms"]["median"])
        self.assertEqual(r["real_use"]["android_ttid_ms"]["median"], 1714)
        self.assertEqual(r["real_use"]["settle_how"], ["quiet-gap"])
        self.assertEqual(r["real_use"]["memory"]["idle"]["app_pss_kb"]["median"], 570000)
        self.assertEqual({p["id"] for p in r["real_use"]["panes"]}, {"radio"})

    def test_frame_counts_come_from_the_fake_screen(self):
        busy = self.rep["graded"]["reference"]["per_run"][0]["tiers"]["busy"]
        self.assertTrue(busy["sf_valid"])
        self.assertGreater(busy["frames"]["n"], 20)

    def test_it_never_changes_a_setting(self):
        shell = [c[len("shell "):] for c in self.commands if c.startswith("shell ")]
        inputs = sorted({c for c in shell if c.startswith("input ")})
        self.assertEqual(inputs, ["input keyevent KEYCODE_WAKEUP", "input tap 720 360"])
        self.assertEqual(sum(1 for c in shell if c == "input tap 720 360"), 6)   # 2 taps on each reference dashboard
        forbidden = re.compile(r"(^|[;&|]\s*)(settings|svc|cmd|setprop|am broadcast|pm (?!path\b))|--es url|--ez ")
        self.assertEqual([c for c in shell if forbidden.search(c)], [])
        extras = {m for c in shell for m in re.findall(r"--e\w \w+", c)}
        self.assertEqual(extras, {"--es open", "--es screen"})
        opens = [re.search(r"--es open '([^']+)'", c).group(1) for c in shell if "--es open" in c]
        self.assertEqual(len(opens), 4)
        self.assertTrue(opens[0].startswith("http://127.0.0.1:18089/index.html?"), opens[0])
        for url, tier in zip(opens[1:], ("light", "typical", "busy")):
            self.assertTrue(url.startswith(f"http://127.0.0.1:18089/dashboard.html?tier={tier}&nonce=") and "&taps=2" in url, url)

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
        self.assertTrue(c["own_page_quiet"])
        self.assertEqual(sorted(c["tiers"]), ["busy", "light", "typical"])
        self.assertTrue(all("step" in e and "page" in e for e in c["tiers"].values()))
        self.assertGreater(c["engine_mono"], c["proc_mono"])


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
