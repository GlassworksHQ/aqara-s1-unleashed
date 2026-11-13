// The installer page (index.html, design E). Renders kiln-core's InstallFlow with the S1 recipe; all hardware work lives there.
import felImg from '../../../docs/img/fel-button.jpg?url';
import { loadOrCreateAdbKey, type AdbKey, type PanelShell } from '@glassworks/adb';
import type { Fastboot } from '@glassworks/fastboot';
import { FelDevice } from '@glassworks/fel';
import { SETUP_SOCKET, SetupChannel, validHaUrl, type SetupState } from '@glassworks/setup-channel';
import {
  IMPROV_SOCKET, ImprovSerialClient, PacketReader, PacketType, Rpc, State as ImprovState, describeSwitch, frame, parseResult, s1Plus, strings, unprovision,
  type AssetName, type ByteStream, type Provisioned, type SetUpOptions,
} from '@glassworks/aqara-s1-plus-installer';
import {
  Assets, BackupRefused, BackupStore, InstallFlow, backupZip, fmtSize, readBackupZip, restoreFromBackup, webUsbDevices,
  type AskKind, type Bar, type BackupFile, type BackupMeta, type FlowUi, type PartState, type Progress,
} from '@glassworks/kiln-core';
import { giveBackApps } from './undo';

const $ = <T extends HTMLElement = HTMLElement>(s: string) => document.querySelector<T>(s)!;
const $$ = (s: string) => [...document.querySelectorAll<HTMLElement>(s)];
const still = matchMedia('(prefers-reduced-motion: reduce)').matches;

type ScreenId = 'welcome' | 'fel' | 'install' | 'app' | 'done' | 'undo' | 'error';
type Mode = 'install' | 'undo';

const STEPS: Record<Mode, { name: string; done: string; cur: string }[]> = {
  install: [
    { name: 'Check this browser', done: 'WebUSB ready', cur: 'WebUSB ready' },
    { name: 'Enter FEL mode', done: 'A133 found over USB', cur: 'Hold the button, plug in' },
    { name: 'Back up & install', done: 'Backed up · flashed · verified', cur: 'Backing up' },
    { name: 'Set up the app', done: 'Kiosk installed', cur: 'Settings, then Wi-Fi' },
    { name: 'Done', done: 'Panel ready', cur: '' },
  ],
  undo: [
    { name: 'Choose the backup', done: 'Backup checked', cur: 'From this browser or a file' },
    { name: 'Enter FEL mode', done: 'A133 found over USB', cur: 'Hold the button, plug in' },
    { name: 'Restore', done: 'Restored · verified', cur: 'Writing the original software' },
    { name: 'Done', done: 'Original software back', cur: '' },
  ],
};
const IDX: Record<Mode, Partial<Record<ScreenId, number>>> = {
  install: { welcome: 0, fel: 1, install: 2, app: 3, done: 4 },
  undo: { undo: 0, fel: 1, install: 2, done: 3 },
};

/** What the action panel says for each device the browser must be allowed to open. */
const ASK: Record<AskKind, { lead: string; title: string; body: string; pick: string; note: string }> = {
  fastboot: {
    lead: 'The panel restarted in installer mode and is waiting for you. Nothing has been written yet.',
    title: 'Allow this page to talk to the installer',
    body: 'Your browser asks once for every new USB device. The installer continues by itself as soon as you allow it.',
    pick: 'USB download gadget',
    note: 'It\'s the only device in the list. If the list is empty, keep it open: the panel appears within a few seconds.',
  },
  adb: {
    lead: 'The panel is starting with its new software and is waiting for you.',
    title: 'Allow this page to set up the panel',
    body: 'One last OK, so this page can install S1 Kiosk. Meanwhile, don\'t touch the panel\'s screen: leave Aqara\'s setup (language, Wi-Fi, terms) alone.',
    pick: 'QUAD-CORE R818 EVB2',
    note: 'That\'s the panel\'s name over USB. If it isn\'t in the list yet, keep the list open: it appears once the panel has started (up to a minute).',
  },
  connect: {
    lead: 'Connect to your installed panel to continue setting it up. Nothing is reflashed.',
    title: 'Allow this page to connect to the panel',
    body: 'The panel should be running S1 Kiosk and plugged in to this computer. Your browser asks once; after that it reconnects by itself.',
    pick: 'QUAD-CORE R818 EVB2',
    note: 'That\'s the panel\'s name over USB. Not in the list? Check the cable, and run "adb kill-server" if adb is open on this computer.',
  },
};
const LOCK = '<svg class="lock" viewBox="0 0 24 24" aria-label="needs a password"><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>';

/** Wi-Fi mark (dot + 3 arcs) with as many parts lit as the signal deserves. */
function signalIcon(rssi: number): string {
  const level = rssi >= -55 ? 4 : rssi >= -67 ? 3 : rssi >= -78 ? 2 : 1;
  const on = (n: number) => (level >= n ? ' class="on"' : '');
  return `<svg class="sig" viewBox="0 0 24 24" aria-label="signal ${level} of 4">`
    + `<path${on(4)} d="M2.5 9.5a13.5 13.5 0 0 1 19 0"/><path${on(3)} d="M5.5 12.8a9 9 0 0 1 13 0"/>`
    + `<path${on(2)} d="M8.6 16a4.6 4.6 0 0 1 6.8 0"/><circle${on(1)} cx="12" cy="19.2" r="1.6"/></svg>`;
}

/** Development only: the app's setup channel, for ?demo=wifi. */
function demoSetup(): ByteStream {
  let cb: (c: Uint8Array) => void = () => {};
  const state: SetupState = {
    ip: '192.168.1.50',
    ha: { discovered: 'http://homeassistant.local:8123', url: '', signedIn: false, signinUrl: 'http://192.168.1.50:8099/signin' },
    radio: {
      mode: 'off', status: 'Off: radio idle', helper: true,
      options: [
        { id: 'off', label: 'Off', detail: 'The radio stays idle. Choose this if you don\'t use Zigbee or Thread here.' },
        { id: 'zigbee_tcp', label: 'Zigbee coordinator', detail: 'Home Assistant runs a Zigbee network (ZHA) on this panel\'s radio, over the network.' },
        { id: 'thread', label: 'Thread border router', detail: 'Runs a Thread network for Matter devices, through Home Assistant\'s OpenThread Border Router integration.' },
        // the app's repeater roles (RadioStep.OPTIONS), experimental like there
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
        if (m.op === 'set_radio') { state.radio.mode = m.mode!; state.radio.status = m.mode === 'off' ? 'Off: radio idle' : 'ok Zigbee coordinator (TCP): listening on :6638 for nobody yet (waiting for Home Assistant to connect over ESPHome)'; state.radio.allowed = m.mode === 'off' ? undefined : ''; }
        send({ id: m.id, ok: true, state }, m.op === 'set_radio' ? 1500 : 200);
      }
    },
  };
}

/** Development only: a panel answering Improv Serial, for ?demo=wifi. */
function demoImprov(fail: boolean): ByteStream {
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
        if (cmd === Rpc.RequestState) send(PacketType.State, Uint8Array.of(ImprovState.Ready));
        if (cmd === Rpc.ScanWifi) {
          [['Home Net', '-48', 'YES'], ['Home Net 5G', '-62', 'YES'], ['Neighbours', '-74', 'YES'], ['Cafe Guest', '-83', 'NO']]
            .forEach((n, i) => result(Rpc.ScanWifi, n, 600 + i * 40));
          result(Rpc.ScanWifi, [], 800);
        }
        if (cmd === Rpc.WifiSettings) {
          void parseResult(p.data);
          send(PacketType.State, Uint8Array.of(ImprovState.Provisioning), 900);
          if (fail) send(PacketType.Error, Uint8Array.of(0x03), 4200);
          else { send(PacketType.State, Uint8Array.of(ImprovState.Provisioned), 4200); result(Rpc.WifiSettings, ['http://192.168.1.50:6053'], 4250); }
        }
      }
    },
  };
}

const BARS: Bar[] = ['backup', 'patch', 'flash', 'verify'];
const TITLE = 'S1 Unleashed Installer';

class Installer {
  private bars: Record<Bar, number> = { backup: 0, patch: 0, flash: 0, verify: 0 };
  private overall = 0;
  private mode: Mode = 'install';
  private cur: ScreenId | null = null;
  private errAt = 1;
  private assets = new Assets<AssetName>(`${import.meta.env.BASE_URL}assets/`);
  private backups = new BackupStore(s1Plus.backup.db);
  private key?: AdbKey;
  private flow?: InstallFlow<AssetName, SetUpOptions, Provisioned>;
  private result?: { meta: BackupMeta; files: BackupFile[] };
  private panel?: PanelShell;
  private undoFiles?: Map<string, Uint8Array>;
  private started = 0;
  private stepDetail = new Map<number, string>();

