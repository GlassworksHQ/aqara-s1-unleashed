#!/usr/bin/env node
// Device renders for the frame cards (MIT; #144): HA's real frontend (the build Clarity pins,
// Apache-2.0) drawing the showcase dashboard (home.py) from Clarity's fake Home Assistant, captured in
// headless Chrome at each frame's screen size, written to frames/<id>/design/render.webp.
//
// Touches nothing but those files: the fake HA listens on 127.0.0.1, Chrome runs with a throwaway
// profile. Needs python3, Node 22+ (its WebSocket) and Chrome (CHROME=<path> for another one). The
// first run fetches HA's frontend (tools/perf/hafrontend.py, checked by SHA-256).
//
//   node tools/showcase/render.mjs [--variant rich|calm] [--out <dir>] [frame-id ...]
// --out writes <dir>/<frame-id>.webp instead of the frames' own files (e.g. to compare the variants).
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
// CSS px per screen px. The S1's own density is 1.5 (app/AGENTS.md); 1 is the kiosk's zoom at 67%,
// which fits a whole home on a wall panel's screen, as people set it up to show one.
const DENSITY = Number(process.env.SHOWCASE_DENSITY ?? 1);
// A frame without one screen size (generic-android) is drawn as a common 10" tablet.
const DEFAULT_DISPLAY = { width: 1920, height: 1200 };
const QUALITY = 82;

// The kiosk's own look: no sidebar, no header (app/kiosk/src/main/assets/extension/content.js), and
// HA's default theme in dark mode.
const inject = (now) => `(() => {
  // The home's NOW on the page's clock, so the clock card and the graphs' windows are the same every run.
  // It moves on only in whole minutes, so anything waiting on the time still gets there.
  const t0 = performance.now(), RealDate = Date;
  const now = () => ${now} + Math.floor((performance.now() - t0) / 60000) * 60000;
  class FixedDate extends RealDate {
    constructor(...a) { if (a.length) super(...a); else super(now()); }
    static now() { return now(); }
  }
  window.Date = FixedDate;
  try {
    localStorage.setItem('dockedSidebar', '"always_hidden"');
    localStorage.setItem('selectedTheme', JSON.stringify({ theme: 'default', dark: true }));
  } catch (e) {}
  const css = ':host { --header-height: 0px !important; } .header { display: none !important; } #view { padding-top: 0 !important; min-height: 100vh !important; }';
  setInterval(() => {
    const sr = (el) => el && el.shadowRoot;
    const ha = document.querySelector('home-assistant');
    const main = sr(ha) && sr(ha).querySelector('home-assistant-main');
    const panel = sr(main) && sr(main).querySelector('ha-panel-lovelace');
    const hui = sr(sr(panel) && sr(panel).querySelector('hui-root'));
    if (hui && !hui.querySelector('style#gw-kiosk')) {
      const s = document.createElement('style');
      s.id = 'gw-kiosk';
      s.textContent = css;
      hui.appendChild(s);
    }
  }, 50);
})();`;

// Every card of the dashboard is on the page, its images have loaded, and the kiosk style is in.
const READY = `(() => {
  const all = [];
  const walk = (root) => root.querySelectorAll('*').forEach((el) => { all.push(el); if (el.shadowRoot) walk(el.shadowRoot); });
  walk(document);
  const cards = all.filter((el) => /^HUI-.+-CARD$/.test(el.tagName) && el.tagName !== 'HUI-CARD').length;
  const imgs = all.filter((el) => el.tagName === 'IMG');
  const loaded = imgs.every((i) => i.complete && i.naturalWidth > 0);
  const styled = all.some((el) => el.id === 'gw-kiosk');
  return JSON.stringify({ cards, images: imgs.length, loaded, styled });
})()`;

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

function chromePath() {
  if (process.env.CHROME) return process.env.CHROME;
  for (const p of ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser']) {
    if (existsSync(p)) return p;
  }
  throw new Error('no Chrome found: set CHROME=<path>');
}

/** Starts a child and resolves with the first match of `re` in its output. */
function start(cmd, args, re, opts = {}) {
  const child = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'], ...opts });
  return new Promise((resolve, reject) => {
    let out = '';
    const on = (d) => {
      out += d;
      const m = out.match(re);
      if (m) resolve({ child, match: m });
    };
    child.stdout.on('data', on);
    child.stderr.on('data', on);
    child.on('error', reject);
    child.on('exit', (code) => reject(new Error(`${cmd} exited (${code}):\n${out}`)));
  });
}

/** A DevTools protocol connection: send(method, params, sessionId). */
async function cdp(url) {
  const ws = new WebSocket(url);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let next = 1;
  const pending = new Map();
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    const p = m.id && pending.get(m.id);
    if (!p) return;
    pending.delete(m.id);
    if (m.error) p.reject(new Error(`${p.method}: ${m.error.message}`));
    else p.resolve(m.result);
  };
  const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
    const id = next++;
    pending.set(id, { resolve, reject, method });
    ws.send(JSON.stringify({ id, method, params, sessionId }));
  });
  return { send, close: () => ws.close() };
}

