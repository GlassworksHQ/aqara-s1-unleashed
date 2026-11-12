// The radio step (simulated panel: setRadio answers after 1.5 s).
import { describe, expect, it, vi } from 'vitest';
import { demoSession } from '../src/wizard/demo';

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe('the radio step', () => {
  it('keeps Skip from finishing while a radio change is pending, and a late answer changes nothing after Done', async () => {
    const s = demoSession('radio');
    await s.route('radio');
    expect(s.store.state.setup.phase).toBe('radio');
    const change = s.click('radio', () => s.applyRadio('zigbee_tcp'));
    await s.click('finish', () => s.finish()); // Skip, while the change is pending
    expect(s.store.state.screen).toBe('setup'); // ignored: one action at a time
    s.finish(); // even if something finishes anyway…
    expect(s.store.state.screen).toBe('done');
    await change; // …the radio's late answer doesn't bring a setup screen back over Done
    expect(s.store.state.screen).toBe('done');
    s.close();
  }, 10_000);

  it('shows a failure when the app doesn\'t answer, instead of waiting forever', async () => {
    const s = demoSession('radio');
    await s.route('radio');
    vi.spyOn(s as unknown as { withSetup: () => Promise<unknown> }, 'withSetup').mockRejectedValue(new Error('the app restarted and is not answering'));
    await s.radio();
    expect(s.store.state.screen).toBe('error');
    expect(s.store.state.failure).toMatchObject({ at: 3, raw: 'the app restarted and is not answering' });
    s.close();
  });
});
