// tools/install.sh (and tools/debloat/debloat.sh) over adb from the browser. Same steps, same order,
// same "try" (never fails) vs "run" (must succeed) split. Wake-word models are not pushed from here:
// their license (CC BY-NC-SA) keeps them out of our hosting and GitHub sends no CORS headers, so the
// kiosk app downloads them on the panel itself.
import type { PanelShell } from '@glassworks/adb';
import {
  type SwitchOutcome, type SwitchPlan, dropAdmin, isKeyMismatch, panelWifi, planSwitch, resendWifi, switchSigningKey, wifiAtRisk,
} from './switch';

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

/** Each APK provision installs: S1 Kiosk, and the on-screen keyboard when the recipe has it. */
export type AppApk = 'kiosk' | 'keyboard';

/**
 * What the panel is doing while the app goes in, at the commands that start each part:
 * `apk` `copy`, an APK is pushed, then `install`, its `pm install` runs (for S1 Kiosk on Android 10 it
 * returns only after dex2oat has optimised it, about 22 s on an S1, docs/research/web-installer.md
 * §7.1, while Android stops and restarts the app twice); `home`, permissions, home app, DND and the
 * debloat; `prefs`, time zone, language and clock; `start`, the app starts with them (once more).
 */
export type AppPhase =
  | { step: 'apk'; apk: AppApk; stage: 'copy' | 'install' }
  | { step: 'home' }
  | { step: 'prefs' }
  | { step: 'start' };

export interface ProvisionOptions {
  kioskApk: Uint8Array;
  haUrl?: string;
  /** e.g. America/Los_Angeles; the UI offers the browser's own. */
  timezone?: string;
  /** e.g. en-US. Sent to the kiosk app, which applies it (needs CHANGE_CONFIGURATION, granted below). */
  locale?: string;
  /** 24-hour clock (true) or 12-hour (false); unchanged if undefined. */
  clock24?: boolean;

  keyboardApk?: Uint8Array;
  debloat?: boolean;   // default true
  dndOff?: boolean;    // default true

  /**
   * Called when Android refuses the APK because the installed S1 Kiosk has another signing key (a
   * debug build): resolve true to switch (switch.ts: uninstall + install, data kept with root, Wi-Fi
   * re-sent), false to stop with nothing changed. Without it, provision stops.
   */
  confirmSwitch?: (plan: SwitchPlan) => Promise<boolean>;

  /** Called as each AppPhase begins, in order. */
  onPhase?: (phase: AppPhase) => void;
}

/** What a signing-key switch did (provision's result, when there was one). */
export interface SwitchResult {
  dataKept: boolean;
  /** kept: never at risk; resent: sent to the new app; lost: the Wi-Fi step has to ask for it. */
  wifi: 'kept' | 'resent' | 'lost';
  ssid?: string;
  notes: string[];
}

/** What provision found and did: the panel's Wi-Fi address, if it has one, and any key switch. */
export interface Provisioned { ip?: string; switched?: SwitchResult }

/** Android won't update the installed S1 Kiosk with this APK (other signing key), and no switch was made. */
export class KeyMismatchError extends Error {
  constructor(readonly plan: SwitchPlan) {
    super(`S1 Kiosk on the panel is ${plan.debugBuild ? 'a development build, ' : ''}signed with a different key, so Android won't update it in place. Nothing was changed.`);
  }
}

export interface WifiNetwork { ssid: string; rssi: number; secure: boolean }

/**
 * Parses the "Latest scan results" table of `dumpsys wifi` (Android 10):
 *   BSSID  Frequency  RSSI  Age(sec)  SSID  Flags
 * The SSID may contain spaces: it is whatever lies between the age and the first `[` of the flags.
 * Returns one entry per SSID (strongest signal), strongest first; hidden networks are skipped.
 */
