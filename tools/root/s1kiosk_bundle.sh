#!/system/bin/sh
# s1kiosk_bundle: root install service for signed radio bundles (docs/radio.md "Radio bundles"),
# contract v1. Ships in the boot image (overlay.d/sbin, ${MAGISKTMP}) next to s1kiosk_verify and
# s1kiosk_bundle.pub (the project's Ed25519 public key).
#
# A radio bundle is what the root side runs that is not the boot image itself: a newer radio helper
# (s1kiosk_radio.sh), otbr-agent/ot-ctl, the Silicon Labs zigbeed runtime (no zigbeed binary: the
# panel downloads that from Silicon Labs), and optionally radio firmware images. The release workflow
# builds and signs it (tools/radio/make-bundle.py); the app ships it as an APK asset and stages it in
# its private dir. Layout there, and in /data/s1kiosk/radio/bundle/current once installed:
#   manifest       the signed text (format below)
#   manifest.sig   Ed25519 signature of manifest, 128 hex digits
#   <path>         every file the manifest lists
#
# Manifest (one item per line, single spaces, nothing else):
#   S1RADIO-BUNDLE 1
#   version <n>                          1..999999999, must not go down (anti-rollback)
#   created <text>                       informational
#   file <mode> <size> <sha256> <path>   mode 0600|0644|0700|0755; path: up to 4 components of
#                                        [A-Za-z0-9._+-], none starting with "." ("manifest*" reserved)
#   firmware <id> <path> <app> <label>   a radio firmware image among the files (s1kiosk_radio.sh)
#   end
#
# Modes:
#   (no argument)    the init service: install the bundle the app staged (below).
#   --check <dir>    used by the radio helper's loader: verify an installed bundle; prints
#                    "<version>[ helper]" and exits 0, or prints why not and exits 1. Nothing is changed.
#   --publish <state> <version>   used by the loader: writes the app-readable state file (below).
# (init passes no arguments, so only root callers reach the two modes with arguments.)
#
# App-readable state: $PUB_DIR/radio-bundle (root:root, dir 0755, file 0644) = "<ok|bad|none> <version>
# <floor>", written by this script after an install and by the loader at every helper start. The app
# decides whether to offer its bundle from this file, never from a sys.* property (any app can set those).
#
# Install (app side: stage files/radio/bundle-in/, setprop sys.s1kiosk.bundle.status requested,
# setprop ctl.start s1kiosk_bundle). Status in sys.s1kiosk.bundle.status = "<running|ok|error>: <detail>".
#
# Threat model: SELinux is permissive here, so any app may be able to start this service and set
# properties. It therefore takes no input but the kiosk's one staging dir, and:
#   1. finds the kiosk's uid (PackageManager, and the owner of its data dir; they must agree);
#   2. copies manifest + signature into a fresh root-only dir and checks the signature against the
#      key in the boot image before reading anything else (s1kiosk_verify, Monocypher Ed25519);
#   3. refuses a version below the highest one ever installed ($BDIR/version);
#   4. copies only the files the signed manifest names, with `s1kiosk_verify --copy`: it walks the path
#      with openat(O_NOFOLLOW) (every component kiosk-owned, no symlinks, so nothing can be swapped in
#      between a check and the copy), opens the file O_NONBLOCK, checks regular/owner/one link/size on
#      the open fd and copies exactly that many bytes; then the copy's SHA-256 is checked;
#   5. records the new version floor, swaps the new dir in with renames and restarts the radio helper.
# At worst another app can make it (re)install a bundle we signed, of the same or a newer version, or
# spoof the status property.
PKG=sh.edm.s1kiosk
DATA=${S1B_DATA:-/data/data/$PKG}
SRC=$DATA/files/radio/bundle-in
ROOT=${S1B_ROOT:-/data/s1kiosk}
DIR=$ROOT/radio
BDIR=$DIR/bundle
HERE=${0%/*}
PUBKEY=${S1B_PUBKEY:-$HERE/s1kiosk_bundle.pub}
VERIFY=${S1B_VERIFY:-$HERE/s1kiosk_verify}
PUB_DIR=${S1B_PUB:-/data/s1kiosk-pub}
MAX_FILES=200
MAX_TOTAL=$((96 * 1024 * 1024))
MAX_MANIFEST=65536

say() { log -t s1kiosk_bundle "$*" 2>/dev/null; }
status() { # cut to 91 bytes: setprop refuses longer values and the old status would stay
  [ -n "$CHECK" ] && return 0
  st_=$1; [ ${#st_} -gt 91 ] && st_="$(printf '%.88s' "$st_")..."
  setprop sys.s1kiosk.bundle.status "$st_"; say "$1"
}
num() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; [ ${#1} -le 9 ]; }
hex64() { case "$1" in *[!0-9a-f]*) return 1 ;; esac; [ ${#1} = 64 ]; }
sha256() { set -- $(sha256sum "$1" 2>/dev/null); echo "$1"; }
size() { stat -c %s "$1" 2>/dev/null; }
# A directory that is ours: exists, not a symlink, owned by root.
root_dir() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1" 2>/dev/null)" = 0 ]; }

# Bundle-relative path rules (also enforced by make-bundle.py).
good_path() {
  case "$1" in
    ""|/*|*/|*//*|.*|*/.*|*[!A-Za-z0-9._+/-]*|manifest|manifest.sig) return 1 ;;
  esac
  [ ${#1} -le 160 ] || return 1
  s_=$(echo "$1" | tr -cd / | wc -c); [ "$s_" -le 3 ]
}

# --- taking files from the kiosk (install only) -------------------------------------------------
UID_=""
take() { # <path under files/radio/> <N|max:N> <dest>: the only way this script reads the kiosk's files
  rm -f "$3"
  out_=$("$VERIFY" --copy "$DATA" "files/radio/$1" "$UID_" "$2" "$3" 2>&1 >/dev/null); r_=$?
  case $r_ in
    0) return 0 ;;
    3) WHY="${1#bundle-in/} does not exist" ;;
    4) out_=${out_#s1kiosk_verify: }; WHY="${1#bundle-in/} refused: ${out_%%: *}" ;;  # e.g. "the file is a symlink…"
    *) WHY="cannot copy ${1#bundle-in/}" ;;
  esac
  rm -f "$3"; return 1
}

