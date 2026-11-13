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


def cmd_coldstart(dev, a):
    """Force-stop, then time the next launch. A kiosk that is the home app is relaunched by the
    system at once (the S1 does it in 0.2 s), so logcat is followed from before the stop, and
    `am start -W` is sent only if nothing relaunched the app within --settle seconds.

    Both record when the engine is ready: GeckoView's first PageStart (its own about:blank), which
    comes before the kiosk does anything for Home Assistant.
    coldstart (real use, not graded) then follows the kiosk's own dashboard to its PageStop.
    refstart (graded) waits until the kiosk's own page has finished (or --own-page-wait seconds if
    it never does), then opens a warm-up page (--warmup-tier, not graded: it takes the kiosk's
    teardown, the first JIT and the cold caches), then each reference dashboard (--url, --tiers) and
    follows its lines: taps and swipes where the page asks. The order turns by one page each run
    (run 1: light, typical, heavy; run 2: typical, heavy, light; run 3: heavy, light, typical), so no
    page always comes first. Each page opens only once the screen has been still for --between-quiet
    seconds (the page before it stops its updates when it is done). `open` can't be sent sooner: until
    the session is up the kiosk drops it (#103's pins). After the first open, any other page is an
    error.

    logcat follows the whole device, so its queue is rarely empty: no decision here waits for an
    empty queue. Before deciding, the loop reads everything queued so far (drain)."""
    ref = a.cmd == "refstart"
    base = [t for t in a.tiers.split(",") if t] if ref else []
    turn = (a.run - 1) % len(base) if base else 0
    tiers = base[turn:] + base[:turn]
    slots = (["warmup"] if ref and a.warmup_tier else []) + tiers
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
           "displayed_mono": None, "engine_mono": None, "engine_uri": None, "own_page_start_mono": None,
           "own_page_stop_mono": None, "own_page_loads": 0, "own_page_state": None,
           "page_start_mono": None, "page_stop_mono": None, "tiers": {}, "order": tiers, "warmup": None,
           "between": [], "bad_page_lines": 0, "protocol_restart": ref, "memory": [], "error": None}
    page_lines = P.PageLines()
    st = {"engine_at": None, "own_stop_at": 0.0, "current": None, "measure_mono": None, "break_after": None,
          "opened": 0, "next_at": None, "gestures": []}
    size = screen_size(dev) if ref else None
    proc_re = re.compile(rf"Start proc \d+:{re.escape(dev.package)}/")

    def entry_of(slot):
        return res["warmup"] if slot == "warmup" else res["tiers"][slot]

    def send(nonce, cmd, n, gap):
        """Taps or a swipe for the page that asked, and only while it is still the current page."""
        for i in range(n):
            if not st["current"] or st["current"][1] != nonce:
                return
            dev.sh(cmd)
            if i + 1 < n:
                time.sleep(gap)

    def open_next():
        # No gesture for the last page may reach the next one: wait for them (the page has said done).
        for th in st["gestures"]:
            th.join(timeout=15)
        st["gestures"] = []
        slot = slots[st["opened"]]
        st["opened"] += 1
        nonce = secrets.token_hex(4)
        st["current"], st["measure_mono"], st["next_at"] = (slot, nonce), None, None
        paused.clear()
        if slot == "warmup":
            tier, extra = a.warmup_tier, "&taps=0&swipes=0&warmup=1"
            res["warmup"] = {"tier": tier, "nonce": nonce, "swipes": []}
        else:
            tier, extra = slot, f"&taps={a.taps}"
            res["tiers"][tier] = {"nonce": nonce, "swipes": []}
        # Protocol 4: a reference dashboard of the fake Home Assistant (fakeha.py), at its own path.
        url = f"{a.url.rstrip('/')}/clarity-{tier}/0?nonce={nonce}&run={a.run}&vsync={a.vsync}{extra}"
        dev.sh(f"am start -n {dev.component} --es open '{url}'")

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
        elif t == "done":
            st["restart_done_at"] = time.time()
        elif t == "error":
            res["restart_page_error"] = pl.get("message")
            st["restart_done_at"] = time.time()
        return False

    def handle_page(tier, pl, rec):
        entry, t = entry_of(tier), pl.get("t")
        pl["mono"] = rec["mono"]
        if t == "refdash":
            entry["page"] = pl
        elif t == "warmup":
            entry["live_mono"] = rec["mono"]
            paused.set()  # no dumpsys while frames are measured, taps are timed or the page scrolls
            dev.wake()
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
        elif t in ("done", "error"):
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

    dev.sh("input keyevent KEYCODE_WAKEUP")
    dev.sh(f"am force-stop {dev.package}")
    t0 = time.time()
    while time.time() - t0 < a.timeout:
        if st["break_after"] and time.time() > st["break_after"]:
            break
        if res["launch"] is None and time.time() - t0 > a.settle:
            if drain():
                break
            if res["launch"] is None:   # still nothing relaunched it
                res["launch"] = "am-start"
                am = dev.sh(f"am start -W -n {dev.component}", timeout=120)
                res["am_total_ms"] = int((re.findall(r"TotalTime: (\d+)", am) or [0])[0]) or None
        if ref and st["current"] is None and st.get("restart_done_at") and st["next_at"] is None and not st["opened"]:
            # Protocol 4: the kiosk came back to the reference dashboard and it is usable: open the
            # graded pages once the screen is still (the next_at branch below).
            res["own_page_state"] = "reference"
            st["next_at"] = time.time()
        if ref and st["current"] is None and st["engine_at"] and not st.get("restart_done_at") and not st["opened"] \
                and not res.get("restart_page"):
            finished = own_page_finished(res["own_page_stop_mono"], st["own_stop_at"], time.time())
            if finished or time.time() - st["engine_at"] > a.own_page_wait:
                before = (res["own_page_stop_mono"], res["own_page_loads"])
                still = quiet_for(2.0, 10.0) if finished else False
                if drain():
                    break
                if (res["own_page_stop_mono"], res["own_page_loads"]) != before:
                    continue  # the kiosk loaded its page again meanwhile: wait for that one
                # finished-moving: a clock card keeps painting; normal, and not a reason to doubt the run.
                res["own_page_state"] = "never-finished" if not finished else ("finished-still" if still else "finished-moving")
                open_next()
                continue
        if st["next_at"] is not None:
            # The page before has stopped its updates; open the next one on a still screen, so no
            # page is measured over the last one's work. (Its teardown on navigation is part of
            # opening a view, as in HA, and the warm-up makes it the same for every page.)
            if a.memory and st["opened"]:
                # RAM after each graded page, between pages: nothing is timed now. The largest of
                # these is the peak during the graded tasks (report.py, ram).
                res["memory"].append(dict(memory_sample(dev, [x for x in a.radio_procs.split(",") if x], summaries=False),
                                          after=slots[st["opened"] - 1]))
            paused.clear()
            still = quiet_for(a.between_quiet, 10.0) if a.between_quiet > 0 else True
            if drain():
                break
            res["between"].append("still" if still else "moving")
            open_next()
            continue
        try:
            rec = P.parse_logcat_line(lines.get(timeout=0.2))
        except queue.Empty:
            continue
        if rec and handle(rec):
            break
    if ref and a.memory and st["opened"] == len(slots) and not res["error"]:
        res["memory"].append(dict(memory_sample(dev, [x for x in a.radio_procs.split(",") if x], summaries=False),
                                  after=slots[-1]))
    lc.terminate()
    if res["error"]:
        pass
    elif res["engine_mono"] is None:
        res["error"] = f"GeckoView never started a page within {a.timeout:.0f} s"
    elif ref and st["opened"] < len(slots):
        res["error"] = f"the reference dashboards did not finish within {a.timeout:.0f} s"
    # A page that said done without its updates step (smoke7: the line was cut off in logcat) isn't a
    # failed run: its other measurements stand, and the report says what is missing.
    res["missing_steps"] = [t for t, e in res["tiers"].items() if "step" not in e] if ref else []
    if res["missing_steps"]:
        print(f"  {', '.join(res['missing_steps'])}: no updates step; {page_lines.bad} page line(s) didn't parse",
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
    mem = memory_sample(dev, radio)
    write_json(os.path.join(a.out, f"idle-{a.label}-{a.run}.json"), dict({
        "label": a.label, "run": a.run, "window_s": a.window, "ncpu": ncpu,
        "cpu": P.cpu_percent(before, after, ncpu),
        "frames": {"period_ms": period, "presents_mono_ms": sorted(t / 1e6 for t in presents)},
    }, **mem))


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
    ap.add_argument("--tiers", default="light,typical,heavy", help="refstart: reference dashboards; run k starts k-1 pages in")
    ap.add_argument("--warmup-tier", default="", help="refstart: a warm-up page opened first and not graded (protocol 4: none; the restart page is the warm-up)")
    ap.add_argument("--memory", type=int, default=1, help="refstart: sample RAM between the graded pages (0: no)")
    ap.add_argument("--between-quiet", type=float, default=2.0, help="refstart: seconds of still screen before each page opens")
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
