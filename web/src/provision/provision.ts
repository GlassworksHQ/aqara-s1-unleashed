// tools/install.sh (and tools/debloat/debloat.sh) over adb from the browser. Same steps, same order,
// same "try" (never fails) vs "run" (must succeed) split. Wake-word models are not pushed from here:
// their license (CC BY-NC-SA) keeps them out of our hosting and GitHub sends no CORS headers, so the
// kiosk app downloads them on the panel itself.
import type { PanelShell } from '../adb/panel';

export const PKG = 'sh.edm.s1kiosk';
const ACT = `${PKG}/.MainActivity`;
const LAUNCHER = 'com.aqara.inhome.launcher';
const AQARA_HOME = `${LAUNCHER}/.MainActivity`;
const BAIDU_IME = 'com.baidu.input_auto';

/** debloat.sh SAFE: cloud/telemetry, OTA, extra apps, factory/test, Aqara voice. */
export const DEBLOAT = [
  'com.aqara.inhome.manager',
  'com.lumi.ota',
  'com.aqara.inhome.vassistant',
  'com.aqara.inhome.embedded_chat',
  'com.aqara.music',
  'com.lumi.siri',
  'com.lumi.dream',
  'com.lumi.aging',
  'com.lumi.factory',
] as const;

/** Aqara's radio + cloud services, started again on undo (provider-first order, docs/radio.md). */
const AQARA_SERVICES = ['zigbee_cpcd', 'zigbee_socat', 'zigbee_zigbeed', 'zigbee_host', 'zigbee_agent', 'ha_matter', 'ha_master'];

export interface ProvisionOptions {
  kioskApk: Uint8Array;
  haUrl?: string;
  /** e.g. America/Los_Angeles; the UI offers the browser's own. */
  timezone?: string;
  /** e.g. en-US; applies after a reboot. */
  locale?: string;
  keyboardApk?: Uint8Array;
  debloat?: boolean;   // default true
  dndOff?: boolean;    // default true
}

export type StepFn = (step: string, detail?: string) => void;

