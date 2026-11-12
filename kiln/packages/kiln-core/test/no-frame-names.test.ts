// kiln-core is shared by every frame, so nothing in it may name one. What the S1 needs lives in its
// recipe (frames/aqara-s1-plus/installer). Add a pattern here when a new frame brings new names.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';

const ROOT = join(__dirname, '..');
const SELF = relative(ROOT, __filename);

/** Each pattern with names it must catch, one per alternative, so a weakened pattern fails a test. */
const FRAME_NAMES: [what: string, pattern: RegExp, catches: string[]][] = [
  ['the S1 (s1Overlay, s1kiosk, s1-backup, S1 Plus)', /\bs1/i, ['s1Overlay', 's1-backup', 'S1 Plus']],
  ['Aqara and its packages', /aqara|inhome|com\.lumi|baidu/i, ['Aqara', 'inhome', 'com.lumi.ota', 'com.baidu.input_auto']],
  ['the S1 Kiosk app id', /sh\.edm|kiosk/i, ['sh.edm.s1kiosk', 'kiosk-apk']],
  ['the S1 chip, board and USB ids', /a133|sunxi|allwinner|r818|quad-core|1f3a|efe8/i,
    ['A133', 'sunxi', 'Allwinner', 'R818', 'QUAD-CORE', '0x1f3a', '0xefe8']],
  ['the S1 partitions and their sizes', /vbmeta_system|vbmeta_vendor|dtbo|0x0*2000000\b|0x0*60000000\b|\b33554432\b|\b1610612736\b/i,
    ['vbmeta_system', 'vbmeta_vendor', 'dtbo', '0x2000000', '0x60000000', '33554432', '1610612736']],
  ['the S1 boot patch, radio and branding', /magisk|overlay|radio|otbr|zigbee|thread_router|glass.?tile/i,
    ['isMagiskPatched', 'overlay.d', 'radio-rc', 'otbr-agent', 'zigbee_host', 'thread_router', 'Glass Tile', 'glass-tile']],
  ['the S1 debloat list', /debloat/i, ['DEBLOAT']],
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

  it.each(FRAME_NAMES.flatMap(([what, pattern, catches]) => catches.map((name) => [name, what, pattern] as const)))(
    'catches %s (%s)', (name, _, pattern) => {
      expect(pattern.test(name)).toBe(true);
    });

  it.each(FRAME_NAMES)('nothing names %s', (_, pattern) => {
    const hits = sources.flatMap((f) => readFileSync(f, 'utf8').split('\n')
      .map((line, i) => (pattern.test(line) ? `${relative(ROOT, f)}:${i + 1}: ${line.trim()}` : ''))
      .filter(Boolean));
    expect(hits).toEqual([]);
  });
});
