// A minimal STORED (uncompressed) zip writer, so the backup opens with the OS's own unzip on
// Windows, macOS and Linux. Files stay as Blobs: nothing is copied into one big buffer.

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(data: Uint8Array, crc = 0): number {
  let c = ~crc >>> 0;
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]!) & 0xff]! ^ (c >>> 8);
  return ~c >>> 0;
}

export interface ZipEntry { name: string; data: Blob | Uint8Array; crc: number; date?: Date }

function dosTime(d: Date): { time: number; date: number } {
  return {
    time: (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1),
    date: ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate(),
  };
}

export function zipBlob(entries: ZipEntry[]): Blob {
  const parts: BlobPart[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  for (const e of entries) {
    const name = new TextEncoder().encode(e.name);
    const size = e.data instanceof Blob ? e.data.size : e.data.length;
    if (size >= 0xffffffff || offset >= 0xffffffff) throw new Error('backup too large for a plain zip');
    const { time, date } = dosTime(e.date ?? new Date());
    const local = new Uint8Array(30 + name.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);        // version needed
    lv.setUint16(6, 0x0800, true);    // UTF-8 names
    lv.setUint16(8, 0, true);         // STORED
    lv.setUint16(10, time, true);
    lv.setUint16(12, date, true);
    lv.setUint32(14, e.crc, true);
    lv.setUint32(18, size, true);
    lv.setUint32(22, size, true);
    lv.setUint16(26, name.length, true);
    local.set(name, 30);
    const cd = new Uint8Array(46 + name.length);
    const cv = new DataView(cd.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 0x0314, true);    // made by: unix, 2.0
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true);
    cv.setUint16(12, time, true);
    cv.setUint16(14, date, true);
    cv.setUint32(16, e.crc, true);
    cv.setUint32(20, size, true);
    cv.setUint32(24, size, true);
    cv.setUint16(28, name.length, true);
    cv.setUint32(38, (0o100644 << 16) >>> 0, true); // external attrs: regular file 0644
    cv.setUint32(42, offset, true);
    cd.set(name, 46);
    central.push(cd);
    parts.push(local as Uint8Array<ArrayBuffer>, e.data as Blob | Uint8Array<ArrayBuffer>);
    offset += local.length + size;
  }
  const cdSize = central.reduce((n, c) => n + c.length, 0);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, entries.length, true);
  ev.setUint16(10, entries.length, true);
  ev.setUint32(12, cdSize, true);
  ev.setUint32(16, offset, true);
  return new Blob([...parts, ...(central as Uint8Array<ArrayBuffer>[]), end], { type: 'application/zip' });
}
