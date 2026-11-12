#!/usr/bin/env bash
# Glassworks perf test: grade a frame (MIT). Docs: docs/perf.md.
#
# What it touches on the device:
#   - Opens the test page once per run with the kiosk's one-off `--es open` extra. The saved
#     dashboard URL is never changed. The page is served from this machine over `adb reverse`.
#   - Sends `--es screen on` (the kiosk's own wake intent) during warm-ups, so the screen stays on
#     without changing the idle timeout. It changes no setting.
#   - Taps the test page's tap target (`input tap`, centre of the screen) to measure input latency.
#   - Force-stops and restarts the app for each cold start, with KEYCODE_WAKEUP in between so the
#     screen is on. The last restart puts the saved dashboard back; the exit trap does the same if
#     the run stops early.
#   Read-only otherwise: getprop, /proc, dumpsys meminfo and SurfaceFlinger, logcat.
#
# Usage: tools/perf/run.sh --frame aqara-s1-plus [--serial S] [--runs 3] [--out DIR]
#          [--skip-synthetic] [--skip-real-use] [--taps 10] [--quick] [--apk FILE]...
#        tools/perf/run.sh --frame ID --out DIR --idle-only LABEL   # one idle sample (pane diffs)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb}"
FRAME="" RUNS=3 OUT="" PORT=8089 PACKAGE=sh.edm.s1kiosk TAPS=10 QUICK=0
SYNTH=1 REAL=1 IDLE_ONLY="" MAX_TEMP_C=50 APKS=()
# Waits, in seconds. Only the off-device test (test/test_runner.py) shortens them.
IDLE_SETTLE="${GW_PERF_IDLE_SETTLE:-30}" IDLE_WINDOW="${GW_PERF_IDLE_WINDOW:-15}"
COLD_SETTLE="${GW_PERF_COLD_SETTLE:-8}" RUN_GAP="${GW_PERF_RUN_GAP:-10}"

while [ $# -gt 0 ]; do
  case "$1" in
    --frame) FRAME="$2"; shift 2 ;;
    --serial) export ANDROID_SERIAL="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --package) PACKAGE="$2"; shift 2 ;;
    --taps) TAPS="$2"; shift 2 ;;
    --quick) QUICK=1; shift ;;
    --skip-synthetic) SYNTH=0; shift ;;
    --skip-real-use) REAL=0; shift ;;
    --idle-only) IDLE_ONLY="$2"; shift 2 ;;
    --max-start-temp) MAX_TEMP_C="$2"; shift 2 ;;
    --apk) APKS+=(--apk "$2"); shift 2 ;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$FRAME" ] || { echo "--frame is required (e.g. aqara-s1-plus)" >&2; exit 2; }
[ "$RUNS" -ge 1 ] || { echo "--runs must be at least 1" >&2; exit 2; }
OUT="${OUT:-perf-$FRAME-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
export ADB

run() { python3 "$HERE/perfrun.py" "$@" --package "$PACKAGE" --frame "$FRAME" --out "$OUT"; }
sh_dev() { "$ADB" shell "$@" | tr -d '\r'; }
log() { echo "[$(date +%H:%M:%S)] $*" >&2; }

thermal() { # "<phase>\t<zone>=<°C> ..." into thermal.tsv
  local t
  t=$(sh_dev 'for z in /sys/class/thermal/thermal_zone*; do echo "$(cat $z/type)=$(cat $z/temp)"; done 2>/dev/null' | tr '\n' ' ')
  printf '%s\t%s\n' "$1" "$t" >> "$OUT/thermal.tsv"
}

cpu_temp_c() {
  sh_dev 'for z in /sys/class/thermal/thermal_zone*; do case $(cat $z/type) in *cpu*) cat $z/temp;; esac; done 2>/dev/null' \
    | awk 'NF { t = $1 > 1000 ? $1 / 1000 : $1; if (t > m) m = t } END { print int(m) }'
}

