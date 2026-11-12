'use client';
// Keeps the address bar and the session in step (routes.ts has the rules). The path moved: the
// person navigated, so the URL rules apply. The state moved: the URL follows it, without a history
// entry. Entering a route goes through Session.click, like a button.
import { useEffect, useRef, useSyncExternalStore } from 'react';
import type { Entry } from './registry';
import { ENTRIES, ROUTES, fromHash, onNavigate, onState, stepOf, type Step } from './routes';
import type { WizardState } from './state';

export function useRouteSync(entry: Entry, path: string, replace: (url: string) => void, renew: () => Entry): WizardState {
  const { session } = entry;
  const s = useSyncExternalStore(session.store.subscribe, session.store.get, session.store.get);
  const last = useRef<string | undefined>(undefined);

  useEffect(() => {
    const search = entry.demo ? location.search : '';
    const to = (step: Step) => replace(entry.base + ROUTES[step] + search);
    const step = path.startsWith(entry.base) ? stepOf(path.slice(entry.base.length)) : undefined;
    const moved = last.current !== path;
    if (moved && !entry.demo && step === 'start') {
      // this frame's own older #/ links. The path isn't recorded yet, so StrictMode's second run
      // repeats this same redirect instead of putting the start back in between.
      const old = fromHash(location.hash);
      if (old) { to(old); return; }
    }
    last.current = path;
    const d = entry.demo || !moved ? onState(s, step) : onNavigate(s, session.started, step);
    if (d.kind === 'replace') to(d.step);
    if (d.kind === 'enter') {
      const e = d.fresh ? renew() : entry;
      const route = ENTRIES[d.step]!.route;
      void e.session.click('route', () => e.session.route(route));
    }
  }, [path, s, entry, session, replace, renew]);

  return s;
}
