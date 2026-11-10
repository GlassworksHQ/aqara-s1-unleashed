// FEL over WebUSB: the subset of sunxi-fel that tools/fel/fel-boot.sh and fel-install.sh use.
import { A133, FEL_CHUNK } from './a133';
import {
  AW_FEL_1_EXEC, AW_FEL_1_READ, AW_FEL_1_WRITE, AW_FEL_VERSION, AW_USB_READ, AW_USB_WRITE,
  READL_CODE_SIZE, SCTLR_CODE, SCTLR_RESULT_OFFSET, STACKINFO_CODE, STACKINFO_RESULT_OFFSET,
  ascii, checkEgon, felRequest, isUsbStatus, usbRequest, parseVersion, readlCode, rmrCode, splThunk, splWritePlan,
  type FelVersion,
} from './protocol';
import { WebUsbBulk, type BulkTransport } from './transport';

export const FEL_FILTER: USBDeviceFilter = { vendorId: 0x1f3a, productId: 0xefe8 };

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export class FelDevice {
  /** Called with one line per step, like sunxi-fel's -v output. */
  log: (line: string) => void = () => {};

  constructor(private readonly t: BulkTransport) {}

  /** Asks the browser for the panel in FEL mode (needs a user gesture). */
  static async request(): Promise<FelDevice> {
    return FelDevice.fromDevice(await navigator.usb.requestDevice({ filters: [FEL_FILTER] }));
  }

  static async fromDevice(d: USBDevice): Promise<FelDevice> {
    return new FelDevice(await WebUsbBulk.open(d));
  }

  /** Already-permitted FEL devices (no prompt), for reconnecting. */
  static async known(): Promise<USBDevice[]> {
    return (await navigator.usb.getDevices()).filter(
      (d) => d.vendorId === FEL_FILTER.vendorId && d.productId === FEL_FILTER.productId,
    );
  }

  // --- AWUC/AWUS framing (fel_lib.c aw_usb_write / aw_usb_read) ---
  private async usbWrite(data: Uint8Array): Promise<void> {
    await this.t.out(usbRequest(AW_USB_WRITE, data.length));
    await this.t.out(data);
    await this.usbStatus();
  }

  private async usbRead(length: number): Promise<Uint8Array> {
    await this.t.out(usbRequest(AW_USB_READ, length));
    const data = await this.t.in(length);
    await this.usbStatus();
    return data;
  }

  private async usbStatus(): Promise<void> {
    const s = await this.t.in(13);
    if (!isUsbStatus(s)) throw new Error('FEL: bad USB status (expected AWUS)');
  }

  private async felRequest(type: number, addr: number, length: number): Promise<void> {
    await this.usbWrite(felRequest(type, addr, length));
  }

  private async felStatus(): Promise<void> {
    await this.usbRead(8);
  }

  // --- FEL requests ---
  async version(): Promise<FelVersion> {
    await this.felRequest(AW_FEL_VERSION, 0, 0);
    const raw = await this.usbRead(32);
    await this.felStatus();
    return parseVersion(raw);
  }

  async read(addr: number, len: number): Promise<Uint8Array> {
    await this.felRequest(AW_FEL_1_READ, addr, len);
    const data = await this.usbRead(len);
    await this.felStatus();
    return data;
  }

  /** One FEL write per 64 KiB (see FEL_CHUNK). */
  async write(addr: number, data: Uint8Array, onProgress?: (done: number, total: number) => void): Promise<void> {
    for (let off = 0; off < data.length; off += FEL_CHUNK) {
      const chunk = data.subarray(off, Math.min(off + FEL_CHUNK, data.length));
      await this.felRequest(AW_FEL_1_WRITE, addr + off, chunk.length);
      await this.usbWrite(chunk);
      await this.felStatus();
      onProgress?.(off + chunk.length, data.length);
    }
  }

  async exec(addr: number): Promise<void> {
    await this.felRequest(AW_FEL_1_EXEC, addr, 0);
    await this.felStatus();
  }

  /** Reads a 32-bit register by running sunxi-fel's readl code in the scratch area. */
  async readl(addr: number): Promise<number> {
    await this.write(A133.scratchAddr, readlCode(addr, 1));
    await this.exec(A133.scratchAddr);
    const b = await this.read(A133.scratchAddr + READL_CODE_SIZE, 4);
    return new DataView(b.buffer, b.byteOffset).getUint32(0, true);
  }

  async sctlr(): Promise<number> {
    await this.write(A133.scratchAddr, SCTLR_CODE);
    await this.exec(A133.scratchAddr);
    const b = await this.read(A133.scratchAddr + SCTLR_RESULT_OFFSET, 4);
    return new DataView(b.buffer, b.byteOffset).getUint32(0, true);
  }

  async stackInfo(): Promise<{ spIrq: number; sp: number }> {
    await this.write(A133.scratchAddr, STACKINFO_CODE);
    await this.exec(A133.scratchAddr);
    const b = await this.read(A133.scratchAddr + STACKINFO_RESULT_OFFSET, 8);
    const v = new DataView(b.buffer, b.byteOffset);
    return { spIrq: v.getUint32(0, true), sp: v.getUint32(4, true) };
  }

  /**
   * sunxi-fel `spl`: loads the SPL (u-boot-sunxi-with-spl.bin; only the eGON part is used), runs it
   * through the fel-to-spl thunk and checks it returned to FEL ("eGON.FEL"). DRAM is up afterwards.
   */
  async runSpl(splWithUboot: Uint8Array): Promise<void> {
    const len = checkEgon(splWithUboot);
    const sp = await this.stackInfo();
    this.log(`stack pointers: sp_irq=0x${hex(sp.spIrq)}, sp=0x${hex(sp.sp)}`);
    const sctlr = await this.sctlr();
    if ((sctlr & ~((0x3 << 22) | (0x7 << 11) | (1 << 6) | 1)) >>> 0 !== 0x00050038) {
      throw new Error(`unexpected SCTLR 0x${hex(sctlr)} (sunxi-fel would stop here too)`);
    }
    // The A133 boot ROM leaves the MMU off; sunxi-fel's MMU table backup is not ported.
    if (sctlr & 1) throw new Error('the boot ROM enabled the MMU: not expected on an A133');
    const plan = splWritePlan(A133.splAddr, len, A133.sramSize, A133.thunkAddr, A133.swapBuffers);
    if (len > plan.limit) throw new Error(`SPL: too large (need ${len}, have ${plan.limit})`);
    for (const w of plan.writes) await this.write(w.addr, splWithUboot.subarray(w.offset, w.offset + w.length));
    const thunk = splThunk(A133.splAddr, A133.swapBuffers);
    if (thunk.length > A133.thunkSize) throw new Error('SPL: bad thunk size');
    this.log('executing the SPL');
    await this.write(A133.thunkAddr, thunk);
    await this.exec(A133.thunkAddr);
    await sleep(250); // sunxi-fel has the same workaround
    const sig = ascii(await this.read(A133.splAddr + 4, 8), 0, 8);
    if (sig !== 'eGON.FEL') throw new Error(`SPL: failure code '${sig}'`);
  }

  /** sunxi-fel `reset64`: RVBAR = entry, RMR warm reset into AArch64. The device leaves FEL. */
  async reset64(entry: number): Promise<void> {
    this.log(`store entry point 0x${hex(entry)} to RVBAR 0x${hex(A133.rvbarReg)}, warm reset (RMR 3)`);
    await this.write(A133.scratchAddr, rmrCode(A133.rvbarReg, entry, true));
    // The chip warm-resets out of FEL right away; its status reply may never arrive (or USB drops
    // first). Once EXEC is sent the reset is happening, so a missing reply is not an error.
    await this.felRequest(AW_FEL_1_EXEC, A133.scratchAddr, 0);
    try { await this.felStatus(); } catch (e) { this.log(`reset: no status reply (${(e as Error).message}); the panel is restarting`); }
  }

  async close(): Promise<void> {
    await this.t.close();
  }
}

export function hex(n: number): string {
  return (n >>> 0).toString(16).toUpperCase().padStart(8, '0');
}
