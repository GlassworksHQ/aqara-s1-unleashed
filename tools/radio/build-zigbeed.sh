#!/usr/bin/env bash
# build-zigbeed.sh - PROTOTYPE: package Silicon Labs' prebuilt zigbeed (EmberZNet 8.2.2, EZSP v18,
# Simplicity SDK 2025.6.3) so it runs on the Aqara S1 panel (Android 10, 32-bit ARM userspace)
# next to Aqara's own cpcd 4.3.3 and the stock multi-PAN RCP. Goal: Zigbee2MQTT's `ember` adapter
# (needs EZSP 13-19) without reflashing the radio. See docs/research/zigbee2mqtt.md.
#
# BUILD ONLY. This script never talks to the device (no adb). Nothing is vendored in the repo.
#
# Output: $ZIGBEED_OUT (default /tmp/zigbeed-sisdk). Deliberately OUTSIDE the repo tree:
# the bundle contains SiLabs' MSLA binary, which must never be committed or hosted. Downloads and
# intermediates go to $ZIGBEED_BUILD (default /tmp/zigbeed-sisdk-build). Both must be
# outside the checkout; the script refuses paths inside it.
#   bin/zigbeed           SiLabs' armhf glibc binary, unmodified (from zigbeed_8.2.2_armhf.deb)
#   lib/libcpc.so.3       libcpc built from cpc-daemon v4.3.3: Aqara's cpcd is 4.3.3.0 and libcpc
#                         refuses a daemon whose version string differs ("libcpc version (v4.7.1.0)
#                         does not match the daemon version"), so SiLabs' libcpc3 4.6.0 can't be used.
#                         zigbeed only imports the 7 base API-v3 calls (cpc_init/open/read/write/close/
#                         restart/set_endpoint_option), all present in 4.3.3 with the same soname.
#   lib/ld-linux-armhf.so.3, libc.so.6, libm.so.6, libstdc++.so.6, libgcc_s.so.1
#                         glibc 2.35 / libstdc++ (GCC 12) armhf runtime from Ubuntu 22.04's cross
#                         packages (zigbeed needs GLIBC_2.34; Android has no glibc).
#   zigbeed.sh            wrapper: runs bin/zigbeed through the bundled loader. zigbeed keeps its NVM
#                         in ./host_token.nvm (relative to the cwd), so the wrapper cd's to a state dir
#                         ($ZIGBEED_STATE, default /data/s1kiosk/radio/zigbeed-sisdk) and never touches
#                         Aqara's /data/zigbee_host/zigbeed_host_token.nvm.
#   BUILD-INFO.txt        pins, hashes, licenses.
#
# Third-party inputs (fetched at build time):
#  1. Silicon Labs Simplicity SDK v2025.6.3 release asset debian-bookworm.zip
#     https://github.com/SiliconLabs/simplicity_sdk/releases/download/v2025.6.3/debian-bookworm.zip
#     sha256 7ecbd91cdae94da1ec9eb4817a6bc1fe73741106a0c70a40879956bc09833c0e (= GitHub's asset digest)
#     -> deb/zigbeed_8.2.2_armhf.deb sha256 dc430b383ad6dfe8e447de6031f8b1b79ca6b6cbc161a95f3a8283fe78091df8
#     License: Silicon Labs MSLA (usr/share/doc/zigbeed/copyright). Object code may only be
#     distributed "for use with Authorized Applications and not on a standalone basis" (MSLA 4.2.5,
#     5.4). That is why this script downloads it from Silicon Labs instead of the repo shipping it.
#  2. cpc-daemon v4.3.3 (commit 684595db...), lib/sl_cpc.c + misc/sleep.c + misc/sl_slist.c only,
#     no encryption. License: MSLA source sections (same as build-otbr.sh uses).
#  3. Ubuntu 22.04 arm-linux-gnueabihf cross runtime (libc6-armhf-cross, libstdc++6-armhf-cross,
#     libgcc-s1-armhf-cross): LGPL-2.1+ (glibc), GPL-3.0+ with the GCC Runtime Library Exception.
#
# Usage:  tools/radio/build-zigbeed.sh            # fetch (cached), assemble, smoke test
# Knobs:  SMOKE=0 skips the arm32v7 smoke test.  CPC_SOCKET_DIR (default /dev/shm, Aqara's cpcd).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_BASE=/tmp
BUILD_DIR="${ZIGBEED_BUILD:-$TMP_BASE/zigbeed-sisdk-build}"
OUT_DIR="${ZIGBEED_OUT:-$TMP_BASE/zigbeed-sisdk}"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
mkdir -p "$BUILD_DIR" "$OUT_DIR"
BUILD_DIR="$(cd "$BUILD_DIR" && pwd -P)"; OUT_DIR="$(cd "$OUT_DIR" && pwd -P)"
for d in "$BUILD_DIR" "$OUT_DIR"; do
  case "$d/" in "$(cd "$REPO_ROOT" && pwd -P)/"*)
    echo "refusing $d: inside the repo (zigbeed is MSLA object code; keep it out of the tree)" >&2; exit 1;;
  esac
