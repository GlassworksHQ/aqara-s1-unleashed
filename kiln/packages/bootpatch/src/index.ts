// bootpatch: byte-exact boot image + partition edits for the web installer (pure, no USB).
// patchBoot() == tools/root/patch-boot-magisk.sh, byte for byte. See docs/research/web-installer.md §4, §6.

import { describeBootImage, findBytes, parseBootImage, repackBootImage, sha1Hex } from './bootimg';
import { loadCompressor } from './compress';
import { CPIO_MAGISK, Cpio } from './cpio';
import { checkBinaryPatches, extractMagiskPayload, magiskPatchRamdisk } from './magisk';

export interface OverlayFile { path: string; mode: number; data: Uint8Array }  // e.g. 'overlay.d/sbin/x', 0o700
export interface PatchInput {
  boot: Uint8Array;               // the panel's boot.img: stock, or already Magisk-patched (restored first)
  magiskApk: Uint8Array;          // Magisk v31.1 APK (sha256 verified by the caller)
  overlay: OverlayFile[];         // exactly what tools/root/patch-boot-magisk.sh adds (see there)
  preinitDevice?: string;         // default 'cache'
}
export interface PatchResult { image: Uint8Array; wasMagisk: boolean; magiskConfig: string; log: string[] }

export { vbmetaFlags, withVbmetaFlags } from './vbmeta';
export { readBootlogo, replaceBootlogo } from './bootlogo';
export { s1Overlay, type S1OverlayInputs } from './overlay';
export { loadCompressor } from './compress';
export { zipEntries, type ZipEntry } from './magisk';

function unpackRamdisk(boot: ReturnType<typeof parseBootImage>, gunzip: (d: Uint8Array) => Uint8Array, unxz: (d: Uint8Array) => Uint8Array | null): Uint8Array {
  switch (boot.ramdiskFormat) {
    case 'gzip': return gunzip(boot.ramdisk);
    case 'xz': {
      const d = unxz(boot.ramdisk);
      if (!d) throw new Error('corrupt xz ramdisk');
      return d;
    }
    case 'raw': return boot.ramdisk.slice();
    case 'none': throw new Error('boot image has no ramdisk: unsupported');
  }
}

/**
 * Magisk v31.1 boot_patch.sh (KEEPVERITY=true KEEPFORCEENCRYPT=true, PREINITDEVICE) followed by the
 * wrapper's overlay pass, in one in-memory cpio and one repack (equivalent: the cpio is canonical).
 * On an already-patched image Magisk restores the stock ramdisk first, and the old config's SHA1
 * and PREINITDEVICE win over `preinitDevice` (as in the CLI).
 */
export async function patchBoot(input: PatchInput): Promise<PatchResult> {
  const log: string[] = [];
  const say = (s: string) => log.push(s);
  const comp = await loadCompressor();

  say('- Unpacking boot image');
  const boot = parseBootImage(input.boot);
  describeBootImage(boot).forEach(say);
  const cpio = Cpio.load(unpackRamdisk(boot, comp.gunzip, comp.unxz), say);

  const payload = await extractMagiskPayload(input.magiskApk, comp);
  const { status, config } = magiskPatchRamdisk(cpio, payload, {
    bootSha1: await sha1Hex(input.boot),
    preinitDevice: input.preinitDevice ?? 'cache',
  }, comp, say);

  checkBinaryPatches(boot.kernel, boot.dtb, findBytes);

  say('- Adding overlay files');
  for (const f of input.overlay) cpio.add(f.mode, f.path, f.data);

  say('- Repacking boot image');
  const raw = cpio.dump();
  const ramdisk = boot.ramdiskFormat === 'gzip' ? comp.gzip(raw)
    : boot.ramdiskFormat === 'xz' ? comp.xz(raw)
    : raw;
  const image = await repackBootImage(boot, { ramdisk });
  if (image.length !== input.boot.length) throw new Error('size changed: refusing');
  say(`- Done: ramdisk ${raw.length} bytes cpio, ${ramdisk.length} bytes ${boot.ramdiskFormat}`);
  return { image, wasMagisk: status === CPIO_MAGISK, magiskConfig: config, log };
}

/** `magiskboot cpio ramdisk.cpio test` == 1 (Magisk-patched). */
export async function isMagiskPatched(boot: Uint8Array): Promise<boolean> {
  const comp = await loadCompressor();
  const b = parseBootImage(boot);
  return Cpio.load(unpackRamdisk(b, comp.gunzip, comp.unxz)).test() === CPIO_MAGISK;
}
