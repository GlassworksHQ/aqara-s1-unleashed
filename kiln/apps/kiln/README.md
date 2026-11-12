<p>
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
</p>

# Kiln

Kiln installs Glassworks on a wall panel from the browser, over WebUSB. It backs the panel up
first, and can put it back. This is Kiln on Next.js (App Router), built on the design in
[`docs/design/kiln`](../../../docs/design/kiln/README.md) and the Glassworks brand.

**Status:** not live yet. s1.edm.sh serves the page in [`kiln/src`](../../src/README.md) until this
app has done a full install on a real panel from a Vercel preview (#27).

## Routes

| Route | What |
|---|---|
| `/` | Pick your panel. Undo and "finish setting up" are links to the frame's routes |
| `/aqara-s1-plus` | The S1 wizard: connect (recovery mode), install, set up, done. `#/undo`, `#/resume`, `#/wifi`, `#/home-assistant`, `#/radio` and `#/install` work as on s1.edm.sh |
| `/api/release-asset` | Streams an S1 Kiosk release APK from GitHub Releases (no CORS there) |

## How it fits together

| File | What |
|---|---|
| `src/wizard/state.ts` | The wizard's state and its one reducer. The rail, the stack, the plain log and the time left are derived |
| `src/wizard/flowUi.ts` | kiln-core's `FlowUi` as wizard actions. `AskBridge` is the "your turn" sheet |
| `src/wizard/session.ts` | The hardware steps: InstallFlow with the recipe, then the app's setup over adb. Screens only call it |
| `src/wizard/screens.tsx` | One component per screen of the design, on Radix primitives |
| `src/frames/aqara-s1-plus.ts` | What the screens say for the S1: the USB names to pick, help links, the radio's next steps |
| `src/recovery/player.ts` | The recovery-mode player, drawing a frame's data. The S1's is `frames/aqara-s1-plus/installer/recovery.json` (canonical); `docs/design/kiln/recovery/` keeps the prototype's copy, and a test keeps them equal |

Rules that tests guard:

- **`requestDevice()` runs inside the click.** A button that leads to a USB prompt calls it before
  any `await` (`test/in-click.test.tsx`).
- **No literal colours.** Colours come from `@glassworks/brand` (`test/no-hex.test.ts`). Ember is
  Kiln's; green, amber and rose mean status only.

## Commands

From `kiln/`:

```sh
npm install
node scripts/stage-assets.mjs --remote --out apps/kiln/public/assets   # the pinned blobs, as on Vercel
npm run dev -w @glassworks/kiln-app       # http://localhost:5174
npm test -w @glassworks/kiln-app          # also part of npm test
npm run build -w @glassworks/kiln-app     # next build
```

Without a panel: `/aqara-s1-plus?demo=run` clicks through a simulated panel behind the real
InstallFlow. Other `?demo=` names jump to one screen (`src/wizard/demo.ts`). They work in `next dev`,
or in a build made with `NEXT_PUBLIC_KILN_DEMO=1`.

## Vercel

`vercel.json` installs and builds from `kiln/` (the workspace root): `npm ci`, then the blobs, then
`next build`. The project's Root Directory is `kiln/apps/kiln`, with "Include files outside the root
directory" on (the build reads `kiln/packages`, `frames/`, `tools/`, `branding/` and `docs/img`).
