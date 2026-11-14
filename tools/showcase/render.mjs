#!/usr/bin/env node
// Device renders for the frame cards (MIT; #144): HA's real frontend (the build Clarity pins,
// Apache-2.0) drawing the showcase dashboard (home.py) from Clarity's fake Home Assistant, captured in
// headless Chrome at each frame's screen size, written to frames/<id>/design/render.webp.
//
// Touches nothing but those files: the fake HA listens on 127.0.0.1, Chrome runs with a throwaway
// profile. Needs python3, Node 22+ (its WebSocket) and Chrome (CHROME=<path> for another one). The
// first run fetches HA's frontend (tools/perf/hafrontend.py, checked by SHA-256).
//
//   node tools/showcase/render.mjs [--out <dir>] [frame-id ...]
// --out writes <dir>/<frame-id>.webp instead of the frames' own files (e.g. to compare with them).
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
// Screen px per CSS px. The S1's own density is 1.5 (app/AGENTS.md); 1 is the kiosk's zoom at 67%. Each
// frame is drawn at the density, from MIN_DENSITY to MAX_DENSITY, at which the screen's bottom edge falls
// just under a row of sections: as many whole rows as fit, none cut through.
const MIN_DENSITY = 0.6;
const MAX_DENSITY = 1.1;
// Frames drawn at a density set by hand instead of fitted, and what the check then expects to see.
// The Mirror shows three columns of three sections: the fit can't hold that, because the number of
// columns changes with the density it tries (three at 0.94, two at 0.71), so it never settles.
const PINNED = {
  'lululemon-mirror': { density: 0.94, columns: 3, rows: 3 },
};
// The view's own padding under the last row, kept on screen.
const BOTTOM_PAD = 12;
// A frame without one screen size (generic-android) is drawn as a common 10" tablet.
const DEFAULT_DISPLAY = { width: 1920, height: 1200 };
// WebP quality: 60 keeps every render under 80 KiB with the camera photos in, and text still sharp.
const QUALITY = 60;

// The kiosk's own look: no sidebar, no header (app/kiosk/src/main/assets/extension/content.js), and
// HA's default theme in dark mode.
const inject = (now) => `(() => {
  // The page's clock stands still at the home's NOW, so the clock card and the graphs' windows are the
  // same on every run, however slow the machine.
  const RealDate = Date;
  const now = () => ${now};
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

// Each section's top and the bottom of its lowest card, in CSS px. HA stretches a section's own box to its
// row, so only the cards show a column that ends early.
const SECTION_BOXES = `(() => {
  const out = [];
  const lowest = (root, acc) => root.querySelectorAll('*').forEach((el) => {
    if (el.tagName === 'HUI-CARD' || /^HUI-.+-CARD$/.test(el.tagName)) {
      const r = el.getBoundingClientRect();
      if (r.height > 0) acc.bottom = Math.max(acc.bottom, r.bottom);
    }
    if (el.shadowRoot) lowest(el.shadowRoot, acc);
  });
  const walk = (root) => root.querySelectorAll('*').forEach((el) => {
    if (el.tagName === 'HUI-SECTION') {
      const acc = { bottom: -1 };
      lowest(el, acc);
      if (el.shadowRoot) lowest(el.shadowRoot, acc);
      out.push({ top: el.getBoundingClientRect().top, bottom: acc.bottom });
      return;
    }
    if (el.shadowRoot) walk(el.shadowRoot);
  });
  walk(document);
  return JSON.stringify(out);
})()`;

// The clock card's text, to check the page still shows the home's NOW when it is captured.
const CLOCK_TEXT = `(() => {
  const deep = (root) => [...root.childNodes].map((n) => n.nodeType === 3 ? n.textContent
    : (n.shadowRoot ? deep(n.shadowRoot) : '') + (n.childNodes ? deep(n) : '')).join(' ');
  let text = '';
  const walk = (root) => root.querySelectorAll('*').forEach((el) => {
    if (el.tagName === 'HUI-CLOCK-CARD') text = deep(el.shadowRoot ?? el).replace(/\\s+/g, '');
    else if (el.shadowRoot) walk(el.shadowRoot);
  });
  walk(document);
  return text;
})()`;

// How unevenly a row of sections may end (px): more is a column that stops early.
const MAX_SPREAD = 4;

/** Sections grouped into rows (same top): [{ top, bottom, spread }], bottom = the row's lowest card, spread = how unevenly it ends. */
function rowsOf(boxes) {
  const rows = [];
  for (const b of [...boxes].sort((x, y) => x.top - y.top)) {
    if (b.bottom < 0) throw new Error(`a section with no cards at ${b.top}`);
    const row = rows.find((r) => Math.abs(r.top - b.top) < 4);
    if (row) row.bottoms.push(b.bottom);
    else rows.push({ top: b.top, bottoms: [b.bottom] });
  }
  return rows.map((r) => ({
    top: r.top, bottom: Math.max(...r.bottoms), spread: Math.max(...r.bottoms) - Math.min(...r.bottoms), columns: r.bottoms.length,
  }));
}

/**
 * What the screen (height px at density d) shows of the rows: the whole rows above its bottom edge.
 * Throws if a row runs through that edge (a card cut), or a shown row ends unevenly (a column stops early).
 */
function shownRows(rows, height, d, id) {
  const edge = height / d;
  const cut = rows.find((r) => r.top < edge && r.bottom > edge);
  if (cut) throw new Error(`${id}: the screen's bottom edge cuts a row of sections: ${JSON.stringify(cut)} at ${edge.toFixed(0)}`);
  const shown = rows.filter((r) => r.bottom <= edge);
  const uneven = shown.find((r) => r.spread > MAX_SPREAD);
  if (uneven) throw new Error(`${id}: a column ends ${uneven.spread.toFixed(0)} px before the others: ${JSON.stringify(shown)}`);
  return shown;
}

