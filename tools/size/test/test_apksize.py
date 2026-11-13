"""python3 -m unittest discover -s tools/size/test

tools/size/apksize.py on small made-up APKs and apkanalyzer listings: who owns what, the parts adding up
to the file, the assets lint, the budgets (and a budget raised in the same PR), and the comment
rendered from numbers only. No Android SDK needed."""
import copy
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(os.path.dirname(HERE), "apksize.py")
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.dirname(HERE))
import apksize as A  # noqa: E402

PARTS = A.read_json(os.path.join(os.path.dirname(HERE), "parts.json"))

# apkanalyzer `dex packages --defined-only` lines: C d <defined methods> <referenced methods> <size> <name>
CLASSES = """P d 10	10	10000	<TOTAL>
C d 1	1	4000	org.mozilla.geckoview.GeckoView
C d 1	1	1000	sh.edm.s1kiosk.MainActivity
C d 1	1	1000	sh.edm.s1kiosk.voice.AssistPane
C d 1	1	500	sh.edm.s1kiosk.VoiceOverlay$Ring
C d 1	1	500	sh.edm.s1kiosk.sendspin.SendspinPane
C d 1	1	1000	org.bouncycastle.crypto.Digest
C d 1	1	1000	org.tensorflow.lite.Interpreter
C d 1	1	500	sh.edm.s1kiosk.frame.TimeZonePane
C d 1	1	500	sh.edm.s1kiosk.frame.AqaraS1Plus
"""


def make_apk(path, extra=(), dex=20_000, xul=500_000):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        z.writestr("classes.dex", b"d" * dex)
        z.writestr("lib/armeabi-v7a/libxul.so", b"x" * xul)
        z.writestr("lib/armeabi-v7a/libtensorflowlite_jni.so", b"t" * 30_000)
        z.writestr("lib/armeabi-v7a/libown.so", b"o" * 1_000)
        z.writestr("assets/omni.ja", b"j" * 100_000)
        z.writestr("assets/extension/content.js", b"c" * 2_000)
        z.writestr("org/bouncycastle/x509/CertPathReviewerMessages.properties", b"b" * 3_000)
        z.writestr("resources.arsc", b"r" * 4_000)
        for name, data in extra:
            z.writestr(name, data)
    return path


class Owners(unittest.TestCase):
    def setUp(self):
        self.apk = make_apk(os.path.join(tempfile.mkdtemp(), "a.apk"))
        self.b = A.breakdown(self.apk, A.parse_classes(CLASSES), PARTS)
        self.rows = A.rows(self.b, PARTS)

    def test_the_parts_add_up_to_the_file(self):
        self.assertEqual(sum(r["total"] for r in self.rows.values()), os.path.getsize(self.apk))

    def test_each_part_gets_its_own(self):
        r = self.rows
        self.assertEqual(r["engine"]["native"], 500_000)
        self.assertEqual(r["engine"]["assets"], 100_000)
        self.assertEqual(r["engine"]["dex"], 8_000)                  # 4000 of 10000 class bytes, of 20 000
        # Assist: its package, its overlay class, and TensorFlow Lite, which only it uses.
        self.assertEqual(r["pane:assist"]["dex"], 2_000 + 1_000 + 2_000)
        self.assertEqual(r["pane:assist"]["native"], 30_000)
        self.assertEqual(r["pane:time-zone"]["dex"], 1_000)           # one class, not its whole package
        self.assertEqual(r["pane:sendspin"]["dex"], 1_000)
        # BouncyCastle is the core's and Sendspin's: shared, in neither's own total.
        self.assertEqual(r["shared:bouncycastle"]["dex"], 2_000)
        self.assertEqual(r["shared:bouncycastle"]["other"], 3_000)
        self.assertEqual(r["core"]["native"], 1_000)                 # a native library nobody claims
        self.assertEqual(r["core"]["dex"], 2_000 + 1_000)            # MainActivity, AqaraS1Plus

    def test_a_prefix_needs_a_package_or_class_boundary(self):
        self.assertEqual(A.owner_of_class("sh.edm.s1kiosk.frame.TimeZonePane$1", PARTS), ("pane", "time-zone"))
        self.assertEqual(A.owner_of_class("sh.edm.s1kiosk.frame.TimeZonePanel", PARTS), ("core", None))
        self.assertEqual(A.owner_of_class("org.mozillax.Foo", PARTS), ("core", None))

    def test_a_pane_with_no_code_is_noted(self):
        self.assertIn("radio", self.b["panes_without_code"])
        self.assertNotIn("assist", self.b["panes_without_code"])


