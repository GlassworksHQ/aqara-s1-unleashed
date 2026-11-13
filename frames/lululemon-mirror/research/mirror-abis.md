# Mirror ABIs: which ABIs does the lululemon Mirror's userspace run?

**Status (2026-11-13): read from the community image, untested on a stock unit.** The Glass Panes
community image for the Mirror runs a 64-bit userspace with 32-bit support:
`arm64-v8a,armeabi-v7a,armeabi`. Nobody has read the stock software's ABI list yet.

Device: lululemon Mirror (Qualcomm MSM8916), Android 6.0.1. Image: Glass Panes `mirror-android-1`.

## Question

`frames/lululemon-mirror/design/frame.json` declares `abis`: the ABIs the device's userspace runs
(#98). The release builds one APK for every ABI any frame lists, so the value matters. MSM8916 is a
64-bit SoC (Cortex-A53), but a vendor can still ship a 32-bit userspace on it, as Allwinner did on the
S1's A133.

## What we tried

1. **Read `ro.product.cpu.abilist` from the community image** (2026-11-12, read). This is from the
   research for #40, posted in
   [that issue](https://github.com/GlassworksHQ/aqara-s1-unleashed/issues/40#issuecomment-6829959406).
   The steps, as that comment describes them:
   - Download `system.img` from the Glass Panes `mirror-android-1` release (published 2026-11-05,
     850,905,808 bytes).
   - Check it against the release's `SHA256SUMS`: it matched.
   - Convert it to raw ext4, and list it read-only with `debugfs` in a Debian container. Nothing was
     mounted, and nothing from the image is in this repo.

   It found `ro.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi`.

   That rules out a 32-bit-only userspace for this image. It says nothing about the stock software.

## Result

| Software | `ro.product.cpu.abilist` | How sure |
|---|---|---|
| Glass Panes `mirror-android-1` | `arm64-v8a,armeabi-v7a,armeabi` | read from its `system.img` (2026-11-12) |
| Stock Mirror software | unknown | untested |

So `frame.json` lists `arm64-v8a`, then `armeabi-v7a` (we build no `armeabi` APK). If a stock unit
turns out to be 32-bit only, the Mirror's list drops `arm64-v8a`. The release's ABIs stay the same,
because `generic-android` lists both.

## How to reproduce

Read-only. The commands below are a guide; they are not the exact ones run on 2026-11-12.

1. Download `system.img` and `SHA256SUMS` from the `mirror-android-1` release and check the hash.
2. `simg2img system.img system.raw` if the image is sparse.
3. `debugfs -R 'cat /build.prop' system.raw | grep abilist`.

On a Mirror with adb: `adb shell getprop ro.product.cpu.abilist`.

## Open questions

- The stock software's `ro.product.cpu.abilist`. Read it with adb on a stock unit, if stock adb is
  usable (also open in #40).