async function render(c, base, home, frame) {
  const display = frame.display ?? DEFAULT_DISPLAY;
  const { browserContextId } = await c.send('Target.createBrowserContext');
  const { targetId } = await c.send('Target.createTarget', { url: 'about:blank', browserContextId });
  const { sessionId } = await c.send('Target.attachToTarget', { targetId, flatten: true });
  const s = (m, p) => c.send(m, p, sessionId);
  await s('Page.enable');
  await s('Emulation.setDeviceMetricsOverride', {
    width: Math.round(display.width / DENSITY), height: Math.round(display.height / DENSITY), deviceScaleFactor: DENSITY, mobile: false,
  });
  await s('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: 'dark' }, { name: 'prefers-reduced-motion', value: 'reduce' }] });
  await s('Emulation.setTimezoneOverride', { timezoneId: 'UTC' });
  await s('Page.addScriptToEvaluateOnNewDocument', { source: inject(home.now) });
  await s('Page.navigate', { url: `${base}/showcase/home` });

  let state;
  const ready = () => state.styled && state.loaded && state.cards >= home.cards;
  for (let t = 0; t < 120; t++) {
    state = JSON.parse((await s('Runtime.evaluate', { expression: READY, returnByValue: true })).result.value);
    if (ready()) break;
    await wait(500);
  }
  if (!ready()) throw new Error(`${frame.id}: the dashboard didn't finish drawing (${JSON.stringify(state)}, ${home.cards} cards)`);

  // Settled: two captures a second apart are the same.
  let last = '';
  for (let t = 0; t < 30; t++) {
    await wait(1000);
    const { data } = await s('Page.captureScreenshot', { format: 'png' });
    if (data === last) break;
    last = data;
    if (t === 29) throw new Error(`${frame.id}: the page never stopped changing`);
  }
  const { data } = await s('Page.captureScreenshot', { format: 'webp', quality: QUALITY });
  await c.send('Target.disposeBrowserContext', { browserContextId });
  return Buffer.from(data, 'base64');
}

async function main() {
  const args = process.argv.slice(2);
  const opt = (name) => { const i = args.indexOf(name); return i < 0 ? undefined : args.splice(i, 2)[1]; };
  const variant = opt('--variant') ?? 'rich';
  const outDir = opt('--out');
  const want = args;
  if (!['rich', 'calm'].includes(variant)) throw new Error(`no such variant: ${variant}`);
  const frames = readdirSync(join(ROOT, 'frames'))
    .filter((id) => existsSync(join(ROOT, 'frames', id, 'design', 'frame.json')) && (!want.length || want.includes(id)))
    .map((id) => JSON.parse(readFileSync(join(ROOT, 'frames', id, 'design', 'frame.json'), 'utf8')));
  if (!frames.length) throw new Error(`no such frame: ${want.join(' ')}`);

  const serve = `import sys; sys.path[:0] = ${JSON.stringify([join(ROOT, 'tools', 'perf'), join(ROOT, 'tools', 'showcase')])}
import fakeha, home
home.VARIANT = ${JSON.stringify(variant)}
srv = fakeha.serve(0, harness=False, home=home)
print("serving on", srv.server_address[1], "cards", home.cards(), "now", int(home.NOW * 1000), flush=True)
srv.serve_forever()`;
  const ha = await start('python3', ['-c', serve], /serving on (\d+) cards (\d+) now (\d+)/);
  const profile = mkdtempSync(join(tmpdir(), 'gw-showcase-'));
  let chrome;
  try {
    chrome = await start(chromePath(), [
      '--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run',
      '--no-default-browser-check', '--hide-scrollbars', '--force-color-profile=srgb', '--disable-gpu',
      // One raster thread, all of it before each draw: without these, a few edges came out
      // differently from run to run (checked 2026-11-13).
      '--lang=en-US', '--num-raster-threads=1', '--disable-partial-raster', '--disable-lcd-text',
      '--run-all-compositor-stages-before-draw', '--disable-threaded-animation', '--disable-threaded-scrolling',
      'about:blank',
    ], /DevTools listening on (ws:\/\/\S+)/);
    const c = await cdp(chrome.match[1]);
    const { product } = await c.send('Browser.getVersion');
    const base = `http://127.0.0.1:${ha.match[1]}`;
    const home = { cards: Number(ha.match[2]), now: Number(ha.match[3]) };
    for (const f of frames) {
      const webp = await render(c, base, home, f);
      const out = outDir ? join(outDir, `${f.id}.webp`) : join(ROOT, 'frames', f.id, 'design', 'render.webp');
      writeFileSync(out, webp);
      const d = f.display ?? DEFAULT_DISPLAY;
      console.log(`${f.id} (${variant}): ${d.width}x${d.height}, ${(webp.length / 1024).toFixed(1)} KiB -> ${out}`);
    }
    console.log(`(${product})`);
    c.close();
  } finally {
    chrome?.child.kill();
    ha.child.kill();
    await wait(300);
    rmSync(profile, { recursive: true, force: true });
  }
}

main().catch((e) => { console.error(e.message ?? e); process.exit(1); });
