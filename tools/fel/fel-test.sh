#!/usr/bin/env bash
# RAM-only FEL test: run our SPL (DRAM init) and then U-Boot from USB. Writes nothing to eMMC.
# A power cycle (unplug/replug USB-C) returns the panel to normal Android.
#
# 1. Enter FEL: hold the unlabeled PCB button while plugging in USB-C (~10 s). Screen stays black.
# 2. SUNXI_FEL=~/src/sunxi-tools/sunxi-fel UBOOT_BIN=.../u-boot-sunxi-with-spl.bin tools/fel/fel-test.sh
#
# Watch the UART console (115200 8N1) at the same time to see SPL/U-Boot output.
set -euo pipefail
FEL="${SUNXI_FEL:-sunxi-fel}"
BIN="${UBOOT_BIN:?set UBOOT_BIN to u-boot-sunxi-with-spl.bin}"

echo "== FEL device"
"$FEL" -l || { echo "No FEL device. Hold the PCB button while plugging in USB-C."; exit 1; }
"$FEL" ver

echo "== Stage 1: SPL only (DRAM init), returns to FEL"
"$FEL" -v spl "$BIN"
sleep 1
"$FEL" ver   # still in FEL after SPL -> SPL returned cleanly

echo "== Stage 2: full U-Boot (SPL + ATF + U-Boot), from RAM"
"$FEL" -v uboot "$BIN"
echo "U-Boot started. Use the UART console; power-cycle to return to Android."
