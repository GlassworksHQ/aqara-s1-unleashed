// "Keep its settings" gives the apps back against the backup in use; from then on that backup is the
// one to restore. A depth change, a new Undo, or a pick while the give-back runs can't switch it.
import { describe, expect, it, vi } from 'vitest';

vi.mock('@glassworks/aqara-s1-plus-installer', async (orig) => ({
  ...(await orig<typeof import('@glassworks/aqara-s1-plus-installer')>()),
  unprovision: async () => {}, // the apps come back (its steps have their own tests)
}));

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
  const requestAdb = vi.fn(() => new Promise<PanelShell>((r) => { pick = r; }));
  const s = new Session({
    recipe: s1Plus, frame: s1PlusUi, requestFel: () => new Promise(() => {}),
    asset: async () => new Uint8Array(0), hasAsset: async () => false,
    backups: { save: async () => {}, original: async () => meta, files: async () => files },
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
  return { s, inUse, pick: () => pick({ run: async () => ({ out: '', code: 0 }), push: async () => {} } as never), otherFile };
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
});
