// Magisk v31.1: read boot_patch.sh's inputs out of the APK, and reproduce boot_patch.sh's ramdisk
// steps (scripts/boot_patch.sh, run by tools/root/patch-boot-magisk.sh with KEEPVERITY=true
// KEEPFORCEENCRYPT=true, outside Android so BOOTMODE=false).

import type { Compressor } from './compress';
import { CPIO_MAGISK, CPIO_STOCK, CPIO_UNSUPPORTED, Cpio } from './cpio';

/** The armeabi-v7a payload (the panel's userspace is 32-bit only) plus the scripts we ported. */
export interface MagiskPayload {
  magiskinit: Uint8Array;
  magisk: Uint8Array;
  initLd: Uint8Array;
  bootPatchSh: Uint8Array;
  utilFunctionsSh: Uint8Array;
}

/**
 * SHA-256 of the v31.1 scripts this module is a port of. If they differ, the APK is not the Magisk
 * we reimplemented and the output would silently diverge from the CLI, so we refuse.
 */
export const PORTED_SCRIPTS = {
  'assets/boot_patch.sh': '721e879b9ebdda200796d95dbe6f8e9776cfff89b7550fdb5ca77ac87dcede17',
  'assets/util_functions.sh': '417866965cb3845c7ea085f1499393efcf65e6c7cf1321e7911a3bd7c25a6c8d',
} as const;

export const PAYLOAD_ABI = 'armeabi-v7a';

// ---------------------------------------------------------------------------------------------
// Minimal zip reader (stored + deflate, CRC-checked)

let crcTable: Uint32Array | undefined;
export function crc32(d: Uint8Array): number {
  if (!crcTable) {
    crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c >>> 0;
    }
  }
  let c = 0xffffffff;
  for (let i = 0; i < d.length; i++) c = crcTable[(c ^ d[i]!) & 0xff]! ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

export interface ZipEntry {
  name: string;
  method: number;
  crc: number;
  compressedSize: number;
  size: number;
  localOffset: number;
}

/** Central directory of a zip (no zip64; the Magisk APK does not need it). */
export function zipEntries(zip: Uint8Array): Map<string, ZipEntry> {
  const dv = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  let eocd = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 0xffff); i--) {
    if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a zip file (no end of central directory)');
  const count = dv.getUint16(eocd + 10, true);
  let p = dv.getUint32(eocd + 16, true);
  const td = new TextDecoder();
  const out = new Map<string, ZipEntry>();
  for (let i = 0; i < count; i++) {
    if (dv.getUint32(p, true) !== 0x02014b50) throw new Error('bad zip central directory');
    const method = dv.getUint16(p + 10, true);
    const crc = dv.getUint32(p + 16, true);
    const compressedSize = dv.getUint32(p + 20, true);
    const size = dv.getUint32(p + 24, true);
    const nlen = dv.getUint16(p + 28, true), xlen = dv.getUint16(p + 30, true), clen = dv.getUint16(p + 32, true);
    const localOffset = dv.getUint32(p + 42, true);
    const name = td.decode(zip.subarray(p + 46, p + 46 + nlen));
    out.set(name, { name, method, crc, compressedSize, size, localOffset });
    p += 46 + nlen + xlen + clen;
  }
  return out;
}

export function zipRead(zip: Uint8Array, e: ZipEntry, comp: Compressor): Uint8Array {
  const dv = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  const lo = e.localOffset;
  if (dv.getUint32(lo, true) !== 0x04034b50) throw new Error(`bad zip local header for ${e.name}`);
  const start = lo + 30 + dv.getUint16(lo + 26, true) + dv.getUint16(lo + 28, true);
  const raw = zip.subarray(start, start + e.compressedSize);
  let data: Uint8Array;
  if (e.method === 0) data = raw.slice();
  else if (e.method === 8) data = comp.inflateRaw(raw);
  else throw new Error(`${e.name}: unsupported zip method ${e.method}`);
  if (data.length !== e.size || crc32(data) !== e.crc) throw new Error(`${e.name}: CRC/size mismatch`);
  return data;
}

