#!/system/bin/sh
# Panel side of the signing-key switch (debug build -> release build) in tools/install.sh and the web
# installer (web/src/provision/switch.ts). Android won't update an app across signing keys, so the
# switch is uninstall + install, which deletes the app's data and the Wi-Fi networks it saved. With
# root this script carries the data across; the installers re-send Wi-Fi through the new app.
#
# Runs as root: pushed to /data/local/tmp and started with `su -c 'sh <this> <command>'` from adb's
# shell, only on panels where adb's shell has root (a development boot image, see docs/install.md).
# It takes no secrets as input and writes none to disk.
#
#   wifi-list      kiosk-saved networks, one per line: "<n> <psk|open|wep|other> <SSID as stored>"
#                  (the stored SSID is XML-escaped and quoted, e.g. &quot;Home&quot;). No secrets.
#   wifi-psk <n>   network <n>'s stored PreSharedKey (XML-escaped) on stdout, nothing else. The
#                  installer keeps it in memory and sends it to the new app over Improv.
#   backup         copy the app's data (credential- and device-encrypted storage, without lib, cache,
#                  code_cache) to a root-only directory. Run with the app stopped.
#   restore        put that copy into the app installed now, owned by its uid, labels restored.
#   discard        delete the copy.
#   has-backup     exit 0 if a copy is there.
PKG=sh.edm.s1kiosk
CE=${S1_CE:-/data/data/$PKG}
DE=${S1_DE:-/data/user_de/0/$PKG}
WORK=${S1_WORK:-/data/s1kiosk/switch}
STORES=${S1_WIFI_STORES:-/data/misc/wifi/WifiConfigStore.xml /data/misc_ce/0/wifi/WifiConfigStore.xml}

fail() { echo "s1kiosk-switch: $*" >&2; exit 1; }

# Prints kiosk-saved networks from WifiConfigStore.xml (Android 10 writes one element per line).
#   mode=list: "<n> <kind> <ssid>";  mode=psk: the PreSharedKey of network <want>.
wifi() {
  files=""
  for f in $STORES; do [ -f "$f" ] && files="$files $f"; done
  [ -n "$files" ] || fail "no WifiConfigStore.xml"
  # shellcheck disable=SC2086
  awk -v mode="$1" -v want="${2:-0}" -v pkg="$PKG" '
    function val(s) { sub(/^[^>]*>/, "", s); sub(/<\/string>.*$/, "", s); return s }
    /<WifiConfiguration>/ { inb = 1; ssid = ""; psk = ""; haspsk = 0; wep = 0; inwep = 0; creator = ""; km = ""; next }
    !inb { next }
    /<string name="SSID">/ { ssid = val($0) }
    /<string name="PreSharedKey">/ { psk = val($0); haspsk = 1 }
    /<string-array name="WEPKeys"/ { inwep = 1 }
    inwep && /<item value="[^"]/ { wep = 1 }
    inwep && /<\/string-array>/ { inwep = 0 }
    /<string name="CreatorName">/ { creator = val($0) }
    /<byte-array name="AllowedKeyMgmt"/ { km = $0; sub(/^[^>]*>/, "", km); sub(/<.*$/, "", km) }
    /<\/WifiConfiguration>/ {
      inb = 0
      if (creator != pkg) next
      n++
      kind = haspsk ? "psk" : wep ? "wep" : (km == "01" || km == "") ? "open" : "other"
      if (mode == "list") print n " " kind " " ssid
      else if (n == want + 0) { print psk; found = 1 }
    }
    END { if (mode == "psk" && !found) exit 1 }
  ' $files
}

# Top-level entries of an app data dir that are worth keeping (not lib, cache, code_cache).
entries() { ls -A "$1" 2>/dev/null | while IFS= read -r e; do case "$e" in lib|cache|code_cache) ;; *) echo "$e" ;; esac; done; }

uid_of() {
  u=$(stat -c %u "$CE" 2>/dev/null)
  case "$u" in ''|*[!0-9]*) fail "$PKG is not installed (no $CE)" ;; esac
  [ "$u" -ge 10000 ] || fail "$CE is owned by uid $u, not an app"
  # PackageManager must agree (pm's filter is a substring match: compare the exact package)
  pm list packages -U "$PKG" 2>/dev/null | grep -q "^package:$PKG uid:$u\$" || fail "PackageManager does not list $PKG with uid $u"
  echo "$u"
}

copy_out() { # <src dir> <dest dir>
  [ -d "$1" ] || return 0
  mkdir -p "$2" || fail "cannot create $2"
  entries "$1" | while IFS= read -r e; do cp -a "$1/$e" "$2/" || { echo "copy failed: $e" >&2; exit 1; }; done || fail "could not copy the app's data (full storage?)"
}

copy_in() { # <saved dir> <app dir> <uid>
  [ -d "$1" ] || return 0
  [ -d "$2" ] || fail "$2 is missing"
  entries "$2" | while IFS= read -r e; do rm -rf "${2:?}/$e"; done
  entries "$1" | while IFS= read -r e; do
    cp -a "$1/$e" "$2/" && chown -R "$3:$3" "$2/$e" || { echo "restore failed: $e" >&2; exit 1; }
  done || fail "could not restore the app's data"
  restorecon -R "$2" 2>/dev/null || true
}

case "${1:-}" in
  wifi-list) wifi list ;;
  wifi-psk) case "${2:-}" in ''|*[!0-9]*) fail "usage: wifi-psk <n>" ;; esac; wifi psk "$2" || fail "no network $2" ;;
  backup)
    [ -d "$CE" ] || fail "$PKG has no data to keep"
    rm -rf "$WORK"
    mkdir -p "$WORK" && chown root:root "$WORK" && chmod 700 "$WORK" || fail "cannot create $WORK"
    copy_out "$CE" "$WORK/ce"
    copy_out "$DE" "$WORK/de"
    touch "$WORK/complete"
    echo "kept $(entries "$WORK/ce" | wc -l | tr -d ' ') item(s), $(du -sk "$WORK" | cut -f1) KiB"
    ;;
  restore)
    [ -f "$WORK/complete" ] || fail "no complete copy of the app's data in $WORK"
    uid=$(uid_of) || exit 1
    copy_in "$WORK/ce" "$CE" "$uid"
    copy_in "$WORK/de" "$DE" "$uid"
    echo "restored for uid $uid"
    ;;
  discard) rm -rf "$WORK" ;;
  has-backup) [ -f "$WORK/complete" ] ;;
  *) fail "usage: $0 wifi-list | wifi-psk <n> | backup | restore | discard | has-backup" ;;
esac
