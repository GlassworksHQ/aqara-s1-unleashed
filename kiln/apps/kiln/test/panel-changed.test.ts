// The panel's history across attempts: once a run may have changed the panel, a later attempt that
// fails before writing says "this attempt wrote nothing; an earlier try did" and keeps Undo, in the
// install and the undo flow alike, until a run reaches Done.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { demoSession } from '../src/wizard/demo';
import type { Session } from '../src/wizard/session';
import type { Flow } from '../src/wizard/state';

vi.spyOn(console, 'error').mockImplementation(() => {}); // Session.fail logs the error
const open: Session[] = [];
afterEach(() => { for (const s of open.splice(0)) s.close(); });

function session() {
  const s = demoSession('connect');
  open.push(s);
  const fail = (at: number, e: unknown) => (s as unknown as { fail(at: number, e: unknown): void }).fail(at, e);
  const d = s.store.dispatch;
  /** A run that starts writing; `verified`: one part was flashed and read back before it failed. */
  const writtenRun = (flow: Flow, verified: boolean) => {
    d({ type: 'start', flow, now: 0 });
    d({ type: 'progress', p: { overall: 0.6, phase: 'flash', phasePct: 0.1, title: 'Writing', sub: '' } });
    if (verified) d({ type: 'log', line: '10:00:00 vbmeta flashed and verified' });
    fail(2, new Error('boot: read-back does not match what was written'));
  };
  return { s, fail, d, writtenRun, failure: () => s.store.state.failure! };
}
const closed = () => new DOMException('No device selected.', 'NotFoundError');
const EARLIER = /^This attempt wrote nothing; an earlier try did\. /;

describe('after a run that wrote', () => {
  it.each<Flow>(['install', 'undo'])('%s: a retry that fails before its own write keeps Undo', (flow) => {
    const { d, fail, writtenRun, failure } = session();
    writtenRun(flow, true);
    expect(failure()).toMatchObject({ wrote: true, body: expect.stringMatching(/^Part of the new software was written\./) });
    d({ type: 'go', screen: 'connect' }); // Try again
    d({ type: 'start', flow, now: 1 });
    fail(2, new Error('transferIn: the device was disconnected'));
    expect(failure()).toMatchObject({ title: 'It disconnected.', wrote: true });
    expect(failure().body).toBe('This attempt wrote nothing; an earlier try did. Don\'t unplug it yet: put it in recovery mode again and use Undo with your backup.');
  });

  it('a fastboot device list closed on the retry (step 2) keeps Undo', () => {
    const { d, fail, writtenRun, failure } = session();
    writtenRun('install', true);
    d({ type: 'go', screen: 'connect' });
    d({ type: 'start', flow: 'install', now: 1 });
    fail(2, closed());
    expect(failure().wrote).toBe(true);
    expect(failure().body).toMatch(EARLIER);
  });

  it('a write that only started (never verified) counts as an earlier try too, and is the last attempt\'s, not this one\'s', () => {
    const { d, fail, writtenRun, failure } = session();
    writtenRun('install', false);
    expect(failure().body).toMatch(/^Part of the new software may have been written\./);
    d({ type: 'go', screen: 'connect' }); // Try again
    fail(1, closed()); // the device list closed: no run started
    expect(failure()).toMatchObject({ at: 1, wrote: true });
    expect(failure().body).toMatch(EARLIER);
  });

  it('is forgotten once a run reaches Done', () => {
    const { s, d, fail, writtenRun, failure } = session();
    writtenRun('undo', true);
    d({ type: 'go', screen: 'connect' });
    d({ type: 'start', flow: 'undo', now: 1 });
    d({ type: 'done', done: { title: 'The original software is back.', body: '' } });
    expect(s.store.state.changed).toBeUndefined();
    d({ type: 'go', screen: 'connect', flow: 'install' });
    fail(1, closed());
    expect(failure()).toMatchObject({ wrote: false, body: expect.stringMatching(/^Nothing was written\./) });
  });
});
