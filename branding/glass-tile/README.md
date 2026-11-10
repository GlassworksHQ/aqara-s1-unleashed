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
