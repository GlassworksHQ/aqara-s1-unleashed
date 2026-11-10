#!/usr/bin/env bash
# Build U-Boot (SPL + proper) for the Aqara Panel Hub S1 Plus, to run from FEL (RAM only).
#
#   UBOOT_SRC=~/src/u-boot  BL31=~/src/trusted-firmware-a/build/sun50i_a133/release/bl31.bin \
#     tools/fel/build-uboot.sh
#
# Copies this repo's board files (tools/fel/uboot/) into the U-Boot tree, then builds inside an
# arm64 Ubuntu container (macOS host tools for U-Boot/libfdt don't build cleanly).
# Output: $UBOOT_SRC/build-s1/u-boot-sunxi-with-spl.bin
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
UBOOT_SRC="${UBOOT_SRC:?set UBOOT_SRC to a U-Boot checkout}"
BL31="${BL31:?set BL31 to a TF-A sun50i_a133 bl31.bin}"

cp "$HERE/uboot/aqara-s1-plus_defconfig" "$UBOOT_SRC/configs/"
cp "$HERE/uboot/sun50i-a133-aqara-s1-plus.dts" "$UBOOT_SRC/dts/upstream/src/arm64/allwinner/"
cp "$BL31" "$UBOOT_SRC/bl31-s1.bin"

# Board-specific fixes to mainline (see docs/research/fel.md). Applied once; skipped if already present.
for p in "$HERE"/uboot/patches/*.patch; do
  [ -e "$p" ] || continue
  if git -C "$UBOOT_SRC" apply --reverse --check "$p" 2>/dev/null; then
    echo "already applied: $(basename "$p")"
  else
    git -C "$UBOOT_SRC" apply "$p"
    echo "applied: $(basename "$p")"
  fi
done

docker run --rm --platform linux/arm64 -v "$UBOOT_SRC":/src -w /src arm64v8/ubuntu:22.04 bash -euc '
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq >/dev/null
  apt-get install -y -qq build-essential bc bison flex libssl-dev python3 python3-dev \
      python3-setuptools python3-pyelftools swig device-tree-compiler libgnutls28-dev uuid-dev >/dev/null
  make O=build-s1 aqara-s1-plus_defconfig >/dev/null
  make O=build-s1 -j"$(nproc)" BL31=/src/bl31-s1.bin SCP=/dev/null 2>&1 | tail -20
'
ls -l "$UBOOT_SRC/build-s1/u-boot-sunxi-with-spl.bin"