# --- the manifest -------------------------------------------------------------------------------
# walk <manifest> <check|copy> <dir>: check = every listed file in <dir> matches; copy = copy every
# listed file from $SRC into <dir> and check the copy. Sets VERSION, NFILES, TOTAL, HELPER; WHY on error.
walk() {
  m_=$1; act_=$2; d_=$3
  VERSION=""; NFILES=0; TOTAL=0; HELPER=""; seen_=" "; n_=0; ended=""; FW_PATHS=""
  set -f
  while IFS= read -r l_ || [ -n "$l_" ]; do
    n_=$((n_ + 1))
    [ -n "$ended" ] && { WHY="manifest: text after end"; set +f; return 1; }
    # shellcheck disable=SC2086
    set -- $l_
    if [ $n_ = 1 ]; then
      [ "$l_" = "S1RADIO-BUNDLE 1" ] || { WHY="manifest: not a v1 radio bundle"; set +f; return 1; }
      continue
    fi
    case "$1" in
      version)
        [ $# = 2 ] && num "$2" && [ "$2" -gt 0 ] && [ -z "$VERSION" ] || { WHY="manifest: bad version line"; set +f; return 1; }
        VERSION=$2 ;;
      created) ;;
      file)
        [ $# = 5 ] || { WHY="manifest line $n_: bad file line"; set +f; return 1; }
        mo_=$2; sz_=$3; h_=$4; p_=$5
        case "$mo_" in 0600|0644|0700|0755) ;; *) WHY="manifest line $n_: bad mode"; set +f; return 1 ;; esac
        num "$sz_" && hex64 "$h_" && good_path "$p_" || { WHY="manifest line $n_: bad size, hash or path"; set +f; return 1; }
        case "$seen_" in *" $p_ "*) WHY="manifest: $p_ listed twice"; set +f; return 1 ;; esac
        seen_="$seen_$p_ "
        NFILES=$((NFILES + 1)); TOTAL=$((TOTAL + sz_))
        [ $NFILES -le $MAX_FILES ] && [ $TOTAL -le $MAX_TOTAL ] || { WHY="manifest: too many files or too big"; set +f; return 1; }
        if [ "$act_" = copy ]; then
          case "$p_" in */*) mkdir -p "$d_/${p_%/*}" || { WHY="cannot create ${p_%/*}"; set +f; return 1; } ;; esac
          take "bundle-in/$p_" "$sz_" "$d_/$p_" || { set +f; return 1; }
          chmod "$mo_" "$d_/$p_" || { WHY="cannot chmod $p_"; set +f; return 1; }
        else
          { [ -f "$d_/$p_" ] && [ ! -L "$d_/$p_" ]; } || { WHY="$p_ missing"; set +f; return 1; }
        fi
        [ "$(size "$d_/$p_")" = "$sz_" ] && [ "$(sha256 "$d_/$p_")" = "$h_" ] || { WHY="$p_ does not match the manifest"; set +f; return 1; }
        [ "$p_" = s1kiosk_radio.sh ] && HELPER=1 ;;
      firmware)
        [ $# -ge 5 ] || { WHY="manifest line $n_: bad firmware line"; set +f; return 1; }
        case "$2" in stock|*[!a-z0-9-]*) WHY="manifest line $n_: bad firmware id"; set +f; return 1 ;; esac
        [ ${#2} -le 16 ] || { WHY="manifest line $n_: firmware id too long"; set +f; return 1; }
        case "$4" in *[!A-Za-z0-9._-]*|"") WHY="manifest line $n_: bad firmware version"; set +f; return 1 ;; esac
        FW_PATHS="$FW_PATHS $3" ;;
      end) [ $# = 1 ] && ended=1 || { WHY="manifest: bad end line"; set +f; return 1; } ;;
      *) WHY="manifest line $n_: unknown item '$1'"; set +f; return 1 ;;
    esac
  done < "$m_"
  set +f
  [ -n "$ended" ] || { WHY="manifest: no end line (truncated?)"; return 1; }
  [ -n "$VERSION" ] || { WHY="manifest: no version"; return 1; }
  for p_ in $FW_PATHS; do
    case "$seen_" in *" $p_ "*) ;; *) WHY="manifest: firmware $p_ is not one of the files"; return 1 ;; esac
  done
  return 0
}

# The version floor: the highest version ever installed (bundle/version), and at least the installed one.
floor() {
  f_=$(cat "$BDIR/version" 2>/dev/null); num "$f_" || f_=0
  c_=$(sed -n 's/^version \([0-9][0-9]*\)$/\1/p' "$BDIR/current/manifest" 2>/dev/null | head -1)
  num "$c_" && [ "$c_" -gt "$f_" ] && f_=$c_
  echo "$f_"
}

publish() { # state version: the app-readable state file
  if [ -L "$PUB_DIR" ] || { [ -e "$PUB_DIR" ] && ! root_dir "$PUB_DIR"; }; then rm -rf "$PUB_DIR"; fi
  mkdir -p "$PUB_DIR" && chown root:root "$PUB_DIR" && chmod 755 "$PUB_DIR" || return 1
  rm -f "$PUB_DIR/.radio-bundle.tmp"
  echo "$1 $2 $(floor)" > "$PUB_DIR/.radio-bundle.tmp" && chmod 644 "$PUB_DIR/.radio-bundle.tmp" &&
    mv -f "$PUB_DIR/.radio-bundle.tmp" "$PUB_DIR/radio-bundle"
}

sig_ok() { # dir: manifest + manifest.sig are root-owned regular files and the signature checks out
  for f_ in manifest manifest.sig; do
    { [ -f "$1/$f_" ] && [ ! -L "$1/$f_" ]; } || { WHY="$f_ missing"; return 1; }
  done
  [ "$(size "$1/manifest")" -le $MAX_MANIFEST ] || { WHY="manifest too big"; return 1; }
  { [ -x "$VERIFY" ] && [ -s "$PUBKEY" ]; } || { WHY="no s1kiosk_verify or public key in the boot image"; return 1; }
  "$VERIFY" "$PUBKEY" "$1/manifest" "$1/manifest.sig" 2>/dev/null || { WHY="bad signature (not signed with the project's key)"; return 1; }
}

# --- --check <dir> ------------------------------------------------------------------------------
if [ "$1" = --check ]; then
  CHECK=1; d=$2
  { root_dir "$ROOT" && root_dir "$DIR" && root_dir "$BDIR" && root_dir "$d"; } || { echo "not a root-owned directory"; exit 1; }
  sig_ok "$d" || { echo "$WHY"; exit 1; }
  walk "$d/manifest" check "$d" || { echo "$WHY"; exit 1; }
  [ "$VERSION" -ge "$(floor)" ] || { echo "version $VERSION is below the installed $(floor)"; exit 1; }
  echo "$VERSION${HELPER:+ helper}"
  exit 0
fi

if [ "$1" = --publish ]; then
  CHECK=1
  case "$2" in ok|bad|none) ;; *) exit 2 ;; esac
  num "${3:-0}" || exit 2
  publish "$2" "${3:-0}"; exit $?
fi

# --- install ------------------------------------------------------------------------------------
STAGE=$BDIR/stage
fail() { status "error: $*"; rm -rf "$STAGE"; exit 1; }

status "running: checking the request"
line=$(pm list packages -U "$PKG" 2>/dev/null | while IFS= read -r l; do
  case "$l" in "package:$PKG "*) echo "$l" ;; esac
done)
UID_=$(echo "$line" | tr ' ' '\n' | sed -n 's/^uid://p')
num "$UID_" && [ "$UID_" -ge 10000 ] || fail "$PKG is not installed"
[ "$(stat -c %u "$DATA" 2>/dev/null)" = "$UID_" ] || fail "$DATA is not owned by the kiosk (uid $UID_)"

for d in "$ROOT" "$DIR" "$BDIR"; do
  [ -L "$d" ] && fail "$d is a symlink"
  [ -d "$d" ] || mkdir -p "$d" || fail "cannot create $d"
  chown root:root "$d" && chmod 700 "$d" || fail "cannot prepare $d"
done
rm -rf "$STAGE"; mkdir "$STAGE" && chmod 700 "$STAGE" || fail "cannot create the staging dir"

take bundle-in/manifest "max:$MAX_MANIFEST" "$STAGE/manifest" || fail "$WHY"
take bundle-in/manifest.sig max:256 "$STAGE/manifest.sig" || fail "$WHY"
sig_ok "$STAGE" || fail "$WHY"

# Signed by us from here on. Version first, before copying megabytes.
v=$(sed -n 's/^version \([0-9][0-9]*\)$/\1/p' "$STAGE/manifest" | head -1)
num "$v" || fail "manifest: no version"
fl=$(floor)
[ "$v" -ge "$fl" ] || fail "refused: bundle $v is older than the installed $fl (no downgrades)"
# The same bundle again is a no-op, unless its helper was marked bad (then it is reinstalled, which
# clears the mark, so a reinstall from the app gives it another chance).
if [ "$v" = "$fl" ] && [ ! -e "$BDIR/bad-$v" ] && root_dir "$BDIR/current" && cmp -s "$STAGE/manifest" "$BDIR/current/manifest" &&
   /system/bin/sh "$0" --check "$BDIR/current" >/dev/null 2>&1; then
  rm -rf "$STAGE"; publish ok "$v"; status "ok: radio bundle $v is already installed"; exit 0
fi

status "running: copying radio bundle $v"
walk "$STAGE/manifest" copy "$STAGE" || fail "$WHY"
[ "$VERSION" = "$v" ] || fail "manifest: version lines disagree"

# Don't swap the helper's files away under a radio firmware flash.
case "$(getprop sys.s1kiosk.radio.fw)" in
  flashing*) [ -e "$DIR/fw/in-progress" ] && fail "a radio firmware flash is running; try again when it is done" ;;
esac

# The floor first: if anything below fails, an older bundle can't come back (the loader then runs the
# boot image's helper until a bundle of at least this version is installed).
nf=$fl; [ "$v" -gt "$nf" ] && nf=$v
rm -f "$BDIR/version.tmp"
{ echo "$nf" > "$BDIR/version.tmp" && chmod 600 "$BDIR/version.tmp" && mv -f "$BDIR/version.tmp" "$BDIR/version"; } ||
  fail "cannot record version $nf as the new floor"
rm -rf "$BDIR/old"
if [ -e "$BDIR/current" ]; then mv "$BDIR/current" "$BDIR/old" || fail "cannot move the old bundle away"; fi
if ! mv "$STAGE" "$BDIR/current"; then
  [ -e "$BDIR/old" ] && mv "$BDIR/old" "$BDIR/current"
  fail "cannot install the new bundle"
fi
rm -rf "$BDIR/old" "$BDIR"/bad-*
publish ok "$v"
status "ok: radio bundle $v installed ($NFILES files, $TOTAL bytes)"

# The helper's loader picks the new bundle up on its next start.
[ "$(getprop init.svc.s1kiosk_radio)" = running ] && setprop ctl.restart s1kiosk_radio
exit 0
