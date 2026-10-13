"""Generates the alternative launcher icons: <id>_bg.png / <id>_fg.png (432, adaptive layers) and
<id>.png (192, legacy rounded square) into RES/mipmap-nodpi, plus mipmap-anydpi-v26/ic_launcher_<id>.xml."""
import math, random, sys, os
from PIL import Image, ImageDraw, ImageFilter, ImageChops, ImageFont

RES = sys.argv[1]
OUT = os.path.join(RES, 'mipmap-nodpi')
V26 = os.path.join(RES, 'mipmap-anydpi-v26')
os.makedirs(OUT, exist_ok=True); os.makedirs(V26, exist_ok=True)
N = 432
SS = 3
W = N * SS  # supersampled canvas


def hexc(h, a=255):
    h = h.lstrip('#'); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def gradient(c1, c2, vertical=True, diag=False):
    c1, c2 = hexc(c1), hexc(c2)
    g = Image.new('RGBA', (W, W))
    px = g.load()
    for y in range(W):
        for x in range(W):
            if diag: t = (x + y) / (2 * W)
            else: t = (y if vertical else x) / W
            px[x, y] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(4))
    return g


def radial(c_in, c_out, cx=.5, cy=.5, rad=.75):
    a, b = hexc(c_in), hexc(c_out)
    small = 216
    g = Image.new('RGBA', (small, small)); px = g.load()
    for y in range(small):
        for x in range(small):
            d = min(1, math.hypot(x / small - cx, y / small - cy) / rad)
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * d) for i in range(4))
    return g.resize((W, W), Image.BILINEAR)


def blank(): return Image.new('RGBA', (W, W), (0, 0, 0, 0))


class Box:
    """Maps a 0..100 unit square onto a pixel square of `size` (in 432 space) centred at (cx, cy)."""
    def __init__(self, cx, cy, size): self.s = size * SS / 100; self.x0 = (cx - size / 2) * SS; self.y0 = (cy - size / 2) * SS
    def p(self, x, y): return (self.x0 + x * self.s, self.y0 + y * self.s)
    def pts(self, ps): return [self.p(x, y) for x, y in ps]
    def ell(self, cx, cy, rx, ry): return [*self.p(cx - rx, cy - ry), *self.p(cx + rx, cy + ry)]
    def w(self, v): return max(1, int(v * self.s))


def arc_pts(cx, cy, rx, ry, a0, a1, n=40):
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def line(d, b, ps, col, width):
    d.line(b.pts(ps), fill=col, width=b.w(width), joint='curve')
    r = b.w(width) / 2
    for x, y in b.pts([ps[0], ps[-1]]): d.ellipse([x - r, y - r, x + r, y + r], fill=col)


# ------------------------------------------------------------------ cat shapes (unit space)
def head_shape(d, b, fill):
    d.ellipse(b.ell(50, 57, 38, 32), fill=fill)
    d.polygon(b.pts([(13, 44), (17, 8), (45, 27)]), fill=fill)
    d.polygon(b.pts([(87, 44), (83, 8), (55, 27)]), fill=fill)


def face_features(d, b, eye=hexc('#2b2118'), nose=hexc('#ff8fa3'), whisk=hexc('#6b5646'), blush=True, glints=True, ey=55):
    if blush:
        for cx in (24, 76): d.ellipse(b.ell(cx, 66, 7, 4.5), fill=hexc('#ff9bb0', 150))
    for cx in (35, 65):
        d.ellipse(b.ell(cx, ey, 5.2, 7.2), fill=eye)
        if glints: d.ellipse(b.ell(cx + 1.8, ey - 2.6, 1.9, 2.2), fill=(255, 255, 255, 255))
    d.polygon(b.pts([(46, 64), (54, 64), (50, 68.5)]), fill=nose)
    line(d, b, arc_pts(45, 68, 5, 4, 0, 160, 12), whisk, 1.4)
    line(d, b, arc_pts(55, 68, 5, 4, 20, 180, 12), whisk, 1.4)
    for sgn in (-1, 1):
        for dy, ang in ((0, -6), (4, 4), (8, 14)):
            x0 = 50 + sgn * 24; x1 = 50 + sgn * 46
            line(d, b, [(x0, 66 + dy * .5), (x1, 66 + dy + ang * .6)], whisk, 1.1)


