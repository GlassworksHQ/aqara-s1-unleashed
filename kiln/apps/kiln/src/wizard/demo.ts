// Development and preview only (?demo=…): a simulated S1 Plus, so every screen can be seen and
// captured without a panel. `?demo=run` drives the whole flow through the real InstallFlow and Session
// with a fake device behind them; the other names jump straight to one state.
import type { PanelShell } from '@glassworks/adb';
import {
  PacketReader, PacketType, Rpc, State as ImprovState, frame, parseResult, s1Plus, strings,
  type AppPhase, type AssetName, type ByteStream, type Provisioned, type SetUpOptions,
} from '@glassworks/aqara-s1-plus-installer';
import type { Fastboot } from '@glassworks/fastboot';
import type { FelDevice } from '@glassworks/fel';
import { isMagiskPatched, loadCompressor } from '@glassworks/bootpatch';
import { Assets, checkRestorable, fmtSize, type BackupFile, type BackupMeta, type Recipe } from '@glassworks/kiln-core';
import { SETUP_SOCKET, type SetupState } from '@glassworks/setup-channel';
import { s1PlusUi } from '../frames/aqara-s1-plus';
import { explain } from './explain';
import { Session, type SessionDeps } from './session';

export const DEMOS = ['run', 'selftest', 'connect', 'ask-fastboot', 'install', 'ask-adb', 'app', 'app-update', 'app-installing', 'app-keyboard', 'wifi', 'wifi-new', 'wifi-fail', 'home-assistant', 'radio', 'radio-next', 'radio-thread', 'radio-thread-old',
  'done', 'undo', 'undo-refused', 'undo-refused-splash', 'undo-apps-back', 'undo-unreadable', 'undo-connect', 'restore', 'undo-done', 'error', 'error-written'] as const;
export type Demo = (typeof DEMOS)[number];

const wait = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));
const IP = '192.0.2.50';

/** Whether the simulated panel is on Wi-Fi: its app's setup state and Android's `dumpsys wifi` read it. */
interface Net { online: boolean }

/** `dumpsys wifi` on a panel on the made-up "Home Net", in Android 10's shape (switch.ts parseWifiOwner). */
const DUMPSYS_WIFI = [
  'mWifiInfo SSID: Home Net, BSSID: 02:00:00:00:00:01, Supplicant state: COMPLETED, RSSI: -48, Net ID: 0, score: 60',
  'Configured networks Begin ----',
  '* ID: 0 SSID: "Home Net" PROVIDER-NAME: null BSSID: null FQDN: null PRIO: 0 HIDDEN: false',
  ' cuid=10071 cname=sh.edm.s1kiosk luid=10071',
  'Configured networks End ----',
].join('\n');

/** The app's setup channel on a simulated panel. */
function setupStream(net: Net): ByteStream {
  let cb: (c: Uint8Array) => void = () => {};
  const state: SetupState = {
    ip: net.online ? IP : undefined,
    ha: { discovered: 'http://homeassistant.local:8123', url: '', signedIn: false, signinUrl: `http://${IP}:8099/signin` },
    radio: {
      mode: 'off', status: 'Off: radio idle', helper: true,
      options: [
        { id: 'off', label: 'Off', detail: 'The radio stays idle. Choose this if you don\'t use Zigbee or Thread here.' },
        { id: 'zigbee_tcp', label: 'Zigbee coordinator', detail: 'Home Assistant runs a Zigbee network (ZHA) on this panel\'s radio, over the network.' },
        { id: 'thread', label: 'Thread border router', detail: 'Runs a Thread network for Matter devices, through Home Assistant\'s OpenThread Border Router integration.' },
        { id: 'zigbee_router', label: 'Zigbee router (repeater)', detail: 'Joins the Zigbee network you already have.', experimental: true, repeater: true },
        { id: 'thread_router', label: 'Thread router (repeater)', detail: 'Joins the Thread network you already have.', experimental: true, repeater: true },
      ],
    },
  };
  const send = (o: object, ms = 0) => setTimeout(() => cb(new TextEncoder().encode(`${JSON.stringify(o)}\n`)), ms);
  return {
    onData: (f) => { cb = f; },
    close: async () => {},
    write: async (data) => {
      for (const line of new TextDecoder().decode(data).split('\n').filter(Boolean)) {
        const m = JSON.parse(line) as { id: number; op: string; url?: string; mode?: string };
        if (m.op === 'set_ha') { state.ha.url = m.url; setTimeout(() => { state.ha.signedIn = true; send({ event: 'state', state }); }, 6000); }
        if (m.op === 'set_radio') {
          state.radio.mode = m.mode!;
          state.radio.status = m.mode === 'off' ? 'Off: radio idle' : 'ok Zigbee coordinator (TCP): listening on :6638 (waiting for Home Assistant to connect over ESPHome)';
          state.radio.allowed = m.mode === 'off' ? undefined : '';
        }
        state.ip = net.online ? IP : undefined;
        send({ id: m.id, ok: true, state }, m.op === 'set_radio' ? 1500 : 200);
      }
    },
  };
}

