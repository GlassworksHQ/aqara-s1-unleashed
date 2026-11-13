#!/usr/bin/env bash
# Off-device tests of tools/install.sh's signing-key switch and Wi-Fi-keeping --undo, of the panel
# side (tools/install/s1kiosk-switch.sh) and of tools/install/improv-send.py. A fake `adb` simulates
# the panel (PackageManager, device admin, root, dumpsys wifi, Improv); nothing talks to a real one.
# Runs on the host (macOS or Linux, bash + python3), in a few seconds:
#
#   tools/install/test/install-tests.sh
#
# The Wi-Fi password in the fixtures is fake. The tests check that it reaches the new app and
# appears nowhere else: not in install.sh's output, not in any adb command line.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
FIX="$HERE/fixtures"
PASS=0; FAILS=0
ok() { PASS=$((PASS + 1)); echo "ok   $*"; }
no() { FAILS=$((FAILS + 1)); echo "FAIL $*"; }
check() { local d=$1; shift; if "$@"; then ok "$d"; else no "$d"; fi; }
has() { case "$1" in *"$2"*) return 0 ;; esac; echo "    want '$2' in:"; sed 's/^/      | /' <<<"$1" | tail -25; return 1; }
hasnt() { case "$1" in *"$2"*) echo "    did not want '$2' in:"; sed 's/^/      | /' <<<"$1" | tail -25; return 1 ;; esac; return 0; }
before() { # <log> <first> <second>: first occurrence of <first> comes before the first of <second>
  local a b
  a=$(grep -nF -- "$2" <<<"$1" | head -1 | cut -d: -f1); b=$(grep -nF -- "$3" <<<"$1" | head -1 | cut -d: -f1)
  [[ -n $a && -n $b && $a -lt $b ]] || { echo "    want '$2' (line ${a:-none}) before '$3' (line ${b:-none})"; return 1; }
}
PSK='fixture <not> "real" & fake'
PSK_SHA=$(printf '%s' "$PSK" | shasum -a 256 | cut -d' ' -f1)

T=$(mktemp -d "${TMPDIR:-/tmp}/s1-install-test.XXXXXX")
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/pbin"
w() { cat > "$1"; chmod 755 "$1"; }

# ---- the panel's own commands, for s1kiosk-switch.sh (run on this computer against $T/panel) ----
w "$T/pbin/stat" <<'EOF'
#!/bin/sh
# stat -c %u <path>: the app's uid for its data dirs
[ "$1" = -c ] && [ "$2" = %u ] || exit 1
[ -e "$3" ] || exit 1
cat "$S1T/uid"
EOF
w "$T/pbin/pm" <<'EOF'
#!/bin/sh
[ "$1 $2 $3" = "list packages -U" ] && [ "$(cat "$S1T/installed")" != none ] && echo "package:sh.edm.s1kiosk uid:$(cat "$S1T/uid")"
exit 0
EOF
w "$T/pbin/chown" <<'EOF'
#!/bin/sh
echo "chown $*" >> "$S1T/root.log"
EOF
w "$T/pbin/restorecon" <<'EOF'
#!/bin/sh
echo "restorecon $*" >> "$S1T/root.log"
EOF

# A fresh app data dir, as installd makes it: lib -> the install's native libs, empty cache dirs.
fresh_data() { # <uid> <build>
  rm -rf "$T/panel/ce" "$T/panel/de"; mkdir -p "$T/panel/ce/cache" "$T/panel/ce/code_cache" "$T/panel/de"
  ln -s "/data/app/sh.edm.s1kiosk-$2/lib/arm" "$T/panel/ce/lib"
  echo "$1" > "$T/uid"; echo fresh > "$T/panel/ce/fresh"
}

