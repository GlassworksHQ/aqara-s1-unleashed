"""Parsers and statistics for Clarity, the Glassworks perf test (MIT). Pure functions, no adb.

frame_stats() is the same definition as page/stats.js; test/stats-cases.json pins both.
"""
import json
import math
import re
import statistics

BUDGET_MS = 1000 / 60
FROZEN_MS = 700
SF_PENDING = 9223372036854775807


def _r2(x):
    # Same rounding as the page's Math.round(x * 100) / 100 (Python's round() is half-to-even).
    return None if x is None else math.floor(x * 100 + 0.5) / 100


def nearest_rank(sorted_xs, p):
    if not sorted_xs:
        return None
    i = max(0, math.ceil(p * len(sorted_xs)) - 1)
    return sorted_xs[min(i, len(sorted_xs) - 1)]


def frame_stats(intervals_ms, vsync_ms=None):
    """Frame intervals -> median, vsync-snapped p95, jank % (frames over the 16.7 ms budget), frozen %."""
    period = vsync_ms if vsync_ms and vsync_ms > 0 else BUDGET_MS
    xs = [x for x in intervals_ms if isinstance(x, (int, float)) and math.isfinite(x) and x > 0]
    n = len(xs)
    if not n:
        return {"n": 0}
    # JS Math.round rounds .5 up; Python's round() rounds half to even. Match the page.
    snapped = [max(1, math.floor(x / period + 0.5)) * period for x in xs]
    raw = sorted(xs)
    q = sorted(snapped)
    over = sum(1 for x in snapped if x > BUDGET_MS + 0.5)
    frozen = sum(1 for x in xs if x > FROZEN_MS)
    return {
        "n": n,
        "median_ms": _r2(nearest_rank(raw, 0.5)),
        "p95_ms": _r2(nearest_rank(q, 0.95)),
        "p95_raw_ms": _r2(nearest_rank(raw, 0.95)),
        "max_ms": _r2(raw[-1]),
        "fps": _r2(1000 * n / sum(xs)),
        "jank_pct": _r2(100 * over / n),
        "frozen_pct": _r2(100 * frozen / n),
    }


def intervals(ts):
    return [b - a for a, b in zip(ts, ts[1:])]


def spread(values):
    """Median and range of repeated runs; None values (a run that measured nothing) are dropped."""
    xs = [v for v in values if v is not None]
    if not xs:
        return None
    return {"median": _r2(statistics.median(xs)), "min": _r2(min(xs)), "max": _r2(max(xs)), "n": len(xs)}


# d2(n): the expected range of n normal samples in standard deviations (the control-chart constant).
D2 = {2: 1.128, 3: 1.693, 4: 2.059, 5: 2.326, 6: 2.534, 7: 2.704}


def noise_band(xs):
    """Twice an estimate of one sample's standard deviation (#186), or None under two samples.
    Up to seven samples: the range / d2(n), since the median absolute deviation of three samples is
    the smaller of two gaps and is 0 when two agree, and at five it still averages only 1.64 σ. From
    eight: 1.4826 × the median absolute deviation (about 1.8 σ there), so one odd sample doesn't set
    it. Both estimate σ, so the band doesn't grow with more samples.
    test_report.NoiseBand checks it by simulation."""
    if len(xs) < 2:
        return None
    if len(xs) in D2:
        return 2 * (max(xs) - min(xs)) / D2[len(xs)]
    m = statistics.median(xs)
    return 2 * 1.4826 * statistics.median(abs(x - m) for x in xs)


# ---- SurfaceFlinger `dumpsys SurfaceFlinger --latency <layer>`
# Line 1 is the refresh period in ns; each later line is desired-present, actual-present and
# frame-ready time in ns (CLOCK_MONOTONIC). Unpresented rows are 0 or INT64_MAX.

def parse_sf_latency(text):
    lines = [l.split() for l in text.strip().splitlines() if l.strip()]
    if not lines or len(lines[0]) != 1 or not lines[0][0].isdigit():
        return None, []
    period_ms = int(lines[0][0]) / 1e6
    presents = []
    for parts in lines[1:]:
        if len(parts) >= 2 and parts[1].isdigit():
            t = int(parts[1])
            if 0 < t < SF_PENDING:
                presents.append(t)
    return period_ms, sorted(set(presents))


