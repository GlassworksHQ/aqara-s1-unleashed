// kiln-core is shared by every frame, so nothing in it may name one. What the S1 needs lives in its
// recipe (frames/aqara-s1-plus/installer). Add a pattern here when a new frame brings new names.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';

const ROOT = join(__dirname, '..');
const SELF = relative(ROOT, __filename);

const FRAME_NAMES: [what: string, pattern: RegExp][] = [
  ['the S1 (s1Overlay, s1kiosk, s1-backup, S1 Plus)', /\bs1/i],
  ['Aqara and its packages', /aqara|inhome|com\.lumi|baidu/i],
  ['the S1 Kiosk app id', /sh\.edm|kiosk/i],
  ['the S1 chip, board and USB ids', /a133|sunxi|allwinner|r818|quad-core|1f3a|efe8/i],
  ['the S1 partitions and their sizes', /vbmeta_system|vbmeta_vendor|dtbo|0x0*2000000\b|0x0*60000000\b|\b33554432\b|\b1610612736\b/i],
  ['the S1 boot patch, radio and branding', /magisk|overlay|radio|otbr|zigbee|thread_router|glass.?tile/i],
  ['the S1 debloat list', /debloat/i],
];

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    if (n === 'node_modules') return [];
    return statSync(p).isDirectory() ? files(p) : [p];
  });
}

describe('kiln-core names no frame', () => {
  const sources = files(ROOT).filter((f) => relative(ROOT, f) !== SELF);

  it('has sources to check', () => {
    expect(sources.map((f) => relative(ROOT, f))).toEqual(expect.arrayContaining(['package.json', 'src/flow.ts', 'src/recipe.ts']));
  });

  it.each(FRAME_NAMES)('nothing names %s', (_, pattern) => {
    const hits = sources.flatMap((f) => readFileSync(f, 'utf8').split('\n')
      .map((line, i) => (pattern.test(line) ? `${relative(ROOT, f)}:${i + 1}: ${line.trim()}` : ''))
      .filter(Boolean));
    expect(hits).toEqual([]);
  });
});
