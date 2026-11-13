'use client';
// Keeps the address bar and the session in step (routes.ts has the rules). The path moved: the
// person navigated, so the URL rules apply. The state moved: the URL follows it, without a history
// entry, except moving forward through set-up, which adds one so Back returns. Entering a route goes
// through Session.click, like a button.
import { useEffect, useRef, useSyncExternalStore } from 'react';
import type { Entry } from './registry';
import { ENTRIES, ROUTES, fromHash, onNavigate, onState, stepOf, type Step } from './routes';
import type { WizardState } from './state';

export function useRouteSync(entry: Entry, path: string, nav: { replace(url: string): void; push(url: string): void }, renew: () => Entry): WizardState {
  const { session } = entry;
  const s = useSyncExternalStore(session.store.subscribe, session.store.get, session.store.get);
  const last = useRef<string | undefined>(undefined);
  /** a push asked for and not yet in the address bar: a store change meanwhile must not push it again */
  const pushing = useRef<string | undefined>(undefined);

  useEffect(() => {
    const search = entry.demo ? location.search : '';
    const url = (step: Step) => entry.base + ROUTES[step] + search;
    const to = (step: Step) => nav.replace(url(step));
    const step = path.startsWith(entry.base) ? stepOf(path.slice(entry.base.length)) : undefined;
    const moved = last.current !== path;
    if (moved) pushing.current = undefined; // the address bar moved: any push asked for has landed or lost
    if (moved && !entry.demo && step === 'start') {
      // this frame's own older #/ links. The path isn't recorded yet, so StrictMode's second run
      // repeats this same redirect instead of putting the start back in between.
      const old = fromHash(location.hash);
      if (old) { to(old); return; }
    }
    last.current = path;
    const d = entry.demo || !moved ? onState(s, step) : onNavigate(s, session.started, step);
    if (d.kind === 'replace') to(d.step);
    if (d.kind === 'push' && pushing.current !== url(d.step)) { pushing.current = url(d.step); nav.push(url(d.step)); }
    if (d.kind === 'enter') {
      const e = d.fresh ? renew() : entry;
      const route = ENTRIES[d.step]!.route;
      void e.session.click('route', () => e.session.route(route));
    }
  }, [path, s, entry, session, nav, renew]);

  return s;
}
