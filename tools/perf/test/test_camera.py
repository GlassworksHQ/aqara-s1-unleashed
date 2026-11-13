"""python3 -m unittest discover -s tools/perf/test

camera.py (#174): the streams' settings, and that ffmpeg makes the same bytes twice (when ffmpeg is on
this computer; CI's runner has it)."""
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

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


@unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg isn't on this computer")
class SameBytes(unittest.TestCase):
    def test_twice_the_same(self):
        out = []
        for _ in range(2):
            d = tempfile.mkdtemp()
            subprocess.run(camera.ffmpeg_args("360p", seconds=2), cwd=d, check=True)
            out.append({f: hashlib.sha256(open(os.path.join(d, f), "rb").read()).hexdigest()
                        for f in sorted(os.listdir(d)) if f != "index.m3u8"})
        self.assertEqual(out[0], out[1])
        self.assertEqual(sorted(out[0]), ["init.mp4", "seg000.m4s", "seg001.m4s"])


if __name__ == "__main__":
    unittest.main()
