"""The Glass Tile mark, as the panel's boot animation drew it until #63. branding/social-preview.py
renders its resting loop frame (render(N_INTRO)).

Concept 2 - "Glass Tile".

A single glass card shaped like the panel (2:1, radius 26). It carries the
dashboard card's anatomy: a small letter-spaced label, a big thin "S1" like the
card's clock, "Unleashed" beside it, and a status dot.
Splash: the card at rest (glass fill .045, 1 px border .07), with "S1" in
muted grey.
Intro: light sweeps in diagonally across the glass. Sky and violet glow fill
the card behind a soft sheen, the border catches the light, "S1" brightens to
full white, and an amber STARTING status appears top-right.
Loop (2 s): the glows inside the card drift slowly and the amber dot breathes.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import numpy as np  # noqa: E402
from PIL import Image, ImageChops  # noqa: E402

from common import (AMBER, FA, MU, SKY, SS, STAGE, TX, VIOLET, BORDER_A, GLASS_A,  # noqa: E402
                    Mask, Stage, _XS, _YS, ambient, backdrop, cap_height, ease, font,
                    lerp, mix, seg, text_width, wave)

META = {
    "name": "Glass Tile",
    "rationale": "One glass card in the dashboard's anatomy: a small label, a big thin S1 like "
                 "the card's clock, and a status dot. On boot, light sweeps across the glass "
                 "and the card lights up, the same surface you then live with on the dashboard.",
}

N_INTRO, N_LOOP = 60, 60
# The card is the light source; the room glows stay low (they also cost ~40% of
# every frame's PNG size).
AMB = 0.4

X0, Y0, X1, Y1 = 170, 85, 630, 315          # 460 x 230, the panel's 2:1
RAD = 26
PAD = 30
F_BIG = font(132, wght=200)
F_LABEL = font(15, wght=500)
F_SUB = font(34, wght=300)
TR_LABEL = 0.14 * 15
TR_BIG = -0.02 * 132
CAP_L = cap_height(F_LABEL)
Y_LABEL = Y0 + PAD + CAP_L
Y_BIG = Y1 - PAD - 2
STATUS = "STARTING"
DOT_R = 4.2
W_STATUS = text_width(STATUS, F_LABEL, TR_LABEL)

_W, _H = X1 - X0, Y1 - Y0
_U = ((_XS - X0) / _W) * 0.78 + ((_YS - Y0) / _H) * 0.22     # diagonal sweep coordinate
TILE = Mask().rrect((X0, Y0, X1, Y1), RAD).im


def _L(arr):
    im = Image.fromarray(np.clip(arr * 255 + 0.5, 0, 255).astype(np.uint8), "L")
    return ImageChops.multiply(im.resize((STAGE[0] * SS, STAGE[1] * SS), Image.BILINEAR), TILE)


def _radial(cx, cy, rx, ry, p=1.5):
    d = np.sqrt(((_XS - cx) / rx) ** 2 + ((_YS - cy) / ry) ** 2)
    return np.clip(1 - d, 0, 1) ** p


def render(T):
    if T < N_INTRO:
        s = lerp(-0.25, 1.35, ease(seg(T, 4, 46)))             # sweep front position
        reveal = np.clip((s - _U) / 0.30, 0, 1)                 # lit behind the front
        reveal = reveal * reveal * (3 - 2 * reveal)
        sheen = np.exp(-((_U - s) / 0.07) ** 2) * (1 - seg(T, 40, 50))
        amb = AMB
        lit = ease(seg(T, 14, 44))
        border_a = BORDER_A + 0.10 * math.exp(-((T - 26) / 9.0) ** 2) + 0.035 * ease(seg(T, 20, 59))
        status = ease(seg(T, 38, 58))
        ph, breath = 0.0, 0.0
    else:
        i = (T - N_INTRO) % N_LOOP
        reveal, sheen = 1.0, None
        amb, lit, status = AMB, 1.0, 1.0
        border_a = BORDER_A + 0.035
        ph, breath = 2 * math.pi * i / N_LOOP, wave(i, N_LOOP)

    st = Stage(backdrop(ambient(amb, amb, 0.0)))
    # glass card
    st.glass((X0, Y0, X1, Y1), RAD, fill_a=GLASS_A, border_a=0)

    # light inside the card: sky from top-left, violet from bottom-right, both
    # drifting on small circles in the loop
    dx, dy = 16 * math.sin(ph), 8 * (1 - math.cos(ph))
    sky = _radial(X0 + 40 + dx, Y0 + 10 + dy, 330, 210) * 0.26 * (1 + 0.12 * breath)
    vio = _radial(X1 - 40 - dx, Y1 - 10 - dy, 300, 200) * 0.20 * (1 - 0.10 * breath)
    if not isinstance(reveal, float) or reveal < 1.0:
        sky, vio = sky * reveal, vio * reveal
    st.paint(SKY, _L(sky))
    st.paint(VIOLET, _L(vio))
    if sheen is not None and sheen.max() > 0.01:
        st.paint((255, 255, 255), _L(sheen * 0.075))
    st.paint((255, 255, 255), Mask().rrect((X0, Y0, X1, Y1), RAD, width=1.0), border_a)

    # content
    st.paint(FA, Mask().text((X0 + PAD, Y_LABEL), "OPEN WALL PANEL", F_LABEL, anchor="ls", tracking=TR_LABEL))
    st.paint(mix(MU, TX, lit), Mask().text((X0 + PAD - 6, Y_BIG), "S1", F_BIG, anchor="ls", tracking=TR_BIG))
    st.paint(mix(MU, TX, 0.55 * lit), Mask().text((X1 - PAD, Y_BIG), "Unleashed", F_SUB, anchor="rs",
                                                  tracking=-0.02 * 34))
    if status > 0:
        xs = X1 - PAD - W_STATUS
        st.paint(mix(FA, MU, 0.35), Mask().text((xs, Y_LABEL), STATUS, F_LABEL, anchor="ls",
                                               tracking=TR_LABEL), status)
        dot = Mask().circle((xs - 12 - DOT_R, Y_LABEL - CAP_L / 2), DOT_R)
        st.paint(AMBER, dot, status)
        st.glow(AMBER, dot, 7, status * (0.5 + 0.5 * breath))
    return st.finish()

