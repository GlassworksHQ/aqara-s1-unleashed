"""Output formats for the boot branding: the bootloader splash (BMP) and Android's bootanimation.zip.

Facts this file relies on:
  * Bootloader splash: 720x1440 24-bit BMP, bottom-up, 54-byte header, 3110454 bytes. The panel is
    landscape; the stored image is the landscape picture rotated 90 degrees clockwise
    (PIL: landscape.transpose(ROTATE_270)). tools/branding/set-bootlogo.py and Kiln's
    replaceBootlogo() swap it in place, so the size must stay exactly the same.
  * Android 10's bootanimation (frameworks/base cmds/bootanimation/BootAnimation.cpp,
    android-10.0.0_r41), read 2026-11-12:
      - Frames are drawn 1:1, centred, at their trim.txt rect ("WxH+X+Y" per frame, in the part's
        folder). Everything outside that rect is cleared to the part's colour every frame, and
        blending is off. So a trimmed frame must be flat BG outside its rect.
      - A part with count != 1 keeps every frame as a GL texture until the animation ends; a part
        with count 1 decodes each frame, uploads it and moves on. Several desc.txt lines may name
        the same folder (each gets its own frame list).
      - 'c' parts play to the end even after boot completes; 'p' parts stop at once.
"""
import io
import os
import re
import struct
import subprocess
import zipfile

import numpy as np
from PIL import Image

SCREEN = (1440, 720)          # landscape, as the user sees it; the animation is the full screen
FPS = 30
BMP_SIZE = 3110454

# The zip ships inside the boot image's gzip ramdisk (tools/root/patch-boot-magisk.sh), in a
# 32 MiB boot partition: kernel, dtb and the rest of the ramdisk end at 23.2 MB.
# Measured 2026-11-12 with Kiln's patchBoot on the stock 4.5.80 boot and today's overlay: 10.31 MB
# free without an animation, and gzip takes about 6% off the zip. 9 MB leaves about 1.8 MB for the
# rest of the ramdisk to grow. The zip also stays in RAM (Magisk's tmpfs) while the panel runs.
ZIP_BUDGET = 9_000_000
LOOP_REPEATS = 25             # p-part repeats of the loop (3 min) before it holds its first frame


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


BG = hexc("#0a0b0f")

# ---------------------------------------------------------------- fonts (OFL)
FONT_CACHE = "/tmp/s1-unleashed-fonts"
GF_RAW = "https://github.com/google/fonts/raw/main/"
FONTS = {"inter": "ofl/inter/Inter%5Bopsz,wght%5D.ttf"}


def _fetch(url, dest):
    if not os.path.exists(dest):
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        # curl uses the system trust store (python.org builds often lack one)
        subprocess.run(["curl", "-sSfL", "--max-time", "120", "-o", dest + ".part", url], check=True)
        os.replace(dest + ".part", dest)
    return dest


def font_file(key):
    """Download the font and its OFL.txt from google/fonts (ofl/ tree). Refuse
    to use the font unless the licence file really is the SIL OFL."""
    rel = FONTS[key]
    folder = rel.rsplit("/", 1)[0]
    lic = _fetch(GF_RAW + folder + "/OFL.txt", os.path.join(FONT_CACHE, key, "OFL.txt"))
    with open(lic, encoding="utf-8", errors="replace") as f:
        if "SIL Open Font License" not in f.read():
            raise RuntimeError(f"{key}: licence is not OFL")
    return _fetch(GF_RAW + rel, os.path.join(FONT_CACHE, key, key + ".ttf"))


# ---------------------------------------------------------------- splash
def bmp_header(w=720, h=1440):
    size_img = w * 3 * h
    return (b"BM" + struct.pack("<IHHI", 54 + size_img, 0, 0, 54)
            + struct.pack("<IiiHHIIiiII", 40, w, h, 1, 24, 0, size_img, 2834, 2834, 0, 0))


