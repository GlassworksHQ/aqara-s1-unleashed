"""tools/perf/debug/screencam_moments.py (#202): the screen camera is for debugging only. Nothing in
the grading path may use it, and the helper maps the page's times to the recording's frames."""
import importlib.util
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PERF = os.path.dirname(HERE)
spec = importlib.util.spec_from_file_location("screencam_moments", os.path.join(PERF, "debug", "screencam_moments.py"))
M = importlib.util.module_from_spec(spec)
spec.loader.exec_module(M)


class Isolation(unittest.TestCase):
    def test_nothing_in_the_grading_path_reads_the_screen_camera(self):
        """The maintainer, 2026-11-13: Clarity never depends on the screen camera."""
        bad = re.compile(r"screencam|debug[/.]|index\.tsv|StreamCam", re.I)
        for root, dirs, files in os.walk(PERF):
            dirs[:] = [d for d in dirs if d not in ("debug", "test", "node_modules", "__pycache__")]
            for f in files:
                if f.endswith((".py", ".sh", ".ts", ".js", ".mjs", ".json")):
                    with open(os.path.join(root, f), errors="replace") as fh:
                        hits = [l for l in fh.read().splitlines() if bad.search(l)]
                    self.assertEqual(hits, [], f"{os.path.relpath(os.path.join(root, f), PERF)} mentions the screen camera")


class Moments(unittest.TestCase):
    def test_page_times_map_to_the_recordings_frames(self):
        c = {"tiers": {"heavy": {
            "page": {"origin_epoch_ms": 1_000_000, "nav_ms": 5000, "usable": {"rendered_tick_ms": 6000, "interactive_ms": 9000}},
            "camera": {"t0_ms": 12000, "window_ms": 30000, "nav_ms": 5000,
                       "streams": [{"first_frame_ms": 2000, "stalls_at": [[20000, 21000]], "gaps_source": "webrtc",
                                    "callback_gaps": {"stalls_at": [[15000, 17500]]}}]}}}}
        ms, why = M.moments(c, "heavy")
        self.assertIsNone(why)
        got = {label: (s, e) for label, s, e in ms}
        self.assertEqual(got["navigation"], (1_005_000, None))
        self.assertEqual(got["usable (main thread free)"], (1_009_000, None))
        self.assertEqual(got["camera window"], (1_012_000, 1_042_000))
        self.assertEqual(got["camera 0: first frame"], (1_007_000, None))
        self.assertEqual(got["camera 0: stall by webrtc (graded)"], (1_020_000, 1_021_000))
        self.assertEqual(got["camera 0: frame callbacks' gap (detail: the page busy?)"], (1_015_000, 1_017_500))
        index = [(1_004_750, "00001"), (1_005_000, "00002"), (1_005_250, "00003")]
        self.assertEqual(M.frames_near(index, 1_005_100), [("00002", 1_005_000), ("00003", 1_005_250)])

    def test_a_view_never_usable_says_so(self):
        self.assertEqual(M.moments({"tiers": {"heavy": {"nonce": "x"}}}, "heavy"), ([], "never usable (no refdash line)"))
        self.assertEqual(M.moments({"tiers": {}}, "heavy"), ([], "not opened in this run"))


if __name__ == "__main__":
    unittest.main()
