// The wizard's state: which screen, the "your turn" sheet, progress, logs, and each setup phase's
// data. Every change goes through `reduce`; the rail, the stack, the plain log and the chip are
// derived from it by the selectors below, so a screen never keeps its own copy.
import type { AskKind, BackupMeta, PartState, Phase, Progress } from '@glassworks/kiln-core';
import type { SetupState } from '@glassworks/setup-channel';

export type Flow = 'install' | 'undo';
/**
 * What an earlier step did to the panel: `written`, a run started writing; `apps`, the panel was (being)
 * given back to Aqara's apps; `unknown`, this browser tab can't remember (its storage is blocked), so an
 * earlier page load may have done either.
 */
export type Changed = 'written' | 'apps' | 'unknown';
const OUTRANKS: Record<Changed, number> = { unknown: 0, apps: 1, written: 2 };
export type Screen = 'connect' | 'firing' | 'setup' | 'done' | 'undo' | 'error';

export interface Network { ssid: string; rssi: number; secure: boolean }

/** The app's settings as the person chose them: kept, so going back to the step shows them again. */
export interface AppSettings { locale?: string; timezone?: string; clock24: boolean; keyboard: boolean }

/** What the setup step shows, one phase at a time (S1 Kiosk over adb). */
export type Setup =
  | { phase: 'waiting'; note: string }
  | { phase: 'settings'; busy?: string; error?: string; update: boolean; chosen?: AppSettings }
  | {
      phase: 'wifi';
      /** undefined while the panel scans */
      networks?: Network[];
      status: string;
      hint?: string;
      unreachable?: boolean;
      joining?: { ssid: string; step: 'sent' | 'join' | 'ok' | 'err'; error?: string };
    }
  /** signingIn: a sign-in tab is open, and the panel isn't signed in yet */
  | { phase: 'ha'; ha: SetupState['ha']; note?: string; error?: string; busy?: boolean; signingIn?: boolean }
  | { phase: 'radio'; radio: SetupState['radio']; note: string; error?: string; busy?: boolean }
  | { phase: 'radio-next'; plan: RadioPlan };

export interface RadioStep { title: string; body: string; kv?: [string, string, boolean?][]; link?: string }
export interface RadioPlan { title: string; lead: string; steps: RadioStep[] }

export interface Failure {
  title: string;
  body: string;
  /** whether anything may have reached the panel's storage before it stopped */
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
  /** a backup file is being checked */
  checking?: boolean;
  depth: 'factory' | 'keep';
  appsBack: boolean;
  /**
   * The apps were given back against this backup, so it is the one to restore: no other may be
   * picked in this session. Unlike `appsBack`, a depth change or a new Undo keeps it.
   */
  locked?: boolean;
  /** `link`: a way forward when undo here can't go on, e.g. undoing by hand */
  note?: { kind: 'busy' | 'ok' | 'err'; title: string; sub: string; link?: { href: string; text: string } };
}

export interface DoneState {
  title: string;
  body: string;
  backup?: { name: string; info: string; hint: string; error?: string };
}

/** A backup a run in this tab saved: what Done offers. The serial stays in IndexedDB only. */
export type SavedBackup = Omit<BackupMeta, 'serial'>;

export interface Part { state: PartState; size?: number; sha256?: string; pct?: number }

