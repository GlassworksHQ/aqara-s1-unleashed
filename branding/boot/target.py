"""What the boot generator needs to know about a device, and the layout it derives from that.

No third-party imports, so the CI can test it without Pillow or numpy.

A target comes from a frame's design data (`frames/<id>/design/frame.json`):

    "name": "Aqara S1 Plus",
    "display": { "width": 1440, "height": 720 },
    "boot": { "androidApi": 29, "splash": "allwinner-boot-resource", "zipBudget": 9000000 }

`name` and `display` are all a new frame needs. `boot` is optional:

    androidApi   Android's API level. Frames are trimmed (trim.txt) only from 25 (Android 7.1), the
                 first version whose player reads it. Without it, frames are full screen, which
                 every version plays.
    splash       The bootloader splash format, or absent for none. Only "allwinner-boot-resource"
                 exists: the landscape picture rotated 90 degrees clockwise, as a 24-bit BMP of
                 exactly the stock file's size (the S1's BOOTLOGO.BMP).
    zipBudget    The largest bootanimation.zip the frame takes, in bytes, e.g. when it rides in the
                 boot image. Absent = no limit.
"""
import json
import os
from dataclasses import dataclass
from typing import Optional

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

TRIM_API = 25                       # Android 7.1: BootAnimation.cpp reads trim.txt from here on
SPLASHES = ("allwinner-boot-resource",)

# The design's reference page lays the screen out at 960x480 CSS px.
REF_VIEWPORT = (960, 480)
STACK_CSS = 150                     # the svg's size; its viewBox is 120 units
GAP_CSS = 22                        # between the stack and the wordmark
WORD_CSS, WORD_LINE = 46, 1.5       # the wordmark's font size and line height
DEVICE_CSS, DEVICE_BOTTOM = 11, 30  # the device line's font size and its distance from the bottom


@dataclass(frozen=True)
class Target:
    name: str
    width: int
    height: int
    android_api: Optional[int] = None
    splash: Optional[str] = None
    zip_budget: Optional[int] = None

    def __post_init__(self):
        if not self.name.strip():
            raise ValueError("the device name is empty")
        for v, what in ((self.width, "width"), (self.height, "height")):
            if not isinstance(v, int) or not 240 <= v <= 8192:
                raise ValueError(f"display {what} must be a whole number of pixels from 240 to 8192, not {v!r}")
        if self.splash is not None and self.splash not in SPLASHES:
            raise ValueError(f"unknown splash format {self.splash!r} (known: {', '.join(SPLASHES)})")
        if self.splash == "allwinner-boot-resource" and self.height % 4:
            # rotated, the height is the BMP's row width; the in-place swap has no room for padding
            raise ValueError(f"an allwinner-boot-resource splash needs a height that is a multiple of 4, not {self.height}")
        if self.android_api is not None and (not isinstance(self.android_api, int) or self.android_api < 21):
            raise ValueError(f"androidApi must be an API level of 21 or more, not {self.android_api!r}")
        if self.zip_budget is not None and (not isinstance(self.zip_budget, int) or self.zip_budget <= 0):
            raise ValueError(f"zipBudget must be a positive number of bytes, not {self.zip_budget!r}")

    @property
    def screen(self):
        return (self.width, self.height)

    @property
    def trim(self):
        return self.android_api is not None and self.android_api >= TRIM_API


def from_frame(frame_id, repo=REPO):
    """The target in frames/<id>/design/frame.json."""
    path = os.path.join(repo, "frames", frame_id, "design", "frame.json")
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    disp = d.get("display")
    if not disp:
        raise ValueError(f"{os.path.relpath(path, repo)} has no \"display\": {{ \"width\", \"height\" }} in pixels")
    boot = d.get("boot") or {}
    unknown = set(boot) - {"androidApi", "splash", "zipBudget"}
    if unknown:
        raise ValueError(f"{os.path.relpath(path, repo)}: unknown boot field(s) {', '.join(sorted(unknown))}")
    return Target(d["name"], disp.get("width"), disp.get("height"),
                  boot.get("androidApi"), boot.get("splash"), boot.get("zipBudget"))


@dataclass(frozen=True)
class Layout:
    """Where the reference page's elements land on this screen.

    The page is a fixed design in CSS px: the stack and wordmark centred, the device line near the
    bottom. One CSS px is `scale` screen px, chosen so the reference's 960x480 viewport fits the
    screen: the S1 (1440x720) gets 1.5, as before; a portrait 1080x1920 gets 1.125, which keeps
    the stack the same share of the screen's width and gives it more room above and below."""
    screen: tuple
    scale: float

    @classmethod
    def for_screen(cls, w, h):
        return cls((w, h), min(w / REF_VIEWPORT[0], h / REF_VIEWPORT[1]))

    @property
    def viewport(self):
        """The screen in CSS px."""
        return (self.screen[0] / self.scale, self.screen[1] / self.scale)

    @property
    def unit(self):
        """Screen px per unit of the stack's 120-unit viewBox."""
        return STACK_CSS / 120 * self.scale

    @property
    def lock_top(self):
        """CSS top of .lock: a 150 px svg, a 22 px gap and the wordmark's line box, centred."""
        return (self.viewport[1] - (STACK_CSS + GAP_CSS + WORD_CSS * WORD_LINE)) / 2

    @property
    def origin(self):
        """Screen px of the stack's viewBox origin."""
        return (self.screen[0] / 2 - STACK_CSS / 2 * self.scale, self.lock_top * self.scale)

    @property
    def word_top(self):
        return self.lock_top + STACK_CSS + GAP_CSS

    @property
    def device_top(self):
        """CSS top of the device line's box."""
        return self.viewport[1] - DEVICE_BOTTOM - DEVICE_CSS
