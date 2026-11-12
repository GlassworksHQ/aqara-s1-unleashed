// #32: Kiln reads the brand's tokens; no colour is copied in by hand. The one exception is the drawn
// device's paint (src/recovery/materials.ts), which depicts hardware, not the brand.
import { globSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, it } from 'vitest';

const src = fileURLToPath(new URL('../src', import.meta.url));
const ALLOWED = ['recovery/materials.ts'];
const COLOUR = /#[0-9a-f]{3,8}\b|\brgba?\(|\bhsla?\(/gi;

it('has no literal colours outside the brand tokens', () => {
  const files = globSync('**/*.{ts,tsx,css}', { cwd: src }).filter((f) => !ALLOWED.includes(f));
  expect(files.length).toBeGreaterThan(10);
  const hits = files.flatMap((f) => readFileSync(join(src, f), 'utf8').split('\n')
    .flatMap((line, i) => [...line.matchAll(COLOUR)].map((m) => `${f}:${i + 1} ${m[0]}`)));
  expect(hits).toEqual([]);
});
