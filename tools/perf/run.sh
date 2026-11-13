#!/usr/bin/env bash
# Clarity, the Glassworks perf test: grade a frame (MIT). Docs: docs/perf.md.
#
# What it touches on the device:
#   - Opens the test page and the three reference dashboards with the kiosk's one-off `--es open`
#     extra. The saved dashboard URL is never changed. The pages are served from this machine over
#     `adb reverse`, with caching off.
#   - Sends `--es screen on` (the kiosk's own wake intent) during warm-ups, so the screen stays on
#     without changing the idle timeout. It changes no setting.
#   - Taps each reference dashboard's "Living room" toggle (`input tap`) and swipes it (`input swipe`),
#     only where and when the page asks, to measure taps and scrolling.
#   - Force-stops the app for each cold start (KEYCODE_WAKEUP first, so the screen is on); the app
#     comes back on the saved dashboard. If the run stops early or the last cold start fails, the
#     exit trap restarts the app.
#   Read-only otherwise: getprop, /proc, dumpsys meminfo and SurfaceFlinger, logcat.
#
# Usage: tools/perf/run.sh --frame aqara-s1-plus [--name "Aqara S1 Plus"] [--serial S] [--runs 3] [--out DIR]
#          [--skip-synthetic] [--skip-reference] [--skip-real-use] [--taps 10] [--quick] [--apk FILE]...
#        tools/perf/run.sh --frame ID --out DIR --idle-only LABEL   # one idle sample (pane diffs)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb}"
FRAME="" NAME="" RUNS=3 OUT="" PORT=8089 PACKAGE=sh.edm.s1kiosk TAPS=10 QUICK=0
SYNTH=1 REF=1 REAL=1 IDLE_ONLY="" MAX_TEMP_C=50 APKS=() RESTORE=""
# Waits, in seconds. Only the off-device test (test/test_runner.py) shortens them.
IDLE_SETTLE="${GW_PERF_IDLE_SETTLE:-30}" IDLE_WINDOW="${GW_PERF_IDLE_WINDOW:-15}"
COLD_SETTLE="${GW_PERF_COLD_SETTLE:-3}" COLD_TIMEOUT="${GW_PERF_COLD_TIMEOUT:-120}" RUN_GAP="${GW_PERF_RUN_GAP:-10}"
BETWEEN_QUIET="${GW_PERF_BETWEEN_QUIET:-2}" SWITCH_SETTLE="${GW_PERF_SWITCH_SETTLE:-10}"

while [ $# -gt 0 ]; do
  case "$1" in
    --frame) FRAME="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --serial) export ANDROID_SERIAL="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --package) PACKAGE="$2"; shift 2 ;;
    --taps) TAPS="$2"; shift 2 ;;
    --quick) QUICK=1; shift ;;
    --skip-synthetic) SYNTH=0; shift ;;
    --skip-reference) REF=0; shift ;;
    --skip-real-use) REAL=0; shift ;;
    --idle-only) IDLE_ONLY="$2"; shift 2 ;;
    --max-start-temp) MAX_TEMP_C="$2"; shift 2 ;;
    --apk) APKS+=(--apk "$2"); shift 2 ;;
    --restore-dashboard) RESTORE="$2"; shift 2 ;;
    -h|--help) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

thermal() { # "<phase>\t<zone>=<°C> ..." into thermal.tsv; zones report m°C or °C
  local t
  t=$(sh_dev 'for z in /sys/class/thermal/thermal_zone*; do echo "$(cat $z/type) $(cat $z/temp)"; done 2>/dev/null' \
    | awk 'NF == 2 { v = $2 > 1000 ? $2 / 1000 : $2; printf "%s=%.1f ", $1, v }')
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
  [ -f "$OUT/device.json" ] || run device ${NAME:+--name "$NAME"}
  thermal "idle-$IDLE_ONLY-$n"
  log "idle sample '$IDLE_ONLY' #$n"
  run idle --label "$IDLE_ONLY" --run "$n" --settle "$IDLE_SETTLE" --window "$IDLE_WINDOW"
  python3 "$HERE/report.py" "$OUT" --keep-apk ${APKS[@]+"${APKS[@]}"} >/dev/null
  log "report: $OUT/report.json"
  exit 0
fi

# A full run starts from an empty directory, so no earlier run's files end up in its report.
for f in "$OUT"/synthetic-*.jsonl "$OUT"/refstart-*.json "$OUT"/coldstart-*.json "$OUT"/idle-*.json; do
  [ -e "$f" ] && { echo "$OUT already holds a run ($(basename "$f")); use a new --out" >&2; exit 2; }
done

# DASHBOARD_OK=1 while the panel shows the saved dashboard as the runner found it. It clears before
# anything that leaves it (the test page, a force-stop), and only a cold start that loaded the
# dashboard sets it again. On exit, the app is restarted unless it is set; that is harmless if the
# dashboard was up after all.
SERVER_PID="" DASHBOARD_OK=1 SWITCHED=0
# Protocol 4: the graded part points the kiosk at the fake Home Assistant's light dashboard (its
# saved dashboard, `--es url`), so a restart comes back to it; it points it back at RESTORE after,
# and here on any exit. The runner can't read the setting itself (it never reads app data).
point_back() {
  if [ "$SWITCHED" = 1 ]; then
    log "pointing the kiosk back at its dashboard ($RESTORE)"
    sh_dev am start -n "$PACKAGE/.MainActivity" --es url "'$RESTORE'" >/dev/null || true
    SWITCHED=0
    DASHBOARD_OK=0
  fi
}
cleanup() {
  point_back
  if [ "$DASHBOARD_OK" != 1 ]; then
    log "restoring the dashboard (restart the app)"
    sh_dev am force-stop "$PACKAGE" || true
    sh_dev am start -n "$PACKAGE/.MainActivity" >/dev/null || true
  fi
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  "$ADB" reverse --remove "tcp:$PORT" 2>/dev/null || true
}
trap cleanup EXIT

if [ "$REF" = 1 ]; then
  # The graded part changes the kiosk's saved dashboard for its duration, so it must know what to put
  # back: the panel's dashboard URL, or "auto" if it finds HA itself. See docs/perf.md, "Run it".
  case "$RESTORE" in
    "") echo "the graded part needs --restore-dashboard <url|auto>: the dashboard the kiosk shows now" >&2; exit 2 ;;
    *127.0.0.1:"$PORT"*) echo "--restore-dashboard is the fake Home Assistant; give the panel's own dashboard" >&2; exit 2 ;;
    *"'"*|*'"'*|*' '*) echo "--restore-dashboard can't hold quotes or spaces" >&2; exit 2 ;;
  esac
