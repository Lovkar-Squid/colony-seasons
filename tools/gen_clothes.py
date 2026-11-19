"""Paints the winter clothes textures: five knitted scarves, the striped festive scarf and five wool hats.

Writes resources/assets/colony_seasons/textures/entity/scarf_<colour>.png, scarf_festive.png and
wool_hat_<colour>.png, 64x32 each at one texel per model pixel (the size ScarfModel and WoolHatModel declare).
The box layouts below are the texOffs/addBox of those two classes; a box's faces sit where Minecraft's model
cubes expect them (top, bottom, then right, front, left, back in the row under them).

Pure Python (zlib + struct), no image library, and the same bytes on every run.
Run:  python -I tools/gen_clothes.py
"""
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # also works under python -I
import paths  # noqa: E402

W, H = 64, 32
OUT = paths.res("assets", "colony_seasons", "textures", "entity")

# (base) RGB per colour; light and dark shades are mixed from it. Muted wool dyes that sit with MineColonies' skins.
COLOURS = {
    "red": (164, 48, 44),
    "green": (62, 108, 58),
    "blue": (54, 82, 132),
    "mustard": (204, 152, 44),
    "plum": (112, 60, 98),
}
CREAM = (236, 226, 202)
FESTIVE_RED = (176, 34, 40)
GOLD = (214, 172, 62)

# box layouts: texOffs (u, v) and size (w, h, d), as in ScarfModel / WoolHatModel
SCARF_WRAP = ((0, 0), (8, 2, 4))
SCARF_TAIL = ((0, 8), (2, 5, 1))
HAT_BODY = ((0, 0), (8, 3, 8))
HAT_TOP = ((0, 22), (8, 1, 8))
HAT_CUFF = ((0, 12), (8, 2, 8))
HAT_POMPOM = ((32, 0), (2, 2, 2))


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def shades(base):
    return {"base": base, "light": mix(base, (255, 255, 255), 0.22), "dark": mix(base, (0, 0, 0), 0.28),
            "deep": mix(base, (0, 0, 0), 0.45)}


class Canvas:
    def __init__(self):
        self.px = [[(0, 0, 0, 0)] * W for _ in range(H)]

    def put(self, x, y, rgb, alpha=255):
        if 0 <= x < W and 0 <= y < H:
            self.px[y][x] = (rgb[0], rgb[1], rgb[2], alpha)

    def clear(self, x, y):
        self.px[y][x] = (0, 0, 0, 0)

    def png(self):
        raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *p) for p in row) for row in self.px)

        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def faces(layout):
    """{face: (x0, y0, w, h)} of a box on the texture (Minecraft's cube layout)."""
    (u, v), (w, h, d) = layout
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


def ring(layout):
    """The four side faces in order round the box, with the x offset of each along the ring (for patterns that wrap)."""
    f = faces(layout)
    out, along = [], 0
    for name in ("right", "front", "left", "back"):
        out.append((f[name], along))
        along += f[name][2]
    return out


def knit_rows(c, layout, pal):
    """Side faces of a ring of knitting: V stitches, a light row over a darker row, repeated."""
    for (x0, y0, w, h), along in ring(layout):
        for y in range(h):
            for x in range(w):
                k = (x + along) % 2
                if y % 2 == 0:
                    rgb = pal["light"] if k == 0 else pal["base"]
                else:
                    rgb = pal["base"] if k == 0 else pal["dark"]
                c.put(x0 + x, y0 + y, rgb)


def flat(c, rect, rgb):
    x0, y0, w, h = rect
    for y in range(h):
        for x in range(w):
            c.put(x0 + x, y0 + y, rgb)


def ribbed_cap(c, rect, pal):
    """A top or bottom face: base with rib lines every other column."""
    x0, y0, w, h = rect
    for y in range(h):
        for x in range(w):
            c.put(x0 + x, y0 + y, pal["dark"] if x % 2 else pal["base"])


def tail_with_fringe(c, layout, pal, fringe):
    """The hanging end: knitting, then a fringe of tassels along its last row (every other pixel open)."""
    knit_rows(c, layout, pal)
    f = faces(layout)
    for (x0, y0, w, h), along in ring(layout):
        for x in range(w):
            if (x + along) % 2 == 0:
                c.put(x0 + x, y0 + h - 1, fringe)
            else:
                c.clear(x0 + x, y0 + h - 1)
        # the row above the fringe: a darker band where the knitting ends
        for x in range(w):
            c.put(x0 + x, y0 + h - 2, pal["deep"])
    ribbed_cap(c, f["top"], pal)
    flat(c, f["bottom"], fringe)


