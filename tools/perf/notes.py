"""Clarity's notes (MIT): a short paragraph on what is good on a frame and what holds it back (#201).

Built from a finished report (report.json) by fixed rules: the same report always gives the same
text. At most four sentences (five when the grade is provisional): the best page's strengths, the
three worst limits with one number each, the likely cause, memory. The report body has the rest.
docs/perf.md, "The notes", lists each rule and its evidence.
"""
import statistics

import grade as G

PAGES = G.TIERS
# How each page reads in a sentence. Rubric v10 (#205): typical is measured without its camera
# (typical-nocam, which C grades) and with it (typical, which B grades).
NAME = {"typical-nocam": "typical", "typical": "typical with a camera"}
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
CAM_STALL_MS = _CAM["fluid"]["stall_total_max_ms"]

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


def _camera_options(r):
    """One run's camera numbers that fail the fluid bar, as (how far past it, phrase), with
    grade.camera_level's own tests: a stall at exactly its bar fails (>=), the others past it."""
    if r.get("first_frame_ms") is None:
        # No frame at all ranks above any measured miss; 10x is past every bar's worst real ratio.
        return [(10.0, "has a camera stream that shows no frame")]
    out = []
    if r["first_frame_ms"] > CAM_FIRST_MS:
        out.append((r["first_frame_ms"] / CAM_FIRST_MS, f"takes {_time(r['first_frame_ms'])} to show its camera"))
    if r.get("shown_pct") is not None and r["shown_pct"] < CAM_SHOWN_PCT:
        out.append((CAM_SHOWN_PCT / max(r["shown_pct"], 1), f"shows {r['shown_pct']:.0f}% of its camera's frames"))
    if r.get("stall_total_ms") is not None and r["stall_total_ms"] >= CAM_STALL_MS:
        out.append((r["stall_total_ms"] / CAM_STALL_MS, f"stalls its camera for {_time(r['stall_total_ms'])}"))
    return out


def _camera_limit(t):
    """From the runs whose camera level fails (the grade takes the median of per-run levels): the
    median of each number over those runs, the one furthest past its bar. When no such median fails
    (the runs fail on different numbers), the worst failing number of a single run."""
    runs = t.get("camera_runs") or []
    levels = t.get("camera_levels") or []
    failing = [r for r, lv in zip(runs, levels) if lv not in GOOD | FLUID | {None}] or runs
    if not failing:
        return None
    if all(r.get("first_frame_ms") is not None for r in failing):
        meds = {k: statistics.median(r[k] for r in failing) for k in ("first_frame_ms", "shown_pct", "stall_total_ms")
                if all(r.get(k) is not None for r in failing)}
        best = max(_camera_options(meds), default=None)
        if best:
            return best
    return max((o for r in failing for o in _camera_options(r)), default=None)


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
        # Only a number that fails its bar: a passing one would read as a strength.
        return _camera_limit(t)
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


def _name(p, rep=None):
    """A page in words; a report from before rubric v10 has no camera-free typical, and its typical
    keeps its own name (s1v7's had no camera at all)."""
    return NAME.get(p, p) if rep is None or "typical-nocam" in _tiers(rep) else p


def _with_camera(rep):
    """When typical reaches a level without its camera and not with it: (task, phrase) of the worst
    thing the camera page fails on, to say beside it ("with one, it ..."); else None."""
    levels = (rep.get("grade") or {}).get("levels") or {}
    if not levels.get("typical-nocam") or levels.get("typical") or "typical" not in _tiers(rep):
        return None
    b = _bands(rep).get("typical") or {}
    scored = [(s, G.TASKS.index(t), t) for t in G.TASKS if b.get(t) not in NOT_A_LIMIT
              for s in [_limit(t, _tiers(rep).get("typical"))] if s]
    if not scored:
        return None
    (how_far, phrase), _, task = max(scored, key=lambda x: (x[0][0], -x[1]))
    return task, phrase


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
    if page == "typical-nocam":
        # "Typical dashboards feel good without a camera: ...; with one, it shows 40% of its camera's frames."
        lead = f"Typical dashboards {'feel good' if reached else 'come closest'} without a camera"
        cam = _with_camera(rep) if reached else None
        tail = f"; with one, it {cam[1]}" if cam else ""
        body = (', '.join(parts[:-1]) + ', and ' + parts[-1] if len(parts) > 1 else parts[0]) if parts else ""
        return f"{lead}: {body}{tail}." if body else f"{lead}{tail}."
    lead = f"{_cap(_name(page, rep))} dashboards {'feel good' if reached else 'come closest'}"
    return f"{lead}: {', '.join(parts[:-1]) + ', and ' + parts[-1] if len(parts) > 1 else parts[0]}." if parts else f"{lead}."


