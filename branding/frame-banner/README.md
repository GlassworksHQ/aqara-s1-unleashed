# Frame banners

`generate.py` draws each frame's README banner, `banner-frame-<id>-on-dark.svg` and
`banner-frame-<id>-on-light.svg`, from its `frames/<id>/design/frame.json`. It writes only into
`--out`. The banners are served from glassworks.dev, like every other banner ([docs/branding.md](../../docs/branding.md)),
so nothing it writes is committed here.

```sh
python3 -m venv /tmp/fbvenv && /tmp/fbvenv/bin/pip install uharfbuzz==0.57.0
/tmp/fbvenv/bin/python branding/frame-banner/generate.py --out /tmp/banners                       # every frame with a tagline
/tmp/fbvenv/bin/python branding/frame-banner/generate.py --out /tmp/banners --frame aqara-s1-plus
/tmp/fbvenv/bin/python -m unittest discover -s branding/frame-banner/test -v
```

## What it draws

The Glassworks part banner from glassworks.dev (`scripts/brand/generate.mjs`, `partBanner`), 640 × 180:

| Part | From |
|---|---|
| The device, standing, at the stack's isometric angle | `model`: `body` (width, height, depth, radius), `bezel`, or `screen`'s box when the screen is smaller than the face, and `finish` (a mirror gets a faint sheen). Portrait or landscape follows the body |
| The six cells on its screen, two of them lit | 3 × 2 on a landscape screen, 2 × 3 on a portrait one |
| "GLASSWORKS · FRAME" and the mark (small cut) | Fixed |
| The name: the maker light, the model bold | `brand` and the rest of `name`. Without `brand`, all of `name` is bold. It shrinks from 34 px to fit, and fails below 20 px |
| `frames/<id>` in mono | `id` |
| The tagline | `tagline`. It fails if the line runs past the banner's right margin |

The light is neutral glass: a frame is hardware, the bottom plate of the stack. Sky to violet is only
on the two lit cells (the panes), and there is no ember and no status colour.

## Fonts

Text is outlined, as on the site: GitHub shows README images as `<img>`, which can't load fonts.
The outlines come from the same files as the site's banners: `@fontsource/inter` 5.3.0 (Latin, 300, 400,
500, 600) and `@fontsource/jetbrains-mono` 5.3.0 (Latin, 500), both SIL OFL 1.1. Each npm tarball is
pinned by SHA-256, its `LICENSE` must be the OFL, and the WOFF file is read from it in memory and
unwrapped for HarfBuzz. The fonts are cached in `/tmp/s1-unleashed-fonts/fontsource`.

The text is set without kerning, like the site's: its opentype.js doesn't read the kerning in these
files. Widths match the site's `measure()` to 0.01 px (checked 2026-11-13), and the six banners
match the site-generated mockups to within anti-aliasing.

## Same input, same bytes

The output depends only on `frame.json` and the pinned fonts: no dates, no randomness, numbers
rounded to fixed places. The tests check that two runs give the same bytes.
