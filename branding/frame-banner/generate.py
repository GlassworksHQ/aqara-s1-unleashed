#!/usr/bin/env python3
"""Frame banners: banner-frame-<id>-on-dark.svg and -on-light.svg, the README header of a frame.

Reads:  frames/<id>/design/frame.json (name, brand, tagline, model), and the site's fonts (Inter
        and JetBrains Mono, SIL OFL 1.1) from pinned npm tarballs, each checked by SHA-256
        (cached in /tmp).
Writes: only into --out. Nothing in the repo, nothing on a device.

    python3 branding/frame-banner/generate.py --out /tmp/banners                # every frame with a tagline
    python3 branding/frame-banner/generate.py --out /tmp/banners --frame aqara-s1-plus

The banner is the Glassworks part banner from glassworks.dev (scripts/brand/generate.mjs at
GlassworksHQ/glassworks.dev@c014f3b, `partBanner`), with the frame's device on the left: 640 x 180,
the drawing in a dotted cell with dashed guides, and "GLASSWORKS · FRAME", the name, `frames/<id>`
and the tagline on a sweep of neutral glass. The device is drawn from frame.json's `model` (body
size and radius, bezel or screen box, finish), standing up, at the stack's isometric angle. Text is
outlined, as on the site, because GitHub shows README images as <img>, which can't load fonts.

The same frame.json gives the same bytes. It fails if the name or the tagline doesn't fit.
Needs uharfbuzz (0.57.0, as branding/boot). The format is in branding/frame-banner/README.md.
"""
import argparse
import hashlib
import json
import math
import os
import struct
import subprocess
import sys
import tarfile
import zlib

import uharfbuzz as hb

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ---------------------------------------------------------------- fonts (OFL)
# The files glassworks.dev's banners are outlined from (scripts/brand/text.mjs): @fontsource/inter and
# @fontsource/jetbrains-mono 5.3.0, Latin, as WOFF. Each npm tarball is pinned by SHA-256, its
# LICENSE must be the SIL OFL, and the one file needed is read out of it in memory.
FONT_CACHE = "/tmp/s1-unleashed-fonts/fontsource"
NPM = "https://registry.npmjs.org/@fontsource/"
PACKAGES = {  # package: (tarball, SHA-256)
    "inter": ("inter/-/inter-5.3.0.tgz", "02034af8d41dcc67ac8eab88f642e129bb8a2e8922abf30229c32725f54d8fd6"),
    "jetbrains-mono": ("jetbrains-mono/-/jetbrains-mono-5.3.0.tgz",
                       "1bbea47d1387406da5b6ccc4184cc61eae6851cc0db5c1d9bbd088ea9daa0b4a"),
}
FACES = {  # face: (package, file in the package)
    "light": ("inter", "inter-latin-300-normal.woff"),
    "regular": ("inter", "inter-latin-400-normal.woff"),
    "medium": ("inter", "inter-latin-500-normal.woff"),
    "semibold": ("inter", "inter-latin-600-normal.woff"),
    "mono": ("jetbrains-mono", "jetbrains-mono-latin-500-normal.woff"),
}


def _sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def _tarball(pkg):
    rel, sha = PACKAGES[pkg]
    dest = os.path.join(FONT_CACHE, os.path.basename(rel))
    if not os.path.exists(dest) or _sha256(dest) != sha:
        os.makedirs(FONT_CACHE, exist_ok=True)
        # curl uses the system trust store (python.org builds often lack one)
        subprocess.run(["curl", "-sSfL", "--max-time", "120", "-o", dest + ".part", NPM + rel], check=True)
        got = _sha256(dest + ".part")
        if got != sha:
            raise SystemExit(f"{NPM + rel}: SHA-256 {got}, expected {sha}")
        os.replace(dest + ".part", dest)
    return dest


def _member(tgz, name):
    with tarfile.open(tgz, "r:gz") as t:
        f = t.extractfile("package/" + name)   # read in memory; nothing is extracted to disk
        if f is None:
            raise SystemExit(f"{tgz}: no package/{name}")
        return f.read()


