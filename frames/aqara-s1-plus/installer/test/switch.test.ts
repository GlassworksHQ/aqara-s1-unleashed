// The signing-key switch (debug build -> release) and the Wi-Fi-keeping undo, on a simulated panel.
// The panel-side script's Wi-Fi commands run for real (tools/install/s1kiosk-switch.sh against the
// fixture WifiConfigStore.xml); its data copy/restore is simulated here and tested in
// tools/install/test/install-tests.sh.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { PanelShell } from '@glassworks/adb';
import { IMPROV_SOCKET, PacketReader, PacketType, Rpc, frame, parseResult, strings, type ByteStream } from '../src/improv';
import { KeyMismatchError, provision, unprovision } from '../src/provision';
import {
  SWITCH_SCRIPT, describeSwitch, isKeyMismatch, parseWifiList, parseWifiOwner, xmlUnescape, type SwitchPlan,
} from '../src/switch';

const REPO = join(__dirname, '../../../..');
const FIX = join(REPO, 'tools/install/test/fixtures');
const fixture = (f: string) => readFileSync(join(FIX, f), 'utf8');
const SCRIPT = join(REPO, 'tools/install/s1kiosk-switch.sh');
/** The fixture's (fake) password for "Home & Garden", as the app must receive it. */
const PSK = 'fixture <not> "real" & fake';
const MISMATCH = 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package sh.edm.s1kiosk signatures do not match previously installed version; ignoring!]\n';

/** S1 Kiosk's Improv server over adb, as far as the installer uses it. */
function improvApp(onWifi: (ssid: string, psk: string) => void): ByteStream {
  const rd = new PacketReader();
  let cb: (b: Uint8Array) => void = () => {};
  let state = 0x02;
  return {
    async write(d) {
      for (const p of rd.push(d)) {
        if (p.type !== PacketType.Rpc) continue;
        const r = parseResult(p.data); // same layout as an RPC command: command, length, strings
        if (p.data[0] === Rpc.RequestState) cb(frame(PacketType.State, Uint8Array.of(state)));
        if (p.data[0] === Rpc.WifiSettings) {
          onWifi(r.strings[0]!, r.strings[1]!);
          state = 0x04;
          cb(frame(PacketType.State, Uint8Array.of(state)));
          const s = strings('');
          cb(frame(PacketType.RpcResult, Uint8Array.of(Rpc.WifiSettings, s.length, ...s)));
        }
      }
    },
    onData(f) { cb = f; },
    async close() {},
  };
}

/** A panel with a debug build of S1 Kiosk as HOME and device admin, on a Wi-Fi network it saved. */
class SwitchPanel implements PanelShell {
  cmds: string[] = [];
  pushed = new Map<string, Uint8Array>();
  installed: 'debug' | 'release' | 'none' = 'debug';
  root = true;
  newInstallWorks = true;
  wifiDump = fixture('dumpsys-wifi-kiosk.txt');
  adminAsked = false;
  disabled = false;
  backup = false;
  restoredInto?: 'debug' | 'release';
  wifiSent: { ssid: string; psk: string }[] = [];
  streams = 0;