async function sha256Hex(d: Uint8Array): Promise<string> {
  const h = new Uint8Array(await crypto.subtle.digest('SHA-256', d as Uint8Array<ArrayBuffer>));
  return [...h].map((x) => x.toString(16).padStart(2, '0')).join('');
}

/** Pull boot_patch.sh's inputs out of the Magisk APK (what patch-boot-magisk.sh unzips). */
export async function extractMagiskPayload(apk: Uint8Array, comp: Compressor): Promise<MagiskPayload> {
  const ents = zipEntries(apk);
  const get = (name: string) => {
    const e = ents.get(name);
    if (!e) throw new Error(`Magisk APK has no ${name}`);
    return zipRead(apk, e, comp);
  };
  const p: MagiskPayload = {
    magiskinit: get(`lib/${PAYLOAD_ABI}/libmagiskinit.so`),
    magisk: get(`lib/${PAYLOAD_ABI}/libmagisk.so`),
    initLd: get(`lib/${PAYLOAD_ABI}/libinit-ld.so`),
    bootPatchSh: get('assets/boot_patch.sh'),
    utilFunctionsSh: get('assets/util_functions.sh'),
  };
  for (const [name, want] of Object.entries(PORTED_SCRIPTS)) {
    const got = await sha256Hex(name.endsWith('boot_patch.sh') ? p.bootPatchSh : p.utilFunctionsSh);
    if (got !== want) throw new Error(`${name} is not Magisk v31.1's (sha256 ${got}); this patcher is a port of v31.1 only`);
  }
  return p;
}

// ---------------------------------------------------------------------------------------------
// boot_patch.sh

/** util_functions.sh grep_prop KEY: first `KEY=` line (CRs stripped), else ''. */
export function grepProp(key: string, text: string): string {
  for (const line of text.replace(/\r$/gm, '').split('\n')) {
    if (line.startsWith(key + '=')) return line.slice(key.length + 1);
  }
  return '';
}

export interface MagiskConfig {
  keepVerity: boolean;
  keepForceEncrypt: boolean;
  recoveryMode: boolean;
  vendorBoot: boolean;
  preinitDevice: string;
  sha1: string;
}

/** The `config` file boot_patch.sh writes and stores as `.backup/.magisk`. */
export function magiskConfigText(c: MagiskConfig): string {
  let s = `KEEPVERITY=${c.keepVerity}\nKEEPFORCEENCRYPT=${c.keepForceEncrypt}\nRECOVERYMODE=${c.recoveryMode}\nVENDORBOOT=${c.vendorBoot}\n`;
  if (c.preinitDevice) s += `PREINITDEVICE=${c.preinitDevice}\n`;
  if (c.sha1) s += `SHA1=${c.sha1}\n`;
  return s;
}

export interface RamdiskPatchOptions {
  /** `magiskboot sha1` of the boot image as read (used only for a stock image). */
  bootSha1: string;
  /** The PREINITDEVICE env (wrapper default 'cache'); a re-patch inherits the old config's instead. */
  preinitDevice: string;
  keepVerity?: boolean;
  keepForceEncrypt?: boolean;
}

export interface RamdiskPatchResult {
  /** `cpio test` status before patching: 0 stock, 1 Magisk. */
  status: number;
  config: string;
}

/**
 * boot_patch.sh from "Ramdisk Checks" through the ramdisk cpio edit, applied to `cpio` in place.
 * KEEPVERITY / KEEPFORCEENCRYPT default to true (what the wrapper passes).
 */
