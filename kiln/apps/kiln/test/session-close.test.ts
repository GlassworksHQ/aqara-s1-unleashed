// A replaced session lets go of the panel: no Home Assistant polling, no open channels. And a sign-in
// waiting on its tab counts as running.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { registry, failureLog } from '../src/wizard/registry';
import { status } from '../src/wizard/routes';
import { demoSession } from '../src/wizard/demo';
import { Store } from '../src/wizard/session';
import { explain } from '../src/wizard/explain';
import { initial } from '../src/wizard/state';

// A failed expect must not leave a stubbed window or fake timers to the next test.
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); registry.clear(); });

describe('Session.close', () => {
  it('stops the Home Assistant poll and closes the setup channel and the panel', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] }); // the poll's clock; the simulated panel's replies stay real
    const s = demoSession('home-assistant');
    await s.route('home-assistant');
    expect(s.store.state.setup.phase).toBe('ha');
    const internals = s as unknown as { withSetup: () => Promise<unknown>; setup: { close(): Promise<void> }; panel: { close?: () => Promise<void> } };
    const poll = vi.spyOn(internals, 'withSetup');
    const setupClose = vi.spyOn(internals.setup, 'close');
    const panelClose = vi.fn(async () => {});
    internals.panel.close = panelClose;
    vi.advanceTimersByTime(3000);
    expect(poll).toHaveBeenCalledTimes(1); // it polls every 3 s while open
    s.close();
    expect(setupClose).toHaveBeenCalled();
    expect(panelClose).toHaveBeenCalled();
    vi.advanceTimersByTime(9000);
    expect(poll).toHaveBeenCalledTimes(1);
    expect(s.isClosed).toBe(true);
  });

  it('is called when a new session replaces the current one', () => {
    const one = demoSession('connect');
    const close = vi.spyOn(one, 'close');
    registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: one });
    registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: one });
    expect(close).not.toHaveBeenCalled();
    registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: demoSession('connect') });
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('reports a sign-in tab the browser blocked, instead of waiting on it', async () => {
    const s = demoSession('home-assistant');
    await s.route('home-assistant');
    (s as unknown as { d: { openTab: () => null } }).d.openTab = () => null;
    vi.stubGlobal('window', { open: () => null }); // the pop-up blocker said no, twice
    s.signIn('http://homeassistant.local:8123');
    await vi.waitFor(() => expect(s.store.state.setup).toMatchObject({ phase: 'ha', signingIn: false, error: expect.stringMatching(/blocked the sign-in tab/) }));
    expect(status(s.store.state)).toBe('idle');
    s.close();
  });

  it('counts a sign-in waiting on its tab as running', async () => {
    const s = demoSession('home-assistant');
    await s.route('home-assistant');
    expect(status(s.store.state)).toBe('idle');
    (s as unknown as { d: { openTab: () => null } }).d.openTab = () => null;
    s.signIn('http://homeassistant.local:8123');
    expect(s.store.state.setup).toMatchObject({ phase: 'ha', signingIn: true });
    expect(status(s.store.state)).toBe('running');
    s.close();
  });
});

describe('the last failure', () => {
  const failing = () => {
    const store = new Store(initial());
    store.dispatch({ type: 'start', flow: 'install', now: 0 });
    store.dispatch({ type: 'log', line: '10:00:00 boot saved, 32 MiB' });
    store.dispatch({ type: 'fail', failure: explain(new Error('transferIn: the device was disconnected'), 2, 'none') });
    return { store, close: () => {} };
  };

  it('is kept when the person moves on from the Error screen, and dropped when a run starts', () => {
    const session = failing();
    registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: session as never });
    expect(registry.lastFailure()).toBeUndefined();
    session.store.dispatch({ type: 'go', screen: 'connect' }); // Try again
    const f = registry.lastFailure()!;
    expect(f.failure.title).toBe('It disconnected.');
    expect(failureLog(f)).toMatch(/^Kiln, Aqara S1 Plus\nStopped .+: It disconnected\.\nNothing was written\. .+\nError: transferIn: the device was disconnected\n\nTechnical log:\n10:00:00 boot saved, 32 MiB\n$/);
    session.store.dispatch({ type: 'start', flow: 'install', now: 1 });
    expect(registry.lastFailure()).toBeUndefined();
  });

  it('is kept when a new session replaces one that failed', () => {
    registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: failing() as never });
    registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: { store: new Store(initial()), close: () => {} } as never });
    expect(registry.lastFailure()?.failure.title).toBe('It disconnected.');
  });
});
