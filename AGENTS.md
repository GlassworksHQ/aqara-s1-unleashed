# Agent guide: S1 Unleashed

S1 Unleashed turns the Aqara Panel Hub S1 Plus (Allwinner A133, Android 10, 32-bit userspace) into an
open Home Assistant wall panel. It is also the reference device ("frame") for **Glassworks**, a
device-neutral open panel core that is being pulled out of this repo step by step.

This file applies to the whole repo. Each area has its own file with the details:
[`app/AGENTS.md`](app/AGENTS.md) (the kiosk app), [`kiln/AGENTS.md`](kiln/AGENTS.md) (the browser
installer), [`tools/AGENTS.md`](tools/AGENTS.md) (FEL, U-Boot, root side, radio, install scripts).
Breaking into hardware has its own playbook: [`docs/research/bringup.md`](docs/research/bringup.md).

## Layout

| Path | What | Language |
|---|---|---|
| `app/kiosk` | S1 Kiosk, the GeckoView kiosk app (`sh.edm.s1kiosk`, never rename it) | Kotlin, Android |
| `app/libs/*` | Libraries split out of the app: `esphome-api` (JVM), `esphome-api-android`, `improv`, `bluetooth-proxy` | Kotlin |
| `kiln/` | Kiln, the WebUSB installer (FEL → fastboot → boot patch → adb), served at s1.edm.sh. The page in `src/`, protocol modules in `packages/`, the Next.js app in `apps/kiln` | TypeScript, Vite, Next.js, Rust→WASM |
| `frames/<id>/` | Everything about one frame: its README, its Kiln recipe (`installer/`), its design data (`design/frame.json`, `recovery.json`) and its research notes (`research/`) | TypeScript, JSON, Markdown |
| `packages/` | Packages Kiln and glassworks.dev share: `frame-card`, the frame card and 3D device mockup | TypeScript, React |
| `tools/fel`, `tools/install` | FEL boot, U-Boot board files and patches, the CLI installer | bash |
| `tools/root`, `tools/radio` | Init services in the patched boot image: root helper, update and bundle services | `/system/bin/sh` (mksh), C |
| `docs/` | User guides; `docs/research/` holds the general engineering notes and the index of every note | Markdown |
| `branding/` | Boot logo, boot animation, splash | Python |

## Commands

```sh
cd app && ./gradlew test :kiosk:assembleDebug        # app + libs: unit tests and a debug APK
npm install && npm run stage -w kiln && npm test     # npm workspaces, from the repo root (also: npm run typecheck, npm run build)
tools/install/test/install-tests.sh                  # install scripts, off-device
python3 tools/check-doc-links.py                     # relative links and #anchors in docs
tools/root/build-verify.sh && tools/radio/test/run.sh  # root side, in an armhf container (Docker)
python3 -m unittest discover -s branding/boot/test   # boot generator: frame targets and layout
python3 -m unittest discover -s tools/size/test      # APK size per frame and pane (CI's size job)
python3 branding/boot/generate.py --frame aqara-s1-plus --out branding/glass-tile && git diff --exit-code branding
                                                     # the S1's boot branding rebuilds byte for byte (CI: in python:3.11-slim)
```

CI (`.github/workflows/pr.yml`) runs all of these. Run the ones for the area you touched before
you open a PR.

## Principles

