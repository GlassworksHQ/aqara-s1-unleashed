<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/banner-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/banner-kiln-on-light.svg" width="640">
    </picture>
  </a>
</p>

<h1 align="center">Kiln</h1>

<p align="center">
  <b>Install Glassworks on a wall panel from the browser.</b><br>
  Over WebUSB, with a backup first and a way back.
</p>

<p align="center">
  <a href="https://www.glassworks.dev/brand#parts">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-kiln-on-dark.svg">
      <img alt="Glassworks Kiln" src="https://www.glassworks.dev/brand/badge-kiln-on-light.svg" height="30">
    </picture>
  </a>
  <a href="../../../LICENSE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-under-development-on-dark.svg">
    <img alt="Status: under development" src="https://www.glassworks.dev/brand/badge-status-under-development-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

Kiln installs Glassworks on a wall panel from the browser, over WebUSB. It backs the panel up
first, and can put it back. This is Kiln on Next.js (App Router), built on the design in
[`docs/design/kiln`](../../../docs/design/kiln/README.md) and the Glassworks brand.

**Status:** not live yet. s1.edm.sh serves the page in [`kiln/src`](../../src/README.md) until this
app has done a full install on a real panel from a Vercel preview (#27).

## Routes

| Route | What | Reached with nothing running |
|---|---|---|
| `/` | Pick your panel: the frames as on glassworks.dev/frames, from each frame's `design/frame.json`. Forwards s1.edm.sh's old `/#/…` links | — |
| `/aqara-s1-plus` | Connect: put the panel in recovery mode, click Connect | shows Connect |
| `/aqara-s1-plus/install` | Backing up and writing, and the "your turn" sheets | goes to `/aqara-s1-plus` |
| `/aqara-s1-plus/set-up` | The app's settings on a freshly installed panel | connects to an installed panel over adb, then settings (was `#/resume`) |
| `/aqara-s1-plus/set-up/wifi`, `/home-assistant`, `/radio` | One set-up step each | connects to an installed panel, then that step (was `#/wifi`, …) |
| `/aqara-s1-plus/done` | Done, with the backup to download | goes to `/aqara-s1-plus` |
| `/aqara-s1-plus/undo` | Undo: how far back, which backup. "Keep its settings" checks the backup (kiln-core's `checkRestorable`) before Aqara's apps go back; a refused backup, or a broken plan in the recipe, changes nothing on the panel and links to undoing by hand | shows Undo |
| `/aqara-s1-plus/undo/connect`, `/undo/restore`, `/undo/done` | Undo's recovery mode, writing, and done | go to `/aqara-s1-plus/undo` |
| `/aqara-s1-plus/resume` | An alias of `/set-up` (s1.edm.sh's `#/resume`) | as `/set-up`: connects to an installed panel; the address then becomes `/aqara-s1-plus/set-up` |
| `/api/release-asset` | Streams an S1 Kiosk release APK from GitHub Releases (no CORS there) | — |

The session is the truth and the address bar follows it (`src/wizard/routes.ts`, `useRouteSync.ts`).
Moving forward through set-up adds a history entry, so Back (and the rail's set-up steps) go back to
the step before on the same session, with what was entered kept; every other move replaces the
address.
A route never starts or repeats a flow: while something runs (USB work, a picker, the app going in),
any other route of the frame shows the running step, and leaving for the picker keeps it running
(the picker shows "Back to it"). A route that starts something new once the last flow finished gets
a new session, and the old one lets go of the panel (`Session.close`: the Home Assistant poll, the
app's channels, adb). A Home Assistant sign-in waiting on its tab counts as running. Kiln's own links
are client-side (`next/link`), so they never cut an install short; the leave-page warning and the
screen wake lock (`components/LeaveGuard.tsx`, in the root layout) follow a write onto every route,
the picker included.

When a run stops with an error and the person moves on (Try again, another route, a new install),
Kiln keeps that failure until the next run starts: the picker and Connect say "The last run stopped:
…" with **Download its log**, a text file with what happened, the error and the technical log
(`wizard/registry.ts`). Nothing else keeps it, and a reload drops it.

The browser tab keeps two things across sessions and reloads (`wizard/registry.ts`, sessionStorage):
whether an earlier step may have changed the panel, and which backup the latest run saved. So a later
session's failure never says "nothing was written" when a try did, and its Done still offers the
backup's download (the files stay in IndexedDB). When the browser blocks storage, a reload can't
know, and the screens say Kiln can't tell instead.

## How it fits together

| File | What |
|---|---|
| `src/wizard/state.ts` | The wizard's state and its one reducer. The rail, the stack, the plain log and the time left are derived |
| `src/wizard/flowUi.ts` | kiln-core's `FlowUi` as wizard actions. `AskBridge` is the "your turn" sheet |
| `src/wizard/session.ts` | The hardware steps: InstallFlow with the recipe, then the app's setup over adb. Screens only call it |
| `src/wizard/routes.ts`, `useRouteSync.ts`, `registry.ts` | The routes, and the frame's session that outlives them |
| `src/frames/catalog.ts`, `src/components/FrameCard.tsx` | The picker's frames, from `frames/<id>/design/frame.json` ([frames/README.md](../../../frames/README.md)), drawn by the card glassworks.dev shares ([`packages/frame-card`](../../../packages/frame-card/README.md)) as radio items |
| `src/wizard/screens.tsx` | One component per screen of the design, on Radix primitives |
| `src/frames/aqara-s1-plus.ts` | What the screens say for the S1: the USB names to pick, help links, the radio's next steps |
| `src/recovery/player.ts` | The recovery-mode player, drawing a frame's data. The S1's is `frames/aqara-s1-plus/design/recovery.json` (canonical); `docs/design/kiln/recovery/` keeps the prototype's copy, and a test keeps them equal |

Rules that tests guard:

- **`requestDevice()` runs inside the click.** A button that leads to a USB prompt calls it before
  any `await` (`test/in-click.test.tsx`).
- **No literal colours.** Colours come from `@glassworks/brand` (`test/no-hex.test.ts`). Ember is
  Kiln's; green, amber and rose mean status only.

## Commands

From the repo root, the npm workspace root:

```sh
npm install
node kiln/scripts/stage-assets.mjs --remote --out apps/kiln/public/assets   # the pinned blobs, as on Vercel
npm run dev -w @glassworks/kiln-app       # http://localhost:5174
npm test -w @glassworks/kiln-app          # also part of npm test
npm run build -w @glassworks/kiln-app     # next build
```

Without a panel: `/aqara-s1-plus?demo=run` clicks through a simulated panel behind the real
InstallFlow. Other `?demo=` names jump to one screen (`src/wizard/demo.ts`), for example
`app-installing` (the app's install phases), `wifi` (the panel on Wi-Fi already) and `wifi-new` (it
isn't). They work in `next dev`,
or in a build made with `NEXT_PUBLIC_KILN_DEMO=1`.

## Vercel

`vercel.json` installs and builds from the repo root (the workspace root): `npm ci`, then the blobs,
then `next build`. The project's Root Directory is `kiln/apps/kiln`, with "Include files outside the root
directory" on (the build reads the root's `package.json` and lockfile, `kiln/packages`, `packages/`,
`frames/`, `tools/`, `branding/` and `docs/img`).
