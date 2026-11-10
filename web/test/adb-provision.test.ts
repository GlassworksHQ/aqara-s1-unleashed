import { describe, expect, it } from 'vitest';
import { MemoryKeyStorage, loadOrCreateAdbKey, type PanelShell } from '../src/adb';
import { DEBLOAT, provision, q, unprovision } from '../src/provision';

describe('adb key', () => {
  it('is created once, kept, and its public key is an adb_keys line for the same modulus', async () => {
    const storage = new MemoryKeyStorage();
    const a = await loadOrCreateAdbKey(storage);
    const b = await loadOrCreateAdbKey(storage);
    expect(b.publicKey).toBe(a.publicKey);
    expect(a.publicKey).toMatch(/^QAAAA[A-Za-z0-9+/=]+ s1-unleashed-web@browser$/);
    // Android RSAPublicKey: u32 len (64 words), u32 n0inv, n[256] LE, rr[256] LE, u32 e
    const raw = Uint8Array.from(atob(a.publicKey.split(' ')[0]!), (c) => c.charCodeAt(0));
    expect(raw.length).toBe(524);
    const v = new DataView(raw.buffer);
    expect(v.getUint32(0, true)).toBe(64);
    expect(v.getUint32(520, true)).toBe(65537);
    const pkcs8 = (await storage.load())!;
    const key = await crypto.subtle.importKey('pkcs8', pkcs8 as Uint8Array<ArrayBuffer>, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-1' }, true, ['sign']);
    const jwk = await crypto.subtle.exportKey('jwk', key);
    const n = Uint8Array.from(atob(jwk.n!.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
    expect(Array.from(raw.subarray(8, 8 + 256)).reverse()).toEqual(Array.from(n));
  });
});

class FakePanel implements PanelShell {
  cmds: string[] = [];
  pushed = new Map<string, Uint8Array>();
  disabled = new Set<string>(['com.aqara.inhome.vassistant']); // 4.5.80 ships it disabled (state 2)
  fail = new Set<string>();
  async run(cmd: string) {
    this.cmds.push(cmd);
    if ([...this.fail].some((f) => cmd.startsWith(f))) return { out: 'Error', code: 1 };
    if (cmd === 'getprop ro.product.model') return { out: 'Aqara S1 Plus\n', code: 0 };
    if (cmd === 'getprop ro.build.version.sdk') return { out: '29\n', code: 0 };
    if (cmd.startsWith('pm install')) return { out: 'Success\n', code: 0 };
    if (cmd.startsWith('pm list packages -d ')) {
      const p = cmd.slice(20);
      return { out: this.disabled.has(p) ? `package:${p}\n` : '', code: 0 };
    }
    if (cmd === 'ime list -a -s') return { out: 'com.baidu.input_auto/.ImeService\nrkr.simplekeyboard.inputmethod/.latin.LatinIME\n', code: 0 };
    if (cmd.startsWith('ip -4')) return { out: '', code: 1 }; // no wlan0 on a fresh panel
    return { out: '', code: 0 };
  }
  async push(path: string, data: Uint8Array) { this.pushed.set(path, data); }
}

describe('provision (install.sh port)', () => {
  it('runs install.sh steps in order and skips already-disabled packages', async () => {
    const p = new FakePanel();
    const steps: string[] = [];
    const res = await provision(p, {
      kioskApk: new Uint8Array([1, 2, 3]), haUrl: 'http://ha.local:8123/panel', timezone: 'America/Los_Angeles',
      keyboardApk: new Uint8Array([4]),
    }, (s) => { if (s) steps.push(s); });
    expect(res).toEqual({});
    expect(steps).toEqual([
      'Device', 'Install kiosk app', 'Permissions', 'Make the kiosk the HOME app',
      'Disable Aqara DND screen-off (the kiosk handles night mode)', 'De-bloat (reversible pm disable-user)',
      'Keyboard', 'Time zone America/Los_Angeles', 'Start the kiosk',
    ]);
    expect(p.cmds).toContain('pm install -r -g /data/local/tmp/s1kiosk.apk');
    expect(p.cmds).not.toContain('pm disable-user --user 0 com.aqara.inhome.vassistant');
    expect(p.cmds).toContain('pm disable-user --user 0 com.lumi.ota');
    // the launcher only after the kiosk is HOME
    expect(p.cmds.indexOf('pm disable-user --user 0 com.aqara.inhome.launcher'))
      .toBeGreaterThan(p.cmds.indexOf('cmd package set-home-activity sh.edm.s1kiosk/.MainActivity'));
    expect(p.cmds).toContain(`ime set 'rkr.simplekeyboard.inputmethod/.latin.LatinIME'`);
    expect(p.cmds).toContain(`service call alarm 3 s16 'America/Los_Angeles'`);
    expect(p.cmds.at(-2)).toBe(`am start -n sh.edm.s1kiosk/.MainActivity --es url 'http://ha.local:8123/panel'`);
    expect(p.cmds).toContain('rm -f /data/local/tmp/s1kiosk.apk');
  });

  it('a failed grant is fine (try), a failed HOME switch is not (run)', async () => {
    const p = new FakePanel();
    p.fail.add('pm grant');
    await provision(p, { kioskApk: new Uint8Array(1), debloat: false });
    const p2 = new FakePanel();
    p2.fail.add('cmd package set-home-activity');
    await expect(provision(p2, { kioskApk: new Uint8Array(1) })).rejects.toThrow(/set-home-activity/);
    expect(p2.cmds.some((c) => c.includes('disable-user'))).toBe(false); // never disables the launcher then
  });

  it('refuses shell metacharacters in time zone / locale; quotes the URL', async () => {
    await expect(provision(new FakePanel(), { kioskApk: new Uint8Array(1), timezone: 'x; reboot' })).rejects.toThrow(/time zone/);
    expect(q(`a'b`)).toBe(`'a'\\''b'`);
  });

  it('undo re-enables everything, then sets Aqara HOME, then disables the kiosk', async () => {
    const p = new FakePanel();
    await unprovision(p);
    for (const pkg of DEBLOAT) expect(p.cmds).toContain(`pm enable ${pkg}`);
    const i = (c: string) => p.cmds.indexOf(c);
    expect(i('cmd package set-home-activity com.aqara.inhome.launcher/.MainActivity')).toBeGreaterThan(i('pm enable com.aqara.inhome.launcher'));
    expect(i('pm disable-user --user 0 sh.edm.s1kiosk')).toBeGreaterThan(i('am force-stop sh.edm.s1kiosk'));
    expect(p.cmds.at(-1)).toBe('setprop ctl.start ha_master');
  });
});
