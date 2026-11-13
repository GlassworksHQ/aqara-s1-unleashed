// @vitest-environment jsdom
// Navigation against a real Session (useRouteSync with a fake router): it never starts or repeats a
// flow, a running install stays on screen, and a new install gets a clean session.
import { StrictMode, useState, useSyncExternalStore } from 'react';
import { act, cleanup, render } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { s1Plus } from '@glassworks/aqara-s1-plus-installer';
import { FelDevice } from '@glassworks/fel';
import { FakeBrom, makeSpl } from '@glassworks/fel/fakes';
import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { registry, type Entry } from '../src/wizard/registry';
import { Session } from '../src/wizard/session';
import { useRouteSync } from '../src/wizard/useRouteSync';
import { demoSession } from '../src/wizard/demo';

const BASE = '/aqara-s1-plus';

beforeAll(() => { vi.spyOn(console, 'error').mockImplementation(() => {}); });
afterEach(() => { cleanup(); registry.clear(); location.hash = ''; });

const installedPanel = { run: async () => ({ out: '1\n', code: 0 }), push: async () => {} };

function session(requestFel = vi.fn(() => Promise.resolve(new FelDevice(new FakeBrom()))), panel?: typeof installedPanel) {
  const knownAdb = vi.fn(async () => panel);
  const s = new Session({
    recipe: s1Plus, frame: s1PlusUi, requestFel,
    asset: async (n) => (n === 'uboot-spl' ? makeSpl(0x9000) : new Uint8Array(200_000).fill(7)),
    hasAsset: async () => true,
    backups: { save: async () => {}, original: async () => undefined, files: async () => [] },
    loadKey: async () => ({ publicKey: 'QAAAA test@example' }) as never,
    sleep: () => new Promise((r) => setTimeout(r, 0)),
    devices: { knownFastboot: async () => undefined, requestFastboot: () => new Promise(() => {}), knownAdb, requestAdb: () => new Promise(() => {}) },
  });
  return { s, requestFel, knownAdb };
}

/** The app's route sync, with the address bar and the browser's history as state. */
function harness(first: { s: Session }, path: string, strict = false) {
  const nav = { path, replaced: [] as string[], history: [path], set: (_: string) => {} };
  const renewals: Entry[] = [];
  registry.set({ base: BASE, name: 'Aqara S1 Plus', session: first.s });
  const renew = () => { const e = registry.set({ base: BASE, name: 'Aqara S1 Plus', session: session().s }); renewals.push(e); return e; };
  const router = {
    replace: (url: string) => { nav.replaced.push(url); nav.history[nav.history.length - 1] = url; nav.set(url); },
    push: (url: string) => { nav.history.push(url); nav.set(url); },
  };
  function Sync() {
    const [p, setP] = useState(path);
    nav.set = setP;
    nav.path = p;
    const entry = useSyncExternalStore(registry.subscribe, registry.get)!;
    useRouteSync(entry, p, router, renew);
    return null;
  }
  render(strict ? <StrictMode><Sync /></StrictMode> : <Sync />);
  const go = (to: string) => act(() => { nav.history.push(BASE + to); nav.set(BASE + to); });
  /** the browser's Back button */
  const back = () => act(() => { nav.history.pop(); nav.set(nav.history.at(-1)!); });
  return { nav, go, back, renewals, state: () => registry.get()!.session.store.state };
}

const settle = () => act(() => new Promise((r) => setTimeout(r, 30)));
const until = async (f: () => boolean) => act(async () => { for (let i = 0; i < 400 && !f(); i++) await new Promise((r) => setTimeout(r, 5)); });

describe('navigation', () => {
  it('never starts a second flow, and keeps a running install on screen', async () => {
    const one = session();
    const h = harness(one, BASE);
    await settle();
    await act(() => { void one.s.click('connect', () => one.s.connectFel()); return Promise.resolve(); });
    await until(() => !!h.state().ask);
    expect(h.state()).toMatchObject({ screen: 'firing', ask: { kind: 'fastboot' } });
    expect(h.nav.path).toBe(`${BASE}/install`);
    for (const to of ['', '/undo', '/set-up', '/resume', '/done', '/undo/restore', '/install']) {
      h.go(to);
      await settle();
      expect(h.nav.path, `after ${to || '/'}`).toBe(`${BASE}/install`);
    }
    expect(one.requestFel).toHaveBeenCalledTimes(1);
    expect(h.renewals).toHaveLength(0);
    expect(registry.get()!.session).toBe(one.s);
    expect(h.state()).toMatchObject({ screen: 'firing', ask: { kind: 'fastboot' } });
  });

  it('sends a deep link into a flow, with nothing running, to the start', async () => {
    const h = harness(session(), `${BASE}/install`);
    await settle();
    expect(h.nav.path).toBe(BASE);
    expect(h.state().screen).toBe('connect');
  });

  it('enters an entry route on the same session while nothing began', async () => {
    const one = session();
    const h = harness(one, BASE);
    await settle();
    h.go('/undo');
    await settle();
    expect(h.state().screen).toBe('undo');
    expect(registry.get()!.session).toBe(one.s);
  });

  it('starts a new install on a clean session once the last one finished', async () => {
    const one = session(undefined, installedPanel);
    const h = harness(one, `${BASE}/set-up/wifi`);
    await until(() => one.s.started && h.state().setup.phase === 'wifi');
    act(() => one.s.store.dispatch({ type: 'done', done: { title: 'Done.', body: '' } }));
    await settle();
    expect(h.nav.path).toBe(`${BASE}/done`);
    h.go('/install'); // back into the finished flow: the result stays
    await settle();
    expect(h.nav.path).toBe(`${BASE}/done`);
    h.go(''); // the start: a new install, on a new session
    await settle();
    expect(h.renewals).toHaveLength(1);
    expect(registry.get()!.session).not.toBe(one.s);
    expect(h.state().screen).toBe('connect');
    expect(one.s.store.state.screen).toBe('done');
  });

  it('Back in set-up returns to the step before, on the same session', async () => {
    const one = { s: demoSession('app') };
    const h = harness(one, `${BASE}/set-up/wifi`);
    await until(() => h.state().setup.phase === 'wifi' && h.state().busy === undefined);
    await act(() => one.s.click('ha', () => one.s.homeAssistant()));
    await until(() => h.state().setup.phase === 'ha');
    expect(h.nav.history.slice(-2)).toEqual([`${BASE}/set-up/wifi`, `${BASE}/set-up/home-assistant`]); // a history entry each
    h.back();
    await until(() => h.state().setup.phase === 'wifi' && h.state().busy === undefined);
    expect(h.nav.path).toBe(`${BASE}/set-up/wifi`);
    expect(h.renewals).toHaveLength(0);
    expect(registry.get()!.session).toBe(one.s);
    h.go('/set-up/home-assistant'); // the rail's link forward again, or Forward
    await until(() => h.state().setup.phase === 'ha');
    expect(registry.get()!.session).toBe(one.s);
    one.s.close();
  });

  it('runs an entry route once under StrictMode', async () => {
    const one = session();
    const route = vi.spyOn(one.s, 'route');
    harness(one, `${BASE}/set-up/wifi`, true);
    await settle();
    expect(route).toHaveBeenCalledTimes(1);
    expect(route).toHaveBeenCalledWith('wifi');
    expect(one.knownAdb).toHaveBeenCalledTimes(1);
  });

  it('follows this frame\'s own older #/ links', async () => {
    location.hash = '#/undo';
    const h = harness(session(), BASE);
    await settle();
    expect(h.nav.path).toBe(`${BASE}/undo`);
    expect(h.state().screen).toBe('undo');
  });
});
