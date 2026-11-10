#!/usr/bin/env bash
# End-to-end install on an Aqara Panel Hub S1 Plus (factory or already installed), with only the PCB
# FEL button and a USB-C cable: no UART, no adb needed beforehand.
#
#   tools/install/fel-install.sh [backup-dir]
#
#   1. wait for FEL (you hold the PCB button while plugging in), check the SoC is an A133
#   2. boot our U-Boot from RAM (tools/fel/fel-boot.sh); it starts USB fastboot by itself
#   3. check the partition layout is the S1 Plus one
#   4. back up the panel's own partitions to <backup-dir> (boot read twice and compared)
#   5. patch boot on this computer (tools/root/patch-boot-magisk.sh: Magisk + S1 helper + this
#      computer's adb key, authorized on first boot)
#   6. vbmeta: if its flags are not 3 (verification off), flash the panel's own vbmeta with flags 3,
#      the configuration proven on the reference panel; verified by read-back
#   7. flash boot, verify by read-back, reboot
#   8. wait for adb (authorized by the first-boot service), then point you to tools/install.sh
#
# Needs: sunxi-fel, fastboot, adb, docker, and a U-Boot build (tools/fel/build-uboot.sh).
# Undo: boot to FEL again and `fastboot flash boot <backup-dir>/boot.img` (and vbmeta.img).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
FEL=${SUNXI_FEL:-sunxi-fel}; FB=${FASTBOOT:-fastboot}; ADB=${ADB:-adb}
UBUILD=${UBOOT_BUILD:-$HOME/src/u-boot-s1/build-s1}
OUT=${1:-$PWD/s1-backup-$(date +%Y%m%d-%H%M%S)}
BACKUP_PARTS="boot vbmeta vbmeta_system vbmeta_vendor dtbo misc recovery env bootloader"

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
for t in "$FEL" "$FB" "$ADB" docker shasum python3; do command -v "$t" >/dev/null || die "missing tool: $t"; done
[ -f "$UBUILD/u-boot-sunxi-with-spl.bin" ] || die "no U-Boot build in $UBUILD (tools/fel/build-uboot.sh)"
mkdir -p "$OUT"; chmod 700 "$OUT"

say "1/8 Waiting for FEL: hold the PCB button (docs/img/fel-button.jpg), plug in USB-C, keep holding ~10 s"
for i in $(seq 1 120); do v=$("$FEL" ver 2>/dev/null) && break; sleep 1; done
[ -n "${v:-}" ] || die "no FEL device found"
echo "$v"
case "$v" in *soc=00001855*) ;; *) die "not an A133 (expected soc=00001855)";; esac

say "2/8 Booting our U-Boot from RAM (nothing is written)"
NO_BL31=1 SUNXI_FEL="$FEL" "$HERE/../fel/fel-boot.sh" "$UBUILD"
for i in $(seq 1 60); do "$FB" devices | grep -q fastboot && break; sleep 1; done
"$FB" devices | grep -q fastboot || die "fastboot did not come up"

say "3/8 Checking the partition layout"
gv() { "$FB" getvar "$1" 2>&1 | sed -n "s/^$1: //p"; }
[ "$(gv product)" = sunxi ] || die "unexpected bootloader product"
[ "$(gv partition-size:boot)" = 0x0000000002000000 ] || die "boot is not 32 MiB: not an S1 Plus layout?"
[ "$(gv partition-size:super)" = 0x0000000060000000 ] || die "super is not 1.5 GiB: not an S1 Plus layout?"

say "4/8 Backing up partitions to $OUT"
for p in $BACKUP_PARTS; do "$HERE/fb-read-partition.sh" "$p" "$OUT/$p.img"; done
"$HERE/fb-read-partition.sh" boot "$OUT/boot.check.img" >/dev/null
cmp -s "$OUT/boot.img" "$OUT/boot.check.img" || die "two reads of boot differ"
rm "$OUT/boot.check.img"
(cd "$OUT" && shasum -a 256 *.img > SHA256SUMS)
echo "backup complete:"; cat "$OUT/SHA256SUMS"

say "5/8 Patching boot (Magisk + S1 helper + this computer's adb key)"
"$HERE/../root/patch-boot-magisk.sh" "$OUT/boot.img" "$OUT/s1-boot.img" | tee "$OUT/patch.log" | tail -4
if grep -q "Magisk patched boot image detected" "$OUT/patch.log"; then
  echo "NOTE: this panel's boot was already Magisk-patched, so boot.img in this backup is NOT stock."
  echo "      Keep the stock boot.img from your first install for a full undo."
fi

say "6/8 vbmeta"
flags=$(python3 -c 'import sys,struct; d=open(sys.argv[1],"rb").read(); assert d[:4]==b"AVB0"; print(struct.unpack_from(">I",d,120)[0])' "$OUT/vbmeta.img")
echo "current vbmeta flags: $flags"
if [ "$flags" != 3 ]; then
  python3 -c 'import sys,struct; d=bytearray(open(sys.argv[1],"rb").read()); struct.pack_into(">I",d,120,struct.unpack_from(">I",d,120)[0]|3); open(sys.argv[2],"wb").write(d)' \
    "$OUT/vbmeta.img" "$OUT/vbmeta.flags3.img"
  "$FB" flash vbmeta "$OUT/vbmeta.flags3.img"
  "$HERE/fb-read-partition.sh" vbmeta "$OUT/vbmeta.readback.img" >/dev/null
  cmp -s "$OUT/vbmeta.flags3.img" "$OUT/vbmeta.readback.img" || die "vbmeta read-back mismatch"
  echo "vbmeta flags set to 3 and verified"
else
  echo "already 3: unchanged"
fi

say "7/8 Flashing boot"
"$FB" flash boot "$OUT/s1-boot.img"
"$HERE/fb-read-partition.sh" boot "$OUT/boot.readback.img" >/dev/null
cmp -s "$OUT/s1-boot.img" "$OUT/boot.readback.img" || die "boot read-back mismatch: do NOT reboot; re-run, or flash $OUT/boot.img to undo"
rm -f "$OUT/boot.readback.img" "$OUT/vbmeta.readback.img"
echo "boot flashed and verified"
"$FB" reboot

say "8/8 Waiting for Android and adb (first boot can take a few minutes)"
for i in $(seq 1 90); do
  st=$("$ADB" get-state 2>/dev/null || true)
  [ "$st" = device ] && "$ADB" shell true 2>/dev/null && break
  sleep 4
done
"$ADB" shell 'echo "android up: $(getprop ro.build.version.release), boot_completed=$(getprop sys.boot_completed)"' ||
  die "adb not authorized yet: check the panel screen for a USB debugging prompt"
echo
echo "Done. Next: tools/install.sh --url http://<home-assistant>:8123/<dashboard>"
echo "Backup: $OUT (keep it: it is your undo)"
