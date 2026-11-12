// Pure parts of the FEL protocol (no USB): byte layouts and the ARM thunks sunxi-fel runs on the
// chip. Each is a port of sunxi-tools (fel_lib.c, fel.c, thunks/fel-to-spl-thunk.h); the comments
// say where from, so they can be checked word for word.
import type { SwapBuffer } from './a133';

export const AW_USB_READ = 0x11;
export const AW_USB_WRITE = 0x12;
export const AW_FEL_VERSION = 0x001;
export const AW_FEL_1_WRITE = 0x101;
export const AW_FEL_1_EXEC = 0x102;
export const AW_FEL_1_READ = 0x103;

/** struct aw_usb_request (packed, 32 bytes): "AWUC", len, 0x0c000000, u16 request, len, pad. */
export function usbRequest(type: number, length: number): Uint8Array {
  const b = new Uint8Array(32);
  const v = new DataView(b.buffer);
  b.set([0x41, 0x57, 0x55, 0x43]); // "AWUC", NUL-padded to 8
  v.setUint32(8, length >>> 0, true);
  v.setUint32(12, 0x0c000000, true);
  v.setUint16(16, type, true);
  v.setUint32(18, length >>> 0, true); // packed: right after the u16
  return b;
}

/** struct aw_fel_request (16 bytes): request, address, length, pad. */
export function felRequest(type: number, addr: number, length: number): Uint8Array {
  const b = new Uint8Array(16);
  const v = new DataView(b.buffer);
  v.setUint32(0, type, true);
  v.setUint32(4, addr >>> 0, true);
  v.setUint32(8, length >>> 0, true);
  return b;
}

export function isUsbStatus(b: Uint8Array): boolean {
  return b.length >= 4 && b[0] === 0x41 && b[1] === 0x57 && b[2] === 0x55 && b[3] === 0x53; // "AWUS"
}

export interface FelVersion { signature: string; socId: number; protocol: number; scratchpad: number; raw: Uint8Array }

/** struct aw_fel_version (32 bytes, soc_info.h); soc_id = (le32 >> 8) & 0xffff. */
export function parseVersion(raw: Uint8Array): FelVersion {
  if (raw.length < 32) throw new Error(`FEL version reply too short (${raw.length} bytes)`);
  const v = new DataView(raw.buffer, raw.byteOffset, raw.byteLength);
  return {
    signature: String.fromCharCode(...raw.subarray(0, 8)).replace(/\0+$/, ''),
    socId: (v.getUint32(8, true) >>> 8) & 0xffff,
    protocol: v.getUint16(16, true),
    scratchpad: v.getUint32(20, true),
    raw,
  };
}

export function words(ws: readonly number[]): Uint8Array {
  const b = new Uint8Array(ws.length * 4);
  const v = new DataView(b.buffer);
  ws.forEach((w, i) => v.setUint32(i * 4, w >>> 0, true));
  return b;
}

/** eGON.BT0 header check + checksum (fel.c aw_fel_write_and_execute_spl). Returns the SPL length. */
export function checkEgon(spl: Uint8Array): number {
  if (spl.length < 32 || ascii(spl, 4, 8) !== 'eGON.BT0') throw new Error('SPL: eGON header is not found');
  const v = new DataView(spl.buffer, spl.byteOffset, spl.byteLength);
  const len = v.getUint32(16, true);
  if (len > spl.length || len % 4 !== 0) throw new Error('SPL: bad length in the eGON header');
  let sum = (2 * v.getUint32(12, true) - 0x5f0a6c39) >>> 0;
  for (let i = 0; i < len; i += 4) sum = (sum - v.getUint32(i, true)) >>> 0;
  if (sum !== 0) throw new Error('SPL: checksum check failed');
  return len;
}

export function ascii(b: Uint8Array, off: number, len: number): string {
  return String.fromCharCode(...b.subarray(off, off + len));
}

/** aw_fel_readl_n code (fel_lib.c): reads `count` words from `addr` to scratch + 48. */
export const READL_CODE_SIZE = 48;
const LCODE_MAX_WORDS = 0x100 - 12;
export function readlCode(addr: number, count: number): Uint8Array {
  if (count < 1 || count > LCODE_MAX_WORDS) throw new Error(`readl: bad count ${count}`);
  return words([
    0xe59f0020, // ldr  r0, [read_addr]
    0xe28f1024, // adr  r1, read_data
    0xe59f201c, // ldr  r2, [read_count]
    0xe3520000 + LCODE_MAX_WORDS, // cmp r2, #max
    0xc3a02000 + LCODE_MAX_WORDS, // movgt r2, #max
    0xe2522001, // read_loop: subs r2, r2, #1
    0x412fff1e, // bxmi lr
    0xe4903004, // ldr  r3, [r0], #4
    0xe4813004, // str  r3, [r1], #4
    0xeafffffa, // b    read_loop
    addr,
    count,
  ]);
}