export function parseWifiScan(dump: string): WifiNetwork[] {
  const i = dump.indexOf('Latest scan results:');
  if (i < 0) return [];
  const best = new Map<string, WifiNetwork>();
  for (const line of dump.slice(i).split(/\r?\n/).slice(1)) {
    const m = /^\s*([0-9a-f]{2}(?::[0-9a-f]{2}){5})\s+\d+\s+(-?\d+)\s+[\d.>]+\s+(.*?)\s*(\[.*)?$/i.exec(line);
    if (!m) { if (best.size && line.trim() === '') break; continue; }
    const ssid = m[3]!.trim();
    if (!ssid) continue;
    const rssi = Number(m[2]);
    const secure = /WPA|WEP|SAE|EAP|PSK/.test(m[4] ?? '');
    const prev = best.get(ssid);
    if (!prev || rssi > prev.rssi) best.set(ssid, { ssid, rssi, secure });
  }
  return [...best.values()].sort((a, b) => b.rssi - a.rssi);
}

/** Turns Wi-Fi on and reads what the panel can see (best effort: empty if nothing yet). */
export async function scanWifi(p: PanelShell, sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms))): Promise<WifiNetwork[]> {
  await p.run('svc wifi enable');
  for (let i = 0; i < 6; i++) {
    const found = parseWifiScan((await p.run('dumpsys wifi')).out);
    if (found.length) return found;
    await sleep(2500);
  }
  return [];
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
  async install(name: string, apk: Uint8Array, grant: boolean, pushed?: () => void) {
    const out = await this.tryInstall(name, apk, grant, pushed);
    if (!/Success/.test(out)) throw new Error(`installing ${name} failed: ${out.trim()}`);
  }
  /** pm install -r; returns pm's output either way (the caller decides what a failure means). `pushed`: the APK is on the panel. */
  async tryInstall(name: string, apk: Uint8Array, grant: boolean, pushed?: () => void): Promise<string> {
    const tmp = `/data/local/tmp/${name}.apk`;
    await this.p.push(tmp, apk, 0o644);
    pushed?.();
    try {
      return (await this.p.run(`pm install -r ${grant ? '-g ' : ''}${tmp}`)).out;
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

/**
 * S1 Kiosk's version on the panel, from `dumpsys package` (read-only); undefined when it isn't
 * installed. `signal` cancels the read.
 */
export async function kioskVersion(p: PanelShell, signal?: AbortSignal): Promise<{ name?: string; code: number } | undefined> {
  const dump = (await p.run(`dumpsys package ${PKG}`, signal)).out;
  const code = /versionCode=(\d+)/.exec(dump)?.[1];
  const name = /versionName=(\S+)/.exec(dump)?.[1];
  return code ? { name: name === 'null' ? undefined : name, code: Number(code) } : undefined;
}

export async function deviceInfo(p: PanelShell): Promise<{ model: string; sdk: string }> {
  const model = (await p.run('getprop ro.product.model')).out.trim();
  const sdk = (await p.run('getprop ro.build.version.sdk')).out.trim();
  return { model, sdk };
}

export async function provision(p: PanelShell, opts: ProvisionOptions, onStep: StepFn = () => {},
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms))): Promise<Provisioned> {
  const r = new Runner(p, onStep);
  r.step('Device');
  const { model, sdk } = await deviceInfo(p);
  r.detail(`model=${model} sdk=${sdk}`);
  if (sdk !== '29') r.detail('WARNING: expected Android 10 (sdk 29); continuing');

  const phase = opts.onPhase ?? (() => {});
  r.step('Install kiosk app');
  phase({ step: 'apk', apk: 'kiosk', stage: 'copy' });
  let sw: SwitchOutcome | undefined;
  const pmOut = await r.tryInstall('s1kiosk', opts.kioskApk, true, () => phase({ step: 'apk', apk: 'kiosk', stage: 'install' }));
  if (!/Success/.test(pmOut)) {
    if (!isKeyMismatch(pmOut)) throw new Error(`installing s1kiosk failed: ${pmOut.trim()}`);
    const plan = await planSwitch(p);
    r.detail(`installed S1 Kiosk has another signing key${plan.debugBuild ? ' (development build)' : ''}; root: ${plan.root ? 'yes' : 'no'}; Wi-Fi saved by S1 Kiosk: ${plan.wifi.owner === 'kiosk' ? 'yes' : plan.wifi.owner === 'unknown' ? 'maybe' : 'no'}`);
    if (!opts.confirmSwitch || !(await opts.confirmSwitch(plan))) throw new KeyMismatchError(plan);
    r.step('Switch to this build (remove the old one, install this one)');
    sw = await switchSigningKey(p, opts.kioskApk, plan, (d) => r.detail(d), sleep);
    sw.notes.forEach((n) => r.detail(n));
  }

  phase({ step: 'home' });
  r.step('Permissions');
  await r.try(`pm grant ${PKG} android.permission.RECORD_AUDIO`);
  await r.try(`pm grant ${PKG} android.permission.CAMERA`);
  await r.try(`pm grant ${PKG} android.permission.WRITE_SECURE_SETTINGS`);
  await r.try(`appops set ${PKG} SYSTEM_ALERT_WINDOW allow`);
  await r.try(`pm grant ${PKG} android.permission.CHANGE_CONFIGURATION`);   // language from the installer
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
    phase({ step: 'apk', apk: 'keyboard', stage: 'copy' });
    await r.install('keyboard', opts.keyboardApk, false, () => phase({ step: 'apk', apk: 'keyboard', stage: 'install' }));
    const ime = (await r.try('ime list -a -s')).split(/\r?\n/).map((l) => l.trim()).find((l) => l && !l.includes('baidu'));
    if (ime) {
      await r.run(`ime enable ${q(ime)}`);
      await r.run(`ime set ${q(ime)}`);
      await r.try(`pm disable-user --user 0 ${BAIDU_IME}`);
    }
  }

  phase({ step: 'prefs' });
  if (opts.timezone) {
    if (!/^[A-Za-z0-9_+\-/]+$/.test(opts.timezone)) throw new Error(`bad time zone ${opts.timezone}`);
    r.step(`Time zone ${opts.timezone}`);
    await r.run('settings put global auto_time_zone 0');
    await r.run(`service call alarm 3 s16 ${q(opts.timezone)}`);
    // Kept by the root helper (v4): Aqara's SystemManager resets the zone at every boot (docs/install.md).
    await r.run(`setprop persist.s1kiosk.timezone ${q(opts.timezone)}`);
  }
  if (opts.locale && !/^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$/.test(opts.locale)) throw new Error(`bad locale ${opts.locale}`);
  if (opts.locale) {
    r.step(`Language ${opts.locale}`);
    await r.run(`settings put system system_locales ${q(opts.locale)}`); // read at boot; the app applies it now
  }
  if (opts.clock24 !== undefined) {
    r.step(`${opts.clock24 ? '24' : '12'}-hour clock`);
    await r.run(`settings put system time_12_24 ${opts.clock24 ? 24 : 12}`);
  }

  phase({ step: 'start' });
  r.step('Start the kiosk');
  const extras = [opts.haUrl ? `--es url ${q(opts.haUrl)}` : '', opts.locale ? `--es set_locale ${q(opts.locale)}` : ''].filter(Boolean);
  await r.run(`am start -n ${ACT}${extras.length ? ` ${extras.join(' ')}` : ''}`);

  let switched: SwitchResult | undefined;
  if (sw) {
    let wifi: SwitchResult['wifi'] = sw.wifi === 'resend' ? 'lost' : sw.wifi;
    if (sw.secret) {
      r.step(`Wi-Fi ${sw.secret.ssid} to the new app`);
      if (await resendWifi(p, sw.secret, (d) => r.detail(d), sleep)) wifi = 'resent';
    }
    switched = { dataKept: sw.dataKept, wifi, ssid: sw.ssid, notes: sw.notes };
  }

  // A factory-fresh panel has no Wi-Fi yet (no wlan0): not an error.
  const m = /inet (\d+\.\d+\.\d+\.\d+)/.exec(await r.try('ip -4 -o addr show wlan0 2>/dev/null'));
  return { ...(m?.[1] ? { ip: m[1] } : {}), ...(switched ? { switched } : {}) };
}

