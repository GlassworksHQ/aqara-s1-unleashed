// Two CSS rules the screens rely on. jsdom has no layout, so these read the rules; the screenshots
// check them in Chrome.
import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';

const css = readFileSync(new URL('../src/app/globals.css', import.meta.url), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
/** The declarations of every rule whose selector is exactly `selector`. */
const rules = (selector: string) => [...css.matchAll(/(?<=^|\})\s*([^{}]+?)\s*\{([^}]*)\}/g)]
  .filter((m) => m[1] === selector)
  .map((m) => Object.fromEntries(m[2]!.split(';').map((d) => d.split(':').map((x) => x.trim())).filter(([k]) => k)));

it('the sheet (your turn, the photo) is no taller than the viewport, and scrolls', () => {
  const sheet = rules('.sheet');
  expect(sheet).toHaveLength(1); // one rule, so nothing later undoes it
  expect(sheet[0]).toMatchObject({ position: 'fixed', 'max-height': 'calc(100dvh - 32px)', 'overflow-y': 'auto' });
});

it('a disabled link looks off, as a disabled button does', () => {
  const link = rules('.link:disabled');
  expect(link).toHaveLength(1);
  expect(link[0]).toMatchObject({ opacity: rules('.btn:disabled')[0]!.opacity, cursor: 'default', 'text-decoration': 'none' });
  expect(css.indexOf('.link:disabled')).toBeGreaterThan(css.indexOf('.link:hover')); // after hover, so hovering doesn't underline it
});

it('the ember fill is for buttons only, and covers the button to its border', () => {
  const filled = [...css.matchAll(/(?<=^|\})\s*([^{}]+?)\s*\{([^}]*--ember-gradient[^}]*)\}/g)];
  expect(filled.map((m) => m[1])).toEqual(['.btn.ember']); // not the recovery player's current step
  // the default padding-box origin repeats the gradient under the 1px transparent border: a sliver of
  // the far colour at each end of the pill
  expect(rules('.btn.ember')[0]!.background).toMatch(/border-box/);
});