/** A panel answering Improv Serial; `fail`: a wrong password. */
function improvStream(fail: boolean, net: Net): ByteStream {
  const reader = new PacketReader();
  let cb: (c: Uint8Array) => void = () => {};
  const send = (t: number, d: Uint8Array, ms = 0) => setTimeout(() => cb(frame(t, d)), ms);
  const result = (cmd: number, ss: string[], ms = 0) => {
    const b = strings(...ss);
    send(PacketType.RpcResult, Uint8Array.of(cmd, b.length, ...b), ms);
  };
  return {
    onData: (f) => { cb = f; },
    close: async () => {},
    write: async (data) => {
      for (const p of reader.push(data)) {
        const cmd = p.data[0];
        if (cmd === Rpc.RequestState) send(PacketType.State, Uint8Array.of(net.online ? ImprovState.Provisioned : ImprovState.Ready));
        if (cmd === Rpc.ScanWifi) {
          [['Home Net', '-48', 'YES'], ['Home Net 5G', '-62', 'YES'], ['Neighbours', '-74', 'YES'], ['Cafe Guest', '-83', 'NO']]
            .forEach((n, i) => result(Rpc.ScanWifi, n, 600 + i * 40));
          result(Rpc.ScanWifi, [], 800);
        }
        if (cmd === Rpc.WifiSettings) {
          void parseResult(p.data);
          send(PacketType.State, Uint8Array.of(ImprovState.Provisioning), 900);
          if (fail) send(PacketType.Error, Uint8Array.of(0x03), 4200);
          else { setTimeout(() => { net.online = true; }, 4200); send(PacketType.State, Uint8Array.of(ImprovState.Provisioned), 4200); result(Rpc.WifiSettings, [`http://${IP}:6053`], 4250); }
        }
      }
    },
  };
}

/** `online`: on Wi-Fi already, as a panel set up with Aqara's app is. */
/** `app`: S1 Kiosk's version on the panel (`dumpsys package`), or null when it isn't installed. */
export function simulatedPanel(wifiFails = false, online = true, app: { name: string; code: number } | null = { name: '0.5.0', code: 500 }): PanelShell {
  const net: Net = { online };
  const out = (cmd: string) => cmd === 'getprop sys.boot_completed' || cmd.startsWith('grep -c') ? '1\n'
    : cmd === 'pm path sh.edm.s1kiosk' && app ? 'package:/data/app/sh.edm.s1kiosk-1/base.apk\n'
    : cmd === 'dumpsys package sh.edm.s1kiosk' && app ? `    versionCode=${app.code} minSdk=26 targetSdk=34\n    versionName=${app.name}\n`
    : cmd === 'dumpsys wifi' && net.online ? DUMPSYS_WIFI
    : cmd.startsWith('ip -4') && net.online ? `3: wlan0    inet ${IP}/24 brd 192.0.2.255 scope global wlan0\n` : '';
  return {
    run: async (cmd) => ({ out: out(cmd), code: 0 }),
    push: async () => {},
    openStream: async (svc) => (svc === SETUP_SOCKET ? setupStream(net) : improvStream(wifiFails, net)),
  };
}

