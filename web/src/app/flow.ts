// tools/install/fel-install.sh in the browser, without the DOM: the UI drives it and renders its
// events. WebUSB only opens a new device from a click, so where the panel re-enumerates (U-Boot's
// fastboot, then Android's adb) the flow asks the UI for a click unless permission was given before.
import type { AdbKey } from '../adb/key';
import type { PanelShell } from '../adb/panel';
import type { AssetName } from '../assets/assets';
import { isMagiskPatched, patchBoot, replaceBootlogo, s1Overlay, vbmetaFlags, withVbmetaFlags } from '../bootpatch';
import type { Fastboot } from '../fastboot/fastboot';
import { bootUboot, checkChip, type FelDevice } from '../fel';
import { provision, type ProvisionOptions } from '../provision/provision';
import {
  BACKUP_PARTS, RESTORE_PARTS, backupFile, backupId, type BackupFile, type BackupMeta, type BackupStore,
} from './backup';

export type Phase = 'boot' | 'backup' | 'patch' | 'flash' | 'verify';
export interface Progress { overall: number; phase: Phase; phasePct: number; title: string; sub: string }
export type PartState = 'pend' | 'cur' | 'done';

export interface FlowUi {
  log(line: string): void;
  progress(p: Progress): void;
  partition(part: string, state: PartState, info: { size?: number; sha256?: string; pct?: number }): void;
  /** Shows a button; on click runs `action` (inside the click, so WebUSB may prompt) and resolves with it. */
  ask<T>(kind: 'fastboot' | 'adb', text: string, action: () => Promise<T>): Promise<T>;
}

/** Where USB devices come from: WebUSB in the browser, simulations in the tests. */
export interface Devices {
  /** Already-permitted fastboot device, or undefined (no prompt). */
  knownFastboot(): Promise<Fastboot | undefined>;
  requestFastboot(): Promise<Fastboot>;
  knownAdb(key: AdbKey): Promise<PanelShell | undefined>;
  requestAdb(key: AdbKey): Promise<PanelShell>;
}

export interface FlowDeps {
  asset(name: AssetName, onProgress?: (done: number, total: number) => void): Promise<Uint8Array>;
  hasAsset(name: AssetName): Promise<boolean>;
  backups: Pick<BackupStore, 'save' | 'original'>;
  key: AdbKey;
  devices: Devices;
  sleep?: (ms: number) => Promise<void>;
  /** Branded splash + boot animation (Glass Tile); default true. BRANDING=none in the CLI. */
  branding?: boolean;
}

const BOOT_SIZE = 0x2000000;      // 32 MiB: S1 Plus layout check (fel-install.sh step 3)
const SUPER_SIZE = 0x60000000;    // 1.5 GiB
const MiB = 1024 * 1024;

export function fmtSize(n: number): string {
  return n >= MiB ? `${Math.round(n / MiB)} MiB` : `${Math.round(n / 1024)} KiB`;
}
const short = (sha: string) => `${sha.slice(0, 4)}…${sha.slice(-4)}`;
const stamp = () => new Date().toTimeString().slice(0, 8);

export interface InstallResult {
  backup: { meta: BackupMeta; files: BackupFile[] };
  bootSha256: string;
  panel: PanelShell;
}

export class InstallFlow {
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(private readonly d: FlowDeps, private readonly ui: FlowUi) {
    this.sleep = d.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
  }

  private log(s: string) { this.ui.log(`${stamp()} ${s}`); }

  /** Steps 1-2: FEL (already opened by the Connect click) to our U-Boot in RAM. Nothing is written. */
  async bootFromFel(fel: FelDevice): Promise<void> {
    fel.log = (l) => this.log(l);
    const p = (overall: number, title: string, sub: string, phasePct = 0) =>
      this.ui.progress({ overall, phase: 'boot', phasePct, title, sub });
    p(0.005, 'Checking the panel', 'Reading its chip over USB');
    const chip = await checkChip(fel);
    this.log(`SoC 0x${chip.socId.toString(16)} (A133), secure boot off`);
    const [spl, uboot] = await Promise.all([this.d.asset('uboot-spl'), this.d.asset('uboot')]);
    p(0.01, 'Starting the installer on the panel', 'Loading it into memory · nothing is written');
    await bootUboot(fel, spl, uboot, (x) => p(0.01 + 0.04 * x, 'Starting the installer on the panel', 'Loading it into memory · nothing is written', x));
    this.log('U-Boot started from RAM; waiting for its fastboot');
  }

