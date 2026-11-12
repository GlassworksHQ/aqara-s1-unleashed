// The S1's recovery data lives with its frame; the design prototype keeps its own copy (with a
// `photo` path for its preview page). They must not drift apart.
import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';

const read = (p: string) => JSON.parse(readFileSync(new URL(`../../../../${p}`, import.meta.url), 'utf8')) as Record<string, unknown>;

it('the design copy of the S1 recovery data equals the frame\'s, photo aside', () => {
  const { photo, ...design } = read('docs/design/kiln/recovery/aqara-s1-plus.json');
  expect(photo).toBeTypeOf('string');
  expect(design).toEqual(read('frames/aqara-s1-plus/design/recovery.json'));
});
