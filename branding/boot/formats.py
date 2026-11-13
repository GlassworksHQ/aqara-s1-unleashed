"""Output formats for the boot branding: Android's bootanimation.zip and the bootloader splash.

Facts this file relies on:
  * Android's bootanimation (frameworks/base cmds/bootanimation/BootAnimation.cpp), read 2026-11-12
    at android-10.0.0_r41 and at android-6.0.1_r81, 7.0.0_r1 and 7.1.1_r1:
      - The surface is RGB_565 with dithering off on all of them.
      - A part with count != 1 keeps every frame as a GL texture until the animation ends; a part
        with count 1 decodes each frame, uploads it and moves on. Several desc.txt lines may name
        the same folder (each gets its own frame list). Same on 6.0.1 and 10.
      - 'c' parts play to the end even after boot completes; 'p' parts stop at once (6.0.1 on).
      - trim.txt ("WxH+X+Y" per frame, in the part's folder) is read from 7.1 on (not in 7.0.0_r1).
        Frames are then drawn 1:1 at that rect, and everything outside it is cleared to the part's
        colour every frame, blending off. So a trimmed frame must be flat BG outside its rect.
        Older players draw every frame at the animation's full size, so those get full frames.
  * Allwinner boot-resource splash (the S1's BOOTLOGO.BMP): a 24-bit BMP, bottom-up, 54-byte
    header, of the panel's portrait framebuffer: the landscape picture rotated 90 degrees clockwise
    (PIL: landscape.transpose(ROTATE_270)). tools/branding/set-bootlogo.py and Kiln's
    replaceBootlogo() swap it in place, so its size must stay exactly the stock file's.
"""
import datetime
import hashlib
import io
import json
import os
import re
import struct
import subprocess
import zipfile

import numpy as np
from PIL import Image

FPS = 30
LOOP_REPEATS = 25             # p-part repeats of the loop (3 min at 7.2 s) before it holds its first frame


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# The brand's night. The S1 composites its boot animation in 32-bit (measured 2026-11-12), so it
# shows exactly; on an RGB 565 surface it would not (docs/research/boot-animation.md, step 5).
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
def bmp_header(w, h):
    size_img = w * 3 * h
    return (b"BM" + struct.pack("<IHHI", 54 + size_img, 0, 0, 54)
            + struct.pack("<IiiHHIIiiII", 40, w, h, 1, 24, 0, size_img, 2834, 2834, 0, 0))


def bmp_size(target):
    """The boot-resource splash's file size: the screen, 24-bit, no row padding. Rotated, the
    screen's height is the stored row width, so it must be a multiple of 4 (Target checks)."""
    return 54 + target.width * target.height * 3


def write_bmp(target, landscape, path):
    img = landscape.transpose(Image.Transpose.ROTATE_270)   # stored rotated 90 deg clockwise
    assert img.size == (target.height, target.width), img.size
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


def desc_txt(target):
    """part0 plays once and finishes even if boot completes. The loop is a count-1 'p' part named
    LOOP_REPEATS times, so its frames are decoded as they play and never held as textures (a
    count-0 part would keep all 216 frames as GPU textures). part2, the loop's first
    frame, holds after that."""
    bg = "#%02x%02x%02x" % BG
    lines = [f"{target.width} {target.height} {FPS}", f"c 1 0 part0 {bg}"]
    lines += [f"p 1 0 part1 {bg}"] * LOOP_REPEATS
    lines += [f"p 0 0 part2 {bg}"]
    return "\n".join(lines) + "\n"