def write_bmp(landscape, path):
    img = landscape.transpose(Image.Transpose.ROTATE_270)   # stored rotated 90 deg clockwise
    assert img.size == (720, 1440), img.size
    arr = np.asarray(img.convert("RGB"))[::-1, :, ::-1]      # bottom-up rows, BGR
    with open(path, "wb") as f:
        f.write(bmp_header(*img.size))
        f.write(np.ascontiguousarray(arr).tobytes())


# ---------------------------------------------------------------- animation
try:  # optional: ~20% smaller frames (pip install pyoxipng)
    import oxipng
except ImportError:  # pragma: no cover
    oxipng = None


def png_bytes(img):
    b = io.BytesIO()
    img.save(b, "PNG", optimize=True)
    data = b.getvalue()
    if oxipng is not None:
        data = oxipng.optimize_from_memory(data, level=4)
    return data


def trim(frame):
    """The smallest rect holding every pixel that isn't BG, as (crop, 'WxH+X+Y'). Android clears
    the rest to BG, so the frame on screen is unchanged."""
    a = np.asarray(frame)
    ys, xs = np.nonzero(np.any(a != np.array(BG, np.uint8), -1))
    if not len(xs):
        x0, y0, x1, y1 = 0, 0, 1, 1
    else:
        x0, y0, x1, y1 = xs.min(), ys.min(), xs.max() + 1, ys.max() + 1
    return frame.crop((x0, y0, x1, y1)), f"{x1 - x0}x{y1 - y0}+{x0}+{y0}"


def desc_txt():
    """part0 plays once and finishes even if boot completes. The loop is a count-1 'p' part named
    LOOP_REPEATS times, so its frames are decoded as they play and never held as textures (a
    count-0 part would keep all 216 frames as GPU textures). part2, the loop's first
    frame, holds after that."""
    bg = "#%02x%02x%02x" % BG
    lines = [f"{SCREEN[0]} {SCREEN[1]} {FPS}", f"c 1 0 part0 {bg}"]
    lines += [f"p 1 0 part1 {bg}"] * LOOP_REPEATS
    lines += [f"p 0 0 part2 {bg}"]
    return "\n".join(lines) + "\n"


def write_bootanimation(path, intro, loop):
    dt = (2026, 1, 1, 0, 0, 0)

    def add(z, name, data, is_dir=False):
        zi = zipfile.ZipInfo(name, dt)
        zi.compress_type = zipfile.ZIP_STORED
        zi.create_system = 3
        zi.external_attr = ((0o40755 << 16) | 0x10) if is_dir else (0o100644 << 16)
        z.writestr(zi, data)

    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        add(z, "desc.txt", desc_txt().encode())
        n = 0
        for part, frames in (("part0", intro), ("part1", loop), ("part2", loop[:1])):
            add(z, part + "/", b"", is_dir=True)
            rects = []
            for fr in frames:
                crop, rect = trim(fr)
                add(z, f"{part}/frame_{n:03d}.png", png_bytes(crop))
                rects.append(rect)
                n += 1
            add(z, f"{part}/trim.txt", ("\n".join(rects) + "\n").encode())


def write_preview(path, frames, scale=0.5):
    size = (round(SCREEN[0] * scale), round(SCREEN[1] * scale))
    small = [f.resize(size, Image.LANCZOS) for f in frames]
    durs = [34 if i % 3 == 0 else 33 for i in range(len(small))]   # ~30 fps
    # quality 100: below it, libwebp treats small per-pixel changes as unchanged and the slow
    # breathing turns into steps
    small[0].save(path, "WEBP", save_all=True, append_images=small[1:], duration=durs, loop=0, quality=100, method=5)