done

SISDK_TAG=v2025.6.3
DEB_ZIP_URL=https://github.com/SiliconLabs/simplicity_sdk/releases/download/$SISDK_TAG/debian-bookworm.zip
DEB_ZIP_SHA256=7ecbd91cdae94da1ec9eb4817a6bc1fe73741106a0c70a40879956bc09833c0e
ZIGBEED_DEB=debian-bookworm/deb/zigbeed_8.2.2_armhf.deb
ZIGBEED_DEB_SHA256=dc430b383ad6dfe8e447de6031f8b1b79ca6b6cbc161a95f3a8283fe78091df8

CPCD_REPO=https://github.com/SiliconLabs/cpc-daemon
CPCD_TAG=v4.3.3
CPCD_COMMIT=684595db6be6534f856cfc7455551e71567298ee
CPCD_EXPECTED_VERSION=4.3.3.0

BUILDER_IMAGE=aqara-s1-otbr-builder:jammy-1   # same image as build-otbr.sh (built there if missing)
BASE_IMAGE=arm64v8/ubuntu:22.04
TEST_IMAGE=arm32v7/ubuntu:22.04
CPC_SOCKET_DIR="${CPC_SOCKET_DIR:-/dev/shm}"
SMOKE="${SMOKE:-1}"

command -v docker >/dev/null || { echo "docker not found" >&2; exit 1; }
mkdir -p "$BUILD_DIR" "$OUT_DIR"
log() { printf '\n=== %s\n' "$*"; }
sha() { shasum -a 256 "$1" | awk '{print $1}'; }

log "builder image $BUILDER_IMAGE"
docker build --platform linux/arm64 -t "$BUILDER_IMAGE" - <<EOF
FROM $BASE_IMAGE
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8
RUN apt-get update && apt-get install -y --no-install-recommends \
      gcc-arm-linux-gnueabihf g++-arm-linux-gnueabihf binutils-arm-linux-gnueabihf \
      cmake ninja-build make git ca-certificates curl file python3 \
 && rm -rf /var/lib/apt/lists/* \
 && git config --system --add safe.directory '*' \
 && git config --system advice.detachedHead false
EOF

log "fetch $DEB_ZIP_URL"
ZIP="$BUILD_DIR/debian-bookworm.zip"
if [ ! -f "$ZIP" ] || [ "$(sha "$ZIP")" != "$DEB_ZIP_SHA256" ]; then
  curl -fL --retry 3 -o "$ZIP.part" "$DEB_ZIP_URL"
  mv "$ZIP.part" "$ZIP"
fi
[ "$(sha "$ZIP")" = "$DEB_ZIP_SHA256" ] || { echo "HASH MISMATCH $ZIP" >&2; exit 1; }

cat > "$BUILD_DIR/inner.sh" <<'INNER'
#!/usr/bin/env bash
set -euo pipefail
W=/build; OUT=/out
cd "$W"
log() { printf '\n=== %s\n' "$*"; }

log "unpack zigbeed deb"
rm -rf deb x && mkdir -p deb x
python3 - "$W/debian-bookworm.zip" "$ZIGBEED_DEB" "$W/deb/zigbeed.deb" <<'PY'
import sys, zipfile, shutil
with zipfile.ZipFile(sys.argv[1]) as z, z.open(sys.argv[2]) as s, open(sys.argv[3], 'wb') as d:
    shutil.copyfileobj(s, d)
PY
echo "$ZIGBEED_DEB_SHA256  deb/zigbeed.deb" | sha256sum -c -
python3 - "$W/deb/zigbeed.deb" "$W/x" <<'PY'
# .deb = ar archive; extract data.tar.* with the stdlib (no dpkg/ar/xz in the builder image)
import sys, io, tarfile
data = open(sys.argv[1], 'rb').read()
assert data[:8] == b'!<arch>\n'
off = 8
while off < len(data):
    name = data[off:off+16].decode().strip().rstrip('/'); size = int(data[off+48:off+58])
    body = data[off+60:off+60+size]; off += 60 + size + (size & 1)
    if name.startswith('data.tar'):
        with tarfile.open(fileobj=io.BytesIO(body)) as t:
            t.extractall(sys.argv[2], filter='data') if hasattr(tarfile, 'data_filter') else t.extractall(sys.argv[2])
PY
ZB=x/usr/local/bin/zigbeed
file "$ZB"
arm-linux-gnueabihf-readelf -d "$ZB" | grep NEEDED

log "libcpc.so.3 from cpc-daemon $CPCD_TAG"
CP=$W/cpc-daemon
if ! { [ -d "$CP/.git" ] && [ "$(git -C "$CP" rev-parse HEAD)" = "$CPCD_COMMIT" ]; }; then
  rm -rf "$CP"; git clone -q --depth 1 -b "$CPCD_TAG" "$CPCD_REPO" "$CP"
fi
[ "$(git -C "$CP" rev-parse HEAD)" = "$CPCD_COMMIT" ] || { echo "cpc-daemon pin mismatch" >&2; exit 1; }
ver=$(sed -n 's/^ *VERSION "\([0-9.]*\)".*/\1/p' "$CP/CMakeLists.txt" | head -1)
api=$(sed -n 's/^set(CPC_LIBRARY_API_VERSION "\([0-9]*\)").*/\1/p' "$CP/CMakeLists.txt")
proto=$(sed -n 's/^set(CPC_PROTOCOL_VERSION "\([0-9]*\)").*/\1/p' "$CP/CMakeLists.txt")
[ "$ver" = "$CPCD_EXPECTED_VERSION" ] && [ "$api" = 3 ] || { echo "unexpected libcpc $ver api $api" >&2; exit 1; }
IFS=. read -r vmaj vmin vpat vtw <<< "$ver"
LB=$W/build-libcpc; rm -rf "$LB"; mkdir -p "$LB/autogen"
sed -e "s/@CPC_LIBRARY_API_VERSION@/$api/" -e "s/@CPC_PROTOCOL_VERSION@/$proto/" \
    -e "s/@SOURCES_HASH@//" -e "s/@PROJECT_NAME@/cpcd/" -e "s/@PROJECT_VERSION@/$ver/" \
    -e "s/@PROJECT_VERSION_MAJOR@/$vmaj/" -e "s/@PROJECT_VERSION_MINOR@/$vmin/" \
    -e "s/@PROJECT_VERSION_PATCH@/$vpat/" -e "s/@PROJECT_VERSION_TWEAK@/$vtw/" \
    "$CP/misc/version.h.in" > "$LB/autogen/version.h"
