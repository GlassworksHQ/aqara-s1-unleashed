#!/usr/bin/env python3
"""Build report.json and report.md from a tools/perf run directory (MIT). Offline; no adb.

    python3 tools/perf/report.py <run-dir> [--apk app.apk ...] [--budgets tools/perf/budgets.json]

The report schema (glassworks.perf/3) is documented in docs/perf.md.
"""
import argparse
import glob
import json
import os
import re
import statistics
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import grade as G  # noqa: E402
import perflib as P  # noqa: E402

SCHEMA = "glassworks.perf/3"
SETTLE_GAP_MS = 750
FAMILIES = ["css-transform", "css-opacity", "svg", "canvas2d", "ha-cards"]


def read_json(path):
    with open(path) as f:
        return json.load(f)


def read_jsonl(path):
    with open(path) as f:
        return [json.loads(l) for l in f if l.strip()]


# ---- frames

def sf_intervals(pres, win, period):
    """Frame intervals over the whole window. Gaps at its edges count when they are longer than 1.5
    refreshes (a frame was due and none came); a shorter edge gap is only the vsync phase."""
    out = P.intervals(pres)
    if not pres:
        return [win[1] - win[0]]
    for gap in (pres[0] - win[0], win[1] - pres[-1]):
        if gap > 1.5 * period:
            out.append(gap)
    return out


def step_frames(pl, motion=True):
    """Frame stats for one window, graded on the worse of SurfaceFlinger and rAF.

    rAF misses frames the compositor drops (CSS animations run off the main thread); SurfaceFlinger
    misses main-thread jank while the compositor still animates something. SurfaceFlinger's data is
    complete when its history reached back before the window: then every gap in the window, edges
    included, is a real stall. If it didn't (evicted), or nothing is meant to move (motion=False:
    a still page presents nothing, which isn't a stall), only rAF is used."""
    raf = pl.get("raf") or {"n": 0}
    sf = pl.get("sf") or {}
    pres = sf.get("presents_ms") or []
    win, period, oldest = sf.get("window_ms"), sf.get("period_ms"), sf.get("oldest_ms")
    if "oldest_ms" in sf:
        sf_valid = bool(motion and win and period and oldest is not None and oldest <= win[0])
    else:  # runs from before oldest_ms was recorded
        sf_valid = bool(motion and win and period and len(pres) > 1)
    sf_stats = P.frame_stats(sf_intervals(pres, win, period), period) if sf_valid else {"n": 0}

    def key(st):
        return (G.ORDER.get(G.frame_band(st), -1), st.get("p95_ms") or 0, st.get("jank_pct") or 0)

    if not sf_valid:
        chosen, source, limited = raf, "raf", None
    elif key(sf_stats) > key(raf):
        chosen, source, limited = sf_stats, "surfaceflinger", "compositor"
    elif key(raf) > key(sf_stats):
        chosen, source, limited = raf, "raf", "main-thread"
    else:
        chosen, source, limited = sf_stats, "surfaceflinger", "equal"
    return {"source": source, "limited_by": limited, "stats": chosen, "raf": raf, "sf": sf_stats, "sf_valid": sf_valid}


# ---- the test page (detail, not graded)

def synthetic_run(lines):
    hello = next((l for l in lines if l["t"] == "hello"), {})
    steps = [dict(stage=l["stage"], n=l["n"], scrolled_px=l.get("scrolled_px"), **step_frames(l)) for l in lines if l["t"] == "step"]
    js = next((l for l in lines if l["t"] == "js"), None)
    ladder = {}
    for fam in FAMILIES:
        fs = [(s["n"], s["stats"]) for s in steps if s["stage"] == fam]
        if fs:
            ladder[fam] = {"held_60fps": G.capacity(fs, {G.GOOD}),
                           "held_30fps": G.capacity(fs, {G.GOOD, G.NI}),
                           "top_step": max(n for n, _ in fs),
                           "steps": [{"n": s["n"], "source": s["source"], "limited_by": s["limited_by"],
                                      "sf_valid": s["sf_valid"], "frames": s["stats"]} for s in steps if s["stage"] == fam]}
    scroll = next((s for s in steps if s["stage"] == "scroll"), None)
    return {
        "ua": hello.get("ua"), "page_load": hello.get("load") or {}, "dpr": hello.get("dpr"),
        "ladder": ladder,
        "scroll": scroll and {"source": scroll["source"], "scrolled_px": scroll["scrolled_px"],
                              "moved": bool(scroll["scrolled_px"]), **scroll["stats"]},
        "js": js and {"median_ms": js["median_ms"], "p95_ms": js["p95_ms"],
                      "checksum_ok": js["checksum"] == js["expected"]},
        "complete": any(l["t"] == "done" for l in lines),
    }


