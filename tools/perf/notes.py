"""Clarity's notes (MIT): a short paragraph on what is good on a frame and what holds it back (#201).

Built from a finished report (report.json) by fixed rules: the same report always gives the same
text. At most four sentences (five when the grade is provisional): the best page's strengths, the
three worst limits with one number each, the likely cause, memory. The report body has the rest.
docs/perf.md, "The notes", lists each rule and its evidence.
"""
import statistics

import grade as G

PAGES = G.TIERS
GOOD = {"good", "premium"}
FLUID = {"fluid"}
NOT_A_LIMIT = GOOD | FLUID | {"none", None}
MAX_LIMITS = 3

# The fluid bars each limit is measured against (rubric.json): how far past it a task is ranks it.
_LEVEL = {lv["level"]: lv for lv in G.RULES["scroll_levels"]}
_UPD = {lv["level"]: lv for lv in G.RULES["update_levels"]}
_CAM = {lv["level"]: lv for lv in G.RULES["camera_levels"]}
SCROLL_FPS = _LEVEL["fluid"]["fps_min"]
SCROLL_P95_MS = _LEVEL["fluid"]["p95_max_ms"]
UPDATE_MS = _UPD["fluid"]["latency_p95_max_ms"]
TAP_MS = G.METRICS["inp_ms"]["good_max"]          # fluid accepts a good tap only
OPEN_MS = G.METRICS["load_ms"]["poor_above"]       # fluid accepts good and needs-improvement
CAM_FIRST_MS = _CAM["fluid"]["first_frame_max_ms"]
CAM_SHOWN_PCT = _CAM["fluid"]["shown_min_pct"]

# A camera page starves (the player asks for video late) when a stream's segments arrive this far
# apart, against 1 s segments (camera.py SEGMENT_S), while each fetch is quicker than FAST_FETCH_MS.
STARVE_GAP_MS = 2000
FAST_FETCH_MS = 500
# Segments this slow to fetch put the link in question instead.
SLOW_FETCH_MS = 1000
# The app's CPU on the typical reference dashboard, untouched, worth naming (a share of one core).
IDLE_CPU_PCT = 25
# The fake sends a live change at most this late before the grade is provisional (report.py).
STREAM_LATE_MS = 200


def _med(x):
    return x["median"] if isinstance(x, dict) else x


def _time(ms):
    """0.7 s, 6.6 s, 12 s; under half a second in tens of ms."""
    if ms < 500:
        return f"{round(ms, -1):.0f} ms"
    return f"{ms / 1000:.1f} s" if ms < 10_000 else f"{ms / 1000:.0f} s"


def _join(xs):
    xs = list(xs)
    return xs[0] if len(xs) == 1 else ", ".join(xs[:-1]) + " and " + xs[-1]


def _cap(s):
    return s[:1].upper() + s[1:]


def _bands(rep):
    return (rep.get("grade") or {}).get("bands") or {}


def _tiers(rep):
    return (((rep.get("graded") or {}).get("reference") or {}).get("tiers")) or {}


def _camera(t):
    runs = (t or {}).get("camera_runs") or []
    ff = [r["first_frame_ms"] for r in runs if r.get("first_frame_ms") is not None]
    shown = [r["shown_pct"] for r in runs if r.get("shown_pct") is not None]
    return (statistics.median(ff) if ff else None), (statistics.median(shown) if shown else None)


def _limit(task, t):
    """(how far past its fluid bar, the phrase with one number), or None without a value."""
    t = t or {}
    if task == "scroll" and _med((t.get("scroll") or {}).get("fps")):
        fps, p95 = _med(t["scroll"]["fps"]), _med(t["scroll"].get("p95_ms"))
        by_fps, by_p95 = SCROLL_FPS / fps, (p95 or 0) / SCROLL_P95_MS
        if by_fps >= 1 or not p95:
            return max(by_fps, by_p95), f"scrolls at {fps:.0f} fps"
        # Enough frames on average, but uneven: say the slow frames, which is what failed.
        return by_p95, f"scrolls unevenly (slowest frames {_time(p95)})"
    if task == "load" and _med(t.get("load_ms")) is not None:
        v = _med(t["load_ms"])
        return v / OPEN_MS, f"takes {_time(v)} to open"
    if task == "updates" and _med(t.get("latency_p95_ms")) is not None:
        v = _med(t["latency_p95_ms"])
        return v / UPDATE_MS, f"takes {_time(v)} to show a change"
    if task == "tap" and _med(t.get("inp_ms")) is not None:
        v = _med(t["inp_ms"])
        return v / TAP_MS, f"takes {_time(v)} to answer a tap"
    if task == "camera":
        ff, shown = _camera(t)
        if shown is not None and shown < CAM_SHOWN_PCT:
            return CAM_SHOWN_PCT / max(shown, 1), f"shows {shown:.0f}% of its camera's frames"
        if ff is not None:
            return ff / CAM_FIRST_MS, f"takes {_time(ff)} to show its camera"
    return None


