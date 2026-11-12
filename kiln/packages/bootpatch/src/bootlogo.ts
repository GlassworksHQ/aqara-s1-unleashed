// Port of tools/branding/set-bootlogo.py: replace BOOTLOGO.BMP inside the `bootloader` partition
// (Allwinner boot-resource, FAT16) in place: same size, same clusters, FAT and directory untouched.

function find(img: Uint8Array, dv: DataView) {
  const bps = dv.getUint16(11, true), spc = img[13]!, reserved = dv.getUint16(14, true);
  const nfats = img[16]!, nroot = dv.getUint16(17, true), spf = dv.getUint16(22, true);
  if (String.fromCharCode(...img.subarray(54, 62)) !== 'FAT16   ') throw new Error('not a FAT16 boot-resource image');
  const root = (reserved + nfats * spf) * bps;
  const data = root + nroot * 32;
  for (let i = 0; i < nroot; i++) {
    const e = root + i * 32;
    if (String.fromCharCode(...img.subarray(e, e + 11)) === 'BOOTLOGOBMP') {
      return { bps, spc, reserved, data, first: dv.getUint16(e + 26, true), size: dv.getUint32(e + 28, true) };
    }
  }
  throw new Error('BOOTLOGO.BMP not found in the root directory');
}

function chain(dv: DataView, bps: number, reserved: number, first: number): number[] {
  const fat = reserved * bps, out: number[] = [];
  let c = first;
  while (c >= 2 && c < 0xfff8) {
    out.push(c);
    c = dv.getUint16(fat + 2 * c, true);
    if (out.length > 1 << 16) throw new Error('FAT chain loop');
  }
  return out;
}

/** == `python3 tools/branding/set-bootlogo.py <bootloader.img> <bmp> <out>`, byte for byte. */
export function replaceBootlogo(bootloaderImg: Uint8Array, bmp: Uint8Array): Uint8Array {
  const img = bootloaderImg.slice();
  const dv = new DataView(img.buffer);
  if (bmp[0] !== 0x42 || bmp[1] !== 0x4d) throw new Error('new logo is not a BMP');
  const bv = new DataView(bmp.buffer, bmp.byteOffset, bmp.byteLength);
  const w = bv.getInt32(18, true), h = bv.getInt32(22, true), bpp = bv.getUint16(28, true), comp = bv.getUint32(30, true);
  if (w !== 720 || Math.abs(h) !== 1440 || bpp !== 24 || comp !== 0)
    throw new Error(`new logo must be 720x1440, 24-bit, uncompressed (got ${w}x${h}, ${bpp} bpp, comp ${comp})`);
  const { bps, spc, reserved, data, first, size } = find(img, dv);
  if (bmp.length !== size) throw new Error(`size mismatch: stock BOOTLOGO.BMP is ${size} bytes, new one is ${bmp.length}`);
  const csize = bps * spc;
  const clusters = chain(dv, bps, reserved, first);
  if (clusters.length * csize < size) throw new Error('FAT chain shorter than the file');
  clusters.forEach((c, n) => {
    const part = bmp.subarray(n * csize, (n + 1) * csize);
    if (!part.length) return;
    const off = data + (c - 2) * csize;
    if (off + part.length > img.length) throw new Error('cluster outside the image');
    img.set(part, off);
  });
  // Read it back through the same chain before handing anything out.
  const back = new Uint8Array(clusters.length * csize);
  clusters.forEach((c, n) => back.set(img.subarray(data + (c - 2) * csize, data + (c - 1) * csize), n * csize));
  for (let i = 0; i < size; i++) if (back[i] !== bmp[i]) throw new Error('verification failed');
  return img;
}
