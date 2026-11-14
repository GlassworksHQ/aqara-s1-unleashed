#!/bin/sh
# Runs the camera pane's ONVIF server on this computer (OnvifLabServer, through Gradle) and Home
# Assistant's client against it (onvif-ha-client.py), then counts what the server logged. Localhost
# only: touches no panel and no Home Assistant. docs/research/onvif-rtsp.md says what each case shows.
# Needs: ANDROID_HOME, python3. Makes a venv with onvif-zeep-async 4.3.0 in $LAB (default /tmp/onvif-lab).
# Usage: tools/camera/onvif-lab.sh <seconds> [ONVIF_LAB_RESTART_AT=30] [ONVIF_LAB_CREDS_CHANGE_AT=20]
set -eu
secs=$1; shift
repo=$(cd "$(dirname "$0")/../.." && pwd)
LAB=${LAB:-/tmp/onvif-lab}
mkdir -p "$LAB"
[ -x "$LAB/venv/bin/python" ] || { python3 -m venv "$LAB/venv"; "$LAB/venv/bin/pip" -q install onvif-zeep-async==4.3.0; }
while nc -z 127.0.0.1 18000 2>/dev/null; do sleep 1; done
rm -f "$LAB/server.log"
(cd "$repo/app" && env ONVIF_LAB_SECONDS=$((secs + 40)) ONVIF_LAB_LOG="$LAB/server.log" "$@" \
    ./gradlew :kiosk:testDebugUnitTest --tests '*OnvifLabServer*' -q > "$LAB/gradle.log" 2>&1 &)
until [ -f "$LAB/server.log" ] && nc -z 127.0.0.1 18000 2>/dev/null; do sleep 1; done
"$LAB/venv/bin/python" "$repo/tools/camera/onvif-ha-client.py" 127.0.0.1 18000 viewer made-up-pass "$secs" | tee "$LAB/client.log" | tail -5
echo "--- the server's log, counted:"
cut -d' ' -f2- "$LAB/server.log" | sed 's/[0-9a-f]\{16\}/<id>/; s/waited=[0-9]*ms/waited=Nms/' | sort | uniq -c | sort -rn
