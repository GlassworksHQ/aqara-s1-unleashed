// Qualcomm LK's fastboot as far as flashBlob uses it, read from CAF-derived lk2nd
// (app/aboot/fastboot.c, aboot.c): max-download-size as "\t0x%x", "data too large" above it,
// FAIL for unknown variables, and sparse images written chunk by chunk at their offsets.
import type { BulkTransport } from '@glassworks/fel/transport';

const SPARSE_MAGIC = 0xed26ff3a;

export class FakeLk implements BulkTransport {
  replies: Uint8Array[] = [];
  commands: string[] = [];
  downloads: number[] = [];
  /** Partitions that answer is-logical with yes, as on a device with a super partition. LK has none. */
  logical = new Set<string>();
  parts = new Map<string, Uint8Array>();
  private buf: Uint8Array = new Uint8Array(0);
  private download: { buf: Uint8Array; got: number } | null = null;

  constructor(readonly maxDownload: number, parts: [string, number][], readonly maxDownloadReply = `\t0x${maxDownload.toString(16)}`) {
    for (const [name, size] of parts) this.parts.set(name, new Uint8Array(size).fill(0xee));
  }

  private reply(s: string) { this.replies.push(new TextEncoder().encode(s)); }

  async out(data: Uint8Array): Promise<void> {
    if (this.download) {
      const d = this.download;
      if (d.got + data.length > d.buf.length) throw new Error('host sent more than it announced');
      d.buf.set(data, d.got);
      d.got += data.length;
      if (d.got === d.buf.length) { this.buf = d.buf; this.download = null; this.reply('OKAY'); }
      return;
    }
    if (data.length > 64) throw new Error('command over 64 bytes');
    const cmd = new TextDecoder().decode(data);
    this.commands.push(cmd);
    if (cmd === 'getvar:max-download-size') {
      this.reply(`OKAY${this.maxDownloadReply}`);
    } else if (cmd.startsWith('getvar:is-logical:') && this.logical.has(cmd.slice(18))) {
      this.reply('OKAYyes');
    } else if (cmd.startsWith('resize-logical-partition:')) {
      this.reply('OKAY');
    } else if (cmd.startsWith('getvar:')) {
      this.reply('FAILGetVar Variable Not found');
    } else if (cmd.startsWith('download:')) {
      const n = Number.parseInt(cmd.slice(9), 16);
      if (n > this.maxDownload) { this.reply('FAILdata too large'); return; }
      this.downloads.push(n);
      this.download = { buf: new Uint8Array(n), got: 0 };
      this.reply(`DATA${n.toString(16).padStart(8, '0')}`);
    } else if (cmd.startsWith('flash:')) {
      const p = this.parts.get(cmd.slice(6));
      if (!p) { this.reply('FAILpartition table doesn\'t exist'); return; }
      const err = isSparse(this.buf) ? writeSparse(p, this.buf) : writeRaw(p, this.buf);
      this.reply(err ? `FAIL${err}` : 'OKAY');
    } else if (cmd === 'boot') {
      this.reply('OKAY');
    } else {
      this.reply('FAILunknown command');
    }
  }

  async inPacket(max: number): Promise<Uint8Array> {
    const r = this.replies.shift();
    if (!r || r.length > max) throw new Error('unexpected packet read');
    return r;
  }

  async in(): Promise<Uint8Array> { throw new Error('LK has no upload'); }
  async close() {}
}

function isSparse(b: Uint8Array) {
  return b.length >= 28 && new DataView(b.buffer, b.byteOffset).getUint32(0, true) === SPARSE_MAGIC;
}

function writeRaw(p: Uint8Array, b: Uint8Array): string | null {
  if (b.length > p.length) return 'size too large';
  p.set(b, 0);
  return null;
}

/** aboot.c cmd_flash_mmc_sparse_img: RAW, FILL, DONT_CARE and CRC32 chunks. */
function writeSparse(p: Uint8Array, b: Uint8Array): string | null {
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const blockSize = v.getUint32(12, true), blocks = v.getUint32(16, true), chunks = v.getUint32(20, true);
  if (blocks * blockSize > p.length) return 'size too large';
  let off = 28, block = 0;
  for (let i = 0; i < chunks; i++) {
    const type = v.getUint16(off, true), n = v.getUint32(off + 4, true), total = v.getUint32(off + 8, true);
    const body = b.subarray(off + 12, off + total);
    if (type === 0xcac1) {
      if (body.length !== n * blockSize) return 'bogus chunk size for chunk type Raw';
      p.set(body, block * blockSize);
    } else if (type === 0xcac2) {
      for (let j = 0; j < n * blockSize; j += 4) p.set(body.subarray(0, 4), block * blockSize + j);
    } else if (type !== 0xcac3 && type !== 0xcac4) {
      return 'Unkown chunk type';
    }
    if (type !== 0xcac4) block += n;
    off += total;
  }
  if (off !== b.length || block !== blocks) return 'sparse image does not add up';
  return null;
}