def best_page(rep):
    """The heaviest page that reaches a level; without one, the page with the most good or fluid tasks."""
    levels = (rep.get("grade") or {}).get("levels") or {}
    reached = [p for p in PAGES if levels.get(p)]
    if reached:
        return reached[-1], True
    bands = _bands(rep)
    count = {p: sum(1 for t in G.TASKS if (bands.get(p) or {}).get(t) in GOOD | FLUID) for p in PAGES}
    p = max(PAGES, key=lambda p: (count[p], -PAGES.index(p)))
    return (p, False) if count[p] else (None, False)


def good_sentence(rep):
    page, reached = best_page(rep)
    if not page:
        return None
    b = _bands(rep).get(page) or {}
    word = {"tap": "taps", "updates": "live updates", "load": "opening a view", "camera": "the cameras"}
    word["scroll"] = "scrolling"
    quick = [word[t] for t in ("tap", "updates", "load", "camera") if b.get(t) in GOOD]
    parts = []
    if quick:
        parts.append(f"{_join(quick)} {'is' if quick == ['opening a view'] else 'are'} quick")
    if b.get("scroll") in GOOD:
        parts.append("scrolling is smooth")
    fluid = [word[t] for t in ("scroll", "updates", "camera") if b.get(t) in FLUID]
    if fluid:
        parts.append(f"{_join(fluid)} {'is' if fluid == ['scrolling'] else 'are'} fluid")
    lead = f"{_cap(page)} dashboards {'feel good' if reached else 'come closest'}"
    return f"{lead}: {', '.join(parts[:-1]) + ', and ' + parts[-1] if len(parts) > 1 else parts[0]}." if parts else f"{lead}."


def limits_sentence(rep):
    """The worst limits (furthest past their fluid bar on any page), each quoted once, on the
    lightest page it fails on. A slow restart only when it caps the letter."""
    bands, tiers = _bands(rep), _tiers(rep)
    found = []
    for t in G.TASKS:
        bad = [p for p in PAGES if (bands.get(p) or {}).get(t) not in NOT_A_LIMIT]
        scored = [(p, _limit(t, tiers.get(p))) for p in bad]
        scored = [(p, s) for p, s in scored if s]
        if scored:
            worst = max(s[0] for _, s in scored)
            page, (_, phrase) = scored[0]
            found.append((worst, PAGES.index(page), G.TASKS.index(t), page, phrase))
    found.sort(key=lambda f: (-f[0], f[1], f[2]))
    g = rep.get("grade") or {}
    restart = g.get("capped_by_start") and (g.get("start") or {}).get("value")
    chosen = found[:MAX_LIMITS - (1 if restart else 0)]
    if not chosen and not restart:
        return None
    # Each page once, lightest first: "typical scrolls at 25 fps and takes 6.6 s to open".
    by_page = []
    for p in PAGES:
        phrases = [f[4] for f in sorted(chosen, key=lambda f: f[2]) if f[3] == p]
        if phrases:
            by_page.append(f"{p} {_join(phrases)}")
    if restart:
        by_page.insert(0, f"a restart takes {_time(restart)}")
    best, _ = best_page(rep)
    lead = "Bigger dashboards slow down" if best and all(PAGES.index(f[3]) > PAGES.index(best) for f in chosen) \
        else "It slows down"
    clauses = by_page[:-1] + ["and " + by_page[-1]] if len(by_page) > 2 else by_page
    return f"{lead}: {(', ' if len(by_page) > 2 else ', and ').join(clauses) if len(by_page) > 1 else by_page[0]}."


def _starving_cameras(rep):
    """Camera pages whose streams get video late while the decoder keeps up: (page, hardware)."""
    out = []
    for p in PAGES:
        if (_bands(rep).get(p) or {}).get("camera") in NOT_A_LIMIT:
            continue
        gaps, fetch, decoded, dropped, codecs = [], [], 0, 0, set()
        for r in _tiers(rep).get(p, {}).get("camera_runs") or []:
            seg = ((r.get("fetches") or {}).get("segment")) or {}
            if seg.get("arrival_gap_max_ms") is not None:
                gaps.append(seg["arrival_gap_max_ms"])
            if (seg.get("total_ms") or {}).get("p95") is not None:
                fetch.append(seg["total_ms"]["p95"])
            for s in r.get("detail") or []:
                decoded += s.get("decoded") or 0
                dropped += s.get("dropped") or 0
            codecs |= set(((r.get("media") or {}).get("codecs")) or [])
        if gaps and fetch and decoded and max(gaps) >= STARVE_GAP_MS and max(fetch) < FAST_FETCH_MS \
                and dropped <= 0.01 * decoded:
            out.append((p, bool(codecs) and not any(c.startswith(("c2.android.", "OMX.google.")) for c in codecs)))
    return out


