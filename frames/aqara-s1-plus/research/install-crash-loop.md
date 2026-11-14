# Install crash loop: why the kiosk can crash-loop for minutes after an install (#248)

**Status (2026-11-14): cause found and reproduced; fix in review, its armed run pending.** The loop
is a bug in Android 10's ActivityManager, not dex2oat. One of the counters in its process list (the
LRU list) drops by one with every kiosk force-stop. At -1, every new process fails to attach and is
started again at once. It stops only when `system_server` crashes on the same bug and the framework
restarts. dex2oat is a bystander: the loop takes its CPU, which is why it seemed to run for 11
minutes. On the dev panel the loop came back on purpose at the 15th force-stop, and a dry-run
watchdog caught it 4 s in. The watchdog now runs inside `s1kiosk_netwatch`. Its armed mode has not
run on a panel yet.

Device: Aqara Panel Hub S1 Plus, Android 10 (security patch 2020-09-05), armeabi-v7a,
`ro.config.low_ram=true`. S1 Kiosk: main test build `5d0a0d2` (the panel's baseline) and test builds
installed over it. Tools: adb (platform-tools), `tools/install/install-churn.py`, the AOSP sources of
`frameworks/base` (`android10-release`, `android11-release`, `android12-release`).

This answers the open questions in [framework-restart.md](framework-restart.md), which found the
same `Index: -1` failure on 2026-11-12.

## Question

After an `adb install -r` of a test build on 2026-11-14 (02:51), the kiosk was started and died
about 347 times in 11 minutes, at load ~39, while dex2oat ran. Other installs of similar builds took
~25 s. Panels in the field update the same way (`s1kiosk_update.sh` runs `pm install -r`), so an
update could leave a panel crash-looping for minutes. Why does it happen, and why only sometimes?

## What we tried

All read-only: logcat, `getprop` and `dumpsys` on the panel, and source reading. Nothing was
installed or changed for this note.

1. **The dexopt settings** (2026-11-14, verified)
   ```
   $ adb shell 'getprop | grep -E "pm.dexopt|dex2oat"'
   [dalvik.vm.dex2oat-flags]: [--no-watch-dog]
   [dalvik.vm.dex2oat-threads]: [3]
   [pm.dexopt.bg-dexopt]: [speed-profile]
   [pm.dexopt.install]: [speed-profile]
   $ adb shell 'dumpsys package dexopt' | grep -A2 '\[sh.edm.s1kiosk\]'
         arm: [status=speed-profile] [reason=bg-dexopt]
   ```
   An install compiles with `speed-profile`, and the nightly background job compiles again later.
   Each install passes a profile to dex2oat (`--profile-file-fd`, `--compilation-reason=install`).
   The APK has a 366-byte `assets/dexopt/baseline.prof` and ~19.6 MB of dex (`classes.dex`,
   `classes2.dex`).

2. **Every install in the panel's logcat, one row each** (2026-11-14, verified)
   ```
   $ adb shell 'logcat -d -b all -v time' > all.txt     # covers 03:08 to 08:50
   $ tools/install/install-churn.py all.txt | grep -E 'time|install'
   time          kind        killed  dexopt  dex2oat wall/cpu starts bindfail  restart
   03:08:39.504  install         12   27.7s       27.5s/17.9s      6        0  -
   03:24:27.399  install         10   18.3s       18.2s/16.8s      5        0  -
   …  (11 more rows like these: 17–29 s wall, 15.8–18.0 s CPU)
   06:09:51.023  install          6   88.9s       88.8s/15.1s     46       46  06:11:04.969
   06:23:24.336  install         12   17.7s       17.6s/16.3s      6        0  -
   ```
   dex2oat needs 15–18 s of CPU every time. In 13 of 14 installs it took 17–29 s. At 06:09 it took
   89 s of wall time for 15 s of CPU, because a loop of 46 failed kiosk starts was running at the
   same time. The 02:51 install is no longer in the buffer, but ~17 s of CPU over 11 minutes fits
   the same picture at load ~39. A cheaper compile filter or a baseline profile would not change
   this: the compile is not the slow part.

   The same table shows that **every install force-stops the kiosk twice**. The first time is when
   the install starts (`installPackageLI`). The second is ~0.3 s after dexopt ends (`pkg removed`).
   In between, Android starts the kiosk again as the home app, and it brings up 5–7 processes
   (main, `:boot`, GeckoView's GPU, crash helper and tab processes), which the second force-stop
   kills. That makes 10–12 kiosk process deaths per install.

3. **What ActivityManager logged in the 06:09 loop** (2026-11-14, verified; PIDs and hashes trimmed)
   ```
   06:09:51.023 I ActivityManager: Force stopping sh.edm.s1kiosk appid=10083 user=-1: installPackageLI
   06:09:51.357 I PackageManager.DexOptimizer: Running dexopt … pkg=sh.edm.s1kiosk … targetFilter=speed-profile
   06:09:51.436 I ActivityManager: Start proc …:sh.edm.s1kiosk/u0a83 for activity {sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity}
   06:09:51.468 E ActivityManager: Exception thrown during bind of ProcessRecord{… sh.edm.s1kiosk/u0a83}
   06:09:51.468 E ActivityManager: java.lang.IndexOutOfBoundsException: Index: -1, Size: 17
   06:09:51.468 E ActivityManager:   at java.util.ArrayList.add(ArrayList.java:483)
   06:09:51.468 E ActivityManager:   at com.android.server.am.ProcessList.updateLruProcessLocked(ProcessList.java:2947)
   06:09:51.468 E ActivityManager:   at com.android.server.am.ActivityManagerService.attachApplicationLocked(ActivityManagerService.java:5121)
   06:09:51.487 I am_proc_start: [0,…,10083,sh.edm.s1kiosk,bind-fail,sh.edm.s1kiosk]
   …  (45 more bind-fail starts, 06:09:51 to 06:11:00)
   06:10:59.153 E AndroidRuntime: java.lang.SecurityException: Unable to find app for caller … when publishing content providers
   06:10:59.211 E ActivityManager: java.lang.NullPointerException: … ProcessRecord.processName … at …recordAppError
   06:11:00.677 E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: android.display
   06:11:00.677 E AndroidRuntime: java.lang.IndexOutOfBoundsException: Index: -1, Size: 17
   06:11:04.969 E Zygote: Exit zygote because system server (pid …) has terminated
   06:11:20.294 V installd: DexInv: --- END '…/sh.edm.s1kiosk-…/base.apk' (success) ---
   ```
   The loop works like this:
   1. ActivityManager attaches the new kiosk process and puts it in its LRU list at index -1. That
      throws. ActivityManager catches it, gives up on that process and starts a new one
      (`bind-fail`).
   2. The old process has already been told to bind. It runs on into `installContentProviders`, and
      ActivityManager no longer knows it, so it dies with `SecurityException: Unable to find app for
      caller`. Reporting that crash then fails inside ActivityManager (`NullPointerException …
      processName`), because the process has no record.
   3. The new process hits step 1 again. Each turn leaves a zombie and costs a fork, so the load
      climbs.

   The loop ended at 06:11:00, when the same `add(-1)` ran on a path that doesn't catch it
   (`ProcessRecord.onStartActivity` on the `android.display` thread). `system_server` died and the
   framework restarted. dex2oat ran on in `installd` and finished 16 s later. "It recovered when
   dex2oat finished" was a framework restart that happened at about the same time.

4. **The 04:23 framework restart has the same cause, without an install** (2026-11-14, verified)
   ```
   04:23:22.677 I ActivityManager: Force stopping sh.edm.s1kiosk appid=10083 user=0: from pid …
   04:23:22.875 E ActivityManager: Exception thrown during bind of ProcessRecord{… sh.edm.s1kiosk/u0a83}
   04:23:22.875 E ActivityManager: java.lang.IndexOutOfBoundsException: Index: -1, Size: 17
   04:23:22.960 E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: android.display
   ```
   This one came from a plain `am force-stop` (a perf run's restart). The loop lasted 0.3 s because
   the uncaught path ran almost at once. The trigger is killing kiosk processes, whether an install
   or a force-stop does it. In the 03:08–06:23 log, 2 of about 44 kiosk force-stops (14 installs
   with two each, and 16 plain ones) ended in the loop.

5. **The bug in the AOSP source** (2026-11-14, read)

   `frameworks/base/services/core/java/com/android/server/am/ProcessList.java`:

   | Branch | `removeLruProcessLocked` | `updateLruProcessLocked` (its own removal) |
   |---|---|---|
   | `android10-release` | `if (lrui <= mLruProcessActivityStart) mLruProcessActivityStart--;` and the same with `<=` for `mLruProcessServiceStart` (lines 2097–2101) | `<` (lines 2825–2829) |
   | `android11-release` | `<` (lines 2600–2604) | `<` |
   | `android12-release` | `<` (lines 2736–2740) | `<` |

   The LRU list is ordered "other" processes, then services, then activities. The two counters mark
   where those sections start. In Android 10, removing the process at the very start of a section
   also moves the start down by one, which is wrong, so the starts drift down as processes die.
   Android 11 fixed it. Once `mLruProcessServiceStart` is -1, a process with no activity yet (any
   process that is just attaching) goes in at `mLruProcesses.add(mLruProcessServiceStart, app)`
   (line 2925), which throws. Nothing ever moves the counter back up, so only a framework restart
   clears it. The panel's line numbers are 22 lines off AOSP's (2947 against 2925). The panel's
   ActivityManager also logs lines that AOSP doesn't (`app.processName = …`), so the vendor changed
   this code. We haven't traced the exact order of removals that takes a start from 0 to -1.

   The S1 is more exposed than most devices: its list is small (17–23 entries, low-RAM build), and
   the kiosk runs 5–7 processes that die together at every restart.

6. **The counters can be read on a running panel** (2026-11-14, verified)
   ```
   $ adb shell 'dumpsys activity processes' | grep 'Process LRU list'
     Process LRU list (sorted by oom_adj, 23 total, non-act at 9, non-svc at 9):
   ```
   `non-act at` is `size - mLruProcessActivityStart` (`ProcessList.dumpLruListHeaderLocked`), so the
   starts are 14 and 14 here, which is healthy. In the broken state a start is below 0, so `non-svc at`
   is larger than `total`. One read takes about 70 ms on the S1. That makes a cheap, exact check
   that needs no logcat.

7. **Is the update service's install different from `adb install`?** (2026-11-14, read)
   No. `/system/bin/pm` on the panel is `cmd package "$@"`, and `adb install` on Android 10 goes
   through `abb` to the same `package install` command. Both stream the APK (`-S`), get the same
   install-time dexopt and the same two force-stops. `s1kiosk_update.sh` then adds one more
   `am start` 3 s later if the kiosk isn't running.

8. **The crash-loop guard** (2026-11-14, read)
   No crash-loop guard is on `main`. Issue #155 is the setup and settings follow-ups. Its PRs (#157,
   #195) don't count crashes, and nothing in `app/` or `tools/` matches "crash loop". These crashes
   also happen in `ActivityThread.handleBindApplication`, before `Application.onCreate`. A future
   guard that counts crashes from `onCreate` on would not see them. One that installs a handler
   earlier (`attachBaseContext`) must skip `SecurityException … when publishing content providers`,
   because the app can't fix that and restarting faster only adds to the loop.

9. **Reproduced on the dev panel, with the watchdog in dry-run mode** (2026-11-14 09:06–09:32,
   verified; run by the root thread from the #248 panel plan)

   Setup: S1 Kiosk main test build `5d0a0d2` (hash `4a9d4a76…`), radio `thread_router`. A sampler on
   the computer read the LRU header every 2 s. The draft watchdog ran on the panel as root in dry-run
   mode, every 10 s. Then `am force-stop sh.edm.s1kiosk` every ~40 s, letting the home app relaunch
   the kiosk each time.
   ```
   $ adb shell 'dumpsys activity processes' | grep 'Process LRU list'    # before each force-stop
   stop 1 before:   Process LRU list (sorted by oom_adj, 23 total, non-act at 9, non-svc at 9):
   stop 2 before:   Process LRU list (sorted by oom_adj, 24 total, non-act at 11, non-svc at 11):
   …
   stop 15 before:  Process LRU list (sorted by oom_adj, 24 total, non-act at 24, non-svc at 24):
   ```
   The start (`total - non-act`), from the 2 s samples:

   | Time | After | Total | Activity start | Service start |
   |---|---|---|---|---|
   | 09:06:21 | (before) | 23 | 14 | 14 |
   | 09:06:26 | stop 1 | 20 | 13 | 13 |
   | 09:07:06 – 09:15:16 | stops 2–14 | 18–21 | 12, 11, … 0 (one less at each stop) | the same |
   | 09:15:57 | stop 15 | 18 | **-1** | **-1** |
   | 09:17:24 – 09:17:38 | framework restart | 1 → 27 | = total | = total |
   | 09:19:37 | | 27 | 24 | 24 |

   Every kiosk force-stop moved both starts down by exactly one, 15 times out of 15. Between stops
   they didn't move. After a framework restart the starts begin at the list's size.

   The watchdog's trace:
   ```
   09:15:50 total=24 act=0 svc=0
   09:16:01 LRU start below 0 (total=18 act=-1 svc=-1), read 1
   09:16:11 LRU start below 0 (total=18 act=-1 svc=-1), read 2
   09:16:11 would restart the framework (dry run)
   …  (dumpsys kept answering every 10 s through the loop)
   09:17:24 no LRU header (framework down?)
   ```
   The loop, from the events buffer (`am_proc_start … bind-fail`) and the crash buffer:

   | | |
   |---|---|
   | First `bind-fail` start | 09:15:59.355 (the start read -1 at 09:15:57) |
   | Ended by | `FATAL EXCEPTION IN SYSTEM PROCESS: ActivityManager`, `Index: -1, Size: 18` at 09:17:16.720; `Exit zygote` at 09:17:17.156 |
   | Length | ~78 s |
   | `bind-fail` starts | 1040 for the kiosk (~13 a second), and 11 for Aqara's own apps (`com.lumi.systemmanager.logService`, `com.lumi.settings`, `…aqaraGesture`): no new app process could start |
   | Kiosk crashes (`Unable to find app for caller`) | 1040 |
   | Afterwards | kiosk resumed, the same APK hash, radio `thread_router`; LRU 27 total, starts 24 |

   Armed, the watchdog would have killed `system_server` at 09:16:11, 66 s before it crashed by
   itself. `tools/install/install-churn.py` found no rows in these dumps: the loop's stack traces
   overwrote the `system` buffer (it began at 09:16:13), so the force-stop lines were gone. The
   `events` buffer kept the loop itself.

## Result

| Claim | Evidence |
|---|---|
| The loop is ActivityManager failing to attach each new kiosk process (`add(-1)` in `updateLruProcessLocked`), then retrying at once (`bind-fail`) | attempt 3 |
| It ends only when `system_server` dies on the same bug; the panel then recovers by itself. How long that takes is luck (0.3 s at 04:23, 69 s at 06:09, ~11 min at 02:51) | attempts 3, 4 |
| The cause is Android 10's `<=` in `ProcessList.removeLruProcessLocked`, fixed in Android 11 | attempt 5 (read; the vendor's copy may differ) |
| Killing kiosk processes triggers it: an install, or a plain force-stop | attempts 2, 4 |
| dex2oat takes 15–18 s of CPU per install, and the loop is what stretches it (89 s at 06:09) | attempt 2 |
| A different compile filter or a baseline profile would not fix it | attempts 1, 2 |
| Each install kills 10–12 kiosk processes: two force-stops, with a cold start that has no future in between | attempt 2 |
| `dumpsys activity processes` shows the broken state exactly, in ~70 ms | attempt 6 |
| The update service installs the same way as `adb install` | attempt 7 |
| Each kiosk force-stop moves both starts down by exactly one; the loop starts at the force-stop that takes them from 0 to -1 | attempt 9 (15 of 15) |
| While the starts are below 0, no new app process can start, not only the kiosk | attempt 9 |
| The header check sees the broken state within one read, and `dumpsys` keeps answering during the loop | attempt 9 |

What can be done, best first:

1. **Restart the framework as soon as the counter breaks (built; armed run pending).**
   `s1kiosk_netwatch.sh` reads the header every 15 s (`dumpsys activity processes sh.edm.s1kiosk`,
   ~35 ms). If a start is below 0 on two reads in a row, it kills `system_server`, at most once per
   10 minutes. That is what ends the loop anyway, only sooner and every time: 15–30 s of loop and one
   framework restart, instead of up to 11 minutes. Init brings zygote and the framework back. The
   radio helper is a separate init service, so it should keep running (to be checked in the armed
   run). It lives in netwatch so the boot image's file list stays the same for the CLI and Kiln. It
   still needs a new boot image, so it reaches panels only when they run the installer again.
2. **Kill fewer kiosk processes per update.** Each death is another chance to move the counter. The
   cold start between the two force-stops is wasted. Fewer GeckoView processes (GPU process, crash
   helper, content processes) would mean fewer deaths per restart. That changes the engine
   and needs a Clarity run.
3. **Report the update correctly when the framework restarts mid-install (built).** If
   `system_server` dies while `pm install` waits, pm fails even though the package may be committed.
   `s1kiosk_update.sh` now notes `system_server`'s PID before the install. If pm fails and that PID
   changed, it waits up to 5 minutes for PackageManager, then makes its usual checks (versionCode
   went up, nothing else changed). Its status is also cut to 91 bytes, the most a property holds;
   a longer one was refused by `setprop` and left the status at `running`.
4. **Not worth doing for this bug:** a cheaper `pm.dexopt.install`, a real baseline profile, or
   holding off the kiosk's relaunch until dexopt ends. The system relaunches the home app on its own,
   and none of these touches the counter.

## How to reproduce

This is what attempt 9 did. The panel plan for #248 (kept with the root's notes, not in the repo) has
every command. In short, on a panel with the kiosk running:

1. Read the counters (read-only): `adb shell 'dumpsys activity processes' | grep 'Process LRU list'`.
   The activity start is `total - non-act`.
2. `adb shell am force-stop sh.edm.s1kiosk`, wait ~40 s for the home app to bring it back, and read
   the counters again. Each force-stop takes one off. Repeat until the start is below 0: that many
   force-stops from a fresh framework (~24 on the S1). This stops and starts the app only.
3. Save `adb shell 'logcat -d -b events -v time'` straight away: the loop overwrites the other
   buffers in seconds.

The loop ends in a framework restart by itself (78 s in attempt 9). Nothing is installed, so there is
nothing to undo. Check that the kiosk is back and the radio mode is unchanged afterwards.

## Open questions

- Which removal takes a start from 0 to -1? The process at index 0 is `system_server`'s own record,
  which is never removed. Attempt 9 shows that it happens, at the force-stop after the start reached
  0, but not which process it was.
- How fast does a panel in normal use drift? A framework restart leaves about 24 force-stops of
  headroom, and an update uses two. Kiosk crashes and Clarity's app restarts count too. Between 06:23
  and 08:49 on 2026-11-14 the dev panel lost more than its counted force-stops explain. With the
  watchdog in place, `sys.s1kiosk.am.status` shows the starts on any panel.
- Could the watchdog restart the framework before the loop instead, for example at night once the
  start is down to 2? That would trade the 15–30 s of loop for a planned restart.
- Is killing `system_server` safe while `pm install` is committing? The bug's own crash does the same
  at random times, but the watchdog should wait for dexopt to finish if it can.
