#!/bin/bash
# Profile one Clarity view open in desktop Firefox, on this computer (MIT). Not the panel: it
# touches no device. It starts Clarity's fake Home Assistant (tools/perf/fakeha.py) on a port of
# its own, a fresh Firefox profile in /tmp with the Gecko profiler on from startup, opens the start
# view, then asks the harness to open VIEW as the runner does (/clarity/open), quits Firefox through
# Marionette so it writes the profile, and splits the content main thread's CPU (split.py).
# It stops only the processes it started. Used for #244 (frames/aqara-s1-plus/research/view-open.md).
#
#   tools/perf-lab/profile-view.sh OUT VIEW [--perf DIR] [--prefs FILE] [--port 18089]
#
# OUT is a path prefix: OUT.stdout (the harness's lines), OUT.profile.json, OUT.split.txt.
# --perf: the Clarity harness to serve (default tools/perf). --prefs: extra user.js lines.
set -u
OUT=$1 VIEW=$2; shift 2
HERE=$(cd "$(dirname "$0")" && pwd)
PERF="$HERE/../perf" PREFS="" PORT=18089
while [ $# -gt 0 ]; do
  case $1 in
    --perf) PERF=$2; shift 2 ;;
    --prefs) PREFS=$2; shift 2 ;;
    --port) PORT=$2; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
FIREFOX=${FIREFOX:-/Applications/Firefox.app/Contents/MacOS/firefox}
P=$(mktemp -d /tmp/perflab.XXXXXX)
python3 "$PERF/fakeha.py" --port "$PORT" --turn-port $((PORT + 1)) > "$OUT.fakeha.log" 2>&1 &
FAKE=$!
FF=
cleanup() {
  [ -n "$FF" ] && kill "$FF" 2>/dev/null
  kill "$FAKE" 2>/dev/null
  rm -rf "$P"
}
trap cleanup EXIT
until curl -s -o /dev/null "http://127.0.0.1:$PORT/manifest.json"; do
  kill -0 "$FAKE" 2>/dev/null || { echo "the fake didn't start: $OUT.fakeha.log" >&2; exit 1; }
  sleep 1
done
cat > "$P/user.js" <<PREFS
user_pref("devtools.console.stdout.content", true);
user_pref("browser.shell.checkDefaultBrowser", false);
user_pref("browser.aboutwelcome.enabled", false);
user_pref("datareporting.policy.dataSubmissionEnabled", false);
user_pref("app.update.disabledForTesting", true);
user_pref("layout.css.devPixelsPerPx", "1.5");
PREFS
[ -n "$PREFS" ] && cat "$PREFS" >> "$P/user.js"
# --window-size is the outer size: 1009x521 gives a CSS viewport near the S1's 960x480 at 1.5.
MOZ_PROFILER_STARTUP=1 MOZ_PROFILER_STARTUP_ENTRIES=64000000 MOZ_PROFILER_STARTUP_INTERVAL=1 \
MOZ_PROFILER_STARTUP_FEATURES="js,stackwalk,cpu" MOZ_PROFILER_STARTUP_FILTERS="GeckoMain,StyleThread,Renderer,RenderBackend" \
MOZ_PROFILER_SHUTDOWN="$OUT.profile.json" \
  "$FIREFOX" -no-remote -profile "$P" -headless --marionette --window-size=1009,521 \
  "http://127.0.0.1:$PORT/clarity-ref/start" > "$OUT.stdout" 2>&1 &
FF=$!
wait_for() { for _ in $(seq 1 120); do grep -q "$1" "$OUT.stdout" 2>/dev/null && return 0; sleep 1; done; return 1; }
wait_for 'stage\\":\\"restart' || { echo "the start view never became usable: $OUT.stdout" >&2; exit 1; }
sleep 8
curl -s "http://127.0.0.1:$PORT/clarity/open?view=$VIEW&nonce=$(openssl rand -hex 4)&run=1&taps=0&swipes=0&vsync=16.67"
wait_for "stage\\\\\":\\\\\"$VIEW" || echo "the view didn't finish; quitting anyway" >&2
python3 -I "$HERE/mquit.py" > /dev/null
for _ in $(seq 1 60); do kill -0 "$FF" 2>/dev/null || break; sleep 1; done
FF=
python3 -I "$HERE/split.py" "$OUT.profile.json" "$OUT.stdout" "$VIEW" | tee "$OUT.split.txt"
