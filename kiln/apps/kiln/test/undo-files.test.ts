// Undo's backup choice: the latest one picked is what Undo uses, whether it is a file whose check
// ends before an earlier file's, or a file picked while this browser's backup still loads.
import { describe, expect, it, vi } from 'vitest';

const slow = vi.hoisted(() => ({ a: 60, b: 5 }));
vi.mock('@glassworks/kiln-core', async (orig) => {
  const real = await orig<typeof import('@glassworks/kiln-core')>();
  return {
    ...real,
    readBackupZip: async (f: File) => {
      await new Promise((r) => setTimeout(r, f.name === 'a.zip' ? slow.a : slow.b));
      return { id: f.name.replace('.zip', ''), files: new Map([['boot', new Uint8Array([f.name === 'a.zip' ? 1 : 2])]]) };
    },
  };
});

import { s1Plus } from '@glassworks/aqara-s1-plus-installer';
import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { Session } from '../src/wizard/session';
import { initial } from '../src/wizard/state';

function session(browser?: { slowMs: number }) {
  const meta = { id: 's1-backup-browser', created: '', bootWasPatched: false, files: [] };
  const browserFiles = [{ part: 'boot', data: new Uint8Array([9]), sha256: '', crc: 0 }];
  return new Session({
    recipe: s1Plus, frame: s1PlusUi, requestFel: () => new Promise(() => {}),
    asset: async () => new Uint8Array(0), hasAsset: async () => false,
    backups: {
      save: async () => {},
      original: async () => (browser ? meta : undefined),
      files: async () => { await new Promise((r) => setTimeout(r, browser?.slowMs ?? 0)); return browserFiles; },
    },
    loadKey: async () => ({ publicKey: 'QAAAA test@example' }) as never,
    devices: { knownFastboot: async () => undefined, requestFastboot: () => new Promise(() => {}), knownAdb: async () => undefined, requestAdb: () => new Promise(() => {}) },
  }, initial('undo'));
}
const boot = (s: Session) => (s as unknown as { undoFiles?: Map<string, Uint8Array> }).undoFiles?.get('boot');

describe('Undo with backup files', () => {
  it('a file picked while this browser\'s backup still loads wins over that load', async () => {
    const s = session({ slowMs: 80 });
    const opening = s.openUndo(); // reads the browser backup slowly
    await new Promise((r) => setTimeout(r, 10));
    await s.undoFromFile(new File(['b'], 'b.zip')); // picked meanwhile; its check ends first
    await opening;
    expect(s.store.state.undo).toMatchObject({ source: 'file', ready: true, file: 'b · 1 partitions · checked' });
    expect(boot(s)).toEqual(new Uint8Array([2]));
    const back = s.undoFromBrowser(); // the same through "Use this browser's"
    await new Promise((r) => setTimeout(r, 10));
    await s.undoFromFile(new File(['b'], 'b.zip'));
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
    await Promise.all([a, b]);
    expect(s.store.state.undo).toMatchObject({ source: 'file', ready: true, checking: false, file: 'b · 1 partitions · checked' });
    expect(boot(s)).toEqual(new Uint8Array([2]));
  });
});
