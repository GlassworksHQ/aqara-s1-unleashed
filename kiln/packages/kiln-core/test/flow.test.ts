// The rules InstallFlow keeps whatever the recipe does, with a made-up recipe on simulated fastboot.
import { describe, expect, it } from 'vitest';
import type { AdbKey } from '@glassworks/adb';
import { Fastboot } from '@glassworks/fastboot';
import { FakeUboot } from '@glassworks/fel/fakes';
import { InstallFlow, restoreFromBackup, type BackupMeta, type BackupPlan, type FlowUi, type Recipe } from '../src';

/** The made-up frame marks a patched boot with 0xee in its first byte. */
const MARK = 0xee;
const plan: BackupPlan = {
  parts: ['boot', 'vbmeta'], restore: ['boot', 'vbmeta'],
  idPrefix: 'test-backup', db: 'test-backups', isPatched: async (boot) => boot[0] === MARK,
};

function recipe(over: Partial<Recipe<'blob', undefined, undefined>> = {}): Recipe<'blob', undefined, undefined> {
  return {
    fel: { checkChip: async () => 'chip ok', loader: [], boot: async () => {} },
    checkLayout: async () => 'layout ok',
    backup: plan,
    patching: 'Patching',
    patchBoot: async ({ backup }) => { const b = backup.get('boot')!.slice(); b[0] = MARK; return b; },
    writesBeforeBoot: async () => [],
    setUp: async () => undefined,
    ...over,
  };
}

function run(r: Recipe<'blob', undefined, undefined>, uboot = new FakeUboot()) {
  const saved: BackupMeta[] = [];
  const ui: FlowUi = { log: () => {}, progress: () => {}, partition: () => {}, ask: (_, action) => action() };
  const flow = new InstallFlow(r, {
    asset: async () => new Uint8Array(0), hasAsset: async () => false,
    backups: { save: async (m) => { saved.push(m); }, original: async () => undefined },
    key: { publicKey: 'QAAAA test' } as AdbKey, sleep: async () => {},
    devices: { knownFastboot: async () => undefined, requestFastboot: async () => { throw new Error('x'); }, knownAdb: async () => undefined, requestAdb: async () => { throw new Error('x'); } },
  }, ui);
  return { uboot, saved, install: () => flow.install(new Fastboot(uboot)) };
}

describe('InstallFlow keeps its rules whatever the recipe does', () => {
  it('writes the recipe\'s steps, then boot, once and last', async () => {
    const vbmeta = new Uint8Array(0x10000).fill(3);
    const t = run(recipe({ writesBeforeBoot: async () => [{ part: 'vbmeta', data: vbmeta, what: 'vbmeta' }] }));
    await t.install();
    expect(t.uboot.flashed).toEqual(['vbmeta', 'boot']);
    expect(t.uboot.data('boot')[0]).toBe(MARK);
    expect(t.uboot.rebooted).toBe(true);
  });

  it('refuses a recipe that writes boot before the last step, and writes nothing', async () => {
    const t = run(recipe({ writesBeforeBoot: async ({ backup }) => [{ part: 'boot', data: backup.get('boot')!, what: 'early boot' }] }));
    await expect(t.install()).rejects.toThrow('this recipe writes boot before the last step; boot is written once, last. Nothing was written.');
    expect(t.uboot.flashed).toEqual([]);
  });

  it.each([
    ['no boot', { ...plan, parts: ['vbmeta'], restore: ['vbmeta'] }, 'it doesn\'t read boot'],
    ['a restore part it never reads', { ...plan, restore: ['boot', 'spare'] }, 'it doesn\'t read spare'],
    // a recipe written in plain JS can leave it out
    ['no isPatched', { ...plan, isPatched: undefined } as unknown as BackupPlan, 'it has no isPatched'],
  ])('refuses a backup plan with %s before reading anything', async (_, backup, why) => {
    const t = run(recipe({ backup }));
    await expect(t.install()).rejects.toThrow(`this recipe's backup plan is broken: ${why}. Nothing was read.`);
    expect(t.uboot.commands).toEqual([]);
    await expect(restoreFromBackup(backup, new Fastboot(t.uboot), new Map(), () => {})).rejects.toThrow('backup plan is broken');
    expect(t.uboot.commands).toEqual([]);
  });

  it('refuses a patch that leaves a stock boot unchanged, and writes nothing', async () => {
    const t = run(recipe({ patchBoot: async ({ backup }) => backup.get('boot')!.slice() }));
    await expect(t.install()).rejects.toThrow('the patch left boot unchanged, so there is nothing to install. Nothing was written.');
    expect(t.uboot.flashed).toEqual([]);
  });

  it('a re-run on a patched boot may produce the same boot, and writes it', async () => {
    const uboot = new FakeUboot();
    uboot.data('boot')[0] = MARK;
    const t = run(recipe({ patchBoot: async ({ backup }) => backup.get('boot')!.slice() }), uboot);
    const r = await t.install();
    expect(r.meta.bootWasPatched).toBe(true);
    expect(t.uboot.flashed).toEqual(['boot']);
  });

  it('undo refuses a backup that lacks a restore part, and writes nothing', async () => {
    const u = new FakeUboot();
    await expect(restoreFromBackup(plan, new Fastboot(u), new Map([['boot', new Uint8Array(4096)]]), () => {}))
      .rejects.toThrow('this backup has no vbmeta');
    expect(u.flashed).toEqual([]);
  });

  it('asks the recipe whether boot is patched, for the backup and for undo', async () => {
    const t = run(recipe());
    expect((await t.install()).meta.bootWasPatched).toBe(false);
    const patched = new Uint8Array(4096); patched[0] = MARK;
    const u = new FakeUboot();
    await expect(restoreFromBackup(plan, new Fastboot(u), new Map([['boot', patched], ['vbmeta', new Uint8Array(4096)]]), () => {}))
      .rejects.toThrow('this backup\'s boot is already patched (a re-run backup): use the one from your first install');
    expect(u.flashed).toEqual([]);
  });
});
