#!/usr/bin/env python3
"""A stand-in for `adb` that answers like an Android 10 panel, for test_runner.py.

State lives in $FAKE_ADB_STATE (a directory): the nonce of the page the runner opened, the taps it
sent, and the time ranges in which the fake screen paints. logcat, SurfaceFlinger and the fake page
all use this machine's monotonic clock, as the real ones share the device's.
"""
import json
import os
import re
import sys
import time

STATE = os.environ["FAKE_ADB_STATE"]
PKG = "sh.edm.s1kiosk"
PERIOD_NS = 16_666_666
PIDS = {PKG: 4242, PKG + ":gpu_disable_art_image_": 4300, PKG + ":sandboxed_tab_disable_art_image_24": 4301, "otbr-agent": 900}


def state(name, default=None):
    try:
        with open(os.path.join(STATE, name)) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save(name, value):
    with open(os.path.join(STATE, name), "w") as f:
        json.dump(value, f)


def paint(start, end):
    save("paint.json", state("paint.json", []) + [[start, end]])


def now():
    return time.monotonic()


def shell(cmd):
    props = {"ro.product.manufacturer": "Example", "ro.product.model": "Test Panel", "ro.build.version.release": "10",
             "ro.build.version.sdk": "29", "ro.product.cpu.abi": "armeabi-v7a", "ro.product.cpu.abilist": "armeabi-v7a,armeabi",
             "ro.board.platform": "testsoc", "ro.hardware": "testhw"}
    if cmd.startswith("getprop "):
        return props.get(cmd.split()[1], "")
    if cmd.startswith("am start"):
        m = re.search(r"--es open '([^']+)'", cmd)
        # Like the S1 kiosk (#103's pins): `open` is dropped until GeckoView's session is up.
        if m and state("session_up.json", True):
            nonce = re.search(r"nonce=(\w+)", m.group(1)).group(1)
            save("refnonce.json" if "/dashboard.html" in m.group(1) else "nonce.json", [nonce, m.group(1)])
        if " -W " in cmd:
            if not os.environ.get("FAKE_NO_DASHBOARD"):
                save("cold.json", True)
            return "Starting: Intent\nStatus: ok\nActivity: sh.edm.s1kiosk/.MainActivity\nTotalTime: 1700\nWaitTime: 1710\nComplete\n"
        return "Starting: Intent\n"
    if cmd.startswith("input tap"):
        save("taps.json", state("taps.json", 0) + 1)
        return ""
    if cmd.startswith("dumpsys SurfaceFlinger --list"):
        return f"Display Root#0\nSurfaceView - {PKG}/{PKG}.MainActivity#0\nBackground for -SurfaceView - {PKG}/{PKG}.MainActivity#0\n"
    if cmd.startswith("dumpsys SurfaceFlinger --latency"):
        t, frames = int(now() * 1e9), []
        for a, b in state("paint.json", []):
            x = int(a * 1e9)
            while x <= min(int(b * 1e9), t):
                frames.append(x)
                x += PERIOD_NS
        rows = [f"{f - 1000}\t{f}\t{f - 2000}" for f in frames[-127:]]
        return "\n".join([str(PERIOD_NS)] + rows + ["0\t0\t0"]) + "\n"
    if cmd.startswith("dumpsys package"):
        return "    versionCode=403 minSdk=26 targetSdk=34\n    versionName=0.4.3\n"
    if cmd.startswith("pm path"):
        return "package:/data/app/sh.edm.s1kiosk-1/base.apk\n"
    if cmd.startswith("stat -c %s"):
        return "123456789\n"
    if cmd.startswith("pidof"):
        return "4242\n"
    if cmd == "ps -A -o PID,NAME":
        return "  PID NAME\n" + "".join(f"{p} {n}\n" for n, p in PIDS.items()) + "  1 init\n"
    if cmd == "dumpsys meminfo":
        rows = {PKG + ":gpu_disable_art_image_": 200_000, PKG: 190_000, PKG + ":sandboxed_tab_disable_art_image_24": 180_000, "otbr-agent": 2_500}
        body = "".join(f"    {v:,}K: {n} (pid {PIDS[n]}{' / activities' if n == PKG else ''})\n" for n, v in rows.items())
        return "Total PSS by process:\n" + body + "\nTotal PSS by OOM adjustment:\n"
    if cmd.startswith("dumpsys meminfo "):
        return " App Summary\n                       Pss(KB)\n           Java Heap:     9032\n         Native Heap:    24792\n" \
               "            Graphics:    29916\n               TOTAL:   190000       TOTAL SWAP PSS:       30\n"
    if re.match(r"cat /proc/\d+/status", cmd):
        return "Name:\tx\nVmHWM:\t  300000 kB\n"
    if cmd == "cat /proc/meminfo":
        return "MemTotal:        2023196 kB\nMemFree:          285844 kB\nMemAvailable:     899720 kB\n"
    if cmd.startswith("cat /proc/stat"):
        j = int(now() * 100)
        out = f"cpu  {j} 0 {j} {j * 6} 0 0 0 0 0 0\n"
        for pid in re.findall(r"echo PID (\d+)", cmd):
            out += f"PID {pid}\n{pid} (Gecko Main) S 1 1 0 0 -1 1 2 3 4 5 {j // 10} {j // 20} 1 1 10 -10\n"
        return out
    if cmd == "nproc":
        return "4\n"
    if "thermal_zone" in cmd:
        if "case" in cmd:
            return "45000\n"
        return "cpu_thermal_zone=45000 gpu_thermal_zone=44000\n" if "=" in cmd else "cpu_thermal_zone 45000\ngpu_thermal_zone 44000\n"
    if cmd == "cat /proc/loadavg":
        return "0.50 0.60 0.70 1/500 1234\n"
    if cmd.startswith("wm size"):
        return "Physical size: 1440x720\n"
    if cmd.startswith("wm density"):
        return "Physical density: 240\n"
    if cmd.startswith("am force-stop"):
        save("stopped.json", True)
        save("session_up.json", False)
        if os.environ.get("FAKE_HOME_RELAUNCH"):
            save("cold.json", True)
        return ""
    return ""


