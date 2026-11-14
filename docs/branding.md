# Branding

This repository is [Glassworks](https://www.glassworks.dev), with the Aqara S1 Plus as its reference
frame, so it follows the Glassworks brand. This page says where the brand lives and which assets each part of this repo
uses. It doesn't restate the rules. When this page and glassworks.dev disagree, glassworks.dev wins.

## Where to find it

| What | Where |
|---|---|
| Mark, colour, type, motion, and all downloads | [glassworks.dev/brand](https://www.glassworks.dev/brand) |
| Built on Glassworks badge and its rules | [glassworks.dev/brand#badge](https://www.glassworks.dev/brand#badge) |
| Core, pane and Kiln badges | [glassworks.dev/brand#parts](https://www.glassworks.dev/brand#parts) |
| Licence, status and roadmap badges (instead of shields.io) | [glassworks.dev/brand#info](https://www.glassworks.dev/brand#info) |
| README banners (core, Kiln, libraries, frames) | [glassworks.dev/brand#banners](https://www.glassworks.dev/brand#banners) |
| What you may and may not do with the names | [glassworks.dev/trademarks](https://www.glassworks.dev/trademarks) |
| Source of the assets, and the generator | [GlassworksHQ/glassworks.dev](https://github.com/GlassworksHQ/glassworks.dev): `public/brand/`, `scripts/brand/` |

Every badge and banner comes as `-on-dark.svg` and `-on-light.svg`. Text is outlined, so they need
no fonts. Each section on /brand has a README snippet you can copy. It uses `<picture>`, so the
image follows GitHub's theme.

## What each part of this repo uses

| Part | Badge or banner | Where it goes |
|---|---|---|
| The repository's front page | **Glassworks banner** (`banner-glassworks-on-*.svg`), then the status and roadmap badges | Top of [README.md](../README.md). /brand gives this banner to the org profile and the main README. It has no licence badge, because the repository is mixed: MIT, with the core under MPL-2.0 |
| S1 Kiosk, the app on the core | **Built on Glassworks** (`badge-on-*.svg`) | Top of [app/README.md](../app/README.md). The badge rules also ask for it on the app's About screen |
| A library in `app/libs/` | **Library banner** (`banner-library-<name>-on-*.svg`), then the MIT licence, status and roadmap badges | Top of that library's README. Copy the header of [improv's README](../app/libs/improv/README.md) |
| Libraries in a list or table, where a banner doesn't fit | **Library badge** (`badge-library-on-*.svg`) | E.g. the Libraries section of [principles.md](principles.md) |
| [Glassworks Principles](principles.md) | **Principles banner** (`banner-principles-on-*.svg`), then a part badge under each part's heading | `docs/principles.md` |
| The Glassworks core (`dev.glassworks.*`), including the pane API | **Core banner** (`banner-core-on-*.svg`) as the header, and the **core badge** (`badge-core-on-*.svg`) | `app/kiosk/src/main/java/dev/glassworks/README.md` |
| A pane | **Pane badge** (`badge-pane-on-*.svg`) | The pane's README |
| [Clarity](perf.md), the perf test and grade every frame follows | **Core badge** (`badge-core-on-*.svg`) alone, under the title. No banner | `docs/perf.md`. Its tools in `tools/perf` stay plain, like every dev tool |
| The installer (`kiln/`, `tools/install.sh`) | **Kiln badge** (`badge-kiln-on-*.svg`) | Kiln's README. The S1 installer is still S1-branded until it moves into Kiln |
| A frame (`frames/<id>/`) | **Frame banner** (`banner-frame-<id>-on-*.svg`), then the Built on Glassworks badge, the Kiln badge if Kiln installs it, and the status, roadmap and Clarity badges | Top of `frames/<id>/README.md`. glassworks.dev draws it from the frame's `frame.json` (see [Adding a frame banner](#adding-a-frame-banner)) |

Device-specific code lives in the frame, so it doesn't get a badge of its own.

## Licence, status and roadmap

Use the Glassworks info badges, not shields.io: a licence badge, `badge-roadmap`, and one status
badge for the stage the project is at: `badge-status-planned`, `badge-status-under-development`,
`badge-status-alpha`, `badge-status-beta` or `badge-status-stable`. Each comes as `-on-dark.svg` and `-on-light.svg`. They're on [/brand#info](https://www.glassworks.dev/brand#info), with a README snippet.
The licence badge is `badge-license` (MPL-2.0) for the Glassworks core and `badge-license-mit` (MIT)
for the libraries in `app/libs/`, which are MIT like the rest of this repo.
They sit on the same field as the part badges, so a header reads as one set. If you need one that
isn't there, add it to the generator. Only a live value, such as the latest release version, still
needs shields.io or a release link.

## Adding a library

All libraries share one banner drawing, so only its name and one-line description change:

1. In [glassworks.dev](https://github.com/GlassworksHQ/glassworks.dev), add the library to
   `src/lib/libraries.json`: its `name`, which is the folder name (e.g. `esphome-api`), and a one-line
   `description`.
2. Run `pnpm brand` there, and open a PR with the new SVGs.
3. Once that is deployed, put the banner snippet from
   [/brand#banners](https://www.glassworks.dev/brand#banners) at the top of the library's README,
   with the file name swapped for `banner-library-<name>`.

CI checks step 3: `tools/check-brand-images.py` (in the scripts job) fails while a README points at
a banner or badge that glassworks.dev doesn't serve yet.

Don't draw a banner or badge by hand, and don't recolour one. If one is missing, add it to the
generator.

## Adding a frame banner

Every frame gets its own banner, drawn from its `frames/<id>/design/frame.json`: the device from
`model`, the name with `brand` set light, `frames/<id>`, and `tagline` (at most 50 characters). It
has no hue of its own: a frame is hardware, the bottom plate of the stack.

1. In this repo, give the frame's `frame.json` a `brand` (the maker's part of `name`, if any) and a
   `tagline`, and merge it.
2. In [glassworks.dev](https://github.com/GlassworksHQ/glassworks.dev), move the `vendor/s1-unleashed`
   submodule pin to that commit, run `pnpm brand`, and open a PR with the new SVGs. Its build fails
   if a frame's banner isn't the one its `frame.json` gives.
3. Once that is deployed, put the banner at the top of the frame's README, with the file name
   `banner-frame-<id>`.

The frame's README also carries its Clarity badge, `https://www.glassworks.dev/frames/<id>/clarity-on-dark.svg`
and `-on-light.svg`. The site draws it at build time from the frame's `design/clarity.json` (none
exists yet, so it reads "not measured"), and `tools/check-brand-images.py` doesn't check it: that tool covers
`/brand/` images only.

## Colour, in short

The full palette is on [/brand#colour](https://www.glassworks.dev/brand#colour). These four rules
matter most when you build UI here:

- **Sky to violet** means "on" or "lit".
- **Ember** (orange to rose) belongs to Kiln and the installer only.
- **Green, amber and rose** mean status (ready, working, problem), and nothing else.
- The panel's own screens are always night (`#0a0b0f`), even when the OS is in light mode.

## S1's own branding

The boot splash and boot animation show the Glassworks stack and wordmark, drawn in #63, with the
frame's name under them. The social preview still shows S1's older Glass Tile mark. Both live in
[`branding/`](../branding/glass-tile/README.md) and use the same colours and type (Inter).
