#!/usr/bin/env python3
"""Per-thread CPU of the kiosk's processes over a window (MIT).

Read-only: it runs `cat /proc/<pid>/task/*/stat` over adb twice and diffs the jiffies. It writes
nothing on the device and needs no root. Used for #105 (frames/aqara-s1-plus/research/idle-cpu.md).

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
    """One /proc/<pid>/stat or /proc/<pid>/task/<tid>/stat line -> (id, name, utime + stime in jiffies)."""
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
    """-> (uptime s, {pid: process jiffies}, {tid: (pid, thread name, jiffies)}).

    The process line counts threads that already exited; the thread lines say who owns each thread.
    """
    cmd = "".join(
        f'echo "P {p} $(cat /proc/{p}/stat)"; for t in /proc/{p}/task/*/stat; do echo "T {p} $(cat $t)"; done; '
        for p in procs
    ) + "cat /proc/uptime"
    out = adb(serial, cmd + " 2>/dev/null").splitlines()
    uptime = float(out[-1].split()[0])
    totals, threads = {}, {}
    for line in out[:-1]:
        kind, pid, stat = (line.split(" ", 2) + ["", ""])[:3]
        if not stat.strip():
            continue  # the process or thread exited between the glob and the read
        try:
            tid, name, j = parse_stat(stat)
        except (ValueError, IndexError):
            continue  # not a stat line (e.g. an error message from a wrapper that merges stderr)
        if kind == "P":
            totals[int(pid)] = j
        else:
            threads[tid] = (int(pid), name, j)
    return uptime, totals, threads


def shares(before, after, procs, hz, window):
    """Two samples -> ({process name: % of one core}, [(process name, tid, thread name, %)]).

    Thread rows only cover threads alive at both ends; the process totals cover all of them.
    """
    pct = lambda d: 100 * d / hz / window  # noqa: E731
    totals = {}
    for p, j in after[1].items():
        if p in before[1]:
            totals[procs[p]] = totals.get(procs[p], 0) + pct(j - before[1][p])
    rows = [
        (procs[p], tid, name, pct(j - before[2][tid][2]))
        for tid, (p, name, j) in after[2].items()
        if tid in before[2] and before[2][tid][0] == p
    ]
    return totals, rows


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
    before = sample(a.serial, procs)
    time.sleep(a.seconds)
    after = sample(a.serial, procs)
    window = after[0] - before[0]
    if pids(a.serial, a.package, set(a.extra)) != procs:
        print("warning: a process started or stopped during the window; it is left out", file=sys.stderr)
    totals, rows = shares(before, after, procs, a.hz, window)

    print(f"window {window:.1f} s, {len(rows)} threads, % of one core")
    for proc, total in sorted(totals.items(), key=lambda kv: -kv[1]):
        print(f"\n{total:6.1f}  {proc}")
        for p, tid, name, pct in sorted(rows, key=lambda r: -r[3]):
            if p == proc and pct >= a.min:
                print(f"{pct:6.1f}    {name} ({tid})")


if __name__ == "__main__":
    main()
