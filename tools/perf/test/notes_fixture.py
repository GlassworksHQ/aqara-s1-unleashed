"""Make a notes test fixture from a real run's report.json (MIT). Reads the report only.

    python3 tools/perf/test/notes_fixture.py <run-dir>/report.json tools/perf/test/notes-runs/<name>.json

Keeps what tools/perf/notes.py reads and drops the bulk (real use, the animation page, per-run frame
lists). The report holds no serials or addresses (docs/perf.md, "The summary").
"""
import json
import sys

DROP_TOP = ("detail", "real_use", "apk", "notes")   # notes are the output under test, not its input
DROP_TIER = ("scroll_runs", "updates_runs", "arrivals", "state_ticks")


def trim(rep):
    rep = {k: v for k, v in rep.items() if k not in DROP_TOP}
    ref = rep["graded"]["reference"]
    ref.pop("per_run", None)
    ref["tiers"] = {t: {k: v for k, v in s.items() if k not in DROP_TIER} for t, s in ref["tiers"].items()}
    return rep


if __name__ == "__main__":
    with open(sys.argv[1]) as f:
        rep = trim(json.load(f))
    with open(sys.argv[2], "w") as f:
        json.dump(rep, f, indent=1, sort_keys=True)
        f.write("\n")
