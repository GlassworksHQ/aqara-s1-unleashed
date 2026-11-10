#!/usr/bin/env bash
# build-sisdk-runtime.sh: the parts of the Silicon Labs Zigbee stack (zigbeed 8.2.2, EZSP 18) that
# this project may ship, for the radio bundle's sisdk/ directory (docs/radio.md "Zigbee stack",
# docs/research/zigbee2mqtt.md). The zigbeed binary itself is NOT here and must never be: it is Silicon
# Labs MSLA object code, downloaded by the panel from Silicon Labs' release (pinned SHA-256).
#
#   tools/radio/build-sisdk-runtime.sh            # -> tools/radio/out/sisdk (SISDK_OUT=<dir> to change)
#
# Output:
#   zigbeed.sh               tools/radio/sisdk/zigbeed.sh (runs zigbeed through the bundled loader)
#   lib/libcpc.so.3          built here from cpc-daemon v4.3.3 (Aqara's cpcd is 4.3.3.0 and libcpc refuses
#                            a daemon whose version differs; zigbeed 8.2.2 imports only the 7 base API-v3
#                            calls, all in 4.3.3 with the same soname)
#   lib/ld-linux-armhf.so.3, libc.so.6, libm.so.6, libstdc++.so.6, libgcc_s.so.1
#                            unmodified, from Ubuntu 22.04's arm-linux-gnueabihf cross runtime packages
#                            (glibc 2.35, GCC 12; zigbeed needs GLIBC_2.34). Proven on the panel.
#   NOTICE.txt, licenses/    what each file is, its licence and where its source is
#   BUILD-INFO.txt           pins and hashes
#
# Licences (checked 2026-11-10; not legal advice):
#   libcpc   Silicon Labs MSLA, "Source Code" sections. 4.2.5 allows distributing compiled "Licensed
#            Programs" to end users "only for use with Authorized Applications and not on a standalone
#            basis" (an Authorized Application includes a host processor working with a Silicon Labs
#            device: the panel's EFR32). Hence: only inside the signed radio bundle that only runs on
#            the panel, never as a separate download, with the MSLA's end-user terms in NOTICE.txt
#            (5.4). Same position as the libcpc already linked into otbr-agent (build-otbr.sh).
#   glibc    LGPL-2.1-or-later (+ GPL-2.0 parts with the linking exception): unmodified binaries, licence
#            texts included, Corresponding Source = the pinned Ubuntu source packages (NOTICE.txt).
#   libstdc++, libgcc_s  GPL-3.0-or-later with the GCC Runtime Library Exception 3.1: same.
#
# Reproducible: the builder is ubuntu:22.04 pinned by digest, always linux/amd64 (emulated on an ARM
# computer, like CI), and its packages come from a fixed Ubuntu archive snapshot (snapshot.ubuntu.com,
# $SNAPSHOT). The result must match the committed tools/radio/sisdk-runtime.sha256, or the script fails
# (that is what the release workflow's signing job relies on). After a deliberate change (new snapshot,
# new cpc-daemon pin): SISDK_UPDATE_HASHES=1 tools/radio/build-sisdk-runtime.sh, and commit the file.
# BUILD ONLY: never talks to a device.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${SISDK_OUT:-$HERE/out/sisdk}"
W="${SISDK_BUILD:-$HERE/.build/sisdk}"
CPCD_REPO=https://github.com/SiliconLabs/cpc-daemon
CPCD_TAG=v4.3.3
CPCD_COMMIT=684595db6be6534f856cfc7455551e71567298ee
CPCD_EXPECTED_VERSION=4.3.3.0
CPC_SOCKET_DIR="${CPC_SOCKET_DIR:-/dev/shm}"      # Aqara's cpcd
BASE_IMAGE=ubuntu:22.04@sha256:076e7bf82319619f28dd0aa9c02ab34b055ad37100395b4f49a2ffba04fb5e11
PLATFORM=linux/amd64
SNAPSHOT=20261101T000000Z                         # Ubuntu archive snapshot the packages come from
BUILDER_IMAGE=aqara-s1-sisdk-builder:jammy-$SNAPSHOT-amd64
HASHES="$HERE/sisdk-runtime.sha256"
# What zigbeed 8.2.2 imports from libcpc (objdump -T, tools/radio/build-zigbeed.sh checks it against the
# real binary when it has one).
ZIGBEED_CPC_IMPORTS="cpc_close_endpoint cpc_init cpc_open_endpoint cpc_read_endpoint cpc_restart cpc_set_endpoint_option cpc_write_endpoint"

command -v docker >/dev/null || { echo "docker not found" >&2; exit 1; }
mkdir -p "$W" "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd -P)"; W="$(cd "$W" && pwd -P)"