  /** Waits for our U-Boot's fastboot; asks for a click if this browser has not been allowed yet. */
  async connectFastboot(): Promise<Fastboot> {
    for (let i = 0; i < 20; i++) {
      const fb = await this.d.devices.knownFastboot();
      if (fb) return fb;
      await this.sleep(500);
    }
    return this.ui.ask('fastboot', 'Allow this page to talk to the panel\'s installer (pick "USB download gadget")',
      () => this.d.devices.requestFastboot());
  }

  /** Steps 3-8. Returns once the panel is rebooting into the patched Android. */
  async install(fb: Fastboot): Promise<{ meta: BackupMeta; files: BackupFile[]; bootSha256: string }> {
    fb.log = (l) => this.log(l);
    // 3: layout
    const product = await fb.getvar('product');
    const bootSize = await fb.partitionSize('boot');
    const superSize = await fb.partitionSize('super');
    if (product !== 'sunxi' || bootSize !== BOOT_SIZE || superSize !== SUPER_SIZE) {
      throw new Error(`this does not look like an Aqara S1 Plus (product ${product}, boot ${bootSize}, super ${superSize}). Nothing was changed.`);
    }
    this.log('partition layout OK (S1 Plus)');

    // 4: back up (boot read twice and compared)
    const sizes = new Map<string, number>();
    for (const part of BACKUP_PARTS) {
      sizes.set(part, await fb.partitionSize(part));
      this.ui.partition(part, 'pend', { size: sizes.get(part) });
    }
    const total = [...sizes.values()].reduce((a, b) => a + b, 0) + BOOT_SIZE;
    let doneBytes = 0;
    const backupProgress = (sub: string, n: number) => {
      const x = (doneBytes + n) / total;
      this.ui.progress({ overall: 0.05 + 0.5 * x, phase: 'backup', phasePct: x, title: `Backing up your panel`, sub });
    };
    const files: BackupFile[] = [];
    for (const [i, part] of BACKUP_PARTS.entries()) {
      const size = sizes.get(part)!;
      const label = `Saving ${part} · ${i + 1} of ${BACKUP_PARTS.length}`;
      this.ui.partition(part, 'cur', { size, pct: 0 });
      const data = await fb.readPartition(part, (n, t) => { backupProgress(label, n); this.ui.partition(part, 'cur', { size, pct: n / t }); });
      doneBytes += size;
      const f = await backupFile(part, data);
      files.push(f);
      this.ui.partition(part, 'done', { size, sha256: short(f.sha256) });
      this.log(`${part} saved, ${fmtSize(size)}`);
    }
    const boot = files[0]!;
    const again = await fb.readPartition('boot', (n) => backupProgress('Reading boot a second time to be sure', n));
    doneBytes += BOOT_SIZE;
    if ((await backupFile('boot', again)).sha256 !== boot.sha256) throw new Error('two reads of boot differ: USB is unreliable here. Nothing was changed; try another cable or port.');
    this.log('boot read twice, hashes match');
    const bootWasPatched = await isMagiskPatched(boot.data);
    const meta: BackupMeta = {
      id: backupId(), created: new Date().toISOString(), bootWasPatched,
      files: files.map((f) => ({ part: f.part, size: f.data.length, sha256: f.sha256 })),
    };
    await this.d.backups.save(meta, files);
    this.log(`backup ${meta.id} stored in this browser`);
    if (bootWasPatched) {
      const orig = await this.d.backups.original();
      this.log(orig
        ? `NOTE: boot was already patched; your original software is backup ${orig.id}`
        : 'NOTE: boot was already patched, so this backup is not the original software. Keep the one from your first install.');
    }

    // 5: patch
    const ph = (phase: Phase, overall: number, phasePct: number, title: string, sub: string) =>
      this.ui.progress({ overall, phase, phasePct, title, sub });
    ph('patch', 0.56, 0, 'Preparing the new software', 'Adding root, the radio helper and this browser\'s key');
    const branding = this.d.branding ?? true;
    const a = (n: AssetName) => this.d.asset(n);
    const [magiskApk, radioRc, radioSh, otbrAgent, otCtl, firstbootRc, firstbootSh, netwatchRc, netwatchSh] =
      await Promise.all([a('magisk'), a('radio-rc'), a('radio-sh'), a('otbr-agent'), a('ot-ctl'), a('firstboot-rc'), a('firstboot-sh'), a('netwatch-rc'), a('netwatch-sh')]);
    const overlay = s1Overlay({
      radioRc, radioSh, otbrAgent, otCtl, firstbootRc, firstbootSh, netwatchRc, netwatchSh,
      adbKeys: new TextEncoder().encode(`${this.d.key.publicKey}\n`),
      branding: branding ? { rc: await a('branding-rc'), bootAnimation: await a('bootanimation') } : undefined,
    });
    const patched = await patchBoot({ boot: boot.data, magiskApk, overlay });
    patched.log.forEach((l) => this.log(l));
    const patchedSha = (await backupFile('boot', patched.image)).sha256;
    this.log(`patched boot ${short(patchedSha)} (from ${short(boot.sha256)})`);
    ph('patch', 0.6, 1, 'Preparing the new software', 'Done');

    // 6: vbmeta flags 3 (verification off), only if needed
    const vbmeta = files.find((f) => f.part === 'vbmeta')!.data;
    const flags = vbmetaFlags(vbmeta);
    this.log(`vbmeta flags: ${flags}`);
    const steps: { part: string; data: Uint8Array; what: string }[] = [];
    if (flags !== 3) steps.push({ part: 'vbmeta', data: withVbmetaFlags(vbmeta, 3), what: 'Turning off verified boot' });
    // 7: splash
    if (branding) {
      const bl = files.find((f) => f.part === 'bootloader')!.data;
      const branded = replaceBootlogo(bl, await a('bootlogo'));
      if (branded.some((x, i) => x !== bl[i])) steps.push({ part: 'bootloader', data: branded, what: 'Writing the boot logo' });
      else this.log('splash already in place: unchanged');
    }
    // 8: boot last
    steps.push({ part: 'boot', data: patched.image, what: 'Writing the new software' });

    const flashTotal = steps.reduce((n, s) => n + s.data.length, 0);
    let flashed = 0;
    for (const s of steps) {
      await fb.flashVerified(s.part, s.data, (phase, d, t) => {
        const x = (flashed + d / 2 + (phase === 'verify' ? t / 2 : 0)) / flashTotal;
        ph(phase === 'write' ? 'flash' : 'verify', 0.6 + 0.37 * x, phase === 'write' ? d / t : d / t,
          s.what, phase === 'write' ? `Writing ${s.part}` : `Reading ${s.part} back to check it`);
      });
      flashed += s.data.length;
      this.log(`${s.part} flashed and verified`);
    }
    ph('verify', 0.98, 1, 'Restarting the panel', 'First start takes a minute or two');
    await fb.reboot();
    this.log('rebooting into Android');
    return { meta, files, bootSha256: patchedSha };
  }

