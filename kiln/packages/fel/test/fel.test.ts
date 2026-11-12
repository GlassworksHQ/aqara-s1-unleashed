import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { describe, expect, it } from 'vitest';
import { A133, FEL_CHUNK, FelDevice, bootUboot } from '../src';
import { FakeBrom, eq, makeSpl, u32 } from './fakes';
import { AW_FEL_1_WRITE, AW_USB_WRITE, checkEgon, felRequest, splThunk, splWritePlan, usbRequest } from '../src/protocol';


describe('FEL byte layouts', () => {
  it('AWUC request is the packed 32-byte struct', () => {
    const b = usbRequest(AW_USB_WRITE, 0x10000);
    expect(Array.from(b.subarray(0, 24))).toEqual([
      0x41, 0x57, 0x55, 0x43, 0, 0, 0, 0, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x0c,
      0x12, 0x00, 0x00, 0x00, 0x01, 0x00, 0, 0,
    ]);
    expect(b.length).toBe(32);
  });
  it('FEL request is request, address, length, pad', () => {
    expect(Array.from(felRequest(AW_FEL_1_WRITE, 0x4a000000, 0x400))).toEqual([
      0x01, 0x01, 0, 0, 0, 0, 0, 0x4a, 0, 0x04, 0, 0, 0, 0, 0, 0,
    ]);
  });
  it('eGON checksum: accepts a good SPL and rejects a flipped byte', () => {
    const spl = makeSpl(0x8000, 0x1000);
    expect(checkEgon(spl)).toBe(0x8000);
    spl[100]! ^= 1;
    expect(() => checkEgon(spl)).toThrow(/checksum/);
  });
  it('SPL write plan routes the BROM scratch window to buf2 (as sunxi-fel)', () => {
    const { writes, limit } = splWritePlan(A133.splAddr, 0x9000, A133.sramSize, A133.thunkAddr, A133.swapBuffers);
    expect(writes).toEqual([
      { addr: 0x20000, offset: 0, length: 0x1000 },
      { addr: 0x40000, offset: 0x1000, length: 0x400 },
      { addr: 0x21400, offset: 0x1400, length: 0x7c00 },
    ]);
    expect(limit).toBe(0x20000); // buf2 0x40000 - spl 0x20000
  });
  it('the thunk is 69 words + spl_addr + one swap entry + terminator', () => {
    const t = splThunk(A133.splAddr, A133.swapBuffers);
    expect(t.length).toBe((69 + 1 + 6) * 4);
    expect(t.length).toBeLessThanOrEqual(A133.thunkSize);
    expect(u32(t, 69 * 4)).toBe(0x20000);
    expect(u32(t, 70 * 4)).toBe(0x21000);
    expect(u32(t, 73 * 4)).toBe(0);
  });
});

describe('FelDevice against a simulated boot ROM', () => {
  it('reads the version, a register, and round-trips memory in 64 KiB writes', async () => {
    const brom = new FakeBrom();
    const fel = new FelDevice(brom);
    const v = await fel.version();
    expect(v.socId).toBe(0x1855);
    expect(v.signature).toBe('AWUSBFEX');
    brom.regs.set(0x03006004, 0x12345678);
    expect(await fel.readl(0x03006004)).toBe(0x12345678);
    const data = new Uint8Array(FEL_CHUNK * 2 + 100).map((_, i) => i & 0xff);
    await fel.write(0x50000000, data);
    expect(brom.felWrites.map((w) => w.len).slice(-3)).toEqual([FEL_CHUNK, FEL_CHUNK, 100]);
    expect(eq(await fel.read(0x50000000, data.length), data)).toBe(true);
  });

  it('bootUboot: SPL runs with the right SRAM layout, U-Boot lands at 0x4a000000, reset64 there', async () => {
    const brom = new FakeBrom();
    const fel = new FelDevice(brom);
    const spl = makeSpl(0x9000, 0x40000); // with U-Boot appended, as u-boot-sunxi-with-spl.bin
    const uboot = new Uint8Array(300_000).map((_, i) => (i * 13) & 0xff);
    const progress: number[] = [];
    await bootUboot(fel, spl, uboot, (p) => progress.push(p));
    expect(brom.splRan && eq(brom.splRan, spl.subarray(0, 0x9000))).toBe(true);
    expect(eq(brom.load(A133.ubootAddr, uboot.length), uboot)).toBe(true);
    expect(brom.resetEntry).toBe(A133.ubootAddr);
    expect(brom.felWrites.every((w) => w.len <= FEL_CHUNK)).toBe(true);
    expect(progress.at(-1)).toBe(1);
    expect(brom.closed).toBe(true);
  });

  it('refuses a chip with secure boot enabled before running anything', async () => {
    const brom = new FakeBrom();
    brom.regs.set(A133.sidSecureBootReg, 1);
    await expect(bootUboot(new FelDevice(brom), makeSpl(0x9000), new Uint8Array(16))).rejects.toThrow(/secure boot/);
    expect(brom.splRan).toBeNull();
  });

  it('refuses when the boot ROM left the MMU on (not ported)', async () => {
    const brom = new FakeBrom();
    brom.sctlr |= 1;
    await expect(new FelDevice(brom).runSpl(makeSpl(0x9000))).rejects.toThrow(/MMU/);
  });
});

const realSpl = `${homedir()}/src/u-boot-s1/build-s1/u-boot-sunxi-with-spl.bin`;
describe.skipIf(!existsSync(realSpl))('our U-Boot build', () => {
  it('its SPL passes the eGON check and fits below the thunk', () => {
    const b = new Uint8Array(readFileSync(realSpl));
    const len = checkEgon(b);
    const { limit } = splWritePlan(A133.splAddr, len, A133.sramSize, A133.thunkAddr, A133.swapBuffers);
    expect(len).toBeLessThanOrEqual(limit);
  });
});
