// "Keep its settings" gives the apps back against the backup in use; from then on that backup is the
// one to restore. A depth change, a new Undo, a pick while the give-back runs, or a browser read that
// was still pending can't switch it. And the give-back counts as changing the panel.
import { afterEach, describe, expect, it, vi } from 'vitest';

const apps = vi.hoisted(() => ({ unprovision: async () => {} }));
vi.mock('@glassworks/aqara-s1-plus-installer', async (orig) => ({
  ...(await orig<typeof import('@glassworks/aqara-s1-plus-installer')>()),
  unprovision: () => apps.unprovision(), // the apps come back (its steps have their own tests)
}));
afterEach(() => { apps.unprovision = async () => {}; });
vi.spyOn(console, 'error').mockImplementation(() => {});

import type { PanelShell } from '@glassworks/adb';
import { s1Plus } from '@glassworks/aqara-s1-plus-installer';
import { backupFile, backupZip, type BackupMeta } from '@glassworks/kiln-core';
import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { demoBootImage } from '../src/wizard/demo';
import { Session } from '../src/wizard/session';
import { initial } from '../src/wizard/state';

async function setup() {
  const parts = { boot: demoBootImage(), vbmeta: new Uint8Array(4096), bootloader: new Uint8Array(4096) };
  const files = await Promise.all(Object.entries(parts).map(([p, d]) => backupFile(p, d)));
  const meta: BackupMeta = { id: 's1-backup-20261112-090000', created: '', bootWasPatched: false, files: files.map((f) => ({ part: f.part, size: f.data.length, sha256: f.sha256 })) };
  let pick!: (p: PanelShell) => void;
  /** set: this browser's backup reads wait until `release` */
  const gate: { on: boolean; release?: () => void } = { on: false };
  const files_ = vi.fn(async () => { if (gate.on) await new Promise<void>((r) => { gate.release = r; }); return files; });
  const requestAdb = vi.fn(() => new Promise<PanelShell>((r) => { pick = r; }));
  const s = new Session({
    recipe: s1Plus, frame: s1PlusUi, requestFel: () => new Promise(() => {}),
    asset: async () => new Uint8Array(0), hasAsset: async () => false,
    backups: { save: async () => {}, original: async () => meta, files: files_ },
    loadKey: async () => ({ publicKey: 'QAAAA test@example' }) as never,
    devices: { knownFastboot: async () => undefined, requestFastboot: () => new Promise(() => {}), knownAdb: async () => undefined, requestAdb },
  }, initial('undo'));
  await s.openUndo();
  await new Promise((r) => setTimeout(r)); // the adb key loads
  s.store.dispatch({ type: 'undo', patch: { depth: 'keep' } });
  const inUse = () => (s as unknown as { undoFiles?: Map<string, Uint8Array> }).undoFiles;
  // another backup, as a file: a different boot
  const other = backupZip({ ...meta, id: 's1-backup-other' }, await Promise.all(Object.entries({ ...parts, boot: demoBootImage(2 << 20) }).map(([p, d]) => backupFile(p, d))));
  const otherFile = new File([await other.arrayBuffer()], 'other.zip');
  return { s, inUse, pick: () => pick({ run: async () => ({ out: '', code: 0 }), push: async () => {} } as never), otherFile, gate, browserReads: files_ };
}

