#!/usr/bin/env python3
"""APK size per frame and per pane, for CI on every pull request (MIT). Docs: docs/size.md.

Reads the release APKs (one per ABI), splits each into its parts (the engine, the core, each pane,
the shared libraries, assets and native code) with apkanalyzer's dex listing and tools/size/parts.json,
compares them with the base branch's APKs, checks the frame's size budget (frames/<id>/budgets.json,
`size`) and that pane assets live under assets/panes/<id>/. Read-only: it only reads the APKs.

  tools/size/apksize.py report --frame aqara-s1-plus --apk armeabi-v7a=head-v7.apk --apk arm64-v8a=head-64.apk \\
      [--base-apk armeabi-v7a=base-v7.apk ...] [--base-budgets base/frames/aqara-s1-plus/budgets.json] \\
      --json size.json --md size.md
  tools/size/apksize.py render size.json      # the markdown again, from the numbers alone (the PR comment)

Exit status: 0; 1 when an APK or a pane is over its budget or the assets lint fails (the report is
written either way); 2 on bad input (a bad argument, a missing budgets file, an APK whose native
libraries aren't its ABI's), when apkanalyzer fails, or when `render` refuses a report.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
SCHEMA = "glassworks.apk-size/1"
MARKER = "<!-- glassworks-apk-size -->"
ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,40}$")
ABI_RE = re.compile(r"^(armeabi-v7a|arm64-v8a|x86|x86_64)$")
KINDS = ("dex", "native", "assets", "other")
# Why a base APK is missing (the size job's --base-missing): fixed words, so the comment only says these.
BASE_MISSING = {"no-base-commit": "there is no base commit", "base-build-failed": "the base didn't build"}
# The comment's limits: a report can't make it long, whatever it holds.
MAX_ROWS, MAX_FINDINGS = 40, 20


def die(msg):
    """Bad input or a tool that failed: exit status 2 (1 is reserved for over budget)."""
    print(msg, file=sys.stderr)
    sys.exit(2)


def read_json(path):
    with open(path) as f:
        return json.load(f)


# ---- the dex: apkanalyzer's per-class sizes

def analyzer_path():
    """apkanalyzer from the Android SDK's command-line tools: APKANALYZER, else the one on PATH
    (setup-android puts its cmdline-tools there), else $ANDROID_HOME/cmdline-tools/latest."""
    import shutil
    if os.environ.get("APKANALYZER"):
        return os.environ["APKANALYZER"]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    return shutil.which("apkanalyzer") or os.path.join(sdk, "cmdline-tools", "latest", "bin", "apkanalyzer")


def dex_classes(apk, analyzer=None):
    """[(class, defined bytes)] from `apkanalyzer dex packages --defined-only`: its class lines,
    `C d <defined methods> <referenced methods> <size> <name>`."""
    out = subprocess.run([analyzer or analyzer_path(), "dex", "packages", "--defined-only", apk],
                         capture_output=True, text=True, timeout=600)
    if out.returncode:
        die(f"apkanalyzer failed on {apk}: {out.stderr.strip()[:300]}")
    return parse_classes(out.stdout)


def parse_classes(text):
    out = []
    for line in text.splitlines():
        f = line.split()
        if len(f) >= 6 and f[0] == "C" and f[4].isdigit():
            out.append((" ".join(f[5:]), int(f[4])))
    return out


# ---- who owns what

def owner_of_class(name, parts):
    """("engine"|"core"|"pane"|"library", id or None): the longest matching dex prefix wins."""
    best, who = "", ("core", None)
    cands = [(p, ("engine", None)) for p in parts["engine"]["dex"]]
    cands += [(p, ("library", k)) for k, v in parts["libraries"].items() for p in v.get("dex", [])]
    cands += [(p, ("pane", k)) for k, v in parts["panes"].items() for p in v.get("dex", [])]
    for prefix, w in cands:
        hit = name.startswith(prefix) and (prefix.endswith(".") or name == prefix or name[len(prefix):][:1] in ("$", ""))
        if hit and len(prefix) > len(best):
            best, who = prefix, w
    return who


def owner_of_file(path, parts, lint):
    """The owner and kind of one APK entry that isn't dex. Pane assets must be under
    assets/panes/<id>/, or under a path parts.json names for that pane (`assets`: the radio bundle's
    assets/radio-bundle/, which the app and the release checks expect there); any other asset that
    isn't the engine's or the core's is a lint finding."""
    name = path.rsplit("/", 1)[-1]
    if path.startswith("lib/"):
        if name in parts["engine"]["native"]:
            return ("engine", None), "native"
        for k, v in parts["libraries"].items():
            if name in v.get("native", []):
                return ("library", k), "native"
        for k, v in parts["panes"].items():
            if name in v.get("native", []):
                return ("pane", k), "native"
        return ("core", None), "native"
    if path.startswith("assets/"):
        if path in parts["engine"]["assets"]:
            return ("engine", None), "assets"
        m = re.match(r"assets/panes/([^/]+)/", path)
        if m:
            if m.group(1) in parts["panes"]:
                return ("pane", m.group(1)), "assets"
            lint.append(f"{path}: under assets/panes/ but `{m.group(1)}` isn't a pane")
            return ("core", None), "assets"
        if any(path.startswith(p) for p in parts["core_assets"]):
            return ("core", None), "assets"
        for k, v in parts["panes"].items():
            if any(path.startswith(p) for p in v.get("assets", [])):
                return ("pane", k), "assets"
        lint.append(f"{path}: an asset that is neither the engine's, the core's, nor under assets/panes/<id>/")
        return ("core", None), "assets"
    for k, v in parts["libraries"].items():
        if any(path.startswith(p) for p in v.get("files", [])):
            return ("library", k), "other"
    return ("core", None), "other"


