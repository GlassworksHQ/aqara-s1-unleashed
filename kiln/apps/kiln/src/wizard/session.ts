// One install or undo, from the first click to Done: kiln-core's InstallFlow with a frame's recipe,
// then the app's setup over adb. All hardware work is here; screens only read the store and call
// these methods. Port of kiln/src/app/ui.ts without the DOM.
import type { AdbKey, PanelShell } from '@glassworks/adb';
import type { Fastboot } from '@glassworks/fastboot';
import type { FelDevice } from '@glassworks/fel';
import {
  IMPROV_SOCKET, ImprovSerialClient, State as ImprovState, describeSwitch, unprovision,
  type AssetName, type Provisioned, type SetUpOptions,
} from '@glassworks/aqara-s1-plus-installer';
import {
  BackupRefused, InstallFlow, backupZip, checkRestorable, fmtSize, readBackupZip, restoreFromBackup,
  type BackupFile, type BackupMeta, type BackupStore, type Devices, type Recipe,
} from '@glassworks/kiln-core';
import { SETUP_SOCKET, SetupChannel, validHaUrl, type SetupState } from '@glassworks/setup-channel';
import { radioPlan, type FrameUi } from '../frames/aqara-s1-plus';
import { explain } from './explain';
import { AskBridge, flowUi } from './flowUi';
import { initial, reduce, type Action, type Flow, type Setup, type WizardState } from './state';

/** A tiny store: the session writes, React reads with useSyncExternalStore. */
export class Store {
  private listeners = new Set<() => void>();
  constructor(public state: WizardState) {}
  dispatch = (a: Action) => {
    this.state = reduce(this.state, a);
    for (const l of this.listeners) l();
  };
  subscribe = (l: () => void) => {
    this.listeners.add(l);
    return () => { this.listeners.delete(l); };
  };
  get = () => this.state;
}

export interface SessionDeps {
  recipe: Recipe<AssetName, SetUpOptions, Provisioned>;
  frame: FrameUi;
  devices: Devices;
  /** Must call navigator.usb.requestDevice() before its first await. */
  requestFel(): Promise<FelDevice>;
  asset(name: AssetName, onProgress?: (done: number, total: number) => void): Promise<Uint8Array>;
  hasAsset(name: AssetName): Promise<boolean>;
  backups: Pick<BackupStore, 'save' | 'original' | 'files'>;
  loadKey(): Promise<AdbKey>;
  sleep?: (ms: number) => Promise<void>;
  /** Opens a tab; must run inside the click (popup blockers). */
  openTab?: (url: string) => Window | null;
  confirm?: (text: string) => boolean;
  download?: (name: string, blob: Blob) => void;
  /** The simulated panel needs no wait for the app's socket. */
  simulated?: boolean;
}

export interface Settings { locale?: string; timezone?: string; clock24: boolean; keyboard: boolean }

const notPicked = (e: unknown) => e instanceof DOMException && e.name === 'NotFoundError';

export class Session {
  readonly store: Store;
  readonly asks: AskBridge;
  private readonly ui;
  private key?: AdbKey;
  private keyReady: Promise<AdbKey>;
  private flow?: InstallFlow<AssetName, SetUpOptions, Provisioned>;
  private result?: { meta: BackupMeta; files: BackupFile[] };
  private panel?: PanelShell;
  private improv?: ImprovSerialClient;
  private setup?: SetupChannel;
  private undoFiles?: Map<string, Uint8Array>;
  /** The backup in this browser, when Undo uses it: Undo offers it as a download. */
  private undoBackup?: { meta: BackupMeta; files: BackupFile[] };
  private ip?: string;
  private wifiSsid?: string;
  private wifiHint?: string;
  private haTimer?: ReturnType<typeof setInterval>;
  private readonly sleep: (ms: number) => Promise<void>;
  private running?: string;
  private began = false;
  private closed = false;