def limits_sentence(rep):
    """The worst limits, each quoted once on the lightest page it fails on, and ranked by how far
    that quoted number is past its fluid bar, so the number shown is the reason it was picked. A
    slow restart only when it caps the letter."""
    bands, tiers = _bands(rep), _tiers(rep)
    found = []
    best, reached = best_page(rep)
    said = _with_camera(rep) if best == "typical-nocam" and reached else None   # in the good sentence already
    for t in G.TASKS:
        bad = [p for p in PAGES if (bands.get(p) or {}).get(t) not in NOT_A_LIMIT and not (said and p == "typical" and t == said[0])]
        scored = [(p, _limit(t, tiers.get(p))) for p in bad]
        scored = [(p, s) for p, s in scored if s]
        if scored:
            page, (how_far, phrase) = scored[0]
            found.append((how_far, PAGES.index(page), G.TASKS.index(t), page, phrase))
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
            by_page.append(f"{_name(p, rep)} {_join(phrases)}")
    if restart:
        by_page.insert(0, f"a restart takes {_time(restart)}")
    lead = "Bigger dashboards slow down" if best and all(PAGES.index(f[3]) > PAGES.index(best) for f in chosen) \
        else "It slows down"
    clauses = by_page[:-1] + ["and " + by_page[-1]] if len(by_page) > 2 else by_page
    return f"{lead}: {(', ' if len(by_page) > 2 else ', and ').join(clauses) if len(by_page) > 1 else by_page[0]}."


def _starving_run(r):
    """True when one run shows a player that gets video late while its decoder keeps up; None when
    the run lacks any of the three kinds of evidence (they are never combined across runs). HLS runs
    only: a WebRTC run's segment fetches are the HLS player's, which runs only until WebRTC has video
    (#202), so they say nothing about the stream that was graded."""
    if (r.get("path") or "hls") != "hls":
        return None
    seg = ((r.get("fetches") or {}).get("segment")) or {}
    gap, fetch = seg.get("arrival_gap_max_ms"), (seg.get("total_ms") or {}).get("p95")
    decoded = sum(s.get("decoded") or 0 for s in r.get("detail") or [])
    dropped = sum(s.get("dropped") or 0 for s in r.get("detail") or [])
    if gap is None or fetch is None or not decoded:
        return None
    return gap >= STARVE_GAP_MS and fetch < FAST_FETCH_MS and dropped <= 0.01 * decoded


def _starving_cameras(rep):
    """Failing camera pages where every run with the full evidence starves, and at least one does:
    (page, whether those runs' decoders were all hardware)."""
    out = []
    for p in PAGES:
        if (_bands(rep).get(p) or {}).get("camera") in NOT_A_LIMIT:
            continue
        runs = _tiers(rep).get(p, {}).get("camera_runs") or []
        judged = [(r, _starving_run(r)) for r in runs]
        judged = [(r, v) for r, v in judged if v is not None]
        if judged and all(v for _, v in judged):
            codecs = [c for r, _ in judged for c in ((r.get("media") or {}).get("codecs") or [])]
            hw = all(((r.get("media") or {}).get("codecs")) for r, _ in judged) \
                and not any(c.startswith(("c2.android.", "OMX.google.")) for c in codecs)
            out.append((p, hw))
    return out


def _slow_fetch(rep):
    for p in PAGES:
        if (_bands(rep).get(p) or {}).get("camera") in NOT_A_LIMIT:
            continue
        p95 = [((r.get("fetches") or {}).get("segment") or {}).get("total_ms", {}).get("p95")
               for r in _tiers(rep).get(p, {}).get("camera_runs") or [] if (r.get("path") or "hls") == "hls"]
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
    if peak.get("ok") and avail.get("ok"):
        return f"Memory isn't the limit ({mb(peak['value_kb'])} of {mb(peak['budget_kb'])} MB)."
    if peak.get("ok"):
        # Available memory wasn't read: only the app's own peak is known.
        return f"The app's memory peaks at {mb(peak['value_kb'])} of {mb(peak['budget_kb'])} MB."
    return None


def provisional_sentence(rep):
    """One plain clause for why the grade is provisional; the report lists the reasons in full.
    From the run's protocols and what is missing, not from the reasons' wording."""
    g = rep.get("grade") or {}
    if not g.get("provisional"):
        return None
    ref = (rep.get("graded") or {}).get("reference") or {}
    protocols = ref.get("protocols") or []
    # A report from before Clarity v1 (2026-11-14) has no `clarity`: pre-release, whatever its
    # protocol's number (they start again from 1 in v1).
    pre = bool(protocols) and None in (ref.get("clarity") or [None])
    other = protocols if pre else [p for p in protocols if p != G.PROTOCOL_VERSION]
    missing = list(g.get("missing") or [])
    parts = []
    if other:
        clause = f"measured with a {'pre-release' if pre else 'different'} version of {G.NAME}"
        cams = [m for m in missing if m.endswith(".camera")]
        if pre and cams and all(isinstance(p, int) and p < 5 for p in protocols):
            # Cameras came with pre-release protocol 5 (#174): an older run has none to measure.
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
