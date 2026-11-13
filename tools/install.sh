#!/usr/bin/env bash
# One-shot setup of an Aqara Panel Hub S1 Plus as a Home Assistant kiosk, over ADB (no root).
#
# Prerequisite: ADB reachable (USB or Wi-Fi). Stock firmware exposes no ADB; see
# docs/install.md#command-line for how to enable it. Everything below is reversible: see --undo.
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
#   --switch            if S1 Kiosk on the panel has another signing key (a debug build) and Android
#                       refuses the APK: switch without asking (default: explain and ask). See
#                       docs/install.md#switching-a-development-build-to-a-release
#   --undo              revert: re-enable Aqara apps + launcher, restore DND and the IME, remove
#                       S1 Kiosk (or disable it, if it saved the panel's Wi-Fi), start Aqara's radio
#                       services again, check HOME is Aqara's
#   --dry-run           print the commands instead of running them (only reads the panel's state)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PKG=sh.edm.s1kiosk
ACT="$PKG/.MainActivity"
APK="$ROOT/app/kiosk/build/outputs/apk/debug/kiosk-debug.apk"
URL="" TZ_="" LOCALE="" KEYBOARD="" MODELS=1 DEBLOAT=1 DND_OFF=1 UNDO=0 DRY=0 SWITCH_OK=0

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
    --switch) SWITCH_OK=1; shift ;;
    --undo) UNDO=1; shift ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

step() { printf '\n== %s\n' "$*"; }
run() { if [[ $DRY -eq 1 ]]; then echo "  + $*"; else "$@"; fi; }
# Like run, but never fails the script (for idempotent steps such as re-granting).
try() { if [[ $DRY -eq 1 ]]; then echo "  + $* || true"; else "$@" || true; fi; }
sh_() { run adb shell "$@"; }

# ---- Wi-Fi ownership and the signing-key switch (docs/install.md; frames/aqara-s1-plus/installer/src/switch.ts) ----
# Android 10 forgets every Wi-Fi network an app saved when that app is uninstalled, and S1 Kiosk
# saves the panel's Wi-Fi itself (Improv). The Wi-Fi password is only ever held in a shell variable:
# never echoed, never in a command line (it reaches improv-send.py on stdin), never in a file.
SW_SH=/data/local/tmp/s1kiosk-switch.sh
OLD_APK=/data/local/tmp/s1kiosk-old.apk
IMPROV_SEND=${S1_IMPROV_SEND:-$HERE/install/improv-send.py}
WIFI_OWNER=none WIFI_SSID="" HAS_ROOT=0 DEBUG_BUILD=0 INSTALLED_VER="" SWITCHED=0 DATA_KEPT=0 WIFI_LOST=0
WIFI_PSK="" HAVE_PSK=0 KEPT=0

