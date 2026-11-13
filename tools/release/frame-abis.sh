#!/usr/bin/env bash
# Prints the ABIs the app's release builds, one APK each: every ABI that some frame's device runs
# (`abis` in frames/<id>/design/frame.json, #98), in frame-id order, each once, space-separated.
# Fails if a frame's `abis` isn't a non-empty list of names, or if no frame declares any.
# Read-only. Needs jq. release.yml, test-build.yml and pr.yml use it; app/kiosk/build.gradle.kts reads
# the same files, and ApkAbiListsTest checks both against the app's ApkAbi.
#   tools/release/frame-abis.sh [repo root]
set -euo pipefail
cd "${1:-$(dirname "$0")/../..}"
command -v jq >/dev/null || { echo "frame-abis.sh needs jq" >&2; exit 1; }
# One jq program over all frames: jq 1.8 exits 0 when an error() hits an input other than the last.
out=$(jq -rn '
  [inputs | {file: input_filename, abis}]
  | map(if (.abis | type) == "array" and (.abis | length) > 0 and all(.abis[]; type == "string") then .abis
        else error("\(.file): abis must be a non-empty list of ABI names, e.g. [\"armeabi-v7a\"]") end)
  | reduce .[][] as $a ([]; if index([$a]) then . else . + [$a] end) | join(" ")' frames/*/design/frame.json)
[ -n "$out" ] || { echo "no frame declares abis (frames/*/design/frame.json)" >&2; exit 1; }
echo "$out"