fi

run device ${NAME:+--name "$NAME"}
thermal start
VSYNC=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["display"]["vsync_ms"] or 16.667)' "$OUT/device.json")
log "device recorded; vsync ${VSYNC} ms"
wait_cool

if [ "$SYNTH" = 1 ] || [ "$REF" = 1 ]; then
  # The fake Home Assistant (fakeha.py) serves HA's pinned frontend, the reference dashboards and
  # the animation test page (under /clarity/page/). The first use fetches the frontend (117 MB).
  python3 "$HERE/hafrontend.py" >/dev/null
  python3 "$HERE/fakeha.py" --port "$PORT" >"$OUT/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 60); do curl -s -o /dev/null "http://127.0.0.1:$PORT/manifest.json" && break; sleep 0.5; done
  "$ADB" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
fi

if [ "$SYNTH" = 1 ]; then
  for k in $(seq 1 "$RUNS"); do
    nonce=$(python3 -c 'import secrets; print(secrets.token_hex(4))')
    url="http://127.0.0.1:$PORT/clarity/page/index.html?run=$k&seed=1&vsync=$VSYNC&nonce=$nonce&quick=$QUICK"
    log "synthetic run $k/$RUNS"
    DASHBOARD_OK=0
    thermal "synthetic-$k"
    run synthetic --run "$k" --url "$url" --nonce "$nonce"
    sleep "$RUN_GAP"
  done
  log "memory on the test page after the ladder"
  run idle --label after-synthetic --run 1 --settle 5 --window "$IDLE_WINDOW"
fi

if [ "$REF" = 1 ]; then
  # How many graded runs were attempted, so a run that left no file still counts against the grade.
  printf '{"runs": %d}\n' "$RUNS" > "$OUT/plan.json"
  # The graded part. The kiosk is pointed at the fake HA's light dashboard; each run force-stops it,
  # times the restart to that dashboard being usable, then opens the three reference dashboards in
  # turn (the order turns each run).
  log "pointing the kiosk at the reference dashboard for the graded runs (it goes back to $RESTORE after)"
  DASHBOARD_OK=0
  SWITCHED=1
  sh_dev am start -n "$PACKAGE/.MainActivity" --es url "'http://127.0.0.1:$PORT/clarity-light/0'" >/dev/null
  sleep "$SWITCH_SETTLE"
  for k in $(seq 1 "$RUNS"); do
    log "graded run $k/$RUNS: restart to the reference dashboard, then the three reference dashboards"
    thermal "refstart-$k"
    run refstart --run "$k" --settle "$COLD_SETTLE" --url "http://127.0.0.1:$PORT" \
      --vsync "$VSYNC" --taps "$TAPS" --between-quiet "$BETWEEN_QUIET" || log "graded run $k failed; see refstart-$k.json"
    sleep "$RUN_GAP"
  done
  point_back
fi

if [ "$REAL" = 1 ]; then
  for k in $(seq 1 "$RUNS"); do
    log "cold start $k/$RUNS"
    thermal "coldstart-$k"
    # A failed cold start (no dashboard within 120 s) is recorded and the run goes on. Its idle
    # sample is skipped: it wouldn't be the dashboard's.
    DASHBOARD_OK=0
    if ! run coldstart --run "$k" --settle "$COLD_SETTLE" --timeout "$COLD_TIMEOUT"; then
      log "cold start $k failed; see coldstart-$k.json"
      continue
    fi
    DASHBOARD_OK=1
    log "idle on the dashboard $k/$RUNS"
    run idle --label all-on --run "$k" --settle "$IDLE_SETTLE" --window "$IDLE_WINDOW"
  done
fi

thermal end
python3 "$HERE/report.py" "$OUT" ${APKS[@]+"${APKS[@]}"}
log "report: $OUT/report.json and $OUT/report.md"
