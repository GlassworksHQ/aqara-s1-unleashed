// The boot animation the recipe ships (branding/glass-tile/bootanimation.zip, built by branding/boot/generate.py)
// against what the S1 allows: it rides in the 32 MiB boot image, and Android 10's bootanimation keeps
// every frame of a looping part (count != 1) as a GPU texture until boot completes.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { zipEntries } from '@glassworks/bootpatch';

const ZIP = readFileSync(join(__dirname, '../../../../branding/glass-tile/bootanimation.zip'));
// With 9 MB the patched boot image keeps about 1.8 MB free (measured 2026-11-12, branding/glass-tile/README.md).
const ZIP_BUDGET = 9_000_000;

const entries = zipEntries(new Uint8Array(ZIP));
const read = (name: string) => {
  const e = entries.get(name);
  if (!e) throw new Error(`${name} missing`);
  return new TextDecoder().decode(ZIP.subarray(e.localOffset + 30 + ZIP.readUInt16LE(e.localOffset + 26) + ZIP.readUInt16LE(e.localOffset + 28)).subarray(0, e.size));
};
const [header, ...partLines] = read('desc.txt').trim().split('\n');
const parts = partLines.map((l) => { const [type, count, pause, path] = l.split(' '); return { type, count: Number(count), pause: Number(pause), path: path! }; });
const frames = (path: string) => [...entries.keys()].filter((n) => n.startsWith(`${path}/`) && n.endsWith('.png'));

describe('bootanimation.zip', () => {
  it('fits the boot image budget', () => expect(ZIP.length).toBeLessThanOrEqual(ZIP_BUDGET));
  it('is the full 1440x720 screen at 30 fps, every entry stored', () => {
    expect(header).toBe('1440 720 30');
    expect([...entries.values()].filter((e) => e.method !== 0)).toEqual([]);
  });
  it('places every frame with one trim.txt line', () => {
    for (const path of new Set(parts.map((p) => p.path))) {
      expect(frames(path).length).toBeGreaterThan(0);
      expect(read(`${path}/trim.txt`).trim().split('\n')).toHaveLength(frames(path).length);
    }
  });
  it('holds no more than one frame as a texture at a time', () => {
    const cached = parts.filter((p) => p.count !== 1 && frames(p.path).length > 1);
    expect(cached).toEqual([]);
  });
  it('plays the intro to the end and stops the loop as soon as the panel is ready', () => {
    expect(parts[0]).toMatchObject({ type: 'c', count: 1, path: 'part0' });
    expect(parts.slice(1).every((p) => p.type === 'p')).toBe(true);
  });
});
