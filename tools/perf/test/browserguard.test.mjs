// node --test tools/perf/test/browserguard.test.mjs
// browserguard.mjs (#205): a test's browser and fake never outlive it, however the test ends. A
// stand-in "browser" (a node process whose command line names the test's directory, as Firefox's
// --profile and the fake's --log do) is started detached, as `open -n -a` starts Firefox; then
// the test's process is ended each way, and the browser must be gone within a few seconds.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync } from "node:fs";
import { tmpdir, platform } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { sweepStale } from "./browserguard.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const alive = (dir) => (spawnSync("pgrep", ["-f", dir], { encoding: "utf8" }).stdout || "").trim().length > 0;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function gone(dir, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (!alive(dir)) return true;
    await sleep(200);
  }
  return false;
}

// The "test": guards its directory, starts a detached stand-in browser, says ready, then waits.
const TEST = `
import { spawn } from "node:child_process";
import { guard } from ${JSON.stringify(join(HERE, "browserguard.mjs"))};
const dir = process.argv[1];
guard(dir);
const b = spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)", "--", "--profile", dir + "/profile"], { detached: true, stdio: "ignore" });
b.unref();
setTimeout(() => console.log("ready"), 300);
if (process.argv[2] === "throw") setTimeout(() => { throw new Error("a crash"); }, 600);
if (process.argv[2] === "exit") setTimeout(() => process.exit(3), 600);
setInterval(() => {}, 1000);
`;

for (const how of ["SIGTERM", "SIGINT", "SIGKILL", "throw", "exit"]) {
  test(`the browser dies with its test: ${how}`, { skip: platform() === "win32" }, async () => {
    const dir = mkdtempSync(join(tmpdir(), "gw-guard-"));
    try {
      const t = spawn(process.execPath, ["--input-type=module", "-e", TEST, dir, how], { stdio: ["ignore", "pipe", "pipe"] });
      await new Promise((r) => t.stdout.on("data", (d) => { if (String(d).includes("ready")) r(); }));
      assert.ok(alive(dir), "the stand-in browser started");
      if (how.startsWith("SIG")) t.kill(how);
      // SIGKILL: no handler runs; the watchdog polls every 2 s.
      assert.ok(await gone(dir, 8000), `the browser outlived its test (${how})`);
    } finally {
      spawnSync("pkill", ["-f", dir]);
      rmSync(dir, { recursive: true, force: true });
    }
  });
}

test("sweepStale kills a leftover browser whose directory is gone, and leaves a live one", { skip: platform() === "win32" }, async () => {
  const stale = mkdtempSync(join(tmpdir(), "gw-guard-")), live = mkdtempSync(join(tmpdir(), "gw-guard-"));
  mkdirSync(join(live, "profile"));
  const start = (d) => spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)", "--", "--profile", join(d, "profile")],
                             { detached: true, stdio: "ignore" }).unref();
  try {
    start(stale);
    start(live);
    await sleep(300);
    rmSync(stale, { recursive: true, force: true });              // its test is long gone
    const killed = sweepStale(["gw-guard-"]);
    assert.equal(killed.length, 1);
    assert.ok(await gone(stale, 3000), "the stale one is gone");
    assert.ok(alive(live), "the live one is left alone");
  } finally {
    spawnSync("pkill", ["-f", stale]);
    spawnSync("pkill", ["-f", live]);
    rmSync(live, { recursive: true, force: true });
  }
});