def write_bootanimation(target, path, intro, loop):
    dt = (2026, 1, 1, 0, 0, 0)

    def add(z, name, data, is_dir=False):
        zi = zipfile.ZipInfo(name, dt)
        zi.compress_type = zipfile.ZIP_STORED
        zi.create_system = 3
        zi.external_attr = ((0o40755 << 16) | 0x10) if is_dir else (0o100644 << 16)
        z.writestr(zi, data)

    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        add(z, "desc.txt", desc_txt(target).encode())
        n = 0
        for part, frames in (("part0", intro), ("part1", loop), ("part2", loop[:1])):
            add(z, part + "/", b"", is_dir=True)
            rects = []
            for fr in frames:
                if target.trim:
                    fr, rect = trim(fr)
                    rects.append(rect)
                add(z, f"{part}/frame_{n:03d}.png", png_bytes(fr))
                n += 1
            if target.trim:
                add(z, f"{part}/trim.txt", ("\n".join(rects) + "\n").encode())


def write_preview(target, path, frames, scale=0.5):
    size = (round(target.width * scale), round(target.height * scale))
    small = [f.resize(size, Image.LANCZOS) for f in frames]
    durs = [34 if i % 3 == 0 else 33 for i in range(len(small))]   # ~30 fps
    # quality 100: below it, libwebp treats small per-pixel changes as unchanged and the slow
    # breathing turns into steps
    small[0].save(path, "WEBP", save_all=True, append_images=small[1:], duration=durs, loop=0, quality=100, method=5)


# The splashes undo knows as ours (#106, branding/bootlogos.json). Every build lists its splash.
BOOTLOGOS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "bootlogos.json")


def list_bootlogo(bmp, name):
    """Adds bmp's SHA-256 to branding/bootlogos.json, the splashes undo knows as ours. Only appends."""
    sha = hashlib.sha256(open(bmp, "rb").read()).hexdigest()
    with open(BOOTLOGOS, encoding="utf-8") as f:
        doc = json.load(f)
    if any(b["sha256"] == sha for b in doc["bootlogos"]):
        return
    doc["bootlogos"].append({"sha256": sha, "name": name, "since": datetime.date.today().isoformat()})
    lines = ",\n".join("    " + json.dumps(b) for b in doc["bootlogos"])
    with open(BOOTLOGOS, "w", encoding="utf-8") as f:
        f.write('{\n  "about": ' + json.dumps(doc["about"]) + ',\n  "bootlogos": [\n' + lines + "\n  ]\n}\n")
    print(f"  listed {sha[:12]}… in {os.path.relpath(BOOTLOGOS)} (commit it with the new bootlogo.bmp)")


def build(target, out, render, n_intro, n_loop, splash_name):
    """render(T) -> screen-sized RGB array, T = global frame index. Frames [0, n_intro) are part0
    and the next n_loop part1, which must be periodic: render(n_intro + n_loop) == render(n_intro).
    Writes into `out` and lists a splash it writes as `splash_name` (bootlogos.json);
    returns the list of problems (empty = all good)."""
    os.makedirs(out, exist_ok=True)
    print(f"{target.name}: {target.width}x{target.height}, "
          f"{'trimmed' if target.trim else 'full'} frames, splash {target.splash or 'none'}", flush=True)
    print(f"rendering {n_intro}+{n_loop} frames ...", flush=True)
    frames = [Image.fromarray(render(T)) for T in range(n_intro + n_loop + 1)]
    wrap = frames.pop()
    intro, loop = frames[:n_intro], frames[n_intro:]
    seam = np.abs(np.asarray(wrap, np.int16) - np.asarray(loop[0], np.int16)).max()
    assert seam <= 1, f"loop not periodic: frame {n_intro + n_loop} differs from {n_intro} by {seam}"

    splash = intro[0]
    splash.save(os.path.join(out, "splash-landscape.png"), optimize=True)
    if target.splash:
        write_bmp(target, splash, os.path.join(out, "bootlogo.bmp"))
        list_bootlogo(os.path.join(out, "bootlogo.bmp"), splash_name)
    write_bootanimation(target, os.path.join(out, "bootanimation.zip"), intro, loop)
    write_preview(target, os.path.join(out, "preview.webp"), [splash] * 30 + intro + loop)
    problems = validate(target, out)
    for p in problems:
        print("  FAIL:", p)
    size = os.path.getsize(os.path.join(out, "bootanimation.zip"))
    budget = f" (budget {target.zip_budget / 1e6:.2f} MB)" if target.zip_budget else ""
    print(f"  bootanimation.zip {size / 1e6:.2f} MB{budget}")
    print("done, " + ("OK" if not problems else "PROBLEMS"))
    return problems


