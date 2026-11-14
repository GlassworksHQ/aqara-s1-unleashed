#!/usr/bin/env python3
"""The showcase's two camera stills (MIT script; the photos are CC0, see README.md here), from the
1280 px previews Wikimedia Commons serves of each photo: checked by SHA-256, cropped to 16:9 from the
top (a camera mounted high), anything readable blurred, resized and saved as JPEG next to this file.

Reads the two previews you downloaded, writes front-garden.jpg and driveway.jpg here. Needs Pillow.

  python3 tools/showcase/photos/make.py <front-garden preview> <driveway preview>
"""
import hashlib
import os
import sys

from PIL import Image, ImageEnhance, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SIZE = (800, 450)
QUALITY = 62
# The dashboard's time is 19:30: the stills are graded to dusk, as a camera sees it then. Darker,
# flatter pictures also keep the renders inside their size budget.
DUSK = {"brightness": 0.55, "contrast": 0.8, "color": 0.75, "tint": (0.92, 0.97, 1.12)}

PHOTOS = [
    {"out": "front-garden.jpg",
     "sha256": "efdf063824b9e7423034f8fb098a88f7071894c9f676b6a3cbab66f9bef34bb3",
     "crop": (0, 150, 1280, 870),            # the house, its steps, the lawn and the path
     "blur": [(905, 590, 975, 680)]},         # the sign post by the path
    {"out": "driveway.jpg",
     "sha256": "850cc506e346c7431dd8918cbbab420907f52b6e81f402a007d797210124729e",
     "crop": (0, 60, 1280, 780),             # the lane, the gate and the fences, a house far off
     "blur": []},
]


def make(photo, path):
    with open(path, "rb") as f:
        got = hashlib.sha256(f.read()).hexdigest()
    if got != photo["sha256"]:
        raise SystemExit(f"{path}: SHA-256 {got}, expected {photo['sha256']} (not the 1280 px preview?)")
    im = Image.open(path).convert("RGB")
    for box in photo["blur"]:
        im.paste(im.crop(box).filter(ImageFilter.GaussianBlur(12)), box[:2])
    im = im.crop(photo["crop"]).resize(SIZE, Image.LANCZOS)
    im = ImageEnhance.Brightness(im).enhance(DUSK["brightness"])
    im = ImageEnhance.Contrast(im).enhance(DUSK["contrast"])
    im = ImageEnhance.Color(im).enhance(DUSK["color"])
    im = Image.merge("RGB", [ch.point(lambda v, k=k: min(255, round(v * k))) for ch, k in zip(im.split(), DUSK["tint"])])
    out = os.path.join(HERE, photo["out"])
    im.save(out, "JPEG", quality=QUALITY, optimize=True, progressive=True)
    print(f"{photo['out']}: {SIZE[0]}x{SIZE[1]}, {os.path.getsize(out) / 1024:.1f} KiB")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    for photo, path in zip(PHOTOS, sys.argv[1:]):
        make(photo, path)
