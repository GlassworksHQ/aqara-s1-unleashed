# Boot animation: what Android 10 and the boot image allow

**Status (2026-11-12): read and measured on a host; not yet run on a panel.** The #63 animation fits
the boot image with 2.05 MB to spare, and holds one frame in GPU memory at a time. The design's corner
glows don't fit and were dropped.

Device: Aqara S1 Plus, firmware 4.5.80 (stock `boot` from a local backup, not in the repo). Tools:
AOSP `frameworks/base` at `android-10.0.0_r41`, Kiln's `@glassworks/bootpatch`, Pillow 13, oxipng.

## Question

The approved design (#63, "Boot animation (final)") is a full-screen picture: a stack that drops in
from above the screen, corner glows, a wordmark, and a device line at the bottom. It plays 4 s, then
loops 7.2 s at 30 fps. Can the panel's boot animation carry that, and what does it cost?

## What we tried

1. **Read Android 10's player** (2026-11-12, read: `cmds/bootanimation/BootAnimation.cpp`,
   android-10.0.0_r41).
   ```
   $ curl -sSfL "https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r41/cmds/bootanimation/BootAnimation.cpp?format=TEXT" | base64 -d
   ```
   - `playAnimation`, around line 961: `if (part.count != 1) { glGenTextures(...) }`. A looping part
     keeps every frame as its own texture until the animation ends (freed after the last part,
     line 1024). A count-1 part uploads each frame into one texture and moves on.
   - `initTexture`, line 229: textures are RGBA, padded to a power of two unless the GL driver lists
     `GL_OES_texture_npot` or `GL_ARB_texture_non_power_of_two` (line 870).
   - Lines 971–989: each frame is drawn 1:1 at its `trim.txt` rect ("WxH+X+Y", one line per frame,
     line 803). Everything outside that rect is cleared to the part's colour every frame, and blending
     is off (line 880). So a trimmed frame looks the same as the full one, as long as everything
     outside its rect is background.
   - Lines 943 and 954: a `p` part stops as soon as boot completes, a `c` part plays to its end.
   - `preloadZip`, line 765: a zip entry is added to every part whose path matches, so several
     desc.txt lines may name the same folder.

   This rules out a looping part of full-screen frames: 216 frames x 1440x720x4 is about 900 MB
   (1.8 GB if the driver pads to a power of two), on a 2 GiB panel that is still booting.

2. **Measured the boot image's room** (2026-11-12, verified on a host). The zip ships in the boot
   image's gzip ramdisk (`tools/root/patch-boot-magisk.sh`). A throwaway vitest ran Kiln's `patchBoot`
   on the stock 4.5.80 `boot` with the overlay inputs of the 2026-11-10 goldens (same `otbr-agent`
   and `ot-ctl` sizes as the pinned `installer-files-2` release) and a given zip, then read the
   header of the result:
   ```
   FIT none:                ramdisk  2540832 end 23244800 of 33554432, left 10309632
   FIT Glass Tile (4.26 MB): ramdisk  6497510 end 27201536 of 33554432, left  6352896
   FIT #63, glows (19.9 MB): patched image (42295872 bytes) does not fit in the original 33554432: refusing
   FIT #63, final (8.72 MB): ramdisk 10799772 end 31504384 of 33554432, left  2050048
   ```
   PNG data barely gzips (about 6%).

3. **Measured the frames** (2026-11-12, verified). Rendered by `branding/glass-tile/generate.py`
   and compressed with oxipng: a full-screen loop frame is about 60 KB with the corner glows and
   36 KB without. Without the glows, every frame can be trimmed to the pixels that aren't background,
   about 300x485 in the loop. oxipng level 4 saves 2.3% over level 2; zopfli another 2%, at about 6 s
   a frame, so it isn't used.

## Result

| | Full design | Shipped |
|---|---|---|
| Corner glows | yes | no (flat `#0a0b0f`) |
| Frames | 1440x720 | trimmed, about 300x485 in the loop |
| Zip | 19.9 MB, doesn't fit | 8.72 MB, 2.05 MB left in `boot` |
| Loop | `c 0 0`: 216 textures kept | `p 1 0 part1` x25, then `p 0 0 part2` (1 frame): one texture at a time |
| part0 | 4.0 s | 4.8 s, so the loop starts where the reference's curves repeat |

The zip also lives in RAM (Magisk's tmpfs) for as long as the panel runs: 8.7 MB, up from 4.3 MB.

## How to reproduce

1. `branding/glass-tile/generate.py` (host only) builds the zip and prints its size; its checks
   refuse a zip over 9 MB.
2. The fit numbers need a stock `boot.img` (never in the repo) and Kiln's `patchBoot`: call it as
   `kiln/packages/bootpatch/test/bootpatch.test.ts` does for `stock-4.5.80`, with
   `branding: { bootAnimation: <zip> }`, and read `ramdisk_size` at offset 16 of the result. Host only.

## Open questions

All need a panel (hard rule 5):

- Does the panel's bootanimation behave like AOSP's? An Allwinner BSP could have changed it. The
  first boot shows it: the drop must play at full speed and the loop must not stop after 7.2 s.
- Is the screen the 1440x720 that frames are centred on? The old 800x400 animation sat in the middle,
  so it was never tested at full size.
- How long does the panel take to boot? 25 loops cover 3 minutes. After that the stack holds still.
- Is decoding a 300x485 PNG each frame fast enough at 30 fps while the system starts? The old
  animation decoded 800x400 frames in its intro, so it should be.