describe('the backup the apps were given back against', () => {
  it('stays through a depth change and a new Undo', async () => {
    const { s, inUse, pick, otherFile } = await setup();
    const browser = inUse();
    const giving = s.click('undo-continue', () => s.undoContinue());
    pick();
    await giving;
    expect(s.store.state.undo).toMatchObject({ appsBack: true, locked: true });
    s.store.dispatch({ type: 'undo', patch: { depth: 'factory', appsBack: false, note: undefined } }); // what the depth radio does
    await s.undoFromFile(otherFile);
    expect(inUse()).toBe(browser);
    await s.openUndo(); // Back from Connect
    expect(s.store.state.undo).toMatchObject({ locked: true, source: 'browser', ready: true });
    expect(inUse()).toBe(browser);
    await s.undoFromFile(otherFile);
    expect(inUse()).toBe(browser);
  });

  it('can\'t be switched while the give-back runs', async () => {
    const { s, inUse, pick, otherFile } = await setup();
    const browser = inUse();
    const giving = s.click('undo-continue', () => s.undoContinue());
    await s.undoFromFile(otherFile); // picked while the adb device list is open
    expect(s.store.state.undo.source).toBe('browser');
    pick();
    await giving;
    expect(s.store.state.undo.locked).toBe(true);
    expect(inUse()).toBe(browser); // the backup the give-back checked
  });

  it('isn\'t replaced by a browser read that was still pending when the give-back started', async () => {
    const { s, inUse, pick, otherFile, gate } = await setup();
    await s.undoFromFile(otherFile);
    const file = inUse();
    gate.on = true;
    const back = s.undoFromBrowser(); // "Use this browser's": the read waits
    await vi.waitFor(() => expect(gate.release).toBeDefined());
    const giving = s.click('undo-continue', () => s.undoContinue()); // gives back against the file
    gate.release!();
    await back; // the read ends while the give-back runs
    pick();
    await giving;
    expect(s.store.state.undo).toMatchObject({ locked: true, source: 'file' });
    expect(inUse()).toBe(file);
  });

  it('"Use this browser\'s" during the give-back doesn\'t even read', async () => {
    const { s, inUse, pick, browserReads } = await setup();
    const browser = inUse();
    const reads = browserReads.mock.calls.length;
    const giving = s.click('undo-continue', () => s.undoContinue());
    await s.undoFromBrowser();
    expect(browserReads.mock.calls.length).toBe(reads);
    pick();
    await giving;
    expect(inUse()).toBe(browser);
  });
});

describe('giving the apps back', () => {
  const closedList = (s: Session) => (s as unknown as { fail(at: number, e: unknown): void }).fail(1, new DOMException('No device selected.', 'NotFoundError'));

  it('counts as changing the panel: a closed device list afterwards keeps Undo and says what changed', async () => {
    const { s, pick } = await setup();
    const giving = s.click('undo-continue', () => s.undoContinue());
    pick();
    await giving;
    expect(s.store.state.changed).toBe('apps');
    s.store.dispatch({ type: 'go', screen: 'connect', flow: 'undo' }); // Continue
    closedList(s);
    expect(s.store.state.failure).toMatchObject({ wrote: true, body: expect.stringMatching(/^This attempt wrote nothing; the panel's apps were changed earlier\. /) });
  });

  it('counts from the start, so a give-back that stops partway counts too', async () => {
    const { s, pick } = await setup();
    let seen: unknown;
    apps.unprovision = async () => { seen = s.store.state.changed; throw new Error('pm enable failed'); };
    const giving = s.click('undo-continue', () => s.undoContinue());
    pick();
    await giving;
    expect(seen).toBe('apps'); // set before it started
    expect(s.store.state.undo.locked).toBeFalsy(); // not given back: no lock
    expect(s.store.state.undo.note?.kind).toBe('err');
    expect(s.store.state.changed).toBe('apps');
  });

  it('a refused backup changes nothing, and isn\'t counted', async () => {
    const { s, pick } = await setup();
    apps.unprovision = async () => { throw new Error('must not run'); };
    s.store.dispatch({ type: 'undo', patch: { depth: 'keep' } });
    (s as unknown as { undoFiles: Map<string, Uint8Array> }).undoFiles = new Map([['boot', new Uint8Array(8)]]); // no vbmeta, no bootloader
    const giving = s.click('undo-continue', () => s.undoContinue());
    pick();
    await giving;
    expect(s.store.state.undo.note?.title).toBe('This backup can\'t undo the install.');
    expect(s.store.state.changed).toBeUndefined();
  });
});