# `dumpsys wifi` on stdin -> "<kiosk|other|unknown|none><TAB><ssid>": the current network id from
# "mWifiInfo ... Net ID: n", then that network's "ID: n SSID: "x" ..." entry and its " cname=<pkg>".
wifi_owner() {
  awk -v pkg="$PKG" '
    !info && /mWifiInfo/ { info = 1; id = "?"; if (match($0, /Net ID: -?[0-9]+/)) id = substr($0, RSTART + 8, RLENGTH - 8) }
    /^[ \t]*[*-]?[ \t]*ID: -?[0-9]+ SSID: .* PROVIDER-NAME:/ {
      if (state == 1) state = 2
      if (state == 0 && info) {
        h = $0; sub(/^[ \t]*[*-]?[ \t]*ID: /, "", h); bid = h; sub(/ .*/, "", bid)
        if (bid == id) {
          state = 1; s = h; sub(/^-?[0-9]+ SSID: /, "", s); sub(/ PROVIDER-NAME:.*/, "", s)
          if (s ~ /^".*"$/) s = substr(s, 2, length(s) - 2)
          ssid = s
        }
      }
      next
    }
    state == 1 && /Configured networks End/ { state = 2 }
    state == 1 && / cname=/ { c = $0; sub(/.* cname=/, "", c); sub(/ .*/, "", c); owner = (c == pkg) ? "kiosk" : "other"; state = 2 }
    END {
      if (!info || id == "?") { print "unknown\t"; exit }
      if (id + 0 < 0) { print "none\t"; exit }
      print (owner == "" ? "unknown" : owner) "\t" ssid
    }'
}
panel_wifi() {
  local r
  r=$(adb shell dumpsys wifi | tr -d '\r' | wifi_owner) || r=unknown$'\t'
  WIFI_OWNER=${r%%$'\t'*}; WIFI_SSID=${r#*$'\t'}
  if [[ $WIFI_OWNER == unknown ]] && ! adb shell ip -4 -o addr show wlan0 2>/dev/null | grep -q 'inet '; then WIFI_OWNER=none; fi
  return 0
}
wifi_at_risk() { [[ $WIFI_OWNER == kiosk || $WIFI_OWNER == unknown ]]; }
net_name() { if [[ -n $WIFI_SSID ]]; then printf '"%s"' "$WIFI_SSID"; else printf 'its Wi-Fi network'; fi; }
is_key_mismatch() { grep -qiE 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match' <<<"$1"; }
has_root() { [[ "$(adb shell "timeout 15 su -c 'id -u' 2>/dev/null" | tr -d '\r' | tail -1)" == 0 ]]; }
su_() { adb shell "su -c 'sh $SW_SH $*'"; }
# XML text as Android writes it (&quot; &apos; &lt; &gt; &amp;); fails on anything else (&#...;).
# The value goes to sed on stdin, not as an argument.
xml_unescape() {
  [[ $1 == *'&#'* ]] && return 1
  printf '%s\n' "$1" | sed -e 's/&quot;/"/g' -e "s/&apos;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&amp;/\&/g'
}
unquote() { local v=$1; if [[ ${#v} -ge 2 && $v == \"*\" ]]; then v=${v:1:${#v}-2}; fi; printf '%s' "$v"; }

# Read-only look at the installed app, root and Wi-Fi (also what --dry-run reports).
probe_switch() {
  local d
  d=$(adb shell dumpsys package "$PKG" | tr -d '\r')
  INSTALLED_VER=$(sed -n 's/^ *versionName=\([^ ]*\).*/\1/p' <<<"$d" | head -1)
  if grep -qE 'pkgFlags=\[[^]]*DEBUGGABLE' <<<"$d"; then DEBUG_BUILD=1; else DEBUG_BUILD=0; fi
  if has_root; then HAS_ROOT=1; else HAS_ROOT=0; fi
  panel_wifi
}
explain_switch() { # [if]: --dry-run, before Android said anything (the keys may well match)
  if [[ $DEBUG_BUILD -eq 1 ]]; then echo "  S1 Kiosk on the panel is a development build${INSTALLED_VER:+ ($INSTALLED_VER)}."
  elif [[ ${1:-} == if ]]; then echo "  If the keys differ (S1 Kiosk on the panel signed with another key than $(basename "$APK")),"
  else echo "  S1 Kiosk on the panel is signed with a different key than $(basename "$APK")."; fi
  echo "  Android can't update it in place: it has to be removed and installed again."
  if [[ $HAS_ROOT -eq 1 ]]; then
    echo "  adb has root on this panel, so the app's settings, sign-in and keys are kept."
    if wifi_at_risk; then echo "  Android would forget $(net_name) with the old app; it is sent to the new app afterwards."; fi
  else
    echo "  adb has no root on this panel, so the app starts fresh: its settings, Home Assistant sign-in"
    echo "  and pairing key are reset (Home Assistant may ask to set the panel up again)."
    if wifi_at_risk; then echo "  Android also forgets $(net_name), which S1 Kiosk saved: type its password when asked, or send it again afterwards."; fi
  fi
  if [[ $WIFI_OWNER == other ]]; then echo "  The panel's Wi-Fi $(net_name) wasn't saved by S1 Kiosk and stays."; fi
  echo "  If installing the new build fails, the old one is put back."
}

# Uninstall the installed S1 Kiosk and install $APK, carrying its data over when adb has root.
# The usual steps (permissions, admin, HOME) follow in the main script.
switch_key() {
  local path out list n kind raw i k v ans owner=0
  step "Switch S1 Kiosk to this build"
  probe_switch
  explain_switch
  if [[ $SWITCH_OK -eq 0 ]]; then
    if [[ ! -t 0 ]]; then echo "Not switching without asking (no terminal): re-run with --switch. Nothing was changed." >&2; exit 1; fi
    read -r -p "  Switch now? Type yes: " ans
    [[ $ans == yes ]] || { echo "Nothing was changed."; exit 1; }
  fi
  if [[ $HAS_ROOT -eq 0 ]] && wifi_at_risk && [[ -n $WIFI_SSID && -t 0 ]]; then
    read -r -s -p "  Password for \"$WIFI_SSID\" (Enter to skip; sent to the new app, not stored): " WIFI_PSK; echo
    if [[ -n $WIFI_PSK ]]; then HAVE_PSK=1; fi
  fi
  # 1. the installed APK, to fall back on
  path=$(adb shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -1)
  if [[ -z $path ]] || ! adb shell cp "$path" "$OLD_APK"; then
    echo "ERROR: could not keep a copy of the installed S1 Kiosk to fall back on. Nothing was changed." >&2; exit 1
  fi
  if [[ $HAS_ROOT -eq 1 ]]; then
    if ! adb push "$HERE/install/s1kiosk-switch.sh" "$SW_SH" >/dev/null; then
      adb shell rm -f "$OLD_APK" || true
      echo "ERROR: could not copy the switch helper to the panel. Nothing was changed." >&2; exit 1
    fi
    # 2. the Wi-Fi details of the current network (or the only kiosk-saved one), into memory
    if wifi_at_risk; then
      list=$(su_ wifi-list | tr -d '\r') || list=""
      n="" kind=""
      while read -r i k v; do
        [[ -n $i ]] || continue
        raw=$(xml_unescape "$v") || continue
        if [[ $(unquote "$raw") == "$WIFI_SSID" || $(grep -c . <<<"$list") -eq 1 ]]; then n=$i kind=$k WIFI_SSID=$(unquote "$raw"); break; fi
      done <<<"$list"
      if [[ $kind == open ]]; then WIFI_PSK="" HAVE_PSK=1
      elif [[ $kind == psk ]] && raw=$(su_ "wifi-psk $n" | tr -d '\r') && raw=$(xml_unescape "$raw"); then
        WIFI_PSK=$(unquote "$raw"); HAVE_PSK=1
      fi
      raw="" list=""
      if [[ $HAVE_PSK -eq 1 ]]; then echo "  Wi-Fi $(net_name): read as root, kept in memory for the new app"
      else echo "  Wi-Fi: could not read it as root; send it again afterwards (see the end)"; fi
    fi
  fi
  # 3. Android won't uninstall an active device admin (a device owner is released first). The app
  #    has to be running for these, so they come before it is stopped.
  if adb shell dumpsys device_policy | tr -d '\r' | grep -A3 'Device Owner' | grep -q "$PKG"; then
    owner=1; adb shell am start -n "$PKG/.improv.ImprovActivity" --ez release_device_owner true >/dev/null || true; sleep 2
  fi
  adb shell am start -n "$ACT" --ez remove_admin true >/dev/null || true
  for _ in $(seq 1 30); do adb shell dumpsys device_policy | grep -q "$PKG" || break; sleep 0.5; done
  # 4. stop it for good: as the HOME app a force-stopped kiosk is started again at once, and its data
  #    must not change while it is copied. Disabling keeps its Wi-Fi networks (uninstalling doesn't).
  adb shell pm disable-user --user 0 "$PKG" >/dev/null || true
  if [[ $HAS_ROOT -eq 1 ]]; then
    if ! out=$(su_ backup 2>&1); then
      su_ discard >/dev/null 2>&1 || true; switch_undo; adb shell rm -f "$OLD_APK" "$SW_SH" || true
      WIFI_PSK=""
      echo "ERROR: could not keep S1 Kiosk's data: $(tr -d '\r' <<<"$out" | tail -1). Nothing was changed." >&2; exit 1
    fi
    echo "  app data kept: $(tr -d '\r' <<<"$out" | tail -1)"
  fi
  # 5. remove the old build
  out=$(adb shell pm uninstall "$PKG" | tr -d '\r') || true
  if ! grep -q Success <<<"$out"; then
    switch_undo
    if [[ $HAS_ROOT -eq 1 ]]; then su_ discard >/dev/null 2>&1 || true; fi
    adb shell rm -f "$OLD_APK" "$SW_SH" || true
    WIFI_PSK=""
    echo "ERROR: Android would not remove the installed S1 Kiosk ($out). It is unchanged." >&2; exit 1
  fi
  echo "  removed the old build"
  # 6. install the new one; on failure put the old one back (with its data, given root)
  out=$(adb install -r -g "$APK" 2>&1) || true
  if ! grep -q Success <<<"$out"; then
    sed 's/^/  /' <<<"$out"
    if adb shell pm install -r -g "$OLD_APK" | grep -q Success; then
      if [[ $HAS_ROOT -eq 1 ]]; then restore_into >/dev/null 2>&1 || true; fi
      switch_undo
      echo "ERROR: the new S1 Kiosk would not install. The previous build is back$([[ $HAS_ROOT -eq 1 ]] && echo ' with its data')." >&2
    else
      echo "ERROR: the new S1 Kiosk would not install, and neither would the previous one. Run $0 again." >&2
    fi
    if [[ $HAS_ROOT -eq 1 ]]; then su_ discard >/dev/null 2>&1 || true; fi
    adb shell rm -f "$OLD_APK" "$SW_SH" || true
    WIFI_PSK=""; exit 1
  fi
  echo "  installed the new build"
  # 7. the data back in, owned by the new uid, labels restored
  if [[ $HAS_ROOT -eq 1 ]]; then
    if out=$(restore_into 2>&1); then DATA_KEPT=1; su_ discard >/dev/null 2>&1 || true; echo "  app data restored"
    else echo "  WARNING: could not restore the app's data ($(tr -d '\r' <<<"$out" | tail -1)); it starts fresh. The copy stays in /data/s1kiosk/switch (root only)."; fi
  fi
  if [[ $owner -eq 1 ]] && ! adb shell dpm set-device-owner "$PKG/.AdminReceiver" >/dev/null 2>&1; then
    echo "  NOTE: S1 Kiosk was device owner and could not be made device owner again; it works without."
  fi
  adb shell rm -f "$OLD_APK" "$SW_SH" || true
  SWITCHED=1
  if wifi_at_risk; then WIFI_LOST=1; fi
}
# Puts the old build back as it was: enabled, device admin (and device owner if it was).
switch_undo() {
  adb shell pm enable "$PKG" >/dev/null 2>&1 || true
  adb shell dpm set-active-admin "$PKG/.AdminReceiver" >/dev/null 2>&1 || true
  if [[ ${owner:-0} -eq 1 ]]; then adb shell dpm set-device-owner "$PKG/.AdminReceiver" >/dev/null 2>&1 || true; fi
}
# The kept data into whichever build is installed now, with it disabled (as HOME it would start).
restore_into() {
  local rc=0
  adb shell pm disable-user --user 0 "$PKG" >/dev/null || true
  su_ restore || rc=$?
  adb shell pm enable "$PKG" >/dev/null || true
  return $rc
}

# Sends the remembered Wi-Fi to the new app over Improv through adb, then forgets it.
resend_wifi() {
  local port rc=1
  step "Wi-Fi $(net_name) to the new app"
  for _ in $(seq 1 40); do
    [[ "$(adb shell "grep -c ' @s1kiosk_improv\$' /proc/net/unix" | tr -d '\r')" -gt 0 ]] 2>/dev/null && break
    sleep 1.5
  done
  if ! command -v python3 >/dev/null; then
    echo "  python3 not found: can't send it from here"
  elif port=$(adb forward tcp:0 localabstract:s1kiosk_improv | tr -d '\r') && [[ $port =~ ^[0-9]+$ ]]; then
    printf '%s\0%s' "$WIFI_SSID" "$WIFI_PSK" | python3 "$IMPROV_SEND" "$port" && rc=0 || rc=$?
    adb forward --remove "tcp:$port" >/dev/null 2>&1 || true
  fi
  WIFI_PSK="" HAVE_PSK=0
  if [[ $rc -eq 0 ]]; then WIFI_LOST=0; elif [[ $rc -eq 3 ]]; then echo "  The panel could not join with that password."; fi
  return 0
}

step "Device"
adb get-state >/dev/null
MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "  model=$MODEL sdk=$SDK"
[[ "$SDK" == "29" ]] || echo "  WARNING: expected Android 10 (sdk 29); continuing"

if [[ $UNDO -eq 1 ]]; then
  step "Undo"
  # The kiosk is a device admin, and Android won't disable or uninstall an active admin: ask the app
  # to drop it, and wait until it has.
  try adb shell am start -n "$ACT" --ez remove_admin true
  if [[ $DRY -eq 0 ]]; then
    for _ in $(seq 1 30); do adb shell dumpsys device_policy | grep -q "$PKG" || break; sleep 0.5; done
  fi
  run env ADB_SERIAL="${ANDROID_SERIAL:-}" "$HERE/debloat/debloat.sh" enable
  # after debloat re-enabled the launcher: set-home-activity fails on a disabled package
  try adb shell cmd package set-home-activity com.aqara.inhome.launcher/.MainActivity
  try adb shell service call A_device_control 7 i32 1 i32 0
  try adb shell pm enable com.baidu.input_auto
  try adb shell ime set com.baidu.input_auto/.ImeService
  # Remove the kiosk (it restarts itself as a foreground service, so stopping it is not enough);
  # fall back to disabling it. Reinstall with this script.
  try adb shell am force-stop "$PKG"
  # Uninstalling makes Android 10 forget the Wi-Fi networks S1 Kiosk saved, which would take the panel
  # off its network. Then S1 Kiosk is disabled instead (reversible: pm enable), and the network stays.
  panel_wifi
  if wifi_at_risk; then
    KEPT=1
    run adb shell pm disable-user --user 0 "$PKG"
    echo "  kept S1 Kiosk installed but disabled: it saved the panel's Wi-Fi $(net_name), and Android"
    echo "  forgets that network when S1 Kiosk is uninstalled"
  elif [[ $DRY -eq 1 ]]; then
    echo "  + adb shell pm uninstall $PKG || adb shell pm disable-user --user 0 $PKG"
  elif ! adb shell pm uninstall "$PKG" | grep -q Success; then
    try adb shell pm disable-user --user 0 "$PKG"
  fi
  # Radio (docs/radio.md): the app kept Aqara's radio services stopped ("Off" mode) and blocked
  # ha_master, Aqara's cloud link. Start them in provider-first order; they are init services, so a
  # reboot would bring them back as well.
  for svc in zigbee_cpcd zigbee_socat zigbee_zigbeed zigbee_host zigbee_agent ha_matter ha_master; do
    try adb shell setprop ctl.start "$svc"
  done
  if [[ $DRY -eq 0 ]]; then
    HOME_ACT=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)
    case "$HOME_ACT" in
      com.aqara.inhome.launcher/*) ;;
      *) echo "ERROR: the home screen is still $HOME_ACT, not Aqara's launcher" >&2; exit 1 ;;
    esac
    adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
  fi
  if [[ $KEPT -eq 1 ]]; then
    echo "Done. Aqara's apps are back and S1 Kiosk is disabled, so the panel stays on its Wi-Fi."
    echo "To remove S1 Kiosk completely, see docs/install.md#undo. Reinstall: $0 --url ..."
  else
    echo "Done. Aqara's apps are back and S1 Kiosk is removed (reinstall: $0 --url ...)."
  fi
  exit 0
fi

step "Install kiosk app"
[[ -f "$APK" ]] || { echo "APK not found: $APK (build it: cd app && ./gradlew :kiosk:assembleDebug)" >&2; exit 1; }
if [[ $DRY -eq 1 ]]; then
  echo "  + adb install -r -g $APK"
  if adb shell pm list packages "$PKG" | tr -d '\r' | grep -qx "package:$PKG"; then
    probe_switch
    echo "  If Android refuses it because the installed S1 Kiosk has another signing key, the switch"
    echo "  would (after you confirm, or with --switch):"
    explain_switch if
    echo "  + adb shell cp <installed APK> $OLD_APK      # to fall back on"
    if [[ $HAS_ROOT -eq 1 ]]; then
      if wifi_at_risk; then echo "  + (as root) $SW_SH wifi-list / wifi-psk: Wi-Fi details of $(net_name), into memory only"; fi
      echo "  + (as root) $SW_SH backup                 # app data -> /data/s1kiosk/switch"
    fi
    echo "  + adb shell am start -n $ACT --ez remove_admin true"
    echo "  + adb shell pm uninstall $PKG"
    echo "  + adb install -r -g $APK      # on failure: pm install -r -g $OLD_APK$([[ $HAS_ROOT -eq 1 ]] && echo ' + restore')"
    if [[ $HAS_ROOT -eq 1 ]]; then echo "  + (as root) $SW_SH restore; $SW_SH discard"; fi
    if wifi_at_risk; then echo "  + after starting the kiosk: Wi-Fi $(net_name) to the new app over Improv (tools/install/improv-send.py)"; fi
  fi
else
  out=$(adb install -r -g "$APK" 2>&1) || true
  sed 's/^/  /' <<<"$out"
  if ! grep -q Success <<<"$out"; then
    is_key_mismatch "$out" || { echo "ERROR: installing $APK failed" >&2; exit 1; }
    switch_key
  fi
fi

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
  # the launcher too (HOME is the kiosk now), in the same run: no "launcher NOT disabled" note
  run env ADB_SERIAL="${ANDROID_SERIAL:-}" "$HERE/debloat/debloat.sh" disable --launcher
fi

if [[ -n "$KEYBOARD" ]]; then
  step "Keyboard"
  run adb install -r "$KEYBOARD"
  IME=$(adb shell ime list -a -s | tr -d '\r' | grep -v baidu | head -1)
  [[ -n "$IME" ]] && { sh_ ime enable "$IME"; sh_ ime set "$IME"; try adb shell pm disable-user --user 0 com.baidu.input_auto; }
fi

if [[ -n "$TZ_" ]]; then
  # adb shell runs it through the panel's shell: only Olson-id characters (same rule as the web installer)
  [[ "$TZ_" =~ ^[A-Za-z0-9_+/-]+$ ]] || { echo "bad --timezone '$TZ_' (an id like Europe/Berlin)" >&2; exit 1; }
  step "Time zone $TZ_"
  sh_ settings put global auto_time_zone 0
  sh_ service call alarm 3 s16 "$TZ_"
  # kept by the root helper (v4): Aqara's SystemManager resets the zone at every boot
  sh_ setprop persist.s1kiosk.timezone "$TZ_"
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

if [[ $SWITCHED -eq 1 && $HAVE_PSK -eq 1 ]]; then resend_wifi; fi
WIFI_PSK=""

# A factory-fresh panel has no Wi-Fi yet (no wlan0): that must not fail the run under set -e.
IP=$(adb shell ip -4 -o addr show wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -1 || true)
cat <<EOF

Done.
- Log in to Home Assistant on the panel once (the session is remembered).
- Home Assistant: Settings -> Devices & services -> Add integration -> ESPHome,
  host ${IP:-<panel IP>}, port 6053 (or accept the auto-discovered "S1 Panel").
- Hidden settings on the panel: tap the top-left corner 5 times.
- Undo everything: $0 --undo
EOF
if [[ $SWITCHED -eq 1 ]]; then
  if [[ $DATA_KEPT -eq 1 ]]; then echo "- Switched builds: the app's settings were kept."
  else echo "- Switched builds: the app's settings were reset (sign in again; Home Assistant may ask to set the panel up again)."; fi
fi
if [[ $WIFI_LOST -eq 1 ]]; then
  cat <<EOF
- The panel forgot its Wi-Fi $(net_name) with the old build. Send it again: the web installer's
  #/wifi page over USB, or Improv over Bluetooth (the panel's setup screen, or Home Assistant).
EOF
fi
