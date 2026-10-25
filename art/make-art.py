# Draws the Lean TV Home icon and TV banner in a calm style: warm dark card,
# one ink line glyph (a house with the set-top box inside), soft tinted glow.
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "res")
INK, SOFT = (236, 230, 220, 255), (142, 135, 125, 255)
BG_TOP, BG_BOTTOM, TINT = (23, 21, 26), (13, 12, 15), (127, 166, 196)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

def background(w, h, radius):
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    grad = Image.new("RGBA", (w, h))
    gd = ImageDraw.Draw(grad)
    for y in range(h):
        t = y / max(1, h - 1)
        gd.line([(0, y), (w, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM)) + (255,))
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([w * 0.1, h * 0.35, w * 0.9, h * 1.25], fill=TINT + (90,))
    grad = Image.alpha_composite(grad, glow.filter(ImageFilter.GaussianBlur(min(w, h) * 0.18)))
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=255)
    im.paste(grad, (0, 0), mask)
    return im

def glyph(d, cx, cy, s, w):
    """House outline with the box (two status dots) inside, s = half width."""
    roof = [(cx - s, cy - s * 0.05), (cx, cy - s * 0.95), (cx + s, cy - s * 0.05)]
    d.line(roof, fill=INK, width=w, joint="curve")
    d.line([(cx - s * 0.78, cy - s * 0.25), (cx - s * 0.78, cy + s * 0.85),
            (cx + s * 0.78, cy + s * 0.85), (cx + s * 0.78, cy - s * 0.25)], fill=INK, width=w, joint="curve")
    for p in roof + [(cx - s * 0.78, cy + s * 0.85), (cx + s * 0.78, cy + s * 0.85)]:
        d.ellipse([p[0] - w / 2, p[1] - w / 2, p[0] + w / 2, p[1] + w / 2], fill=INK)
    bx0, by0, bx1, by1 = cx - s * 0.48, cy + s * 0.22, cx + s * 0.48, cy + s * 0.56
    d.rounded_rectangle([bx0, by0, bx1, by1], radius=(by1 - by0) / 2.6, outline=INK, width=max(2, int(w * 0.7)))
    r = (by1 - by0) * 0.13
    for x in (bx1 - (by1 - by0) * 0.75, bx1 - (by1 - by0) * 0.42):
        d.ellipse([x - r, (by0 + by1) / 2 - r, x + r, (by0 + by1) / 2 + r], fill=INK)

def save(im, size, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.resize(size, Image.LANCZOS).save(path)

# Launcher icon, 4x supersampled.
S = 768
im = background(S, S, S // 5)
glyph(ImageDraw.Draw(im), S / 2, S / 2 + 20, S * 0.3, 30)
save(im, (192, 192), os.path.join(RES, "mipmap-xhdpi", "ic_launcher.png"))

# TV banner 320x180, 4x supersampled.
W, H = 1280, 720
im = background(W, H, 60)
d = ImageDraw.Draw(im)
glyph(d, 300, 370, 150, 26)
big, small = ImageFont.truetype(FONT, 132), ImageFont.truetype(FONT, 62)
d.text((520, 250), "LEAN TV", font=big, fill=INK)
d.text((526, 420), "H  O  M  E", font=small, fill=SOFT)
save(im, (320, 180), os.path.join(RES, "drawable-xhdpi", "banner.png"))
print("art written")
