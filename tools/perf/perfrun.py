#!/usr/bin/env python3
"""Device side of Clarity, the Glassworks perf test (MIT). Called by tools/perf/run.sh; see docs/perf.md.

What it touches on the device:
  device     read-only (getprop, /proc, dumpsys, wm size).
  synthetic  `am start` the app if it isn't running, then `am start --es open <page>` (one-off
             navigation; the saved dashboard URL is not changed), `--es screen on` during each step's warm-up, and `input tap` on the test
             page's tap target when --taps > 0.
  coldstart  `input keyevent KEYCODE_WAKEUP` (the app's own wake intent would start it), then
             `am force-stop` the app. A home-app kiosk is relaunched by the system; otherwise
             `am start -W` starts it. Either way it comes back on the saved dashboard.
  refstart   As coldstart; once the kiosk's own page has finished, `am start --es open` each
             reference dashboard in turn (one-off; the saved URL is not changed), `--es screen on`
             at each warm-up, `input tap` on its toggle when --taps > 0, and `input swipe` where the
             page asks (only on our page, only for the run's nonce).
  idle       `--es screen on` before each window; otherwise read-only.
Everything it writes goes to --out on this machine. It records no URLs, hosts or tokens.
"""
import argparse
import json
import os
import queue
import urllib.parse
import urllib.request
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

    def nudge(self):
        """Android's own wake key, for while the kiosk is stopped or starting: the kiosk's intent
        (wake) would launch it, and change the restart being measured."""
        subprocess.Popen(self.adb + ["shell", "input keyevent KEYCODE_WAKEUP"],
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
        "measured_on": time.strftime("%Y-%m-%d", time.gmtime()),   # UTC date of the run, from this computer
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
        lines_in = P.PageLines()
        for line in lc.stdout:
            rec = P.parse_logcat_line(line)
            if rec and seen.is_set() and SLEEP_RE.search(rec["msg"]):
                # The screen went off while the test page ran: its frames aren't the panel's then.
                f.write(json.dumps({"t": "screen_off", "mono": rec["mono"]}) + "\n")
                f.flush()
            pl = lines_in.feed(rec["msg"]) if rec else None
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


def parse_set(spec):
    """"voice_enabled=false,mic_muted=true,..." -> {extra: bool}."""
    out = {}
    for part in (spec or "").split(","):
        if part:
            k, _, v = part.partition("=")
            if not re.fullmatch(r"[a-z_]{1,40}", k) or v not in ("true", "false"):
                raise SystemExit(f"--set {part!r}: give extra=true|false")
            out[k] = v == "true"
    return out


def expected_state(want, voice_now):
    """What pane_state should read for `want`. The microphone is open only with voice on and not
    muted; when the kiosk didn't apply voice_enabled (S1 Kiosk 0.4.3 saves it asynchronously, then
    exits to restart, so the write is lost), that is judged with the voice it really runs. Another
    pane's switch `<id>_enabled` shows as `pane:<id>` (#67: wall_base_enabled, pane:wall-base)."""
    voice = want.get("voice_enabled", voice_now)
    out = {}
    if "sendspin_enabled" in want:
        out["sendspin"] = want["sendspin_enabled"]
    for k, v in want.items():
        if k.endswith("_enabled") and k not in ("voice_enabled", "sendspin_enabled"):
            out["pane:" + k[:-len("_enabled")].replace("_", "-")] = v
    if "mic_muted" in want or "voice_enabled" in want:
        out["mic"] = bool(voice_now if voice_now is not None else voice) and not want.get("mic_muted", False)
    return out


def cmd_panes(dev, a):
    """Set the panes the kiosk runs, then check from its log that it runs them (#158's s1v7: every
    switch was lost). The settings applied live (the other panes' switches, the microphone mute, the
    dashboard URL) go first, in an intent that doesn't restart the app, and are saved while the runner
    waits; then voice_enabled and a restart, so each sample starts from a fresh process (a kiosk
    before #67 restarts for voice_enabled alone). Up to --tries times. Prints the result as JSON; exit
    0 when everything but voice_enabled took (voice_applied says whether that did too), else 1."""
    want = parse_set(a.set)
    live = " ".join(f"--ez {k} {'true' if v else 'false'}" for k, v in want.items() if k != "voice_enabled")
    url = f"--es url '{a.url}'" if a.url else ""
    res = {"want": want, "attempts": 0, "state": None, "ok": False, "voice_applied": None,
           "url_taken": None if not a.url else False}
    for attempt in range(1, a.tries + 1):
        res["attempts"] = attempt
        if live or url:
            out = dev.sh(f"am start -W -n {dev.component} {url} {live}".strip(), timeout=60)
            if a.url:
                # am exits 0 even when the kiosk didn't take the intent: read its answer (as run.sh does).
                res["url_taken"] = any(l.strip() == "Status: ok" for l in out.splitlines())
            time.sleep(PANES_SAVE_S)
        if "voice_enabled" in want:
            dev.sh(f"am start -W -n {dev.component} --ez voice_enabled {'true' if want['voice_enabled'] else 'false'} --ez restart true", timeout=60)
        else:
            dev.sh(f"am start -W -n {dev.component} --ez restart true", timeout=60)
        time.sleep(a.settle)
        st = P.pane_state(dev.sh("logcat -d -v monotonic", timeout=60))
        res["state"] = st
        exp = expected_state(want, st["voice"])
        res["ok"] = st["voice"] is not None and all(st.get(k) == v for k, v in exp.items()) and res["url_taken"] is not False
        res["voice_applied"] = None if "voice_enabled" not in want else st["voice"] == want["voice_enabled"]
        if res["ok"] and res["voice_applied"] is not False:
            break
        if res["ok"] and attempt >= 2:
            break                     # voice_enabled doesn't take on this kiosk; the rest did
    print(json.dumps(res))
    sys.exit(0 if res["ok"] else 1)


# Decoders, from logcat (refstart's note_media). MediaCodec and CCodec are Android's own; omx_vdec_aw
# and omx_vdec are the Allwinner decoder's (the S1), read 2026-11-13 from the panel's logcat.
MEDIA_TAGS = {"MediaCodec", "CCodec", "omx_vdec_aw", "omx_vdec"}
MEDIA_ALLOC_RE = re.compile(r"^\[([\w.]+)\] setting surface generation|^allocate\(([\w.]+)\)")

# The live settings are saved with apply(): give the write this long before the app restarts.
PANES_SAVE_S = float(os.environ.get("GW_PERF_PANES_SAVE_S", "3"))

GESTURE_MS = (50, 2000)   # a swipe's duration the page may ask for
MAX_TAPS = 50
OWN_PAGE_QUIET_S = 2.0    # the kiosk's own page must have stopped this long, with no new load


def screen_size(dev):
    """(width, height) in pixels from `wm size`; an override size, listed last, wins."""
    m = re.findall(r"(\d+)x(\d+)", dev.sh("wm size"))
    return (int(m[-1][0]), int(m[-1][1])) if m else None


def _whole(v, lo, hi):
    return type(v) is int and lo <= v <= hi


def gesture(pl, size, max_taps=MAX_TAPS):
    """A page's tap or swipe request, checked before anything reaches `adb shell`: whole pixels on
    the screen, a sane count and duration. Returns (command, count) or (None, why)."""
    if not size:
        return None, "the screen size is unknown"
    w, h = size
    if pl.get("t") == "input-ready":
        x, y, n = pl.get("x"), pl.get("y"), pl.get("taps")
        if not (_whole(x, 0, w - 1) and _whole(y, 0, h - 1)):
            return None, f"a tap at {x!r}, {y!r} is not a whole pixel on the {w}x{h} screen"
        if not _whole(n, 1, max_taps):
            return None, f"a tap count of {n!r} is not 1 to {max_taps}"
        return f"input tap {x} {y}", n
    x, y1, y2, ms = pl.get("x"), pl.get("y1"), pl.get("y2"), pl.get("ms")
    if not (_whole(x, 0, w - 1) and _whole(y1, 0, h - 1) and _whole(y2, 0, h - 1)):
        return None, f"a swipe at {x!r}, {y1!r} to {y2!r} is not whole pixels on the {w}x{h} screen"
    if not _whole(ms, *GESTURE_MS):
        return None, f"a swipe of {ms!r} ms is not {GESTURE_MS[0]} to {GESTURE_MS[1]} ms"
    return f"input swipe {x} {y1} {x} {y2} {ms}", 1


def tap_cap(taps):
    """The most taps a page may ask for: what --taps asked, never more than MAX_TAPS."""
    return min(taps or MAX_TAPS, MAX_TAPS)


def own_page_finished(stop_mono, stop_at, now):
    """The kiosk's own page counts as finished once it has stopped and OWN_PAGE_QUIET_S have passed
    (wall clock) with no new load. A new load resets stop_mono to None."""
    return stop_mono is not None and now - stop_at >= OWN_PAGE_QUIET_S


# ---- cold start to dashboard

PAGE_RE = re.compile(r"GeckoView:(PageStart|PageStop) uri=(\S+)")
# Android's PowerManagerService, on any device: the screen is going off. With the screen off Gecko
# runs no animation frames and slows its timers, so nothing timed then is the panel's (smoke10: the
# kiosk's 20 s night idle timeout held light's view switch back 22 s and stopped heavy).
SLEEP_RE = re.compile(r"PowerManagerService.*Going to sleep|Going to sleep due to")
# While a view opens, and between views, the runner sends the kiosk's own wake intent this often, so
# its idle timeout (20 s at night on the S1) never turns the screen off. Not between a view's warm-up
# and done: its taps and swipes are touches, which reset the timeout, and nothing extra lands there.
KEEP_AWAKE_S = float(os.environ.get("GW_PERF_KEEP_AWAKE_S", "8"))     # the off-device test shortens it
# A live view that hasn't been touched (no tap, swipe or wake) for this long gets the wake intent too:
# normal views tap or swipe every few seconds, but a page stuck on a busy panel can outlast the
# kiosk's idle timeout, and with the screen off nothing it measures is the panel's (#202, smoke 7:
# heavy went 120 s without a gesture, and the screen went off).
# 10 s, under the kiosk's 20 s night timeout with room for a slow `adb shell input` (2.4 to 4.7 s a
# command on a busy S1): s1rtc5 run 1 (23:50) lost heavy's screen 16 to 20 s after its last tap, while
# the page waited out its camera window before the swipes, and the wake came 14 s later (it was 30 s).
LIVE_IDLE_WAKE_S = float(os.environ.get("GW_PERF_LIVE_IDLE_WAKE_S", "10"))
# From the force-stop until the restart page is done (and through a recovery's restart), the wake key
# this often, and once at the stop and once at the relaunch: s1rtc7 run 2 lost the screen 20.4 s after
# the stop, before the kiosk's first frame, which then waited 280 s for the screen to come back.
RESTART_WAKE_S = float(os.environ.get("GW_PERF_RESTART_WAKE_S", "8"))
# The CPU clock each view ran at (detail, #244: the S1's power HAL held 1.2 of its 1.608 GHz): every
# core's scaling_cur_freq this often while a view is open, and each core's cpuinfo_max_freq once.
# Read-only `cat`s of sysfs over adb; one every 5 s is a fraction of a percent of a core.
CPU_CLOCK_S = float(os.environ.get("GW_PERF_CPU_CLOCK_S", "5"))
CPU_FREQ = "/sys/devices/system/cpu/cpu[0-9]*/cpufreq/"


def cpu_khz(dev, name):
    """Each core's cpufreq `name` (scaling_cur_freq, cpuinfo_max_freq) in kHz, in core order; [] if
    the device has none."""
    try:
        out = dev.sh(f"cat {CPU_FREQ}{name} 2>/dev/null", timeout=15)
    except (subprocess.SubprocessError, OSError):
        return []
    return [int(x) for x in out.split() if x.isdigit()]
# A view not done this long after it opened is recorded as timed out: what it measured stands, and
# what it didn't fails (#202, smoke 7: heavy never finished, and the whole run was dropped after 900 s).
# The runner then restarts the kiosk, as at the run's start, and opens the run's remaining views: a
# view that can't finish fails its own tasks and nothing else (#202, s1rtc3).
VIEW_DEADLINE_S = float(os.environ.get("GW_PERF_VIEW_DEADLINE_S", "300"))
# Protocol 8 (#202, s1rtc4): that deadline covers only a view that never became usable. After that the
# page gives each task its own budget, and a view is timed out only when it says nothing for
# STAGE_IDLE_S (stuck in one task); s1rtc4's heavy was usable after 43 s and still busy at 300 s,
# before its swipes, which never ran.
STAGE_IDLE_S = float(os.environ.get("GW_PERF_STAGE_IDLE_S", "180"))
# After that restart: if nothing relaunched the kiosk within this, `am start` it; if its reference
# dashboard isn't usable within RECOVER_WAIT_S, the run fails.
RECOVER_LAUNCH_S = float(os.environ.get("GW_PERF_RECOVER_LAUNCH_S", "10"))
RECOVER_WAIT_S = float(os.environ.get("GW_PERF_RECOVER_WAIT_S", "180"))


def watch_threads():
    """Every exception a thread of this process raises, from now on: [str], printed as usual too.
    cmd_coldstart fails the run on any (s1rtc5's runs went on with a dead helper thread)."""
    errors = []

    def hook(args):
        name = args.thread.name if args.thread else "a thread"
        errors.append(f"{name}: {args.exc_type.__name__}: {args.exc_value}")
        threading.__excepthook__(args)
    threading.excepthook = hook
    return errors


def cmd_coldstart(dev, a):
    """Force-stop, then time the next launch. A kiosk that is the home app is relaunched by the
    system at once (the S1 does it in 0.2 s), so logcat is followed from before the stop, and
    `am start -W` is sent only if nothing relaunched the app within --settle seconds.

    Both record when the engine is ready: GeckoView's first PageStart (its own about:blank), which
    comes before the kiosk does anything for Home Assistant.
    coldstart (real use, not graded) then follows the kiosk's own dashboard to its PageStop.
    refstart (graded, protocol 4) waits until the kiosk is back on the reference dashboard's start
    view (run.sh pointed it there) and the harness says it is usable: the restart's end. Then it opens
    each reference view (--tiers; --warmup-tier adds one, not graded) through the fake Home
    Assistant (--url): the harness, in the frontend that is already loaded, navigates to it as HA's
    view tabs do. It follows each view's lines: taps and swipes where the view asks. The order turns
    by one view each run (tiers.json ORDER: run 1 light, typical-nocam, typical, heavy; run 2 from
    typical-nocam; run 3 from typical), so no view always comes first. Each opens only once the screen has been still
    for --between-quiet seconds (the view before stops its updates when it is done). After the first
    open, any page load is an error: a view switch loads no page. Without a restart page (the kiosk
    came back to its own dashboard), it waits for that page as protocols 1 to 3 did.

    logcat follows the whole device, so its queue is rarely empty: no decision here waits for an
    empty queue. Before deciding, the loop reads everything queued so far (drain)."""
    ref = a.cmd == "refstart"
    base = [t for t in a.tiers.split(",") if t] if ref else []
    if ref and a.warmup_tier and a.warmup_tier in base:
        # The frontend keeps a view it has shown (hui-root's _viewCache): the graded open of that view
        # would skip building it.
        sys.exit(f"--warmup-tier {a.warmup_tier} is also graded; a view shown twice is cached the second time")
    turn = (a.run - 1) % len(base) if base else 0
    tiers = base[turn:] + base[:turn]
    # The HLS fallback views (#202; detail, not graded) come after the graded ones, in a fixed order.
    fallback = [t for t in a.fallback.split(",") if t] if ref else []
    slots = (["warmup"] if ref and a.warmup_tier else []) + tiers + fallback
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

    def keep_awake():
        """The kiosk's wake intent, every KEEP_AWAKE_S, from the restart page's end until the last view
        is done; while a view is live (between its warm-up and its done), only once nothing has touched
        the panel for LIVE_IDLE_WAKE_S. Checked every second (or more often), so neither waits on the
        other's period."""
        last = last_nudge = 0.0
        relaunch_seen = False
        while not stop.wait(min(1.0, KEEP_AWAKE_S, LIVE_IDLE_WAKE_S / 2, RESTART_WAKE_S / 2)):
            if not st.get("restart_done_at") or st.get("recovering"):
                # The restart (or a recovery's): the wake key, at once when the app comes back up.
                relaunched = res["proc_mono"] is not None and not relaunch_seen
                relaunch_seen = relaunch_seen or relaunched
                if relaunched or time.time() - last_nudge >= RESTART_WAKE_S:
                    last_nudge = time.time()
                    dev.nudge()
                    res["restart_wakes"] = res.get("restart_wakes", 0) + 1
                continue
            if st.get("restart_done_at") and not st.get("live") and not st.get("break_after"):
                if time.time() - last < KEEP_AWAKE_S:
                    continue
                last = time.time()
                dev.wake()
                res["wakes"] = res.get("wakes", 0) + 1
            elif st.get("live") and time.time() - max(st.get("touched_at", 0), st.get("live_at", 0)) > LIVE_IDLE_WAKE_S:
                dev.wake()
                st["touched_at"] = time.time()
                res.setdefault("live_wakes", []).append([st["current"][0] if st["current"] else None, round(time.time(), 1)])

    def cpu_clock():
        """[view, time, [kHz per core]] every CPU_CLOCK_S while a view is open (cpu_khz in the run's file)."""
        res["cpu_max_khz"] = cpu_khz(dev, "cpuinfo_max_freq")
        while not stop.wait(CPU_CLOCK_S):
            cur = st.get("current")
            if cur and not st.get("recovering"):
                khz = cpu_khz(dev, "scaling_cur_freq")
                if khz:
                    res.setdefault("cpu_khz", []).append([cur[0], round(time.time(), 1), khz])

    def quiet_for(secs, cap):
        """Wait until no new frame has been presented for `secs` (at most `cap` s). True if it went quiet."""
        end, last, since = time.time() + cap, len(presents), time.time()
        while time.time() < end and time.time() - since < secs:
            time.sleep(0.25)
            if len(presents) != last:
                last, since = len(presents), time.time()
        return time.time() - since >= secs

    thread_errors = watch_threads()
    poller = threading.Thread(target=poll, daemon=True)
    poller.start()
    if ref:
        threading.Thread(target=keep_awake, daemon=True).start()
        threading.Thread(target=cpu_clock, daemon=True).start()
    res = {"run": a.run, "launch": None, "am_total_ms": None, "ttid_ms": None, "stop_mono": None, "proc_mono": None,
           "displayed_mono": None, "engine_mono": None, "engine_uri": None, "own_page_start_mono": None,
           "own_page_stop_mono": None, "own_page_loads": 0, "own_page_state": None,
           "page_start_mono": None, "page_stop_mono": None, "tiers": {}, "order": tiers, "warmup": None,
           "between": [], "bad_page_lines": 0, "protocol_restart": ref, "memory": [], "error": None,
           "fallback": {}}
    page_lines = P.PageLines()
    st = {"engine_at": None, "own_stop_at": 0.0, "current": None, "measure_mono": None, "break_after": None,
          "opened": 0, "next_at": None, "gestures": []}
    size = screen_size(dev) if ref else None
    proc_re = re.compile(rf"Start proc \d+:{re.escape(dev.package)}/")

    def entry_of(slot):
        if slot == "warmup":
            return res["warmup"]
        return res["fallback"][slot] if slot in fallback else res["tiers"][slot]

    def note_media(rec, cur):
        """Which decoders each view got, from Android's own log lines (any device): the codec name
        when MediaCodec puts one on a surface (video) or Codec2 allocates one. On the S1 also the
        Allwinner decoder's flushes and end-of-stream drains (Gecko drains a decoder that ran out of
        data). Names and counts only; read-only."""
        tag, msg = rec["tag"], rec["msg"]
        if tag not in MEDIA_TAGS:
            return
        m = MEDIA_ALLOC_RE.search(msg) if tag in ("MediaCodec", "CCodec") else None
        flush = tag == "omx_vdec_aw" and "decoder flush" in msg
        eos = tag == "omx_vdec" and "found eos flag" in msg
        if not (m or flush or eos):
            return
        where = cur[0] if cur else "between"
        rec_ = res.setdefault("media", {}).setdefault(where, {"codecs": [], "flushes": 0, "eos": 0})
        if m:
            rec_["codecs"].append(m.group(1) or m.group(2))
        rec_["flushes"] += flush
        rec_["eos"] += eos

    def send(nonce, cmd, n, gap):
        """Taps or a swipe for the page that asked, and only while it is still the current page."""
        for i in range(n):
            cur = st["current"]
            if not cur or cur[1] != nonce:
                return
            t0 = time.time()
            st["touched_at"] = t0
            dev.sh(cmd)
            st["touched_at"] = time.time()     # it reached the panel when `input` returned (seconds later)
            # When each gesture went over adb and how long `input` took on the panel, on this
            # computer's clock (fakeha.jsonl's): #202, smoke 5's heavy stalled during the taps.
            res.setdefault("gestures_sent", []).append([cur[0], round(t0, 3), cmd.split()[1],
                                                         round(time.time() - t0, 3)])
            if i + 1 < n:
                time.sleep(gap)

    def open_next():
        # No gesture for the last page may reach the next one: wait for them (the page has said done).
        for th in st["gestures"]:
            th.join(timeout=15)
        st["gestures"] = []
        slot = slots[st["opened"]]
        st["opened"] += 1
        st["opened_at"] = time.time()
        nonce = secrets.token_hex(4)
        st["current"], st["measure_mono"], st["next_at"] = (slot, nonce), None, None
        st["line_at"], st["last_line"] = None, None
        paused.clear()
        if slot == "warmup":
            tier, extra = a.warmup_tier, {"taps": 0, "swipes": 0, "warmup": 1}
            res["warmup"] = {"tier": tier, "nonce": nonce, "swipes": []}
        elif slot in fallback:
            # As a graded view, taps included, so the camera window is the same; no swipes.
            tier, extra = slot, {"taps": a.taps, "swipes": 0}
            res["fallback"][slot] = {"nonce": nonce, "swipes": []}
        else:
            tier, extra = slot, {"taps": a.taps}
            res["tiers"][tier] = {"nonce": nonce, "swipes": []}
        # Protocol 4: the frontend is already loaded on the start view. The fake Home Assistant hands
        # this command to the harness, which opens the view with HA's own navigation (no page load).
        # The idle timeout starts again now: the view has its full 20 s (and KEEP_AWAKE_S after that).
        dev.wake()
        q = urllib.parse.urlencode(dict({"view": tier, "nonce": nonce, "run": a.run, "vsync": a.vsync}, **extra))
        try:
            with urllib.request.urlopen(f"{a.url.rstrip('/')}/clarity/open?{q}", timeout=10) as r:
                r.read()
        except OSError as e:
            res["error"] = f"the fake Home Assistant didn't take the command to open {tier}: {e}"

    def still(nonce):
        try:
            with urllib.request.urlopen(f"{a.url.rstrip('/')}/clarity/still?nonce={nonce}", timeout=10) as r:
                r.read()
        except OSError:
            pass

    def read_sf(pl, rec):
        """SurfaceFlinger's history just after a window, cut to the window the page logged."""
        p, ts = dev.sf()
        m0 = st["measure_mono"]
        end_mono = m0 + pl["window_ms"] / 1000 if pl.get("window_ms") else rec["mono"]
        pl["sf"] = {"period_ms": p, "window_ms": [m0 * 1000, end_mono * 1000],
                    "oldest_ms": min(ts) / 1e6 if ts else None, "presents_ms": P.presents_in(ts, m0, end_mono)}

    def handle(rec):
        """One logcat line. Returns True when the run is over (done or failed)."""
        msg = rec["msg"]
        if res["stop_mono"] is None:
            if f"Force stopping {dev.package} " in msg:
                res["stop_mono"] = rec["mono"]
            return False
        if res["proc_mono"] is None and proc_re.search(msg):
            res["proc_mono"] = rec["mono"]
            res["launch"] = res["launch"] or "relaunched-by-system"
        if res["ttid_ms"] is None:
            res["ttid_ms"] = P.parse_displayed(msg, dev.component)
            if res["ttid_ms"] is not None:
                res["displayed_mono"] = rec["mono"]
        cur = st["current"]
        if ref:
            note_media(rec, cur)
        if ref and SLEEP_RE.search(msg) and res["proc_mono"] is not None:
            res.setdefault("screen_off", []).append({"mono": rec["mono"], "during": cur[0] if cur else
                                                     "recovery" if st.get("recovering") else "the restart"})
        if st.get("recovering"):
            # The kiosk coming back after a view timed out: only its restart page's lines count, and
            # they end the recovery; the run's own restart measurement stays the first one's.
            if proc_re.search(msg):
                st["recovering"]["launched"] = True
            pl = page_lines.feed(msg)
            if pl and pl.get("nonce") == "" and pl.get("t") in ("done", "error"):
                rv = st.pop("recovering")
                res["recoveries"][-1].update(back_s=round(time.time() - rv["at"], 1), launch=rv.get("launch") or "relaunched-by-system",
                                             **({"error": pl.get("message")} if pl.get("t") == "error" else {}))
                st["no_memory"] = True        # RAM right after a restart isn't the views' peak
                st["next_at"] = time.time()
                print(f"  the kiosk is back after {res['recoveries'][-1]['back_s']} s; carrying on", file=sys.stderr)
            return False
        pl = page_lines.feed(msg)
        res["bad_page_lines"] = page_lines.bad
        if ref and cur is None and pl and pl.get("nonce") == "":
            return handle_restart(pl, rec)
        if cur and pl and pl.get("nonce") == cur[1]:
            return handle_page(cur[0], pl, rec)
        m = PAGE_RE.search(msg)
        if not m or res["proc_mono"] is None:
            return False
        kind, uri = m.group(1), m.group(2)
        if kind == "PageStart" and res["engine_mono"] is None:
            res["engine_mono"], res["engine_uri"] = rec["mono"], uri
            st["engine_at"] = time.time()
        if kind == "PageStart" and not uri.startswith("http"):
            return False
        if cur is None:
            # Before the first open: the kiosk's own page (its dashboard). A second load (a reload, a
            # sign-in redirect) restarts the wait: the reference pages must not meet it.
            if kind == "PageStart":
                res["own_page_loads"] += 1
                res["own_page_start_mono"], res["own_page_stop_mono"] = rec["mono"], None
                res["own_page_uri"] = uri
            elif kind == "PageStop" and res["own_page_start_mono"] is not None and res["own_page_stop_mono"] is None:
                res["own_page_stop_mono"] = rec["mono"]
                st["own_stop_at"] = time.time()
                return not ref
        elif kind == "PageStart":
            if cur[1] in uri:
                entry_of(cur[0]).setdefault("page_start_mono", rec["mono"])
            else:
                res["error"] = "the kiosk loaded another page while a reference dashboard was measured"
                return True
        return False

    def handle_restart(pl, rec):
        """The kiosk's own page after the restart: the reference dashboard it was pointed at (run.sh
        --restore-dashboard), with no nonce. Its `refdash` holds the restart's usable time; its `done`
        lets the graded pages open."""
        t = pl.get("t")
        pl["mono"] = rec["mono"]
        if t == "refdash":
            res["restart_page"] = pl
            st["restart_usable_at"] = time.time()
        elif t == "done":
            st["restart_done_at"] = time.time()
        elif t == "error":
            res["restart_page_error"] = pl.get("message")
            st["restart_done_at"] = time.time()
        return False

    def handle_page(tier, pl, rec):
        entry, t = entry_of(tier), pl.get("t")
        pl["mono"] = rec["mono"]
        st["line_at"], st["last_line"] = time.time(), t
        if t == "refdash":
            entry["page"] = pl
        elif t == "warmup":
            entry["live_mono"] = rec["mono"]
            paused.set()  # no dumpsys while frames are measured, taps are timed or the page scrolls
            dev.wake()
            st["live"] = True
            st["live_at"] = time.time()
        elif t == "measure":
            st["measure_mono"] = rec["mono"]
            if pl.get("stage") == "updates":
                entry["measure"] = pl
        elif t == "step" and st["measure_mono"] is not None:
            read_sf(pl, rec)
            if pl.get("stage") == "scroll":
                entry["swipes"].append(pl)
            else:
                entry["step"] = pl
            st["measure_mono"] = None
        elif t == "step":
            if pl.get("stage") == "scroll":
                entry["swipes"].append(pl)   # it never scrolled, so no window to read
        elif t in ("input-ready", "swipe-ready") and (t == "swipe-ready" or a.taps):
            cmd, n = gesture(pl, size, max_taps=tap_cap(a.taps))
            if cmd and t == "swipe-ready":
                entry["swipes_asked"] = entry.get("swipes_asked", 0) + 1
            if cmd is None:
                # Not sent: the page asked for something off the screen or malformed. That page isn't graded.
                entry["error"] = f"bad {t} line: {n}"
                print(f"  {tier}: {entry['error']}", file=sys.stderr)
            else:
                th = threading.Thread(target=send, args=(st["current"][1], cmd, n, 0.6), daemon=True)
                th.start()
                st["gestures"].append(th)
        elif t == "input":
            entry["input"] = pl
        elif t == "camera":
            entry["camera"] = pl
        elif t in ("done", "error"):
            st["live"] = False
            # Stop the view's stream at the fake (protocol 8: the page asks too, but doesn't wait on a
            # busy main thread for the answer); a stop for this view's nonce only.
            threading.Thread(target=still, args=(pl.get("nonce") or st["current"][1],), daemon=True).start()
            if t == "error":
                entry["error"] = pl.get("message")
            else:
                entry["done"] = True
            # Anything of this page's that didn't arrive whole is lost: a line cut into parts that
            # never all came, and a swipe answered with no scroll step back.
            entry["lost_lines"] = page_lines.close(pl.get("nonce"))
            entry["lost_scroll_steps"] = max(0, entry.get("swipes_asked", 0) - len(entry["swipes"]))
            res["bad_page_lines"] = page_lines.bad
            print(f"  {tier} {t}", file=sys.stderr)
            if st["opened"] == len(slots):
                # Watch a little longer: another page now means one was on its way during the last tier.
                st["break_after"] = time.time() + 1.5
            else:
                st["next_at"] = time.time()   # the main loop opens it once the screen is still
        return False

    def drain():
        while True:
            try:
                line = lines.get_nowait()
            except queue.Empty:
                return False
            rec = P.parse_logcat_line(line)
            if rec and handle(rec):
                return True

    if ref:
        # No command left from a view that was cut short: the frontend after the restart must not run it.
        try:
            with urllib.request.urlopen(f"{a.url.rstrip('/')}/clarity/cancel", timeout=10) as r:
                r.read()
        except OSError:
            pass                          # the open below says so if the fake is gone
    dev.sh("input keyevent KEYCODE_WAKEUP")
    dev.sh(f"am force-stop {dev.package}")
    if ref:
        dev.nudge()                       # the restart starts awake; keep_awake goes on from here
    t0 = time.time()
    while time.time() - t0 < a.timeout:
        if res["error"]:
            break                         # e.g. a view couldn't be opened
        if st["break_after"] and time.time() > st["break_after"]:
            break
        late = None
        if ref and st["current"] and st.get("opened_at"):
            entry = entry_of(st["current"][0])
            if not entry.get("page") and time.time() - st["opened_at"] > VIEW_DEADLINE_S:
                late = ("usable", VIEW_DEADLINE_S)
            elif entry.get("page") and time.time() - max(st.get("line_at") or 0, st["opened_at"]) > STAGE_IDLE_S:
                late = (st.get("last_line") or "usable", STAGE_IDLE_S)
        if late:
            if not entry.get("done") and not entry.get("error"):
                view = st["current"][0]
                entry["timed_out_s"] = round(time.time() - st["opened_at"], 1)
                entry["timed_out_after"] = late[0]      # the last thing it said (or "usable": never was)
                entry["lost_lines"] = page_lines.close(st["current"][1])
                res.setdefault("timed_out", []).append(view)
                st["live"], st["current"] = False, None   # its gestures stop (send checks the page)
                if st["opened"] == len(slots):
                    print(f"  {view} timed out ({'not usable within' if late[0] == 'usable' else 'nothing after ' + late[0] + ' for'} "
                          f"{late[1]:.0f} s); it was the run's last view", file=sys.stderr)
                    break
                # Carry on: restart the kiosk as at the run's start (the frontend is reloaded, so the
                # next view isn't measured on what the stuck one left behind), then open the rest.
                print(f"  {view} timed out ({'not usable within' if late[0] == 'usable' else 'nothing after ' + late[0] + ' for'} "
                      f"{late[1]:.0f} s); restarting the kiosk for the run's other views",
                      file=sys.stderr)
                for th in st["gestures"]:
                    th.join(timeout=15)
                st["gestures"] = []
                try:
                    with urllib.request.urlopen(f"{a.url.rstrip('/')}/clarity/cancel", timeout=10) as r:
                        r.read()
                except OSError:
                    pass
                paused.clear()
                st["recovering"] = {"at": time.time()}
                res.setdefault("recoveries", []).append({"after": view})
                dev.nudge()
                dev.sh(f"am force-stop {dev.package}")
                dev.nudge()
                continue
        if st.get("recovering"):
            rv = st["recovering"]
            if not rv.get("launched") and not rv.get("launch") and time.time() - rv["at"] > RECOVER_LAUNCH_S:
                if drain():
                    break
                if st.get("recovering") and not st["recovering"].get("launched"):
                    rv["launch"] = "am-start"
                    dev.sh(f"am start -W -n {dev.component}", timeout=120)
            elif time.time() - rv["at"] > RECOVER_WAIT_S:
                res["error"] = (f"the kiosk didn't come back within {RECOVER_WAIT_S:.0f} s after "
                                f"{res['recoveries'][-1]['after']} timed out")
                break
        if res["launch"] is None and time.time() - t0 > a.settle:
            if drain():
                break
            if res["launch"] is None:   # still nothing relaunched it
                res["launch"] = "am-start"
                am = dev.sh(f"am start -W -n {dev.component}", timeout=120)
                res["am_total_ms"] = int((re.findall(r"TotalTime: (\d+)", am) or [0])[0]) or None
        if ref and st.get("restart_usable_at") and not st.get("restart_done_at") \
                and time.time() - st["restart_usable_at"] > RESTART_DONE_WAIT_S:
            # Its usable line came, its done line didn't (lost in logcat): the restart is measured
            # from the usable line alone; go on rather than wait out the whole run (F8).
            res["restart_done_missing"] = True
            st["restart_done_at"] = time.time()
        if ref and st["current"] is None and st.get("restart_done_at") and st["next_at"] is None and not st["opened"]:
            # Protocol 4: the kiosk came back to the reference dashboard and it is usable: open the
            # graded pages once the screen is still (the next_at branch below).
            res["own_page_state"] = "reference"
            st["next_at"] = time.time()
        if ref and st["current"] is None and st["engine_at"] and not st.get("restart_done_at") and not st["opened"] \
                and not res.get("restart_page"):
            # The kiosk came back to the reference dashboard: only its harness says when the restart
            # ended (refdash, then done), and HA's load event comes well before that, so a page that
            # finished loading mustn't open the views (it would cut the restart's lines off). The
            # wait for the kiosk's own page is for a kiosk that came back somewhere else.
            on_reference = "/clarity-ref/" in (res.get("own_page_uri") or "")
            finished = not on_reference and own_page_finished(res["own_page_stop_mono"], st["own_stop_at"], time.time())
            if finished or time.time() - st["engine_at"] > a.own_page_wait:
                before = (res["own_page_stop_mono"], res["own_page_loads"])
                calm = quiet_for(2.0, 10.0) if finished else False
                if drain():
                    break
                if (res["own_page_stop_mono"], res["own_page_loads"]) != before:
                    continue  # the kiosk loaded its page again meanwhile: wait for that one
                # finished-moving: a clock card keeps painting; normal, and not a reason to doubt the run.
                res["own_page_state"] = "never-finished" if not finished else ("finished-still" if calm else "finished-moving")
                open_next()
                continue
        if st["next_at"] is not None:
            # The page before has stopped its updates; open the next one on a still screen, so no
            # page is measured over the last one's work. (Its teardown on navigation is part of
            # opening a view, as in HA, and the warm-up makes it the same for every page.)
            if a.memory and st["opened"] and slots[st["opened"] - 1] not in fallback and not st.pop("no_memory", False):
                # RAM after each graded page, between pages: nothing is timed now. The largest of
                # these is the peak during the graded tasks (report.py, ram); the fallback views
                # aren't graded tasks.
                res["memory"].append(dict(memory_sample(dev, [x for x in a.radio_procs.split(",") if x], summaries=False),
                                          after=slots[st["opened"] - 1]))
            paused.clear()
            calm = quiet_for(a.between_quiet, 10.0) if a.between_quiet > 0 else True
            if drain():
                break
            res["between"].append("still" if calm else "moving")
            open_next()
            continue
        try:
            rec = P.parse_logcat_line(lines.get(timeout=0.2))
        except queue.Empty:
            continue
        if rec and handle(rec):
            break
    if ref and a.memory and st["opened"] == len(slots) and not res["error"] and slots[-1] not in fallback \
            and not (res.get("timed_out") or [None])[-1] == slots[-1]:
        res["memory"].append(dict(memory_sample(dev, [x for x in a.radio_procs.split(",") if x], summaries=False),
                                  after=slots[-1]))
    lc.terminate()
    if res["error"]:
        pass
    elif res["engine_mono"] is None:
        res["error"] = f"GeckoView never started a page within {a.timeout:.0f} s"
    elif ref and (st["opened"] < len(slots) or st.get("recovering")):
        res["error"] = f"the reference dashboards did not finish within {a.timeout:.0f} s"
    elif ref and st["current"] and not entry_of(st["current"][0]).get("done") and not entry_of(st["current"][0]).get("error") \
            and not entry_of(st["current"][0]).get("timed_out_s"):
        # The last view never said done (smoke10's heavy, with the screen off): not a lost line.
        res["error"] = f"{st['current'][0]} didn't finish within {a.timeout:.0f} s"
    graded_done = ref and all(any((res["tiers"].get(t) or {}).get(k) for k in ("done", "error", "timed_out_s")) for t in tiers)
    if res["error"] and graded_done and fallback and (st["current"] is None or st["current"][0] in fallback):
        # A fallback view that failed fails only itself: the graded views were done. Only the
        # errors above, which come from the view the run stopped in; the screen check comes after.
        res["fallback_error"], res["error"] = res["error"], None
        print(f"  fallback: {res['fallback_error']}", file=sys.stderr)
    # Off while the kiosk restarted after a timed-out view: nothing was measured then.
    if thread_errors:
        # A runner thread died (s1rtc5: a local named like the stop helper made it a bool): what it
        # kept (wakes, gestures, the stream's stop) is gone, so the run isn't trusted. Loud, not quiet.
        res["thread_errors"] = thread_errors
        res["error"] = f"a runner thread failed ({thread_errors[0]}); the run isn't trusted"
    offs = [x for x in res.get("screen_off", []) if x["during"] not in fallback and x["during"] != "recovery"]
    if ref and not res["error"] and offs:
        res["error"] = (f"the screen went off during the graded run ({len(offs)} time(s), first "
                        f"{offs[0]['during']}): with the screen off nothing timed is the panel's")
    for x in res.get("screen_off", []):
        if x["during"] in res["fallback"]:
            # A copy measured with the screen off isn't the panel's either: said on that copy.
            res["fallback"][x["during"]].setdefault("error", "the screen went off during this view")
    # A page that said done without its updates step (smoke7: the line was cut off in logcat) isn't a
    # failed run: its other measurements stand, and the report says what is missing.
    res["missing_steps"] = [t for t, e in res["tiers"].items() if "step" not in e] if ref else []
    if res["missing_steps"]:
        print(f"  {', '.join(res['missing_steps'])}: no updates step; {page_lines.bad} page line(s) didn't parse or never arrived whole",
              file=sys.stderr)
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


# The restart page logs done right after its usable line; this long without it, the line was lost.
RESTART_DONE_WAIT_S = 10


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
    if a.url:
        # A reference dashboard (no nonce: the harness only says it is usable, and the live updates
        # stay off), opened one-off as the graded pages are.
        dev.sh(f"am start -n {dev.component} --es open '{a.url}'")
    end = time.time() + a.settle
    while time.time() < end:
        dev.wake()
        time.sleep(min(KEEP_AWAKE_S, max(0, end - time.time())))
    dev.wake()
    time.sleep(3)
    pids = dev.pids(radio)
    ncpu = int(dev.sh("nproc").strip() or 1)
    # Where the log is now, so a screen that goes off in the window can be found in it afterwards.
    mark = last_mono(dev.sh("logcat -d -v monotonic -t 1", timeout=60))
    before = cpu_snapshot(dev, pids)
    # Frames from before the window are still in SurfaceFlinger's history; keep only newer ones.
    period, history = dev.sf()
    newest_before = max(history, default=0)
    presents = set()
    t_end, woke = time.time() + a.window, time.time()
    while time.time() < t_end:
        time.sleep(1.5)
        if time.time() - woke >= KEEP_AWAKE_S:
            # The kiosk's idle timeout (20 s at night) is shorter than the settle and the window:
            # keep the screen on through the window too (s1v7: off 0.14 s before its end).
            dev.wake()
            woke = time.time()
        p, ts = dev.sf()
        period = p or period
        presents.update(t for t in ts if t > newest_before)
    after = cpu_snapshot(dev, pids)
    log = dev.sh("logcat -d -v monotonic", timeout=120)
    dev.wake()
    mem = memory_sample(dev, radio)
    write_json(os.path.join(a.out, f"idle-{a.label}-{a.run}.json"), dict({
        "label": a.label, "run": a.run, "window_s": a.window, "ncpu": ncpu,
        "cpu": P.cpu_percent(before, after, ncpu),
        "frames": {"period_ms": period, "presents_mono_ms": sorted(t / 1e6 for t in presents)},
        # The screen going off in the window: its CPU and frames aren't the panel's at work.
        "screen_off": screen_offs(log, mark),
        # What the kiosk ran (voice, Sendspin, the wake word's microphone), from its log.
        "panes_state": P.pane_state(log),
    }, **mem))


def last_mono(logcat_text):
    """The monotonic time of the last line of a `logcat -v monotonic` dump (None if it has none)."""
    recs = [P.parse_logcat_line(l) for l in logcat_text.splitlines()]
    monos = [r["mono"] for r in recs if r]
    return monos[-1] if monos else None


def screen_offs(logcat_text, since):
    """When the screen went off after `since` (monotonic s), from Android's PowerManagerService."""
    out = []
    for line in logcat_text.splitlines():
        rec = P.parse_logcat_line(line)
        if rec and (since is None or rec["mono"] > since) and SLEEP_RE.search(rec["msg"]):
            out.append(round(rec["mono"], 3))
    return out


def memory_sample(dev, radio, summaries=True):
    """PSS of every app process (main, :boot, Gecko's GPU, content and crash helper processes) and
    the radio's, with each one's peak RSS, and the device's MemTotal/MemAvailable. Read-only."""
    procs = {}
    for name, pid, pss in P.parse_meminfo_by_process(dev.sh("dumpsys meminfo", timeout=120)):
        role = P.process_role(name, dev.package)
        if role or name in radio:
            hwm = re.search(r"VmHWM:\s+(\d+)", dev.sh(f"cat /proc/{pid}/status"))
            entry = {"pid": pid, "role": role or "radio", "pss_kb": pss, "rss_peak_kb": int(hwm.group(1)) if hwm else None}
            if role and summaries:
                entry["summary"] = P.parse_meminfo_app(dev.sh(f"dumpsys meminfo {pid}", timeout=60))
            procs[name] = entry
    return {"processes": procs, "device_mem": P.parse_proc_meminfo(dev.sh("cat /proc/meminfo"))}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["device", "synthetic", "refstart", "coldstart", "idle", "panes"])
    ap.add_argument("--set", help="panes: the kiosk's pane extras, extra=true|false,...")
    ap.add_argument("--tries", type=int, default=3, help="panes: attempts")
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
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "page", "tiers.json")) as f:
        order = ",".join(json.load(f)["ORDER"])
    ap.add_argument("--tiers", default=order, help="refstart: reference dashboards (tiers.json ORDER); run k starts k-1 pages in")
    ap.add_argument("--fallback", default="", help="refstart: views opened after the graded ones, measured as detail "
                    "(protocol 6: typical-hls,heavy-hls, the cameras over HLS)")
    ap.add_argument("--warmup-tier", default="", help="refstart: a warm-up page opened first and not graded (protocol 4: none; the restart page is the warm-up)")
    ap.add_argument("--memory", type=int, default=1, help="refstart: sample RAM between the graded pages (0: no)")
    ap.add_argument("--between-quiet", type=float, default=2.0, help="refstart: seconds of still screen before each page opens")
    ap.add_argument("--vsync", default="16.667", help="refstart: the display's refresh period in ms")
    a = ap.parse_args()
    if a.timeout is None:
        # refstart: room for views that time out (VIEW_DEADLINE_S each) and the restarts after them.
        a.timeout = {"coldstart": 120, "refstart": 1800}.get(a.cmd, 1200)
    os.makedirs(a.out, exist_ok=True)
    dev = Device(a.serial, a.package, a.activity)
    {"device": cmd_device, "synthetic": cmd_synthetic, "refstart": cmd_coldstart, "coldstart": cmd_coldstart,
     "idle": cmd_idle, "panes": cmd_panes}[a.cmd](dev, a)


if __name__ == "__main__":
    main()
