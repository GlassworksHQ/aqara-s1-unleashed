// A test's browser never outlives the test (MIT, #205). On macOS the Gecko test starts Firefox with
// `open -n -a`, so launchd, not the test, is its parent: a test killed by a timeout, ^C or a crash
// left it running (the root found one at ~97% CPU for 2 h 46 min, through s1rtc6 to s1rtc8, loading
// the computer that serves the panel's fake Home Assistant). So, for each profile the test starts a
// browser with:
// - on the test's way out (exit, SIGINT, SIGTERM, SIGHUP, an uncaught exception): pkill -f <profile>;
// - a detached watchdog that waits for the test's process to be gone, then does the same: this one
//   also covers SIGKILL, which no handler sees;
// - at the start, sweepStale(): a leftover browser whose profile's directory is gone (its test's
//   temporary directory was removed) is killed. One whose directory is there may be another
//   session's live test, and is left alone.
// Touches only processes whose command line names one of the test's own profiles.
import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";

const profiles = new Set();
let installed = false;

export function killProfile(profile) {
  spawnSync("pkill", ["-f", profile]);
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
  const w = spawn("sh", ["-c", sh, "browserguard", profile], { detached: true, stdio: "ignore" });
  w.unref();
  return () => {
    killProfile(profile);
    profiles.delete(profile);
  };
}

// Kills leftover browsers from earlier tests: a process whose command line names a profile under
// one of these temporary-directory prefixes (gw-gecko-, gw-gecko-ha-) whose directory is gone.
export function sweepStale(markers) {
  const ps = spawnSync("ps", ["-axo", "pid=,command="], { encoding: "utf8" }).stdout || "";
  const killed = [];
  for (const line of ps.split("\n")) {
    const m = /^\s*(\d+)\s+(.*)$/.exec(line);
    if (!m || Number(m[1]) === process.pid) continue;
    const prof = /--profile\s+(\S+)/.exec(m[2]);
    if (!prof || !markers.some((k) => prof[1].includes(k))) continue;
    if (!existsSync(prof[1])) {
      try {
        process.kill(Number(m[1]), "SIGKILL");
        killed.push(Number(m[1]));
      } catch (e) { /* gone already */ }
    }
  }
  return killed;
}
