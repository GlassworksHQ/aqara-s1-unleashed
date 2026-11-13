// Records every USB call a full S1 install and undo make on the simulated U-Boot, so a change to
// @glassworks/fastboot can be diffed on the wire: run it before and after, then diff the files.
//   S1_WIRE_TRACE=/tmp/before.txt npx vitest run test/wire-trace.test.ts   (from kiln/)
// The trace holds payload hashes of the real partitions: keep it out of the repo.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { s1Plus, type AssetName } from '@glassworks/aqara-s1-plus-installer';
import { InstallFlow, restoreFromBackup, type FlowUi } from '@glassworks/kiln-core';
import { Fastboot } from '@glassworks/fastboot';
import type { BulkTransport } from '@glassworks/fel/transport';
import { FakeUboot } from '@glassworks/fel/fakes';

const B = join(homedir(), 'src/aqara-panel-backups');
const STOCK = join(B, 'install-4.5.80-20261109-203611');
const G = process.env.S1_WEB_GOLDEN ?? join(B, 'web-golden');
const OUT = process.env.S1_WIRE_TRACE;
const HAVE = existsSync(join(STOCK, 'boot.img')) && existsSync(join(G, 'out/SHA256SUMS'));
const rd = (p: string) => new Uint8Array(readFileSync(p));

const text = (b: Uint8Array) => (b.length <= 64 && b.every((x) => x >= 0x20 && x < 0x7f) ? JSON.stringify(new TextDecoder().decode(b)) : '');
const sha = (b: Uint8Array) => createHash('sha256').update(b).digest('hex').slice(0, 16);
const show = (b: Uint8Array) => text(b) || sha(b);

class Trace implements BulkTransport {
  constructor(private readonly t: BulkTransport, private readonly lines: string[]) {}
  async out(d: Uint8Array, ms?: number) { this.lines.push(`out ${d.length} ${ms} ${show(d)}`); await this.t.out(d, ms); }
  async in(n: number, ms?: number) { const r = await this.t.in(n, ms); this.lines.push(`in ${n} ${ms} -> ${r.length} ${show(r)}`); return r; }
  async inPacket(n: number, ms?: number) { const r = await this.t.inPacket(n, ms); this.lines.push(`inPacket ${n} ${ms} -> ${r.length} ${show(r)}`); return r; }
  async close() { this.lines.push('close'); await this.t.close(); }
}

function assets(): Record<AssetName, () => Uint8Array> {
  const ov = (f: string) => () => rd(join(G, 'fixtures/overlay', f));
  return {
    'uboot-spl': () => new Uint8Array(0), uboot: () => new Uint8Array(0),
    magisk: () => rd(join(G, 'fixtures/Magisk-v31.1.apk')),
    'otbr-agent': ov('otbr-agent'), 'ot-ctl': ov('ot-ctl'),
    'radio-rc': ov('s1kiosk_radio.rc'), 'radio-sh': ov('s1kiosk_radio.sh'),
    'firstboot-rc': ov('s1kiosk_firstboot.rc'), 'firstboot-sh': ov('s1kiosk_firstboot.sh'),
    'netwatch-rc': ov('s1kiosk_netwatch.rc'), 'netwatch-sh': ov('s1kiosk_netwatch.sh'),
    'update-rc': ov('s1kiosk_update.rc'), 'update-sh': ov('s1kiosk_update.sh'),
    'bundle-rc': ov('s1kiosk_bundle.rc'), 'bundle-sh': ov('s1kiosk_bundle.sh'), verify: ov('s1kiosk_verify'), 'bundle-pub': ov('s1kiosk_bundle.pub'),
    'branding-rc': ov('s1kiosk_branding.rc'), bootanimation: ov('s1kiosk_bootanimation.zip'),
    bootlogo: () => rd(join(G, 'fixtures/bootlogo.bmp')),
    'kiosk-apk': () => new Uint8Array(0), 'keyboard-apk': () => new Uint8Array(0),
  };
}

describe.skipIf(!HAVE || !OUT)('S1 wire trace', () => {
  it('records a full install and undo', async () => {
    const lines: string[] = [];
    const layout: [string, number | Uint8Array][] = [
      ...s1Plus.backup.parts.map((p) => [p, rd(join(STOCK, `${p}.img`))] as [string, Uint8Array]),
      ['super', 0x60000000],
    ];
    const uboot = new FakeUboot(layout);
    const a = assets();
    const ui: FlowUi = { log: () => {}, progress: () => {}, partition: () => {}, ask: async (_k, action) => action() };
    const flow = new InstallFlow(s1Plus, {
      asset: async (n) => a[n](), hasAsset: async () => true,
      backups: { save: async () => {}, original: async () => undefined },
      key: { publicKey: new TextDecoder().decode(rd(join(G, 'fixtures/test_adbkey.pub'))).trimEnd(), store: { generateKey: () => { throw new Error('unused'); }, iterateKeys: () => [] } },
      sleep: async () => {},
      devices: {
        knownFastboot: async () => new Fastboot(new Trace(uboot, lines)), requestFastboot: async () => { throw new Error('x'); },
        knownAdb: async () => undefined, requestAdb: async () => { throw new Error('x'); },
      },
    }, ui);
    lines.push('# install');
    const res = await flow.install(await flow.connectFastboot());
    expect(uboot.flashed).toEqual(['vbmeta', 'bootloader', 'boot']);

    lines.push('# undo');
    const u2 = new FakeUboot(layout.map(([n, x]) => [n, n === 'super' ? x : uboot.data(n)]));
    await restoreFromBackup(s1Plus.backup, new Fastboot(new Trace(u2, lines)), new Map(res.files.map((f) => [f.part, f.data])), () => {});
    expect(u2.flashed).toEqual(s1Plus.backup.restore);
    writeFileSync(OUT!, `${lines.join('\n')}\n`);
  }, 120_000);
});
