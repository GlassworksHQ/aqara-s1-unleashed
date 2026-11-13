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
function page({ launchScreen = true, externalAuth = true } = {}) {
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
    createElement: (tag) => ({ tag }),
    head: null, // document_start: no <head> yet
    documentElement: { appendChild: (el) => appended.push(el) },
  };
  const ctx = {
    window, document, console,
    location: { search: externalAuth ? "?external_auth=1" : "" },
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
  assert.match(css, /#ha-launch-screen \*\s*\{[^}]*visibility:\s*hidden/);
  // Every selector names the launch screen, so once the frontend removes it HA's theme is untouched.
  const selectors = css.split("}").map((r) => r.split("{")[0].trim()).filter(Boolean).flatMap((sel) => sel.split(","));
  assert.ok(selectors.length >= 3);
  for (const sel of selectors) assert.match(sel, /ha-launch-screen/, `unscoped rule: ${sel.trim()}`);
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
