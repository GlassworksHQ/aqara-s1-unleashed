// The pane catalog (#231): the generated list matches the pane files, the words line up, and a frame's
// rating of a pane is its own when it gives one and is worked out otherwise.
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { CAPABILITIES, PANES, PANE_ALIASES, PANE_IDS, capabilityText, framePanes, isCapability, isPaneAlias, paneById, paneSupport, type FrameDesign, type PaneInfo } from '../src';

const script = fileURLToPath(new URL('../scripts/panes.mjs', import.meta.url));
const frame = (id: string) =>
  JSON.parse(readFileSync(fileURLToPath(new URL(`../../../frames/${id}/design/frame.json`, import.meta.url)), 'utf8')) as FrameDesign;

describe('the pane list', () => {
  it('is what the pane files give (scripts/panes.mjs --check)', () => {
    expect(() => execFileSync(process.execPath, [script, '--check'], { stdio: 'pipe' })).not.toThrow();
  });

  it('has every pane file once: not empty, no id twice, and the same ids as the files', () => {
    const java = fileURLToPath(new URL('../../../app/kiosk/src/main/java', import.meta.url));
    const files = readdirSync(java, { recursive: true }).map(String).filter((f) => f.split(/[\\/]/).pop() === 'pane.json');
    const ids = files.map((f) => (JSON.parse(readFileSync(`${java}/${f}`, 'utf8')) as { id: string }).id);
    expect(PANE_IDS.length).toBeGreaterThan(0);
    expect(new Set(PANE_IDS).size).toBe(PANE_IDS.length);
    expect([...PANE_IDS].sort()).toEqual([...ids].sort());
    expect(PANE_IDS).toContain(PANE_ALIASES.base);
  });

  it('says every requirement in the capability words', () => {
    for (const p of PANES) expect(p.requires.filter((r) => !isCapability(r)), p.id).toEqual([]);
  });

  it('carries each icon as an SVG on the 24 grid', () => {
    for (const p of PANES) {
      expect(p.icon, p.id).toMatch(/^<svg [^>]*viewBox="0 0 24 24"/);
      expect(p.icon, p.id).not.toMatch(/#[0-9a-f]{3,8}\b|rgb\(|<script|href/i);
    }
  });

  it('finds a pane by an old alias', () => {
    expect(PANE_ALIASES.base).toBe('wall-base');
    expect(paneById('base')?.id).toBe('wall-base');
    expect(paneById('wall-base')?.id).toBe('wall-base');
    expect(paneById('sensors')).toBeUndefined();
  });

  it('knows only its own keys, not Object.prototype\'s', () => {
    for (const k of ['constructor', 'toString', '__proto__', 'hasOwnProperty']) {
      expect(isPaneAlias(k), k).toBe(false);
      expect(isCapability(k), k).toBe(false);
      expect(paneById(k), k).toBeUndefined();
    }
    expect(isCapability('microphone')).toBe(true);
    expect(capabilityText(['constructor'])).toBe('constructor');
    expect(CAPABILITIES).not.toHaveProperty('constructor');
  });

  it('says a list of capabilities in words', () => {
    expect(capabilityText(['microphone'])).toBe('a microphone');
    expect(capabilityText(['microphone', 'speaker'])).toBe('a microphone and a speaker');
    expect(capabilityText(['microphone', 'speaker', 'silabs-radio'])).toBe('a microphone, a speaker and a Silicon Labs radio');
  });
});

describe('a frame and a pane', () => {
  const pane = (o: Partial<PaneInfo>): PaneInfo => ({ id: 'p', name: 'P', does: 'd', requires: [], status: 'beta', icon: '<svg/>', source: 'x', ...o });
  const f = (o: Partial<FrameDesign>) => ({ id: 'f', status: 'beta', panes: {}, ...o }) as unknown as FrameDesign;

  it('takes the frame\'s own rating first, with its note', () => {
    const s = paneSupport(f({ panes: { p: { support: 'alpha', note: 'n' } }, provides: [] }), pane({ requires: ['microphone'] }));
    expect(s).toEqual({ support: 'alpha', note: 'n', derived: false });
  });

  it('reads a rating under the old alias (base for wall-base)', () => {
    const s = paneSupport(f({ panes: { base: { support: 'no', note: 'S1 only.' } } }), pane({ id: 'wall-base', frame: 'aqara-s1-plus' }));
    expect(s).toEqual({ support: 'no', note: 'S1 only.', derived: false });
  });

  it('keeps a frame pane to its own frame', () => {
    expect(paneSupport(f({ id: 'other' }), pane({ frame: 'aqara-s1-plus' }))).toEqual({ support: 'no', derived: true, reason: 'Only on the aqara-s1-plus frame.' });
    expect(paneSupport(f({ id: 'other' }), pane({ frame: 'aqara-s1-plus' }), (id) => (id === 'aqara-s1-plus' ? 'Aqara S1 Plus' : undefined)).reason).toBe('Only on the Aqara S1 Plus.');
    expect(paneSupport(f({ id: 'aqara-s1-plus' }), pane({ frame: 'aqara-s1-plus' }))).toMatchObject({ support: 'beta', derived: true });
  });

  it('says no, and what is missing, when the frame lacks a requirement', () => {
    expect(paneSupport(f({ provides: ['speaker'] }), pane({ requires: ['microphone', 'speaker'] })))
      .toEqual({ support: 'no', derived: true, reason: 'Needs a microphone.' });
  });

  it('says depends when the frame lists no hardware (it varies by device)', () => {
    expect(paneSupport(f({}), pane({ requires: ['microphone'] }))).toEqual({ support: 'depends', derived: true, reason: 'Needs a microphone.' });
  });

  it('runs at the pane\'s stage when the frame ships and has what it needs', () => {
    expect(paneSupport(f({ provides: ['microphone'] }), pane({ requires: ['microphone'], status: 'alpha' })))
      .toEqual({ support: 'alpha', derived: true, reason: 'Has a microphone.' });
    expect(paneSupport(f({}), pane({ status: 'alpha' }))).toEqual({ support: 'alpha', derived: true, reason: 'Needs no special hardware.' });
  });

  it('is planned while the frame itself doesn\'t ship', () => {
    expect(paneSupport(f({ status: 'planned', provides: ['microphone'] }), pane({ requires: ['microphone'] }))).toMatchObject({ support: 'planned', derived: true });
    expect(paneSupport(f({ status: 'planned' }), pane({}))).toMatchObject({ support: 'planned', derived: true });
  });
});

describe('the real frames', () => {
  const support = (id: string) => Object.fromEntries(framePanes(frame(id)).map((p) => [p.pane.id, [p.support, p.derived]]));

  it('rate every pane, explicitly or worked out', () => {
    for (const id of ['aqara-s1-plus', 'lululemon-mirror', 'generic-android']) expect(Object.keys(support(id)).sort(), id).toEqual([...PANE_IDS].sort());
    expect(support('aqara-s1-plus')).toMatchObject({
      assist: ['beta', false], diagnostics: ['alpha', true], radio: ['beta', false], sendspin: ['beta', false],
      timers: ['beta', false], 'time-zone': ['beta', true], 'wall-base': ['alpha', false], 'bluetooth-proxy': ['alpha', true],
    });
    expect(support('lululemon-mirror')).toMatchObject({ diagnostics: ['planned', true], 'time-zone': ['no', true], 'wall-base': ['no', false], 'bluetooth-proxy': ['planned', true] });
    expect(support('generic-android')).toMatchObject({ diagnostics: ['planned', true], 'time-zone': ['no', true], 'wall-base': ['no', false], 'bluetooth-proxy': ['depends', true] });
  });
});