  init() {
    document.documentElement.style.setProperty('--fel-img', `url(${felImg})`);
    $<HTMLImageElement>('#fel-img').src = felImg;
    this.checkBrowser();
    this.osTabs();
    $('#start').onclick = () => { this.mode = 'install'; this.show('fel'); };
    $('#go-undo').onclick = $('#done-undo').onclick = () => this.openUndo();
    $('#fel-back').onclick = () => this.show(this.mode === 'undo' ? 'undo' : 'welcome');
    $('#fel-connect').onclick = () => this.connectFel();
    $('#go-resume').onclick = () => void this.resume();
    $('#disc').onclick = () => this.setDetails($('#disc').getAttribute('aria-expanded') !== 'true');
    $('#disc-app').onclick = () => {
      const open = $('#disc-app').getAttribute('aria-expanded') !== 'true';
      $('#details-app').classList.toggle('open', open);
      $('#disc-app').setAttribute('aria-expanded', String(open));
      $('#disc-app').firstElementChild!.textContent = open ? 'Hide details' : 'Show details';
    };
    $('#bk-dl').onclick = () => this.downloadBackup();
    $('#finish').onclick = () => this.finishApp();
    $('#err-home').onclick = () => { history.replaceState(null, '', location.pathname); location.reload(); }; // back to the start, not the route
    $('#err-retry').onclick = () => this.show('fel');
    $('#undo-back').onclick = () => { this.mode = 'install'; this.show('welcome'); };
    $('#undo-go').onclick = () => this.undoContinue();
    this.panelSettings();
    const initial = location.hash; // showing welcome records '#/', so keep the address the user came with
    this.show('welcome');
    if (initial) history.replaceState(null, '', initial);
    addEventListener('hashchange', () => this.route());
    if (!new URLSearchParams(location.search).has('demo')) this.route();
    if (import.meta.env.DEV) this.demo(new URLSearchParams(location.search).get('demo'));
  }

  // ---- screens --------------------------------------------------------------------------------
  private show(id: ScreenId) {
    const prev = this.cur;
    if (prev === id) return;
    this.cur = id;
    for (const s of $$('[data-s]')) {
      const k = s.dataset.s;
      s.classList.remove('enter', 'leaving');
      if (k === id) { s.classList.add('on'); if (prev) { void s.offsetWidth; s.classList.add('enter'); } }
      else if (k === prev) { s.classList.remove('on'); s.classList.add('leaving'); setTimeout(() => s.classList.remove('leaving'), 300); }
      else s.classList.remove('on');
    }
    document.body.dataset.cur = id;
    if (id === 'welcome') this.setRoute('');
    else if (id === 'fel') this.setRoute(this.mode === 'undo' ? 'undo' : 'install');
    else if (id === 'undo') this.setRoute('undo');
    else if (id === 'app' && !this.routing) this.setRoute('resume'); // a reload reconnects instead of starting over
    this.renderSteps();
    scrollTo({ top: 0 });
  }

  private renderSteps() {
    const steps = STEPS[this.mode];
    const id = this.cur!;
    const err = id === 'error';
    const i = err ? this.errAt : IDX[this.mode][id] ?? 0;
    const all = id === 'done';
    $('#rail-title').innerHTML = this.mode === 'undo' ? 'Put the original<br>software back' : 'Let\'s set up<br>your panel';
    $('#steps').innerHTML = '';
    steps.forEach((s, n) => {
      const st = all || n < i ? 'done' : n === i ? (err ? 'err' : 'cur') : 'pend';
      const li = document.createElement('li');
      li.className = st;
      li.innerHTML = `<i class="dot">${st === 'err' ? '!' : n + 1}</i><div><b></b><small></small></div>`;
      li.querySelector('b')!.textContent = s.name;
      li.querySelector('small')!.textContent = st === 'pend' ? '' : st === 'err' ? 'Needs attention' : st === 'done' ? s.done : this.stepDetail.get(n) ?? s.cur;
      $('#steps').append(li);
    });
    $('#mline').style.width = `${all ? 100 : ((i + 0.5) / steps.length) * 100}%`;
    $('#mcount').textContent = all ? 'Complete' : err ? `Step ${i + 1} of ${steps.length} · needs attention` : `Step ${i + 1} of ${steps.length}`;
  }

  private setStepDetail(n: number, s: string) {
    this.stepDetail.set(n, s);
    const small = $$('#steps li')[n]?.querySelector('small');
    if (small && $$('#steps li')[n]!.className === 'cur') small.textContent = s;
  }

  private setDetails(open: boolean) {
    $('#details').classList.toggle('open', open);
    $('#disc').setAttribute('aria-expanded', String(open));
    $('#disc').firstElementChild!.textContent = open ? 'Hide details' : 'Show details';
  }

  // ---- welcome --------------------------------------------------------------------------------
  private checkBrowser() {
    const ua = navigator.userAgent;
    const os = /Android/.test(ua) ? 'android' : /Windows/.test(ua) ? 'win' : /Linux/.test(ua) ? 'linux' : 'mac';
    const osName = { mac: 'macOS', win: 'Windows', linux: 'Linux', android: 'Android' }[os];
    const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Chrome\//.test(ua) ? 'Chrome' : 'This browser';
    const row = (sel: string, ok: boolean, text: string, em: string) => {
      const r = $(sel);
      r.querySelector('.dot')!.className = `dot sm${ok ? ' done' : ''}`;
      r.querySelector('span')!.textContent = text;
      const e = r.querySelector('em')!;
      e.textContent = em;
      e.className = ok ? '' : 'r';
    };
    const usb = 'usb' in navigator;
    row('#chk-usb', usb, usb ? `${browser} on ${osName} · WebUSB works here` : 'No WebUSB here: use Chrome or Edge on a computer, or Chrome on Android', usb ? 'Ready' : 'Not supported');
    row('#chk-secure', isSecureContext, isSecureContext ? 'Served securely, so the browser may open USB devices' : 'Open this page over https (or localhost)', isSecureContext ? 'Ready' : 'Not secure');
    ($('#start') as HTMLButtonElement).disabled = !(usb && isSecureContext);
    this.os = os;
  }
  private os = 'mac';

  private osTabs() {
    const tab = (t: string) => {
      $$('[data-t]').forEach((b) => b.classList.toggle('on', b.dataset.t === t));
      $$('[data-tp]').forEach((p) => p.classList.toggle('on', p.dataset.tp === t));
    };
    for (const b of $$('[data-t]')) {
      if (b.dataset.t === this.os) { b.classList.add('me'); b.title = 'This device'; }
      b.onclick = () => tab(b.dataset.t!);
    }
    tab(this.os);
  }

  // ---- FEL -> install / restore ---------------------------------------------------------------
  private async connectFel() {
    let fel: FelDevice;
    try {
      fel = await FelDevice.request();
    } catch (e) {
      if (e instanceof DOMException && e.name === 'NotFoundError') return this.felNotFound();
      return this.fail(1, e);
    }
    this.resetProgress();
    this.show('install');
    const flow = await this.getFlow();
    try {
      await flow.bootFromFel(fel);
      const fb = await flow.connectFastboot();
      if (this.mode === 'undo') return await this.runRestore(fb);
      this.setStepDetail(2, 'Backing up');
      const r = await flow.install(fb);
      this.result = r;
      this.show('app');
      void this.waitForPanel();
    } catch (e) {
      this.fail(2, e);
    }
  }

  // ---- routes: #/resume, #/wifi, #/home-assistant, #/radio, #/undo, #/install ----------------
  private routing = false;

  /** Records where the user is, without a hashchange event (so it doesn't route again). */
  private setRoute(r: string) {
    const h = r ? `#/${r}` : ' ';
    if (location.hash !== h.trim()) history.replaceState(null, '', h === ' ' ? location.pathname + location.search : h);
  }