export interface WizardState {
  flow: Flow;
  screen: Screen;
  ask?: { kind: AskKind; busy: boolean; error?: string };
  progress: { overall: number; phase: Phase; title: string; sub: string; started: number };
  /** a write was flashed and verified: from here on, a failure has changed the panel */
  wrote: boolean;
  /**
   * a write may have started: the first flash or verify progress. A failure from here on may have
   * changed the panel, even before any write is verified (explain.ts says so).
   */
  touched: boolean;
  /**
   * an earlier step may have changed the panel, and no run has reached Done since: `written`, a run
   * started writing; `apps`, the panel was (being) given back to Aqara's apps. Unlike `wrote` and
   * `touched`, which are this attempt's, a new attempt (go, start) keeps it, and the registry keeps it
   * for the browser tab across sessions: a later failure must not say nothing was written.
   */
  changed?: Changed;
  /**
   * The latest backup a run in this browser tab saved. Like `changed`, the registry keeps it for the tab
   * across sessions and reloads, so Done offers it after any session's set-up, not only the one that
   * installed.
   */
  backup?: SavedBackup;
  log: string[];
  parts: [string, Part][];
  setup: Setup;
  undo: UndoState;
  failure?: Failure;
  done?: DoneState;
  /** A clicked action is running; its buttons are off until it ends (Session.click). */
  busy?: string;
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
  | { type: 'done'; done: DoneState }
  | { type: 'busy'; busy?: string }
  | { type: 'changed'; by: Changed }
  | { type: 'backup'; backup: SavedBackup };

export const LOG_LIMIT = 400;

export function initial(flow: Flow = 'install'): WizardState {
  return {
    flow,
    screen: flow === 'undo' ? 'undo' : 'connect',
    progress: { overall: 0, phase: 'boot', title: 'Starting', sub: '', started: 0 },
    wrote: false,
    touched: false,
    log: [],
    parts: [],
    setup: { phase: 'waiting', note: '' },
    undo: { ready: false, depth: 'factory', appsBack: false },
  };
}

export function reduce(s: WizardState, a: Action): WizardState {
  switch (a.type) {
    case 'go': {
      // `wrote` and `touched` are this attempt's. Leaving the Error screen (Try again, Undo) starts the
      // next attempt clean; `changed` keeps the panel's history.
      const left = s.screen === 'error' ? { wrote: false, touched: false } : {};
      return { ...s, ...left, screen: a.screen, flow: a.flow ?? s.flow, ask: undefined };
    }
    case 'start':
      return {
        ...s, flow: a.flow, screen: 'firing', ask: undefined, wrote: false, touched: false, log: [], parts: [], failure: undefined,
        progress: { overall: 0, phase: 'boot', title: 'Starting', sub: '', started: a.now },
      };
    case 'progress': {
      // Progress never goes backwards, even when the flow's phases alternate.
      const overall = Math.max(s.progress.overall, Math.min(1, a.p.overall));
      // kiln-core reports flash and verify only from inside a write (flashVerified)
      const touched = s.touched || a.p.phase === 'flash' || a.p.phase === 'verify';
      return { ...s, touched, changed: touched ? 'written' : s.changed, progress: { ...s.progress, overall, phase: a.p.phase, title: a.p.title, sub: a.p.sub } };
    }
    case 'log': {
      const log = [...s.log, a.line].slice(-LOG_LIMIT);
      const wrote = s.wrote || /(flashed|restored) and verified/.test(a.line);
      return { ...s, log, wrote, changed: wrote ? 'written' : s.changed };
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
      return { ...s, screen: 'done', ask: undefined, done: a.done, changed: undefined };
    case 'busy':
      return { ...s, busy: a.busy };
    case 'backup':
      return { ...s, backup: a.backup };
    case 'changed': // a write outranks the apps, and both outrank not knowing
      return { ...s, changed: s.changed && OUTRANKS[s.changed] > OUTRANKS[a.by] ? s.changed : a.by };
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
    install: { connect: 1, firing: 2, setup: 3, done: 4 },
    undo: { undo: 0, connect: 1, firing: 2, done: 3 },
  };
  return at[s.flow][s.screen] ?? 0;
}

/** Set up's own steps, with the route of each (routes.ts SET_UP). */
const SET_UP_STEPS = [['App', 'set-up'], ['Wi-Fi', 'wifi'], ['Home Assistant', 'home-assistant'], ['Radio', 'radio']] as const;
const SET_UP_AT: Record<Setup['phase'], number> = { waiting: 0, settings: 0, wifi: 1, ha: 2, radio: 3, 'radio-next': 3 };

export interface RailStep {
  name: string;
  mark: RailMark;
  /** during set-up, its own steps; `route` is the step's (routes.ts), for going back to it */
  sub?: { name: string; mark: RailMark; route: (typeof SET_UP_STEPS)[number][1] }[];
}

export function rail(s: WizardState): RailStep[] {
  const now = railIndex(s);
  const all = s.screen === 'done';
  const at = SET_UP_AT[s.setup.phase];
  return STEPS[s.flow].map((name, i) => ({
    name,
    mark: all || i < now ? 'done' : i > now ? 'todo' : s.screen === 'error' ? 'bad' : s.ask ? 'turn' : 'now',
    ...(s.flow === 'install' && s.screen === 'setup' && i === now
      ? { sub: SET_UP_STEPS.map(([n, route], j) => ({ name: n, route, mark: j < at ? 'done' as const : j > at ? 'todo' as const : 'now' as const })) }
      : {}),
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
