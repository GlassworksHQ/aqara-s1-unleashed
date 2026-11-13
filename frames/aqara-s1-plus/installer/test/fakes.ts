// Test images in the S1's own formats, built here so no Aqara bytes are needed.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

export const REPO = join(__dirname, '../../../..');

/** Our shipped splash (branding/glass-tile/bootlogo.bmp). */
export const ourBootlogo = () => new Uint8Array(readFileSync(join(REPO, 'branding/glass-tile/bootlogo.bmp')));

/** A BMP of the same size and header as ours with one pixel changed: a splash that isn't ours. */
export function otherBootlogo(): Uint8Array {
  const b = ourBootlogo();
  b[54 + 1000]! ^= 0xff;
  return b;
}

/**
 * A FAT16 boot-resource like the S1's `bootloader` partition: another file in the first cluster,
 * then `bmp` as BOOTLOGO.BMP in clusters laid out backwards, so a reader must follow the FAT chain.
 */
export function bootResource(bmp: Uint8Array): Uint8Array {
  const bps = 512, spc = 4, csize = bps * spc, reserved = 4, nfats = 2, nroot = 512;
  const n = Math.ceil(bmp.length / csize), total = n + 1;
  const spf = Math.ceil(((total + 2) * 2) / bps);
  const root = (reserved + nfats * spf) * bps, data = root + nroot * 32, fat = reserved * bps;
  const img = new Uint8Array(data + total * csize);
  const dv = new DataView(img.buffer);
  dv.setUint16(11, bps, true); img[13] = spc; dv.setUint16(14, reserved, true);
  img[16] = nfats; dv.setUint16(17, nroot, true); img[21] = 0xf8; dv.setUint16(22, spf, true);
  img.set(new TextEncoder().encode('FAT16   '), 54);
  const entry = (i: number, name: string, first: number, size: number) => {
    const e = root + i * 32;
    img.set(new TextEncoder().encode(name), e);
    img[e + 11] = 0x20;
    dv.setUint16(e + 26, first, true);
    dv.setUint32(e + 28, size, true);
  };
  entry(0, 'BAT     BMP', 2, 16);
  dv.setUint16(fat + 2 * 2, 0xffff, true);
  const clusters = Array.from({ length: n }, (_, i) => total + 1 - i);
  clusters.forEach((c, i) => {
    dv.setUint16(fat + 2 * c, clusters[i + 1] ?? 0xffff, true);
    img.set(bmp.subarray(i * csize, (i + 1) * csize), data + (c - 2) * csize);
  });
  entry(1, 'BOOTLOGOBMP', clusters[0]!, bmp.length);
  return img;
}
