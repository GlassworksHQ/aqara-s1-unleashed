#!/usr/bin/env python3
"""Per-install table of what an install or force-stop of S1 Kiosk did to the panel (#248).

Off-device and read-only: reads a logcat saved on the computer and touches nothing.

    adb shell 'logcat -d -b all -v time' > all.txt
    tools/install/install-churn.py all.txt

One row per force-stop of the kiosk. An install shows up as `install`: Android force-stops the app
when the install starts ("installPackageLI") and again after dexopt ("pkg removed"). Columns:

    killed      kiosk processes the force-stop(s) killed
    dexopt      installd's wall time for the dexopt (DexInv BEGIN to END)
    dex2oat     dex2oat's own "took <wall> (<cpu> cpu)" line, when logcat kept it
    starts      kiosk processes started while dexopt ran (killed again by the second force-stop)
    bindfail    ActivityManager "Exception thrown during bind" for the kiosk, up to the next row:
                the loop from Android 10's LRU list bug (frames/aqara-s1-plus/research/install-crash-loop.md)
    restart     time system_server died, if it did before the next row
"""
import re
import sys

LINE = re.compile(r"^(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d+) \w/(.+?)\(\s*(\d+)\): (.*)$")
PKG = "sh.edm.s1kiosk"


def seconds(t):
    h, m, s = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def rows(lines):
    out = []
    cur = None
    dex2oat_pid = None
    for raw in lines:
        m = LINE.match(raw.rstrip("\n"))
        if not m:
            continue
        _, t, tag, pid, msg = m.groups()
        tag = tag.strip()
        if tag == "ActivityManager" and msg.startswith(f"Force stopping {PKG} "):
            reason = msg.rsplit(": ", 1)[-1]
            if reason == "pkg removed" and cur and cur["kind"] == "install" and not cur["second"]:
                cur["second"] = True
                cur["stop"] = "stop"
                continue
            cur = {"time": t, "kind": "install" if reason == "installPackageLI" else "force-stop",
                   "why": reason, "killed": 0, "dexopt": None, "dexopt_t": None, "dex2oat": None,
                   "starts": 0, "bindfail": 0, "restart": None, "second": False, "stop": "stop"}
            out.append(cur)
            continue
        if cur is None:
            continue
        if tag == "ActivityManager" and re.match(rf"Killing \d+:{re.escape(PKG)}[:/]", msg):
            cur["killed"] += 1
        elif (tag == "installd" and msg.startswith("DexInv: --- BEGIN") and f"/{PKG}-" in msg
              and cur["kind"] == "install" and cur["dexopt_t"] is None):
            cur["dexopt_t"] = seconds(t)
        elif tag == "installd" and msg.startswith("DexInv: --- END") and f"/{PKG}-" in msg:
            if cur["dexopt_t"] is not None and cur["dexopt"] is None:
                cur["dexopt"] = seconds(t) - cur["dexopt_t"]
        elif (tag == "dex2oat" and "--compilation-reason=install" in msg and cur["dexopt_t"] is not None
              and cur["dexopt"] is None):
            dex2oat_pid = pid
        elif tag == "dex2oat" and pid == dex2oat_pid and msg.startswith("dex2oat took"):
            w = re.match(r"dex2oat took ([\d.]+)(m?s) \(([\d.]+)(m?s) cpu\)", msg)
            if w:
                def sec(v, u):
                    return float(v) / (1000 if u == "ms" else 1)
                cur["dex2oat"] = (sec(w.group(1), w.group(2)), sec(w.group(3), w.group(4)))
            dex2oat_pid = None
        elif (tag == "ActivityManager" and msg.startswith("Start proc ") and f":{PKG}" in msg
              and cur["dexopt_t"] is not None and cur["dexopt"] is None):
            cur["starts"] += 1
        elif tag == "ActivityManager" and msg.startswith("Exception thrown during bind") and PKG in msg:
            cur["bindfail"] += 1
        elif tag == "Zygote" and "Exit zygote because system server" in msg and cur["restart"] is None:
            cur["restart"] = t
    return out


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    with open(sys.argv[1], errors="replace") as f:
        table = rows(f)
    print(f"{'time':<13} {'kind':<11} {'killed':>6} {'dexopt':>7} {'dex2oat wall/cpu':>17} "
          f"{'starts':>6} {'bindfail':>8}  restart")
    for r in table:
        dexopt = f"{r['dexopt']:.1f}s" if r["dexopt"] is not None else "-"
        d2o = f"{r['dex2oat'][0]:.1f}s/{r['dex2oat'][1]:.1f}s" if r["dex2oat"] else "-"
        kind = r["kind"] if r["kind"] == "install" else f"stop:{r['why'][:6]}"
        print(f"{r['time']:<13} {kind:<11} {r['killed']:>6} {dexopt:>7} {d2o:>17} "
              f"{r['starts']:>6} {r['bindfail']:>8}  {r['restart'] or '-'}")


if __name__ == "__main__":
    main()