export function magiskPatchRamdisk(
  cpio: Cpio, payload: MagiskPayload, opts: RamdiskPatchOptions, comp: Compressor, log: (s: string) => void,
): RamdiskPatchResult {
  const keepVerity = opts.keepVerity ?? true;
  const keepForceEncrypt = opts.keepForceEncrypt ?? true;

  log('- Checking ramdisk status');
  const status = cpio.test();
  let sha1 = '';
  let preinit = opts.preinitDevice;
  let configOrig: string | undefined;
  if (status === CPIO_STOCK) {
    log('- Stock boot image detected');
    sha1 = opts.bootSha1;
  } else if (status === CPIO_MAGISK) {
    log('- Magisk patched boot image detected');
    // "extract .backup/.magisk config.orig" "restore": if extract fails the CLI would carry on
    // with the unrestored ramdisk (old/foreign Magisk), so refuse instead.
    if (!cpio.exists('.backup/.magisk')) throw new Error('Magisk-patched ramdisk has no .backup/.magisk (old Magisk?): restore the stock boot image first');
    configOrig = new TextDecoder().decode(cpio.extract('.backup/.magisk').data);
    cpio.restore(comp);
  } else if (status === CPIO_UNSUPPORTED) {
    throw new Error('Boot image patched by unsupported programs: please restore back to stock boot image');
  }
  const orig = cpio.clone(); // ramdisk.cpio.orig

  if (configOrig !== undefined) {
    sha1 = grepProp('SHA1', configOrig);
    // BOOTMODE=false: the old config's PREINITDEVICE wins over the environment
    preinit = grepProp('PREINITDEVICE', configOrig);
  }

  log('- Patching ramdisk');
  const magiskXz = comp.xz(payload.magisk, 4096); // compress=xz sniffs the first 4 KiB
  const initLdXz = comp.xz(payload.initLd, 4096);

  if (preinit) log(`- Pre-init storage partition: ${preinit}`);
  const config = magiskConfigText({ keepVerity, keepForceEncrypt, recoveryMode: false, vendorBoot: false, preinitDevice: preinit, sha1 });

  cpio.add(0o750, 'init', payload.magiskinit);
  cpio.mkdir(0o750, 'overlay.d');
  cpio.mkdir(0o750, 'overlay.d/sbin');
  cpio.add(0o644, 'overlay.d/sbin/magisk.xz', magiskXz);
  cpio.add(0o644, 'overlay.d/sbin/init-ld.xz', initLdXz);
  cpio.patch(keepVerity, keepForceEncrypt);
  cpio.backup(orig, comp);
  cpio.mkdir(0o000, '.backup');
  cpio.add(0o000, '.backup/.magisk', new TextEncoder().encode(config));
  return { status, config };
}

// "Binary Patches": what boot_patch.sh would change outside the ramdisk. With KEEPVERITY=true none
// of these apply to this panel's images; if one ever does, refuse rather than emit a different
// image than the CLI.
const KERNEL_HEXPATCHES = [
  '49010054011440B93FA00F71E9000054010840B93FA00F7189000054001840B91FA00F7188010054', // Samsung RKP
  '821B8012', // Samsung defex
  '70726F63615F636F6E66696700', // Samsung PROCA
];

/** Throws if boot_patch.sh would patch this dtb or kernel (it never does on the S1). */
export function checkBinaryPatches(kernel: Uint8Array, dtb: Uint8Array, findBytes: (h: Uint8Array, n: Uint8Array) => number): void {
  const enc = new TextEncoder();
  if (findBytes(dtb, enc.encode('/system_root\0')) >= 0)
    throw new Error('Boot image dtb was patched by old (unsupported) Magisk');
  if (findBytes(dtb, enc.encode('skip_initramfs')) >= 0)
    throw new Error('dtb has skip_initramfs (legacy SAR): would be patched by magiskboot, unsupported here');
  for (const h of KERNEL_HEXPATCHES) {
    const pat = new Uint8Array(h.match(/../g)!.map((x) => parseInt(x, 16)));
    if (findBytes(kernel, pat) >= 0) throw new Error(`kernel matches Magisk hexpatch ${h}: unsupported here`);
  }
}
