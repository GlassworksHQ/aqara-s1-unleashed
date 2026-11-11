#!/usr/bin/env bash
# Runs inside the arm32v7 container of tools/radio/test/run.sh (as root, disposable filesystem).
# /repo is the checkout (read-only). Builds an Android-like layout with mocks, then tests:
#   s1kiosk_verify + make-bundle.py, s1kiosk_bundle.sh (install, --check, every refusal),
#   the helper's loader (bundle helper, fallback, quick-exit marking, dev override),
#   the helper v4 (off, firmware flashing + rollback + request guards, GBL checks, Zigbee stack).
set -uo pipefail
R=/repo; T=/t; TB=/tb
PASS=0; FAILS=0
ok() { PASS=$((PASS + 1)); echo "ok   $*"; }
no() { FAILS=$((FAILS + 1)); echo "FAIL $*"; }
check() { local d=$1; shift; if "$@"; then ok "$d"; else no "$d"; fi; }
prop() { cat "$T/props/$1" 2>/dev/null; }
has() { case "$1" in *"$2"*) return 0 ;; esac; echo "    got: '$1' (want '$2')"; return 1; }
hasnt_pct() { case "$1" in *": %"*|*": % "*) echo "    got an empty percentage: '$1'"; return 1 ;; esac; return 0; }

# ---------------------------------------------------------------------------------- layout + mocks
mkdir -p "$T/props" "$T/bin" "$TB" /system/bin /vendor/bin /vendor/firmware /dev/shm/cpcd/cpcd_0 /data/data /data/s1kiosk /etc
ln -sf /bin/mksh /system/bin/sh
touch /etc/cpcd.conf /etc/cpcd_ota.conf
export PATH="$T/bin:$PATH"
w() { cat > "$1"; chmod 755 "$1"; }
w "$T/bin/getprop" <<'EOF'
#!/bin/sh
[ -f "/t/props/$1" ] && cat "/t/props/$1"; exit 0
EOF
w "$T/bin/setprop" <<'EOF'
#!/bin/sh
echo "$1=$2" >> /t/setprop.log
case "$1" in
  ctl.start) echo running > "/t/props/init.svc.$2" ;;
  ctl.stop) echo stopped > "/t/props/init.svc.$2" ;;
  ctl.restart) echo "$2" >> /t/restarts ;;
  *) [ ${#2} -le 91 ] || { echo "setprop: value too long" >&2; exit 1; }; printf '%s' "$2" > "/t/props/$1" ;;
esac
EOF
w "$T/bin/log" <<'EOF'
#!/bin/sh
shift 2; echo "$*" >> /t/log
EOF
w "$T/bin/pm" <<'EOF'
#!/bin/sh
case "$1" in
  install) cat > /dev/null; echo 30300 > /t/pm-version; echo Success; exit 0 ;;
esac
echo "package:sh.edm.s1kiosk.other versionCode:1 uid:10077"
echo "package:sh.edm.s1kiosk versionCode:$(cat /t/pm-version 2>/dev/null || echo 30200) uid:10050"
EOF
printf '#!/bin/sh\nexit 0\n' | w "$T/bin/am"
w "$T/bin/pidof" <<'EOF'
#!/bin/sh
p=$(pgrep -x "$1" | tr '\n' ' '); [ -n "$p" ] && echo "${p% }"
EOF
# AlarmManager.setTimeZone as `service call alarm 3 s16 <id>`: Android takes known zones, and falls
# back to GMT for anything else (zones listed in /t/tz-unknown).
w "$T/bin/service" <<'EOF'
#!/bin/sh
[ "$1 $2 $3 $4" = "call alarm 3 s16" ] || exit 1
echo "$5" >> /t/tz-calls
if grep -qx -- "$5" /t/tz-unknown 2>/dev/null; then printf GMT > /t/props/persist.sys.timezone
else printf '%s' "$5" > /t/props/persist.sys.timezone; fi
echo "Result: Parcel(00000000    '....')"
EOF
w "$T/bin/settings" <<'EOF'
#!/bin/sh
case "$1" in
  get) cat "/t/settings.$2.$3" 2>/dev/null || echo 1 ;;
  put) echo "$4" > "/t/settings.$2.$3" ;;
esac
EOF
for b in iptables ip6tables iptables-restore ip6tables-restore iptables-save ip6tables-save; do printf '#!/bin/sh\nexit 0\n' | w "$T/bin/$b"; done
w "$T/bin/ip" <<'EOF'
#!/bin/sh
case "$*" in *"rule del"*) exit 1 ;; esac; exit 0
EOF
# Aqara's socat: the pty drain reads /t/stale once; bridges just sit there. (The "EZSP bridge" section
# swaps in the real socat.)
w /vendor/bin/socat <<'EOF'
#!/bin/sh
case "$*" in
  -h) printf '      exec:<command-line>\tgroups=FD,FIFO,SOCKET,EXEC,FORK,TERMIOS,PTY,PARENT,UNIX\n'; exit 0 ;;
  *"-u -T 1 FILE:"*) if [ -s /t/stale ]; then cat /t/stale; : > /t/stale; echo drained >> /t/drains; fi; exit 0 ;;
  *TCP-LISTEN*) echo "$*" >> /t/bridge.log; exec sleep 1000 ;;
esac
exit 1
EOF
cp /vendor/bin/socat "$T/socat-mock"
# Aqara's cpcd: -p reports /t/radio-app, -f "flashes": the radio then reports the APP that /t/fw-apps
# maps the image's sha256 to (garbage if unknown). /t/cpcd-fail-once makes one flash fail. It prints
# "Info : progress:<n>" lines like the real one (CRLF, blank lines between); /t/cpcd-slow makes the
# transfer take ~5 s (so the helper reports mid-flash), /t/cpcd-quiet prints no progress at all.
w /vendor/bin/cpcd <<'EOF'
#!/bin/sh
case "$*" in
  "-c /etc/cpcd.conf -p") echo "[19:40:00:823934] Info : Secondary APP v$(cat /t/radio-app)"; exit 0 ;;
  "-c /etc/cpcd_ota.conf -f "*)
    img=$4; echo "$img" >> /t/flashed
    # /t/cpcd-start-aqara: Aqara's boot sequence starts zigbee_cpcd in the middle of the flash
    if [ -e /t/cpcd-start-aqara ]; then setprop ctl.start zigbee_cpcd; sleep 3; fi
    prog() { [ -e /t/cpcd-quiet ] || printf '[19:40:49:485025] Info : progress:%s\r\n\r\n' "$1"; }
    echo "[19:40:49:419443] Info : Transferring firmware..."
    prog 0; prog 40
    if [ -e /t/cpcd-slow ]; then sleep 5; else sleep 1; fi
    prog 100
    if [ -e /t/cpcd-fail-always ]; then echo "[x] Info : Firmware upgrade failed"; exit 1; fi
    if [ -e /t/cpcd-fail-once ]; then rm -f /t/cpcd-fail-once; echo "[x] Info : Firmware upgrade failed"; exit 1; fi
    h=$(sha256sum "$img" | cut -d' ' -f1); a=$(grep "^$h " /t/fw-apps | cut -d' ' -f2)
    echo "${a:-garbage}" > /t/radio-app
    echo "[x] Info : Firmware upgrade successful"; exit 0 ;;
