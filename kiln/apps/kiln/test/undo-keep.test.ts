// "Keep its settings" gives the panel back to Aqara's apps before the restore, and that removes or
// disables S1 Kiosk. So it only runs for a backup undo can restore (kiln-core's checkRestorable); a
// backup that can't be restored is refused with nothing on the panel changed.
import { describe, expect, it, vi } from 'vitest';
import { s1Plus } from '@glassworks/aqara-s1-plus-installer';
import { backupFile, type BackupMeta } from '@glassworks/kiln-core';
import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { demoBootImage } from '../src/wizard/demo';
import { Session } from '../src/wizard/session';
import { initial } from '../src/wizard/state';

async function setup(parts: Record<string, Uint8Array>, recipe = s1Plus) {
  const files = await Promise.all(Object.entries(parts).map(([p, d]) => backupFile(p, d)));
  const meta: BackupMeta = { id: 's1-backup-20261112-090000', created: '', bootWasPatched: false, files: files.map((f) => ({ part: f.part, size: f.data.length, sha256: f.sha256 })) };
  const panel = { run: vi.fn(async () => ({ out: '', code: 0 })), push: vi.fn(async () => {}) };
  const requestAdb = vi.fn(async () => panel);
  const session = new Session({
    recipe, frame: s1PlusUi, requestFel: () => new Promise(() => {}),
    asset: async () => new Uint8Array(0), hasAsset: async () => false,
    backups: { save: async () => {}, original: async () => meta, files: async () => files },
    loadKey: async () => ({ publicKey: 'QAAAA test@example' }) as never,
    devices: { knownFastboot: async () => undefined, requestFastboot: () => new Promise(() => {}), knownAdb: async () => undefined, requestAdb },
  }, initial('undo'));
  await session.openUndo();
  session.store.dispatch({ type: 'undo', patch: { depth: 'keep' } });
  return { session, panel, requestAdb };
}

describe('Undo, keeping the settings', () => {
  it('refuses a backup undo can\'t restore, before touching the panel', async () => {
    const { session, panel, requestAdb } = await setup({ boot: demoBootImage() }); // no vbmeta, no splash
    await session.undoContinue();
    expect(requestAdb).toHaveBeenCalledTimes(1); // the picker still opens inside the click
    expect(panel.run).not.toHaveBeenCalled();
    expect(panel.push).not.toHaveBeenCalled();
    expect(session.store.state.undo).toMatchObject({
      appsBack: false,
      note: {
        kind: 'err', title: 'This backup can\'t undo the install.', sub: expect.stringMatching(/^This backup has no .+\. This step changed nothing on the panel\.$/),
        link: { href: s1PlusUi.help.undo, text: 'Undo by hand' }, // docs/install.md#undo: the way on
      },
    });
  });

  it('reports a broken plan in the recipe as a backup problem, not as USB, and changes nothing', async () => {
    const broken = { ...s1Plus, backup: { ...s1Plus.backup, restore: [...s1Plus.backup.restore, 'not-backed-up'] } };
    const { session, panel } = await setup({ boot: demoBootImage(), vbmeta: new Uint8Array(4096), bootloader: new Uint8Array(4096) }, broken);
    await session.undoContinue();
    expect(panel.run).not.toHaveBeenCalled();
    const note = session.store.state.undo.note!;
    expect(note).toMatchObject({ kind: 'err', title: 'Kiln can\'t check this backup.', link: { href: s1PlusUi.help.undo } });
    expect(note.sub).toMatch(/not-backed-up.*This step changed nothing on the panel\.$/);
    expect(note.title).not.toMatch(/adb/);
  });

  it('goes on to give the apps back for a backup it can restore', async () => {
    const { session, panel } = await setup({ boot: demoBootImage(), vbmeta: new Uint8Array(4096), bootloader: new Uint8Array(4096) });
    await session.undoContinue();
    expect(panel.run).toHaveBeenCalled(); // unprovision ran (the stand-in panel then fails it; that's not this test)
    expect(session.store.state.undo.note?.title).not.toBe('This backup can\'t undo the install.');
  });
});
