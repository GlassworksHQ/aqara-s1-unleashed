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
| Pane `<id>` | The pane's own classes (its package, and classes elsewhere that only it uses), the libraries only it uses, and its assets under `assets/panes/<id>/` (or a path `parts.json` names for it: the radio's `assets/radio-bundle/`) |
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

Classes a pane alone uses outside its own package are listed with it, so its total isn't short:
`esphome/VoiceAssistantController.kt` (`VoiceAssistantController`, `UrlPlayer`, `WakeWordCatalog`,
used only by `voice/`) is Assist's, and `frame/TimeZoneKeeper` (used only by `TimeZonePane`) is the
time zone pane's. A class nothing matches is the core's, so a pane that moves code out of its package
without saying so here looks smaller than it is.

A new pane needs an entry in `parts.json` (a test checks every pane in the code has one), and a new
dependency is the core's until `parts.json` says otherwise.

## The assets lint

A pane's assets live under `assets/panes/<id>/`. Any other asset that isn't the engine's or the
core's fails the check, and so does a folder under `assets/panes/` that isn't a pane.

One pane ships assets elsewhere: the radio's signed bundle is at `assets/radio-bundle/` in the
release's `armeabi-v7a` APK. `RadioBundle.kt`, `make-bundle.py` and release.yml's checks expect it
there, and moving it would change the update path, which needs a panel. So `parts.json` names that
path as the radio's (`panes.radio.assets`). Assist ships no assets: it downloads its wake word
models at run time.

## Budgets

The budgets are the frame's own, in `frames/<id>/budgets.json`, next to its RAM budgets:

- `size.apk_max_bytes.<abi>`: the whole APK, per ABI.
- `panes.<id>.size_max_bytes`: a pane's own total.

The check reads the budget in the pull request's own branch, so going over passes only when the same
pull request raises the budget; the comment then leads with the change. On the S1 the APK budgets
are the 2026-11-13 release build as it ships plus about 2.5%: 101.17 MB `armeabi-v7a` (with the radio
bundle, as release.yml adds it), so 103.70 MB, and 100.78 MB `arm64-v8a` (without), so 103.30 MB.
Each pane's is about twice its own size then, at least 100 kB; the radio's own size includes the
bundle (3.49 MB, of which the bundle is 3.32 MB), so its budget is 7.00 MB. These are project
choices, so that growth is a decision, not a drift. (The first budgets, 2026-11-13, measured a build
without the bundle, 97.84 MB, which is not what ships; v0.4.3's released APK was 101.08 MB.)

The release build has R8 off (`app/kiosk/build.gradle.kts`, `isMinifyEnabled = false`), so the dex is
the unshrunk code. Turning R8 on would change these numbers and needs its own PR (GeckoView and the
reflection in the app need keep rules).

## In CI

The `size` job in `.github/workflows/pr.yml` measures what ships:
1. builds the radio bundle as release.yml does (`tools/size/radio-bundle.sh`): the same files (the
   radio helper, the pinned `otbr-agent` and `ot-ctl` checked against `kiln/assets.lock.json`, and the
   sisdk runtime checked against `tools/radio/sisdk-runtime.sha256`), signed with a throwaway key
   made in the job. The signature is 64 bytes whichever key makes it, so only `manifest.sig` and the
   manifest's version line differ from the release's. No secret is used. The sisdk runtime is cached
   by its inputs;
2. builds the release APK of the pull request's branch for `armeabi-v7a` (with the bundle, as the
   release does) and `arm64-v8a` (without, as the release does);
3. builds the base commit's the same way, with its own bundle when the bundle's inputs differ.
   Without a base commit, or for an ABI the base doesn't build, the report says so (`base_missing`:
   `no-base-commit` or `base-build-failed`), has no change column for it, and still checks the budget;
4. runs `tools/size/apksize.py report`, which writes the report. Its exit status is 0, 1 over budget
   or on the assets lint (the report is written either way), or 2 on bad input: a bad argument, a
   missing budgets file, an APK whose native libraries aren't its ABI's, or apkanalyzer failing;
5. puts the report in the job summary, and keeps its numbers as the `apk-size` artifact.

**The comment's headline.** When the pull request raises a budget, or adds one the base didn't
have, the comment opens with "This PR raises the size budget." and lists each change, before the
budget check: that is the decision a reviewer is asked to make. A lowered budget opens with "This PR
changes the size budget."

**The PR comment.** `.github/workflows/size-comment.yml` posts the report on the pull request, one
comment updated in place. It runs after PR checks, from the default branch, with permission to
comment, and never runs the pull request's code: it reads only the artifact's numbers, renders them
with the default branch's `apksize.py render`, and
comments only on the pull request whose head is the commit that run built. That is the
`workflow_run` pattern, so it works for pull requests from forks, whose own runs get no write token.
`pull_request_target` would give the pull request's code a write token, and a job summary alone isn't
on the pull request's page. A workflow that runs on `workflow_run` takes effect from the default
branch, so the comment starts once this is merged; until then the job summary has it.

**What `render` accepts.** The report comes from the pull request's own code, so the renderer treats
it as untrusted. It refuses a report whose ABIs, row names, frame or `base_missing` aren't the known
ids and words, whose sizes aren't whole numbers, or whose `rows` aren't objects of rows, and one with
more than 40 rows for an ABI. The findings (failures, budget changes, notes) are plain text: any
character outside letters, digits and simple punctuation becomes `?`, anything with `://` becomes
"(link removed)", each is cut at 300 characters, and each list stops after 20 with "and N more".

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
