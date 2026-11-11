// Moving a panel from one signing key to another (in practice: a debug build to a release build)
// without it losing its Wi-Fi, and keeping S1 Kiosk's data where the installer has root. Same steps
// as tools/install.sh's switch; the panel side is tools/install/s1kiosk-switch.sh.
//
// Why this is needed: Android won't update an app across signing keys, so the only way is uninstall
// + install. Uninstalling deletes the app's data, and on Android 10 also every Wi-Fi network the app
// saved (WifiConfiguration.creatorUid). S1 Kiosk saves the panel's Wi-Fi itself (Improv), so a plain
// uninstall takes the panel off the network.
//
// Root: adb's shell has none on a normal install (the boot image's root helpers are init services
// that take no commands). Only a development boot image (patch-boot-magisk.sh DEV_SU_HOURS) or a
// hand-made Magisk policy gives it `su`. Then the app's data is carried across and the Wi-Fi password
// is read from Android's store into this page's memory and sent to the new app over Improv. Without
// root the app starts fresh and the Wi-Fi step asks for the password again.
//
// The Wi-Fi password never goes into a command line, a log line or an error message.
import type { PanelShell } from '../adb/panel';
import { IMPROV_SOCKET, ImprovSerialClient } from '../improv';
import switchScript from '../../../tools/install/s1kiosk-switch.sh?raw';

const PKG = 'sh.edm.s1kiosk';
const ACT = `${PKG}/.MainActivity`;
const ADMIN = `${PKG}/.AdminReceiver`;
export const SWITCH_SCRIPT: string = switchScript;
export const SWITCH_SH = '/data/local/tmp/s1kiosk-switch.sh';
export const OLD_APK = '/data/local/tmp/s1kiosk-old.apk';
const NEW_APK = '/data/local/tmp/s1kiosk.apk';

/** Who saved the network the panel is on: S1 Kiosk, someone else, can't tell, or no Wi-Fi. */
export type WifiOwner = 'kiosk' | 'other' | 'unknown' | 'none';
export interface PanelWifi { owner: WifiOwner; ssid?: string }

/** Android refused `pm install -r` because the APK's signing key differs from the installed app's. */
export function isKeyMismatch(pmOut: string): boolean {
  return /INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match/i.test(pmOut);
}

/**
 * Reads `dumpsys wifi` (Android 10): the current network id from `mWifiInfo … Net ID: n`, then that
 * network's entry in WifiConfigManager's list (`ID: n SSID: "x" …`, later ` cname=<package>`).
 * The dump never contains passwords (WifiConfiguration prints the PSK as `*`).
 */
export function parseWifiOwner(dump: string, pkg = PKG): PanelWifi {
  const info = /mWifiInfo[^\n]*/.exec(dump)?.[0];
  const id = info ? /Net ID: (-?\d+)/.exec(info)?.[1] : undefined;
  if (id === undefined) return { owner: 'unknown' };
  if (Number(id) < 0) return { owner: 'none' };
  const lines = dump.split(/\r?\n/);
  const head = /^\s*[*-]?\s*ID: (-?\d+) SSID: (?:"(.*)"|(\S+)) PROVIDER-NAME:/;
  for (let i = 0; i < lines.length; i++) {
    const m = head.exec(lines[i]!);
    if (!m || m[1] !== id) continue;
    const ssid = m[2] ?? m[3];
    for (let j = i + 1; j < lines.length && !head.test(lines[j]!); j++) {
      const c = /\bcname=(\S+)/.exec(lines[j]!);
      if (c) return { owner: c[1] === pkg ? 'kiosk' : 'other', ssid };
      if (/Configured networks End/.test(lines[j]!)) break;
    }
    return { owner: 'unknown', ssid };
  }
  return { owner: 'unknown' };
}

/** XML text as Android's XmlSerializer writes it, decoded; undefined for anything malformed. */
export function xmlUnescape(s: string): string | undefined {
  let bad = false;
  const out = s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);?/gi, (all, e: string) => {
    if (!all.endsWith(';')) { bad = true; return all; }
    const named: Record<string, string> = { quot: '"', amp: '&', lt: '<', gt: '>', apos: "'" };
    if (e[0] === '#') {
      const n = e[1] === 'x' || e[1] === 'X' ? Number.parseInt(e.slice(2), 16) : Number.parseInt(e.slice(1), 10);
      if (!Number.isFinite(n) || n > 0x10ffff) { bad = true; return all; }
      return String.fromCodePoint(n);
    }
    if (!(e in named)) { bad = true; return all; }
    return named[e]!;
  });
  return bad ? undefined : out;
}

