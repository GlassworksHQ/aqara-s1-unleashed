"""The frame banner generator (branding/frame-banner/generate.py). Needs uharfbuzz 0.57.0 and, the
first time, the network for the pinned fonts:
python3 -m unittest discover -s branding/frame-banner/test"""
import copy
import os
import re
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import generate as g  # noqa: E402


def cells(svg):
    """The dashboard's cell rects: (x, y, w, h) in body mm, the lit ones flagged."""
    return [(float(m[1]), float(m[2]), float(m[3]), float(m[4]), m[5] == "url(#lit)")
            for m in re.finditer(r'<rect x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="([\d.]+)" rx="[\d.]+" fill="([^"]+)"', svg)
            if m[5] == "url(#lit)" or 'fill-opacity=".08"' in svg[m.start():m.start() + 200]]


class FramesTest(unittest.TestCase):
    def test_every_frame_with_a_tagline_draws_and_fits(self):
        ids = g.frames_with_tagline()
        self.assertEqual({"aqara-s1-plus", "lululemon-mirror", "generic-android"} - set(ids), set())
        for fid in ids:
            for theme in ("dark", "light"):
                with self.subTest(frame=fid, theme=theme):
                    svg = g.banner(g.read_frame(fid), theme)
                    ET.fromstring(svg)                                   # well-formed
                    self.assertNotRegex(svg, r"NaN|Infinity")
                    self.assertIn(f'aria-label="{g.read_frame(fid)["name"]}, a Glassworks frame"', svg)
                    self.assertIn('viewBox="0 0 640 180"', svg)
                    self.assertNotIn("<text", svg)                       # outlined, as on the site

    def test_the_same_frame_gives_the_same_bytes(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            for fid in g.frames_with_tagline():
                for pa, pb in zip(g.write(fid, a), g.write(fid, b)):
                    with open(pa, "rb") as fa, open(pb, "rb") as fb:
                        self.assertEqual(fa.read(), fb.read(), os.path.basename(pa))

    def test_the_themes_differ_only_in_colour(self):
        f = g.read_frame("aqara-s1-plus")
        strip = lambda s: re.sub(r'#[0-9a-f]{6}|fill-opacity="[^"]*"|stop-opacity="[^"]*"', "", s)  # noqa: E731
        self.assertEqual(strip(g.banner(f, "dark")), strip(g.banner(f, "light")))


class TextTest(unittest.TestCase):
    def frame(self, **kw):
        f = copy.deepcopy(g.read_frame("aqara-s1-plus"))
        f.update(kw)
        return f

    def test_a_tagline_that_runs_past_the_edge_fails(self):
        with self.assertRaises(g.DoesNotFit) as e:
            g.banner(self.frame(tagline="A tagline far too long to fit in the banner's text column"), "dark")
        self.assertIn("tagline", str(e.exception))

    def test_the_longest_tagline_that_fits_is_about_50_characters(self):
        g.banner(self.frame(tagline="Any Android device, as an app, with no root at all"), "dark")

    def test_a_long_name_shrinks_then_fails(self):
        svg_short = g.banner(self.frame(), "dark")
        svg_long = g.banner(self.frame(name="Aqara Panel Hub S1 Plus"), "dark")   # shrinks, still fits
        self.assertNotEqual(svg_short, svg_long)
        with self.assertRaises(g.DoesNotFit):
            g.banner(self.frame(name="Aqara " + "Extraordinarily Long Model Name Plus"), "dark")

    def test_brand_must_start_the_name(self):
        with self.assertRaises(ValueError):
            g.banner(self.frame(brand="Lumi"), "dark")
        with self.assertRaises(ValueError):
            g.banner(self.frame(brand="Aqara S"), "dark")                  # not at a word break

    def test_without_brand_the_whole_name_is_bold(self):
        f = self.frame()
        del f["brand"]
        self.assertEqual(g.split_name(f), ("", "Aqara S1 Plus"))
        svg = g.banner(f, "dark")
        mu = f'fill="{g.THEMES["dark"]["mu"]}"/>'
        self.assertEqual(svg.count(mu), 1)                               # only frames/<id>
        self.assertEqual(g.banner(self.frame(), "dark").count(mu), 2)    # and the light "Aqara"
        self.assertEqual(g.split_name(self.frame()), ("Aqara", "S1 Plus"))

    def test_no_tagline_is_an_error_not_a_blank_line(self):
        f = self.frame()
        del f["tagline"]
        with self.assertRaises(ValueError):
            g.banner(f, "dark")

    def test_widths_match_the_sites_measure(self):
        # glassworks.dev scripts/brand/text.mjs measure(), run on 2026-11-13 with the same fonts
        for s, face, size, ls, site in [("Studio Mirror", "semibold", 34, -0.6, 208.57), ("Aqara", "light", 34, -0.6, 90.54),
                                        ("A 40-inch mirror with Android behind it.", "regular", 11.5, 0, 215.61),
                                        ("GLASSWORKS · FRAME", "medium", 9, 2, 136.27), ("frames/lululemon-mirror", "mono", 12, 0, 165.60)]:
            with self.subTest(text=s):
                self.assertAlmostEqual(g.measure(s, face, size, ls), site, delta=0.01)


class DeviceTest(unittest.TestCase):
    def test_landscape_and_portrait_screens_get_3x2_and_2x3_cells(self):
        land = g.screen_box(g.read_frame("aqara-s1-plus")["model"])
        port = g.screen_box(g.read_frame("lululemon-mirror")["model"])
        t = g.THEMES["dark"]
        for sc, (cols, rows) in ((land, (3, 2)), (port, (2, 3))):
            cs = cells(g.dashboard(t, sc))
            self.assertEqual(len(cs), 6)
            self.assertEqual(len({c[0] for c in cs}), cols)
            self.assertEqual(len({c[1] for c in cs}), rows)
            self.assertEqual(sum(c[4] for c in cs), 2)
            for x, y, w, h, _ in cs:                                     # inside the screen
                self.assertGreaterEqual(x, sc["x"])
                self.assertLessEqual(x + w, sc["x"] + sc["w"] + 0.01)
                self.assertGreaterEqual(y, sc["y"])
                self.assertLessEqual(y + h, sc["y"] + sc["h"] + 0.01)

    def test_the_screen_box_is_the_given_one_or_the_face_less_the_bezel(self):
        m = g.read_frame("lululemon-mirror")["model"]
        sc = g.screen_box(m)
        self.assertEqual((sc["w"], sc["h"], sc["y"]), (m["screen"]["width"], m["screen"]["height"], m["screen"]["top"]))
        self.assertAlmostEqual(sc["x"], (m["body"]["width"] - m["screen"]["width"]) / 2)
        s1 = g.read_frame("aqara-s1-plus")["model"]
        sc = g.screen_box(s1)
        self.assertEqual((sc["x"], sc["w"]), (s1["bezel"], s1["body"]["width"] - 2 * s1["bezel"]))

    def test_the_device_is_drawn_from_the_model_inside_the_cell(self):
        f = g.read_frame("aqara-s1-plus")
        wide = copy.deepcopy(f)
        wide["model"]["body"]["width"] *= 1.5
        self.assertNotEqual(g.banner(f, "dark"), g.banner(wide, "dark"))
        for fid in g.frames_with_tagline():
            d = g.device(g.THEMES["dark"], g.read_frame(fid)["model"])
            # the face's corner sits inside the 300 x 180 cell
            ox, oy = (float(v) for v in re.search(r"matrix\([\d.]+ [\d.]+ 0 [\d.]+ ([\d.]+) ([\d.]+)\)\"><rect [^>]*stroke-opacity=\".75\"", d).groups())
            self.assertTrue(0 < ox < g.CELL and 0 < oy < g.BH, (fid, ox, oy))


class FontTest(unittest.TestCase):
    def test_woff_unwraps_to_a_font_harfbuzz_reads(self):
        path = g.font_file("regular")
        with open(path, "rb") as f:
            head = f.read(4)
        self.assertIn(head, (b"\x00\x01\x00\x00", b"OTTO", b"true"))
        self.assertGreater(g.measure("Glassworks"), 0)


if __name__ == "__main__":
    unittest.main()
