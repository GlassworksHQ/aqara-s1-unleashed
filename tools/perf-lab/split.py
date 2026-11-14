"""Split a Gecko profile's CPU over one Clarity view open (MIT). Reads files only.

The window runs from the harness's navigation (`nav_ms`) to usable (`interactive_ms`), from the
view's `refdash` line in the harness output. Prints each thread's CPU in the window, then the busiest
content main thread's split by category and subcategory, before and after the first screen was
drawn, and its hottest leaf frames. With --brief, one line. Used by profile-view.sh (#244).

  python3 -I tools/perf-lab/split.py PROFILE.json HARNESS.stdout VIEW [--brief] [--top N]
"""
import collections
import json
import sys

prof_path, out_path, view = sys.argv[1:4]
brief = "--brief" in sys.argv
top = int(sys.argv[sys.argv.index("--top") + 1]) if "--top" in sys.argv else 15


def refdash(path, view):
    for raw in open(path, errors="replace"):
        if "console.log: " not in raw or "GWPERF {" not in raw:
            continue
        try:
            d = json.loads(json.loads(raw.split("console.log: ", 1)[1])[len("GWPERF "):])
        except (ValueError, IndexError):
            continue
        if d.get("t") == "refdash" and d.get("tier") == view:
            return d
    sys.exit(f"no refdash line for {view} in {path}")


line = refdash(out_path, view)
u = line["usable"]
t0 = line["origin_epoch_ms"] + line["nav_ms"]
td = line["origin_epoch_ms"] + u["rendered_tick_ms"]
t1 = line["origin_epoch_ms"] + u["interactive_ms"]

# threadCPUDelta's unit differs by platform (sampleUnits in each process's meta): µs on macOS,
# ns on Linux and Android, CPU cycles on Windows, which can't be turned into time.
MS_PER_UNIT = {"µs": 1e-3, "us": 1e-3, "ns": 1e-6, "ms": 1.0}

P = json.load(open(prof_path))
cats = P["meta"]["categories"]
rows, main = [], None
for pr in [P] + P.get("processes", []):
    base = pr["meta"]["startTime"]
    unit = pr["meta"].get("sampleUnits", {}).get("threadCPUDelta", "?")
    for th in pr["threads"]:
        sch = th["samples"]["schema"]
        ci = sch.get("threadCPUDelta")
        # Rows drop trailing fields they have no value for, so a CPU column can be missing per row.
        data = [(base + d[sch["time"]], d[sch["stack"]], d[ci] if ci is not None and ci < len(d) else None)
                for d in th["samples"]["data"] if t0 <= base + d[sch["time"]] <= t1]
        cpu = sum(d for _, _, d in data if d) * MS_PER_UNIT.get(unit, float("nan"))
        rows.append((cpu, th["name"], th.get("processType"), th.get("pid"), len(data), unit))
        if th["name"] == "GeckoMain" and th.get("processType") == "tab" and data and (main is None or cpu > main[0]):
            main = (cpu, th, data, unit)

_, th, data, unit = main
if unit not in MS_PER_UNIT:
    sys.exit(f"threadCPUDelta is in '{unit}', which this script can't turn into time")
missing = sum(1 for _, _, d in data if d is None)
by_samples = missing > 0
if by_samples:
    print(f"warning: {missing} of {len(data)} main-thread samples have no CPU delta; "
          "splitting by sample count, not CPU time", file=sys.stderr)
st, ft, strs = th["stackTable"], th["frameTable"], th["stringTable"]
ss, fs = st["schema"], ft["schema"]


def category(stack):
    """The first frame from the leaf up that has a category (frame rows drop trailing nulls)."""
    while stack is not None:
        r = st["data"][stack]
        f = ft["data"][r[ss["frame"]]]
        get = lambda i: f[i] if i < len(f) else None  # noqa: E731
        c, sc = get(fs["category"]), get(fs["subcategory"])
        if c is not None:
            return cats[c]["name"], cats[c]["subcategories"][sc or 0]
        stack = r[ss["prefix"]]
    return "Other", ""


def leaf(stack):
    loc = ft["data"][st["data"][stack][ss["frame"]]][fs["location"]]
    return strs[loc] if isinstance(loc, int) else str(loc)


split, sub, leaves, phase = (collections.Counter() for _ in range(4))
for t, stack, d in data:
    w = 1 if by_samples else d * MS_PER_UNIT[unit]
    c, s = category(stack) if stack is not None else ("Other", "")
    split[c] += w
    sub[(c, s)] += w
    leaves[leaf(stack) if stack is not None else "(none)"] += w
    phase[("until drawn" if t <= td else "after drawn", c)] += w
tot = sum(split.values())
pct = lambda v: 100 * v / tot  # noqa: E731
slow_js = sum(v for (c, s), v in sub.items() if c == "JavaScript" and ("nterpreter" in s or "Compile" in s))

if brief:
    print(f"main_cpu={'n/a' if by_samples else f'{tot:.0f}ms'} js={pct(split['JavaScript']):.0f}% interp+compile={pct(slow_js):.0f}% "
          f"dom={pct(split['DOM']):.0f}% layout={pct(split['Layout']):.0f}% graphics={pct(split['Graphics']):.0f}%")
    sys.exit()
print(f"{view}: drawn {u['rendered_tick_ms'] - line['nav_ms']:.0f} ms, usable {u['interactive_ms'] - line['nav_ms']:.0f} ms "
      f"after the navigation; busy_ms {u['busy_ms']}")
print(f"threads in the window ({t1 - t0:.0f} ms), CPU in ms:")
for cpu, name, ptype, pid, n, unit_ in sorted(rows, reverse=True)[:8]:
    if n:
        print(f"  {ptype or '?':8} {pid:>7} {name:20} {cpu:10.1f}")
print(f"content main thread: {len(data)} samples" if by_samples else f"content main thread: {tot:.0f} ms of CPU")
for k, v in split.most_common():
    print(f"  {k:14} {pct(v):5.1f}%")
print("by subcategory:")
for (c, s), v in sub.most_common(12):
    print(f"  {c:12} {s:30} {pct(v):5.1f}%")
print("until and after the first screen was drawn:")
for (p, c), v in sorted(phase.items()):
    print(f"  {p:12} {c:14} {pct(v):5.1f}%")
print("hottest leaf frames:")
for k, v in leaves.most_common(top):
    print(f"  {pct(v):5.1f}%  {k[:120]}")
