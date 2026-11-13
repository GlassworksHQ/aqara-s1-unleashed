// The simulated panel behind ?demo=run drives the real InstallFlow and Session to the end, so every
// screen can be seen without hardware.
import { isMagiskPatched } from '@glassworks/bootpatch';
import { describe, expect, it } from 'vitest';
import { demoBootImage, demoSession } from '../src/wizard/demo';

const until = async (f: () => boolean, ms = 30_000) => {
  const end = Date.now() + ms;
  while (!f() && Date.now() < end) await new Promise((r) => setTimeout(r, 20));
  expect(f()).toBe(true);
};

describe('the simulated panel', () => {
  it('has a boot image the engine reads as not yet patched', async () => {
    await expect(isMagiskPatched(demoBootImage())).resolves.toBe(false);
  });

  it('goes from Connect to Done through every step', async () => {
    const s = demoSession('run');
    const st = () => s.store.state;
    void s.connectFel();
    await until(() => st().ask?.kind === 'fastboot');
    await s.asks.click();
    await until(() => st().ask?.kind === 'adb');
    expect(st().log.some((l) => l.endsWith('boot flashed and verified'))).toBe(true);
    expect(st().wrote).toBe(true);
    await s.asks.click();
    await until(() => st().setup.phase === 'settings');
    expect(st().setup).toMatchObject({ update: false });
    await s.route('resume'); // Back, or the rail's App, before the app went in: still a first install
    expect(st().setup).toMatchObject({ phase: 'settings', update: false });
    await s.installApp({ clock24: true, keyboard: false });
    await until(() => st().setup.phase === 'wifi' && !!(st().setup as { on?: unknown }).on);
    expect(st().setup).toMatchObject({ phase: 'wifi', on: { ssid: 'Home Net', ip: '192.0.2.50' } }); // on Wi-Fi already: said first
    await s.scanWifi(); // Use another network
    await until(() => st().setup.phase === 'wifi' && !!(st().setup as { networks?: unknown[] }).networks);
    await s.joinWifi('Home Net', 'not-a-real-password');
    expect(st().setup).toMatchObject({ phase: 'wifi', joining: { step: 'ok' } });
    await s.homeAssistant();
    expect(st().setup.phase).toBe('ha');
    await s.saveHa('http://homeassistant.local:8123');
    expect(st().setup.phase).toBe('radio');
    await s.applyRadio('off');
    expect(st().screen).toBe('done');
    expect(st().done?.backup?.name).toMatch(/^s1-backup-\d{8}-\d{6}\.zip$/);
  }, 60_000);
});