def build(here, render, n_intro, n_loop):
    """render(T) -> SCREEN-sized RGB array, T = global frame index. Frames [0, n_intro) are part0
    and the next n_loop part1, which must be periodic: render(n_intro + n_loop) == render(n_intro)."""
    print(f"rendering {n_intro}+{n_loop} frames ...", flush=True)
    frames = [Image.fromarray(render(T)) for T in range(n_intro + n_loop + 1)]
    wrap = frames.pop()
    intro, loop = frames[:n_intro], frames[n_intro:]
    seam = np.abs(np.asarray(wrap, np.int16) - np.asarray(loop[0], np.int16)).max()
    assert seam <= 1, f"loop not periodic: frame {n_intro + n_loop} differs from {n_intro} by {seam}"

    splash = intro[0]
    splash.save(os.path.join(here, "splash-landscape.png"), optimize=True)
    write_bmp(splash, os.path.join(here, "bootlogo.bmp"))
    write_bootanimation(os.path.join(here, "bootanimation.zip"), intro, loop)
    write_preview(os.path.join(here, "preview.webp"), [splash] * 30 + intro + loop)
    problems = validate(here)
    for p in problems:
        print("  FAIL:", p)
    print(f"  bootanimation.zip {os.path.getsize(os.path.join(here, 'bootanimation.zip')) / 1e6:.2f} MB"
          f" (budget {ZIP_BUDGET / 1e6:.2f} MB)")
    print("done, " + ("OK" if not problems else "PROBLEMS"))
    return problems


# ---------------------------------------------------------------- validation
TRIM_RE = re.compile(r"^(\d+)x(\d+)\+(\d+)\+(\d+)$")


def frames_on_screen(z, part):
    """A part's frames as Android draws them: each trimmed PNG placed at its rect, BG around it."""
    names = sorted(n for n in z.namelist() if n.startswith(part + "/") and n.endswith(".png"))
    rects = z.read(part + "/trim.txt").decode().split()
    if len(rects) != len(names):
        raise ValueError(f"{part}: {len(names)} frames but {len(rects)} trim.txt lines")
    for n, r in zip(names, rects):
        m = TRIM_RE.match(r)
        if not m:
            raise ValueError(f"{part}/trim.txt: bad line {r!r}")
        w, h, x, y = map(int, m.groups())
        im = Image.open(io.BytesIO(z.read(n))).convert("RGB")
        if im.size != (w, h) or x + w > SCREEN[0] or y + h > SCREEN[1]:
            raise ValueError(f"{n}: {im.size} doesn't match trim {r}")
        full = Image.new("RGB", SCREEN, BG)
        full.paste(im, (x, y))
        yield n, full


def validate(here):
    """Programmatic checks. Returns a list of problems (empty = all good)."""
    P = []
    d = open(os.path.join(here, "bootlogo.bmp"), "rb").read()
    if len(d) != BMP_SIZE:
        P.append(f"bmp size {len(d)} != {BMP_SIZE}")
    if d[:54] != bmp_header():
        P.append("bmp header differs from the stock layout (720x1440, 24-bit, bottom-up)")
    land = Image.open(os.path.join(here, "bootlogo.bmp")).transpose(Image.Transpose.ROTATE_90)
    prev = Image.open(os.path.join(here, "splash-landscape.png")).convert("RGB")
    if land.size != SCREEN or not np.array_equal(np.asarray(land), np.asarray(prev)):
        P.append("bmp rotated CCW != splash-landscape.png")

    zp = os.path.join(here, "bootanimation.zip")
    if os.path.getsize(zp) > ZIP_BUDGET:
        P.append(f"bootanimation.zip is {os.path.getsize(zp)} bytes, over the {ZIP_BUDGET} budget")
    with zipfile.ZipFile(zp) as z:
        for i in z.infolist():
            if i.compress_type != zipfile.ZIP_STORED:
                P.append(f"zip entry {i.filename} not stored")
        if z.testzip() is not None:
            P.append("zip CRC error")
        if z.read("desc.txt").decode() != desc_txt():
            P.append("desc.txt differs from desc_txt()")
        try:
            shown = {part: list(frames_on_screen(z, part)) for part in ("part0", "part1", "part2")}
        except ValueError as e:
            return P + [str(e)]
        if not np.array_equal(np.asarray(shown["part0"][0][1]), np.asarray(prev)):
            P.append("first animation frame != splash")
        if not np.array_equal(np.asarray(shown["part2"][0][1]), np.asarray(shown["part1"][0][1])):
            P.append("part2 != the loop's first frame")
    return P
