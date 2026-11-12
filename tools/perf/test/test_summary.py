"""python3 -m unittest discover -s tools/perf/test

The summary (tools/perf/summary.py) holds measurements only; the letter is derived from it and
rubric.json. These tests build fixture reports, export their summaries, and check that grade.py and
grade.ts give each one the report's own grade."""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import grade as G  # noqa: E402
import report as R  # noqa: E402
import summary as S  # noqa: E402
import test_report as T  # noqa: E402

SUMMARY_KEYS = {"schema", "protocol_version", "report_schema", "measured_on", "frame", "app_version", "viewport",
                "runs", "pages", "restart_ms", "gaps", "not_comparable", "report", "complete"}
PAGE_KEYS = {"inp_ms", "open_ms", "scroll", "updates", "runs"}
GRADE_KEYS = ("grade", "meaning", "levels", "bands", "capped_by_start", "missing", "provisional", "start")


def fixture(variant):
    """A finished report.json for one variant of test_report's fixture runs."""
    b = T.Build()
    b.setUp()
    b.write("device.json", dict(json.load(open(os.path.join(b.dir, "device.json"))), measured_on="2026-11-12"))
    for k in (1, 2, 3):
        run = T.refstart(k, [120, 180, 150][k - 1])
        if variant == "A":
            run = T.refstart(k, 100, heavy_missed=0)
        elif variant == "E":           # smoke5's shape
            run["tiers"]["light"]["swipes"] = [T.swipe(30.0, i, T.late(60, 30, 50.0)) for i in range(3)]
            run["tiers"]["typical"]["swipes"] = [T.swipe(40.0, i, T.late(60, 30, 50.0)) for i in range(3)]
            run["tiers"]["light"]["page"]["fcp_ms"] = 900
        elif variant == "capped":
            run["proc_mono"], run["engine_mono"] = 20.0, 26.0
        elif variant == "zoom":
            for t in run["tiers"].values():
                t["page"]["dpr"] = 2.25
        elif variant == "raf-only":
            run["tiers"]["heavy"]["swipes"] = [T.swipe(50.0, i, T.late(60, 20), sf=False) for i in range(3)]
        elif variant == "even":         # two runs: the worse middle band
            if k == 3:
                continue
            if k == 2:
                run["tiers"]["heavy"]["swipes"] = [T.swipe(50.0, i, T.late(60, 40, 50.0)) for i in range(3)]
        b.write(f"refstart-{k}.json", run)
    if variant == "even":
        os.remove(os.path.join(b.dir, "refstart-3.json"))
    if variant == "missing-run":
        b.write("plan.json", {"runs": 4})
    rep = R.build(b.dir)
    shutil.rmtree(b.dir)
    return rep


VARIANTS = ["B", "A", "E", "capped", "zoom", "raf-only", "even", "missing-run"]


def ts_grades(summaries):
    node = shutil.which("node")
    if not node:
        if os.environ.get("CI"):
            raise AssertionError("node is needed for the grade.ts parity test")
        raise unittest.SkipTest("node isn't installed (CI has it)")
    p = subprocess.run([node, os.path.join(HERE, "grade_cli.mjs")], input=json.dumps({"summaries": summaries}),
                       capture_output=True, text=True, timeout=60)
    if p.returncode:
        raise AssertionError(p.stderr)
    return json.loads(p.stdout)


