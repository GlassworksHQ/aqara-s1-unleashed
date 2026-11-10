// The whole install flow against simulated hardware: FEL boot ROM -> our U-Boot's fastboot holding the
// panel's real 4.5.80 partitions -> the real patcher -> flash + verify -> adb provisioning.
// With the goldens, the flashed boot/vbmeta/splash must equal what tools/install/fel-install.sh wrote.
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { AdbKey, PanelShell } from '../src/adb';
import { BACKUP_PARTS, backupZip, readBackupZip, sha256sums, type BackupFile, type BackupMeta } from '../src/app/backup';
import { InstallFlow, restoreFromBackup, type FlowUi } from '../src/app/flow';
import type { AssetName } from '../src/assets';
import { Fastboot } from '../src/fastboot';
import { FelDevice } from '../src/fel';
import { FakeBrom, FakeUboot, eq, makeSpl } from './fakes';

const B = join(homedir(), 'src/aqara-panel-backups');
const STOCK = join(B, 'install-4.5.80-20261109-203611');
const G = process.env.S1_WEB_GOLDEN ?? join(B, 'web-golden');
const HAVE = existsSync(join(STOCK, 'boot.img')) && existsSync(join(G, 'out/SHA256SUMS'));
const rd = (p: string) => new Uint8Array(readFileSync(p));
const SPL = join(homedir(), 'src/u-boot-s1/build-s1/u-boot-sunxi-with-spl.bin');

function assets(): Record<AssetName, () => Uint8Array> {
  const ov = (f: string) => () => rd(join(G, 'fixtures/overlay', f));
  return {
    'uboot-spl': () => (existsSync(SPL) ? rd(SPL) : makeSpl(0x9000)),
    uboot: () => new Uint8Array(200_000).fill(7),
    magisk: () => rd(join(G, 'fixtures/Magisk-v31.1.apk')),
    'otbr-agent': ov('otbr-agent'), 'ot-ctl': ov('ot-ctl'),
    'radio-rc': ov('s1kiosk_radio.rc'), 'radio-sh': ov('s1kiosk_radio.sh'),
    'firstboot-rc': ov('s1kiosk_firstboot.rc'), 'firstboot-sh': ov('s1kiosk_firstboot.sh'),
    'netwatch-rc': ov('s1kiosk_netwatch.rc'), 'netwatch-sh': ov('s1kiosk_netwatch.sh'),
    'branding-rc': ov('s1kiosk_branding.rc'), bootanimation: ov('s1kiosk_bootanimation.zip'),
    bootlogo: () => rd(join(G, 'fixtures/bootlogo.bmp')),
    'kiosk-apk': () => new Uint8Array([0x50, 0x4b, 3, 4]),
    'keyboard-apk': () => new Uint8Array([0x50, 0x4b, 3, 4]),
  };
}

class Panel implements PanelShell {
  cmds: string[] = [];
  async run(cmd: string) {
    this.cmds.push(cmd);
    if (cmd === 'getprop sys.boot_completed') return { out: '1\n', code: 0 };
    if (cmd === 'getprop ro.build.version.sdk') return { out: '29\n', code: 0 };
    if (cmd.startsWith('pm install')) return { out: 'Success\n', code: 0 };
    return { out: '', code: 0 };
  }
  async push() {}
}

function ui(): FlowUi & { logs: string[]; asked: string[]; overall: number[] } {
  const u = {
    logs: [] as string[], asked: [] as string[], overall: [] as number[],
    log: (l: string) => { u.logs.push(l); },
    progress: (p: { overall: number }) => { u.overall.push(p.overall); },
    partition: () => {},
    ask: async <T>(kind: string, _t: string, action: () => Promise<T>) => { u.asked.push(kind); return action(); },
  };
  return u;
}