def geckoview_version(ua):
    m = re.search(r"rv:(\d+(?:\.\d+)*)", ua or "")
    return m.group(1) if m else None


def synthetic_summary(runs):
    def sp(f):
        return P.spread([f(r) for r in runs])

    ladder = {}
    for fam in FAMILIES:
        if any(fam in r["ladder"] for r in runs):
            ladder[fam] = {k: sp(lambda r, k=k: (r["ladder"].get(fam) or {}).get(k)) for k in ("held_60fps", "held_30fps")}
            # Held at the ladder's last step in every run: the device can do more than the ladder asks.
            fam_runs = [r["ladder"][fam] for r in runs if fam in r["ladder"]]
            for k in ("held_60fps", "held_30fps"):
                ladder[fam][k + "_at_top"] = all(x[k] == x["top_step"] for x in fam_runs)
    return {
        "runs": len(runs),
        "complete_runs": sum(1 for r in runs if r["complete"]),
        "ladder": ladder,
        "steps_without_sf": sum(1 for r in runs for f in r["ladder"].values() for st in f["steps"] if not st["sf_valid"]),
        "scroll_moved": all(r["scroll"]["moved"] for r in runs if r["scroll"]) if any(r["scroll"] for r in runs) else None,
        "scroll": {k: sp(lambda r, k=k: (r["scroll"] or {}).get(k)) for k in ("p95_ms", "jank_pct", "fps")},
        "js_burst_ms": sp(lambda r: (r["js"] or {}).get("median_ms")),
        "js_checksum_ok": all((r["js"] or {}).get("checksum_ok") for r in runs) if runs else None,
        "page_lcp_ms": sp(lambda r: r["page_load"].get("lcp_ms") or r["page_load"].get("fcp_ms")),
        # Engines without LCP report FCP instead, an earlier paint, so the load reads as faster.
        "page_lcp_from": sorted({r["page_load"].get("lcp_from") or ("lcp" if r["page_load"].get("lcp_ms") else "fcp")
                                 for r in runs if r["page_load"]}),
    }


# ---- the reference dashboards (graded)

def settle_before(frames_ms, loaded_ms, live_ms, gap_ms=SETTLE_GAP_MS):
    """When a reference dashboard finished loading: the first frame followed by gap_ms with no new
    frame, where the quiet reaches past the load event (a pause before the load event is mid-load),
    and never before the load event. Frames are those between navigation start and the page going
    live; the page is still for 2 s before that, so the quiet before live_ms counts as a gap.
    Returns (ms, None), or (None, why) when it can't be measured: never 0 by default."""
    fs = sorted(frames_ms)
    if not fs:
        return None, "no frames on screen while it loaded"
    for i, f in enumerate(fs):
        nxt = fs[i + 1] if i + 1 < len(fs) else live_ms
        if nxt - f >= gap_ms and nxt > loaded_ms:
            return max(f, loaded_ms), None
    return None, "it never went still before going live"


def tier_run(tier, entry, presents_ms):
    """One reference dashboard in one run: load (page clock + SurfaceFlinger), frames, INP."""
    page, step = entry.get("page"), entry.get("step")
    out = {"tier": tier}
    if entry.get("error") or not page or not step:
        return dict(out, error=entry.get("error") or "incomplete")
    if not page.get("load_ms") or not page.get("fcp_ms"):
        out["load_error"] = "no load event or first paint reported"
    else:
        nav = P.page_to_mono_ms(page, 0)
        loaded = P.page_to_mono_ms(page, page["load_ms"])
        fcp = P.page_to_mono_ms(page, page["fcp_ms"])
        live = entry.get("live_mono", 0) * 1000
        settle, why = settle_before([t for t in presents_ms if nav <= t <= live], loaded, live)
        if why:
            out["load_error"] = why
        elif settle < fcp:
            out["load_error"] = "settled before its first paint"
        else:
            out["load_ms"] = round(settle - nav)
    check = entry.get("measure") or {}
    # Whether something is meant to move comes from the page itself (tiers.js, via its live check):
    # Light has no animations, so a still screen there isn't a stall and only rAF counts.
    f = step_frames(step, (check.get("animations_expected") or 0) > 0)
    inp = entry.get("input") or {}
    out.update(frames=f["stats"], source=f["source"], limited_by=f["limited_by"], sf_valid=f["sf_valid"],
               inp_ms=inp.get("inp_ms") if inp.get("taps") else None, input_method=inp.get("method"),
               fcp_ms=page.get("fcp_ms"), nodes=page.get("nodes"))
    why = live_check_failed(check)
    if why:
        # The frames (and with the toggle off screen, the taps) aren't of the page as defined.
        out.update(frames=None, frames_error=why)
        if "toggle or a live element is off screen" in why:
            out["inp_ms"] = None
    return out


