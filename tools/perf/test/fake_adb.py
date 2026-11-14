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
import threading
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
    if os.environ.get("FAKE_SCREEN_SLEEP_S") and ("KEYCODE_WAKEUP" in cmd or "--es screen on" in cmd
                                                   or cmd.startswith(("input swipe", "input tap"))):
        save("awake_at.json", time.time())       # what keeps the fake screen on (a wake, a touch)
        save("asleep.json", False)
        if "KEYCODE_WAKEUP" in cmd:
            save("nudges.json", state("nudges.json", 0) + 1)
            return ""
    props = {"ro.product.manufacturer": "Example", "ro.product.model": "Test Panel", "ro.build.version.release": "10",
             "ro.build.version.sdk": "29", "ro.product.cpu.abi": "armeabi-v7a", "ro.product.cpu.abilist": "armeabi-v7a,armeabi",
             "ro.board.platform": "testsoc", "ro.hardware": "testhw"}
    if cmd.startswith("getprop "):
        return props.get(cmd.split()[1], "")
    m = re.search(r"echo (\w+);? .*toybox nc( -u)? .*?(\d+\.\d+\.\d+\.\d+) (\d+)$", cmd)
    if m:
        # lanprobe.py's checks (#202): the panel reaches this computer over the LAN as FAKE_LAN says
        # (udp: both; tcp: TCP only; unset: neither), and then really sends the token there.
        token, udp, addr, port = m.group(1), bool(m.group(2)), m.group(3), int(m.group(4))
        save("lan_checks.json", state("lan_checks.json", []) + ["udp" if udp else "tcp"])
        lan = os.environ.get("FAKE_LAN", "")
        if (udp and lan == "udp") or (not udp and lan in ("udp", "tcp")):
            import socket
            if udp:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                    s.sendto((token + "\n").encode(), (addr, port))
            else:
                with socket.create_connection((addr, port), timeout=2) as s:
                    s.sendall((token + "\n").encode())
        return ""
    if cmd.startswith("am start"):
        u = re.search(r"--es url '([^']+)'", cmd)
        if u and (os.environ.get("FAKE_RESTORE_FAILS") and "127.0.0.1" not in u.group(1)
                  or os.environ.get("FAKE_REF_URL_FAILS") and "127.0.0.1" in u.group(1)):
            # The kiosk didn't take the restore (am exits 0 all the same): nothing is saved.
            save("restore_refused.json", state("restore_refused.json", 0) + 1)
            return "Starting: Intent { cmp=sh.edm.s1kiosk/.MainActivity }\nError: Activity not started, unable to resolve Intent\n"
        if u:
            # Like the kiosk: `url` saves the dashboard (a URL or "auto") and loads it.
            save("saved_url.json", u.group(1))
            save("urls_set.json", state("urls_set.json", []) + [u.group(1)])
        ez = re.findall(r"--ez (\w+) (true|false)", cmd)
        if ez:
            # Like the kiosk: each `--ez` saves a setting (voice_enabled, sendspin_enabled, restart).
            # FAKE_VOICE_LOST: as S1 Kiosk 0.4.3, which saves voice_enabled asynchronously and exits
            # to restart at once, so the write is lost.
            save("intents.json", state("intents.json", []) + [dict(ez)])
            keep = {k: v for k, v in ez if k != "restart" and not (k == "voice_enabled" and os.environ.get("FAKE_VOICE_LOST"))}
            save("settings.json", dict(state("settings.json", {}), **keep))
        m = re.search(r"--es open '([^']+)'", cmd)
        # Like the S1 kiosk (#103's pins): `open` is dropped until GeckoView's session is up.
        if m and state("session_up.json", True):
            nonce = re.search(r"nonce=(\w+)", m.group(1))
            if nonce:
                save("refnonce.json" if "/clarity-" in m.group(1) else "nonce.json", [nonce.group(1), m.group(1)])
            else:
                save("opened_still.json", state("opened_still.json", []) + [m.group(1)])   # an idle sample's page
        if " -W " in cmd:
            # A launch (the cold start); an intent with a setting goes to the running kiosk instead.
            if not os.environ.get("FAKE_NO_DASHBOARD") and not u and not ez:
                save("cold.json", True)
            return "Starting: Intent\nStatus: ok\nActivity: sh.edm.s1kiosk/.MainActivity\nTotalTime: 1700\nWaitTime: 1710\nComplete\n"
        return "Starting: Intent\n"
    if cmd.startswith("logcat -d"):
        # The kiosk's start lines for the panes it runs now (perflib.pane_state reads them).
        s = state("settings.json", {})
        voice = s.get("voice_enabled", "true") == "true"
        t = now()
        out = [f"{t - 5:10.3f}  31236  31237 I S1ESPHome: ESPHome device 's1-panel' mac 00:00:00:00:00:00, voice={'true' if voice else 'false'}"]
        if s.get("sendspin_enabled", "true") == "true":
            out.append(f"{t - 4.9:10.3f}  31236  31237 I S1Sendspin: Sendspin player starting (client id test)")
        for pane in ("wall-base", "time-zone", "diagnostics"):   # #67: the kiosk says what each switch is
            on = s.get(pane.replace("-", "_") + "_enabled", "true") == "true"
            out.append(f"{t - 4.8:10.3f}  31236  31237 I S1ESPHome: Pane {pane} {'on' if on else 'off'}")
        if voice and s.get("mic_muted", "false") != "true":
            out.append(f"{t - 3:10.3f}  31236  31237 I S1Voice: Microphone open: source=6 buffer=20480")
        if os.environ.get("FAKE_IDLE_SCREEN_OFF") and " -t 1" not in cmd:
            # One before the sample's window (an earlier night, not this sample's) and one in it.
            out.insert(0, f"{t - 600:10.3f}   1919   1920 I PowerManagerService: Going to sleep due to timeout (uid 1000)...")
            out.append(f"{t - 0.5:10.3f}   1919   1920 I PowerManagerService: Going to sleep due to device_admin (uid 1000)...")
        if " -t 1" in cmd:
            out = [f"{t - 2:10.3f}      1      1 I old: the last line"]
        return "\n".join(out) + "\n"
    if cmd.startswith(("input swipe", "input tap")):
        # Like a page: a gesture nobody asked for (no current *-ready left to answer) lands on whatever
        # is on screen. Record it, and don't count it as the page's.
        kind = "swipe" if cmd.startswith("input swipe") else "tap"
        asked = state("asked.json", {})
        if asked.get(kind, 0) < 1:
            save("unasked.json", state("unasked.json", []) + [cmd])
            return ""
        asked[kind] -= 1
        save("asked.json", asked)
        save(f"{kind}s.json", state(f"{kind}s.json", 0) + 1)
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
        # A pane turned on with its extra adds to the app's PSS: Assist 30 MB, Sendspin 10 MB, the wall
        # base 3 MB, the time zone 1 MB, diagnostics 2 MB.
        on = state("settings.json", {})
        # Assist costs its 30 MB while its microphone runs (voice on, not muted), as on the S1.
        assist = on.get("voice_enabled") == "true" and on.get("mic_muted", "false") != "true"
        main = 190_000 + (30_000 if assist else 0) + (10_000 if on.get("sendspin_enabled") == "true" else 0) + sum(
            kb for k, kb in (("wall_base_enabled", 3_000), ("time_zone_enabled", 1_000), ("diagnostics_enabled", 2_000)) if on.get(k) == "true")
        rows = {PKG + ":gpu_disable_art_image_": 200_000, PKG: main, PKG + ":sandboxed_tab_disable_art_image_24": 180_000, "otbr-agent": 2_500}
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
        if os.environ.get("FAKE_IDLE_FAILS"):
            # The device step reads it first; the idle sample's read then fails: a run that stops
            # partway (run.sh's exit trap).
            n = state("nproc_reads.json", 0) + 1
            save("nproc_reads.json", n)
            if n > 1:
                return "garbled\n"
        return "4\n"
    if "thermal_zone" in cmd:
        if "case" in cmd:
            return "45000\n"
        return "cpu_thermal_zone=45000 gpu_thermal_zone=44000\n" if "=" in cmd else "cpu_thermal_zone 45000\ngpu_thermal_zone 44000\n"
    if "cpufreq/scaling_cur_freq" in cmd:
        return "1200000\n1200000\n1200000\n1200000\n"      # as #244 found the S1: held at 1.2 GHz
    if "cpufreq/cpuinfo_max_freq" in cmd:
        return "1608000\n1608000\n1608000\n1608000\n"
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


