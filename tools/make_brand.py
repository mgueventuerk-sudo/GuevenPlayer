"""Erzeugt Launcher-Icon und Android-TV-Banner für GuevenPlayer als PNG."""
import math, os, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

RES = sys.argv[1]
FONT_BOLD = "/usr/share/fonts/truetype/google-fonts/Poppins-Bold.ttf"
FONT_MED = "/usr/share/fonts/truetype/google-fonts/Poppins-Medium.ttf"
BG = (7, 9, 15)
INDIGO = (91, 92, 246)
CYAN = (34, 211, 238)
WHITE = (244, 245, 250)
MUTED = (140, 146, 170)

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def gradient_square(size):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = lerp(INDIGO, CYAN, t)
    return img

def mark(size, ss=4):
    """Bildmarke: abgerundete Verlaufs-Kachel, offener Ring (G) und Play-Dreieck – wie BrandMark in Compose."""
    S = size * ss
    tile = gradient_square(S).convert("RGBA")
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.28), fill=255)
    tile.putalpha(mask)
    d = ImageDraw.Draw(tile)
    r = S * 0.29
    w = S * 0.095
    c = S / 2
    box = [c - r, c - r, c + r, c + r]
    # PIL-Winkel: 0° = 3 Uhr, im Uhrzeigersinn – identisch zu Compose drawArc.
    d.arc(box, start=38, end=38 + 284, fill=(255, 255, 255, 255), width=int(w))
    # runde Enden
    for ang in (38, 38 + 284):
        a = math.radians(ang)
        ex, ey = c + (r - w / 2) * math.cos(a), c + (r - w / 2) * math.sin(a)
        d.ellipse([ex - w / 2, ey - w / 2, ex + w / 2, ey + w / 2], fill=(255, 255, 255, 255))
    tri = [(c - S * 0.085, c - S * 0.115), (c + S * 0.135, c), (c - S * 0.085, c + S * 0.115)]
    d.polygon(tri, fill=(255, 255, 255, 255))
    return tile.resize((size, size), Image.LANCZOS)

def launcher_icons():
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in sizes.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        mark(px).save(os.path.join(folder, "ic_launcher.png"))

def banner():
    W, H = 640, 360  # 320x180 dp in xhdpi
    img = Image.new("RGB", (W, H), BG)
    # weiches Indigo-Leuchten links
    glow = Image.new("RGB", (W, H), BG)
    gd = ImageDraw.Draw(glow)
    gd.ellipse([-160, -120, 420, 480], fill=(30, 31, 90))
    glow = glow.filter(ImageFilter.GaussianBlur(90))
    img = Image.blend(img, glow, 0.85)
    m = mark(124)
    img.paste(m, (48, (H - 124) // 2 - 14), m)
    d = ImageDraw.Draw(img)
    x = 196
    size = 62
    while size > 30:
        font = ImageFont.truetype(FONT_BOLD, size)
        if d.textlength("GuevenPlayer", font=font) <= W - x - 36:
            break
        size -= 1
    y = H // 2 - 14 - int(size * 0.95)
    d.text((x, y), "Gueven", font=font, fill=WHITE)
    gw = d.textlength("Gueven", font=font)
    d.text((x + gw, y), "Player", font=font, fill=CYAN)
    small = ImageFont.truetype(FONT_MED, 22)
    d.text((x + 3, y + int(size * 1.35)), "Live-TV  ·  Filme  ·  Serien", font=small, fill=MUTED)
    # dünner Verlaufsbalken unten
    bar_y = H - 34
    for i in range(W - 96):
        t = i / (W - 97)
        d.line([(48 + i, bar_y), (48 + i, bar_y + 4)], fill=lerp(INDIGO, CYAN, t))
    folder = os.path.join(RES, "drawable-xhdpi")
    os.makedirs(folder, exist_ok=True)
    img.save(os.path.join(folder, "tv_banner.png"))

launcher_icons()
banner()
print("ok")