  private async route() {
    if (this.routing || this.cur === 'install') return; // never leave a running install
    const r = location.hash.replace(/^#\/?/, '');
    this.routing = true;
    try {
      switch (r) {
        case '': if (this.cur !== 'welcome') this.show('welcome'); break;
        case 'install': this.mode = 'install'; this.show('fel'); break;
        case 'undo': await this.openUndo(); break;
        case 'resume': await this.resume(); break;
        case 'wifi': if (await this.connectPanel()) await this.wifiStep(); break;
        case 'home-assistant': if (await this.connectPanel()) await this.haStep(); break;
        case 'radio':
          if (await this.connectPanel()) {
            this.setup ??= await this.openSetup();
            if (this.setup) await this.radioStep();
            else this.adbRow('err', 'S1 Kiosk isn\'t answering', 'Is the app installed and running? Try #/resume to update it.', 'No answer');
          }
          break;
        default: this.show('welcome'); this.setRoute('');
      }
    } finally {
      this.routing = false;
    }
  }

  /** Connects to an installed, running panel over adb (asks for a click if needed), on the app step. */
  private async connectPanel(): Promise<boolean> {
    this.mode = 'install';
    for (const n of [0, 1, 2]) this.stepDetail.set(n, STEPS.install[n]!.done);
    this.show('app');
    if (this.panel) return true;
    this.adbRow('wait', 'Connecting to the panel', 'It should be running and plugged in', 'Waiting');
    try {
      await this.getFlow();
      this.panel = (await webUsbDevices.knownAdb(this.key!).catch(() => undefined))
        ?? (await this.ask('connect', () => webUsbDevices.requestAdb(this.key!), new AbortController().signal));
      this.adbRow('ok', 'Connected over adb', 'S1 Kiosk is installed', 'Connected');
      $('#hands').hidden = true;
      return true;
    } catch (e) {
      const msg = e instanceof DOMException && e.name === 'NotFoundError' ? 'Nothing was picked.' : (e as Error).message;
      this.adbRow('err', 'Could not connect to the panel', `${msg} Is it running and plugged in? Run "adb kill-server" if adb is open on this computer.`, 'Not connected');
      return false;
    }
  }

  /** An installed panel (design §8.2): skip FEL, connect over adb, update the app, then the rest. */
  private async resume() {
    if (!(await this.connectPanel())) return;
    this.phase('settings', 'Set up the panel', 'Connected to your installed panel. Check these settings, then update S1 Kiosk and finish setting up.', 'Settings');
    this.adbRow('ok', 'Connected over adb', 'Ready to update S1 Kiosk and finish setting up', 'Connected');
    ($('#finish') as HTMLButtonElement).disabled = false;
    $('#finish').textContent = 'Update and continue';
    $('#finish').onclick = () => this.finishApp();
    this.setRoute('resume');
  }

  private async getFlow(): Promise<InstallFlow<AssetName, SetUpOptions, Provisioned>> {
    this.key ??= await loadOrCreateAdbKey();
    this.flow ??= new InstallFlow(s1Plus, {
      asset: (n, p) => this.assets.get(n, p),
      hasAsset: (n) => this.assets.has(n),
      backups: this.backups,
      key: this.key,
      devices: webUsbDevices,
    }, this.flowUi());
    return this.flow;
  }

  private flowUi(): FlowUi {
    return {
      log: (l) => this.log(l),
      progress: (p) => this.progress(p),
      partition: (part, st, info) => this.partition(part, st, info),
      ask: (kind, action, signal) => this.ask(kind, action, signal),
    };
  }

  /**
   * "Your turn": the screen's content makes way for one action panel (what happened, the exact name
   * to pick in the browser's list, one button), the heading says so, and the rail and tab title
   * show it too. Nothing else on screen pretends to be busy meanwhile.
   */
  private ask<T>(kind: AskKind, action: () => Promise<T>, signal: AbortSignal): Promise<T> {
    const screen = document.querySelector<HTMLElement>(`[data-s="${this.cur}"]`)!;
    const content = screen.querySelector<HTMLElement>('.content')!;
    const h2 = screen.querySelector('h2')!;
    const lead = screen.querySelector<HTMLElement>('.body');
    const was = { h2: h2.textContent, lead: lead?.textContent ?? '', leadHidden: lead?.hidden ?? false };
    const hidden = [...content.children].filter((el): el is HTMLElement => el instanceof HTMLElement && !el.hidden);
    const copy = ASK[kind];
    const node = $<HTMLTemplateElement>('#act-tpl').content.firstElementChild!.cloneNode(true) as HTMLElement;
    node.querySelector('h3')!.textContent = copy.title;
    node.querySelector('.act-body')!.textContent = copy.body;
    node.querySelector('.act-pick')!.textContent = copy.pick;
    node.querySelector('.act-note')!.textContent = copy.note;
    const btn = node.querySelector<HTMLButtonElement>('.act-btn')!;
    const msg = node.querySelector<HTMLElement>('.act-msg')!;

    hidden.forEach((el) => { el.hidden = true; });
    h2.textContent = 'Your turn.';
    if (lead) { lead.textContent = copy.lead; lead.hidden = false; }
    content.prepend(node);
    this.yourTurn(true);
    scrollTo({ top: 0, behavior: still ? 'auto' : 'smooth' });
    btn.focus({ preventScroll: true });

    const restore = () => {
      node.remove();
      hidden.forEach((el) => { el.hidden = false; });
      h2.textContent = was.h2;
      if (lead) { lead.textContent = was.lead; lead.hidden = was.leadHidden; }
      this.yourTurn(false);
    };
    return new Promise<T>((resolve) => {
      signal.addEventListener('abort', restore, { once: true }); // found it by itself after all
      btn.onclick = async () => {
        btn.disabled = true;
        msg.className = 'act-msg';
        msg.textContent = 'Pick it in the browser\'s list…';
        try {
          const r = await action();
          if (!signal.aborted) restore();
          resolve(r);
        } catch (e) {
          msg.className = 'act-msg err';
          msg.textContent = e instanceof DOMException && e.name === 'NotFoundError'
            ? 'Nothing was picked. Click the button again.'
            : String((e as Error).message ?? e);
        } finally {
          btn.disabled = false;
        }
      };
    });
  }

  private yourTurn(on: boolean) {
    const i = IDX[this.mode][this.cur!] ?? 0;
    const li = $$('#steps li')[i];
    if (li) {
      li.classList.toggle('you', on);
      li.querySelector('small')!.textContent = on ? 'Waiting for you' : this.stepDetail.get(i) ?? STEPS[this.mode][i]!.cur;
    }
    const n = STEPS[this.mode].length;
    $('#mcount').textContent = on ? `Step ${i + 1} of ${n} · your turn` : `Step ${i + 1} of ${n}`;
    document.title = on ? `Your turn · ${TITLE}` : TITLE;
  }

  private resetProgress() {
    this.started = Date.now();
    $('#log').innerHTML = '';
    $('#plist').innerHTML = '';
    this.bars = { backup: 0, patch: 0, flash: 0, verify: 0 };
    this.overall = 0;
    this.setDetails(false);
    const undo = this.mode === 'undo';
    $('#in-label').textContent = undo ? 'Step 3 · Restore' : 'Step 3 · Back up & install';
    $('#in-body').textContent = undo
      ? 'Writing your panel\'s original software back, and checking each part after writing it.'
      : 'Nothing is changed until your panel\'s own software is safely in this browser. Keep this page open, too.';
    $('#plist-label').textContent = undo ? 'Partitions' : 'Partitions · backup';
    $$('#phases > div').forEach((d, i) => {
      d.style.display = undo && i < 2 ? 'none' : '';
    });
    $('#phases').style.gridTemplateColumns = undo ? 'repeat(2,1fr)' : '';
    this.progress({ overall: 0, phase: 'boot', phasePct: 0, title: 'Starting', sub: '' });
  }

  private progress(p: Progress) {
    // Neither the ring nor a bar ever goes backwards.
    this.overall = Math.max(this.overall, Math.min(1, p.overall));
    for (const k of BARS) {
      const v = p.bars?.[k];
      if (v !== undefined) this.bars[k] = Math.max(this.bars[k], Math.min(1, v));
    }
    const C = 540.35;
    $('#ringp').style.strokeDashoffset = String(C * (1 - this.overall));
    $('#pct').textContent = String(Math.floor(this.overall * 100));
    $('#pl').textContent = p.title;
    let sub = p.sub;
    const elapsed = (Date.now() - this.started) / 1000;
    if (this.overall > 0.08 && this.overall < 0.97 && elapsed > 20) {
      const left = (elapsed / this.overall) * (1 - this.overall);
      sub += left > 90 ? ` · about ${Math.round(left / 60)} minutes left` : left > 15 ? ' · less than a minute left' : '';
    }
    $('#ps').textContent = sub;
    $$('#phases > div').forEach((d, i) => {
      const k = BARS[i]!;
      d.classList.toggle('cur', p.phase === k);
      (d.querySelector('b') as HTMLElement).style.width = `${Math.round(this.bars[k] * 100)}%`;
    });
    if (p.phase === 'backup') this.setStepDetail(2, p.sub.split(' · ')[1] ? `Backing up · ${p.sub.split(' · ')[1]}` : 'Backing up');
    else this.setStepDetail(2, p.title);
  }

  private partition(part: string, st: PartState, info: { size?: number; sha256?: string; pct?: number }) {
    let li = $('#plist').querySelector<HTMLElement>(`[data-p="${part}"]`);
    if (!li) {
      li = document.createElement('li');
      li.dataset.p = part;
      li.innerHTML = '<i class="dot sm"></i><code></code><small></small><span class="h">–</span>';
      li.querySelector('code')!.textContent = part;
      $('#plist').append(li);
    }
    li.className = st === 'done' ? '' : st;
    li.querySelector('.dot')!.className = `dot sm${st === 'done' ? ' done' : st === 'cur' ? ' spin' : ''}`;
    if (info.size) li.querySelector('small')!.textContent = fmtSize(info.size);
    li.querySelector('.h')!.textContent = info.sha256 ?? (st === 'cur' && info.pct !== undefined ? `${Math.round(info.pct * 100)}%` : st === 'pend' ? '–' : '');
  }

  private log(line: string) {
    const div = document.createElement('div');
    const sp = line.indexOf(' ');
    const t = document.createElement('span');
    t.textContent = line.slice(0, sp);
    div.append(t, line.slice(sp));
    for (const log of $$('.log')) {
      log.prepend(log === $('#log') ? div : div.cloneNode(true)); // newest at the top
      while (log.childElementCount > 400) log.lastElementChild!.remove();
      if (log.scrollTop < 40) log.scrollTop = 0; // follow new lines unless the reader scrolled down
    }
  }

  // ---- app ------------------------------------------------------------------------------------
  private adbRow(state: 'wait' | 'ok' | 'busy' | 'err', title: string, sub: string, em: string) {
    const r = $('#adb-row');
    r.querySelector('.dot')!.className = `dot sm${state === 'ok' ? ' done' : state === 'err' ? '' : ' spin'}`;
    r.querySelector('b')!.textContent = title;
    r.querySelector('span')!.textContent = sub;
    const e = r.querySelector('em')!;
    e.textContent = em;
    e.className = state === 'ok' ? '' : state === 'err' ? 'r' : 'a';
  }

  private async waitForPanel() {
    this.adbRow('wait', 'Waiting for the panel', 'First start takes a minute or two', 'Starting');
    try {
      this.panel = await this.flow!.connectAdb();
      this.adbRow('ok', 'Connected over adb', 'Ready to install S1 Kiosk', 'Connected');
      ($('#finish') as HTMLButtonElement).disabled = false;
      $('#finish-hint').textContent = '';
    } catch (e) {
      this.fail(3, e);
    }
  }

  /** Development only: shows a state without hardware, for checking the design. */
  private demo(which: string | null) {
    if (!which) return;
    const never = new AbortController().signal;
    if (which.startsWith('ask') || which === 'install') {
      this.resetProgress();
      this.show('install');
      this.progress({ overall: 0.05, phase: 'boot', phasePct: 1, title: 'Waiting for the panel to reconnect', sub: 'It restarts in installer mode · about 10 seconds' });
      if (which === 'install') this.progress({ overall: 0.8, phase: 'verify', phasePct: 0.5, title: 'Writing the new software', sub: 'Reading boot back to check it', bars: { backup: 1, patch: 1, flash: 0.8, verify: 0.6 } });
      if (which === 'ask-fastboot') void this.ask('fastboot', () => new Promise(() => {}), never);
    }
    if (which.startsWith('wifi')) {
      // a simulated panel speaking Improv Serial: ?demo=wifi (works) or ?demo=wifi-fail (wrong password)
      this.show('app');
      this.adbRow('ok', 'S1 Kiosk installed', 'Set as the home screen', 'Done');
      $('#hands').hidden = true;
      this.panel = { run: async () => ({ out: '', code: 0 }), push: async () => {},
        openStream: async (svc: string) => (svc === SETUP_SOCKET ? demoSetup() : demoImprov(which === 'wifi-fail')) };
      void this.wifiStep();
      return;
    }
    if (which === 'app' || which === 'ask-adb') {
      this.show('app');
      if (which === 'ask-adb') void this.ask('adb', () => new Promise(() => {}), never);
    }
  }

  private clock24 = false;

  /** Language, time zone and clock, defaulting to this computer's. */
  private panelSettings() {
    const here = navigator.language || 'en-US';
    // Languages Android 10 ships translations for that are likely on this panel; plus this computer's.
    const tags = ['en-US', 'en-GB', 'en-AU', 'en-CA', 'de-DE', 'fr-FR', 'fr-CA', 'es-ES', 'es-US', 'es-MX', 'it-IT', 'nl-NL', 'pt-PT', 'pt-BR',
      'pl-PL', 'cs-CZ', 'sv-SE', 'da-DK', 'nb-NO', 'fi-FI', 'ru-RU', 'uk-UA', 'tr-TR', 'el-GR', 'hu-HU', 'ro-RO', 'ja-JP', 'ko-KR', 'zh-CN', 'zh-TW', 'zh-HK'];
    if (/^[a-z]{2,3}-[A-Z]{2}$/.test(here) && !tags.includes(here)) tags.push(here);
    const names = new Intl.DisplayNames([here], { type: 'language' });
    const loc = $('#opt-locale') as HTMLSelectElement;
    const opts = tags.map((t) => ({ t, n: names.of(t) ?? t })).sort((a, b) => a.n.localeCompare(b.n, here));
    loc.append(new Option('Leave as it is', ''), ...opts.map(({ t, n }) => new Option(`${n} (${t})`, t)));
    loc.value = tags.includes(here) ? here : (tags.find((t) => t.split('-')[0] === here.split('-')[0]) ?? '');

    const tzHere = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const tz = $('#opt-tz') as HTMLSelectElement;
    const zones = (Intl as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone') ?? [tzHere];
    tz.append(new Option('Leave as it is', ''), ...zones.map((z) => new Option(z.replace(/_/g, ' '), z)));
    tz.value = zones.includes(tzHere) ? tzHere : '';

    const seg = $$('#opt-clock button');
    const set = (h24: boolean) => {
      this.clock24 = h24;
      seg.forEach((b) => { const on = (b.dataset.h === '24') === h24; b.classList.toggle('on', on); b.setAttribute('aria-checked', String(on)); });
    };
    seg.forEach((b) => { b.onclick = () => set(b.dataset.h === '24'); });
    set(!new Intl.DateTimeFormat(here, { hour: 'numeric' }).resolvedOptions().hour12);
  }

  private ip?: string;
  private improv?: ImprovSerialClient;
  private wifiDone = false;

  /**
   * Waits until the app on the panel listens on `socket` (it cold-starts after the install, which can
   * take a while on this panel), then connects. Errors go to the log instead of disappearing.
   */
  private async waitForAppSocket(socket: string, say: (s: string) => void, timeoutMs = 90_000): Promise<boolean> {
    const name = socket.replace(/^localabstract:/, '@');
    const until = Date.now() + timeoutMs;
    say('Starting S1 Kiosk on the panel…');
    while (Date.now() < until) {
      const r = await this.panel!.run(`grep -c ' ${name}$' /proc/net/unix`).catch(() => ({ out: '0', code: 1 }));
      if (Number.parseInt(r.out.trim(), 10) > 0) return true;
      await new Promise((ok) => setTimeout(ok, 1500));
    }
    this.log(`${new Date().toTimeString().slice(0, 8)} ${name} never started listening on the panel`);
    return false;
  }

  private async openImprov(say: (s: string) => void = () => {}): Promise<ImprovSerialClient | undefined> {
    const open = this.panel?.openStream?.bind(this.panel);
    if (!open) return undefined;
    const demo = import.meta.env.DEV && new URLSearchParams(location.search).has('demo'); // simulated panel
    if (!demo && !(await this.waitForAppSocket(IMPROV_SOCKET, say))) return undefined;
    say('Connecting to S1 Kiosk…');
    for (let i = 0; i < 5; i++) {
      try {
        const c = new ImprovSerialClient(await open(IMPROV_SOCKET));
        await c.requestState(5000);
        return c;
      } catch (e) {
        this.log(`${new Date().toTimeString().slice(0, 8)} Improv over adb, try ${i + 1}: ${(e as Error).message ?? e}`);
        await new Promise((r) => setTimeout(r, 1500));
      }
    }
    return undefined;
  }

  /** After the app is in: Wi-Fi from this computer, over Improv Serial through adb. */
  private async wifiStep() {
    const btn = $('#finish') as HTMLButtonElement;
    this.phase('wifi', 'Connect it to Wi-Fi', 'Pick the network the panel should use. The password goes straight to the panel over USB.', 'Wi-Fi', () => void this.haStep());
    btn.textContent = 'Connect';
    btn.disabled = true;
    $('#finish-hint').textContent = '';
    const list = $('#wifi-list');
    list.innerHTML = '';
    $('#wifi-pick').hidden = false;
    $('#wifi-connect').hidden = true;
    this.improv = await this.openImprov((m) => { $('#wifi-scan-st').textContent = m; });
    if (!this.improv) {
      $('#wifi-scan-st').textContent = 'Couldn\'t reach S1 Kiosk on the panel';
      $('#wifi-st').className = 'status err';
      $('#wifi-st').textContent = 'Try again, or set up Wi-Fi on the panel: it shows Wi-Fi as the first step. Details are in the log.';
      btn.textContent = 'Try again';
      btn.disabled = false;
      btn.onclick = () => { $('#wifi-st').textContent = ''; $('#wifi-st').className = 'status'; void this.wifiStep(); };
      return;
    }
    $('#wifi-scan-st').textContent = 'Looking for networks the panel can see…';
    if (this.improv.state === ImprovState.Provisioned) $('#wifi-st').textContent = 'The panel is already on Wi-Fi; pick another network to change it, or skip.';
    else if (this.wifiHint) $('#wifi-st').textContent = this.wifiHint;
    let networks: { ssid: string; rssi: number; secure: boolean }[] = [];
    try { networks = await this.improv.scan(); } catch { /* typed by hand then */ }
    $('#wifi-scan-st').textContent = networks.length ? `${networks.length} in range of the panel` : 'None found: type the name';
    const pick = (row: HTMLElement) => {
      for (const r of list.children) r.setAttribute('aria-selected', String(r === row));
      this.wifiChoice = row.dataset.other ? { other: true, secure: true } : { ssid: row.dataset.ssid!, secure: row.dataset.secure === 'true' };
      $('#wifi-other-row').hidden = !row.dataset.other;
      $('#wifi-psk-row').hidden = !this.wifiChoice.secure;
      btn.disabled = false;
      (row.dataset.other ? $('#wifi-other') : this.wifiChoice.secure ? $('#wifi-psk') : btn).focus();
    };
    for (const n of networks) {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'net';
      row.setAttribute('role', 'option');
      row.dataset.ssid = n.ssid;
      row.dataset.secure = String(n.secure);
      row.innerHTML = `<i class="rad"></i><span class="nm"></span>${n.secure ? LOCK : ''}${signalIcon(n.rssi)}`;
      row.querySelector('.nm')!.textContent = n.ssid;
      row.title = `${n.ssid} · ${n.rssi} dBm${n.secure ? ' · password' : ' · open'}`;
      row.onclick = () => pick(row);
      list.append(row);
    }
    const other = document.createElement('button');
    other.type = 'button';
    other.className = 'net other';
    other.setAttribute('role', 'option');
    other.dataset.other = '1';
    other.innerHTML = '<i class="rad"></i><span class="nm">Other network…</span>';
    other.onclick = () => pick(other);
    list.append(other);
    if (list.firstElementChild) pick(list.firstElementChild as HTMLElement);
    btn.onclick = () => void this.sendWifi();
  }

  private wifiChoice: { ssid?: string; secure: boolean; other?: boolean } = { secure: true };
  private wifiSsid?: string;
  /** Set when a signing-key switch made the panel forget its network (shown on the Wi-Fi step). */
  private wifiHint?: string;
  private setup?: SetupChannel;

  /** Shows one phase of the app step (settings → Wi-Fi → Home Assistant → radio). */
  private phase(which: 'settings' | 'wifi' | 'ha' | 'radio', title: string, lead: string, detail: string, skip?: () => void) {
    for (const [k, id] of [['settings', '#settings-block'], ['wifi', '#wifi-block'], ['ha', '#ha-block'], ['radio', '#radio-block']] as const) {
      $(id).hidden = k !== which;
    }
    document.querySelector<HTMLElement>('[data-s="app"] h2')!.textContent = title;
    document.querySelector<HTMLElement>('[data-s="app"] .body')!.textContent = lead;
    this.setStepDetail(3, detail);
    this.setRoute(which === 'settings' ? 'resume' : which === 'ha' ? 'home-assistant' : which);
    const sk = $('#wifi-skip') as HTMLButtonElement;
    sk.hidden = !skip;
    sk.textContent = which === 'radio' ? 'Skip: leave it off for now' : 'Skip: do this on the panel';
    if (skip) sk.onclick = skip;
    $('#finish-hint').textContent = '';
  }

  /**
   * Runs a setup-channel request; if it fails (saving a new Home Assistant host can restart the app,
   * which closes the channel), reconnects once the app is back and retries once.
   */
  private haTimer?: ReturnType<typeof setInterval>;

  private async withSetup<T>(f: (c: SetupChannel) => Promise<T>): Promise<T> {
    try {
      return await f(this.setup!);
    } catch (first) {
      const onState = this.setup?.onState;
      const again = await this.openSetup(15);
      if (!again) throw first;
      if (onState) again.onState = onState;
      this.setup = again;
      return f(again);
    }
  }

  private async openSetup(tries = 5): Promise<SetupChannel | undefined> {
    const open = this.panel?.openStream?.bind(this.panel);
    if (!open) return undefined;
    for (let i = 0; i < tries; i++) {
      try {
        const c = new SetupChannel(await open(SETUP_SOCKET));
        await c.getState();
        return c;
      } catch { await new Promise((r) => setTimeout(r, 1000)); }
    }
    return undefined;
  }

  /** Home Assistant from the computer: address (prefilled from what the panel found), sign-in, add. */
  private async haStep() {
    const btn = $('#finish') as HTMLButtonElement;
    this.setup ??= await this.openSetup();
    if (!this.setup) return this.showDone(this.ip, this.wifiSsid); // older app: the panel's own setup does the rest
    const c = this.setup;
    this.phase('ha', 'Connect it to Home Assistant', 'Tell the panel where Home Assistant is, then sign in. You can also do this on the panel.', 'Home Assistant', () => void this.radioStep());
    const input = $('#ha-url') as HTMLInputElement;
    const st = $('#ha-st');
    const render = (s: SetupState) => {
      this.ip = s.ip ?? this.ip;
      $('#ha-found').textContent = s.ha.discovered ? 'Found on your network' : 'Not found automatically: type it';
      if (!input.value) input.value = s.ha.url || s.ha.discovered || 'http://homeassistant.local:8123';
      const row = $('#ha-signin');
      row.querySelector('.dot')!.className = `dot sm${s.ha.signedIn ? ' done' : ''}`;
      row.querySelector('b')!.textContent = s.ha.signedIn ? 'Signed in' : 'Sign in';
      ($('#ha-signin-btn') as HTMLButtonElement).hidden = s.ha.signedIn;
      if (s.ha.signedIn) { btn.textContent = 'Continue'; st.className = 'status ok'; st.textContent = 'The panel is signed in to Home Assistant.'; }
    };
    c.onState = render;
    render(c.state!);
    // keep the state fresh (and the channel alive across an app restart) while on this phase
    clearInterval(this.haTimer);
    this.haTimer = setInterval(() => {
      if ($('#ha-block').hidden) { clearInterval(this.haTimer); return; }
      void this.withSetup((x) => x.getState()).then(render).catch(() => {});
    }, 3000);
    btn.textContent = 'Continue';
    btn.disabled = false;
    // Sign in: the popup must open inside the click, so open it first and point it after saving the address.
    $('#ha-signin-btn').onclick = () => {
      const url = input.value.trim();
      if (!validHaUrl(url)) { st.className = 'status err'; st.textContent = 'That doesn\'t look like a web address (http://… or https://…).'; return; }
      const tab = window.open('about:blank', '_blank');
      st.className = 'status';
      st.textContent = 'Sign in on the tab that opened; this page notices when you are done.';
      void (async () => {
        try {
          const s = url === this.setup?.state?.ha.url ? this.setup.state : await this.withSetup((x) => x.setHa(url));
          if (!s.ha.signinUrl) throw new Error('the panel has no sign-in page yet (is it on Wi-Fi?)');
          if (tab) tab.location.href = s.ha.signinUrl; else window.open(s.ha.signinUrl, '_blank');
        } catch (e) {
          tab?.close();
          st.className = 'status err';
          st.textContent = (e as Error).message;
        }
      })();
    };
    $('#ha-add').onclick = () => { $('#ha-add-dot').className = 'dot sm done'; };
    btn.onclick = async () => {
      const url = input.value.trim();
      if (url && url !== this.setup?.state?.ha.url) {
        if (!validHaUrl(url)) { st.className = 'status err'; st.textContent = 'That doesn\'t look like a web address (http://… or https://…).'; return; }
        btn.disabled = true;
        try { await this.withSetup((x) => x.setHa(url)); } catch (e) { st.className = 'status err'; st.textContent = (e as Error).message; btn.disabled = false; return; }
      }
      void this.radioStep();
    };
  }

  /** The radio role, from the computer (also offered on the panel and in Home Assistant). */
  private async radioStep() {
    // the channel may have closed if saving the address restarted the app: make sure it's live
    await this.withSetup((x) => x.getState());
    const c = this.setup!;
    const btn = $('#finish') as HTMLButtonElement;
    this.phase('radio', 'Choose what the radio does', 'The panel has a Zigbee/Thread radio. Leave it off, or let Home Assistant use it.', 'Radio');
    const list = $('#radio-list');
    const st = $('#radio-st');
    list.hidden = false;
    $('#radio-next').hidden = true;
    const s = c.state!;
    let chosen = s.radio.mode;
    list.innerHTML = '';
    // Every role the app offers (RadioStep.OPTIONS on the panel), routers included: none is "coming soon".
    for (const o of s.radio.options) {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'opt';
      row.setAttribute('role', 'radio');
      row.innerHTML = '<i class="rad"></i><span class="t"><b></b><span></span></span>';
      row.querySelector('b')!.textContent = o.label;
      if (o.experimental) row.querySelector('b')!.insertAdjacentHTML('beforeend', '<em class="tag">Experimental</em>');
      row.querySelector('.t > span')!.textContent = o.detail;
      const usable = s.radio.helper || o.id === 'off';
      row.setAttribute('aria-disabled', String(!usable));
      row.setAttribute('aria-checked', String(o.id === chosen));
      row.onclick = () => {
        if (!usable) return;
        chosen = o.id;
        for (const r of list.children) r.setAttribute('aria-checked', String(r === row));
      };
      list.append(row);
    }
    st.className = 'status';
    st.textContent = s.radio.helper ? `Now: ${s.radio.status}` : 'Only Off is available: this panel has no root helper for the radio.';
    btn.textContent = 'Finish';
    btn.disabled = false;
    btn.onclick = async () => {
      if (chosen !== this.setup!.state!.radio.mode) {
        btn.disabled = true;
        st.textContent = 'Switching the radio…';
        try {
          const r = await this.withSetup((x) => x.setRadio(chosen));
          st.className = 'status ok';
          st.textContent = r.radio.status;
        } catch (e) {
          st.className = 'status err';
          st.textContent = (e as Error).message;
          btn.disabled = false;
          return;
        }
      }
      if (this.radioNextSteps(chosen)) return; // next: add it to HA and pair; Finish appears there
      void this.setup?.close();
      this.showDone(this.ip, this.wifiSsid);
    };
  }

  /** The connecting view: animated Wi-Fi mark + three steps; ok / err end states. */
  private wifiView(state: 'busy' | 'ok' | 'err', title: string, step: 'sent' | 'join' | 'ok', err = '') {
    $('#wifi-pick').hidden = true;
    const v = $('#wifi-connect');
    v.hidden = false;
    v.className = `well wconn ${state}`;
    $('#wc-label').textContent = state === 'ok' ? 'Connected' : state === 'err' ? 'Could not connect' : 'Connecting';
    $('#wc-title').textContent = title;
    $('#wc-err').textContent = err;
    const order = ['sent', 'join', 'ok'];
    const at = order.indexOf(step);
    v.querySelectorAll<HTMLElement>('.wsteps li').forEach((li, i) => {
      li.className = state === 'ok' || i < at ? 'done' : i === at ? (state === 'err' ? 'fail' : 'cur') : '';
    });
  }

  private async sendWifi() {
    const btn = $('#finish') as HTMLButtonElement;
    const c = this.wifiChoice;
    const ssid = c.other ? ($('#wifi-other') as HTMLInputElement).value : c.ssid ?? '';
    const psk = c.secure ? ($('#wifi-psk') as HTMLInputElement).value : '';
    const st = $('#wifi-st');
    if (!ssid) { st.className = 'status err'; st.textContent = 'Pick or type a network.'; return; }
    btn.disabled = true;
    ($('#wifi-skip') as HTMLButtonElement).hidden = true;
    this.wifiView('busy', ssid, 'sent');
    const succeeded = () => {
      ($('#wifi-psk') as HTMLInputElement).value = '';
      this.wifiView('ok', ssid, 'ok');
      this.log(`${new Date().toTimeString().slice(0, 8)} panel connected to Wi-Fi ${ssid}`);
      void this.improv?.close();
      this.wifiSsid = ssid;
      btn.textContent = 'Continue';
      btn.disabled = false;
      btn.onclick = () => void this.haStep();
    };
    try {
      await this.improv!.provision(ssid, psk, () => this.wifiView('busy', ssid, 'join'));
      succeeded();
    } catch (e) {
      // The app can restart right after joining (it found Home Assistant and must reload its engine),
      // which cuts this connection before the result arrives. Reconnect and ask: Provisioned = on Wi-Fi.
      this.log(`${new Date().toTimeString().slice(0, 8)} Wi-Fi: ${(e as Error).message}; asking the panel again`);
      const again = await this.openImprov((m) => this.wifiView('busy', ssid, 'join', m === 'Connecting to S1 Kiosk…' ? '' : m));
      const state = again ? await again.requestState().catch(() => 0) : 0;
      if (again && state === ImprovState.Provisioned) {
        this.improv = again;
        succeeded();
        return;
      }
      this.wifiView('err', ssid, 'join', (e as Error).message);
      btn.textContent = 'Try again';
      btn.disabled = false;
      ($('#wifi-skip') as HTMLButtonElement).hidden = false;
      btn.onclick = () => {
        $('#wifi-connect').hidden = true;
        $('#wifi-pick').hidden = false;
        btn.textContent = 'Connect';
        btn.onclick = () => void this.sendWifi();
      };
    }
  }

  /**
   * After the radio role is applied: what to do in Home Assistant to use it, per mode (docs/radio.md).
   * Returns false when there is nothing to do (Off).
   */
  private radioNextSteps(mode: string): boolean {
    const btn = $('#finish') as HTMLButtonElement;
    const state = this.setup?.state;
    const ip = state?.ip ?? this.ip ?? '<panel IP>';
    const ha = state?.ha.url ? new URL(state.ha.url).origin : '';
    type Step = { title: string; body: string; kv?: [string, string, boolean?][]; link?: string };
    const zhaAdd = ha ? `${ha}/config/zha/add` : 'https://my.home-assistant.io/redirect/integration/?domain=zha';
    // Home Assistant's own pages when the panel knows its address (frontend 2026.11: /config/integrations/
    // dashboard/add?domain=… starts a config flow; /config/thread is the Thread page), else My Home Assistant.
    const otbrAdd = ha ? `${ha}/config/integrations/dashboard/add?domain=otbr` : 'https://my.home-assistant.io/redirect/config_flow_start/?domain=otbr';
    const threadPage = ha ? `${ha}/config/thread` : 'https://my.home-assistant.io/redirect/integration/?domain=thread';
    const esphome = ha ? `${ha}/config/integrations/integration/esphome` : 'https://my.home-assistant.io/redirect/integration/?domain=esphome';
    const plans: Record<string, { title: string; lead: string; steps: Step[] }> = {
      zigbee_tcp: {
        title: 'Use the radio in Home Assistant', lead: 'The panel is now a Zigbee coordinator. Add it to Home Assistant, then pair your devices.',
        steps: [
          { title: '1 · Add the radio to Home Assistant', body: 'Opens Zigbee (ZHA) setup. Choose Enter manually, then use these values. When ZHA asks about network settings, keep the radio\'s current ones: creating a new network from ZHA doesn\'t work with this radio yet.',
            kv: [['Radio type', 'EZSP'], ['Serial path', `socket://${ip}:6638`, true], ['Port speed', 'Leave as is (115200)']],
            link: 'https://my.home-assistant.io/redirect/config_flow_start/?domain=zha' },
          { title: '2 · Pair your Zigbee devices', body: 'Opens Home Assistant\'s Zigbee “Add device”, which starts pairing for a few minutes. Put each device in pairing mode.', link: zhaAdd },
          { title: 'Using Zigbee2MQTT instead? (experimental)', body: 'On the panel\'s device in Home Assistant (S1 Panel, under ESPHome), set Zigbee stack to Silicon Labs 8.2.2. The panel downloads it from Silicon Labs, which takes a minute. Then point Zigbee2MQTT at the panel. If the panel\'s Radio helper says v3, install the panel\'s software with this installer once more first.',
            kv: [['adapter', 'ember'], ['port', `tcp://${ip}:6638`, true]] },
        ],
      },
      thread: {
        title: 'Use the border router in Home Assistant', lead: 'The panel is now a Thread border router: it runs its own Thread network for Home Assistant\'s OpenThread Border Router integration. To join a Thread network you already have as a repeater, choose Thread router (repeater) instead.',
        steps: [
          { title: 'Add OpenThread Border Router', body: 'Opens the integration setup in Home Assistant. Enter this URL. If you already have a border router, Home Assistant can use both.',
            kv: [['URL', `http://${ip}:8081`, true]], link: otbrAdd },
        ],
      },
      zigbee_router: {
        title: 'Join your Zigbee network', lead: 'The panel is now a Zigbee router (repeater, experimental). It joins your existing network and extends it.',
        steps: [
          { title: '1 · Open pairing in Home Assistant', body: 'Opens your Zigbee “Add device”, which lets new devices join for a few minutes.', link: zhaAdd },
          { title: '2 · Tell the panel to join', body: 'In Home Assistant, open the panel\'s device (S1 Panel, under ESPHome) and press Join Zigbee network. It shows up in ZHA as a router.' },
        ],
      },
      thread_router: {
        title: 'Join your Thread network', lead: 'The panel is now a Thread router (repeater, experimental). It joins your existing Thread network and extends it.',
        steps: [
          { title: '1 · Copy your Thread network\'s dataset', body: 'Opens Home Assistant\'s Thread page. Under Preferred network, open ⓘ Network information and copy Active dataset TLVs. Home Assistant shows it to admin users, for a network from an OpenThread Border Router it manages.', link: threadPage },
          { title: '2 · Give it to the panel', body: 'Opens ESPHome in Home Assistant. Open the panel\'s device (S1 Panel) → Configuration and paste it into Thread dataset; spaces or line breaks in the copy are fine. The dataset contains the network key: paste it only there. It is never shown again.', link: esphome },
          { title: '3 · Watch it join', body: 'The panel\'s Radio status says “joined … as a child”, then “as a router” once the network promotes it, which takes a few minutes.' },
        ],
      },
    };
    const plan = plans[mode];
    if (!plan) return false;
    // Helper v3 firewall: the radio's ports only open to Home Assistant, once it has added the panel.
    if ((mode === 'zigbee_tcp' || mode === 'thread') && state?.radio.allowed === '') {
      plan.steps.unshift({ title: 'First · Add the panel to Home Assistant', body: 'Only Home Assistant can reach the radio, and the panel learns its address when Home Assistant adds the panel. If you haven\'t yet: in Home Assistant open Settings → Devices & services, and add the S1 Panel shown under Discovered (ESPHome). Until then the radio refuses every connection.',
        link: 'https://my.home-assistant.io/redirect/integrations/' });
    }
    $('#radio-list').hidden = true;
    const box = $('#radio-next');
    box.innerHTML = '';
    box.hidden = false;
    document.querySelector<HTMLElement>('[data-s="app"] h2')!.textContent = plan.title;
    document.querySelector<HTMLElement>('[data-s="app"] .body')!.textContent = plan.lead;
    for (const st of plan.steps) {
      const row = document.createElement('div');
      row.className = 'row';
      row.innerHTML = '<i class="dot sm"></i><div class="t"><b></b><span></span></div>';
      row.querySelector('b')!.textContent = st.title;
      row.querySelector('span')!.textContent = st.body;
      if (st.kv) {
        const dl = document.createElement('dl');
        dl.className = 'kv';
        for (const [k, v, copy] of st.kv) {
          const dt = document.createElement('dt'); dt.textContent = k;
          const dd = document.createElement('dd');
          if (copy) {
            const code = document.createElement('code'); code.textContent = v;
            const c = document.createElement('button'); c.className = 'copy'; c.type = 'button'; c.textContent = 'Copy';
            c.onclick = async () => {
              try { await navigator.clipboard.writeText(v); c.textContent = 'Copied'; } catch { c.textContent = 'Select it'; }
              setTimeout(() => { c.textContent = 'Copy'; }, 1600);
            };
            dd.append(code, c);
          } else dd.textContent = v;
          dl.append(dt, dd);
        }
        row.querySelector('.t')!.append(dl);
      }
      if (st.link) {
        const a = document.createElement('a');
        a.className = 'btn'; a.target = '_blank'; a.rel = 'noopener'; a.href = st.link; a.textContent = 'Open';
        a.onclick = () => { row.querySelector('.dot')!.className = 'dot sm done'; };
        row.append(a);
      }
      box.append(row);
    }
    btn.textContent = 'Finish';
    btn.disabled = false;
    btn.onclick = () => { void this.setup?.close(); this.showDone(this.ip, this.wifiSsid); };
    return true;
  }

  private async finishApp() {
    const btn = $('#finish') as HTMLButtonElement;
    btn.disabled = true;
    this.adbRow('busy', 'Installing S1 Kiosk', 'Copying the app to the panel', 'Working');
    $('#finish-hint').textContent = 'About a minute';
    try {
      const res = await this.flow!.setUpApp(this.panel!, {
        timezone: ($('#opt-tz') as HTMLSelectElement).value || undefined,
        locale: ($('#opt-locale') as HTMLSelectElement).value || undefined,
        clock24: this.clock24,
        keyboard: ($('#opt-kb') as HTMLInputElement).checked,
        // A development build on the panel: Android needs it removed first (switch.ts). Ask, with
        // what will and won't be kept on this panel.
        confirmSwitch: async (plan) => {
          this.adbRow('busy', 'Switching S1 Kiosk to this version', 'Waiting for you to confirm', 'Confirm');
          const ok = window.confirm(`${describeSwitch(plan)}\n\nSwitch now?`);
          if (ok) this.adbRow('busy', 'Switching S1 Kiosk to this version', plan.root ? 'Keeping its data · about a minute' : 'About a minute', 'Working');
          return ok;
        },
      });
      const sw = res.switched;
      this.adbRow('ok', sw ? 'S1 Kiosk switched to this version' : 'S1 Kiosk installed',
        sw ? `${sw.dataKept ? 'Settings kept' : 'Settings reset'} · ${sw.wifi === 'lost' ? 'Wi-Fi needs its password again' : 'Wi-Fi kept'}` : 'Set as the home screen', 'Done');
      $('#hands').hidden = true;
      this.ip = res.ip;
      this.wifiHint = sw?.wifi === 'lost'
        ? `Switching versions made the panel forget ${sw.ssid ? `"${sw.ssid}"` : 'its Wi-Fi'}. Pick it again and type its password.`
        : undefined;
      await this.wifiStep();
    } catch (e) {
      this.fail(3, e);
    }
  }

  private showDone(ip?: string, wifi?: string) {
    const r = this.result;
    $('#done-title').textContent = 'You\'re all set';
    $('#done-body').textContent = (wifi
      ? `The panel is on ${wifi}. On the panel, add it to Home Assistant and sign in; Home Assistant also finds it by itself as an ESPHome device.`
      : 'On the panel, connect to Wi-Fi and sign in to Home Assistant. Home Assistant then finds the panel by itself as an ESPHome device'
        + (ip ? ` (${ip}, port 6053).` : '.'));
    $('#bk').hidden = !r;
    if (r) {
      const total = r.meta.files.reduce((n, f) => n + f.size, 0);
      $('#bk-info').textContent = `${r.meta.id}.zip · ${r.meta.files.length} partitions · ${fmtSize(total)}${r.meta.bootWasPatched ? ' · from an already-installed panel' : ' · original software'}`;
      $('#bk-hint').textContent = r.meta.bootWasPatched
        ? 'This panel already had S1 Unleashed, so this backup is not the original software. Keep the backup from your first install.'
        : 'Keep this first backup: it\'s the only copy of the original software. It is also kept in this browser.';
    }
    $('#done-undo').hidden = false;
    this.show('done');
  }

  private downloadBackup() {
    const r = this.result;
    if (!r) return;
    const a = document.createElement('a');
    a.href = URL.createObjectURL(backupZip(r.meta, r.files));
    a.download = `${r.meta.id}.zip`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 60_000);
  }

  // ---- undo -----------------------------------------------------------------------------------
  private async openUndo() {
    this.mode = 'undo';
    this.undoFiles = undefined;
    const go = $('#undo-go') as HTMLButtonElement;
    go.disabled = true;
    const browserRadio = document.querySelector<HTMLInputElement>('input[name=src][value=browser]')!;
    const fileRadio = document.querySelector<HTMLInputElement>('input[name=src][value=file]')!;
    let orig: BackupMeta | undefined;
    try { orig = await this.backups.original(); } catch { /* no IndexedDB */ }
    $('#undo-browser-l').hidden = !orig;
    if (orig) $('#undo-browser-info').textContent = `${orig.id} · ${orig.files.length} partitions · original software`;
    const pick = async () => {
      go.disabled = true;
      this.undoFiles = undefined;
      if (browserRadio.checked && orig) {
        const files = await this.backups.files(orig);
        this.undoFiles = new Map(files.map((f) => [f.part, f.data]));
        go.disabled = false;
      } else if (fileRadio.checked) {
        ($('#undo-file') as HTMLInputElement).click();
      }
    };
    browserRadio.onchange = fileRadio.onchange = () => void pick();
    fileRadio.onclick = () => { if (fileRadio.checked) ($('#undo-file') as HTMLInputElement).click(); };
    ($('#undo-file') as HTMLInputElement).onchange = async (ev) => {
      const f = (ev.target as HTMLInputElement).files?.[0];
      if (!f) return;
      $('#undo-file-info').textContent = `Checking ${f.name}…`;
      try {
        const b = await readBackupZip(f);
        this.undoFiles = b.files;
        $('#undo-file-info').textContent = `${b.id} · ${b.files.size} partitions · checked`;
        go.disabled = false;
      } catch (e) {
        $('#undo-file-info').textContent = (e as Error).message;
      }
    };
    browserRadio.checked = !!orig;
    fileRadio.checked = !orig;
    if (orig) void pick();
    for (const r of document.querySelectorAll<HTMLInputElement>('input[name=how]')) r.onchange = () => this.renderUndoOrder();
    $('#undo-note').hidden = true;
    this.appsGivenBack = false;
    this.renderUndoOrder();
    this.show('undo');
  }

  private appsGivenBack = false;
  private undoPath(): 'factory' | 'keep' {
    return document.querySelector<HTMLInputElement>('input[name=how]:checked')?.value === 'keep' ? 'keep' : 'factory';
  }

  /** The steps of the chosen undo, in order, with where the user is. */
  private renderUndoOrder() {
    const keep = this.undoPath() === 'keep';
    const steps: [string, string][] = keep
      ? [
          ['Give the panel back to Aqara\'s apps', 'With the panel running normally and plugged in. This page removes S1 Kiosk and turns Aqara\'s launcher, voice, updates and radio back on.'],
          ['Restore the original software', 'Then put the panel in FEL mode. This page writes its own boot, verified-boot and splash partitions back from the backup.'],
        ]
      : [
          ['Restore the original software', 'Put the panel in FEL mode. This page writes its own boot, verified-boot and splash partitions back from the backup.'],
          ['Factory reset the panel', 'Last, on the panel: swipe down from the top, tap Settings on the S1 Kiosk notification, then Advanced settings → Factory reset. That removes S1 Kiosk and everything set up on the panel. A factory reset alone does not restore the software, so restore first.'],
        ];
    const at = keep && this.appsGivenBack ? 1 : 0;
    const ol = $('#undo-order');
    ol.innerHTML = '';
    steps.forEach(([b, sub], i) => {
      const li = document.createElement('li');
      li.className = i < at ? 'done' : i === at ? 'now' : '';
      li.innerHTML = '<i class="n"></i><div class="t"><b></b><span></span></div><em></em>';
      li.querySelector('.n')!.textContent = i < at ? '' : String(i + 1);
      li.querySelector('b')!.textContent = b;
      li.querySelector('span')!.textContent = sub;
      li.querySelector('em')!.textContent = i < at ? 'Done' : i === at ? 'Now' : 'Then';
      ol.append(li);
    });
    $('#undo-go').textContent = keep && !this.appsGivenBack ? 'Give back Aqara\'s apps' : 'Continue to FEL mode';
  }

  private undoNote(kind: 'busy' | 'ok' | 'err', title: string, sub: string) {
    const n = $('#undo-note');
    n.hidden = false;
    n.className = `well note${kind === 'err' ? ' err' : ''}`;
    n.querySelector('.dot')!.className = `dot sm${kind === 'ok' ? ' done' : kind === 'busy' ? ' spin' : ''}`;
    $('#undo-note-t').textContent = title;
    $('#undo-note-s').textContent = sub;
  }

  private async undoContinue() {
    if (!this.undoFiles) return;
    const go = $('#undo-go') as HTMLButtonElement;
    if (this.undoPath() === 'keep' && !this.appsGivenBack) {
      go.disabled = true;
      this.undoNote('busy', 'Giving the panel back to Aqara\'s apps', 'In the browser\'s list, pick QUAD-CORE R818 EVB2 (the panel)');
      try {
        this.key ??= await loadOrCreateAdbKey();
        const panel = (await webUsbDevices.knownAdb(this.key)) ?? (await webUsbDevices.requestAdb(this.key));
        await giveBackApps(s1Plus.backup, this.undoFiles, () =>
          unprovision(panel, (s, d) => { this.undoNote('busy', 'Giving the panel back to Aqara\'s apps', s || d || ''); }));
        this.appsGivenBack = true;
        this.renderUndoOrder();
        this.undoNote('ok', 'Aqara\'s apps are back', 'Next: restore the original software. Click Continue, then put the panel in FEL mode.');
      } catch (e) {
        if (e instanceof BackupRefused) {
          this.undoNote('err', 'This backup can\'t undo the install', `${e.message}. Nothing on the panel was changed.`);
        } else {
          const msg = e instanceof DOMException && e.name === 'NotFoundError' ? 'Nothing was picked.' : (e as Error).message;
          this.undoNote('err', 'Could not reach the panel over adb', `${msg} Is it running and plugged in? Then try again, or choose "Back to how it came from Aqara".`);
        }
      } finally {
        go.disabled = false;
      }
      return;
    }
    this.show('fel');
  }

  private async runRestore(fb: Fastboot) {
    const parts = s1Plus.backup.restore.filter((p) => this.undoFiles!.has(p));
    const total = parts.reduce((n, p) => n + this.undoFiles!.get(p)!.length, 0);
    const before = (part: string) => parts.slice(0, parts.indexOf(part)).reduce((n, p) => n + this.undoFiles!.get(p)!.length, 0);
    await restoreFromBackup(s1Plus.backup, fb, this.undoFiles!, (l) => this.log(`${new Date().toTimeString().slice(0, 8)} ${l}`), (part, phase, d, t) => {
      const done = before(part);
      const write = (done + (phase === 'write' ? d : t)) / total;
      const verify = (done + (phase === 'verify' ? d : 0)) / total;
      this.progress({
        overall: 0.05 + 0.93 * (write + verify) / 2, phase: phase === 'write' ? 'flash' : 'verify',
        phasePct: phase === 'write' ? write : verify, title: 'Restoring the original software',
        sub: phase === 'write' ? `Writing ${part}` : `Reading ${part} back to check it`, bars: { flash: write, verify },
      });
    });
    this.result = undefined;
    $('#done-title').textContent = 'The original software is back';
    $('#done-body').textContent = this.undoPath() === 'keep'
      ? 'The panel is restarting with Aqara\'s software and apps. Your settings and Wi-Fi are as they were.'
      : 'One last step, on the panel: swipe down from the top, tap Settings on the S1 Kiosk notification, then Advanced settings → Factory reset. After that it is exactly as it came from Aqara.';
    $('#bk').hidden = true;
    $('#bk-hint').textContent = 'Keep your backup in case you want to install again later.';
    $('#done-undo').hidden = true;
    this.show('done');
  }

  // ---- errors ---------------------------------------------------------------------------------
  private felNotFound() {
    this.errAt = 1;
    this.showError('Panel not found in FEL mode', 'The browser didn\'t see a panel in recovery mode. Nothing has been written to it.', [
      ['Did the screen light up?', 'Then it started normally. Unplug, hold the board button first, then plug in. Keep holding for 10 seconds.'],
      ['Try another cable or port.', 'Some USB-C cables only charge. Plug straight in, not through a hub.'],
      ['Right button?', 'It\'s the small one on the board, not the reset button on the case.'],
      ['Windows or Linux?', 'Check the Zadig driver or the udev rule from step 1.'],
    ], '');
  }

  private fail(step: number, e: unknown) {
    this.errAt = step;
    const msg = e instanceof Error ? e.message : String(e);
    console.error(e);
    const written = /flashed and verified|restored and verified/.test($('#log').textContent ?? '');
    const tips: [string, string][] = written
      ? [
          ['Don\'t unplug yet if it was writing.', 'If a write failed its check, the panel may not start. Put it in FEL mode again and use Undo with your backup.'],
          ['Your backup is safe.', 'It is kept in this browser, and you can download it from the undo screen.'],
        ]
      : [
          ['Nothing was written to the panel.', 'Everything so far ran from the panel\'s memory. Unplug it, then start again from FEL mode.'],
          ['Try another cable or port.', 'Plug straight into this computer, not through a hub or dock.'],
          ['Is adb running on this computer?', 'Only one program can hold the panel. Run "adb kill-server" and try again.'],
        ];
    this.showError(written ? 'Something went wrong while writing' : 'Something went wrong', written
      ? 'Read the steps below before you unplug anything.'
      : 'The installer stopped before changing anything on the panel.', tips, msg);
  }

  private showError(title: string, body: string, tips: [string, string][], msg: string) {
    $('#err-label').textContent = `Step ${this.errAt + 1} · ${STEPS[this.mode][this.errAt]?.name ?? ''}`;
    $('#err-title').textContent = title;
    $('#err-body').textContent = body;
    $('#err-tips').innerHTML = '';
    tips.forEach(([b, s], i) => {
      const li = document.createElement('li');
      li.innerHTML = `<i>${i + 1}</i><span><b></b></span>`;
      li.querySelector('b')!.textContent = b;
      li.querySelector('span')!.append(s);
      $('#err-tips').append(li);
    });
    $('#err-msg').textContent = msg;
    $('#err-msg').hidden = !msg;
    this.show('error');
  }
}

export function start() {
  if (still) document.documentElement.classList.add('still');
  new Installer().init();
}
