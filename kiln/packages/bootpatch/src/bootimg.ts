// Android boot image v2 unpack/repack, as magiskboot v31.1 does it (native/src/boot/bootimg.cpp:
// boot_img::parse_image, unpack, repack). Only what this panel's images use is supported; anything
// else (other header versions, vendor_boot, MTK/zImage/compressed kernels, Samsung/LG/AVB1 tails,
// DHTB/ChromeOS wrappers) is refused rather than guessed at.

const BOOT_MAGIC = 'ANDROID!';
const HDR_V2_SIZE = 1660; // sizeof(boot_img_hdr_v2)
const ID_OFF = 576; // id[32]
const AVB_FOOTER_SIZE = 64;

// boot_img_hdr_v2 field offsets
const O = {
  kernelSize: 8, ramdiskSize: 16, secondSize: 24, pageSize: 36, headerVersion: 40,
  recoveryDtboSize: 1632, recoveryDtboOffset: 1636, headerSize: 1644, dtbSize: 1648,
} as const;

export interface BootImage {
  /** The whole input (header page included). */
  raw: Uint8Array;
  pageSize: number;
  headerVersion: number;
  /** The raw header struct (first 1660 bytes). */
  header: Uint8Array;
  kernel: Uint8Array;
  ramdisk: Uint8Array;
  second: Uint8Array;
  recoveryDtbo: Uint8Array;
  dtb: Uint8Array;
  /** End of the page-aligned payload (`payload` in magiskboot). */
  payloadEnd: number;
  /** id uses SHA-256 (bytes 24..32 of id non-zero) rather than SHA-1. */
  sha256Id: boolean;
  /** AVB hash footer (last 64 bytes) and the vbmeta block it points to, if present. */
  avb?: { footer: Uint8Array; vbmeta: Uint8Array };
  ramdiskFormat: 'gzip' | 'xz' | 'raw' | 'none';
}

const td = new TextDecoder();
const u32 = (d: Uint8Array, o: number) => new DataView(d.buffer, d.byteOffset, d.byteLength).getUint32(o, true);
const alignTo = (x: number, a: number) => Math.ceil(x / a) * a;

function startsWith(d: Uint8Array, s: string | number[], at = 0): boolean {
  const b = typeof s === 'string' ? new TextEncoder().encode(s) : s;
  if (at + b.length > d.length) return false;
  for (let i = 0; i < b.length; i++) if (d[at + i] !== b[i]) return false;
  return true;
}

function indexOf(hay: Uint8Array, needle: Uint8Array): number {
  const f = needle[0];
  outer: for (let i = hay.indexOf(f!); i >= 0 && i + needle.length <= hay.length; i = hay.indexOf(f!, i + 1)) {
    for (let j = 1; j < needle.length; j++) if (hay[i + j] !== needle[j]) continue outer;
    return i;
  }
  return -1;
}

