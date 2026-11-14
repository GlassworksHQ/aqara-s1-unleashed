// Each frame's design data (frames/<id>/design/frame.json, format in frames/README.md): Kiln lists
// every frame folder, the data is complete, and the 3D mockup draws from it.
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { ABIS, PANE_IDS, RATING_KEYS, framePanes, geometry, isCapability, isPaneAlias, type FrameDesign } from '@glassworks/frame-card';
import { FRAMES, RENDERS } from '../src/frames/catalog';

const frames = fileURLToPath(new URL('../../../../frames', import.meta.url));
const folders = readdirSync(frames, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name);
/** geometry() shades the sides by mixing two of these, so they must be #rrggbb */
const COLOUR = /^#[0-9a-f]{6}$/i;
const read = (id: string) => JSON.parse(readFileSync(join(frames, id, 'design', 'frame.json'), 'utf8')) as FrameDesign;

/** A WebP's pixel size, from its first chunk (VP8, VP8L or VP8X), or undefined if it isn't one. */
function webpSize(b: Buffer): { width: number; height: number } | undefined {
  if (b.toString('ascii', 0, 4) !== 'RIFF' || b.toString('ascii', 8, 12) !== 'WEBP') return undefined;
  const chunk = b.toString('ascii', 12, 16);
  if (chunk === 'VP8 ') return { width: b.readUInt16LE(26) & 0x3fff, height: b.readUInt16LE(28) & 0x3fff };
  if (chunk === 'VP8L') {
    const v = b.readUInt32LE(21);
    return { width: (v & 0x3fff) + 1, height: ((v >> 14) & 0x3fff) + 1 };
  }
  if (chunk === 'VP8X') return { width: b.readUIntLE(24, 3) + 1, height: b.readUIntLE(27, 3) + 1 };
  return undefined;
}
/** The size render.mjs draws a frame without one screen at (a common 10" tablet). */
const TABLET = { width: 1920, height: 1200 };

describe('frame designs', () => {
  it('has design data for every frame folder', () => {
    const missing = folders.filter((d) => !existsSync(join(frames, d, 'design', 'frame.json')));
    expect(missing, 'frames/<id> without design/frame.json').toEqual([]);
  });

  it('lists every frame that has design data, in Kiln\'s catalog', () => {
    expect(folders.length).toBeGreaterThanOrEqual(2);
    expect(FRAMES.map((f) => f.id).sort()).toEqual([...folders].sort());
  });

  it.each(folders)('%s: complete, named after its folder, and drawable', (id) => {
    const f = read(id);
    expect(f.id).toBe(id);
    for (const k of ['name', 'summary'] as const) expect(f[k], k).toMatch(/\S/);
    expect(['planned', 'development', 'alpha', 'beta', 'stable']).toContain(f.status);
    if (f.brand !== undefined) expect(f.name.startsWith(`${f.brand} `), `brand "${f.brand}" starts name "${f.name}"`).toBe(true);
    if (f.tagline !== undefined) {
      expect(f.tagline, 'tagline').toMatch(/\S/);
      expect(f.tagline.length, 'tagline: at most 50 characters (glassworks.dev\'s pnpm brand checks the drawn width)').toBeLessThanOrEqual(50);
    }
    expect(Object.keys(f.ratings).sort()).toEqual([...RATING_KEYS].sort());
    // it rates only panes that exist (by id or an old alias); every other pane is worked out, with a reason (#231)
    expect(Object.keys(f.panes).filter((k) => !PANE_IDS.includes(k) && !isPaneAlias(k)), 'panes rated that the app has no pane for').toEqual([]);
    expect((f.provides ?? []).filter((c) => !isCapability(c)), 'provides: unknown capability words').toEqual([]);
    for (const p of framePanes(f)) {
      expect(p.support, p.pane.id).toBeTruthy();
      if (p.derived) expect(p.reason, `${p.pane.id}: a derived rating says why`).toMatch(/\S/);
    }
    expect(f.abis?.length, 'abis').toBeGreaterThan(0);
    expect(f.abis?.filter((a) => !(ABIS as readonly string[]).includes(a)), 'abis Android doesn\'t name').toEqual([]);
    expect(new Set(f.abis).size, 'abis listed twice').toBe(f.abis?.length);
    if (f.recovery) expect(existsSync(join(frames, id, 'design', f.recovery)), f.recovery).toBe(true);
    if (f.kiln) expect(f.kiln).toBe(`/${id}`);
    if (f.kiln) expect(f.install.href, 'install.href on a frame Kiln installs').toBeUndefined();
    expect([f.model.sides.dark, f.model.sides.light, ...(f.model.parts ?? []).map((p) => p.color)].filter((c) => !COLOUR.test(c)), 'colours').toEqual([]);
    for (const aspect of [2, 0.8]) {
      const g = geometry(f.model, aspect);
      expect(g.faces.length).toBeGreaterThan(0);
      expect(JSON.stringify(g)).not.toMatch(/NaN|Infinity/);
    }
  });

  it.each(folders)('%s: a render of the showcase dashboard at its screen size, shown on its card', (id) => {
    const f = read(id);
    expect(f.render, 'frame.json render').toBe('render.webp');
    const b = readFileSync(join(frames, id, 'design', f.render!));
    expect(webpSize(b)).toEqual(f.display ?? TABLET);
    expect(b.length, 'bytes').toBeLessThan(80 * 1024);
    expect(RENDERS[id], 'Kiln imports it').toMatch(/render/);
  });

  it('the S1 is installable from Kiln; the planned frames aren\'t', () => {
    expect(read('aqara-s1-plus')).toMatchObject({ status: 'beta', kiln: '/aqara-s1-plus', recovery: 'recovery.json' });
    expect(read('aqara-s1-plus').abis, 'the S1 runs a 32-bit userspace').toEqual(['armeabi-v7a']);
    for (const id of ['lululemon-mirror', 'generic-android']) {
      expect(read(id), id).toMatchObject({ status: 'planned' });
      expect(read(id).kiln, id).toBeUndefined();
    }
  });
});