esac
exit 2
EOF
echo 0017 > "$T/radio-app"; : > "$T/fw-apps"
touch /dev/shm/cpcd/cpcd_0/ctrl.cpcd.sock

# The boot image: helper + loader, bundle service, verifier, a throwaway test key, fake otbr.
cp "$R/tools/radio/s1kiosk_radio.sh" "$R/tools/root/s1kiosk_bundle.sh" "$R/tools/root/out/s1kiosk_verify" "$TB/"
openssl genpkey -algorithm ed25519 -out "$T/key.pem" 2>/dev/null
openssl pkey -in "$T/key.pem" -pubout -outform DER | tail -c 32 | xxd -p -c 64 > "$TB/s1kiosk_bundle.pub"
openssl genpkey -algorithm ed25519 -out "$T/other.pem" 2>/dev/null
w "$TB/otbr-agent" <<'EOF'
#!/bin/sh
# fake otbr-agent (border-router build marker: /node/ba-id)
case "$*" in *--radio-version*) echo "VERSION:FAKE-RCP"; exit 0 ;; esac
exec sleep 1000
EOF
w "$TB/ot-ctl" <<'EOF'
#!/bin/sh
# fake ot-ctl: the Thread role and network name come from /t/ot-state and /t/ot-name (CRLF, like ot-ctl)
case "$1" in
  state) [ -f /t/ot-state ] && printf '%s\r\nDone\r\n' "$(cat /t/ot-state)" && exit 0 ;;
  networkname) [ -f /t/ot-name ] && printf '%s\r\nDone\r\n' "$(cat /t/ot-name)" && exit 0 ;;
esac
echo disabled
EOF

# The kiosk: uid 10050, its private files dir.
APP=/data/data/sh.edm.s1kiosk
mkdir -p "$APP/files/radio"; chown -R 10050:10050 "$APP"

# A synthetic GBL: header, application, eraseprog, end (little-endian tags as the Gecko bootloader).
gbl() { # out extra-tag-hex(optional) [truncate]
  python3 - "$@" <<'PY'
import struct, sys
def tag(i, body): return struct.pack('<II', i, len(body)) + body
data = tag(0x03A617EB, struct.pack('<II', 0x03000000, 0)) + tag(0xF40A0AF4, bytes(28))
if len(sys.argv) > 2 and sys.argv[2]:
    data += tag(int(sys.argv[2], 16), bytes(16))
data += tag(0xFD0303FD, struct.pack('<I', 0x4000) + bytes(range(256)) * 4) + tag(0xFC0404FC, b'\0\0\0\0')
if len(sys.argv) > 3: data = data[:-3]
open(sys.argv[1], 'wb').write(data)
PY
}
STOCK='/vendor/firmware/lichun#30000000#17#20250821#8f636f.bin'
gbl "$STOCK"
STOCK_SHA=$(sha256sum "$STOCK" | cut -d' ' -f1)
echo "$STOCK_SHA 0017" >> "$T/fw-apps"

mkbundle() { # out version key extra-args...
  local out=$1 v=$2 k=$3; shift 3
  python3 "$R/tools/radio/make-bundle.py" --version "$v" --key "$k" --out "$out" --created test --force "$@" >/dev/null
}
stage() { # bundle-dir: copy it where the app stages it, owned by the kiosk
  rm -rf "$APP/files/radio/bundle-in"; cp -r "$1/radio-bundle" "$APP/files/radio/bundle-in"
  chown -R 10050:10050 "$APP/files/radio/bundle-in"
}
install_bundle() { /system/bin/sh "$TB/s1kiosk_bundle.sh"; prop sys.s1kiosk.bundle.status; }
reset_radio() { rm -rf /data/s1kiosk/radio; rm -f "$T"/props/sys.s1kiosk.* "$T/restarts" "$T/log"; }
helper() { # env... : run the boot helper for a couple of loops
  env S1R_ONCE=${LOOPS:-2} S1R_SLEEP=0 S1R_LOADER_ONCE=1 "$@" /system/bin/sh "$TB/s1kiosk_radio.sh" >/dev/null 2>&1
}

# ------------------------------------------------------------------------------------- verifier
echo "--- s1kiosk_verify / make-bundle.py"
mkbundle "$T/b1" 100 "$T/key.pem" --file "s1kiosk_radio.sh=$R/tools/radio/s1kiosk_radio.sh:0700" \
  --file "otbr-agent=$TB/otbr-agent:0700" --dir "sisdk=$R/tools/radio/sisdk"
check "make-bundle writes manifest + signature" test -s "$T/b1/radio-bundle/manifest" -a -s "$T/b1/radio-bundle/manifest.sig"
check "verifier accepts it" "$TB/s1kiosk_verify" "$TB/s1kiosk_bundle.pub" "$T/b1/radio-bundle/manifest" "$T/b1/radio-bundle/manifest.sig"
mkbundle "$T/bx" 100 "$T/other.pem" --file "s1kiosk_radio.sh=$R/tools/radio/s1kiosk_radio.sh:0700"
"$TB/s1kiosk_verify" "$TB/s1kiosk_bundle.pub" "$T/bx/radio-bundle/manifest" "$T/bx/radio-bundle/manifest.sig" 2>/dev/null
check "verifier rejects another key (exit 1)" test $? = 1
printf '\x7fELFfake' > "$T/zigbeed"
python3 "$R/tools/radio/make-bundle.py" --version 1 --key "$T/key.pem" --out "$T/bz" --file "sisdk/bin/zigbeed=$T/zigbeed" >/dev/null 2>&1
check "make-bundle refuses to package zigbeed (MSLA)" test $? != 0
python3 "$R/tools/radio/make-bundle.py" --version 1 --key "$T/key.pem" --out "$T/bp" --force --file "../x=$TB/ot-ctl" >/dev/null 2>&1
check "make-bundle refuses a bad path" test $? != 0
python3 "$R/tools/radio/make-bundle.py" --version 3020000 --app-version 3.2.0 --key "$T/key.pem" --out "$T/bv" --file "ot-ctl=$TB/ot-ctl" >/dev/null 2>&1
check "make-bundle refuses a version above the app's versionCode" test $? != 0
python3 "$R/tools/radio/make-bundle.py" --version 30200 --app-version 3.2.0 --key "$T/key.pem" --out "$T/bv" --file "ot-ctl=$TB/ot-ctl" >/dev/null 2>&1
check "...and accepts the versionCode itself" test $? = 0
python3 "$R/tools/radio/make-bundle.py" --version 30200 --key "$T/key.pem" --out "$T/bv" --file "ot-ctl=$TB/ot-ctl" >/dev/null 2>&1
check "...and needs --app-version (or --force)" test $? != 0

