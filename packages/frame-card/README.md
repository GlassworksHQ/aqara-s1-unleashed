# @glassworks/frame-card

A frame's card and its 3D device mockup, drawn from the frame's own data
([`frames/<id>/design/frame.json`](../../frames/README.md)). Kiln's picker uses it, and glassworks.dev is
to use the same copy in place of its own. MIT licensed, like the rest of this repo.

It came from glassworks.dev at `fbfd364` (the table in [frames/README.md](../../frames/README.md#what-came-from-glassworksdev)).
Kiln was the first to use it, and #114 moved it here, so there is one copy.

## What's in it

| Export | What |
|---|---|
| `FrameCardBody` | The card's contents: the model that rises and floats on hover, the name and stage, the summary, the three install tags. All spans, so it fits inside a button or a link |
| `frameCardClass`, `useTilt()` | For the card's outer element, which is the caller's (Kiln: a radio item; the site: a link). The class lifts the card on hover; the hook leans the model toward a mouse pointer |
| `StatusPill` | The stage pill, as on the status badges |
| `DeviceModel` | The 3D mockup alone: the body's sides as SVG behind a CSS-3D front glass with an example dashboard |
| `geometry()` | The projection `DeviceModel` draws, as plain data. Pure, so tests can call it |
| `FrameDesign`, `FrameModel`, `Stage`, `cardTags()`, `STAGE_TEXT`, `ships()`, `RATING_KEYS`, `PANE_IDS` | The `frame.json` types and the card's words for them |

The page provides the Glassworks tokens (`--sky`, `--edge`, `--r-tile`, `--muted`, …) and the global
`glass` surface class: Kiln from `@glassworks/brand`, the site from its own `globals.css`. The only
literal colours are the device's paint in `src/paint.ts` (`test/no-hex.test.ts`). A caller's own
states go in the caller's styles; Kiln's ember ring on the picked card is in
`kiln/apps/kiln/src/components/FrameCard.module.css`.

```tsx
import { FrameCardBody, frameCardClass, useTilt, type FrameDesign } from '@glassworks/frame-card';

function Card({ f }: { f: FrameDesign }) {
  const tilt = useTilt<HTMLAnchorElement>();
  return <a {...tilt} href={`/frames/${f.id}`} className={`glass ${frameCardClass}`}><FrameCardBody f={f} /></a>;
}
```

The source is TypeScript and CSS modules, not built. Next.js compiles it like the app's own code.

## How glassworks.dev takes it

The site needs two things from this repo: this package and each frame's `design/frame.json`. They
should come at the same commit, so the card never draws data it wasn't written for.

**Recommended: a git submodule at a pinned commit.** For example, in the site's repo:

```sh
git submodule add https://github.com/GlassworksHQ/aqara-s1-unleashed vendor/s1-unleashed
git -C vendor/s1-unleashed checkout <commit>
pnpm add @glassworks/frame-card@link:vendor/s1-unleashed/packages/frame-card
```

and then import the frames' data from `vendor/s1-unleashed/frames/<id>/design/frame.json`.

The package ships TypeScript and CSS modules, not built JavaScript, so the site's Next config must
compile it: `transpilePackages: ['@glassworks/frame-card']`. Untested on glassworks.dev so far.

- One pin moves the code and the data together, in one reviewed commit of the site.
- Nothing to publish. The package's imports and `react` resolve from the site's `node_modules`,
  because the submodule sits inside the site's folder. Don't run `npm install` inside the submodule:
  that would give the card a second React.
- Vercel clones public submodules over HTTPS. The cost is the whole repo, about 25 MB.

| Way | For | Against |
|---|---|---|
| Git submodule at a commit (recommended) | One pin for code and data; no publish step | Vercel and contributors fetch the whole repo; `git submodule update --init` after cloning |
| pnpm git dependency at a commit, `github:GlassworksHQ/aqara-s1-unleashed#<commit>&path:/packages/frame-card` | A normal dependency; only this folder lands in `node_modules` | It brings no `frame.json`, so the data needs a second way in and a second pin that can drift; Next needs `transpilePackages` for TypeScript in `node_modules` |
| Publishing to npm | Versioned like any library | A release pipeline and a version per change, too much while frames live in this repo |

When frames move to their own repos (#35), the site points at each frame's released `frame.json`
instead, and this package can move with the core.

## Commands

From the repo root: `npm test -w @glassworks/frame-card`, `npm run typecheck -w @glassworks/frame-card`.
Kiln's `test/frame-designs.test.ts` draws every frame with `geometry()`.