/**
 * The smallest boot image the engine accepts: an AOSP v2 header and a raw cpio holding only its
 * trailer. Enough for isMagiskPatched() to say "not patched".
 */
export function demoBootImage(size = 1 << 20): Uint8Array {
  const img = new Uint8Array(size);
  const dv = new DataView(img.buffer);
  img.set(new TextEncoder().encode('ANDROID!'), 0);
  const name = new TextEncoder().encode('TRAILER!!!\0');
  const hex = (n: number) => n.toString(16).padStart(8, '0');
  const head = `070701${[0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, name.length, 0].map(hex).join('')}`;
  const cpio = new Uint8Array(Math.ceil((110 + name.length) / 4) * 4);
  cpio.set(new TextEncoder().encode(head), 0);
  cpio.set(name, 110);
  dv.setUint32(16, cpio.length, true); // ramdisk size
  dv.setUint32(36, 4096, true); // page size
  dv.setUint32(40, 2, true); // header version
  dv.setUint32(1644, 1660, true); // header size
  img.set(cpio, 4096);
  return img;
}

/** U-Boot's fastboot on the simulated panel: the recipe's partitions, scaled down so the demo is quick. */
function fakeFastboot(): Fastboot {
  const sizes = new Map<string, number>(s1Plus.backup.parts.map((p) => [p, p === 'boot' ? 1 << 20 : 256 << 10]));
  const data = new Map<string, Uint8Array>([['boot', demoBootImage()]]);
  const chunked = async (n: number, on?: (d: number, t: number) => void) => {
    for (let i = 1; i <= 8; i++) { await wait(60); on?.((n * i) / 8, n); }
  };
  const fb = {
    log: (_: string) => {},
    getvar: async () => 'sunxi',
    partitionSize: async (p: string) => sizes.get(p) ?? 0,
    readPartition: async (p: string, on?: (d: number, t: number) => void) => {
      const n = sizes.get(p)!;
      await chunked(n, on);
      return data.get(p) ?? new Uint8Array(n);
    },
    flashVerified: async (p: string, d: Uint8Array, on?: (ph: 'write' | 'verify', done: number, t: number) => void) => {
      await chunked(d.length, (x, t) => on?.('write', x, t));
      await chunked(d.length, (x, t) => on?.('verify', x, t));
      data.set(p, d);
    },
    reboot: async () => {},
  };
  return fb as unknown as Fastboot;
}

/** The S1 recipe's shape with the hardware steps simulated. */
const demoRecipe: Recipe<AssetName, SetUpOptions, Provisioned> = {
  ...s1Plus,
  fel: {
    checkChip: async () => { await wait(500); return 'SoC 0x1855 (A133), secure boot off (simulated)'; },
    loader: [],
    boot: async (_fel, _loader, on) => { for (let i = 1; i <= 10; i++) { await wait(120); on(i / 10); } },
  },
  checkLayout: async () => 'partition layout OK (simulated S1 Plus)',
  patchBoot: async ({ backup, log }) => {
    await wait(900);
    log('- simulated patch');
    const image = backup.get('boot')!.slice();
    image[image.length - 1]! ^= 1; // kiln-core refuses a stock boot handed back unchanged
    return image;
  },
  writesBeforeBoot: async ({ backup, log }) => { log('vbmeta flags: 0'); return [{ part: 'vbmeta', data: backup.get('vbmeta')!, what: 'Turning off verified boot' }]; },
  setUp: async (_panel, opts, { step }) => {
    // provision's phases, in its order, without the panel's real durations
    const phases: [AppPhase, string, number][] = [
      [{ step: 'apk', apk: 'kiosk', stage: 'copy' }, 'Install kiosk app', 600], [{ step: 'apk', apk: 'kiosk', stage: 'install' }, '', 2500],
      [{ step: 'home' }, 'Permissions', 600],
      ...(opts.keyboard !== false ? [[{ step: 'apk', apk: 'keyboard', stage: 'copy' }, 'Keyboard', 200], [{ step: 'apk', apk: 'keyboard', stage: 'install' }, '', 600]] as [AppPhase, string, number][] : []),
      [{ step: 'prefs' }, '', 300], [{ step: 'start' }, 'Start the kiosk', 400],
    ];
    for (const [phase, s, ms] of phases) {
      opts.onPhase?.(phase);
      if (s) step(s);
      await wait(ms);
    }
    return { ip: IP };
  },
};