# ---- fake adb: one simulated panel, state in $T ----
w "$T/bin/adb" <<'EOF'
#!/usr/bin/env bash
S=$S1T
st() { cat "$S/$1" 2>/dev/null; }
put() { printf '%s' "$2" > "$S/$1"; }
fresh_data() {
  rm -rf "$S/panel/ce" "$S/panel/de"; mkdir -p "$S/panel/ce/cache" "$S/panel/ce/code_cache" "$S/panel/de"
  ln -s "/data/app/sh.edm.s1kiosk-$2/lib/arm" "$S/panel/ce/lib"; echo "$1" > "$S/uid"; echo fresh > "$S/panel/ce/fresh"
}
echo "$*" >> "$S/adb.log"
case "$1" in
  get-state) echo device; exit 0 ;;
  push) cp "$2" "$S/panel/$(basename "$3")"; exit 0 ;;
  forward) [ "$2" = tcp:0 ] && echo 41234; exit 0 ;;
  install)
    case "$(st installed)" in
      debug) echo "adb: failed to install x.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package sh.edm.s1kiosk signatures do not match previously installed version; ignoring!]"; exit 1 ;;
    esac
    [ "$(st new-install)" = fail ] && { echo "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"; exit 1; }
    [ "$(st installed)" = none ] && fresh_data 10072 release
    put installed release; put disabled 0; echo "Performing Streamed Install"; echo Success; exit 0 ;;
  shell) shift ;;
  *) exit 0 ;;
esac
cmd="$*"
case "$cmd" in
  "getprop ro.product.model") echo "Aqara S1 Plus" ;;
  "getprop ro.build.version.sdk") echo 29 ;;
  "pm list packages sh.edm.s1kiosk") [ "$(st installed)" != none ] && echo "package:sh.edm.s1kiosk" ;;
  "dumpsys package sh.edm.s1kiosk")
    if [ "$(st installed)" = release ]; then printf '    versionName=0.4.1\n    pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]\n'
    else printf '    versionName=0.1.0\n    pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]\n'; fi ;;
  "timeout 15 su -c 'id -u' 2>/dev/null") [ "$(st root)" = 1 ] && echo 0 || exit 1 ;;
  "dumpsys wifi") cat "$(st wifi-dump)" ;;
  "ip -4 -o addr show wlan0") echo "3: wlan0    inet 192.0.2.50/24 brd 192.0.2.255 scope global wlan0" ;;
  "pm path sh.edm.s1kiosk") echo "package:/data/app/sh.edm.s1kiosk-Abc==/base.apk" ;;
  "cp /data/app/sh.edm.s1kiosk-Abc==/base.apk /data/local/tmp/s1kiosk-old.apk") : ;;
  *"--ez remove_admin true") put admin 0 ;;
  "dumpsys device_policy") [ "$(st admin)" = 1 ] && echo "Admin sh.edm.s1kiosk/.AdminReceiver" || echo "no admins" ;;
  "dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver") put admin 1 ;;
  "pm disable-user --user 0 sh.edm.s1kiosk") put disabled 1 ;;
  "pm enable sh.edm.s1kiosk") put disabled 0 ;;
  "pm uninstall sh.edm.s1kiosk")
    if [ "$(st admin)" = 1 ] || [ "$(st uninstall)" = fail ]; then echo "Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]"; exit 1; fi
    put installed none; rm -rf "$S/panel/ce" "$S/panel/de"; echo Success ;;
  "pm install -r -g /data/local/tmp/s1kiosk-old.apk") fresh_data 10073 debug; put installed debug; put disabled 0; echo Success ;;
  "grep -c ' @s1kiosk_improv\$' /proc/net/unix") echo 1 ;;
  "cmd package set-home-activity com.aqara.inhome.launcher/.MainActivity") put home aqara ;;
  "cmd package resolve-activity"*) [ "$(st home)" = aqara ] && echo "com.aqara.inhome.launcher/.MainActivity" || echo "sh.edm.s1kiosk/.MainActivity" ;;
  "su -c 'sh /data/local/tmp/s1kiosk-switch.sh "*"'")
    [ "$(st root)" = 1 ] || { echo "Permission denied"; exit 1; }
    a=${cmd#su -c \'sh /data/local/tmp/s1kiosk-switch.sh }; a=${a%\'}
    case "$a" in backup|restore) [ "$(st disabled)" = 1 ] || { echo "TEST: $a while the app is enabled" >> "$S/violations"; } ;; esac
    echo "$a" >> "$S/root-cmds"
    # shellcheck disable=SC2086
    PATH="$S/pbin:$PATH" S1_CE="$S/panel/ce" S1_DE="$S/panel/de" S1_WORK="$S/panel/work" \
      S1_WIFI_STORES="$S/WifiConfigStore.xml" sh "$S/panel/s1kiosk-switch.sh" $a
    exit $? ;;
  *) : ;;
