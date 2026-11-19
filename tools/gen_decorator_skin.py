"""Paint the Decorator's citizen textures (128x64) and his citizen icons.

The Decorator is a craftsman of the colony's festivals and dresses for the workbench: a knitted cap in rust wool,
a linen shirt with the sleeves rolled to the elbow, an evergreen apron with a red trim that reaches the knee, a
leather belt, a pocket on the apron with a coil of string and the handles of his scissors, brown wool trousers and
boots. Hand-placed pixels, a few shades per colour, no symbols.

The head (face, hair, skin tone) is cut from MineColonies' own Florist textures (falling back to the plain
citizen), so the four skin tones (_a, _b, _d, _w) and both genders keep their faces; the hair layer is kept below
the cap. Everything from the neck down is ours. The layout matches DecoratorClient.DecoratorModel:

  head (0,0) 8x8x8      cap = hat layer (32,0) 8x8x8
  body (16,16) 8x12x4   right arm (40,16) 4x12x4   right leg (0,16) 4x12x4
  left arm (32,48)      left leg (16,48)           pocket (64,0) 3x3x1

Bodies go to resources/assets/minecolonies/textures/entity/citizen/default/ only (MineColonies falls back to
default for every style); the 16x16 icons go to entity_icon/citizen/<style>/ for all eight styles, because the
icon lookup has no fall-back. Nothing else goes into the nether folder (design section 7).

    python tools/gen_decorator_skin.py
The MineColonies jar is $MINECOLONIES_JAR, or the minecolonies-*.jar in $SEASONS_LIBS (see tools/paths.py).
"""
import os
import sys
import zipfile
from collections import Counter

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

NAME = "decorator"   # = DecoratorSetup.MODEL_ID's path (tools/check_skins.py)
RES = paths.res("assets", "minecolonies", "textures")
STYLES = ("default", "eastasian", "hellenic", "medieval", "modern", "nether", "nordic", "undead")
SUFFIXES = ("_a", "_b", "_d", "_w")
HEAD_SOURCES = ("florist{gender}1{sfx}", "citizen{gender}1{sfx}")

# palette
CAP = (160, 92, 44, 255)          # rust wool
CAP_LIGHT = (188, 116, 60, 255)
CAP_DARK = (118, 64, 30, 255)
LINEN = (204, 190, 160, 255)      # shirt
LINEN_LIGHT = (226, 214, 188, 255)
LINEN_DARK = (166, 152, 122, 255)
APRON = (46, 86, 56, 255)         # evergreen
APRON_LIGHT = (62, 110, 72, 255)
APRON_DARK = (32, 62, 40, 255)
TRIM = (162, 48, 42, 255)         # red trim
TRIM_DARK = (122, 34, 30, 255)
LEATHER = (92, 62, 40, 255)       # belt
LEATHER_DARK = (66, 44, 28, 255)
BRASS = (196, 156, 84, 255)
WOOL = (76, 58, 44, 255)          # trousers
WOOL_DARK = (58, 44, 33, 255)
BOOT = (58, 40, 28, 255)
BOOT_LIGHT = (84, 60, 42, 255)
STRING = (232, 226, 210, 255)
IRON = (150, 156, 162, 255)
IRON_DARK = (104, 108, 114, 255)
CLEAR = (0, 0, 0, 0)


def box_faces(u, v, w, h, d):
    """Minecraft box UV: face -> (x0, y0, x1, y1) pixel rectangles (exclusive max)."""
    return {
        "top": (u + d, v, u + d + w, v + d),
        "bottom": (u + d + w, v, u + d + 2 * w, v + d),
        "right": (u, v + d, u + d, v + d + h),
        "front": (u + d, v + d, u + d + w, v + d + h),
        "left": (u + d + w, v + d, u + 2 * d + w, v + d + h),
        "back": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
    }


def fill(im, rect, color):
    x0, y0, x1, y1 = rect
    if x1 > x0 and y1 > y0:
        ImageDraw.Draw(im).rectangle([x0, y0, x1 - 1, y1 - 1], fill=color)


def px(im, rect, col, row, color):
    im.putpixel((rect[0] + col, rect[1] + row), color)


def row(im, rect, r, color, c0=0, c1=None):
    x0, y0, x1, _ = rect
    c1 = (x1 - x0) if c1 is None else c1
    fill(im, (x0 + c0, y0 + r, x0 + c1, y0 + r + 1), color)


