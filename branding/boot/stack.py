"""The Glassworks boot animation's design: the stack, the wordmark and the device line.

The design is the "Translucent" frame of kiosk-ui/boot/boot-reference.html (commit 5df8f044).
`motion` is its `model(spring(.74, .7), 2.4)` and `paint` its `stack()` plus the page around it,
ported line for line. The page lays the screen out in CSS px; target.Layout maps that onto any
screen (one CSS px is L.scale screen px, one unit of the stack's 120-unit viewBox is L.unit).

Frame 0 is the stack in dashed outline. It is also the bootloader splash, so nothing jumps when
Android takes over. Run it through generate.py.
"""
import math
import os
import sys
from dataclasses import dataclass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
import uharfbuzz as hb  # noqa: E402
from PIL import Image, ImageDraw, ImageFont  # noqa: E402

from formats import BG, FPS, font_file, hexc  # noqa: E402
from target import DEVICE_CSS, WORD_CSS, WORD_LINE, Layout  # noqa: E402

NAME = "Glassworks stack"                # how branding/bootlogos.json names its splash
FG = hexc("#f4f4f5")
FA = hexc("#63636b")
BODY = hexc("#0b0c11")
W = (0.7, 0.45, 0.2)                     # outline weight per plate, 0 = top

PLAY, LOOP = 4.0, 7.2
# The reference's loop is periodic only from PLAY + 0.8 s: the float eases in over 0.8 s and the
# device line finishes at 4.2 s. So part0 runs to there and part1 is one 7.2 s period after it.
INTRO_END = PLAY + 0.8
N_INTRO, N_LOOP = round(INTRO_END * FPS), round(LOOP * FPS)


# ---------------------------------------------------------------- motion (the reference's model())
def clamp(v):
    return max(0.0, min(1.0, v))


def seg(t, a, b):
    return clamp((t - a) / (b - a))


def ease(x):
    return x * x * (3 - 2 * x)


def spring(z, period):
    w = 2 * math.pi / period
    wd = w * math.sqrt(1 - z * z)

    def x(u):
        return 0.0 if u <= 0 else 1 - math.exp(-z * w * u) * (math.cos(wd * u) + (z * w / wd) * math.sin(wd * u))
    contact = next((i * 0.002 for i in range(1500) if x(i * 0.002) >= 1), 0.0)
    return x, contact


FALL, CONTACT = spring(0.74, 0.7)
H = 120
GIVE = 2.4


def start(i):
    return 0.2 + (2 - i) * 0.55


def give(u, amp):
    return 0.0 if u <= 0 else -amp * math.exp(-7 * u) * math.sin(math.pi * u / 0.16) * (1 if u < 0.9 else 0)


def breathe(t):
    return 0.88 + 0.12 * math.cos((t - PLAY) * 2 * math.pi / 3.6) if t > PLAY else 1.0


def float_(t):
    u = max(0.0, t - PLAY)
    w = 2 * math.pi / 3.6
    r = ease(clamp(u / 0.8))
    return [r * ((2 - i) * 4 * (0.5 - 0.5 * math.cos(w * u - i * 0.6)) + 6 * math.sin(w * u * 0.5)) for i in range(3)]


@dataclass(frozen=True)
class Plate:
    lift: float     # units above its resting place
    op: float       # 0..1, how far it has appeared (the top plate's body is solid whenever op > 0)
    ghost: float    # 0..1, the dashed outline at its resting place


@dataclass(frozen=True)
class State:
    plates: tuple   # 3 Plates, 0 = top
    lit: float      # the top plate's colour
    glow: float     # its brightness (1 = full; the loop breathes it)
    word: float     # the wordmark
    device: float   # the device line's opacity


def motion(t):
    fl = float_(t)
    off = [0.0, 0.0, 0.0]
    for i in range(3):
        land = start(i) + CONTACT
        for j in range(i, 3):
            off[j] += give(t - land, GIVE * (1 if j == i else 0.6))
    plates = tuple(Plate(lift=H * (1 - FALL(t - start(i))) + off[i] + fl[i],
                         op=seg(t, start(i), start(i) + 0.2),
                         ghost=1 - seg(t, start(i) + CONTACT - 0.05, start(i) + CONTACT + 0.25))
                   for i in range(3))
    return State(plates, ease(seg(t, 2.35, 3.1)), breathe(t), ease(seg(t, 3.0, 3.8)), seg(t, 3.5, 4.2) * 0.8)


# ---------------------------------------------------------------- geometry
C, S = math.cos(math.pi / 6), 0.5


