#!/usr/bin/env python3
"""GitHub social preview (1280x640) in the Glass Tile visual language.

The mark on the right is the Glass Tile boot animation's resting loop frame (the panel's boot
animation until #63), rendered by social-preview/glass_tile.py. The rest is the
same language: #0a0b0f, soft sky (top left) and violet (bottom right) glows, thin Inter,
amber as the single accent.

Run: python3 branding/social-preview.py   -> branding/social-preview.png
Needs Pillow and numpy; Inter (SIL OFL 1.1) is fetched from google/fonts at build time.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "social-preview"))
import numpy as np  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

import glass_tile as tile  # noqa: E402
from common import AMBER, BG, FA, MU, SKY, SS, TX, VIOLET, font  # noqa: E402

W, H = 1280, 640
OUT = os.path.join(HERE, "social-preview.png")

# layout (1x px)
LEFT = 96
TILE_X0, TILE_Y0 = 728, 205            # where the 460x230 tile lands on the canvas


def glows():
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    acc = np.zeros((H, W, 3), np.float32)
    for cx, cy, rx, ry, rgb, a in [
        (210, 120, 760, 470, SKY, 0.15),       # sky from the top left
        (1120, 560, 760, 460, VIOLET, 0.15),   # violet from the bottom right
    ]:
        d = np.sqrt(((xs - cx) / rx) ** 2 + ((ys - cy) / ry) ** 2)
        g = np.clip(1 - d, 0, 1) ** 1.7
        acc += g[..., None] * (np.array(rgb, np.float32) - np.array(BG, np.float32)) * a
    return np.array(BG, np.float32) + acc


def tile_layer():
    """The tile's loop frame, as light added on top of BG (its stage feathers to BG)."""
    stage = np.asarray(tile.render(tile.N_INTRO), np.float32)  # 800x400, the resting loop frame
    add = stage - np.array(BG, np.float32)
    layer = np.zeros((H, W, 3), np.float32)
    sx, sy = TILE_X0 - tile.X0, TILE_Y0 - tile.Y0             # stage origin on the canvas
    x0, y0 = max(sx, 0), max(sy, 0)
    x1, y1 = min(sx + add.shape[1], W), min(sy + add.shape[0], H)
    layer[y0:y1, x0:x1] = add[y0 - sy:y1 - sy, x0 - sx:x1 - sx]
    return layer


def tracked(d, xy, s, fnt, fill, tracking):
    """Letter-spaced text at SS resolution; xy in 1x px, baseline anchored."""
    x, y = xy
    for i, ch in enumerate(s):
        d.text((x * SS, y * SS), ch, font=fnt, fill=fill, anchor="ls")
        if i + 1 < len(s):
            x += (fnt.getlength(s[i:i + 2]) - fnt.getlength(s[i + 1])) / SS + tracking


def text_layer():
    """All type and the amber dot, drawn at SS resolution as an RGBA overlay."""
    im = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    f_label = font(15, wght=500)
    f_head = font(64, wght=200)
    f_sub = font(23, wght=300)
    f_foot = font(15, wght=400)

    y_label = 196
    r = 4.5
    d.ellipse([(LEFT + 0) * SS, (y_label - 5.5 - r) * SS, (LEFT + 2 * r) * SS, (y_label - 5.5 + r) * SS],
              fill=AMBER + (255,))
    tracked(d, (LEFT + 2 * r + 12, y_label), "HOME ASSISTANT WALL PANEL", f_label, MU + (255,), 0.16 * 15)

    for i, line in enumerate(["Your S1 Plus,", "unleashed."]):
        d.text((LEFT * SS - 4 * SS, (276 + i * 76) * SS), line, font=f_head, fill=TX + (255,), anchor="ls")

    for i, line in enumerate(["Kiosk, voice, music and Zigbee/Thread for",
                              "the Aqara Panel Hub S1 Plus. Installed from",
                              "your browser, with undo built in."]):
        d.text((LEFT * SS, (412 + i * 34) * SS), line, font=f_sub, fill=MU + (255,), anchor="ls")

    tracked(d, (LEFT, 548), "OPEN SOURCE  ·  NOT AFFILIATED WITH AQARA", f_foot, FA + (255,), 0.12 * 15)
    return im.reduce(SS)


def main():
    rgb = glows() + tile_layer()
    rng = np.random.default_rng(1)
    rgb += rng.uniform(-0.5, 0.5, rgb.shape).astype(np.float32)   # dither: no banding in the glows
    base = Image.fromarray(np.clip(rgb + 0.5, 0, 255).astype(np.uint8), "RGB")
    txt = text_layer()
    base.paste(txt, (0, 0), txt)
    base.save(OUT, optimize=True)
    print(f"wrote {OUT} ({os.path.getsize(OUT) // 1024} KiB)")


if __name__ == "__main__":
    main()