def empty():
    return {k: 0 for k in KINDS}


def breakdown(apk, classes, parts):
    """One APK split into its parts, in APK bytes (compressed, as stored), so the parts add up to
    the file. The dex files' bytes are shared out by apkanalyzer's per-class sizes."""
    lint, owners = [], {}

    def add(who, kind, n):
        key = who[0] if who[1] is None else f"{who[0]}:{who[1]}"
        owners.setdefault(key, empty())[kind] += n

    dex_bytes, abis, stored = 0, set(), 0
    with zipfile.ZipFile(apk) as z:
        for i in z.infolist():
            stored += i.compress_size
            if re.match(r"classes\d*\.dex$", i.filename):
                dex_bytes += i.compress_size
                continue
            if i.filename.startswith("lib/") and i.filename.count("/") >= 2:
                abis.add(i.filename.split("/")[1])
            who, kind = owner_of_file(i.filename, parts, lint)
            add(who, kind, i.compress_size)
    total_cls = sum(n for _, n in classes) or 1
    by_owner = {}
    for name, n in classes:
        who = owner_of_class(name, parts)
        by_owner[who] = by_owner.get(who, 0) + n
    shared = 0
    for who, n in sorted(by_owner.items(), key=lambda kv: str(kv[0])):
        share = round(dex_bytes * n / total_cls)
        add(who, "dex", share)
        shared += share
    if classes and shared != dex_bytes:
        add(("core", None), "dex", dex_bytes - shared)       # rounding
    elif not classes:
        add(("core", None), "dex", dex_bytes)
    found = {w[1] for w in by_owner if w[0] == "pane"}
    missing = sorted(set(parts["panes"]) - found) if classes else []
    return {"bytes": os.path.getsize(apk), "stored_bytes": stored, "dex_bytes": dex_bytes, "abis": sorted(abis),
            "owners": owners, "lint": lint, "panes_without_code": missing}


def rows(b, parts):
    """The report's rows: the engine, the core (with the libraries only it uses), each pane (with the
    libraries only it uses), each shared library, and the zip's own overhead."""
    o = b["owners"]
    get = lambda k: dict(o.get(k, empty()))  # noqa: E731
    out = {"engine": get("engine"), "core": get("core")}
    for pid in parts["panes"]:
        out[f"pane:{pid}"] = get(f"pane:{pid}")
    for lid, lib in parts["libraries"].items():
        mine = get(f"library:{lid}")
        users = lib["used_by"]
        if len(users) == 1:
            dest = "core" if users[0] == "core" else ("engine" if users[0] == "engine" else f"pane:{users[0]}")
            for k in KINDS:
                out[dest][k] += mine[k]
        else:
            out[f"shared:{lid}"] = mine
    out["zip-overhead"] = dict(empty(), other=b["bytes"] - b["stored_bytes"])
    for r in out.values():
        r["total"] = sum(r[k] for k in KINDS)
    return out