def live_check_failed(check):
    """Why this tier's frames can't be trusted, or None. Gecko doesn't run CSS animations that are
    off screen, so a page whose animated elements were off screen, or whose animations weren't
    running, shows a still screen that would read as frozen frames."""
    if "offscreen" not in check:
        return "no live check (a page from before it was added)"
    if check["offscreen"]:
        return f"the toggle or a live element is off screen ({check['offscreen']} of {check['moving']})"
    if check.get("animations") != check.get("animations_expected"):
        return f"{check.get('animations')} of {check.get('animations_expected')} animations running"
    return None


def reference_run(c):
    """One graded run: the cold start (start = process start to engine ready, both stamped on the
    device, so the harness's adb round trips aren't in it), then each reference dashboard."""
    base = {"ttid_ms": c.get("ttid_ms"), "launch": c.get("launch"), "own_page_quiet": c.get("own_page_quiet"),
            "engine_uri": c.get("engine_uri")}
    proc, engine = c.get("proc_mono"), c.get("engine_mono")
    start, start_error = None, None
    if proc is None or engine is None:
        start_error = "no process start or engine ready in logcat"
    elif "engine_uri" in c and c["engine_uri"] != "about:blank":
        # Engine ready is GeckoView's own first page; any other first page may already wait on the network.
        start_error = f"the engine's first page was {c['engine_uri']}, not about:blank"
    else:
        start = round((engine - proc) * 1000)
    tiers = {t: tier_run(t, e, c.get("presents_mono_ms") or []) for t, e in (c.get("tiers") or {}).items()}
    return dict(base, start_ms=start, start_error=start_error, tiers=tiers, error=c.get("error"))