def layer_number(name):
    """'SurfaceView - pkg/pkg.MainActivity#3' -> 3 (a newer layer of the same name gets a higher #n)."""
    m = re.search(r"#(\d+)$", name)
    return int(m.group(1)) if m else -1


def page_to_mono_ms(page, offset_ms):
    """A time on the page's clock (ms after performance.timeOrigin) -> logcat's monotonic clock, in ms.
    The page's log line carries Date.now() and logcat stamps the same line, which ties the clocks
    together (to within the few ms the console takes to reach logcat)."""
    return page["mono"] * 1000 - (page["epoch_ms"] - (page["origin_epoch_ms"] + offset_ms))


def presents_in(presents_ns, start_s, end_s):
    """Present times (ms) inside a monotonic window given in seconds."""
    lo, hi = start_s * 1e9, end_s * 1e9
    return [t / 1e6 for t in presents_ns if lo <= t <= hi]


# ---- logcat -v monotonic:  "  4945.980 15843 15981 I Tag     : message"

LOGCAT_RE = re.compile(r"^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*?)\s*:\s(.*)$")


def parse_logcat_line(line):
    m = LOGCAT_RE.match(line.rstrip("\n"))
    if not m:
        return None
    return {"mono": float(m.group(1)), "pid": int(m.group(2)), "level": m.group(4), "tag": m.group(5).strip(), "msg": m.group(6)}


class PageLines:
    """Page console lines -> payloads. A long line comes in numbered parts ("GWPERF+ key k/n chunk",
    stats.js consoleLines), because logcat keeps only 1023 bytes of a page's console line. feed()
    returns the payload once a whole line is in, else None. A GWPERF line that doesn't parse (cut
    off) is counted in `bad`, never dropped silently."""

    PART_RE = re.compile(r"(\S+) (\d+)/(\d+) (.*)$", re.S)

    def __init__(self):
        self.parts, self.bad = {}, 0

    def _parse(self, text):
        try:
            pl = json.loads(text)
        except ValueError:
            self.bad += 1
            return None
        return pl if isinstance(pl, dict) else None

    def feed(self, msg):
        i = msg.find("GWPERF+ ")
        if i >= 0:
            m = self.PART_RE.match(msg[i + 8:])
            if not m:
                self.bad += 1
                return None
            key, k, n, chunk = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
            got = self.parts.setdefault(key, {})
            got[k] = chunk
            if len(got) < n:
                return None
            del self.parts[key]
            return self._parse("".join(got[j] for j in range(n))) if all(j in got for j in range(n)) else None
        i = msg.find("GWPERF ")
        return self._parse(msg[i + 7:]) if i >= 0 else None

    def close(self, nonce):
        """The page `nonce` is done: a line of its whose parts never all arrived is lost. Counted in
        `bad`, and returned."""
        lost = [k for k in self.parts if k.split(":")[0] == nonce]
        for k in lost:
            del self.parts[k]
        self.bad += len(lost)
        return len(lost)


def gwperf_payload(msg):
    i = msg.find("GWPERF ")
    if i < 0:
        return None
    try:
        return json.loads(msg[i + 7:])
    except ValueError:
        return None


DISPLAYED_RE = re.compile(r"Displayed (\S+): \+(?:(\d+)s)?(\d+)ms")


def parse_displayed(msg, component):
    """ActivityManager's TTID line, `Displayed pkg/.Activity: +1s714ms` -> 1714."""
    m = DISPLAYED_RE.search(msg)
    if not m or m.group(1) != component:
        return None
    return int(m.group(2) or 0) * 1000 + int(m.group(3))


# ---- memory

APP_SUMMARY_KEYS = {
    "Java Heap": "java_heap_kb", "Native Heap": "native_heap_kb", "Code": "code_kb", "Stack": "stack_kb",
    "Graphics": "graphics_kb", "Private Other": "private_other_kb", "System": "system_kb",
}


def parse_meminfo_app(text):
    """`dumpsys meminfo <pid>` App Summary in KB, plus total_pss_kb."""
    out = {}
    in_summary = False
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("App Summary"):
            in_summary = True
            continue
        if in_summary:
            m = re.match(r"([A-Za-z ]+):\s+(\d+)", s)
            if m and m.group(1) in APP_SUMMARY_KEYS:
                out[APP_SUMMARY_KEYS[m.group(1)]] = int(m.group(2))
            m = re.match(r"TOTAL(?: PSS)?:\s+(\d+)", s)
            if m:
                out["total_pss_kb"] = int(m.group(1))
                break
    return out