def plate_points(L, top, cx=60, side=60, r=9):
    """The reference's plate(): an isometric rounded square, 36 points, in units."""
    pts = []
    for ox, oy, a0 in ((side - r, r, -math.pi / 2), (side - r, side - r, 0), (r, side - r, math.pi / 2), (r, r, math.pi)):
        for i in range(9):
            a = a0 + i * math.pi / 16
            x, y = ox + r * math.cos(a), oy + r * math.sin(a)
            pts.append((cx + C * (x - y), top + S * (x + y)))
    ox, oy = L.origin
    return np.array([(ox + x * L.unit, oy + y * L.unit) for x, y in pts], np.float64)


def plate_top(i, lift=0.0):
    return 11 + i * 19 - lift


class Field:
    """Distance from each pixel of a window to a closed polygon, the arc length (in units) of the
    nearest point, and inside/outside. Coverage from it is antialiased analytically at 1x."""

    def __init__(self, L, pts, pad):
        self.unit = UNIT = L.unit
        x0, y0 = np.floor(pts.min(0) - pad).astype(int)
        x1, y1 = np.ceil(pts.max(0) + pad).astype(int)
        self.x0, self.y0 = max(x0, 0), max(y0, 0)
        x1, y1 = min(x1, L.screen[0]), min(y1, L.screen[1])
        self.empty = x1 <= self.x0 or y1 <= self.y0
        if self.empty:
            return
        ys, xs = np.mgrid[self.y0:y1, self.x0:x1].astype(np.float64) + 0.5
        a, b = pts, np.roll(pts, -1, 0)
        seglen = np.hypot(*(b - a).T)
        cum = np.concatenate([[0], np.cumsum(seglen)[:-1]])
        self.length = seglen.sum() / UNIT
        d = np.full(xs.shape, np.inf)
        s = np.zeros(xs.shape)
        wind = np.zeros(xs.shape, np.int32)
        for (ax, ay), (bx, by), sl, c0 in zip(a, b, seglen, cum):
            dx, dy = bx - ax, by - ay
            u = np.clip(((xs - ax) * dx + (ys - ay) * dy) / (sl * sl), 0, 1)
            dd = np.hypot(xs - (ax + u * dx), ys - (ay + u * dy))
            closer = dd < d
            d = np.where(closer, dd, d)
            s = np.where(closer, (c0 + u * sl) / UNIT, s)
            up = (ay <= ys) & (by > ys)
            down = (ay > ys) & (by <= ys)
            side = dx * (ys - ay) - (xs - ax) * dy
            wind += (up & (side > 0)).astype(np.int32) - (down & (side < 0)).astype(np.int32)
        self.d, self.s, self.inside = d, s, wind != 0
        self.xs, self.ys = xs, ys
        self.box = pts.min(0), pts.max(0)

    def fill(self):
        sd = np.where(self.inside, self.d, -self.d)
        return np.clip(sd + 0.5, 0, 1)

    def stroke(self, width_units, dash=None):
        UNIT = self.unit
        cov = np.clip(width_units * UNIT / 2 - self.d + 0.5, 0, 1)
        if dash:
            on, off = dash
            m = np.mod(self.s, on + off)
            inside = np.where(m <= on, np.minimum(m, on - m), -np.minimum(m - on, on + off - m))
            # the path ends mid-pattern: the last dash stops at the end of the path
            inside = np.minimum(inside, self.length - self.s)
            cov = cov * np.clip(inside * UNIT + 0.5, 0, 1)
        return cov

    def bbox_uv(self):
        (bx, by), (ex, ey) = self.box
        return (self.xs - bx) / (ex - bx), (self.ys - by) / (ey - by)


# ---------------------------------------------------------------- painting
class Canvas:
    """Straight sRGB compositing, as the browser does: `over` with a colour, a coverage and an alpha."""

    def __init__(self, base):
        self.px = base.copy()

    def over(self, f, rgb, alpha):
        if f.empty:
            return
        a = np.asarray(alpha, np.float64)
        if a.ndim == 0 and a <= 0:
            return
        v = self.px[f.y0:f.y0 + f.d.shape[0], f.x0:f.x0 + f.d.shape[1]]
        a = np.broadcast_to(a, f.d.shape)[..., None]
        v += (np.asarray(rgb, np.float64) - v) * a


class Group:
    """An SVG <g opacity>: paint into a premultiplied layer, then composite it once."""

    def __init__(self, f):
        self.f = f
        self.rgb = np.zeros(f.d.shape + (3,))
        self.a = np.zeros(f.d.shape)

    def over(self, rgb, alpha):
        a = np.broadcast_to(np.asarray(alpha, np.float64), self.a.shape)
        rgb = np.broadcast_to(np.asarray(rgb, np.float64), self.rgb.shape)
        self.rgb = rgb * a[..., None] + self.rgb * (1 - a[..., None])
        self.a = a + self.a * (1 - a)

    def onto(self, canvas, opacity):
        f = self.f
        v = canvas.px[f.y0:f.y0 + f.d.shape[0], f.x0:f.x0 + f.d.shape[1]]
        a = (self.a * opacity)[..., None]
        v *= 1 - a
        v += self.rgb * opacity


