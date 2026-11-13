"""The boot generator's targets and layout (branding/boot/target.py). Standard library only:
python3 -m unittest discover -s branding/boot/test"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from target import REPO, Layout, Target, from_frame  # noqa: E402


class FramesTest(unittest.TestCase):
    def test_every_frame_has_a_target(self):
        ids = sorted(d for d in os.listdir(os.path.join(REPO, "frames"))
                     if os.path.isfile(os.path.join(REPO, "frames", d, "design", "frame.json")))
        self.assertIn("aqara-s1-plus", ids)
        for i in ids:
            with self.subTest(frame=i):
                t = from_frame(i)
                self.assertTrue(t.name)
                Layout.for_screen(t.width, t.height)

    def test_s1(self):
        t = from_frame("aqara-s1-plus")
        self.assertEqual((t.name, t.screen), ("Aqara S1 Plus", (1440, 720)))
        self.assertTrue(t.trim)                          # Android 10 reads trim.txt
        self.assertEqual(t.splash, "allwinner-boot-resource")
        self.assertEqual(t.zip_budget, 9_000_000)       # the S1 recipe's branding test checks the same

    def test_android_6_gets_full_frames(self):
        self.assertFalse(from_frame("lululemon-mirror").trim)


class TargetTest(unittest.TestCase):
    def test_trim_needs_android_7_1(self):
        self.assertFalse(Target("X", 800, 480).trim)
        self.assertFalse(Target("X", 800, 480, android_api=24).trim)
        self.assertTrue(Target("X", 800, 480, android_api=25).trim)

    def test_refuses_bad_values(self):
        for kw in ({"width": 0}, {"width": 1440.0}, {"height": 99999}, {"splash": "png"},
                   {"android_api": 9}, {"zip_budget": 0}, {"name": " "},
                   {"splash": "allwinner-boot-resource", "height": 722}):
            with self.subTest(**kw), self.assertRaises(ValueError):
                Target(**{"name": "X", "width": 1440, "height": 720, **kw})

    def test_frame_json_without_display(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "frames", "x", "design"))
            path = os.path.join(d, "frames", "x", "design", "frame.json")
            with open(path, "w") as f:
                json.dump({"name": "X"}, f)
            with self.assertRaisesRegex(ValueError, "display"):
                from_frame("x", repo=d)
            with open(path, "w") as f:
                json.dump({"name": "X", "display": {"width": 800, "height": 480}, "boot": {"api": 29}}, f)
            with self.assertRaisesRegex(ValueError, "unknown boot field"):
                from_frame("x", repo=d)


class LayoutTest(unittest.TestCase):
    def test_s1_is_the_reference_page_at_1_5(self):
        # The values generate.py hard-coded before targets; the S1's output must not move.
        L = Layout.for_screen(1440, 720)
        self.assertEqual(L.scale, 1.5)
        self.assertEqual(L.unit, 150 / 120 * 1.5)
        self.assertEqual(L.lock_top, (480 - (150 + 22 + 46 * 1.5)) / 2)
        self.assertEqual(L.origin, (1440 / 2 - 75 * 1.5, L.lock_top * 1.5))
        self.assertEqual(L.word_top, L.lock_top + 150 + 22)
        self.assertEqual(L.device_top, 480 - 30 - 11)

    def test_the_reference_viewport_always_fits(self):
        for w, h in ((1440, 720), (1080, 1920), (1920, 1080), (800, 1280), (1024, 600), (480, 480)):
            with self.subTest(screen=(w, h)):
                L = Layout.for_screen(w, h)
                vw, vh = L.viewport
                self.assertGreaterEqual(vw + 1e-9, 960)
                self.assertGreaterEqual(vh + 1e-9, 480)
                self.assertTrue(min(abs(vw - 960), abs(vh - 480)) < 1e-9)   # one side is tight
                # the lockup sits above the device line, and the stack starts on screen
                self.assertGreater(L.lock_top, 0)
                self.assertLess(L.word_top + 46 * 1.5, L.device_top)
                self.assertGreaterEqual(L.origin[0], 0)

    def test_portrait_keeps_the_stack_share_of_width(self):
        s1, mirror = Layout.for_screen(1440, 720), Layout.for_screen(1080, 1920)
        self.assertAlmostEqual(150 * s1.scale / 1440, 150 * mirror.scale / 1080)


if __name__ == "__main__":
    unittest.main()
