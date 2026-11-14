#!/usr/bin/env python3
"""How many of the screen's pixels changed between two screenshots taken N s apart (MIT, #202).

Debugging only, read-only: it runs `screencap` over adb twice (the raw RGBA form, no file written
on the device) and counts the pixels where any of red, green or blue moved by more than --levels.
A still room through a live camera still changes many pixels (compression noise), so the count says
frames are updating, not which player drew them. Nothing in the grading path reads it.

  tools/perf/debug/pixelchange.py [--seconds 10] [--levels 3]     (ADB, ANDROID_SERIAL as for run.sh)

Prints the screen's size, the seconds between the two captures and the count of changed pixels.
"""
import argparse
import os
import struct
import subprocess
import sys
import time


def capture(adb):
    """(width, height, RGBA bytes) of one `screencap` (raw): a 12-byte header on Android 10 (width,
    height, format), 16 with the colour space on later versions; the pixels follow."""
    raw = subprocess.run(adb + ["exec-out", "screencap"], check=True, capture_output=True).stdout
    return parse(raw)


def parse(raw):
    w, h = struct.unpack("<II", raw[:8])
    head = len(raw) - w * h * 4
    if head not in (12, 16):
        raise ValueError(f"not a raw RGBA screencap ({len(raw)} bytes for {w}x{h})")
    return w, h, raw[head:]


def changed(a, b, levels):
    """Pixels whose red, green or blue differ by more than `levels` between two RGBA buffers."""
    n = 0
    for i in range(0, min(len(a), len(b)), 4):
        if abs(a[i] - b[i]) > levels or abs(a[i + 1] - b[i + 1]) > levels or abs(a[i + 2] - b[i + 2]) > levels:
            n += 1
    return n


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seconds", type=float, default=10)
    ap.add_argument("--levels", type=int, default=3)
    a = ap.parse_args()
    adb = [os.environ.get("ADB", "adb")] + (["-s", os.environ["ANDROID_SERIAL"]] if os.environ.get("ANDROID_SERIAL") else [])
    t0 = time.time()
    w, h, one = capture(adb)
    time.sleep(max(0.0, a.seconds - (time.time() - t0)))
    t1 = time.time()
    w2, h2, two = capture(adb)
    if (w, h) != (w2, h2):
        sys.exit("the screen's size changed between the two captures")
    print(f"{w}x{h}, {t1 - t0:.1f} s apart: {changed(one, two, a.levels)} pixels changed by more than {a.levels} levels")


if __name__ == "__main__":
    main()