esac
exit 0
EOF
# The new app's Improv over adb: records what it was sent (the password only as a hash).
w "$T/fake-improv.py" <<'EOF'
import hashlib, os, sys
ssid, psk = sys.stdin.buffer.read().split(b"\0", 1)
open(os.path.join(os.environ["S1T"], "improv.got"), "w").write(ssid.decode() + "\n" + hashlib.sha256(psk).hexdigest() + "\n")
print("improv-send: the panel joined " + ssid.decode())
EOF
export S1T="$T" PATH="$T/bin:$PATH" S1_IMPROV_SEND="$T/fake-improv.py"
APK="$T/kiosk-release.apk"; echo apk > "$APK"

# A debug build with its data, as HOME + device admin, on Wi-Fi it saved.
panel() { # [wifi fixture]
  rm -rf "$T/panel" "$T"/{adb.log,root-cmds,root.log,violations,improv.got,installed,disabled,root,admin,home,new-install,uninstall}
  mkdir -p "$T/panel"
  cp "$FIX/WifiConfigStore.xml" "$T/WifiConfigStore.xml"
  fresh_data 10071 debug
  rm "$T/panel/ce/fresh"
  mkdir -p "$T/panel/ce/shared_prefs" "$T/panel/ce/files/mozilla"
  echo '<map><string name="ha_url">http://ha.local:8123</string></map>' > "$T/panel/ce/shared_prefs/kiosk.xml"
  echo profile > "$T/panel/ce/files/mozilla/prefs.js"; echo junk > "$T/panel/ce/cache/c"; echo de > "$T/panel/de/de.txt"
  printf debug > "$T/installed"; printf 0 > "$T/disabled"; printf 1 > "$T/root"; printf 1 > "$T/admin"
  printf '%s' "$FIX/${1:-dumpsys-wifi-kiosk.txt}" > "$T/wifi-dump"
}
inst() { "$REPO/tools/install.sh" --apk "$APK" --no-models --no-debloat "$@" </dev/null 2>&1; }