def gradient(t, stops):
    """Linear interpolation through [(offset, (r, g, b), alpha)], padded at both ends."""
    offs = np.array([s[0] for s in stops])
    cols = np.array([s[1] for s in stops], np.float64)
    alph = np.array([s[2] for s in stops])
    t = np.clip(t, offs[0], offs[-1])
    rgb = np.stack([np.interp(t, offs, cols[:, k]) for k in range(3)], -1)
    return rgb, np.interp(t, offs, alph)


TRANS = [(0, hexc("#38bdf8"), 0.78), (0.55, hexc("#818cf8"), 0.66), (1, hexc("#a78bfa"), 0.76)]
RIM = [(0, hexc("#ffffff"), 0.95), (0.5, hexc("#e0f2fe"), 0.35), (1, hexc("#c4b5fd"), 0.55)]


def gaussian_blur(a, sigma):
    r = int(math.ceil(sigma * 3))
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()

    def band(n):
        m = np.zeros((n, n))
        for o, w in zip(range(-r, r + 1), k):
            m += np.eye(n, k=o) * w
        return m
    return band(a.shape[0]) @ a @ band(a.shape[1]).T


def paint_stack(cv, st, scene):
    L = scene.layout
    UNIT = L.unit
    P = st.plates
    pad = 6 * UNIT
    under = []   # the lower plates' outlines, which the top plate's frost blurs
    for i in (2, 1):
        q = P[i]
        if q.ghost > 0:
            f = Field(L, plate_points(L, plate_top(i)), pad)
            cv.over(f, FG, f.stroke(4, (7, 6)) * 0.22 * q.ghost)
        if q.op <= 0:
            continue
        pts = plate_points(L, plate_top(i, q.lift))
        f = Field(L, pts, pad)
        cv.over(f, FG, f.stroke(5) * round(W[i] * q.op, 3))
        under.append((pts, min(1.0, round(W[i] * q.op, 3) * 2.6)))
    q = P[0]
    if q.ghost > 0:
        f = Field(L, plate_points(L, plate_top(0)), pad)
        cv.over(f, FG, f.stroke(4, (7, 6)) * 0.22 * q.ghost)
    if q.op <= 0:
        return
    f = Field(L, plate_points(L, plate_top(0, q.lift)), pad)
    if f.empty:
        return
    body = f.fill()
    cv.over(f, BODY, body)
    if under:
        # frost: the plates below, stroked at 9, blurred (stdDeviation 5.5), nudged 2.5 down, clipped
        m = 6 * 5.5 * UNIT
        g = Field(L, plate_points(L, plate_top(0, q.lift)), pad + m)
        lay = Group(g)
        for pts, a in under:
            sub = Field(L, pts, 6 * UNIT)
            cov = np.zeros(g.d.shape)
            ys, xs = sub.y0 - g.y0, sub.x0 - g.x0
            if not sub.empty:
                h, w = sub.d.shape
                y0, x0 = max(ys, 0), max(xs, 0)
                y1, x1 = min(ys + h, cov.shape[0]), min(xs + w, cov.shape[1])
                if y1 > y0 and x1 > x0:
                    cov[y0:y1, x0:x1] = sub.stroke(9)[y0 - ys:y1 - ys, x0 - xs:x1 - xs]
            lay.over(FG, cov * a)
        blur_a = gaussian_blur(lay.a, 5.5 * UNIT)
        blur_rgb = np.stack([gaussian_blur(lay.rgb[..., k], 5.5 * UNIT) for k in range(3)], -1)
        dy = 2.5 * UNIT
        shift = np.arange(g.d.shape[0]) - dy
        rows = np.clip(shift, 0, g.d.shape[0] - 1)
        r0 = np.floor(rows).astype(int)
        r1 = np.minimum(r0 + 1, g.d.shape[0] - 1)
        fr = (rows - r0)[:, None]
        ba = blur_a[r0] * (1 - fr) + blur_a[r1] * fr
        br = blur_rgb[r0] * (1 - fr[..., None]) + blur_rgb[r1] * fr[..., None]
        oy, ox = f.y0 - g.y0, f.x0 - g.x0
        h, w = f.d.shape
        frost = Group(f)
        frost.rgb = br[oy:oy + h, ox:ox + w] * body[..., None]
        frost.a = ba[oy:oy + h, ox:ox + w] * body
        frost.onto(cv, q.op)
    if st.lit > 0:
        u, v = f.bbox_uv()
        lit = Group(f)
        rgb, a = gradient((u + v) / 2, TRANS)
        lit.over(rgb, a * body)
        h, w = f.d.shape
        lit.over((255, 255, 255), scene.grain[f.y0:f.y0 + h, f.x0:f.x0 + w] * body)
        lit.over((255, 255, 255), (1 - np.clip(np.hypot(u - 0.3, v - 0.15) / 0.7, 0, 1)) * 0.3 * body)
        lit.over(BG, (1 - st.glow) * body)
        rgb, a = gradient(v, RIM)
        lit.over(rgb, a * f.stroke(2.2))
        lit.onto(cv, st.lit * q.op)
    if st.lit < 1:
        cv.over(f, FG, f.stroke(5) * round(W[0] * q.op * (1 - st.lit), 3))


