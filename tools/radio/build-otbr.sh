#!/usr/bin/env bash
# build-otbr.sh - reproducible cross-build of OpenThread Border Router (otbr-agent + ot-ctl)
# for the Aqara S1 panel (Android 10, kernel 4.9, armv8l CPU, 32-bit ARM userspace), with
# Silicon Labs CPC (multi-PAN RCP over cpcd) radio support: spinel+cpc://cpcd_0?iid=2&iid-list=0
#
# BUILD ONLY. This script never talks to the device (no adb, no serial).
#
# Output: tools/radio/out/{otbr-agent,ot-ctl}  -- fully static glibc ARM EABI5 (armhf) ELF,
#         no bionic / libc++ dependencies. Plus BUILD-INFO.txt with pins, options and runtime paths.
#         PROFILE=br writes the border-router build to tools/radio/out/br/ instead (see PROFILE below).
# Cache : tools/radio/.build/  (sources, build trees). Both dirs are gitignored.
# Needs : Docker on the host (tested: Docker 29, Apple Silicon). Builder image is derived from
#         arm64v8/ubuntu:22.04 and uses Ubuntu's gcc-arm-linux-gnueabihf cross toolchain;
#         the smoke test runs the binaries in arm32v7/ubuntu:22.04.
#
# ---------------------------------------------------------------------------------------------
# Third-party sources fetched at build time (nothing is vendored in this repo):
#
#  1. ot-br-posix        https://github.com/openthread/ot-br-posix
#                        tag v2026.11.0 = commit 76fb21d666a41050b43f8eaabc30e71d9d75d9fd
#                        License: BSD-3-Clause (The OpenThread Authors)
#  2. openthread         https://github.com/openthread/openthread  (ot-br-posix submodule
#                        third_party/openthread/repo) commit ecd308b4a00f76d643b352f8f5c4ffc287bb33a2
#                        License: BSD-3-Clause.
#  2b. mbedtls           https://github.com/Mbed-TLS/mbedtls (openthread submodule third_party/mbedtls/repo)
#                        v3.6.5 = commit e185d7fd85499c8ce5ca2a54f5cf8fe7dbe3f8df
#                        License: Apache-2.0 OR GPL-2.0-or-later (used under Apache-2.0); compiled in
#                        for OpenThread's own crypto (unrelated to CPC encryption, which stays off).
#                        The other ot-br-posix submodules (cJSON, cpp-httplib) are only fetched for
#                        PROFILE=br (REST server), pinned by ot-br-posix's gitlinks:
#  2c. cJSON             https://github.com/DaveGamble/cJSON  commit cf97c6f066d81fdbba4ef722cfd327bbbba2365c
#                        License: MIT.
#  2d. cpp-httplib       https://github.com/yhirose/cpp-httplib  commit 03cf43ebaa55f27a2778bed870ea3549f7e84e2c
#                        License: MIT. (header-only, used by the REST server)
#  3. cpc-daemon/libcpc  https://github.com/SiliconLabs/cpc-daemon
#                        tag v4.3.3 = commit 684595db6be6534f856cfc7455551e71567298ee ("CPCd Release: 4.3.3")
#                        License: Silicon Labs MSLA (Master Software License Agreement), source-code
#                        sections (see its LICENSE and the lib/sl_cpc.c header). Only the client
#                        library (lib/sl_cpc.c, misc/sleep.c, misc/sl_slist.c) is compiled, statically,
#                        without mbedtls/encryption. The device's cpcd reports git 823a260adc4d...,
#                        which is not in the public repo (it comes from a Gecko SDK tree); libcpc's
#                        handshake only compares the version string ("4.3.3.0") and the protocol
#                        version (4), both identical in the public v4.3.3 tag.
#                        Build-time patch: libcpc honours $CPC_SOCKET_DIR at runtime (falls back
#                        to the compile-time CPC_SOCKET_DIR, default /dev/shm).
#  4. SiLabs CPC vendor interface for OpenThread POSIX
#                        https://github.com/SiliconLabs/simplicity_sdk
#                        tag v2025.6.3 = commit b41bec3ff2485199c1a5a9995b3e649e118c1b8d
#                        protocol/openthread/platform-abstraction/posix/{cpc_interface.cpp,
#                        cpc_interface.hpp} (sha256-pinned). SiLabs' openthread-core-silabs-posix-config.h
#                        is NOT used: it force-enables border routing/TREL/TCP/etc.; the few CPC/multi-PAN
#                        settings it carries ("spinel+cpc" URL scheme, help text, no channel monitor/manager,
#                        RCP restoration count) are reproduced in a generated header instead.
#                        License: Zlib (SPDX-License-Identifier: Zlib in each file header,
#                        Copyright 2024 Silicon Laboratories Inc.)
#                        (The older Gecko SDK v4.3.3 copy of cpc_interface.cpp carries a BSD-3 OpenThread
#                        header but targets the removed OT_POSIX_CONFIG_RCP_BUS=VENDOR API, so the
#                        Simplicity SDK copy is used: it targets OT_POSIX_RCP_VENDOR_BUS /
#                        OPENTHREAD_POSIX_CONFIG_SPINEL_VENDOR_INTERFACE_ENABLE as current OpenThread does.)
#
# Reference recipe: Home Assistant "Silicon Labs Multiprotocol" add-on (home-assistant/addons,
# silabs-multiprotocol/Dockerfile before commit 6ed5149): ot-br-posix + OT_MULTIPAN_RCP=ON +
# vendor RCP bus with SiLabs cpc_interface.cpp + openthread-core-silabs-posix-config.h + libcpc.
#
# Runtime notes (Android): radio URL needs iid= on this multi-PAN build; zigbeed holds iid=1, so use
#   otbr-agent -s -v -I wpan0 --auto-attach=0 'spinel+cpc://cpcd_0?iid=2&iid-list=0'   (no -B)
#   otbr-agent -s --radio-version 'spinel+cpc://cpcd_0?iid=2&iid-list=0'               (dry run)
#   ot-ctl state     (socket $OTBR_RUNTIME_DIR/openthread-wpan0.sock)
# The runtime dir must exist (mkdir -p /data/local/tmp/otbr); the settings subdir is created.
# ---------------------------------------------------------------------------------------------
#
# Usage:  tools/radio/build-otbr.sh            # fetch (if needed), build, smoke-test
#         CLEAN=1 tools/radio/build-otbr.sh    # wipe build trees first (keeps fetched sources)
# Knobs (environment, all optional):
#   PROFILE           agent = minimal Thread agent (no border routing/REST/mDNS), out/      [agent]
#                     br    = border router for Home Assistant's OTBR integration, out/br/:
#                             border routing (-B <infra if>), REST API (:8081), OpenThread's own
#                             mDNS (meshcop/_srpl, SRP advertising proxy), SRP server auto-enable,
#                             border agent; no NAT64/TREL/backbone router/DHCPv6-PD/DNS upstream/
#                             firewall/web/D-Bus (none of them is needed by HA, several need
#                             Linux userland Android doesn't have). Also runs without -B (Thread router).
#   OTBR_RUNTIME_DIR  compile-time dir for the ot-ctl/agent unix socket   [/data/local/tmp/otbr]
#   OTBR_SETTINGS_DIR compile-time default Thread settings dir             [$OTBR_RUNTIME_DIR/thread]
#                     (overridable at runtime with otbr-agent --data-path)
#   OTBR_TUN_DEVICE   TUN clone device opened by otbr-agent                [/dev/tun] (Android path;
#                     Android has no /dev/net/tun)
#   CPC_SOCKET_DIR    compile-time default cpcd socket dir for libcpc      [/dev/shm]
#                     (overridable at runtime with env CPC_SOCKET_DIR=...)
#   JOBS              parallel build jobs                                  [nproc in container]
#   SIM_TEST          1 = also build a simulated RCP (test only, not in out/) and run
#                     otbr-agent + ot-ctl end to end in an arm32v7 container with a TUN
#                     device mapped at $OTBR_TUN_DEVICE                     [1]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/.build"
OUT_DIR="$SCRIPT_DIR/out"

