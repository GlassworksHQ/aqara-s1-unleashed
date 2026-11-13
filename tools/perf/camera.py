"""Clarity's camera streams (MIT): HLS segments for the reference dashboards' live camera cards (#174).

HA's `stream` integration serves a camera as HLS (fMP4 segments, H.264), and HA's frontend plays it
with hls.js on MSE (ha-hls-player.ts), which on a panel ends in its hardware decoder. The fake Home
Assistant serves the same: these segments, made here with ffmpeg from its moving test pattern
(`testsrc`, with its burnt-in counter), so no video is in the repo. The flags make the bytes the same
on every run with the same ffmpeg (bitexact, one encoder thread); the manifest records the ffmpeg
version and every file's SHA-256.

  python3 tools/perf/camera.py          # make them (once) and print where they are

Read-only for the device; it writes only into the cache.
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys

# The streams in page/tiers.json (CAMERAS): main stream 1280x720 and sub-streams 640x360, 15 fps.
PROFILES = {
    "720p": {"size": "1280x720", "bitrate": "1500k", "bufsize": "3000k"},
    "360p": {"size": "640x360", "bitrate": "500k", "bufsize": "1000k"},
}
FPS = 15
SEGMENT_S = 1
LOOP_S = 120          # the loop the fake serves as a live stream (a view's live phase is shorter)

CACHE = os.environ.get("GW_PERF_CACHE") or os.path.join(os.path.expanduser("~"), ".cache", "glassworks-perf")


def ffmpeg_args(profile, seconds=LOOP_S):
    p = PROFILES[profile]
    return ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", f"testsrc=size={p['size']}:rate={FPS}",
            "-t", str(seconds), "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-preset", "veryfast",
            "-g", str(FPS * SEGMENT_S), "-keyint_min", str(FPS * SEGMENT_S), "-sc_threshold", "0",
            "-b:v", p["bitrate"], "-maxrate", p["bitrate"], "-bufsize", p["bufsize"],
            "-threads", "1", "-x264-params", "threads=1", "-fflags", "+bitexact", "-flags:v", "+bitexact",
            "-map_metadata", "-1", "-f", "hls", "-hls_time", str(SEGMENT_S), "-hls_segment_type", "fmp4",
            "-hls_fmp4_init_filename", "init.mp4", "-hls_segment_filename", "seg%03d.m4s",
            "-hls_playlist_type", "vod", "index.m3u8"]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


def root():
    """The directory with one folder per profile (init.mp4, seg000.m4s ...). GW_PERF_CAMERA points at
    another one instead (the off-device tests use a stub: no ffmpeg there)."""
    if os.environ.get("GW_PERF_CAMERA"):
        return os.environ["GW_PERF_CAMERA"]
    if not shutil.which("ffmpeg"):
        raise SystemExit("Clarity's camera streams need ffmpeg (with libx264) on this computer")
    version = subprocess.run(["ffmpeg", "-hide_banner", "-version"], capture_output=True, text=True).stdout.splitlines()[0]
    key = hashlib.sha256((version + json.dumps([ffmpeg_args(p) for p in sorted(PROFILES)])).encode()).hexdigest()[:12]
    out = os.path.join(CACHE, f"camera-{key}")
    if not os.path.exists(os.path.join(out, "manifest.json")):
        shutil.rmtree(out, ignore_errors=True)
        files = {}
        for prof in sorted(PROFILES):
            d = os.path.join(out, prof)
            os.makedirs(d)
            subprocess.run(ffmpeg_args(prof), cwd=d, check=True)
            files[prof] = {f: sha256(os.path.join(d, f)) for f in sorted(os.listdir(d)) if f != "index.m3u8"}
        with open(os.path.join(out, "manifest.json"), "w") as f:
            json.dump({"ffmpeg": version, "fps": FPS, "segment_s": SEGMENT_S, "loop_s": LOOP_S, "files": files}, f, indent=1)
    return out


def segments(profile):
    """How many segments a profile's loop has."""
    return LOOP_S // SEGMENT_S


if __name__ == "__main__":
    print(root())
    sys.exit(0)