class AssetsLint(unittest.TestCase):
    def lint(self, *names):
        apk = make_apk(os.path.join(tempfile.mkdtemp(), "a.apk"), [(n, b"z" * 10) for n in names])
        return A.breakdown(apk, A.parse_classes(CLASSES), PARTS)

    def test_pane_assets_belong_under_assets_panes_id(self):
        b = self.lint("assets/panes/assist/hey_jarvis.tflite")
        self.assertEqual(b["lint"], [])
        self.assertEqual(b["owners"]["pane:assist"]["assets"], 10)

    def test_an_asset_anywhere_else_fails(self):
        b = self.lint("assets/models/hey_jarvis.tflite", "assets/panes/kitchen/x.bin")
        self.assertEqual(len(b["lint"]), 2)
        self.assertIn("assets/models/hey_jarvis.tflite", b["lint"][0])
        self.assertIn("`kitchen` isn't a pane", b["lint"][1])

    def test_the_app_today_passes(self):
        """The kiosk's own assets (the GeckoView extension, the baseline profile, the licences) are the
        core's; GeckoView's omni.ja is the engine's (2026-11-13: no pane ships assets yet)."""
        self.assertEqual(self.lint("assets/dexopt/baseline.prof", "assets/licenses/LICENSE")["lint"], [])


class Budgets(unittest.TestCase):
    def report(self, budgets, base_budgets=None, xul=500_000):
        d = tempfile.mkdtemp()
        head = make_apk(os.path.join(d, "head.apk"), xul=xul)
        base = make_apk(os.path.join(d, "base.apk"))
        return A.build_report("aqara-s1-plus", {"armeabi-v7a": head}, {"armeabi-v7a": base}, PARTS, budgets, base_budgets,
                              classes_of=lambda p: A.parse_classes(CLASSES))

    def test_within_budget(self):
        rep = self.report({"size": {"apk_max_bytes": {"armeabi-v7a": 10_000_000}}, "panes": {"assist": {"size_max_bytes": 100_000}}})
        self.assertTrue(rep["budget"]["ok"], rep["budget"])

    def test_over_the_apk_budget_fails(self):
        rep = self.report({"size": {"apk_max_bytes": {"armeabi-v7a": 600_000}}}, xul=700_000)
        self.assertFalse(rep["budget"]["ok"])
        self.assertIn("armeabi-v7a: the APK is", rep["budget"]["fails"][0])

    def test_over_a_pane_budget_fails(self):
        rep = self.report({"size": {}, "panes": {"assist": {"size_max_bytes": 10_000}}})
        self.assertFalse(rep["budget"]["ok"])
        self.assertIn("assist is", rep["budget"]["fails"][0])

    def test_raising_the_budget_in_the_same_pr_passes_and_says_so(self):
        base = {"size": {"apk_max_bytes": {"armeabi-v7a": 600_000}}}
        head = {"size": {"apk_max_bytes": {"armeabi-v7a": 900_000}}}
        rep = self.report(head, base, xul=700_000)
        self.assertTrue(rep["budget"]["ok"])
        self.assertIn("this PR changes the APK budget from 0.60 MB to 0.90 MB", " ".join(rep["budget"]["notes"]))

    def test_the_change_from_the_base(self):
        rep = self.report({"size": {}}, xul=510_000)
        a = rep["abis"]["armeabi-v7a"]
        self.assertEqual(a["rows"]["engine"]["total"] - a["base_rows"]["engine"]["total"], 10_000)
        self.assertIn("+10.0 kB", A.markdown(rep))

    def test_the_s1s_budgets(self):
        """The frame's own file has a size budget per ABI and per switchable pane."""
        s1 = A.read_json(os.path.join(ROOT, "frames", "aqara-s1-plus", "budgets.json"))
        self.assertEqual(set(s1["size"]["apk_max_bytes"]), {"armeabi-v7a", "arm64-v8a"})
        for pid in PARTS["panes"]:
            self.assertIsInstance(s1["panes"][pid]["size_max_bytes"], int, pid)


