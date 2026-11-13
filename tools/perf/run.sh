#!/usr/bin/env bash
# Clarity, the Glassworks perf test: grade a frame (MIT). Docs: docs/perf.md.
#
# What it touches on the device:
#   - Points the kiosk's dashboard setting (`--es url`) at the fake Home Assistant's reference
#     dashboard for the graded part (and --pane-ram), and back at --restore-dashboard after it and
#     on any exit.
#   - Opens the test page and the reference dashboards with the kiosk's one-off `--es open` extra.
#     The pages are served by the fake Home Assistant on this machine over `adb reverse`.
#   - With --pane-ram only: turns the switchable panes off and on with the kiosk's own extras
#     (`--ez voice_enabled`, `--ez sendspin_enabled`), and back to the given settings on exit.
#   - Sends `--es screen on` (the kiosk's own wake intent) during warm-ups, so the screen stays on
#     without changing the idle timeout.
#   - Taps each reference dashboard's "Living room" toggle (`input tap`) and swipes it (`input swipe`),
#     only where and when the page asks, to measure taps and scrolling.
#   - Force-stops the app for each cold start (KEYCODE_WAKEUP first, so the screen is on); the app
#     comes back on the saved dashboard. If the run stops early or the last cold start fails, the
#     exit trap restarts the app.
#   Read-only otherwise: getprop, /proc, dumpsys meminfo and SurfaceFlinger, logcat.
#   On this computer: a Python venv for the fake's WebRTC peer (aiortc, from webrtc-requirements.txt,
#   locked with hashes) in ~/.cache/glassworks-perf, and two `adb reverse` ports (--port and the
#   WebRTC relay's, --port + 1), removed on exit.
#
# Usage: tools/perf/run.sh --frame aqara-s1-plus [--name "Aqara S1 Plus"] [--serial S] [--runs 3] [--out DIR]
#          --restore-dashboard URL|auto [--pane-ram assist=on,sendspin=on,mic=on]
#          [--skip-synthetic] [--skip-reference] [--skip-real-use] [--taps 10] [--quick] [--apk FILE]...
#        tools/perf/run.sh --frame ID --out DIR --idle-only LABEL   # one idle sample (pane diffs)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb}"
FRAME="" NAME="" RUNS=3 OUT="" PORT=8089 TURN_PORT="" PACKAGE=sh.edm.s1kiosk TAPS=10 QUICK=0
SYNTH=1 REF=1 REAL=1 IDLE_ONLY="" MAX_TEMP_C=50 APKS=() RESTORE="" PANE_RAM=""
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
    --pane-ram) PANE_RAM="$2"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[ -n "$FRAME" ] || { echo "--frame is required (e.g. aqara-s1-plus)" >&2; exit 2; }
