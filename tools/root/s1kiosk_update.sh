#!/system/bin/sh
# s1kiosk_update: root install service for S1 Kiosk app updates (frames/aqara-s1-plus/research/root.md), contract v1.
# Runs as a disabled one-shot init service (s1kiosk_update.rc: root, u:r:su:s0). The app downloads a
# release, checks its SHA-256, package name and signing certificate, writes it to $SRC and starts
# this service with `setprop ctl.start s1kiosk_update`.
#
# Other apps may be able to start init services too (SELinux is permissive on this firmware), and to
# set properties. So this script takes no input at all except the one fixed file, and checks:
#   1. the kiosk's uid, from PackageManager and from its data dir owner (they must agree);
#   2. $SRC and its directories are not symlinks, are owned by that uid (only the kiosk can write
#      its private dir), and $SRC is a single-link regular file of sane size;
#   3. it installs a root-only copy (the original can't change under pm) with `pm install -r` and no
#      -d: Android refuses another signing key for the same package, and downgrades. The copy is made
#      by s1kiosk_verify --copy (boot images with it): an openat(O_NOFOLLOW) walk, the file opened
#      O_NONBLOCK and checked on the fd, exactly its size copied, so a path swapped for a symlink, a
#      FIFO or /dev/zero between the checks above and the copy can't redirect or hang it;
#   4. afterwards the kiosk's versionCode must have gone up and no other package may have changed;
#      a package that appeared is uninstalled again.
# Status for the app: sys.s1kiosk.update.status = "<running|ok|error>: <detail>".
PKG=sh.edm.s1kiosk
ACTIVITY=$PKG/.MainActivity
DATA=/data/data/$PKG
SRC_DIR=$DATA/files/update
SRC=$SRC_DIR/pending.apk
STAGE_DIR=/data/s1kiosk/update          # root 0700
STAGE=$STAGE_DIR/pending.apk
MAX_BYTES=268435456                      # 256 MiB, as the app's own limit
VERIFY=${0%/*}/s1kiosk_verify

say() { log -t s1kiosk_update "$*" 2>/dev/null; }
status() { setprop sys.s1kiosk.update.status "$1"; say "$1"; }
# Only our own files: $SRC belongs to the app (it clears files/update/ itself), and a root rm
# through a path the app controls could be redirected.
cleanup() { rm -f "$STAGE" "$STAGE_DIR/before.txt" "$STAGE_DIR/after.txt"; }
fail() { status "error: $*"; cleanup; exit 1; }

# "package:<name> versionCode:<n> uid:<n>" for exactly $PKG (pm's filter is a substring match).
pkg_line() {
  pm list packages -U --show-versioncode "$PKG" 2>/dev/null | while IFS= read -r l; do
    case "$l" in "package:$PKG "*) echo "$l" ;; esac
  done
}
field() { echo "$1" | tr ' ' '\n' | sed -n "s/^$2://p"; }
# Every other package with its versionCode, sorted.
snapshot() { pm list packages --show-versioncode 2>/dev/null | grep -v "^package:$PKG " | sort > "$1"; }
num() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; return 0; }

status "running: checking the request"

# 1. the kiosk's uid
line=$(pkg_line)
uid=$(field "$line" uid)
before=$(field "$line" versionCode)
num "$uid" && [ "$uid" -ge 10000 ] || fail "$PKG is not installed"
num "$before" || fail "cannot read the installed versionCode"
[ "$(stat -c %u "$DATA" 2>/dev/null)" = "$uid" ] || fail "$DATA is not owned by the kiosk (uid $uid)"

# 2. the fixed path
for p in "$DATA/files" "$SRC_DIR" "$SRC"; do
  [ -L "$p" ] && fail "${p#"$DATA"/} is a symlink"
  [ -e "$p" ] || fail "${p#"$DATA"/} does not exist"
  owner=$(stat -c %u "$p" 2>/dev/null)
  [ "$owner" = "$uid" ] || fail "${p#"$DATA"/} is not owned by the kiosk (uid ${owner:-?})"
done
[ -f "$SRC" ] || fail "pending.apk is not a regular file"
[ "$(stat -c %h "$SRC")" = 1 ] || fail "pending.apk has extra hard links"
size=$(stat -c %s "$SRC")
num "$size" && [ "$size" -gt 0 ] && [ "$size" -le "$MAX_BYTES" ] || fail "pending.apk has a bad size (${size:-?})"

# 3. install a root-only copy
[ -d "$STAGE_DIR" ] || mkdir -p "$STAGE_DIR"
chown root:root /data/s1kiosk "$STAGE_DIR" && chmod 700 /data/s1kiosk "$STAGE_DIR" || fail "cannot prepare $STAGE_DIR"
rm -f "$STAGE"
if [ -x "$VERIFY" ]; then
  out=$("$VERIFY" --copy "$DATA" files/update/pending.apk "$uid" "$size" "$STAGE" 2>&1 >/dev/null) ||
    fail "pending.apk: ${out#s1kiosk_verify: }"
else # a boot image from before s1kiosk_verify
  cp "$SRC" "$STAGE" && chmod 600 "$STAGE" || fail "cannot copy pending.apk"
fi
[ "$(stat -c %s "$STAGE")" = "$size" ] || fail "pending.apk changed while copying"
snapshot "$STAGE_DIR/before.txt"

status "running: installing ($size bytes, from versionCode $before)"
# The APK goes in on stdin (-S): pm never opens a path, so nothing depends on who may read $STAGE.
out=$(pm install -r -S "$size" < "$STAGE" 2>&1)
case "$out" in
  *Success*) ;;
  *) fail "pm install: $(echo "$out" | grep -v '^$' | tail -n 1 | cut -c 1-160)" ;;
esac

# 4. only the kiosk changed, and it went up
after=$(field "$(pkg_line)" versionCode)
snapshot "$STAGE_DIR/after.txt"
changed=$(grep -vxF -f "$STAGE_DIR/before.txt" "$STAGE_DIR/after.txt")
if [ -n "$changed" ]; then
  names=""
  for n in $(echo "$changed" | sed -n 's/^package:\([^ ]*\).*/\1/p'); do
    names="$names $n"
    grep -q "^package:$n " "$STAGE_DIR/before.txt" || pm uninstall "$n" >/dev/null 2>&1
  done
  fail "refused: other packages changed:$names"
fi
num "$after" && [ "$after" -gt "$before" ] || fail "versionCode $before -> ${after:-?}: the kiosk was not updated"

cleanup
status "ok: $PKG $before -> $after"

# pm stopped the old process. The kiosk is HOME and restarts on MY_PACKAGE_REPLACED; make sure.
sleep 3
pidof "$PKG" >/dev/null 2>&1 || am start -n "$ACTIVITY" >/dev/null 2>&1
exit 0
