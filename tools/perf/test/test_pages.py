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
                    if ref == "${picture(rnd)}":
                        continue   # the picture cards' images, generated on the page (checked below)
                    self.assertRegex(ref, r"^[\w.-]+$")
        self.assertIn('return c.toDataURL("image/jpeg", 0.85);', self.read("refdash.js"))

    def test_no_randomness(self):
        for f in FILES:
            with self.subTest(f):
                self.assertNotIn("Math.random", self.read(f))
                self.assertNotIn("crypto.getRandomValues", self.read(f))

    def test_the_reference_dashboards_follow_home_assistants_source(self):
        """docs/perf.md, "The reference dashboards": HA's 180 ms tile transitions and its masonry rule.
        A change here needs a matching change in HA's source and in that section."""
        text = self.read("refdash.js")
        for needle in (
            "transition: background-color 180ms ease-in-out;",                                # hui-tile-card
            "transition: background-color 180ms ease-in-out, opacity 180ms ease-in-out;",      # ha-tile-icon shape
            "transition: color 180ms ease-in-out;",                                            # ha-tile-icon icon
            "[300, 600, 900, 1200].filter((w) => innerWidth >= w).length",                     # hui-masonry-view
            "let i = sizes.findIndex((s) => s < 5);",
        ):
            self.assertIn(needle, text)
        self.assertNotIn("animation:", text)   # the graded pages don't animate; animation is detail

    def test_the_reference_dashboards_use_the_fixed_seed(self):
        text = self.read("refdash.js")
        self.assertIn("const SEED = 89;", text)
        self.assertIn("W.mulberry32(SEED)", text)
        self.assertIn("W.makeStates(SEED, 400, 0)", text)
        # The live updates are fixed too: each sensor's next value comes from the tick, no randomness.
        self.assertIn("(((tick * 13) % 7) - 3) / 10", text)
        self.assertNotIn("Math.random", text)
        self.assertIn('<script src="tiers.js"></script>', self.read("dashboard.html"))


if __name__ == "__main__":
    unittest.main()
