"""Drawing helpers for the Glass Tile mark in branding/social-preview.py.

This was branding/glass-tile/common.py until the boot branding became the Glassworks stack
(#63); the BMP and bootanimation writers stayed there.

Visual language: the kiosk's onboarding screen (app/kiosk/.../setup/SetupOverlay.kt)
and dashboard card. That means a #0a0b0f backdrop with soft sky and violet glows,
light display type with tight tracking, small uppercase letter-spaced labels, glass
surfaces, and amber as the "current step" accent.

Geometry facts (measured from the stock files):
  * Bootloader splash: 720x1440 24-bit BMP, bottom-up, 54-byte header. The
    panel is landscape; the stored image is the landscape picture rotated 90
    degrees CLOCKWISE (PIL: landscape.transpose(ROTATE_270)). Rotating the
    stock BMP counter-clockwise makes the stock wordmark read correctly.
  * Android bootanimation: 800x400 frames are drawn 1:1 (unscaled), centred on
    the 1440x720 screen at offset (320,160). The rest of the screen is cleared
    to the part's background colour (desc.txt "#rrggbb", AOSP since Android 7;
    the default is black). The stock logo sits at exactly the same screen
    pixels in both stock files.

So every concept is drawn on an 800x400 "stage". Its glows are feathered to
flat BG at the stage edges. The splash is the first animation frame pasted
at (320,160) on a flat BG 1440x720 canvas. That makes the splash -> animation
handoff pixel-identical, with no visible window edge.
"""
import math
import os
import subprocess

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SCREEN = (1440, 720)          # landscape, as the user sees it
STAGE = (800, 400)            # bootanimation frame size
OFF = ((SCREEN[0] - STAGE[0]) // 2, (SCREEN[1] - STAGE[1]) // 2)  # (320,160)
FPS = 30
SS = 3                        # supersampling factor for antialiasing



def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# ---------------------------------------------------------------- tokens
# (SetupOverlay.kt / tablet-home-card.compact.js)
BG = hexc("#0a0b0f")          # if the panel's bootanimation ignored the desc.txt
#                               colour, set this to (0,0,0) and re-render
TX = hexc("#f4f4f5")
MU = hexc("#a1a1aa")
FA = hexc("#63636b")
AMBER = hexc("#fbbf24")
GREEN = hexc("#34d399")
ROSE = hexc("#fb7185")
SKY = (56, 189, 248)          # glow, alpha ~0.13
VIOLET = (167, 139, 250)      # glow, alpha ~0.14
GLASS_A, BORDER_A = 0.045, 0.07

# ---------------------------------------------------------------- fonts (OFL)
FONT_CACHE = "/tmp/s1-unleashed-fonts"
GF_RAW = "https://github.com/google/fonts/raw/main/"
FONTS = {"inter": "ofl/inter/Inter%5Bopsz,wght%5D.ttf"}
_font_objs = {}


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


def font(size, wght=300, key="inter"):
    """Inter at `size` stage px (= screen px). The optical size follows the size."""
    k = (key, size, wght)
    if k not in _font_objs:
        f = ImageFont.truetype(font_file(key), round(size * SS))
        vals = []
        for ax in f.get_variation_axes():
            name = ax["name"].decode() if isinstance(ax["name"], bytes) else ax["name"]
            if name == "Weight":
                vals.append(wght)
            elif name == "Optical size":
                vals.append(max(ax["minimum"], min(ax["maximum"], size)))
            else:
                vals.append(ax["default"])
        f.set_variation_by_axes(vals)
        _font_objs[k] = f
    return _font_objs[k]


# ---------------------------------------------------------------- easing
def clamp(x, a=0.0, b=1.0):
    return a if x < a else b if x > b else x


def seg(t, a, b):
    """Normalised progress of t through [a,b], clamped to 0..1."""
    return clamp((t - a) / float(b - a))


def ease(x):
    """Ease-in-out (sine): calm, no overshoot."""
    x = clamp(x)
    return 0.5 - 0.5 * math.cos(math.pi * x)


def ease3(x):
    """Ease-in-out (cubic)."""
    x = clamp(x)
    return 4 * x ** 3 if x < 0.5 else 1 - (-2 * x + 2) ** 3 / 2


def smoothstep(a, b, x):
    x = clamp((x - a) / (b - a))
    return x * x * (3 - 2 * x)


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c1, c2, t):
    return tuple(round(lerp(a, b, t)) for a, b in zip(c1, c2))