LOCK = threading.Lock()


# As on the S1 (2026-11-12): a page's console line reaches logcat from the content process, tagged
# "Isolated Web Content", and only its first 1023 bytes arrive.
PAGE_TAG, PAGE_LINE_BYTES = "Isolated Web Content", 1023
# The pages' protocol, read from the pages themselves, so the fake can't fall behind them.
with open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "page", "tiers.json")) as _f:
    _T = json.load(_f)
    PROTOCOL, CLARITY = _T["PROTOCOL"], _T["CLARITY"]
PAGE_LINE_MAX = 900     # stats.js LINE_MAX: longer lines are split, as the page does


def page_lines(key, text):
    """The page's consoleLines (stats.js), in Python: split on UTF-8 bytes, never inside a character.
    test_runner.FakeMatchesThePanel checks it against the page's own (run in node)."""
    if len(text.encode()) <= PAGE_LINE_MAX:
        return ["GWPERF " + text]
    sizes = [len(c.encode()) for c in text]
    parts, i = [], 0
    while i < len(text):
        j, size = i, 0
        while j < len(text) and size + sizes[j] <= PAGE_LINE_MAX:
            size += sizes[j]
            j += 1
        while j < len(text) and j > i + 1 and (text[j - 1].isspace() or text[j].isspace()):
            j -= 1
        parts.append(text[i:j])
        i = j
    return [f"GWPERF+ {key} {k}/{len(parts)} {p}" for k, p in enumerate(parts)]


