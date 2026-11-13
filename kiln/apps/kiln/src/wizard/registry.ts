// The frame's current session, outside React, so it outlives route changes and the picker can see
// a running install. Type-only imports: the picker reads this without loading the engine.
import type { Session } from './session';
import type { Failure, WizardState } from './state';

export interface Entry {
  /** the frame's route base, e.g. /aqara-s1-plus */
  base: string;
  name: string;
  session: Session;
  /** ?demo=… states; never in a production build */
  demo?: string;
  demoEntered?: boolean;
}

/**
 * The last run that stopped with an error, kept when the person moves on from the Error screen (Try
 * again, another route, a new session), so its details and log can still be read. Cleared when a new
 * run starts.
 */
export interface LastFailure { name: string; when: Date; failure: Failure; log: string[] }

let current: Entry | undefined;
let lastFailure: LastFailure | undefined;
let unwatch: (() => void) | undefined;
const listeners = new Set<() => void>();
const notify = () => { for (const l of listeners) l(); };

function keep(e: Entry, s: WizardState) {
  if (s.screen !== 'error' || !s.failure) return;
  lastFailure = { name: e.name, when: new Date(), failure: s.failure, log: s.log };
}

function watch(e: Entry) {
  unwatch?.();
  let prev = e.session.store.state;
  unwatch = e.session.store.subscribe(() => {
    const s = e.session.store.state;
    if (prev.screen === 'error' && s.screen !== 'error') { keep(e, prev); notify(); }
    if (s.screen === 'firing' && prev.screen !== 'firing' && lastFailure) { lastFailure = undefined; notify(); }
    prev = s;
  });
}

export const registry = {
  get: (): Entry | undefined => current,
  /** Makes `e` the current session (a new install, or the first visit), and closes the one it replaces. */
  set(e: Entry): Entry {
    if (current && current.session !== e.session) {
      keep(current, current.session.store.state);
      current.session.close();
    }
    current = e;
    watch(e);
    notify();
    return e;
  },
  lastFailure: (): LastFailure | undefined => lastFailure,
  subscribe(l: () => void) {
    listeners.add(l);
    return () => { listeners.delete(l); };
  },
  /** Tests only. */
  clear() { unwatch?.(); unwatch = undefined; current = undefined; lastFailure = undefined; },
};

/** The stopped run as a text file: what happened, then the technical log. */
export function failureLog(f: LastFailure): string {
  return [
    `Kiln, ${f.name}`,
    `Stopped ${f.when.toISOString()}: ${f.failure.title}`,
    f.failure.body,
    ...(f.failure.raw ? [`Error: ${f.failure.raw}`] : []),
    '',
    'Technical log:',
    ...f.log,
    '',
  ].join('\n');
}
