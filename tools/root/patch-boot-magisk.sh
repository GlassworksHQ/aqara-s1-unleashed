#!/usr/bin/env bash
# Patch a stock S1 Plus boot.img with Magisk + the S1 Kiosk root helper (overlay.d/s1kiosk_radio.rc).
# The patching runs on the panel over adb as the shell user (magiskboot only edits files; no root).
# Nothing is flashed here; see docs/root.md for flashing (fastboot from the FEL U-Boot).
#
#   tools/root/patch-boot-magisk.sh <stock-boot.img> <out-boot.img>
#
# Magisk is GPLv3 and is not redistributed by this repo: the official release is downloaded and its
# SHA-256 checked against the digest GitHub publishes for the asset.
set -euo pipefail
MAGISK_TAG=${MAGISK_TAG:-v31.1}
MAGISK_SHA256=${MAGISK_SHA256:-89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7}
ABI=armeabi-v7a                         # the panel's userspace is 32-bit only
IN=${1:?stock boot.img}; OUT=${2:?output boot.img}
HERE="$(cd "$(dirname "$0")" && pwd)"
RC="$HERE/../radio/s1kiosk_radio.rc"
DEV=/data/local/tmp/s1mgk

W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
APK="$W/Magisk-$MAGISK_TAG.apk"
curl -fsSL -o "$APK" "https://github.com/topjohnwu/Magisk/releases/download/$MAGISK_TAG/Magisk-$MAGISK_TAG.apk"
echo "$MAGISK_SHA256  $APK" | shasum -a 256 -c -
unzip -q "$APK" "assets/boot_patch.sh" "assets/util_functions.sh" "lib/$ABI/*" -d "$W/x"
mkdir "$W/p"
cp "$W/x/assets/boot_patch.sh" "$W/x/assets/util_functions.sh" "$W/p/"
for b in magiskboot magiskinit magisk init-ld; do cp "$W/x/lib/$ABI/lib$b.so" "$W/p/$b"; done
cp "$RC" "$W/p/s1kiosk_radio.rc"
cp "$IN" "$W/p/boot.img"

adb shell "rm -rf $DEV && mkdir -p $DEV"
adb push -q "$W/p/." "$DEV/"
adb shell "cd $DEV && chmod 755 magiskboot magiskinit magisk init-ld boot_patch.sh &&
  KEEPVERITY=true KEEPFORCEENCRYPT=true sh boot_patch.sh $DEV/boot.img &&
  ./magiskboot cleanup && ./magiskboot unpack new-boot.img &&
  ./magiskboot cpio ramdisk.cpio 'add 0644 overlay.d/s1kiosk_radio.rc s1kiosk_radio.rc' &&
  ./magiskboot cpio ramdisk.cpio 'ls overlay.d' &&
  ./magiskboot repack new-boot.img s1-boot.img"
adb pull -q "$DEV/s1-boot.img" "$OUT"
adb shell "rm -rf $DEV"
echo "patched: $OUT"
shasum -a 256 "$IN" "$OUT"
