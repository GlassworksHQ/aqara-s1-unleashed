// Undo offers the browser's backup as a download (explain.ts's tips point there).
import { describe, expect, it, vi } from 'vitest';
import { s1Plus } from '@glassworks/aqara-s1-plus-installer';
import { backupFile, backupZip, readBackupZip, type BackupMeta } from '@glassworks/kiln-core';
import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { Session } from '../src/wizard/session';
import { initial } from '../src/wizard/state';

async function setup() {
  const files = await Promise.all(s1Plus.backup.parts.map((p, i) => backupFile(p, new Uint8Array(4096).fill(i + 1))));
  const meta: BackupMeta = { id: 's1-backup-20261112-090000', created: '2026-11-12T09:00:00Z', bootWasPatched: false,
    files: files.map((f) => ({ part: f.part, size: f.data.length, sha256: f.sha256 })) };
  const download = vi.fn<(name: string, blob: Blob) => void>();
  const session = new Session({
    recipe: s1Plus, frame: s1PlusUi, requestFel: () => new Promise(() => {}),
    asset: async () => new Uint8Array(0), hasAsset: async () => false,
    backups: { save: async () => {}, original: async () => meta, files: async () => files },
    loadKey: async () => ({ publicKey: 'QAAAA test@example' }) as never,
    devices: { knownFastboot: async () => undefined, requestFastboot: () => new Promise(() => {}), knownAdb: async () => undefined, requestAdb: () => new Promise(() => {}) },
    download,
  }, initial('undo'));
  return { session, meta, files, download };
}

describe('Undo\'s "Download this backup"', () => {
  it('gives the browser\'s backup as the same zip Done gives', async () => {
    const { session, meta, files, download } = await setup();
    await session.openUndo();
    expect(session.store.state.undo).toMatchObject({ source: 'browser', ready: true, inBrowser: 's1-backup-20261112-090000 · 9 partitions' });
    session.downloadUndoBackup();
    expect(download).toHaveBeenCalledTimes(1);
    const [name, blob] = download.mock.calls[0]!;
    expect(name).toBe(`${meta.id}.zip`);
    const back = await readBackupZip(blob);
    expect(back.id).toBe(meta.id);
    expect([...back.files.keys()].sort()).toEqual([...s1Plus.backup.parts].sort());
    expect(back.files.get('boot')).toEqual(files.find((f) => f.part === 'boot')!.data);
  });

  it('offers nothing for a backup that came from a file', async () => {
    const { session, meta, files, download } = await setup();
    await session.openUndo();
    await session.undoFromFile(new File([backupZip(meta, files)], 'b.zip'));
    expect(session.store.state.undo.source).toBe('file');
    session.downloadUndoBackup();
    expect(download).not.toHaveBeenCalled();
  });
});
