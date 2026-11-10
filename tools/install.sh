#!/usr/bin/env bash
# One-shot setup of an Aqara Panel Hub S1 Plus as a Home Assistant kiosk, over ADB (no root).
#
# Prerequisite: ADB reachable (USB or Wi-Fi). Stock firmware exposes no ADB; see README for how to
# enable it. Everything below is reversible: see --undo.
#
#   export ANDROID_SERIAL=<panel serial>        # if more than one device is attached
#   tools/install.sh --url http://homeassistant.local:8123/my-dashboard [options]
#
# Options:
#   --apk PATH          kiosk APK (default: app/kiosk/build/outputs/apk/debug/kiosk-debug.apk)
#   --url URL           dashboard URL (default: keep the app's current/default URL)
#   --timezone TZ       e.g. America/Los_Angeles (default: leave unchanged)
#   --locale TAG        e.g. en-US (default: leave unchanged; applies after reboot)
#   --keyboard APK      install this IME APK, make it default and disable the stock Baidu IME
#   --no-models         don't download/push the openWakeWord models
#   --no-debloat        keep the Aqara apps enabled (the kiosk still becomes HOME)
#   --keep-dnd          keep Aqara's 22:00-07:00 Do-Not-Disturb screen-off
#   --undo              revert: re-enable Aqara apps + launcher, restore DND, remove admin,
#                       stop the kiosk and start Aqara's radio services again
#   --dry-run           print the commands instead of running them
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PKG=sh.edm.s1kiosk
ACT="$PKG/.MainActivity"
APK="$ROOT/app/kiosk/build/outputs/apk/debug/kiosk-debug.apk"
URL="" TZ_="" LOCALE="" KEYBOARD="" MODELS=1 DEBLOAT=1 DND_OFF=1 UNDO=0 DRY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --url) URL="$2"; shift 2 ;;
    --timezone) TZ_="$2"; shift 2 ;;
    --locale) LOCALE="$2"; shift 2 ;;
    --keyboard) KEYBOARD="$2"; shift 2 ;;
    --no-models) MODELS=0; shift ;;
    --no-debloat) DEBLOAT=0; shift ;;
    --keep-dnd) DND_OFF=0; shift ;;
    --undo) UNDO=1; shift ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

step() { printf '\n== %s\n' "$*"; }
run() { if [[ $DRY -eq 1 ]]; then echo "  + $*"; else "$@"; fi; }
# Like run, but never fails the script (for idempotent steps such as re-granting).
try() { if [[ $DRY -eq 1 ]]; then echo "  + $* || true"; else "$@" || true; fi; }
sh_() { run adb shell "$@"; }

step "Device"
adb get-state >/dev/null
MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "  model=$MODEL sdk=$SDK"
[[ "$SDK" == "29" ]] || echo "  WARNING: expected Android 10 (sdk 29); continuing"

if [[ $UNDO -eq 1 ]]; then
  step "Undo"
  try adb shell am start -n "$ACT" --ez remove_admin true
  run env ADB_SERIAL="${ANDROID_SERIAL:-}" "$HERE/debloat/debloat.sh" enable
  # after debloat re-enabled the launcher: set-home-activity fails on a disabled package
  try adb shell cmd package set-home-activity com.aqara.inhome.launcher/.MainActivity
  try adb shell service call A_device_control 7 i32 1 i32 0
  try adb shell pm enable com.baidu.input_auto
  try adb shell ime set com.baidu.input_auto/.ImeService
  # Radio (docs/radio.md): the app keeps Aqara's radio services stopped ("Off" mode). Stop the app so
  # it doesn't stop them again (a force-stopped app also gets no BOOT_COMPLETED), then start them now
  # in provider-first order; they are init services, so a reboot would bring them back as well.
  # Disable, not just force-stop: the app restarts itself (foreground service) and would block the
  # Aqara stack again. Reversible: a reinstall (or pm enable) brings it back.
  try adb shell am force-stop "$PKG"
  try adb shell pm disable-user --user 0 "$PKG"
  # ha_master is Aqara's cloud link, which the app blocks ("Block Aqara cloud"); the Aqara app and its
  # firmware updates need it back.
  for svc in zigbee_cpcd zigbee_socat zigbee_zigbeed zigbee_host zigbee_agent ha_matter ha_master; do
    try adb shell setprop ctl.start "$svc"
  done
  echo "Done. The kiosk app is still installed but disabled (pm enable $PKG, or adb uninstall $PKG)."
  exit 0
fi

step "Install kiosk app"
[[ -f "$APK" ]] || { echo "APK not found: $APK (build it: cd app && ./gradlew :kiosk:assembleDebug)" >&2; exit 1; }
run adb install -r -g "$APK"

step "Permissions"
try adb shell pm grant "$PKG" android.permission.RECORD_AUDIO
try adb shell pm grant "$PKG" android.permission.CAMERA
try adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS   # presence range
try adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow                 # start from boot
# Device admin (force-lock only) for instant screen-off. "already active" is fine.
try adb shell dpm set-active-admin "$PKG/.AdminReceiver"

step "Make the kiosk the HOME app"
sh_ cmd package set-home-activity "$ACT"

if [[ $DND_OFF -eq 1 ]]; then
  step "Disable Aqara DND screen-off (the kiosk handles night mode)"
  try adb shell service call A_device_control 7 i32 0 i32 0
fi

if [[ $DEBLOAT -eq 1 ]]; then
  step "De-bloat (reversible pm disable-user)"
  run env ADB_SERIAL="${ANDROID_SERIAL:-}" "$HERE/debloat/debloat.sh" disable
  run env ADB_SERIAL="${ANDROID_SERIAL:-}" "$HERE/debloat/debloat.sh" disable-launcher
fi

if [[ -n "$KEYBOARD" ]]; then
  step "Keyboard"
  run adb install -r "$KEYBOARD"
  IME=$(adb shell ime list -a -s | tr -d '\r' | grep -v baidu | head -1)
  [[ -n "$IME" ]] && { sh_ ime enable "$IME"; sh_ ime set "$IME"; try adb shell pm disable-user --user 0 com.baidu.input_auto; }
fi

if [[ -n "$TZ_" ]]; then
  step "Time zone $TZ_"
  sh_ settings put global auto_time_zone 0
  sh_ service call alarm 3 s16 "$TZ_"
fi
if [[ -n "$LOCALE" ]]; then
  step "Locale $LOCALE (applies after reboot)"
  sh_ settings put system system_locales "$LOCALE"
fi

if [[ $MODELS -eq 1 ]]; then
  step "Wake word models"
  run "$HERE/voice/fetch-models.sh"
fi

step "Start the kiosk"
if [[ -n "$URL" ]]; then
  sh_ am start -n "$ACT" --es url "$URL"
else
  sh_ am start -n "$ACT"
fi

IP=$(adb shell ip -4 -o addr show wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -1)
cat <<EOF

Done.
- Log in to Home Assistant on the panel once (the session is remembered).
- Home Assistant: Settings -> Devices & services -> Add integration -> ESPHome,
  host ${IP:-<panel IP>}, port 6053 (or accept the auto-discovered "S1 Panel").
- Hidden settings on the panel: tap the top-left corner 5 times.
- Undo everything: $0 --undo
EOF
