#!/usr/bin/env python3
"""Generate the Glassworks boot animation (and the bootloader splash, where the frame has one) for
any frame, from its name and display size.

    generate.py --frame <id> --out <dir>             # the target in frames/<id>/design/frame.json
    generate.py --name "Some Panel" --width 1280 --height 800 --out <dir>   # no frame.json needed
    generate.py --frame <id> --stills <dir>          # stills at fixed times instead (no zip/bmp)

The S1's committed files are rebuilt with
    generate.py --frame aqara-s1-plus --out branding/glass-tile

Writes into <dir>: bootanimation.zip, splash-landscape.png, preview.webp, and bootlogo.bmp when the
frame has a splash format. Exits 1 when a check fails (README: "What the build checks").
Also writes outputs.json (the target and the SHA-256 of the generator, the font and each output).
Needs Pillow, numpy, uharfbuzz and pyoxipng at the versions in README.md. Inter (SIL OFL 1.1) is
fetched from a pinned google/fonts commit and checked by SHA-256.
"""
import argparse
import dataclasses
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from target import Target, from_frame  # noqa: E402

STILLS = (0.0, 0.5, 0.8, 1.0, 1.6, 2.0, 2.6, 3.2, 4.0, 4.8, 6.6, 8.4)


def target_from_args(a):
    if a.frame:
        t = from_frame(a.frame)
        over = {k: v for k, v in (("name", a.name), ("width", a.width), ("height", a.height),
                                  ("android_api", a.android_api), ("splash", a.splash),
                                  ("zip_budget", a.zip_budget)) if v is not None}
        return dataclasses.replace(t, **over)
    if a.name is None or a.width is None or a.height is None:
        raise SystemExit("give --frame <id>, or --name, --width and --height")
    return Target(a.name, a.width, a.height, a.android_api, a.splash, a.zip_budget)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--frame", help="frames/<id>: read the target from its design/frame.json")
    ap.add_argument("--name", help="the device's name, for the line under the wordmark")
    ap.add_argument("--width", type=int, help="the display's width in px, as Android's boot animation sees it")
    ap.add_argument("--height", type=int, help="the display's height in px")
    ap.add_argument("--android-api", type=int, help="Android's API level; 25+ gets trimmed frames")
    ap.add_argument("--splash", help="the bootloader splash format (allwinner-boot-resource)")
    ap.add_argument("--zip-budget", type=int, help="the largest bootanimation.zip allowed, in bytes")
    ap.add_argument("--out", metavar="DIR", help="where to write the files")
    ap.add_argument("--stills", metavar="DIR", help="write the comparison stills here instead of building")
    a = ap.parse_args()
    try:
        target = target_from_args(a)
    except (ValueError, OSError) as e:
        raise SystemExit(str(e))
    if not (a.out or a.stills):
        raise SystemExit("give --out <dir> (or --stills <dir>)")

    import stack
    from formats import build
    from PIL import Image
    scene = stack.Scene(target)
    if a.stills:
        os.makedirs(a.stills, exist_ok=True)
        for t in STILLS:
            Image.fromarray(stack.render(t, scene)).save(os.path.join(a.stills, f"t{t:04.1f}.png"))
        return 0
    problems = build(target, a.out, lambda T: stack.render(T / stack.FPS, scene), stack.N_INTRO, stack.N_LOOP, stack.NAME)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
