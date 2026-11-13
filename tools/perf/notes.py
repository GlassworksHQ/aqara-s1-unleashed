"""Clarity's notes (MIT): a short paragraph on what is good on a frame and what holds it back (#201).

Built from a finished report (report.json) by fixed rules: the same report always gives the same
text. Strengths first, then the limits, then a cause only where the report has the evidence for it.
docs/perf.md, "The notes", lists each rule and its evidence.
"""
import statistics

import grade as G

PAGES = G.TIERS
WORD = {"tap": "taps", "scroll": "scrolling", "updates": "live updates", "load": "opening a view", "camera": "the cameras"}
PLURAL = {"tap", "updates", "camera"}
GOOD = {"good", "premium"}
FLUID = {"fluid"}
NOT_A_LIMIT = GOOD | FLUID | {"none", None}

# A camera page starves (the player asks for video late) when a stream's segments arrive this far
# apart, against 1 s segments (camera.py SEGMENT_S), while each fetch is quicker than FAST_FETCH_MS.
STARVE_GAP_MS = 2000
FAST_FETCH_MS = 500
# Segments this slow to fetch put the link or the fake in question instead.
SLOW_FETCH_MS = 1000
# The app's CPU at idle on the typical reference dashboard worth saying (a share of one core).
IDLE_CPU_PCT = 25
# The runner waits for the CPU to drop below this before it starts (run.sh --max-start-temp).
START_TEMP_C = 50
# The fake sends a live change at most this late before the grade is provisional (report.py).
STREAM_LATE_MS = 200


def _med(x):
    return x["median"] if isinstance(x, dict) else x


def _ms(v):
    return f"{v:.0f} ms" if v < 1000 else f"{v / 1000:.1f} s"


def _join(xs):
    xs = list(xs)
    return xs[0] if len(xs) == 1 else ", ".join(xs[:-1]) + " and " + xs[-1]


def _cap(s):
    return s[:1].upper() + s[1:]


def _bands(rep):
    return (rep.get("grade") or {}).get("bands") or {}


def _tiers(rep):
    return (((rep.get("graded") or {}).get("reference") or {}).get("tiers")) or {}


def _value(task, page, tier):
    """The task's measured value on one page, in words, or None."""
    t = tier or {}
    if task == "tap" and _med(t.get("inp_ms")) is not None:
        return _ms(_med(t["inp_ms"]))
    if task == "scroll" and _med((t.get("scroll") or {}).get("fps")) is not None:
        return f"{_med(t['scroll']['fps']):.1f} fps"
    if task == "updates" and _med(t.get("latency_p95_ms")) is not None:
        return f"{_ms(_med(t['latency_p95_ms']))} to show"
    if task == "load" and _med(t.get("load_ms")) is not None:
        return _ms(_med(t["load_ms"]))
    if task == "camera":
        runs = t.get("camera_runs") or []
        ff = [r["first_frame_ms"] for r in runs if r.get("first_frame_ms") is not None]
        shown = [r["shown_pct"] for r in runs if r.get("shown_pct") is not None]
        if ff and shown:
            return f"first frame {_ms(statistics.median(ff))}, {statistics.median(shown):.0f}% of frames shown"
    return None


def _clause(tasks, word):
    verb = "are" if len(tasks) > 1 or tasks[0] in PLURAL else "is"
    return f"{_join(WORD[t] for t in tasks)} {verb} {word}"


def good_sentence(rep):
    """Tasks good (or at least fluid) on every page that measured them, then each page's own."""
    bands = _bands(rep)
    measured = {t: [p for p in PAGES if (bands.get(p) or {}).get(t) not in ("none", None)] for t in G.TASKS}
    all_good = [t for t in G.TASKS if measured[t] and all(bands[p][t] in GOOD for p in measured[t])]
    all_fluid = [t for t in G.TASKS if measured[t] and t not in all_good
                 and all(bands[p][t] in GOOD | FLUID for p in measured[t])]
    parts = []
    if all_good or all_fluid:
        every = [c for c in ([_clause(all_good, "good")] if all_good else [])
                 + ([_clause(all_fluid, "at least fluid")] if all_fluid else [])]
        parts.append(" and ".join(every) + " on every dashboard")
    for p in PAGES:
        b = bands.get(p) or {}
        rest = [t for t in G.TASKS if t not in all_good + all_fluid]
        good = [t for t in rest if b.get(t) in GOOD]
        fluid = [t for t in rest if b.get(t) in FLUID]
        here = ([_clause(good, "good")] if good else []) + ([_clause(fluid, "fluid")] if fluid else [])
        if here:
            parts.append(f"on {p}, " + " and ".join(here))
    return _cap("; ".join(parts)) + "." if parts else None


