"""Regenerates the installer's wizard images (art/*.bmp) from appIcons/LinuxIcon.png.

Inno Setup shows WizardImageFile in the left panel of the welcome/finish pages and
WizardSmallImageFile in the top-right corner of the inner pages; with `WizardStyle=modern dynamic`
it picks the *DynamicDark* variants when Windows is in dark mode. Several sizes per image are given
so the art stays sharp at 100/125/150/200 % display scaling.

    python art/gen_art.py
"""
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
ICON = Image.open(HERE.parent.parent.parent / "appIcons" / "LinuxIcon.png").convert("RGBA")

# name -> (top colour, bottom colour); the wizard's own page background must match the image edge.
THEMES = {
    "light": ((0xF6, 0xF8, 0xFC), (0xE3, 0xE8, 0xF2)),
    "dark": ((0x1C, 0x1F, 0x27), (0x0E, 0x10, 0x14)),
}
LARGE = {100: (164, 314), 125: (192, 386), 150: (246, 459), 200: (328, 604)}
SMALL = {100: (55, 58), 125: (64, 68), 150: (83, 80), 200: (110, 106)}


def gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        t = y / max(h - 1, 1)
        row = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = row
    return img


def with_icon(size, colors, icon_fraction, y_fraction):
    img = gradient(size, *colors).convert("RGBA")
    side = round(min(size) * icon_fraction)
    icon = ICON.resize((side, side), Image.LANCZOS)
    img.alpha_composite(icon, ((size[0] - side) // 2, round(size[1] * y_fraction) - side // 2))
    return img.convert("RGB")


for theme, colors in THEMES.items():
    for scale, size in LARGE.items():
        with_icon(size, colors, 0.62, 0.30).save(HERE / f"wizard-{theme}-{scale}.bmp")
    for scale, size in SMALL.items():
        with_icon(size, colors, 0.86, 0.50).save(HERE / f"small-{theme}-{scale}.bmp")
print("ok", sorted(p.name for p in HERE.glob("*.bmp")))
