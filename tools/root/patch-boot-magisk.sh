#!/usr/bin/env bash
# Patch a stock S1 Plus boot.img with Magisk + the S1 Kiosk root helper, entirely on the host.
# No adb and no panel needed, so it works for factory panels (stock boot read over FEL fastboot).
#
#   tools/root/patch-boot-magisk.sh <stock-boot.img> <out-boot.img>
#
# Runs Magisk's own boot_patch.sh in an arm64 Linux container (Docker): the arm64 magiskboot does
# the work, and the panel's armeabi-v7a magiskinit/magisk/init-ld are the payload. Then it adds:
#   overlay.d/s1kiosk_radio.rc           init service (tools/radio/s1kiosk_radio.rc)
#   overlay.d/sbin/s1kiosk_radio.sh      root helper   -> ${MAGISKTMP}/s1kiosk_radio.sh
#   overlay.d/sbin/{otbr-agent,ot-ctl}   Thread stack  (tools/radio/build-otbr.sh)
#   overlay.d/s1kiosk_firstboot.rc       one-shot first-boot provisioning (tools/root/s1kiosk_firstboot.*)
#   overlay.d/sbin/s1kiosk_firstboot.sh
#   overlay.d/sbin/s1kiosk_adb_keys      this computer's adb public key, authorized on first boot so a
#                                        factory panel (no adb) can continue with tools/install.sh
#                                        (ADB_PUBKEY=<file>, default ~/.android/adbkey.pub; none = skip)
#   overlay.d/s1kiosk_netwatch.rc        network watchdog (cycles Wi-Fi if the link wedges)
#   overlay.d/sbin/s1kiosk_netwatch.sh
#   overlay.d/s1kiosk_branding.rc        boot animation, bind-mounted over /system/media/bootanimation.zip
#   overlay.d/sbin/s1kiosk_bootanimation.zip   (BOOT_ANIMATION=<zip>, default branding/glass-tile; none = stock)
# Nothing is flashed here; see docs/research/root.md.
#
# Magisk is GPLv3 and is not redistributed by this repo: the official release is downloaded and its
# SHA-256 checked against the digest GitHub publishes for the asset.
set -euo pipefail
MAGISK_TAG=${MAGISK_TAG:-v31.1}
MAGISK_SHA256=${MAGISK_SHA256:-89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7}
PAYLOAD_ABI=armeabi-v7a                 # the panel's userspace is 32-bit only
PREINITDEVICE=${PREINITDEVICE:-cache}   # what Magisk itself picks on this panel (--preinit-device)
IN=${1:?stock boot.img}; OUT=${2:?output boot.img}
HERE="$(cd "$(dirname "$0")" && pwd)"
RADIO="$HERE/../radio"
ADB_PUBKEY=${ADB_PUBKEY:-$HOME/.android/adbkey.pub}
BOOT_ANIMATION=${BOOT_ANIMATION:-$HERE/../../branding/glass-tile/bootanimation.zip}

for b in otbr-agent ot-ctl; do
  [ -x "$RADIO/out/$b" ] || { echo "missing $RADIO/out/$b: run OTBR_RUNTIME_DIR=/data/s1kiosk/radio/run tools/radio/build-otbr.sh"; exit 1; }
done
grep -q '/data/s1kiosk/radio/run' "$RADIO/out/BUILD-INFO.txt" ||
  { echo "otbr was built with the wrong OTBR_RUNTIME_DIR (need /data/s1kiosk/radio/run)"; exit 1; }
[ "$(head -c 8 "$IN")" = "ANDROID!" ] || { echo "$IN is not an Android boot image"; exit 1; }

W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
APK="$W/Magisk-$MAGISK_TAG.apk"
curl -fsSL -o "$APK" "https://github.com/topjohnwu/Magisk/releases/download/$MAGISK_TAG/Magisk-$MAGISK_TAG.apk"
echo "$MAGISK_SHA256  $APK" | shasum -a 256 -c -
unzip -q "$APK" assets/boot_patch.sh assets/util_functions.sh "lib/$PAYLOAD_ABI/*" "lib/arm64-v8a/libmagiskboot.so" -d "$W/x"
mkdir "$W/p"
cp "$W/x/assets/boot_patch.sh" "$W/x/assets/util_functions.sh" "$W/p/"
cp "$W/x/lib/arm64-v8a/libmagiskboot.so" "$W/p/magiskboot"           # runs on the host
for b in magiskinit magisk init-ld; do cp "$W/x/lib/$PAYLOAD_ABI/lib$b.so" "$W/p/$b"; done  # go in the image
cp "$RADIO/s1kiosk_radio.rc" "$RADIO/s1kiosk_radio.sh" "$RADIO/out/otbr-agent" "$RADIO/out/ot-ctl" "$W/p/"
cp "$HERE/s1kiosk_firstboot.rc" "$HERE/s1kiosk_firstboot.sh" "$HERE/s1kiosk_netwatch.rc" "$HERE/s1kiosk_netwatch.sh" "$W/p/"
if [ "$ADB_PUBKEY" = none ]; then : > "$W/p/s1kiosk_adb_keys"
elif [ -s "$ADB_PUBKEY" ]; then
  grep -q '^QAAAA' "$ADB_PUBKEY" || { echo "$ADB_PUBKEY does not look like an adb public key"; exit 1; }
  cp "$ADB_PUBKEY" "$W/p/s1kiosk_adb_keys"