# ---------------------------------------------------------------- text
_fonts = {}


def shaped(scale, text, css_px, wght, tracking_em):
    """Glyphs as (char, x) in screen px from the run's left edge, and the run's advance, shaped by
    HarfBuzz with kerning and CSS letter-spacing (added after every letter, the last one too)."""
    path = font_file("inter")
    size = css_px * scale
    opsz = 14   # the page loads Inter from Google Fonts by wght only, which serves the default opsz
    k = (size, wght)
    if k not in _fonts:
        f = ImageFont.truetype(path, round(size * 64) / 64)
        f.set_variation_by_axes([opsz, wght])
        _fonts[k] = f
    blob = hb.Blob.from_file_path(path)
    hf = hb.Font(hb.Face(blob))
    hf.set_variations({"wght": wght, "opsz": opsz})
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(hf, buf, {"kern": True, "liga": False})
    upem = hf.face.upem
    x, out = 0.0, []
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        out.append((text[info.cluster], x + pos.x_offset * size / upem))
        x += pos.x_advance * size / upem + tracking_em * size
    return _fonts[k], out, x


def text_alpha(L, text, css_px, wght, tracking_em, baseline_css):
    """Coverage of a centred line of text, as an 'L' image the size of the screen."""
    fnt, glyphs, adv = shaped(L.scale, text, css_px, wght, tracking_em)
    if adv > L.screen[0] - 2 * 16 * L.scale:
        raise ValueError(f"{text!r} is wider than the screen ({adv:.0f} of {L.screen[0]} px); shorten the name")
    im = Image.new("L", L.screen, 0)
    d = ImageDraw.Draw(im)
    x0 = L.screen[0] / 2 - adv / 2
    for ch, x in glyphs:
        d.text((x0 + x, baseline_css * L.scale), ch, font=fnt, fill=255, anchor="ls")
    return np.asarray(im, np.float64) / 255


def baseline(scale, box_top_css, line_css, css_px):
    """Baseline of a single line in a CSS line box, from Inter's ascent and descent."""
    fnt, _, _ = shaped(scale, "H", css_px, 400, 0)
    asc, desc = (v / scale for v in fnt.getmetrics())
    return box_top_css + (line_css - (asc + desc)) / 2 + asc


# ---------------------------------------------------------------- the screen
class Scene:
    """Everything that doesn't depend on t, computed once per target (screen and device name).

    The background is flat BG. The reference page also has two corner glows, but they cost about
    24 KB in every frame: with them the zip is 19.9 MB and the boot image doesn't fit (README)."""

    def __init__(self, target):
        L = self.layout = Layout.for_screen(target.width, target.height)
        h, w = L.screen[::-1]
        self.base = np.empty((h, w, 3))
        self.base[:] = BG
        self.grain = np.clip(np.random.default_rng(4).normal(0.5, 0.11, (h, w)), 0, 1) * 0.09  # feTurbulence -> alpha
        self.word_css = baseline(L.scale, L.word_top, WORD_CSS * WORD_LINE, WORD_CSS)
        self.device = text_alpha(L, f"On {target.name}".upper(), DEVICE_CSS, 500, 0.14,
                                 baseline(L.scale, L.device_top, DEVICE_CSS, DEVICE_CSS))
        self._word = {}

    def word(self, dy_css):
        k = round(dy_css * self.layout.scale * 8) / 8   # eighth-pixel steps are plenty for a 12 px slide
        if k not in self._word:
            self._word[k] = text_alpha(self.layout, "Glassworks", WORD_CSS, 200, -0.03, self.word_css + k / self.layout.scale)
        return self._word[k]


def render(t, scene):
    st = motion(t)
    cv = Canvas(scene.base)
    paint_stack(cv, st, scene)
    for alpha, cov in ((st.word, scene.word(8 * (1 - st.word)) if st.word > 0 else None),
                       (st.device, scene.device if st.device > 0 else None)):
        if cov is not None:
            rgb = FG if cov is not scene.device else FA
            cv.px += (np.asarray(rgb, np.float64) - cv.px) * (cov * alpha)[..., None]
    return np.clip(cv.px + 0.5, 0, 255).astype(np.uint8)