wait_cool() {
  local i t
  for i in $(seq 1 20); do
    t=$(cpu_temp_c)
    [ "${t:-0}" -le "$MAX_TEMP_C" ] && { log "CPU ${t} °C"; return 0; }
    log "CPU ${t} °C > ${MAX_TEMP_C} °C; waiting 30 s ($i/20)"
    sleep 30
  done
  log "still above ${MAX_TEMP_C} °C after 10 min; going on (recorded in thermal.tsv)"
}

"$ADB" get-state >/dev/null || { echo "no device" >&2; exit 1; }
sh_dev pm path "$PACKAGE" | grep -q package: || { echo "$PACKAGE is not installed" >&2; exit 1; }

if [ -n "$IDLE_ONLY" ]; then
  n=0
  for f in "$OUT"/idle-"$IDLE_ONLY"-*.json; do [ -e "$f" ] && n=$((n + 1)); done
  [ -f "$OUT/device.json" ] || run device
  thermal "idle-$IDLE_ONLY-$n"
  log "idle sample '$IDLE_ONLY' #$n"
  run idle --label "$IDLE_ONLY" --run "$n" --settle "$IDLE_SETTLE" --window "$IDLE_WINDOW"
  python3 "$HERE/report.py" "$OUT" ${APKS[@]+"${APKS[@]}"} >/dev/null
  log "report: $OUT/report.json"
  exit 0
fi

SERVER_PID="" ON_TEST_PAGE=0
cleanup() {
  if [ "$ON_TEST_PAGE" = 1 ]; then
    log "restoring the dashboard (restart the app)"
    sh_dev am force-stop "$PACKAGE" || true
    sh_dev am start -n "$PACKAGE/.MainActivity" >/dev/null || true
  fi
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  "$ADB" reverse --remove "tcp:$PORT" 2>/dev/null || true
}
trap cleanup EXIT

run device
thermal start
VSYNC=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["display"]["vsync_ms"] or 16.667)' "$OUT/device.json")
log "device recorded; vsync ${VSYNC} ms"
wait_cool

if [ "$SYNTH" = 1 ]; then
  python3 -m http.server --bind 127.0.0.1 "$PORT" --directory "$HERE/page" >"$OUT/server.log" 2>&1 &
  SERVER_PID=$!
  "$ADB" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
  for k in $(seq 1 "$RUNS"); do
    nonce=$(python3 -c 'import secrets; print(secrets.token_hex(4))')
    input=$([ "$TAPS" -gt 0 ] && echo 1 || echo 0)
    url="http://127.0.0.1:$PORT/index.html?run=$k&seed=1&vsync=$VSYNC&nonce=$nonce&input=$input&quick=$QUICK"
    log "synthetic run $k/$RUNS"
    ON_TEST_PAGE=1
    thermal "synthetic-$k"
    run synthetic --run "$k" --url "$url" --nonce "$nonce" --taps "$TAPS"
    sleep "$RUN_GAP"
  done
  log "memory on the test page after the ladder"
  run idle --label after-synthetic --run 1 --settle 5 --window "$IDLE_WINDOW"
fi

if [ "$REAL" = 1 ]; then
  for k in $(seq 1 "$RUNS"); do
    log "cold start $k/$RUNS"
    thermal "coldstart-$k"
    run coldstart --run "$k" --settle "$COLD_SETTLE"
    ON_TEST_PAGE=0
    log "idle on the dashboard $k/$RUNS"
    run idle --label all-on --run "$k" --settle "$IDLE_SETTLE" --window "$IDLE_WINDOW"
  done
elif [ "$ON_TEST_PAGE" = 1 ]; then
  cleanup
  ON_TEST_PAGE=0
fi

thermal end
python3 "$HERE/report.py" "$OUT" ${APKS[@]+"${APKS[@]}"}
log "report: $OUT/report.json and $OUT/report.md"
