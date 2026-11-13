// Allwinner A133 constants for FEL, from sunxi-tools soc_info.c (A133 entry and
// a133_sram_swap_buffers) and our own U-Boot build (tools/fel/fel-boot.sh, frames/aqara-s1-plus/research/fel.md).

export interface SwapBuffer { buf1: number; buf2: number; size: number }

export const A133 = {
  socId: 0x1855,
  splAddr: 0x20000,
  scratchAddr: 0x21000,
  thunkAddr: 0x40400,
  thunkSize: 0x200,
  sramSize: 148 * 1024,
  swapBuffers: [{ buf1: 0x21000, buf2: 0x40000, size: 0x400 }] as readonly SwapBuffer[],
  rvbarReg: 0x08100040,
  sidBase: 0x03006000,
  /** Secure-boot status word in the SID. Non-zero: the boot ROM only runs signed (TOC0) code. */
  sidSecureBootReg: 0x030060a0,
  /** CONFIG_TEXT_BASE of our U-Boot; with NO_BL31 it is also the AArch64 entry point. */
  ubootAddr: 0x4a000000,
} as const;

/** 64 KiB per FEL write: at the SPL's 408 MHz the boot ROM's USB times out on larger ones. */
export const FEL_CHUNK = 64 * 1024;