def sil_cat(d, b, fill, eyes=hexc('#ffd84a')):
    d.ellipse(b.ell(50, 71, 22, 26), fill=fill)
    d.ellipse(b.ell(50, 93, 25, 6), fill=fill)
    d.ellipse(b.ell(50, 38, 16, 14), fill=fill)
    d.polygon(b.pts([(35, 32), (36, 12), (48, 24)]), fill=fill)
    d.polygon(b.pts([(65, 32), (64, 12), (52, 24)]), fill=fill)
    line(d, b, [(70, 92), (82, 90), (89, 80), (88, 68), (83, 60)], fill, 7)
    if eyes:
        for cx in (44, 56): d.ellipse(b.ell(cx, 38, 2.6, 3.4), fill=eyes)


def glow(layer, radius, times=2):
    g = layer.filter(ImageFilter.GaussianBlur(radius * SS))
    out = layer.copy()
    for _ in range(times): out = Image.alpha_composite(out, g)
    return out


def outline_of(mask, px):
    k = int(px * SS) | 1
    return ImageChops.subtract(mask, mask.filter(ImageFilter.MinFilter(k)))


# ------------------------------------------------------------------ icons
def icon_neon():
    bg = gradient('#0a0a2e', '#35104f', diag=True)
    fg = blank(); b = Box(216, 226, 270)
    m = Image.new('L', (W, W), 0); head_shape(ImageDraw.Draw(m), b, 255)
    ol = outline_of(m, 3.4 * b.s / SS * 1.0)
    cy = Image.new('RGBA', (W, W), hexc('#27f0ff')); cy.putalpha(ol)
    d = ImageDraw.Draw(fg)
    fg = glow(cy, 7)
    d = ImageDraw.Draw(fg)
    mag = blank(); dm = ImageDraw.Draw(mag)
    for cx in (35, 65): dm.ellipse(b.ell(cx, 55, 5.5, 7.5), fill=hexc('#ff3df2'))
    line(dm, b, arc_pts(45, 68, 5, 4, 0, 160, 12), hexc('#ff3df2'), 1.8); line(dm, b, arc_pts(55, 68, 5, 4, 20, 180, 12), hexc('#ff3df2'), 1.8)
    dm.polygon(b.pts([(46, 64), (54, 64), (50, 68.5)]), fill=hexc('#ff3df2'))
    for sgn in (-1, 1):
        for dy, ang in ((0, -6), (4, 4), (8, 14)):
            line(dm, b, [(50 + sgn * 24, 66 + dy * .5), (50 + sgn * 46, 66 + dy + ang * .6)], hexc('#27f0ff'), 1.4)
    fg = Image.alpha_composite(fg, glow(mag, 4))
    return bg, fg


def icon_sunset():
    bg = gradient('#ffb347', '#6a1b9a', vertical=True)
    d0 = ImageDraw.Draw(bg)
    r = 120 * SS; cx, cy = 216 * SS, 215 * SS
    sun = blank(); ImageDraw.Draw(sun).ellipse([cx - r, cy - r, cx + r, cy + r], fill=hexc('#fff3b0'))
    sun = Image.alpha_composite(sun.filter(ImageFilter.GaussianBlur(14 * SS)), sun)
    bg = Image.alpha_composite(bg, sun)
    fg = blank(); d = ImageDraw.Draw(fg)
    sil_cat(d, Box(216, 222, 250), hexc('#1a0a24'))
    return bg, fg


