#!/bin/bash
# Boot mainline U-Boot on the S1 Plus from FEL, RAM only (nothing is written to eMMC).
#
# Why not `sunxi-fel uboot`: our SPL keeps the CPU at 408 MHz (there is no PMIC driver to raise
# VDD-CPU), and the boot ROM's CPU-driven USB is then too slow for sunxi-fel's large bulk
# transfers, which hit its 10 s USB timeout. 64 KiB writes always complete, so upload in chunks
# and trigger the AArch64 warm reset ourselves.
#
# Usage: tools/fel/fel-boot.sh [build-dir]   (default ~/src/u-boot-s1/build-s1)
set -euo pipefail
FEL=${SUNXI_FEL:-sunxi-fel}
B=${1:-$HOME/src/u-boot-s1/build-s1}
SPL=$B/u-boot-sunxi-with-spl.bin
BL31=${BL31:-$B/../bl31-s1.bin}
UBOOT=$B/u-boot.bin            # u-boot-nodtb.bin + appended DTB; BL31 finds the DTB at its end
BL31_ADDR=0x40000000           # TF-A sun50i_a133 runs from DRAM (SUNXI_BL31_IN_DRAM)
UBOOT_ADDR=0x4a000000          # CONFIG_TEXT_BASE, TF-A PRELOADED_BL33_BASE
CHUNK=65536

W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
$FEL ver
$FEL spl "$SPL"
sleep 1

args=()
add_chunks() { # file addr
  local f=$1 base=$2 size n i
  size=$(stat -f %z "$f" 2>/dev/null || stat -c %s "$f")
  n=$(( (size + CHUNK - 1) / CHUNK ))
  for ((i = 0; i < n; i++)); do
    dd if="$f" of="$W/$(basename "$f").$i" bs=$CHUNK skip=$i count=1 2>/dev/null
    args+=(write "$(printf '0x%08x' $((base + i * CHUNK)))" "$W/$(basename "$f").$i")
  done
}
ENTRY=$BL31_ADDR
if [ "${NO_BL31:-0}" = 1 ]; then
  # Enter U-Boot proper directly at EL3 (no TF-A, so no PSCI). Enough for fastboot / eMMC work.
  ENTRY=$UBOOT_ADDR
else
  add_chunks "$BL31" $BL31_ADDR
fi
add_chunks "$UBOOT" $UBOOT_ADDR
echo "uploading $(( ${#args[@]} / 3 )) x 64 KiB chunks, then reset64 $ENTRY"
$FEL "${args[@]}" reset64 $ENTRY
echo "started; watch the UART console"
