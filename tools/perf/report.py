#!/usr/bin/env python3
"""Build report.json and report.md from a tools/perf run directory (MIT). Offline; no adb.

    python3 tools/perf/report.py <run-dir> [--apk app.apk ...] [--budgets tools/perf/budgets.json]

The report schema (glassworks.perf/1) is documented in docs/perf.md.
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

SCHEMA = "glassworks.perf/1"
SETTLE_GAP_MS = 750
SF_MIN_COVERAGE = 0.8
FAMILIES = ["css-transform", "css-opacity", "svg", "canvas2d", "ha-cards"]


def read_json(path):
    with open(path) as f:
        return json.load(f)


def read_jsonl(path):
    with open(path) as f:
        return [json.loads(l) for l in f if l.strip()]


# ---- synthetic

def step_frames(pl):
    """Frame stats for one step: SurfaceFlinger presents when they cover the window, else rAF."""
    raf = pl.get("raf") or {"n": 0}
    sf = pl.get("sf") or {}
    pres = sf.get("presents_ms") or []
    sf_stats = P.frame_stats(P.intervals(pres), sf.get("period_ms")) if len(pres) > 1 else {"n": 0}
    coverage = sf_stats["n"] / raf["n"] if raf.get("n") else 0
    use_sf = sf_stats["n"] and coverage >= SF_MIN_COVERAGE
    return {"source": "surfaceflinger" if use_sf else "raf", "stats": sf_stats if use_sf else raf,
            "raf": raf, "sf": sf_stats, "sf_coverage": round(coverage, 2)}


def synthetic_run(lines):
    hello = next((l for l in lines if l["t"] == "hello"), {})
    steps = [dict(stage=l["stage"], n=l["n"], **step_frames(l)) for l in lines if l["t"] == "step"]
    js = next((l for l in lines if l["t"] == "js"), None)
    inp = next((l for l in lines if l["t"] == "input"), None)
    ladder = {}
    for fam in FAMILIES:
        fs = [(s["n"], s["stats"]) for s in steps if s["stage"] == fam]
        if fs:
            ladder[fam] = {"held_60fps": G.capacity(fs, {G.GOOD}),
                           "held_30fps": G.capacity(fs, {G.GOOD, G.NI}),
                           "top_step": max(n for n, _ in fs),
                           "steps": [{"n": s["n"], "source": s["source"], "sf_coverage": s["sf_coverage"], **s["stats"]}
                                     for s in steps if s["stage"] == fam]}
    ref = next((s for s in steps if s["stage"] == "reference"), None)
    scroll = next((s for s in steps if s["stage"] == "scroll"), None)
    return {
        "ua": hello.get("ua"), "page_load": hello.get("load") or {}, "dpr": hello.get("dpr"),
        "ladder": ladder,
        "reference": ref and {"source": ref["source"], "sf_coverage": ref["sf_coverage"], **ref["stats"]},
        "scroll": scroll and {"source": scroll["source"], **scroll["stats"]},
        "js": js and {"median_ms": js["median_ms"], "p95_ms": js["p95_ms"],
                      "checksum_ok": js["checksum"] == js["expected"]},
        "input": inp and {"method": inp["method"], "taps": inp["taps"], "inp_ms": inp["inp_ms"] if inp["taps"] else None},
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
    ref = [r["reference"] for r in runs if r["reference"]]
    return {
        "runs": len(runs),
        "complete_runs": sum(1 for r in runs if r["complete"]),
        "ladder": ladder,
        "reference": {k: sp(lambda r, k=k: (r["reference"] or {}).get(k)) for k in ("p95_ms", "median_ms", "jank_pct", "frozen_pct", "fps")},
        "reference_sources": sorted({x["source"] for x in ref}),
        "scroll": {k: sp(lambda r, k=k: (r["scroll"] or {}).get(k)) for k in ("p95_ms", "jank_pct", "fps")},
        "js_burst_ms": sp(lambda r: (r["js"] or {}).get("median_ms")),
        "js_checksum_ok": all((r["js"] or {}).get("checksum_ok") for r in runs) if runs else None,
        "inp_ms": sp(lambda r: (r["input"] or {}).get("inp_ms")),
        "input_method": sorted({r["input"]["method"] for r in runs if r["input"]}),
        "page_lcp_ms": sp(lambda r: r["page_load"].get("lcp_ms") or r["page_load"].get("fcp_ms")),
    }


# ---- real use

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
    if c.get("error") or c.get("page_start_mono") is None:
        return {"ttid_ms": c.get("ttid_ms"), "error": c.get("error")}
    start, stop = c["page_start_mono"] * 1000, c["page_stop_mono"] * 1000
    settle, how = visual_settle(c.get("presents_mono_ms") or [], stop, c.get("ended_quiet", False))
    return {"ttid_ms": c.get("ttid_ms"), "am_total_ms": c.get("am_total_ms"),
            "page_stop_ms": round(stop - start), "dashboard_load_ms": round(settle - start), "settle": how}


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
        "app_pss_kb": sum(p["pss_kb"] for p in app.values()),
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
        "cold_start_ttid_ms": P.spread([c["ttid_ms"] for c in cold]),
        "dashboard_load_ms": P.spread([c.get("dashboard_load_ms") for c in cold]),
        "dashboard_page_stop_ms": P.spread([c.get("page_stop_ms") for c in cold]),
        "settle_how": sorted({c["settle"] for c in cold if c.get("settle")}),
        "errors": [c["error"] for c in cold if c.get("error")],
        "idle": {
            "samples": len(on),
            "app_pss_kb": P.spread([m["app_pss_kb"] for m in mems]),
            "by_role_pss_kb": {r: P.spread([m["by_role"].get(r, {}).get("pss_kb") for m in mems]) for r in roles},
            "main_kb": {k: P.spread([m["main"].get(k) for m in mems]) for k in sorted({k for m in mems for k in m["main"]})},
            "radio_pss_kb": P.spread([m["radio_pss_kb"] for m in mems]),
            "device_used_pct": P.spread([100 - 100 * m["mem_available_kb"] / m["mem_total_kb"] for m in mems if m["mem_total_kb"]]),
            "cpu_app_pct_of_core": P.spread([c["app_pct_of_core"] for c in cpus]),
            "cpu_radio_pct_of_core": P.spread([c["radio_pct_of_core"] for c in cpus]),
            "cpu_device_busy_pct": P.spread([c["device_busy_pct"] for c in cpus]),
            "frames": {k: P.spread([idle_frames(i)[k] for i in on]) for k in ("presented_fps", "p95_ms", "max_ms")},
        },
        "peak": {
            "app_pss_kb": max((m["app_pss_kb"] for m in allmem), default=None),
            "app_rss_hwm_kb": max((sum(r["rss_peak_kb"] for r in m["by_role"].values()) for m in allmem), default=None),
            "mem_available_min_kb": min((m["mem_available_kb"] for m in allmem if m["mem_available_kb"]), default=None),
        },
    }


def pane_diffs(idles, package):
    """Each `<pane>-off` label against the all-on samples. A delta inside the all-on range is noise."""
    on = idles.get("all-on", [])
    if not on:
        return []
    on_pss = [app_memory(i)["app_pss_kb"] for i in on]
    on_cpu = [app_cpu(i, package)["app_pct_of_core"] for i in on]
    out = []
    for label, runs in sorted(idles.items()):
        if not label.endswith("-off"):
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

def build(run_dir, apks=(), budgets=None):
    device = read_json(os.path.join(run_dir, "device.json"))
    package = device["app"]["package"]
    syn = [synthetic_run(read_jsonl(p)) for p in sorted(glob.glob(os.path.join(run_dir, "synthetic-*.jsonl")))]
    cold = [coldstart_run(read_json(p)) for p in sorted(glob.glob(os.path.join(run_dir, "coldstart-*.json")))]
    idles = {}
    for p in sorted(glob.glob(os.path.join(run_dir, "idle-*.json"))):
        i = read_json(p)
        idles.setdefault(i["label"], []).append(i)

    s = synthetic_summary(syn) if syn else None
    r = real_use_summary(cold, idles, package) if (cold or idles) else None
    med = lambda x: x and x["median"]  # noqa: E731
    load = med(r and r["dashboard_load_ms"]) or med(s and s["page_lcp_ms"])
    values = {
        "frame_p95_ms": med(s and s["reference"]["p95_ms"]),
        "frame_jank_pct": med(s and s["reference"]["jank_pct"]),
        "frame_frozen_pct": med(s and s["reference"]["frozen_pct"]),
        "load_ms": load,
        "inp_ms": med(s and s["inp_ms"]),
        "cold_start_ms": med(r and r["cold_start_ttid_ms"]),
    }
    grade = G.headline(values)
    grade["load_from"] = "dashboard" if med(r and r["dashboard_load_ms"]) else ("test-page" if load else None)

    budgets = budgets or {"default": {"app_pss_max_pct_of_ram": 50, "device_available_min_pct": 15}, "frames": {}}
    budget = budgets["frames"].get(device["frame"], budgets["default"])
    memory = None
    if r:
        memory = {"idle": r["idle"], "peak": r["peak"], "budget": G.memory_budget(
            {"app_pss_peak_kb": r["peak"]["app_pss_kb"], "mem_total_kb": device["mem_total_kb"],
             "mem_available_min_kb": r["peak"]["mem_available_min_kb"]}, budget)}

    gv = next((geckoview_version(x["ua"]) for x in syn if x["ua"]), None)
    js_band = G.band(med(s and s["js_burst_ms"]), G.JS_BURST)
    return {
        "schema": SCHEMA,
        "frame": {"id": device["frame"], "device": {k: v for k, v in device.items() if k not in ("frame", "app")},
                  "app": dict(device["app"], geckoview=gv)},
        "apk": apk_sizes(apks),
        "grade": grade,
        "synthetic": s and dict(s, js_band=js_band, per_run=syn),
        "real_use": r and dict(r, per_run=cold),
        "memory": memory,
        "panes": pane_diffs(idles, package) + radio_pane(r),
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


def markdown(rep):
    g = rep["grade"]
    dev = rep["frame"]["device"]
    app = rep["frame"]["app"]
    out = [f"# Perf report: {rep['frame']['id']}", "", device_line(dev, app), "",
           f"**Grade: {g['grade'] or 'none'}**" + (f" (provisional: {', '.join(g['missing'])} not measured)" if g["provisional"] else ""),
           "", "| Component | Metric | Value | Band |", "|---|---|---|---|"]
    for comp, metrics in G.COMPONENTS.items():
        for m in metrics:
            v = g["metrics"][m]
            out.append(f"| {comp} | {m} | {v['value'] if v['value'] is not None else 'n/a'} | {v['band'] or 'n/a'} |")
    s = rep["synthetic"]
    if s:
        out += ["", "## Animations it can handle", "",
                "Largest element count before the first step that missed the target (median of runs). "
                "A + means it held at the ladder's last step.", "",
                "| Family | 60 fps | 30 fps |", "|---|---|---|"]
        for fam, v in s["ladder"].items():
            out.append(f"| {fam} | {fmt(v['held_60fps'], plus=v['held_60fps_at_top'])} | {fmt(v['held_30fps'], plus=v['held_30fps_at_top'])} |")
        out += ["", f"Reference load p95 {fmt(s['reference']['p95_ms'], ' ms')}, jank {fmt(s['reference']['jank_pct'], ' %')}, "
                    f"frames from {', '.join(s['reference_sources']) or 'n/a'}.",
                f"Scroll p95 {fmt(s['scroll']['p95_ms'], ' ms')}. INP {fmt(s['inp_ms'], ' ms')} ({', '.join(s['input_method']) or 'not measured'}).",
                f"JS state burst {fmt(s['js_burst_ms'], ' ms')} (band {s['js_band'] or 'n/a'}, checksum {'ok' if s['js_checksum_ok'] else 'MISMATCH'}). "
                f"Test page LCP {fmt(s['page_lcp_ms'], ' ms')}."]
    r = rep["real_use"]
    if r:
        i = r["idle"]
        out += ["", "## Real use", "",
                f"Cold start (TTID) {fmt(r['cold_start_ttid_ms'], ' ms')}. Dashboard load {fmt(r['dashboard_load_ms'], ' ms')} "
                f"({', '.join(r['settle_how']) or 'n/a'}); its load event {fmt(r['dashboard_page_stop_ms'], ' ms')}.",
                f"Idle: app PSS {fmt(i['app_pss_kb'], ' MB', 1024)}, device RAM in use {fmt(i['device_used_pct'], ' %')}, "
                f"app CPU {fmt(i['cpu_app_pct_of_core'], ' % of a core')}, device busy {fmt(i['cpu_device_busy_pct'], ' %')}, "
                f"{fmt(i['frames']['presented_fps'], ' frames/s')} presented."]
        out += ["", "| Process | PSS at idle |", "|---|---|"] + [f"| {k} | {fmt(v, ' MB', 1024)} |" for k, v in i["by_role_pss_kb"].items()]
    m = rep["memory"]
    if m and m["budget"]:
        out += ["", "## Memory budget", ""]
        for k, c in m["budget"]["checks"].items():
            val = "n/a" if c["value_kb"] is None else f"{c['value_kb'] / 1024:.0f} MB"
            out.append(f"- {k}: {val} against {c['budget_kb'] / 1024:.0f} MB: {'ok' if c['ok'] else 'over' if c['ok'] is False else 'n/a'}")
    if rep["panes"]:
        out += ["", "## Panes", "", "| Pane | Method | PSS | CPU |", "|---|---|---|---|"]
        for p in rep["panes"]:
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
    a = ap.parse_args()
    rep = build(a.run_dir, a.apk, read_json(a.budgets))
    with open(os.path.join(a.run_dir, "report.json"), "w") as f:
        json.dump(rep, f, indent=1)
    md = markdown(rep)
    with open(os.path.join(a.run_dir, "report.md"), "w") as f:
        f.write(md)
    print(md)


if __name__ == "__main__":
    main()
