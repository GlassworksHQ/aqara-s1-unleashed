// Read-only panel commands that never answer are cancelled on the panel after a while (a signal on
// PanelShell.run), and the step goes on with the cautious answer: a first install, an older app, no
// network name. Each wedged command here only ends when its signal aborts.
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PanelShell } from '@glassworks/adb';
import { demoSession } from '../src/wizard/demo';
import type { Session } from '../src/wizard/session';

vi.spyOn(console, 'error').mockImplementation(() => {});
const open: Session[] = [];
afterEach(() => { for (const s of open.splice(0)) s.close(); });

/** The session's panel, with `wedged` commands hanging until their signal aborts. Returns the aborts seen. */
function wedge(s: Session, wedged: RegExp): string[] {
  const panel = (s as unknown as { panel: PanelShell }).panel;
  const real = panel.run.bind(panel);
  const aborted: string[] = [];
  panel.run = (cmd, signal) => (wedged.test(cmd)
    ? new Promise((_, reject) => signal?.addEventListener('abort', () => { aborted.push(cmd); reject(signal.reason); }))
    : real(cmd, signal));
  return aborted;
}

async function connected(which: Parameters<typeof demoSession>[0]) {
  const s = demoSession(which);
  open.push(s);
  await s.connectPanel();
  return s;
}

describe('a panel that doesn\'t answer a read', () => {
  it('pm path: settings come up as a first install', async () => {
    const s = await connected('app-update');
    const aborted = wedge(s, /^pm path /);
    await s.route('resume');
    expect(s.store.state.setup).toMatchObject({ phase: 'settings', update: false });
    expect(aborted).toEqual(['pm path sh.edm.s1kiosk']);
  }, 10_000);

  it('dumpsys package: the Thread step takes the older app\'s way', async () => {
    const s = await connected('radio-thread');
    await s.route('radio');
    const aborted = wedge(s, /^dumpsys package /);
    await s.applyRadio('thread_router');
    const setup = s.store.state.setup;
    expect(setup.phase === 'radio-next' && !!setup.plan.more).toBe(true);
    expect(aborted).toEqual(['dumpsys package sh.edm.s1kiosk']);
  }, 10_000);

  it('dumpsys wifi: on Wi-Fi, without the network\'s name', async () => {
    const s = await connected('wifi');
    const aborted = wedge(s, /^dumpsys wifi$/);
    await s.wifi();
    expect(s.store.state.setup).toMatchObject({ phase: 'wifi', on: { ip: '192.0.2.50', ssid: undefined } });
    expect(aborted).toEqual(['dumpsys wifi']);
  }, 10_000);
});
