'use client';
// The wizard for one frame: the shell, the screen for the current state, the "your turn" sheet, and
// what Kiln adds around a long write (leave-page warning, screen wake lock, live announcements).
import { useEffect, useSyncExternalStore } from 'react';
import { Shell, useBrowserChip } from '../components/Shell';
import type { FrameUi } from '../frames/aqara-s1-plus';
import { Browser, Connect, Done, Failure, Firing, SetUp, Undo, YourTurn } from './screens';
import type { Session } from './session';
import { busyWriting, milestones, rail, stackLevel, type WizardState } from './state';

/** Where the address bar points for each state, so a reload picks up there (docs/install.md). */
function hashFor(s: WizardState): string | undefined {
  if (s.screen === 'connect') return s.flow === 'undo' ? 'undo' : 'install';
  if (s.screen === 'undo') return 'undo';
  if (s.screen === 'setup') {
    const p = s.setup.phase;
    return p === 'settings' || p === 'waiting' ? 'resume' : p === 'wifi' ? 'wifi' : p === 'ha' ? 'home-assistant' : 'radio';
  }
  return undefined;
}

function announce(s: WizardState, frame: FrameUi): string {
  if (s.ask) return `Your turn. ${frame.asks[s.ask.kind].title} Pick ${frame.asks[s.ask.kind].pick} in the list.`;
  if (s.screen === 'firing') { const m = milestones(s, frame.short).at(-1); return m ? `${m.text}${m.done ? ', done' : ''}.` : ''; }
  if (s.screen === 'error') return `${s.failure?.title} ${s.failure?.body}`;
  if (s.screen === 'done') return s.done?.title ?? '';
  return '';
}

export function Wizard({ session, frame, routes = true }: { session: Session; frame: FrameUi; routes?: boolean }) {
  const s = useSyncExternalStore(session.store.subscribe, session.store.get, session.store.get);
  const chip = useBrowserChip();

  useEffect(() => {
    if (!routes) return;
    const go = () => {
      if (session.store.state.screen === 'firing') return; // never leave a running install
      const r = location.hash.replace(/^#\/?/, '');
      if (r) void session.route(r);
    };
    go();
    addEventListener('hashchange', go);
    return () => removeEventListener('hashchange', go);
  }, [session, routes]);

  useEffect(() => {
    if (!routes) return;
    const h = hashFor(s);
    const want = h ? `#/${h}` : '';
    if (location.hash !== want) history.replaceState(null, '', want || location.pathname + location.search);
  }, [s, routes]);

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
      {unsupported || s.screen === 'browser' ? <Browser />
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
