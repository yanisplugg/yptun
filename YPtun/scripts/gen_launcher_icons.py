"""Generates the alternative launcher icons into <res>/mipmap-nodpi (+ adaptive XML into mipmap-anydpi-v26).

Usage: python -I gen_launcher_icons.py <res dir> [icon id ...]

  * Cat icons: the SAME artwork as the default icon (mipmap-xxxhdpi/ic_launcher_foreground.png: the kitten
    sitting on the "yptun" wordmark), re-coloured with a 4-stop gradient map over its luminance.
  * Wordmark icons: "yptun" on a colour background (no cat), each in its own display font from scripts/fonts
    (Google Fonts, SIL OFL: Pacifico, Audiowide, Righteous, Abril Fatface).

Per icon: ic_icon_<id>_bg.png / ic_icon_<id>_fg.png (432 px adaptive layers), ic_launcher_<id>.png (192 px
legacy rounded square) and mipmap-anydpi-v26/ic_launcher_<id>.xml. Keep the ids in sync with AppIcons.kt
and the activity-aliases in AndroidManifest.xml.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = sys.argv[1]
OUT = os.path.join(RES, 'mipmap-nodpi')
V26 = os.path.join(RES, 'mipmap-anydpi-v26')
os.makedirs(OUT, exist_ok=True)
os.makedirs(V26, exist_ok=True)
N = 432
SS = 3
W = N * SS
SOURCE = os.path.join(RES, 'mipmap-xxxhdpi', 'ic_launcher_foreground.png')
FONTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'fonts')


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


# ------------------------------------------------------------------ cat icons: one artwork, many colourings
CAT_PALETTES = {  # shadow, low-mid, high-mid, highlight
    'ocean': ('#030b1f', '#0a3d8f', '#5ec1ff', '#f0fbff'),
    'sunset': ('#1a0620', '#8a1c4a', '#ff8a4c', '#fff4dc'),
    'mint': ('#021410', '#0a5c45', '#4be0a8', '#f0fff8'),
    'violet': ('#0c0220', '#4a1a9a', '#b07cff', '#fbf3ff'),
    'gold': ('#0a0602', '#5a3a06', '#e0a526', '#fff6d6'),
}


def cat_icon(name):
    src = Image.open(SOURCE).convert('RGBA')
    base = Image.new('RGBA', src.size, (0, 0, 0, 255))
    base.alpha_composite(src)
    a = np.asarray(base.convert('RGB')).astype(np.float32)
    lum = np.clip((a @ np.array([.3, .59, .11], dtype=np.float32)) / 255.0, 0, 1) ** 0.85
    stops = [np.array(hexc(c)[:3], dtype=np.float32) for c in CAT_PALETTES[name]]
    xs = [0.0, 0.28, 0.62, 1.0]
    out = np.zeros(lum.shape + (3,), dtype=np.float32)
    for ch in range(3):
        out[..., ch] = np.interp(lum, xs, [s[ch] for s in stops])
    fg = Image.fromarray(out.astype(np.uint8), 'RGB').convert('RGBA')
    bg = Image.new('RGBA', (N, N), hexc(CAT_PALETTES[name][0]))
    return bg, fg


# ------------------------------------------------------------------ wordmark icons (no cat)
def gradient(c1, c2):
    c1, c2 = np.array(hexc(c1), dtype=np.float32), np.array(hexc(c2), dtype=np.float32)
    t = (np.add.outer(np.arange(N), np.arange(N)) / (2.0 * N))[..., None]
    return Image.fromarray((c1 + (c2 - c1) * t).astype(np.uint8), 'RGBA')


def wordmark(bg, font_file, text_color, shadow=None, glow=None, accent=None):
    path = os.path.join(FONTS, font_file)
    f_size = 150 * SS
    font = ImageFont.truetype(path, f_size)
    probe = ImageDraw.Draw(Image.new('RGBA', (W, W)))
    while probe.textlength('yptun', font=font) > 262 * SS:
        f_size -= 4 * SS
        font = ImageFont.truetype(path, f_size)
    box = probe.textbbox((0, 0), 'yptun', font=font)
    tx = (W - (box[2] - box[0])) // 2 - box[0]
    ty = (W - (box[3] - box[1])) // 2 - box[1]
    layer = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    if shadow:
        sh = Image.new('RGBA', (W, W), (0, 0, 0, 0))
        ImageDraw.Draw(sh).text((tx, ty + 8 * SS), 'yptun', font=font, fill=shadow)
        layer = Image.alpha_composite(layer, sh.filter(ImageFilter.GaussianBlur(6 * SS)))
    if glow:
        g = Image.new('RGBA', (W, W), (0, 0, 0, 0))
        ImageDraw.Draw(g).text((tx, ty), 'yptun', font=font, fill=glow)
        blur = g.filter(ImageFilter.GaussianBlur(9 * SS))
        layer = Image.alpha_composite(Image.alpha_composite(layer, blur), blur)
    if accent:
        a = Image.new('RGBA', (W, W), (0, 0, 0, 0))
        ImageDraw.Draw(a).text((tx + 5 * SS, ty + 5 * SS), 'yptun', font=font, fill=accent)
        layer = Image.alpha_composite(layer, a)
    ImageDraw.Draw(layer).text((tx, ty), 'yptun', font=font, fill=text_color)
    return bg, layer.resize((N, N), Image.LANCZOS)


WORD_ICONS = {
    'wsky': lambda: wordmark(gradient('#4aa8ff', '#1b3fae'), 'Pacifico-Regular.ttf', hexc('#ffffff'), shadow=(0, 20, 80, 150)),
    'wneon': lambda: wordmark(gradient('#070716', '#2a0b4a'), 'Audiowide-Regular.ttf', hexc('#eaffff'), glow=hexc('#27f0ff'), accent=hexc('#ff3df2', 200)),
    'wsun': lambda: wordmark(gradient('#ffd23f', '#ff6a13'), 'Righteous-Regular.ttf', hexc('#2b1200'), shadow=(120, 40, 0, 90)),
    'wlight': lambda: wordmark(gradient('#fbfcff', '#d9deec'), 'AbrilFatface-Regular.ttf', hexc('#14233f'), shadow=(40, 60, 120, 60)),
}

ICONS = {**{n: (lambda n=n: cat_icon(n)) for n in CAT_PALETTES}, **WORD_ICONS}

for name in (sys.argv[2:] or list(ICONS)):
    bg, fg = ICONS[name]()
    bg = bg.resize((N, N), Image.LANCZOS)
    bg.convert('RGB').save(f'{OUT}/ic_icon_{name}_bg.png', optimize=True)
    fg.save(f'{OUT}/ic_icon_{name}_fg.png', optimize=True)
    comp = bg.convert('RGBA')
    comp.alpha_composite(fg)
    c = comp.crop((72, 72, 360, 360)).resize((384, 384), Image.LANCZOS)  # the part the launcher mask shows
    m = Image.new('L', c.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, c.width - 1, c.height - 1], radius=int(c.width * .22), fill=255)
    c.putalpha(m)
    c.resize((192, 192), Image.LANCZOS).save(f'{OUT}/ic_launcher_{name}.png', optimize=True)
    with open(f'{V26}/ic_launcher_{name}.xml', 'w', encoding='utf-8', newline='\n') as xml:
        xml.write('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                  f'    <background android:drawable="@mipmap/ic_icon_{name}_bg" />\n'
                  f'    <foreground android:drawable="@mipmap/ic_icon_{name}_fg" />\n</adaptive-icon>\n')
    print('ok', name)
