#!/usr/bin/env bash
# Read one eMMC partition to a host file, from our FEL-booted U-Boot in fastboot mode.
# Uses 'oem run' + the 'upload' command added by tools/fel/uboot/patches/0002 ("fastboot get_staged").
# Read-only: nothing is written to the panel.
#
#   tools/install/fb-read-partition.sh <partition> <out-file>
#
# Partitions up to the 512 MiB fastboot buffer (CONFIG_FASTBOOT_BUF_ADDR/SIZE in the defconfig).
set -euo pipefail
PART=${1:?partition name, e.g. boot}; OUT=${2:?output file}
FB=${FASTBOOT:-fastboot}
unset ANDROID_SERIAL   # fastboot honours it, and our U-Boot's serial is not the adb one
BUF=0x50000000                       # CONFIG_FASTBOOT_BUF_ADDR
MMC=1                                # eMMC on the S1 Plus

oem() { "$FB" oem run:"$1" >/dev/null 2>&1 || { echo "oem run failed: $1" >&2; exit 1; }; }
# Each 'oem run' command must fit fastboot's 64-byte command limit, hence the short env names.
oem "mmc dev $MMC"
oem "part start mmc $MMC $PART s"
oem "part size mmc $MMC $PART n"
oem "mmc read $BUF \$s \$n"
oem "setexpr fastboot_upload_size \$n * 200"
"$FB" get_staged "$OUT" 2>&1 | grep -v '^Finished' || true
[ -s "$OUT" ] || { echo "read failed: $PART" >&2; exit 1; }
echo "$PART -> $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
