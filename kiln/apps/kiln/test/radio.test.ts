// The radio step (simulated panel: setRadio answers after 1.5 s).
import { describe, expect, it, vi } from 'vitest';
import type { SetupState } from '@glassworks/setup-channel';
import { demoSession } from '../src/wizard/demo';

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
const panel = (s: object) => (s as { setup: { state: SetupState } }).setup.state;

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

  it('answers the panel\'s own radio step when a fresh panel keeps Off', async () => {
    const s = demoSession('radio');
    await s.route('radio');
    expect(panel(s).radio).toMatchObject({ mode: 'off', answered: false });
    await s.click('radio', () => s.applyRadio('off'));
    expect(panel(s).radio).toMatchObject({ mode: 'off', answered: true });
    expect(s.store.state.screen).toBe('done');
    s.close();
  }, 10_000);

  it('sends nothing for a kept mode to an app that doesn\'t say whether the step is answered', async () => {
    const s = demoSession('radio');
    await s.route('radio');
    delete panel(s).radio.answered; // apps before #155: a same-mode request restarts their radio
    const sent = vi.spyOn(s as unknown as { withSetup: () => Promise<unknown> }, 'withSetup');
    await s.click('radio', () => s.applyRadio('off'));
    expect(sent).not.toHaveBeenCalled();
    expect(s.store.state.screen).toBe('done');
    s.close();
  });
});