/** A session on the simulated panel. */
export function demoSession(which: Demo): Session {
  const saved: { meta: BackupMeta; files: BackupFile[] }[] = [];
  // run and the app's first-install states start without S1 Kiosk on the panel; radio-thread-old has 0.4.3
  const app = ['run', 'app', 'app-installing', 'app-keyboard'].includes(which) ? null
    : which === 'radio-thread-old' ? { name: '0.4.3', code: 403 } : { name: '0.5.0', code: 500 };
  const panel = simulatedPanel(which === 'wifi-fail', which !== 'wifi-fail' && which !== 'wifi-new', app);
  let adbAllowed = which !== 'run'; // the static states start on an installed panel
  const deps: SessionDeps = {
    recipe: demoRecipe,
    frame: s1PlusUi,
    simulated: true,
    sleep: (ms) => wait(Math.min(ms, 800)),
    // error-written: "Try again", then Connect, is a picker closed with nothing picked
    requestFel: async () => {
      if (which === 'error-written') throw new DOMException('No device selected.', 'NotFoundError');
      return { log: () => {} } as unknown as FelDevice;
    },
    asset: async () => new Uint8Array(16),
    hasAsset: async () => false,
    backups: {
      save: async (meta, files) => { saved.push({ meta, files }); },
      original: async () => saved[0]?.meta ?? (which.startsWith('undo')
        ? { id: 's1-backup-20261112-090000', created: '', bootWasPatched: false, files: s1Plus.backup.parts.map((part) => ({ part, size: 4096, sha256: '' })) }
        : undefined),
      files: async () => {
        if (which === 'undo-unreadable') throw new DOMException('The operation failed for reasons unrelated to the database itself.', 'UnknownError');
        return saved[0]?.files ?? s1Plus.backup.parts.map((part) => ({ part, data: new Uint8Array(4096), sha256: '', crc: 0 }));
      },
    },
    loadKey: async () => ({ publicKey: 'QAAAAdemo kiln@demo' }) as never,
    devices: {
      knownFastboot: async () => undefined,
      requestFastboot: async () => fakeFastboot(),
      knownAdb: async () => (adbAllowed ? panel : undefined),
      requestAdb: async () => { adbAllowed = true; return panel; },
    },
    openTab: () => null,
    confirm: () => true,
    download: () => {},
  };
  return new Session(deps);
}

