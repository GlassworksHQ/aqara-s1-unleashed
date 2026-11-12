// The FlowUi adapter, alone and under kiln-core's real InstallFlow on simulated hardware.
import { describe, expect, it, vi } from 'vitest';
import { s1Plus } from '@glassworks/aqara-s1-plus-installer';
import { Fastboot } from '@glassworks/fastboot';
import { FelDevice } from '@glassworks/fel';
import { FakeBrom, FakeUboot, makeSpl } from '@glassworks/fel/fakes';
import type { BackupMeta } from '@glassworks/kiln-core';
import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { demoBootImage } from '../src/wizard/demo';
import { AskBridge, flowUi } from '../src/wizard/flowUi';
import { Session } from '../src/wizard/session';
import { initial, reduce, type Action } from '../src/wizard/state';

function store() {
  let s = initial();
  const actions: Action[] = [];
  return { dispatch: (a: Action) => { actions.push(a); s = reduce(s, a); }, get: () => s, actions };
}

describe('AskBridge', () => {
  it('starts the action inside click(), before anything is awaited', () => {
    const st = store();
    const bridge = new AskBridge(st.dispatch);
    const action = vi.fn(() => new Promise<string>(() => {}));
    void bridge.open('fastboot', action, new AbortController().signal);
    expect(st.get().ask).toEqual({ kind: 'fastboot', busy: false });
    void bridge.click();
    expect(action).toHaveBeenCalledTimes(1);
    expect(st.get().ask?.busy).toBe(true);
  });

  it('resolves the engine\'s ask with what was picked, and closes the sheet', async () => {
    const st = store();
    const bridge = new AskBridge(st.dispatch);
    const asked = bridge.open('adb', async () => 'panel', new AbortController().signal);
    await bridge.click();
    await expect(asked).resolves.toBe('panel');
    expect(st.get().ask).toBeUndefined();
  });

  it('keeps the sheet up when nothing was picked, so the next click tries again', async () => {
    const st = store();
    const bridge = new AskBridge(st.dispatch);
    let n = 0;
    const asked = bridge.open('fastboot', async () => {
      if (n++ === 0) throw new DOMException('No device selected.', 'NotFoundError');
      return 'fb';
    }, new AbortController().signal);
    await bridge.click();
    expect(st.get().ask).toEqual({ kind: 'fastboot', busy: false, error: 'Nothing was picked. Click the button again.' });
    await bridge.click();
    await expect(asked).resolves.toBe('fb');
    expect(st.get().ask).toBeUndefined();
  });

  it('closes the sheet when the device turns up by itself', () => {
    const st = store();
    const bridge = new AskBridge(st.dispatch);
    const stop = new AbortController();
    void bridge.open('fastboot', async () => 'x', stop.signal);
    stop.abort();
    expect(st.get().ask).toBeUndefined();
  });
});

describe('flowUi', () => {
  it('turns the engine\'s calls into wizard actions', () => {
    const st = store();
    const ui = flowUi(st.dispatch, new AskBridge(st.dispatch));
    ui.progress({ overall: 0.3, phase: 'backup', phasePct: 0.5, title: 'Backing up your panel', sub: 'Saving boot · 1 of 9' });
    ui.partition('boot', 'cur', { size: 32, pct: 0.5 });
    ui.log('10:00:00 boot saved, 32 MiB');
    expect(st.actions.map((a) => a.type)).toEqual(['progress', 'partition', 'log']);
    expect(st.get().progress).toMatchObject({ overall: 0.3, phase: 'backup', title: 'Backing up your panel' });
    expect(st.get().parts).toEqual([['boot', { state: 'cur', size: 32, pct: 0.5 }]]);
  });
});

/** A Session on the real S1 recipe and InstallFlow, with simulated USB devices. */
function session(uboot: FakeUboot) {
  const saved: BackupMeta[] = [];
  const s = new Session({
    recipe: s1Plus,
    frame: s1PlusUi,
    requestFel: async () => new FelDevice(new FakeBrom()),
    asset: async (n) => (n === 'uboot-spl' ? makeSpl(0x9000) : new Uint8Array(200_000).fill(7)),
    hasAsset: async () => true,
    backups: { save: async (m) => { saved.push(m); }, original: async () => undefined, files: async () => [] },
    loadKey: async () => ({ publicKey: 'QAAAA test@example' }) as never,
    // a macrotask, so the engine's device polling can't starve the test's own timers
    sleep: () => new Promise((r) => setTimeout(r, 0)),
    devices: {
      knownFastboot: async () => undefined, // first time: the browser must ask
      requestFastboot: async () => new Fastboot(uboot),
      knownAdb: async () => undefined,
      requestAdb: async () => { throw new Error('unused'); },
    },
  });
  vi.spyOn(console, 'error').mockImplementation(() => {});
  return { s, saved };
}

const until = async (f: () => boolean) => { for (let i = 0; i < 2000 && !f(); i++) await new Promise((r) => setTimeout(r, 1)); expect(f()).toBe(true); };

describe('the real InstallFlow through the wizard', () => {
  it('boots over FEL, asks for the fastboot click, then shows a refusal with nothing written', async () => {
    const uboot = new FakeUboot([['boot', 0x1000000], ['vbmeta', 0x10000], ['super', 0x60000000]]);
    const { s, saved } = session(uboot);
    const done = s.connectFel();
    await until(() => !!s.store.state.ask);
    expect(s.store.state).toMatchObject({ screen: 'firing', ask: { kind: 'fastboot' } });
    expect(s.store.state.progress.overall).toBe(0.05);
    expect(s.store.state.log.some((l) => l.endsWith('U-Boot started from RAM; waiting for its fastboot'))).toBe(true);
    await s.asks.click();
    await done;
    expect(s.store.state.screen).toBe('error');
    expect(s.store.state.failure).toMatchObject({ title: 'This isn\'t a panel Kiln knows.', wrote: false, at: 2 });
    expect(s.store.state.failure!.body).toMatch(/^Nothing was written\./);
    expect(uboot.flashed).toEqual([]);
    expect(saved).toEqual([]);
  });

  it('backs up every partition into the store before a later step fails, and says nothing was written', async () => {
    const layout: [string, number | Uint8Array][] = [
      ...s1Plus.backup.parts.map((p): [string, number | Uint8Array] => [p, p === 'boot' ? demoBootImage(0x2000000) : 0x4000]),
      ['super', 0x60000000],
    ];
    const uboot = new FakeUboot(layout);
    const { s, saved } = session(uboot);
    const done = s.connectFel();
    await until(() => !!s.store.state.ask);
    await s.asks.click();
    await done;
    const st = s.store.state;
    expect(st.parts.map(([p, x]) => [p, x.state])).toEqual(s1Plus.backup.parts.map((p) => [p, 'done']));
    expect(saved).toHaveLength(1);
    expect(st.log.some((l) => l.endsWith('boot read twice, hashes match'))).toBe(true);
    // the patch fails on these stand-in assets: after the backup, before any write
    expect(st.screen).toBe('error');
    expect(st.failure).toMatchObject({ wrote: false, at: 2 });
    expect(uboot.flashed).toEqual([]);
  }, 60_000);
});