echo "== s1kiosk-switch.sh (panel side)"
panel
SW="$REPO/tools/install/s1kiosk-switch.sh"
cp "$SW" "$T/panel/s1kiosk-switch.sh"
out=$(S1_WIFI_STORES="$FIX/WifiConfigStore.xml" sh "$SW" wifi-list)
check "wifi-list: only kiosk-saved networks, no secrets" test "$out" = $'1 psk &quot;Home &amp; Garden&quot;\n2 open &quot;Cafe&quot;'
check "wifi-psk 1: that network's stored key" test "$(S1_WIFI_STORES="$FIX/WifiConfigStore.xml" sh "$SW" wifi-psk 1)" = '&quot;fixture &lt;not&gt; &quot;real&quot; &amp; fake&quot;'
check "wifi-psk of a missing network fails" bash -c '! S1_WIFI_STORES="$1" sh "$2" wifi-psk 3 2>/dev/null' _ "$FIX/WifiConfigStore.xml" "$SW"
check "wifi-psk refuses a non-number" bash -c '! sh "$1" wifi-psk "1;id" 2>/dev/null' _ "$SW"
env_sw() { PATH="$T/pbin:$PATH" S1_CE="$T/panel/ce" S1_DE="$T/panel/de" S1_WORK="$T/panel/work" sh "$SW" "$@"; }
out=$(env_sw backup)
check "backup: reports what it kept" has "$out" "kept 2 item(s)"
check "backup: complete marker, no lib/cache" bash -c '[ -f "$1/complete" ] && [ ! -e "$1/ce/lib" ] && [ ! -e "$1/ce/cache" ] && [ -f "$1/ce/files/mozilla/prefs.js" ] && [ -f "$1/de/de.txt" ]' _ "$T/panel/work"
fresh_data 10072 release; printf release > "$T/installed"
out=$(env_sw restore 2>&1)
check "restore: into the new uid" has "$out" "restored for uid 10072"
check "restore: data back, the fresh install's files gone" bash -c '[ -f "$1/shared_prefs/kiosk.xml" ] && [ -f "$1/files/mozilla/prefs.js" ] && [ ! -e "$1/fresh" ] && [ -f "$2/de.txt" ]' _ "$T/panel/ce" "$T/panel/de"
check "restore: lib still points at the new install" test "$(readlink "$T/panel/ce/lib")" = /data/app/sh.edm.s1kiosk-release/lib/arm
check "restore: owned by the new uid, labels restored" bash -c 'grep -q "chown -R 10072:10072 .*/ce/shared_prefs" "$1" && grep -q "restorecon -R .*/ce" "$1"' _ "$T/root.log"
env_sw discard
check "discard: the copy is gone" test ! -e "$T/panel/work"
check "restore without a copy fails" bash -c '! "$@" restore 2>/dev/null' _ env PATH="$T/pbin:$PATH" S1_CE="$T/panel/ce" S1_WORK="$T/panel/work" sh "$SW"
printf none > "$T/installed"
mkdir -p "$T/panel/work"; touch "$T/panel/work/complete"
check "restore refuses when PackageManager disagrees" bash -c '! "$@" restore 2>/dev/null' _ env PATH="$T/pbin:$PATH" S1_CE="$T/panel/ce" S1_WORK="$T/panel/work" sh "$SW"

echo "== improv-send.py against a fake S1 Kiosk"
python3 - "$REPO/tools/install/improv-send.py" <<'EOF' && ok "improv-send: sends ssid + password, exit 0; wrong password exit 3; never prints it" || no "improv-send"
import os, socket, subprocess, sys, threading
sys.path.insert(0, os.path.dirname(sys.argv[1]))
import importlib.util
spec = importlib.util.spec_from_file_location("improv", sys.argv[1]); im = importlib.util.module_from_spec(spec); spec.loader.exec_module(im)
def serve(reject):
    srv = socket.socket(); srv.bind(("127.0.0.1", 0)); srv.listen(8)
    got = []
    def loop():
        while True:
            try: c, _ = srv.accept()
            except OSError: return
            r = im.Reader()
            while True:
                d = c.recv(4096)
                if not d: break
                for kind, data in r.push(d):
                    if data[0] == im.REQUEST_STATE: c.sendall(im.frame(im.STATE, bytes([2])))
                    if data[0] == im.WIFI_SETTINGS:
                        n = data[2]; ssid = data[3:3 + n].decode(); m = data[3 + n]; psk = data[4 + n:4 + n + m].decode()
                        got.append((ssid, psk))
                        if reject: c.sendall(im.frame(im.ERROR, bytes([3])))
                        else:
                            s = im.strings(""); c.sendall(im.frame(im.RPC_RESULT, bytes([im.WIFI_SETTINGS, len(s)]) + s))
            c.close()
    threading.Thread(target=loop, daemon=True).start()
    return srv, got
psk = 'fixture <not> "real" & fake'
for reject, want in ((False, 0), (True, 3)):
    srv, got = serve(reject)
    p = subprocess.run([sys.executable, sys.argv[1], str(srv.getsockname()[1])], input=b"Home & Garden\0" + psk.encode(), capture_output=True, timeout=60)
    srv.close()
    assert p.returncode == want, (p.returncode, p.stderr)
    assert got and got[-1] == ("Home & Garden", psk), got
    assert b"fixture" not in p.stdout + p.stderr, p.stdout + p.stderr
EOF

