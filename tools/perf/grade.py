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

TIERS = ["light", "typical", "heavy"]
TASKS = ["tap", "scroll", "updates", "load"]

# What a page needs to pass at each level: {task: accepted bands}. Frames (scroll, updates) are
# smooth at 60 fps (band good: p95 within one frame plus slack, jank <= 5%, no freeze) and fluid at
# 30 fps (not poor). A tap is good at INP <= 200 ms; a view may load in up to 4 s (needs improvement).
LEVELS = {
    "smooth": {"tap": {GOOD}, "scroll": {GOOD}, "updates": {GOOD}, "load": {GOOD, NI}},
    "fluid": {"tap": {GOOD}, "scroll": {GOOD, NI}, "updates": {GOOD, NI}, "load": {GOOD, NI}},
}
LEVEL_RANK = {"smooth": 2, "fluid": 1, None: 0}

# The letters, best first: (letter, plain meaning, {page: level it needs}). The first letter whose
# every requirement holds is the grade; none holds -> E. The bands underneath are the cited ones
# (rubric.json); the pages, the levels and this table are project choices, set before any frame was
# graded under them (docs/perf.md, "The grade").
LETTERS = [
    ("A", "a good wall panel even for large dashboards", {"heavy": "smooth"}),
    ("B", "a good wall panel for typical dashboards; large ones stay fluid", {"typical": "smooth", "heavy": "fluid"}),
    ("C", "a good wall panel for typical dashboards", {"typical": "fluid"}),
    ("D", "a good wall panel for light dashboards", {"light": "fluid"}),
]
E_MEANING = "not a good wall panel, even for light dashboards"
MEANINGS = dict([(l, m) for l, m, _ in LETTERS] + [("E", E_MEANING)])
# A poor start (process start to engine ready >= 5 s, Android vitals' bound) caps the letter here.
START_CAP = "C"

# Not in the grade: no published standard, so the band is a project choice.
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


def tier_bands(tier):
    """The band of each task on one page (None when it wasn't measured). Frames bands come from the
    caller as the median of each run's band."""
    tier = tier or {}
    return {
        "tap": band(tier.get("inp_ms"), METRICS["inp_ms"]),
        "scroll": tier.get("scroll_band"),
        "updates": tier.get("updates_band"),
        "load": band(tier.get("load_ms"), METRICS["load_ms"]),
    }


def page_level(bands):
    """"smooth", "fluid" or None: the best level whose every task passes."""
    for level in ("smooth", "fluid"):
        if all(bands[task] in ok for task, ok in LEVELS[level].items()):
            return level
    return None


def limits(bands, level):
    """The tasks that keep a page from `level`."""
    return [task for task, ok in LEVELS[level].items() if bands[task] not in ok]


def grade(tiers, start_ms):
    """tiers: {light|typical|heavy: {"inp_ms", "load_ms", "scroll_band", "updates_band"}}; start_ms:
    process start to engine ready. Returns the letter, its plain meaning, each page's bands and
    level, and what is missing. A missing measurement fails whatever needs it, and the grade is then
    provisional."""
    bands = {t: tier_bands((tiers or {}).get(t)) for t in TIERS}
    levels = {t: page_level(bands[t]) for t in TIERS}
    start = band(start_ms, METRICS["start_ms"])
    missing = [f"{t}.{m}" for t in TIERS for m, b in bands[t].items() if b is None] + ([] if start else ["start"])
    letter = next((l for l, _, needs in LETTERS
                   if all(LEVEL_RANK[levels[t]] >= LEVEL_RANK[need] for t, need in needs.items())), "E")
    capped = start == POOR and letter < START_CAP
    if capped:
        letter = START_CAP
    return {
        "rubric_version": RUBRIC_VERSION,
        "grade": letter,
        "meaning": MEANINGS[letter],
        "capped_by_start": capped,
        "provisional": bool(missing),
        "missing": missing,
        "bands": bands,
        "levels": levels,
        "start": {"value": start_ms, "band": start},
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