# ------------------------------------------------------------------------------- bundle service
echo "--- s1kiosk_bundle.sh"
reset_radio; stage "$T/b1"
s=$(install_bundle)
check "installs a signed bundle" has "$s" "ok: radio bundle 100 installed"
check "files are in bundle/current, root-owned" test "$(stat -c %u /data/s1kiosk/radio/bundle/current/otbr-agent)" = 0
check "version floor recorded" test "$(cat /data/s1kiosk/radio/bundle/version)" = 100
check "app-readable state written (root, 0644)" test "$(cat /data/s1kiosk-pub/radio-bundle)/$(stat -c %a:%u /data/s1kiosk-pub/radio-bundle)" = "ok 100 100/644:0"
check "--check passes" has "$(/system/bin/sh "$TB/s1kiosk_bundle.sh" --check /data/s1kiosk/radio/bundle/current)" "100 helper"
s=$(install_bundle); check "same bundle again: no-op" has "$s" "already installed"

mkbundle "$T/b0" 99 "$T/key.pem" --file "otbr-agent=$TB/otbr-agent:0700"
stage "$T/b0"; s=$(install_bundle); check "older version refused (anti-rollback)" has "$s" "older than the installed 100"
stage "$T/bx"; s=$(install_bundle); check "wrong key refused" has "$s" "bad signature"

mkbundle "$T/b2" 101 "$T/key.pem" --file "otbr-agent=$TB/otbr-agent:0700" --file "ot-ctl=$TB/ot-ctl:0700"
stage "$T/b2"; echo tampered >> "$APP/files/radio/bundle-in/ot-ctl"
s=$(install_bundle); check "tampered file refused" has "$s" "wrong size"
check "...and the old bundle stays" test "$(cat /data/s1kiosk/radio/bundle/version)" = 100
stage "$T/b2"; printf 'x' | dd of="$APP/files/radio/bundle-in/ot-ctl" bs=1 seek=3 conv=notrunc 2>/dev/null
s=$(install_bundle); check "same-size tampering refused (sha256)" has "$s" "does not match the manifest"
stage "$T/b2"; rm "$APP/files/radio/bundle-in/ot-ctl"; ln -s /etc/passwd "$APP/files/radio/bundle-in/ot-ctl"
s=$(install_bundle); check "symlink refused" has "$s" "is a symlink"
stage "$T/b2"; chown 10077 "$APP/files/radio/bundle-in/ot-ctl"
s=$(install_bundle); check "file owned by another app refused" has "$s" "not owned by the uid"
stage "$T/b2"; ln "$APP/files/radio/bundle-in/ot-ctl" "$APP/files/radio/hardlink"
s=$(install_bundle); check "hard-linked file refused" has "$s" "extra hard links"; rm -f "$APP/files/radio/hardlink"
stage "$T/b2"; rm "$APP/files/radio/bundle-in/ot-ctl"; ln -s /dev/zero "$APP/files/radio/bundle-in/ot-ctl"
s=$(timeout 20 /system/bin/sh "$TB/s1kiosk_bundle.sh"; prop sys.s1kiosk.bundle.status); check "symlink to /dev/zero refused at once" has "$s" "is a symlink"
stage "$T/b2"; rm "$APP/files/radio/bundle-in/ot-ctl"; mkfifo "$APP/files/radio/bundle-in/ot-ctl"; chown 10050 "$APP/files/radio/bundle-in/ot-ctl"
s=$(timeout 20 /system/bin/sh "$TB/s1kiosk_bundle.sh"; prop sys.s1kiosk.bundle.status); check "FIFO refused without blocking" has "$s" "not a regular file"
stage "$T/b2"; mv "$APP/files/radio/bundle-in" "$T/elsewhere-in"; ln -s "$T/elsewhere-in" "$APP/files/radio/bundle-in"
s=$(install_bundle); check "staging dir swapped for a symlink refused" has "$s" "refused"; rm -f "$APP/files/radio/bundle-in"; rm -rf "$T/elsewhere-in"
stage "$T/b2"; echo "file 0700 1 $(printf %064d 0) evil" >> "$APP/files/radio/bundle-in/manifest"
s=$(install_bundle); check "manifest edited after signing refused" has "$s" "bad signature"
long=$(printf 'x%.0s' $(seq 1 150))
mkbundle "$T/b8" 101 "$T/key.pem" --file "$long=$TB/ot-ctl:0700"
stage "$T/b8"; rm "$APP/files/radio/bundle-in/$long"
s=$(install_bundle); check "a long error still reaches the status (cut to 91 bytes)" test "${#s}" -le 91 -a "${s#error}" != "$s"
stage "$T/b2"; s=$(install_bundle); check "newer bundle installs" has "$s" "ok: radio bundle 101 installed"
rm -f /data/s1kiosk/radio/bundle/version; stage "$T/b0"; s=$(install_bundle)
check "floor also comes from the installed manifest" has "$s" "older than the installed 101"
check "...and restarts the helper only if it runs" test ! -e "$T/restarts"
check "staging dir removed" test ! -e /data/s1kiosk/radio/bundle/stage

