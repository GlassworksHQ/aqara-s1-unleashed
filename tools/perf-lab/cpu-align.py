"""Line a cpu-trace.sh trace up with a Clarity run (MIT). Reads files only.

For each graded view open in the run (refstart-<n>.json: navigation to usable), prints the device's
busy share of all cores, the CPU clock it ran at, the CPU zone's temperature, the cpufreq cooling
state, the ceiling and governor (root traces only), and each kiosk process's (and its main thread's,
and the voice threads') share of one core. With --timeline, also one line per BIN seconds over the
whole trace, labelled with the view open it falls in, so a drop in the clock can be put next to the
temperature and the load at that moment. Used for #244.

  python3 -I tools/perf-lab/cpu-align.py TRACE CLARITY_OUT_DIR [--view typical-nocam] [--timeline [BIN]]
"""
import bisect
import collections
import glob
import json
import os
import statistics
import sys

trace, outdir = sys.argv[1:3]
only = sys.argv[sys.argv.index("--view") + 1] if "--view" in sys.argv else None
timeline = "--timeline" in sys.argv
BIN = 5.0
if timeline:
    k = sys.argv.index("--timeline") + 1
    if k < len(sys.argv) and sys.argv[k].replace(".", "").isdigit():
        BIN = float(sys.argv[k])
HZ = 100


class Sample:
    __slots__ = ("up", "jiffies", "busy", "total", "freq", "temps", "cooling", "ceiling", "governor")

    def __init__(self, up):
        self.up, self.jiffies, self.busy, self.total = up, {}, 0, 0
        self.freq = self.temps = self.cooling = self.ceiling = self.governor = None


def jiffies(stat):
    f = stat.rpartition(")")[2].split()
    return int(f[11]) + int(f[12])  # utime + stime (fields 14 and 15)


samples, offset, cur, cpu_temp_prop = [], None, None, set()
for line in open(trace, errors="replace"):
    kind, _, rest = line.rstrip("\n").partition(" ")
    if kind == "E":
        epoch, up = rest.split()[:2]
        offset = float(epoch) - float(up)
    elif kind == "G":
        cpu_temp_prop.add(rest.strip())
    elif kind == "U":
        if cur:
            samples.append(cur)
        cur = Sample(float(rest))
    elif cur is None:
        continue
    elif kind == "C" and rest.startswith("cpu "):
        v = [int(x) for x in rest.split()[1:8]]
        cur.busy, cur.total = sum(v) - v[3] - v[4], sum(v)
    elif kind == "F":
        cur.freq = int(rest)
    elif kind == "T":
        f = rest.split()
        cur.temps, cur.cooling = [int(x) / 1000 for x in f[:3]], int(f[3])
    elif kind == "X":
        f = rest.split()
        cur.ceiling, cur.governor = int(f[0]), (f[1] if len(f) > 1 else None)
    elif kind in ("P", "M", "H"):
        head, _, stat = rest.partition("|")
        ident, name = head.split(" ", 1)
        key = {"P": name, "M": name + " [main thread]", "H": "  thread " + name}[kind]
        try:
            cur.jiffies[key] = jiffies(stat)
        except (IndexError, ValueError):
            pass
if cur:
    samples.append(cur)
times = [s.up for s in samples]


def window(t0, t1):
    i, j = bisect.bisect_left(times, t0), bisect.bisect_right(times, t1) - 1
    if i >= j:
        return None
    a, b, span = samples[i], samples[j], samples[i:j + 1]
    dt = b.up - a.up
    return {
        "dt": dt,
        "busy": 100 * (b.busy - a.busy) / max(1, b.total - a.total),
        "shares": {k: 100 * (b.jiffies[k] - a.jiffies[k]) / HZ / dt for k in b.jiffies if k in a.jiffies},
        "freqs": collections.Counter(s.freq for s in span),
        "cpu_c": [s.temps[0] for s in span if s.temps],
        "cooling": collections.Counter(s.cooling for s in span if s.cooling is not None),
        "ceilings": collections.Counter((s.ceiling, s.governor) for s in span if s.ceiling),
    }


def describe(w):
    t = w["cpu_c"]
    temp = f"CPU {min(t):.1f} to {max(t):.1f} °C" if t else "no temperatures"
    ceil = (", ceiling " + ", ".join(f"{c} {g} x{n}" for (c, g), n in w["ceilings"].most_common())) if w["ceilings"] else ""
    clock = ", ".join(f"{f} x{n}" for f, n in sorted(w["freqs"].items(), key=lambda kv: -(kv[0] or 0)))
    return f"device busy {w['busy']:.0f}% of all cores; clock {clock}; {temp}; cooling {dict(w['cooling'])}{ceil}"


opens = []  # (t0, t1, label) on the device's uptime
for path in sorted(glob.glob(os.path.join(outdir, "refstart-*.json"))):
    r = json.load(open(path))
    for view, t in (r.get("tiers") or {}).items():
        p = (t or {}).get("page") or {}
        u = p.get("usable") or {}
        if "nav_ms" not in p or u.get("interactive_ms") is None or offset is None:
            continue
        o = p["origin_epoch_ms"] / 1000
        t0, t1 = o + p["nav_ms"] / 1000 - offset, o + u["interactive_ms"] / 1000 - offset
        opens.append((t0, t1, f"run {r.get('run')} {view} open"))
        if only and view != only:
            continue
        print(f"run {r.get('run')} {view}: opens in {u['interactive_ms'] - p['nav_ms']:.0f} ms")
        w = window(t0, t1)
        if not w:
            print("  not in the trace")
            continue
        print(f"  traced {w['dt']:.2f} s; {describe(w)}")
        for k, v in sorted(w["shares"].items(), key=lambda kv: -kv[1]):
            if v >= 1:
                print(f"  {v:6.1f}% of a core  {k.replace('_disable_art_image_', '')}")

if cpu_temp_prop:
    print(f"sys.cpu_temp over the trace: {sorted(cpu_temp_prop)}")

if timeline and samples:
    print(f"timeline, {BIN:.0f} s bins (device uptime s, label, busy, median clock, clock range, CPU °C, cooling, ceiling):")
    t = samples[0].up
    while t < samples[-1].up:
        w = window(t, t + BIN)
        if w:
            label = next((lab for a, b, lab in opens if a < t + BIN and b > t), "")
            fs = [f for f in w["freqs"].elements() if f]
            temp = f"{max(w['cpu_c']):.1f}" if w["cpu_c"] else "?"
            ceil = ",".join(str(c) for (c, g) in w["ceilings"]) or "-"
            print(f"  {t:9.1f}  {label:28} {w['busy']:4.0f}%  {statistics.median(fs) if fs else 0:>8.0f}  "
                  f"{min(fs) if fs else 0}-{max(fs) if fs else 0}  {temp}  {dict(w['cooling'])}  {ceil}")
        t += BIN
