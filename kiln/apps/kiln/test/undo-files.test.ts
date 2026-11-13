// Undo's backup choice: the latest one picked is what Undo uses, whether it is a file whose check
// ends before an earlier file's, or a file picked while this browser's backup still loads. Each read
// waits on a gate the test opens, so the order is the test's, not the clock's.
import { describe, expect, it, vi } from 'vitest';

const gates = vi.hoisted(() => {
  const waiting = new Map<string, () => void>();
  return {
    waiting,
    wait: (name: string) => new Promise<void>((r) => waiting.set(name, r)),
    open(name: string) {
      const r = waiting.get(name);
      if (!r) throw new Error(`nothing waits on ${name}`);
      waiting.delete(name);
      r();
    },
  };
});
vi.mock('@glassworks/kiln-core', async (orig) => {
  const real = await orig<typeof import('@glassworks/kiln-core')>();
  return {
    ...real,
    readBackupZip: async (f: File) => {
      await gates.wait(f.name);
      return { id: f.name.replace('.zip', ''), files: new Map([['boot', new Uint8Array([f.name === 'a.zip' ? 1 : 2])]]) };
    },
  };
});

import { s1Plus } from '@glassworks/aqara-s1-plus-installer';
import type { BackupFile } from '@glassworks/kiln-core';
import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { Session } from '../src/wizard/session';
import { initial } from '../src/wizard/state';

const browserFiles = [{ part: 'boot', data: new Uint8Array([9]), sha256: '', crc: 0 }];

/** `files`: how this browser's backup reads; without it, this browser has none. */
function session(files?: () => Promise<BackupFile[]>) {
  const meta = { id: 's1-backup-browser', created: '', bootWasPatched: false, files: [] };
  return new Session({
    recipe: s1Plus, frame: s1PlusUi, requestFel: () => new Promise(() => {}),
    asset: async () => new Uint8Array(0), hasAsset: async () => false,
    backups: { save: async () => {}, original: async () => (files ? meta : undefined), files: files ?? (async () => []) },
    loadKey: async () => ({ publicKey: 'QAAAA test@example' }) as never,
    devices: { knownFastboot: async () => undefined, requestFastboot: () => new Promise(() => {}), knownAdb: async () => undefined, requestAdb: () => new Promise(() => {}) },
  }, initial('undo'));
}
const gatedBrowser = async () => { await gates.wait('browser'); return browserFiles; };
const reading = (name: string) => vi.waitFor(() => expect(gates.waiting.has(name)).toBe(true));
const boot = (s: Session) => (s as unknown as { undoFiles?: Map<string, Uint8Array> }).undoFiles?.get('boot');

describe('Undo with backup files', () => {
  it('a file picked while this browser\'s backup still loads wins over that load', async () => {
    const s = session(gatedBrowser);
    const opening = s.openUndo();
    await reading('browser');
    const file = s.undoFromFile(new File(['b'], 'b.zip')); // picked meanwhile; its check ends first
    gates.open('b.zip');
    await file;
    gates.open('browser');
    await opening;
    expect(s.store.state.undo).toMatchObject({ source: 'file', ready: true, file: 'b · 1 partitions · checked' });
    expect(boot(s)).toEqual(new Uint8Array([2]));

    const back = s.undoFromBrowser(); // the same through "Use this browser's"
    await reading('browser');
    const again = s.undoFromFile(new File(['b'], 'b.zip'));
    gates.open('b.zip');
    await again;
    gates.open('browser');
    await back;
    expect(s.store.state.undo.source).toBe('file');
    expect(boot(s)).toEqual(new Uint8Array([2]));
  });

  it('uses the latest file, even when an earlier file\'s check ends later', async () => {
    const s = session();
    await s.openUndo();
    const a = s.undoFromFile(new File(['a'], 'a.zip'));
    expect(s.store.state.undo).toMatchObject({ checking: true, ready: false });
    const b = s.undoFromFile(new File(['b'], 'b.zip'));
    gates.open('b.zip');
    await b;
    gates.open('a.zip');
    await a;
    expect(s.store.state.undo).toMatchObject({ source: 'file', ready: true, checking: false, file: 'b · 1 partitions · checked' });
    expect(boot(s)).toEqual(new Uint8Array([2]));
  });

  it('a new Undo forgets a file check still running, so the backup links work again', async () => {
    const s = session();
    await s.openUndo();
    const a = s.undoFromFile(new File(['a'], 'a.zip'));
    expect(s.store.state.undo.checking).toBe(true);
    await s.openUndo(); // e.g. Back from Connect, or Undo from another screen
    expect(s.store.state.undo.checking).toBe(false);
    gates.open('a.zip');
    await a; // its answer is for a choice nobody holds any more
    expect(s.store.state.undo).toMatchObject({ checking: false, ready: false, source: undefined });
    expect(boot(s)).toBeUndefined();
  });

  it('says so when this browser\'s backup can\'t be read, instead of failing unseen', async () => {
    const s = session(async () => { throw new DOMException('The operation failed for reasons unrelated to the database itself.', 'UnknownError'); });
    await expect(s.openUndo()).resolves.toBeUndefined();
    expect(s.store.state.undo).toMatchObject({
      ready: false, source: undefined, file: 'This browser\'s backup can\'t be read.',
      note: { kind: 'err', title: 'Couldn\'t read the backup in this browser.', sub: 'The operation failed for reasons unrelated to the database itself. Use a backup file instead.' },
    });
    expect(boot(s)).toBeUndefined();
  });

  it('keeps a file in use when "Use this browser\'s" can\'t read this browser\'s backup', async () => {
    const s = session(async () => { throw new Error('IndexedDB is closed'); });
    await s.openUndo();
    const file = s.undoFromFile(new File(['b'], 'b.zip'));
    gates.open('b.zip');
    await file;
    await expect(s.undoFromBrowser()).resolves.toBeUndefined();
    expect(s.store.state.undo).toMatchObject({
      source: 'file', ready: true, file: 'b · 1 partitions · checked',
      note: { kind: 'err', sub: 'IndexedDB is closed. Use a backup file instead.' },
    });
    expect(boot(s)).toEqual(new Uint8Array([2]));
  });

  it('keeps the backup once "keep its settings" gave the apps back', async () => {
    const s = session(async () => browserFiles);
    await s.openUndo();
    s.store.dispatch({ type: 'undo', patch: { depth: 'keep', appsBack: true } }); // what giveBack ends with
    void s.undoFromFile(new File(['b'], 'b.zip'));
    expect(gates.waiting.has('b.zip')).toBe(false); // not even read
    expect(s.store.state.undo).toMatchObject({ source: 'browser', ready: true, appsBack: true });
    expect(boot(s)).toEqual(new Uint8Array([9]));
    await s.undoFromBrowser();
    expect(boot(s)).toEqual(new Uint8Array([9]));
  });
});