# ------------------------------------------------------------------------------------- loader
echo "--- loader"
reset_radio
echo off > "$T/props/persist.s1kiosk.radio_mode"
helper; check "no bundle: boot helper, bundle none" test "$(prop sys.s1kiosk.radio.source)/$(prop sys.s1kiosk.radio.bundle)" = "boot/none"
check "...published for the app" test "$(cat /data/s1kiosk-pub/radio-bundle)" = "none 0 0"
check "contract version 4" test "$(prop sys.s1kiosk.radio.version)" = 4
check "status ok off" has "$(prop sys.s1kiosk.radio.status)" "ok off"
stage "$T/b1"; install_bundle >/dev/null
helper; check "signed bundle helper runs" test "$(prop sys.s1kiosk.radio.source)" = "bundle 100"
check "bundle state ok 100" test "$(prop sys.s1kiosk.radio.bundle)" = "ok 100"
echo junk >> /data/s1kiosk/radio/bundle/current/otbr-agent
helper; check "tampered installed bundle: boot helper" has "$(prop sys.s1kiosk.radio.source)" "boot (bundle rejected"
check "...reported" has "$(prop sys.s1kiosk.radio.bundle)" "bad: bundle rejected: otbr-agent does not match"
reset_radio
printf '#!/bin/sh\nexit 3\n' > "$T/crash.sh"
mkbundle "$T/b3" 200 "$T/key.pem" --file "s1kiosk_radio.sh=$T/crash.sh:0700"
stage "$T/b3"; install_bundle >/dev/null
env S1R_ONCE=1 S1R_SLEEP=0 /system/bin/sh "$TB/s1kiosk_radio.sh" >/dev/null 2>&1
check "bundle helper exiting 3 times: marked bad" test -e /data/s1kiosk/radio/bundle/bad-200
check "...boot helper took over" has "$(prop sys.s1kiosk.radio.source)" "boot (bundle 200: its helper kept exiting)"
helper; check "...and is skipped next time" has "$(prop sys.s1kiosk.radio.bundle)" "bad: bundle 200"
check "...published as bad for the app" test "$(cat /data/s1kiosk-pub/radio-bundle)" = "bad 200 200"
stage "$T/b3"; s=$(install_bundle)
check "reinstalling the same version is not a no-op when marked bad" has "$s" "ok: radio bundle 200 installed"
check "...and clears the mark" test ! -e /data/s1kiosk/radio/bundle/bad-200
reset_radio; mkdir -p /data/s1kiosk/radio
printf '#!/bin/sh\nsetprop test.dev ran\n' > /data/s1kiosk/radio/s1kiosk_radio.sh
helper; check "old-style override ignored without dev-override" test "$(prop test.dev)" != ran
touch /data/s1kiosk/radio/dev-override
helper; check "dev override with the root marker" test "$(prop test.dev)" = ran
rm -f "$T/props/test.dev"

# -------------------------------------------------------------------------------------- firmware
echo "--- radio firmware"
reset_radio; echo off > "$T/props/persist.s1kiosk.radio_mode"
LOOPS=1 helper
check "fw idle at start" has "$(prop sys.s1kiosk.radio.fw)" "idle stock"
check "stock target published (unpinned, APP from the name)" has "$(prop sys.s1kiosk.radio.fw_targets)" "stock:${STOCK_SHA:0:12}:0017"
req() { # content [age-seconds]
  printf '%s\n' "$1" > "$APP/files/radio/fw-request"; chown 10050:10050 "$APP/files/radio/fw-request"
  [ -n "${2:-}" ] && touch -d "@$(( $(date +%s) - $2 ))" "$APP/files/radio/fw-request"
}
req "stock ${STOCK_SHA:0:12}"; : > "$T/flashed"; echo 0016 > "$T/radio-app"
LOOPS=1 helper
check "stock flash requested: flashed with Aqara's cpcd" test "$(cat "$T/flashed")" = "$STOCK"
check "...verified over CPC" has "$(prop sys.s1kiosk.radio.fw)" "ok stock: APP 0017 (VERSION:FAKE-RCP)"
check "...request taken" test ! -e "$APP/files/radio/fw-request"
check "...no in-progress marker left" test ! -e /data/s1kiosk/radio/fw/in-progress
check "...stock reference recorded" grep -q "^stock $STOCK_SHA 0017 " /data/s1kiosk/radio/fw/stock.ref
req "stock ${STOCK_SHA:0:12}" 600; : > "$T/flashed"; LOOPS=1 helper
check "stale request (10 min) ignored" test ! -s "$T/flashed"
check "...and said so" has "$(prop sys.s1kiosk.radio.fw)" "too old"
req "stock 000000000000"; LOOPS=1 helper
check "request naming another hash refused" has "$(prop sys.s1kiosk.radio.fw)" "not the one requested"
req "stock"; LOOPS=1 helper
check "request without a hash refused" has "$(prop sys.s1kiosk.radio.fw)" "names no image hash"
printf 'stock %s\n' "${STOCK_SHA:0:12}" > "$T/req-elsewhere"; chown 10050 "$T/req-elsewhere"
ln -s "$T/req-elsewhere" "$APP/files/radio/fw-request"; : > "$T/flashed"; LOOPS=1 helper
check "symlinked request refused" test ! -s "$T/flashed"; rm -f "$APP/files/radio/fw-request"
req "stock ${STOCK_SHA:0:12}"; chown 10077 "$APP/files/radio/fw-request"; : > "$T/flashed"; LOOPS=1 helper
check "request owned by another app refused" test ! -s "$T/flashed"; rm -f "$APP/files/radio/fw-request"

# A bundle image that flashes but reports the wrong version: rolled back to stock.
gbl "$T/new.gbl"; NEW_SHA=$(sha256sum "$T/new.gbl" | cut -d' ' -f1)
# (same bytes as the stock fake would collide: make it differ)
printf 'x' | dd of="$T/new.gbl" bs=1 seek=100 conv=notrunc 2>/dev/null; NEW_SHA=$(sha256sum "$T/new.gbl" | cut -d' ' -f1)
echo "$NEW_SHA 9999" >> "$T/fw-apps"
mkbundle "$T/b4" 300 "$T/key.pem" --file "otbr-agent=$TB/otbr-agent:0700" --file "fw/new.gbl=$T/new.gbl:0644" \
  --firmware "test-rcp=fw/new.gbl:0018:Test RCP"