# ---- budgets

def check(report, budgets, base_budgets=None):
    """Failures, budget changes and notes against the frame's `size` budget: the APK per ABI
    (apk_max_bytes) and each pane's own total (panes.<id>.size_max_bytes). The budget is the one in
    this branch's file, so going over passes only when the same PR raises it. `changes` lists every
    budget this PR raises, lowers or adds (one the base didn't have), and `raised` says whether any
    went up: the comment leads with it. A budget the base had and this branch drops is listed too
    (removed), for the APK of an ABI and for a pane. Without the base's budgets file there are no
    changes to show."""
    fails, notes, changes, raised = [], [], [], False
    size = (budgets or {}).get("size") or {}
    base_size = ((base_budgets or {}).get("size") or {})

    def change(what, old, new):
        nonlocal raised
        if base_budgets is None or new == old:
            return
        if new is None:
            changes.append(f"{what}: this PR removes the budget of {mb(old)}")
            return
        if old is None:
            changes.append(f"{what}: this PR adds a budget of {mb(new)} (the base had none)")
            raised = True
        else:
            changes.append(f"{what}: this PR {'raises' if new > old else 'lowers'} the budget from {mb(old)} to {mb(new)}")
            raised = raised or new > old

    for abi, a in report["abis"].items():
        cap = (size.get("apk_max_bytes") or {}).get(abi)
        if cap is None:
            notes.append(f"{abi}: no APK budget")
        elif a["bytes"] > cap:
            fails.append(f"{abi}: the APK is {mb(a['bytes'])}, over its budget of {mb(cap)}")
        change(f"{abi} APK", (base_size.get("apk_max_bytes") or {}).get(abi), cap)
        panes = (budgets or {}).get("panes") or {}
        # The base's panes too, so a pane budget this PR drops (or a pane it drops) is a change.
        for pid in sorted(set(panes) | set(((base_budgets or {}).get("panes") or {}))):
            pcap = (panes.get(pid) or {}).get("size_max_bytes")
            row = a["rows"].get(f"pane:{pid}")
            if pcap is not None and row and row["total"] > pcap:
                fails.append(f"{abi}: {pid} is {mb(row['total'])}, over its budget of {mb(pcap)}")
            if abi == sorted(report["abis"])[0]:
                change(f"pane {pid}", (((base_budgets or {}).get("panes") or {}).get(pid) or {}).get("size_max_bytes"), pcap)
        for f in a["lint"]:
            fails.append(f"{abi}: assets lint: {f}")
        for p in a["panes_without_code"]:
            notes.append(f"{abi}: no code found for pane {p} (tools/size/parts.json)")
    return fails, notes, changes, raised


# ---- the report

def checked_breakdown(path, abi, classes, parts):
    """breakdown(), refusing an APK whose native libraries are another ABI's than its label."""
    b = breakdown(path, classes, parts)
    if b["abis"] != [abi]:
        die(f"{path}: labelled {abi}, but its native libraries are for {', '.join(b['abis']) or 'no ABI'}")
    return b


def build_report(frame, apks, base_apks, parts, budgets, base_budgets, analyzer=None, classes_of=None, base_missing=None):
    """base_missing: {abi: a BASE_MISSING code} for the ABIs whose base APK there isn't."""
    classes_of = classes_of or (lambda p: dex_classes(p, analyzer))
    out = {"schema": SCHEMA, "frame": frame, "abis": {}}
    for abi, path in sorted(apks.items()):
        b = checked_breakdown(path, abi, classes_of(path), parts)
        entry = {"bytes": b["bytes"], "rows": rows(b, parts), "lint": b["lint"], "panes_without_code": b["panes_without_code"]}
        if abi in base_apks:
            bb = checked_breakdown(base_apks[abi], abi, classes_of(base_apks[abi]), parts)
            entry["base_bytes"] = bb["bytes"]
            entry["base_rows"] = rows(bb, parts)
        elif (base_missing or {}).get(abi):
            entry["base_missing"] = base_missing[abi]
        out["abis"][abi] = entry
    fails, notes, changes, raised = check(out, budgets, base_budgets)
    out["budget"] = {"apk_max_bytes": ((budgets or {}).get("size") or {}).get("apk_max_bytes"),
                     "fails": fails, "notes": notes, "changes": changes, "raised": raised, "ok": not fails}
    return out