[ "$RUNS" -ge 1 ] || { echo "--runs must be at least 1" >&2; exit 2; }
TURN_PORT=$((PORT + 1))
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
SERVER_PID="" DASHBOARD_OK=1 SWITCHED=0 PANES_SWITCHED=0 PANES=""
# Protocol 4: the graded part points the kiosk at the reference dashboard's start view on the fake
# Home Assistant (its saved dashboard, `--es url`), so a restart comes back to it; it points it back
# at RESTORE after, and here on any exit (exit status 3 if that failed). --pane-ram also turns the panes off
# and on; perfrun.py panes puts them back with the dashboard and checks the kiosk's log that they took.
# The runner can't read either setting itself (it never reads app data).
pane_set() { # the pane extras (extra=true|false,...) for: one pane on by itself (its id), "none", or "restore"
  awk -v s="$1" '$1 == "STATE" && $2 == s { print $3 }' <<< "$PANES"
}
point_back() {
  local out set=""
  [ "$PANES_SWITCHED" = 1 ] && set=$(pane_set restore)
  if [ "$SWITCHED" = 1 ] || [ "$PANES_SWITCHED" = 1 ]; then
    log "pointing the kiosk back at its dashboard ($RESTORE)${set:+ and its panes ($PANE_RAM)}"
    DASHBOARD_OK=0
    if [ -n "$set" ]; then
      # The panes and the dashboard together, checked in the kiosk's log (perfrun.py panes): the
      # live settings first, then voice_enabled, which restarts the app. Retried if one didn't take.
      if out=$(run panes --set "$set" --url "$RESTORE" --settle "$SWITCH_SETTLE" --tries 3 2>&1); then
        SWITCHED=0 PANES_SWITCHED=0
        if printf '%s\n' "$out" | tail -n 1 | grep -q '"voice_applied": false'; then
          log "voice_enabled didn't take (this kiosk saves it asynchronously before it restarts); it is as it was"
        fi
      else
        log "COULD NOT put the panes back: $(printf '%s' "$out" | tail -n 1)"
        log "send it yourself: adb shell am start -n $PACKAGE/.MainActivity --es url '$RESTORE' $(printf '%s' "$set" | sed 's/\([a-z_]*\)=\([a-z]*\)/--ez \1 \2/g; s/,/ /g')"
      fi
      return 0
    fi
    # -W: wait until the kiosk has the intent. am exits 0 even when it fails, so read its answer.
    out=$(sh_dev am start -W -n "$PACKAGE/.MainActivity" --es url "'$RESTORE'" 2>&1) || out="$out (adb failed)"
    if printf '%s\n' "$out" | grep -q '^Status: ok'; then
      SWITCHED=0
      # Let the kiosk save the setting and load the dashboard before anything else restarts it.
      sleep "$SWITCH_SETTLE"
    else
      log "COULD NOT point the kiosk back: $(printf '%s' "$out" | tr '\n' ' ')"
      log "send it yourself: adb shell am start -n $PACKAGE/.MainActivity --es url '$RESTORE'"
    fi
  fi
}
restored() { [ "$SWITCHED" = 0 ] && [ "$PANES_SWITCHED" = 0 ]; }
cleanup() {
  local status=$?
  point_back
  if ! restored; then
    point_back                     # once more; then say so in the exit status
  fi
  if [ "$DASHBOARD_OK" != 1 ] && restored; then
    log "restoring the dashboard (restart the app)"
    sh_dev am force-stop "$PACKAGE" || true
    sh_dev am start -n "$PACKAGE/.MainActivity" >/dev/null || true
  fi
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  "$ADB" reverse --remove "tcp:$PORT" 2>/dev/null || true
  "$ADB" reverse --remove "tcp:$TURN_PORT" 2>/dev/null || true
  restored || exit 3
  exit "$status"
}
trap cleanup EXIT

if [ -n "$PANE_RAM" ]; then
  # "<id>=on|off,…" for every pane the frame can switch (frames/<id>/budgets.json, `switch`), and
  # mic=on|off when one has a microphone mute (`mute`): what to put back. Out: one
  # "STATE <none|id|restore> extra=true|false,..." line per state the runner sets.
  PANES=$(python3 - "$HERE/../../frames/$FRAME/budgets.json" "$PANE_RAM" <<'PY'
import json, os, re, sys
path, spec = sys.argv[1:]
panes = json.load(open(path))["panes"] if os.path.exists(path) else {}
switch = {k: v for k, v in panes.items() if v.get("switch")}
if not switch:
    sys.exit(f"--pane-ram: {path} names no pane with a switch")
keys = set(switch) | ({"mic"} if any(v.get("mute") for v in switch.values()) else set())
got = {}
for part in spec.split(","):
    m = re.fullmatch(r"([a-z0-9-]+)=(on|off)", part)
    if not m or m.group(1) not in keys:
        sys.exit(f"--pane-ram: {part!r} isn't <name>=on|off for one of {', '.join(sorted(keys))}")
    got[m.group(1)] = m.group(2) == "on"
if set(got) != keys:
    sys.exit(f"--pane-ram needs the setting now of: {', '.join(sorted(keys))} (mic: the microphone, on unless muted)")
def state(on):
    out = []
    for pid in sorted(switch):
        p = switch[pid]
        v = got[pid] if on == "restore" else pid == on
        out.append(f"{p['switch']}={'true' if v else 'false'}")
        if p.get("mute"):
            muted = (not got["mic"]) if on == "restore" else not v
            out.append(f"{p['mute']}={'true' if muted else 'false'}")
    return ",".join(out)
for name in ["none"] + sorted(switch) + ["restore"]:
    print("STATE", name, state(name))
PY
) || exit 2
fi

