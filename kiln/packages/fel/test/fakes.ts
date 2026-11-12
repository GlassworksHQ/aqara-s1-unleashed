// Simulated devices for the tests: the A133 boot ROM's FEL and our U-Boot's fastboot.
import { expect } from 'vitest';
import { A133, FEL_CHUNK, type BulkTransport } from '../src';
import {
  AW_FEL_1_EXEC, AW_FEL_1_READ, AW_FEL_1_WRITE, AW_FEL_VERSION, AW_USB_READ, AW_USB_WRITE,
  READL_CODE_SIZE, SCTLR_CODE, STACKINFO_CODE, checkEgon, readlCode, rmrCode, splThunk,
} from '../src/protocol';

export const u32 = (b: Uint8Array, o: number) => new DataView(b.buffer, b.byteOffset).getUint32(o, true);
export const eq = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((x, i) => x === b[i]);

/** A fake eGON SPL of `len` bytes with a valid checksum. */
export function makeSpl(len: number, extra = 0): Uint8Array {
  const b = new Uint8Array(len + extra);
  for (let i = 32; i < len; i++) b[i] = (i * 7 + 3) & 0xff;
  const v = new DataView(b.buffer);
  v.setUint32(0, 0xea000016, true);
  b.set(new TextEncoder().encode('eGON.BT0'), 4);
  v.setUint32(16, len, true);
  let sum = 0x5f0a6c39;
  for (let i = 0; i < len; i += 4) if (i !== 12) sum = (sum + v.getUint32(i, true)) >>> 0;
  v.setUint32(12, sum, true);
  return b;
}

/**
 * The A133 boot ROM as far as FEL goes: AWUC/AWUS framing, the four FEL requests, sparse memory,
 * and the effect of each ARM thunk the host uploads (recognised by its exact code).
 */
export class FakeBrom implements BulkTransport {
  mem = new Map<number, Uint8Array>(); // 64 KiB pages
  regs = new Map<number, number>([[A133.sidSecureBootReg, 0]]);
  sctlr = 0x00c50838; // passes sunxi-fel's mask (RES1 bits, CP15BEN, Z), MMU off
  pendingIn: Uint8Array[] = [];
  private usb: { type: number; left: number; buf: number[] } | null = null;
  private fel: { type: number; addr: number; len: number; stage: number } | null = null;
  felWrites: { addr: number; len: number }[] = [];
  splRan: Uint8Array | null = null;
  resetEntry: number | null = null;
  closed = false;

  async out(data: Uint8Array): Promise<void> {
    if (!this.usb) {
      expect(data.length).toBe(32);
      expect(String.fromCharCode(...data.subarray(0, 4))).toBe('AWUC');
      expect(u32(data, 12)).toBe(0x0c000000);
      expect(u32(data, 18)).toBe(u32(data, 8));
      const type = data[16]! | (data[17]! << 8);
      const len = u32(data, 8);
      if (type === AW_USB_READ) { this.onUsbRead(len); return; }
      expect(type).toBe(AW_USB_WRITE);
      this.usb = { type, left: len, buf: [] };
      return;
    }
    // data phase of a USB write (may arrive in pieces)
    for (const x of data) this.usb.buf.push(x);
    this.usb.left -= data.length;
    expect(this.usb.left).toBeGreaterThanOrEqual(0);
    if (this.usb.left === 0) {
      const payload = Uint8Array.from(this.usb.buf);
      this.usb = null;
      this.onUsbWrite(payload);
      this.pendingIn.push(status13());
    }
  }

  async in(length: number): Promise<Uint8Array> {
    const next = this.pendingIn.shift();
    if (!next) throw new Error('host read with nothing queued');
    expect(next.length).toBe(length);
    return next;
  }

  async inPacket(max: number): Promise<Uint8Array> {
    const next = this.pendingIn.shift();
    if (!next || next.length > max) throw new Error('unexpected packet read');
    return next;
  }

  async close() { this.closed = true; }

  private onUsbWrite(p: Uint8Array) {
    if (this.fel?.type === AW_FEL_1_WRITE && this.fel.stage === 0) {
      expect(p.length).toBe(this.fel.len);
      expect(p.length).toBeLessThanOrEqual(FEL_CHUNK);
      this.store(this.fel.addr, p);
      this.felWrites.push({ addr: this.fel.addr, len: p.length });
      this.fel.stage = 1; // FEL status next
      return;
    }
    expect(this.fel).toBeNull();
    expect(p.length).toBe(16);
    const type = u32(p, 0), addr = u32(p, 4), len = u32(p, 8);
    this.fel = { type, addr, len, stage: 0 };
    if (type === AW_FEL_1_EXEC) { this.exec(addr); this.fel.stage = 1; }
  }

