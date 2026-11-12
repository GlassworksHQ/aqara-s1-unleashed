'use client';
// The wizard for one frame: the shell, the screen for the current state, the "your turn" sheet, and
// what Kiln adds around a long write (leave-page warning, screen wake lock, live announcements). The
// routes are useRouteSync's.
import { useEffect, useSyncExternalStore } from 'react';
import { Shell, useBrowserChip } from '../components/Shell';
import type { FrameUi } from '../frames/aqara-s1-plus';
import { Browser, Connect, Done, Failure, Firing, SetUp, Undo, YourTurn } from './screens';
import type { Session } from './session';
import { busyWriting, milestones, rail, stackLevel, type WizardState } from './state';

function announce(s: WizardState, frame: FrameUi): string {
  if (s.ask) return `Your turn. ${frame.asks[s.ask.kind].title} Pick ${frame.asks[s.ask.kind].pick} in the list.`;
  if (s.screen === 'firing') { const m = milestones(s, frame.short).at(-1); return m ? `${m.text}${m.done ? ', done' : ''}.` : ''; }
  if (s.screen === 'error') return `${s.failure?.title} ${s.failure?.body}`;
  if (s.screen === 'done') return s.done?.title ?? '';
  return '';
}

export function Wizard({ session, frame }: { session: Session; frame: FrameUi }) {
  const s = useSyncExternalStore(session.store.subscribe, session.store.get, session.store.get);
  const chip = useBrowserChip();

  const writing = busyWriting(s);
  useEffect(() => {
    if (!writing) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); };
    addEventListener('beforeunload', warn);
    let lock: WakeLockSentinel | undefined;
    navigator.wakeLock?.request('screen').then((l) => { lock = l; }, () => {});
    return () => { removeEventListener('beforeunload', warn); void lock?.release(); };
  }, [writing]);

  useEffect(() => {
    document.title = s.ask ? `Your turn · ${frame.name} · Kiln` : `${frame.name} · Kiln`;
  }, [s.ask, frame]);

  const p = { s, session, frame };
  const unsupported = chip?.kind === 'bad';
  return (
    <Shell steps={rail(s)} level={stackLevel(s)} error={s.screen === 'error'} chip={s.ask ? { kind: 'work', text: 'Your turn' } : chip}>
      {unsupported ? <Browser />
        : s.screen === 'connect' ? <Connect {...p} />
        : s.screen === 'firing' ? <Firing {...p} />
        : s.screen === 'setup' ? <SetUp {...p} />
        : s.screen === 'done' ? <Done {...p} />
        : s.screen === 'undo' ? <Undo {...p} />
        : <Failure {...p} />}
      <YourTurn {...p} />
      <p className="visually-hidden" aria-live="polite">{announce(s, frame)}</p>
    </Shell>
  );
}