# ---- pins -----------------------------------------------------------------------------------
OTBR_REPO=https://github.com/openthread/ot-br-posix
OTBR_TAG=v2026.11.0
OTBR_COMMIT=76fb21d666a41050b43f8eaabc30e71d9d75d9fd
OT_COMMIT=ecd308b4a00f76d643b352f8f5c4ffc287bb33a2
MBEDTLS_COMMIT=e185d7fd85499c8ce5ca2a54f5cf8fe7dbe3f8df   # mbedtls v3.6.5 (openthread submodule)
CJSON_COMMIT=cf97c6f066d81fdbba4ef722cfd327bbbba2365c     # PROFILE=br only (ot-br-posix submodule)
HTTPLIB_COMMIT=03cf43ebaa55f27a2778bed870ea3549f7e84e2c   # PROFILE=br only (ot-br-posix submodule)

CPCD_REPO=https://github.com/SiliconLabs/cpc-daemon
CPCD_TAG=v4.3.3
CPCD_COMMIT=684595db6be6534f856cfc7455551e71567298ee
CPCD_EXPECTED_VERSION=4.3.3.0

SISDK_RAW=https://raw.githubusercontent.com/SiliconLabs/simplicity_sdk
SISDK_TAG=v2025.6.3
SISDK_COMMIT=b41bec3ff2485199c1a5a9995b3e649e118c1b8d
SISDK_POSIX_DIR=protocol/openthread/platform-abstraction/posix
# file:sha256
SISDK_FILES="cpc_interface.cpp:cafc7983f9cdaad43ecb2d43cd07665af128d9f6e1240ecbd5a04c7de7ef23ff
cpc_interface.hpp:617aebadf3d6223b69d9b40041226de2673f119faf9f1a6549d149f493fcbe82"

BASE_IMAGE=arm64v8/ubuntu:22.04
TEST_IMAGE=arm32v7/ubuntu:22.04
BUILDER_IMAGE=aqara-s1-otbr-builder:jammy-1

# ---- runtime path knobs ---------------------------------------------------------------------
OTBR_RUNTIME_DIR="${OTBR_RUNTIME_DIR:-/data/local/tmp/otbr}"
OTBR_SETTINGS_DIR="${OTBR_SETTINGS_DIR:-$OTBR_RUNTIME_DIR/thread}"
OTBR_TUN_DEVICE="${OTBR_TUN_DEVICE:-/dev/tun}"
CPC_SOCKET_DIR="${CPC_SOCKET_DIR:-/dev/shm}"
CLEAN="${CLEAN:-0}"
PROFILE="${PROFILE:-agent}"
case "$PROFILE" in
  agent) OUT_DIR="$SCRIPT_DIR/out" ;;
  br)    OUT_DIR="$SCRIPT_DIR/out/br" ;;
  *) echo "PROFILE must be agent or br" >&2; exit 1 ;;