stage "$T/b4"; install_bundle >/dev/null
LOOPS=1 helper
check "bundle firmware listed" has "$(prop sys.s1kiosk.radio.fw_targets)" "test-rcp:${NEW_SHA:0:12}:0018"
req "test-rcp ${NEW_SHA:0:12}"; : > "$T/flashed"; LOOPS=1 helper
check "wrong version after flash: rolled back to stock" has "$(prop sys.s1kiosk.radio.fw)" "rolled back to stock (APP 0017)"
check "...result kept after the helper restarts" has "$(LOOPS=1 helper; prop sys.s1kiosk.radio.fw)" "rolled back to stock"
check "...flashed the new image twice, then stock" test "$(wc -l < "$T/flashed")" = 3 -a "$(tail -1 "$T/flashed")" = "$STOCK"
sed -i "s/^$NEW_SHA 9999/$NEW_SHA 0018/" "$T/fw-apps"
req "test-rcp ${NEW_SHA:0:12}"; : > "$T/flashed"; touch "$T/cpcd-fail-once"; LOOPS=1 helper
check "one failed transfer is retried, then ok" has "$(prop sys.s1kiosk.radio.fw)" "ok test-rcp: APP 0018"
gbl "$T/btl.gbl" f50909f5
mkbundle "$T/b5" 301 "$T/key.pem" --file "fw/btl.gbl=$T/btl.gbl:0644" --firmware "btl=fw/btl.gbl:0018:x"
stage "$T/b5"; install_bundle >/dev/null; LOOPS=1 helper
check "an image with a bootloader tag is never offered" test "$(prop sys.s1kiosk.radio.fw_targets)" = "stock:${STOCK_SHA:0:12}:0017"
req "btl $(sha256sum "$T/btl.gbl" | cut -c 1-12)"; : > "$T/flashed"; LOOPS=1 helper
check "...nor flashed" test ! -s "$T/flashed" -a -n "$(has "$(prop sys.s1kiosk.radio.fw)" "replace the bootloader" && echo y)"
gbl "$T/trunc.gbl" "" truncate
mkbundle "$T/b6" 302 "$T/key.pem" --file "fw/t.gbl=$T/trunc.gbl:0644" --firmware "trunc=fw/t.gbl:0018:x"
stage "$T/b6"; install_bundle >/dev/null; req "trunc $(sha256sum "$T/trunc.gbl" | cut -c 1-12)"; : > "$T/flashed"; LOOPS=1 helper
check "a truncated image is refused" test ! -s "$T/flashed"
echo "stock $STOCK_SHA 1" > /data/s1kiosk/radio/fw/in-progress; LOOPS=1 helper
check "an interrupted flash is reported at start" has "$(prop sys.s1kiosk.radio.fw)" "flash was interrupted"
rm -f /data/s1kiosk/radio/fw/in-progress
python3 - "$T/huge.gbl" <<'PY'
import struct, sys
d = struct.pack('<II', 0x03A617EB, 8) + bytes(8) + struct.pack('<II', 0xF40A0AF4, 0xFFFFFFF8) + bytes(64)
open(sys.argv[1], 'wb').write(d)
PY
mkbundle "$T/b9" 303 "$T/key.pem" --file "fw/h.gbl=$T/huge.gbl:0644" --firmware "huge=fw/h.gbl:0018:x"
stage "$T/b9"; install_bundle >/dev/null
timeout 60 env S1R_ONCE=1 S1R_SLEEP=0 S1R_LOADER_ONCE=1 /system/bin/sh "$TB/s1kiosk_radio.sh" >/dev/null 2>&1
check "a GBL tag length of 0xfffffff8 is refused (no endless loop)" test $? != 124 -a "$(prop sys.s1kiosk.radio.fw_targets)" = "stock:${STOCK_SHA:0:12}:0017"

# Aqara's zigbee_cpcd starting mid-flash is stopped again
req "stock ${STOCK_SHA:0:12}"; : > "$T/flashed"; : > "$T/log"; touch "$T/cpcd-start-aqara"; LOOPS=1 helper; rm -f "$T/cpcd-start-aqara"
check "zigbee_cpcd started during a flash is stopped" grep -q "stopped zigbee_cpcd during the flash" "$T/log"
check "...and the flash still completes" has "$(prop sys.s1kiosk.radio.fw)" "ok stock"
# another cpcd running: no flash
cp /bin/sleep "$T/cpcd"; "$T/cpcd" 300 & CP=$!
req "stock ${STOCK_SHA:0:12}"; : > "$T/flashed"; LOOPS=1 helper; kill $CP
check "no flash while another cpcd runs" test ! -s "$T/flashed"
check "...and said so" has "$(prop sys.s1kiosk.radio.fw)" "a cpcd process is still using the radio"

# Progress while flashing: cpcd's own "progress:<n>", or the seconds so far when it printed none
req "stock ${STOCK_SHA:0:12}"; : > "$T/setprop.log"; touch "$T/cpcd-slow"; LOOPS=1 helper
fwlog=$(sed -n 's/^sys.s1kiosk.radio.fw=//p' "$T/setprop.log")
check "flash progress: cpcd's percentage" has "$fwlog" "flashing stock: 40% (do not unplug the panel)"
req "stock ${STOCK_SHA:0:12}"; : > "$T/setprop.log"; touch "$T/cpcd-quiet"; LOOPS=1 helper
fwlog=$(sed -n 's/^sys.s1kiosk.radio.fw=//p' "$T/setprop.log")
check "no progress from cpcd: elapsed seconds instead" grep -qE '^flashing stock: [0-9]+ s \(do not unplug the panel\)$' <<<"$fwlog"
check "...never an empty percentage" hasnt_pct "$fwlog"
check "...and the flash still completes" has "$(prop sys.s1kiosk.radio.fw)" "ok stock"
rm -f "$T/cpcd-slow" "$T/cpcd-quiet"

# an interrupted flash: stock is restored before any stack starts
echo zigbee_tcp > "$T/props/persist.s1kiosk.radio_mode"; echo aqara > "$T/props/persist.s1kiosk.radio_zigbee"
echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
echo "test-rcp $NEW_SHA 1" > /data/s1kiosk/radio/fw/in-progress; : > "$T/flashed"; echo garbage > "$T/radio-app"
LOOPS=1 helper
check "interrupted flash: stock restored at start" test "$(head -1 "$T/flashed")" = "$STOCK"
check "...said so" has "$(prop sys.s1kiosk.radio.fw)" "interrupted; restored stock (APP 0017)"
check "...then the mode starts" test "$(prop init.svc.zigbee_zigbeed)" = running -a ! -e /data/s1kiosk/radio/fw/in-progress
pkill -f "sleep 1000"
echo "test-rcp $NEW_SHA 1" > /data/s1kiosk/radio/fw/in-progress; touch "$T/cpcd-fail-always"; setprop ctl.stop zigbee_zigbeed; setprop ctl.stop zigbee_cpcd
: > "$T/flashed"; LOOPS=1 helper
check "restore fails: radio held off" has "$(prop sys.s1kiosk.radio.status)" "radio firmware missing after an interrupted flash"
check "...no stack started" test "$(prop init.svc.zigbee_cpcd)" != running -a "$(prop init.svc.zigbee_zigbeed)" != running
LOOPS=1 helper; : > "$T/flashed"; LOOPS=1 helper
check "...at most two automatic restores across restarts" test ! -s "$T/flashed" -a "$(cat /data/s1kiosk/radio/fw/restore-tries)" = 2
rm -f "$T/cpcd-fail-always"; req "stock ${STOCK_SHA:0:12}"; LOOPS=1 helper
check "...an explicit stock flash repairs it" test ! -e /data/s1kiosk/radio/fw/in-progress -a ! -e /data/s1kiosk/radio/fw/restore-tries
pkill -f "sleep 1000"
echo off > "$T/props/persist.s1kiosk.radio_mode"; rm -f /data/s1kiosk/radio/radio.conf

if [ -f /stock.gbl ]; then
  cp /stock.gbl "$STOCK"; LOOPS=1 helper
  check "the real stock GBL parses and is the pinned one" has "$(prop sys.s1kiosk.radio.fw_targets)" "stock:d7b79c6fab1e:0017"
  gbl "$STOCK"
fi

