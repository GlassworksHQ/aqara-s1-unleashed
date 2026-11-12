// The wizard's state: which screen, the "your turn" sheet, progress, logs, and each setup phase's
// data. Every change goes through `reduce`; the rail, the stack, the plain log and the chip are
// derived from it by the selectors below, so a screen never keeps its own copy.
import type { AskKind, PartState, Phase, Progress } from '@glassworks/kiln-core';
import type { SetupState } from '@glassworks/setup-channel';

export type Flow = 'install' | 'undo';
export type Screen = 'browser' | 'connect' | 'firing' | 'setup' | 'done' | 'undo' | 'error';

export interface Network { ssid: string; rssi: number; secure: boolean }

/** What the setup step shows, one phase at a time (S1 Kiosk over adb). */
export type Setup =
  | { phase: 'waiting'; note: string }
  | { phase: 'settings'; busy?: string; error?: string; update: boolean }
  | {
      phase: 'wifi';
      /** undefined while the panel scans */
      networks?: Network[];
      status: string;
      hint?: string;
      unreachable?: boolean;
      joining?: { ssid: string; step: 'sent' | 'join' | 'ok' | 'err'; error?: string };
    }
  | { phase: 'ha'; ha: SetupState['ha']; note?: string; error?: string; busy?: boolean }
  | { phase: 'radio'; radio: SetupState['radio']; note: string; error?: string; busy?: boolean }
  | { phase: 'radio-next'; plan: RadioPlan };

export interface RadioStep { title: string; body: string; kv?: [string, string, boolean?][]; link?: string }
export interface RadioPlan { title: string; lead: string; steps: RadioStep[] }

export interface Failure {
  title: string;
  body: string;
  /** whether anything reached the panel's storage before it stopped */
  wrote: boolean;
  tips: [string, string][];
  raw: string;
  /** the rail step it failed on */
  at: number;
}

export interface UndoState {
  /** where the backup comes from; undefined until one is chosen and checked */
  source?: 'browser' | 'file';
  inBrowser?: string;
  file?: string;
  ready: boolean;
  depth: 'factory' | 'keep';
  appsBack: boolean;
  note?: { kind: 'busy' | 'ok' | 'err'; title: string; sub: string };
}

export interface DoneState {
  title: string;
  body: string;
  backup?: { name: string; info: string; hint: string };
}

export interface Part { state: PartState; size?: number; sha256?: string; pct?: number }

export interface WizardState {
  flow: Flow;
  screen: Screen;
  ask?: { kind: AskKind; busy: boolean; error?: string };
  progress: { overall: number; phase: Phase; title: string; sub: string; started: number };
  /** a write was flashed and verified: from here on, a failure has changed the panel */
  wrote: boolean;
  log: string[];
  parts: [string, Part][];
  setup: Setup;
  undo: UndoState;
  failure?: Failure;
  done?: DoneState;
}

export type Action =
  | { type: 'go'; screen: Screen; flow?: Flow }
  | { type: 'start'; flow: Flow; now: number }
  | { type: 'progress'; p: Progress }
  | { type: 'log'; line: string }
  | { type: 'partition'; part: string; state: PartState; info: Omit<Part, 'state'> }
  | { type: 'ask'; kind: AskKind }
  | { type: 'ask-busy' }
  | { type: 'ask-error'; error: string }
  | { type: 'ask-end' }
  | { type: 'setup'; setup: Setup }
  | { type: 'undo'; patch: Partial<UndoState> }
  | { type: 'fail'; failure: Failure }
  | { type: 'done'; done: DoneState };

export const LOG_LIMIT = 400;

export function initial(flow: Flow = 'install', supported = true): WizardState {
  return {
    flow,
    screen: supported ? (flow === 'undo' ? 'undo' : 'connect') : 'browser',
    progress: { overall: 0, phase: 'boot', title: 'Starting', sub: '', started: 0 },
    wrote: false,
    log: [],
    parts: [],
    setup: { phase: 'waiting', note: '' },
    undo: { ready: false, depth: 'factory', appsBack: false },
  };
}

