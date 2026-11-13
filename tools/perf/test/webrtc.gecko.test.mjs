// node --test tools/perf/test/webrtc.gecko.test.mjs
//
// #202, Gecko: the panel's engine is GeckoView, and Gecko is stricter than Chrome about TURN. It never
// connects to a TURN server given as a loopback IP, and it drops a relayed candidate on loopback (the
// S1's smoke, 2026-11-13: 14 offers, no candidate, ICE never connected). Firefox desktop has the same
// ICE code (nICEr), so this test runs the fake's WebRTC through Firefox: the fake's client config,
// the offer, answer and candidates through the fake's websocket API as HA's frontend sends them, and
// the video's frames by Firefox's own counts. Firefox reaches the relay through a stand-in for adb
// reverse: a TCP forwarder that connects to the relay from 127.0.0.1, as adb does on the computer, so
// the relay sees the browser as loopback, as it does a panel (the S1's second smoke failed on that).
//
// Needs Firefox (FIREFOX= picks one), ffmpeg with libx264 and the WebRTC venv (webrtcenv.py;
// GW_PERF_FAKE_PYTHON= picks another). Without them it skips, except with GW_PERF_E2E=1 (CI). A
// Firefox that offers no H.264 (Linux, before it has fetched OpenH264) still has its relay
// candidate checked; the frames need H.264.

// adb reverse, as the relay sees it: every connection arrives from 127.0.0.1.
async function adbReverse(target) {
  const srv = tcpServer((inbound) => {
    const out = tcpConnect({ host: "127.0.0.1", port: target });
    inbound.pipe(out).pipe(inbound);
    const end = () => { inbound.destroy(); out.destroy(); };
    inbound.on("error", end); out.on("error", end); inbound.on("close", end); out.on("close", end);
  });
  await new Promise((r) => srv.listen(0, "127.0.0.1", r));
  return srv;
}
// Starts its own fake on free ports and its own Firefox profile; touches no device.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from "node:fs";
import { createServer as tcpServer, connect as tcpConnect } from "node:net";
import { createServer as httpServer } from "node:http";
import { tmpdir, platform } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const PERF = join(dirname(fileURLToPath(import.meta.url)), "..");
const MUST = process.env.GW_PERF_E2E === "1";
const MAC_APP = "/Applications/Firefox.app";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function firefox() {
  if (process.env.FIREFOX) return process.env.FIREFOX;
  if (platform() === "darwin") return existsSync(MAC_APP) ? MAC_APP : null;
  return ["/usr/bin/firefox", "/usr/local/bin/firefox", "/snap/bin/firefox"].find((p) => existsSync(p)) || null;
}

