"""The Glassworks perf rubric (MIT). docs/perf.md explains each row and links its source.

Bands follow Core Web Vitals: good, needs-improvement, poor. Thresholds come from published
guidelines and do not depend on any frame's own numbers. Change them only on purpose, with the
docs and test/test_grade.py in the same PR.
"""
import json
import os

with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "page", "rubric.json")) as _f:
    RUBRIC = json.load(_f)

RUBRIC_VERSION = RUBRIC["version"]
GOOD, NI, POOR = "good", "needs-improvement", "poor"
ORDER = {GOOD: 0, NI: 1, POOR: 2}

# metric id -> (good if value <= good_max, poor if value > poor_above or >= poor_at)
METRICS = RUBRIC["metrics"]

# Components of the headline grade; a component's band is the worst band of its metrics.
COMPONENTS = {
    "frames": ["frame_p95_ms", "frame_jank_pct", "frame_frozen_pct"],
    "load": ["load_ms"],
    "input": ["inp_ms"],
    "start": ["cold_start_ms"],
}

# Not in the headline grade: no published standard, so the band is a project choice.
JS_BURST = RUBRIC["js_burst_ms"]


def band(value, rule):
    if value is None:
        return None
    if "poor_at" in rule and value >= rule["poor_at"]:
        return POOR
    if "poor_above" in rule and value > rule["poor_above"]:
        return POOR
    return GOOD if value <= rule["good_max"] else NI


def worst(bands):
    bands = [b for b in bands if b]
    return max(bands, key=ORDER.get) if bands else None


def frame_band(stats):
    """Band of one ladder step (or the reference load) from its frame stats."""
    if not stats or not stats.get("n"):
        return None
    return worst(band(stats.get(k), METRICS["frame_" + k]) for k in ("p95_ms", "jank_pct", "frozen_pct"))


def capacity(steps, accept):
    """Largest element count reached before the first step whose band is not in `accept`.
    steps: [(n, stats)] in ladder order."""
    best = 0
    for n, stats in steps:
        if frame_band(stats) not in accept:
            break
        best = n
    return best


def letter(component_bands):
    """A: all good. B: no poor, one needs-improvement. C: no poor, more. D: one poor. E: two or more poor."""
    bs = list(component_bands)
    poor = bs.count(POOR)
    ni = bs.count(NI)
    if poor >= 2:
        return "E"
    if poor == 1:
        return "D"
    if ni >= 2:
        return "C"
    if ni == 1:
        return "B"
    return "A"


def headline(values):
    """values: metric id -> number or None. Returns the grade, each component's band, and what is missing."""
    metric_bands = {k: band(values.get(k), rule) for k, rule in METRICS.items()}
    comps = {c: worst(metric_bands[m] for m in ms) for c, ms in COMPONENTS.items()}
    missing = [c for c, b in comps.items() if b is None]
    return {
        "rubric_version": RUBRIC_VERSION,
        "grade": letter(b for b in comps.values() if b) if len(missing) < len(comps) else None,
        "provisional": bool(missing),
        "missing": missing,
        "components": comps,
        "metrics": {k: {"value": values.get(k), "band": metric_bands[k]} for k in METRICS},
    }


def memory_budget(memory, budget):
    """#68-style budget check. memory: app_pss_peak_kb, mem_total_kb, mem_available_min_kb.
    budget: app_pss_max_pct_of_ram, device_available_min_pct (tools/perf/budgets.json).
    ok is None unless every budgeted value was measured."""
    total = memory.get("mem_total_kb")
    if not total:
        return None
    pss = memory.get("app_pss_peak_kb")
    avail = memory.get("mem_available_min_kb")
    pss_max = total * budget["app_pss_max_pct_of_ram"] / 100
    avail_min = total * budget["device_available_min_pct"] / 100
    checks = {
        "app_pss_peak": {"value_kb": pss, "budget_kb": round(pss_max), "ok": None if pss is None else pss <= pss_max},
        "device_available_min": {"value_kb": avail, "budget_kb": round(avail_min),
                                 "ok": None if avail is None else avail >= avail_min},
    }
    oks = [c["ok"] for c in checks.values()]
    ok = False if False in oks else (None if None in oks else True)
    return {"budget": budget, "checks": checks, "ok": ok}


def frame_budget(budgets, frame_id):
    """The default budget with the frame's override on top, so an override may name one key."""
    return dict(budgets["default"], **budgets.get("frames", {}).get(frame_id, {}))