echo "== install.sh: debug build -> this build, with root"
panel
out=$(inst --switch); rc=$?
log=$(cat "$T/adb.log")
check "exit 0" test $rc -eq 0
check "explains it" has "$out" "development build (0.1.0)"
check "says the data is kept" has "$out" "settings, sign-in and keys are kept"
check "app data restored" has "$out" "app data restored"
check "the Wi-Fi went to the new app" test "$(cat "$T/improv.got" 2>/dev/null)" = "Home & Garden"$'\n'"$PSK_SHA"
check "the password is in no output" hasnt "$out" "fixture"
check "the password is in no adb command" hasnt "$log" "fixture"
check "backup and restore with the app disabled" test ! -e "$T/violations"
check "data carried into the new install" bash -c '[ -f "$1/shared_prefs/kiosk.xml" ] && [ ! -e "$1/fresh" ]' _ "$T/panel/ce"
check "ends enabled, release, admin" test "$(cat "$T/installed") $(cat "$T/disabled") $(cat "$T/admin")" = "release 0 1"
check "copy discarded" test ! -e "$T/panel/work"
check "Wi-Fi read before the uninstall" before "$log" "wifi-psk 1" "shell pm uninstall"
check "admin dropped before disabling" before "$log" "remove_admin true" "pm disable-user --user 0 sh.edm.s1kiosk"
check "backup before the uninstall" before "$log" "s1kiosk-switch.sh backup" "shell pm uninstall"
check "restore after the new install" before "$(sed -n '/pm uninstall/,$p' <<<"$log")" "install -r -g $APK" "s1kiosk-switch.sh restore"
check "Wi-Fi sent after the kiosk is started" before "$(sed -n '/pm uninstall/,$p' <<<"$log")" "am start -n sh.edm.s1kiosk/.MainActivity" "forward tcp:0 localabstract:s1kiosk_improv"
check "temporary files removed" has "$log" "shell rm -f /data/local/tmp/s1kiosk-old.apk /data/local/tmp/s1kiosk-switch.sh"
check "no 'forgot its Wi-Fi' note" hasnt "$out" "forgot its Wi-Fi"

echo "== install.sh: without root"
panel; printf 0 > "$T/root"
out=$(inst --switch); rc=$?
log=$(cat "$T/adb.log")
check "exit 0" test $rc -eq 0
check "says it starts fresh" has "$out" "the app starts fresh"
check "no root commands" hasnt "$log" "su -c 'sh"
check "no root script pushed" hasnt "$log" "push "
check "no password to send (no terminal to ask)" test ! -e "$T/improv.got"
check "says how to send the Wi-Fi again" has "$out" "forgot its Wi-Fi \"Home & Garden\""
check "settings reset, said so" has "$out" "settings were reset"
check "ends enabled, release" test "$(cat "$T/installed") $(cat "$T/disabled")" = "release 0"

echo "== install.sh: a network S1 Kiosk didn't save"
panel dumpsys-wifi-other.txt
out=$(inst --switch)
check "says it stays" has "$out" "wasn't saved by S1 Kiosk and stays"
check "reads no Wi-Fi details" hasnt "$(cat "$T/root-cmds")" "wifi-"
check "nothing to send" test ! -e "$T/improv.got"

echo "== install.sh: no terminal and no --switch"
panel
out=$(inst); rc=$?
check "stops" test $rc -ne 0
check "says nothing changed" has "$out" "Nothing was changed"
check "nothing changed" test "$(cat "$T/installed") $(cat "$T/disabled") $(cat "$T/admin")" = "debug 0 1"
check "no uninstall" hasnt "$(cat "$T/adb.log")" "pm uninstall"

echo "== install.sh: the new build won't install"
panel; printf fail > "$T/new-install"
out=$(inst --switch); rc=$?
check "fails" test $rc -ne 0
check "says the old build is back with its data" has "$out" "previous build is back with its data"
check "old build back, enabled, admin, data restored" bash -c '[ "$(cat "$1/installed") $(cat "$1/disabled") $(cat "$1/admin")" = "debug 0 1" ] && [ -f "$1/panel/ce/shared_prefs/kiosk.xml" ]' _ "$T"
check "copy discarded" test ! -e "$T/panel/work"