  /** Step 9: the patched Android authorizes this browser's key on first boot. */
  async connectAdb(timeoutMs = 6 * 60_000): Promise<PanelShell> {
    const until = Date.now() + timeoutMs;
    await this.sleep(15_000);
    // Allowed before (a re-run, or the same browser): no click needed.
    let p: PanelShell | undefined;
    for (const askAt = Date.now() + 60_000; !p && Date.now() < askAt; ) {
      p = await this.d.devices.knownAdb(this.d.key).catch(() => undefined);
      if (!p) await this.sleep(2000);
    }
    p ??= await this.ui.ask('adb', 'Allow this page to set up the panel (pick the panel in the list)',
      () => this.d.devices.requestAdb(this.d.key));
    this.log('adb connected; waiting for Android to finish starting');
    while (Date.now() < until) {
      if ((await p.run('getprop sys.boot_completed')).out.trim() === '1') return p;
      await this.sleep(2000);
    }
    throw new Error('the panel did not finish starting up');
  }

  async setUpApp(panel: PanelShell, opts: Omit<ProvisionOptions, 'kioskApk' | 'keyboardApk'> & { keyboard?: boolean }) {
    const kioskApk = await this.d.asset('kiosk-apk');
    const keyboardApk = opts.keyboard !== false && (await this.d.hasAsset('keyboard-apk')) ? await this.d.asset('keyboard-apk') : undefined;
    return provision(panel, { ...opts, kioskApk, keyboardApk }, (step, detail) => this.log(step || `  ${detail}`));
  }
}

/** fel-install.sh --undo: boot, vbmeta and splash back from a backup, each verified. */
export async function restoreFromBackup(fb: Fastboot, files: Map<string, Uint8Array>, log: (s: string) => void,
  onProgress?: (part: string, phase: 'write' | 'verify', done: number, total: number) => void): Promise<void> {
  const boot = files.get('boot');
  if (!boot || !files.get('vbmeta')) throw new Error('this backup has no boot or vbmeta');
  if (await isMagiskPatched(boot)) throw new Error('this backup\'s boot is already patched (a re-run backup): use the one from your first install');
  for (const part of RESTORE_PARTS) {
    const data = files.get(part);
    if (!data) continue;
    await fb.flashVerified(part, data, (phase, d, t) => onProgress?.(part, phase, d, t));
    log(`${part} restored and verified`);
  }
  await fb.reboot();
}
