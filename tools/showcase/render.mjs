#!/usr/bin/env node
// Device renders for the frame cards (MIT; #144): HA's real frontend (the build Clarity pins,
// Apache-2.0) drawing the showcase dashboard (home.py) from Clarity's fake Home Assistant, captured in
// headless Chrome at each frame's screen size, written to frames/<id>/design/render.webp.
//
// Touches nothing but those files: the fake HA listens on 127.0.0.1, Chrome runs with a throwaway
// profile. Needs python3, Node 22+ (its WebSocket) and Chrome (CHROME=<path> for another one). The
// first run fetches HA's frontend (tools/perf/hafrontend.py, checked by SHA-256).
//
//   node tools/showcase/render.mjs [frame-id ...]
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const DENSITY = 1.5; // the S1's (app/AGENTS.md); the others' aren't measured, so the same
// A frame without one screen size (generic-android) is drawn as a common 10" tablet.
const DEFAULT_DISPLAY = { width: 1920, height: 1200 };
const QUALITY = 82;

// The kiosk's own look: no sidebar, no header (app/kiosk/src/main/assets/extension/content.js), and
// HA's default theme in dark mode.
const INJECT = `(() => {
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

// Every tile card has drawn its name, and the kiosk style is in.
const READY = `(() => {
  const all = [];
  const walk = (root) => root.querySelectorAll('*').forEach((el) => { all.push(el); if (el.shadowRoot) walk(el.shadowRoot); });
  walk(document);
  const tiles = all.filter((el) => el.tagName === 'HUI-TILE-CARD');
  const named = tiles.filter((t) => t.shadowRoot && t.shadowRoot.textContent.trim().length > 0);
  const styled = all.some((el) => el.id === 'gw-kiosk');
  return JSON.stringify({ tiles: tiles.length, named: named.length, styled });
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

async function render(c, base, tiles, frame) {
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
  await s('Page.addScriptToEvaluateOnNewDocument', { source: INJECT });
  await s('Page.navigate', { url: `${base}/showcase/home` });

  let state;
  for (let t = 0; t < 120; t++) {
    state = JSON.parse((await s('Runtime.evaluate', { expression: READY, returnByValue: true })).result.value);
    if (state.styled && state.named === tiles) break;
    await wait(500);
  }
  if (!state.styled || state.named !== tiles) throw new Error(`${frame.id}: the dashboard didn't finish drawing (${JSON.stringify(state)}, ${tiles} tiles)`);

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
  const want = process.argv.slice(2);
  const frames = readdirSync(join(ROOT, 'frames'))
    .filter((id) => existsSync(join(ROOT, 'frames', id, 'design', 'frame.json')) && (!want.length || want.includes(id)))
    .map((id) => JSON.parse(readFileSync(join(ROOT, 'frames', id, 'design', 'frame.json'), 'utf8')));
  if (!frames.length) throw new Error(`no such frame: ${want.join(' ')}`);

  const serve = `import sys; sys.path[:0] = ${JSON.stringify([join(ROOT, 'tools', 'perf'), join(ROOT, 'tools', 'showcase')])}
import fakeha, home
srv = fakeha.serve(0, harness=False, home=home)
tiles = sum(c["type"] == "tile" for s in home.lovelace(home.DASHBOARD)["views"][0]["sections"] for c in s["cards"])
print("serving on", srv.server_address[1], "tiles", tiles, flush=True)
srv.serve_forever()`;
  const ha = await start('python3', ['-c', serve], /serving on (\d+) tiles (\d+)/);
  const profile = mkdtempSync(join(tmpdir(), 'gw-showcase-'));
  let chrome;
  try {
    chrome = await start(chromePath(), [
      '--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run',
      '--no-default-browser-check', '--hide-scrollbars', '--force-color-profile=srgb', '--disable-gpu',
      '--lang=en-US', 'about:blank',
    ], /DevTools listening on (ws:\/\/\S+)/);
    const c = await cdp(chrome.match[1]);
    const { product } = await c.send('Browser.getVersion');
    const base = `http://127.0.0.1:${ha.match[1]}`;
    for (const f of frames) {
      const webp = await render(c, base, Number(ha.match[2]), f);
      const out = join(ROOT, 'frames', f.id, 'design', 'render.webp');
      writeFileSync(out, webp);
      const d = f.display ?? DEFAULT_DISPLAY;
      console.log(`${f.id}: ${d.width}x${d.height}, ${(webp.length / 1024).toFixed(1)} KiB -> frames/${f.id}/design/render.webp`);
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