def wave(i, n, phase=0.0):
    """Seamless 0..1..0 oscillation over a loop of n frames (0 at i=0)."""
    return 0.5 - 0.5 * math.cos(2 * math.pi * (i / n + phase))


# ---------------------------------------------------------------- backdrop
_YS, _XS = np.mgrid[0:STAGE[1], 0:STAGE[0]].astype(np.float32)


def _ss(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


# the stage edges fade to flat BG, so the 800x400 window never shows
FEATHER = (_ss(0, 150, _XS) * _ss(0, 150, STAGE[0] - 1 - _XS)
           * _ss(0, 120, _YS) * _ss(0, 120, STAGE[1] - 1 - _YS))


def backdrop(glows):
    """BG plus soft radial glows. Each glow is (cx, cy, rx, ry, rgb, alpha).
    The glows are feathered to BG at the stage border. Returns a 1x RGB image."""
    acc = np.zeros((STAGE[1], STAGE[0], 3), np.float32)
    for cx, cy, rx, ry, rgb, alpha in glows:
        d = np.sqrt(((_XS - cx) / rx) ** 2 + ((_YS - cy) / ry) ** 2)
        g = np.clip(1 - d, 0, 1) ** 1.7
        acc += g[..., None] * (np.array(rgb, np.float32) - np.array(BG, np.float32)) * alpha
    img = np.array(BG, np.float32) + acc * FEATHER[..., None]
    return Image.fromarray(np.clip(img + 0.5, 0, 255).astype(np.uint8), "RGB")


def ambient(k_sky=1.0, k_violet=1.0, drift=0.0):
    """The dashboard backdrop's two glows, folded into the stage: sky from the
    top-left and violet from the bottom-right. `drift` (-1..1) slides them gently."""
    return [(215 + 40 * drift, 105 + 14 * drift, 470, 300, SKY, 0.16 * k_sky),
            (590 - 40 * drift, 300 - 14 * drift, 430, 275, VIOLET, 0.17 * k_violet)]


def grad_mask(cx, cy, rx, ry, power=1.6, scale=1.0):
    """Radial 0..255 'L' mask at SS resolution (computed at 1x, then upsampled)."""
    d = np.sqrt(((_XS - cx) / rx) ** 2 + ((_YS - cy) / ry) ** 2)
    g = np.clip(1 - d, 0, 1) ** power * scale
    im = Image.fromarray(np.clip(g * 255 + 0.5, 0, 255).astype(np.uint8), "L")
    return im.resize((STAGE[0] * SS, STAGE[1] * SS), Image.BILINEAR)


# ---------------------------------------------------------------- drawing
class Mask:
    """An 'L' coverage mask at SS resolution, addressed in stage pixels."""

    def __init__(self):
        self.im = Image.new("L", (STAGE[0] * SS, STAGE[1] * SS), 0)
        self.d = ImageDraw.Draw(self.im)

    @staticmethod
    def _p(pts):
        return [(x * SS, y * SS) for x, y in pts]

    def line(self, pts, width, round_caps=True, value=255):
        self.d.line(self._p(pts), fill=value, width=max(1, round(width * SS)), joint="curve")
        if round_caps:
            for x, y in (pts[0], pts[-1]):
                self.circle((x, y), width / 2, value=value)
        return self

    def circle(self, c, r, width=None, value=255):
        x, y = c
        box = [(x - r) * SS, (y - r) * SS, (x + r) * SS, (y + r) * SS]
        if width is None:
            self.d.ellipse(box, fill=value)
        else:
            self.d.ellipse(box, outline=value, width=max(1, round(width * SS)))
        return self

    def arc(self, c, r, start, end, width, round_caps=True, value=255):
        """Arc centred on the stroke radius r; angles in degrees, 0 = 3 o'clock,
        clockwise (Pillow convention)."""
        x, y = c
        ro = r + width / 2
        box = [(x - ro) * SS, (y - ro) * SS, (x + ro) * SS, (y + ro) * SS]
        if end - start >= 360:
            self.d.ellipse(box, outline=value, width=max(1, round(width * SS)))
            return self
        self.d.arc(box, start, end, fill=value, width=max(1, round(width * SS)))
        if round_caps:
            for a in (start, end):
                self.circle((x + r * math.cos(math.radians(a)), y + r * math.sin(math.radians(a))),
                            width / 2, value=value)
        return self

    def rrect(self, box, radius, width=None, value=255):
        b = [v * SS for v in box]
        r = radius * SS
        if width is None:
            self.d.rounded_rectangle(b, r, fill=value)
        else:
            self.d.rounded_rectangle(b, r, outline=value, width=max(1, round(width * SS)))
        return self

    def text(self, xy, s, fnt, anchor="ls", tracking=0.0, value=255):
        """Draw text. With tracking (stage px; may be negative), the letters are
        placed one by one. The x anchor l|m|r applies to the whole run."""
        x, y = xy
        if not tracking:
            self.d.text((x * SS, y * SS), s, font=fnt, fill=value, anchor=anchor)
            return self
        total = text_width(s, fnt, tracking)
        if anchor[0] == "m":
            x -= total / 2
        elif anchor[0] == "r":
            x -= total
        for ch, cx in zip(s, letter_positions(s, fnt, x, tracking)):
            self.d.text((cx * SS, y * SS), ch, font=fnt, fill=value, anchor="l" + anchor[1])
        return self


def letter_positions(s, fnt, x, tracking):
    """x of each letter. Uses kerned pair advances, so tight tracking keeps the
    font's kerning."""
    out = []
    for i, ch in enumerate(s):
        out.append(x)
        if i + 1 < len(s):
            adv = (fnt.getlength(s[i:i + 2]) - fnt.getlength(s[i + 1])) / SS
            if ch == " ":  # keep word spaces open when tracking is tight
                adv += fnt.size / SS * 0.06
            x += adv + tracking
    return out


def text_width(s, fnt, tracking=0.0):
    if not s:
        return 0.0
    if not tracking:
        return fnt.getlength(s) / SS
    xs = letter_positions(s, fnt, 0.0, tracking)
    return xs[-1] + fnt.getlength(s[-1]) / SS


def cap_height(fnt):
    return -fnt.getbbox("H", anchor="ls")[1] / SS


class Stage:
    """RGB working image at SS resolution over a (1x) backdrop."""

    def __init__(self, bg_img=None):
        if bg_img is None:
            self.im = Image.new("RGB", (STAGE[0] * SS, STAGE[1] * SS), BG)
        else:
            self.im = bg_img.resize((STAGE[0] * SS, STAGE[1] * SS), Image.BILINEAR)
        self.glows = []

    def paint(self, color, mask, opacity=1.0):
        if opacity <= 0.002:
            return
        m = mask.im if isinstance(mask, Mask) else mask
        if opacity < 0.998:
            m = m.point([round(v * opacity) for v in range(256)])
        self.im.paste(color, (0, 0) + self.im.size, m)

    def glass(self, box, radius, fill_a=GLASS_A, border_a=BORDER_A, border_w=1.0):
        """Glass card: translucent white fill plus a 1px translucent white border."""
        self.paint((255, 255, 255), Mask().rrect(box, radius), fill_a)
        self.paint((255, 255, 255), Mask().rrect(box, radius, width=border_w), border_a)

    def glow(self, color, mask, radius, strength):
        """Queue an additive bloom (applied at 1x after downsampling)."""
        if strength > 0.002:
            self.glows.append((color, mask, radius, strength))

    def finish(self):
        out = self.im.reduce(SS)
        if not self.glows:
            return out
        acc = np.asarray(out, dtype=np.float32)
        for color, mask, radius, strength in self.glows:
            m = (mask.im if isinstance(mask, Mask) else mask).reduce(SS)
            b = np.asarray(m.filter(ImageFilter.GaussianBlur(radius)), dtype=np.float32) / 255.0
            acc += b[..., None] * np.array(color, dtype=np.float32)[None, None, :] * strength
        return Image.fromarray(np.clip(acc + 0.5, 0, 255).astype(np.uint8), "RGB")