def scarf(colour):
    pal = shades(COLOURS[colour])
    c = Canvas()
    knit_rows(c, SCARF_WRAP, pal)
    wrap = faces(SCARF_WRAP)
    ribbed_cap(c, wrap["top"], pal)
    ribbed_cap(c, wrap["bottom"], pal)
    tail_with_fringe(c, SCARF_TAIL, pal, pal["light"])
    return c


def festive_scarf():
    red, cream = shades(FESTIVE_RED), shades(CREAM)
    c = Canvas()
    # the wrap: stripes two stitches wide round the neck, red and cream
    for (x0, y0, w, h), along in ring(SCARF_WRAP):
        for y in range(h):
            for x in range(w):
                pal = red if ((x + along) // 2) % 2 == 0 else cream
                c.put(x0 + x, y0 + y, pal["light"] if y == 0 else pal["base"])
    wrap = faces(SCARF_WRAP)
    for name in ("top", "bottom"):
        x0, y0, w, h = wrap[name]
        for y in range(h):
            for x in range(w):
                c.put(x0 + x, y0 + y, (red if (x // 2) % 2 == 0 else cream)["base"])
    # the end: stripes across it, one stitch high, and a gold fringe
    (x0s, y0s, _, _) = faces(SCARF_TAIL)["front"]
    for (x0, y0, w, h), along in ring(SCARF_TAIL):
        for y in range(h):
            for x in range(w):
                pal = red if y % 2 == 0 else cream
                c.put(x0 + x, y0 + y, pal["light"] if (x + along) % 2 == 0 else pal["base"])
        for x in range(w):
            if (x + along) % 2 == 0:
                c.put(x0 + x, y0 + h - 1, GOLD)
            else:
                c.clear(x0 + x, y0 + h - 1)
    tail = faces(SCARF_TAIL)
    flat(c, tail["top"], red["base"])
    flat(c, tail["bottom"], GOLD)
    return c


def hat(colour):
    pal = shades(COLOURS[colour])
    c = Canvas()
    body = faces(HAT_BODY)
    # the body: ribs running up, the top row a shade lighter where it rounds over
    for (x0, y0, w, h), along in ring(HAT_BODY):
        for y in range(h):
            for x in range(w):
                rib = (x + along) % 2
                rgb = pal["base"] if rib == 0 else pal["dark"]
                if y == 0:
                    rgb = pal["light"] if rib == 0 else pal["base"]
                c.put(x0 + x, y0 + y, rgb)
    ribbed_cap(c, body["top"], pal)
    flat(c, body["bottom"], pal["dark"])
    # the top tier: a light band round it, and on top the four lines where the knitting closes
    top = faces(HAT_TOP)
    for (x0, y0, w, h), along in ring(HAT_TOP):
        for x in range(w):
            c.put(x0 + x, y0, pal["light"] if (x + along) % 2 == 0 else pal["base"])
    x0, y0, w, h = top["top"]
    for y in range(h):
        for x in range(w):
            on_line = x == y or x == w - 1 - y
            c.put(x0 + x, y0 + y, pal["dark"] if on_line else (pal["light"] if (x + y) % 2 == 0 else pal["base"]))
    flat(c, top["bottom"], pal["base"])
    # the cuff: the folded band, darker, with deep ribs and a light edge where it folds
    for (x0, y0, w, h), along in ring(HAT_CUFF):
        for y in range(h):
            for x in range(w):
                rib = (x + along) % 2
                if y == 0:
                    rgb = pal["base"] if rib == 0 else pal["dark"]
                else:
                    rgb = pal["dark"] if rib == 0 else pal["deep"]
                c.put(x0 + x, y0 + y, rgb)
    cuff = faces(HAT_CUFF)
    flat(c, cuff["top"], pal["base"])
    flat(c, cuff["bottom"], pal["deep"])
    # the pompom: cream wool
    wool = shades(CREAM)
    for name, (x0, y0, w, h) in faces(HAT_POMPOM).items():
        for y in range(h):
            for x in range(w):
                c.put(x0 + x, y0 + y, wool["light"] if (x + y) % 2 == 0 else wool["base"])
    return c


def main():
    os.makedirs(OUT, exist_ok=True)
    made = []
    for colour in COLOURS:
        made.append((f"scarf_{colour}.png", scarf(colour)))
        made.append((f"wool_hat_{colour}.png", hat(colour)))
    made.append(("scarf_festive.png", festive_scarf()))
    for name, canvas in made:
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(canvas.png())
    print(f"{len(made)} textures -> {OUT}")


if __name__ == "__main__":
    main()
