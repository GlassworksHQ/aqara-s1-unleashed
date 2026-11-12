"""run.sh and perfrun.py end to end against test/fake_adb.py (no device)."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

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
        self.assertEqual(r["schema"], "glassworks.perf/1")
        self.assertEqual(r["frame"]["device"]["model"], "Test Panel")
        self.assertEqual(r["frame"]["app"]["geckoview"], "159.0")
        self.assertEqual(r["grade"]["components"], {"frames": "good", "load": "good", "input": "good", "start": "good"})
        self.assertEqual(r["grade"]["grade"], "A")
        self.assertFalse(r["grade"]["provisional"])
        self.assertEqual(r["synthetic"]["reference_sources"], ["surfaceflinger"])
        self.assertEqual(r["synthetic"]["inp_ms"]["median"], 48)
        self.assertEqual(r["real_use"]["cold_start_ttid_ms"]["median"], 1714)
        self.assertEqual(r["real_use"]["settle_how"], ["quiet-gap"])
        self.assertEqual(r["memory"]["idle"]["app_pss_kb"]["median"], 570000)
        self.assertEqual({p["id"] for p in r["panes"]}, {"radio"})

    def test_frame_counts_come_from_the_fake_screen(self):
        ref = self.rep["synthetic"]["per_run"][0]["reference"]
        self.assertGreater(ref["n"], 25)
        self.assertEqual(ref["p95_ms"], 16.67)

    def test_it_never_changes_a_setting(self):
        bad = [c for c in self.commands if "--es url" in c or "setprop" in c or "settings put" in c or " pm " in c and "pm path" not in c]
        self.assertEqual(bad, [])
        self.assertEqual(sum(1 for c in self.commands if c.startswith("shell input tap 720 360")), 2)
        opens = [c for c in self.commands if "--es open" in c]
        self.assertEqual(len(opens), 1)
        self.assertIn("http://127.0.0.1:18089/index.html?", opens[0])

    def test_the_dashboard_comes_back_last(self):
        starts = [c for c in self.commands if c.startswith("shell am start") and "screen on" not in c]
        self.assertTrue(starts[-1].startswith("shell am start -W"), starts[-3:])


class IdleOnly(unittest.TestCase):
    """The pane-diff steps in docs/perf.md, starting from an empty directory."""

    def test_samples_count_up_and_the_pane_diff_appears(self):
        tmp = tempfile.mkdtemp()
        state = os.path.join(tmp, "state")
        os.makedirs(state)
        adb = os.path.join(tmp, "adb")
        with open(adb, "w") as f:
            f.write(f'#!/bin/sh\nexec "{sys.executable}" "{os.path.join(HERE, "fake_adb.py")}" "$@"\n')
        os.chmod(adb, 0o755)
        env = dict(os.environ, ADB=adb, FAKE_ADB_STATE=state, GW_PERF_IDLE_SETTLE="0", GW_PERF_IDLE_WINDOW="1")
        env.pop("ANDROID_SERIAL", None)
        out = os.path.join(tmp, "panes")
        for label in ("all-on", "all-on", "sendspin-off"):
            p = subprocess.run([RUN, "--frame", "test-frame", "--out", out, "--idle-only", label],
                               env=env, capture_output=True, text=True, timeout=120)
            self.assertEqual(p.returncode, 0, p.stderr[-2000:])
        self.assertTrue(os.path.exists(os.path.join(out, "idle-all-on-0.json")))
        self.assertTrue(os.path.exists(os.path.join(out, "idle-all-on-1.json")))
        with open(os.path.join(out, "report.json")) as f:
            panes = {p["id"]: p for p in json.load(f)["panes"]}
        self.assertEqual(panes["sendspin"]["samples_on"], 2)


if __name__ == "__main__":
    unittest.main()
