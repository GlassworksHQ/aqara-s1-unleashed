# Frames

A **frame** is one device's definition: its installer recipe, and the design data that describes it to
people. Each frame has a folder here, named by its id, and that folder is the one home for both: Kiln
and glassworks.dev read them from here.

| Folder | What |
|---|---|
| `<id>/installer/` | Kiln's recipe for the device (`@glassworks/<id>-installer`), an npm workspace of the repo root. Only frames that install from Kiln have one |
| `<id>/design/frame.json` | What the device looks like and what the catalog says about it. Every frame has one |
| `<id>/design/recovery.json` | The steps into recovery mode, as drawing data (format: [docs/design/kiln](../docs/design/kiln/README.md#the-recovery-section)) |

| Frame | Status | Installer | Design |
|---|---|---|---|
| [`aqara-s1-plus`](aqara-s1-plus) | Beta | yes | `frame.json`, `recovery.json` |
| [`lululemon-mirror`](lululemon-mirror) | Planned | not yet | `frame.json` only (no recovery drawing yet) |
| [`generic-android`](generic-android) | Planned | not yet | `frame.json` only. Any Android device as an app, no root. It has no `display`: there is no one screen size |

Every device glassworks.dev lists has a frame here, planned ones included. The site's slugs are
these ids, except one:

| glassworks.dev slug | Frame id here |
|---|---|
| `aqara-s1` | `aqara-s1-plus` |

When the site switches to these files, it should redirect its old `/frames/aqara-s1` to
`/frames/aqara-s1-plus`. The ids here don't change: Kiln's routes, the boot generator and the release
tags (`aqara-s1-plus-v<version>`) use them.

## `design/frame.json`

The catalog fields from planning's `kiln.md`, and the `model` section from decisions.md ("frame.json
describes the device's look in millimetres"). Kiln's picker reads it, and glassworks.dev can too.

| Field | What |
|---|---|
| `id` | The frame's id, the same as its folder (and Kiln's route, `/<id>`) |
| `name` | The product's name, e.g. `Aqara S1 Plus` |
| `brand` | Optional. The maker's part of `name` (`Aqara`), set light in the frame's banner; the rest of `name` is bold. `name` must start with it and a space |
| `aka` | Optional. Other names it is sold under |
| `status` | `planned`, `development`, `alpha`, `beta` or `stable` (the status badges) |
| `summary` | One sentence on why this device |
| `tagline` | Optional. The frame's banner's line under `frames/<id>`, at most 50 characters. The [banner generator](../branding/frame-banner/README.md) fails if it doesn't fit |
| `project` | Optional. `{ name, href }` of the frame's own project |
| `kiln` | Optional. Kiln's route, once the frame installs from Kiln |
| `model` | The device's look in mm, for the 3D mockup (below) |
| `recovery` | Optional. The recovery drawing data's file, next to this one |
| `abis` | The ABIs the device's userspace runs, preferred first, as Android names them (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`). The app's release builds one APK for every ABI some frame lists (below) |
| `display` | `{ width, height }`: the screen in pixels, as Android's boot animation sees it. With `name`, it is all the [boot generator](../branding/boot/README.md) needs |
| `boot` | Optional. The boot branding's limits: `androidApi` (the API level; 25+ gets trimmed frames), `splash` (the bootloader splash format, `allwinner-boot-resource`), `zipBudget` (the largest `bootanimation.zip`, bytes) |
| `specs` | Hardware rows: `{ label, value }`. Labels: `Size`, `SoC`, `Android`, `Screen`, `Radio`, `Presence`, `Audio`, `Extras`, `Mount`, `Root` |
| `panes` | For each pane (`assist`, `timers`, `sendspin`, `radio`, `sensors`, `base`): `{ support, note? }`. Support is a status or `depends`, `unknown`, `no` |
| `install` | `{ how, href? }`: one sentence on how it installs. `href` is only for a frame that installs from somewhere other than Kiln; a frame with `kiln` has none (the S1 installs from Kiln) |
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
mockup (glassworks.dev `deviceGeometry.ts`), now in the shared package
[`packages/frame-card`](../packages/frame-card/README.md), which Kiln draws with. Its output is the
same as the site's for both frames to within 0.001 of a stage unit (checked 2026-11-12). The Glassworks mark, badges and banners stay the
supplied SVGs from glassworks.dev/brand ([docs/branding.md](../docs/branding.md)); nothing here
redraws them.

`frame.json` is written by hand, and the build reads it; nothing generates it (maintainer, 2026-11-13,
#98). It differs from decisions.md's sketch in two names: the renderer needs side colours (`sides`)
where the sketch has `color.body`, and the screen is placed with `top` where the sketch has `offset`.

### `abis`

What the device runs, not what its release ships. The release builds the union of every frame's
`abis`, in frame-id order (`tools/release/frame-abis.sh`, and `app/kiosk/build.gradle.kts` the same
way), so today: `armeabi-v7a` and `arm64-v8a`. `ApkAbiListsTest` checks that the app's update check
(`ApkAbi`) knows exactly that list.

| Frame | `abis` | From |
|---|---|---|
| `aqara-s1-plus` | `armeabi-v7a` | The panel's 32-bit userspace (`ro.product.cpu.abilist` = `armeabi-v7a,armeabi`, read on a panel 2026-11-12) |
| `lululemon-mirror` | `arm64-v8a`, `armeabi-v7a` | The community image's `ro.product.cpu.abilist` (`arm64-v8a,armeabi-v7a,armeabi`, read from its `system.img` 2026-11-12). Not read on a stock unit ([mirror-abis.md](lululemon-mirror/research/mirror-abis.md)) |
| `generic-android` | `arm64-v8a`, `armeabi-v7a` | The ARM tablets and phones it targets. No x86 APK is built |

`kiln/apps/kiln/test/frame-designs.test.ts` checks every `design/frame.json`: Kiln lists it, every
rating and pane is there, `recovery` exists, and the mockup draws without NaN.

## What came from glassworks.dev

All read from [GlassworksHQ/glassworks.dev](https://github.com/GlassworksHQ/glassworks.dev) at
`main` = `fbfd364f5f87570b3f0a95639340b7ac4cb38a6e` (2026-11-12). Nothing was pushed there.

| Site file | Here | How |
|---|---|---|
| `src/lib/devices.ts`: the `aqara-s1` and `lululemon-mirror` entries | `aqara-s1-plus/design/frame.json`, `lululemon-mirror/design/frame.json` | Data copied; the S1's id is this repo's `aqara-s1-plus` (the site's slug is `aqara-s1`) and its project link is GlassworksHQ's |
| `src/lib/devices.ts`: the `generic-android` entry, read at `main` = `8abae2d` (2026-11-12) | `generic-android/design/frame.json` | Data copied field for field. The S1's `install.href` (s1.edm.sh) came from the same read, and was dropped later, because the S1 installs from Kiln. Nothing else differed, apart from the S1's project link |
| `src/components/deviceGeometry.ts`: `MODELS.s1`, `MODELS.mirror`, `MODELS.tablet` | The `model` sections of those files (`tablet` is generic-android's) | Data copied; the Mirror's stand, computed in code there, written out as 13 tubes. The tablet is a generic shape, not a measured device |
| `src/components/deviceGeometry.ts`: `geometry()` | `packages/frame-card/src/geometry.ts` | Ported; takes the model as an argument. Same output to 0.001 |
| `src/components/DeviceModel.tsx`, `DeviceModel.module.css` | `packages/frame-card/src/DeviceModel.*` | Ported; colours mapped to the brand tokens and the device paint (`paint.ts`) |
| `src/components/DeviceCard.tsx`, `DeviceCard.module.css`, `TiltCard.tsx`; from `DeviceBits.tsx`/`.module.css`: `StatusPill`, `StageMark`, `RatingTags`; from `src/lib/devices.ts`: the types, `cardTags`, `statusText`; four glyphs from `Glyphs.tsx` | `packages/frame-card/src/FrameCard.*`, `frame.ts` | Ported as the card's body, pill and tilt; the outer element is the caller's (Kiln's radio item is `kiln/apps/kiln/src/components/FrameCard.tsx`) |
| `src/app/frames/frames.module.css`: `.grid`, `.add`; `src/app/globals.css`: `.glass` | `kiln/apps/kiln/src/app/globals.css` (`.frames`, `.yours`, `.glass`) | Restyled from the same rules |
| `src/app/globals.css` tokens, `src/lib/markGeometry.ts`, `public/brand/favicon-kiln.svg`, `public/brand/app-icon-kiln.svg` | `kiln/packages/brand/src/` | Generated by `kiln/packages/brand/sync.mjs`, pinned in its `source.json`: today `33166cc` on the site's `brand/kiln-favicon` branch (GlassworksHQ/glassworks.dev#12, which adds the two Kiln icons from the site's generator), until that merges |

## How the website reads it

glassworks.dev takes each frame's `design/frame.json` and the card that draws it
([`packages/frame-card`](../packages/frame-card/README.md)) from this repo, at one pinned commit, so
there is one copy of each. The recommendation, a git submodule, and the alternatives are in
[that package's README](../packages/frame-card/README.md#how-glassworksdev-takes-it). The site's
`devices.ts` entries and `MODELS` table then give way to these files.

When frames move to their own repos (#35), the site points at each frame's released `frame.json`
instead.