if [ "$REF" = 1 ] || [ -n "$PANE_RAM" ]; then
  # The graded part changes the kiosk's saved dashboard for its duration, so it must know what to put
  # back: the panel's dashboard URL, or "auto" if it finds HA itself. See docs/perf.md, "Run it".
  case "$RESTORE" in
    "") echo "the graded part and --pane-ram need --restore-dashboard <url|auto>: the dashboard the kiosk shows now" >&2; exit 2 ;;
    *127.0.0.1:"$PORT"*) echo "--restore-dashboard is the fake Home Assistant; give the panel's own dashboard" >&2; exit 2 ;;
    *"'"*|*'"'*|*' '*) echo "--restore-dashboard can't hold quotes or spaces" >&2; exit 2 ;;
  esac
fi

run device ${NAME:+--name "$NAME"}
thermal start
VSYNC=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["display"]["vsync_ms"] or 16.667)' "$OUT/device.json")
log "device recorded; vsync ${VSYNC} ms"
wait_cool

if [ "$SYNTH" = 1 ] || [ "$REF" = 1 ] || [ -n "$PANE_RAM" ]; then
  # The fake Home Assistant (fakeha.py) serves HA's pinned frontend, the reference dashboards and
  # the animation test page (under /clarity/page/). The first use fetches the frontend (117 MB).
  python3 "$HERE/hafrontend.py" >/dev/null
  # The reference dashboards' camera streams (#174): HLS segments made once with ffmpeg (about 40 s).
  # Only the reference dashboards play them (the graded part, its idle samples and --pane-ram); the
  # animation test page alone doesn't need ffmpeg.
  if [ "$REF" = 1 ] || [ -n "$PANE_RAM" ]; then
    python3 "$HERE/camera.py" >/dev/null || { echo "Clarity's camera streams need ffmpeg with libx264 (camera.py)" >&2; exit 2; }
  fi
  # Protocol 6 (#202): the fake answers HA's WebRTC offers with aiortc, from a venv of
  # webrtc-requirements.txt (hashes checked, wheels only; made once, about 60 MB).
  FAKE_PY="${GW_PERF_FAKE_PYTHON:-}"
  if [ -z "$FAKE_PY" ]; then
    FAKE_PY=$(python3 "$HERE/webrtcenv.py") || { echo "the fake's WebRTC peer needs Python 3.11+ and its venv (webrtcenv.py)" >&2; exit 2; }
  fi
  # Something else on the ports (an earlier run's fake left behind) would answer for ours.
  if curl -s -o /dev/null --connect-timeout 1 --max-time 3 "http://127.0.0.1:$PORT/manifest.json"; then
    echo "port $PORT is already in use (an earlier run's fake Home Assistant?); stop it or use --port" >&2
    exit 2
  fi
  # fakeha.jsonl: every command the frontend sent, and the stream's sends (report.py checks them).
  "$FAKE_PY" "$HERE/fakeha.py" --port "$PORT" --turn-port "$TURN_PORT" --log "$OUT/fakeha.jsonl" >"$OUT/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 60); do curl -s -o /dev/null --connect-timeout 1 --max-time 3 "http://127.0.0.1:$PORT/manifest.json" && break; sleep 0.5; done
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "the fake Home Assistant didn't start; see $OUT/server.log" >&2; exit 2; }
  "$ADB" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
  # The WebRTC relay (TURN over TCP): the panel's only way to this computer's WebRTC peer.
  "$ADB" reverse "tcp:$TURN_PORT" "tcp:$TURN_PORT" >/dev/null
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
  FALLBACK=$(python3 -c 'import json,sys; print(",".join(json.load(open(sys.argv[1])).get("FALLBACK", {})))' "$HERE/page/tiers.json")
  # How many graded runs were attempted, so a run that left no file still counts against the grade.
  printf '{"runs": %d}\n' "$RUNS" > "$OUT/plan.json"
  # The graded part. The kiosk is pointed at the reference dashboard's start view; each run
  # force-stops it, times the restart to that view being usable, then opens the three reference
  # views in turn within the loaded frontend (the order turns each run).
  log "pointing the kiosk at the reference dashboard for the graded runs (it goes back to $RESTORE after)"
  DASHBOARD_OK=0
  SWITCHED=1
  sh_dev am start -n "$PACKAGE/.MainActivity" --es url "'http://127.0.0.1:$PORT/clarity-ref/start'" >/dev/null
  sleep "$SWITCH_SETTLE"
  for k in $(seq 1 "$RUNS"); do
    log "graded run $k/$RUNS: restart to the reference dashboard, then the three reference dashboards"
    thermal "refstart-$k"
    # Protocol 6: then the camera pages' HLS copies, the fallback (detail, not graded).
    if run refstart --run "$k" --settle "$COLD_SETTLE" --url "http://127.0.0.1:$PORT" \
      --vsync "$VSYNC" --taps "$TAPS" --between-quiet "$BETWEEN_QUIET" --fallback "$FALLBACK"; then
      # RAM at idle on the typical reference dashboard, opened with no nonce: nothing is timed and
      # the live updates stay off (docs/perf.md, "RAM").
      log "idle on the typical reference dashboard $k/$RUNS"
      run idle --label ref-idle --run "$k" --url "http://127.0.0.1:$PORT/clarity-ref/typical" \
        --settle "$IDLE_SETTLE" --window "$IDLE_WINDOW"
    else
      log "graded run $k failed; see refstart-$k.json"
    fi
    sleep "$RUN_GAP"
  done
