// Kiln's dialogs (the "your turn" sheet, the recovery-button photo) fit a short or zoomed screen:
// the sheet scrolls inside the viewport, so its button and Close stay reachable. jsdom has no
// layout, so this reads the rule; the screenshots check it in Chrome.
import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';

const css = readFileSync(new URL('../src/app/globals.css', import.meta.url), 'utf8');

it('the sheet is no taller than the viewport, and scrolls', () => {
  const rules = [...css.matchAll(/(?:^|\})\s*\.sheet\s*\{([^}]*)\}/g)].map((m) => m[1]!);
  expect(rules).toHaveLength(1); // one rule, so nothing later undoes it
  const decl = Object.fromEntries(rules[0]!.replace(/\/\*[\s\S]*?\*\//g, '').split(';').map((d) => d.split(':').map((x) => x.trim())).filter(([k]) => k));
  expect(decl['max-height']).toBe('calc(100dvh - 32px)');
  expect(decl['overflow-y']).toBe('auto');
  expect(decl.position).toBe('fixed');
});