esac
SIM_TEST="${SIM_TEST:-1}"
JOBS="${JOBS:-}"

command -v docker >/dev/null || { echo "docker not found" >&2; exit 1; }
mkdir -p "$BUILD_DIR" "$OUT_DIR"

log() { printf '\n=== %s\n' "$*"; }

# ---- 1. builder image (cached by docker; rebuilt only when this recipe changes) ---------------
log "builder image $BUILDER_IMAGE (from $BASE_IMAGE)"
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

# ---- 2. fetch + build inside the builder container ---------------------------------------------
cat > "$BUILD_DIR/inner-build.sh" <<'INNER'
#!/usr/bin/env bash
set -euo pipefail
W=/work/.build
SRC=$W/src
PREFIX=$W/prefix
case "$PROFILE" in agent) OUT=/work/out ;; br) OUT=/work/out/br ;; esac
JOBS="${JOBS:-$(nproc)}"
mkdir -p "$SRC" "$PREFIX/include" "$PREFIX/lib" "$OUT"
[ "$CLEAN" = 1 ] && rm -rf "$W/build-otbr" "$W/build-otbr-br" "$W/build-libcpc" "$W/build-sim" "$W/sim"

log() { printf '\n=== %s\n' "$*"; }

# clone <dir> <url> <tag> <commit>: shallow clone at tag, verify commit; reuse if already there.
clone() {
  local dir=$1 url=$2 tag=$3 commit=$4
  if [ -d "$dir/.git" ] && [ "$(git -C "$dir" rev-parse HEAD)" = "$commit" ]; then
    echo "reuse $dir @ $commit"
  else
    rm -rf "$dir"
    git clone -q --depth 1 -b "$tag" "$url" "$dir"
  fi
  local got; got=$(git -C "$dir" rev-parse HEAD)
  [ "$got" = "$commit" ] || { echo "PIN MISMATCH $url $tag: got $got want $commit" >&2; exit 1; }
  # discard any previous build-time patches so patching below is idempotent
  git -C "$dir" checkout -q -- . && git -C "$dir" clean -qfdx
}

log "fetch ot-br-posix $OTBR_TAG"
clone "$SRC/ot-br-posix" "$OTBR_REPO" "$OTBR_TAG" "$OTBR_COMMIT"
want_ot=$(git -C "$SRC/ot-br-posix" ls-tree HEAD third_party/openthread/repo | awk '{print $3}')
[ "$want_ot" = "$OT_COMMIT" ] || { echo "openthread submodule pin mismatch: $want_ot" >&2; exit 1; }
OTDIR=$SRC/ot-br-posix/third_party/openthread/repo
if [ "$(git -C "$OTDIR" rev-parse HEAD 2>/dev/null || true)" != "$OT_COMMIT" ]; then
  git -C "$SRC/ot-br-posix" submodule update -q --init --depth 1 third_party/openthread/repo
fi
[ "$(git -C "$OTDIR" rev-parse HEAD)" = "$OT_COMMIT" ] || { echo "openthread checkout mismatch" >&2; exit 1; }
git -C "$OTDIR" checkout -q -- . && git -C "$OTDIR" clean -qfdx -e third_party/mbedtls/repo
want_mb=$(git -C "$OTDIR" ls-tree HEAD third_party/mbedtls/repo | awk '{print $3}')
[ "$want_mb" = "$MBEDTLS_COMMIT" ] || { echo "mbedtls submodule pin mismatch: $want_mb" >&2; exit 1; }
MBDIR=$OTDIR/third_party/mbedtls/repo
if [ "$(git -C "$MBDIR" rev-parse HEAD 2>/dev/null || true)" != "$MBEDTLS_COMMIT" ]; then
  git -C "$OTDIR" submodule update -q --init --depth 1 third_party/mbedtls/repo
fi
# mbedtls 3.6 needs its 'framework' submodule at configure time (pinned by mbedtls' gitlink)
if [ ! -f "$MBDIR/framework/CMakeLists.txt" ] && [ ! -d "$MBDIR/framework/scripts" ]; then
  git -C "$MBDIR" submodule update -q --init --depth 1 framework
fi
MBFW_COMMIT=$(git -C "$MBDIR/framework" rev-parse HEAD)
[ "$(git -C "$MBDIR" rev-parse HEAD)" = "$MBEDTLS_COMMIT" ] || { echo "mbedtls checkout mismatch" >&2; exit 1; }

