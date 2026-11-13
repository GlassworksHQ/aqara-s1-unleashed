# Framework restart: why the S1 sometimes "drops off USB" during app reinstalls and restarts

**Status (2026-11-12): partly understood.** On the S1, a fast run of kiosk process restarts can
break Android 10's ActivityManager. Android then restarts its own framework (`system_server`), a
"soft reboot". The panel recovers by itself in about a minute. We know the signature and roughly what
triggers it. We don't have a fix, and the mitigation below is untested.

Device: Aqara Panel Hub S1 Plus, Android 10 (build `QP1A.191105.004`, kernel 4.9.170), S1 Kiosk 0.4.3
and test builds, dev boot image with adb root. Tools: adb (platform-tools), `dumpsys dropbox`.

## Question

Twice on 2026-11-12 the panel stopped answering in the middle of shared-panel work:

- at 13:48, during a perf run (`tools/perf/run.sh`), adb lost the device, and the panel needed a
  restart by hand;
- at 15:21, while S1 Kiosk 0.4.3 was being reinstalled over a test build, the kiosk vanished from
  `ps`, `dumpsys activity` failed with error -32, and the load average went over 40.

Was this our app, the panel, or adb?

## What we tried

1. **Read the crash buffer after the 15:21 event** (2026-11-12, verified)
   ```
   $ adb logcat -b crash -d
   E AndroidRuntime: Process: sh.edm.s1kiosk
   E AndroidRuntime: java.lang.SecurityException: Unable to find app for caller
     android.app.IApplicationThread$Stub$Proxy@… when publishing content providers
   …
   E AndroidRuntime: DeadSystemException: The system died; earlier logs will point to the root cause
   ```
   New kiosk processes died while binding, because ActivityManager no longer knew them. Other apps
   died with `DeadSystemException`, which means `system_server` went down.

2. **Read the dropbox entries** (2026-11-12, verified; root needed to read the files)
   ```
   $ adb shell "su -c 'ls -l /data/system/dropbox'"
   $ adb shell "su -c 'cat /data/system/dropbox/system_server_wtf@<timestamp>.txt'"
   $ adb shell "su -c 'cat /data/system/dropbox/system_server_crash@<timestamp>.txt'"
   … system_server_wtf@…   (ActivityManager)
   … system_server_crash@… (the kiosk's MainActivity)
   … SYSTEM_RESTART@…      (15:23)
   ```
   Every `system_server_wtf` entry has the same cause:
   ```
   android.util.Log$TerribleFailure: Exception thrown during bind of ProcessRecord{… sh.edm.s1kiosk/u0a83}
     at com.android.server.am.ActivityManagerService.attachApplicationLocked(ActivityManagerService.java:5128)
   Caused by: java.lang.IndexOutOfBoundsException: Index: -1, Size: 18
     at java.util.ArrayList.add(ArrayList.java:483)
     at com.android.server.am.ProcessList.updateLruProcessLocked(ProcessList.java:2947)
   ```
   ActivityManager's list of running processes (the LRU list) had gone inconsistent. Each new kiosk
   process then failed to attach. Its `MainActivity` crashed with `SecurityException: Failed to find
   PID …` from inside `SetupOverlay`'s `EditText` (the first system service lookup it makes). The
   app restarted again, and the loop kept going until `system_server` restarted.

3. **Counted the entries per minute** (2026-11-12, verified)

   | When | `system_server_crash` | `system_server_wtf` | Restart | What was running |
   |---|---|---|---|---|
   | 2026-11-11 23:51–23:57 | ~10 a minute | ~17 a minute | not recorded | another session's work on the panel |
   | 2026-11-12 13:48 | 4 | 21 | the panel was restarted by hand | perf run: repeated force-stop and cold start |
   | 2026-11-12 15:21–15:23 | 20 | 20 | `SYSTEM_RESTART` at 15:23 | `adb install -r` of 0.4.3 over a test build, two installs about 4 minutes apart |

   Counted per minute from the file dates:
   ```
   $ adb shell "su -c 'ls -l /data/system/dropbox'" | grep -E 'system_server_(crash|wtf)|SYSTEM_RESTART' \
       | awk '{print $6, substr($7,1,5), $8}' | sed 's/@.*//' | sort | uniq -c
   ```

   The 13:48 "USB drop" shows the same ActivityManager failure at the same minute. That it caused
   the adb disconnect is likely but **untested**; a separate cable or adb fault isn't ruled out.

## Result

| Claim | Evidence |
|---|---|
| The failure is Android 10's ActivityManager, not our code: `updateLruProcessLocked` adds at index -1 | `system_server_wtf` entries, attempt 2 |
| **Hypothesis (untested):** a fast churn of kiosk processes triggers it (reinstall, force-stop and cold start, the app's own relaunch), and the app's restart behaviour may contribute | timing only, attempt 3; no test separates the two yet |
| It ends in a framework restart, and the panel recovers by itself | `SYSTEM_RESTART` at 15:23; at 15:24 the kiosk was resumed, its service in the foreground, still 0.4.3 and `thread_router` |
| The installed APK and the app's settings file came through unchanged | same APK hash (`8b6a5f00…`) and same `kiosk.xml` hash before and after; nothing else was checked |

Likely why the kiosk is hit (**untested**): it runs several processes (`:boot`, GeckoView's GPU,
crash helper and tab processes), and both the system and our own `BootReceiver` / `KioskService` start
it again at once after a kill. That makes many attach and death events overlap in ActivityManager.

## How to reproduce

Not reproduced on purpose; it costs the shared panel a framework restart. The pattern so far:

1. Install a test build with `adb install -r <test-build.apk>`, wait for dex2oat (~25 s).
2. Install the release over it with `adb install -r -d <release.apk>` while the app is running.
3. Watch `adb shell "su -c 'ls -lt /data/system/dropbox | head'"` for `system_server_wtf` entries.

This installs the app, and the failure adds dropbox entries and restarts the framework. We only
checked that the APK and `kiosk.xml` came through unchanged; other storage wasn't checked. Undo: wait for the restart, then check the app version
and the radio mode.

## Mitigation for anyone driving the dev panel (untested)

- Leave at least a minute between installs, and wait until `dumpsys activity activities` shows the
  kiosk resumed before the next install.
- After any install or perf run, check `ls -lt /data/system/dropbox | head` (root) for new
  `system_server_wtf` entries before going on.
- If adb disappears, wait about two minutes before unplugging: the framework restart brings it back.

## Open questions

- Does the same happen on stock Aqara firmware with the Aqara launcher, or only with a multi-process
  app that restarts itself? The next test is a fast install loop of a single-process test app.
- Is it the AOSP bug fixed later in `ProcessList.updateLruProcessLocked`? Compare with the Android 11
  source of that method.
- Would one start path (only `KioskService` restarting the activity, not `BootReceiver` too) stop it?