export function reduce(s: WizardState, a: Action): WizardState {
  switch (a.type) {
    case 'go':
      return { ...s, screen: a.screen, flow: a.flow ?? s.flow, ask: undefined };
    case 'start':
      return {
        ...s, flow: a.flow, screen: 'firing', ask: undefined, wrote: false, log: [], parts: [], failure: undefined,
        progress: { overall: 0, phase: 'boot', title: 'Starting', sub: '', started: a.now },
      };
    case 'progress': {
      // Progress never goes backwards, even when the flow's phases alternate.
      const overall = Math.max(s.progress.overall, Math.min(1, a.p.overall));
      return { ...s, progress: { ...s.progress, overall, phase: a.p.phase, title: a.p.title, sub: a.p.sub } };
    }
    case 'log': {
      const log = [...s.log, a.line].slice(-LOG_LIMIT);
      return { ...s, log, wrote: s.wrote || /(flashed|restored) and verified/.test(a.line) };
    }
    case 'partition': {
      const was = s.parts.find(([p]) => p === a.part)?.[1];
      const part: Part = { ...was, ...a.info, state: a.state };
      const parts = was ? s.parts.map(([p, x]): [string, Part] => [p, p === a.part ? part : x]) : [...s.parts, [a.part, part] as [string, Part]];
      return { ...s, parts };
    }
    case 'ask':
      return { ...s, ask: { kind: a.kind, busy: false } };
    case 'ask-busy':
      return s.ask ? { ...s, ask: { ...s.ask, busy: true, error: undefined } } : s;
    case 'ask-error':
      return s.ask ? { ...s, ask: { ...s.ask, busy: false, error: a.error } } : s;
    case 'ask-end':
      return { ...s, ask: undefined };
    case 'setup':
      return { ...s, screen: 'setup', setup: a.setup };
    case 'undo':
      return { ...s, undo: { ...s.undo, ...a.patch } };
    case 'fail':
      return { ...s, screen: 'error', ask: undefined, failure: a.failure };
    case 'done':
      return { ...s, screen: 'done', ask: undefined, done: a.done };
  }
}

// ---- derived views ------------------------------------------------------------------------------

export const STEPS: Record<Flow, readonly string[]> = {
  install: ['Panel', 'Connect', 'Install', 'Set up', 'Done'],
  undo: ['Backup', 'Connect', 'Restore', 'Done'],
};

export type RailMark = 'done' | 'now' | 'turn' | 'bad' | 'todo';

/** The rail's step for a screen; the picker (step 0 of an install) is its own page. */
export function railIndex(s: WizardState): number {
  if (s.screen === 'error') return s.failure?.at ?? 1;
  const at: Record<Flow, Partial<Record<Screen, number>>> = {
    install: { browser: 1, connect: 1, firing: 2, setup: 3, done: 4 },
    undo: { browser: 0, undo: 0, connect: 1, firing: 2, done: 3 },
  };
  return at[s.flow][s.screen] ?? 0;
}

export function rail(s: WizardState): { name: string; mark: RailMark }[] {
  const now = railIndex(s);
  const all = s.screen === 'done';
  return STEPS[s.flow].map((name, i) => ({
    name,
    mark: all || i < now ? 'done' : i > now ? 'todo' : s.screen === 'error' ? 'bad' : s.ask ? 'turn' : 'now',
  }));
}

/**
 * How many layers of the stack have set above Kiln's floor: 1 once the panel runs our installer, 2
 * once the new software is written, 3 with the app in, 4 when done.
 */
export function stackLevel(s: WizardState): number {
  const fired = s.progress.overall >= 0.6 || s.wrote ? 2 : s.progress.overall > 0 ? 1 : 0;
  switch (s.screen) {
    case 'done': return 4;
    case 'setup': return 3;
    case 'firing':
    case 'error': return fired;
    default: return 0;
  }
}

export interface Milestone { text: string; done: boolean }

const ORDER: Phase[] = ['boot', 'backup', 'patch', 'flash', 'verify'];

/** The plain log: what is done and what is happening, in words, not partition names. */
export function milestones(s: WizardState, panelName: string): Milestone[] {
  const { overall, phase } = s.progress;
  const at = ORDER.indexOf(phase);
  const restarting = overall >= 0.98;
  const rows: [string, boolean, boolean][] = s.flow === 'undo'
    ? [
        [`Found your ${panelName}`, overall > 0, overall >= 0.01],
        ['Started the installer in its memory', overall >= 0.01, at >= 3],
        ['Putting the original software back', at >= 3, restarting],
        ['Restarting it', restarting, false],
      ]
    : [
        [`Found your ${panelName}`, overall > 0, overall >= 0.01],
        ['Started the installer in its memory', overall >= 0.01, at >= 1],
        ['Backed up and checked', at >= 1, at >= 2],
        ['Prepared the new software', at >= 2, at >= 3],
        ['Writing the new software', at >= 3, restarting],
        ['Restarting it', restarting, false],
      ];
  return rows.filter(([, reached]) => reached).map(([text, , done]) => ({ text, done }));
}

/** "68% · ~3 min": the estimate once there is enough to go on. */
export function eta(s: WizardState, now: number): string {
  const { overall, started } = s.progress;
  const pct = `${Math.floor(overall * 100)}%`;
  const elapsed = (now - started) / 1000;
  if (overall < 0.08 || overall >= 0.97 || elapsed < 20) return pct;
  const left = (elapsed / overall) * (1 - overall);
  return left > 90 ? `${pct} · ~${Math.round(left / 60)} min` : left > 15 ? `${pct} · under a minute` : pct;
}

/** Whether leaving the page now could interrupt a write (the leave-page warning). */
export const busyWriting = (s: WizardState) => s.screen === 'firing' && s.progress.phase !== 'boot';
