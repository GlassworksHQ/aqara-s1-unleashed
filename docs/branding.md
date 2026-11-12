# Branding

S1 Unleashed is the reference frame for [Glassworks](https://www.glassworks.dev), so it follows the
Glassworks brand. This page says where the brand lives and which assets each part of this repo
uses. It doesn't restate the rules. When this page and glassworks.dev disagree, glassworks.dev wins.

## Where to find it

| What | Where |
|---|---|
| Mark, colour, type, motion, and all downloads | [glassworks.dev/brand](https://www.glassworks.dev/brand) |
| Built on Glassworks badge and its rules | [glassworks.dev/brand#badge](https://www.glassworks.dev/brand#badge) |
| Core, pane and Kiln badges | [glassworks.dev/brand#parts](https://www.glassworks.dev/brand#parts) |
| Licence, status and roadmap badges (instead of shields.io) | [glassworks.dev/brand#info](https://www.glassworks.dev/brand#info) |
| README banners (core, Kiln, libraries) | [glassworks.dev/brand#banners](https://www.glassworks.dev/brand#banners) |
| What you may and may not do with the names | [glassworks.dev/trademarks](https://www.glassworks.dev/trademarks) |
| Source of the assets, and the generator | [GlassworksHQ/glassworks.dev](https://github.com/GlassworksHQ/glassworks.dev): `public/brand/`, `scripts/brand/` |

Every badge and banner comes as `-on-dark.svg` and `-on-light.svg`. Text is outlined, so they need
no fonts. Each section on /brand has a README snippet you can copy. It uses `<picture>`, so the
image follows GitHub's theme.

## What each part of this repo uses

| Part | Badge or banner | Where it goes |
|---|---|---|
| S1 Unleashed as a whole (a frame) | **Built on Glassworks** (`badge-on-*.svg`) | Top of [README.md](../README.md), already there. The badge rules also ask for it on the app's About screen |
| A library in `app/libs/` | **Library banner** (`banner-library-<name>-on-*.svg`) | Top of that library's README |
| The Glassworks core (`dev.glassworks.*`), including the pane API | **Core banner** (`banner-core-on-*.svg`) as the header, and the **core badge** (`badge-core-on-*.svg`) | `app/kiosk/src/main/java/dev/glassworks/README.md` |
| A pane | **Pane badge** (`badge-pane-on-*.svg`) | The pane's README |
| The installer (`web/`, `tools/install.sh`) | **Kiln badge** (`badge-kiln-on-*.svg`) | Kiln's README. The S1 installer is still S1-branded until it moves into Kiln |

Device-specific code lives in the frame, so it doesn't get a badge of its own.

## Licence, status and roadmap

Use the Glassworks info badges, not shields.io: `badge-license`, `badge-roadmap`, and one status
badge for the stage the project is at: `badge-status-under-development`, `badge-status-alpha`,
`badge-status-beta` or `badge-status-stable`. Each comes as `-on-dark.svg` and `-on-light.svg`. They're on [/brand#info](https://www.glassworks.dev/brand#info), with a README snippet.
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

Don't draw a banner or badge by hand, and don't recolour one. If one is missing, add it to the
generator.

## Colour, in short

The full palette is on [/brand#colour](https://www.glassworks.dev/brand#colour). These four rules
matter most when you build UI here:

- **Sky to violet** means "on" or "lit".
- **Ember** (orange to rose) belongs to Kiln and the installer only.
- **Green, amber and rose** mean status (ready, working, problem), and nothing else.
- The panel's own screens are always night (`#0a0b0f`), even when the OS is in light mode.

## S1's own branding

The Glass Tile boot splash, boot animation and the social preview belong to S1 Unleashed, not to
Glassworks. They live in [`branding/`](../branding/glass-tile/README.md) and use the same colours and
type (Inter).
