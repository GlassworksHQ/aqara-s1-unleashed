"""Clarity's camera streams (MIT): the H.264 video of the reference dashboards' live camera cards
(#174, #202), as HLS segments and as one Annex B stream for WebRTC.

Real setups play a camera over WebRTC (go2rtc in HA, webrtc.py here) or HLS (HA's `stream`
integration: fMP4 segments, which HA's frontend plays with hls.js on MSE). The fake Home Assistant
serves both from one encode, made here with ffmpeg from its moving test pattern (`testsrc`, with its
burnt-in counter), so no video is in the repo. `stream.h264` is the segments' own bitstream, remuxed
(`-c copy`), so both paths carry the same bytes. I and P frames only (`-bf 0`), as IP cameras send:
browsers' WebRTC receivers don't reorder B-frames (protocol 6; protocol 5's segments had them). The
flags make the bytes the same on every run with the same ffmpeg (bitexact, one encoder thread); the
manifest records the ffmpeg version and every file's SHA-256.

  python3 tools/perf/camera.py          # make them (once) and print where they are

Read-only for the device; it writes only into the cache.
"""
import functools
import hashlib
import json
import os
import re
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
            "-t", str(seconds), "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-preset", "veryfast", "-bf", "0",
            "-g", str(FPS * SEGMENT_S), "-keyint_min", str(FPS * SEGMENT_S), "-sc_threshold", "0",
            "-b:v", p["bitrate"], "-maxrate", p["bitrate"], "-bufsize", p["bufsize"],
            "-threads", "1", "-x264-params", "threads=1", "-fflags", "+bitexact", "-flags:v", "+bitexact",
            "-map_metadata", "-1", "-f", "hls", "-hls_time", str(SEGMENT_S), "-hls_segment_type", "fmp4",
            "-hls_fmp4_init_filename", "init.mp4", "-hls_segment_filename", "seg%03d.m4s",
            "-hls_playlist_type", "vod", "index.m3u8"]


# The segments' bitstream as Annex B (SPS and PPS before every IDR), for the WebRTC peer: read from
# the segments joined (init.mp4 then seg*.m4s, a valid fragmented MP4) on stdin.
ANNEXB_ARGS = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "mp4", "-i", "pipe:0", "-c:v", "copy",
               "-bsf:v", "h264_mp4toannexb", "-fflags", "+bitexact", "-f", "h264", "stream.h264"]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


class Unavailable(RuntimeError):
    """No ffmpeg with libx264 here: the streams can't be made. The fake answers a segment request with
    a 500 and this message rather than ending its request thread."""


def root():
    """The directory with one folder per profile (init.mp4, seg000.m4s ...). GW_PERF_CAMERA points at
    another one instead (the off-device tests use a stub: no ffmpeg there)."""
    if os.environ.get("GW_PERF_CAMERA"):
        return os.environ["GW_PERF_CAMERA"]
    return _made()


@functools.lru_cache(maxsize=None)
def _made():
    """Made once per process: the fake asks on every segment request, and running ffmpeg to find its
    version took 50 to 100 ms each time, inside the segments' fetch times (#182's verifier). A failure
    isn't cached, so installing ffmpeg mid-run works on the next request."""
    if not shutil.which("ffmpeg"):
        raise Unavailable("Clarity's camera streams need ffmpeg (with libx264) on this computer")
    encoders = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True).stdout
    if not re.search(r"\blibx264\b", encoders):
        raise Unavailable("this computer's ffmpeg has no libx264 encoder, which Clarity's camera streams need")
    version = subprocess.run(["ffmpeg", "-hide_banner", "-version"], capture_output=True, text=True).stdout.splitlines()[0]
    key = hashlib.sha256((version + json.dumps([ffmpeg_args(p) for p in sorted(PROFILES)] + [ANNEXB_ARGS])).encode()).hexdigest()[:12]
    out = os.path.join(CACHE, f"camera-{key}")
    if not os.path.exists(os.path.join(out, "manifest.json")):
        shutil.rmtree(out, ignore_errors=True)
        files = {}
        for prof in sorted(PROFILES):
            d = os.path.join(out, prof)
            os.makedirs(d)
            subprocess.run(ffmpeg_args(prof), cwd=d, check=True)
            parts = ["init.mp4"] + sorted(f for f in os.listdir(d) if f.endswith(".m4s"))
            joined = b"".join(open(os.path.join(d, f), "rb").read() for f in parts)
            subprocess.run(ANNEXB_ARGS, cwd=d, input=joined, check=True)
            files[prof] = {f: sha256(os.path.join(d, f)) for f in sorted(os.listdir(d)) if f != "index.m3u8"}
        with open(os.path.join(out, "manifest.json"), "w") as f:
            json.dump({"ffmpeg": version, "fps": FPS, "segment_s": SEGMENT_S, "loop_s": LOOP_S, "files": files}, f, indent=1)
    return out


def access_units(path):
    """An Annex B stream's access units, in order. One slice per picture (x264 with one thread), so an
    access unit ends at its picture's slice (NAL types 1 and 5); SPS, PPS and SEI go with the next."""
    with open(path, "rb") as f:
        data = f.read()
    starts, i = [], 0
    while True:
        i = data.find(b"\x00\x00\x01", i)
        if i < 0:
            break
        starts.append(i - 1 if i and data[i - 1] == 0 else i)
        i += 3
    aus, cur = [], b""
    for k, s in enumerate(starts):
        nal = data[s:starts[k + 1] if k + 1 < len(starts) else len(data)]
        cur += nal
        if nal[nal.index(b"\x00\x00\x01") + 3] & 0x1F in (1, 5):
            aus.append(cur)
            cur = b""
    return aus


def annexb(profile):
    """The profile's whole loop as one Annex B H.264 stream (the WebRTC peer's video)."""
    return os.path.join(root(), profile, "stream.h264")


def segments(profile):
    """How many segments a profile's loop has."""
    return LOOP_S // SEGMENT_S


if __name__ == "__main__":
    try:
        print(root())
    except Unavailable as e:
        sys.exit(str(e))
