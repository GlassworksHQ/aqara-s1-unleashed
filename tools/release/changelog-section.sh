#!/usr/bin/env bash
# Prints the CHANGELOG.md section for one version (without its "## [x.y.z] - date" heading), for the
# GitHub release notes. Fails if the version has no section or it is empty.
#   tools/release/changelog-section.sh 1.2.3 [CHANGELOG.md]
set -euo pipefail
V=${1:?version, e.g. 1.2.3}; F=${2:-CHANGELOG.md}
out=$(awk -v v="$V" '
  $0 ~ "^## \\[" v "\\]" { on = 1; next }
  on && /^## \[/ { exit }
  on && /^\[[^]]+\]: / { exit }
  on { print }
' "$F" | sed -e '/./,$!d' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')
[ -n "$out" ] || { echo "CHANGELOG.md has no entry for $V" >&2; exit 1; }
printf '%s\n' "$out"
