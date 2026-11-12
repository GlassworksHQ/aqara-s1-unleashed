# Frames

A **frame** is one device's definition: its installer recipe, and the design data that describes it to
people. Each frame has a folder here, named by its id.

| Folder | What |
|---|---|
| `<id>/installer/` | Kiln's recipe for the device (`@glassworks/<id>-installer`). Only frames that install from Kiln have one |
| `<id>/design/frame.json` | What the device looks like and what the catalog says about it. Every frame has one |
| `<id>/design/recovery.json` | The steps into recovery mode, as drawing data (format: [docs/design/kiln](../docs/design/kiln/README.md#the-recovery-section)) |

| Frame | Status | Installer | Design |
|---|---|---|---|
| [`aqara-s1-plus`](aqara-s1-plus) | Beta | yes | `frame.json`, `recovery.json` |
| [`lululemon-mirror`](lululemon-mirror) | Planned | not yet | `frame.json` only (no recovery drawing yet) |

## `design/frame.json`

The catalog fields from planning's `kiln.md`, and the `model` section from decisions.md ("frame.json
describes the device's look in millimetres"). Kiln's picker reads it, and glassworks.dev can too.

| Field | What |
|---|---|
| `id` | The frame's id, the same as its folder (and Kiln's route, `/<id>`) |
| `name` | The product's name, e.g. `Aqara S1 Plus` |
| `aka` | Optional. Other names it is sold under |
| `status` | `planned`, `development`, `alpha`, `beta` or `stable` (the status badges) |
| `summary` | One sentence on why this device |
| `project` | Optional. `{ name, href }` of the frame's own project |
| `kiln` | Optional. Kiln's route, once the frame installs from Kiln |
| `model` | The device's look in mm, for the 3D mockup (below) |
| `recovery` | Optional. The recovery drawing data's file, next to this one |
| `specs` | Hardware rows: `{ label, value }`. Labels: `Size`, `SoC`, `Android`, `Screen`, `Radio`, `Presence`, `Audio`, `Extras`, `Mount`, `Root` |
| `panes` | For each pane (`assist`, `timers`, `sendspin`, `radio`, `sensors`, `base`): `{ support, note? }`. Support is a status or `depends`, `unknown`, `no` |
| `install` | `{ how }`: one sentence on how it installs |
| `ratings` | The nine install ratings, each `{ value, detail? }`: `difficulty`, `reversible`, `modifiesAndroid`, `root`, `opensDevice`, `vendorApps`, `wipes`, `backup`, `updates` |
| `ratingsNote`, `notice` | Optional. A sentence of context; something a buyer must know |
| `unverified` | Optional. `true` when any of it comes from writeups rather than a real device |

`model`:

| Field | What |
|---|---|
| `body` | `{ shape: "rect", width, height, depth, radius }` in mm |
| `bezel` | The frame around the screen, mm |
| `screen` | `{ radius }`, plus `width`, `height` and `top` (mm from the body's top edge) when the screen is smaller than the face minus the bezel, e.g. behind a mirror |
| `sides` | `{ dark, light }`: the body's side colour facing away from the light, and facing it |
| `finish` | `glass` or `mirror` |
| `mount` | `wall`, `floor` or `lean`; `lean` takes `lean`, the degrees the top tilts back |
| `parts` | Optional. `{ kind: "tube", from, to, diameter, color }` outside the body, in mm from its centre (x right, y down, z towards you), e.g. a stand |

There is no SVG illustration per frame: the device is drawn from `model`. That's the site's 3D
mockup (glassworks.dev `deviceGeometry.ts`), which Kiln draws too
(`kiln/apps/kiln/src/components/device/`). Its output is the same as the site's for both frames to
within 0.001 of a stage unit (checked 2026-11-12). The Glassworks mark, badges and banners stay the
supplied SVGs from glassworks.dev/brand ([docs/branding.md](../docs/branding.md)); nothing here
redraws them.

`frame.json` here is the hand-written start of the generated `frame.json` in #35: the Gradle task that
writes `frame.json` takes its catalog and `model` fields from this file, and adds the generated ones
(package name, ABIs (#98), capabilities). It differs from decisions.md's sketch in two names: the
renderer needs side colours (`sides`) where the sketch has `color.body`, and the screen is placed with
`top` where the sketch has `offset`.

`kiln/apps/kiln/test/frame-designs.test.ts` checks every `design/frame.json`: Kiln lists it, every
rating and pane is there, `recovery` exists, and the mockup draws without NaN.

## What came from glassworks.dev

All read from [GlassworksHQ/glassworks.dev](https://github.com/GlassworksHQ/glassworks.dev) at
`main` = `fbfd364f5f87570b3f0a95639340b7ac4cb38a6e` (2026-11-12). Nothing was pushed there.

| Site file | Here | How |
|---|---|---|
| `src/lib/devices.ts`: the `aqara-s1` and `lululemon-mirror` entries | `aqara-s1-plus/design/frame.json`, `lululemon-mirror/design/frame.json` | Data copied; the S1's id is this repo's `aqara-s1-plus` (the site's slug is `aqara-s1`) and its project link is GlassworksHQ's |
| `src/components/deviceGeometry.ts`: `MODELS.s1`, `MODELS.mirror` | The `model` sections of those files | Data copied; the Mirror's stand, computed in code there, written out as 13 tubes |
| `src/components/deviceGeometry.ts`: `geometry()` | `kiln/apps/kiln/src/components/device/geometry.ts` | Ported; takes the model as an argument. Same output to 0.001 |
| `src/components/DeviceModel.tsx`, `DeviceModel.module.css` | `kiln/apps/kiln/src/components/device/` | Ported; colours mapped to the brand tokens and Kiln's device paint |
| `src/components/DeviceCard.tsx`, `DeviceCard.module.css`, `TiltCard.tsx`; from `DeviceBits.tsx`/`.module.css`: `StatusPill`, `StageMark`, `RatingTags`; from `src/lib/devices.ts`: `cardTags`, `statusText`; four glyphs from `Glyphs.tsx` | `kiln/apps/kiln/src/components/frame-card/`, `kiln/apps/kiln/src/frames/catalog.ts` | Ported as one radio-item card |
| `src/app/frames/frames.module.css`: `.grid`, `.add`; `src/app/globals.css`: `.glass` | `kiln/apps/kiln/src/app/globals.css` (`.frames`, `.yours`, `.glass`) | Restyled from the same rules |
| `src/app/globals.css` tokens, `src/lib/markGeometry.ts`, `public/brand/icon-kiln-on-dark.svg` | `kiln/packages/brand/src/` | Generated by `kiln/packages/brand/sync.mjs` (earlier in this PR) |

## How the website reads it

**Recommended: a pinned raw URL, synced into the site by a script.** For each frame, glassworks.dev
fetches

```
https://raw.githubusercontent.com/GlassworksHQ/aqara-s1-unleashed/<commit>/frames/<id>/design/frame.json
```

at a commit pinned in the site repo, writes it into the site's own source, and has a `--check` mode
that fails when the pinned copy changed. That is how Kiln already takes the brand from the site,
the other way round (`kiln/packages/brand/sync.mjs`). Then:

- the site's `devices.ts` entries become `frame.json` files, and its `MODELS` table reads `model`;
- a frame changes on the site only when someone moves the pin, in a reviewed commit;
- nothing needs publishing, and the site's build doesn't depend on this repo being reachable.

The alternatives:

| Way | For | Against |
|---|---|---|
| Pinned raw URL + sync script (recommended) | No release step; reviewed diffs; offline builds | A pin to move by hand |
| An npm package (`@glassworks/frames`) | Versioned like code | A publish pipeline and a package per change; overkill until frames live in their own repos |
| Fetch at build time, unpinned | Always current | An unreviewed change here changes the site; the build needs GitHub |
| A git submodule | Pinned | Submodules in a Vercel build, and the whole repo to fetch |

When frames move to their own repos (#35), the same script points at each frame's released
`frame.json` instead.
