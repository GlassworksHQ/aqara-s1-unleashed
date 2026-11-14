// A test's browser never outlives the test (MIT, #205). On macOS the Gecko test starts Firefox with
// `open -n -a`, so launchd, not the test, is its parent: a test killed by a timeout, ^C or a crash
// left it running (the root found one at ~97% CPU for 2 h 46 min, through s1rtc6 to s1rtc8, loading
// the computer that serves the panel's fake Home Assistant). So, for each profile the test starts a
// browser with:
// - on the test's way out (exit, SIGINT, SIGTERM, SIGHUP, an uncaught exception): SIGKILL, by pid,
//   to every process whose command line contains the profile's path (matched as literal text);
// - a detached watchdog that waits for the test's process to be gone, then does the same: this one
//   also covers SIGKILL of the test, which no handler sees (its pkill -f pattern is ERE-escaped);
// - at the start, sweepStale(): a leftover browser whose profile's directory is gone (its test's
//   temporary directory was removed) is killed. One whose directory is there may be another
//   session's live test, and is left alone.
// Touches only processes whose command line names one of the test's own profiles.
import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";

const profiles = new Set();
let installed = false;

// Every process (but this one) whose command line contains `text`, matched literally: [{pid, command}].
function matching(text) {
  const ps = spawnSync("ps", ["-axo", "pid=,command="], { encoding: "utf8" }).stdout || "";
  return ps.split("\n").map((l) => /^\s*(\d+)\s+(.*)$/.exec(l)).filter(Boolean)
    .map((m) => ({ pid: Number(m[1]), command: m[2] })).filter((p) => p.pid !== process.pid && p.command.includes(text));
}

// For pkill -f, whose pattern is an extended regular expression: the path as literal text.
export const ere = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

export function killProfile(profile) {
  for (const p of matching(profile)) {
    try { process.kill(p.pid, "SIGKILL"); } catch (e) { /* gone already */ }
  }
}

function killAll() {
  for (const p of profiles) killProfile(p);
}

function install() {
  if (installed) return;
  installed = true;
  process.on("exit", killAll);
  for (const sig of ["SIGINT", "SIGTERM", "SIGHUP"]) {
    process.on(sig, () => {
      killAll();
      process.exit(128 + ({ SIGINT: 2, SIGTERM: 15, SIGHUP: 1 })[sig]);
    });
  }
  process.on("uncaughtException", (e) => {
    killAll();
    console.error(e);
    process.exit(1);
  });
}

// Before starting a browser with this profile directory. Returns a function that kills it now.
export function guard(profile) {
  install();
  profiles.add(profile);
  // The watchdog: a shell, detached from the test, that outlives a SIGKILL of it.
  const sh = `while kill -0 ${process.pid} 2>/dev/null; do sleep 2; done; pkill -f "$1"`;
  const w = spawn("sh", ["-c", sh, "browserguard", ere(profile)], { detached: true, stdio: "ignore" });
  w.unref();
  return () => {
    killProfile(profile);
    profiles.delete(profile);
  };
}

// Kills leftover browsers from earlier tests: a process whose command line names a profile
// (Firefox's --profile <dir>, Chrome's --user-data-dir=<dir>) under one of these temporary-directory
// prefixes (gw-gecko-, gw-e2e-) whose directory is gone.
export function sweepStale(markers) {
  const killed = [];
  for (const p of matching("")) {
    const m = /--profile\s+(\S+)|--user-data-dir=(\S+)/.exec(p.command);
    const dir = m && (m[1] || m[2]);
    if (!dir || !markers.some((k) => dir.includes(k)) || existsSync(dir)) continue;
    try {
      process.kill(p.pid, "SIGKILL");
      killed.push(p.pid);
    } catch (e) { /* gone already */ }
  }
  return killed;
}