# --------------------------------------------------------------------------------- Zigbee stack
echo "--- Zigbee stack (Silicon Labs zigbeed)"
reset_radio
echo zigbee_tcp > "$T/props/persist.s1kiosk.radio_mode"; echo sisdk > "$T/props/persist.s1kiosk.radio_zigbee"
mkdir -p /data/s1kiosk/radio; echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
LOOPS=1 helper
check "sisdk without a bundle: needs the runtime" has "$(prop sys.s1kiosk.radio.status)" "needs a radio bundle with its runtime"
check "...zigbee state no-runtime" test "$(prop sys.s1kiosk.radio.zigbee)" = "sisdk no-runtime"
check "...Aqara's zigbeed not started" test "$(prop init.svc.zigbee_zigbeed)" != running
# The runtime with a fake loader that just runs the binary, and a fake zigbeed that holds the pty.
mkdir -p "$T/rt/lib"; cp "$R/tools/radio/sisdk/zigbeed.sh" "$T/rt/"
w "$T/rt/lib/ld-linux-armhf.so.3" <<'EOF'
#!/bin/sh
while [ "$1" != --argv0 ]; do shift; done; shift 2; exec "$@"
EOF
w "$T/fakezb" <<'EOF'
#!/bin/sh
echo "$*" > /t/zigbeed.args; pwd > /t/zigbeed.cwd
exec 3<> "$(readlink /tmp/ttyZigbeeNCP)"; exec sleep 1000
EOF
mkbundle "$T/b7" 400 "$T/key.pem" --file "otbr-agent=$TB/otbr-agent:0700" --dir "sisdk=$T/rt"
stage "$T/b7"; install_bundle >/dev/null
# RADIO_ALLOW=any: no iptables here (the firewall is unchanged since v3 and was verified on the panel)
echo "RADIO_ALLOW=any; SISDK_SHA256=$(sha256sum "$T/fakezb" | cut -d' ' -f1); SISDK_SIZE=$(stat -c %s "$T/fakezb")" > /data/s1kiosk/radio/radio.conf
touch /t/pty-host /t/pty-zb; ln -sf /t/pty-host /dev/ttyZigbeeNCP; ln -sf /t/pty-zb /tmp/ttyZigbeeNCP
LOOPS=1 helper
check "runtime present, binary missing: waiting for the download" has "$(prop sys.s1kiosk.radio.status)" "waiting for zigbeed 8.2.2"
check "...zigbee state tells the app what to fetch" has "$(prop sys.s1kiosk.radio.zigbee)" "sisdk missing 8.2.2"
check "...cpcd + socat wanted, Aqara's zigbeed not" test "$(prop init.svc.zigbee_cpcd)/$(prop init.svc.zigbee_socat)/$(prop init.svc.zigbee_zigbeed)" != "running/running/running" -a "$(prop init.svc.zigbee_cpcd)/$(prop init.svc.zigbee_socat)" = running/running
echo "not zigbeed" > "$APP/files/radio/zigbeed-8.2.2"; chown 10050 "$APP/files/radio/zigbeed-8.2.2"
LOOPS=1 helper
check "a wrong download is refused" has "$(prop sys.s1kiosk.radio.zigbee)" "sisdk bad"
check "...and not installed" test ! -e /data/s1kiosk/radio/sisdk/zigbeed-8.2.2
cp "$T/fakezb" "$APP/files/radio/zigbeed-8.2.2"; chown 10050 "$APP/files/radio/zigbeed-8.2.2"
printf '\xc1\x02\x0b\x0a\x52\x7e' > "$T/stale"; : > "$T/bridge.log"; rm -f "$T/drains"
LOOPS=3 helper
check "the pinned binary is installed root 0700" test "$(stat -c %a:%u /data/s1kiosk/radio/sisdk/zigbeed-8.2.2)" = 700:0
check "zigbeed runs on IID 1 and the pty, in its own state dir" test "$(cat "$T/zigbeed.cwd")" = /data/s1kiosk/radio/zigbeed-sisdk
check "...args" has "$(cat "$T/zigbeed.args")" "-c /dev/null -r spinel+cpc://cpcd_0?iid=1&iid-list=0 -p /tmp/ttyZigbeeNCP"
check "boot RSTACK drained before the bridge" test -s "$T/drains" -a ! -s "$T/stale"
check "bridge on :6638" has "$(cat "$T/bridge.log")" "TCP-LISTEN:6638"
check "...each client through the drain-on-connect relay" has "$(cat "$T/bridge.log")" "EXEC:/system/bin/sh /data/s1kiosk/radio/ezsp-relay.sh"
check "...root-only, draining the pty before it relays" test "$(stat -c %a:%u /data/s1kiosk/radio/ezsp-relay.sh)" = 600:0 -a \
  "$(grep -c -e '-u -T 0.2 FILE:/dev/ttyZigbeeNCP,raw,echo=0 - </dev/null' -e '^exec /vendor/bin/socat - FILE:/dev/ttyZigbeeNCP,raw,echo=0$' /data/s1kiosk/radio/ezsp-relay.sh)" = 2
check "status ok with EZSP 18" has "$(prop sys.s1kiosk.radio.status)" "ok zigbee_tcp: EZSP 18 on tcp:6638, Silicon Labs zigbeed 8.2.2"
check "zigbee state ready" test "$(prop sys.s1kiosk.radio.zigbee)" = "sisdk ready 8.2.2"
pkill -f fakezb; pkill -f "sleep 1000"
echo aqara > "$T/props/persist.s1kiosk.radio_zigbee"; printf 'xx' > "$T/stale"
LOOPS=1 helper
check "aqara stack: Aqara's zigbeed wanted again" test "$(prop init.svc.zigbee_zigbeed)" = running
check "...zigbee state aqara" test "$(prop sys.s1kiosk.radio.zigbee)" = aqara
pkill -f "sleep 1000"

