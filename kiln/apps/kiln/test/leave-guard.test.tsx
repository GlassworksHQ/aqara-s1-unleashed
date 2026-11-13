// @vitest-environment jsdom
// While a session writes to the panel, leaving asks first and the screen stays awake, on any route:
// LeaveGuard sits in the root layout and reads the registry, so it holds on the picker too.
import { act, cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LeaveGuard } from '../src/components/LeaveGuard';
import { registry } from '../src/wizard/registry';
import { Store } from '../src/wizard/session';
import { initial } from '../src/wizard/state';

afterEach(() => { cleanup(); registry.clear(); });

const leave = () => {
  const e = new Event('beforeunload', { cancelable: true });
  dispatchEvent(e);
  return e.defaultPrevented;
};

describe('LeaveGuard', () => {
  it('asks before leaving, and keeps the screen on, only while writing', async () => {
    const release = vi.fn(async () => {});
    const request = vi.fn(async () => ({ release }));
    Object.defineProperty(navigator, 'wakeLock', { configurable: true, value: { request } });
    const store = new Store(initial());
    registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: { store, close: () => {} } as never });
    render(<LeaveGuard />); // as on the picker: no wizard mounted
    expect(leave()).toBe(false);
    act(() => {
      store.dispatch({ type: 'start', flow: 'install', now: 0 });
      store.dispatch({ type: 'progress', p: { overall: 0.7, phase: 'flash', phasePct: 0.3, title: 'Writing the new software', sub: '' } });
    });
    expect(leave()).toBe(true);
    await act(() => Promise.resolve());
    expect(request).toHaveBeenCalledWith('screen');
    act(() => store.dispatch({ type: 'done', done: { title: 'Done.', body: '' } }));
    expect(leave()).toBe(false);
    expect(release).toHaveBeenCalled();
  });

  it('takes the wake lock again when the page comes back during a write', async () => {
    const locks: { released: boolean; release: () => Promise<void> }[] = [];
    const request = vi.fn(async () => { const l = { released: false, release: async () => { l.released = true; } }; locks.push(l); return l; });
    Object.defineProperty(navigator, 'wakeLock', { configurable: true, value: { request } });
    const store = new Store(initial());
    registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: { store, close: () => {} } as never });
    render(<LeaveGuard />);
    act(() => {
      store.dispatch({ type: 'start', flow: 'install', now: 0 });
      store.dispatch({ type: 'progress', p: { overall: 0.7, phase: 'flash', phasePct: 0.3, title: 'Writing the new software', sub: '' } });
    });
    await act(() => Promise.resolve());
    expect(request).toHaveBeenCalledTimes(1);
    const visibility = (v: string) => { Object.defineProperty(document, 'visibilityState', { configurable: true, value: v }); document.dispatchEvent(new Event('visibilitychange')); };
    locks[0]!.released = true; // the browser drops it when the tab is hidden
    visibility('hidden');
    visibility('visible');
    await act(() => Promise.resolve());
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('holds one wake lock, however fast the tab comes back', async () => {
    const locks: { released: boolean; release: () => Promise<void> }[] = [];
    const answers: (() => void)[] = [];
    const request = vi.fn(() => new Promise((r) => {
      const l = { released: false, release: async () => { l.released = true; } };
      locks.push(l);
      answers.push(() => r(l));
    }));
    Object.defineProperty(navigator, 'wakeLock', { configurable: true, value: { request } });
    const store = new Store(initial());
    registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: { store, close: () => {} } as never });
    render(<LeaveGuard />);
    act(() => {
      store.dispatch({ type: 'start', flow: 'install', now: 0 });
      store.dispatch({ type: 'progress', p: { overall: 0.7, phase: 'flash', phasePct: 0.3, title: 'Writing the new software', sub: '' } });
    });
    const visibility = (v: string) => { Object.defineProperty(document, 'visibilityState', { configurable: true, value: v }); document.dispatchEvent(new Event('visibilitychange')); };
    visibility('hidden');
    visibility('visible'); // back before the first request answered
    visibility('hidden');
    visibility('visible');
    expect(request).toHaveBeenCalledTimes(1);
    await act(async () => { answers.shift()!(); });
    locks[0]!.released = true; // hidden again: the browser drops it
    visibility('hidden');
    visibility('visible');
    visibility('hidden');
    visibility('visible');
    expect(request).toHaveBeenCalledTimes(2);
    await act(async () => { answers.shift()!(); });
    act(() => store.dispatch({ type: 'done', done: { title: 'Done.', body: '' } }));
    expect(locks.every((l) => l.released)).toBe(true); // none left holding the screen on
  });

  it('follows a new session', () => {
    render(<LeaveGuard />);
    const store = new Store(initial());
    act(() => { registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: { store, close: () => {} } as never }); });
    act(() => {
      store.dispatch({ type: 'start', flow: 'undo', now: 0 });
      store.dispatch({ type: 'progress', p: { overall: 0.4, phase: 'flash', phasePct: 0.3, title: 'Putting the original software back', sub: '' } });
    });
    expect(leave()).toBe(true);
  });
});