/** Jumps a fresh demo session to one state. `run` starts the live flow from Connect. */
export async function enterDemo(session: Session, which: Demo): Promise<void> {
  const { dispatch } = session.store;
  const never = new AbortController().signal;
  const progress = (overall: number, phase: 'boot' | 'backup' | 'patch' | 'flash' | 'verify', title: string, sub: string) =>
    dispatch({ type: 'progress', p: { overall, phase, phasePct: 0, title, sub } });
  const install = () => {
    dispatch({ type: 'start', flow: 'install', now: Date.now() - 95_000 });
    progress(0.05, 'boot', 'Waiting for the panel to reconnect', '');
  };
  switch (which) {
    case 'run':
    case 'connect':
      return;
    case 'selftest':
      return selfTest(session);
    case 'ask-fastboot':
      install();
      void session.asks.open('fastboot', () => new Promise(() => {}), never);
      return;
    case 'install':
      install();
      for (const [i, part] of s1Plus.backup.parts.entries()) {
        dispatch({ type: 'partition', part, state: 'done', info: { size: part === 'boot' ? 32 << 20 : 1 << 20, sha256: `${(0x3a7f + i * 977).toString(16)}…${(0x91c2 - i * 313).toString(16)}` } });
      }
      for (const l of ['SoC 0x1855 (A133), secure boot off', 'U-Boot started from RAM; waiting for its fastboot', 'partition layout OK (S1 Plus)', 'boot read twice, hashes match', 'backup s1-backup-20261112-101500 stored in this browser', 'patched boot 4c1e…a0b7 (from 3a7f…91c2)', 'vbmeta flags: 3']) {
        dispatch({ type: 'log', line: `10:15:00 ${l}` });
      }
      progress(0.68, 'flash', 'Writing the new software', 'Writing boot');
      return;
    case 'ask-adb':
      dispatch({ type: 'setup', setup: { phase: 'waiting', note: 'The panel is starting its new software. The first start takes a minute or two.' } });
      void session.asks.open('adb', () => new Promise(() => {}), never);
      return;
    case 'app': // a first install: the panel has no S1 Kiosk yet
    case 'app-update': // S1 Kiosk is on the panel: installing replaces it
      await session.route('resume');
      return;
    case 'app-installing': // Android installing and optimising the app
    case 'app-keyboard': // the next APK: the on-screen keyboard
      await session.route('resume');
      dispatch({ type: 'setup', setup: { phase: 'settings', update: false, chosen: { clock24: true, keyboard: true }, installing: [
        { step: 'apk', apk: 'kiosk', stage: 'copy' }, { step: 'apk', apk: 'kiosk', stage: 'install' },
        ...(which === 'app-keyboard' ? [{ step: 'home' }, { step: 'apk', apk: 'keyboard', stage: 'install' }] as AppPhase[] : []),
      ] } });
      return;
    case 'wifi': // the panel is on Wi-Fi already
    case 'wifi-new': // it isn't: the networks it can see
    case 'wifi-fail':
      await session.route('wifi');
      return;
    case 'home-assistant':
      await session.route('home-assistant');
      return;
    case 'radio':
      await session.route('radio');
      return;
    case 'radio-next':
      await session.route('radio');
      await session.applyRadio('zigbee_tcp');
      return;
    case 'radio-thread': // a Thread router, on an S1 Kiosk that gets the network from Home Assistant
    case 'radio-thread-old': // the same on 0.4.3, which can't
      await session.route('radio');
      await session.applyRadio('thread_router');
      return;
    case 'done':
      dispatch({ type: 'done', done: {
        title: 'Done.', body: 'The panel is on Home Net. Home Assistant finds it by itself as an ESPHome device.',
        backup: { name: 's1-backup-20261112-101500.zip', info: '9 partitions · 52 MiB', hint: 'Original software, backed up. Keep it: it\'s also kept in this browser.' },
      } });
      return;
    case 'undo':
      await session.openUndo();
      return;
    case 'undo-refused': // what "Give back Aqara's apps" shows for a backup undo can't restore
      await session.openUndo();
      dispatch({ type: 'undo', patch: { depth: 'keep', note: { kind: 'err', title: 'This backup can\'t undo the install.', sub: 'This backup has no vbmeta or bootloader. This step changed nothing on the panel.', link: { href: s1PlusUi.help.undo, text: 'Undo by hand' } } } });
      return;
    case 'undo-apps-back': // "Keep its settings" after the apps are back: the backup is the one they were checked against
      await session.openUndo();
      dispatch({ type: 'undo', patch: { depth: 'keep', appsBack: true, locked: true, note: { kind: 'ok', title: 'Aqara\'s apps are back.', sub: 'Next, put the original software back: Continue, then recovery mode.' } } });
      return;
    case 'undo-unreadable': // this browser has a backup, but IndexedDB fails to read it
      await session.openUndo();
      return;
    case 'undo-refused-splash': { // the same for a backup whose splash is already ours (issue 106), worded by the real check
      await session.openUndo();
      const plan = { ...s1Plus.backup, isPatched: async () => false, ours: { bootloader: { ...s1Plus.backup.ours!.bootloader!, is: async () => true } } };
      const why = await checkRestorable(plan, new Map(plan.restore.map((p) => [p, new Uint8Array(16)]))).then(() => '', (e: Error) => e.message);
      dispatch({ type: 'undo', patch: { depth: 'keep', note: { kind: 'err', title: 'This backup can\'t undo the install.', sub: `${why.charAt(0).toUpperCase()}${why.slice(1)}. This step changed nothing on the panel.`, link: { href: s1PlusUi.help.undo, text: 'Undo by hand' } } } });
      return;
    }
    case 'undo-connect':
      await session.openUndo();
      dispatch({ type: 'go', screen: 'connect', flow: 'undo' });
      return;
    case 'restore':
      dispatch({ type: 'start', flow: 'undo', now: Date.now() - 40_000 });
      progress(0.45, 'flash', 'Putting the original software back', 'Writing boot');
      return;
    case 'undo-done':
      dispatch({ type: 'start', flow: 'undo', now: Date.now() });
      dispatch({ type: 'done', done: { title: 'The original software is back.', body: 'One last step, on the panel: swipe down, tap Settings on the S1 Kiosk notification, then Advanced settings → Factory reset.' } });
      return;
    case 'error':
      install();
      dispatch({ type: 'fail', failure: explain(new Error('transfer error: the device was disconnected'), 2, 'none') });
      return;
    case 'error-written':
      install();
      dispatch({ type: 'log', line: '10:15:00 vbmeta flashed and verified' });
      dispatch({ type: 'fail', failure: explain(new Error('boot: read-back does not match what was written'), 2, 'verified') });
      return;
  }
}