def icon_kawaii():
    bg = gradient('#c8f5dc', '#7fd3b1', diag=True)
    fg = blank(); d = ImageDraw.Draw(fg); b = Box(216, 226, 276)
    head_shape(d, b, hexc('#fff6e9'))
    d.polygon(b.pts([(21, 40), (22, 18), (38, 29)]), fill=hexc('#ffb3c1'))
    d.polygon(b.pts([(79, 40), (78, 18), (62, 29)]), fill=hexc('#ffb3c1'))
    # orange patch + forehead stripes
    d.chord(b.ell(50, 57, 38, 32), 180, 300, fill=hexc('#ffa94d'))
    d.polygon(b.pts([(13, 44), (17, 8), (30, 18), (24, 34)]), fill=hexc('#ffa94d'))
    d.polygon(b.pts([(21, 40), (22, 18), (28, 22)]), fill=hexc('#ffb3c1'))
    d.rounded_rectangle([*b.p(47, 26), *b.p(53, 37)], radius=b.w(2), fill=hexc('#e58a2b'))
    face_features(d, b)
    sh = Image.new('RGBA', (W, W), (0, 0, 0, 0)); fg2 = fg
    shadow = Image.new('RGBA', (W, W), (0, 70, 50, 90)); shadow.putalpha(fg.getchannel('A').point(lambda v: v * 90 // 255))
    shadow = shadow.filter(ImageFilter.GaussianBlur(6 * SS)).transform((W, W), Image.AFFINE, (1, 0, 0, 0, 1, -8 * SS))
    return bg, Image.alpha_composite(shadow, fg)


PIXEL = [
    "................",
    "..##........##..",
    ".#oo#......#oo#.",
    ".#ooo######ooo#.",
    ".#oooooooooooo#.",
    "#oooooooooooooo#",
    "#ooeeooooooeeoo#",
    "#ooeeooooooeeoo#",
    "#oooooopp ooooo#".replace(' ', 'o'),
    "#oowwwwwwwwwwoo#",
    "#ooowwwwwwwwooo#",
    ".#oooooooooooo#.",
    "..############..",
]


def icon_pixel():
    bg = gradient('#123524', '#06120b')
    d0 = ImageDraw.Draw(bg)
    for y in range(0, N, 6): d0.line([(0, y * SS), (W, y * SS)], fill=(0, 0, 0, 40), width=SS)
    cols = {'#': hexc('#1b1b2f'), 'o': hexc('#ff9f1c'), 'w': hexc('#fff1d6'), 'e': hexc('#2ec4b6'), 'p': hexc('#ff6b8a')}
    px = Image.new('RGBA', (16, 16), (0, 0, 0, 0)); pl = px.load()
    for y, row in enumerate(PIXEL):
        assert len(row) == 16, (y, len(row))
        for x, ch in enumerate(row):
            if ch in cols: pl[x, y] = cols[ch]
    px = px.crop((0, 0, 16, 13)).resize((16 * 17 * SS, 13 * 17 * SS), Image.NEAREST)
    fg = blank(); fg.paste(px, ((W - px.width) // 2, (W - px.height) // 2 + 4 * SS), px)
    return bg, fg


def icon_space():
    bg = radial('#2b1b6e', '#07051a', .5, .45, .8)
    rnd = random.Random(7); d0 = ImageDraw.Draw(bg)
    for _ in range(70):
        x, y = rnd.randrange(W), rnd.randrange(W); r = rnd.choice([1, 1, 2, 3]) * SS
        d0.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, rnd.choice([120, 180, 255])))
    fg = blank(); d = ImageDraw.Draw(fg)
    cx, cy, R = 216 * SS, 216 * SS, 132 * SS
    d.ellipse([cx - R, cy - R, cx + R, cy + R], fill=hexc('#0b1233', 235))
    glass = radial('#274a9a', '#0b1233', .35, .3, .9)
    gm = Image.new('L', (W, W), 0); ImageDraw.Draw(gm).ellipse([cx - R, cy - R, cx + R, cy + R], fill=255)
    fg.paste(glass, (0, 0), gm)
    b = Box(216, 226, 190); d = ImageDraw.Draw(fg)
    head_shape(d, b, hexc('#b8bcc6'))
    d.polygon(b.pts([(21, 40), (22, 18), (38, 29)]), fill=hexc('#ffb3c1')); d.polygon(b.pts([(79, 40), (78, 18), (62, 29)]), fill=hexc('#ffb3c1'))
    for dx in (-10, 0, 10): d.polygon(b.pts([(50 + dx - 2.5, 26), (50 + dx + 2.5, 26), (50 + dx, 40)]), fill=hexc('#7d8391'))
    face_features(d, b, blush=False)
    # helmet rim + shine
    d.ellipse([cx - R, cy - R, cx + R, cy + R], outline=hexc('#e9eefc'), width=14 * SS)
    d.ellipse([cx - R - 7 * SS, cy - R - 7 * SS, cx + R + 7 * SS, cy + R + 7 * SS], outline=hexc('#7f8db5'), width=3 * SS)
    d.arc([cx - R + 24 * SS, cy - R + 24 * SS, cx + R - 24 * SS, cy + R - 24 * SS], 205, 255, fill=(255, 255, 255, 200), width=7 * SS)
    return bg, fg


def icon_shield():
    bg = gradient('#2f80ff', '#0b2a8f', diag=True)
    fg = blank(); d = ImageDraw.Draw(fg); b = Box(216, 218, 250)
    left = [(50 - 38 * (1 - (i / 30) ** 2.2 * .0), 14) for i in range(1)]
    pts = [(12, 18), (50, 6), (88, 18), (88, 52)]
    pts += [(50 + 38 * math.cos(math.radians(t)), 52 + 40 * math.sin(math.radians(t))) for t in range(0, 91, 6)][:0]
    side = [(88 - 38 * (1 - math.cos(math.radians(a))) * .0 + 0, 52 + 0) for a in range(1)]
    # right edge curving to the tip, then mirrored
    curve = [(88 - 38 * math.sin(math.radians(a)) * .0, 0) for a in range(1)]
    r = [(88 - 38 * (1 - math.cos(math.radians(a))), 52 + 44 * math.sin(math.radians(a))) for a in range(0, 61, 6)]
    r = [(88 - (88 - 50) * (t / 10) ** 1.7, 52 + 42 * (t / 10) ** .85) for t in range(0, 11)]
    poly = [(12, 18), (50, 6), (88, 18)] + r + [(50 - (x - 50), y) for x, y in reversed(r)]
    sh = blank(); ImageDraw.Draw(sh).polygon(b.pts(poly), fill=(0, 0, 40, 120))
    sh = sh.filter(ImageFilter.GaussianBlur(8 * SS)).transform((W, W), Image.AFFINE, (1, 0, 0, 0, 1, -10 * SS))
    fg = Image.alpha_composite(fg, sh); d = ImageDraw.Draw(fg)
    d.polygon(b.pts(poly), fill=hexc('#ffffff'))
    inner = [(50 + (x - 50) * .84, 52 + (y - 52) * .84 - 2) for x, y in poly]
    d.polygon(b.pts(inner), fill=hexc('#eaf1ff'))
    d.ellipse(b.ell(50, 40, 11, 11), fill=hexc('#1b57d6'))
    d.polygon(b.pts([(44, 46), (56, 46), (59, 72), (41, 72)]), fill=hexc('#1b57d6'))
    return bg, fg


def icon_turtle(src):
    bg = gradient('#c9bcff', '#8d78e8', diag=True)
    art = Image.open(src).convert('RGBA').resize((300 * SS, 300 * SS), Image.LANCZOS)
    m = Image.new('L', art.size, 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, art.width - 1, art.height - 1], radius=64 * SS, fill=255)
    fg = blank(); d = ImageDraw.Draw(fg)
    x0 = (W - art.width) // 2; y0 = (W - art.height) // 2
    sh = blank(); ImageDraw.Draw(sh).rounded_rectangle([x0, y0, x0 + art.width, y0 + art.height], radius=64 * SS, fill=(30, 10, 80, 120))
    fg = Image.alpha_composite(fg, sh.filter(ImageFilter.GaussianBlur(10 * SS)).transform((W, W), Image.AFFINE, (1, 0, 0, 0, 1, -8 * SS)))
    fg.paste(art, (x0, y0), m)
    ImageDraw.Draw(fg).rounded_rectangle([x0, y0, x0 + art.width, y0 + art.height], radius=64 * SS, outline=(255, 255, 255, 230), width=4 * SS)
    return bg, fg


def icon_wordmark():
    bg = gradient('#00c6a7', '#1e4d92', diag=True)
    font_path = 'C:/Windows/Fonts/seguibl.ttf'
    fg = blank(); d = ImageDraw.Draw(fg)
    size = 150 * SS
    f = ImageFont.truetype(font_path, size)
    while d.textlength('yptun', font=f) > 272 * SS: size -= 4 * SS; f = ImageFont.truetype(font_path, size)
    bbox = d.textbbox((0, 0), 'yptun', font=f)
    tx = (W - (bbox[2] - bbox[0])) // 2 - bbox[0]; ty = (W - (bbox[3] - bbox[1])) // 2 - bbox[1] - 6 * SS
    sh = blank(); ImageDraw.Draw(sh).text((tx, ty + 8 * SS), 'yptun', font=f, fill=(0, 20, 60, 130))
    fg = Image.alpha_composite(fg, sh.filter(ImageFilter.GaussianBlur(6 * SS)))
    ImageDraw.Draw(fg).text((tx, ty), 'yptun', font=f, fill=(255, 255, 255, 255))
    # accent underline
    d = ImageDraw.Draw(fg); y = ty + (bbox[3] - bbox[1]) + 22 * SS
    d.rounded_rectangle([W // 2 - 40 * SS, y, W // 2 + 40 * SS, y + 9 * SS], radius=5 * SS, fill=hexc('#ffd24a'))
    return bg, fg


def icon_tunnel():
    bg = radial('#0d2d3f', '#02060d', .5, .5, .75)
    fg = blank(); d = ImageDraw.Draw(fg)
    cx = cy = 216 * SS
    n = 7
    for i in range(n):
        t = i / (n - 1)
        r = (132 - 17 * i) * SS
        col = tuple(int(a + (b - a) * t) for a, b in zip(hexc('#1c7c9c'), hexc('#7ffcff')))[:3] + (int(120 + 135 * t),)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=int((9 - i * .55) * SS))
    core = blank(); ImageDraw.Draw(core).ellipse([cx - 22 * SS, cy - 22 * SS, cx + 22 * SS, cy + 22 * SS], fill=hexc('#e9ffff'))
    fg = Image.alpha_composite(glow(fg, 5, 1), glow(core, 10, 2))
    return bg, fg


ICONS = {
    'neon': icon_neon, 'sunset': icon_sunset, 'kawaii': icon_kawaii, 'pixel': icon_pixel, 'space': icon_space,
    'shield': icon_shield, 'turtle': lambda: icon_turtle(sys.argv[2]), 'word': icon_wordmark, 'tunnel': icon_tunnel,
}

only = sys.argv[3:] or list(ICONS)
for name in only:
    bg, fg = ICONS[name]()
    bg = bg.resize((N, N), Image.LANCZOS); fg = fg.resize((N, N), Image.LANCZOS)
    bg.convert('RGB').save(f'{OUT}/ic_icon_{name}_bg.png', optimize=True)
    fg.save(f'{OUT}/ic_icon_{name}_fg.png', optimize=True)
    comp = bg.convert('RGBA'); comp.alpha_composite(fg)
    c = comp.crop((72, 72, 360, 360)).resize((192 * 2, 192 * 2), Image.LANCZOS)
    m = Image.new('L', c.size, 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, c.width - 1, c.height - 1], radius=int(c.width * .22), fill=255)
    c.putalpha(m); c = c.resize((192, 192), Image.LANCZOS)
    c.save(f'{OUT}/ic_launcher_{name}.png', optimize=True)
    open(f'{V26}/ic_launcher_{name}.xml', 'w', encoding='utf-8', newline='\n').write(
        '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        f'    <background android:drawable="@mipmap/ic_icon_{name}_bg" />\n    <foreground android:drawable="@mipmap/ic_icon_{name}_fg" />\n</adaptive-icon>\n')
    print('ok', name)