echo "== install.sh: Android won't remove the old build"
panel; printf fail > "$T/uninstall"
out=$(inst --switch); rc=$?
check "fails, unchanged" bash -c '[ "$1" -ne 0 ] && [ "$(cat "$2/installed") $(cat "$2/disabled") $(cat "$2/admin")" = "debug 0 1" ]' _ $rc "$T"
check "says so" has "$out" "It is unchanged"

echo "== install.sh --dry-run on a debug build"
panel
out=$(inst --dry-run); rc=$?
log=$(cat "$T/adb.log")
check "exit 0" test $rc -eq 0
check "prints the plan" has "$out" "(as root) /data/local/tmp/s1kiosk-switch.sh backup"
check "prints the Wi-Fi re-send" has "$out" "Wi-Fi \"Home & Garden\" to the new app over Improv"
check "changes nothing" test "$(cat "$T/installed") $(cat "$T/disabled") $(cat "$T/admin")" = "debug 0 1"
for c in "install " "push " "pm uninstall" "pm disable-user" "s1kiosk-switch.sh" "forward "; do
  check "dry run: no '$c'" hasnt "$log" "$c"
done

echo "== install.sh --dry-run on a release build (the keys may well match)"
panel; printf release > "$T/installed"
out=$(inst --dry-run); rc=$?
check "exit 0" test $rc -eq 0
check "the switch is only a possibility" has "$out" "If the keys differ (S1 Kiosk on the panel signed with another key than"
check "...never stated as a fact" hasnt "$out" "is signed with a different key"
check "...nor a development build" hasnt "$out" "development build"

echo "== install.sh --dry-run: de-bloat disables the launcher in the same run"
panel; printf release > "$T/installed"
out=$("$REPO/tools/install.sh" --apk "$APK" --no-models --dry-run </dev/null 2>&1)
check "one debloat call with --launcher" has "$out" "debloat.sh disable --launcher"
check "...no separate disable-launcher" hasnt "$out" "disable-launcher"

echo "== install.sh: a normal update is unchanged"
panel; printf release > "$T/installed"
out=$(inst); rc=$?
check "exit 0" test $rc -eq 0
check "no switch" hasnt "$out" "Switch S1 Kiosk"
check "no uninstall" hasnt "$(cat "$T/adb.log")" "pm uninstall"

echo "== install.sh --undo keeps the panel on its Wi-Fi"
panel
out=$("$REPO/tools/install.sh" --undo </dev/null 2>&1); rc=$?
log=$(cat "$T/adb.log")
check "exit 0" test $rc -eq 0
check "disables instead of uninstalling" bash -c 'grep -q "shell pm disable-user --user 0 sh.edm.s1kiosk" <<<"$1" && ! grep -q "pm uninstall sh.edm.s1kiosk" <<<"$1"' _ "$log"
check "says why" has "$out" "it saved the panel's Wi-Fi \"Home & Garden\""
panel dumpsys-wifi-other.txt
out=$("$REPO/tools/install.sh" --undo </dev/null 2>&1)
check "someone else's network: uninstalls" has "$(cat "$T/adb.log")" "shell pm uninstall sh.edm.s1kiosk"
check "and says it is removed" has "$out" "S1 Kiosk is removed"
panel
out=$("$REPO/tools/install.sh" --undo --dry-run </dev/null 2>&1)
check "dry run: shows the disable" has "$out" "+ adb shell pm disable-user --user 0 sh.edm.s1kiosk"
check "dry run: changes nothing" test "$(cat "$T/installed") $(cat "$T/disabled")" = "debug 0"

