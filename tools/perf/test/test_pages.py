"""Every page the test ships is self-contained (nothing from the network) and seeded (no randomness)."""
import os
import re
import unittest

PAGE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "page")
FILES = sorted(f for f in os.listdir(PAGE) if f.endswith((".html", ".js")))
SVG_NS = "http://www.w3.org/2000/svg"


class Pages(unittest.TestCase):
    def read(self, f):
        with open(os.path.join(PAGE, f)) as fh:
            return fh.read()

    def test_the_pages_are_there(self):
        for f in ("index.html", "dashboard.html", "perf.js", "refdash.js", "stats.js", "tiers.js", "workload.js", "rubric.json"):
            self.assertTrue(os.path.exists(os.path.join(PAGE, f)), f)

    def test_no_url_outside_the_page_directory(self):
        for f in FILES:
            with self.subTest(f):
                text = self.read(f).replace(SVG_NS, "")
                self.assertEqual(re.findall(r"(?:https?:)?//[\w.-]+\.[a-z]{2,}", text), [])

    def test_scripts_styles_and_fetches_are_relative(self):
        for f in FILES:
            with self.subTest(f):
                text = self.read(f)
                for ref in re.findall(r'(?:src|href)="([^"]+)"', text) + re.findall(r'fetch\("([^"]+)"', text):
                    self.assertRegex(ref, r"^[\w.-]+$")

    def test_no_randomness(self):
        for f in FILES:
            with self.subTest(f):
                self.assertNotIn("Math.random", self.read(f))
                self.assertNotIn("crypto.getRandomValues", self.read(f))

    def test_the_reference_dashboards_use_the_fixed_seed(self):
        text = self.read("refdash.js")
        self.assertIn("const SEED = 89;", text)
        self.assertIn("W.mulberry32(SEED)", text)
        self.assertIn("W.makeStates(SEED, 400, 0)", text)
        self.assertIn('<script src="tiers.js"></script>', self.read("dashboard.html"))


if __name__ == "__main__":
    unittest.main()
