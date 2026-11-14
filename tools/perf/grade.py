"""Clarity's rubric (MIT): the grade of the Glassworks perf test. docs/perf.md explains each row and links its source.

Bands follow Core Web Vitals: good, needs-improvement, poor. Thresholds come from published
guidelines and do not depend on any frame's own numbers. Change them only on purpose, with the
docs and test/test_grade.py in the same PR.
"""
import json
import os

with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "page", "rubric.json")) as _f:
    RUBRIC = json.load(_f)

RUBRIC_VERSION = RUBRIC["version"]
# Clarity's version (v1 from 2026-11-14). A report or summary without it is pre-release: re-measure.
CLARITY = RUBRIC["clarity"]
PRE_RELEASE = "measured with a pre-release version of Clarity"
NAME = RUBRIC["name"]          # "Clarity": the test's name, kept in rubric.json only
GOOD, NI, POOR = "good", "needs-improvement", "poor"
ORDER = {GOOD: 0, NI: 1, POOR: 2}

# metric id -> (good if value <= good_max, poor if value > poor_above or >= poor_at)
METRICS = RUBRIC["metrics"]

# The letter rules live in rubric.json ("grade"), so grade.ts (the site) reads the same ones.
RULES = RUBRIC["grade"]
PROTOCOL_VERSION = RUBRIC["protocol_version"]
TIERS = RULES["pages"]
TASKS = list(RULES["tasks"])

# Scroll has its own levels, judged per run (rubric v5): premium is the 60 fps rule (frames band
# good), good is an average of >= 50 fps with p95 <= 50 ms, fluid >= 30 fps with p95 <= 67 ms.
SCROLL_LEVELS = RULES["scroll_levels"]
# Live updates too (rubric v5): good when 95% of changes reach the screen within 100 ms, fluid within
# 200 ms; premium adds the 60 fps bar on the frames that had a change to draw.
UPDATE_LEVELS = RULES["update_levels"]
BELOW = RULES["below"]
CAMERA_LEVELS = RULES["camera_levels"]
CAMERA_PAGES = RULES["camera_pages"]
NO_CAMERA = "none"            # a page without streams: every level accepts it
TASK_ORDER = [lv["level"] for lv in SCROLL_LEVELS] + [BELOW]
SCROLL_ORDER = TASK_ORDER

# What a page needs to pass at each level: {task: accepted bands, or task levels}, best level first.
# A tap is good at INP <= 200 ms; a view may load in up to 4 s (needs improvement).
LEVELS = {lv["level"]: {task: set(ok) for task, ok in lv["accepts"].items()} for lv in RULES["levels"]}
LEVEL_ORDER = [lv["level"] for lv in RULES["levels"]]
LEVEL_RANK = dict([(lv, len(LEVEL_ORDER) - i) for i, lv in enumerate(LEVEL_ORDER)] + [(None, 0)])

# The letters, best first: (letter, plain meaning, {page: level it needs}). The bands underneath are
# the cited ones; the pages, the levels and this table are project choices (docs/perf.md, "The grade").
LETTERS = [(l["letter"], l["meaning"], l["needs"]) for l in RULES["letters"]]
E_MEANING = RULES["fallback"]["meaning"]
MEANINGS = dict([(l, m) for l, m, _ in LETTERS] + [(RULES["fallback"]["letter"], E_MEANING)])
START_CAP = RULES["start_cap"]["letter"]
# Letters rank by their place in the table (A+ first), the fallback last; never by string order.
LETTER_ORDER = [l for l, _, _ in LETTERS] + [RULES["fallback"]["letter"]]

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


def scroll_level(stats):
    """One run's scroll level: the first of rubric.json's scroll_levels whose rule holds, or "slow"
    (measured, below fluid); None when it wasn't measured."""
    if stats and stats.get("timed_out"):
        return BELOW                    # the view didn't finish within the runner's deadline: measured, failed
    if not stats or not stats.get("n"):
        return None
    for lv in SCROLL_LEVELS:
        if "frames_band" in lv:
            if frame_band(stats) in lv["frames_band"]:
                return lv["level"]
        elif (stats.get("fps") or 0) >= lv["fps_min"] and stats.get("p95_ms") is not None and stats["p95_ms"] <= lv["p95_max_ms"]:
            return lv["level"]
    return BELOW


def update_level(run):
    """One run's live-updates level from its change-to-screen p95 (and, for premium, the frames that
    had a change to draw), or "slow"; None when it wasn't measured."""
    if run and run.get("timed_out"):
        return BELOW
    if not run or not run.get("latency_n") or run.get("latency_p95_ms") is None:
        return None
    for lv in UPDATE_LEVELS:
        if run["latency_p95_ms"] > lv["latency_p95_max_ms"]:
            continue
        if "frames_band" in lv and frame_band(run.get("frames")) not in lv["frames_band"]:
            continue
        return lv["level"]
    return BELOW