def woff_to_sfnt(data):
    """WOFF 1.0 to the plain font it wraps (HarfBuzz reads only the latter): unzip each table."""
    sig, flavor, _length, num, _res, _total, _maj, _min, _mo, _ml, _ol, _po, _pl = struct.unpack(">4sIIHHIHHIIIII", data[:44])
    if sig != b"wOFF":
        raise ValueError("not a WOFF 1.0 file")
    tables = []
    for i in range(num):
        tag, off, comp, orig, csum = struct.unpack(">4sIIII", data[44 + 20 * i:64 + 20 * i])
        raw = data[off:off + comp]
        tables.append((tag, zlib.decompress(raw) if comp < orig else raw, csum))
    tables.sort()
    sel = max(k for k in range(16) if (1 << k) <= num)
    out = bytearray(struct.pack(">IHHHH", flavor, num, (1 << sel) * 16, sel, num * 16 - (1 << sel) * 16))
    off = 12 + 16 * num
    body = bytearray()
    for tag, raw, csum in tables:
        out += struct.pack(">4sIII", tag, csum, off + len(body), len(raw))
        body += raw + b"\0" * (-len(raw) % 4)
    return bytes(out + body)


def font_file(face):
    """The face as a TrueType file in the cache, after its package's LICENSE: refuse it unless the
    licence really is the SIL OFL."""
    pkg, name = FACES[face]
    dest = os.path.join(FONT_CACHE, pkg, name.replace(".woff", ".ttf"))
    if not os.path.exists(dest):
        tgz = _tarball(pkg)
        if b"SIL Open Font License" not in _member(tgz, "LICENSE"):
            raise SystemExit(f"@fontsource/{pkg}: licence is not OFL")
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest + ".part", "wb") as f:
            f.write(woff_to_sfnt(_member(tgz, "files/" + name)))
        os.replace(dest + ".part", dest)
    return dest


_fonts = {}


def _font(face):
    if face not in _fonts:
        _fonts[face] = hb.Font(hb.Face(hb.Blob.from_file_path(font_file(face))))
    return _fonts[face]


def f2(v):
    s = f"{v:.2f}"
    return "0.00" if s == "-0.00" else s


def f6(v):
    s = f"{v:.6f}"
    return "0.000000" if s == "-0.000000" else s


def _layout(s, face, size, ls):
    """Glyphs and pen x, in px: each glyph's advance plus the letter-spacing, no ligatures or
    contextual forms. No kerning either, to match the site: its opentype.js getKerningValue() finds no
    pairs in these WOFF files (their kerning is in GPOS), so its banners are set unkerned (checked
    2026-11-13: these widths equal text.mjs's measure() to 0.01 px)."""
    font = _font(face)
    scale = size / font.face.upem
    buf = hb.Buffer()
    buf.add_str(s)
    buf.guess_segment_properties()
    hb.shape(font, buf, {"kern": False, "liga": False, "calt": False, "clig": False, "dlig": False})
    out, x = [], 0.0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        out.append((info.codepoint, x + pos.x_offset * scale))
        x += pos.x_advance * scale + ls
    return out, max(0.0, x - (ls if out else 0)), scale


def measure(s, face="regular", size=11.5, ls=0.0):
    return _layout(s, face, size, ls)[1]


class _Pen:
    def __init__(self, x, y, scale):
        self.x, self.y, self.k, self.d = x, y, scale, []

    def _p(self, pt):
        return f"{f2(self.x + pt[0] * self.k)} {f2(self.y - pt[1] * self.k)}"

    def moveTo(self, p):
        self.d.append("M" + self._p(p))

    def lineTo(self, p):
        self.d.append("L" + self._p(p))

    def qCurveTo(self, *pts):
        # HarfBuzz hands quadratics over one control point at a time
        self.d.append("Q" + " ".join(self._p(p) for p in pts))

    def curveTo(self, *pts):
        self.d.append("C" + " ".join(self._p(p) for p in pts))

    def closePath(self):
        self.d.append("Z")


def text(s, x, y, fill, face="regular", size=11.5, ls=0.0):
    """One <path> for a run of text, its baseline at y."""
    glyphs, _, scale = _layout(s, face, size, ls)
    font = _font(face)
    d = []
    for gid, gx in glyphs:
        pen = _Pen(x + gx, y, scale)
        font.draw_glyph_with_pen(gid, pen)
        d.extend(pen.d)
    return f'<path d="{"".join(d)}" fill="{fill}"/>'


# ---------------------------------------------------------------- the brand
# glassworks.dev scripts/brand/draw.mjs THEMES, and the banner frame (generate.mjs partBanner)
THEMES = {
    "dark": {"name": "dark", "bg": "#111319", "fg": "#f4f4f5", "mu": "#a1a1aa", "fa": "#63636b",
             "dotL": 'fill="#ffffff" fill-opacity=".09"', "border": "#3a3d46"},
    "light": {"name": "light", "bg": "#ffffff", "fg": "#0c0e13", "mu": "#4f525c", "fa": "#8d919b",
              "dotL": 'fill="#0c0e13" fill-opacity=".1"', "border": "#c9ccd4"},
}
SKY, VIOLET = "#38bdf8", "#a78bfa"
# A frame is hardware, the bottom plate of the stack: neutral glass with no hue of its own.
# Not sky to violet (the panes, "lit"), not ember (Kiln's), not a status colour.
FRAME_LIGHT = {"sweep": ("#e2e8f0", "#cbd5e1"), "strength": 0.6,
               "rim": {"dark": ("#e2e8f0", "#f4f4f5"), "light": ("#94a3b8", "#475569")}}