else echo "no adb public key at $ADB_PUBKEY (run 'adb start-server' once, or ADB_PUBKEY=none)"; exit 1
fi
BRANDING_CPIO=""
if [ "$BOOT_ANIMATION" != none ]; then
  [ -s "$BOOT_ANIMATION" ] || { echo "no boot animation at $BOOT_ANIMATION (BOOT_ANIMATION=none for stock)"; exit 1; }
  python3 - "$BOOT_ANIMATION" <<'PY' || exit 1
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
bad = [i.filename for i in z.infolist() if i.compress_type != zipfile.ZIP_STORED]
assert not bad, f"bootanimation.zip entries must be STORED, not compressed: {bad[:3]}"
PY
  cp "$BOOT_ANIMATION" "$W/p/s1kiosk_bootanimation.zip"
  cp "$HERE/s1kiosk_branding.rc" "$W/p/"
  BRANDING_CPIO='"add 0644 overlay.d/s1kiosk_branding.rc s1kiosk_branding.rc" "add 0644 overlay.d/sbin/s1kiosk_bootanimation.zip s1kiosk_bootanimation.zip"'
fi
cp "$IN" "$W/p/boot.img"

docker run --rm --platform linux/arm64 -e PREINITDEVICE="$PREINITDEVICE" -e BRANDING_CPIO="$BRANDING_CPIO" -v "$W/p":/w -w /w \
  arm64v8/ubuntu:22.04 sh -euc '
  chmod 755 magiskboot magiskinit magisk init-ld
  # Outside Android: boot_patch.sh needs dos2unix (its grep_prop reads the stock SHA1/PREINITDEVICE
  # back from an already-patched image) and an OUTFD for ui_print.
  mkdir -p /tmp/bin && printf "#!/bin/sh\nexec sed \"s/\\r\$//\"\n" > /tmp/bin/dos2unix && chmod 755 /tmp/bin/dos2unix
  export PATH=/tmp/bin:$PATH OUTFD=1
  KEEPVERITY=true KEEPFORCEENCRYPT=true sh boot_patch.sh /w/boot.img
  ./magiskboot cleanup
  ./magiskboot unpack new-boot.img
  ./magiskboot cpio ramdisk.cpio \
    "add 0644 overlay.d/s1kiosk_radio.rc s1kiosk_radio.rc" \
    "add 0700 overlay.d/sbin/s1kiosk_radio.sh s1kiosk_radio.sh" \
    "add 0700 overlay.d/sbin/otbr-agent otbr-agent" \
    "add 0700 overlay.d/sbin/ot-ctl ot-ctl" \
    "add 0644 overlay.d/s1kiosk_firstboot.rc s1kiosk_firstboot.rc" \
    "add 0700 overlay.d/sbin/s1kiosk_firstboot.sh s1kiosk_firstboot.sh" \
    "add 0600 overlay.d/sbin/s1kiosk_adb_keys s1kiosk_adb_keys" \
    "add 0644 overlay.d/s1kiosk_netwatch.rc s1kiosk_netwatch.rc" \
    "add 0700 overlay.d/sbin/s1kiosk_netwatch.sh s1kiosk_netwatch.sh"
  [ -z "$BRANDING_CPIO" ] || eval "./magiskboot cpio ramdisk.cpio $BRANDING_CPIO"
  ./magiskboot cpio ramdisk.cpio "extract .backup/.magisk magisk.cfg"
  echo "--- Magisk config:"; cat magisk.cfg
  echo "--- overlay.d:"; ./magiskboot cpio ramdisk.cpio "ls -r overlay.d"
  ./magiskboot repack new-boot.img s1-boot.img'
cp "$W/p/s1-boot.img" "$OUT"
[ "$(wc -c < "$OUT")" = "$(wc -c < "$IN")" ] || { echo "size changed: refusing"; exit 1; }
echo "patched: $OUT"
shasum -a 256 "$IN" "$OUT"