if [ "$PROFILE" = br ]; then
  # REST server deps, pinned by ot-br-posix's own gitlinks (checked against the expected commits)
  for sm in "third_party/cJSON/repo:$CJSON_COMMIT" "third_party/cpp-httplib/repo:$HTTPLIB_COMMIT"; do
    path=${sm%%:*}; want=${sm##*:}
    got=$(git -C "$SRC/ot-br-posix" ls-tree HEAD "$path" | awk '{print $3}')
    [ "$got" = "$want" ] || { echo "$path gitlink mismatch: $got want $want" >&2; exit 1; }
    if [ "$(git -C "$SRC/ot-br-posix/$path" rev-parse HEAD 2>/dev/null || true)" != "$want" ]; then
      git -C "$SRC/ot-br-posix" submodule update -q --init --depth 1 "$path"
    fi
    [ "$(git -C "$SRC/ot-br-posix/$path" rev-parse HEAD)" = "$want" ] || { echo "$path checkout mismatch" >&2; exit 1; }
  done
fi

log "fetch cpc-daemon $CPCD_TAG"
clone "$SRC/cpc-daemon" "$CPCD_REPO" "$CPCD_TAG" "$CPCD_COMMIT"

log "fetch SiLabs CPC vendor interface (simplicity_sdk $SISDK_TAG)"
SIL=$SRC/silabs-posix
mkdir -p "$SIL"
while IFS=: read -r f sum; do
  [ -n "$f" ] || continue
  if ! echo "$sum  $SIL/$f" | sha256sum -c --status 2>/dev/null; then
    curl -fsSL -o "$SIL/$f.tmp" "$SISDK_RAW/$SISDK_COMMIT/$SISDK_POSIX_DIR/$f"
    mv "$SIL/$f.tmp" "$SIL/$f"
  fi
  echo "$sum  $SIL/$f" | sha256sum -c || { echo "sha256 mismatch for $f" >&2; exit 1; }
done <<< "$SISDK_FILES"

# ---- libcpc (static, no encryption) ----
log "build libcpc.a (cpc-daemon $CPCD_TAG, socket dir default $CPC_SOCKET_DIR, runtime env CPC_SOCKET_DIR)"
CPCD=$SRC/cpc-daemon
ver=$(sed -n 's/^ *VERSION "\([0-9.]*\)".*/\1/p' "$CPCD/CMakeLists.txt" | head -1)
api=$(sed -n 's/^set(CPC_LIBRARY_API_VERSION "\([0-9]*\)").*/\1/p' "$CPCD/CMakeLists.txt")
proto=$(sed -n 's/^set(CPC_PROTOCOL_VERSION "\([0-9]*\)").*/\1/p' "$CPCD/CMakeLists.txt")
[ "$ver" = "$CPCD_EXPECTED_VERSION" ] || { echo "unexpected cpcd version $ver" >&2; exit 1; }
IFS=. read -r vmaj vmin vpat vtw <<< "$ver"
# runtime override of the cpcd socket directory (patch applied to a freshly reset checkout)
python3 - "$CPCD/lib/sl_cpc.c" <<'PY'
import sys, re
p = sys.argv[1]; s = open(p).read()
n = s.count('CPC_SOCKET_DIR, lib_handle->instance_name')
assert n == 6, n
s = s.replace('CPC_SOCKET_DIR, lib_handle->instance_name', 'sli_cpc_socket_dir(), lib_handle->instance_name')
hook = ('\n/* aqara-s1-radio build patch: allow overriding the cpcd socket dir at runtime */\n'
        'static const char *sli_cpc_socket_dir(void)\n{\n'
        '  const char *d = getenv("CPC_SOCKET_DIR");\n'
        '  return (d != NULL && d[0] != \'\\0\') ? d : CPC_SOCKET_DIR;\n}\n')
anchor = '#include "version.h"\n'
assert anchor in s
s = s.replace(anchor, anchor + hook, 1)
open(p, 'w').write(s)
PY
LB=$W/build-libcpc; rm -rf "$LB"; mkdir -p "$LB/autogen"
sed -e "s/@CPC_LIBRARY_API_VERSION@/$api/" -e "s/@CPC_PROTOCOL_VERSION@/$proto/" \
    -e "s/@SOURCES_HASH@//" -e "s/@PROJECT_NAME@/cpcd/" -e "s/@PROJECT_VERSION@/$ver/" \
    -e "s/@PROJECT_VERSION_MAJOR@/$vmaj/" -e "s/@PROJECT_VERSION_MINOR@/$vmin/" \
    -e "s/@PROJECT_VERSION_PATCH@/$vpat/" -e "s/@PROJECT_VERSION_TWEAK@/$vtw/" \
    "$CPCD/misc/version.h.in" > "$LB/autogen/version.h"
for f in lib/sl_cpc.c misc/sleep.c misc/sl_slist.c; do
  arm-linux-gnueabihf-gcc -std=c99 -D_POSIX_C_SOURCE=200809L -O2 -fPIC -Wall \
    -DCPC_SOCKET_DIR="\"$CPC_SOCKET_DIR\"" -DDEFAULT_INSTANCE_NAME='"cpcd_0"' \
    -DCPCD_REBOOT_TIME_MS=2000 -DCPCD_CONFIG_FILE_PATH='"/etc/cpcd.conf"' \
    -DGIT_SHA1="\"$CPCD_COMMIT\"" -DGIT_REFSPEC="\"refs/tags/$CPCD_TAG\"" \
    -I"$CPCD" -I"$CPCD/include" -I"$CPCD/lib" -I"$LB/autogen" \
    -c "$CPCD/$f" -o "$LB/$(basename "$f" .c).o"
done
rm -f "$PREFIX/lib/libcpc.a"
arm-linux-gnueabihf-ar rcs "$PREFIX/lib/libcpc.a" "$LB"/*.o
cp "$CPCD/lib/sl_cpc.h" "$PREFIX/include/"

# ---- ot-br-posix ----
log "configure ot-br-posix"
cat > "$W/toolchain-armhf.cmake" <<EOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR arm)
set(CMAKE_C_COMPILER arm-linux-gnueabihf-gcc)
set(CMAKE_CXX_COMPILER arm-linux-gnueabihf-g++)
set(CMAKE_FIND_ROOT_PATH /usr/arm-linux-gnueabihf $PREFIX)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
EOF

# vendor deps package: links the static libcpc into the SiLabs vendor interface
# (same role as SiLabs' posix_vendor_rcp.cmake / FindSilabsRcpDeps.cmake)
cat > "$W/cpc-vendor-deps.cmake" <<EOF
set(OT_POSIX_CONFIG_RCP_VENDOR_TARGET "cpc-interface")
add_library(cpc-interface INTERFACE)
target_include_directories(cpc-interface INTERFACE
    $PREFIX/include $SIL
    \${PROJECT_SOURCE_DIR}/include \${PROJECT_SOURCE_DIR}/src \${PROJECT_SOURCE_DIR}/src/core
    \${PROJECT_SOURCE_DIR}/src/posix/platform \${PROJECT_SOURCE_DIR}/src/posix/platform/include)
target_link_libraries(cpc-interface INTERFACE $PREFIX/lib/libcpc.a ot-posix-config)
EOF

# OpenThread platform config. The SiLabs openthread-core-silabs-posix-config.h (not fetched)
# force-enables border routing, TREL, TCP, DHCPv6, coex, DEBG logging etc. for a full OTBR, which
# conflicts with the minimal OTBR options below. This header keeps only what the CPC vendor
# interface and multi-PAN need, copied from that SiLabs header.
cat > "$W/openthread-core-aqara-cpc-posix-config.h" <<'EOF'
#ifndef OPENTHREAD_CORE_AQARA_CPC_POSIX_CONFIG_H_
#define OPENTHREAD_CORE_AQARA_CPC_POSIX_CONFIG_H_
/* URL scheme handled by the SiLabs VendorInterface (cpc_interface.cpp) */
#ifndef OPENTHREAD_POSIX_CONFIG_SPINEL_VENDOR_INTERFACE_URL_PROTOCOL_NAME
#define OPENTHREAD_POSIX_CONFIG_SPINEL_VENDOR_INTERFACE_URL_PROTOCOL_NAME "spinel+cpc"
#endif
#define OT_VENDOR_RADIO_URL_HELP_BUS                                                               \
    "Protocol=[spinel+cpc*]            Specify the Spinel interface as the Spinel CPC interface\n" \
    "    spinel+cpc://cpcd_0?${Parameters} for connecting to cpcd\n"                               \
    "Parameters:\n"                                                                                \
    "    cpc-bus-speed[=speed]         CPC bus speed used for communicating with RCP.\n"
/* multi-PAN: the radio is shared with zigbeed, so no channel monitor/manager energy scans
 * and no host-side max power table (as in the SiLabs header) */
#if OPENTHREAD_CONFIG_MULTIPAN_RCP_ENABLE
#undef OPENTHREAD_POSIX_CONFIG_MAX_POWER_TABLE_ENABLE
#define OPENTHREAD_POSIX_CONFIG_MAX_POWER_TABLE_ENABLE 0
#undef OPENTHREAD_CONFIG_CHANNEL_MANAGER_ENABLE
#define OPENTHREAD_CONFIG_CHANNEL_MANAGER_ENABLE 0
#undef OPENTHREAD_CONFIG_CHANNEL_MONITOR_ENABLE
#define OPENTHREAD_CONFIG_CHANNEL_MONITOR_ENABLE 0
#endif
/* tolerate RCP resets while zigbeed/cpcd restart (SiLabs value) */
#undef OPENTHREAD_SPINEL_CONFIG_RCP_RESTORATION_MAX_COUNT
#define OPENTHREAD_SPINEL_CONFIG_RCP_RESTORATION_MAX_COUNT 100
#include "openthread-core-posix-config.h"
#endif
EOF

# compile-time runtime paths for Android, force-included into every C/C++ TU
cat > "$W/otbr-android-paths.h" <<EOF
#ifndef OTBR_ANDROID_PATHS_H_
#define OTBR_ANDROID_PATHS_H_
/* unix socket used by otbr-agent (daemon) and ot-ctl: <dir>/openthread-<ifname>.sock (+ .lock) */
#define OPENTHREAD_POSIX_CONFIG_DAEMON_SOCKET_BASENAME "$OTBR_RUNTIME_DIR/openthread-%s"
/* Android exposes the TUN clone device as /dev/tun, not /dev/net/tun */
#define OPENTHREAD_POSIX_TUN_DEVICE "$OTBR_TUN_DEVICE"
/* optional OT posix config files (power calibration / region); absence is tolerated */
#define OPENTHREAD_POSIX_CONFIG_FACTORY_CONFIG_FILE "$OTBR_RUNTIME_DIR/openthread-factory.conf"
#define OPENTHREAD_POSIX_CONFIG_PRODUCT_CONFIG_FILE "$OTBR_RUNTIME_DIR/openthread-product.conf"
#endif
EOF

ARCH_FLAGS="-march=armv7-a -mfpu=vfpv3-d16 -mfloat-abi=hard -mthumb"
COMMON_FLAGS="$ARCH_FLAGS -include $W/otbr-android-paths.h -I$W -I$SIL -ffunction-sections -fdata-sections"
BUILD=$W/build-otbr; [ "$PROFILE" = br ] && BUILD=$W/build-otbr-br
OTBR_CMAKE_ARGS=(
  -G Ninja
  -DCMAKE_TOOLCHAIN_FILE="$W/toolchain-armhf.cmake"
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_C_FLAGS="$COMMON_FLAGS"
  -DCMAKE_CXX_FLAGS="$COMMON_FLAGS"
  -DCMAKE_EXE_LINKER_FLAGS="-static -Wl,--gc-sections"
  -DBUILD_TESTING=OFF
  -DOTBR_VERSION="$OTBR_TAG-aqara-s1"
  # compile-time vendor/model, otherwise otbr-agent refuses to start without --vendor-name/--model-name
  -DOTBR_VENDOR_NAME=Aqara
  -DOTBR_PRODUCT_NAME=S1-CPC
  # --- profile options: see OTBR_PROFILE_ARGS below
  -DOT_POSIX_SETTINGS_PATH="\"$OTBR_SETTINGS_DIR\""
  # --- SiLabs multi-PAN RCP over CPC (HA silabs-multiprotocol recipe, current OT option names)
  -DOT_MULTIPAN_RCP=ON
  -DOT_POSIX_RCP_HDLC_BUS=ON
  -DOT_POSIX_RCP_VENDOR_BUS=ON
  -DOT_POSIX_CONFIG_RCP_VENDOR_INTERFACE="$SIL/cpc_interface.cpp"
  -DOT_POSIX_CONFIG_RCP_VENDOR_DEPS_PACKAGE="$W/cpc-vendor-deps.cmake"
  -DOT_PLATFORM_CONFIG=openthread-core-aqara-cpc-posix-config.h
)
if [ "$PROFILE" = agent ]; then
OTBR_PROFILE_ARGS=(
  # --- everything optional off
  -DOTBR_MDNS=OFF
  -DOTBR_BORDER_AGENT=OFF
  -DOTBR_BORDER_AGENT_MESHCOP_SERVICE=OFF
  -DOTBR_EPSKC=OFF
  -DOTBR_BACKBONE_ROUTER=OFF
  -DOTBR_BORDER_ROUTING=OFF
  -DOTBR_BORDER_ROUTING_COUNTERS=OFF
  -DOTBR_NAT64=OFF
  -DOTBR_DNS_UPSTREAM_QUERY=OFF
  -DOTBR_DBUS=OFF
  -DOTBR_REST=OFF
  -DOTBR_WEB=OFF
  -DOTBR_TREL=OFF
  -DOTBR_TREL_DNSSD=OFF
  -DOTBR_DNSSD_DISCOVERY_PROXY=OFF
  -DOTBR_SRP_ADVERTISING_PROXY=OFF
  -DOTBR_OT_SRP_ADV_PROXY=OFF
  -DOTBR_OT_DISCOVERY_PROXY=OFF
  -DOTBR_DNSSD_PLAT=OFF
  -DOTBR_DHCP6_PD=OFF
  -DOTBR_MULTI_AIL=OFF
  -DOTBR_FEATURE_FLAGS=OFF
  -DOTBR_TELEMETRY_DATA_API=OFF
  -DOTBR_LINK_METRICS_TELEMETRY=OFF
  -DOTBR_POWER_CALIBRATION=OFF
  -DOTBR_PUBLISH_MESHCOP_BA_ID=OFF
  -DOTBR_NOTIFY_UPSTART=OFF
  -DOTBR_NFTABLES=OFF
  -DOT_FIREWALL=OFF
  -DOT_READLINE=OFF
)
else
# Border router for Home Assistant's OTBR integration (python-otbr-api: /node, /node/dataset/*,
# /node/ba-id, /node/ext-address, /node/coprocessor/version, /node/state). mDNS is OpenThread's own
# implementation (no avahi/mDNSResponder daemon on Android): it publishes _meshcop._udp (border
# agent) and SRP-registered services (Matter) on the backbone interface.
OTBR_PROFILE_ARGS=(
  -DOTBR_MDNS=openthread
  -DOTBR_BORDER_AGENT=ON
  -DOTBR_BORDER_AGENT_MESHCOP_SERVICE=OFF   # with OT mDNS the OT core publishes meshcop itself
  -DOTBR_PUBLISH_MESHCOP_BA_ID=ON
  -DOTBR_EPSKC=OFF
  -DOTBR_BORDER_ROUTING=ON
  -DOTBR_BORDER_ROUTING_COUNTERS=ON
  -DOTBR_SRP_SERVER_AUTO_ENABLE=ON
  -DOTBR_REST=ON
  -DOTBR_WEB=OFF
  -DOTBR_DBUS=OFF
  -DOTBR_BACKBONE_ROUTER=OFF               # Thread 1.2 multicast across the backbone: needs CONFIG_IPV6_MROUTE, not in the A133 kernel
  -DOTBR_NAT64=OFF                         # kernel could (IPv4 MASQUERADE is there); needs a NAT rule in the helper. HA doesn't use it
  -DOTBR_DNS_UPSTREAM_QUERY=OFF            # no /etc/resolv.conf on Android
  -DOTBR_TREL=OFF
  -DOTBR_TREL_DNSSD=OFF
  -DOTBR_DHCP6_PD=OFF
  -DOTBR_MULTI_AIL=OFF
  -DOTBR_FEATURE_FLAGS=OFF
  -DOTBR_TELEMETRY_DATA_API=OFF
  -DOTBR_LINK_METRICS_TELEMETRY=OFF
  -DOTBR_POWER_CALIBRATION=OFF
  -DOTBR_NOTIFY_UPSTART=OFF
  -DOTBR_NFTABLES=OFF
  -DOT_FIREWALL=OFF
  -DOT_READLINE=OFF
)
fi
OTBR_CMAKE_ARGS+=("${OTBR_PROFILE_ARGS[@]}")
# no target pkg-config dir exists, so host .pc files (e.g. libsystemd) can't leak in
export PKG_CONFIG_LIBDIR=/nonexistent PKG_CONFIG_PATH=
cmake -S "$SRC/ot-br-posix" -B "$BUILD" "${OTBR_CMAKE_ARGS[@]}" > "$W/cmake-configure-$PROFILE.log" 2>&1 \
  || { tail -60 "$W/cmake-configure-$PROFILE.log"; exit 1; }
grep -E "OT_PLATFORM_CONFIG|Readline|Version:|WARNING" "$W/cmake-configure-$PROFILE.log" || true

log "build otbr-agent ot-ctl (-j$JOBS)"
ninja -C "$BUILD" -j "$JOBS" otbr-agent ot-ctl

for b in otbr-agent ot-ctl; do
  src=$(find "$BUILD" -type f -name "$b" -perm -u+x | head -1)
  arm-linux-gnueabihf-strip -o "$OUT/$b" "$src"
done

if [ "$SIM_TEST" = 1 ]; then
  # test-only: OpenThread simulated RCP (same OT tree), used by the host-side smoke test to run
  # otbr-agent + ot-ctl end to end. Never copied to out/.
  log "build simulated ot-rcp (test only)"
  cmake -S "$OTDIR" -B "$W/build-sim" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$W/toolchain-armhf.cmake" \
    -DCMAKE_BUILD_TYPE=Release -DOT_PLATFORM=simulation -DOT_COMPILE_WARNING_AS_ERROR=OFF \
    -DOT_APP_CLI=OFF -DOT_APP_NCP=OFF -DOT_APP_RCP=ON -DOT_FTD=OFF -DOT_MTD=OFF -DOT_RCP=ON \
    -DOT_BUILD_EXECUTABLES=ON -DCMAKE_EXE_LINKER_FLAGS=-static > "$W/cmake-sim.log" 2>&1 \
    || { tail -40 "$W/cmake-sim.log"; exit 1; }
  ninja -C "$W/build-sim" -j "$JOBS" ot-rcp > "$W/ninja-sim.log" 2>&1 || { tail -40 "$W/ninja-sim.log"; exit 1; }
  mkdir -p "$W/sim" && cp "$W/build-sim/examples/apps/ncp/ot-rcp" "$W/sim/ot-rcp"
fi

log "verify"
cd "$OUT"
for b in otbr-agent ot-ctl; do
  file "$b"
  ls -l "$b" | awk '{print $5" bytes  "$9}'
  if arm-linux-gnueabihf-readelf -l "$b" | grep -q "Requesting program interpreter"; then
    echo "ERROR: $b is dynamically linked" >&2; exit 1
  fi
  if arm-linux-gnueabihf-readelf -d "$b" 2>/dev/null | grep -q NEEDED; then
    echo "ERROR: $b has DT_NEEDED entries" >&2; exit 1
  fi
done
# prove the CPC vendor interface and libcpc are linked in
pats=("spinel+cpc" "ctrl.cpcd.sock" "$OTBR_TUN_DEVICE" "$OTBR_RUNTIME_DIR/openthread-%s")
[ "$PROFILE" = br ] && pats+=("/node/dataset/active" "/node/ba-id" "_meshcop._udp")
for pat in "${pats[@]}"; do
  grep -a -q -F "$pat" otbr-agent || { echo "ERROR: '$pat' not found in otbr-agent" >&2; exit 1; }
  echo "otbr-agent contains: $pat"
done
grep -a -q -F "$OTBR_RUNTIME_DIR/openthread-%s" ot-ctl || { echo "ERROR: ot-ctl socket path" >&2; exit 1; }
echo "ot-ctl contains: $OTBR_RUNTIME_DIR/openthread-%s"

{
  echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ)  profile: $PROFILE"
  echo "ot-br-posix $OTBR_TAG $OTBR_COMMIT (BSD-3-Clause)"
  echo "openthread  $OT_COMMIT (BSD-3-Clause)"
  echo "mbedtls     v3.6.5 $MBEDTLS_COMMIT, framework $MBFW_COMMIT (Apache-2.0)"
  echo "cpc-daemon  $CPCD_TAG $CPCD_COMMIT libcpc $ver api $api proto $proto (Silicon Labs MSLA)"
  echo "simplicity_sdk $SISDK_TAG $SISDK_COMMIT cpc_interface.cpp (Zlib)"
  [ "$PROFILE" = br ] && echo "cJSON       $CJSON_COMMIT (MIT)" && echo "cpp-httplib $HTTPLIB_COMMIT (MIT)"
  echo "toolchain   $(arm-linux-gnueabihf-gcc --version | head -1)"
  echo "glibc       $(dpkg-query -W -f='${Version}' libc6-armhf-cross 2>/dev/null || echo '?') (static)"
  echo
  echo "runtime paths compiled in:"
  echo "  daemon socket : $OTBR_RUNTIME_DIR/openthread-<ifname>.sock (+ .lock), default ifname wpan0"
  echo "  settings dir  : $OTBR_SETTINGS_DIR  (otbr-agent --data-path overrides)"
  echo "  TUN device    : $OTBR_TUN_DEVICE"
  echo "  cpcd sockets  : \${CPC_SOCKET_DIR:-$CPC_SOCKET_DIR}/cpcd/<instance>/{ctrl,ep<N>}.cpcd.sock"
  echo
  echo "cmake args:"; printf '  %s\n' "${OTBR_CMAKE_ARGS[@]}"
  echo
  for b in otbr-agent ot-ctl; do file "$b"; done
  sha256sum otbr-agent ot-ctl
} > "$OUT/BUILD-INFO.txt"
INNER

