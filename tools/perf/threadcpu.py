#!/usr/bin/env python3
"""Per-thread CPU of the kiosk's processes over a window (MIT).

Read-only: it runs `cat /proc/<pid>/task/*/stat` over adb twice and diffs the jiffies. It writes
nothing on the device and needs no root. Used for #105 (docs/research/idle-cpu.md).

  tools/perf/threadcpu.py [-s SERIAL] [--seconds 60] [--package sh.edm.s1kiosk] [--extra otbr-agent]

Prints each thread's share of one core over the window, hottest first, grouped by process.
"""
import argparse
import os
import subprocess
import sys
import time

ADB = os.environ.get("ADB") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")


def adb(serial, cmd):
    args = [ADB] + (["-s", serial] if serial else []) + ["shell", cmd]
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def parse_stat(line):
    """One /proc/<pid>/task/<tid>/stat line -> (tid, name, utime + stime in jiffies)."""
    head, _, rest = line.rpartition(")")
    tid, _, name = head.partition(" (")
    f = rest.split()
    # rest starts at field 3 (state); utime and stime are fields 14 and 15.
    return int(tid), name, int(f[11]) + int(f[12])


def pids(serial, package, extra):
    out = adb(serial, "ps -A -o PID,NAME")
    found = {}
    for line in out.splitlines()[1:]:
        parts = line.split(None, 1)
        if len(parts) != 2:
            continue
        pid, name = parts[0], parts[1].strip()
        if name == package or name.startswith(package + ":") or name in extra:
            found[int(pid)] = name
    return found


def sample(serial, procs):
    cmd = "; ".join(f"cat /proc/{p}/task/*/stat 2>/dev/null" for p in procs) + "; cat /proc/uptime"
    out = adb(serial, cmd).splitlines()
    uptime = float(out[-1].split()[0])
    threads = {}
    for line in out[:-1]:
        if line.strip():
            tid, name, j = parse_stat(line)
            threads[tid] = (name, j)
    return uptime, threads


def thread_owner(serial, procs):
    cmd = "; ".join(f"for t in /proc/{p}/task/*; do echo {p} ${{t##*/}}; done" for p in procs)
    owner = {}
    for line in adb(serial, cmd).splitlines():
        p, t = line.split()
        owner[int(t)] = int(p)
    return owner


def shares(before, after, owner, procs, hz, window):
    """Threads seen at both ends -> [(process name, tid, thread name, % of one core)]."""
    rows = []
    for tid, (name, j1) in after.items():
        if tid in before:
            pct = 100 * (j1 - before[tid][1]) / hz / window
            rows.append((procs.get(owner.get(tid), "?"), tid, name, pct))
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--seconds", type=float, default=60)
    ap.add_argument("--package", default="sh.edm.s1kiosk")
    ap.add_argument("--extra", action="append", default=[], help="another process name to include")
    ap.add_argument("--hz", type=int, default=100, help="CLK_TCK on the device")
    ap.add_argument("--min", type=float, default=0.2, help="hide threads under this %% of a core")
    a = ap.parse_args()

    procs = pids(a.serial, a.package, set(a.extra))
    if not procs:
        sys.exit(f"no process named {a.package}")
    owner = thread_owner(a.serial, procs)
    t0, before = sample(a.serial, procs)
    time.sleep(a.seconds)
    t1, after = sample(a.serial, procs)
    window = t1 - t0
    if pids(a.serial, a.package, set(a.extra)) != procs:
        print("warning: a process started or stopped during the window; its threads are missing", file=sys.stderr)

    rows = shares(before, after, owner, procs, a.hz, window)
    print(f"window {window:.1f} s, {len(rows)} threads, % of one core")
    by_proc = {}
    for proc, _, _, pct in rows:
        by_proc[proc] = by_proc.get(proc, 0) + pct
    for proc, total in sorted(by_proc.items(), key=lambda kv: -kv[1]):
        print(f"\n{total:6.1f}  {proc}")
        for p, tid, name, pct in sorted(rows, key=lambda r: -r[3]):
            if p == proc and pct >= a.min:
                print(f"{pct:6.1f}    {name} ({tid})")


if __name__ == "__main__":
    main()
