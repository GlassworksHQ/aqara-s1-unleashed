# Boot branding

The panel's power-on splash and Android boot animation: the Glassworks stack, as drawn in #63
("Boot animation (final)"). `generate.py` takes the curves and timings of the design's reference
page (`kiosk-ui/boot/boot-reference.html` at `5df8f044`, the "Translucent" frame) as they are, draws
the stack with the page's SVG filters emulated (the blur and grain are close, not exact), and renders
every frame at the panel's 1440x720. The folder keeps its old name because the installers read these files from
here.

| File | What | Installed by |
|---|---|---|
| `bootlogo.bmp` | Splash shown by the bootloader at power-on: frame 0 of the animation. 720x1440, 24-bit, uncompressed, the landscape picture rotated 90° clockwise, 3,110,454 bytes (same format as stock) | `tools/install/fel-install.sh` (step 7) and Kiln's S1 recipe, both swapping it into the panel's own `bootloader` (boot-resource, FAT16) partition in place (`tools/branding/set-bootlogo.py`, `replaceBootlogo()`) |
| `bootanimation.zip` | Android boot animation: 1440x720 @ 30 fps, STORED, each frame trimmed (`trim.txt`) | `tools/root/patch-boot-magisk.sh` and Kiln: shipped in the boot image and bind-mounted over `/system/media/bootanimation.zip` (`tools/root/s1kiosk_branding.rc`) |
| `generate.py`, `common.py` | The generator: the design, and the output formats with their checks. Needs the Python packages under [Rebuild](#rebuild). Inter (SIL OFL 1.1) is fetched from a pinned google/fonts commit and checked by SHA-256 | - |
| `outputs.json` | What the last build used and wrote: the SHA-256 of `generate.py`, `common.py`, the font and the four outputs. CI compares the committed files with it | - |
| `splash-landscape.png`, `preview.webp` | Previews only | - |

## The sequence

| Time | What |
|---|---|
| 0 | Frame 0, also the splash: the stack in dashed outline, where it ends up |
| 0.2, 0.75, 1.3 s | The plates drop bottom-first on a damped spring (damping 0.74, period 0.7 s). Each landing dips the plate 2.4 units and the ones under it 60% of that. Its outline fades |
| 2.35–3.1 s | The top plate lights: frosted, a solid body, sky→violet tint, grain, sheen, rim |
| 3.0–3.8 s | "Glassworks" fades up |
| 3.5–4.2 s | "On Aqara S1 Plus" (the frame's name from `frames/<id>/design/frame.json`) |
| 4.0–4.8 s | The loop's motion eases in. `part0` is 0–4.8 s, 144 frames |
| then, 7.2 s loop | `part1`, 216 frames: the stack rises and falls (±6 units), the upper plates open and close (8 and 4 units every 3.6 s), and the top plate breathes by brightness |

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
  the background colour, so the picture is the same, and each decode is about a seventh of the screen.
- **The S1 shows the animation in 32-bit colour** (measured on the panel, 2026-11-12), so the
  background is the brand's night, `#0a0b0f`, exactly, in the splash and in every frame. Stock
  Android 10 would draw it in RGB 565, where `#0a0b0f` isn't exact; the research note has what a frame
  with that player would need.

Two differences from the reference page, both forced by the above:

- **No corner glows.** The page's sky and violet glows cost about 24 KB in every frame: with them
  the zip is 19.9 MB and the patched boot image would be 42.3 MB. The background is flat.
- **`part0` is 4.8 s, not 4.0 s.** The reference's loop motion eases in over the first 0.8 s and
  the device line ends at 4.2 s, so its curves only repeat from 4.8 s. The loop is the 7.2 s after
  that, unchanged.

On the reference panel a normal boot shows the animation for about 6.5 s (7.2 s to 13.7 s after
power-on, read 2026-11-12), so the intro plays in full and the loop mostly matters on slow boots.

## Splashes undo knows as ours

Undo must not put our splash back as if it were Aqara's (#106). So both installers refuse a backup
whose `bootloader.img` holds a splash we shipped. They know ours by the SHA-256 of `BOOTLOGO.BMP`,
listed in [`branding/bootlogos.json`](../bootlogos.json). The splash's header is the same as stock,
so the hash is the only mark.

| Who | What |
|---|---|
| The branding build (`common.build`, so every `generate.py`) | Appends the hash of each `bootlogo.bmp` it writes, with the concept's name and the date. Commit the list with the new `bootlogo.bmp` |
| Whoever adds a splash some other way | Adds its hash to the list by hand, in the same PR |
| Nobody | Removes an entry. Panels in the field still carry old splashes |
| `frames/aqara-s1-plus/installer/test/splash.test.ts` | Fails if any `bootlogo.bmp` under `branding/` is missing from the list |
| Readers | Kiln's S1 recipe (`src/splash.ts`, `backup.ours`) and `fel-install.sh --undo` (`tools/branding/set-bootlogo.py --ours`) |

A splash passed with `BRANDING=<your.bmp>` isn't in the list, so undo treats it as not ours.

## Rebuild

```sh
python3 -m venv /tmp/s1venv
/tmp/s1venv/bin/pip install pillow==13.0.0 numpy==2.4.6 uharfbuzz==0.57.0 pyoxipng==9.1.1
/tmp/s1venv/bin/python branding/glass-tile/generate.py                 # writes the files above
/tmp/s1venv/bin/python branding/glass-tile/generate.py --frame <id>    # another frame's device name
/tmp/s1venv/bin/python branding/glass-tile/generate.py --stills <dir>  # stills at fixed times, for comparisons
```

The build checks the splash format, that frame 0 is the splash pixel for pixel, the trim of every
frame, that the loop is seamless, and the size budget. Then it lists the splash in
`branding/bootlogos.json` and writes `outputs.json`. With these versions a rebuild is
byte-identical on macOS arm64 and on Linux amd64 (`python:3.11-slim`), checked 2026-11-12. Without
pyoxipng the build stops.

**Changed `generate.py` or `common.py`? Run the build and commit everything it writes**: the four
outputs, `outputs.json`, and `bootlogos.json` if the splash changed.
`frames/aqara-s1-plus/installer/test/branding.test.ts` fails in CI when the generator or an output
doesn't match `outputs.json`, and CI's `branding` job (`.github/workflows/pr.yml`) rebuilds in that
Linux image and fails if anything committed differs. A new splash also needs a real panel before merge (hard rule 5). Opt out at install: `BRANDING=none` (splash)
and `BOOT_ANIMATION=none` (animation). Undo: `fel-install.sh --undo <first-install backup>` restores
the stock splash partition and boot.
