// The frame's current session, outside React, so it outlives route changes and the picker can see
// a running install. Type-only imports: the picker reads this without loading the engine.
import type { Session } from './session';
import type { Changed, Failure, WizardState } from './state';

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

/**
 * The panel's history (WizardState.changed) for this browser tab, per frame base. A new session (Back
 * to Kiln, then the frame again) starts from it, and so does a reload (sessionStorage), so neither
 * forgets that an earlier step changed the panel. Only a run reaching Done clears it.
 *
 * With storage blocked (site data off), a reload forgets: the first session of a page load then
 * starts from `unknown`, never from "nothing changed".
 */
const history = new Map<string, Changed>();
/** Bases this page load has had a session for: from then on `history` alone knows. */
const seen = new Set<string>();
const storageKey = (base: string) => `kiln:changed:${base}`;

/** sessionStorage, if this browser lets the page write it (Chrome with site data blocked throws). */
function storage(): Storage | undefined {
  try {
    sessionStorage.setItem('kiln:probe', '1');
    sessionStorage.removeItem('kiln:probe');
    return sessionStorage;
  } catch { return undefined; }
}

function recall(base: string): Changed | undefined {
  if (history.has(base)) return history.get(base);
  if (seen.has(base)) return undefined;
  const store = storage();
  if (!store) return 'unknown';
  const v = store.getItem(storageKey(base));
  return v === 'written' || v === 'apps' ? v : undefined;
}

function remember(base: string, c: Changed | undefined) {
  if (c) history.set(base, c); else history.delete(base);
  if (c === 'unknown') return; // nothing to keep: it is what a page load without storage starts from
  try {
    if (c) sessionStorage.setItem(storageKey(base), c); else sessionStorage.removeItem(storageKey(base));
  } catch { /* no storage: this page load still has `history` */ }
}

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
    if (s.changed !== prev.changed) remember(e.base, s.changed);
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
    const earlier = recall(e.base);
    seen.add(e.base);
    watch(e); // first, so `history` keeps what the session starts from
    if (earlier) e.session.store.dispatch({ type: 'changed', by: earlier });
    notify();
    return e;
  },
  lastFailure: (): LastFailure | undefined => lastFailure,
  subscribe(l: () => void) {
    listeners.add(l);
    return () => { listeners.delete(l); };
  },
  /** Tests only. */
  clear() {
    unwatch?.(); unwatch = undefined; current = undefined; lastFailure = undefined; seen.clear();
    for (const base of history.keys()) remember(base, undefined);
    try { for (const k of Object.keys(sessionStorage)) if (k.startsWith('kiln:changed:')) sessionStorage.removeItem(k); } catch { /* none */ }
  },
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