def mb(n):
    return f"{n / 1e6:.2f} MB"


def signed(n):
    if n == 0:
        return "±0"
    return f"{'+' if n > 0 else '−'}{abs(n) / 1e3:,.1f} kB" if abs(n) < 1e6 else f"{'+' if n > 0 else '−'}{abs(n) / 1e6:.2f} MB"


def label(key):
    kind, _, rid = key.partition(":")
    return {"engine": "Engine (GeckoView)", "core": "Core", "zip-overhead": "Zip overhead"}.get(key) or \
        (f"Pane `{rid}`" if kind == "pane" else f"Shared library `{rid}`")


def validate(rep):
    """A report read back (the PR comment workflow reads it from an artifact): its shape, and names
    that can only be ids, so nothing from it reaches the comment but numbers and known words."""
    if rep.get("schema") != SCHEMA or not isinstance(rep.get("abis"), dict):
        raise ValueError("not an APK size report")
    if not ID_RE.match(str(rep.get("frame", ""))):
        raise ValueError("bad frame id")
    if len(rep["abis"]) > 4:
        raise ValueError("too many ABIs")
    for abi, a in rep["abis"].items():
        if not ABI_RE.match(abi) or not isinstance(a, dict):
            raise ValueError(f"bad ABI {abi!r}")
        for name in ("rows", "base_rows"):
            rs = a.get(name)
            if rs is None and name == "base_rows":
                continue
            if not isinstance(rs, dict) or not all(isinstance(r, dict) for r in rs.values()):
                raise ValueError(f"{abi}: {name} must be an object of rows")
            if len(rs) > MAX_ROWS:
                raise ValueError(f"{abi}: more than {MAX_ROWS} rows")
            for key, r in rs.items():
                kind, _, rid = key.partition(":")
                if key not in ("engine", "core", "zip-overhead") and not (kind in ("pane", "shared") and ID_RE.match(rid)):
                    raise ValueError(f"bad row {key!r}")
                for k in KINDS + ("total",):
                    if not isinstance(r.get(k), int) or isinstance(r.get(k), bool):
                        raise ValueError("sizes must be whole numbers")
        for n in (a.get("bytes"), a.get("base_bytes")):
            if n is not None and (not isinstance(n, int) or isinstance(n, bool)):
                raise ValueError("sizes must be whole numbers")
        if a.get("base_missing") is not None and a["base_missing"] not in BASE_MISSING:
            raise ValueError("bad base_missing")
    b = rep.get("budget")
    if b is not None and not isinstance(b, dict):
        raise ValueError("bad budget")


def clean(text):
    """A finding's text for the comment: plain characters only, no links (anything with ://)."""
    text = re.sub(r"\S*://\S*", "(link removed)", str(text))
    return re.sub(r"[^\w .,:;/()+\-−'`#]", "?", text)[:300]


def findings(items):
    """A list from the report, capped, as comment lines."""
    items = items if isinstance(items, list) else []
    out = [f"- {clean(x)}" for x in items[:MAX_FINDINGS]]
    if len(items) > MAX_FINDINGS:
        out.append(f"- and {len(items) - MAX_FINDINGS} more (the job summary has them all)")
    return out


