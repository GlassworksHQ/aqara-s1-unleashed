// The route rules (src/wizard/routes.ts), as a table.
import { describe, expect, it } from 'vitest';
import { ROUTES, canonical, fits, fromHash, onNavigate, onState, status, stepOf, type Step } from '../src/wizard/routes';
import { initial, reduce, type Action, type WizardState } from '../src/wizard/state';

const run = (s: WizardState, ...as: Action[]) => as.reduce(reduce, s);
const fail = (at: number) => ({ type: 'fail', failure: { title: '', body: '', wrote: false, tips: [], raw: '', at } }) as Action;
const S = {
  connect: initial(),
  firing: run(initial(), { type: 'start', flow: 'install', now: 0 }),
  asking: run(initial(), { type: 'start', flow: 'install', now: 0 }, { type: 'ask', kind: 'fastboot' }),
  waiting: run(initial(), { type: 'setup', setup: { phase: 'waiting', note: '' } }),
  settings: run(initial(), { type: 'setup', setup: { phase: 'settings', update: false } }),
  wifi: run(initial(), { type: 'setup', setup: { phase: 'wifi', status: '' } }),
  joining: run(initial(), { type: 'setup', setup: { phase: 'wifi', status: '', joining: { ssid: 'Home', step: 'join' } } }),
  joinSent: run(initial(), { type: 'setup', setup: { phase: 'wifi', status: '', joining: { ssid: 'Home', step: 'sent' } } }),
  joined: run(initial(), { type: 'setup', setup: { phase: 'wifi', status: '', joining: { ssid: 'Home', step: 'ok' } } }),
  installingApp: run(initial(), { type: 'setup', setup: { phase: 'settings', update: false } }, { type: 'busy', busy: 'install-app' }),
  done: run(initial(), { type: 'done', done: { title: '', body: '' } }),
  failedWriting: run(initial(), { type: 'start', flow: 'install', now: 0 }, fail(2)),
  undo: initial('undo'),
  undoConnect: run(initial('undo'), { type: 'go', screen: 'connect', flow: 'undo' }),
  restoring: run(initial('undo'), { type: 'start', flow: 'undo', now: 0 }),
  undoDone: run(initial('undo'), { type: 'start', flow: 'undo', now: 0 }, { type: 'done', done: { title: '', body: '' } }),
};

describe('routes', () => {
  it('maps every path to its step, and nothing else', () => {
    for (const [step, path] of Object.entries(ROUTES)) expect(stepOf(path)).toBe(step);
    expect(stepOf('/install/')).toBe('install');
    expect(stepOf('/nope')).toBeUndefined();
  });

  it.each([
    ['connect', 'start'], ['firing', 'install'], ['asking', 'install'], ['waiting', 'set-up'], ['settings', 'set-up'], ['wifi', 'wifi'],
    ['done', 'done'], ['failedWriting', 'install'], ['undo', 'undo'], ['undoConnect', 'undo-connect'], ['restoring', 'undo-restore'], ['undoDone', 'undo-done'],
  ] as [keyof typeof S, Step][])('%s is on %s', (state, step) => {
    expect(canonical(S[state])).toBe(step);
  });

  it('knows what is running', () => {
    expect([S.firing, S.asking, S.waiting, S.installingApp].map(status)).toEqual(['running', 'running', 'running', 'running']);
    expect([S.connect, S.settings, S.wifi, S.undo, S.undoConnect].map(status)).toEqual(['idle', 'idle', 'idle', 'idle', 'idle']);
    expect([S.done, S.failedWriting, S.undoDone].map(status)).toEqual(['finished', 'finished', 'finished']);
  });

  it('counts a Wi-Fi join in flight as running, so leaving the screen cannot renew the session mid-join', () => {
    expect([S.joinSent, S.joining].map(status)).toEqual(['running', 'running']);
    expect(status(S.joined)).toBe('idle');
    expect(onNavigate(S.joining, true, 'set-up')).toEqual({ kind: 'replace', step: 'wifi' });
  });

  it('keeps a running flow on screen, whatever the address', () => {
    for (const step of Object.keys(ROUTES) as Step[]) {
      const d = onNavigate(S.firing, true, step);
      expect(d, step).toEqual(step === 'install' ? { kind: 'stay' } : { kind: 'replace', step: 'install' });
    }
  });

  it('sends a mid-flow route with nothing running to its mode\'s start', () => {
    expect(onNavigate(S.connect, false, 'install')).toEqual({ kind: 'replace', step: 'start' });
    expect(onNavigate(S.connect, false, 'done')).toEqual({ kind: 'replace', step: 'start' });
    expect(onNavigate(S.connect, false, 'undo-restore')).toEqual({ kind: 'replace', step: 'undo' });
    expect(onNavigate(S.connect, false, 'undo-done')).toEqual({ kind: 'replace', step: 'undo' });
  });

  it('enters an entry route, on a new session once the old one began a flow', () => {
    expect(onNavigate(S.connect, false, 'undo')).toEqual({ kind: 'enter', step: 'undo', fresh: false });
    expect(onNavigate(S.done, true, 'start')).toEqual({ kind: 'enter', step: 'start', fresh: true });
    expect(onNavigate(S.wifi, true, 'undo')).toEqual({ kind: 'enter', step: 'undo', fresh: true });
  });

  it('keeps a finished result when someone goes back into its flow', () => {
    expect(onNavigate(S.done, true, 'install')).toEqual({ kind: 'replace', step: 'done' });
    expect(onNavigate(S.undoDone, true, 'undo-restore')).toEqual({ kind: 'replace', step: 'undo-done' });
  });

  it('lets a set-up route stay while its panel connects, but not the resume alias', () => {
    expect(fits(S.waiting, 'wifi')).toBe(true);
    expect(onState(S.waiting, 'radio')).toEqual({ kind: 'stay' });
    expect(onState(S.waiting, 'resume')).toEqual({ kind: 'replace', step: 'set-up' });
    expect(onState(S.wifi, 'set-up')).toEqual({ kind: 'push', step: 'wifi' }); // forward in set-up: Back returns to it
  });

  it('goes back through set-up on the same session, and adds history only going forward', () => {
    const ha = run(initial(), { type: 'setup', setup: { phase: 'ha', ha: { signedIn: false } } });
    expect(onNavigate(ha, true, 'wifi')).toEqual({ kind: 'enter', step: 'wifi', fresh: false });
    expect(onNavigate(ha, true, 'set-up')).toEqual({ kind: 'enter', step: 'set-up', fresh: false });
    expect(onState(ha, 'wifi')).toEqual({ kind: 'push', step: 'home-assistant' });
    expect(onState(S.wifi, 'home-assistant')).toEqual({ kind: 'replace', step: 'wifi' }); // back: no new entry
    expect(onNavigate(S.joining, true, 'set-up')).toEqual({ kind: 'replace', step: 'wifi' }); // a join in flight stays
    expect(onNavigate(S.done, true, 'wifi')).toEqual({ kind: 'enter', step: 'wifi', fresh: true }); // after Done: a new session
  });

  it('follows the state when it moves on by itself', () => {
    expect(onState(S.firing, 'start')).toEqual({ kind: 'replace', step: 'install' });
    expect(onState(S.done, 'done')).toEqual({ kind: 'stay' });
  });

  it('maps s1.edm.sh\'s old links (docs/install.md lists them)', () => {
    expect(['#/install', '#/undo', '#/resume', '#/wifi', '#/home-assistant', '#/radio', '#/nope', ''].map(fromHash))
      .toEqual(['start', 'undo', 'set-up', 'wifi', 'home-assistant', 'radio', undefined, undefined]);
  });
});
