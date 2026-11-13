// @vitest-environment jsdom
// The panel's history outlives the session for the browser tab: Back to Kiln and the frame again
// (a new session, S1's renew), or a reload (a new page: only sessionStorage is left). Only a run that
// reaches Done clears it.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { demoSession } from '../src/wizard/demo';
import { registry } from '../src/wizard/registry';
import type { Session } from '../src/wizard/session';

vi.spyOn(console, 'error').mockImplementation(() => {});
const sessions: Session[] = [];
afterEach(() => { registry.clear(); sessionStorage.clear(); for (const s of sessions.splice(0)) s.close(); });

const entry = (session: Session) => ({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session });
const fresh = (make = demoSession) => { const s = make('connect'); sessions.push(s); return s; };
const writeSome = (s: Session) => {
  s.store.dispatch({ type: 'start', flow: 'install', now: 0 });
  s.store.dispatch({ type: 'progress', p: { overall: 0.6, phase: 'flash', phasePct: 0.1, title: 'Writing', sub: '' } });
};
const closedList = (s: Session) => (s as unknown as { fail(at: number, e: unknown): void }).fail(1, new DOMException('No device selected.', 'NotFoundError'));

describe('the panel\'s history', () => {
  it('carries into a new session (Back to Kiln, then the frame again)', () => {
    const one = fresh();
    registry.set(entry(one));
    writeSome(one);
    const two = fresh();
    registry.set(entry(two)); // what S1's renew does
    expect(two.store.state.changed).toBe('written');
    closedList(two);
    expect(two.store.state.failure).toMatchObject({ wrote: true, body: expect.stringMatching(/^This attempt wrote nothing; an earlier try did\. /) });
  });

  it('carries across a reload of the tab', async () => {
    const one = fresh();
    registry.set(entry(one));
    writeSome(one);
    vi.resetModules(); // a new page load: fresh modules, the same tab's sessionStorage
    const again = await import('../src/wizard/registry');
    const demo = await import('../src/wizard/demo');
    const two = fresh(demo.demoSession);
    again.registry.set(entry(two));
    expect(two.store.state.changed).toBe('written');
    again.registry.clear();
  });

  it('is cleared when a run reaches Done, for the next session too', () => {
    const one = fresh();
    registry.set(entry(one));
    writeSome(one);
    one.store.dispatch({ type: 'done', done: { title: 'Done.', body: '' } });
    expect(sessionStorage.length).toBe(0);
    const two = fresh();
    registry.set(entry(two));
    expect(two.store.state.changed).toBeUndefined();
    closedList(two);
    expect(two.store.state.failure).toMatchObject({ wrote: false, body: expect.stringMatching(/^Nothing was written\. /) });
  });

  it('keeps the frames apart', () => {
    const one = fresh();
    registry.set(entry(one));
    writeSome(one);
    const other = fresh();
    registry.set({ base: '/another-frame', name: 'Another', session: other });
    expect(other.store.state.changed).toBeUndefined();
  });
});

describe('with this tab\'s storage blocked', () => {
  const blocked = () => {
    const err = () => { throw new DOMException('The operation is insecure.', 'SecurityError'); };
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(err);
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(err);
  };
  afterEach(() => vi.restoreAllMocks());

  it('a reload never claims nothing was written: it says Kiln can\'t tell', async () => {
    blocked();
    vi.resetModules(); // a new page load in a tab whose earlier load may have written
    const again = await import('../src/wizard/registry');
    const demo = await import('../src/wizard/demo');
    const two = fresh(demo.demoSession);
    again.registry.set(entry(two));
    expect(two.store.state.changed).toBe('unknown');
    closedList(two);
    expect(two.store.state.failure!.body).toMatch(/^This attempt wrote nothing\. Kiln can't tell if an earlier try in this browser tab did\. /);
    expect(two.store.state.failure!.body).not.toMatch(/Nothing was written/);
    const three = fresh(demo.demoSession);
    again.registry.set(entry(three)); // Back to Kiln and the frame again: still unknown
    expect(three.store.state.changed).toBe('unknown');
    writeSome(three);
    expect(three.store.state.changed).toBe('written'); // a write this load saw outranks not knowing
    three.store.dispatch({ type: 'done', done: { title: 'Done.', body: '' } });
    const four = fresh(demo.demoSession);
    again.registry.set(entry(four)); // this page load saw the Done: nothing since
    expect(four.store.state.changed).toBeUndefined();
    again.registry.clear();
  });

  it('storage that writes but throws on reads gives unknown, not a broken page', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('The operation is insecure.', 'SecurityError'); });
    vi.resetModules();
    const again = await import('../src/wizard/registry');
    const demo = await import('../src/wizard/demo');
    const two = fresh(demo.demoSession);
    expect(() => again.registry.set(entry(two))).not.toThrow();
    expect(two.store.state.changed).toBe('unknown');
    again.registry.clear();
  });
});