def markdown(rep):
    validate(rep)
    out = [MARKER, f"### APK size: `{rep['frame']}`", ""]
    b = rep.get("budget") or {}
    caps = b.get("apk_max_bytes") if isinstance(b.get("apk_max_bytes"), dict) else {}
    if b.get("changes"):
        # A PR that raises a budget says so first: that is the decision a reviewer is asked to make.
        out.append("**This PR raises the size budget.**" if b.get("raised") is True else "**This PR changes the size budget.**")
        out += findings(b.get("changes"))
        out.append("")
    out.append("**Over budget or failing the assets lint:**" if not b.get("ok", True) else "Within budget.")
    out += findings(b.get("fails"))
    out += findings(b.get("notes"))
    for abi, a in sorted(rep["abis"].items()):
        if a.get("base_missing"):
            out.append(f"- {abi}: no change column: {BASE_MISSING[a['base_missing']]}.")
    for abi, a in sorted(rep["abis"].items()):
        base = a.get("base_rows")
        cap = caps.get(abi)
        head = f"#### {abi}: {mb(a['bytes'])}"
        if a.get("base_bytes") is not None:
            head += f" ({signed(a['bytes'] - a['base_bytes'])} against the base branch)"
        if isinstance(cap, int) and not isinstance(cap, bool):
            head += f", budget {mb(cap)}"
        out += ["", head, "", "| Part | Dex | Native | Assets | Other | Total | Change |", "|---|---|---|---|---|---|---|"]
        for key, r in a["rows"].items():
            ch = signed(r["total"] - base[key]["total"]) if base and key in base else ("new" if base else "")
            out.append(f"| {label(key)} | {mb(r['dex'])} | {mb(r['native'])} | {mb(r['assets'])} | {mb(r['other'])} "
                       f"| **{mb(r['total'])}** | {ch} |")
        for key in sorted(set(base or {}) - set(a["rows"])):
            out.append(f"| {label(key)} | | | | | gone | {signed(-base[key]['total'])} |")
    out += ["", "Sizes are bytes as stored in the APK; the dex files are shared out by apkanalyzer's per-class "
                "sizes. A pane's row has its own code and the libraries only it uses. "
                "[How this is measured](https://github.com/GlassworksHQ/aqara-s1-unleashed/blob/main/docs/size.md)."]
    return "\n".join(out) + "\n"


def parse_pairs(pairs, what, values=None):
    out = {}
    for p in pairs or []:
        abi, sep, val = p.partition("=")
        if not sep or not ABI_RE.match(abi) or (values is not None and val not in values):
            die(f"{what} {p!r}: give ABI=" + ("|".join(values) if values else "path") + " (armeabi-v7a, arm64-v8a)")
        out[abi] = val
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("report")
    r.add_argument("--frame", required=True)
    r.add_argument("--apk", action="append", required=True, help="ABI=path, one per ABI")
    r.add_argument("--base-apk", action="append", default=[], help="ABI=path of the base branch's APK")
    r.add_argument("--parts", default=os.path.join(HERE, "parts.json"))
    r.add_argument("--budgets", default=None, help="default: frames/<frame>/budgets.json")
    r.add_argument("--base-budgets", default=None)
    r.add_argument("--base-missing", action="append", default=[],
                   help="ABI=" + "|".join(BASE_MISSING) + ": why there is no base APK for that ABI")
    r.add_argument("--apkanalyzer", default=None)
    r.add_argument("--json", required=True)
    r.add_argument("--md", required=True)
    v = sub.add_parser("render")
    v.add_argument("json")
    a = ap.parse_args()
    if a.cmd == "render":
        try:
            sys.stdout.write(markdown(read_json(a.json)))
        except (ValueError, KeyError, TypeError, AttributeError) as e:
            die(f"not rendered: {e}")
        return
    if not ID_RE.match(a.frame):
        die(f"bad frame id {a.frame!r}")
    budgets_path = a.budgets or os.path.join(ROOT, "frames", a.frame, "budgets.json")
    if not os.path.exists(budgets_path):
        die(f"{budgets_path}: no such file")
    base_budgets = read_json(a.base_budgets) if a.base_budgets and os.path.exists(a.base_budgets) else None
    rep = build_report(a.frame, parse_pairs(a.apk, "--apk"), parse_pairs(a.base_apk, "--base-apk"), read_json(a.parts),
                       read_json(budgets_path), base_budgets, analyzer=a.apkanalyzer,
                       base_missing=parse_pairs(a.base_missing, "--base-missing", BASE_MISSING))
    with open(a.json, "w") as f:
        json.dump(rep, f, indent=1)
    with open(a.md, "w") as f:
        f.write(markdown(rep))
    print(markdown(rep))
    sys.exit(0 if rep["budget"]["ok"] else 1)


if __name__ == "__main__":
    main()
