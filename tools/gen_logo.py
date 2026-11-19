"""The Colony Seasons logo: 400x400 PNG, made from the mod's own textures and one of its own renders.

The name is drawn with a small pixel font defined in this file, so the picture does not depend on any
font installed on the machine and the name stays readable when CurseForge shows the logo at 64 px.

Three variants, picked by the owner (docs/renders/s11/):
    a  the tree built from our ornament leaves and star, on a night sky
    b  the wreath, large, on a night sky
    c  a crop of the square render (tree, gift pile, two decorated houses) with the name on a band

    python tools/gen_logo.py              writes the three variants to docs/renders/s11/ and the
                                          chosen one (LOGO_CHOICE) to resources/colony_seasons_logo.png
    python tools/gen_logo.py --variant b  writes only that variant to resources/colony_seasons_logo.png

Needs Pillow. Reads only files of this repository.
"""
import argparse
import pathlib
import sys

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEXTURES = ROOT / "resources" / "assets" / "colony_seasons" / "textures" / "block"
SQUARE_RENDER = ROOT / "docs" / "renders" / "s2" / "2026-11-19-s2-square-day.png"
LOGO_OUT = ROOT / "resources" / "colony_seasons_logo.png"
VARIANTS_OUT = ROOT / "docs" / "renders" / "s11"

SIDE = 400
LOGO_CHOICE = "a"
DATE_TAG = "2026-11-19"  # in the picture file names, as the project asks

SKY_TOP = (16, 28, 52)
SKY_BOTTOM = (64, 94, 132)
SNOW = (236, 242, 248)
SNOW_SHADE = (200, 214, 230)
BAND = (46, 31, 20)
BAND_EDGE = (92, 64, 40)
BAND_DARK = (30, 20, 13)
CREAM = (250, 236, 196)
TEXT_SHADOW = (20, 12, 8)
LEAF_BACK = (22, 54, 34)

