// Kiln's favicon is the small cut of Kiln's mark (the ember floor), synced from glassworks.dev, and
// not the furnace, which is Kiln's UI icon (glassworks.dev/brand).
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { kilnPlates } from '@glassworks/brand';

const read = (p: string) => readFileSync(new URL(p, import.meta.url), 'utf8');
const layout = read('../src/app/layout.tsx');
const favicon = read('../../../packages/brand/src/favicon-kiln.svg');
const appIcon = read('../../../packages/brand/src/app-icon-kiln.svg');
const paths = (svg: string) => [...svg.matchAll(/<path d="([^"]+)"([^>]*)\/>/g)].map((m) => ({ d: m[1], attrs: m[2]! }));

describe('the favicon', () => {
  it('is the synced Kiln favicon, first in the layout, and the furnace is gone', () => {
    expect(layout).toMatch(/import favicon from '@glassworks\/brand\/favicon-kiln\.svg';/);
    expect(layout).toMatch(/icon: \[\s*\{ url: favicon\.src, type: 'image\/svg\+xml', sizes: 'any' \}/);
    expect(layout).not.toMatch(/brand\/icon-kiln/); // the furnace
  });

  it('is the small cut of the Kiln mark: two plates, the bottom one fired', () => {
    const want = kilnPlates('small');
    const got = paths(favicon);
    expect(got.map((p) => p.d)).toEqual(want.map((p) => p.d));
    expect(got.filter((p) => p.attrs.includes('fill="url(#lit)"'))).toHaveLength(1);
    expect(favicon).toMatch(/<linearGradient id="lit"[^>]*><stop offset="0" stop-color="#fdba74"\/><stop offset="1" stop-color="#f43f5e"\/>/);
  });

  it('follows the OS theme', () => {
    expect(favicon).toMatch(/\.i\{stroke:#0c0e13\}@media \(prefers-color-scheme:dark\)\{\.i\{stroke:#f4f4f5\}\}/);
    expect(paths(favicon).filter((p) => p.attrs.includes('class="i"'))).toHaveLength(1);
  });

  it('has the full Kiln mark in the 512 app icon', () => {
    expect(appIcon).toMatch(/viewBox="0 0 512\.00 512\.00"/);
    expect(paths(appIcon).map((p) => p.d)).toEqual(kilnPlates('full').map((p) => p.d));
  });
});
