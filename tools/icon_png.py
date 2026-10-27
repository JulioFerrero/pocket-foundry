"""Pocket Foundry's icon as plain PNGs, for whatever doesn't read the adaptive icon (vivo's notification
center seemed to take Termux:X11's PNGs instead). Composes the committed foreground (the 3D
furnace, rendered on its own blueprint paper) on the paper of drawable/ic_launcher_background.xml, and writes them under
both our names and the Termux:X11 names they replace, so no X icon is left in the app.

    python3 tools/icon_png.py      (from the repo root; needs Pillow)
"""
from PIL import Image, ImageDraw

RES = "app/src/main/res"
S = 432                                           # 108 dp at 4 px/dp
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def paper():
    im = Image.new("RGB", (S, S))
    px = im.load()
    inner, outer = (0x1A, 0x3D, 0x61), (0x09, 0x17, 0x26)
    cx, cy, r = 54 * 4, 50 * 4, 76 * 4             # the vector's radial gradient
    for y in range(S):
        for x in range(S):
            t = min(1, ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 / r)
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(inner, outer))
    lines = Image.new("RGBA", (S, S))
    d = ImageDraw.Draw(lines)
    for i in range(1, 8):                          # minor grid every 13.5, major every 27
        v = round(13.5 * i * 4)
        major = i % 2 == 0
        colour = (0x78, 0xAA, 0xE6, 0x38 if major else 0x1F)
        width = 4 if major else 2
        d.line([(v, 0), (v, S)], fill=colour, width=width)
        d.line([(0, v), (S, v)], fill=colour, width=width)
    return Image.alpha_composite(im.convert("RGBA"), lines)


def glyph(size):
    """drawable/ic_launcher_monochrome.xml as pixels: the furnace on 24 units, scaled 2.25 into the 108 dp layer."""
    k = size / 108 * 2.25
    at = lambda x, y: (27 * size / 108 + x * k, 27 * size / 108 + y * k)
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rectangle([at(15, 3.6), at(19, 8)], fill=255)                 # chimney and its cap
    d.rectangle([at(14.4, 2), at(19.6, 3.6)], fill=255)
    d.rounded_rectangle([at(3, 7), at(21, 22)], radius=2 * k, fill=255)
    d.rounded_rectangle([at(7, 15), at(17, 20)], radius=1 * k, fill=0)   # the mouth
    for x in (7, 11.1, 15.2):                                       # the vents
        d.rectangle([at(x, 9.5), at(x + 1.8, 13)], fill=0)
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    out.putalpha(mask)
    return out


def main():
    icon = paper()
    icon.alpha_composite(Image.open(f"{RES}/mipmap-xxxhdpi/ic_launcher_foreground.png").convert("RGBA"))
    visible = icon.crop((S // 6, S // 6, S - S // 6, S - S // 6))   # the 72 dp a launcher shows
    for bucket, size in SIZES.items():
        art = visible.resize((size * 4, size * 4), Image.LANCZOS)
        for shape in ("square", "round"):
            mask = Image.new("L", art.size)
            d = ImageDraw.Draw(mask)
            if shape == "square":
                d.rounded_rectangle([0, 0, art.width - 1, art.height - 1], radius=art.width // 5, fill=255)
            else:
                d.ellipse([0, 0, art.width - 1, art.height - 1], fill=255)
            out = Image.new("RGBA", art.size)
            out.paste(art, mask=mask)
            out = out.resize((size, size), Image.LANCZOS)
            suffix = "" if shape == "square" else "_round"
            for name in ("ic_launcher", "lorie_ic_launcher"):
                out.save(f"{RES}/mipmap-{bucket}/{name}{suffix}.png", optimize=True)
    # Termux:X11's adaptive layers too, so not even an unused X is left.
    foreground = Image.open(f"{RES}/mipmap-xxxhdpi/ic_launcher_foreground.png").convert("RGBA")
    silhouette = glyph(foreground.width)                # the foreground is opaque: draw the flat furnace instead
    for bucket, size in SIZES.items():
        layer = size * 9 // 4                        # 108 dp
        for part, image in (("foreground", foreground), ("background", paper()), ("monochrome", silhouette)):
            image.resize((layer, layer), Image.LANCZOS).save(f"{RES}/mipmap-{bucket}/lorie_ic_launcher_{part}.png", optimize=True)


main()