/** The density at which the screen (height px) shows rows 1..k whole and nothing of row k+1: the most rows that fit. */
function fold(rows, height) {
  for (let k = rows.length; k >= 1; k--) {
    const need = rows[k - 1].bottom + BOTTOM_PAD;
    const d = Math.min(MAX_DENSITY, height / need);
    if (d < MIN_DENSITY) continue;
    if (k < rows.length && rows[k].top < height / d) continue;
    return { density: d, rows: k };
  }
  throw new Error(`no density from ${MIN_DENSITY} to ${MAX_DENSITY} ends the screen between rows: ${JSON.stringify(rows)}`);
}

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
  const metrics = (d) => s('Emulation.setDeviceMetricsOverride', {
    width: Math.round(display.width / d), height: Math.round(display.height / d), deviceScaleFactor: d, mobile: false,
  });
  // Settled: two captures a second apart are the same.
  const settle = async () => {
    let last = '';
    for (let t = 0; t < 30; t++) {
      await wait(1000);
      const { data } = await s('Page.captureScreenshot', { format: 'png' });
      if (data === last) return;
      last = data;
    }
    throw new Error(`${frame.id}: the page never stopped changing`);
  };
  const rows = async () => rowsOf(JSON.parse((await s('Runtime.evaluate', { expression: SECTION_BOXES, returnByValue: true })).result.value));

  await s('Page.enable');
  await metrics(1);
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
  await settle();

  const pinned = PINNED[frame.id];
  let f;
  if (pinned) {
    f = { density: pinned.density, rows: pinned.rows };
    await metrics(f.density);
    await settle();
  } else {
    // A wider page can lay the cards out a little differently: fold again until it holds.
    f = fold(await rows(), display.height);
    let stable = false;
    for (let t = 0; t < 4 && !stable; t++) {
      await metrics(f.density);
      await settle();
      const again = fold(await rows(), display.height);
      stable = Math.abs(again.density - f.density) < 0.002 && again.rows === f.rows;
      f = again;
    }
    if (!stable) throw new Error(`${frame.id}: the fold didn't settle (last: density ${f.density}, ${f.rows} rows)`);
  }
  const shown = shownRows(await rows(), display.height, f.density, frame.id);
  if (shown.length !== f.rows) throw new Error(`${frame.id}: ${shown.length} whole rows of sections on screen, not ${f.rows}`);
  if (pinned && shown.some((r) => r.columns !== pinned.columns)) {
    throw new Error(`${frame.id}: not ${pinned.columns} columns in every row: ${JSON.stringify(shown)}`);
  }
  const spread = Math.max(...shown.map((r) => r.spread));
  const clock = (await s('Runtime.evaluate', { expression: CLOCK_TEXT, returnByValue: true })).result.value;
  // the clock card's digits, without the colon it draws apart: "1930"
  const hhmm = new Date(home.now).toISOString().slice(11, 16).replace(':', '');
  if (!clock.includes(hhmm)) throw new Error(`${frame.id}: the clock reads "${clock}", not ${hhmm}`);
  const { data } = await s('Page.captureScreenshot', { format: 'webp', quality: QUALITY });
  await c.send('Target.disposeBrowserContext', { browserContextId });
  return { webp: Buffer.from(data, 'base64'), density: f.density, rows: f.rows, columns: shown[0].columns, spread, pinned: !!pinned };
}

async function main() {
  const args = process.argv.slice(2);
  const opt = (name) => { const i = args.indexOf(name); return i < 0 ? undefined : args.splice(i, 2)[1]; };
  const outDir = opt('--out');
  const want = args;
  const frames = readdirSync(join(ROOT, 'frames'))
    .filter((id) => existsSync(join(ROOT, 'frames', id, 'design', 'frame.json')) && (!want.length || want.includes(id)))
    .map((id) => JSON.parse(readFileSync(join(ROOT, 'frames', id, 'design', 'frame.json'), 'utf8')));
  if (!frames.length) throw new Error(`no such frame: ${want.join(' ')}`);

  const serve = `import sys; sys.path[:0] = ${JSON.stringify([join(ROOT, 'tools', 'perf'), join(ROOT, 'tools', 'showcase')])}
import fakeha, home
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
      const r = await render(c, base, home, f);
      const out = outDir ? join(outDir, `${f.id}.webp`) : join(ROOT, 'frames', f.id, 'design', 'render.webp');
      writeFileSync(out, r.webp);
      const d = f.display ?? DEFAULT_DISPLAY;
      console.log(`${f.id}: ${d.width}x${d.height}, ${(r.webp.length / 1024).toFixed(1)} KiB, density ${r.density.toFixed(3)}` +
        `${r.pinned ? ' (pinned)' : ''}, ${r.rows} row(s) x ${r.columns} columns of sections, rows end within ${r.spread.toFixed(0)} px -> ${out}`);
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