def median_band(bands):
    """The middle band of several runs (the worse of the two middles for an even count)."""
    bs = sorted((b for b in bands if b), key=G.ORDER.get)
    return bs[len(bs) // 2] if bs else None


def reference_summary(all_runs):
    # A run that ended in an error (another page loaded, a page didn't finish) isn't graded at all.
    runs = [r for r in all_runs if not r.get("error")]
    starts = [r["start_ms"] for r in runs]
    tiers = {}
    for t in G.TIERS:
        rs = [r["tiers"][t] for r in runs if t in r["tiers"] and not r["tiers"][t].get("error")]
        if not rs:
            continue
        fr = [x["frames"] for x in rs if x["frames"]]
        tiers[t] = {
            "frames": {k: P.spread([f.get(k) for f in fr]) for k in ("p95_ms", "jank_pct", "frozen_pct", "fps", "n")},
            # Each run's frames band, and their median: three runs each poor for a different reason
            # are poor, not the band of per-measure medians.
            "frames_bands": [G.frame_band(f) for f in fr],
            "frames_band": median_band(G.frame_band(f) for f in fr),
            "frames_errors": [x["frames_error"] for x in rs if x.get("frames_error")],
            # Per graded run: was SurfaceFlinger's history complete for the window? (None: no frames.)
            "sf_valid": [x.get("sf_valid") if x["frames"] else None for x in rs],
            "sources": sorted({x["source"] for x in rs if x["frames"]}),
            "limited_by": sorted({x["limited_by"] for x in rs if x["limited_by"]}),
            "inp_ms": P.spread([x["inp_ms"] for x in rs]),
            "input_method": sorted({x["input_method"] for x in rs if x["input_method"]}),
            "load_ms": P.spread([x.get("load_ms") for x in rs]),
            "load_errors": [x["load_error"] for x in rs if x.get("load_error")],
            "fcp_ms": P.spread([x["fcp_ms"] for x in rs]),
            "nodes": sorted({x["nodes"] for x in rs if x.get("nodes")}),
        }
    return {
        "runs": len(all_runs),
        "graded_runs": len(runs),
        "start_ms": P.spread(starts),
        "start_errors": [r["start_error"] for r in runs if r.get("start_error")],
        "engine_uris": sorted({r["engine_uri"] for r in runs if r.get("engine_uri")}),
        # Android's TTID: the first frame of the launched activity, which on a kiosk is its own
        # loading screen. Reported, not graded.
        "android_ttid_ms": P.spread([r["ttid_ms"] for r in runs]),
        "launch": sorted({r["launch"] for r in runs if r.get("launch")}),
        # False: the kiosk's own page never finished (Home Assistant down?), so the reference pages
        # were opened after a fixed wait instead. Still graded; the flag explains a slow run.
        "own_page_quiet": sorted({r["own_page_quiet"] for r in runs}, key=str),
        "tiers": tiers,
        "errors": [r["error"] for r in all_runs if r.get("error")],
        "per_run": all_runs,
    }


def grade_inputs(graded):
    """The grade's inputs: medians from the reference dashboards only, so the grade can't depend on
    anyone's own Home Assistant."""
    ref = graded.get("reference") or {}
    med = lambda x: x and x["median"]  # noqa: E731
    tiers = {}
    for t, s in (ref.get("tiers") or {}).items():
        fr = {k: med(s["frames"][k]) for k in ("p95_ms", "jank_pct", "frozen_pct", "n")}
        tiers[t] = {"frames": fr if fr["n"] else None, "frames_band": s["frames_band"],
                    "inp_ms": med(s["inp_ms"]), "load_ms": med(s["load_ms"])}
    return tiers, med(ref.get("start_ms"))


def headline(name, g, ref):
    """'Aqara S1 Plus: C, good for typical dashboards. Busy animated dashboards drop to about 13 fps.'"""
    out = f"{name}: {g['grade']}, {g['meaning']}."
    tiers = (ref or {}).get("tiers") or {}
    # The first tier (busiest first) that isn't smooth: its average rate and how many frames were late,
    # so "55 fps" next to a band that isn't good doesn't read as smooth.
    for t, label in (("busy", "Busy animated"), ("typical", "Typical"), ("light", "Light")):
        if g["bands"][t]["frames"] not in (G.GOOD, None) and tiers.get(t):
            fps, jank = tiers[t]["frames"]["fps"], tiers[t]["frames"]["jank_pct"]
            if fps and jank:
                out += f" {label} dashboards average about {fps['median']:.0f} fps, with {jank['median']:.0f}% of frames late."
            break
    slow = [f"{t} {m}" for t in G.TIERS for m in ("inp", "load") if g["bands"][t][m] == G.POOR]
    if slow:
        out += " Poor: " + ", ".join(slow) + "."
    if g["capped_by_start"]:
        out += f" A poor start ({g['start']['value'] / 1000:.1f} s) caps it at {G.START_CAP}."
    if g["provisional"]:
        why = list(g.get("provisional_reasons") or []) + (["not everything was measured"] if g["missing"] else [])
        out += " Provisional: " + "; ".join(why) + "."
    return out


# ---- real use (not graded)

def visual_settle(presents_ms, stop_ms, ended_quiet, gap_ms=SETTLE_GAP_MS):
    """When the dashboard stopped painting: the first frame at or after PageStop that is followed by
    gap_ms of no new frames. ended_quiet: sampling stopped because nothing painted for a while.
    A dashboard that never goes quiet (a constant animation) falls back to PageStop. Returns (ms, how)."""
    after = sorted(t for t in presents_ms if t >= stop_ms)
    if not after:
        return stop_ms, "page-stop"
    for a, b in zip(after, after[1:]):
        if b - a >= gap_ms:
            return a, "quiet-gap"
    if ended_quiet:
        return after[-1], "quiet-gap"
    return stop_ms, "no-quiet-gap"


def coldstart_run(c):
    base = {"ttid_ms": c.get("ttid_ms"), "launch": c.get("launch"), "am_total_ms": c.get("am_total_ms")}
    if c.get("error") or c.get("page_start_mono") is None:
        return dict(base, error=c.get("error") or "no dashboard PageStart")
    start, stop = c["page_start_mono"] * 1000, c["page_stop_mono"] * 1000
    settle, how = visual_settle(c.get("presents_mono_ms") or [], stop, c.get("ended_quiet", False))
    killed, proc, engine = c.get("stop_mono"), c.get("proc_mono"), c.get("engine_mono")
    return dict(base, page_stop_ms=round(stop - start), dashboard_load_ms=round(settle - start), settle=how,
                app_start_to_dashboard_ms=round(start - proc * 1000) if proc else None,
                engine_ready_ms=round((engine - proc) * 1000) if proc and engine else None,
                stop_to_dashboard_ms=round(settle - killed * 1000) if killed else None)


def app_memory(idle, package_roles=None):
    procs = idle["processes"]
    app = {n: p for n, p in procs.items() if p["role"] != "radio"}
    roles = {}
    for p in app.values():
        r = roles.setdefault(p["role"], {"pss_kb": 0, "rss_peak_kb": 0, "count": 0})
        r["pss_kb"] += p["pss_kb"]
        r["rss_peak_kb"] += p.get("rss_peak_kb") or 0
        r["count"] += 1
    main = next((p.get("summary") for p in app.values() if p["role"] == "main"), {}) or {}
    dm = idle.get("device_mem") or {}
    return {
        # No app process in the sample means nothing was measured, not zero memory.
        "app_pss_kb": sum(p["pss_kb"] for p in app.values()) if app else None,
        "by_role": roles,
        "main": main,
        "radio_pss_kb": sum(p["pss_kb"] for p in procs.values() if p["role"] == "radio"),
        "mem_total_kb": dm.get("mem_total_kb"),
        "mem_available_kb": dm.get("mem_available_kb"),
    }


def app_cpu(idle, package):
    cpu = idle.get("cpu") or {}
    procs = cpu.get("procs_pct_of_core") or {}
    roles = {n: p["role"] for n, p in idle["processes"].items()}
    app = sum(v for n, v in procs.items() if n.startswith(package))
    radio = sum(v for n, v in procs.items() if roles.get(n) == "radio")
    return {"app_pct_of_core": round(app, 2), "radio_pct_of_core": round(radio, 2), "device_busy_pct": cpu.get("device_busy_pct")}


def idle_frames(idle):
    fr = idle.get("frames") or {}
    pres = fr.get("presents_mono_ms") or []
    st = P.frame_stats(P.intervals(pres), fr.get("period_ms"))
    return {"presented_fps": round(len(pres) / idle["window_s"], 2), "p95_ms": st.get("p95_ms"), "max_ms": st.get("max_ms")}


def real_use_summary(cold, idles, package):
    on = idles.get("all-on", [])
    mems = [app_memory(i) for i in on]
    cpus = [app_cpu(i, package) for i in on]
    allmem = [app_memory(i) for runs in idles.values() for i in runs]
    roles = sorted({r for m in mems for r in m["by_role"]})
    return {
        "app_start_to_dashboard_ms": P.spread([c.get("app_start_to_dashboard_ms") for c in cold if not c.get("error")]),
        "android_ttid_ms": P.spread([c["ttid_ms"] for c in cold if not c.get("error")]),
        "dashboard_load_ms": P.spread([c.get("dashboard_load_ms") for c in cold]),
        "dashboard_page_stop_ms": P.spread([c.get("page_stop_ms") for c in cold]),
        "stop_to_dashboard_ms": P.spread([c.get("stop_to_dashboard_ms") for c in cold]),
        "settle_how": sorted({c["settle"] for c in cold if c.get("settle")}),
        "launch": sorted({c["launch"] for c in cold if c.get("launch")}),
        "errors": [c["error"] for c in cold if c.get("error")],
        "idle": {
            "samples": len(on),
            "app_pss_kb": P.spread([m["app_pss_kb"] for m in mems]),
            "by_role_pss_kb": {r: P.spread([m["by_role"].get(r, {}).get("pss_kb") for m in mems]) for r in roles},
            "main_kb": {k: P.spread([m["main"].get(k) for m in mems]) for k in sorted({k for m in mems for k in m["main"]})},
            "radio_pss_kb": P.spread([m["radio_pss_kb"] for m in mems]),
            "device_used_pct": P.spread([100 - 100 * m["mem_available_kb"] / m["mem_total_kb"] for m in mems
                                         if m["mem_total_kb"] and m["mem_available_kb"] is not None]),
            "cpu_app_pct_of_core": P.spread([c["app_pct_of_core"] for c in cpus]),
            "cpu_radio_pct_of_core": P.spread([c["radio_pct_of_core"] for c in cpus]),
            "cpu_device_busy_pct": P.spread([c["device_busy_pct"] for c in cpus]),
            "frames": {k: P.spread([idle_frames(i)[k] for i in on]) for k in ("presented_fps", "p95_ms", "max_ms")},
        },
        "peak": {
            "app_pss_kb": max((m["app_pss_kb"] for m in allmem if m["app_pss_kb"] is not None), default=None),
            "app_rss_hwm_kb": max((sum(r["rss_peak_kb"] for r in m["by_role"].values()) for m in allmem), default=None),
            "mem_available_min_kb": min((m["mem_available_kb"] for m in allmem if m["mem_available_kb"] is not None), default=None),
        },
    }


def pane_diffs(idles, package):
    """Each `<pane>-off` label against the all-on samples. A delta inside the all-on range is noise."""
    on = [i for i in idles.get("all-on", []) if app_memory(i)["app_pss_kb"] is not None]
    if not on:
        return []
    on_pss = [app_memory(i)["app_pss_kb"] for i in on]
    on_cpu = [app_cpu(i, package)["app_pct_of_core"] for i in on]
    out = []
    for label, runs in sorted(idles.items()):
        runs = [i for i in runs if app_memory(i)["app_pss_kb"] is not None]
        if not label.endswith("-off") or not runs:
            continue
        off_pss = [app_memory(i)["app_pss_kb"] for i in runs]
        off_cpu = [app_cpu(i, package)["app_pct_of_core"] for i in runs]
        d_pss = statistics.median(on_pss) - statistics.median(off_pss)
        d_cpu = statistics.median(on_cpu) - statistics.median(off_cpu)
        out.append({
            "id": label[:-4], "method": "off-diff", "samples_on": len(on), "samples_off": len(runs),
            "pss_kb": round(d_pss), "pss_noise_kb": max(on_pss) - min(on_pss),
            "pss_measurable": abs(d_pss) > max(on_pss) - min(on_pss),
            "cpu_pct_of_core": round(d_cpu, 2), "cpu_noise_pct": round(max(on_cpu) - min(on_cpu), 2),
            "cpu_measurable": abs(d_cpu) > max(on_cpu) - min(on_cpu),
        })
    return out


# ---- APK size

def apk_sizes(paths):
    """Per-ABI APK size and its parts (compressed bytes), for #68."""
    out = []
    for path in paths:
        with zipfile.ZipFile(path) as z:
            parts = {"dex": 0, "native": 0, "assets": 0, "resources": 0, "other": 0}
            abis = set()
            for i in z.infolist():
                n = i.filename
                if n.startswith("lib/"):
                    abis.add(n.split("/")[1])
                    parts["native"] += i.compress_size
                elif re.match(r"classes\d*\.dex$", n):
                    parts["dex"] += i.compress_size
                elif n.startswith("assets/"):
                    parts["assets"] += i.compress_size
                elif n.startswith("res/") or n == "resources.arsc":
                    parts["resources"] += i.compress_size
                else:
                    parts["other"] += i.compress_size
        out.append({"file": os.path.basename(path), "abis": sorted(abis) or ["universal"],
                    "bytes": os.path.getsize(path), "parts_bytes": parts})
    return out


# ---- assemble

# ---- assemble

def build(run_dir, apks=(), budgets=None):
    device = read_json(os.path.join(run_dir, "device.json"))
    package = device["app"]["package"]
    files = lambda pat: sorted(glob.glob(os.path.join(run_dir, pat)))  # noqa: E731
    syn = [synthetic_run(read_jsonl(p)) for p in files("synthetic-*.jsonl")]
    refs = [reference_run(read_json(p)) for p in files("refstart-*.json")]
    cold = [coldstart_run(read_json(p)) for p in files("coldstart-*.json")]
    idles = {}
    for p in files("idle-*.json"):
        i = read_json(p)
        idles.setdefault(i["label"], []).append(i)

    graded = {"reference": reference_summary(refs) if refs else None}
    tiers, start = grade_inputs(graded)
    grade = G.grade(tiers, start)
    ref = graded["reference"]
    if ref and False in ref["own_page_quiet"]:
        # The 30 s fallback: Home Assistant was down or hung during at least one run, so the kiosk may
        # still have been retrying its page while the reference dashboards ran. The letter is kept
        # (dropping a measurement would let Home Assistant move it) and flagged.
        grade["provisional"] = True
        grade["provisional_reasons"] = ["the kiosk's own page never went quiet in a graded run (Home Assistant down?)"]
    name = device.get("frame_name") or device["frame"]
    grade["headline"] = headline(name, grade, graded["reference"])

    s = synthetic_summary(syn) if syn else None
    med = lambda x: x and x["median"]  # noqa: E731
    detail = {"synthetic": s and dict(s, js_band=G.band(med(s["js_burst_ms"]), G.JS_BURST), per_run=syn)}

    r = real_use_summary(cold, idles, package) if (cold or idles) else None
    real_use = None
    if r:
        budgets = budgets or {"default": {"app_pss_max_pct_of_ram": 50, "device_available_min_pct": 15}, "frames": {}}
        memory = {"idle": r["idle"], "peak": r["peak"], "budget": G.memory_budget(
            {"app_pss_peak_kb": r["peak"]["app_pss_kb"], "mem_total_kb": device["mem_total_kb"],
             "mem_available_min_kb": r["peak"]["mem_available_min_kb"]}, G.frame_budget(budgets, device["frame"]))}
        real_use = dict(r, per_run=cold, memory=memory, panes=pane_diffs(idles, package) + radio_pane(r))

    gv = next((geckoview_version(x["ua"]) for x in syn if x["ua"]), None)
    return {
        "schema": SCHEMA,
        "frame": {"id": device["frame"], "name": name,
                  "device": {k: v for k, v in device.items() if k not in ("frame", "frame_name", "app")},
                  "app": dict(device["app"], geckoview=gv)},
        "apk": apk_sizes(apks),
        "grade": grade,
        "graded": graded,
        "detail": detail,
        "real_use": real_use,
    }


def radio_pane(r):
    if not r or not r["idle"]["radio_pss_kb"]:
        return []
    return [{"id": "radio", "method": "process", "pss_kb": r["idle"]["radio_pss_kb"]["median"],
             "cpu_pct_of_core": (r["idle"]["cpu_radio_pct_of_core"] or {}).get("median"),
             "note": "the radio's own processes (outside the app); measured in place, not switched off"}]


def fmt(sp, unit="", scale=1, plus=False):
    if not sp:
        return "n/a"
    m, lo, hi = sp["median"] / scale, sp["min"] / scale, sp["max"] / scale
    d = 1 if max(abs(m), abs(hi)) < 100 and any(x != int(x) for x in (m, lo, hi)) else 0
    return f"{m:.{d}f}{'+' if plus else ''}{unit} ({lo:.{d}f} to {hi:.{d}f}, n={sp['n']})"


def device_line(dev, app):
    cpu = dev.get("cpu") or {}
    parts = [" ".join(x for x in (dev.get("manufacturer"), dev.get("model")) if x),
             dev.get("android") and f"Android {dev['android']} (API {dev.get('api')})", dev.get("abi"),
             cpu.get("cores") and f"{cpu['cores']} cores", dev.get("mem_total_kb") and f"{round(dev['mem_total_kb'] / 1024)} MB RAM",
             app.get("version_name") and f"app {app['version_name']}", app.get("geckoview") and f"GeckoView {app['geckoview']}"]
    return ", ".join(p for p in parts if p) + "."


def band_cell(b):
    return b or "n/a"


def markdown(rep):
    g = rep["grade"]
    dev = rep["frame"]["device"]
    app = rep["frame"]["app"]
    out = [f"# Perf report: {rep['frame']['name']}", "", device_line(dev, app), "",
           f"**{g['headline']}**", "",
           "The grade comes only from the three reference dashboards this test ships (light, typical, busy), "
           "so it doesn't depend on your Home Assistant. Background load (panes, the radio, heat) still reaches it.", ""]
    ref = rep["graded"]["reference"]
    if ref:
        out += ["| Dashboard | Frames (p95, fps) | Frames | INP | INP | Load | Load |", "|---|---|---|---|---|---|---|"]
        for t in G.TIERS:
            s, b = ref["tiers"].get(t), g["bands"][t]
            if not s:
                out.append(f"| {t} | not measured | | | | | |")
                continue
            fr = s["frames"]
            frames = f"{fmt(fr['p95_ms'], ' ms')}, {fmt(fr['fps'], ' fps')}"
            out.append(f"| {t} | {frames} | {band_cell(b['frames'])} | {fmt(s['inp_ms'], ' ms')} | {band_cell(b['inp'])} "
                       f"| {fmt(s['load_ms'], ' ms')} | {band_cell(b['load'])} |")
        out += ["", f"Start, app process to engine ready: {fmt(ref['start_ms'], ' ms')} ({band_cell(g['start']['band'])}; "
                    f"{', '.join(ref['launch']) or 'n/a'}). Android's TTID {fmt(ref['android_ttid_ms'], ' ms')}: the first "
                    "frame of the kiosk's loading screen; not graded."]
        for t, s in ref["tiers"].items():
            if s["frames_errors"]:
                out.append(f"{t}: frames not measured in {len(s['frames_errors'])} run(s) ({'; '.join(sorted(set(s['frames_errors'])))}).")
            if s["load_errors"]:
                out.append(f"{t}: load not measured in {len(s['load_errors'])} run(s) ({'; '.join(sorted(set(s['load_errors'])))}).")
        if ref["errors"]:
            out.append(f"Failed runs: {len(ref['errors'])} ({'; '.join(sorted(set(ref['errors'])))}).")
        if not all(ref["own_page_quiet"]):
            out.append("Some runs opened the reference dashboards before the kiosk's own page finished.")
    s = rep["detail"]["synthetic"]
    if s:
        out += ["", "## Detail: animations it can handle (test page, not graded)", "",
                "Largest element count before the first step that missed the target (median of runs). "
                "A + means it held at the ladder's last step.", "",
                "| Family | 60 fps | 30 fps |", "|---|---|---|"]
        for fam, v in s["ladder"].items():
            out.append(f"| {fam} | {fmt(v['held_60fps'], plus=v['held_60fps_at_top'])} | {fmt(v['held_30fps'], plus=v['held_30fps_at_top'])} |")
        out += ["", f"Scroll p95 {fmt(s['scroll']['p95_ms'], ' ms')}" + (" (the list did not move: not a measurement)" if s["scroll_moved"] is False else "")
                + f". JS state burst {fmt(s['js_burst_ms'], ' ms')} (band {s['js_band'] or 'n/a'}, checksum {'ok' if s['js_checksum_ok'] else 'MISMATCH'}). "
                f"Test page {'/'.join(x.upper() for x in s['page_lcp_from']) or 'LCP'} {fmt(s['page_lcp_ms'], ' ms')}."]
        if s["steps_without_sf"]:
            out.append(f"{s['steps_without_sf']} step(s) had no complete SurfaceFlinger history and use rAF only.")
    r = rep["real_use"]
    if r and (r["idle"]["samples"] or r["per_run"]):
        i = r["idle"]
        out += ["", "## Real use on this dashboard (not graded)", "",
                "Your own dashboard and panes. These numbers change with your Home Assistant, so they are not in the grade.", "",
                f"App process start to dashboard load {fmt(r['app_start_to_dashboard_ms'], ' ms')} ({', '.join(r['launch']) or 'n/a'}). "
                f"Dashboard load {fmt(r['dashboard_load_ms'], ' ms')} ({', '.join(r['settle_how']) or 'n/a'}); "
                f"its load event {fmt(r['dashboard_page_stop_ms'], ' ms')}; force-stop to a settled dashboard "
                f"{fmt(r['stop_to_dashboard_ms'], ' ms')}." + (f" Failed cold starts: {len(r['errors'])}." if r["errors"] else ""),
                f"Idle: app PSS {fmt(i['app_pss_kb'], ' MB', 1024)}, device RAM in use {fmt(i['device_used_pct'], ' %')}, "
                f"app CPU {fmt(i['cpu_app_pct_of_core'], ' % of a core')}, device busy {fmt(i['cpu_device_busy_pct'], ' %')}, "
                f"{fmt(i['frames']['presented_fps'], ' frames/s')} presented."]
        out += ["", "| Process | PSS at idle |", "|---|---|"] + [f"| {k} | {fmt(v, ' MB', 1024)} |" for k, v in i["by_role_pss_kb"].items()]
    m = r and r["memory"]
    if m and m["budget"]:
        out += ["", "## Memory budget (real use, not graded)", ""]
        for k, c in m["budget"]["checks"].items():
            val = "n/a" if c["value_kb"] is None else f"{c['value_kb'] / 1024:.0f} MB"
            out.append(f"- {k}: {val} against {c['budget_kb'] / 1024:.0f} MB: {'ok' if c['ok'] else 'over' if c['ok'] is False else 'n/a'}")
    if r and r["panes"]:
        out += ["", "## Panes (real use, not graded)", "", "| Pane | Method | PSS | CPU |", "|---|---|---|---|"]
        for p in r["panes"]:
            pss = f"{p['pss_kb'] / 1024:.1f} MB" + ("" if p.get("pss_measurable", True) else " (within noise)")
            cpu = p.get("cpu_pct_of_core")
            cpu = "n/a" if cpu is None else f"{cpu:.1f} %" + ("" if p.get("cpu_measurable", True) else " (within noise)")
            out.append(f"| {p['id']} | {p['method']} | {pss} | {cpu} |")
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run_dir")
    ap.add_argument("--apk", action="append", default=[])
    ap.add_argument("--budgets", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "budgets.json"))
    ap.add_argument("--keep-apk", action="store_true", help="keep the apk sizes of the existing report.json (run.sh --idle-only)")
    a = ap.parse_args()
    rep = build(a.run_dir, a.apk, read_json(a.budgets))
    old = os.path.join(a.run_dir, "report.json")
    if a.keep_apk and not a.apk and os.path.exists(old):
        rep["apk"] = read_json(old).get("apk", [])
    with open(os.path.join(a.run_dir, "report.json"), "w") as f:
        json.dump(rep, f, indent=1)
    md = markdown(rep)
    with open(os.path.join(a.run_dir, "report.md"), "w") as f:
        f.write(md)
    print(md)


if __name__ == "__main__":
    main()
