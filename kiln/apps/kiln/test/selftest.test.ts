// @vitest-environment jsdom
// ?demo=selftest ends in PASS or FAIL, also when the staged manifest can't be read.
import { afterEach, expect, it, vi } from 'vitest';
import { demoSession, enterDemo } from '../src/wizard/demo';

afterEach(() => vi.unstubAllGlobals());

it('reports FAIL when the manifest is missing, instead of staying on the running screen', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('missing', { status: 404 })));
  const s = demoSession('selftest');
  await enterDemo(s, 'selftest');
  expect(document.title).toBe('selftest FAIL');
  expect(s.store.state.log.some((l) => /FAIL manifest: installer files are missing \(manifest\.json: HTTP 404\)/.test(l))).toBe(true);
  s.close();
});