# A 5x7 pixel font; only the letters the name needs.
GLYPHS = {
    "B": ("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    "C": ("01110", "10001", "10000", "10000", "10000", "10001", "01110"),
    "O": ("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    "L": ("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    "N": ("10001", "11001", "10101", "10101", "10011", "10001", "10001"),
    "Y": ("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
    "S": ("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
}
GLYPH_W, GLYPH_H, GLYPH_GAP = 5, 7, 1


def text_width(word, unit):
    return (len(word) * (GLYPH_W + GLYPH_GAP) - GLYPH_GAP) * unit


def draw_word(img, word, x, y, unit, color=CREAM, shadow=TEXT_SHADOW):
    """Draw a word of pixel letters, each lit pixel a unit x unit square, with a one-unit drop shadow."""
    draw = ImageDraw.Draw(img)
    cells = []
    for index, letter in enumerate(word):
        left = x + index * (GLYPH_W + GLYPH_GAP) * unit
        for row, bits in enumerate(GLYPHS[letter]):
            for col, bit in enumerate(bits):
                if bit == "1":
                    cells.append((left + col * unit, y + row * unit))
    for cx, cy in cells:
        for dx, dy in ((unit, unit), (unit, 0), (0, unit), (-unit // 2, unit), (unit, -unit // 2)):
            draw.rectangle([cx + dx, cy + dy, cx + dx + unit - 1, cy + dy + unit - 1], fill=shadow)
    for cx, cy in cells:
        draw.rectangle([cx, cy, cx + unit - 1, cy + unit - 1], fill=color)


def draw_name(img, top, unit):
    """COLONY over SEASONS, each centred; returns the bottom edge."""
    lines = ("COLONY", "SEASONS")
    y = top
    for line in lines:
        draw_word(img, line, (SIDE - text_width(line, unit)) // 2, y, unit)
        y += GLYPH_H * unit + unit * 2
    return y


def texture(name, scale):
    tex = Image.open(TEXTURES / f"{name}.png").convert("RGBA")
    return tex.resize((tex.width * scale, tex.height * scale), Image.NEAREST)


def sky():
    img = Image.new("RGB", (SIDE, SIDE))
    draw = ImageDraw.Draw(img)
    for y in range(SIDE):
        t = y / (SIDE - 1)
        draw.line([(0, y), (SIDE, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(SKY_TOP, SKY_BOTTOM)))
    # a few fixed flakes, no randomness so the picture is the same on every run
    for fx, fy in ((34, 40), (84, 118), (52, 190), (350, 52), (318, 150), (372, 214), (150, 26), (258, 84), (30, 250), (364, 120)):
        draw.rectangle([fx, fy, fx + 3, fy + 3], fill=(226, 236, 248))
    return img


def sign_band(img, top):
    draw = ImageDraw.Draw(img)
    draw.rectangle([0, top, SIDE, SIDE], fill=BAND)
    draw.rectangle([0, top, SIDE, top + 5], fill=BAND_EDGE)
    draw.rectangle([0, top + 6, SIDE, top + 9], fill=BAND_DARK)


def variant_a():
    """The tree, in whole blocks of our ornament leaves, with the star on top."""
    img = sky()
    draw = ImageDraw.Draw(img)
    cell = 32
    rows = (1, 3, 3, 5, 5, 7)  # block widths from the top
    ground = 238
    top = ground - len(rows) * cell
    leaves = [texture("ornament_leaves_0", 2), texture("ornament_leaves_1", 2), texture("ornament_leaves_2", 2)]
    glow = [texture("ornament_leaves_0_glow", 2), texture("ornament_leaves_1_glow", 2), texture("ornament_leaves_2_glow", 2)]
    for r, width in enumerate(rows):
        left = (SIDE - width * cell) // 2
        for c in range(width):
            x, y = left + c * cell, top + r * cell
            draw.rectangle([x, y, x + cell - 1, y + cell - 1], fill=LEAF_BACK)
            pick = (r * 2 + c * 3) % 3
            img.paste(leaves[pick], (x, y), leaves[pick])
            img.paste(glow[pick], (x, y), glow[pick])
    # trunk
    trunk = (SIDE // 2 - 16, ground, SIDE // 2 + 15, ground + 14)
    draw.rectangle(trunk, fill=(84, 56, 32))
    star = texture("tree_star", 3)
    img.paste(star, ((SIDE - star.width) // 2, top - star.height + 6), star)
    # snow under the tree
    draw.rectangle([0, ground + 12, SIDE, ground + 22], fill=SNOW)
    draw.rectangle([0, ground + 22, SIDE, ground + 26], fill=SNOW_SHADE)
    sign_band(img, ground + 26)
    draw_name(img, ground + 26 + 22, 6)
    return img


def variant_b():
    """The wreath, large, with the name under it."""
    img = sky()
    wreath = texture("wreath", 14)
    img.paste(wreath, ((SIDE - wreath.width) // 2, 10), wreath)
    draw = ImageDraw.Draw(img)
    ground = 238
    draw.rectangle([0, ground, SIDE, ground + 10], fill=SNOW)
    draw.rectangle([0, ground + 10, SIDE, ground + 14], fill=SNOW_SHADE)
    sign_band(img, ground + 14)
    draw_name(img, ground + 14 + 26, 6)
    return img


def variant_c():
    """A crop of the square render, with the name on a band across the bottom."""
    if not SQUARE_RENDER.exists():
        raise SystemExit(f"missing render: {SQUARE_RENDER}")
    render = Image.open(SQUARE_RENDER).convert("RGB")
    crop = render.crop((65, 50, 765, 750))  # the tree in the middle, a house each side
    img = crop.resize((SIDE, SIDE), Image.LANCZOS)
    band_top = 276
    sign_band(img, band_top)
    draw_name(img, band_top + 16, 6)
    return img


def contact_sheet(built):
    """The variants side by side, each also at 128 and 64 px, the sizes CurseForge shows."""
    tile, gap = 300, 20
    width = len(built) * tile + (len(built) + 1) * gap
    sheet = Image.new("RGB", (width, gap + tile + gap + 128 + gap + 20), (236, 236, 230))
    for index, key in enumerate(sorted(built)):
        x = gap + index * (tile + gap)
        image = built[key]
        sheet.paste(image.resize((tile, tile), Image.LANCZOS), (x, gap))
        sheet.paste(image.resize((128, 128), Image.LANCZOS), (x, gap + tile + gap))
        sheet.paste(image.resize((64, 64), Image.LANCZOS), (x + 148, gap + tile + gap))
        draw_word_small(sheet, key.upper(), x + 148, gap + tile + gap + 76)
    return sheet


def draw_word_small(img, text, x, y):
    """A one-letter label (A, B, C) in the pixel font."""
    if text in GLYPHS:
        draw_word(img, text, x, y, 4, color=(40, 40, 40), shadow=(236, 236, 230))


VARIANTS = {"a": variant_a, "b": variant_b, "c": variant_c}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--variant", choices=sorted(VARIANTS), help="write only this variant, to the logo path")
    args = parser.parse_args()
    if args.variant:
        image = VARIANTS[args.variant]()
        image.save(LOGO_OUT)
        print(LOGO_OUT, image.size)
        return 0
    VARIANTS_OUT.mkdir(parents=True, exist_ok=True)
    built = {}
    for key, build in VARIANTS.items():
        image = build()
        built[key] = image
        dest = VARIANTS_OUT / f"logo-{key}-{DATE_TAG}.png"
        image.save(dest)
        print(dest, image.size)
        if key == LOGO_CHOICE:
            image.save(LOGO_OUT)
            print(LOGO_OUT, image.size)
    sheet = contact_sheet(built)
    dest = VARIANTS_OUT / f"logo-sheet-{DATE_TAG}.png"
    sheet.save(dest)
    print(dest, sheet.size)
    return 0


if __name__ == "__main__":
    sys.exit(main())