def _slow_fetch(rep):
    for p in PAGES:
        if (_bands(rep).get(p) or {}).get("camera") in NOT_A_LIMIT:
            continue
        p95 = [((r.get("fetches") or {}).get("segment") or {}).get("total_ms", {}).get("p95")
               for r in _tiers(rep).get(p, {}).get("camera_runs") or []]
        if any(x is not None and x >= SLOW_FETCH_MS for x in p95):
            return True
    return False


def cause_sentence(rep):
    """One likely limit, with at most two pieces of evidence, or None without evidence."""
    bands, tiers = _bands(rep), _tiers(rep)
    slow_scroll = [p for p in PAGES if (bands.get(p) or {}).get("scroll") not in NOT_A_LIMIT]
    main = [p for p in slow_scroll if "main-thread" in (tiers.get(p, {}).get("scroll_limited_by") or [])]
    comp = [p for p in slow_scroll if "compositor" in (tiers.get(p, {}).get("scroll_limited_by") or [])]
    starving = _starving_cameras(rep)
    if main or starving:
        busy = None
        idle = _med((((rep.get("ram") or {}).get("reference_idle")) or {}).get("cpu_app_pct_of_core"))
        if idle is not None and idle >= IDLE_CPU_PCT:
            cam = (bands.get("typical") or {}).get("camera") not in ("none", None)
            busy = f"it stays busy ({idle:.0f}% of a core on typical {'with a camera playing' if cam else 'with nothing touched'})"
        if starving:
            hw = "hardware " if any(h for _, h in starving) else ""
            seen, link = f"cameras get video late even though the {hw}decoder keeps up", "so"
        else:
            seen, link = "the page draws too few frames", "and"
        return "The likely limit is the browser's main thread: " + (f"{busy}, {link} {seen}" if busy else seen) + "."
    if comp:
        return "The likely limit is the GPU: the screen drops frames the page drew."
    if _slow_fetch(rep):
        return "The likely limit is the link: camera video takes a second or more to fetch."
    late = (rep.get("fake_ha") or {}).get("stream_late_max_ms")
    if late is not None and late > STREAM_LATE_MS:
        return "Part of the update time is the test's own: its fake Home Assistant sent changes late."
    return None


def memory_sentence(rep):
    checks = ((((rep.get("ram") or {}).get("budget")) or {}).get("checks")) or {}
    peak, avail = checks.get("app_pss_peak") or {}, checks.get("device_available_min") or {}
    if peak.get("value_kb") is None:
        return None
    mb = lambda kb: f"{kb / 1024:.0f}"  # noqa: E731
    if peak.get("ok") is False:
        return f"Memory is a limit: the app peaks at {mb(peak['value_kb'])} MB, over its {mb(peak['budget_kb'])} MB budget."
    if avail.get("ok") is False:
        return f"Memory is a limit: free memory falls to {mb(avail['value_kb'])} MB, under the {mb(avail['budget_kb'])} MB the frame keeps."
    if peak.get("ok"):
        return f"Memory isn't the limit ({mb(peak['value_kb'])} of {mb(peak['budget_kb'])} MB)."
    return None


def provisional_sentence(rep):
    """One plain clause for why the grade is provisional; the report lists the reasons in full.
    From the run's protocols and what is missing, not from the reasons' wording."""
    g = rep.get("grade") or {}
    if not g.get("provisional"):
        return None
    protocols = (((rep.get("graded") or {}).get("reference") or {}).get("protocols")) or []
    other = [p for p in protocols if p != G.PROTOCOL_VERSION]
    missing = list(g.get("missing") or [])
    parts = []
    if other:
        older = all(isinstance(p, int) and p < G.PROTOCOL_VERSION for p in other)
        clause = f"measured with {'an older' if older else 'another'} version of the test"
        cams = [m for m in missing if m.endswith(".camera")]
        if older and cams:
            # Cameras came with protocol 5 (#174): an older run has none to measure.
            clause += ", without cameras"
            missing = [m for m in missing if m not in cams]
        parts.append(clause)
    # Everything else: each other reason, and each task not measured.
    n = len(g.get("provisional_reasons") or []) - (1 if other else 0) + len(missing)
    if n > 0:
        what = "1 thing wasn't" if n == 1 else f"{n} things weren't"
        parts.append(f"{'and ' if parts else ''}{what} measured fully")
    return "Provisional: " + (", ".join(parts) if parts else "not everything was measured fully") + "."


def notes(rep):
    """The paragraph and its parts: good, limits, causes (the cause and memory sentences), text."""
    g = rep.get("grade") or {}
    if g.get("refused"):
        return {"good": None, "limits": None, "causes": [], "rubric": g.get("rubric_version"),
                "text": f"Not graded: {g['refused']}."}
    good, limits = good_sentence(rep), limits_sentence(rep)
    causes = [s for s in (cause_sentence(rep), memory_sentence(rep)) if s]
    parts = [s for s in [good, limits] + causes + [provisional_sentence(rep)] if s]
    return {"good": good, "limits": limits, "causes": causes, "text": " ".join(parts), "rubric": g.get("rubric_version")}
