// The wizard's routes under a frame (/aqara-s1-plus/…), one per rail step and mode. The session's
// state is the truth: the URL follows it, and a URL the user goes to (a link, Back, typing it) may
// only start what that route's own screen starts. A route never starts or repeats a flow.
import type { WizardState } from './state';

/** Steps, by the path after the frame's base. */
export const ROUTES = {
  start: '',
  install: '/install',
  'set-up': '/set-up',
  wifi: '/set-up/wifi',
  'home-assistant': '/set-up/home-assistant',
  radio: '/set-up/radio',
  done: '/done',
  undo: '/undo',
  'undo-connect': '/undo/connect',
  'undo-restore': '/undo/restore',
  'undo-done': '/undo/done',
  resume: '/resume',
} as const;

export type Step = keyof typeof ROUTES;

/**
 * Where a route can be entered from scratch, and what entering does. Entries only show a screen
 * (Connect waits for a click) or connect to an installed panel over adb; nothing here writes.
 * `route` is the session's entry (`Session.route`).
 */
export const ENTRIES: Partial<Record<Step, { route: 'install' | 'undo' | 'resume' | 'wifi' | 'home-assistant' | 'radio' }>> = {
  start: { route: 'install' },
  undo: { route: 'undo' },
  'set-up': { route: 'resume' },
  resume: { route: 'resume' },
  wifi: { route: 'wifi' },
  'home-assistant': { route: 'home-assistant' },
  radio: { route: 'radio' },
};

/** A mid-flow step reached with nothing running goes to its mode's start. */
const START_OF: Record<Step, Step> = {
  start: 'start', install: 'start', done: 'start',
  'set-up': 'set-up', wifi: 'wifi', 'home-assistant': 'home-assistant', radio: 'radio', resume: 'set-up',
  undo: 'undo', 'undo-connect': 'undo', 'undo-restore': 'undo', 'undo-done': 'undo',
};

/** The step for a path under the frame's base, or undefined for an unknown one. */
export function stepOf(subpath: string): Step | undefined {
  const p = subpath.replace(/\/+$/, '');
  return (Object.keys(ROUTES) as Step[]).find((s) => ROUTES[s] === p);
}

/** The step the state is on: where the address bar should point. */
export function canonical(s: WizardState): Step {
  const undo = s.flow === 'undo';
  switch (s.screen) {
    case 'connect': return undo ? 'undo-connect' : 'start';
    case 'undo': return 'undo';
    case 'firing': return undo ? 'undo-restore' : 'install';
    case 'done': return undo ? 'undo-done' : 'done';
    case 'setup': {
      const p = s.setup.phase;
      return p === 'wifi' ? 'wifi' : p === 'ha' ? 'home-assistant' : p === 'radio' || p === 'radio-next' ? 'radio' : 'set-up';
    }
    case 'error': {
      const at = s.failure?.at ?? 1;
      if (undo) return at <= 0 ? 'undo' : at === 1 ? 'undo-connect' : 'undo-restore';
      return at <= 1 ? 'start' : at === 2 ? 'install' : 'set-up';
    }
  }
}

const SET_UP: Step[] = ['set-up', 'wifi', 'home-assistant', 'radio'];

/**
 * Whether the address bar may stay on `step` for this state: its canonical step, or, while a panel is
 * being connected for set-up, whichever set-up route it was entered by (it goes there next).
 */
export function fits(s: WizardState, step: Step | undefined): boolean {
  if (step === canonical(s)) return true;
  return s.screen === 'setup' && s.setup.phase === 'waiting' && step !== undefined && SET_UP.includes(step);
}

export type Status = 'idle' | 'running' | 'finished';

/**
 * running: something is happening that leaving would cut short (USB work, a picker, the app going in).
 * finished: done or failed. idle: a screen waiting for the person (including the setup forms).
 */
export function status(s: WizardState): Status {
  if (s.busy || s.ask || s.screen === 'firing' || (s.screen === 'setup' && s.setup.phase === 'waiting')) return 'running';
  if (s.screen === 'done' || s.screen === 'error') return 'finished';
  return 'idle';
}

export type Decision =
  | { kind: 'stay' }
  /** point the address bar at this step, without a history entry */
  | { kind: 'replace'; step: Step }
  /** enter this route; `fresh`: on a new session, the old one having started something */
  | { kind: 'enter'; step: Step; fresh: boolean };

/**
 * What to do when the person went to `step`. `started`: the session has begun a flow (FEL picked,
 * or a panel connected) at some point.
 */
export function onNavigate(s: WizardState, started: boolean, step: Step | undefined): Decision {
  const here = canonical(s);
  if (!step) return { kind: 'replace', step: here };
  if (fits(s, step)) return { kind: 'stay' };
  const st = status(s);
  if (st === 'running') return { kind: 'replace', step: here }; // a running flow stays on screen
  if (ENTRIES[step]) return { kind: 'enter', step, fresh: started };
  return st === 'finished' ? { kind: 'replace', step: here } : { kind: 'replace', step: START_OF[step] };
}

/** When the state moved on by itself, the URL follows. */
export function onState(s: WizardState, step: Step | undefined): Decision {
  return fits(s, step) ? { kind: 'stay' } : { kind: 'replace', step: canonical(s) };
}

/** s1.edm.sh's (and docs/install.md's) hash links, and this app's older ones, to routes. */
export const OLD_HASH: Record<string, Step> = {
  install: 'start',
  undo: 'undo',
  resume: 'set-up',
  wifi: 'wifi',
  'home-assistant': 'home-assistant',
  radio: 'radio',
};

/** `#/wifi` → `wifi`, or undefined. */
export function fromHash(hash: string): Step | undefined {
  return OLD_HASH[hash.replace(/^#\/?/, '').replace(/\/+$/, '')];
}
