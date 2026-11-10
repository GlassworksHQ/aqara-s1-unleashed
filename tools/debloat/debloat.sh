#!/usr/bin/env bash
# Reversible Aqara/Lumi de-bloat for the Panel Hub S1 Plus.
# Uses `pm disable-user` (not uninstall) so everything is restorable with `enable`.
# Nothing is flashed; these are version-agnostic, shell-level transforms over ADB.
#
# Usage:
#   ADB_SERIAL=<serial> ./debloat.sh status      # show enabled/disabled state
#   ADB_SERIAL=<serial> ./debloat.sh disable      # disable the safe set
#   ADB_SERIAL=<serial> ./debloat.sh enable       # re-enable everything (undo)
#
# IMPORTANT: set a replacement HOME launcher BEFORE disabling the Aqara launcher,
# or the device will have no launcher. The launcher is listed separately and only
# disabled when you pass `disable-launcher` explicitly.
set -euo pipefail

ADB="adb"
[ -n "${ADB_SERIAL:-}" ] && ADB="adb -s ${ADB_SERIAL}"

# Safe to disable: cloud/telemetry, OTA, extra apps, factory/test, Aqara voice.
# (Aqara voice APK is kept on-device + backed up for reverse-engineering.)
SAFE=(
  com.aqara.inhome.manager        # Aqara cloud manager / telemetry
  com.lumi.ota                    # firmware OTA updater (phone-home)
  com.aqara.inhome.vassistant     # Aqara voice assistant (we use HA Assist)
  com.aqara.inhome.embedded_chat  # embedded chat
  com.aqara.music                 # music app
  com.lumi.siri                   # Siri/HomeKit bridge
  com.lumi.dream                  # daydream/screensaver
  com.lumi.aging                  # factory burn-in
  com.lumi.factory                # factory test
)

# NOT touched by default (may be system-critical): com.lumi.settings,
# com.lumi.systemmanager. The launcher is handled separately:
LAUNCHER=com.aqara.inhome.launcher

op() { # op <disable|enable> <pkg>
  if [ "$1" = "disable" ]; then
    # Already disabled by the firmware itself (state 2, e.g. vassistant on 4.5.80): the shell may not
    # turn that into disabled-user, and doesn't need to.
    if $ADB shell pm list packages -d "$2" 2>/dev/null | grep -qx "package:$2"; then echo "already   $2 (disabled)"; return; fi
    $ADB shell pm disable-user --user 0 "$2" >/dev/null 2>&1 && echo "disabled  $2" || echo "skip/err  $2"
  else
    $ADB shell pm enable "$2" >/dev/null 2>&1 && echo "enabled   $2" || echo "skip/err  $2"
  fi
}

case "${1:-status}" in
  disable)
    for p in "${SAFE[@]}"; do op disable "$p"; done
    echo "NOTE: launcher ($LAUNCHER) NOT disabled. Set a replacement HOME first, then run: $0 disable-launcher"
    ;;
  disable-launcher) op disable "$LAUNCHER" ;;
  enable)
    for p in "${SAFE[@]}"; do op enable "$p"; done
    op enable "$LAUNCHER"
    ;;
  status)
    for p in "${SAFE[@]}" "$LAUNCHER" com.lumi.settings com.lumi.systemmanager; do
      st=$($ADB shell pm list packages -d 2>/dev/null | grep -c "$p" || true)
      [ "$st" -gt 0 ] && echo "DISABLED  $p" || echo "enabled   $p"
    done
    ;;
  *) echo "usage: $0 {status|disable|disable-launcher|enable}"; exit 1 ;;
esac
