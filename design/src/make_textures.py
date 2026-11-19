"""Colony Seasons look options: every custom 16x16 texture, block model and blockstate.

Run:  python make_textures.py   (from anywhere)
Writes src/assets/colonyseasons/{textures/block,models/block,blockstates}/ and the 16x zooms
(design/*-texture-x16.png). Textures are drawn as character grids with small hand-picked,
hue-shifted palettes; the few procedural helpers only place whole pixels from those palettes
(no gradients, no noise, no blending).
"""
import io
import json
import math
import os
import zipfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
DESIGN = os.path.dirname(HERE)
ASSETS = os.path.join(HERE, "assets", "colonyseasons")
TEX = os.path.join(ASSETS, "textures", "block")
MOD = os.path.join(ASSETS, "models", "block")
BST = os.path.join(ASSETS, "blockstates")
JAR = os.environ.get("MC_JAR", r"D:\Claude\voyager-libs\client-1.21.1-20240808.144430-extra.jar")
for d in (TEX, MOD, BST):
    os.makedirs(d, exist_ok=True)


def hexc(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def from_grid(rows, pal, size=16):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        row = row.replace(" ", "")
        assert len(row) == size, (y, row, len(row))
        for x, ch in enumerate(row):
            if ch != ".":
                img.putpixel((x, y), hexc(pal[ch]))
    return img


def overlay(img, rows, pal, ox=0, oy=0):
    for y, row in enumerate(rows):
        row = row.replace(" ", "")
        for x, ch in enumerate(row):
            if ch not in ". ":
                img.putpixel((ox + x, oy + y), hexc(pal[ch]))
    return img


def save_tex(img, name):
    img.save(os.path.join(TEX, name + ".png"))
    return img


def zoom(img, out_name, bg=(205, 205, 200)):
    """16x nearest-neighbour zoom on a light neutral background with a faint 1-texel checker."""
    w, h = img.size
    base = Image.new("RGBA", (w, h))
    for y in range(h):
        for x in range(w):
            base.putpixel((x, y), (bg[0], bg[1], bg[2], 255) if (x + y) % 2 else (bg[0] - 12, bg[1] - 12, bg[2] - 12, 255))
    base.alpha_composite(img)
    big = base.resize((w * 16, h * 16), Image.NEAREST)
    big.save(os.path.join(DESIGN, out_name))


def write_json(folder, name, data):
    with open(os.path.join(folder, name + ".json"), "w") as f:
        json.dump(data, f, indent=2)


def vanilla(name):
    with zipfile.ZipFile(JAR) as z:
        return Image.open(io.BytesIO(z.read(f"assets/minecraft/textures/block/{name}.png"))).convert("RGBA")


# =====================================================================  ornament leaves
def spruce_base():
    """vanilla spruce_leaves.png tinted with the spruce foliage colour (#619961), as the game shows it."""
    im = vanilla("spruce_leaves")
    px = im.load()
    for y in range(16):
        for x in range(16):
            r, g, b, a = px[x, y]
            px[x, y] = (r * 0x61 // 255, g * 0x99 // 255, b * 0x61 // 255, a)
    return im


ORN = {"R": "#7a1622", "r": "#b3262c", "h": "#e2553f", "w": "#ffd9b0",   # red bauble: shadow, base, light, glint
       "G": "#7a4a12", "g": "#c98a1f", "y": "#f2c94a", "W": "#fff4c2",   # gold bauble
       "k": "#3b3a36",                                                   # cap
       "L": "#fff1a8", "l": "#f0a43a"}                                   # warm light: core, amber
BAUBLE4_R = ["..k.",
             ".hr.",  # placeholder (replaced below)
             ]
RED4 = [".kk.",
        "rhwr",
        "rrhr",
        "Rrrr",
        ".RR."]
GOLD4 = [".kk.",
         "gyWg",
         "ggyg",
         "Gggg",
         ".GG."]
RED3 = [".k.",
        "rhr",
        "Rrr",
        ".R."]
GOLD3 = [".k.",
         "gyg",
         "Ggg",
         ".G."]
LIGHT = ["L",
         "l"]


def ornament_leaves():
    outs = []
    # variant 0: big red + small gold + two lights
    a = spruce_base()
    overlay(a, RED4, ORN, 2, 2)
    overlay(a, GOLD3, ORN, 10, 9)
    overlay(a, LIGHT, ORN, 12, 3)
    overlay(a, LIGHT, ORN, 4, 11)
    overlay(a, LIGHT, ORN, 8, 14)
    outs.append(save_tex(a, "ornament_leaves_0"))
    # variant 1: big gold + small red + lights
    b = spruce_base()
    overlay(b, GOLD4, ORN, 9, 1)
    overlay(b, RED3, ORN, 3, 9)
    overlay(b, LIGHT, ORN, 2, 2)
    overlay(b, LIGHT, ORN, 13, 11)
    overlay(b, LIGHT, ORN, 8, 7)
    outs.append(save_tex(b, "ornament_leaves_1"))
    # variant 2: lights only + one small red
    c = spruce_base()
    for (x, y) in ((2, 1), (9, 3), (13, 7), (5, 7), (10, 12), (2, 13)):
        overlay(c, LIGHT, ORN, x, y)
    overlay(c, RED3, ORN, 6, 11)
    outs.append(save_tex(c, "ornament_leaves_2"))
    # garland variant (used by the grand tree): a gold tinsel swag across every face
    t = spruce_base()
    tin = {"a": "#fff0a0", "b": "#e9b83a", "c": "#a8701c"}
    sw = ["bb............bb",
          "abb..........bba",
          "cabb........bbac",
          ".cabbb....bbbac.",
          "..ccabbbbbbacc..",
          "....ccaaaacc....",
          "......cccc......"]
    overlay(t, sw, tin, 0, 5)
    overlay(t, LIGHT, ORN, 3, 1)
    overlay(t, LIGHT, ORN, 12, 1)
    overlay(t, RED3, ORN, 7, 0)
    outs.append(save_tex(t, "ornament_leaves_garland"))
    for i in range(3):
        write_json(MOD, f"ornament_leaves_{i}", {"parent": "minecraft:block/cube_all",
                                                 "textures": {"all": f"colonyseasons:block/ornament_leaves_{i}"}})
    write_json(MOD, "ornament_leaves_garland", {"parent": "minecraft:block/cube_all",
                                                "textures": {"all": "colonyseasons:block/ornament_leaves_garland"}})
    # vanilla-style random texture variants (weighted list), like stone or grass rotations
    write_json(BST, "ornament_leaves", {"variants": {
        "style=mixed": [{"model": "colonyseasons:block/ornament_leaves_0"}, {"model": "colonyseasons:block/ornament_leaves_1"},
                        {"model": "colonyseasons:block/ornament_leaves_2"}],
        "style=baubles": {"model": "colonyseasons:block/ornament_leaves_0"},
        "style=lights": {"model": "colonyseasons:block/ornament_leaves_2"},
        "style=garland": {"model": "colonyseasons:block/ornament_leaves_garland"}}})
    return outs


# =====================================================================  tree star
STAR_PAL = {"o": "#6e4310", "d": "#b27418", "m": "#e3a52a", "l": "#f7d055", "h": "#fff6c4"}
STAR = [
    "................",
    ".......oo.......",
    "......ohlo......",
    "......ohlo......",
    ".....ohllmo.....",
    "ooooohllllmooooo",
    ".ohhhllllllmmdo.",
    "..ollllllmmmdo..",
    "...ommmmmmmdo...",
    "....ommmmmddo...",
    "....ommmdddddo..",
    "...ommmdoodddo..",
    "...ommdo..oddo..",
    "..ommdo....odo..",
    "..oddo......oo..",
    "................",
]
# 24-px star on a 32x32 canvas, stored as four 16x16 textures (tl, tr, bl, br)
STAR_L = [
    "................................",
    "................................",
    "................................",
    "...............oo...............",
    "..............ohlo..............",
    "..............ohlo..............",
    ".............ohhlmo.............",
    ".............ohllmo.............",
    "............ohhllmmo............",
    "............ohlllmmo............",
    "....ooooooooohllllmmooooooooo...",
    "....ohhhhhhhhllllllmmmmmmmmdo...",
    ".....ohhhllllllllllllmmmmmdo....",
    "......ollllllllllllllmmmmdo.....",
    ".......ommmllllllllmmmmmdo......",
    "........ommmmmmmmmmmmmmdo.......",
    ".........ommmmmmmmmmmmddo.......",
    "..........ommmmmmmmmmddo........",
    "..........ommmmmmmmdddddo.......",
    ".........ommmmmmmdddddddo.......",
    ".........ommmmmmdoodddddo.......",
    "........ommmmmdoo..oodddo.......",
    "........ommmmdo......odddo......",
    ".......ommmmdo........oddo......",
    ".......ommdoo..........oddo.....",
    "......oddoo.............ooo.....",
    "......ooo.......................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
]


def fix_width(rows, w):
    return [r.replace(" ", "")[:w].ljust(w, ".") for r in rows]


def extruded_elements(img, tex_refs, depth_from, depth_to, plane="z", yoff=0, xoff=0, tile=16):
    """Item-style extrusion: one element per horizontal run of opaque texels (split at tile edges).

    img: RGBA image (16x16 or 32x32), tex_refs: texture ref per tile index (row-major tiles).
    plane z: sprite in the x/y plane (thin in z between depth_from..depth_to);
    plane x: the same sprite turned 90 degrees (thin in x).
    """
    w, h = img.size
    tiles_x = w // tile
    els = []
    for y in range(h):
        x = 0
        while x < w:
            if img.getpixel((x, y))[3] == 0:
                x += 1
                continue
            x0 = x
            while x < w and img.getpixel((x, y))[3] != 0 and (x == x0 or x % tile != 0):
                x += 1
            x1 = x
            ti = (y // tile) * tiles_x + (x0 // tile)
            ref = "#" + tex_refs[ti]
            u0, u1 = x0 % tile, (x1 - 1) % tile + 1
            v0 = y % tile
            uv_face = [u0, v0, u1, v0 + 1]
            # model coords: sprite x -> model x (or z), sprite y (down) -> model y (up)
            my1 = h - y + yoff
            my0 = my1 - 1
            left_uv = [u0, v0, u0 + 1, v0 + 1]
            right_uv = [u1 - 1, v0, u1, v0 + 1]
            if plane == "z":
                el = {"from": [x0 + xoff, my0, depth_from], "to": [x1 + xoff, my1, depth_to],
                      "faces": {"north": {"uv": [u1, v0, u0, v0 + 1], "texture": ref},
                                "south": {"uv": uv_face, "texture": ref},
                                "west": {"uv": left_uv, "texture": ref},
                                "east": {"uv": right_uv, "texture": ref},
                                "up": {"uv": uv_face, "texture": ref},
                                "down": {"uv": uv_face, "texture": ref}}}
            else:
                el = {"from": [depth_from, my0, x0 + xoff], "to": [depth_to, my1, x1 + xoff],
                      "faces": {"east": {"uv": uv_face, "texture": ref},
                                "west": {"uv": [u1, v0, u0, v0 + 1], "texture": ref},
                                "north": {"uv": left_uv, "texture": ref},
                                "south": {"uv": right_uv, "texture": ref},
                                "up": {"uv": uv_face, "texture": ref},
                                "down": {"uv": uv_face, "texture": ref}}}
            els.append(el)
    return els


def tree_star():
    small = from_grid(STAR, STAR_PAL)
    save_tex(small, "tree_star")
    big = from_grid(fix_width(STAR_L, 32), STAR_PAL, size=32)
    names = []
    for i, (bx, by) in enumerate(((0, 0), (16, 0), (0, 16), (16, 16))):
        q = big.crop((bx, by, bx + 16, by + 16))
        save_tex(q, f"tree_star_large_{i}")
        names.append(f"q{i}")
    # small star: two crossed extrusions, 2 px thick, sprite bottom row 14 sits 1 px above the block floor
    els = extruded_elements(small, ["star"], 7, 9, "z") + extruded_elements(small, ["star"], 7, 9, "x")
    write_json(MOD, "tree_star", {"parent": "minecraft:block/block", "ambientocclusion": False,
                                  "textures": {"star": "colonyseasons:block/tree_star", "particle": "colonyseasons:block/tree_star"},
                                  "elements": els})
    # large star: 32 px canvas centred on the block (x -8..24), bottom at y=-6 so the lower points rest on the leaves
    bl = extruded_elements(big, names, 7, 9, "z", yoff=-6, xoff=-8) + extruded_elements(big, names, 7, 9, "x", yoff=-6, xoff=-8)
    tex = {n: f"colonyseasons:block/tree_star_large_{i}" for i, n in enumerate(names)}
    tex["particle"] = "colonyseasons:block/tree_star_large_0"
    write_json(MOD, "tree_star_large", {"parent": "minecraft:block/block", "ambientocclusion": False,
                                        "textures": tex, "elements": bl})
    write_json(BST, "tree_star", {"variants": {"size=small": {"model": "colonyseasons:block/tree_star"},
                                               "size=large": {"model": "colonyseasons:block/tree_star_large"}}})
    return small, big


# =====================================================================  gift pile
def gift(paper, ribbon):
    p0, p1, p2 = paper
    r0, r1, r2 = ribbon
    pal = {"a": p0, "b": p1, "c": p2, "R": r0, "S": r1, "T": r2}
    rows = [
        "bbbbbbbSTbbbbbbb",
        "bcbbbbbSTbbbbcbb",
        "bbbbbbbSTbbbbbbb",
        "bbbbcbbSTbbbbbbb",
        "bbbbbbbSTbbbbbcb",
        "bcbbbbbSTbbbbbbb",
        "bbbbbbbSTbbbcbbb",
        "SSSSSSSTTSSSSSSS",
        "RRRRRRRSRRRRRRRR",
        "bbbbbcbSTbbbbbbb",
        "bbbbbbbSTbcbbbbb",
        "bcbbbbbSTbbbbbbb",
        "bbbbbbbSTbbbbbcb",
        "abbbbbbSTbbbbbbb",
        "aabbbbbSTbbbbbba",
        "aaaaaaaRTaaaaaaa",
    ]
    return from_grid(rows, pal)


def gift_pile():
    red = gift(("#6a1820", "#b02a2e", "#d8504a"), ("#a8701c", "#e9b83a", "#fff0a0"))
    green = gift(("#1d402c", "#2f6b3e", "#4f8f4a"), ("#7a1622", "#c0322e", "#ec7058"))
    blue = gift(("#2a3560", "#4058a0", "#6a86c8"), ("#9aa0aa", "#e4e8ec", "#ffffff"))
    save_tex(red, "gift_red"); save_tex(green, "gift_green"); save_tex(blue, "gift_blue")

    def box(f, t, tex, bow=True):
        w, h, d = t[0] - f[0], t[1] - f[1], t[2] - f[2]
        # side faces: centred horizontally, from row 0 down (above the horizontal ribbon)
        def side(width):
            u0 = 8 - width // 2
            return [u0, 7 - h if h <= 7 else 0, u0 + width, 7 if h <= 7 else h]
        top = [8 - w // 2, 8 - d // 2, 8 - w // 2 + w, 8 - d // 2 + d]
        faces = {"north": {"uv": side(w), "texture": tex}, "south": {"uv": side(w), "texture": tex},
                 "west": {"uv": side(d), "texture": tex}, "east": {"uv": side(d), "texture": tex},
                 "up": {"uv": top, "texture": tex}, "down": {"uv": top, "texture": tex, "cullface": "down"}}
        els = [{"from": f, "to": t, "faces": faces}]
        if bow:
            cx, cz = (f[0] + t[0]) / 2, (f[2] + t[2]) / 2
            ruv = [7, 0, 9, 2]
            for dx in (-2, 0):
                els.append({"from": [cx + dx, t[1], cz - 0.5], "to": [cx + dx + 2, t[1] + 2, cz + 0.5],
                            "faces": {k: {"uv": ruv, "texture": tex} for k in ("north", "south", "east", "west", "up")}})
        return els

    els = (box([1, 0, 1], [9, 6, 9], "#red") + box([9, 0, 7], [15, 5, 13], "#green")
           + box([3, 0, 10], [7, 4, 14], "#blue") + box([10, 0, 2], [14, 3, 6], "#blue", bow=False))
    write_json(MOD, "gift_pile", {"parent": "minecraft:block/block",
                                  "textures": {"red": "colonyseasons:block/gift_red", "green": "colonyseasons:block/gift_green",
                                               "blue": "colonyseasons:block/gift_blue", "particle": "colonyseasons:block/gift_red"},
                                  "elements": els})
    write_json(BST, "gift_pile", {"variants": {f"facing={f}": {"model": "colonyseasons:block/gift_pile", "y": r}
                                               for f, r in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}})
    return red, green, blue


# =====================================================================  wall-hung thin blocks
def wall_block(name, tex_name, extra_elements=(), depth=1):
    """A flat cutout plane against the wall (like a ladder), facing = the way it looks out."""
    els = [{"from": [0, 0, 16 - depth], "to": [16, 16, 16],
            "faces": {"north": {"uv": [0, 0, 16, 16], "texture": "#tex"},
                      "south": {"uv": [16, 0, 0, 16], "texture": "#tex", "cullface": "south"}}}]
    els += list(extra_elements)
    write_json(MOD, name, {"parent": "minecraft:block/block", "ambientocclusion": False,
                           "textures": {"tex": f"colonyseasons:block/{tex_name}", "particle": f"colonyseasons:block/{tex_name}"},
                           "elements": els})


def wall_blockstate(block, models):
    """models: {variant_value or None: model}"""
    v = {}
    for f, r in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for key, model in models.items():
            k = f"facing={f}" + (f",variant={key}" if key is not None else "")
            v[k] = {"model": f"colonyseasons:block/{model}", "y": r}
    write_json(BST, block, {"variants": v})


def relief(rect_uv, frm, to, tex="#tex"):
    """A small raised box (bow, bell...) whose faces all sample one texture rectangle."""
    return {"from": frm, "to": to, "faces": {k: {"uv": rect_uv, "texture": tex} for k in ("north", "east", "west", "up", "down")}}


def ring_clumps(pal_ramp, n=9, r_mid=4.9, r_clump=2.75, light=(-0.62, -0.78), phase=0.35, size=16):
    """Wreath ring made of overlapping round needle clumps, each shaded top-left -> bottom-right
    with the 4-step ramp (0 = darkest). Returns {(x,y): ramp index}."""
    cx = cy = (size - 1) / 2
    pix = {}
    for i in range(n):
        a = phase + i * 2 * math.pi / n
        ccx, ccy = cx + r_mid * math.cos(a), cy + r_mid * math.sin(a)
        for y in range(size):
            for x in range(size):
                dx, dy = x - ccx, y - ccy
                dd = math.hypot(dx, dy)
                if dd <= r_clump:
                    v = (dx * light[0] + dy * light[1]) / r_clump
                    if dd > r_clump - 0.85 and v < 0.1:
                        idx = 0
                    elif v > 0.42:
                        idx = 3
                    elif v > -0.05:
                        idx = 2
                    else:
                        idx = 1
                    pix[(x, y)] = idx
    # inner hole kept clean
    for (x, y) in list(pix):
        if math.hypot(x - cx, y - cy) < 2.6:
            del pix[(x, y)]
    return pix


def pix_to_img(pix, ramp, size=16):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for (x, y), i in pix.items():
        img.putpixel((x, y), hexc(ramp[i]))
    return img


def clean_orphans(img):
    """Replace single pixels whose colour no orthogonal neighbour shares (keeps pixel clusters, drops speckle)."""
    w, h = img.size
    src = img.copy()
    for y in range(h):
        for x in range(w):
            c = src.getpixel((x, y))
            if c[3] == 0:
                continue
            nbs = [src.getpixel((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                   if 0 <= x + dx < w and 0 <= y + dy < h]
            opaque = [n for n in nbs if n[3]]
            if opaque and c not in opaque:
                # most common neighbour colour
                best = max(set(opaque), key=opaque.count)
                if opaque.count(best) >= 2:
                    img.putpixel((x, y), best)
    return img


def wreaths():
    out = {}
    # ---- A classic: spruce greens, red bow at the bottom, red berries
    greens = ["#173326", "#22502f", "#3a7a3a", "#6ea444"]
    pa = ring_clumps(greens)
    a = clean_orphans(pix_to_img(pa, greens))
    pal = {"D": "#5a1420", "r": "#9a2226", "R": "#cc3a32", "H": "#f07a5a", "k": "#3e0c14"}
    # two loops, a knot, two tails - 12 wide, centred on x 2..13, rows 8..15
    bow = [".DD..DD.",
           "DHRDDRHD",
           "DRRkkRRD",
           ".DDkkDD.",
           "..DrrD..",
           ".DrDDrD.",
           ".DD..DD."]
    pal["k"] = "#7a1a20"
    overlay(a, bow, pal, 4, 9)
    berries = {"R": "#c4302c", "H": "#f26a52", "D": "#6a1420"}
    for (x, y) in ((3, 3), (11, 2), (13, 7), (1, 7)):
        overlay(a, ["HR", "RD"], berries, x, y)
    out["A"] = save_tex(a, "wreath_classic")
    wall_block("wreath_classic", "wreath_classic",
               [relief([7, 11, 9, 13], [7, 3, 14], [9, 5, 15])])  # the bow knot stands 1 px proud
    # ---- B frosted: darker blue-green pine, snow on the upper rims, pine cones, small gold bell
    pines = ["#0f2226", "#1a3836", "#2a5148", "#3f6e5a"]
    pb = ring_clumps(pines, n=10, r_clump=2.6, phase=0.1)
    b = pix_to_img(pb, pines)
    snow = ["#cfdcea", "#f4f8fc"]
    # snow sits on the top-facing rims: lit pixels whose pixel above is empty or darker
    cx = 7.5
    for (x, y), i in sorted(pb.items()):
        above = (x, y - 1)
        if i >= 2 and (above not in pb) and y < 9:
            b.putpixel((x, y), hexc(snow[1]))
            if (x, y + 1) in pb and y + 1 < 8 and pb[(x, y + 1)] >= 2:
                b.putpixel((x, y + 1), hexc(snow[0]))
    b = clean_orphans(b)
    cone = {"d": "#3a2214", "m": "#6e4426", "l": "#a0683a"}
    for (x, y) in ((1, 6), (12, 4), (4, 11)):
        overlay(b, [".l.", "mlm", "dmd", ".d."], cone, x, y)
    bell = {"o": "#5e3a0c", "d": "#b0781c", "m": "#e8b33a", "l": "#fff0a0", "c": "#3e2a10"}
    overlay(b, ["..oo..", ".oldo.", ".lmdo.", "olmmdo", "oooooo", "..cc.."], bell, 5, 10)
    out["B"] = save_tex(b, "wreath_frosted")
    wall_block("wreath_frosted", "wreath_frosted",
               [relief([6, 11, 10, 14], [6, 2, 14], [10, 5, 15])])  # bell body proud
    # ---- C rustic: straw and twig ring, dried orange slices, red ribbon wrap
    c = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    straw = ["#4e3418", "#7d5a2c", "#b08a48", "#dcc07a"]
    ccx = ccy = 7.5
    rmid, half = 5.3, 2.1
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - ccx, y - ccy)
            if rmid - half <= r <= rmid + half:
                # twisted straw rope: tube shading (light from the top left) ...
                t = (r - rmid) / half
                ca, sa = (x - ccx) / max(r, 1e-6), (y - ccy) / max(r, 1e-6)
                v = t * (ca * -0.7 + sa * -0.7)
                idx = 3 if v > 0.45 else 2 if v > -0.1 else 1
                if abs(t) > 0.86:
                    idx = 0 if v < 0.3 else 1          # dark rim
                # ... with the twist: every third diagonal is a darker strand
                elif (x + 2 * y) % 4 == 0:
                    idx = max(idx - 1, 1)
                c.putpixel((x, y), hexc(straw[idx]))
    ribbon = {"r": "#a8242a", "R": "#d8443a", "d": "#5e1218"}
    # ribbon wraps: short diagonal bands crossing the ring at four spots
    for (x, y, rows) in ((2, 2, ["Rr.", "dRr", ".dR"]), (11, 2, [".rR", "rRd", "Rd."]),
                         (11, 11, ["Rr.", "dRr", ".dR"]), (2, 11, [".rR", "rRd", "Rd."])):
        overlay(c, rows, ribbon, x, y)
    orange = {"o": "#e07a22", "O": "#a84a12", "p": "#ffd27a", "w": "#fff2c8"}
    for (x, y) in ((6, 0), (12, 6), (0, 6), (6, 12)):
        overlay(c, [".oo.", "opwo", "owpO", ".OO."], orange, x, y)
    out["C"] = save_tex(c, "wreath_rustic")
    wall_block("wreath_rustic", "wreath_rustic",
               [relief([6, 0, 10, 4], [6, 12, 14], [10, 16, 15])])  # top slice proud
    write_json(BST, "wreath", {"variants": {f"facing={f},style={s}": {"model": f"colonyseasons:block/wreath_{s}", "y": r}
                                            for f, r in (("north", 0), ("east", 90), ("south", 180), ("west", 270))
                                            for s in ("classic", "frosted", "rustic")}})
    return out


def strings():
    out = {}
    # ---- A evergreen garland: green swag sagging between red bows (bow at the tile centre)
    g = {"a": "#173326", "b": "#22502f", "c": "#3a7a3a", "d": "#6ea444",
         "D": "#5a1420", "r": "#9a2226", "R": "#cc3a32", "H": "#f07a5a", "k": "#3e0c14"}
    # one continuous swag per tile: high under the bow (tile centre), lowest at the tile edges where it
    # meets the next tile's swag; 3-4 px thick, lit along the top, tufts poking out every few pixels.
    # Content starts at row 2 so the eave above does not hide it.
    rows = [
        "................",
        "................",
        "......DD.DD.....",
        ".....DRHDHRD....",
        "....cDRRkRRDc...",
        "..cdcbDrkrDbcdc.",
        "dcdcbbaDrDabbcdc",
        "cdcbbaaDrDaabbcd",
        "bcbba..D.D..abbc",
        "bbaa..........ab",
        "a..............a",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]
    # the swag has to tile: column 0 must continue column 15
    a = from_grid(rows, g)
    out["A"] = save_tex(a, "string_garland")
    wall_block("string_garland", "string_garland", [relief([7, 3, 9, 6], [7, 10, 14], [9, 13, 15])])
    # ---- B lantern string: dark cord, two small paper lanterns per tile, three colourways
    cords = {"k": "#3a2a1e"}
    lantern_pals = {
        "red": {"c": "#3a2418", "L": "#d8402e", "H": "#ff9060", "S": "#962428", "t": "#f2c94a"},
        "yellow": {"c": "#3a2418", "L": "#efbe46", "H": "#fff1a0", "S": "#b07e1e", "t": "#d8402e"},
        "orange": {"c": "#3a2418", "L": "#ea7e2c", "H": "#ffc27a", "S": "#a4501a", "t": "#f2c94a"},
    }
    lantern = ["..k..",
               ".ccc.",
               "LHLLS",
               "SSSSS",
               "LHLLS",
               "LHLLS",
               ".ccc.",
               "..t.."]
    cord = ["kk..............",
            "..kk..........kk",
            "....kkkk..kkkk..",
            "........kk......"]
    # cord sag: y 1..4 ; lanterns hang at x 2..6 and 9..13
    for name, order in (("0", ("red", "yellow")), ("1", ("yellow", "orange")), ("2", ("orange", "red"))):
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        dy = 2  # keep clear of the eave
        overlay(img, cord, cords, 0, 1 + dy)
        p1 = dict(lantern_pals[order[0]]); p1.update(cords)
        p2 = dict(lantern_pals[order[1]]); p2.update(cords)
        overlay(img, lantern, p1, 2, 3 + dy)
        overlay(img, lantern, p2, 9, 4 + dy)
        save_tex(img, f"string_lanterns_{name}")
        if name == "0":
            out["B"] = img
        # lantern bodies stand 1 px proud of the cord plane (paper lanterns are round)
        wall_block(f"string_lanterns_{name}", f"string_lanterns_{name}",
                   [relief([3, 5 + dy, 6, 9 + dy], [10, 7 - dy, 14], [13, 11 - dy, 15]),
                    relief([10, 6 + dy, 13, 10 + dy], [3, 6 - dy, 14], [6, 10 - dy, 15])])
    # ---- C fairy lights: dark cord, four tiny bulbs per tile
    f = {"k": "#26201c", "s": "#55504a",
         "R": "#e8443a", "r": "#ffb0a0", "G": "#3ec060", "g": "#b8ffc8",
         "B": "#3a86f0", "b": "#b8dcff", "Y": "#f6cc3a", "y": "#fff6c0"}
    f.update({"1": "#a82a28", "2": "#24843e", "3": "#2458a8", "4": "#b88a1e"})
    # cord dips between the four hooks; each bulb = grey socket + 2x3 glass (light, base, shaded base)
    rows = ["................",
            "................",
            "................",
            "................",
            ".kk..kk..kk..kk.",
            "ksskksskksskkssk",
            ".rR..gG..bB..yY.",
            ".RR..GG..BB..YY.",
            ".1R..2G..3B..4Y.",
            "................"]
    rows = rows + ["................"] * (16 - len(rows))
    c = from_grid(rows, f)
    out["C"] = save_tex(c, "string_fairy_lights")
    wall_block("string_fairy_lights", "string_fairy_lights")
    # ---- D bunting (invented for the steep-roof cottage): triangle flags on a cord, two colourways
    bpal = {"k": "#3a2a1e",
            "0": {"F": "#b8322e", "f": "#e2604a", "d": "#7a1c20", "G": "#d8a836", "g": "#f4d070", "e": "#9a7020"},
            "1": {"F": "#2f6b3e", "f": "#5c9a52", "d": "#1d402c", "G": "#3e5a9a", "g": "#6a86c8", "e": "#26356a"}}
    # cord along row 2, two 6x6 pennants hanging from it (lit left edge, shaded right edge)
    rows = ["................",
            "................",
            "kkkkkkkkkkkkkkkk",
            ".fFFFFd..gGGGGe.",
            ".fFFFFd..gGGGGe.",
            "..fFFd....gGGe..",
            "..fFFd....gGGe..",
            "...fd......ge...",
            "...fd......ge...",
            "................"]
    for v in ("0", "1"):
        p = dict(bpal[v]); p["k"] = bpal["k"]
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        overlay(img, rows, p, 0, 0)
        save_tex(img, f"string_bunting_{v}")
        wall_block(f"string_bunting_{v}", f"string_bunting_{v}")
        if v == "0":
            out["D"] = img
    wall_blockstate("string_garland", {None: "string_garland"})
    wall_blockstate("string_lanterns", {"0": "string_lanterns_0", "1": "string_lanterns_1", "2": "string_lanterns_2"})
    wall_blockstate("string_fairy_lights", {None: "string_fairy_lights"})
    wall_blockstate("string_bunting", {"0": "string_bunting_0", "1": "string_bunting_1"})
    return out


def tile_zoom(img, out_name, rows=8, reps=3):
    """3 copies side by side (to show the horizontal tiling), top `rows` texels, 16x."""
    strip = Image.new("RGBA", (16 * reps, 16), (0, 0, 0, 0))
    for i in range(reps):
        strip.paste(img, (16 * i, 0))
    zoom(strip, out_name)


if __name__ == "__main__":
    ornament_leaves()
    tree_star()
    gift_pile()
    w = wreaths()
    s = strings()
    for k, im in w.items():
        zoom(im, f"wreath-{k}-texture-x16.png")
    for k, im in s.items():
        if k in "ABC":
            zoom(im, f"string-{k}-texture-x16.png")
    print("textures:", sorted(os.listdir(TEX)))

