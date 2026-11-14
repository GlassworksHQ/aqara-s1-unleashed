#!/usr/bin/env python3
"""Clarity debugging helper (MIT, #202): which screen-camera frames to look at for a view.

DEBUGGING ONLY. A camera filming the panel's screen can check that Clarity measures what is on the
screen (for example, that a stall the page logged was a stall of the video). Clarity never depends
on it: nothing in run.sh, perfrun.py, report.py, summary.py or the graders imports this file or
reads what a screen camera records, and no grade, report.json, report.md or summary takes anything
from it (the maintainer, 2026-11-13). The frames show the room: keep them local, never in a PR.

Reads only: a run's refstart-<k>.json (the page's lines) and, if given, a recording's index.tsv
(Mac epoch ms, frame number per line, as ~/.t3/glassworks-m1/screencam/record.sh writes). With
--adb it also runs `date` on the panel (read-only) to measure the panel's clock against this one.
Writes nothing.

  tools/perf/debug/screencam_moments.py <run-dir> [--view heavy] [--runs 1,2,3]
      [--frames <recording-dir>] [--panel-offset-ms N | --adb] [--cam-offset-ms N]

Times: the page's own (performance.now) plus its origin (origin_epoch_ms) give the panel's wall
clock; less the panel's offset against this computer (panel minus Mac, from --adb or the flag)
gives Mac epoch ms, the recording's clock. record.sh numbers frames from when it started ffmpeg,
which shows its first frame a little later: check one known moment (the navigation, when the screen
changes to the view) and pass the difference as --cam-offset-ms (positive: the frames are later).

For each moment it prints the Mac epoch ms and, with --frames, the frames around it. For each
window (the camera window, each stall the receiver counted, the page's longest callback gaps) it
prints the frames at its start and end and how far the test pattern's frame counter should move at
15 fps if the video played: read both counters, and the counter's move against the time says
whether the video stalled there.
"""
import argparse
import bisect
import json
import os
import subprocess
import sys
import time

FPS = 15


def panel_offset_ms(adb, tries=5):
    """The panel's wall clock minus this computer's, in ms, from the read-only `date` with the
    shortest round trip; None if the panel's date has no sub-second field."""
    best = None
    for _ in range(tries):
        a = time.time()
        out = subprocess.run(adb + ["shell", "date +%s.%N"], capture_output=True, text=True, timeout=20).stdout.strip()
        b = time.time()
        try:
            panel = float(out)
        except ValueError:
            return None
        if "." not in out or out.endswith(".N"):
            return None
        rtt = b - a
        if best is None or rtt < best[0]:
            best = (rtt, (panel - (a + b) / 2) * 1000)
    return round(best[1]) if best else None


def read_index(d):
    rows = []
    with open(os.path.join(d, "index.tsv")) as f:
        for line in f:
            parts = line.split()
            if len(parts) == 2:
                rows.append((int(parts[0]), parts[1]))
    rows.sort()
    return rows


def frames_near(index, epoch_ms, n=1):
    """The frames just before and after a Mac epoch ms: [(frame, its ms)]."""
    if not index:
        return []
    ts = [t for t, _ in index]
    i = bisect.bisect_left(ts, epoch_ms)
    return [(index[k][1], index[k][0]) for k in range(max(0, i - n), min(len(index), i + n))]


def moments(c, view):
    """The view's moments and windows on the panel's wall clock (epoch ms), from its page lines.
    [(label, start, end or None)]."""
    e = (c.get("tiers") or {}).get(view) or (c.get("fallback") or {}).get(view)
    if not e or not e.get("page"):
        return [], "never usable (no refdash line)" if e else "not opened in this run"
    page = e["page"]
    origin = page.get("origin_epoch_ms")
    if origin is None:
        return [], "no origin_epoch_ms in its refdash line"
    at = lambda t: None if t is None else origin + t  # noqa: E731
    u = page.get("usable") or {}
    out = [("navigation", at(page.get("nav_ms")), None),
           ("first screen drawn", at(u.get("rendered_tick_ms")), None),
           ("usable (main thread free)", at(u.get("interactive_ms")), None)]
    cam = e.get("camera") or {}
    if cam.get("t0_ms") is not None:
        out.append(("camera window", at(cam["t0_ms"]), at(cam["t0_ms"] + (cam.get("window_ms") or 0))))
    nav = cam.get("nav_ms", page.get("nav_ms"))
    for i, s in enumerate(cam.get("streams") or []):
        if s.get("first_frame_ms") is not None and nav is not None:
            out.append((f"camera {i}: first frame", at(nav + s["first_frame_ms"]), None))
        for a, b in (s.get("stalls_at") or [])[:5]:
            out.append((f"camera {i}: stall by {s.get('gaps_source') or 'the callbacks'} (graded)", at(a), at(b)))
        for a, b in ((s.get("callback_gaps") or {}).get("stalls_at") or [])[:3]:
            out.append((f"camera {i}: frame callbacks' gap (detail: the page busy?)", at(a), at(b)))
    if e.get("timed_out_s"):
        out.append((f"timed out (after {e.get('timed_out_after')})", None, None))
    return out, None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run_dir")
    ap.add_argument("--view", default="heavy")
    ap.add_argument("--runs", default="", help="comma-separated run numbers (default: every refstart-<k>.json)")
    ap.add_argument("--frames", help="a recording's directory (index.tsv)")
    ap.add_argument("--panel-offset-ms", type=float, help="the panel's clock minus this computer's")
    ap.add_argument("--adb", action="store_true", help="measure that offset with the panel's `date` (read-only)")
    ap.add_argument("--cam-offset-ms", type=float, default=0, help="the frames' lag behind index.tsv's times")
    a = ap.parse_args()
    off = a.panel_offset_ms
    if a.adb:
        adb = [os.environ.get("ADB", "adb")] + (["-s", os.environ["ANDROID_SERIAL"]] if os.environ.get("ANDROID_SERIAL") else [])
        off = panel_offset_ms(adb)
        print(f"panel clock minus this computer's: {off} ms" if off is not None else
              "the panel's date gave no sub-second time; pass --panel-offset-ms", file=sys.stderr)
    off = off or 0
    index = read_index(a.frames) if a.frames else []
    runs = [int(x) for x in a.runs.split(",") if x] or sorted(
        int(f[len("refstart-"):-5]) for f in os.listdir(a.run_dir) if f.startswith("refstart-") and f.endswith(".json"))
    for k in runs:
        with open(os.path.join(a.run_dir, f"refstart-{k}.json")) as f:
            c = json.load(f)
        ms, why = moments(c, a.view)
        print(f"run {k}, {a.view}" + (f": {why}" if why else ""))
        for label, s, e in ms:
            if s is None:
                print(f"  {label}")
                continue
            s_mac, e_mac = s - off + a.cam_offset_ms, None if e is None else e - off + a.cam_offset_ms
            line = f"  {label}: {round(s_mac)}" + ("" if e_mac is None else f" to {round(e_mac)} ({(e - s) / 1000:.1f} s; "
                                                                           f"if playing, the counter moves {round((e - s) / 1000 * FPS)})")
            print(line)
            if index:
                near = frames_near(index, s_mac) + (frames_near(index, e_mac) if e_mac is not None else [])
                if near:
                    print("     frames: " + ", ".join(f"{n} ({t - round(s_mac):+d} ms)" for n, t in near))


if __name__ == "__main__":
    main()