def limits_sentence(rep):
    bands, tiers = _bands(rep), _tiers(rep)
    phrases, pages = [], []
    for t in G.TASKS:
        bad = [p for p in PAGES if (bands.get(p) or {}).get(t) not in NOT_A_LIMIT]
        if not bad:
            continue
        pages += [p for p in bad if p not in pages]
        v = _value(t, bad[0], tiers.get(bad[0]))
        phrases.append(WORD[t] + (f" ({v} on {bad[0]})" if v else ""))
    g = rep.get("grade") or {}
    out = []
    if phrases:
        pages = [p for p in PAGES if p in pages]
        out.append(f"{_cap(_join(pages))} dashboards are held back by {_join(phrases)}")
    start = (g.get("start") or {})
    if start.get("band") not in (None, "good"):
        ref = (rep.get("graded") or {}).get("reference") or {}
        eng = _med(ref.get("engine_ready_ms"))
        s = f"a restart takes {_ms(start['value'])} to a usable dashboard"
        if eng is not None and eng < start["value"]:
            s += f", {_ms(start['value'] - eng)} of it after the engine was ready"
        out.append(s)
    missing = g.get("missing") or []
    if missing:
        what = sorted({WORD[m.split(".")[1]] if "." in m else "the restart" for m in missing})
        out.append(f"{_join(what)} {'wasn' if what == ['the restart'] else 'weren'}'t measured")
    return _cap("; ".join(out)) + "." if out else None


def _starving_cameras(rep):
    """Camera pages whose streams starve while the decoder keeps up: (page, longest gap, fetch p95,
    hardware decoder, frames dropped, frames decoded), from each run's worst-stream detail."""
    out = []
    for p in PAGES:
        if (_bands(rep).get(p) or {}).get("camera") in NOT_A_LIMIT:
            continue
        runs = _tiers(rep).get(p, {}).get("camera_runs") or []
        gaps, fetch, decoded, dropped, codecs = [], [], 0, 0, set()
        for r in runs:
            seg = ((r.get("fetches") or {}).get("segment")) or {}
            if seg.get("arrival_gap_max_ms") is not None:
                gaps.append(seg["arrival_gap_max_ms"])
            if _med((seg.get("total_ms") or {}).get("p95")) is not None:
                fetch.append(seg["total_ms"]["p95"])
            for s in r.get("detail") or []:
                decoded += s.get("decoded") or 0
                dropped += s.get("dropped") or 0
            codecs |= set(((r.get("media") or {}).get("codecs")) or [])
        if not gaps or not fetch or not decoded:
            continue
        if max(gaps) >= STARVE_GAP_MS and max(fetch) < FAST_FETCH_MS and dropped <= 0.01 * decoded:
            hw = bool(codecs) and not any(c.startswith(("c2.android.", "OMX.google.")) for c in codecs)
            out.append((p, max(gaps), max(fetch), hw, dropped, decoded))
    return out


def _slow_fetch(rep):
    for p in PAGES:
        if (_bands(rep).get(p) or {}).get("camera") in NOT_A_LIMIT:
            continue
        p95 = [((r.get("fetches") or {}).get("segment") or {}).get("total_ms", {}).get("p95")
               for r in _tiers(rep).get(p, {}).get("camera_runs") or []]
        p95 = [x for x in p95 if x is not None]
        if p95 and max(p95) >= SLOW_FETCH_MS:
            return p, max(p95)
    return None


