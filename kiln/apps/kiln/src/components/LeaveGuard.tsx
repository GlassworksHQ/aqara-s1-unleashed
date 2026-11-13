'use client';
// While a frame's session writes to the panel, leaving or reloading the page asks first, and the
// screen stays awake. It sits in the root layout and reads the registry, so it holds on every route:
// the session keeps writing when someone goes to the picker (wizard/registry.ts).
import { useEffect, useState, useSyncExternalStore } from 'react';
import { registry } from '../wizard/registry';
import { busyWriting } from '../wizard/state';

export function useWriting(): boolean {
  const entry = useSyncExternalStore(registry.subscribe, registry.get, () => undefined);
  const [writing, setWriting] = useState(false);
  useEffect(() => {
    if (!entry) { setWriting(false); return; }
    const check = () => setWriting(busyWriting(entry.session.store.state));
    check();
    return entry.session.store.subscribe(check);
  }, [entry]);
  return writing;
}

export function LeaveGuard() {
  const writing = useWriting();
  useEffect(() => {
    if (!writing) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); };
    addEventListener('beforeunload', warn);
    let lock: WakeLockSentinel | undefined;
    let gone = false;
    const acquire = () => {
      navigator.wakeLock?.request('screen').then((l) => { if (gone) void l.release(); else lock = l; }, () => {});
    };
    // The browser lets go of the lock when the tab is hidden; take it again on the way back.
    const back = () => { if (document.visibilityState === 'visible' && (!lock || lock.released)) acquire(); };
    acquire();
    document.addEventListener('visibilitychange', back);
    return () => {
      gone = true;
      removeEventListener('beforeunload', warn);
      document.removeEventListener('visibilitychange', back);
      void lock?.release();
    };
  }, [writing]);
  return null;
}