BW, BH, CELL = 640, 180, 300
TX = CELL + 26
TEXT_RIGHT = BW - 24          # nothing on the right may pass this x
C, S = math.cos(math.pi / 6), 0.5
NS = 'vector-effect="non-scaling-stroke"'


def guide(d, t, op=0.4, w=0.5, dash="2 1.6", color=None):
    return (f'<path d="{d}" stroke="{color or t["fg"]}" stroke-opacity="{op}" stroke-width="{w}" '
            f'stroke-dasharray="{dash}" fill="none"/>')


def plate_path(cx, top, side, r):
    """kiln/packages/brand/src/mark.ts platePath: a rounded square at the stack's angle."""
    pts = []

    def corner(ox, oy, a0):
        for i in range(9):
            a = a0 + i * math.pi / 2 / 8
            x, y = ox + r * math.cos(a), oy + r * math.sin(a)
            pts.append((cx + C * (x - y), top + S * (x + y)))
    corner(side - r, r, -math.pi / 2)
    corner(side - r, side - r, 0)
    corner(r, side - r, math.pi / 2)
    corner(r, r, math.pi)
    return "M" + "L".join(f"{x:.2f} {y:.2f}" for x, y in pts) + "Z"


def mark_small(t, x, y, size):
    """The mark's small cut (mark.ts MARK_SPEC.small): two plates, the top one lit."""
    side, r, step, top, stroke, fade = 64, 10, 24, 18, 9, 0.55
    body = (f'<path d="{plate_path(60, top + step, side, r)}" fill="none" stroke="{t["fg"]}" '
            f'stroke-opacity="{fade}" stroke-width="{stroke}" stroke-linejoin="round"/>'
            f'<path d="{plate_path(60, top, side, r)}" fill="url(#lit)"/>')
    return f'<g transform="translate({x} {y}) scale({size / 120:.4f})">{body}</g>'


def defs(t):
    return ("<defs>"
            f'<linearGradient id="lit" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{SKY}"/>'
            f'<stop offset="1" stop-color="{VIOLET}"/></linearGradient>'
            f'<pattern id="dotL" width="9" height="9" patternUnits="userSpaceOnUse">'
            f'<circle cx="4.5" cy="4.5" r=".9" {t["dotL"]}/></pattern>'
            "</defs>")


# ---------------------------------------------------------------- the device
def screen_box(m):
    """The screen in body mm: the given box (a screen smaller than the face), or the face less the bezel."""
    W, H = m["body"]["width"], m["body"]["height"]
    sc = m["screen"]
    if "width" in sc:
        return {"x": (W - sc["width"]) / 2, "y": sc["top"], "w": sc["width"], "h": sc["height"], "r": sc["radius"]}
    b = m["bezel"]
    return {"x": b, "y": b, "w": W - 2 * b, "h": H - 2 * b, "r": sc["radius"]}


