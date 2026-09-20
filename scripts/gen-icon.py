#!/usr/bin/env python3
"""Generate all launcher icon sizes (plus the status-bar glyph) from a square
source image. Also rewrites res/values/colors.xml with the sampled background
color so the adaptive icon blends seamlessly.

Usage: python3 gen-icon.py <icon.png> <repo-root>
"""
import os
import sys

from PIL import Image, ImageDraw

src_path = sys.argv[1]
root = sys.argv[2]

img = Image.open(src_path).convert("RGBA")

# sample the background color from the four corners
corners = [img.getpixel((2, 2)), img.getpixel((img.width - 3, 2)),
           img.getpixel((2, img.height - 3)), img.getpixel((img.width - 3, img.height - 3))]
bg = tuple(sum(c[i] for c in corners) // 4 for i in range(3))
bg_hex = "#{:02X}{:02X}{:02X}".format(*bg)

with open(os.path.join(root, "res/values/colors.xml"), "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            f'    <color name="ic_launcher_background">{bg_hex}</color>\n</resources>\n')

def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, im.size[0] - 1, im.size[1] - 1], radius=radius, fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out

def circled(im):
    mask = Image.new("L", im.size, 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([0, 0, im.size[0] - 1, im.size[1] - 1], fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out

sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, s in sizes.items():
    d = os.path.join(root, f"res/mipmap-{name}")
    os.makedirs(d, exist_ok=True)
    im = img.resize((s, s), Image.LANCZOS)
    rounded(im, s * 0.24).save(os.path.join(d, "ic_launcher.png"))
    circled(im).save(os.path.join(d, "ic_launcher_round.png"))

# adaptive foreground: content scaled into the 66% safe zone of a 432px canvas
fg = Image.new("RGBA", (432, 432), bg + (255,))
inner = img.resize((300, 300), Image.LANCZOS)
fg.paste(inner, ((432 - 300) // 2, (432 - 300) // 2), inner)
fg.save(os.path.join(root, "res/drawable/ic_launcher_foreground.png"))

# status-bar glyph: white silhouette whose alpha comes from luminance
lum = img.convert("L").resize((48, 48), Image.LANCZOS)
stat = Image.new("RGBA", (48, 48), (255, 255, 255, 0))
stat.putalpha(lum)
stat.save(os.path.join(root, "res/drawable/ic_stat.png"))

print("icons generated, background =", bg_hex)