describe.skipIf(!HAVE)('install flow on simulated hardware with the real 4.5.80 partitions', () => {
  it('backs up, patches, flashes exactly what the CLI flashed, reboots, provisions', async () => {
    const a = assets();
    const layout: [string, number | Uint8Array][] = [
      ...BACKUP_PARTS.map((p) => [p, rd(join(STOCK, `${p}.img`))] as [string, Uint8Array]),
      ['super', 0x60000000],
    ];
    const uboot = new FakeUboot(layout);
    const saved: { meta: BackupMeta; files: BackupFile[] }[] = [];
    const panel = new Panel();
    const key: AdbKey = {
      publicKey: new TextDecoder().decode(rd(join(G, 'fixtures/test_adbkey.pub'))).trimEnd(),
      store: { generateKey: () => { throw new Error('unused'); }, iterateKeys: () => [] },
    };
    const u = ui();
    let fastbootAsks = 0;
    const flow = new InstallFlow({
      asset: async (n) => a[n](),
      hasAsset: async () => true,
      backups: { save: async (meta, files) => { saved.push({ meta, files }); }, original: async () => undefined },
      key,
      sleep: async () => {},
      devices: {
        knownFastboot: async () => undefined, // first time: the browser must ask
        requestFastboot: async () => { fastbootAsks++; return new Fastboot(uboot); },
        knownAdb: async () => panel,
        requestAdb: async () => panel,
      },
    }, u);

    const brom = new FakeBrom();
    await flow.bootFromFel(new FelDevice(brom));
    expect(brom.resetEntry).toBe(0x4a000000);
    const fb = await flow.connectFastboot();
    expect(u.asked).toEqual(['fastboot']);
    expect(fastbootAsks).toBe(1);

    const res = await flow.install(fb);
    // the backup is the panel's own partitions, same hashes as the CLI's backup
    const cliSums = readFileSync(join(STOCK, 'SHA256SUMS'), 'utf8').split('\n')
      .filter((l) => BACKUP_PARTS.some((p) => l.endsWith(`  ${p}.img`))).sort().join('\n');
    expect(sha256sums(res.files).trim().split('\n').sort().join('\n')).toBe(cliSums);
    expect(res.meta.bootWasPatched).toBe(false);
    expect(saved).toHaveLength(1);
    // flashed: vbmeta (flags 3), splash, then boot; each equals the CLI's
    expect(uboot.flashed).toEqual(['vbmeta', 'bootloader', 'boot']);
    expect(eq(uboot.data('boot'), rd(join(G, 'out/stock-4.5.80.img')))).toBe(true);
    expect(eq(uboot.data('vbmeta'), rd(join(STOCK, 'vbmeta.flags3.img')))).toBe(true);
    expect(eq(uboot.data('bootloader'), rd(join(G, 'out/bootloader-glass-tile.img')))).toBe(true);
    expect(uboot.rebooted).toBe(true);
    // progress only goes forward
    expect(u.overall.every((x, i) => i === 0 || x >= u.overall[i - 1]! - 1e-9)).toBe(true);

    const p = await flow.connectAdb();
    await flow.setUpApp(p, { haUrl: 'http://ha.local:8123/x' });
    expect(panel.cmds).toContain('cmd package set-home-activity sh.edm.s1kiosk/.MainActivity');

    // the downloadable zip round-trips and passes its own SHA256SUMS
    const zip = backupZip(res.meta, res.files);
    const back = await readBackupZip(zip);
    expect(back.id).toBe(res.meta.id);
    expect(eq(back.files.get('boot')!, res.files[0]!.data)).toBe(true);

    // undo from that zip: stock boot, vbmeta, splash back
    const u2 = new FakeUboot(layout.map(([n, x]) => [n, n === 'super' ? x : uboot.data(n)]));
    await restoreFromBackup(new Fastboot(u2), back.files, () => {});
    for (const part of ['boot', 'vbmeta', 'bootloader']) expect(eq(u2.data(part), rd(join(STOCK, `${part}.img`)))).toBe(true);
    // and refuses a re-run backup whose boot is already patched
    const patched = new Map(back.files); patched.set('boot', uboot.data('boot'));
    await expect(restoreFromBackup(new Fastboot(new FakeUboot(layout)), patched, () => {})).rejects.toThrow(/already patched/);
  }, 120_000);

  it('a second run on the installed panel produces the same boot and flashes nothing else', async () => {
    const a = assets();
    const installed: [string, number | Uint8Array][] = [
      ...BACKUP_PARTS.map((p) => [p, rd(join(STOCK, p === 'boot' ? 's1-boot.img' : p === 'vbmeta' ? 'vbmeta.flags3.img' : `${p}.img`))] as [string, Uint8Array]),
      ['super', 0x60000000],
    ];
    installed[BACKUP_PARTS.indexOf('boot')] = ['boot', rd(join(G, 'out/stock-4.5.80.img'))];
    installed[BACKUP_PARTS.indexOf('bootloader')] = ['bootloader', rd(join(G, 'out/bootloader-glass-tile.img'))];
    const uboot = new FakeUboot(installed);
    const key: AdbKey = {
      publicKey: new TextDecoder().decode(rd(join(G, 'fixtures/test_adbkey.pub'))).trimEnd(),
      store: { generateKey: () => { throw new Error('unused'); }, iterateKeys: () => [] },
    };
    const u = ui();
    const flow = new InstallFlow({
      asset: async (n) => a[n](), hasAsset: async () => true,
      backups: { save: async () => {}, original: async () => undefined }, key, sleep: async () => {},
      devices: { knownFastboot: async () => new Fastboot(uboot), requestFastboot: async () => { throw new Error('x'); }, knownAdb: async () => undefined, requestAdb: async () => { throw new Error('x'); } },
    }, u);
    const res = await flow.install(await flow.connectFastboot());
    expect(res.meta.bootWasPatched).toBe(true);
    expect(u.logs.some((l) => l.includes('not the original software'))).toBe(true);
    expect(uboot.flashed).toEqual(['boot']);
    expect(eq(uboot.data('boot'), rd(join(G, 'out/stock-4.5.80.img')))).toBe(true);
  }, 120_000);
});