export function hexToBytes(h: string): Uint8Array {
  const out = new Uint8Array(h.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(h.slice(2 * i, 2 * i + 2), 16);
  return out;
}

export function toHex(b: Uint8Array): string {
  return [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
}

/** format.rs check_fmt, reduced to the formats we can tell apart and must refuse. */
function sniffFormat(d: Uint8Array): string {
  if (startsWith(d, [0x1f, 0x8b, 0x08]) || startsWith(d, [0x1f, 0x9e, 0x08])) return 'gzip';
  if (startsWith(d, [0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00])) return 'xz';
  if (startsWith(d, [0x89, 0x4c, 0x5a, 0x4f])) return 'lzop';
  if (startsWith(d, 'BZh')) return 'bzip2';
  if (startsWith(d, [0x04, 0x22, 0x4d, 0x18]) || startsWith(d, [0x03, 0x21, 0x4c, 0x18])) return 'lz4';
  if (startsWith(d, [0x02, 0x21, 0x4c, 0x18])) return 'lz4_legacy';
  if (startsWith(d, [0x88, 0x16, 0x88, 0x58])) return 'mtk';
  if (d.length >= 0x28 && startsWith(d, [0x18, 0x28, 0x6f, 0x01], 0x24)) return 'zimage';
  // guess_lzma: props byte < 225, power-of-two dict, unknown size
  if (d.length >= 13 && d[0]! < 225 && d.subarray(5, 13).every((x) => x === 0xff)) {
    const dict = u32(d, 1);
    if (dict !== 0 && (dict & (dict - 1)) === 0) return 'lzma';
  }
  return 'unknown';
}

/** Does `buf` contain something find_dtb_offset could take for a device tree? (conservative) */
function maybeContainsDtb(buf: Uint8Array): boolean {
  const magic = [0xd0, 0x0d, 0xfe, 0xed];
  for (let i = buf.indexOf(0xd0); i >= 0; i = buf.indexOf(0xd0, i + 1)) {
    if (startsWith(buf, magic, i) && i + 40 <= buf.length) return true;
  }
  return false;
}

/** magiskboot `parse_image` for a plain AOSP v2 boot image. Throws on anything else. */
export function parseBootImage(raw: Uint8Array): BootImage {
  if (!startsWith(raw, BOOT_MAGIC)) throw new Error('not an Android boot image (no ANDROID! magic at offset 0)');
  const pageSize = u32(raw, O.pageSize);
  const headerVersion = u32(raw, O.headerVersion);
  if (headerVersion !== 2) throw new Error(`unsupported boot image header version ${headerVersion} (only v2)`);
  if (![2048, 4096, 8192, 16384].includes(pageSize)) throw new Error(`unsupported page size ${pageSize}`);
  if (raw.length < pageSize) throw new Error('boot image truncated');
  // magiskboot looks for the AVB footer at the end of the 4 KiB-rounded mmap; partitions are aligned.
  if (raw.length % 4096) throw new Error(`boot image size ${raw.length} is not a multiple of 4096 (not a partition dump?)`);
  const header = raw.slice(0, HDR_V2_SIZE);

  const id = raw.subarray(ID_OFF, ID_OFF + 32);
  const sha256Id = id.subarray(24, 32).some((x) => x !== 0);

  const mapEnd = alignTo(raw.length, 4096);
  let off = pageSize;
  const block = (size: number) => {
    const b = raw.subarray(off, off + size);
    off = alignTo(off + size, pageSize);
    if (off > mapEnd) throw new Error('Corrupted boot image!');
    return b;
  };
  const kernel = block(u32(raw, O.kernelSize));
  const ramdisk = block(u32(raw, O.ramdiskSize));
  const second = block(u32(raw, O.secondSize));
  // v2 has no extra block
  const recoveryDtbo = block(u32(raw, O.recoveryDtboSize));
  const dtb = block(u32(raw, O.dtbSize));
  const payloadEnd = off;

  if (kernel.length) {
    const kf = sniffFormat(kernel);
    if (kf !== 'unknown') throw new Error(`unsupported kernel format ${kf} (only a raw kernel)`);
    if (maybeContainsDtb(kernel)) throw new Error('kernel has an appended DTB: unsupported');
  }

  let ramdiskFormat: BootImage['ramdiskFormat'] = 'none';
  if (ramdisk.length) {
    const rf = sniffFormat(ramdisk);
    if (rf === 'gzip' || rf === 'xz') ramdiskFormat = rf;
    else if (rf === 'unknown' && startsWith(ramdisk, '070701')) ramdiskFormat = 'raw';
    else throw new Error(`unsupported ramdisk format ${rf}`);
  }

  // tail: refuse the proprietary trailers magiskboot would carry over or re-sign
  const tail = raw.subarray(payloadEnd);
  if (startsWith(tail, 'SEANDROIDENFORCE')) throw new Error('Samsung SEANDROID boot image: unsupported');
  if (startsWith(tail, [0x41, 0xa9, 0xe4, 0x67, 0x74, 0x4d, 0x1d, 0x1b, 0xa4, 0x29, 0xf2, 0xec, 0xea, 0x65, 0x52, 0x79]))
    throw new Error('LG bump boot image: unsupported');
  if (tail.length >= 2 && tail[0] === 0x30 && (tail[1]! & 0x80)) throw new Error('AVB1-signed boot image: unsupported');

  let avb: BootImage['avb'];
  if (tail.length >= AVB_FOOTER_SIZE) {
    const footer = raw.subarray(raw.length - AVB_FOOTER_SIZE);
    if (startsWith(footer, 'AVBf')) {
      const dv = new DataView(footer.buffer, footer.byteOffset, 64);
      const vbOff = Number(dv.getBigUint64(20, false));
      const vbSize = Number(dv.getBigUint64(28, false));
      if (vbOff + 4 <= raw.length && startsWith(raw, 'AVB0', vbOff)) {
        if (vbOff + vbSize > raw.length) throw new Error('AVB vbmeta block truncated');
        avb = { footer: footer.slice(), vbmeta: raw.slice(vbOff, vbOff + vbSize) };
      }
    }
  }

  return { raw, pageSize, headerVersion, header, kernel, ramdisk, second, recoveryDtbo, dtb, payloadEnd, sha256Id, avb, ramdiskFormat };
}

/** Header summary for logs (like magiskboot's print). */
export function describeBootImage(b: BootImage): string[] {
  const cmd = td.decode(b.header.subarray(64, 64 + 512)).replace(/\0.*$/s, '');
  return [
    `HEADER_VER [${b.headerVersion}] PAGESIZE [${b.pageSize}]`,
    `KERNEL_SZ [${b.kernel.length}] RAMDISK_SZ [${b.ramdisk.length}] DTB_SZ [${b.dtb.length}] RAMDISK_FMT [${b.ramdiskFormat}]`,
    `CMDLINE [${cmd}]`,
    `CHECKSUM [${toHex(b.raw.subarray(ID_OFF, ID_OFF + 32))}]${b.avb ? ' VBMETA' : ''}`,
  ];
}

async function digest(alg: 'SHA-1' | 'SHA-256', parts: Uint8Array[]): Promise<Uint8Array> {
  let n = 0;
  for (const p of parts) n += p.length;
  const buf = new Uint8Array(n);
  n = 0;
  for (const p of parts) { buf.set(p, n); n += p.length; }
  return new Uint8Array(await crypto.subtle.digest(alg, buf));
}

/** `magiskboot sha1 <file>`: hex SHA-1 of the whole file. */
export async function sha1Hex(data: Uint8Array): Promise<string> {
  return toHex(await digest('SHA-1', [data]));
}

export interface RepackBlocks {
  kernel?: Uint8Array;
  /** Already compressed the way the source ramdisk was (see compress.ts). */
  ramdisk?: Uint8Array;
}

/**
 * magiskboot `repack <src> <out>` with the unpacked files equal to `src`'s blocks except those in
 * `blocks`. Header id recomputed, AVB footer re-emitted (PATCHVBMETAFLAG unset), padded to the
 * source size. Throws if the result would not fit in the source size.
 */
export async function repackBootImage(src: BootImage, blocks: RepackBlocks): Promise<Uint8Array> {
  const page = src.pageSize;
  const kernel = blocks.kernel ?? src.kernel;
  const ramdisk = blocks.ramdisk ?? src.ramdisk;
  const second = src.second, rdtbo = src.recoveryDtbo, dtb = src.dtb;

  // Layout (offsets relative to the header, which is at 0 here)
  let pos = page; // header page copied raw from the source payload
  const offKernel = pos; pos = alignTo(pos + kernel.length, page);
  const offRamdisk = pos; pos = ramdisk.length ? alignTo(pos + ramdisk.length, page) : pos;
  const offSecond = pos; pos = second.length ? alignTo(pos + second.length, page) : pos;
  let rdtboOffset = new DataView(src.header.buffer, src.header.byteOffset).getBigUint64(O.recoveryDtboOffset, true);
  if (rdtbo.length) { rdtboOffset = BigInt(pos); pos = alignTo(pos + rdtbo.length, page); }
  const offDtb = pos; pos = dtb.length ? alignTo(pos + dtb.length, page) : pos;
  const eof = pos; // no SEANDROID/LG trailers (refused at parse)
  let offVbmeta = 0;
  if (src.avb) {
    pos = alignTo(pos, 4096);
    offVbmeta = pos;
    pos += src.avb.vbmeta.length;
  }
  if (pos > src.raw.length || (src.avb && pos > src.raw.length - AVB_FOOTER_SIZE))
    throw new Error(`patched image (${pos} bytes) does not fit in the original ${src.raw.length}: refusing`);

  const out = new Uint8Array(src.raw.length);
  out.set(src.raw.subarray(0, page), 0);
  out.set(kernel, offKernel);
  out.set(ramdisk, offRamdisk);
  out.set(second, offSecond);
  if (rdtbo.length) out.set(rdtbo, Number(rdtboOffset));
  out.set(dtb, offDtb);
  if (src.avb) out.set(src.avb.vbmeta, offVbmeta);

  // New header: clone of the source struct, sizes and id updated
  const hdr = src.header.slice();
  const hv = new DataView(hdr.buffer);
  hv.setUint32(O.kernelSize, kernel.length, true);
  hv.setUint32(O.ramdiskSize, ramdisk.length, true);
  hv.setUint32(O.secondSize, second.length, true);
  hv.setUint32(O.recoveryDtboSize, rdtbo.length, true);
  hv.setBigUint64(O.recoveryDtboOffset, rdtboOffset, true);
  hv.setUint32(O.dtbSize, dtb.length, true);
  hv.setUint32(O.headerSize, HDR_V2_SIZE, true);

  const le32 = (n: number) => { const b = new Uint8Array(4); new DataView(b.buffer).setUint32(0, n, true); return b; };
  const rdtboAt = Number(rdtboOffset);
  const id = await digest(src.sha256Id ? 'SHA-256' : 'SHA-1', [
    kernel, le32(kernel.length),
    ramdisk, le32(ramdisk.length),
    second, le32(second.length),
    out.subarray(rdtboAt, rdtboAt + rdtbo.length), le32(rdtbo.length),
    dtb, le32(dtb.length),
  ]);
  hdr.fill(0, ID_OFF, ID_OFF + 32);
  hdr.set(id, ID_OFF);
  out.set(hdr, 0);

  if (src.avb) {
    const f = src.avb.footer.slice();
    const fv = new DataView(f.buffer);
    fv.setBigUint64(12, BigInt(eof), false); // original_image_size
    fv.setBigUint64(20, BigInt(offVbmeta), false); // vbmeta_offset
    out.set(f, out.length - AVB_FOOTER_SIZE);
  }
  return out;
}

export { indexOf as findBytes };
