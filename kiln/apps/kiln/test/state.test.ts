import { describe, expect, it } from 'vitest';
import { eta, initial, milestones, rail, reduce, stackLevel, type Action, type WizardState } from '../src/wizard/state';

const run = (s: WizardState, ...as: Action[]) => as.reduce(reduce, s);
const progress = (overall: number, phase: 'boot' | 'backup' | 'patch' | 'flash' | 'verify', title = 't'): Action =>
  ({ type: 'progress', p: { overall, phase, phasePct: 0, title, sub: '' } });
const marks = (s: WizardState) => rail(s).map((r) => r.mark).join(' ');

describe('wizard state', () => {
  it('never moves the progress bar backwards', () => {
    const s = run(initial(), { type: 'start', flow: 'install', now: 0 }, progress(0.6, 'patch'), progress(0.4, 'flash', 'Writing'));
    expect(s.progress.overall).toBe(0.6);
    expect(s.progress.title).toBe('Writing');
  });

  it('counts the panel as changed only once a write is verified', () => {
    let s = run(initial(), { type: 'start', flow: 'install', now: 0 }, { type: 'log', line: '10:00:00 boot saved, 32 MiB' });
    expect(s.wrote).toBe(false);
    s = reduce(s, { type: 'log', line: '10:00:01 vbmeta flashed and verified' });
    expect(s.wrote).toBe(true);
    expect(reduce(s, { type: 'start', flow: 'install', now: 1 }).wrote).toBe(false);
  });

  it('keeps the technical log to its last 400 lines', () => {
    let s = initial();
    for (let i = 0; i < 450; i++) s = reduce(s, { type: 'log', line: `l${i}` });
    expect(s.log).toHaveLength(400);
    expect(s.log[0]).toBe('l50');
  });

  it('updates a partition row in place', () => {
    const s = run(initial(),
      { type: 'partition', part: 'boot', state: 'pend', info: { size: 10 } },
      { type: 'partition', part: 'vbmeta', state: 'pend', info: { size: 1 } },
      { type: 'partition', part: 'boot', state: 'done', info: { sha256: 'ab…cd' } });
    expect(s.parts).toEqual([['boot', { state: 'done', size: 10, sha256: 'ab…cd' }], ['vbmeta', { state: 'pend', size: 1 }]]);
  });

  it('marks the rail: done, now, your turn, failed', () => {
    const s = run(initial(), { type: 'start', flow: 'install', now: 0 });
    expect(marks(s)).toBe('done done now todo todo');
    expect(marks(reduce(s, { type: 'ask', kind: 'fastboot' }))).toBe('done done turn todo todo');
    const failed = reduce(s, { type: 'fail', failure: { title: '', body: '', wrote: false, tips: [], raw: '', at: 2 } });
    expect(marks(failed)).toBe('done done bad todo todo');
    expect(marks(reduce(s, { type: 'done', done: { title: '', body: '' } }))).toBe('done done done done done');
    expect(rail(initial('undo')).map((r) => r.name)).toEqual(['Backup', 'Connect', 'Restore', 'Done']);
    expect(marks(initial('undo'))).toBe('now todo todo todo');
  });

  it('sets the stack layer by layer', () => {
    let s = initial();
    expect(stackLevel(s)).toBe(0);
    s = run(s, { type: 'start', flow: 'install', now: 0 }, progress(0.05, 'boot'));
    expect(stackLevel(s)).toBe(1);
    s = reduce(s, progress(0.7, 'flash'));
    expect(stackLevel(s)).toBe(2);
    s = reduce(s, { type: 'setup', setup: { phase: 'waiting', note: '' } });
    expect(stackLevel(s)).toBe(3);
    expect(stackLevel(reduce(s, { type: 'done', done: { title: '', body: '' } }))).toBe(4);
  });

  it('says in plain words what is done and what is happening', () => {
    let s = run(initial(), { type: 'start', flow: 'install', now: 0 }, progress(0.005, 'boot'));
    expect(milestones(s, 'S1 Plus')).toEqual([{ text: 'Found your S1 Plus', done: false }]);
    s = reduce(s, progress(0.3, 'backup'));
    expect(milestones(s, 'S1 Plus').map((m) => `${m.done ? '✓' : '→'} ${m.text}`)).toEqual([
      '✓ Found your S1 Plus', '✓ Started the installer in its memory', '→ Backed up and checked',
    ]);
    s = reduce(s, progress(0.7, 'flash'));
    expect(milestones(s, 'S1 Plus').at(-1)).toEqual({ text: 'Writing the new software', done: false });
    s = reduce(s, progress(0.98, 'verify'));
    expect(milestones(s, 'S1 Plus').slice(-2)).toEqual([{ text: 'Writing the new software', done: true }, { text: 'Restarting it', done: false }]);
  });

  it('estimates the time left only once there is enough to go on', () => {
    const s = run(initial(), { type: 'start', flow: 'install', now: 0 }, progress(0.5, 'backup'));
    expect(eta(s, 10_000)).toBe('50%');
    expect(eta(s, 300_000)).toBe('50% · ~5 min');
    expect(eta(s, 40_000)).toBe('50% · under a minute');
  });

  it('marks a possible write at the first flash or verify progress, and clears it on a new run', () => {
    let s = run(initial(), { type: 'start', flow: 'install', now: 0 }, progress(0.5, 'backup'), progress(0.58, 'patch'));
    expect(s.touched).toBe(false);
    s = reduce(s, progress(0.6, 'flash'));
    expect(s.touched).toBe(true);
    expect(s.wrote).toBe(false);
    expect(reduce(s, { type: 'start', flow: 'install', now: 1 }).touched).toBe(false);
  });

  it('keeps the panel\'s history across attempts, from a progress or a verified write alike, until Done', () => {
    const start = (s: WizardState) => run(s, { type: 'go', screen: 'connect' }, { type: 'start', flow: 'install', now: 1 });
    const flashed = run(initial(), { type: 'start', flow: 'install', now: 0 }, progress(0.6, 'flash'));
    expect(start(flashed)).toMatchObject({ touched: false, changed: true });
    const logged = run(initial(), { type: 'start', flow: 'undo', now: 0 }, { type: 'log', line: '10:00:00 boot restored and verified' });
    expect(start(logged)).toMatchObject({ wrote: false, changed: true });
    expect(reduce(start(logged), { type: 'done', done: { title: 'Done.', body: '' } }).changed).toBe(false);
  });
});
