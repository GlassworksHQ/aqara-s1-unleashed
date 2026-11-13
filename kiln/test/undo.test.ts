// "Keep the panel's settings": the apps go back (S1 Kiosk removed) only for a backup undo can restore.
import { describe, expect, it } from 'vitest';
import { s1Plus } from '@glassworks/aqara-s1-plus-installer';
import { BackupRefused, type BackupPlan } from '@glassworks/kiln-core';
import { giveBackApps } from '../src/app/undo';

describe('giving the apps back before an undo', () => {
  it('refuses an S1 backup without the splash before the apps go back', async () => {
    let gaveBack = 0;
    const files = new Map([['boot', new Uint8Array(4096)], ['vbmeta', new Uint8Array(4096)]]);
    const p = giveBackApps(s1Plus.backup, files, async () => { gaveBack++; });
    await expect(p).rejects.toThrow('this backup has no bootloader');
    await expect(p).rejects.toBeInstanceOf(BackupRefused);
    expect(gaveBack).toBe(0);
  });

  it('refuses a backup whose boot is already patched before the apps go back', async () => {
    let gaveBack = 0;
    const plan: BackupPlan = { ...s1Plus.backup, isPatched: async () => true };
    const files = new Map(s1Plus.backup.restore.map((p) => [p, new Uint8Array(16)]));
    await expect(giveBackApps(plan, files, async () => { gaveBack++; })).rejects.toThrow('already patched');
    expect(gaveBack).toBe(0);
  });

  it('gives the apps back for a backup undo can restore', async () => {
    let gaveBack = 0;
    const plan: BackupPlan = { ...s1Plus.backup, isPatched: async () => false };
    const files = new Map(s1Plus.backup.restore.map((p) => [p, new Uint8Array(16)]));
    await giveBackApps(plan, files, async () => { gaveBack++; });
    expect(gaveBack).toBe(1);
  });
});
