"""The Glassworks perf rubric (MIT). docs/perf.md explains each row and links its source.

Bands follow Core Web Vitals: good, needs-improvement, poor. Thresholds come from published
guidelines and do not depend on any frame's own numbers. Change them only on purpose, with the
docs and test/test_grade.py in the same PR.
"""
RUBRIC_VERSION = 1

GOOD, NI, POOR = "good", "needs-improvement", "poor"
ORDER = {GOOD: 0, NI: 1, POOR: 2}

ONE_FRAME_MS = 1000 / 60
# Frame times are snapped to whole vsyncs; 0.5 ms covers displays that run slightly under 60 Hz.
VSYNC_SLACK_MS = 0.5

# metric id -> (good if value <= good_max, poor if value > poor_above or >= poor_at)
METRICS = {
    "frame_p95_ms": {"good_max": ONE_FRAME_MS + VSYNC_SLACK_MS, "poor_above": 2 * ONE_FRAME_MS + VSYNC_SLACK_MS,
                     "source": "web.dev RAIL (16 ms frame budget); Android vitals slow frame (> 16 ms)"},
    "frame_jank_pct": {"good_max": 5, "poor_above": 50,
                       "source": "good: same as p95 within budget (derived); poor: Play Console 'excessive slow frames' (> 50% over 16 ms)"},
    "frame_frozen_pct": {"good_max": 0, "poor_above": 0.1,
                         "source": "Android vitals frozen frame (> 700 ms); Play Console 'excessive frozen frames' (> 0.1%)"},
    "load_ms": {"good_max": 2500, "poor_above": 4000, "source": "Core Web Vitals LCP (good <= 2.5 s, poor > 4 s)"},
    "inp_ms": {"good_max": 200, "poor_above": 500, "source": "Core Web Vitals INP (good <= 200 ms, poor > 500 ms)"},
    "cold_start_ms": {"good_max": 2500, "poor_at": 5000,
                      "source": "poor: Android vitals excessive cold start (>= 5 s, TTID); good: project choice (same 2.5 s as LCP)"},
}

# Components of the headline grade; a component's band is the worst band of its metrics.
COMPONENTS = {
    "frames": ["frame_p95_ms", "frame_jank_pct", "frame_frozen_pct"],
    "load": ["load_ms"],
    "input": ["inp_ms"],
    "start": ["cold_start_ms"],
}

# Not in the headline grade: no published standard, so these bands are project choices.
JS_BURST = {"good_max": ONE_FRAME_MS, "poor_above": 50,
            "source": "project choice: one state burst fits in a frame (16.7 ms); poor past RAIL's 50 ms input budget"}


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
    budget: app_pss_max_pct_of_ram, device_available_min_pct (tools/perf/budgets.json)."""
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
    oks = [c["ok"] for c in checks.values() if c["ok"] is not None]
    return {"budget": budget, "checks": checks, "ok": all(oks) if oks else None}