class Comment(unittest.TestCase):
    """The PR comment is rendered by a workflow with write access, from a report another workflow
    built from the PR's code: only numbers and known words may reach it."""

    def rep(self):
        d = tempfile.mkdtemp()
        apk = make_apk(os.path.join(d, "a.apk"))
        return A.build_report("aqara-s1-plus", {"armeabi-v7a": apk}, {}, PARTS, {"size": {}}, None,
                              classes_of=lambda p: A.parse_classes(CLASSES))

    def test_it_starts_with_the_marker_it_is_found_by(self):
        md = A.markdown(self.rep())
        self.assertTrue(md.startswith(A.MARKER))
        self.assertIn("| Pane `assist` |", md)

    def test_a_report_with_anything_else_in_its_names_is_refused(self):
        for bad in (lambda r: r["abis"]["armeabi-v7a"]["rows"].update({"pane:x](http://evil)": r["abis"]["armeabi-v7a"]["rows"]["core"]}),
                    lambda r: r.update(frame="s1<script>"),
                    lambda r: r["abis"].update({"evil": r["abis"]["armeabi-v7a"]}),
                    lambda r: r["abis"]["armeabi-v7a"].update(bytes="10 MB"),
                    lambda r: r.update(schema="other")):
            rep = self.rep()
            bad(rep)
            with self.assertRaises(ValueError):
                A.markdown(rep)

    def test_findings_are_plain_text(self):
        rep = self.rep()
        rep["budget"]["fails"] = ["x <img src=x onerror=alert(1)> [link](http://evil) @someone"]
        md = A.markdown(rep)
        body = md.split("How this is measured")[0]
        self.assertNotIn("](", body)
        self.assertNotIn("<img", body)
        self.assertNotIn("@someone", md)

    def test_render_from_the_command_line(self):
        d = tempfile.mkdtemp()
        p = os.path.join(d, "size.json")
        with open(p, "w") as f:
            json.dump(self.rep(), f)
        out = subprocess.run([sys.executable, TOOL, "render", p], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertTrue(out.stdout.startswith(A.MARKER))
        with open(p, "w") as f:
            f.write('{"schema": "x"}')
        self.assertNotEqual(subprocess.run([sys.executable, TOOL, "render", p], capture_output=True).returncode, 0)


class Parts(unittest.TestCase):
    def test_every_pane_in_the_code_has_its_parts(self):
        ids = set()
        for d, _, fs in os.walk(os.path.join(ROOT, "app", "kiosk", "src", "main", "java")):
            for f in fs:
                if f.endswith(".kt"):
                    with open(os.path.join(d, f)) as fh:
                        ids |= set(re.findall(r'override val id = "([a-z0-9-]+)"', fh.read()))
        self.assertEqual(set(PARTS["panes"]), ids)

    def test_ids_are_safe_in_the_comment(self):
        for pid in list(PARTS["panes"]) + list(PARTS["libraries"]):
            self.assertRegex(pid, A.ID_RE)
        for lib in PARTS["libraries"].values():
            self.assertTrue(set(lib["used_by"]) <= set(PARTS["panes"]) | {"core", "engine"})


class Cli(unittest.TestCase):
    def test_exit_status_and_files(self):
        d = tempfile.mkdtemp()
        apk = make_apk(os.path.join(d, "a.apk"))
        fake = os.path.join(d, "apkanalyzer")
        with open(fake, "w") as f:
            f.write("#!/bin/sh\ncat <<'EOF'\n" + CLASSES + "EOF\n")
        os.chmod(fake, 0o755)
        ok = {"size": {"apk_max_bytes": {"armeabi-v7a": 10_000_000}}, "panes": {}}
        over = copy.deepcopy(ok)
        over["size"]["apk_max_bytes"]["armeabi-v7a"] = 1_000
        for budgets, want in ((ok, 0), (over, 1)):
            b = os.path.join(d, "b.json")
            with open(b, "w") as f:
                json.dump(budgets, f)
            p = subprocess.run([sys.executable, TOOL, "report", "--frame", "aqara-s1-plus", "--apk", f"armeabi-v7a={apk}",
                                "--budgets", b, "--apkanalyzer", fake, "--json", os.path.join(d, "s.json"),
                                "--md", os.path.join(d, "s.md")], capture_output=True, text=True)
            self.assertEqual(p.returncode, want, p.stderr)
            self.assertTrue(os.path.exists(os.path.join(d, "s.json")))      # written either way
        p = subprocess.run([sys.executable, TOOL, "report", "--frame", "aqara-s1-plus", "--apk", "mips=x.apk",
                            "--json", "x", "--md", "y"], capture_output=True, text=True)
        self.assertNotEqual(p.returncode, 0)


if __name__ == "__main__":
    unittest.main()