  /**
   * Lets go of everything this session holds: the Home Assistant poll, the app's setup channel and
   * Improv client, and the panel's adb connection. The registry calls it when a new session replaces
   * this one; nothing here may keep polling a panel nobody looks at.
   */
  close(): void {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.haTimer);
    const quiet = (p?: Promise<unknown>) => { void p?.catch(() => {}); };
    quiet(this.setup?.close());
    quiet(this.improv?.close());
    quiet((this.panel as { close?: () => Promise<void> } | undefined)?.close?.());
    this.setup = this.improv = this.panel = undefined;
  }

  get isClosed(): boolean { return this.closed; }

  /** A flow has begun on this session (FEL picked, or a panel connected): a new one gets a new session. */
  get started(): boolean { return this.began; }

  constructor(private readonly d: SessionDeps, state: WizardState = initial()) {
    this.store = new Store(state);
    this.asks = new AskBridge(this.store.dispatch);
    this.ui = flowUi(this.store.dispatch, this.asks);
    this.sleep = d.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
    // Loaded up front, so a later click can call requestDevice() without waiting on IndexedDB first.
    this.keyReady = d.loadKey().then((k) => (this.key = k));
    this.keyReady.catch(() => {});
  }

  private get s() { return this.store.state; }
  private dispatch(a: Action) { this.store.dispatch(a); }
  private setupPhase(setup: Setup) { this.dispatch({ type: 'setup', setup }); }

  private getFlow(key: AdbKey) {
    this.flow ??= new InstallFlow(this.d.recipe, {
      asset: (n, p) => this.d.asset(n, p),
      hasAsset: (n) => this.d.hasAsset(n),
      backups: this.d.backups,
      key,
      devices: this.d.devices,
      sleep: this.d.sleep,
    }, this.ui);
    return this.flow;
  }

  private fail(at: number, e: unknown, installed = false) {
    console.error(e);
    this.dispatch({ type: 'fail', failure: explain(e, at, this.s.wrote, installed) });
  }

  /**
   * Every button that starts work calls through here: a second click while one action runs does
   * nothing (a double-clicked Connect used to start a second flow). `run` starts inside the click,
   * so a requestDevice() at its start still counts as part of it.
   */
  click(name: string, run: () => Promise<void> | void): Promise<void> {
    if (this.running) return Promise.resolve();
    this.running = name;
    this.dispatch({ type: 'busy', busy: name });
    let p: Promise<void>;
    try { p = Promise.resolve(run()); } catch (e) { p = Promise.reject(e); }
    return p.finally(() => {
      this.running = undefined;
      this.dispatch({ type: 'busy', busy: undefined });
    });
  }

  // ---- connect, install, restore --------------------------------------------------------------

  /** The Connect button. The browser's picker opens first, inside the click. */
  connectFel(): Promise<void> {
    const picked = this.d.requestFel();
    return this.afterFel(picked);
  }

  private async afterFel(picked: Promise<FelDevice>) {
    let fel: FelDevice;
    try {
      fel = await picked;
    } catch (e) {
      this.fail(1, e);
      return;
    }
    const flowKind: Flow = this.s.flow;
    this.began = true;
    this.dispatch({ type: 'start', flow: flowKind, now: Date.now() });
    try {
      const flow = this.getFlow(await this.keyReady);
      await flow.bootFromFel(fel);
      const fb = await flow.connectFastboot();
      if (flowKind === 'undo') return await this.restore(fb);
      this.result = await flow.install(fb);
      void this.waitForPanel();
    } catch (e) {
      this.fail(2, e);
    }
  }

  private async waitForPanel() {
    this.setupPhase({ phase: 'waiting', note: 'The panel is starting its new software. The first start takes a minute or two.' });
    try {
      this.panel = await this.flow!.connectAdb();
      this.setupPhase({ phase: 'settings', update: false });
    } catch (e) {
      this.fail(3, e, true);
    }
  }

  private async restore(fb: Fastboot) {
    const files = this.undoFiles!;
    const parts = this.d.recipe.backup.restore.filter((p) => files.has(p));
    const total = parts.reduce((n, p) => n + files.get(p)!.length, 0);
    const before = (part: string) => parts.slice(0, parts.indexOf(part)).reduce((n, p) => n + files.get(p)!.length, 0);
    await restoreFromBackup(this.d.recipe.backup, fb, files, (l) => this.ui.note(l), (part, phase, done, t) => {
      const at = before(part);
      const write = (at + (phase === 'write' ? done : t)) / total;
      const verify = (at + (phase === 'verify' ? done : 0)) / total;
      this.ui.progress({
        overall: 0.05 + 0.93 * (write + verify) / 2, phase: phase === 'write' ? 'flash' : 'verify',
        phasePct: phase === 'write' ? write : verify, title: 'Putting the original software back',
        sub: phase === 'write' ? `Writing ${part}` : `Reading ${part} back to check it`, bars: { flash: write, verify },
      });
    });
    this.ui.progress({ overall: 0.98, phase: 'verify', phasePct: 1, title: 'Restarting the panel', sub: '' });
    this.result = undefined;
    this.dispatch({
      type: 'done', done: {
        title: 'The original software is back.',
        body: this.s.undo.depth === 'keep'
          ? 'It is restarting with Aqara\'s software and apps. Your settings and Wi-Fi are as they were.'
          : `One last step, on the panel: swipe down, tap Settings on the ${this.d.frame.app} notification, then Advanced settings → Factory reset.`,
      },
    });
  }

  // ---- an installed panel: routes and the app's setup ------------------------------------------

  /** Connects to an installed, running panel over adb; asks for a click if this browser hasn't been allowed. */
  async connectPanel(): Promise<boolean> {
    if (this.panel) return true;
    this.began = true;
    this.dispatch({ type: 'go', screen: 'setup', flow: 'install' });
    this.setupPhase({ phase: 'waiting', note: 'Connecting to the panel. It should be running and plugged in.' });
    try {
      const key = await this.keyReady;
      this.panel = (await this.d.devices.knownAdb(key).catch(() => undefined))
        ?? (await this.asks.open('connect', () => this.d.devices.requestAdb(key), new AbortController().signal));
      return true;
    } catch (e) {
      this.fail(3, e, true);
      return false;
    }
  }

  /** #/resume, #/wifi, #/home-assistant, #/radio, #/undo, #/install. */
  async route(r: string): Promise<void> {
    switch (r) {
      case 'install': this.dispatch({ type: 'go', screen: 'connect', flow: 'install' }); return;
      case 'undo': return this.openUndo();
      case 'resume': if (await this.connectPanel()) this.setupPhase({ phase: 'settings', update: true }); return;
      case 'wifi': if (await this.connectPanel()) await this.wifi(); return;
      case 'home-assistant': if (await this.connectPanel()) await this.homeAssistant(); return;
      case 'radio': if (await this.connectPanel()) await this.radio(); return;
    }
  }

  /** Settings → install (or update) the app. */
  async installApp(opts: Settings): Promise<void> {
    const update = this.s.setup.phase === 'settings' && this.s.setup.update;
    this.setupPhase({ phase: 'settings', update, busy: `Installing ${this.d.frame.app}. About a minute.` });
    try {
      const res = await this.getFlow(await this.keyReady).setUpApp(this.panel!, {
        timezone: opts.timezone, locale: opts.locale, clock24: opts.clock24, keyboard: opts.keyboard,
        // A development build on the panel: Android needs it removed first. Ask, saying what is kept.
        confirmSwitch: async (plan) => (this.d.confirm ?? window.confirm)(`${describeSwitch(plan)}\n\nSwitch now?`),
      });
      const sw = res.switched;
      this.ip = res.ip;
      this.wifiHint = sw?.wifi === 'lost'
        ? `Switching versions made the panel forget ${sw.ssid ? `"${sw.ssid}"` : 'its Wi-Fi'}. Pick it again and type its password.`
        : undefined;
      await this.wifi();
    } catch (e) {
      this.setupPhase({ phase: 'settings', update, error: (e as Error).message });
    }
  }

  /** Waits until the app listens on `socket` (it cold-starts after the install), then connects. */
  private async waitForSocket(socket: string, timeoutMs = 90_000): Promise<boolean> {
    if (this.d.simulated) return true;
    const name = socket.replace(/^localabstract:/, '@');
    const until = Date.now() + timeoutMs;
    while (Date.now() < until) {
      const r = await this.panel!.run(`grep -c ' ${name}$' /proc/net/unix`).catch(() => ({ out: '0', code: 1 }));
      if (Number.parseInt(r.out.trim(), 10) > 0) return true;
      await this.sleep(1500);
    }
    this.ui.note(`${name} never started listening on the panel`);
    return false;
  }

  private async openImprov(): Promise<ImprovSerialClient | undefined> {
    const open = this.panel?.openStream?.bind(this.panel);
    if (!open || !(await this.waitForSocket(IMPROV_SOCKET))) return undefined;
    for (let i = 0; i < 5; i++) {
      try {
        const c = new ImprovSerialClient(await open(IMPROV_SOCKET));
        await c.requestState(5000);
        return c;
      } catch (e) {
        this.ui.note(`Improv over adb, try ${i + 1}: ${(e as Error).message ?? e}`);
        await this.sleep(1500);
      }
    }
    return undefined;
  }

  /** Wi-Fi from this computer, over Improv Serial through adb. */
  async wifi(): Promise<void> {
    this.setupPhase({ phase: 'wifi', status: `Starting ${this.d.frame.app} on the panel…`, hint: this.wifiHint });
    this.improv = await this.openImprov();
    if (!this.improv) {
      this.setupPhase({ phase: 'wifi', networks: [], unreachable: true, status: `Couldn't reach ${this.d.frame.app} on the panel.` });
      return;
    }
    const already = this.improv.state === ImprovState.Provisioned;
    this.setupPhase({ phase: 'wifi', status: 'Looking for networks the panel can see…', hint: already ? 'The panel is already on Wi-Fi. Pick another network to change it, or skip.' : this.wifiHint });
    let networks: { ssid: string; rssi: number; secure: boolean }[] = [];
    try { networks = await this.improv.scan(); } catch { /* typed by hand then */ }
    const s = this.s.setup;
    this.setupPhase({
      phase: 'wifi', networks, hint: s.phase === 'wifi' ? s.hint : undefined,
      status: networks.length ? `${networks.length} in range of the panel` : 'None found. Type the name.',
    });
  }

  async joinWifi(ssid: string, psk: string): Promise<void> {
    const base = this.s.setup.phase === 'wifi' ? this.s.setup : { phase: 'wifi' as const, status: '' };
    const step = (st: 'sent' | 'join' | 'ok' | 'err', error?: string) => this.setupPhase({ ...base, joining: { ssid, step: st, error } });
    if (!ssid) { step('err', 'Pick or type a network.'); return; }
    step('sent');
    const succeeded = () => {
      this.ui.note(`panel connected to Wi-Fi ${ssid}`);
      void this.improv?.close();
      this.wifiSsid = ssid;
      step('ok');
    };
    try {
      await this.improv!.provision(ssid, psk, () => step('join'));
      succeeded();
    } catch (e) {
      // The app can restart right after joining (it found Home Assistant), which cuts this connection
      // before the result arrives. Reconnect and ask: Provisioned means it's on Wi-Fi.
      this.ui.note(`Wi-Fi: ${(e as Error).message}; asking the panel again`);
      const again = await this.openImprov();
      const state = again ? await again.requestState().catch(() => 0) : 0;
      if (again && state === ImprovState.Provisioned) { this.improv = again; succeeded(); return; }
      step('err', (e as Error).message);
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
      } catch { await this.sleep(1000); }
    }
    return undefined;
  }

  /** Runs a setup-channel request; saving a new Home Assistant address can restart the app, so reconnect once and retry. */
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

  private haState(s: SetupState, extra: { note?: string; error?: string; busy?: boolean; signingIn?: boolean } = {}) {
    this.ip = s.ip ?? this.ip;
    this.setupPhase({ phase: 'ha', ha: s.ha, ...extra });
  }

  /** Home Assistant: the address (what the panel found), sign-in on the panel's own page. */
  async homeAssistant(): Promise<void> {
    this.setup ??= await this.openSetup();
    if (!this.setup) return this.finish(); // older app: the panel's own setup does the rest
    this.setup.onState = (s) => {
      const cur = this.s.setup;
      if (cur.phase === 'ha') this.haState(s, { note: cur.note, signingIn: cur.signingIn && !s.ha.signedIn });
    };
    this.haState(this.setup.state!);
    clearInterval(this.haTimer);
    this.haTimer = setInterval(() => {
      if (this.closed || this.s.setup.phase !== 'ha') { clearInterval(this.haTimer); return; }
      void this.withSetup((x) => x.getState()).catch(() => {});
    }, 3000);
  }

  /** Sign in: the tab opens inside the click, and is pointed at the panel's sign-in page once the address is saved. */
  signIn(url: string): void {
    const s = this.s.setup;
    if (s.phase !== 'ha') return;
    if (!validHaUrl(url)) { this.haState({ ...this.setup!.state!, ha: s.ha }, { error: 'That doesn\'t look like a web address (http://… or https://…).' }); return; }
    const tab = (this.d.openTab ?? ((u) => window.open(u, '_blank')))('about:blank');
    this.setupPhase({ ...s, error: undefined, signingIn: true, note: 'Sign in on the tab that opened. This page notices when you are done.' });
    void (async () => {
      try {
        const st = url === this.setup?.state?.ha.url ? this.setup.state : await this.withSetup((x) => x.setHa(url));
        if (!st.ha.signinUrl) throw new Error('the panel has no sign-in page yet (is it on Wi-Fi?)');
        if (tab) tab.location.href = st.ha.signinUrl; else window.open(st.ha.signinUrl, '_blank');
      } catch (e) {
        tab?.close();
        const cur = this.s.setup;
        if (cur.phase === 'ha') this.setupPhase({ ...cur, note: undefined, signingIn: false, error: (e as Error).message });
      }
    })();
  }

  async saveHa(url: string): Promise<void> {
    const s = this.s.setup;
    if (s.phase !== 'ha') return;
    if (url && url !== this.setup?.state?.ha.url) {
      if (!validHaUrl(url)) { this.setupPhase({ ...s, error: 'That doesn\'t look like a web address (http://… or https://…).' }); return; }
      this.setupPhase({ ...s, busy: true, error: undefined });
      try { await this.withSetup((x) => x.setHa(url)); } catch (e) { this.setupPhase({ ...s, busy: false, error: (e as Error).message }); return; }
    }
    await this.radio();
  }

  /** The radio role. */
  async radio(): Promise<void> {
    this.setup ??= await this.openSetup();
    if (!this.setup) { this.fail(3, new Error(`${this.d.frame.app} isn't answering. Is it installed and running?`), true); return; }
    const st = await this.withSetup((x) => x.getState());
    this.setupPhase({
      phase: 'radio', radio: st.radio,
      note: st.radio.helper ? `Now: ${st.radio.status}` : 'Only Off is available: this panel has no root helper for the radio.',
    });
  }

  async applyRadio(mode: string): Promise<void> {
    const s = this.s.setup;
    if (s.phase !== 'radio') return;
    if (mode !== this.setup!.state!.radio.mode) {
      this.setupPhase({ ...s, busy: true, note: 'Switching the radio…', error: undefined });
      try {
        const r = await this.withSetup((x) => x.setRadio(mode));
        this.setupPhase({ ...s, radio: r.radio, busy: false, note: r.radio.status });
      } catch (e) {
        this.setupPhase({ ...s, busy: false, error: (e as Error).message });
        return;
      }
    }
    const st = this.setup!.state!;
    const plan = radioPlan(mode, st.ip ?? this.ip ?? '<panel IP>', st.ha.url ?? '', st.radio.allowed);
    if (plan) { this.setupPhase({ phase: 'radio-next', plan }); return; }
    this.finish();
  }

  /** Done, with the backup to download. */
  finish(): void {
    clearInterval(this.haTimer);
    void this.setup?.close();
    const r = this.result;
    const wifi = this.wifiSsid;
    const total = r ? r.meta.files.reduce((n, f) => n + f.size, 0) : 0;
    this.dispatch({
      type: 'done', done: {
        title: 'Done.',
        body: wifi
          ? `The panel is on ${wifi}. Home Assistant finds it by itself as an ESPHome device.`
          : `On the panel, join Wi-Fi and sign in to Home Assistant. Home Assistant then finds it as an ESPHome device${this.ip ? ` (${this.ip}, port 6053)` : ''}.`,
        backup: r && {
          name: `${r.meta.id}.zip`,
          info: `${r.meta.files.length} partitions · ${fmtSize(total)}`,
          hint: r.meta.bootWasPatched
            ? 'This panel already had the software, so this backup isn\'t the original. Keep the one from your first install.'
            : 'Original software, backed up. Keep it: it\'s also kept in this browser.',
        },
      },
    });
  }

  downloadBackup(): void {
    const r = this.result;
    if (!r) return;
    const blob = backupZip(r.meta, r.files);
    (this.d.download ?? saveBlob)(`${r.meta.id}.zip`, blob);
  }

  // ---- undo -----------------------------------------------------------------------------------

  async openUndo(): Promise<void> {
    this.undoFiles = undefined;
    this.undoBackup = undefined;
    this.dispatch({ type: 'go', screen: 'undo', flow: 'undo' });
    this.dispatch({ type: 'undo', patch: { source: undefined, inBrowser: undefined, file: undefined, ready: false, appsBack: false, note: undefined } });
    let orig: BackupMeta | undefined;
    try { orig = await this.d.backups.original(); } catch { /* no IndexedDB */ }
    if (!orig) return;
    this.dispatch({ type: 'undo', patch: { inBrowser: `${orig.id} · ${orig.files.length} partitions` } });
    await this.useBrowserBackup(orig);
  }

  private async useBrowserBackup(meta: BackupMeta) {
    const files = await this.d.backups.files(meta);
    this.undoBackup = { meta, files };
    this.undoFiles = new Map(files.map((f) => [f.part, f.data]));
    this.dispatch({ type: 'undo', patch: { source: 'browser', ready: true } });
  }

  /** "Download this backup" on Undo: the browser's backup as the same zip Done offers. */
  downloadUndoBackup(): void {
    const b = this.undoBackup;
    if (!b) return;
    (this.d.download ?? saveBlob)(`${b.meta.id}.zip`, backupZip(b.meta, b.files));
  }

  async undoFromFile(f: File): Promise<void> {
    this.dispatch({ type: 'undo', patch: { source: 'file', ready: false, file: `Checking ${f.name}…` } });
    try {
      const b = await readBackupZip(f);
      this.undoBackup = undefined;
      this.undoFiles = b.files;
      this.dispatch({ type: 'undo', patch: { ready: true, file: `${b.id} · ${b.files.size} partitions · checked` } });
    } catch (e) {
      this.dispatch({ type: 'undo', patch: { ready: false, file: (e as Error).message } });
    }
  }

  async undoFromBrowser(): Promise<void> {
    const orig = await this.d.backups.original().catch(() => undefined);
    if (orig) await this.useBrowserBackup(orig);
  }

  /**
   * Continue: "keep its settings" first gives the panel back to Aqara's apps over adb (the picker
   * opens inside this click), then both depths go to recovery mode.
   */
  undoContinue(): Promise<void> {
    const u = this.s.undo;
    if (!this.undoFiles) return Promise.resolve();
    if (u.depth === 'keep' && !u.appsBack) {
      const key = this.key;
      if (!key) { this.dispatch({ type: 'undo', patch: { note: { kind: 'err', title: 'Not ready yet.', sub: 'Click Continue again in a moment.' } } }); return Promise.resolve(); }
      const picked = this.d.devices.requestAdb(key);
      return this.giveBack(picked, this.undoFiles);
    }
    this.dispatch({ type: 'go', screen: 'connect', flow: 'undo' });
    return Promise.resolve();
  }

  /**
   * Giving the panel back to Aqara's apps removes or disables S1 Kiosk, so it only happens for a
   * backup undo can restore (kiln-core's checkRestorable, the check the restore itself runs). A backup
   * that can't be restored stops here, with nothing on the panel changed.
   */
  private async giveBack(picked: Promise<PanelShell>, files: ReadonlyMap<string, Uint8Array>) {
    const busy = (sub: string) => this.dispatch({ type: 'undo', patch: { note: { kind: 'busy', title: 'Giving the panel back to Aqara\'s apps', sub } } });
    busy(`In the browser's list, pick ${this.d.frame.asks.connect.pick}.`);
    try {
      const panel = await picked;
      try {
        await checkRestorable(this.d.recipe.backup, files);
      } catch (e) {
        // Refused backup, or a broken plan in the frame's recipe: either way a backup or recipe
        // problem, not USB, and nothing on the panel has changed. Undoing by hand is the way on.
        const msg = String((e as Error)?.message ?? e);
        this.dispatch({ type: 'undo', patch: { note: {
          kind: 'err',
          title: e instanceof BackupRefused ? 'This backup can\'t undo the install.' : 'Kiln can\'t check this backup.',
          sub: `${msg.charAt(0).toUpperCase()}${msg.slice(1)}. Nothing on the panel was changed.`,
          link: { href: this.d.frame.help.undo, text: 'Undo by hand' },
        } } });
        return;
      }
      await unprovision(panel, (s, d) => busy(s || d || ''));
      this.dispatch({ type: 'undo', patch: { appsBack: true, note: { kind: 'ok', title: 'Aqara\'s apps are back.', sub: 'Next, put the original software back: Continue, then recovery mode.' } } });
    } catch (e) {
      const msg = notPicked(e) ? 'Nothing was picked.' : (e as Error).message;
      this.dispatch({ type: 'undo', patch: { note: { kind: 'err', title: 'Couldn\'t reach the panel over adb.', sub: `${msg} Is it running and plugged in?` } } });
    }
  }
}

function saveBlob(name: string, blob: Blob) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 60_000);
}