# ---------------------------------------------------------------- validation
TRIM_RE = re.compile(r"^(\d+)x(\d+)\+(\d+)\+(\d+)$")


def frames_on_screen(target, z, part):
    """A part's frames as Android draws them: each trimmed PNG placed at its rect, BG around it;
    without trim.txt, each frame at full size."""
    names = sorted(n for n in z.namelist() if n.startswith(part + "/") and n.endswith(".png"))
    if target.trim:
        rects = z.read(part + "/trim.txt").decode().split()
        if len(rects) != len(names):
            raise ValueError(f"{part}: {len(names)} frames but {len(rects)} trim.txt lines")
    else:
        if part + "/trim.txt" in z.namelist():
            raise ValueError(f"{part}/trim.txt in a zip for a player that doesn't read it")
        rects = [f"{target.width}x{target.height}+0+0"] * len(names)
    for n, r in zip(names, rects):
        m = TRIM_RE.match(r)
        if not m:
            raise ValueError(f"{part}/trim.txt: bad line {r!r}")
        w, h, x, y = map(int, m.groups())
        im = Image.open(io.BytesIO(z.read(n))).convert("RGB")
        if im.size != (w, h) or x + w > target.width or y + h > target.height:
            raise ValueError(f"{n}: {im.size} doesn't match trim {r}")
        full = Image.new("RGB", target.screen, BG)
        full.paste(im, (x, y))
        yield n, full


def validate(target, out):
    """Programmatic checks. Returns a list of problems (empty = all good)."""
    P = []
    prev = Image.open(os.path.join(out, "splash-landscape.png")).convert("RGB")
    if target.splash == "allwinner-boot-resource":
        d = open(os.path.join(out, "bootlogo.bmp"), "rb").read()
        if len(d) != bmp_size(target):
            P.append(f"bmp size {len(d)} != {bmp_size(target)}")
        if d[:54] != bmp_header(target.height, target.width):
            P.append(f"bmp header differs from the stock layout ({target.height}x{target.width}, 24-bit, bottom-up)")
        land = Image.open(os.path.join(out, "bootlogo.bmp")).transpose(Image.Transpose.ROTATE_90)
        if land.size != target.screen or not np.array_equal(np.asarray(land), np.asarray(prev)):
            P.append("bmp rotated CCW != splash-landscape.png")

    zp = os.path.join(out, "bootanimation.zip")
    if target.zip_budget and os.path.getsize(zp) > target.zip_budget:
        P.append(f"bootanimation.zip is {os.path.getsize(zp)} bytes, over the {target.zip_budget} budget")
    with zipfile.ZipFile(zp) as z:
        for i in z.infolist():
            if i.compress_type != zipfile.ZIP_STORED:
                P.append(f"zip entry {i.filename} not stored")
        if z.testzip() is not None:
            P.append("zip CRC error")
        if z.read("desc.txt").decode() != desc_txt(target):
            P.append("desc.txt differs from desc_txt()")
        try:
            shown = {part: list(frames_on_screen(target, z, part)) for part in ("part0", "part1", "part2")}
        except ValueError as e:
            return P + [str(e)]
        if not np.array_equal(np.asarray(shown["part0"][0][1]), np.asarray(prev)):
            P.append("first animation frame != splash")
        if not np.array_equal(np.asarray(shown["part2"][0][1]), np.asarray(shown["part1"][0][1])):
            P.append("part2 != the loop's first frame")
    return P
