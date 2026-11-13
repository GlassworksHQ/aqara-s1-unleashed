import { describe, expect, it } from 'vitest';
import { Fastboot } from '../src';
import { FakeLk } from './fake-lk';

const BLOCK = 4096;

/** A raw image with a different byte at every position, so a misplaced block shows. */
function rawImage(blocks: number): Uint8Array {
  return new Uint8Array(blocks * BLOCK).map((_, i) => (i * 7 + (i >>> 12)) & 0xff);
}

type Chunk = { raw: Uint8Array } | { fill: number; blocks: number } | { skip: number };

/** A sparse image as img2simg writes one, and what LK should leave on the partition. */
function sparseImage(chunks: Chunk[], before: number): { image: Uint8Array; expected: Uint8Array } {
  const parts: Uint8Array[] = [];
  let blocks = 0;
  for (const c of chunks) blocks += 'raw' in c ? c.raw.length / BLOCK : 'fill' in c ? c.blocks : c.skip;
  const expected = new Uint8Array(blocks * BLOCK).fill(before);
  const head = new Uint8Array(28), hv = new DataView(head.buffer);
  hv.setUint32(0, 0xed26ff3a, true); hv.setUint16(4, 1, true); hv.setUint16(8, 28, true); hv.setUint16(10, 12, true);
  hv.setUint32(12, BLOCK, true); hv.setUint32(16, blocks, true); hv.setUint32(20, chunks.length, true);
  parts.push(head);
  let at = 0;
  for (const c of chunks) {
    const body = 'raw' in c ? c.raw : 'fill' in c ? new Uint8Array(new Uint32Array([c.fill]).buffer) : new Uint8Array(0);
    const n = 'raw' in c ? c.raw.length / BLOCK : 'fill' in c ? c.blocks : c.skip;
    const h = new Uint8Array(12), v = new DataView(h.buffer);
    v.setUint16(0, 'raw' in c ? 0xcac1 : 'fill' in c ? 0xcac2 : 0xcac3, true);
    v.setUint32(4, n, true); v.setUint32(8, 12 + body.length, true);
    parts.push(h, body);
    if ('raw' in c) expected.set(c.raw, at * BLOCK);
    if ('fill' in c) for (let j = 0; j < n * BLOCK; j += 4) expected.set(body, at * BLOCK + j);
    at += n;
  }
  const image = new Uint8Array(parts.reduce((s, p) => s + p.length, 0));
  let o = 0;
  for (const p of parts) { image.set(p, o); o += p.length; }
  return { image, expected };
}

async function flash(lk: FakeLk, part: string, image: Uint8Array) {
  const progress: number[] = [];
  await new Fastboot(lk).flashBlob(part, new Blob([image as Uint8Array<ArrayBuffer>]), (p) => progress.push(p));
  return progress;
}