fi

if [ -n "$PANE_RAM" ]; then
  # Each switchable pane on by itself against every pane off, on the typical reference dashboard.
  # The first intent sets the live settings (Sendspin, the microphone mute) and points the kiosk at
  # the dashboard; the second sets voice_enabled, which restarts the app, so every sample starts
  # from a fresh process. perfrun.py panes reads the kiosk's log to check what it runs. A sample that
  # ran something else, or saw the screen go off, is left out of the report (docs/perf.md).
  log "RAM per pane: every pane off, then each on by itself, $RUNS time(s) (they go back to $PANE_RAM after)"
  DASHBOARD_OK=0
  SWITCHED=1
  PANES_SWITCHED=1
  for k in $(seq 1 "$RUNS"); do
    for state in $(awk '$1 == "STATE" && $2 != "restore" { print $2 }' <<< "$PANES"); do
      label=$([ "$state" = none ] && echo ref-panes-off || echo "ref-$state-on")
      log "panes: $label $k/$RUNS"
      # Set and check what the kiosk runs (perfrun.py panes); each sample starts from a fresh app
      # process, since voice_enabled restarts it. The idle sample records what it ran too.
      # A state that didn't take (a pane, or the dashboard intent refused: url_taken false) gets no
      # sample: it wouldn't be this label on the reference dashboard.
      if ! run panes --set "$(pane_set "$state")" --url "http://127.0.0.1:$PORT/clarity-ref/typical" \
          --settle "$SWITCH_SETTLE" --tries 3 > "$OUT/panes-$label-$k.json"; then
        log "panes: the kiosk didn't run $label (see panes-$label-$k.json); no sample taken"
        continue
      fi
      if grep -q '"voice_applied": false' "$OUT/panes-$label-$k.json"; then
        log "panes: voice_enabled didn't take (this kiosk saves it asynchronously before it restarts); Assist is off by its microphone mute"
      fi
      thermal "$label-$k"
      run idle --label "$label" --run "$k" --settle "$IDLE_SETTLE" --window "$IDLE_WINDOW"
    done
  done
fi
point_back
restored || { log "the kiosk's dashboard or panes weren't put back"; exit 3; }

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
