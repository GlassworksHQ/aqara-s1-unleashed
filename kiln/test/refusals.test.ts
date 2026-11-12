// The CLI's refusal points, through InstallFlow and the S1 recipe, on simulated hardware. Each one
// stops before anything is written. No goldens needed.
import { describe, expect, it } from 'vitest';
import type { AdbKey } from '@glassworks/adb';
import { s1Plus, type AssetName } from '@glassworks/aqara-s1-plus-installer';
import { Fastboot } from '@glassworks/fastboot';
import { A133, FelDevice } from '@glassworks/fel';
import { FakeBrom, FakeUboot } from '@glassworks/fel/fakes';
import { InstallFlow, restoreFromBackup, type FlowUi } from '@glassworks/kiln-core';

function flow() {
  const fetched: AssetName[] = [];
  const saved: unknown[] = [];
  const parts: string[] = [];
  const ui: FlowUi = { log: () => {}, progress: () => {}, partition: (p) => { parts.push(p); }, ask: (_, action) => action() };
  const key = { publicKey: 'QAAAA test@example' } as AdbKey;
  const f = new InstallFlow(s1Plus, {
    asset: async (n) => { fetched.push(n); return new Uint8Array(16); },
    hasAsset: async () => true,
    backups: { save: async (m) => { saved.push(m); }, original: async () => undefined },
    key,
    sleep: async () => {},
    devices: { knownFastboot: async () => undefined, requestFastboot: async () => { throw new Error('x'); }, knownAdb: async () => undefined, requestAdb: async () => { throw new Error('x'); } },
  }, ui);
  return { f, fetched, saved, parts };
}

describe('refusals before any write', () => {
  it('refuses a chip with secure boot on before fetching the U-Boot', async () => {
    const { f, fetched } = flow();
    const brom = new FakeBrom();
    brom.regs.set(A133.sidSecureBootReg, 1);
    await expect(f.bootFromFel(new FelDevice(brom))).rejects.toThrow(/secure boot is enabled on this chip/);
    expect(fetched).toEqual([]);
    expect(brom.splRan).toBeNull();
  });

  it('refuses a partition layout that is not the S1 Plus, before reading anything', async () => {
    const { f, saved, parts } = flow();
    const uboot = new FakeUboot([['boot', 0x1000000], ['vbmeta', 0x10000], ['super', 0x60000000]]);
    await expect(f.install(new Fastboot(uboot))).rejects.toThrow(
      'this does not look like an Aqara S1 Plus (product sunxi, boot 16777216, super 1610612736). Nothing was changed.');
    expect(parts).toEqual([]);
    expect(saved).toEqual([]);
    expect(uboot.flashed).toEqual([]);
  });

  it('undo refuses a backup without vbmeta and writes nothing', async () => {
    const uboot = new FakeUboot();
    const files = new Map([['boot', new Uint8Array(4096)], ['bootloader', new Uint8Array(4096)]]);
    await expect(restoreFromBackup(s1Plus.backup, new Fastboot(uboot), files, () => {})).rejects.toThrow('this backup has no boot or vbmeta');
    expect(uboot.flashed).toEqual([]);
    expect(uboot.rebooted).toBe(false);
  });
});