/** A stored SSID/passphrase is `"text"`; anything else (hex SSID, raw 64-hex PSK) is returned as is. */
export function unquote(v: string): string {
  return v.length >= 2 && v.startsWith('"') && v.endsWith('"') ? v.slice(1, -1) : v;
}

export interface SavedNetwork { n: number; kind: 'psk' | 'open' | 'wep' | 'other'; ssid?: string }

/** `s1kiosk-switch.sh wifi-list` output. */
export function parseWifiList(out: string): SavedNetwork[] {
  const nets: SavedNetwork[] = [];
  for (const line of out.split(/\r?\n/)) {
    const m = /^(\d+) (psk|open|wep|other) (.*)$/.exec(line.trim());
    if (!m) continue;
    const raw = xmlUnescape(m[3]!);
    nets.push({ n: Number(m[1]), kind: m[2] as SavedNetwork['kind'], ssid: raw === undefined ? undefined : unquote(raw) });
  }
  return nets;
}

/** Wi-Fi details held in memory between reading them (root) and sending them to the new app. */
export class WifiSecret {
  #psk: string;
  constructor(readonly ssid: string, psk: string) { this.#psk = psk; }
  get psk(): string { return this.#psk; }
  forget() { this.#psk = ''; }
  toString() { return `WifiSecret(${this.ssid})`; }
  toJSON() { return { ssid: this.ssid }; }
}

export interface SwitchPlan {
  /** versionName of the installed app, if dumpsys shows it. */
  installedVersion?: string;
  /** The installed app is debuggable (a debug build). */
  debugBuild: boolean;
  /** adb's shell has root (`su`): the app's data is kept and the Wi-Fi password re-sent. */
  root: boolean;
  wifi: PanelWifi;
}

export interface SwitchOutcome {
  /** The app's settings, sign-in and keys came across. */
  dataKept: boolean;
  /**
   * kept: the network was not S1 Kiosk's, so Android keeps it. resend: read as root, to be sent to
   * the new app (provision does). lost: Android forgot it; the Wi-Fi step must ask for it.
   */
  wifi: 'kept' | 'resend' | 'lost';
  ssid?: string;
  /** Present only for wifi = resend. */
  secret?: WifiSecret;
  notes: string[];
}

/** Wi-Fi the panel is on, and who saved it. Falls back to wlan0's address when dumpsys is unreadable. */
export async function panelWifi(p: PanelShell): Promise<PanelWifi> {
  const w = parseWifiOwner((await p.run('dumpsys wifi')).out);
  if (w.owner !== 'unknown') return w;
  const ip = /inet \d+\.\d+\.\d+\.\d+/.test((await p.run('ip -4 -o addr show wlan0 2>/dev/null')).out);
  return ip ? w : { owner: 'none' };
}

/** adb's shell can become root (a development boot image or a Magisk policy for uid 2000). */
export async function hasRoot(p: PanelShell): Promise<boolean> {
  const r = await p.run(`timeout 15 su -c 'id -u' 2>/dev/null`);
  return r.code === 0 && r.out.trim().split(/\r?\n/).pop()?.trim() === '0';
}

/** Read-only look at the panel before asking the user. Changes nothing. */
export async function planSwitch(p: PanelShell): Promise<SwitchPlan> {
  const dump = (await p.run(`dumpsys package ${PKG}`)).out;
  const installedVersion = /versionName=(\S+)/.exec(dump)?.[1];
  const debugBuild = /pkgFlags=\[[^\]]*\bDEBUGGABLE\b/.test(dump);
  return { installedVersion, debugBuild, root: await hasRoot(p), wifi: await panelWifi(p) };
}

/** Wi-Fi is at risk when the network is S1 Kiosk's, or when we can't tell whose it is. */
export const wifiAtRisk = (w: PanelWifi) => w.owner === 'kiosk' || w.owner === 'unknown';

/** Plain-language summary for the confirmation (no secrets in it: only the network's name). */
export function describeSwitch(plan: SwitchPlan): string {
  const what = plan.debugBuild
    ? `S1 Kiosk on the panel is a development build${plan.installedVersion ? ` (${plan.installedVersion})` : ''}.`
    : 'S1 Kiosk on the panel is signed with a different key than this version.';
  const lines = [what, 'Android can\'t update it in place: it has to be removed and installed again.', ''];
  const net = plan.wifi.ssid ? `"${plan.wifi.ssid}"` : 'its Wi-Fi network';
  if (plan.root) {
    lines.push('This panel gives the installer root, so the app\'s settings, sign-in and keys are kept.');
    if (wifiAtRisk(plan.wifi)) lines.push(`Android would forget ${net} with the old app; the installer sends it to the new app, so the panel stays online.`);
  } else {
    lines.push('The installer has no root on this panel, so the app starts fresh: its settings, Home Assistant sign-in and pairing key are reset (Home Assistant may ask to set the panel up again).');
    if (wifiAtRisk(plan.wifi)) lines.push(`Android also forgets ${net}, which S1 Kiosk saved. The next step asks for its password again; the cable keeps the panel reachable meanwhile.`);
  }
  if (!wifiAtRisk(plan.wifi) && plan.wifi.owner === 'other') lines.push(`The panel's Wi-Fi ${net} wasn't saved by S1 Kiosk and stays.`);
  lines.push('', 'If installing the new version fails, the old one is put back.');
  return lines.join('\n');
}

export type SwitchLog = (detail: string) => void;

const lastLine = (s: string) => s.trim().split(/\r?\n/).pop() || 'no detail';

/** am start --ez remove_admin true, then waits until Android no longer lists the app as an admin. */
export async function dropAdmin(p: PanelShell, sleep: (ms: number) => Promise<void>): Promise<boolean> {
  await p.run(`am start -n ${ACT} --ez remove_admin true`);
  for (let i = 0; i < 30; i++) {
    if (!(await p.run('dumpsys device_policy')).out.includes(PKG)) return true;
    await sleep(500);
  }
  return false;
}

/**
 * The switch itself: uninstall the installed app, install `apk`, with the data carried over when
 * there is root. Throws (with the old app put back where possible) if the new one won't install.
 * Permissions, HOME etc. are not done here: provision() continues with them as on any install.
 */
export async function switchSigningKey(p: PanelShell, apk: Uint8Array, plan: SwitchPlan, log: SwitchLog,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms))): Promise<SwitchOutcome> {
  const out: SwitchOutcome = { dataKept: false, wifi: wifiAtRisk(plan.wifi) ? 'lost' : 'kept', ssid: plan.wifi.ssid, notes: [] };
  const su = (cmd: string) => p.run(`su -c 'sh ${SWITCH_SH} ${cmd}'`);
  const cleanup = async () => { await p.run(`rm -f ${OLD_APK} ${NEW_APK} ${SWITCH_SH}`); };

  // 1. The installed APK, for putting it back if the new one won't install.
  const path = /^package:(\S+)/m.exec((await p.run(`pm path ${PKG}`)).out)?.[1];
  if (!path || (await p.run(`cp ${path} ${OLD_APK}`)).code !== 0) {
    throw new Error('could not keep a copy of the installed S1 Kiosk to fall back on. Nothing was changed.');
  }
  let root = plan.root;
  if (root) {
    await p.push(SWITCH_SH, new TextEncoder().encode(SWITCH_SCRIPT), 0o755);
    // 2. Wi-Fi, from Android's store into memory. Only this network's password, nothing logged.
    if (wifiAtRisk(plan.wifi)) {
      const nets = parseWifiList((await su('wifi-list')).out);
      const net = nets.find((x) => plan.wifi.ssid !== undefined && x.ssid === plan.wifi.ssid) ?? (nets.length === 1 ? nets[0] : undefined);
      if (net && (net.kind === 'psk' || net.kind === 'open') && net.ssid !== undefined) {
        let psk: string | undefined = '';
        if (net.kind === 'psk') {
          const r = await su(`wifi-psk ${net.n}`);
          psk = r.code === 0 ? xmlUnescape(r.out.replace(/\r?\n$/, '').replace(/\r$/, '')) : undefined;
          if (psk !== undefined) psk = unquote(psk);
        }
        if (psk !== undefined) { out.secret = new WifiSecret(net.ssid, psk); out.ssid = net.ssid; out.wifi = 'resend'; }
      }
      log(out.wifi === 'resend' ? `Wi-Fi ${out.ssid}: read as root; sent to the new app after installing` : 'Wi-Fi: could not read it as root; the Wi-Fi step will ask for it');
    }
  }

  // 3. Device admin: Android won't uninstall an active admin. A device owner is released first.
  //    The app has to be running for these, so they come before it is stopped.
  const policy = (await p.run('dumpsys device_policy')).out;
  const owner = /Device Owner[\s\S]{0,200}?sh\.edm\.s1kiosk/.test(policy);
  if (owner) {
    await p.run(`am start -n ${PKG}/.improv.ImprovActivity --ez release_device_owner true`);
    await sleep(2000);
  }
  if (!(await dropAdmin(p, sleep))) log('device admin still active: removing the app may fail');
  const undo = async () => {
    await p.run(`pm enable ${PKG}`);
    await p.run(`dpm set-active-admin ${ADMIN}`);
    if (owner) await p.run(`dpm set-device-owner ${ADMIN}`);
  };

  // 4. Stop it for good: as the HOME app a force-stopped kiosk is started again at once, and its
  //    data must not change while it is copied. Disabling keeps its Wi-Fi networks (uninstalling
  //    is what removes them).
  await p.run(`pm disable-user --user 0 ${PKG}`);
  if (root) {
    const b = await su('backup');
    if (b.code !== 0) {
      await su('discard');
      await undo();
      await cleanup();
      throw new Error(`could not keep S1 Kiosk's data (${lastLine(b.out)}). Nothing was changed.`);
    }
    log(`app data kept: ${lastLine(b.out)}`);
  }

  // 5. Remove the old build.
  const un = (await p.run(`pm uninstall ${PKG}`)).out;
  if (!/Success/.test(un)) {
    await undo();
    if (root) await su('discard');
    await cleanup();
    throw new Error(`Android would not remove the installed S1 Kiosk (${un.trim()}). It is unchanged.`);
  }
  log('removed the old build');

  // 6. Install the new one; on failure put the old one back (with its data, given root).
  //    Its data goes in while it is disabled (it would start as HOME at once otherwise).
  const restoreInto = async (): Promise<{ out: string; code: number }> => {
    await p.run(`pm disable-user --user 0 ${PKG}`);
    try { return await su('restore'); } finally { await p.run(`pm enable ${PKG}`); }
  };
  await p.push(NEW_APK, apk, 0o644);
  const inst = (await p.run(`pm install -r -g ${NEW_APK}`)).out;
  if (!/Success/.test(inst)) {
    const back = /Success/.test((await p.run(`pm install -r -g ${OLD_APK}`)).out);
    if (back && root) await restoreInto();
    if (back) await undo();
    if (root) await su('discard');
    await cleanup();
    out.secret?.forget();
    throw new Error(back
      ? `the new S1 Kiosk would not install (${inst.trim()}). The previous build is back${root ? ' with its data' : ''}.`
      : `the new S1 Kiosk would not install (${inst.trim()}), and neither would the previous one. Run the installer again.`);
  }
  log('installed the new build');

  // 7. The data back in, owned by the new uid, labels restored.
  if (root) {
    const r = await restoreInto();
    if (r.code === 0) { out.dataKept = true; await su('discard'); log('app data restored'); }
    else {
      root = false;
      out.notes.push(`could not restore the app's data (${lastLine(r.out)}); it starts fresh. The copy stays on the panel in /data/s1kiosk/switch (root only).`);
    }
  }
  if (owner) {
    const d = await p.run(`dpm set-device-owner ${ADMIN}`);
    if (d.code !== 0) out.notes.push('S1 Kiosk was device owner and could not be made device owner again; it works without (see ImprovActivity).');
  }
  await cleanup();
  return out;
}

/** Sends the remembered Wi-Fi to the (new) app over Improv through adb, then forgets it. */
export async function resendWifi(p: PanelShell, secret: WifiSecret, log: SwitchLog,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)), waitMs = 90_000): Promise<boolean> {
  try {
    if (!p.openStream) return false;
    const until = Date.now() + waitMs;
    while (Number.parseInt((await p.run(`grep -c ' @s1kiosk_improv$' /proc/net/unix`)).out.trim(), 10) <= 0) {
      if (Date.now() > until) { log('S1 Kiosk did not start listening for Wi-Fi details'); return false; }
      await sleep(1500);
    }
    for (let i = 0; i < 3; i++) {
      let c: ImprovSerialClient | undefined;
      try {
        c = new ImprovSerialClient(await p.openStream(IMPROV_SOCKET));
        await c.requestState(5000);
        await c.provision(secret.ssid, secret.psk);
        log(`Wi-Fi ${secret.ssid}: sent to the new app, connected`);
        return true;
      } catch (e) {
        // The app can restart right after joining and cut the connection: ask again.
        try {
          const again = new ImprovSerialClient(await p.openStream(IMPROV_SOCKET));
          const s = await again.requestState(5000);
          await again.close();
          if (s === 0x04) { log(`Wi-Fi ${secret.ssid}: connected`); return true; }
        } catch { /* not up yet */ }
        log(`Wi-Fi ${secret.ssid}: try ${i + 1}: ${(e as Error).message}`);
        await sleep(3000);
      } finally {
        await c?.close().catch(() => {});
      }
    }
    return false;
  } finally {
    secret.forget();
  }
}
