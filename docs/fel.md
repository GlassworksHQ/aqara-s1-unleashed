# FEL boot: mainline U-Boot from USB, RAM only

**Status (2026-11-09): working.** Mainline U-Boot 2027.01-rc1 boots on the S1 Plus from FEL to a
`=>` prompt with full eMMC access. It is loaded over USB and runs from RAM, so nothing is written
to the panel. This is the base for a UART-free installer (fastboot is next).

```
U-Boot 2027.01-rc1 ... Allwinner Technology
CPU:   Allwinner A133 (SUN50I)
Model: Aqara Panel Hub S1 Plus
DRAM:  2 GiB
Core:  42 devices, 17 uclasses, devicetree: separate
MMC:   mmc@4022000: 1        (8-bit, 52 MHz, 7.3 GiB, GPT with the Android partitions)
=>
```

## Usage

1. Build: `UBOOT_SRC=~/src/u-boot BL31=<bl31.bin> tools/fel/build-uboot.sh`.
   This applies `tools/fel/uboot/patches/*.patch` once.
2. Enter FEL: hold the PCB button while plugging in USB-C, keep holding for about 10 s. The screen
   stays black.
3. Boot: `NO_BL31=1 SUNXI_FEL=sunxi-fel tools/fel/fel-boot.sh ~/src/u-boot/build-s1`.
   Watch the console on the debug UART at 115200 8N1.
4. Unplug and replug *without* the button to get back to Android.

## What had to be fixed

**1. DRAM controller setup, the real bug: `patches/0001-…`.**
- **Symptom.** Mainline's A133 DDR4 driver brings up 2 GiB that passes every write-then-read test.
  Real code still crashes at random:
  - TF-A hits "undefined instruction" faults on valid instructions;
  - U-Boot hangs in `memcpy`;
  - crash dumps come out garbled.
- **Where it showed up.** A copy loop (load one address, store another) corrupted about 300 64-bit
  words per MiB. So the failure is on read→write bus turnarounds.
- **How it was found.** The controller registers were diffed two ways:
  - as programmed by our SPL, read over FEL;
  - as programmed by stock boot0, read from Android via `/sys/class/sunxi_dump/dump`.
- **The differences:**

| Register | mainline | stock boot0 | |
|---|---|---|---|
| MR0 (`INIT3` + MRS) | `0x840` | `0x520` | CAS latency / WR. The controller timings (`DRAMTMG2` etc.) match stock, so the RAM ran a different CL than the controller assumed. |
| `MSTR` bit 0 | geardown on | off | The RAM's MR3 has geardown off |
| `INIT7` (MR6) | `0x826` | `0x862` | Typo; the MRS write already used `0x862` |
| `DRAMTMG6` | `0x00000005` | `0x02020005` | `0x20 << 16` was truncated to 0 |
| `ADDRMAP1` BA2, `ADDRMAP8` BG1 | `0x1f` | `0x3f` | "Disabled" in the 6-bit fields |
| `SCHED[3:0]` | `0x1` | `0x5` | |

  With all of these matched to stock, everything boots.

**2. CPU clock.** The board's PMIC (AXP806 on R_I2C) has no SPL driver, so `SUNXI_NO_PMIC`
applies. Mainline would then clock the CPU to 1008 MHz at the power-on core voltage. The defconfig
pins `SYS_CLK_FREQ=408000000` instead.

**3. USB uploads.** At 408 MHz the boot ROM's CPU-driven USB is too slow for the large bulk
transfers `sunxi-fel uboot` uses, and they hit its 10 s timeout. `fel-boot.sh` uploads in 64 KiB
chunks and starts the CPU itself with `reset64`.

**4. No TF-A for now.** U-Boot runs directly at EL3. That is enough for fastboot and eMMC work;
PSCI is only needed to boot Linux SMP. The BL31 crashes were the DRAM bug above, so TF-A should
work again with patch 0001. That is untested.

## Verified (all from FEL, RAM only)

- DRAM:
  - Full 2 GiB, no aliasing; holds data for 60 s.
  - 32/64/128-bit accesses and 8-word bursts.
  - 860 M instructions executed from DRAM.
  - The copy-pattern test fails without patch 0001.
- U-Boot reaches the prompt; `mmc info` and `mmc part` work (read-only).

## Next
- Enable USB fastboot (MUSB gadget) for flashing from a host, with no UART.
- Retest TF-A BL31 with the DRAM fix, then upstream the fix to the U-Boot list. It probably
  affects every A133 DDR4 board.
- Use `env` = nowhere so `saveenv` can't touch the panel's Android `env` partition. U-Boot currently
  looks for the environment on FAT on mmc 0, which doesn't exist; that lookup is harmless.
