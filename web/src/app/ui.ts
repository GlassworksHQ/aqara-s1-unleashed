// The installer page (index.html, design E). Renders flow.ts; all hardware work lives there.
import felImg from '../../../docs/img/fel-button.jpg?url';
import { loadOrCreateAdbKey, type AdbKey, type PanelShell } from '../adb';
import { Assets } from '../assets';
import { FelDevice } from '../fel';
import { unprovision } from '../provision';
import { BackupStore, backupZip, readBackupZip, type BackupFile, type BackupMeta } from './backup';
import { webUsbDevices } from './devices';
import { InstallFlow, fmtSize, restoreFromBackup, type FlowUi, type PartState, type Progress } from './flow';

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
    { name: 'Set up the app', done: 'Kiosk installed', cur: 'Home Assistant address' },
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

class Installer {
  private mode: Mode = 'install';
  private cur: ScreenId | null = null;
  private errAt = 1;
  private assets = new Assets();
  private backups = new BackupStore();
  private key?: AdbKey;
  private flow?: InstallFlow;
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
    $('#disc').onclick = () => this.setDetails($('#disc').getAttribute('aria-expanded') !== 'true');
    $('#bk-dl').onclick = () => this.downloadBackup();
    $('#finish').onclick = () => this.finishApp();
    $('#err-home').onclick = () => location.reload();
    $('#err-retry').onclick = () => this.show('fel');
    $('#undo-back').onclick = () => { this.mode = 'install'; this.show('welcome'); };
    $('#undo-go').onclick = () => this.undoContinue();
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    $('#tz-name').textContent = tz;
    $('#tz-hint').textContent = `${tz} · ${navigator.language}`;
    this.show('welcome');
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

