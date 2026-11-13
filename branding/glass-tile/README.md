# Boot branding

The panel's power-on splash and Android boot animation: the Glassworks stack, as drawn in #63
("Boot animation (final)"). `generate.py` ports the curves of the design's reference page
(`kiosk-ui/boot/boot-reference.html` at `5df8f044`, the "Translucent" frame) and renders every frame
at the panel's 1440x720. The folder keeps its old name because the installers read these files from
here.

| File | What | Installed by |
|---|---|---|
| `bootlogo.bmp` | Splash shown by the bootloader at power-on: frame 0 of the animation. 720x1440, 24-bit, uncompressed, the landscape picture rotated 90° clockwise, 3,110,454 bytes (same format as stock) | `tools/install/fel-install.sh` (step 7) and Kiln's S1 recipe, both swapping it into the panel's own `bootloader` (boot-resource, FAT16) partition in place (`tools/branding/set-bootlogo.py`, `replaceBootlogo()`) |
| `bootanimation.zip` | Android boot animation: 1440x720 @ 30 fps, STORED, each frame trimmed (`trim.txt`) | `tools/root/patch-boot-magisk.sh` and Kiln: shipped in the boot image and bind-mounted over `/system/media/bootanimation.zip` (`tools/root/s1kiosk_branding.rc`) |
| `generate.py`, `common.py` | The generator: the design, and the output formats with their checks. Needs Pillow, numpy and uharfbuzz (pyoxipng makes the frames smaller). Inter (SIL OFL 1.1) is fetched from google/fonts at build time | - |
| `splash-landscape.png`, `preview.webp` | Previews only | - |

## The sequence

| Time | What |
|---|---|
| 0 | Frame 0, also the splash: the stack in dashed outline, where it ends up |
| 0.2, 0.75, 1.3 s | The plates drop bottom-first on a damped spring (damping 0.74, period 0.7 s). Each landing dips the plate 2.4 units and the ones under it 60% of that. Its outline fades |
| 2.35–3.1 s | The top plate lights: frosted, a solid body, sky→violet tint, grain, sheen, rim |
| 3.0–3.8 s | "Glassworks" fades up |
| 3.5–4.2 s | "On Aqara S1 Plus" (the frame's name from `frames/<id>/design/frame.json`) |
| 4.0–4.8 s | The loop's motion eases in |
| then, 7.2 s loop | The stack rises and falls (±6 units), the upper plates open and close (8 and 4 units every 3.6 s), and the top plate breathes by brightness |

## What the panel allows

Measured 2026-11-12. The format facts are read from Android 10's `BootAnimation.cpp`; the rest is
in [the research note](../../docs/research/boot-animation.md).

- **The zip rides in the boot image.** Patched with Kiln's `patchBoot` on the stock 4.5.80 boot
  image and today's overlay, the 32 MiB partition has 10.31 MB free without an animation and
  2.05 MB free with this one (8.72 MB). The generator refuses a zip over 9 MB, and so does a test in
  the S1 recipe.
- **A looping part keeps every frame as a GPU texture until boot completes.** 216 full-screen
  frames would be about 900 MB. So the loop is a count-1 part named 25 times (3 minutes of motion,
  each frame decoded as it plays), then `part2` holds the loop's first frame.
- **Each frame is trimmed** to the pixels that aren't background. Android clears the rest to
  `#0a0b0f`, so the picture is the same, and each decode is about a seventh of the screen.

Two differences from the reference page, both forced by the above:

- **No corner glows.** The page's sky and violet glows cost about 24 KB in every frame: with them
  the zip is 19.9 MB and the patched boot image would be 42.3 MB. The background is flat `#0a0b0f`.
- **`part0` is 4.8 s, not 4.0 s.** The reference's loop motion eases in over the first 0.8 s and
  the device line ends at 4.2 s, so its curves only repeat from 4.8 s. The loop is the 7.2 s after
  that, unchanged.

## Splash hashes

For telling our splash from Aqara's (#106): the SHA-256 of every `bootlogo.bmp` we have shipped.

| Splash | SHA-256 |
|---|---|
| Glassworks stack (#63) | `ee564464d01cb8c06a018c1e0240e8a2367affb0d4e01f3133bb799427080c57` |
| Glass Tile (2026-11-09 to #63) | `354402a54d387830a8b2bd3a294db7ab05202d4b7bd3a8429e36a10fa528081b` |

## Rebuild

```sh
python3 -m venv /tmp/s1venv && /tmp/s1venv/bin/pip install pillow numpy uharfbuzz pyoxipng
/tmp/s1venv/bin/python branding/glass-tile/generate.py                 # writes the files above
/tmp/s1venv/bin/python branding/glass-tile/generate.py --frame <id>    # another frame's device name
/tmp/s1venv/bin/python branding/glass-tile/generate.py --stills <dir>  # stills at fixed times, for comparisons
```

The build checks the splash format, that frame 0 is the splash pixel for pixel, the trim of every
frame, that the loop is seamless, and the size budget. Opt out at install: `BRANDING=none` (splash)
and `BOOT_ANIMATION=none` (animation). Undo: `fel-install.sh --undo <first-install backup>` restores
the stock splash partition and boot.