def cause_sentences(rep):
    bands, tiers = _bands(rep), _tiers(rep)
    out = []
    slow_scroll = [p for p in PAGES if (bands.get(p) or {}).get("scroll") not in NOT_A_LIMIT]
    main = [p for p in slow_scroll if "main-thread" in (tiers.get(p, {}).get("scroll_limited_by") or [])]
    comp = [p for p in slow_scroll if "compositor" in (tiers.get(p, {}).get("scroll_limited_by") or [])]
    slow_updates = [p for p in PAGES if (bands.get(p) or {}).get("updates") not in NOT_A_LIMIT]
    starving = _starving_cameras(rep)
    idle = (((rep.get("ram") or {}).get("reference_idle")) or {}).get("cpu_app_pct_of_core")
    idle = _med(idle)

    evidence = []
    if main:
        evidence.append(f"on {_join(main)} the page's own frames (rAF) come slower than the screen's")
    for p in slow_updates:
        v = _med(tiers.get(p, {}).get("latency_p95_ms"))
        if v is not None:
            evidence.append(f"on {p} a change takes {_ms(v)} to show")
    for p, gap, fetch, hw, dropped, decoded in starving:
        evidence.append(f"on {p} the camera player gets video up to {_ms(gap)} apart though a fetch takes {_ms(fetch)} "
                        f"(p95), while the {'hardware ' if hw else ''}decoder drops {dropped} of {decoded} frames")
    if evidence:
        if idle is not None and idle >= IDLE_CPU_PCT:
            cam = (bands.get("typical") or {}).get("camera") not in ("none", None)
            evidence.append(f"untouched on typical{' (its camera playing)' if cam else ''}, the app keeps {idle:.0f}% "
                            "of a core busy")
        lead = "The page's main thread is the likely limit" if (main or starving) else "Live updates are slow"
        out.append(f"{lead}: {'; '.join(evidence)}.")
    if comp:
        out.append(f"Scrolling on {_join(comp)} is limited by the compositor: the screen dropped frames the page drew "
                   "(likely the GPU).")
    sf = _slow_fetch(rep)
    if sf:
        out.append(f"Camera segments took up to {_ms(sf[1])} to fetch on {sf[0]}, so the link or the fake may be the limit.")
    late = (rep.get("fake_ha") or {}).get("stream_late_max_ms")
    if late is not None and late > STREAM_LATE_MS:
        out.append(f"The fake Home Assistant sent changes up to {_ms(late)} late, so part of the update time is the test's.")
    hot = [z for ph in rep.get("thermal") or [] if ph["phase"].startswith("refstart-")
           for n, z in ph["zones"].items() if "cpu" in n.lower()]
    if hot and max(hot) > START_TEMP_C:
        out.append(f"The CPU was at up to {max(hot):.0f} °C as a graded run started; clock speeds aren't recorded, "
                   "so the run can't show whether it throttled.")
    return out


def memory_sentence(rep):
    b = ((rep.get("ram") or {}).get("budget")) or {}
    checks = b.get("checks") or {}
    peak, avail = checks.get("app_pss_peak") or {}, checks.get("device_available_min") or {}
    if peak.get("value_kb") is None:
        return None
    mb = lambda kb: f"{kb / 1024:.0f} MB"  # noqa: E731
    if peak.get("ok") is False:
        return f"Memory is a limit: the app's peak is {mb(peak['value_kb'])}, over its {mb(peak['budget_kb'])} budget."
    if avail.get("ok") is False:
        return (f"Memory is a limit: available memory fell to {mb(avail['value_kb'])}, "
                f"under the {mb(avail['budget_kb'])} the frame keeps free.")
    if peak.get("ok") and avail.get("ok") is not False:
        return f"Memory is not the limit: the app's peak is {mb(peak['value_kb'])} of {mb(peak['budget_kb'])}."
    return None


def notes(rep):
    """The paragraph and its parts: good, limits, causes (each a sentence, or None / [])."""
    g = rep.get("grade") or {}
    if g.get("refused"):
        text = f"Not graded: {g['refused']}."
        return {"good": None, "limits": None, "causes": [], "text": text, "rubric": g.get("rubric_version")}
    good, limits = good_sentence(rep), limits_sentence(rep)
    causes = cause_sentences(rep) + [s for s in [memory_sentence(rep)] if s]
    parts = [s for s in [good, limits] if s] + causes
    if g.get("provisional"):
        parts.append("Provisional: " + "; ".join(g.get("provisional_reasons") or ["not everything was measured"]) + ".")
    return {"good": good, "limits": limits, "causes": causes, "text": " ".join(parts), "rubric": g.get("rubric_version")}