echo "=== builder image $BUILDER_IMAGE"
docker build --platform "$PLATFORM" -t "$BUILDER_IMAGE" - <<EOF
FROM $BASE_IMAGE
ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8
# ca-certificates first (the snapshot service is https only; apt checks the archive signatures anyway),
# then everything else from the pinned snapshot.
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
 && printf 'deb https://snapshot.ubuntu.com/ubuntu/%s jammy main universe\ndeb https://snapshot.ubuntu.com/ubuntu/%s jammy-updates main universe\ndeb https://snapshot.ubuntu.com/ubuntu/%s jammy-security main universe\n' \
      $SNAPSHOT $SNAPSHOT $SNAPSHOT > /etc/apt/sources.list \
 && rm -rf /var/lib/apt/lists/* \
 && apt-get -o Acquire::Check-Valid-Until=false update \
 && apt-get install -y --no-install-recommends \
      gcc-arm-linux-gnueabihf binutils-arm-linux-gnueabihf libc6-armhf-cross libc6-dev-armhf-cross libstdc++6-armhf-cross \
      libgcc-s1-armhf-cross git file \
 && rm -rf /var/lib/apt/lists/* \
 && git config --system --add safe.directory '*' \
 && git config --system advice.detachedHead false
EOF

cp "$HERE/sisdk/zigbeed.sh" "$W/zigbeed.sh"
cat > "$W/inner.sh" <<'INNER'
#!/usr/bin/env bash
set -euo pipefail
W=/build; OUT=/out
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
    -ffile-prefix-map=$W=. -DCPC_SOCKET_DIR="\"$CPC_SOCKET_DIR\"" -DDEFAULT_INSTANCE_NAME='"cpcd_0"' \
    -DCPCD_REBOOT_TIME_MS=2000 -DCPCD_CONFIG_FILE_PATH='"/etc/cpcd.conf"' \
    -DGIT_SHA1="\"$CPCD_COMMIT\"" -DGIT_REFSPEC="\"refs/tags/$CPCD_TAG\"" \
    -I"$CP" -I"$CP/include" -I"$CP/lib" -I"$LB/autogen" \
    -c "$CP/$f" -o "$LB/$(basename "$f" .c).o"
done
arm-linux-gnueabihf-gcc -shared -Wl,-soname,libcpc.so.3 -Wl,--build-id=sha1 -o "$LB/libcpc.so.3" "$LB"/*.o -lpthread
arm-linux-gnueabihf-strip --strip-unneeded "$LB/libcpc.so.3"
have=$(arm-linux-gnueabihf-objdump -T "$LB/libcpc.so.3" | awk '!/\*UND\*/ && $NF ~ /^cpc_/ {print $NF}' | sort -u)
for s in $ZIGBEED_CPC_IMPORTS; do echo "$have" | grep -qx "$s" || { echo "libcpc $ver lacks $s" >&2; exit 1; }; done

