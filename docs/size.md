# APK size per frame and per pane

<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-core-on-dark.svg">
      <img alt="Glassworks Core" src="https://www.glassworks.dev/brand/badge-core-on-light.svg" height="30">
    </picture>
  </a>
</p>

Every pull request builds the release APKs, one per ABI, and splits each into its parts: the engine
(GeckoView), the core, each pane, and the libraries more than one of them use. It shows how each part
changed against the base branch, and fails when an APK or a pane is over the frame's size budget,
unless the same pull request raises that budget. This is the size half of
[#68](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/68); RAM is the other half, measured
on the device by [Clarity](perf.md#ram).

## What it reports

`tools/size/apksize.py` reads each APK, and apkanalyzer's dex listing (`apkanalyzer dex packages
--defined-only`, from the Android SDK's command-line tools). For each ABI:

| Part | What is in it |
|---|---|
| Engine (GeckoView) | `org.mozilla.*` and `org.webrtc.*` classes, Gecko's native libraries (`libxul.so` and the rest) and `assets/omni.ja` |
| Core | Everything else: the shell, `dev.glassworks.*` outside the panes, the ESPHome and Improv libraries, Kotlin, AndroidX, resources, and the core's assets (`assets/extension/`, `assets/dexopt/`, `assets/licenses/`) |
| Pane `<id>` | The pane's own classes, the libraries only it uses, and its assets under `assets/panes/<id>/` |
| Shared library `<name>` | A library more than one part uses, counted once and in nobody's own total |
| Zip overhead | The APK's own headers and directory |

Each part is split into dex, native, assets and other, in bytes as stored in the APK, so the parts
add up to the file. The dex files' bytes are shared out by apkanalyzer's per-class sizes.

**Which classes and libraries are whose** is in `tools/size/parts.json`, read from
`app/kiosk/build.gradle.kts` and the imports in `app/kiosk/src/main` and `app/libs` (2026-11-13). The
longest matching prefix wins; a class nothing matches is the core's. On the S1:

| Library | Used by | So |
|---|---|---|
| TensorFlow Lite (and `libtensorflowlite_jni.so`) | `voice/` only | Assist's |
| XZ | `radio/` only | the radio's |
| ZXing | the sign-in QR code | the core's |
| BouncyCastle | `app/libs/esphome-api` (Noise) and Sendspin | shared |
| Media3 | `voice/`, and GeckoView ships the same version | shared |

A new pane needs an entry in `parts.json` (a test checks every pane in the code has one), and a new
dependency is the core's until `parts.json` says otherwise.

## The assets lint

A pane's assets live under `assets/panes/<id>/`. Any other asset that isn't the engine's or the
core's fails the check, and so does a folder under `assets/panes/` that isn't a pane. On 2026-11-13 no
pane ships assets (Assist downloads its wake word models at run time), so the rule holds from now on.

## Budgets

The budgets are the frame's own, in `frames/<id>/budgets.json`, next to its RAM budgets:

- `size.apk_max_bytes.<abi>`: the whole APK, per ABI.
- `panes.<id>.size_max_bytes`: a pane's own total.

The check reads the budget in the pull request's own branch, so going over passes only when the same
pull request raises the budget; the report then notes the change. On the S1 the APK budgets are the
2026-11-13 release build (97.84 MB `armeabi-v7a`, 100.78 MB `arm64-v8a`) plus about 2.5%, and each
pane's is about twice its own size then, at least 100 kB. These are project choices, so that growth
is a decision, not a drift.

The release build has R8 off (`app/kiosk/build.gradle.kts`, `isMinifyEnabled = false`), so the dex is
the unshrunk code. Turning R8 on would change these numbers and needs its own PR (GeckoView and the
reflection in the app need keep rules).

## In CI

The `size` job in `.github/workflows/pr.yml`:
1. builds the release APK of the pull request's branch for `armeabi-v7a` and `arm64-v8a`;
2. builds the base commit's the same way (without one, or if it doesn't build, there is no change
   column and the budget is still checked);
3. runs `tools/size/apksize.py report`, which writes the report and fails the job over budget or
   on the assets lint;
4. puts the report in the job summary, and keeps its numbers as the `apk-size` artifact.

**The PR comment.** `.github/workflows/size-comment.yml` posts the report on the pull request, one
comment updated in place. It runs after PR checks, from the default branch, with permission to
comment, and never runs the pull request's code: it reads only the artifact's numbers, renders them
with the default branch's `apksize.py render` (which refuses anything but ids and numbers), and
comments only on the pull request whose head is the commit that run built. That is the
`workflow_run` pattern, so it works for pull requests from forks, whose own runs get no write token.
`pull_request_target` would give the pull request's code a write token, and a job summary alone isn't
on the pull request's page. A workflow that runs on `workflow_run` takes effect from the default
branch, so the comment starts once this is merged; until then the job summary has it.

**Why not Diffuse.** [Diffuse](https://github.com/JakeWharton/diffuse) compares two APKs well, but it
doesn't know panes, and it would be one more pinned binary to fetch and check. The change column here
is the per-part difference of the two reports.

## Run it locally

```sh
cd app && ./gradlew :kiosk:assembleRelease -Ps1Abi=armeabi-v7a && cp kiosk/build/outputs/apk/release/kiosk-release-unsigned.apk /tmp/v7.apk
cd .. && python3 tools/size/apksize.py report --frame aqara-s1-plus --apk armeabi-v7a=/tmp/v7.apk --json /tmp/size.json --md /tmp/size.md
python3 -m unittest discover -s tools/size/test
```

`apkanalyzer` comes from `APKANALYZER`, the `PATH`, or `$ANDROID_HOME/cmdline-tools/latest/bin`.