const KEYBOARD_PKG = 'rkr.simplekeyboard.inputmethod';
const HOME_QUERY = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME';

/**
 * install.sh --undo: Aqara's apps, launcher, DND, keyboard and radio/cloud services back, and S1 Kiosk
 * removed. The kiosk is a device admin, and Android refuses to disable or uninstall an active admin,
 * so it first asks the app to drop that and waits until it has. Ends by checking that HOME really is
 * Aqara's launcher again, and throws if not (rather than claiming success with the kiosk on screen).
 */
export async function unprovision(p: PanelShell, onStep: StepFn = () => {},
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms))): Promise<void> {
  const r = new Runner(p, onStep);
  r.step('Removing S1 Kiosk\'s device admin');
  const adminGone = await dropAdmin(p, sleep);
  r.detail(adminGone ? 'device admin removed' : 'device admin still active: removing the app may fail');

  r.step('Bringing back Aqara\'s apps');
  for (const pkg of DEBLOAT) await r.enable(pkg);
  await r.enable(LAUNCHER);
  // after the launcher is enabled again: set-home-activity fails on a disabled package
  await r.try(`cmd package set-home-activity ${AQARA_HOME}`);
  await r.try('service call A_device_control 7 i32 1 i32 0');
  await r.try(`pm enable ${BAIDU_IME}`);
  await r.try(`ime set ${BAIDU_IME}/.ImeService`);

  r.step('Removing S1 Kiosk');
  await r.try(`am force-stop ${PKG}`);
  // Android 10 forgets every Wi-Fi network an app saved when the app is uninstalled, and S1 Kiosk
  // saves the panel's Wi-Fi itself. Then it is disabled instead, which keeps the network.
  const wifi = await panelWifi(p);
  if (wifiAtRisk(wifi)) {
    await r.try(`pm disable-user --user 0 ${PKG}`);
    r.detail(`kept S1 Kiosk installed but disabled: it saved the panel's Wi-Fi${wifi.ssid ? ` (${wifi.ssid})` : ''}, and Android forgets that network when S1 Kiosk is uninstalled`);
  } else if (!/Success/.test(await r.try(`pm uninstall ${PKG}`))) {
    // the app restarts itself (foreground service): uninstall, or at least disable it
    await r.try(`pm disable-user --user 0 ${PKG}`);
    r.detail('could not uninstall S1 Kiosk; disabled it instead');
  }
  if ((await r.try(`pm list packages ${KEYBOARD_PKG}`)).includes(`package:${KEYBOARD_PKG}`)) {
    await r.try(`pm uninstall ${KEYBOARD_PKG}`);
  }
  // ha_master is Aqara's cloud link, which the kiosk blocked; the radio services it kept stopped
  for (const svc of AQARA_SERVICES) await r.try(`setprop ctl.start ${svc}`);

  r.step('Checking the panel opens Aqara\'s home screen');
  const home = (await r.try(HOME_QUERY)).trim().split(/\r?\n/).pop() ?? '';
  if (!home.startsWith(`${LAUNCHER}/`)) {
    throw new Error(`the panel's home screen is still ${home || 'unknown'}, not Aqara's launcher`);
  }
  await r.try('am start -a android.intent.action.MAIN -c android.intent.category.HOME');
}