rm -rf "$OUT"/* && mkdir -p "$OUT/lib" "$OUT/licenses"   # $OUT is a bind mount: empty it, don't remove it
install -m 0755 "$W/zigbeed.sh" "$OUT/zigbeed.sh"
install -m 0644 "$LB/libcpc.so.3" "$OUT/lib/libcpc.so.3"
S=/usr/arm-linux-gnueabihf/lib
for l in libc.so.6 libm.so.6 libstdc++.so.6 libgcc_s.so.1; do install -m 0644 "$(readlink -f "$S/$l")" "$OUT/lib/$l"; done
install -m 0755 "$(readlink -f "$S/ld-linux-armhf.so.3")" "$OUT/lib/ld-linux-armhf.so.3"
pkgs="libc6-armhf-cross libstdc++6-armhf-cross libgcc-s1-armhf-cross"
for p in $pkgs; do cp "/usr/share/doc/$p/copyright" "$OUT/licenses/$p.copyright.txt"; done
cp "$CP/LICENSE" "$OUT/licenses/cpc-daemon.LICENSE.txt"
cp /usr/share/common-licenses/GPL-3 "$OUT/licenses/GPL-3.0.txt"
cp /usr/share/common-licenses/LGPL-2.1 "$OUT/licenses/LGPL-2.1.txt"
src() { dpkg-query -W -f '${source:Package} ${source:Version}' "$1"; }
{
cat <<EOF
Silicon Labs Zigbee stack runtime for the S1 Kiosk radio bundle (sisdk/)
=========================================================================

This directory lets Silicon Labs' zigbeed 8.2.2 (EmberZNet 8.2.2, EZSP 18) run on the Aqara Panel Hub
S1 Plus next to Aqara's cpcd 4.3.3. zigbeed itself is NOT included: it is Silicon Labs object code
under the Silicon Labs Master Software License Agreement (MSLA). The panel downloads it from Silicon
Labs' own release (Simplicity SDK v2025.6.3, debian-bookworm.zip) and checks its SHA-256.

zigbeed.sh
  Part of S1 Unleashed (MIT): https://github.com/aguilaair/aqara-s1-unleashed (tools/radio/sisdk/).

lib/libcpc.so.3
  Compiled, unmodified, from Silicon Labs cpc-daemon $CPCD_TAG (commit $CPCD_COMMIT):
  lib/sl_cpc.c, misc/sleep.c, misc/sl_slist.c. https://github.com/SiliconLabs/cpc-daemon
  Copyright Silicon Laboratories Inc. Licensed under the Silicon Labs MSLA (Source Code sections),
  https://www.silabs.com/about-us/legal/master-software-license-agreement (licenses/cpc-daemon.LICENSE.txt).
  End-user terms (MSLA 4.2.5, 5.4): this library is provided only for use with the Silicon Labs radio
  inside this panel, as part of this bundle. You may not distribute it on its own, sublicense, sell,
  modify, reverse engineer or decompile it, or use it with anything but a Silicon Labs device.

lib/ld-linux-armhf.so.3, lib/libc.so.6, lib/libm.so.6
  The GNU C Library 2.35, unmodified, from Ubuntu 22.04 package $(dpkg-query -W -f '${Package} ${Version}' libc6-armhf-cross).
  License: LGPL-2.1-or-later, with parts under other free licences (licenses/libc6-armhf-cross.copyright.txt,
  licenses/LGPL-2.1.txt). Corresponding Source: Ubuntu source package $(src libc6-armhf-cross),
  https://launchpad.net/ubuntu/+source/$(src libc6-armhf-cross | tr ' ' '/') (it builds glibc from the
  Ubuntu glibc source package it names). You may replace these libraries with your own build: the
  bundle loads them from this directory.

lib/libstdc++.so.6, lib/libgcc_s.so.1
  GCC 12 runtime libraries, unmodified, from Ubuntu 22.04 packages
  $(dpkg-query -W -f '${Package} ${Version}' libstdc++6-armhf-cross) and $(dpkg-query -W -f '${Package} ${Version}' libgcc-s1-armhf-cross).
  License: GPL-3.0-or-later with the GCC Runtime Library Exception 3.1 (licenses/*.copyright.txt,
  licenses/GPL-3.0.txt). Corresponding Source: Ubuntu source packages $(src libstdc++6-armhf-cross) and
  $(src libgcc-s1-armhf-cross), https://launchpad.net/ubuntu/+source/$(src libstdc++6-armhf-cross | tr ' ' '/')
  and https://launchpad.net/ubuntu/+source/$(src libgcc-s1-armhf-cross | tr ' ' '/').
EOF
} > "$OUT/NOTICE.txt"
{
  echo "sisdk runtime, built by tools/radio/build-sisdk-runtime.sh (Ubuntu snapshot $SNAPSHOT)"
  echo "libcpc: cpc-daemon $CPCD_TAG $CPCD_COMMIT, version $ver, API $api, protocol $proto, socket dir $CPC_SOCKET_DIR"
  echo "runtime: $(for p in $pkgs; do dpkg-query -W -f '${Package}=${Version} ' $p; done)"
  echo "compiler: $(arm-linux-gnueabihf-gcc --version | head -1)"
  echo; echo "sha256:"; (cd "$OUT" && find . -type f ! -name BUILD-INFO.txt | sort | xargs sha256sum)
} > "$OUT/BUILD-INFO.txt"
cat "$OUT/BUILD-INFO.txt"
INNER
chmod +x "$W/inner.sh"

docker run --rm --platform "$PLATFORM" -v "$W":/build -v "$OUT_DIR":/out -e SNAPSHOT="$SNAPSHOT" \
  -e CPCD_REPO="$CPCD_REPO" -e CPCD_TAG="$CPCD_TAG" -e CPCD_COMMIT="$CPCD_COMMIT" \
  -e CPCD_EXPECTED_VERSION="$CPCD_EXPECTED_VERSION" -e CPC_SOCKET_DIR="$CPC_SOCKET_DIR" \
  -e ZIGBEED_CPC_IMPORTS="$ZIGBEED_CPC_IMPORTS" \
  "$BUILDER_IMAGE" /build/inner.sh
got=$(cd "$OUT_DIR" && find . -type f | LC_ALL=C sort | xargs shasum -a 256)
if [ "${SISDK_UPDATE_HASHES:-}" = 1 ]; then
  printf '%s\n' "$got" > "$HASHES"; echo "=== wrote $HASHES"
elif ! diff <(printf '%s\n' "$got") "$HASHES"; then
  echo "=== output differs from $HASHES (above): not reproducible, or the inputs changed on purpose" >&2
  echo "    (then: SISDK_UPDATE_HASHES=1 $0, and review + commit the file)" >&2
  exit 1
fi
echo "=== done: $OUT_DIR (matches $HASHES)"