def camera_level(run):
    """One run's camera level from its worst stream (#174): first frame, frames shown, the longest gap
    and the gaps over 500 ms added up; "slow" when a stream showed no frame; None when the run has no
    camera record."""
    if not run:
        return None
    if run.get("timed_out"):
        return BELOW
    if run.get("first_frame_ms") is None or run.get("shown_pct") is None:
        return BELOW
    if run.get("stalls_reported") is False:
        # Stalls n/a (no freeze counts, or counts that left part of the window out): not measured,
        # unless the first frame and frames shown already fail every level, as no stalls could lift.
        if not any(run["first_frame_ms"] <= lv["first_frame_max_ms"] and run["shown_pct"] >= lv["shown_min_pct"]
                   for lv in CAMERA_LEVELS):
            return BELOW
        return None
    for lv in CAMERA_LEVELS:
        if run["first_frame_ms"] > lv["first_frame_max_ms"] or run["shown_pct"] < lv["shown_min_pct"]:
            continue
        if "max_gap_max_ms" in lv and (run.get("max_gap_ms") is None or run["max_gap_ms"] > lv["max_gap_max_ms"]):
            continue
        if "stall_total_max_ms" in lv and (run.get("stall_total_ms") is None or run["stall_total_ms"] >= lv["stall_total_max_ms"]):
            continue
        return lv["level"]
    return BELOW


