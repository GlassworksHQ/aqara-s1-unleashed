// @vitest-environment jsdom
// Done offers the backup a run in this tab made, from whichever session reaches Done: the one that
// installed, a new one (set-up entered again after leaving, S1's renew), or one after a reload. The
// backup is the person's only copy outside this browser.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { demoSession } from '../src/wizard/demo';
import { registry } from '../src/wizard/registry';
import type { Session } from '../src/wizard/session';

// bootpatch's wasm can't load under Node here; the demo's stock boot isn't patched
vi.mock('@glassworks/bootpatch', async (orig) => ({ ...(await orig<object>()), isMagiskPatched: async () => false }));
vi.spyOn(console, 'error').mockImplementation(() => {});
const sessions: Session[] = [];
afterEach(() => { registry.clear(); sessionStorage.clear(); for (const s of sessions.splice(0)) s.close(); });

const entry = (session: Session) => ({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session });
const until = async (what: string, f: () => boolean) => {
  for (let i = 0; i < 2500 && !f(); i++) await new Promise((r) => setTimeout(r, 10));
  if (!f()) throw new Error(`timed out waiting for ${what}`);
};

/** The simulated install, through the real InstallFlow, up to the app's settings. Returns the backup's id. */
async function install(s: Session): Promise<string> {
  void s.click('connect', () => s.connectFel());
  await until('the fastboot ask', () => s.store.state.ask?.kind === 'fastboot');
  void s.asks.click();
  await until('the adb ask', () => s.store.state.ask?.kind === 'adb');
  void s.asks.click();
  await until('settings', () => s.store.state.screen === 'setup' && s.store.state.setup.phase === 'settings');
  return s.store.state.backup!.id;
}

const downloads = (s: Session) => {
  const got: string[] = [];
  (s as unknown as { d: { download: (name: string) => void } }).d.download = (name) => got.push(name);
  return got;
};

describe('Done\'s backup download', () => {
  it('is offered by the session that installed', async () => {
    const one = demoSession('run');
    sessions.push(one);
    registry.set(entry(one));
    const id = await install(one);
    one.finish();
    expect(one.store.state.done!.backup!.name).toBe(`${id}.zip`);
    const got = downloads(one);
    await one.downloadBackup();
    expect(got).toEqual([`${id}.zip`]);
  }, 30_000);

  it('is offered after set-up in a new session, and after a reload', async () => {
    const one = demoSession('run');
    sessions.push(one);
    registry.set(entry(one));
    const id = await install(one);

    const two = demoSession('app'); // set-up entered again: S1's renew makes a new session
    sessions.push(two);
    registry.set(entry(two));
    await two.route('resume');
    two.finish();
    expect(two.store.state.done!.backup).toMatchObject({ name: `${id}.zip`, info: expect.stringMatching(/^9 partitions · /) });
    const got = downloads(two);
    await two.downloadBackup(); // its files come from this browser's store, not from session one
    expect(got).toEqual([`${id}.zip`]);

    vi.resetModules(); // a reload: new modules, the same tab's sessionStorage
    const again = await import('../src/wizard/registry');
    const demo = await import('../src/wizard/demo');
    const three = demo.demoSession('app');
    sessions.push(three);
    again.registry.set(entry(three));
    three.finish();
    expect(three.store.state.done!.backup!.name).toBe(`${id}.zip`);
    again.registry.clear();
  }, 30_000);

  it('is not offered when no run in this tab made a backup', () => {
    const s = demoSession('app');
    sessions.push(s);
    registry.set(entry(s));
    s.finish();
    expect(s.store.state.done!.backup).toBeUndefined();
  });
});
