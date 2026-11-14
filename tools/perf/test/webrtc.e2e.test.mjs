// node --test tools/perf/test/webrtc.e2e.test.mjs
//
// #202 end to end, no device: the fake Home Assistant serves HA's real frontend (the pinned build) to
// headless Chrome, which opens the typical view and its HLS copy as the runner would. The cameras on
// typical must play over WebRTC (aiortc's H.264 through the fake's TURN relay, over TCP), and the
// copy's over HLS; the harness's camera lines say how.
//
// Needs Chrome with H.264 (the system's: Playwright's Chromium has none; CHROME= picks one), ffmpeg
// with libx264, the WebRTC venv (webrtcenv.py; GW_PERF_FAKE_PYTHON= picks another) and the pinned
// frontend (fetched once, 117 MB). Without them it skips, except with GW_PERF_E2E=1 (CI), where it fails.
// Starts its own fake on free ports and its own Chrome profile; touches no device.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const PERF = join(dirname(fileURLToPath(import.meta.url)), "..");
const MUST = process.env.GW_PERF_E2E === "1";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function chrome() {
  const paths = [process.env.CHROME, "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/opt/google/chrome/chrome"];
  return paths.find((p) => p && existsSync(p)) || null;
}

function python3(args) {
  const r = spawnSync("python3", args, { cwd: PERF, encoding: "utf8", timeout: 600000 });
  return r.status === 0 ? r.stdout.trim() : null;
}

function missing() {
  if (!chrome()) return "no Chrome (CHROME=)";
  if (!spawnSync("ffmpeg", ["-hide_banner", "-encoders"], { encoding: "utf8" }).stdout?.includes("libx264")) return "no ffmpeg with libx264";
  return null;
}

async function freePort() {
  // The relay takes port + 1, so ask for two in a row.
  for (;;) {
    const p = await new Promise((res) => { const s = createServer().listen(0, "127.0.0.1", () => { const { port } = s.address(); s.close(() => res(port)); }); });
    const ok = await new Promise((res) => { const s = createServer().once("error", () => res(false)).listen(p + 1, "127.0.0.1", () => s.close(() => res(true))); });
    if (ok) return p;
  }
}

// Whole GWPERF lines from the page's console (stats.js consoleLines splits long ones into parts).
function pageLines() {
  const parts = new Map(), lines = [];
  return {
    lines,
    feed(text) {
      let m = /^GWPERF\+ (\S+) (\d+)\/(\d+) ([\s\S]*)$/.exec(text);
      if (m) {
        const got = parts.get(m[1]) || new Map();
        got.set(Number(m[2]), m[4]);
        parts.set(m[1], got);
        if (got.size === Number(m[3])) {
          lines.push(JSON.parse([...Array(got.size).keys()].map((k) => got.get(k)).join("")));
          parts.delete(m[1]);
        }
        return;
      }
      m = /^GWPERF (.*)$/s.exec(text);
      if (m) lines.push(JSON.parse(m[1]));
    },
  };
}

async function until(what, f, ms) {
  const end = Date.now() + ms;
  for (;;) {
    const v = f();
    if (v) return v;
    if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
    await sleep(200);
  }
}