/** Shell-quotes one argument (single quotes). */
export function q(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`;
}

class Runner {
  constructor(private readonly p: PanelShell, private readonly onStep: StepFn) {}
  step(s: string) { this.onStep(s); }
  detail(s: string) { this.onStep('', s); }
  /** Must succeed. */
  async run(cmd: string): Promise<string> {
    const r = await this.p.run(cmd);
    if (r.code !== 0) throw new Error(`panel command failed (${r.code}): ${cmd}\n${r.out.trim()}`);
    return r.out;
  }
  /** Never fails (idempotent steps such as re-granting). */
  async try(cmd: string): Promise<string> {
    const r = await this.p.run(cmd);
    return r.out;
  }
  async install(name: string, apk: Uint8Array, grant: boolean) {
    const tmp = `/data/local/tmp/${name}.apk`;
    await this.p.push(tmp, apk, 0o644);
    try {
      const out = await this.run(`pm install -r ${grant ? '-g ' : ''}${tmp}`);
      if (!/Success/.test(out)) throw new Error(`installing ${name} failed: ${out.trim()}`);
    } finally {
      await this.try(`rm -f ${tmp}`);
    }
  }
  /** debloat.sh op disable: skip packages the firmware already disabled itself (state 2). */
  async disable(pkg: string) {
    const listed = await this.try(`pm list packages -d ${pkg}`);
    if (listed.split(/\r?\n/).some((l) => l.trim() === `package:${pkg}`)) { this.detail(`already   ${pkg} (disabled)`); return; }
    const r = await this.p.run(`pm disable-user --user 0 ${pkg}`);
    this.detail(`${r.code === 0 ? 'disabled ' : 'skip/err '} ${pkg}`);
  }
  async enable(pkg: string) {
    const r = await this.p.run(`pm enable ${pkg}`);
    this.detail(`${r.code === 0 ? 'enabled  ' : 'skip/err '} ${pkg}`);
  }
}

export async function deviceInfo(p: PanelShell): Promise<{ model: string; sdk: string }> {
  const model = (await p.run('getprop ro.product.model')).out.trim();
  const sdk = (await p.run('getprop ro.build.version.sdk')).out.trim();
  return { model, sdk };
}

export async function provision(p: PanelShell, opts: ProvisionOptions, onStep: StepFn = () => {}): Promise<{ ip?: string }> {
  const r = new Runner(p, onStep);
  r.step('Device');
  const { model, sdk } = await deviceInfo(p);
  r.detail(`model=${model} sdk=${sdk}`);
  if (sdk !== '29') r.detail('WARNING: expected Android 10 (sdk 29); continuing');

  r.step('Install kiosk app');
  await r.install('s1kiosk', opts.kioskApk, true);

  r.step('Permissions');
  await r.try(`pm grant ${PKG} android.permission.RECORD_AUDIO`);
  await r.try(`pm grant ${PKG} android.permission.CAMERA`);
  await r.try(`pm grant ${PKG} android.permission.WRITE_SECURE_SETTINGS`);
  await r.try(`appops set ${PKG} SYSTEM_ALERT_WINDOW allow`);
  await r.try(`dpm set-active-admin ${PKG}/.AdminReceiver`);

  r.step('Make the kiosk the HOME app');
  await r.run(`cmd package set-home-activity ${ACT}`);

  if (opts.dndOff ?? true) {
    r.step('Disable Aqara DND screen-off (the kiosk handles night mode)');
    await r.try('service call A_device_control 7 i32 0 i32 0');
  }

  if (opts.debloat ?? true) {
    r.step('De-bloat (reversible pm disable-user)');
    for (const pkg of DEBLOAT) await r.disable(pkg);
    await r.disable(LAUNCHER); // after the kiosk became HOME
  }

  if (opts.keyboardApk) {
    r.step('Keyboard');
    await r.install('keyboard', opts.keyboardApk, false);
    const ime = (await r.try('ime list -a -s')).split(/\r?\n/).map((l) => l.trim()).find((l) => l && !l.includes('baidu'));
    if (ime) {
      await r.run(`ime enable ${q(ime)}`);
      await r.run(`ime set ${q(ime)}`);
      await r.try(`pm disable-user --user 0 ${BAIDU_IME}`);
    }
  }

  if (opts.timezone) {
    if (!/^[A-Za-z0-9_+\-/]+$/.test(opts.timezone)) throw new Error(`bad time zone ${opts.timezone}`);
    r.step(`Time zone ${opts.timezone}`);
    await r.run('settings put global auto_time_zone 0');
    await r.run(`service call alarm 3 s16 ${q(opts.timezone)}`);
  }
  if (opts.locale) {
    if (!/^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$/.test(opts.locale)) throw new Error(`bad locale ${opts.locale}`);
    r.step(`Locale ${opts.locale} (applies after reboot)`);
    await r.run(`settings put system system_locales ${q(opts.locale)}`);
  }

  r.step('Start the kiosk');
  await r.run(opts.haUrl ? `am start -n ${ACT} --es url ${q(opts.haUrl)}` : `am start -n ${ACT}`);

  // A factory-fresh panel has no Wi-Fi yet (no wlan0): not an error.
  const m = /inet (\d+\.\d+\.\d+\.\d+)/.exec(await r.try('ip -4 -o addr show wlan0 2>/dev/null'));
  return m?.[1] ? { ip: m[1] } : {};
}

/** install.sh --undo: Aqara apps, launcher, DND, IME and radio/cloud services back; kiosk disabled. */
export async function unprovision(p: PanelShell, onStep: StepFn = () => {}): Promise<void> {
  const r = new Runner(p, onStep);
  r.step('Undo');
  await r.try(`am start -n ${ACT} --ez remove_admin true`);
  for (const pkg of DEBLOAT) await r.enable(pkg);
  await r.enable(LAUNCHER);
  // after the launcher is enabled again: set-home-activity fails on a disabled package
  await r.try(`cmd package set-home-activity ${AQARA_HOME}`);
  await r.try('service call A_device_control 7 i32 1 i32 0');
  await r.try(`pm enable ${BAIDU_IME}`);
  await r.try(`ime set ${BAIDU_IME}/.ImeService`);
  await r.try(`am force-stop ${PKG}`);
  await r.try(`pm disable-user --user 0 ${PKG}`);
  for (const svc of AQARA_SERVICES) await r.try(`setprop ctl.start ${svc}`);
}