def skin_tone(src):
    """The citizen's skin colour, for the bare forearms: the commonest colour family of the lower face, leaving out
    every colour of the crown (the hair) and the dark ones (eyes, mouth); the mean of that family."""
    hair = {src.getpixel((x, y)) for x in range(8, 16) for y in range(0, 8)}
    found = []
    for x in range(8, 16):
        for y in range(12, 16):
            p = src.getpixel((x, y))
            if p[3] == 255 and p not in hair and sum(p[:3]) > 120:
                found.append(p)
    if not found:
        return (196, 150, 110, 255)
    families = Counter((p[0] // 12, p[1] // 12, p[2] // 12) for p in found)
    best = families.most_common(1)[0][0]
    members = [p for p in found if (p[0] // 12, p[1] // 12, p[2] // 12) == best]
    return tuple(sum(p[i] for p in members) // len(members) for i in range(3)) + (255,)


def shade(color, amount):
    return tuple(max(0, min(255, c + amount)) for c in color[:3]) + (255,)


def cap(im):
    """The knitted cap on the hat layer: ribbed crown, a darker folded brim; low at the back, clear of the eyes."""
    f = box_faces(32, 0, 8, 8, 8)
    top = f["top"]
    fill(im, top, CAP)
    for r in range(8):
        for c in range(8):
            if (c + r // 2) % 3 == 0:
                px(im, top, c, r, CAP_DARK)
            elif (c + r) % 4 == 1:
                px(im, top, c, r, CAP_LIGHT)
    px(im, top, 3, 3, CAP_DARK)
    px(im, top, 4, 4, CAP_DARK)
    fill(im, f["bottom"], CLEAR)
    # how many rows the cap covers on each side: two on the brow and the brim under them, more behind
    depth = {"front": 2, "right": 3, "left": 3, "back": 4}
    for side, rows in depth.items():
        rect = f[side]
        for r in range(rows):
            for c in range(8):
                color = CAP_DARK if c % 2 == 0 else CAP
                if r == 0 and c % 2 == 1:
                    color = CAP_LIGHT
                px(im, rect, c, r, color)
        row(im, rect, rows, CAP_DARK)                     # the folded brim
        for c in range(1, 8, 3):
            px(im, rect, c, rows, shade(CAP_DARK, -16))   # its stitches


def body(im, skin):
    f = box_faces(16, 16, 8, 12, 4)
    fill(im, f["top"], LINEN)
    fill(im, f["bottom"], WOOL_DARK)
    for side in ("right", "left", "back"):
        rect = f[side]
        fill(im, rect, LINEN)
        row(im, rect, 0, LINEN_LIGHT)
        row(im, rect, 6, LEATHER)                         # the belt
        row(im, rect, 7, LINEN_DARK)
        for r in range(8, 12):
            row(im, rect, r, WOOL if r < 11 else WOOL_DARK)
    for side in ("right", "left"):
        rect = f[side]
        px(im, rect, 0, 6, APRON_DARK)                    # the apron's edge seen from the side
        for r in range(7, 12):
            px(im, rect, 0, r, APRON)
        px(im, rect, 0, 11, TRIM)
    back = f["back"]
    # apron straps crossing on the back, tied at the belt
    for r, (a, b) in enumerate([(1, 6), (2, 5), (3, 4), (4, 3), (5, 2)], start=1):
        px(im, back, a, r, APRON_DARK)
        px(im, back, b, r, APRON_DARK)
    px(im, back, 3, 6, TRIM)
    px(im, back, 4, 6, TRIM)
    px(im, back, 3, 7, TRIM_DARK)
    px(im, back, 5, 7, TRIM_DARK)
    px(im, back, 1, 2, LINEN_DARK)                        # a fold of the shirt
    px(im, back, 6, 4, LINEN_DARK)

    front = f["front"]
    fill(im, front, LINEN)
    row(im, front, 0, LINEN_LIGHT)
    px(im, front, 3, 0, skin)                             # the open collar
    px(im, front, 4, 0, skin)
    px(im, front, 3, 1, LINEN_DARK)
    # the bib with its neck straps
    for r in (0, 1):
        px(im, front, 1, r, APRON_DARK)
        px(im, front, 6, r, APRON_DARK)
    fill(im, (front[0] + 1, front[1] + 2, front[0] + 7, front[1] + 6), APRON)
    row(im, front, 2, TRIM, 1, 7)                         # red trim along the top of the bib
    for r in range(3, 6):
        px(im, front, 1, r, APRON_LIGHT)
        px(im, front, 6, r, APRON_DARK)
    px(im, front, 4, 4, APRON_DARK)                       # a crease
    # the waist band and the skirt
    row(im, front, 6, TRIM)
    px(im, front, 0, 6, TRIM_DARK)
    px(im, front, 7, 6, TRIM_DARK)
    fill(im, (front[0], front[1] + 7, front[0] + 8, front[1] + 12), APRON)
    for r in range(7, 12):
        px(im, front, 0, r, APRON_DARK)
        px(im, front, 7, r, APRON_DARK)
    px(im, front, 2, 8, APRON_LIGHT)
    px(im, front, 2, 9, APRON_LIGHT)
    px(im, front, 5, 9, APRON_DARK)
    px(im, front, 5, 10, APRON_DARK)
    px(im, front, 6, 8, APRON_LIGHT)


def arm(im, u, v, skin):
    """A linen sleeve rolled to the elbow, bare forearm, the hand."""
    f = box_faces(u, v, 4, 12, 4)
    fill(im, f["top"], LINEN)
    fill(im, f["bottom"], skin)
    dark = shade(skin, -22)
    for side in ("right", "front", "left", "back"):
        rect = f[side]
        fill(im, rect, LINEN)
        row(im, rect, 0, LINEN_LIGHT)
        px(im, rect, 1, 2, LINEN_DARK)
        px(im, rect, 2, 4, LINEN_DARK)
        row(im, rect, 5, LINEN_LIGHT)                     # the rolled cuff
        row(im, rect, 6, LINEN_DARK)
        for r in range(7, 12):
            row(im, rect, r, skin)
        px(im, rect, 0, 8, dark)
        row(im, rect, 11, dark)


def leg(im, u, v):
    """Brown wool trousers under the apron's knee-length skirt, boots."""
    f = box_faces(u, v, 4, 12, 4)
    fill(im, f["top"], WOOL)
    fill(im, f["bottom"], BOOT)
    for side in ("right", "front", "left", "back"):
        rect = f[side]
        fill(im, rect, WOOL)
        px(im, rect, 1, 5, WOOL_DARK)
        px(im, rect, 2, 7, WOOL_DARK)
        row(im, rect, 8, BOOT_LIGHT)
        for r in range(9, 12):
            row(im, rect, r, BOOT)
        px(im, rect, 3, 10, LEATHER_DARK)
    front = f["front"]
    fill(im, (front[0], front[1], front[0] + 4, front[1] + 3), APRON)
    row(im, front, 3, TRIM)                               # the apron's hem
    px(im, front, 1, 1, APRON_DARK)


def pocket(im):
    """The apron pocket: a coil of string and the iron handles of his scissors."""
    f = box_faces(64, 0, 3, 3, 1)
    for side in f:
        fill(im, f[side], APRON_DARK)
    top = f["top"]
    px(im, top, 0, 0, STRING)
    px(im, top, 1, 0, APRON_DARK)
    px(im, top, 2, 0, IRON)
    front = f["front"]
    fill(im, front, APRON)
    row(im, front, 0, TRIM)
    px(im, front, 0, 1, STRING)
    px(im, front, 2, 1, IRON_DARK)
    px(im, front, 1, 2, APRON_DARK)


def paint(src):
    im = Image.new("RGBA", (128, 64), CLEAR)
    im.paste(src.crop((0, 0, 64, 16)), (0, 0))          # head and the hair layer, as the citizen was born
    skin = skin_tone(src)
    cap(im)
    body(im, skin)
    arm(im, 40, 16, skin)
    arm(im, 32, 48, skin)
    leg(im, 0, 16)
    leg(im, 16, 48)
    pocket(im)
    return im


def icon(tex):
    """16x16 head icon, face with the cap over it, like MineColonies' own icons."""
    face = tex.crop((8, 8, 16, 16)).convert("RGBA")
    face.alpha_composite(tex.crop((40, 8, 48, 16)).convert("RGBA"))
    return face.resize((16, 16), Image.NEAREST).convert("RGB")


def head_texture(jar, gender, sfx):
    for name in HEAD_SOURCES:
        src = "assets/minecolonies/textures/entity/citizen/default/" + name.format(gender=gender, sfx=sfx) + ".png"
        try:
            with jar.open(src) as fh:
                return Image.open(fh).convert("RGBA"), src
        except KeyError:
            continue
    raise SystemExit(f"no head texture for {gender}{sfx}: tried {HEAD_SOURCES}")


def main():
    jar_path = os.environ.get("MINECOLONIES_JAR") or paths.lib("minecolonies-*.jar")
    body_dir = os.path.join(RES, "entity", "citizen", "default")
    os.makedirs(body_dir, exist_ok=True)
    used = set()
    with zipfile.ZipFile(jar_path) as jar:
        for gender in ("male", "female"):
            for sfx in SUFFIXES:
                src, where = head_texture(jar, gender, sfx)
                used.add(os.path.basename(where))
                tex = paint(src)
                tex.save(os.path.join(body_dir, f"{NAME}{gender}1{sfx}.png"))
                ic = icon(tex)
                for style in STYLES:
                    folder = os.path.join(RES, "entity_icon", "citizen", style)
                    os.makedirs(folder, exist_ok=True)
                    ic.save(os.path.join(folder, f"{NAME}{gender}1{sfx}.png"))
    print("jar:", os.path.basename(jar_path))
    print("heads from:", sorted(used))
    print("textures:", sorted(f for f in os.listdir(body_dir) if f.startswith(NAME)))


if __name__ == "__main__":
    main()