/** aw_read_arm_cp_reg for SCTLR (mrc p15, 0, r0, c1, c0, 0); the value lands at scratch + 12. */
export const SCTLR_CODE = words([0xee110f10, 0xe58f0000, 0xe12fff1e]);
export const SCTLR_RESULT_OFFSET = 12;

/** aw_get_stackinfo: stores sp_irq, sp at scratch + 0x24. */
export const STACKINFO_CODE = words([
  0xe10f0000, 0xe3c0101f, 0xe3811012, 0xe121f001, 0xe1a0100d,
  0xe121f000, 0xe58f1004, 0xe58fd004, 0xe12fff1e,
]);
export const STACKINFO_RESULT_OFFSET = 0x24;

/** thunks/fel-to-spl-thunk.h: swap buffers, verify the eGON checksum, run the SPL, swap back. */
export const FEL_TO_SPL_THUNK: readonly number[] = [
  0xea000015, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff,
  0xffffffff, 0xe1a00000, 0xe28f40e8, 0xe4940004, 0xe4941004, 0xe4946004, 0xe3560000, 0x012fff1e,
  0xe5902000, 0xe5913000, 0xe2566004, 0xe4812004, 0xe4803004, 0x1afffff9, 0xeafffff3, 0xe59f80b0,
  0xe24f0044, 0xe520d004, 0xe1a0d000, 0xe10f2000, 0xe92d4004, 0xe38220c0, 0xe121f002, 0xee112f10,
  0xe3120004, 0x03120a01, 0x1a000013, 0xebffffe5, 0xe59f706c, 0xe1a00008, 0xe5905010, 0xe4902004,
  0xe2555004, 0xe0877002, 0x1afffffb, 0xe598200c, 0xe0577082, 0x1a00000b, 0xe59f2048, 0xe5882008,
  0xee072f9a, 0xee102f10, 0xe202280f, 0xe3520806, 0xce072f95, 0xe12fff38, 0xea000004, 0xe59f2028,
  0xe5882008, 0xea000002, 0xe59f2020, 0xe5882008, 0xebffffcc, 0xe8bd4004, 0xe121f002, 0xe59dd000,
  0xe12fff1e, 0x5f0a6c39, 0x4c45462e, 0x3f3f3f2e, 0x4441422e,
];

/** Thunk + spl_addr + the swap table including its zero terminator (fel.c). */
export function splThunk(splAddr: number, swaps: readonly SwapBuffer[]): Uint8Array {
  const table = swaps.flatMap((s) => [s.buf1, s.buf2, s.size]);
  return words([...FEL_TO_SPL_THUNK, splAddr, ...table, 0, 0, 0]);
}

/** aw_rmr_request: store the entry in RVBAR, request a warm reset (RR, AA64 when aarch64), wfi. */
export function rmrCode(rvbarReg: number, entry: number, aarch64: boolean): Uint8Array {
  return words([
    0xe59f0028, // ldr r0, [rvbar_reg]
    0xe59f1028, // ldr r1, [entry_point]
    0xe5801000, // str r1, [r0]
    0xf57ff04f, // dsb sy
    0xf57ff06f, // isb sy
    0xe59f101c, // ldr r1, [rmr_mode]
    0xee1c0f50, // mrc 15, 0, r0, cr12, cr0, {2}
    0xe1800001, // orr r0, r0, r1
    0xee0c0f50, // mcr 15, 0, r0, cr12, cr0, {2}
    0xf57ff06f, // isb sy
    0xe320f003, // loop: wfi
    0xeafffffd, // b loop
    rvbarReg,
    entry,
    (1 << 1) | (aarch64 ? 1 : 0),
  ]);
}

/**
 * The SPL writes in the order sunxi-fel uses: the part before each swap buffer goes to its real
 * address, the part that would land in buf1 (the boot ROM's own scratch) goes to buf2 instead, and
 * the thunk swaps it in before running the SPL. Also returns the size limit sunxi-fel enforces.
 */
export function splWritePlan(
  splAddr: number, splLen: number, sramSize: number, thunkAddr: number, swaps: readonly SwapBuffer[],
): { writes: { addr: number; offset: number; length: number }[]; limit: number } {
  const writes: { addr: number; offset: number; length: number }[] = [];
  let limit = sramSize;
  let cur = splAddr;
  let off = 0;
  let left = splLen;
  for (const s of swaps) {
    if (s.buf2 >= splAddr && s.buf2 < splAddr + limit) limit = s.buf2 - splAddr;
    if (left > 0 && cur < s.buf1) {
      const n = Math.min(s.buf1 - cur, left);
      writes.push({ addr: cur, offset: off, length: n });
      cur += n; off += n; left -= n;
    }
    if (left > 0 && cur === s.buf1) {
      const n = Math.min(s.size, left);
      writes.push({ addr: s.buf2, offset: off, length: n });
      cur += n; off += n; left -= n;
    }
  }
  if (thunkAddr - splAddr < limit) limit = thunkAddr - splAddr;
  if (left > 0) writes.push({ addr: cur, offset: off, length: left });
  return { writes, limit };
}