def dashboard(t, sc, lit=(0, 4)):
    """The stack's six cells on the screen, two of them lit: 3 x 2 in landscape, 2 x 3 in portrait."""
    cols, rows = (3, 2) if sc["w"] > sc["h"] else (2, 3)
    pad = min(sc["w"], sc["h"]) * 0.07
    cw, ch = (sc["w"] - pad * (cols + 1)) / cols, (sc["h"] - pad * (rows + 1)) / rows
    r = min(cw, ch) * 0.1
    out = []
    for i in range(cols * rows):
        x = sc["x"] + pad + (i % cols) * (cw + pad)
        y = sc["y"] + pad + (i // cols) * (ch + pad)
        box = f'x="{f2(x)}" y="{f2(y)}" width="{f2(cw)}" height="{f2(ch)}" rx="{f2(r)}"'
        out.append(f'<rect {box} fill="url(#lit)" fill-opacity=".9"/>' if i in lit else
                   f'<rect {box} fill="{t["fg"]}" fill-opacity=".08" stroke="{t["fg"]}" stroke-opacity=".3" stroke-width=".5" {NS}/>')
    return "".join(out)


def device(t, m):
    """The device standing up: its face on the plane facing down-left, its depth stepped back up-right,
    fitted in the cell, with guides through the face's corners."""
    W, H, R = m["body"]["width"], m["body"]["height"], m["body"]["radius"]
    D = max(m["body"]["depth"], 4)
    s = min(210 / (C * (W + D)), 128 / (S * W + H + S * D))
    dw, dh = C * (W + D) * s, (S * W + H + S * D) * s
    ox = (CELL - dw) / 2 + C * D * s          # the face's top-left corner
    oy = (BH - dh) / 2 + S * D * s

    def face(dx, dy):
        return f"matrix({f6(C * s)} {f6(S * s)} 0 {f6(s)} {f2(ox + dx)} {f2(oy + dy)})"
    body = f'width="{W}" height="{H}" rx="{R}"'
    steps = math.ceil(D * s * 2)
    slab = []
    for i in range(steps, 0, -1):
        k = i / steps * D * s
        slab.append(f'<g transform="{face(C * k, -S * k)}"><rect {body} fill="{t["bg"]}" stroke="{t["fg"]}" '
                    f'stroke-opacity="{0.5 if i == steps else 0}" stroke-width=".6" {NS}/></g>')
    slab.append(f'<g transform="{face(C * D * s, -S * D * s)}"><rect {body} fill="{t["fg"]}" fill-opacity=".1"/></g>')
    mirror = m.get("finish") == "mirror"
    sc = screen_box(m)
    front = (f'<g transform="{face(0, 0)}">'
             f'<rect {body} fill="{t["bg"]}" stroke="{t["fg"]}" stroke-opacity=".75" stroke-width=".8" {NS}/>'
             + (f'<rect {body} fill="{t["fg"]}" fill-opacity=".05"/>' if mirror else "")
             + f'<rect x="{f2(sc["x"])}" y="{f2(sc["y"])}" width="{f2(sc["w"])}" height="{f2(sc["h"])}" rx="{sc["r"]}" '
               f'fill="{t["fg"]}" fill-opacity="{0.04 if mirror else 0.07}" stroke="{t["fg"]}" stroke-opacity=".4" stroke-width=".5" {NS}/>'
             + dashboard(t, sc) + "</g>")
    lx, ty = ox, oy
    rx = ox + C * W * s
    by = oy + H * s
    bry = oy + S * W * s + H * s
    g = (guide(f"M0 {f2(ty)}H{CELL}M0 {f2(bry)}H{CELL}", t, op=0.26, w=0.8, dash="5 4")
         + guide(f"M{f2(lx)} 0V{BH}M{f2(rx)} 0V{BH}", t, op=0.22, w=0.8, dash="5 4")
         + guide(f"M{f2(lx - 200 * C)} {f2(ty - 200 * S)}L{f2(lx + 400 * C)} {f2(ty + 400 * S)}"
                 f"M{f2(lx - 200 * C)} {f2(by - 200 * S)}L{f2(lx + 400 * C)} {f2(by + 400 * S)}", t, op=0.2, w=0.8, dash="5 4"))
    return g + "".join(slab) + front


# ---------------------------------------------------------------- the banner
class DoesNotFit(ValueError):
    pass


def split_name(frame):
    """(light part, bold part): `brand` is the light part, the rest of `name` the bold part."""
    name, brand = frame["name"], frame.get("brand")
    if not brand:
        return "", name
    if not name.startswith(brand + " "):
        raise ValueError(f'{frame["id"]}: brand "{brand}" must start name "{name}", followed by a space')
    return brand, name[len(brand) + 1:]


def banner(frame, theme):
    t = THEMES[theme]
    light = t["name"] == "light"
    p = FRAME_LIGHT
    a, b = p["sweep"]
    ra, rb = p["rim"][t["name"]]
    k = p["strength"]
    tw = BW - CELL
    name_a, name_b = split_name(frame)
    code = f'frames/{frame["id"]}'
    tagline = frame.get("tagline")
    if not tagline:
        raise ValueError(f'{frame["id"]}: no "tagline" in frame.json')

    size = 34

    def title_w():
        wb = measure(name_b, "semibold", size, -0.6)
        return wb + (measure(name_a, "light", size, -0.6) + 9 if name_a else 0)
    while title_w() > TEXT_RIGHT - TX and size > 20:
        size -= 1
    if title_w() > TEXT_RIGHT - TX:
        raise DoesNotFit(f'{frame["id"]}: the name is {title_w():.1f} px wide at {size} px, past {TEXT_RIGHT - TX} px')
    for what, w in (("tagline", measure(tagline, "regular", 11.5)), ("frames/<id> line", measure(code, "mono", 12))):
        if TX + 1 + w > TEXT_RIGHT:
            raise DoesNotFit(f'{frame["id"]}: the {what} is {w:.1f} px wide, {TX + 1 + w - TEXT_RIGHT:.1f} px past the edge; shorten it')

    sweep = (f'<linearGradient id="sweep" gradientUnits="userSpaceOnUse" x1="{CELL}" y1="0" x2="{BW}" y2="0">'
             f'<stop offset="0" stop-color="{a}" stop-opacity="{0.05 * k:.4g}"/><stop offset=".55" stop-color="{a}" stop-opacity="{0.14 * k:.4g}"/>'
             f'<stop offset="1" stop-color="{b}" stop-opacity="{(0.3 if light else 0.38) * k:.4g}"/></linearGradient>'
             f'<linearGradient id="rim" gradientUnits="userSpaceOnUse" x1="{CELL}" y1="0" x2="{BW}" y2="0">'
             f'<stop offset="0" stop-color="{ra}"/><stop offset="1" stop-color="{rb}"/></linearGradient>'
             f'<linearGradient id="ramp" gradientUnits="userSpaceOnUse" x1="{CELL}" y1="0" x2="{BW - 6}" y2="0">'
             f'<stop offset="0" stop-color="#fff" stop-opacity=".2"/><stop offset="1" stop-color="#fff"/></linearGradient>'
             f'<mask id="lit-edge" maskUnits="userSpaceOnUse" x="{CELL}" y="0" width="{tw}" height="{BH}">'
             f'<rect x="{CELL}" width="{tw}" height="{BH}" fill="url(#ramp)"/></mask>')
    box = f'x=".5" y=".5" width="{BW - 1}" height="{BH - 1}" rx="16"'
    if name_a:
        wa = measure(name_a, "light", size, -0.6)
        title = (text(name_a, TX, 92, t["mu"], "light", size, -0.6)
                 + text(name_b, TX + wa + 9, 92, t["fg"], "semibold", size, -0.6))
    else:
        title = text(name_b, TX, 92, t["fg"], "semibold", size, -0.6)
    words = (mark_small(t, TX, 40, 16)
             + text("GLASSWORKS · FRAME", TX + 22, 52, t["fa"], "medium", 9, 2)
             + title
             + text(code, TX + 1, 118, t["mu"], "mono", 12)
             + text(tagline, TX + 1, 138, t["fa"], "regular", 11.5))
    body = (f"<defs>{sweep}</defs>"
            f'<clipPath id="clip"><rect x="1" y="1" width="{BW - 2}" height="{BH - 2}" rx="15.5"/></clipPath>'
            f'<rect {box} fill="{t["bg"]}"/>'
            f'<g clip-path="url(#clip)"><rect width="{CELL}" height="{BH}" fill="url(#dotL)"/>'
            + device(t, frame["model"])
            + f'<rect x="{CELL}" width="{tw}" height="{BH}" fill="url(#sweep)"/></g>'
            + guide(f"M{CELL} 0V{BH}", t, op=0.55, w=0.7, dash="4 3")
            + f'<rect {box} fill="none" stroke="{t["border"]}"/><rect {box} fill="none" stroke="url(#rim)" mask="url(#lit-edge)"/>'
            + words)
    label = f'{frame["name"]}, a Glassworks frame'
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" role="img" aria-label="{label}" viewBox="0 0 {BW} {BH}" '
           f'width="{BW}" height="{BH}"><title>{label}</title>{defs(t)}{body}</svg>\n')
    if "nan" in svg.lower().replace("banner", ""):
        raise ValueError(f'{frame["id"]}: the drawing came out with NaN')
    return svg


def read_frame(frame_id, repo=REPO):
    with open(os.path.join(repo, "frames", frame_id, "design", "frame.json"), encoding="utf-8") as f:
        return json.load(f)


def frames_with_tagline(repo=REPO):
    root = os.path.join(repo, "frames")
    ids = sorted(d for d in os.listdir(root) if os.path.isfile(os.path.join(root, d, "design", "frame.json")))
    return [i for i in ids if read_frame(i, repo).get("tagline")]


def write(frame_id, out, repo=REPO):
    frame = read_frame(frame_id, repo)
    paths = []
    for theme in ("dark", "light"):
        path = os.path.join(out, f"banner-frame-{frame_id}-on-{theme}.svg")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(banner(frame, theme))
        paths.append(path)
    return paths


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", required=True, help="the folder to write into")
    ap.add_argument("--frame", action="append", help="a frame id; default: every frame with a tagline")
    a = ap.parse_args(argv)
    os.makedirs(a.out, exist_ok=True)
    try:
        for fid in a.frame or frames_with_tagline():
            for p in write(fid, a.out):
                print(p)
    except (DoesNotFit, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