for f in lib/sl_cpc.c misc/sleep.c misc/sl_slist.c; do
  arm-linux-gnueabihf-gcc -std=c99 -D_POSIX_C_SOURCE=200809L -D_DEFAULT_SOURCE -O2 -fPIC -Wall \
    -DCPC_SOCKET_DIR="\"$CPC_SOCKET_DIR\"" -DDEFAULT_INSTANCE_NAME='"cpcd_0"' \
    -DCPCD_REBOOT_TIME_MS=2000 -DCPCD_CONFIG_FILE_PATH='"/etc/cpcd.conf"' \
    -DGIT_SHA1="\"$CPCD_COMMIT\"" -DGIT_REFSPEC="\"refs/tags/$CPCD_TAG\"" \
    -I"$CP" -I"$CP/include" -I"$CP/lib" -I"$LB/autogen" \
    -c "$CP/$f" -o "$LB/$(basename "$f" .c).o"
done
arm-linux-gnueabihf-gcc -shared -Wl,-soname,libcpc.so.3 -o "$LB/libcpc.so.3" "$LB"/*.o -lpthread

log "check zigbeed's CPC imports against libcpc $ver"
need=$(arm-linux-gnueabihf-objdump -T "$ZB" | awk '/\*UND\*/ && $NF ~ /^cpc_/ {print $NF}' | sort -u)
have=$(arm-linux-gnueabihf-objdump -T "$LB/libcpc.so.3" | awk '!/\*UND\*/ && $NF ~ /^cpc_/ {print $NF}' | sort -u)
missing=$(comm -23 <(echo "$need") <(echo "$have"))
echo "zigbeed imports: $(echo $need)"
[ -z "$missing" ] || { echo "libcpc $ver lacks: $missing" >&2; exit 1; }