describe('flashBlob against a Qualcomm LK fake', () => {
  it('flashes a small raw image in one download, as is', async () => {
    const lk = new FakeLk(0x100000, [['system', 64 * BLOCK]]);
    const img = rawImage(8);
    await flash(lk, 'system', img);
    expect(lk.downloads).toEqual([img.length]);
    expect(lk.parts.get('system')!.subarray(0, img.length)).toEqual(img);
    expect(lk.commands).toEqual(['getvar:has-slot:system', 'getvar:max-download-size', 'getvar:is-logical:system', 'download:00008000', 'flash:system']);
  });

  it('turns a raw image over max-download-size into sparse pieces that each fit', async () => {
    const max = 64 * 1024;
    const lk = new FakeLk(max, [['system', 64 * BLOCK]]);
    const img = rawImage(50);
    const progress = await flash(lk, 'system', img);
    expect(lk.downloads.length).toBeGreaterThan(3);
    for (const n of lk.downloads) expect(n).toBeLessThanOrEqual(max);
    expect(lk.parts.get('system')!.subarray(0, img.length)).toEqual(img);
    expect(progress.every((p, i) => i === 0 || p >= progress[i - 1]!)).toBe(true);
    expect(progress.at(-1)).toBeCloseTo(1);
  });

  it('splits a sparse image with fill and don\'t-care chunks and lands each at its offset', async () => {
    const max = 64 * 1024;
    const lk = new FakeLk(max, [['userdata', 256 * BLOCK]]);
    const { image, expected } = sparseImage([
      { raw: rawImage(10) }, { skip: 30 }, { fill: 0x12345678, blocks: 100 }, { raw: rawImage(20) }, { skip: 5 }, { raw: rawImage(3) },
    ], 0xee);
    await flash(lk, 'userdata', image);
    expect(lk.downloads.length).toBeGreaterThan(1);
    for (const n of lk.downloads) expect(n).toBeLessThanOrEqual(max);
    expect(lk.parts.get('userdata')!.subarray(0, expected.length)).toEqual(expected);
  });

  it('reads LK\'s padded "\\t0x..." max-download-size, and falls back when it is missing', async () => {
    const padded = new FakeLk(0x8000, [['system', 64 * BLOCK]]);
    await flash(padded, 'system', rawImage(16));
    expect(Math.max(...padded.downloads)).toBeLessThanOrEqual(0x8000);

    const none = new FakeLk(Number.MAX_SAFE_INTEGER, [['system', 64 * BLOCK]], '');
    await flash(none, 'system', rawImage(16));
    expect(none.downloads).toEqual([16 * BLOCK]); // 512 MiB default: one piece
  });

  it('a FAIL from flash: becomes an error', async () => {
    const lk = new FakeLk(0x100000, [['system', 4 * BLOCK]]);
    await expect(flash(lk, 'system', rawImage(8))).rejects.toThrow(/flash:system: size too large/);
  });

  // Upstream counted neither the chunk's own header nor the closing don't-care chunk, so a piece
  // could come out 24 bytes over the limit, and LK refuses that download ("data too large").
  it('keeps a piece within max-download-size when its chunks fill it exactly', async () => {
    const max = 64 * 1024;
    const lk = new FakeLk(max, [['userdata', 1024 * BLOCK]]);
    const fills: Chunk[] = Array.from({ length: 510 }, (_, i) => ({ fill: i, blocks: 1 }));
    const { image, expected } = sparseImage([...fills, { raw: rawImage(14) }, { raw: rawImage(2) }], 0xee);
    await flash(lk, 'userdata', image);
    for (const n of lk.downloads) expect(n).toBeLessThanOrEqual(max);
    expect(lk.parts.get('userdata')!.subarray(0, expected.length)).toEqual(expected);
  });

  // 7/8 of a limit that is not a multiple of the block size used to cut raw chunks mid-block.
  it('splits correctly when max-download-size is not a multiple of the block size', async () => {
    const lk = new FakeLk(100_000, [['system', 128 * BLOCK]]);
    const img = rawImage(100);
    await flash(lk, 'system', img);
    for (const n of lk.downloads) expect(n).toBeLessThanOrEqual(100_000);
    expect(lk.parts.get('system')!.subarray(0, img.length)).toEqual(img);
  });

  it('refuses a raw image that is not whole blocks when it has to be made sparse', async () => {
    const lk = new FakeLk(0x8000, [['system', 64 * BLOCK]]);
    await expect(flash(lk, 'system', new Uint8Array(16 * BLOCK + 100))).rejects.toThrow(/whole 4096-byte blocks/);
    expect(lk.commands.filter((c) => c.startsWith('flash:'))).toEqual([]);
  });

  // 7/8 of a limit under 4681 bytes, rounded down to 4096-byte blocks, is 0: splitBlob never advanced.
  it('refuses a max-download-size too small to hold one block', async () => {
    const lk = new FakeLk(4000, [['system', 64 * BLOCK]]);
    await expect(flash(lk, 'system', rawImage(4))).rejects.toThrow(/too small/);
    expect(lk.downloads).toEqual([]);
  });

  it('refuses an image with a sparse magic and a bad header, instead of flashing it raw', async () => {
    const lk = new FakeLk(0x100000, [['system', 64 * BLOCK]]);
    const { image } = sparseImage([{ raw: rawImage(2) }], 0xee);
    new DataView(image.buffer).setUint16(4, 2, true); // major version 2
    await expect(flash(lk, 'system', image)).rejects.toThrow(/Unsupported sparse image version 2/);
    expect(lk.downloads).toEqual([]);
  });

  it('bootBlob sends the image and boots it', async () => {
    const lk = new FakeLk(0x8000, [['system', 64 * BLOCK]]);
    await new Fastboot(lk).bootBlob(new Blob([rawImage(4) as Uint8Array<ArrayBuffer>]));
    expect(lk.commands.slice(-2)).toEqual(['download:00004000', 'boot']);
  });

  it('bootBlob refuses an image over max-download-size before reading it', async () => {
    const lk = new FakeLk(0x8000, [['system', 64 * BLOCK]]);
    const big = new Blob([new Uint8Array(0x9000)]);
    big.arrayBuffer = () => Promise.reject(new Error('read the whole image'));
    await expect(new Fastboot(lk).bootBlob(big)).rejects.toThrow(/larger than the fastboot buffer/);
    expect(lk.downloads).toEqual([]);
  });

  it('refuses a sparse magic with a header cut short, and still flashes a tiny raw image', async () => {
    const lk = new FakeLk(0x100000, [['system', 64 * BLOCK]]);
    const cut = sparseImage([{ raw: rawImage(1) }], 0xee).image.slice(0, 16);
    await expect(flash(lk, 'system', cut)).rejects.toThrow(/truncated sparse image header/);
    expect(lk.downloads).toEqual([]);

    await flash(lk, 'system', new Uint8Array([1, 2]));
    expect(lk.downloads).toEqual([2]);
    expect(lk.parts.get('system')!.subarray(0, 3)).toEqual(new Uint8Array([1, 2, 0xee]));
  });
});
