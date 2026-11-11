#!/usr/bin/env python3
"""The Rust crates in rs-matter-android's native library (librs_matter_android.so), as the
Markdown table THIRD-PARTY-NOTICES.md carries under "rs-matter-android".

    tools/release/rust-notices.py [path/to/rs-matter]   (default: app/third_party/rs-matter)

Needs cargo (the toolchain the library builds with). Lists what links into the library for
armv7-linux-androideabi: normal dependencies, no build scripts or proc-macros. For each crate:
version, licence as declared (SPDX), authors as declared, and where its source is.
"""
import json
import os
import subprocess
import sys

TARGET = "armv7-linux-androideabi"


def main() -> int:
    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ws = sys.argv[1] if len(sys.argv) > 1 else os.path.join(repo, "app", "third_party", "rs-matter")
    tree = subprocess.run(
        ["cargo", "tree", "-p", "rs-matter-android", "--target", TARGET,
         "-e", "normal,no-proc-macro", "--prefix", "none"],
        cwd=ws, check=True, capture_output=True, text=True,
    ).stdout
    want = set()
    for line in tree.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1].startswith("v"):
            want.add((parts[0], parts[1][1:]))
    meta = json.loads(subprocess.run(
        ["cargo", "metadata", "--format-version", "1", "--filter-platform", TARGET],
        cwd=ws, check=True, capture_output=True, text=True,
    ).stdout)
    rows = []
    for p in meta["packages"]:
        if (p["name"], p["version"]) not in want:
            continue
        src = p.get("repository") or (
            "this fork" if p["name"].startswith("rs-matter") else f"https://crates.io/crates/{p['name']}")
        authors = ", ".join(a.split(" <")[0] for a in p.get("authors") or []) or "see the crate"
        rows.append((p["name"], p["version"], (p.get("license") or "see the crate").replace("/", " OR "),
                     authors, src))
    rows.sort()
    print("| Crate | Version | Licence | Copyright (authors) | Source |")
    print("|---|---|---|---|---|")
    for name, version, lic, authors, src in rows:
        print(f"| `{name}` | {version} | {lic} | {authors.replace('|', '/')} | {src} |")
    print(f"\n{len(rows)} crates.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