Glassworks is an opinionated, simple Home Assistant panel controller. **Read
[`docs/principles.md`](docs/principles.md) before you propose or build a feature**, and check new
panes against [What makes a pane](app/kiosk/src/main/java/dev/glassworks/README.md#what-makes-a-pane).
In short: HA is the brain, ESPHome is the wire, the dashboard is the UI, and nothing gets installed
in HA. If a request goes against it, say so and point at the page instead of building around it.

## Hard rules

1. **Never brick the panel, and always keep a way back.** Read first, write second. Back up every
   partition you are about to write, hash it, and verify writes by reading them back. Prefer
   RAM-only paths (FEL, `fastboot boot`) to flashing. Every user-facing change needs an undo path:
   `--undo`, `debloat.sh enable`, the installer's restore.
2. **No secrets or device-unique values anywhere**: not in commits, logs, PR bodies, screenshots or
   test fixtures. That covers HA tokens, API keys, Wi-Fi passwords, Thread datasets and network keys,
   serials, MACs, EUI64s, PAN IDs and vbmeta hashes. Use RFC 5737 addresses (`192.0.2.x`),
   `homeassistant.local` and made-up network names in examples and mockups. Crop or blur real ones.
3. **No Aqara binaries or firmware in the repo.** Dumps and backups live outside it (`dumps/` is
   ignored). Describe what you read, and give offsets and symbols, but never commit the bytes.
4. **No GPL payloads shipped by us.** Magisk is downloaded from its official release and checked by
   hash, never vendored. Licences of new dependencies go in `THIRD-PARTY-NOTICES.md` and `LICENSES/`.
5. **Changes to the boot patch, the root helper, the radio or anything in `tools/root` need a real
   panel before merge.** Say so in the PR. Off-device tests alone are not enough there.
6. **Updates must keep installing in place.** Don't change `applicationId`, the signing setup or
   `versionCode` logic. `tools/release/check-update-compat.sh` guards this, so don't weaken it.
7. **Don't drive the shared dev panel blindly.** Other sessions may be using it. Check what is
   installed (`adb shell dumpsys package sh.edm.s1kiosk | grep version`) and which radio mode is on
   before you change anything, and put it back the way you found it.

## Pull requests

- Small PRs against `main`, filled in from `.github/pull_request_template.md`: **What and why**,
  **Tested on**, **Checklist**, **Screenshots**.
- **Tested on** says exactly what ran: a real panel (model, S1 Kiosk version or test build branch,
  HA version), the installer in which browser, or unit tests only. List what you did on the panel,
  step by step, as in #52.
- **Screenshots are required for every UI change.** That covers the app (setup screen, settings
  menu, overlays, voice, timers), the web installer, and anything the boot branding shows. Give
  **before and after** for changes to an existing screen, and after alone for a new screen. Caption
  each image with what to look at. How to capture them:
  - On the panel: `adb exec-out screencap -p > after.png` (1440×720). Wake the screen first with
    `adb shell input keyevent KEYCODE_WAKEUP`.
  - Without a panel: the app's Robolectric mockups at the panel's size
    (`./gradlew :kiosk:testDebugUnitTest --tests '*Mockups*' -Ps1MockupDir=<dir>`); add a mockup
    for a new view. The installer's dev states: `npm run dev -w kiln`, then `?demo=wifi`, `?demo=wifi-fail`, ….
  - *Before* comes from `main`, at the same state and size as *after*.
  - Upload them to the PR body (GitHub user attachments). Don't commit PR screenshots to the repo;
    only images that docs use go in `docs/img/` (as `.webp`).
- **Changelog.** Add a line under **[Unreleased]** in `CHANGELOG.md` when someone running a panel
  or using the installer would notice the change: a feature, fixed behaviour, a new entity, a
  changed default, a new requirement. Internal refactors, CI and docs-only changes usually don't
  get one. The style:
  - Put it under `### Added`, `### Changed` or `### Fixed` (create the heading if it's missing).
  - Start with a **bold one-line summary**, then a sentence or two in plain words about what the
    user sees, not how the code does it.
  - Say if it needs something to reach panels, e.g. "Comes with the radio bundle."
  - The release workflow publishes this section as the release notes, and the first line of each
    version is what Home Assistant shows on the update card.
- **Docs.** Update the frame README's status table (`frames/<id>/README.md`) and `docs/` when
  behaviour, entities or install steps change. Keep `python3 tools/check-doc-links.py` passing.
- Labels: `frame: …`, `pane: …`, `area: …` ([CONTRIBUTING.md](CONTRIBUTING.md#labels)).
- To try a branch on a panel without wiping app data, push it to `test-build/<name>` (admins only)
  and `adb install -r` the artifact. Details in [`app/AGENTS.md`](app/AGENTS.md#trying-a-branch-on-the-panel).

## Document the break-in work

Reverse-engineering and bring-up work is the most expensive knowledge in this project, and the
easiest to lose. Whenever you probe hardware, firmware, a protocol or a vendor binary, **write it
down in the same PR**, even if the attempt failed. Frame-specific notes go in
`frames/<id>/research/`, general ones in `docs/research/`:

- What you tried, the exact commands, and what came back (redacted logs).
- What it rules out. Dead ends save the next person days; the S1's `findings.md` on the stock USB burn mode
  is the model for this.
- How sure you are: **verified** (ran it, with the date), **read** (from source or a binary, with
  the file and offset), or **untested**.
- Scripts you used to find it. Put reusable ones under `tools/` with a header comment that says
  what they touch (RAM only? read-only?). Don't leave them only on your machine.

Use [`docs/research/TEMPLATE.md`](docs/research/TEMPLATE.md) for a new note, add it to the table
in [`docs/research/README.md`](docs/research/README.md) (the index of every note), and, for a
frame's note, to that frame's README. The general approach and the S1's lessons
are in [`docs/research/bringup.md`](docs/research/bringup.md).

## Writing style

Docs, comments, commit messages and PR bodies use plain, short sentences. Say what was measured and
when (`2026-11-10`). Prefer tables for comparisons and numbered steps for procedures. Don't oversell:
"experimental" and "not yet met a real base" are fine to write. Mail to upstream projects (U-Boot,
TF-A, Magisk, HA) is terse and follows their own conventions.

## Branding

Follow the Glassworks brand: [glassworks.dev/brand](https://www.glassworks.dev/brand). Which badge or
banner each part of this repo uses, and how to add one for a new library, is in
[`docs/branding.md`](docs/branding.md). Use the supplied SVGs. Don't redraw or recolour them. Ember is
Kiln's colour only, and status colours mean status only.

**Give every README you create a header, and check the README of any part you touch.** Copy the
header from the example in the table, then change the name, the tagline and the status:

| The README is for | Header | Copy it from |
|---|---|---|
| The repository's front page (the root `README.md`) | `banner-glassworks`, centred title and tagline, then the status and roadmap badges | [README.md](README.md) |
| A library: something another project could use on its own (`app/libs/*`, `packages/*`, or such a package in `kiln/packages/`) | `banner-library-<name>`, centred title and tagline, then the MIT, status and roadmap badges | [improv](app/libs/improv/README.md) |
| The Glassworks core or a pane | `banner-core` or `banner-pane`, title and tagline, then the part badge, licence, status and roadmap badges | [core](app/kiosk/src/main/java/dev/glassworks/README.md) |
| Kiln | `banner-kiln`, title and tagline, then the Kiln, MIT, status and roadmap badges | [Kiln](kiln/apps/kiln/README.md) |
| An app or product built on the core | Title and tagline, then Built on Glassworks, MIT, status and roadmap badges. No banner | [S1 Kiosk](app/README.md) |
| A frame (`frames/<id>/README.md`) | `banner-frame-<id>`, centred title and tagline, then Built on Glassworks, the Kiln badge if Kiln installs it, the status, roadmap and Clarity badges | [aqara-s1-plus](frames/aqara-s1-plus/README.md) |
| Kiln's internals and design work | The Kiln badge alone | [docs/design/kiln](docs/design/kiln/README.md) |
| A Glassworks spec in `docs/` that every frame follows (e.g. the perf grade) | The core badge alone, under the title | [principles.md](docs/principles.md) (its part badges) |

Not every README gets one: research notes, scripts in `tools/*` (the doc they serve carries the badge), the old S1 installer in `kiln/src`
and S1's own branding stay plain. The S1's own detail (status, install, radio, FAQ) lives in
[its frame README](frames/aqara-s1-plus/README.md), not the root one.

- **A new library needs its banner first.** If `banner-library-<name>` isn't on
  [/brand#banners](https://www.glassworks.dev/brand#banners), add the library to glassworks.dev
  (steps in [docs/branding.md](docs/branding.md#adding-a-library)) and say in the PR that it waits on
  that deploy. Don't merge a README that points at a banner that isn't live yet. The banner's line
  fits about 50 characters: render it (`rsvg-convert`) in both themes and look before you open that PR.
- **A fork credits upstream in the header**: the second line under the title says "A fork of
  [owner/repo](link) (licence).", and the body records the upstream commit. Keep upstream's
  copyright in `THIRD-PARTY-NOTICES.md`.
- **Use the Glassworks info badges, not shields.io.** The licence badge is `badge-license-mit` for
  MIT and `badge-license` for MPL-2.0. Pick the one status badge that's true now, and change it when
  the stage changes.
- Every image is a `<picture>` with `-on-dark.svg` and `-on-light.svg`, so it follows GitHub's theme.
  Alt text uses the brand's names: "Glassworks Core", "Glassworks Kiln", "<name>, a Glassworks library".

## Naming (Glassworks)

**Frame** = a device definition (the S1 is `aqara-s1-plus`). **Pane** = a plugin (radio, wall base,
voice, Sendspin), which declares the hardware it `requires`. **Kiln** = the installer (`kiln/` plus
`tools/install.sh`). New Kotlin packages for the core go under `dev.glassworks.*`. The S1 app keeps
`sh.edm.s1kiosk`, because the boot helpers hardcode it.