/**
 * ?demo=selftest: what #27 asks of the bundler, checked in the built page. bootpatch.wasm loads from
 * where Next put it, and every staged blob downloads and passes its SHA-256 (the APK through
 * /api/release-asset). Results go to the technical log; the page title ends in PASS or FAIL.
 */
async function selfTest(session: Session) {
  const { dispatch } = session.store;
  const say = (line: string) => dispatch({ type: 'log', line: `selftest ${line}` });
  dispatch({ type: 'start', flow: 'install', now: Date.now() });
  let ok = true;
  try {
    const c = await loadCompressor();
    const text = new TextEncoder().encode('kiln '.repeat(1000));
    const back = c.gunzip(c.gzip(text));
    if (back.length !== text.length || !back.every((x, i) => x === text[i])) throw new Error('gzip round trip differs');
    say('bootpatch.wasm loaded; gzip round trip OK');
    say(`isMagiskPatched(demo boot) = ${await isMagiskPatched(demoBootImage())}`);
  } catch (e) { ok = false; say(`FAIL wasm: ${(e as Error).message}`); }
  const assets = new Assets<AssetName>('/assets/');
  let list: Awaited<ReturnType<typeof assets.getManifest>>['assets'] = [];
  try {
    list = (await assets.getManifest()).assets;
  } catch (e) { ok = false; say(`FAIL manifest: ${(e as Error).message}`); }
  for (const [i, a] of list.entries()) {
    try {
      const b = await assets.get(a.name);
      say(`${a.name} ${fmtSize(b.length)} sha256 OK (${a.path.startsWith('/api/') ? 'release route' : 'static'})`);
    } catch (e) { ok = false; say(`FAIL ${a.name}: ${(e as Error).message}`); }
    dispatch({ type: 'progress', p: { overall: (i + 1) / list.length * 0.97, phase: 'backup', phasePct: 0, title: `Checked ${i + 1} of ${list.length} files`, sub: '' } });
  }
  say(ok ? 'PASS' : 'FAIL');
  document.title = `selftest ${ok ? 'PASS' : 'FAIL'}`;
}