  private async getFlow(): Promise<InstallFlow> {
    this.key ??= await loadOrCreateAdbKey();
    this.flow ??= new InstallFlow({
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
      ask: (kind, text, action) => this.ask(kind, text, action),
    };
  }

  private ask<T>(kind: 'fastboot' | 'adb', text: string, action: () => Promise<T>): Promise<T> {
    const card = kind === 'adb' ? $('#ask-adb') : $('#ask');
    const btn = (kind === 'adb' ? $('#ask-adb-btn') : $('#ask-btn')) as HTMLButtonElement;
    if (kind === 'fastboot') $('#ask-t').textContent = text;
    card.hidden = false;
    return new Promise<T>((resolve) => {
      btn.onclick = async () => {
        btn.disabled = true;
        try {
          const r = await action();
          card.hidden = true;
          resolve(r);
        } catch (e) {
          // picker closed, or the wrong device: let them try again
          const s = kind === 'adb' ? card.querySelector('span')! : $('#ask-s');
          s.textContent = e instanceof DOMException && e.name === 'NotFoundError' ? 'Nothing was picked. Try again.' : String((e as Error).message ?? e);
        } finally {
          btn.disabled = false;
        }
      };
    });
  }

  private resetProgress() {
    this.started = Date.now();
    $('#log').innerHTML = '';
    $('#plist').innerHTML = '';
    $('#ask').hidden = true;
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
    const C = 540.35;
    const ring = $('#ringp');
    ring.style.strokeDashoffset = String(C * (1 - p.overall));
    $('#pct').textContent = String(Math.floor(p.overall * 100));
    $('#pl').textContent = p.title;
    let sub = p.sub;
    const elapsed = (Date.now() - this.started) / 1000;
    if (p.overall > 0.08 && p.overall < 0.97 && elapsed > 20) {
      const left = (elapsed / p.overall) * (1 - p.overall);
      sub += left > 90 ? ` · about ${Math.round(left / 60)} minutes left` : left > 15 ? ' · less than a minute left' : '';
    }
    $('#ps').textContent = sub;
    const order = ['backup', 'patch', 'flash', 'verify'] as const;
    const at = p.phase === 'boot' ? -1 : order.indexOf(p.phase);
    $$('#phases > div').forEach((d, i) => {
      d.classList.toggle('cur', i === at);
      (d.querySelector('b') as HTMLElement).style.width = `${i < at ? 100 : i === at ? Math.round(p.phasePct * 100) : 0}%`;
    });
    if (p.phase === 'backup') this.setStepDetail(2, p.sub.split(' · ')[1] ? `Backing up · ${p.sub.split(' · ')[1]}` : 'Backing up');
    else if (p.phase !== 'boot') this.setStepDetail(2, p.title);
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
    const log = $('#log');
    log.append(div);
    while (log.childElementCount > 400) log.firstElementChild!.remove();
    log.scrollTop = log.scrollHeight;
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

  private async finishApp() {
    const btn = $('#finish') as HTMLButtonElement;
    btn.disabled = true;
    const url = ($('#ha') as HTMLInputElement).value.trim();
    if (url && !/^https?:\/\/[^\s'"]+$/.test(url)) {
      $('#st').textContent = 'That doesn\'t look like a web address (http://… or https://…).';
      btn.disabled = false;
      return;
    }
    this.adbRow('busy', 'Installing S1 Kiosk', 'Copying the app to the panel', 'Working');
    $('#finish-hint').textContent = 'About a minute';
    try {
      const res = await this.flow!.setUpApp(this.panel!, {
        haUrl: url || undefined,
        timezone: ($('#opt-tz') as HTMLInputElement).checked ? Intl.DateTimeFormat().resolvedOptions().timeZone : undefined,
        keyboard: ($('#opt-kb') as HTMLInputElement).checked,
      });
      this.adbRow('ok', 'S1 Kiosk installed', 'Set as the home screen', 'Done');
      this.showDone(res.ip);
    } catch (e) {
      this.fail(3, e);
    }
  }

  private showDone(ip?: string) {
    const r = this.result;
    $('#done-title').textContent = 'You\'re all set';
    $('#done-body').textContent = 'On the panel, connect to Wi-Fi and sign in to Home Assistant. Home Assistant then finds the panel by itself as an ESPHome device'
      + (ip ? ` (${ip}, port 6053).` : '.');
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
    this.show('undo');
  }

  private async undoContinue() {
    if (!this.undoFiles) return;
    const go = $('#undo-go') as HTMLButtonElement;
    go.disabled = true;
    if (($('#undo-apps') as HTMLInputElement).checked) {
      const card = $('#ask-undo');
      card.hidden = false;
      $('#ask-undo-t').textContent = 'Giving the panel back to Aqara\'s apps';
      $('#ask-undo-s').textContent = 'Pick the panel in the list';
      try {
        this.key ??= await loadOrCreateAdbKey();
        const panel = (await webUsbDevices.knownAdb(this.key)) ?? (await webUsbDevices.requestAdb(this.key));
        await unprovision(panel, (s, d) => { $('#ask-undo-s').textContent = s || d || ''; });
        $('#ask-undo-t').textContent = 'Aqara\'s apps are back';
        $('#ask-undo-s').textContent = 'Now unplug the panel and put it in FEL mode';
      } catch (e) {
        $('#ask-undo-t').textContent = 'Could not reach the panel over adb';
        $('#ask-undo-s').textContent = `${(e as Error).message}. Untick the box to restore the software only.`;
        go.disabled = false;
        return;
      }
    }
    this.show('fel');
  }

  private async runRestore(fb: Awaited<ReturnType<InstallFlow['connectFastboot']>>) {
    const total = ['boot', 'vbmeta', 'bootloader'].reduce((n, p) => n + (this.undoFiles!.get(p)?.length ?? 0), 0);
    let done = 0;
    let last = '';
    await restoreFromBackup(fb, this.undoFiles!, (l) => this.log(`${new Date().toTimeString().slice(0, 8)} ${l}`), (part, phase, d, t) => {
      if (part !== last) { if (last) done += this.undoFiles!.get(last)!.length; last = part; }
      const x = (done + d / 2 + (phase === 'verify' ? t / 2 : 0)) / total;
      this.progress({ overall: 0.05 + 0.93 * x, phase: phase === 'write' ? 'flash' : 'verify', phasePct: d / t, title: 'Restoring the original software', sub: phase === 'write' ? `Writing ${part}` : `Checking ${part}` });
    });
    this.result = undefined;
    $('#done-title').textContent = 'The original software is back';
    $('#done-body').textContent = 'The panel is restarting with Aqara\'s software. Your settings and Wi-Fi are as they were.';
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