def line(tag, msg, pid=4242):
    print(f"{now():10.3f}  {pid}  {pid + 1} I {tag}: {msg}", flush=True)


def page(nonce):
    def gw(obj):
        line("GeckoConsole", "GWPERF " + json.dumps(dict(obj, run=1, nonce=nonce)))

    gw({"t": "hello", "ua": "Mozilla/5.0 (Android 10; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0", "load": {"lcp_ms": 420}})
    gw({"t": "js", "runs": 30, "median_ms": 9.5, "p95_ms": 12.0, "checksum": 7, "expected": 7})
    for stage, n in (("css-transform", 1), ("css-transform", 16), ("scroll", 240)):
        gw({"t": "warmup", "stage": stage, "n": n})
        time.sleep(0.05)
        a = now()
        gw({"t": "measure", "stage": stage, "n": n})
        time.sleep(0.6)
        paint(a, now())
        gw({"t": "step", "stage": stage, "n": n, "window_ms": 600, "scrolled_px": 600 if stage == "scroll" else None,
            "raf": {"n": 36, "median_ms": 16.67, "p95_ms": 16.67, "jank_pct": 0, "frozen_pct": 0}})
        time.sleep(0.3)
    gw({"t": "done"})


def refdash(nonce, url):
    """A reference dashboard: it loads (first paint 150 ms, load event 120 ms), stays still, goes live
    (typical and busy keep painting), one measured window, the taps, done."""
    tier = re.search(r"tier=(\w+)", url).group(1)
    taps = int(re.search(r"taps=(\d+)", url).group(1))
    foreign = os.environ.get("FAKE_FOREIGN_PAGE")
    foreign = foreign if (foreign == "end") == (tier == "busy") else None

    def gw(obj):
        line("GeckoConsole", "GWPERF " + json.dumps(dict(obj, nonce=nonce, run=1)))

    if foreign == "before":
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace?external_auth=1")
    origin = time.time() * 1000
    a = now()
    paint(a + 0.15, a + 0.5)
    line("GeckoSession", f"handleMessage GeckoView:PageStart uri={url}")
    if foreign == "after":
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace?external_auth=1")
    time.sleep(0.7)
    gw({"t": "refdash", "tier": tier, "nodes": 1144, "cards": 44, "fcp_ms": 150, "lcp_ms": 150, "load_ms": 120,
        "origin_epoch_ms": origin, "epoch_ms": time.time() * 1000})
    time.sleep(1.0)
    live = now()
    if tier != "light":
        paint(live, live + 0.85)  # warm-up and window; the page is replaced before the next one loads
    gw({"t": "warmup", "stage": tier, "n": 1})
    time.sleep(0.3)
    expected = {"light": 0, "typical": 12, "busy": 24}[tier]
    gw({"t": "measure", "stage": tier, "n": 1, "moving": 6 + expected, "offscreen": 0,
        "animations": expected, "animations_expected": expected})
    time.sleep(0.5)
    gw({"t": "step", "stage": tier, "n": 1, "window_ms": 500,
        "raf": {"n": 30, "median_ms": 16.67, "p95_ms": 16.67, "jank_pct": 0, "frozen_pct": 0, "fps": 60}})
    if taps:
        before = state("taps.json", 0)
        gw({"t": "input-ready", "x": 720, "y": 360, "taps": taps})
        end = now() + 10
        while state("taps.json", 0) - before < taps and now() < end:
            time.sleep(0.1)
        gw({"t": "input", "stage": tier, "method": "event-timing", "taps": state("taps.json", 0) - before,
            "values": [16, 48], "inp_ms": 48})
    gw({"t": "done", "stage": tier})
    if foreign == "end":
        time.sleep(0.3)
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace?external_auth=1")


