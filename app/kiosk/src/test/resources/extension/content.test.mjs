// content.js's "ready" for the connecting screen (#63, round 4 panel run), run under node:test with
// the browser, the DOM, frames and timers stubbed. Run by ContentScriptReadyTest (or by hand:
// CONTENT_JS=app/kiosk/src/main/assets/extension/content.js node --test this file).
// Touches nothing outside this process.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const SOURCE = readFileSync(process.env.CONTENT_JS, "utf8");

/** content.js in a fresh page: [launchScreen] in the body or not, [externalAuth] in the URL or not. */
function page({ launchScreen = true, externalAuth = true, search = null } = {}) {
  const posted = [];
  const timers = [];
  let frames = [];
  let now = 0;
  let observer = null;
  let launch = launchScreen ? { id: "ha-launch-screen", parentNode: {} } : null;
  const domListeners = {};
  const toPage = [];
  const appended = [];

  const window = {
    wrappedJSObject: { externalBus: (m) => toPage.push(m) },
    dispatchEvent() {},
  };
  const document = {
    readyState: "loading",
    addEventListener: (type, fn) => { (domListeners[type] ||= []).push(fn); },
    getElementById: (id) => (id === "ha-launch-screen" ? launch : null),
    querySelector: () => null,
    createElement: (tag) => ({ tag, removed: false, remove() { this.removed = true; } }),
    head: null, // document_start: no <head> yet
    documentElement: { appendChild: (el) => appended.push(el) },
  };
  const ctx = {
    window, document, console,
    location: { search: search ?? (externalAuth ? "?external_auth=1" : "") },
    URLSearchParams,
    localStorage: { getItem: () => null, setItem() {} },
    CustomEvent: class { constructor(t, o) { this.type = t; Object.assign(this, o); } },
    browser: { runtime: { connectNative: () => ({
      postMessage: (m) => posted.push(JSON.parse(JSON.stringify(m))),
      onMessage: { addListener() {} },
      onDisconnect: { addListener() {} },
    }) } },
    MutationObserver: class {
      constructor(cb) { this.cb = cb; observer = this; }
      observe() {}
      disconnect() { observer = null; }
    },
    createObjectIn: (target, { defineAs }) => (target[defineAs] = {}),
    exportFunction: (fn, target, opts) => { if (opts?.defineAs) target[opts.defineAs] = fn; return fn; },
    cloneInto: (x) => x,
    setTimeout: (fn, ms = 0) => { timers.push({ at: now + ms, fn }); return timers.length; },
    clearTimeout() {},
    setInterval: () => 0,
    requestAnimationFrame: (fn) => { frames.push(fn); return frames.length; },
  };
  vm.runInNewContext(SOURCE, ctx);

  return {
    posted, toPage, window, appended,
    ready: () => posted.filter((m) => m.type === "ready"),
    domContentLoaded() { document.readyState = "interactive"; (domListeners.DOMContentLoaded || []).forEach((f) => f()); },
    /** HA's frontend removes its launch screen. */
    removeLaunchScreen() { launch = null; observer?.cb([]); },
    /** One painted frame: the requestAnimationFrame callbacks queued before it run. */
    frame() { const due = frames; frames = []; due.forEach((f) => f()); },
    /** Time passes: due timers run. */
    advance(ms) {
      now += ms;
      for (;;) {
        const i = timers.findIndex((t) => t.at <= now);
        if (i < 0) break;
        timers.splice(i, 1)[0].fn();
      }
    },
  };
}

test("no ready while Home Assistant's launch screen is up; it says launch", () => {
  const p = page();
  p.domContentLoaded();
  p.advance(5_000);
  p.frame(); p.frame();
  assert.deepEqual(p.ready(), []);
  assert.ok(p.posted.some((m) => m.type === "launch"));
});

test("after the launch screen goes, ready waits for two painted frames and the margin", () => {
  // Round 4: "ready" went out on the DOM change, and the app faded over a frame Gecko hadn't
  // repainted yet, with the white splash still in it.
  const p = page();
  p.domContentLoaded();
  p.removeLaunchScreen();
  p.advance(1_000);
  assert.deepEqual(p.ready(), [], "not before a frame is painted");
  p.frame();
  p.advance(1_000);
  assert.deepEqual(p.ready(), [], "not after one frame");
  p.frame();
  p.advance(149);
  assert.deepEqual(p.ready(), [], "not before the margin");
  p.advance(1);
  assert.deepEqual(p.ready().map((m) => m.why), ["removed"]);
});

