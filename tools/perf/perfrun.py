#!/usr/bin/env python3
"""Device side of the Glassworks perf test (MIT). Called by tools/perf/run.sh; see docs/perf.md.

What it touches on the device:
  device     read-only (getprop, /proc, dumpsys, wm size).
  synthetic  `am start` the app if it isn't running, then `am start --es open <page>` (one-off
             navigation; the saved dashboard URL is not changed), `--es screen on` during each step's warm-up, and `input tap` on the test
             page's tap target when --taps > 0.
  coldstart  `input keyevent KEYCODE_WAKEUP` (the app's own wake intent would start it), then
             `am force-stop` the app. A home-app kiosk is relaunched by the system; otherwise
             `am start -W` starts it. Either way it comes back on the saved dashboard.
  refstart   As coldstart; once the kiosk's own page has gone quiet, `am start --es open` each
             reference dashboard in turn (one-off; the saved URL is not changed), `--es screen on`
             at each warm-up, and `input tap` on its toggle when --taps > 0.
  idle       `--es screen on` before each window; otherwise read-only.
Everything it writes goes to --out on this machine. It records no URLs, hosts or tokens.
"""
import argparse
import json
import os
import queue
import re
import secrets
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import perflib as P  # noqa: E402


class Device:
    def __init__(self, serial, package, activity):
        self.adb = [os.environ.get("ADB", "adb")] + (["-s", serial] if serial else [])
        self.package = package
        self.component = f"{package}/{activity}"

    def sh(self, cmd, timeout=60):
        r = subprocess.run(self.adb + ["shell", cmd], capture_output=True, text=True, timeout=timeout)
        return r.stdout

    def wake(self):
        subprocess.Popen(self.adb + ["shell", f"am start -n {self.component} --es screen on"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def logcat(self):
        p = subprocess.Popen(self.adb + ["logcat", "-v", "monotonic", "-T", "1"],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="replace")
        p.stdout.readline()  # -T 1 starts with one old line
        return p

    def sf(self):
        """(period_ms, present times ns) of the app's SurfaceView, the layer GeckoView draws into.
        After a restart an old layer can still be listed; the live one has the newest frame."""
        names = [l.strip() for l in self.sh("dumpsys SurfaceFlinger --list").splitlines()
                 if l.startswith(f"SurfaceView - {self.package}/")]
        best = (None, [])
        for name in sorted(names, key=P.layer_number, reverse=True):
            period, presents = P.parse_sf_latency(self.sh(f"dumpsys SurfaceFlinger --latency '{name}'"))
            if period and max(presents, default=0) > max(best[1], default=0):
                best = (period, presents)
            elif period and best[0] is None:
                best = (period, presents)
        return best

    def pids(self, names):
        """name -> pid for the app's processes and any process named in `names`."""
        out = {}
        for line in self.sh("ps -A -o PID,NAME").splitlines()[1:]:
            parts = line.split(None, 1)
            if len(parts) == 2 and (parts[1].startswith(self.package) or parts[1] in names):
                out[parts[1].strip()] = int(parts[0])
        return out


def write_json(path, obj):
    with open(path, "w") as f:
        json.dump(obj, f, indent=1)


# ---- device

def cmd_device(dev, a):
    props = {k: dev.sh(f"getprop {k}").strip() for k in (
        "ro.product.manufacturer", "ro.product.model", "ro.build.version.release", "ro.build.version.sdk",
        "ro.product.cpu.abi", "ro.product.cpu.abilist", "ro.board.platform", "ro.hardware")}
    cpuinfo = dev.sh("cat /proc/cpuinfo")
    parts = sorted(set(re.findall(r"CPU part\s*:\s*(\S+)", cpuinfo)))
    freqs = dev.sh("cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq 2>/dev/null").split()
    pkg = dev.sh(f"dumpsys package {dev.package}")
    apk_path = dev.sh(f"pm path {dev.package}").strip().replace("package:", "").splitlines()
    apk_bytes = dev.sh(f"stat -c %s {apk_path[0]}").strip() if apk_path else ""
    period, _ = dev.sf()
    size = re.search(r"(\d+)x(\d+)", dev.sh("wm size"))
    zones = dev.sh("for z in /sys/class/thermal/thermal_zone*; do echo $(cat $z/type) $(cat $z/temp); done 2>/dev/null")
    write_json(os.path.join(a.out, "device.json"), {
        "frame": a.frame, "frame_name": a.name or a.frame,
        "model": props["ro.product.model"], "manufacturer": props["ro.product.manufacturer"],
        "android": props["ro.build.version.release"], "api": int(props["ro.build.version.sdk"] or 0),
        "abi": props["ro.product.cpu.abi"], "abilist": props["ro.product.cpu.abilist"].split(","),
        "soc": props["ro.board.platform"] or props["ro.hardware"],
        "cpu": {"cores": int(dev.sh("nproc").strip() or 0), "parts": parts,
                "max_mhz": sorted({int(f) // 1000 for f in freqs if f.isdigit()})},
        "mem_total_kb": P.parse_proc_meminfo(dev.sh("cat /proc/meminfo"))["mem_total_kb"],
        "display": {"px": [int(size.group(1)), int(size.group(2))] if size else None,
                    "density": (re.findall(r"\d+", dev.sh("wm density")) or [None])[-1],
                    "vsync_ms": round(period, 3) if period else None},
        "app": {"package": dev.package,
                "version_name": (re.findall(r"versionName=(\S+)", pkg) or [None])[0],
                "version_code": (re.findall(r"versionCode=(\d+)", pkg) or [None])[0],
                "apk_bytes": int(apk_bytes) if apk_bytes.isdigit() else None},
        "thermal_c": {l.split()[0]: int(l.split()[1]) / 1000 for l in zones.splitlines()
                      if len(l.split()) == 2 and l.split()[1].lstrip("-").isdigit()},
        "loadavg": dev.sh("cat /proc/loadavg").split()[:3],
    })


# ---- synthetic: the test page

def cmd_synthetic(dev, a):
    path = os.path.join(a.out, f"synthetic-{a.run}.jsonl")
    # The kiosk applies `open` only to a running activity (onNewIntent), so start it first.
    if not dev.sh(f"pidof {dev.package}").strip():
        dev.sh(f"am start -n {dev.component}")
        time.sleep(20)
    dev.wake()
    lc = dev.logcat()
    dev.sh(f"am start -n {dev.component} --es open '{a.url}'")
    deadline = time.time() + a.timeout
    measure_mono = None
    status = "timeout"
    seen = threading.Event()
    timer = threading.Timer(a.timeout, lc.terminate)
    timer.start()
    no_page = threading.Timer(90, lambda: None if seen.is_set() else lc.terminate())
    no_page.start()
    with open(path, "w") as f:
        for line in lc.stdout:
            rec = P.parse_logcat_line(line)
            pl = P.gwperf_payload(rec["msg"]) if rec else None
            if not pl or pl.get("nonce") != a.nonce:
                continue
            pl["mono"] = rec["mono"]
            seen.set()
            t = pl.get("t")
            if t == "warmup":
                dev.wake()
            elif t == "measure":
                measure_mono = rec["mono"]
            elif t == "step" and measure_mono is not None:
                period, presents = dev.sf()
                # oldest_ms: how far back SurfaceFlinger's history reached when it was read. If that is
                # before the window, every frame of the window is in it, and gaps are real.
                # The page's own window length: the step line itself lands a little after the window.
                end_mono = measure_mono + pl["window_ms"] / 1000 if pl.get("window_ms") else rec["mono"]
                pl["sf"] = {"period_ms": period, "window_ms": [measure_mono * 1000, end_mono * 1000],
                            "oldest_ms": min(presents) / 1e6 if presents else None,
                            "presents_ms": P.presents_in(presents, measure_mono, end_mono)}
            f.write(json.dumps(pl) + "\n")
            f.flush()
            print(f"  {t} {pl.get('stage', '')} {pl.get('n', '')}".rstrip(), file=sys.stderr)
            if t in ("done", "error"):
                status = t
                break
            if time.time() > deadline:
                break
    timer.cancel()
    no_page.cancel()
    lc.terminate()
    if not seen.is_set():
        status = "the test page sent nothing in 90 s (is the app in front, and adb reverse up?)"
    if status != "done":
        sys.exit(f"synthetic run {a.run}: {status}")


def tap(dev, x, y, n):
    for _ in range(n):
        dev.sh(f"input tap {x} {y}")
        time.sleep(0.6)


# ---- cold start to dashboard

PAGE_RE = re.compile(r"GeckoView:(PageStart|PageStop) uri=(\S+)")


def cmd_coldstart(dev, a):
    """Force-stop, then time the next launch. A kiosk that is the home app is relaunched by the
    system at once (the S1 does it in 0.2 s), so logcat is followed from before the stop, and
    `am start -W` is sent only if nothing relaunched the app within --settle seconds.

    Both record when the engine is ready: GeckoView's first PageStart (its own about:blank), which
    comes before the kiosk does anything for Home Assistant.
    coldstart (real use, not graded) then follows the kiosk's own dashboard to its PageStop.
    refstart (graded) waits until the kiosk's own first page has gone quiet (or --own-page-wait
    seconds if it never loads), then opens each reference dashboard (--url, --tiers) in turn and
    follows its lines. `open` can't be sent sooner: until the session is up the kiosk drops it
    (#103's pins). After the first open, any other page's PageStart is an error."""
    ref = a.cmd == "refstart"
    tiers = [t for t in a.tiers.split(",") if t] if ref else []
    lc = dev.logcat()
    lines = queue.Queue()
    threading.Thread(target=lambda: [lines.put(l) for l in lc.stdout], daemon=True).start()
    presents, period = set(), [None]
    stop, paused = threading.Event(), threading.Event()

    def poll():
        while not stop.is_set():
            if not paused.is_set():
                p, ts = dev.sf()
                if p:
                    period[0] = p
                    presents.update(ts)
            stop.wait(1.0)

    def quiet_for(secs, cap):
        """Wait until no new frame has been presented for `secs` (at most `cap` s). True if it went quiet."""
        end, last, since = time.time() + cap, len(presents), time.time()
        while time.time() < end and time.time() - since < secs:
            time.sleep(0.25)
            if len(presents) != last:
                last, since = len(presents), time.time()
        return time.time() - since >= secs

    poller = threading.Thread(target=poll, daemon=True)
    poller.start()
    res = {"run": a.run, "launch": None, "am_total_ms": None, "ttid_ms": None, "stop_mono": None, "proc_mono": None,
           "displayed_mono": None, "engine_mono": None, "own_page_start_mono": None, "own_page_stop_mono": None,
           "page_start_mono": None, "page_stop_mono": None, "tiers": {}, "error": None}
    dev.sh("input keyevent KEYCODE_WAKEUP")
    dev.sh(f"am force-stop {dev.package}")
    t0 = time.time()
    engine_at = None
    own_stop_at = 0.0
    current = None  # (tier, nonce) of the reference page being measured
    measure_mono = None
    proc_re = re.compile(rf"Start proc \d+:{re.escape(dev.package)}/")

    def open_next():
        nonlocal current, measure_mono
        measure_mono = None
        tier = tiers[len(res["tiers"])]
        nonce = secrets.token_hex(4)
        current = (tier, nonce)
        res["tiers"][tier] = {"nonce": nonce}
        paused.clear()
        url = f"{a.url}?tier={tier}&nonce={nonce}&run={a.run}&vsync={a.vsync}&taps={a.taps}"
        dev.sh(f"am start -n {dev.component} --es open '{url}'")

    break_after = None
    while time.time() - t0 < a.timeout:
        if break_after and time.time() > break_after and lines.empty():
            break
        # Read every queued line first, so a relaunch logged just before --settle isn't missed.
        if res["launch"] is None and time.time() - t0 > a.settle and lines.empty():
            res["launch"] = "am-start"
            am = dev.sh(f"am start -W -n {dev.component}", timeout=120)
            res["am_total_ms"] = int((re.findall(r"TotalTime: (\d+)", am) or [0])[0]) or None
        if ref and current is None and engine_at and lines.empty():
            # Open once the kiosk's own page has stopped for 2 s with no new load, and the screen is
            # still; or after --own-page-wait if it never finishes (Home Assistant down).
            own_done = res["own_page_stop_mono"] is not None and time.time() - own_stop_at >= 2.0
            if own_done or time.time() - engine_at > a.own_page_wait:
                quiet = quiet_for(2.0, 10.0) if own_done else False
                if own_done and not lines.empty():
                    continue  # something was logged meanwhile; read it first (a new load restarts the wait)
                res["own_page_quiet"] = quiet
                open_next()
        try:
            rec = P.parse_logcat_line(lines.get(timeout=0.2))
        except queue.Empty:
            continue
        if not rec:
            continue
        msg = rec["msg"]
        if res["stop_mono"] is None:
            if f"Force stopping {dev.package} " in msg:
                res["stop_mono"] = rec["mono"]
            continue
        if res["proc_mono"] is None and proc_re.search(msg):
            res["proc_mono"] = rec["mono"]
            res["launch"] = res["launch"] or "relaunched-by-system"
        if res["ttid_ms"] is None:
            res["ttid_ms"] = P.parse_displayed(msg, dev.component)
            if res["ttid_ms"] is not None:
                res["displayed_mono"] = rec["mono"]
        pl = P.gwperf_payload(msg)
        if current and pl and pl.get("nonce") == current[1]:
            tier, t = current[0], pl.get("t")
            entry = res["tiers"][tier]
            pl["mono"] = rec["mono"]
            if t == "refdash":
                entry["page"] = pl
            elif t == "warmup":
                entry["live_mono"] = rec["mono"]
                paused.set()  # no dumpsys while frames are measured and taps are timed
                dev.wake()
            elif t == "measure":
                measure_mono = rec["mono"]
                entry["measure"] = pl
            elif t == "step" and measure_mono is not None:
                p, ts = dev.sf()
                # The page's own window length: the step line itself lands a little later.
                end_mono = measure_mono + (pl.get("window_ms") or 0) / 1000 if pl.get("window_ms") else rec["mono"]
                pl["sf"] = {"period_ms": p, "window_ms": [measure_mono * 1000, end_mono * 1000],
                            "oldest_ms": min(ts) / 1e6 if ts else None,
                            "presents_ms": P.presents_in(ts, measure_mono, end_mono)}
                entry["step"] = pl
            elif t == "input-ready" and a.taps:
                threading.Thread(target=tap, args=(dev, pl["x"], pl["y"], pl.get("taps", a.taps)), daemon=True).start()
            elif t == "input":
                entry["input"] = pl
            elif t in ("done", "error"):
                if t == "error":
                    entry["error"] = pl.get("message")
                print(f"  {tier} {t}", file=sys.stderr)
                if len(res["tiers"]) == len(tiers):
                    # Keep watching briefly: another page now means one was on its way during the last tier.
                    break_after = time.time() + 1.5
                    continue
                open_next()
            continue
        m = PAGE_RE.search(msg)
        if not m or res["proc_mono"] is None:
            continue
        kind, uri = m.group(1), m.group(2)
        if kind == "PageStart" and res["engine_mono"] is None:
            res["engine_mono"] = rec["mono"]
            res["engine_uri"] = uri
            engine_at = time.time()
        if kind == "PageStart" and not uri.startswith("http"):
            continue
        if current is None:
            # Before the first open: the kiosk's own first page (its dashboard). A second one (a
            # reload, a sign-in redirect) restarts the wait: the reference pages must not meet it.
            if kind == "PageStart":
                res["own_page_loads"] = res.get("own_page_loads", 0) + 1
                res["own_page_start_mono"], res["own_page_stop_mono"] = rec["mono"], None
            elif kind == "PageStop" and res["own_page_start_mono"] is not None and res["own_page_stop_mono"] is None:
                res["own_page_stop_mono"] = rec["mono"]
                own_stop_at = time.time()
                if not ref:
                    break
        elif kind == "PageStart":
            if current[1] in uri:
                res["tiers"][current[0]].setdefault("page_start_mono", rec["mono"])
            else:
                res["error"] = "the kiosk loaded another page while a reference dashboard was measured"
                break
    lc.terminate()
    if res["error"]:
        pass
    elif res["engine_mono"] is None:
        res["error"] = f"GeckoView never started a page within {a.timeout:.0f} s"
    elif ref and len(res["tiers"]) < len(tiers) or ref and not all("step" in e for e in res["tiers"].values()):
        res["error"] = f"the reference dashboards did not finish within {a.timeout:.0f} s"
    elif not ref and res["own_page_stop_mono"] is None:
        res["error"] = f"the dashboard did not finish loading within {a.timeout:.0f} s"
    elif not ref:
        # Keep sampling until nothing new has been painted for 2 s (or 20 s pass).
        res["ended_quiet"] = quiet_for(2.0, 20.0)
    if not ref:
        res["page_start_mono"], res["page_stop_mono"] = res["own_page_start_mono"], res["own_page_stop_mono"]
    stop.set()
    poller.join()
    res["sf_period_ms"] = period[0]
    res["presents_mono_ms"] = sorted(t / 1e6 for t in presents)
    write_json(os.path.join(a.out, f"{a.cmd}-{a.run}.json"), res)
    if res["error"]:
        sys.exit(f"{a.cmd} {a.run}: {res['error']}")


# ---- idle: CPU, frames and memory on the dashboard

def cpu_snapshot(dev, pids):
    out = dev.sh("cat /proc/stat; " + "; ".join(f"echo PID {p}; cat /proc/{p}/stat" for p in pids.values()))
    total = P.parse_proc_stat_total(out)
    procs = {}
    for name, pid in pids.items():
        m = re.search(rf"PID {pid}\n(.*)", out)
        if m and ")" in m.group(1):
            procs[name] = P.parse_pid_jiffies(m.group(1))
    return {"total": total, "procs": procs}


def cmd_idle(dev, a):
    radio = [x for x in a.radio_procs.split(",") if x]
    end = time.time() + a.settle
    while time.time() < end:
        dev.wake()
        time.sleep(min(10, max(0, end - time.time())))
    dev.wake()
    time.sleep(3)
    pids = dev.pids(radio)
    ncpu = int(dev.sh("nproc").strip() or 1)
    before = cpu_snapshot(dev, pids)
    # Frames from before the window are still in SurfaceFlinger's history; keep only newer ones.
    period, history = dev.sf()
    newest_before = max(history, default=0)
    presents = set()
    t_end = time.time() + a.window
    while time.time() < t_end:
        time.sleep(1.5)
        p, ts = dev.sf()
        period = p or period
        presents.update(t for t in ts if t > newest_before)
    after = cpu_snapshot(dev, pids)
    dev.wake()
    by_proc = P.parse_meminfo_by_process(dev.sh("dumpsys meminfo", timeout=120))
    procs = {}
    for name, pid, pss in by_proc:
        role = P.process_role(name, dev.package)
        if role or name in radio:
            hwm = re.search(r"VmHWM:\s+(\d+)", dev.sh(f"cat /proc/{pid}/status"))
            entry = {"pid": pid, "role": role or "radio", "pss_kb": pss, "rss_peak_kb": int(hwm.group(1)) if hwm else None}
            if role:
                entry["summary"] = P.parse_meminfo_app(dev.sh(f"dumpsys meminfo {pid}", timeout=60))
            procs[name] = entry
    write_json(os.path.join(a.out, f"idle-{a.label}-{a.run}.json"), {
        "label": a.label, "run": a.run, "window_s": a.window, "ncpu": ncpu,
        "cpu": P.cpu_percent(before, after, ncpu),
        "frames": {"period_ms": period, "presents_mono_ms": sorted(t / 1e6 for t in presents)},
        "processes": procs,
        "device_mem": P.parse_proc_meminfo(dev.sh("cat /proc/meminfo")),
    })


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["device", "synthetic", "refstart", "coldstart", "idle"])
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--package", default="sh.edm.s1kiosk")
    ap.add_argument("--activity", default=".MainActivity")
    ap.add_argument("--frame", default="unknown")
    ap.add_argument("--name", default=None, help="device: the frame's display name for the report")
    ap.add_argument("--out", required=True)
    ap.add_argument("--run", type=int, default=0)
    ap.add_argument("--url")
    ap.add_argument("--nonce")
    ap.add_argument("--taps", type=int, default=0)
    ap.add_argument("--label", default="all-on")
    ap.add_argument("--settle", type=float, default=30)
    ap.add_argument("--window", type=float, default=15)
    ap.add_argument("--timeout", type=float, default=None, help="seconds; default 1200 (synthetic), 120 (coldstart)")
    ap.add_argument("--radio-procs", default="otbr-agent,cpcd,zigbeed")
    ap.add_argument("--own-page-wait", type=float, default=30, help="refstart: seconds to wait for the kiosk's own page")
    ap.add_argument("--tiers", default="light,typical,busy", help="refstart: reference dashboards, in order")
    ap.add_argument("--vsync", default="16.667", help="refstart: the display's refresh period in ms")
    a = ap.parse_args()
    if a.timeout is None:
        a.timeout = {"coldstart": 120, "refstart": 600}.get(a.cmd, 1200)
    os.makedirs(a.out, exist_ok=True)
    dev = Device(a.serial, a.package, a.activity)
    {"device": cmd_device, "synthetic": cmd_synthetic, "refstart": cmd_coldstart, "coldstart": cmd_coldstart,
     "idle": cmd_idle}[a.cmd](dev, a)


if __name__ == "__main__":
    main()