[ -n "$JOBS" ] || JOBS=""
log "build in container"
docker run --rm --platform linux/arm64 \
  -v "$SCRIPT_DIR:/work" -w /work \
  -e CLEAN="$CLEAN" -e JOBS="$JOBS" -e SIM_TEST="$SIM_TEST" -e PROFILE="$PROFILE" \
  -e CJSON_COMMIT="$CJSON_COMMIT" -e HTTPLIB_COMMIT="$HTTPLIB_COMMIT" \
  -e OTBR_REPO="$OTBR_REPO" -e OTBR_TAG="$OTBR_TAG" -e OTBR_COMMIT="$OTBR_COMMIT" -e OT_COMMIT="$OT_COMMIT" -e MBEDTLS_COMMIT="$MBEDTLS_COMMIT" \
  -e CPCD_REPO="$CPCD_REPO" -e CPCD_TAG="$CPCD_TAG" -e CPCD_COMMIT="$CPCD_COMMIT" \
  -e CPCD_EXPECTED_VERSION="$CPCD_EXPECTED_VERSION" \
  -e SISDK_RAW="$SISDK_RAW" -e SISDK_TAG="$SISDK_TAG" -e SISDK_COMMIT="$SISDK_COMMIT" \
  -e SISDK_POSIX_DIR="$SISDK_POSIX_DIR" -e SISDK_FILES="$SISDK_FILES" \
  -e OTBR_RUNTIME_DIR="$OTBR_RUNTIME_DIR" -e OTBR_SETTINGS_DIR="$OTBR_SETTINGS_DIR" \
  -e OTBR_TUN_DEVICE="$OTBR_TUN_DEVICE" -e CPC_SOCKET_DIR="$CPC_SOCKET_DIR" \
  "$BUILDER_IMAGE" bash /work/.build/inner-build.sh