echo "== fel-install.sh --undo: refuses an incomplete backup, restores a complete one"
# Stub tools: nothing reaches FEL or a real fastboot. The fake fastboot is our U-Boot, already up,
# with partitions in $T/emmc: flash writes one, and fb-read-partition.sh's oem run + get_staged read
# it back.
mkdir -p "$T/fbin" "$T/ub" "$T/undo" "$T/emmc"
for t in sunxi-fel adb docker; do w "$T/fbin/$t" <<STUB
#!/bin/sh
echo "$t \$*" >> "$T/tools.log"; exit 1
STUB
done
w "$T/fbin/fastboot" <<STUB
#!/bin/sh
echo "fastboot \$*" >> "$T/tools.log"
case "\$1 \${2:-}" in
  "devices "*) echo "FAKE fastboot" ;;
  "getvar product") echo "product: sunxi" >&2 ;;
  "getvar partition-size:boot") echo "partition-size:boot: 0x0000000002000000" >&2 ;;
  "getvar partition-size:super") echo "partition-size:super: 0x0000000060000000" >&2 ;;
  "flash "*) cp "\$3" "$T/emmc/\$2" ;;
  "oem run:part start mmc "*) set -- \$2; echo "\$5" > "$T/emmc/.cur" ;;
  "oem run:"*) ;;
  "get_staged "*) cp "$T/emmc/\$(cat "$T/emmc/.cur")" "\$2" ;;
  "reboot "*) ;;
  *) exit 1 ;;
esac
STUB
: > "$T/ub/u-boot-sunxi-with-spl.bin"
printf 'not a boot image' > "$T/undo/boot.img"; printf 'vbmeta' > "$T/undo/vbmeta.img"
(cd "$T/undo" && shasum -a 256 boot.img vbmeta.img > SHA256SUMS)
fel_undo() { : > "$T/tools.log"; PATH="$T/fbin:$PATH" UBOOT_BUILD="$T/ub" "$REPO/tools/install/fel-install.sh" --undo "$T/undo" </dev/null 2>&1; }
out=$(fel_undo); rc=$?
check "no bootloader.img: refused" test "$rc" -ne 0
check "and says why" has "$out" "has no bootloader.img: the backup is incomplete. Nothing was changed."
check "and the way forward" has "$out" "flash them by hand from our U-Boot"
check "before any tool runs" test ! -s "$T/tools.log"
printf 'splash' > "$T/undo/bootloader.img"
(cd "$T/undo" && shasum -a 256 boot.img vbmeta.img bootloader.img > SHA256SUMS)
out=$(fel_undo); rc=$?
check "complete backup: exit 0" test "$rc" -eq 0
check "flashes boot, vbmeta, splash, in that order" test "$(grep '^fastboot flash ' "$T/tools.log")" = "fastboot flash boot $T/undo/boot.img
fastboot flash vbmeta $T/undo/vbmeta.img
fastboot flash bootloader $T/undo/bootloader.img"
check "each one read back the same" bash -c 'for p in boot vbmeta bootloader; do cmp -s "$1/undo/$p.img" "$1/emmc/$p" || exit 1; done' _ "$T"
check "says all three are back" has "$out" "stock boot, vbmeta and splash are back"