  async run(cmd: string) {
    this.cmds.push(cmd);
    const ok = (out = '') => ({ out, code: 0 });
    if (cmd === 'getprop ro.product.model') return ok('Aqara S1 Plus\n');
    if (cmd === 'getprop ro.build.version.sdk') return ok('29\n');
    if (cmd === 'pm install -r -g /data/local/tmp/s1kiosk.apk') {
      if (this.installed === 'debug') return { out: MISMATCH, code: 1 };
      if (!this.newInstallWorks) return { out: 'Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]\n', code: 1 };
      this.installed = 'release';
      this.disabled = false;
      return ok('Success\n');
    }
    if (cmd === 'pm install -r -g /data/local/tmp/s1kiosk-old.apk') { this.installed = 'debug'; this.disabled = false; return ok('Success\n'); }
    if (cmd === 'pm disable-user --user 0 sh.edm.s1kiosk') { this.disabled = true; return ok(); }
    if (cmd === 'pm enable sh.edm.s1kiosk') { this.disabled = false; return ok(); }
    if (cmd === 'dumpsys package sh.edm.s1kiosk') return ok('Packages:\n  Package [sh.edm.s1kiosk] (1a2b):\n    versionName=0.1.0\n    pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]\n');
    if (cmd === `timeout 15 su -c 'id -u' 2>/dev/null`) return this.root ? ok('0\n') : { out: '', code: 1 };
    if (cmd === 'dumpsys wifi') return ok(this.wifiDump);
    if (cmd === 'pm path sh.edm.s1kiosk') return ok('package:/data/app/sh.edm.s1kiosk-Abc==/base.apk\n');
    if (cmd.includes('--ez remove_admin true')) { this.adminAsked = true; return ok(); }
    if (cmd === 'dumpsys device_policy') return ok(this.adminAsked || this.installed === 'none' ? 'no admins\n' : 'Admin sh.edm.s1kiosk/.AdminReceiver\n');
    if (cmd === 'pm uninstall sh.edm.s1kiosk') {
      if (this.installed === 'none' || !this.adminAsked) return { out: 'Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n', code: 1 };
      this.installed = 'none';
      return ok('Success\n');
    }
    if (cmd === `grep -c ' @s1kiosk_improv$' /proc/net/unix`) return ok(this.installed === 'none' ? '0\n' : '1\n');
    if (cmd.startsWith('ip -4')) return ok('3: wlan0    inet 192.0.2.50/24 brd 192.0.2.255 scope global wlan0\n');
    const su = /^su -c 'sh \/data\/local\/tmp\/s1kiosk-switch\.sh (.*)'$/.exec(cmd);
    if (su) {
      expect(this.root).toBe(true);
      expect(new TextDecoder().decode(this.pushed.get('/data/local/tmp/s1kiosk-switch.sh'))).toBe(SWITCH_SCRIPT);
      const args = su[1]!.split(' ');
      if (args[0] === 'wifi-list' || args[0] === 'wifi-psk') {
        try {
          const out = execFileSync('sh', [SCRIPT, ...args], { env: { ...process.env, S1_WIFI_STORES: join(FIX, 'WifiConfigStore.xml') }, encoding: 'utf8' });
          return ok(out.replace(/\n/g, '\r\n')); // adb's shell: a PTY
        } catch { return { out: 's1kiosk-switch: no network\n', code: 1 }; }
      }
      if (args[0] === 'backup') { expect(this.installed).toBe('debug'); expect(this.disabled).toBe(true); this.backup = true; return ok('kept 6 item(s), 18342 KiB\n'); }
      if (args[0] === 'restore') {
        expect(this.backup).toBe(true);
        if (this.installed === 'none') return { out: 's1kiosk-switch: not installed\n', code: 1 };
        expect(this.disabled).toBe(true); // or it would start as HOME while its data is replaced
        this.restoredInto = this.installed;
        return ok('restored for uid 10072\n');
      }
      if (args[0] === 'discard') { this.backup = false; return ok(); }
    }
    return ok();
  }
  async push(path: string, data: Uint8Array) { this.pushed.set(path, data); }
  async openStream(service: string) {
    expect(service).toBe(IMPROV_SOCKET);
    this.streams++;
    return improvApp((ssid, psk) => this.wifiSent.push({ ssid, psk }));
  }
}

const noSleep = async () => {};