SEQ = [0]


def shape_of(obj):
    """A page line's type, as test/page-lines.json keys them."""
    t = obj.get("t")
    staged = t in ("measure", "step", "warmup") and obj.get("stage") in ("updates", "scroll")
    return f"{t}/{obj['stage']}" if staged else t


def page_log(obj, split=True, record=True):
    SEQ[0] += 1
    text = json.dumps(obj, separators=(",", ":"))
    if record:
        # The reference page's lines' keys, so test_runner can hold the fake to the panel's shapes
        # (test/page-lines.json).
        with open(os.path.join(STATE, "page-lines.jsonl"), "a") as f:
            f.write(json.dumps({"shape": shape_of(obj), "keys": sorted(obj)}) + "\n")
    lines = page_lines(f"{obj.get('nonce')}:{SEQ[0]}", text) if split else ["GWPERF " + text]
    # Losses, as a busy logcat can lose lines: the last part of a split updates step, or the first
    # scroll step whole.
    if os.environ.get("FAKE_DROP_LAST_PART") and len(lines) > 1 and obj.get("stage") == "updates":
        lines = lines[:-1]
    if os.environ.get("FAKE_DROP_SCROLL_STEP") and obj.get("t") == "step" and obj.get("stage") == "scroll" \
            and obj.get("n") == 0:
        lines = []
    for l in lines:
        line(PAGE_TAG, l)


def line(tag, msg, pid=4242):
    if tag == PAGE_TAG:
        msg = msg.encode()[:PAGE_LINE_BYTES].decode(errors="ignore")
    with LOCK:
        print(f"{now():10.3f}  {pid}  {pid + 1} I {tag}: {msg}", flush=True)


def noise(every):
    """Unrelated device logging, as on a real panel: logcat follows the whole device."""
    while True:
        line("ha_driven", "enterConnectingState: connect fail, errno:111", pid=2527)
        time.sleep(every)