  private onUsbRead(len: number) {
    const f = this.fel;
    if (!f) throw new Error('USB read with no FEL request');
    if (f.stage === 1) { // FEL status
      expect(len).toBe(8);
      this.pendingIn.push(new Uint8Array(8), status13());
      this.fel = null;
      return;
    }
    if (f.type === AW_FEL_VERSION) {
      expect(len).toBe(32);
      const v = new Uint8Array(32);
      v.set(new TextEncoder().encode('AWUSBFEX'));
      new DataView(v.buffer).setUint32(8, 0x00185500, true);
      new DataView(v.buffer).setUint16(16, 1, true);
      new DataView(v.buffer).setUint32(20, 0x7e00, true);
      this.pendingIn.push(v, status13());
    } else {
      expect(f.type).toBe(AW_FEL_1_READ);
      expect(len).toBe(f.len);
      this.pendingIn.push(this.load(f.addr, len), status13());
    }
    f.stage = 1;
  }

  private exec(addr: number) {
    const S = A133.scratchAddr;
    const code = (n: number) => this.load(addr, n);
    if (addr === S && eq(code(12), SCTLR_CODE)) {
      this.storeU32(S + 12, this.sctlr);
    } else if (addr === S && eq(code(36), STACKINFO_CODE)) {
      this.storeU32(S + 0x24, 0x2000); this.storeU32(S + 0x28, 0x7000);
    } else if (addr === S && eq(code(40), readlCode(0, 1).subarray(0, 40))) {
      const reg = u32(code(48), 40);
      expect(u32(code(48), 44)).toBe(1);
      this.storeU32(S + READL_CODE_SIZE, this.regs.get(reg) ?? 0xdeadbeef);
    } else if (addr === S && eq(code(48), rmrCode(A133.rvbarReg, 0, true).subarray(0, 48))) {
      expect(u32(code(60), 48)).toBe(A133.rvbarReg);
      expect(u32(code(60), 56)).toBe(3);
      this.resetEntry = u32(code(60), 52);
    } else if (addr === A133.thunkAddr) {
      const thunk = splThunk(A133.splAddr, A133.swapBuffers);
      expect(eq(code(thunk.length), thunk)).toBe(true);
      // the thunk swaps buf1 <-> buf2, checks the eGON checksum and runs the SPL
      for (const s of A133.swapBuffers) {
        const a = this.load(s.buf1, s.size), b = this.load(s.buf2, s.size);
        this.store(s.buf1, b); this.store(s.buf2, a);
      }
      const len = u32(this.load(A133.splAddr, 32), 16);
      const spl = this.load(A133.splAddr, len);
      checkEgon(spl); // throws if the layout in SRAM is wrong
      this.splRan = spl;
      for (const s of A133.swapBuffers) {
        const a = this.load(s.buf1, s.size), b = this.load(s.buf2, s.size);
        this.store(s.buf1, b); this.store(s.buf2, a);
      }
      this.store(A133.splAddr + 4, new TextEncoder().encode('eGON.FEL'));
    } else {
      throw new Error(`exec of unknown code at 0x${addr.toString(16)}`);
    }
  }

  store(addr: number, data: Uint8Array) {
    for (let i = 0; i < data.length; i++) {
      const a = addr + i, page = a >>> 16;
      let p = this.mem.get(page);
      if (!p) { p = new Uint8Array(0x10000); this.mem.set(page, p); }
      p[a & 0xffff] = data[i]!;
    }
  }
  storeU32(addr: number, v: number) {
    const b = new Uint8Array(4); new DataView(b.buffer).setUint32(0, v >>> 0, true); this.store(addr, b);
  }
  load(addr: number, len: number): Uint8Array {
    const out = new Uint8Array(len);
    for (let i = 0; i < len; i++) { const a = addr + i; out[i] = this.mem.get(a >>> 16)?.[a & 0xffff] ?? 0; }
    return out;
  }
}

export function status13() {
  const s = new Uint8Array(13); s.set([0x41, 0x57, 0x55, 0x53]); return s;
}


const SECTOR = 512;

/** Our U-Boot's fastboot as far as the installer uses it: getvar, oem run (a few shell commands), upload, download, flash. */
export class FakeUboot implements BulkTransport {
  /** data is allocated on first use, so a 1.5 GiB super costs nothing unless read. */
  parts = new Map<string, { start: number; size: number; data?: Uint8Array }>();
  env = new Map<string, string>();
  buf: Uint8Array = new Uint8Array(0); // the staging buffer (only what we touch)
  replies: Uint8Array[] = [];
  commands: string[] = [];
  flashed: string[] = [];
  private download: { buf: Uint8Array; got: number } | null = null;
  closed = false;
  rebooted = false;

