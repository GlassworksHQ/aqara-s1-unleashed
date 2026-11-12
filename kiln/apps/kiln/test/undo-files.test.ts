// Two backup files picked one after the other: the second one is what Undo uses, even when the first
// one's check ends later.
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

describe('Undo with backup files', () => {
  it('uses the latest file, even when an earlier file\'s check ends later', async () => {
    const s = new Session({
      recipe: s1Plus, frame: s1PlusUi, requestFel: () => new Promise(() => {}),
      asset: async () => new Uint8Array(0), hasAsset: async () => false,
      backups: { save: async () => {}, original: async () => undefined, files: async () => [] },
      loadKey: async () => ({ publicKey: 'QAAAA test@example' }) as never,
      devices: { knownFastboot: async () => undefined, requestFastboot: () => new Promise(() => {}), knownAdb: async () => undefined, requestAdb: () => new Promise(() => {}) },
    }, initial('undo'));
    await s.openUndo();
    const a = s.undoFromFile(new File(['a'], 'a.zip'));
    expect(s.store.state.undo).toMatchObject({ checking: true, ready: false });
    const b = s.undoFromFile(new File(['b'], 'b.zip'));
    await Promise.all([a, b]);
    expect(s.store.state.undo).toMatchObject({ source: 'file', ready: true, checking: false, file: 'b · 1 partitions · checked' });
    expect((s as unknown as { undoFiles: Map<string, Uint8Array> }).undoFiles.get('boot')).toEqual(new Uint8Array([2]));
  });
});