def page(nonce):
    def gw(obj):
        page_log(dict(obj, run=1, nonce=nonce), split=not os.environ.get("FAKE_NO_SPLIT"), record=False)

    gw({"t": "hello", "ua": "Mozilla/5.0 (Android 10; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0", "load": {"lcp_ms": 420}})
    gw({"t": "js", "runs": 30, "median_ms": 9.5, "p95_ms": 12.0, "checksum": 7, "expected": 7})
    if os.environ.get("FAKE_SYNTH_SCREEN_OFF"):
        # The kiosk's idle timeout turns the screen off while the test page runs.
        line("PowerManagerService", "Going to sleep due to device_admin (uid 1000)...", pid=1919)
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
    (state updates), one window of frames, the taps, then swipes where it asks, done."""
    tier = re.search(r"/clarity-ref/([\w-]+)", url).group(1)
    # Protocol 6: a camera page's HLS copy (tiers.json FALLBACK) plays over HLS; the rest over WebRTC.
    page, path = (tier[:-4], "hls") if tier.endswith("-hls") else (tier, "web_rtc")
    taps = int(re.search(r"taps=(\d+)", url).group(1))
    m = re.search(r"swipes=(\d+)", url)
    swipes = int(m.group(1)) if m else int(os.environ.get("FAKE_SWIPES", "2"))
    save("opened.json", state("opened.json", []) + [tier + ("-warmup" if "warmup=1" in url else "")])
    foreign = os.environ.get("FAKE_FOREIGN_PAGE")
    foreign = foreign if (foreign == "end") == (tier == "heavy") else None
    dpr = float(os.environ.get("FAKE_DPR", "1.5"))

    def gw(obj):
        # FAKE_STAGE_DELAY (protocol 8): a slow view that keeps going: this long before each line
        # after it is usable, so the whole view outlasts the usable deadline but no stage stops.
        if obj.get("t") not in ("refdash",) and os.environ.get("FAKE_STAGE_DELAY") and tier == os.environ.get("FAKE_STAGE_VIEW", tier):
            time.sleep(float(os.environ["FAKE_STAGE_DELAY"]))
        page_log(dict(obj, nonce=nonce, run=1), split=not os.environ.get("FAKE_NO_SPLIT"))

    # FAKE_SLOW_VIEW: the view takes this long to be usable (the runner keeps the screen awake).
    time.sleep(float(os.environ.get("FAKE_SLOW_VIEW", "0")))
    if os.environ.get("FAKE_NEVER_USABLE") == tier:
        return                 # never usable: not a line
    if os.environ.get("FAKE_SCREEN_OFF") == tier:
        # As smoke10: the kiosk's idle timeout turns the screen off while the view opens.
        line("PowerManagerService", "Going to sleep due to device_admin (uid 1000)...", pid=1919)
    if foreign == "before":
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace?external_auth=1")
    # Protocol 4: the frontend stays loaded; the view opens by navigation, so no page load is logged.
    origin = time.time() * 1000 - 5000
    nav = time.time() * 1000 - origin
    a = now()
    paint(a + 0.15, a + 0.5)
    save("asked.json", {})
    stale = state("last_nonce.json")
    if os.environ.get("FAKE_STALE_READY") and stale:
        # The last page's requests, replayed late into this one (its console lines can arrive after
        # the next page opened). The runner must answer only the current page's nonce.
        for t in ("swipe-ready", "input-ready"):
            line(PAGE_TAG, "GWPERF " + json.dumps({"t": t, "nonce": stale, "run": 1, "n": 0, "x": 720, "y": 360,
                                                          "y1": 612, "y2": 108, "ms": 300, "taps": 2}))
    save("last_nonce.json", nonce)
    if foreign == "after":
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace?external_auth=1")
    time.sleep(0.7)
    gw({"t": "refdash", "tier": tier, "protocol": PROTOCOL, "clarity": CLARITY, "frontend": "20261028.5", "nodes": 1458, "cards": 50,
        "cards_rendered": 47, "css": [960, 480], "dpr": dpr, "scroll_height": 2635,
        "fcp_ms": None, "lcp_ms": None, "load_ms": None, "origin_epoch_ms": origin, "epoch_ms": time.time() * 1000,
        # Protocol 4: navigation at `nav`; the view's first screen rendered 200 ms later, the main thread
        # free 20 ms after that.
        "nav_ms": round(nav, 1), "video_caps": {"mse_h264": True, "native_hls": "", "rvfc": True},
        "usable": {"rendered_tick_ms": round(nav + 200, 1), "first_screen_cards": 14, "head_cards": 10,
                   "interactive_ms": round(nav + 220, 1)}})
    time.sleep(1.0)
    if os.environ.get("FAKE_HANG") == tier:
        return                 # as smoke10's heavy: usable, then nothing more (no done)
    mark(f"page warmup {tier}")
    gw({"t": "warmup", "stage": "updates", "n": 1})
    if os.environ.get("FAKE_HANG_LIVE") == tier:
        return                 # as smoke 7's heavy (#202): live, then too slow to say anything more
    time.sleep(0.3)
    gw({"t": "measure", "stage": "updates", "n": 1, "moving": 12, "offscreen": 0, "targets": 10, "update_every_ms": 200,
        "animations": 0, "animations_expected": 0})
    m0, t0 = now(), time.time() * 1000 - origin
    paint(m0, m0 + 0.5)      # each change on screen within a frame or two
    time.sleep(0.5)
    # As long as the real page's (smoke7's were cut at 1023 bytes; this one is about 1.8 KB, so it is
    # split): a change every 10 ms over the 500 ms window, and 31 rAF times.
    # [sent, arrived, transition, kind, tick]: the fake HA's schedule, the page reads each 0.4 ms later.
    # FAKE_BUSY_MAIN: from 200 ms on, nothing reaches the page in the window (a busy main thread).
    busy = os.environ.get("FAKE_BUSY_MAIN")
    changes = [[round(10.0 * k, 1), None if busy and 10 * k >= 200 else round(10.0 * k + 0.4, 1), 180 if k % 9 == 4 else 0,
                2 if k % 9 == 4 else 0, None if busy and 10 * k >= 200 else round(10.0 * k + 0.6, 1)] for k in range(1, 49)]
    gw({"t": "step", "stage": "updates", "n": 1, "window_ms": 500, "t0": round(t0, 1), "pacing_ms": 500, "from": "sent",
        "clock_rtt_ms": float(os.environ.get("FAKE_CLOCK_RTT", "3.0")), "stream_at_ms": -1000.0, "stream_asked_ms": -1001.5,
        "raf": {"n": 30, "median_ms": 16.67, "p95_ms": 16.67, "jank_pct": 0, "frozen_pct": 0, "fps": 60},
        "raf_times": [round(i * 16.7, 1) for i in range(31)], "changes": changes})
    if taps:
        before = state("taps.json", 0)
        save("asked.json", {"tap": taps})
        gw({"t": "input-ready", "x": 720, "y": 360, "taps": taps})
        end = now() + 10
        while state("taps.json", 0) - before < taps and now() < end:
            time.sleep(0.1)
        gw({"t": "input", "stage": tier, "method": "event-timing", "taps": state("taps.json", 0) - before,
            "values": [16, 48], "inp_ms": 48})
    n = {"typical": 1, "heavy": 4}.get(page, 0)
    if n:
        # #174: the view's live cameras, from going live to the first swipe. FAKE_NO_VIDEO: none plays.
        dead = os.environ.get("FAKE_NO_VIDEO") == tier
        # As the S1 logs them (2026-11-13): over HLS, a hardware decoder per stream from the media
        # process, and on heavy the Allwinner decoder's flushes and drains when a stream runs out of
        # data. Over WebRTC, nothing: Gecko decodes it in software (#202, step 0).
        for _ in range(0 if dead or path == "web_rtc" else n):
            line("MediaCodec", "[OMX.allwinner.video.decoder.avc] setting surface generation to 17119233", pid=16718)
        if tier == "heavy-hls" and not dead:
            line("omx_vdec_aw", "<__anFlush:1043>: decoder flush", pid=1774)
            line("omx_vdec_aw", "<__anFlush:1043>: decoder flush", pid=1774)
            line("omx_vdec", "<doSubmit:2004>: found eos flag in input data", pid=1774)
        webrtc = {"inbound": True, "frames_received": 75, "frames_decoded": 75, "frames_dropped": 0, "freezes": 0, "freeze_s": 0,
                  "decoder": None, "power_efficient": None, "codec": "video/H264",
                  "candidate": {"local": "relay", "relay": "tcp", "protocol": "udp"}}
        gw({"t": "camera", "stage": tier, "path": path, "window_ms": 5000, "t0_ms": 9000.0, "nav_ms": 5000.0, "fps": 15,
            "expected": n, "found": n,
            "streams": [{"found": True, "w": 0 if dead else (1280 if i == 0 else 640), "h": 0 if dead else (720 if i == 0 else 360),
                         "first_frame_ms": None if dead else 640.0, "shown_pct": None if dead else 100.0,
                         "max_gap_ms": None if dead else 70.0, "stall_total_ms": None if dead else 0.0,
                         "stall_presented": None if dead else 0,
                         "frames": 0 if dead else 75, "decoded": 0 if dead else 75, "dropped": 0, "waiting": 0,
                         "source": "quality+rvfc", "path": path,
                         **({"webrtc": None if dead else webrtc, "others": [{"path": "hls", "first_frame_ms": 420.0, "connected": False}]}
                            if path == "web_rtc" else {"others": []})} for i in range(n)],
            # The segments' fetch times (stats.js hlsFetches). Heavy's whole line is longer than a
            # logcat line (1023 bytes), so it goes out in parts, as the page's does.
            "fetches": {"segment": {"n": 5 * n, "queued_ms": {"median": 1.2, "p95": 3.4, "max": 4.1},
                                    "wait_ms": {"median": 6.5, "p95": 12.25, "max": 14.0},
                                    "total_ms": {"median": 18.75, "p95": 40.5, "max": 52.0},
                                    "kb": 180 * n, "arrival_gap_max_ms": 1012.5},
                        "playlist": {"n": 5 * n, "queued_ms": {"median": 0.5, "p95": 1.0, "max": 1.0},
                                     "wait_ms": {"median": 2.0, "p95": 3.0, "max": 3.0},
                                     "total_ms": {"median": 3.0, "p95": 5.0, "max": 5.0}},
                        "streams": n}})
    for i in range(swipes):
        before = state("swipes.json", 0)
        save("asked.json", {"swipe": 1})
        ready = {"t": "swipe-ready", "n": i, "x": 720, "y1": 612, "y2": 108, "ms": 300}
        if os.environ.get("FAKE_BAD_SWIPE"):
            ready = json.loads(os.environ["FAKE_BAD_SWIPE"])
        gw(ready)
        end = now() + float(os.environ.get("FAKE_SWIPE_WAIT", "10"))
        while state("swipes.json", 0) == before and now() < end:
            time.sleep(0.05)
        moved = state("swipes.json", 0) > before
        if not moved:
            gw({"t": "step", "stage": "scroll", "n": i, "window_ms": 0, "scrolled_px": 0, "raf": {"n": 0}})
            continue
        s0 = now()
        paint(s0, s0 + 0.5)   # the swipe and its fling
        gw({"t": "measure", "stage": "scroll", "n": i})
        time.sleep(0.5)
        gw({"t": "step", "stage": "scroll", "n": i, "window_ms": 500, "scrolled_px": 700, "hit_edge": False, "changes_during": 3, "first_scroll_ms": 40,
            "raf": {"n": 30, "median_ms": 16.67, "p95_ms": 16.67, "jank_pct": 0, "frozen_pct": 0, "fps": 60},
            "raf_intervals": [16.7] * 30})
        time.sleep(0.2)
    save("asked.json", {})
    mark(f"page done {tier}")
    if os.environ.get("FAKE_HA_URL"):
        # As the harness does at the end of a view: stop the stream, which also ends the command.
        import urllib.request
        try:
            urllib.request.urlopen(os.environ["FAKE_HA_URL"].rstrip("/") + f"/clarity/still?nonce={nonce}", timeout=10).read()
        except OSError:
            pass
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
    saved = state("saved_url.json") or os.environ.get("FAKE_SAVED_URL")
    if saved and "/clarity-" in saved and not os.environ.get("FAKE_RESTART_PAGE_LOST"):
        # Protocol 4: the kiosk was pointed at a reference dashboard; it comes back to it, and the
        # harness (no nonce: restart mode) says when it was usable, then done.
        line("GeckoSession", f"handleMessage GeckoView:PageStart uri={saved}")
        origin = time.time() * 1000 - 200
        if os.environ.get("FAKE_RESTART_SLOW"):
            # As on the S1: HA's load event (PageStop) comes long before the dashboard is usable.
            line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")
            time.sleep(float(os.environ["FAKE_RESTART_SLOW"]))
        time.sleep(0.5)
        page_log({"t": "refdash", "tier": "start", "protocol": PROTOCOL, "clarity": CLARITY, "frontend": "20261028.5", "nodes": 1082, "cards": 22,
                  "cards_rendered": 20, "css": [960, 480],
                  "dpr": float(os.environ.get("FAKE_DPR", "1.5")), "scroll_height": 1936, "fcp_ms": 120, "lcp_ms": 120,
                  "load_ms": 100, "nav_ms": 0,
                  "usable": {"rendered_tick_ms": 400.0, "first_screen_cards": 13, "head_cards": 10, "interactive_ms": 410.0},
                  "origin_epoch_ms": origin, "epoch_ms": time.time() * 1000, "run": 1, "nonce": ""}, record=False)
        line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")
        time.sleep(0.2)
        if not os.environ.get("FAKE_NO_RESTART_DONE"):     # F8: the done line lost (logcat dropped it)
            page_log({"t": "done", "stage": "restart", "run": 1, "nonce": ""}, record=False)
        return
    line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace?external_auth=1")
    time.sleep(0.8)
    line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")
    if os.environ.get("FAKE_OWN_RELOAD"):
        # A sign-in redirect: the kiosk loads its page a second time, this long after the first stopped.
        time.sleep(float(os.environ.get("FAKE_OWN_RELOAD_DELAY", "0.5")))
        line("GeckoSession", "handleMessage GeckoView:PageStart uri=http://192.0.2.10:8123/lovelace/0?external_auth=1")
        time.sleep(0.8)
        line("GeckoSession", "handleMessage GeckoView:PageStop uri=null")


def mark(text):
    """A line in commands.log, in order with the adb commands (the tests read it)."""
    with open(os.path.join(STATE, "commands.log"), "a") as f:
        f.write(text + "\n")


def take_commands(base):
    """As the harness does in the loaded frontend: wait for the runner's next view at the fake Home
    Assistant (/clarity/next), then play it (refdash, below)."""
    import urllib.parse
    import urllib.request
    last = ""
    while True:
        try:
            with urllib.request.urlopen(base.rstrip("/") + "/clarity/next?after=" + last, timeout=30) as r:
                if r.status != 200:
                    continue
                cmd = json.loads(r.read())
        except OSError:
            time.sleep(0.2)
            continue
        last = cmd["nonce"]
        q = {k: v for k, v in cmd.items() if k != "view"}
        save("views.json", state("views.json", []) + [cmd])
        with open(os.path.join(STATE, "commands.log"), "a") as f:     # in order with the adb commands
            f.write(f"view {cmd['view']} {cmd['nonce']} taps={cmd.get('taps')}\n")
        save("refnonce.json", [cmd["nonce"], f"/clarity-ref/{cmd['view']}?{urllib.parse.urlencode(q)}"])


def screen_timeout(after_s):
    """As the S1 at night: the screen goes off after_s seconds after the last wake or touch."""
    save("awake_at.json", state("awake_at.json") or time.time())
    while True:
        time.sleep(0.1)
        if not state("asleep.json") and time.time() - (state("awake_at.json") or 0) > after_s:
            save("asleep.json", True)
            line("PowerManagerService", "Going to sleep due to timeout (uid 1000)...", pid=1919)


def logcat():
    print("    1.000  1  1 I old: line from before", flush=True)
    if os.environ.get("FAKE_HA_URL"):
        threading.Thread(target=take_commands, args=(os.environ["FAKE_HA_URL"],), daemon=True).start()
    if os.environ.get("FAKE_LOGCAT_NOISE"):
        threading.Thread(target=noise, args=(float(os.environ["FAKE_LOGCAT_NOISE"]),), daemon=True).start()
    if os.environ.get("FAKE_SCREEN_SLEEP_S"):
        threading.Thread(target=screen_timeout, args=(float(os.environ["FAKE_SCREEN_SLEEP_S"]),), daemon=True).start()
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
