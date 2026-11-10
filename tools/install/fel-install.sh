#!/usr/bin/env bash
# End-to-end install on an Aqara Panel Hub S1 Plus (factory or already installed), with only the PCB
# FEL button and a USB-C cable: no UART, no adb needed beforehand.
#
#   tools/install/fel-install.sh [backup-dir]
#   tools/install/fel-install.sh --undo <backup-dir>    restore that backup's stock boot + vbmeta
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
# Undo: --undo <backup-dir> (FEL again, then boot + vbmeta from the backup, verified by read-back).
#       It refuses a backup whose boot is already Magisk-patched (a re-run's backup): use the one
#       from the first install. /data is left alone (the kiosk app keeps working without root).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
FEL=${SUNXI_FEL:-sunxi-fel}; FB=${FASTBOOT:-fastboot}; ADB=${ADB:-adb}
# fastboot also honours ANDROID_SERIAL, but our U-Boot's fastboot serial differs from the panel's adb
# serial: keep it for adb only, or fastboot waits forever for a device that never appears.
ADB_SERIAL=${ANDROID_SERIAL:-}; unset ANDROID_SERIAL
adb_() { "$ADB" ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
UBUILD=${UBOOT_BUILD:-$HOME/src/u-boot-s1/build-s1}
MODE=install
if [ "${1:-}" = --undo ]; then MODE=undo; UNDO=${2:?usage: fel-install.sh --undo <backup-dir>}; fi
OUT=${1:-$PWD/s1-backup-$(date +%Y%m%d-%H%M%S)}
BACKUP_PARTS="boot vbmeta vbmeta_system vbmeta_vendor dtbo misc recovery env bootloader"

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
for t in "$FEL" "$FB" "$ADB" docker shasum python3; do command -v "$t" >/dev/null || die "missing tool: $t"; done
[ -f "$UBUILD/u-boot-sunxi-with-spl.bin" ] || die "no U-Boot build in $UBUILD (tools/fel/build-uboot.sh)"

# Is this boot image Magisk-patched? (looks for .backup/.magisk in the gzip'd ramdisk of a v0-v2 header)
is_magisk() { python3 - "$1" <<'PY'
import gzip, struct, sys
d = open(sys.argv[1], 'rb').read()
assert d[:8] == b'ANDROID!', 'not an Android boot image'
ks, _, rs = struct.unpack_from('<III', d, 8); ps = struct.unpack_from('<I', d, 36)[0]
off = ps + (ks + ps - 1) // ps * ps
sys.exit(0 if b'.backup/.magisk' in gzip.decompress(d[off:off + rs]) else 1)
PY
}

if [ "$MODE" = undo ]; then
  [ -f "$UNDO/SHA256SUMS" ] && [ -f "$UNDO/boot.img" ] && [ -f "$UNDO/vbmeta.img" ] || die "$UNDO is not a fel-install backup"
  for f in boot.img vbmeta.img; do
    (cd "$UNDO" && grep " $f\$" SHA256SUMS | shasum -a 256 -c -) || die "$UNDO/$f does not match its SHA256SUMS"
  done
  ! is_magisk "$UNDO/boot.img" || die "$UNDO/boot.img is Magisk-patched (a re-run backup); use the first install's backup"
else
  mkdir -p "$OUT"; chmod 700 "$OUT"
fi

if "$FB" devices | grep -q fastboot && [ "$("$FB" getvar product 2>&1 | sed -n 's/^product: //p')" = sunxi ]; then
  say "1-2/8 Our U-Boot is already in fastboot: skipping FEL"
else
  say "1/8 Waiting for FEL: hold the PCB button (docs/img/fel-button.jpg), plug in USB-C, keep holding ~10 s"
  for i in $(seq 1 120); do v=$("$FEL" ver 2>/dev/null) && break; sleep 1; done
  [ -n "${v:-}" ] || die "no FEL device found"
  echo "$v"
  case "$v" in *soc=00001855*) ;; *) die "not an A133 (expected soc=00001855)";; esac
  # Secure-boot status word in the SID (A133: SID 0x03006000 + 0xa0, as sunxi-fel's soc_info uses).
  # Non-zero means the boot ROM only runs signed (TOC0) code: our unsigned SPL would be refused.
  sb=$("$FEL" readl 0x030060a0 2>/dev/null) || die "could not read the secure-boot status from FEL"
  echo "secure boot status: $sb"
  [ "$((sb))" -eq 0 ] || die "secure boot is ENABLED on this chip (SID+0xa0=$sb): unsigned code can't run, so this installer can't work"

  say "2/8 Booting our U-Boot from RAM (nothing is written)"
  NO_BL31=1 SUNXI_FEL="$FEL" "$HERE/../fel/fel-boot.sh" "$UBUILD"
  for i in $(seq 1 60); do "$FB" devices | grep -q fastboot && break; sleep 1; done
fi
"$FB" devices | grep -q fastboot || die "fastboot did not come up"

say "3/8 Checking the partition layout"
gv() { "$FB" getvar "$1" 2>&1 | sed -n "s/^$1: //p"; }
[ "$(gv product)" = sunxi ] || die "unexpected bootloader product"
[ "$(gv partition-size:boot)" = 0x0000000002000000 ] || die "boot is not 32 MiB: not an S1 Plus layout?"
[ "$(gv partition-size:super)" = 0x0000000060000000 ] || die "super is not 1.5 GiB: not an S1 Plus layout?"

if [ "$MODE" = undo ]; then
  say "Undo: restoring stock boot and vbmeta from $UNDO"
  for p in boot vbmeta; do
    "$FB" flash "$p" "$UNDO/$p.img"
    "$HERE/fb-read-partition.sh" "$p" "$UNDO/$p.readback.img" >/dev/null
    cmp -s "$UNDO/$p.img" "$UNDO/$p.readback.img" || die "$p read-back mismatch after restore: do NOT reboot, re-run --undo"
    rm -f "$UNDO/$p.readback.img"; echo "$p restored and verified"
  done
  "$FB" reboot
  echo; echo "Undone: stock boot and vbmeta are back. /data is untouched (kiosk app, settings)."
  exit 0
fi

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
  st=$(adb_ get-state 2>/dev/null || true)
  [ "$st" = device ] && adb_ shell true 2>/dev/null && break
  sleep 4
done
adb_ shell 'echo "android up: $(getprop ro.build.version.release), boot_completed=$(getprop sys.boot_completed)"' ||
  die "adb not authorized yet: check the panel screen for a USB debugging prompt"
echo
echo "Done. Next: tools/install.sh --url http://<home-assistant>:8123/<dashboard>"
echo "Backup: $OUT (keep it: it is your undo)"