test("the frontend's own frontend/loaded says ready the same way, once", () => {
  const p = page();
  p.domContentLoaded();
  p.window.externalApp.externalBus(JSON.stringify({ id: 7, type: "frontend/loaded" }));
  p.frame(); p.frame(); p.advance(150);
  assert.deepEqual(p.ready().map((m) => m.why), ["frontend/loaded"]);
  p.removeLaunchScreen();
  p.frame(); p.frame(); p.advance(500);
  assert.equal(p.ready().length, 1);
});

test("config/get tells the frontend the app has its own splash screen", () => {
  const p = page();
  p.window.externalApp.externalBus(JSON.stringify({ id: 1, type: "config/get" }));
  p.advance(0);
  const reply = p.toPage.find((m) => m.id === 1);
  assert.equal(reply.success, true);
  assert.equal(reply.result.hasSplashscreen, true);
});

test("a page without a launch screen is ready once it has painted", () => {
  const p = page({ launchScreen: false, externalAuth: false });
  p.domContentLoaded();
  assert.deepEqual(p.ready(), []);
  p.frame(); p.frame(); p.advance(150);
  assert.deepEqual(p.ready().map((m) => m.why), ["page"]);
});

test("from the start, HA's launch screen draws at night, and only while it is there", () => {
  // Round 5 panel run: one fully white frame in two cold starts, timing alone can't rule it out.
  const p = page();
  const style = p.appended.find((el) => el.tag === "style" && el.id === "s1kiosk-launch");
  assert.ok(style, "the style is added at document_start, before anything is drawn");
  const css = style.textContent;
  assert.match(css, /#ha-launch-screen\s*\{[^}]*background:\s*#0a0b0f/);
  // Every selector names the launch screen, so HA's theme is untouched once the frontend removes it.
  const rules = css.split("}").map((r) => r.split("{")).filter((r) => r[0].trim());
  const selectors = rules.flatMap(([sel]) => sel.split(","));
  assert.ok(selectors.length >= 4);
  for (const sel of selectors) assert.match(sel, /ha-launch-screen/, `unscoped rule: ${sel.trim()}`);
  // ... and the style goes with it (verifier d7acb506, F3).
  p.domContentLoaded();
  assert.equal(style.removed, false);
  p.removeLaunchScreen();
  assert.equal(style.removed, true);
});

test("only the logo and the attribution are hidden: a failed start still says why", () => {
  // Verifier d7acb506, F1: HA writes "Could not load Home Assistant…" into the info box, inside the
  // launch screen. Hiding everything in it turned a failed start into a black screen.
  const css = page().appended.find((el) => el.id === "s1kiosk-launch").textContent;
  const rules = css.split("}").map((r) => r.split("{")).filter((r) => r[0].trim());
  const hidden = rules.filter(([, decl]) => /visibility:\s*hidden|display:\s*none|opacity:\s*0/.test(decl))
    .flatMap(([sel]) => sel.split(",").map((x) => x.trim()));
  assert.deepEqual(hidden.sort(), ["#ha-launch-screen .ha-lockup", "#ha-launch-screen .ohf-logo"]);
  assert.ok(!/#ha-launch-screen \*/.test(css), "no rule for everything inside the launch screen");
  const info = rules.find(([sel]) => /ha-launch-screen-info-box/.test(sel));
  assert.ok(info, "the info box has its own rule");
  assert.match(info[1], /color:\s*#f4f4f5/);
});

test("not on a page opened with ?kiosk=0 or ?edit=1", () => {
  // Verifier d7acb506, F2: like the header and sidebar rules.
  for (const search of ["?kiosk=0", "?edit=1"]) {
    assert.equal(page({ search }).appended.filter((el) => el.id === "s1kiosk-launch").length, 0, search);
  }
});

test("a page without a launch screen drops the style at once", () => {
  const p = page({ launchScreen: false, externalAuth: false });
  const style = p.appended.find((el) => el.id === "s1kiosk-launch");
  p.domContentLoaded();
  assert.equal(style.removed, true);
});

test("only one ready, however many signals come", () => {
  const p = page();
  p.domContentLoaded();
  p.window.externalApp.externalBus(JSON.stringify({ id: 7, type: "frontend/loaded" }));
  p.removeLaunchScreen();
  p.window.externalApp.externalBus(JSON.stringify({ id: 8, type: "frontend/loaded" }));
  p.frame(); p.frame(); p.advance(500);
  assert.equal(p.ready().length, 1);
});