echo "== fel-install.sh --undo: refuses a backup whose splash is ours (#106)"
# A FAT16 boot-resource like the panel's bootloader partition, with <bmp> as BOOTLOGO.BMP in clusters
# laid out backwards (so the FAT chain is followed); "other" changes one pixel: a splash not ours.
boot_resource() { # <bmp> <out.img> [other]
  python3 - "$@" <<'PY'
import struct, sys
bmp = bytearray(open(sys.argv[1], 'rb').read())
if len(sys.argv) > 3: bmp[1054] ^= 0xff
bps, spc, reserved, nfats, nroot = 512, 4, 4, 2, 512
cs = bps * spc; n = -(-len(bmp) // cs); total = n + 1; spf = -(-(total + 2) * 2 // bps)
root = (reserved + nfats * spf) * bps; data = root + nroot * 32; fat = reserved * bps
img = bytearray(data + total * cs)
struct.pack_into('<HBHBH', img, 11, bps, spc, reserved, nfats, nroot); img[21] = 0xf8
struct.pack_into('<H', img, 22, spf); img[54:62] = b'FAT16   '
def entry(i, name, first, size):
    e = root + i * 32; img[e:e + 11] = name; img[e + 11] = 0x20; struct.pack_into('<HI', img, e + 26, first, size)
entry(0, b'BAT     BMP', 2, 16); struct.pack_into('<H', img, fat + 4, 0xffff)
cl = [total + 1 - i for i in range(n)]
for i, c in enumerate(cl):
    struct.pack_into('<H', img, fat + 2 * c, cl[i + 1] if i + 1 < n else 0xffff)
    img[data + (c - 2) * cs: data + (c - 2) * cs + len(bmp[i * cs:(i + 1) * cs])] = bmp[i * cs:(i + 1) * cs]
entry(1, b'BOOTLOGOBMP', cl[0], len(bmp))
open(sys.argv[2], 'wb').write(img)
PY
}
LOGO="$REPO/branding/glass-tile/bootlogo.bmp"
boot_resource "$LOGO" "$T/undo/bootloader.img"
(cd "$T/undo" && shasum -a 256 boot.img vbmeta.img bootloader.img > SHA256SUMS)
rm -f "$T/emmc/bootloader"
out=$(fel_undo); rc=$?
check "our splash: refused" test "$rc" -ne 0
LOGO_NAME=$(python3 -c 'import hashlib, json, sys; h = hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest(); print(next(b["name"] for b in json.load(open(sys.argv[2]))["bootlogos"] if b["sha256"] == h))' "$LOGO" "$REPO/branding/bootlogos.json")
check "and says why" has "$out" "bootloader.img holds our splash ($LOGO_NAME), not Aqara's: this backup was taken after an install. Nothing was changed."
check "and the way forward" has "$out" "put back only boot and vbmeta by hand"
check "before any tool runs" test ! -s "$T/tools.log"
check "nothing written" test ! -e "$T/emmc/bootloader"
boot_resource "$LOGO" "$T/undo/bootloader.img" other
(cd "$T/undo" && shasum -a 256 boot.img vbmeta.img bootloader.img > SHA256SUMS)
out=$(fel_undo); rc=$?
check "a splash that isn't ours: exit 0" test "$rc" -eq 0
check "and it is restored" cmp -s "$T/undo/bootloader.img" "$T/emmc/bootloader"
# The splash check itself fails (a broken list, a crash): undo stops rather than guess.
REAL_PY=$(command -v python3)
w "$T/fbin/python3" <<STUB
#!/bin/sh
case " \$* " in *" --ours "*) echo "Traceback: the list is broken" >&2; exit 1 ;; esac
exec "$REAL_PY" "\$@"
STUB
rm -f "$T/emmc/bootloader"
out=$(fel_undo); rc=$?
rm -f "$T/fbin/python3"
check "splash check fails: refused" test "$rc" -ne 0
check "and says why" has "$out" "could not check the splash in $T/undo/bootloader.img. Nothing was changed."
check "before any tool runs" test ! -s "$T/tools.log"
check "nothing written" test ! -e "$T/emmc/bootloader"

echo "== docs/install.md#undo: the by-hand check passes boot and vbmeta, and stops on either"
CHK=$(awk '/install-tests.sh runs this block/ {f = 1; next} f && /^```sh$/ {g = 1; next} g && /^```$/ {exit} g' "$REPO/docs/install.md")
check "the doc has the check" test -n "$CHK"
bh() { (cd "$1" && sh -c "$CHK" 2>&1); }
mkdir -p "$T/bh"
printf 'boot' > "$T/bh/boot.img"; printf 'vbmeta' > "$T/bh/vbmeta.img"; printf 'splash' > "$T/bh/bootloader.img"
(cd "$T/bh" && shasum -a 256 boot.img vbmeta.img bootloader.img > SHA256SUMS) && rm "$T/bh/bootloader.img"
check "bootloader.img missing: goes on" has "$(bh "$T/bh")" "boot and vbmeta OK: go on"
printf 'damaged' > "$T/bh/vbmeta.img"
check "vbmeta damaged: stops" hasnt "$(bh "$T/bh")" "go on"
printf 'vbmeta' > "$T/bh/vbmeta.img"; grep -v ' vbmeta\.img$' "$T/bh/SHA256SUMS" > "$T/bh/s" && mv "$T/bh/s" "$T/bh/SHA256SUMS"
check "vbmeta entry missing: stops" hasnt "$(bh "$T/bh")" "go on"

echo
echo "$PASS passed, $FAILS failed"
[ "$FAILS" -eq 0 ]