async function freePort() {
  for (;;) {
    const p = await new Promise((res) => { const s = tcpServer().listen(0, "127.0.0.1", () => { const { port } = s.address(); s.close(() => res(port)); }); });
    const ok = await new Promise((res) => { const s = tcpServer().once("error", () => res(false)).listen(p + 1, "127.0.0.1", () => s.close(() => res(true))); });
    if (ok) return p;
  }
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

// HA's frontend's side of the fake's websocket API (ha-web-rtc-player.ts): auth, then commands.
async function haSocket(base) {
  const ws = new WebSocket(base.replace("http", "ws") + "/api/websocket");
  const queue = [], waiters = [];
  ws.onmessage = (ev) => { queue.push(JSON.parse(ev.data)); waiters.splice(0).forEach((w) => w()); };
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  const next = async (pred, ms = 20000) => {
    const end = Date.now() + ms;
    for (;;) {
      const i = queue.findIndex(pred);
      if (i >= 0) return queue.splice(i, 1)[0];
      if (Date.now() > end) throw new Error("no such message from the fake");
      await new Promise((r) => { waiters.push(r); setTimeout(r, 200); });
    }
  };
  await next((m) => m.type === "auth_required");
  ws.send(JSON.stringify({ type: "auth", access_token: "clarity-access" }));
  await next((m) => m.type === "auth_ok");
  let id = 0;
  return {
    ws, next,
    send(msg) { ws.send(JSON.stringify({ ...msg, id: ++id })); return id; },
  };
}

const PAGE = `<!doctype html><script>
const say = (o) => fetch('/r', {method: 'POST', body: JSON.stringify(o)});
(async () => { try {
  const cfg = await (await fetch('/cfg')).json();
  const pc = new RTCPeerConnection(cfg);
  pc.addTransceiver('audio', {direction: 'recvonly'});
  pc.addTransceiver('video', {direction: 'recvonly'});
  pc.onicecandidate = (e) => { if (e.candidate && e.candidate.candidate) {
    say({cand: e.candidate.candidate}); fetch('/cand', {method: 'POST', body: JSON.stringify(e.candidate.toJSON())}); } };
  pc.onicecandidateerror = (e) => say({candErr: {url: e.url, code: e.errorCode}});
  await pc.setLocalDescription(await pc.createOffer());
  const h264 = /H264/.test(pc.localDescription.sdp);
  say({h264});
  if (!h264) return;
  const ans = await (await fetch('/offer', {method: 'POST', body: pc.localDescription.sdp})).json();
  if (ans.type !== 'answer') return say({answer: ans});
  await pc.setRemoteDescription({type: 'answer', sdp: ans.answer});
  for (let i = 0; i < 40; i++) {
    await new Promise((r) => setTimeout(r, 500));
    const v = []; (await pc.getStats()).forEach((x) => { if (x.type === 'inbound-rtp' && (x.kind || x.mediaType) === 'video') v.push(x); });
    say({ice: pc.iceConnectionState, decoded: v.length ? v[0].framesDecoded : null});
  }
} catch (e) { say({err: String(e)}); } })();
</script>`;

test("Gecko gets a relay candidate from the fake's TURN relay and plays its WebRTC stream", { timeout: 300000 }, async (t) => {
  const ff = firefox();
  const ffmpeg = spawnSync("ffmpeg", ["-hide_banner", "-encoders"], { encoding: "utf8" }).stdout || "";
  const why = !ff ? "no Firefox (FIREFOX=)" : !ffmpeg.includes("libx264") ? "no ffmpeg with libx264" : null;
  if (why) {
    if (MUST) assert.fail(`GW_PERF_E2E=1 but ${why}`);
    return t.skip(why);
  }
  const py = process.env.GW_PERF_FAKE_PYTHON || spawnSync("python3", ["webrtcenv.py"], { cwd: PERF, encoding: "utf8" }).stdout.trim();
  assert.ok(py, "webrtcenv.py made no venv");
  assert.equal(spawnSync("python3", ["camera.py"], { cwd: PERF }).status, 0, "camera.py couldn't make the streams");
  assert.equal(spawnSync("python3", ["hafrontend.py"], { cwd: PERF }).status, 0, "hafrontend.py couldn't fetch the frontend");

  const dir = mkdtempSync(join(tmpdir(), "gw-gecko-"));
  const port = await freePort(), base = `http://127.0.0.1:${port}`;
  const fake = spawn(py, [join(PERF, "fakeha.py"), "--port", String(port), "--log", join(dir, "fakeha.jsonl")],
                     { stdio: ["ignore", "pipe", "pipe"] });
  let banner = "", fakeErr = "";
  fake.stdout.on("data", (d) => { banner += d; });
  fake.stderr.on("data", (d) => { fakeErr += d; });
  const profile = join(dir, "profile");
  let browser = null;
  t.after(() => {
    if (browser) browser.kill("SIGKILL");
    spawnSync("pkill", ["-f", profile]);
    fake.kill();
    if (!t.passed && fakeErr) t.diagnostic(`the fake's stderr:\n${fakeErr.slice(-3000)}`);
    rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 });
  });
  await until("the fake", () => banner.includes("WebRTC relay on"), 60000);

  const ha = await haSocket(base);
  const cam = "camera.clarity_cam_1";
  const cfgId = ha.send({ type: "camera/webrtc/get_client_config", entity_id: cam });
  const cfg = (await ha.next((m) => m.id === cfgId)).result.configuration;
  assert.match(cfg.iceServers[0].urls, /^turn:localhost:\d+\?transport=tcp$/);
  const relayPort = Number(/:(\d+)\?/.exec(cfg.iceServers[0].urls)[1]);
  const adb = await adbReverse(relayPort);
  t.after(() => adb.close());
  const pageCfg = { ...cfg, iceServers: [{ ...cfg.iceServers[0], urls: `turn:localhost:${adb.address().port}?transport=tcp` }] };

  let offerId = null, session = null;
  const pendingCands = [], page = [];
  const http = httpServer(async (req, res) => {
    let body = "";
    for await (const c of req) body += c;
    if (req.url === "/") { res.writeHead(200, { "Content-Type": "text/html" }); return res.end(PAGE); }
    if (req.url === "/cfg") { res.writeHead(200, { "Content-Type": "application/json" }); return res.end(JSON.stringify(pageCfg)); }
    if (req.url === "/r") { page.push(JSON.parse(body)); res.writeHead(204); return res.end(); }
    if (req.url === "/cand") {
      // As the frontend: candidates wait for the session's id.
      const cand = JSON.parse(body);
      if (session) ha.send({ type: "camera/webrtc/candidate", entity_id: cam, session_id: session, candidate: cand });
      else pendingCands.push(cand);
      res.writeHead(204); return res.end();
    }
    if (req.url === "/offer") {
      offerId = ha.send({ type: "camera/webrtc/offer", entity_id: cam, offer: body });
      session = (await ha.next((m) => m.id === offerId && m.type === "event" && m.event.type === "session")).event.session_id;
      for (const c of pendingCands.splice(0)) ha.send({ type: "camera/webrtc/candidate", entity_id: cam, session_id: session, candidate: c });
      const ev = (await ha.next((m) => m.id === offerId && m.type === "event" && ["answer", "error"].includes(m.event.type))).event;
      res.writeHead(200, { "Content-Type": "application/json" }); return res.end(JSON.stringify(ev));
    }
    res.writeHead(404); res.end();
  });
  await new Promise((r) => http.listen(0, "127.0.0.1", r));
  t.after(() => http.close());
  const url = `http://127.0.0.1:${http.address().port}/`;

  mkdirSync(profile);
  writeFileSync(join(profile, "user.js"), ['user_pref("browser.shell.checkDefaultBrowser", false);',
    'user_pref("trailhead.firstrun.didSeeAboutWelcome", true);', 'user_pref("datareporting.policy.dataSubmissionEnabled", false);'].join("\n"));
  const args = ["--headless", "--no-remote", "--profile", profile, url];
  // macOS: through LaunchServices, so Firefox doesn't inherit a sandbox this process may run in.
  browser = platform() === "darwin" && ff.endsWith(".app") ? spawn("open", ["-n", "-a", ff, "--args", ...args], { stdio: "ignore" })
                                                            : spawn(ff, args, { stdio: "ignore" });

  const h264 = await until("Firefox's offer", () => page.find((p) => "h264" in p || p.err), 90000);
  assert.equal(h264.err, undefined, h264.err);
  const relay = await until("a relay candidate", () => page.find((p) => / typ relay /.test(p.cand || "")), 30000);
  // Not loopback: Gecko drops those.
  const addr = relay.cand.split(" ")[4];
  assert.ok(!/^127\.|^::1$/.test(addr), "the relay candidate is on loopback");
  // This computer's address stays out of CI's logs.
  t.diagnostic(`Gecko's relay candidate: ${relay.cand.replace(addr, "<media address>")}; offers H.264: ${h264.h264}`);
  const seen = readFileSync(join(dir, "fakeha.jsonl"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
  const alloc = seen.find((e) => e.turn_allocated);
  assert.equal(alloc.client[0], "127.0.0.1", "the relay should see the browser as loopback, as through adb reverse");
  assert.ok(!alloc.mapped[0].startsWith("127."), "a loopback mapped address makes Gecko drop the relay candidate");
  if (!h264.h264) {
    // Firefox on Linux offers H.264 only once it has fetched OpenH264. The relay candidate, what the
    // S1 failed on, is checked above; the frames need H.264.
    t.diagnostic("this Firefox offers no H.264: frames not checked");
    return;
  }
  const played = await until("frames", () => page.find((p) => p.ice === "connected" && p.decoded >= 30 || p.answer), 60000);
  assert.equal(played.answer, undefined, JSON.stringify(played.answer));
  t.diagnostic(`ICE ${played.ice}, ${played.decoded} frames decoded`);

  const log = readFileSync(join(dir, "fakeha.jsonl"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
  assert.ok(log.some((e) => e.turn_allocated), "the relay allocated nothing");
  assert.ok(log.some((e) => e.webrtc_ice === "completed"));
  assert.deepEqual(log.filter((e) => e.webrtc_error || e.turn_refused), []);
});

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

// The harness's own camera line, in Gecko (#202's verifier): HA's real frontend in Firefox, driven
// over WebDriver BiDi (Firefox's remote protocol), so the page's console lines can be read. Gecko's
// getVideoPlaybackQuality reads 0 for a MediaStream video, so this is where "shown" must come from
// the receiver's own counts. Needs a Firefox that offers H.264 (on a Mac it does).
test("in Gecko, the harness grades a WebRTC camera's frames shown from the receiver's counts", { timeout: 400000 }, async (t) => {
  const ff = firefox();
  const ffmpeg = spawnSync("ffmpeg", ["-hide_banner", "-encoders"], { encoding: "utf8" }).stdout || "";
  const why = !ff ? "no Firefox (FIREFOX=)" : !ffmpeg.includes("libx264") ? "no ffmpeg with libx264" : null;
  if (why) {
    if (MUST) assert.fail(`GW_PERF_E2E=1 but ${why}`);
    return t.skip(why);
  }
  const py = process.env.GW_PERF_FAKE_PYTHON || spawnSync("python3", ["webrtcenv.py"], { cwd: PERF, encoding: "utf8" }).stdout.trim();
  const dir = mkdtempSync(join(tmpdir(), "gw-gecko-ha-"));
  const port = await freePort(), base = `http://127.0.0.1:${port}`;
  const fake = spawn(py, [join(PERF, "fakeha.py"), "--port", String(port), "--log", join(dir, "fakeha.jsonl")],
                     { stdio: ["ignore", "pipe", "pipe"] });
  let banner = "";
  fake.stdout.on("data", (d) => { banner += d; });
  fake.stderr.on("data", () => {});
  const profile = join(dir, "profile");
  let browser = null, ws = null;
  t.after(() => {
    try { ws && ws.close(); } catch (e) { /* closing */ }
    if (browser) browser.kill("SIGKILL");
    spawnSync("pkill", ["-f", profile]);
    fake.kill();
    rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 });
  });
  await until("the fake", () => banner.includes("WebRTC relay on"), 60000);

  mkdirSync(profile);
  writeFileSync(join(profile, "user.js"), ['user_pref("browser.shell.checkDefaultBrowser", false);',
    'user_pref("trailhead.firstrun.didSeeAboutWelcome", true);', 'user_pref("datareporting.policy.dataSubmissionEnabled", false);',
    'user_pref("media.autoplay.default", 0);', 'user_pref("media.autoplay.blocking_policy", 0);'].join("\n"));
  const bidi = await freePort();
  const args = ["--headless", "--no-remote", "--profile", profile, "--remote-debugging-port", String(bidi), "--window-size", "1280,800"];
  browser = platform() === "darwin" && ff.endsWith(".app") ? spawn("open", ["-n", "-a", ff, "--args", ...args], { stdio: "ignore" })
                                                            : spawn(ff, args, { stdio: "ignore" });
  // WebDriver BiDi: a session, the console's lines, then the page.
  for (const end = Date.now() + 60000; ;) {
    try {
      ws = new WebSocket(`ws://127.0.0.1:${bidi}/session`);
      await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
      break;
    } catch (e) {
      if (Date.now() > end) throw new Error("Firefox's remote protocol never answered");
      await sleep(500);
    }
  }
  let id = 0;
  const pending = new Map(), page = pageLines();
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    if (m.type === "event" && m.method === "log.entryAdded" && typeof m.params.text === "string") page.feed(m.params.text);
  };
  const call = (method, params = {}) => new Promise((r) => { pending.set(++id, r); ws.send(JSON.stringify({ id, method, params })); });
  await call("session.new", { capabilities: {} });
  await call("session.subscribe", { events: ["log.entryAdded"] });
  const tree = await call("browsingContext.getTree", {});
  const context = tree.result.contexts[0].context;
  await call("browsingContext.navigate", { context, url: `${base}/clarity-ref/start`, wait: "none" });
  await until("the start view's done", () => page.lines.find((l) => l.t === "done" && l.stage === "restart"), 180000);

  const nonce = "9ec0a001";
  const q = new URLSearchParams({ view: "typical", nonce, run: "1", taps: "0", swipes: "0", vsync: "16.667" });
  assert.equal((await fetch(`${base}/clarity/open?${q}`)).status, 204);
  await until("typical done", () => page.lines.find((l) => l.nonce === nonce && (l.t === "done" || l.t === "error")), 180000);
  const cam = page.lines.find((l) => l.nonce === nonce && l.t === "camera");
  assert.ok(cam, "typical logged no camera line");
  const log = readFileSync(join(dir, "fakeha.jsonl"), "utf8").trim().split("\n").map((l) => JSON.parse(l));
  const offer = log.find((e) => e.type === "camera/webrtc/offer");
  if (offer && !/H264/.test(offer.msg.offer)) {
    if (MUST && platform() === "darwin") assert.fail("Firefox on a Mac offers H.264; this one didn't");
    t.diagnostic("this Firefox offers no H.264: the harness path isn't checked here");
    return;
  }
  const s = cam.streams[0];
  t.diagnostic(`typical in Gecko over WebRTC: first frame ${s.first_frame_ms} ms, shown ${s.shown_pct}% (${s.source}), ` +
               `quality decoded ${s.decoded}, receiver decoded ${s.webrtc && s.webrtc.frames_decoded}`);
  assert.equal(cam.path, "web_rtc");
  assert.ok(s.first_frame_ms > 0, `first frame ${s.first_frame_ms}`);
  assert.ok(s.shown_pct > 0, `shown ${s.shown_pct}% from ${s.source}`);
  assert.ok(s.source.startsWith("webrtc"), s.source);
  // The connections the camera's video came over (#202, the S1's heavy): here one, connected once.
  assert.ok(s.webrtc.connections.length >= 1 && s.webrtc.connections[0].ice.some(([, st]) => st === "connected"),
            JSON.stringify(s.webrtc.connections));
  assert.ok(s.webrtc.frames_decoded > 0 && s.webrtc.receivers >= 1);
});