class Export(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.reps = {v: fixture(v) for v in VARIANTS}

    def test_round_trips_from_a_fixture_report(self):
        d = tempfile.mkdtemp()
        rp, sp = os.path.join(d, "report.json"), os.path.join(d, "summary.json")
        with open(rp, "w") as f:
            json.dump(self.reps["B"], f)
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "summary.py"), rp, sp,
                            "--report-url", "reports/aqara-s1-plus.json"], capture_output=True, text=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        with open(sp) as f:
            s = json.load(f)
        self.assertEqual(s, S.summarise(self.reps["B"], "reports/aqara-s1-plus.json"))
        self.assertEqual(json.loads(json.dumps(s)), s)
        self.assertEqual(set(s), SUMMARY_KEYS)
        self.assertEqual(s["schema"], "glassworks.perf-summary/1")
        self.assertEqual((s["protocol_version"], s["report_schema"]), (G.PROTOCOL_VERSION, "glassworks.perf/3"))
        self.assertEqual(s["measured_on"], "2026-11-12")
        self.assertEqual(s["frame"], {"id": "test-frame", "name": "test-frame"})
        self.assertEqual(s["app_version"], "0.4.3")
        self.assertEqual(s["viewport"], {"css": [960, 480], "dpr": 1.5, "default_dpr": 1.5})
        self.assertEqual(s["runs"], {"attempted": 3, "graded": 3})
        self.assertEqual(s["restart_ms"], 800)
        self.assertEqual((s["gaps"], s["not_comparable"], s["complete"]), ([], None, True))
        self.assertEqual(s["report"], "reports/aqara-s1-plus.json")
        heavy = s["pages"]["heavy"]
        self.assertEqual(set(heavy), PAGE_KEYS)
        self.assertEqual((heavy["inp_ms"], heavy["open_ms"]), (150, 400))
        self.assertEqual((heavy["scroll"]["fps"], heavy["scroll"]["p95_ms"], heavy["scroll"]["scrolled_px"]), (45.0, 33.33, 2100))
        self.assertEqual(heavy["scroll"]["sf_valid"], [True, True, True])
        self.assertEqual(len(heavy["scroll"]["runs"]), 3)
        self.assertEqual(heavy["runs"], {"tap": 3, "scroll": 3, "updates": 3, "open": 3})

    def test_measurements_only_never_the_letter(self):
        text = json.dumps(S.summarise(self.reps["B"]))
        for word in ('"grade"', '"meaning"', '"level"', '"levels"', '"band"', "good wall panel", "rubric_version"):
            self.assertNotIn(word, text)

    def test_nothing_device_unique(self):
        s = S.summarise(self.reps["B"])
        text = json.dumps(s)
        self.assertNotIn("serial", text)
        self.assertIsNone(re.search(r"\b\d{1,3}(\.\d{1,3}){3}\b", text))     # no addresses
        self.assertIsNone(re.search(r"([0-9a-f]{2}:){5}", text))             # no MACs

    def test_incomplete_or_not_comparable_is_refused_unless_asked(self):
        for v in ("zoom", "raf-only", "missing-run", "E"):
            with self.subTest(v):
                with self.assertRaises(S.Refused):
                    S.summarise(self.reps[v])
                s = S.summarise(self.reps[v], allow_provisional=True)
                self.assertFalse(s["complete"])
        self.assertIn("expected 1.5", S.summarise(self.reps["zoom"], allow_provisional=True)["not_comparable"])
        self.assertIn("1 of 4 graded runs failed or are missing", S.summarise(self.reps["missing-run"], allow_provisional=True)["gaps"])

    def test_the_cli_refuses_without_the_flag(self):
        d = tempfile.mkdtemp()
        rp = os.path.join(d, "report.json")
        with open(rp, "w") as f:
            json.dump(self.reps["zoom"], f)
        p = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "summary.py"), rp, os.path.join(d, "s.json")],
                           capture_output=True, text=True)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("summary not written", p.stderr)
        self.assertFalse(os.path.exists(os.path.join(d, "s.json")))


class Parity(unittest.TestCase):
    """For every fixture report, grade.py and grade.ts give the summary the report's own grade."""

    @classmethod
    def setUpClass(cls):
        cls.reps = {v: fixture(v) for v in VARIANTS}
        cls.sums = {v: S.summarise(r, allow_provisional=True) for v, r in cls.reps.items()}
        cls.ts = dict(zip(VARIANTS, ts_grades([cls.sums[v] for v in VARIANTS])))

    def test_the_variants_cover_every_status(self):
        letters = {v: self.reps[v]["grade"]["grade"] for v in VARIANTS}
        self.assertEqual(letters["A"], "A")
        self.assertEqual(letters["B"], "B")
        self.assertEqual(letters["E"], "E")
        self.assertEqual(letters["capped"], "C")
        self.assertIsNone(letters["zoom"])
        self.assertTrue(self.reps["raf-only"]["grade"]["provisional"])

    def test_python_from_the_summary_equals_the_report(self):
        for v in VARIANTS:
            with self.subTest(v):
                rep_g, py = self.reps[v]["grade"], G.grade_summary(self.sums[v])
                if rep_g["grade"] is None:
                    self.assertEqual((py["status"], py["grade"]), ("not-comparable", None))
                    continue
                self.assertEqual(py["status"], "graded")
                for k in GRADE_KEYS:
                    self.assertEqual(py[k], rep_g[k], k)

    def test_typescript_equals_python(self):
        for v in VARIANTS:
            with self.subTest(v):
                py, ts = G.grade_summary(self.sums[v]), self.ts[v]
                self.assertEqual(ts["status"], py["status"])
                for k in GRADE_KEYS + ("limits", "why"):
                    if k in py:
                        self.assertEqual(ts[k], py[k], k)

    def test_another_protocol_is_re_measured_in_both(self):
        s = dict(self.sums["B"], protocol_version=G.PROTOCOL_VERSION + 1)
        ts = ts_grades([s])[0]
        py = G.grade_summary(s)
        self.assertEqual((py["status"], py["grade"]), ("re-measure", None))
        self.assertEqual((ts["status"], ts["grade"], ts["why"]), ("re-measure", None, py["why"]))


if __name__ == "__main__":
    unittest.main()