test("HA's frontend plays the fake's cameras over WebRTC through the TURN relay, and the copies over HLS", { timeout: 600000 }, async (t) => {
  const why = missing();
  if (why) {
    if (MUST) assert.fail(`GW_PERF_E2E=1 but ${why}`);
    return t.skip(why);
  }
  const py = process.env.GW_PERF_FAKE_PYTHON || python3(["webrtcenv.py"]);
  assert.ok(py, "webrtcenv.py made no venv");
  assert.ok(python3(["hafrontend.py"]), "hafrontend.py couldn't fetch the pinned frontend");
  assert.ok(python3(["camera.py"]), "camera.py couldn't make the streams");

  const dir = mkdtempSync(join(tmpdir(), "gw-e2e-"));
  const port = await freePort(), base = `http://127.0.0.1:${port}`;
  const fake = spawn(py, [join(PERF, "fakeha.py"), "--port", String(port), "--log", join(dir, "fakeha.jsonl")],
                     { stdio: ["ignore", "pipe", "pipe"] });
  let fakeErr = "";
  fake.stderr.on("data", (d) => { fakeErr += d; });
  const browser = spawn(chrome(), ["--headless=new", "--remote-debugging-port=0", `--user-data-dir=${join(dir, "profile")}`,
    "--no-first-run", "--no-default-browser-check", "--autoplay-policy=no-user-gesture-required", "--mute-audio",
    "--window-size=1280,800", ...(process.env.CI ? ["--no-sandbox"] : []), "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
  t.after(() => {
    browser.kill("SIGKILL");
    fake.kill();
    if (!t.passed && fakeErr) t.diagnostic(`the fake's stderr:\n${fakeErr.slice(-4000)}`);
    if (process.env.GW_PERF_E2E_KEEP) spawnSync("cp", [join(dir, "fakeha.jsonl"), process.env.GW_PERF_E2E_KEEP]);
    rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 });
  });

  let banner = "";
  fake.stdout.on("data", (d) => { banner += d; });
  await until("the fake", () => banner.includes("WebRTC over"), 60000);
  let err = "";
  browser.stderr.on("data", (d) => { err += d; });
  const wsUrl = await until("Chrome's DevTools", () => (/DevTools listening on (ws:\S+)/.exec(err) || [])[1], 30000);

  const ws = new WebSocket(wsUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0;
  const pending = new Map(), page = pageLines();
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    if (m.method === "Runtime.consoleAPICalled") {
      const v = m.params.args[0] && m.params.args[0].value;
      if (typeof v === "string") page.feed(v);
    }
  };
  const cdp = (method, params = {}, sessionId) => new Promise((r) => {
    pending.set(++id, r);
    ws.send(JSON.stringify({ id, method, params, sessionId }));
  });
  const { result: { targetId } } = await cdp("Target.createTarget", { url: "about:blank" });
  const { result: { sessionId } } = await cdp("Target.attachToTarget", { targetId, flatten: true });
  await cdp("Runtime.enable", {}, sessionId);
  await cdp("Page.navigate", { url: `${base}/clarity-ref/start` }, sessionId);
  await until("the start view's done", () => page.lines.find((l) => l.t === "done" && l.stage === "restart"), 180000);

  const open = async (view, nonce) => {
    const q = new URLSearchParams({ view, nonce, run: "1", taps: "0", swipes: "0", vsync: "16.667" });
    const r = await fetch(`${base}/clarity/open?${q}`);
    assert.equal(r.status, 204);
    await until(`${view} done`, () => page.lines.find((l) => l.nonce === nonce && (l.t === "done" || l.t === "error")), 120000);
    return page.lines.find((l) => l.nonce === nonce && l.t === "camera");
  };

  const rtc = await open("typical", "e2e0a001");
  assert.ok(rtc, "typical logged no camera line");
  t.diagnostic(`typical over WebRTC: ${JSON.stringify(rtc.streams)}`);
  assert.equal(rtc.path, "web_rtc");
  assert.equal(rtc.found, 1);
  const s = rtc.streams[0];
  assert.equal(s.path, "web_rtc");
  assert.deepEqual([s.w, s.h], [1280, 720]);
  // From the navigation, under fluid's 4 s even on a CI runner (aiortc's default STUN server held the answer 5 s).
  assert.ok(s.first_frame_ms > 0 && s.first_frame_ms < 4000, `first frame ${s.first_frame_ms}`);
  assert.ok(s.shown_pct >= 80, `shown ${s.shown_pct}%`);
  assert.equal(s.webrtc.inbound, true);
  assert.ok(s.webrtc.frames_decoded > 0);
  assert.equal(s.webrtc.codec, "video/H264");
  assert.deepEqual([s.webrtc.candidate.local, s.webrtc.candidate.relay], ["relay", "tcp"]);
  // With go2rtc HA offers HLS too: the frontend starts it, then keeps WebRTC only once it has video.
  assert.deepEqual(s.others.map((o) => [o.path, o.connected]), [["hls", false]]);

  const hls = await open("typical-hls", "e2e0a002");
  assert.ok(hls, "typical-hls logged no camera line");
  t.diagnostic(`typical-hls over HLS: ${JSON.stringify(hls.streams)}`);
  assert.equal(hls.path, "hls");
  assert.ok(hls.streams[0].first_frame_ms > 0);
  assert.ok(hls.streams[0].shown_pct >= 80, `HLS shown ${hls.streams[0].shown_pct}%`);
  assert.deepEqual(hls.streams[0].others, []);
  assert.equal(hls.streams[0].webrtc, undefined);

  const log = readFileSync(join(dir, "fakeha.jsonl"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
  assert.deepEqual(log.filter((e) => e.unknown || e.failed || e.webrtc_error), []);
  const answers = log.filter((e) => e.webrtc_answer === "camera.clarity_cam_0");
  assert.ok(answers.length >= 1 && answers.every((a) => /profile-level-id=42/.test(a.fmtp)), JSON.stringify(answers));
  assert.ok(log.some((e) => e.webrtc_ice === "completed"));
  assert.ok(!log.some((e) => e.webrtc_offer && e.webrtc_offer.startsWith("camera.clarity_hls_")));
});
