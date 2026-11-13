# Boot branding: "Glass Tile"

The panel's power-on splash and Android boot animation, chosen in a design session on 2026-11-09.
It uses the same visual language as the kiosk's onboarding screen: #0a0b0f, sky and violet glows,
thin type, and glass.

| File | What | Installed by |
|---|---|---|
| `bootlogo.bmp` | splash shown by the bootloader at power-on: 720x1440, 24-bit, uncompressed, landscape design rotated 90° clockwise (same format as stock) | `tools/install/fel-install.sh` (step 7), using `tools/branding/set-bootlogo.py` to swap it into the panel's own `bootloader` (boot-resource, FAT16) partition in place |
| `bootanimation.zip` | Android boot animation: 800x400 @ 30 fps, STORED; `part0` is the 2 s intro, `part1` the 2 s loop, background #0a0b0f | `tools/root/patch-boot-magisk.sh`: shipped in the boot image and bind-mounted over `/system/media/bootanimation.zip` (`tools/root/s1kiosk_branding.rc`) |
| `generate.py`, `common.py`, `build_all.py` | the generator. Requires Pillow and numpy; Inter (SIL OFL 1.1) is fetched from google/fonts at build time | - |
| `splash-landscape.png`, `preview.webp` | previews only | - |

The first animation frame is pixel-identical to the centre of the splash, so the hand-off is
seamless. There are no third-party marks; the only font is Inter (OFL).
Opt out: `BRANDING=none` (splash) and `BOOT_ANIMATION=none` (animation).
Undo: `fel-install.sh --undo <first-install backup>` restores the stock splash partition and boot.

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