PSS_LINE_RE = re.compile(r"^\s*([\d,]+)K: (\S+) \(pid (\d+)")


def parse_meminfo_by_process(text):
    """`dumpsys meminfo` "Total PSS by process" -> [(name, pid, pss_kb)]."""
    out = []
    in_section = False
    for line in text.splitlines():
        if line.startswith("Total PSS by process"):
            in_section = True
            continue
        if in_section:
            if not line.strip():
                break
            m = PSS_LINE_RE.match(line)
            if m:
                out.append((m.group(2), int(m.group(3)), int(m.group(1).replace(",", ""))))
    return out


def process_role(name, package):
    """sh.edm.s1kiosk:sandboxed_tab_disable_art_image_31 -> 'tab'; the app itself -> 'main'."""
    if name == package:
        return "main"
    if not name.startswith(package + ":"):
        return None
    role = name[len(package) + 1:]
    role = re.sub(r"_disable_art_image_\d*$", "", role)
    return "tab" if role.startswith("sandboxed_tab") or role == "tab" else role


def parse_proc_meminfo(text):
    out = {}
    for line in text.splitlines():
        m = re.match(r"(\w+):\s+(\d+) kB", line)
        if m:
            out[m.group(1)] = int(m.group(2))
    return {"mem_total_kb": out.get("MemTotal"), "mem_available_kb": out.get("MemAvailable")}


# ---- CPU from /proc

def parse_proc_stat_total(text):
    """First `cpu` line of /proc/stat -> (busy_jiffies, total_jiffies)."""
    for line in text.splitlines():
        if line.startswith("cpu "):
            f = [int(x) for x in line.split()[1:]]
            idle = f[3] + (f[4] if len(f) > 4 else 0)
            return sum(f) - idle, sum(f)
    return None


def parse_pid_jiffies(text):
    """/proc/<pid>/stat -> utime + stime. The comm field can hold spaces, so split after ')'."""
    rest = text[text.rfind(")") + 2:].split()
    return int(rest[11]) + int(rest[12])


def cpu_percent(before, after, ncpu):
    """Per-process and whole-device CPU over a window, in % of one core and % of the device."""
    busy0, tot0 = before["total"]
    busy1, tot1 = after["total"]
    dt = tot1 - tot0
    if dt <= 0:
        return None
    procs = {}
    for name, j0 in before["procs"].items():
        if name in after["procs"]:
            d = after["procs"][name] - j0
            procs[name] = _r2(100 * d / dt * ncpu)
    return {"device_busy_pct": _r2(100 * (busy1 - busy0) / dt), "procs_pct_of_core": procs}


# ---- what the kiosk started with (the S1's panes), from its log. Read-only.

ESPHOME_START_RE = re.compile(r"ESPHome device .*\bvoice=(true|false)")
# Each pane with a switch, at start and when it is turned on or off (#67).
PANE_SWITCH_RE = re.compile(r"\bPane ([a-z0-9-]+) (on|off)$")


def pane_state(logcat_text):
    """The panes the kiosk's current process runs, from its log (`logcat -d`): `voice` (the ESPHome
    line it logs at start, `voice=`), `sendspin` (its player started after that line), `mic` (the
    wake word's microphone open: voice on, opened, not muted since) and `pane:<id>` for each pane the
    kiosk says is on or off (#67). None when the log has no start line (it rotated, or the kiosk
    hasn't started). Reads the lines only; keeps nothing of them."""
    lines = logcat_text.splitlines()
    idx = None
    for i, line in enumerate(lines):
        if ESPHOME_START_RE.search(line):
            idx = i
    if idx is None:
        return {"voice": None, "sendspin": None, "mic": None}
    voice = ESPHOME_START_RE.search(lines[idx]).group(1) == "true"
    sendspin, mic = False, False
    switched = {}
    for line in lines[idx + 1:]:
        m = PANE_SWITCH_RE.search(line)
        if m:
            switched["pane:" + m.group(1)] = m.group(2) == "on"
        elif "Sendspin player starting" in line:
            sendspin = True
        elif "Microphone open" in line or "Microphone unmuted" in line:
            mic = True
        elif "Microphone muted" in line:
            mic = False
    return {"voice": voice, "sendspin": sendspin, "mic": voice and mic, **switched}