def dashboard():
    """A cold start as on the S1: Displayed, then 0.6 s later GeckoView's own about:blank (the session
    is up from here), then the kiosk's own dashboard."""
    line("ActivityManager", f"Start proc 5555:{PKG}/u0a83 for activity {{{PKG}/{PKG}.MainActivity}}", pid=1918)
    line("ActivityTaskManager", f"Displayed {PKG}/.MainActivity: +1s714ms", pid=1918)
    time.sleep(0.6)
    line("GeckoSession", "handleMessage GeckoView:PageStart uri=about:blank")
    line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")
    save("session_up.json", True)
    a = now()
    paint(a + 0.2, a + 1.2)  # the screen paints whether or not anyone reads logcat
    line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace?external_auth=1")
    time.sleep(0.8)
    line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")
    if os.environ.get("FAKE_OWN_RELOAD"):
        time.sleep(0.5)   # a sign-in redirect: the kiosk loads its page a second time
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace/0?external_auth=1")
        time.sleep(0.8)
        line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")


def logcat():
    print("    1.000  1  1 I old: line from before", flush=True)
    end = now() + 60
    while now() < end:
        if state("nonce.json"):
            nonce = state("nonce.json")[0]
            save("nonce.json", None)
            page(nonce)
        if state("refnonce.json"):
            nonce, url = state("refnonce.json")
            save("refnonce.json", None)
            refdash(nonce, url)
        if state("stopped.json"):
            save("stopped.json", None)
            line("ActivityManager", f"Force stopping {PKG} appid=10083 user=0: from pid 777", pid=1918)
        if state("cold.json"):
            save("cold.json", None)
            dashboard()
        time.sleep(0.05)


def main():
    args = sys.argv[1:]
    if args[:1] == ["-s"]:
        args = args[2:]
    with open(os.path.join(STATE, "commands.log"), "a") as f:
        f.write(" ".join(args) + "\n")
    if args[:1] == ["get-state"]:
        print("device")
    elif args[:1] == ["reverse"]:
        pass
    elif args[:1] == ["logcat"]:
        logcat()
    elif args[:1] == ["shell"]:
        sys.stdout.write(shell(" ".join(args[1:])))


if __name__ == "__main__":
    main()