describe('Wi-Fi owner and store parsing', () => {
  it('reads who saved the current network from dumpsys wifi', () => {
    expect(parseWifiOwner(fixture('dumpsys-wifi-kiosk.txt'))).toEqual({ owner: 'kiosk', ssid: 'Home & Garden' });
    expect(parseWifiOwner(fixture('dumpsys-wifi-other.txt'))).toEqual({ owner: 'other', ssid: 'Neighbours' });
    expect(parseWifiOwner(fixture('dumpsys-wifi-none.txt'))).toEqual({ owner: 'none' });
    // an older, disconnected mWifiInfo printed first names a saved network that isn't the connected one
    const stale = 'mWifiInfo SSID: Neighbours, BSSID: aa:bb:cc:dd:ee:09, Supplicant state: DISCONNECTED, RSSI: -80, Net ID: 0, score: 0\n';
    expect(parseWifiOwner(stale + fixture('dumpsys-wifi-kiosk.txt'))).toEqual({ owner: 'kiosk', ssid: 'Home & Garden' });
    expect(parseWifiOwner('nothing useful')).toEqual({ owner: 'unknown' });
  });

  it('decodes the stored XML values', () => {
    expect(xmlUnescape('&quot;a &amp; b&quot; &lt;&gt;&apos; &#233;&#x41;')).toBe(`"a & b" <>' éA`);
    expect(xmlUnescape('broken &amp')).toBeUndefined();
    expect(xmlUnescape('&nope;')).toBeUndefined();
    expect(parseWifiList('1 psk &quot;Home &amp; Garden&quot;\r\n2 open &quot;Cafe&quot;\r\n')).toEqual([
      { n: 1, kind: 'psk', ssid: 'Home & Garden' }, { n: 2, kind: 'open', ssid: 'Cafe' },
    ]);
  });

  it('knows Android\'s refusal of another signing key', () => {
    expect(isKeyMismatch(MISMATCH)).toBe(true);
    expect(isKeyMismatch('Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]')).toBe(false);
  });

  it('says plainly what is kept, with and without root', () => {
    const plan: SwitchPlan = { installedVersion: '0.1.0', debugBuild: true, root: true, wifi: { owner: 'kiosk', ssid: 'Home' } };
    expect(describeSwitch(plan)).toMatch(/development build \(0\.1\.0\)[\s\S]*settings, sign-in and keys are kept[\s\S]*"Home"[\s\S]*stays online/);
    const noRoot = describeSwitch({ ...plan, root: false });
    expect(noRoot).toMatch(/starts fresh/);
    expect(noRoot).toMatch(/forgets "Home"[\s\S]*asks for its password again/);
    expect(describeSwitch({ ...plan, wifi: { owner: 'other', ssid: 'Home' } })).toMatch(/wasn't saved by S1 Kiosk and stays/);
  });
});

describe('switching a development build to this one', () => {
  it('with root: keeps the data, re-sends the Wi-Fi to the new app, never shows the password', async () => {
    const p = new SwitchPanel();
    const text: string[] = [];
    let asked: SwitchPlan | undefined;
    const res = await provision(p, {
      kioskApk: new Uint8Array([1, 2, 3]), debloat: false,
      confirmSwitch: async (plan) => { asked = plan; return true; },
    }, (s, d) => text.push(s || d || ''), noSleep);

    expect(asked).toEqual({ installedVersion: '0.1.0', debugBuild: true, root: true, wifi: { owner: 'kiosk', ssid: 'Home & Garden' } });
    expect(res.switched).toEqual({ dataKept: true, wifi: 'resent', ssid: 'Home & Garden', notes: [] });
    expect(p.wifiSent).toEqual([{ ssid: 'Home & Garden', psk: PSK }]);
    expect(p.installed).toBe('release');
    expect(p.restoredInto).toBe('release');
    expect(p.disabled).toBe(false);
    expect(p.backup).toBe(false); // discarded after the restore

    // the password: never in a command, a step or a detail
    for (const s of [...p.cmds, ...text]) expect(s).not.toContain('fixture');
    expect(JSON.stringify(res)).not.toContain('fixture');

    const i = (c: string) => p.cmds.indexOf(c);
    const su = (c: string) => i(`su -c 'sh /data/local/tmp/s1kiosk-switch.sh ${c}'`);
    expect(su('wifi-psk 1')).toBeGreaterThan(su('wifi-list'));
    expect(su('backup')).toBeGreaterThan(su('wifi-psk 1'));
    expect(i('pm uninstall sh.edm.s1kiosk')).toBeGreaterThan(su('backup'));
    expect(i('pm uninstall sh.edm.s1kiosk')).toBeGreaterThan(p.cmds.indexOf('am start -n sh.edm.s1kiosk/.MainActivity --ez remove_admin true'));
    expect(p.cmds.lastIndexOf('pm install -r -g /data/local/tmp/s1kiosk.apk')).toBeGreaterThan(i('pm uninstall sh.edm.s1kiosk'));
    expect(su('restore')).toBeGreaterThan(p.cmds.lastIndexOf('pm install -r -g /data/local/tmp/s1kiosk.apk'));
    // then the usual install steps: permissions, admin, HOME, start, and only then Wi-Fi
    expect(i('dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver')).toBeGreaterThan(su('restore'));
    expect(i('cmd package set-home-activity sh.edm.s1kiosk/.MainActivity')).toBeGreaterThan(su('restore'));
    expect(p.cmds.indexOf(`grep -c ' @s1kiosk_improv$' /proc/net/unix`)).toBeGreaterThan(i('am start -n sh.edm.s1kiosk/.MainActivity'));
    expect(p.cmds).toContain('rm -f /data/local/tmp/s1kiosk-old.apk /data/local/tmp/s1kiosk.apk /data/local/tmp/s1kiosk-switch.sh');
    expect(text).toContain('Switch to this build (remove the old one, install this one)');
  });

  it('without root: says the app starts fresh, does no root step, leaves Wi-Fi to the Wi-Fi step', async () => {
    const p = new SwitchPanel();
    p.root = false;
    const res = await provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async (plan) => !plan.root }, () => {}, noSleep);
    expect(res.switched).toEqual({ dataKept: false, wifi: 'lost', ssid: 'Home & Garden', notes: [] });
    expect(p.cmds.filter((c) => c.startsWith('su '))).toEqual([]);
    expect(p.pushed.has('/data/local/tmp/s1kiosk-switch.sh')).toBe(false);
    expect(p.streams).toBe(0);
    expect(p.installed).toBe('release');
  });

  it('a network the app did not save is left alone', async () => {
    const p = new SwitchPanel();
    p.wifiDump = fixture('dumpsys-wifi-other.txt');
    const res = await provision(p, { kioskApk: new Uint8Array([1]), debloat: false, confirmSwitch: async () => true }, () => {}, noSleep);
    expect(res.switched).toMatchObject({ dataKept: true, wifi: 'kept', ssid: 'Neighbours' });
    expect(p.cmds.some((c) => c.includes('wifi-psk'))).toBe(false);
    expect(p.wifiSent).toEqual([]);
  });

  it('declined, or nobody to ask: stops with nothing changed', async () => {
    for (const confirmSwitch of [async () => false, undefined]) {
      const p = new SwitchPanel();
      await expect(provision(p, { kioskApk: new Uint8Array([1]), confirmSwitch }, () => {}, noSleep)).rejects.toThrow(KeyMismatchError);
      expect(p.cmds.some((c) => c.startsWith('pm uninstall') || c.startsWith('su -c \'sh'))).toBe(false);
      expect(p.installed).toBe('debug');
    }
  });

  it('if the new build will not install, the old one comes back with its data', async () => {
    const p = new SwitchPanel();
    p.newInstallWorks = false;
    await expect(provision(p, { kioskApk: new Uint8Array([1]), confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/previous build is back with its data/);
    expect(p.installed).toBe('debug');
    expect(p.restoredInto).toBe('debug');
    expect(p.disabled).toBe(false);
    expect(p.cmds).toContain('dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver');
    expect(p.backup).toBe(false);
    expect(p.wifiSent).toEqual([]); // the old app still has its network
  });

  it('an ordinary install failure is not mistaken for a key mismatch', async () => {
    const p = new SwitchPanel();
    p.installed = 'none';
    p.newInstallWorks = false;
    await expect(provision(p, { kioskApk: new Uint8Array([1]), confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/INSUFFICIENT_STORAGE/);
    expect(p.cmds.some((c) => c.startsWith('pm uninstall'))).toBe(false);
  });

  it('if Android won\'t remove the old build, it is enabled and admin again, nothing else changed', async () => {
    const p = new SwitchPanel();
    const run = p.run.bind(p);
    p.run = async (cmd) => (cmd === 'pm uninstall sh.edm.s1kiosk' ? (p.cmds.push(cmd), { out: 'Failure [DELETE_FAILED_INTERNAL_ERROR]\n', code: 1 }) : run(cmd));
    await expect(provision(p, { kioskApk: new Uint8Array([1]), confirmSwitch: async () => true }, () => {}, noSleep))
      .rejects.toThrow(/would not remove[\s\S]*unchanged/);
    expect(p.installed).toBe('debug');
    expect(p.disabled).toBe(false);
    expect(p.backup).toBe(false);
    expect(p.cmds.at(-1)).toBe('rm -f /data/local/tmp/s1kiosk-old.apk /data/local/tmp/s1kiosk.apk /data/local/tmp/s1kiosk-switch.sh');
    expect(p.cmds).toContain('dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver');
  });
});

describe('undo keeps the panel on its Wi-Fi', () => {
  class UndoWifiPanel extends SwitchPanel {
    home = 'sh.edm.s1kiosk/.MainActivity';
    override async run(cmd: string) {
      if (cmd.startsWith('cmd package set-home-activity com.aqara')) { this.cmds.push(cmd); this.home = 'com.aqara.inhome.launcher/.MainActivity'; return { out: '', code: 0 }; }
      if (cmd.startsWith('cmd package resolve-activity')) { this.cmds.push(cmd); return { out: `${this.home}\n`, code: 0 }; }
      return super.run(cmd);
    }
  }

  it('disables S1 Kiosk instead of uninstalling it when it saved the network', async () => {
    const p = new UndoWifiPanel();
    const text: string[] = [];
    await unprovision(p, (s, d) => text.push(s || d || ''), noSleep);
    expect(p.cmds).toContain('pm disable-user --user 0 sh.edm.s1kiosk');
    expect(p.cmds).not.toContain('pm uninstall sh.edm.s1kiosk');
    expect(text.some((t) => /disabled: it saved the panel's Wi-Fi \(Home & Garden\)/.test(t))).toBe(true);
  });

  it('uninstalls it when the network is someone else\'s', async () => {
    const p = new UndoWifiPanel();
    p.wifiDump = fixture('dumpsys-wifi-other.txt');
    await unprovision(p, () => {}, noSleep);
    expect(p.cmds).toContain('pm uninstall sh.edm.s1kiosk');
    expect(p.cmds).not.toContain('pm disable-user --user 0 sh.edm.s1kiosk');
  });

  it('can\'t tell whose network it is but the panel is online: keeps it (disables)', async () => {
    const p = new UndoWifiPanel();
    p.wifiDump = 'Wi-Fi is enabled\n';
    await unprovision(p, () => {}, noSleep);
    expect(p.cmds).toContain('pm disable-user --user 0 sh.edm.s1kiosk');
    expect(p.cmds).not.toContain('pm uninstall sh.edm.s1kiosk');
  });
});
