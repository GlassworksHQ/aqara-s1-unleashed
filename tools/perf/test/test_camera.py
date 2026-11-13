"""python3 -m unittest discover -s tools/perf/test

camera.py (#174): the streams' settings, and that ffmpeg makes the same bytes twice (when ffmpeg is on
this computer; CI's runner has it)."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import camera  # noqa: E402
import dashboards as D  # noqa: E402


class Settings(unittest.TestCase):
    def test_h264_main_hls_fmp4_one_second_segments(self):
        a = camera.ffmpeg_args("720p")
        for flag in (["-c:v", "libx264"], ["-profile:v", "main"], ["-hls_segment_type", "fmp4"], ["-hls_time", "1"],
                     ["-g", "15"], ["-threads", "1"], ["-fflags", "+bitexact"], ["-flags:v", "+bitexact"]):
            self.assertTrue(any(a[i:i + 2] == flag for i in range(len(a))), flag)
        self.assertIn("testsrc=size=1280x720:rate=15", " ".join(a))           # a moving pattern with a counter, no file
        self.assertIn("testsrc=size=640x360:rate=15", " ".join(camera.ffmpeg_args("360p")))

    def test_the_cameras_match_the_profiles(self):
        self.assertEqual(set(D.CAMERA_PROFILES.values()), set(camera.PROFILES))
        self.assertEqual(D.TIERS["CAMERA"]["fps"], camera.FPS)


class Root(unittest.TestCase):
    """camera.root() is asked on every segment request; it runs ffmpeg only once per process."""

    def setUp(self):
        camera._made.cache_clear()
        self.addCleanup(camera._made.cache_clear)
        self.env = os.environ.pop("GW_PERF_CAMERA", None)
        self.addCleanup(lambda: self.env is not None and os.environ.__setitem__("GW_PERF_CAMERA", self.env))

    def fake(self, encoders="V..... libx264  H.264 / AVC\n"):
        calls, cache = [], tempfile.mkdtemp()

        def run(args, **kw):
            calls.append(args[:3])
            out = encoders if "-encoders" in args else "ffmpeg version 7.1-test\n"
            if args[0] == "ffmpeg" and "-encoders" not in args and "-version" not in args:
                for f in ("init.mp4", "seg000.m4s"):
                    with open(os.path.join(kw["cwd"], f), "wb") as fh:
                        fh.write(b"x")
            return subprocess.CompletedProcess(args, 0, out, "")
        return calls, cache, run

    def test_computed_once(self):
        calls, cache, run = self.fake()
        with mock.patch.object(camera.subprocess, "run", run), mock.patch.object(camera.shutil, "which", lambda _: "/x/ffmpeg"), \
                mock.patch.object(camera, "CACHE", cache):
            first = camera.root()
            n = len(calls)
            for _ in range(20):
                self.assertEqual(camera.root(), first)
        self.assertEqual(len(calls), n)                     # nothing ran after the first

    def test_no_ffmpeg_or_no_libx264_is_a_clear_error(self):
        with mock.patch.object(camera.shutil, "which", lambda _: None):
            with self.assertRaisesRegex(camera.Unavailable, "need ffmpeg"):
                camera.root()
        calls, cache, run = self.fake(encoders="V..... mpeg4  MPEG-4 part 2\n")
        with mock.patch.object(camera.subprocess, "run", run), mock.patch.object(camera.shutil, "which", lambda _: "/x/ffmpeg"):
            with self.assertRaisesRegex(camera.Unavailable, "no libx264"):
                camera.root()


class SameBytes(unittest.TestCase):
    def test_twice_the_same(self):
        if not shutil.which("ffmpeg"):
            if os.environ.get("CI"):
                raise AssertionError("ffmpeg is needed for this test (CI installs it)")
            raise unittest.SkipTest("ffmpeg isn't on this computer")
        out = []
        for _ in range(2):
            d = tempfile.mkdtemp()
            subprocess.run(camera.ffmpeg_args("360p", seconds=2), cwd=d, check=True)
            out.append({f: camera.sha256(os.path.join(d, f)) for f in sorted(os.listdir(d)) if f != "index.m3u8"})
        self.assertEqual(out[0], out[1])
        self.assertEqual(sorted(out[0]), ["init.mp4", "seg000.m4s", "seg001.m4s"])


if __name__ == "__main__":
    unittest.main()
