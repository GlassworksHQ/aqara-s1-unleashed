#!/usr/bin/env bash
# Prints the ABIs the app's release builds, one APK each: every ABI that some frame's device runs
# (`abis` in frames/<id>/design/frame.json, #98), in frame-id order, each once, space-separated.
# Read-only. release.yml, test-build.yml and pr.yml use it; app/kiosk/build.gradle.kts reads the same
# files, and ApkAbiListsTest checks both against the app's ApkAbi.
#   tools/release/frame-abis.sh [repo root]
set -euo pipefail
cd "${1:-$(dirname "$0")/../..}"
jq -r '.abis // error("\(input_filename) has no abis") | .[]' frames/*/design/frame.json | awk '!seen[$0]++' | xargs
