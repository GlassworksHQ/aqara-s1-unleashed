# Boot animation: what Android 10 and the boot image allow

**Status (2026-11-12): working when played on a running panel; the real boot is untested.** The #63
animation fits the boot image with 2.05 MB to spare and holds one frame in GPU memory at a time. The
panel's vendor-modified player plays it right: trimmed frames in place, the intro at full speed, the
loop running, in 32-bit colour. The design's corner glows don't fit and were dropped. Playing it as
the panel boots, and the splash, still need a panel run.

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

4. **Read the panel** (2026-11-12, verified, read-only adb on the reference panel, firmware 4.5.80,
   S1 Kiosk 0.4.3, running main's Glass Tile animation). Serial left out.
   ```
   $ adb shell getprop | grep -E "sf\.|bootanim|ro.boottime.bootanim"
   [ro.vendor.sf.rotation]: [90]
   [ro.boottime.bootanim]: [6670537620]
   $ adb shell dumpsys SurfaceFlinger
   GLES: Imagination Technologies, PowerVR Rogue GE8300, OpenGL ES 3.2 build 1.11@5516664
   ... GL_IMG_texture_npot ... GL_OES_texture_npot ...
   frame=[0 0 1440 720] viewport=[0 0 1440 720]      (layers: 720x1440 buffers, rotated by HWC)
   $ adb shell sha256sum /system/media/bootanimation.zip
   6a93dfea...1532  /system/media/bootanimation.zip   (= main's branding/glass-tile/bootanimation.zip)
   $ adb logcat -d -b all | grep -iE "BootAnimation|boot_progress"
   D BootAnimation: BootAnimationPreloadTiming start time: 7065ms
   E BootAnimation: no audio.wav could be found!
   D BootAnimation: BootAnimation: videoPath: (null), (null)
   D BootAnimation: BootAnimationShownTiming start time: 7183ms
   I boot_progress_enable_screen: 13703
   ```
   - Android composes the screen at 1440x720, so a 1440x720 animation fills it. The panel is
     natively portrait; `ro.vendor.sf.rotation` turns it.
   - The GPU lists `GL_OES_texture_npot`, so AOSP's player would make textures at the frame's size,
     not padded (`BootAnimation.cpp` line 870).
   - The bind mount from Magisk's tmpfs is what the player reads. SELinux logs a `mounton` denial for
     it, which permissive mode lets through.
   - A normal boot shows the animation from 7.2 s to about 13.7 s after power-on: about 6.5 s. So the
     4.8 s intro plays in full and the loop runs for under 2 s. The loop matters on slow boots.
   - `no audio.wav could be found!` and the `videoPath` line are not in AOSP's `cmds/bootanimation`
     at android-10.0.0_r41 (`BootAnimation.cpp`, `audioplay.cpp`, `bootanimation_main.cpp`,
     `BootAnimationUtil.cpp`), and the second comes from another thread. The panel's player is a
     modified one, probably Allwinner's, with a boot video path. Step 6 tests how it plays this zip.

5. **The surface format in AOSP** (2026-11-12, read, and simulated on a host). AOSP's player makes
   its surface `PIXEL_FORMAT_RGB_565` (line 284) and turns dithering off (line 878). On 565, `#0a0b0f`
   becomes (8,8,8) if the GPU truncates or (8,12,16) if it rounds, next to a splash at (10,11,15): a
   step at the hand-off. `#080c10` is (8,12,16) both ways. Truncated to 565, the lit plate keeps its
   look (610 colours in the stack region instead of 4929; the grain hides the steps). Step 6 shows the
   S1 doesn't draw in 565, so this is a note for other frames: one whose player does should use a
   background that 565 holds exactly.

6. **Played on the panel** (2026-11-12, verified by the root: S1 Kiosk 0.4.3, Magisk dev boot).
   The zip of commit 3e46f00 (background `#080c10`) was bind-mounted over
   `/system/media/bootanimation.zip`, `bootanimation` run as root with `screenrecord`, then stopped,
   unmounted and cleaned up; the panel was left as found. No partition was written.
   - The frames fill the 1440x720 screen, and the stack, wordmark and device line sit where the
     host renders put them, so the vendor player places trimmed frames by `trim.txt`.
   - The loop keeps moving (the plate at different heights in stills 2 s apart) and doesn't stop.
     Repeated part names and `p` parts work.
   - Memory: the player's RSS was 25.6 MB, then 26.2 MB 20 s later. No growth per loop.
   - The background read exactly (8,12,16) at two corners. The lit plate had 230 distinct red values
     in one still (238 in the stack region of each of the three stills); 565 allows at most 32.
     **The S1 composites its boot animation in 32-bit**, so the background is the brand's `#0a0b0f`.
   - Speed: matching each recorded frame (resampled to 30 fps) to the zip frame it shows gives one
     zip frame per recorded frame through the whole drop (recorded frames 0–91 and 124–143; 92–123
     are the lit plateau, identical in the crop compared). So the intro plays at the full 30 fps on a
     running panel. Under the load of a real boot it is untested.
   - The first second of the recording shows the dashboard before the player covers it. That part
     is personal and stays out of the PR.

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

1. `branding/boot/generate.py --frame aqara-s1-plus` (host only) builds the zip and prints its size; its checks
   refuse a zip over 9 MB.
2. The fit numbers need a stock `boot.img` (never in the repo) and Kiln's `patchBoot`: call it as
   `kiln/packages/bootpatch/test/bootpatch.test.ts` does for `stock-4.5.80`, with
   `branding: { bootAnimation: <zip> }`, and read `ramdisk_size` at offset 16 of the result. Host only.
3. Playing a zip on a running panel (step 6) needs root (a Magisk dev boot) and writes no
   partition; a reboot undoes it:
   ```
   adb push bootanimation.zip /data/local/tmp/gw-boot.zip
   adb shell su -c 'mount -o bind /data/local/tmp/gw-boot.zip /system/media/bootanimation.zip'
   adb shell su -c 'setprop service.bootanim.exit 0'
   adb shell screenrecord --time-limit 30 /data/local/tmp/gw-boot.mp4 &
   adb shell su -c bootanimation &
   adb exec-out screencap -p > still.png                  # during the loop
   adb shell su -c 'setprop service.bootanim.exit 1'      # the player exits
   adb shell su -c 'umount /system/media/bootanimation.zip'
   adb pull /data/local/tmp/gw-boot.mp4 && adb shell rm /data/local/tmp/gw-boot.zip /data/local/tmp/gw-boot.mp4
   ```
   The recording starts on whatever the screen shows, so cut the start before sharing it.

## Open questions

All need a real boot on a panel (hard rule 5):

- Does the splash hand over to frame 0 without a step? The splash is written to `bootloader`; only
  a power-on shows both.
- Does the intro keep 30 fps while the system starts? Step 6 measured it on a running panel. Each
  frame is a 300x485 or smaller PNG, less than the old animation's 800x400, so it should.
- How long does a slow boot take (first boot after an install)? 25 loops cover 3 minutes. After
  that the stack holds still.