log "assemble $OUT"
rm -rf "$OUT"/* && mkdir -p "$OUT/bin" "$OUT/lib"   # $OUT is a bind mount: empty it, don't remove it
install -m 0755 "$ZB" "$OUT/bin/zigbeed"
install -m 0755 "$LB/libcpc.so.3" "$OUT/lib/"
S=/usr/arm-linux-gnueabihf/lib
for l in ld-linux-armhf.so.3 libc.so.6 libm.so.6 libstdc++.so.6 libgcc_s.so.1; do
  cp -L "$S/$l" "$OUT/lib/$l"
done
cp x/usr/share/doc/zigbeed/copyright "$OUT/LICENSE.zigbeed-MSLA.txt"
cat > "$OUT/zigbeed.sh" <<'SH'
#!/system/bin/sh
# Runs SiLabs zigbeed (glibc) on Android through the bundled loader. Args are zigbeed's own, e.g.
#   zigbeed.sh -r 'spinel+cpc://cpcd_0?iid=1&iid-list=0' -p /tmp/ttyZigbeeNCP -v 1 -d 3
# NVM: ./host_token.nvm in $ZIGBEED_STATE (never Aqara's /data/zigbee_host).
D=$(cd "${0%/*}" && pwd) || exit 1
ST=${ZIGBEED_STATE:-/data/s1kiosk/radio/zigbeed-sisdk}
[ -d "$ST" ] || { mkdir -p "$ST" && chmod 700 "$ST"; }
cd "$ST" || exit 1
# -c /dev/null: no /usr/local/etc/zigbeed.conf on Android; everything comes from the command line.
# Added once only: zigbeed re-execs itself through this script (below), with the args it already has.
[ "$1" = -c ] || set -- -c /dev/null "$@"
# --argv0: on every ASH reset (each EZSP host connect) zigbeed longjmps back into main(), prints
# "Restarting" and calls execvp(argv[0], argv). argv[0] would be bin/zigbeed, whose ELF interpreter
# /lib/ld-linux-armhf.so.3 doesn't exist on Android: execvp fails, zigbeed falls through and
# segfaults. With argv[0] = this script, the re-exec comes back here and through the bundled loader.
exec "$D/lib/ld-linux-armhf.so.3" --library-path "$D/lib" --argv0 "$D/zigbeed.sh" "$D/bin/zigbeed" "$@"
SH
chmod 0755 "$OUT/zigbeed.sh"
{
  echo "zigbeed-sisdk bundle (prototype), built $(date -u +%FT%TZ)"
  echo "zigbeed: EmberZNet 8.2.2 (EZSP 18), Simplicity SDK $SISDK_TAG debian-bookworm.zip sha256 $DEB_ZIP_SHA256"
  echo "         $ZIGBEED_DEB sha256 $ZIGBEED_DEB_SHA256 (binary unmodified)"
  echo "libcpc:  cpc-daemon $CPCD_TAG $CPCD_COMMIT, version $ver, API $api, protocol $proto, socket dir $CPC_SOCKET_DIR"
  echo "runtime: Ubuntu 22.04 arm-linux-gnueabihf cross glibc/libstdc++"
  echo; echo "sha256:"; (cd "$OUT" && sha256sum bin/* lib/* zigbeed.sh)
  echo; echo "Licenses: zigbeed = Silicon Labs MSLA (LICENSE.zigbeed-MSLA.txt; do not redistribute standalone);"
  echo "libcpc = MSLA source; glibc = LGPL-2.1+; libstdc++/libgcc = GPL-3.0+ w/ GCC Runtime Library Exception."
} > "$OUT/BUILD-INFO.txt"
cat "$OUT/BUILD-INFO.txt"
INNER
chmod +x "$BUILD_DIR/inner.sh"

log "assemble in $BUILDER_IMAGE"
docker run --rm --platform linux/arm64 -v "$BUILD_DIR":/build -v "$OUT_DIR":/out \
  -e ZIGBEED_DEB="$ZIGBEED_DEB" -e ZIGBEED_DEB_SHA256="$ZIGBEED_DEB_SHA256" \
  -e CPCD_REPO="$CPCD_REPO" -e CPCD_TAG="$CPCD_TAG" -e CPCD_COMMIT="$CPCD_COMMIT" \
  -e CPCD_EXPECTED_VERSION="$CPCD_EXPECTED_VERSION" -e CPC_SOCKET_DIR="$CPC_SOCKET_DIR" \
  -e SISDK_TAG="$SISDK_TAG" -e DEB_ZIP_SHA256="$DEB_ZIP_SHA256" \
  "$BUILDER_IMAGE" /build/inner.sh

if [ "$SMOKE" = 1 ]; then
  # In a plain arm32v7 container (its own glibc ignored: everything comes from the bundle). With no
  # cpcd running, zigbeed must load, parse its args and fail cleanly at CPC init.
  log "smoke test in $TEST_IMAGE (no cpcd: expect a clean CPC init failure)"
  docker run --rm --platform linux/arm/v7 -v "$OUT_DIR":/z:ro "$TEST_IMAGE" sh -c '
    set -e
    mkdir -p /dev/shm /tmp/st
    cd /tmp/st
    /z/lib/ld-linux-armhf.so.3 --library-path /z/lib /z/bin/zigbeed -h 2>&1 | head -20 || true
    echo "--- start without cpcd"
    rc=0; timeout 20 /z/lib/ld-linux-armhf.so.3 --library-path /z/lib /z/bin/zigbeed -c /dev/null \
      -r "spinel+cpc://cpcd_0?iid=1&iid-list=0" -p /tmp/ttyZigbeeNCP -v 1 -d 5 2>&1 | tail -15 || rc=$?
    echo "exit $rc"; ls -la /tmp/st'
fi
log "done: $OUT_DIR"
