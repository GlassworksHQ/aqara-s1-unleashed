# Boot generator

Builds the Glassworks boot animation, and the bootloader splash where a frame has one, for any
frame. It needs two things from the frame: its name and its screen size. The design is the
Glassworks stack from #63; the S1's output and what was measured on the S1 are in
[`branding/glass-tile`](../glass-tile/README.md).

| File | What |
|---|---|
| `generate.py` | The command line |
| `target.py` | What a frame gives the generator, and the layout it derives from that. Standard library only |
| `stack.py` | The design: the stack, the wordmark and the device line, ported from the reference page |
| `formats.py` | The output formats (`bootanimation.zip`, the Allwinner splash BMP, a preview) and their checks |
| `test/` | Tests for `target.py`; CI runs them |

## Adding a frame

Give the frame a `display` in its `frames/<id>/design/frame.json`, in pixels, as Android's boot
animation sees the screen:

```json
"display": { "width": 1280, "height": 800 }
```

Then build:

```sh
python3 -m venv /tmp/s1venv && /tmp/s1venv/bin/pip install pillow numpy uharfbuzz pyoxipng
/tmp/s1venv/bin/python branding/boot/generate.py --frame <id> --out <dir>
```

Without a `frame.json`, give the same values on the command line:
`--name "Some Panel" --width 1280 --height 800 --out <dir>`.

The output, in `<dir>`:

| File | What |
|---|---|
| `bootanimation.zip` | Android's boot animation: the screen's size, 30 fps, every entry STORED |
| `splash-landscape.png` | Frame 0, which is also the splash |
| `bootlogo.bmp` | Only with a `splash` format (below). The build adds its SHA-256 to [`branding/bootlogos.json`](../bootlogos.json), so undo knows it as ours (#106): commit the list with the BMP |
| `preview.webp` | Half size, for docs |

`--stills <dir>` writes stills at fixed times instead, which takes seconds instead of minutes.

### Optional: `boot`

The defaults play on any Android from 6.0 on (read from source, not tried on a device). A frame narrows them when it knows more:

```json
"boot": { "androidApi": 29, "splash": "allwinner-boot-resource", "zipBudget": 9000000 }
```

| Field | Default | What it changes |
|---|---|---|
| `androidApi` | unknown | From 25 (Android 7.1) each frame is trimmed to the pixels that aren't background (`trim.txt`): a smaller zip and a cheaper decode per frame. Older players don't read `trim.txt`, so without it every frame is full screen |
| `splash` | none | `allwinner-boot-resource`: the Allwinner bootloader's `BOOTLOGO.BMP`, the landscape picture rotated 90° clockwise, 24-bit, swapped into the stock file's place. Needs a height that is a multiple of 4 |
| `zipBudget` | no limit | The build fails when the zip is bigger, e.g. when it rides in a boot image |

## Layout

The design's reference page lays the screen out at 960x480 CSS px. One CSS px becomes
`min(width / 960, height / 480)` screen px, so that viewport always fits. The stack and wordmark
stay centred, and the device line stays 30 CSS px from the bottom. On a landscape 2:1 screen this is
the reference page scaled up; the S1 gets exactly 1.5, and its output is byte for byte what it was
before the generator took parameters (checked 2026-11-12). On a portrait screen the stack keeps the
same share of the width and gets more room above and below.

The build fails when the device line doesn't fit the screen's width.

## Formats

Read from AOSP's `BootAnimation.cpp` (`android-6.0.1_r81`, `7.0.0_r1`, `7.1.1_r1`,
`android-10.0.0_r41`); details in `formats.py`:

| | Android 6.0–7.0 | Android 7.1 and later |
|---|---|---|
| Surface | RGB 565, no dithering | Same |
| `c` and `p` parts | Yes | Yes |
| Looping part (`count != 1`) | Keeps every frame as a GPU texture | Same |
| `trim.txt` | Not read | Read |

Because a looping part keeps all its frames in GPU memory, the loop is a count-1 part named 25
times, then a part that holds its first frame.

The background is the brand's night, `#0a0b0f`. The S1's player composites in 32-bit, so it shows
exactly there (measured 2026-11-12). A frame whose player really draws on AOSP's RGB 565 surface
would show it a step off from the splash; the
[research note](../../docs/research/boot-animation.md) has what such a frame needs. Check it on
each new frame's first boot.

## Frames

| Frame | Screen | Android | Splash | Zip | Measured |
|---|---|---|---|---|---|
| `aqara-s1-plus` | 1440x720 | 10 (API 29) | Allwinner | 8.69 MB, trimmed (9 MB budget) | On the panel, 2026-11-12 ([glass-tile](../glass-tile/README.md#what-the-panel-allows)) |
| `lululemon-mirror` | 1080x1920 | 6.0.1 (API 23) | None | 9.61 MB, full frames (built 2026-11-12, not committed) | Not yet. The screen size is from the spec sheet (a 40-inch 1080p portrait screen), not read from a device |

## What the build checks

- The loop is seamless: the frame after its last one equals its first.
- Frame 0 of the zip is the splash, pixel for pixel, and the held part equals the loop's first frame.
- With `trim.txt`: every rect matches its frame and stays on screen. Without it: no `trim.txt` at all.
- Every zip entry is STORED, the CRCs check out, `desc.txt` is as built, and the zip is within `zipBudget`.
- The splash BMP: its size, its header, and that it rotates back to `splash-landscape.png`.

Inter (SIL OFL 1.1) is fetched from google/fonts at build time, and the build refuses it unless the
licence file is the OFL.