def median_level(levels):
    """The middle level of several runs (the worse of the two middles for an even count)."""
    ls = sorted((l for l in levels if l), key=TASK_ORDER.index)
    return ls[len(ls) // 2] if ls else None


def tier_bands(tier, page=None):
    """The band of each task on one page (None when it wasn't measured). Frames bands come from the
    caller as the median of each run's band. The camera task is `none` on a page without streams."""
    tier = tier or {}
    return {
        "tap": band(tier.get("inp_ms"), METRICS["inp_ms"]),
        "scroll": tier.get("scroll_level"),
        "updates": tier.get("updates_level"),
        "load": band(tier.get("load_ms"), METRICS["load_ms"]),
        "camera": tier.get("camera_level") if page in CAMERA_PAGES else NO_CAMERA,
    }


def page_level(bands):
    """"smooth", "fluid" or None: the best level whose every task passes."""
    for level in LEVEL_ORDER:
        if all(bands[task] in ok for task, ok in LEVELS[level].items()):
            return level
    return None


def limits(bands, level):
    """The tasks that keep a page from `level`."""
    return [task for task, ok in LEVELS[level].items() if bands[task] not in ok]


def grade(tiers, start_ms):
    """tiers: {light|typical|heavy: {"inp_ms", "load_ms", "scroll_level", "updates_level", "camera_level"}};
    start_ms: the restart, process start to the start view usable (protocol 4; engine ready before
    it). Returns the letter, its plain meaning, each page's bands and level, and what is missing. A
    missing measurement fails whatever needs it, and the grade is then provisional."""
    bands = {t: tier_bands((tiers or {}).get(t), t) for t in TIERS}
    levels = {t: page_level(bands[t]) for t in TIERS}
    start = band(start_ms, METRICS["start_ms"])
    missing = [f"{t}.{m}" for t in TIERS for m, b in bands[t].items() if b is None] + ([] if start else ["start"])
    letter = next((l for l, _, needs in LETTERS
                   if all(LEVEL_RANK[levels[t]] >= LEVEL_RANK[need] for t, need in needs.items())),
                  RULES["fallback"]["letter"])
    capped = start == RULES["start_cap"]["band"] and LETTER_ORDER.index(letter) < LETTER_ORDER.index(START_CAP)
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


def median_band(bands):
    """The middle band of several runs (the worse of the two middles for an even count)."""
    bs = sorted((b for b in bands if b), key=ORDER.get)
    if not bs:
        return None
    return bs[len(bs) // 2]


def next_limits(g):
    """What keeps a grade from the next letter up: [{"page", "needs", "tasks"}] for each page below
    the level that letter needs. Empty for the best letter."""
    letters = [l for l, _, _ in LETTERS]
    if g.get("grade") is None or g["grade"] == letters[0]:
        return []
    up = letters[-1] if g["grade"] not in letters else letters[letters.index(g["grade"]) - 1]
    needs = next(n for l, _, n in LETTERS if l == up)
    return [{"page": t, "needs": needs[t], "tasks": limits(g["bands"][t], needs[t])}
            for t in TIERS if t in needs and LEVEL_RANK[g["levels"][t]] < LEVEL_RANK[needs[t]]]


def summary_inputs(summary):
    """A summary's measurements -> grade()'s inputs. Frames bands: each run's band, then the median."""
    tiers = {}
    for t in TIERS:
        p = (summary.get("pages") or {}).get(t)
        if not p:
            continue
        tiers[t] = {"inp_ms": p.get("inp_ms"), "load_ms": p.get("open_ms"),
                    "scroll_level": median_level(scroll_level(r) for r in (p.get("scroll") or {}).get("runs") or []),
                    "updates_level": median_level(update_level(r) for r in (p.get("updates") or {}).get("runs") or []),
                    "camera_level": median_level(camera_level(r) for r in (p.get("camera") or {}).get("runs") or [])}
    return tiers, summary.get("restart_ms")


def protocol_name(v):
    """A protocol version in words; grade.ts says the same."""
    return "unknown" if v is None else str(v)


def grade_summary(summary):
    """The letter from a summary (glassworks.perf-summary/2), as the site computes it with grade.ts.
    status: graded, re-measure (pre-release, or another protocol: these rules don't apply) or not-comparable (the
    measurement says why, e.g. not at the default zoom)."""
    # Not comparable first: it says why there is nothing to grade, whatever the protocol.
    if summary.get("not_comparable"):
        return {"status": "not-comparable", "grade": None, "meaning": None, "why": summary["not_comparable"]}
    if summary.get("clarity") != CLARITY:
        # Before v1 (pre-release protocols 1 to 8): the numbers can repeat, so the marker decides.
        return {"status": "re-measure", "grade": None, "meaning": None,
                "why": f"{PRE_RELEASE} (protocol {protocol_name(summary.get('protocol_version'))}): re-measure"}
    if summary.get("protocol_version") != PROTOCOL_VERSION:
        return {"status": "re-measure", "grade": None, "meaning": None,
                "why": f"measured with protocol {protocol_name(summary.get('protocol_version'))}, "
                       f"these rules are for protocol {PROTOCOL_VERSION}"}
    g = grade(*summary_inputs(summary))
    g["provisional"] = bool(g["missing"] or summary.get("gaps"))
    g["status"] = "graded"
    g["limits"] = next_limits(g)
    # The camera video's path (protocol 7): letters from different transports aren't comparable, so
    # it goes with the letter, and over adb a caveat for heavy's cameras. Never changes the letter.
    t = summary.get("webrtc_transport") or {}
    g["transport"] = t.get("transport")
    g["transport_caveat"] = TRANSPORT_CAVEAT if t.get("possibly_limited") else None
    return g


TRANSPORT_CAVEAT = "heavy's cameras went through the test's adb link and may be limited by it, not by the panel"


def memory_budget(memory, budget):
    """#68-style budget check. memory: app_pss_peak_kb, mem_total_kb, mem_available_min_kb.
    budget: app_pss_max_pct_of_ram, device_available_min_pct (the `ram` of frames/<id>/budgets.json).
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


def frame_budget(default, frame=None):
    """A frame's budgets: its `ram` keys over the default's (so a frame may name one key), and its own
    `panes` (a frame's panes are its own; the default has none)."""
    frame = frame or {}
    return {"ram": dict(default.get("ram") or {}, **(frame.get("ram") or {})),
            "panes": frame.get("panes") or default.get("panes") or {}}


# A pane's share is checked against its budget only with this many samples on each side and a
# tolerance at most this part of the budget (#186). The tolerance is about 2 σ of one sample, not a
# confidence interval, so "ok" means the share is under the budget by this test's noise, no more.
PANE_TOLERANCE_MAX_OF_BUDGET = 0.25
PANE_BUDGET_MIN_SAMPLES = 3


def pane_budgets(panes, budgets):
    """Each measured pane's PSS against its budget (ram_pss_max_kb). ok is None when the pane has no
    budget, wasn't measured, or can't be told (measurable false: too few samples, or too wide a
    tolerance)."""
    out = {}
    for p in panes:
        cap = (budgets.get(p["id"]) or {}).get("ram_pss_max_kb")
        tol = p.get("pss_tolerance_kb")
        n = min(p.get("samples_on") or 0, p.get("samples_off") or 0)
        measurable = None if cap is None else (tol is not None and n >= PANE_BUDGET_MIN_SAMPLES
                                               and tol <= PANE_TOLERANCE_MAX_OF_BUDGET * cap)
        out[p["id"]] = {"value_kb": p.get("pss_kb"), "budget_kb": cap, "tolerance_kb": tol, "measurable": measurable,
                        "ok": None if cap is None or p.get("pss_kb") is None or measurable is False else p["pss_kb"] <= cap}
    return out