# ---- 3. smoke test: execute the binaries on a 32-bit ARM userspace -----------------------------
log "smoke test in $TEST_IMAGE (32-bit ARM userspace)"
docker run --rm --platform linux/arm/v7 -v "$OUT_DIR:/o:ro" "$TEST_IMAGE" bash -c '
  set -u
  uname -m
  echo "--- otbr-agent --version";  /o/otbr-agent --version; echo "rc=$?"
  echo "--- otbr-agent --help";     /o/otbr-agent --help 2>&1 | head -40; echo "rc=${PIPESTATUS[0]}"
  echo "--- ot-ctl -h";             /o/ot-ctl -h 2>&1; echo "rc=$?"
  echo "--- ot-ctl version (no agent running: expect connect error)"
  /o/ot-ctl version 2>&1; echo "rc=$?"
  echo "--- otbr-agent --radio-version spinel+cpc (no cpcd here: expect a libcpc/CPC init failure)"
  timeout 15 /o/otbr-agent -s -d 7 -v --radio-version "spinel+cpc://cpcd_0?iid=2&iid-list=0" 2>&1 | tail -15
  echo "rc=${PIPESTATUS[0]}"
'
if [ "$SIM_TEST" = 1 ]; then
  log "end-to-end test: otbr-agent (no backbone, --auto-attach=0) + ot-ctl against simulated RCP"
  docker run --rm --platform linux/arm/v7 --cap-add NET_ADMIN --device "/dev/net/tun:$OTBR_TUN_DEVICE" \
    -e RUNDIR="$OTBR_RUNTIME_DIR" -e PROFILE="$PROFILE" -v "$OUT_DIR:/o:ro" -v "$BUILD_DIR/sim:/sim:ro" "$TEST_IMAGE" bash -c '
    set -eu
    mkdir -p "$RUNDIR"
    URL="spinel+hdlc+forkpty:///sim/ot-rcp?forkpty-arg=1&iid=0"   # multi-PAN builds require iid=
    echo "--- otbr-agent --radio-version (dry run: no TUN, no socket)"
    /o/otbr-agent -s --radio-version "$URL" 2>/dev/null
    echo "netifs: $(ls /sys/class/net | tr "\n" " ")"
    /o/otbr-agent -s -d 6 -I wpan0 --auto-attach=0 "$URL" > /tmp/agent.log 2>&1 &
    AP=$!
    for i in $(seq 1 30); do [ -S "$RUNDIR/openthread-wpan0.sock" ] && break; sleep 0.5; done
    echo "--- runtime dir"; ls -l "$RUNDIR" "$RUNDIR/thread"
    echo "netifs: $(ls /sys/class/net | tr "\n" " ")"
    for c in version state channel "rcp version" ifconfig; do echo "> ot-ctl $c"; /o/ot-ctl $c; done
    if [ "$PROFILE" = br ]; then
      # REST API as the HA OTBR integration reads it (python-otbr-api), over bash /dev/tcp (no curl here)
      for path in /node /node/ba-id /node/coprocessor/version /node/dataset/active; do
        exec 3<>/dev/tcp/127.0.0.1/8081
        printf "GET %s HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: application/json\r\nConnection: close\r\n\r\n" "$path" >&3
        echo "> GET $path"; head -c 600 <&3 | sed -n "1p;/^\r$/,\$p" | tr -d "\r"; echo; exec 3<&-
      done
    fi
    kill $AP; wait $AP 2>/dev/null || true
    echo "--- agent log"; cat /tmp/agent.log | grep -v "Session socket is ready\|Daemon read"
  '
fi
log "done: $OUT_DIR"
cat "$OUT_DIR/BUILD-INFO.txt"
