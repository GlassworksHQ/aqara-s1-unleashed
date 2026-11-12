// The frame's current session, outside React, so it outlives route changes and the picker can see
// a running install. Type-only imports: the picker reads this without loading the engine.
import type { Session } from './session';

export interface Entry {
  /** the frame's route base, e.g. /aqara-s1-plus */
  base: string;
  name: string;
  session: Session;
  /** ?demo=… states; never in a production build */
  demo?: string;
  demoEntered?: boolean;
}

let current: Entry | undefined;
const listeners = new Set<() => void>();

export const registry = {
  get: (): Entry | undefined => current,
  /** Makes `e` the current session (a new install, or the first visit). */
  set(e: Entry): Entry {
    current = e;
    for (const l of listeners) l();
    return e;
  },
  subscribe(l: () => void) {
    listeners.add(l);
    return () => { listeners.delete(l); };
  },
  /** Tests only. */
  clear() { current = undefined; },
};