  /** Default: small synthetic partitions. Pass [name, size | data] to model the real panel. */
  constructor(layout: [string, number | Uint8Array][] = [['boot', 0x2000000 / 64], ['vbmeta', 0x10000], ['super', 0x40000]]) {
    let lba = 0x8000;
    for (const [name, x] of layout) {
      const size = typeof x === 'number' ? x : x.length;
      const data = typeof x === 'number' ? undefined : x.slice();
      this.parts.set(name, { start: lba, size, data });
      lba += size / SECTOR;
    }
  }

  data(name: string): Uint8Array {
    const p = this.parts.get(name)!;
    p.data ??= new Uint8Array(p.size).map((_, i) => (i * 31 + name.length) & 0xff);
    return p.data;
  }

  private reply(s: string | Uint8Array) {
    this.replies.push(typeof s === 'string' ? new TextEncoder().encode(s) : s);
  }

  async out(data: Uint8Array): Promise<void> {
    if (this.download) {
      const d = this.download;
      expect(d.got + data.length).toBeLessThanOrEqual(d.buf.length);
      d.buf.set(data, d.got);
      d.got += data.length;
      if (d.got === d.buf.length) { this.buf = d.buf; this.download = null; this.reply('OKAY'); }
      return;
    }
    const cmd = new TextDecoder().decode(data);
    expect(data.length).toBeLessThanOrEqual(64);
    this.commands.push(cmd);
    if (cmd.startsWith('getvar:partition-size:')) {
      const p = this.parts.get(cmd.slice(22));
      this.reply(p ? `OKAY0x${p.size.toString(16).padStart(16, '0')}` : 'FAILunknown partition');
    } else if (cmd === 'getvar:product') {
      this.reply('OKAYsunxi');
    } else if (cmd.startsWith('oem run:')) {
      this.reply(this.run(cmd.slice(8)) ? 'OKAY' : 'FAILcommand failed');
    } else if (cmd === 'upload') {
      const n = Number.parseInt(this.env.get('fastboot_upload_size') ?? '0', 16);
      this.reply(`DATA${n.toString(16).padStart(8, '0')}`);
      for (let o = 0; o < n; o += 4096) this.reply(this.buf.slice(o, o + 4096)); // 4 KiB tx requests
      this.reply('OKAY');
    } else if (cmd.startsWith('download:')) {
      this.download = { buf: new Uint8Array(Number.parseInt(cmd.slice(9), 16)), got: 0 };
      this.reply(`DATA${cmd.slice(9)}`);
    } else if (cmd.startsWith('flash:')) {
      const name = cmd.slice(6);
      if (!this.parts.has(name)) { this.reply('FAILno such partition'); return; }
      this.reply('INFOwriting');
      this.data(name).set(this.buf, 0);
      this.flashed.push(name);
      this.reply('OKAY');
    } else if (cmd === 'reboot') {
      this.rebooted = true;
      this.reply('OKAY');
    } else {
      this.reply('FAILunknown command');
    }
  }

  /** The handful of U-Boot shell commands fb-read-partition.sh sends; hex env values like U-Boot. */
  private run(line: string): boolean {
    const sub = (s: string) => s.replace(/\$(\w+)/g, (_, k: string) => this.env.get(k) ?? '');
    const a = sub(line).split(' ');
    if (a[0] === 'mmc' && a[1] === 'dev') return a[2] === '1';
    if (a[0] === 'part' && a[2] === 'mmc' && a[3] === '1') {
      const p = this.parts.get(a[4]!);
      if (!p) return false;
      this.env.set(a[5]!, (a[1] === 'start' ? p.start : p.size / SECTOR).toString(16));
      return true;
    }
    if (a[0] === 'mmc' && a[1] === 'read' && a[2] === '0x50000000') {
      const start = Number.parseInt(a[3]!, 16), n = Number.parseInt(a[4]!, 16);
      const e = [...this.parts.entries()].find(([, q]) => q.start === start);
      if (!e || n * SECTOR !== e[1].size) return false;
      this.buf = this.data(e[0]).slice();
      return true;
    }
    if (a[0] === 'setexpr' && a[1] === 'fastboot_upload_size' && a[3] === '*' && a[4] === '200') {
      this.env.set('fastboot_upload_size', (Number.parseInt(a[2]!, 16) * 0x200).toString(16));
      return true;
    }
    return false;
  }

  async inPacket(max: number): Promise<Uint8Array> {
    const r = this.replies.shift();
    if (!r) throw new Error('host read with nothing queued');
    expect(r.length).toBeLessThanOrEqual(max);
    return r;
  }

  async in(length: number): Promise<Uint8Array> {
    const out = new Uint8Array(length);
    let got = 0;
    while (got < length) {
      const r = this.replies.shift();
      if (!r) throw new Error('host read with nothing queued');
      if (r.length > length - got) throw new Error('read would swallow the next reply');
      out.set(r, got); got += r.length;
    }
    return out;
  }

  async close() { this.closed = true; }
}

