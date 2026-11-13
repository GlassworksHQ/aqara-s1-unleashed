// The standalone `vbmeta` partition (frames/aqara-s1-plus/research/web-installer.md §6a, tools/install/fel-install.sh):
// flags are the big-endian u32 at offset 120 of the AVB0 header; 3 = verification + hashtree off.

const FLAGS_OFF = 120;

function checkVbmeta(vbmeta: Uint8Array): DataView {
  if (vbmeta.length < 256 || String.fromCharCode(...vbmeta.subarray(0, 4)) !== 'AVB0')
    throw new Error('not a vbmeta image (no AVB0 magic)');
  return new DataView(vbmeta.buffer, vbmeta.byteOffset, vbmeta.byteLength);
}

/** AvbVBMetaImageHeader.flags. */
export function vbmetaFlags(vbmeta: Uint8Array): number {
  return checkVbmeta(vbmeta).getUint32(FLAGS_OFF, false);
}

/** A copy of `vbmeta` with flags |= set (python: struct.pack_into('>I', 120, flags | set)). */
export function withVbmetaFlags(vbmeta: Uint8Array, set: number): Uint8Array {
  const out = vbmeta.slice();
  const dv = checkVbmeta(out);
  dv.setUint32(FLAGS_OFF, (dv.getUint32(FLAGS_OFF, false) | set) >>> 0, false);
  return out;
}
