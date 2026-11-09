#!/bin/sh
# Runs Aqara's own /vendor/bin/bracket_cli on the panel against base_emulator.py (on this host),
# through adb reverse + a socat pty. Never touches /dev/ttyS3.
#
#   tools/bracket/cli_proof.sh [emulator args...]      e.g. --fault crc
#
# Needs: adb with ANDROID_SERIAL set, python3. Prints bracket_cli's output and the emulator log.
set -u
DIR=$(cd "$(dirname "$0")" && pwd)
PORT=${PORT:-9999}
LOG=$(mktemp -t bracket_emu.XXXXXX)

python3 "$DIR/base_emulator.py" --port "$PORT" --no-stdin --report-interval 0 "$@" >"$LOG" 2>&1 &
EMU=$!
trap 'kill $EMU 2>/dev/null; adb shell "pkill -f s3a" >/dev/null 2>&1' EXIT
sleep 1
adb reverse tcp:"$PORT" tcp:"$PORT" >/dev/null
adb shell "cd /data/local/tmp && rm -f s3a && (/vendor/bin/socat pty,link=/data/local/tmp/s3a,raw,echo=0 tcp:127.0.0.1:$PORT >/dev/null 2>&1 &) ; sleep 1"

cli() {
    echo "\$ bracket_cli /data/local/tmp/s3a 115200 $*"
    adb shell "/vendor/bin/bracket_cli /data/local/tmp/s3a 115200 $*; echo \"(exit \$?)\""
}
cli 200.1.159
cli 8.0.2022
cli 4.1.85
cli 4.1.85 1
cli 4.1.85
cli 0.12.85
cli 200.4.3 1800.5
cli 14.160.85
sleep 0.5
echo "---- emulator log"
cat "$LOG"
rm -f "$LOG"