# ---------------------------------------------------------------------------------- EZSP bridge
# The real thing: real socat as Aqara's, a pty pair like zigbee_socat's, and a fake zigbeed (python
# named zigbeed, so pidof finds it) that echoes what it gets and writes "stale" bytes on request, the
# way zigbeed's ASH layer leaves an ERROR frame in the pty after a client went away.
echo "--- EZSP bridge: drain on connect (real socat)"
reset_radio; pkill -f "sleep 1000"
printf '#!/bin/sh\nexec /usr/bin/socat "$@"\n' | w /vendor/bin/socat
echo zigbee_tcp > "$T/props/persist.s1kiosk.radio_mode"; echo aqara > "$T/props/persist.s1kiosk.radio_zigbee"
mkdir -p /data/s1kiosk/radio; echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
rm -f /dev/ttyZigbeeNCP /tmp/ttyZigbeeNCP
/usr/bin/socat PTY,link=/dev/ttyZigbeeNCP,raw,echo=0 PTY,link=/tmp/ttyZigbeeNCP,raw,echo=0 >/dev/null 2>&1 &
i=0; while [ ! -e /tmp/ttyZigbeeNCP ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
cat > "$T/fakezb.py" <<'PY'
import os, time, tty
fd = os.open('/tmp/ttyZigbeeNCP', os.O_RDWR | os.O_NOCTTY); tty.setraw(fd); os.set_blocking(fd, False)
def put(b):
    while b:
        try: b = b[os.write(fd, b):]
        except BlockingIOError: time.sleep(0.01)
got = open('/t/zb-got', 'ab', buffering=0)
while True:
    if os.path.exists('/t/zb-emit'):
        d = open('/t/zb-emit', 'rb').read(); os.unlink('/t/zb-emit'); put(d)
    try: b = os.read(fd, 4096)
    except (BlockingIOError, OSError): b = b''
    if b: got.write(b); put(b)
    else: time.sleep(0.02)
PY
cat > "$T/client.py" <<'PY'
import os, socket, sys, time
payload = bytes([0x1a, 0xc0, 0x38, 0xbc, 0x7e, 0x11, 0x13, 0x7d, 0x00, 0xff, 0x0d, 0x0a]) * 50 + os.urandom(3000)
open(sys.argv[1], 'wb').write(payload)
s = socket.create_connection(('127.0.0.1', int(sys.argv[2]) if len(sys.argv) > 2 else 6638), timeout=20)
s.sendall(payload)   # at once, before the relay runs: these must wait for it, not be drained
buf, end = b'', time.time() + 40
s.settimeout(1)
while time.time() < end and len(buf) < len(payload) + 64:
    try: d = s.recv(65536)
    except socket.timeout:
        if len(buf) >= len(payload): break
        continue
    if not d: break
    buf += d
s.close()
print('ok' if buf == payload else 'got %d bytes, want %d; starts %s' % (len(buf), len(payload), buf[:12].hex()))
PY
cp -L /usr/bin/python3 "$T/bin/zigbeed"; : > "$T/zb-got"
"$T/bin/zigbeed" "$T/fakezb.py" & ZBP=$!
sleep 2
LOOPS=3 helper
check "real bridge up through the relay" has "$(prop sys.s1kiosk.radio.status)" "ok zigbee_tcp: EZSP on tcp:6638"
session() { # stale bytes into the pty, then a client that sends at once
  : > "$T/zb-got"; printf '\x1a\xc0\x51\x02\x9c\x7e' > "$T/zb-emit"
  i=0; while [ -e "$T/zb-emit" ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done; sleep 0.5
  python3 "$T/client.py" "$T/sent" "${1:-6638}"
}
r=$(session); check "stale bytes in the pty never reach the next client; its own bytes come back intact" test "$r" = ok; [ "$r" = ok ] || echo "    $r"
check "...and zigbeed got exactly the client's bytes" cmp -s "$T/sent" "$T/zb-got"
sleep 3   # the single slot (max-children=1) frees once the session's processes are gone
r=$(session); check "the next client: the same" test "$r" = ok; [ "$r" = ok ] || echo "    $r"
check "...and zigbeed got exactly its bytes" cmp -s "$T/sent" "$T/zb-got"
# control: the same through a bridge without the relay (as before) does hand the stale bytes over
/usr/bin/socat TCP-LISTEN:6640,reuseaddr FILE:/dev/ttyZigbeeNCP,raw,echo=0 >/dev/null 2>&1 &
sleep 2; r=$(session 6640)
check "(control: without the relay the client gets the stale bytes first)" has "$r" "starts 1ac051029c7e"
kill $ZBP 2>/dev/null; pkill -f /usr/bin/socat; pkill -f "sleep 1000"
cp "$T/socat-mock" /vendor/bin/socat; rm -f /dev/ttyZigbeeNCP /tmp/ttyZigbeeNCP "$T/bin/zigbeed"
echo off > "$T/props/persist.s1kiosk.radio_mode"; rm -f /data/s1kiosk/radio/radio.conf

# ------------------------------------------------------------------------------- Thread dataset
echo "--- Thread dataset hand-off"
echo thread_router > "$T/props/persist.s1kiosk.radio_mode"; echo "RADIO_ALLOW=any" > /data/s1kiosk/radio/radio.conf
echo 0e080000000000010000 > "$APP/files/radio/thread-dataset"; chown 10050 "$APP/files/radio/thread-dataset"
LOOPS=1 helper
check "dataset taken into the root dir and the app's copy removed" test "$(cat /data/s1kiosk/radio/thread-dataset 2>/dev/null)" = 0e080000000000010000 -a ! -e "$APP/files/radio/thread-dataset"
check "...a stored Thread network is reported (yes/no only)" test "$(prop sys.s1kiosk.radio.thread_stored)" = yes
echo child > "$T/ot-state"; printf 'Home "Net"\001\n' > "$T/ot-name"
LOOPS=1 helper
check "Thread router status: role and network name (sanitised)" test "$(prop sys.s1kiosk.radio.status)" = 'ok thread_router: joined as child, network "Home Net"'
echo router > "$T/ot-state"; echo ABCDEFGHIJKLMNOPQRSTUVWXYZ > "$T/ot-name"
LOOPS=1 helper
check "...promoted to router; the name is cut at 16" test "$(prop sys.s1kiosk.radio.status)" = 'ok thread_router: joined as router, network "ABCDEFGHIJKLMNOP"'
echo detached > "$T/ot-state"; rm -f "$T/ot-name"
LOOPS=1 helper
check "...detached, no name" has "$(prop sys.s1kiosk.radio.status)" "ok thread_router: detached: looking for network"
check "...and never more than the role and the name" test -z "$(grep '^sys.s1kiosk.radio' /t/setprop.log | grep -i -E 'key|pskc|extpanid|0e08')"
rm -f "$T/ot-state" "$T/ot-name"; pkill -f "sleep 1000"
mkfifo "$APP/files/radio/thread-dataset"; chown 10050 "$APP/files/radio/thread-dataset"
timeout 60 env S1R_ONCE=1 S1R_SLEEP=0 S1R_LOADER_ONCE=1 /system/bin/sh "$TB/s1kiosk_radio.sh" >/dev/null 2>&1
check "a FIFO dataset never blocks the helper" test $? != 124
check "...and changes nothing" test "$(cat /data/s1kiosk/radio/thread-dataset)" = 0e080000000000010000
rm -f "$APP/files/radio/thread-dataset"; pkill -f "sleep 1000"
mkdir -p /data/s1kiosk/radio/thread-router; echo settings > /data/s1kiosk/radio/thread-router/0_1.data
mkdir -p /data/s1kiosk/radio/thread; echo br > /data/s1kiosk/radio/thread/0_1.data
echo leave > "$APP/files/radio/thread-leave"; chown 10050 "$APP/files/radio/thread-leave"
LOOPS=1 helper
check "Leave Thread network (router mode): dataset and router settings deleted" test ! -e /data/s1kiosk/radio/thread-dataset -a ! -e /data/s1kiosk/radio/thread-router/0_1.data
check "...the border router's own network is kept" test -s /data/s1kiosk/radio/thread/0_1.data
check "...reported" has "$(prop sys.s1kiosk.radio.thread_leave)" "Thread dataset and router settings deleted"
check "...request taken" test ! -e "$APP/files/radio/thread-leave"
check "...and the router waits for a new dataset" has "$(prop sys.s1kiosk.radio.status)" "waiting for a Thread dataset"
pkill -f "sleep 1000"
echo off > "$T/props/persist.s1kiosk.radio_mode"
echo 0e08 > /data/s1kiosk/radio/thread-dataset; mkdir -p /data/s1kiosk/radio/thread-router
echo leave > "$APP/files/radio/thread-leave"; chown 10050 "$APP/files/radio/thread-leave"
LOOPS=1 helper
check "Leave Thread network works in Off too" test ! -e /data/s1kiosk/radio/thread-dataset -a ! -e /data/s1kiosk/radio/thread-router
LOOPS=1 helper
check "...and nothing is stored afterwards" test "$(prop sys.s1kiosk.radio.thread_stored)" = no
echo leave > "$APP/files/radio/thread-leave"; chown 10050 "$APP/files/radio/thread-leave"; LOOPS=1 helper
check "...and says so when nothing was stored" has "$(prop sys.s1kiosk.radio.thread_leave)" "no Thread network was stored"
ln -s /data/s1kiosk/radio/thread "$APP/files/radio/thread-leave"; LOOPS=1 helper
check "...a symlinked request is refused" has "$(prop sys.s1kiosk.radio.thread_leave)" "error" ; rm -f "$APP/files/radio/thread-leave"
check "...and deletes nothing" test -s /data/s1kiosk/radio/thread/0_1.data
rm -f /data/s1kiosk/radio/radio.conf

# ------------------------------------------------------------------------------- app updates
echo "--- s1kiosk_update.sh"
cp "$R/tools/root/s1kiosk_update.sh" "$TB/"
mkdir -p "$APP/files/update"; printf 'PK-apk' > "$APP/files/update/pending.apk"; chown -R 10050 "$APP/files/update"
echo 30200 > "$T/pm-version"
/system/bin/sh "$TB/s1kiosk_update.sh" >/dev/null 2>&1
check "update service installs through the safe copy" has "$(prop sys.s1kiosk.update.status)" "ok: sh.edm.s1kiosk 30200 -> 30300"
rm -f "$APP/files/update/pending.apk"; mkfifo "$APP/files/update/pending.apk"; chown 10050 "$APP/files/update/pending.apk"
timeout 30 /system/bin/sh "$TB/s1kiosk_update.sh" >/dev/null 2>&1
check "update service: a FIFO is refused, no hang" test $? != 124
check "...with an error status" has "$(prop sys.s1kiosk.update.status)" "error"

# ---------------------------------------------------------------------------------- time zone
echo "--- time zone"
reset_radio; echo off > "$T/props/persist.s1kiosk.radio_mode"
rm -f "$T/props/persist.s1kiosk.timezone" "$T/tz-calls" "$T/tz-unknown"
printf 'Antarctica/Casey' > "$T/props/persist.sys.timezone"
LOOPS=1 helper
check "no zone chosen and Aqara's default in place: nothing done" test ! -e "$T/tz-calls" -a ! -e "$T/props/persist.s1kiosk.timezone"
check "...status says what to do" has "$(prop sys.s1kiosk.timezone.status)" "none: no time zone chosen"
printf 'Europe/Berlin' > "$T/props/persist.s1kiosk.timezone"
LOOPS=1 helper
check "Aqara's reset is undone at start" test "$(prop persist.sys.timezone)" = Europe/Berlin -a "$(cat "$T/tz-calls")" = Europe/Berlin
check "...auto time zone switched off" test "$(cat "$T/settings.global.auto_time_zone")" = 0
check "...status ok" test "$(prop sys.s1kiosk.timezone.status)" = "ok Europe/Berlin"
: > "$T/tz-calls"; LOOPS=1 helper
check "already right: no call" test ! -s "$T/tz-calls"
# Aqara's sync running later (its gateway prop changed, or a slow boot): caught by the loop
LOOPS=4 S1R_SLEEP=3 env S1R_ONCE=4 S1R_SLEEP=3 S1R_LOADER_ONCE=1 /system/bin/sh "$TB/s1kiosk_radio.sh" >/dev/null 2>&1 &
H=$!; sleep 2; printf 'Antarctica/Casey' > "$T/props/persist.sys.timezone"; wait $H
check "a later reset is re-applied by the watch loop" test "$(prop persist.sys.timezone)" = Europe/Berlin
# The loop notices a gone helper at its next check (<= 5 s); allow for a loaded/emulated host.
i=0; while [ -n "$(pgrep -f 's1kiosk_radio.sh' | head -1)" ] && [ $i -lt 20 ]; do sleep 0.5; i=$((i + 1)); done
check "...and the loop ends with the helper" test -z "$(pgrep -f 's1kiosk_radio.sh' | head -1)"
printf 'Mars/Olympus_Mons' > "$T/props/persist.s1kiosk.timezone"; echo Mars/Olympus_Mons > "$T/tz-unknown"; : > "$T/tz-calls"
LOOPS=1 helper
check "a zone Android doesn't know is reported" has "$(prop sys.s1kiosk.timezone.status)" "error Mars/Olympus_Mons: Android did not accept it (now GMT)"
check "...after one try" test "$(wc -l < "$T/tz-calls")" = 1
printf 'Europe/Berlin; reboot' > "$T/props/persist.s1kiosk.timezone"; : > "$T/tz-calls"
LOOPS=1 helper
check "a malformed value is never passed on" test ! -s "$T/tz-calls"
check "...and reported" has "$(prop sys.s1kiosk.timezone.status)" "is not a time zone id"
# migration: a panel set up before this, booting with the installer's zone still in place
rm -f "$T/props/persist.s1kiosk.timezone"; printf 'America/Los_Angeles' > "$T/props/persist.sys.timezone"
LOOPS=1 helper
check "the zone in place at boot is adopted when none was chosen" test "$(prop persist.s1kiosk.timezone)" = America/Los_Angeles
check "...and kept from then on" test "$(prop sys.s1kiosk.timezone.status)" = "ok America/Los_Angeles"
pkill -f "sleep 1000"

echo
echo "shell tests: $PASS passed, $FAILS failed"
[ $FAILS = 0 ]
