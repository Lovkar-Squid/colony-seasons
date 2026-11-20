"""Draft models for the Town Square picture round (0.2.0, chunk Q0): blocks that do not exist yet, drawn only so the
owner can choose a look. Nothing here is shipped. Writes design/src/square_assets/colonyseasons/ (blockstates,
models/block, textures/block); squares.py merges it with the mod's own resources/assets for the renderer.

Every model uses vanilla 1.21.1 textures from the client jar where it can; the few new textures (water surface,
notice board, sign, autumn leaves) are made here from vanilla ones with PIL.

    python square_standins.py          (needs MC_JAR, as the other design scripts)
"""
import io
import json
import os
import sys
import zipfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "tools"))
OUT = os.path.join(HERE, "square_assets", "colonyseasons")
JAR = os.environ.get("MC_JAR", r"D:\Claude\voyager-libs\client-1.21.1-20240808.144430-extra.jar")
NS = "colonyseasons"
FACING_Y = {"north": 0, "east": 90, "south": 180, "west": 270}


def vanilla_png(name):
    with zipfile.ZipFile(JAR) as z:
        im = Image.open(io.BytesIO(z.read(f"assets/minecraft/textures/block/{name}.png"))).convert("RGBA")
    return im.crop((0, 0, 16, 16))


def save_tex(name, im):
    p = os.path.join(OUT, "textures", "block", name + ".png")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    im.save(p)


def save_json(rel, data):
    p = os.path.join(OUT, *rel.split("/"))
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)


def el(frm, to, tex, faces=None, rot=None, uv=None):
    """One model element; tex is a texture key (#name). faces limits the faces drawn."""
    e = {"from": list(frm), "to": list(to), "faces": {}}
    for f in faces or ("north", "south", "east", "west", "up", "down"):
        e["faces"][f] = {"texture": tex}
        if uv and f in uv:
            e["faces"][f]["uv"] = uv[f]
    if rot:
        e["rotation"] = rot
    return e


def model(name, textures, elements, facing=True, ao=False):
    textures = dict(textures)
    textures.setdefault("particle", next(iter(textures.values())))
    save_json(f"models/block/{name}.json", {"textures": textures, "elements": elements, "ambientocclusion": ao})
    if facing:
        variants = {f"facing={f}": ({"model": f"{NS}:block/{name}", "y": y} if y else {"model": f"{NS}:block/{name}"})
                    for f, y in FACING_Y.items()}
    else:
        variants = {"": {"model": f"{NS}:block/{name}"}}
    save_json(f"blockstates/{name}.json", {"variants": variants})


# ------------------------------------------------------------------ textures
def textures():
    water = vanilla_png("water_still")
    px = water.load()
    for y in range(16):
        for x in range(16):
            r, g, b, a = px[x, y]
            px[x, y] = (r * 0x3F // 255, g * 0x76 // 255, b * 0xE4 // 255, 205)
    save_tex("sq_water", water)

    board = vanilla_png("spruce_planks")
    bp = board.load()
    notes = [((3, 3, 7, 8), (236, 230, 208)), ((8, 2, 13, 6), (226, 218, 190)), ((9, 8, 13, 13), (240, 236, 222)),
             ((3, 10, 7, 13), (214, 196, 160))]
    for (x0, y0, x1, y1), c in notes:
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                bp[x, y] = c + (255,)
        for y in range(y0 + 2, y1, 2):              # lines of writing
            for x in range(x0 + 1, x1):
                if (x + y) % 3:
                    bp[x, y] = (110, 104, 96, 255)
        bp[(x0 + x1) // 2, y0] = (190, 40, 40, 255)  # the pin
    for x in range(1, 15):
        bp[x, 0] = bp[x, 15] = (58, 40, 24, 255)
    save_tex("sq_notice", board)

    sign = vanilla_png("spruce_planks")
    sp = sign.load()
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                sp[x, y] = (52, 36, 22, 255)
    # a small gold star on the sign: the square's mark
    for (x, y) in [(7, 4), (8, 4), (7, 5), (8, 5), (5, 6), (6, 6), (7, 6), (8, 6), (9, 6), (10, 6), (6, 7), (7, 7),
                   (8, 7), (9, 7), (6, 8), (9, 8), (5, 9), (10, 9)]:
        sp[x, y] = (232, 186, 52, 255)
    for x in range(4, 12):
        sp[x, 12] = (90, 66, 44, 255)
    save_tex("sq_sign", sign)

    leaves = vanilla_png("oak_leaves")
    lp = leaves.load()
    palette = [(214, 120, 40), (190, 72, 34), (226, 168, 52), (160, 64, 30)]
    for y in range(16):
        for x in range(16):
            r, g, b, a = lp[x, y]
            if a == 0:
                continue
            c = palette[((x * 7 + y * 13) ^ (x * y)) % 4]
            lum = (r + g + b) / (3 * 255)
            k = 0.55 + 0.75 * lum
            lp[x, y] = (min(255, int(c[0] * k)), min(255, int(c[1] * k)), min(255, int(c[2] * k)), 255)
    save_tex("sq_autumn_leaves", leaves)


# ------------------------------------------------------------------ models (front = north, as vanilla)
def water():
    model("sq_water", {"w": f"{NS}:block/sq_water"}, [el((0, 0, 0), (16, 7, 16), "#w", faces=("up",))],
          facing=False)


def autumn_leaves():
    model("sq_autumn_leaves", {"l": f"{NS}:block/sq_autumn_leaves"}, [el((0, 0, 0), (16, 16, 16), "#l")],
          facing=False)


def hut_notice():
    """Hut block option A: a notice board, one block wide and one and a half high, with a small roof."""
    t = {"post": "minecraft:block/stripped_spruce_log", "board": f"{NS}:block/sq_notice",
         "roof": "minecraft:block/dark_oak_planks", "base": "minecraft:block/stone_bricks"}
    model("sq_hut_notice", t, [
        el((0, 0, 3), (16, 2, 13), "#base"),
        el((1, 2, 7), (3, 22, 9), "#post"), el((13, 2, 7), (15, 22, 9), "#post"),
        el((2, 8, 7.25), (14, 20, 8.75), "#board", uv={"north": [2, 2, 14, 14], "south": [2, 2, 14, 14]}),
        el((-1, 21, 4), (17, 23, 12), "#roof"), el((0, 23, 5.5), (16, 24.5, 10.5), "#roof"),
    ])


def hut_well():
    """Hut block option B: a small well (stone rim, water, two posts, roof and a bucket on a rope)."""
    t = {"rim": "minecraft:block/cobblestone", "water": f"{NS}:block/sq_water", "post": "minecraft:block/spruce_log",
         "roof": "minecraft:block/spruce_planks", "iron": "minecraft:block/iron_block", "rope": "minecraft:block/white_wool"}
    model("sq_hut_well", t, [
        el((0, 0, 0), (16, 8, 2), "#rim"), el((0, 0, 14), (16, 8, 16), "#rim"),
        el((0, 0, 2), (2, 8, 14), "#rim"), el((14, 0, 2), (16, 8, 14), "#rim"),
        el((2, 6, 2), (14, 6.5, 14), "#water", faces=("up",)),
        el((0.5, 8, 7), (2.5, 22, 9), "#post"), el((13.5, 8, 7), (15.5, 22, 9), "#post"),
        el((0.5, 19, 7.25), (15.5, 20.5, 8.75), "#post"),
        el((-1, 22, 2), (17, 23.5, 14), "#roof"), el((1, 23.5, 4), (15, 25, 12), "#roof"),
        el((7.6, 14, 7.6), (8.4, 19, 8.4), "#rope"),
        el((6.5, 11, 6.5), (9.5, 14, 9.5), "#iron"),
    ])


def hut_lamp():
    """Hut block option C: the foot of a lamp post with a sign; a fence and a lantern go on top as normal blocks."""
    t = {"base": "minecraft:block/stone_bricks", "cap": "minecraft:block/chiseled_stone_bricks",
         "post": "minecraft:block/dark_oak_log", "sign": f"{NS}:block/sq_sign", "chain": "minecraft:block/chain"}
    model("sq_hut_lamp", t, [
        el((2, 0, 2), (14, 3, 14), "#base"), el((3, 3, 3), (13, 5, 13), "#cap"),
        el((6, 5, 6), (10, 16, 10), "#post"),
        el((7, 14, 1), (9, 15.5, 6), "#post"),
        el((7.5, 12, 2.5), (8.5, 14, 3.5), "#chain"),
        el((2, 5, 2.25), (14, 12, 3.75), "#sign"),
    ])


def hut_fountain():
    """Hut block option D: the fountain's centre (a pedestal with a bowl); the basin round it is normal blocks."""
    t = {"ped": "minecraft:block/chiseled_stone_bricks", "bowl": "minecraft:block/smooth_stone",
         "water": f"{NS}:block/sq_water", "top": "minecraft:block/polished_andesite"}
    model("sq_hut_fountain", t, [
        el((4, 0, 4), (12, 10, 12), "#ped"),
        el((1, 10, 1), (15, 13, 15), "#bowl", faces=("north", "south", "east", "west", "down")),
        el((1, 12.9, 1), (15, 13, 2), "#bowl", faces=("up",)), el((1, 12.9, 14), (15, 13, 15), "#bowl", faces=("up",)),
        el((1, 12.9, 2), (2, 13, 14), "#bowl", faces=("up",)), el((14, 12.9, 2), (15, 13, 14), "#bowl", faces=("up",)),
        el((2, 12.5, 2), (14, 12.6, 14), "#water", faces=("up",)),
        el((6.5, 12.5, 6.5), (9.5, 18, 9.5), "#top"), el((5.5, 18, 5.5), (10.5, 19, 10.5), "#top"),
    ])


def snowman_a():
    """Street piece: classic snowman, three snowballs, stick arms, carrot nose, red scarf."""
    t = {"snow": "minecraft:block/snow", "coal": "minecraft:block/coal_block", "carrot": "minecraft:block/orange_terracotta",
         "scarf": "minecraft:block/red_wool", "stick": "minecraft:block/dark_oak_log"}
    e = [
        el((2, 0, 2), (14, 11, 14), "#snow"), el((3, 1, 1), (13, 10, 2), "#snow"), el((3, 1, 14), (13, 10, 15), "#snow"),
        el((1, 1, 3), (2, 10, 13), "#snow"), el((14, 1, 3), (15, 10, 13), "#snow"),
        el((3.5, 11, 3.5), (12.5, 19, 12.5), "#snow"), el((4.5, 12, 2.75), (11.5, 18, 3.5), "#snow"),
        el((5, 19, 5), (11, 25, 11), "#snow"),
        el((4.25, 18.5, 4.25), (11.75, 20, 11.75), "#scarf"), el((9, 13, 3.5), (11, 19, 4.25), "#scarf"),
        el((6.25, 22, 4.5), (7.25, 23, 5), "#coal"), el((8.75, 22, 4.5), (9.75, 23, 5), "#coal"),
        el((7.5, 20.5, 2), (8.5, 21.5, 5), "#carrot"),
        el((7.5, 15.5, 2.25), (8.5, 16.5, 2.75), "#coal"), el((7.5, 13.5, 2.25), (8.5, 14.5, 2.75), "#coal"),
        el((-3, 15, 7.5), (3.5, 16, 8.5), "#stick", rot={"angle": -22.5, "axis": "z", "origin": [3.5, 15.5, 8]}),
        el((12.5, 15, 7.5), (19, 16, 8.5), "#stick", rot={"angle": 22.5, "axis": "z", "origin": [12.5, 15.5, 8]}),
    ]
    model("snowman_a", t, e)


def snowman_b():
    """Street piece: two-ball snowman with a top hat, green scarf, buttons and a broom."""
    t = {"snow": "minecraft:block/snow", "coal": "minecraft:block/coal_block", "carrot": "minecraft:block/orange_terracotta",
         "scarf": "minecraft:block/green_wool", "hat": "minecraft:block/black_wool", "band": "minecraft:block/red_wool",
         "stick": "minecraft:block/stripped_spruce_log", "straw": "minecraft:block/hay_block_side"}
    e = [
        el((1.5, 0, 1.5), (14.5, 13, 14.5), "#snow"), el((2.5, 1, 0.75), (13.5, 12, 1.5), "#snow"),
        el((2.5, 1, 14.5), (13.5, 12, 15.25), "#snow"),
        el((0.75, 1, 2.5), (1.5, 12, 13.5), "#snow"), el((14.5, 1, 2.5), (15.25, 12, 13.5), "#snow"),
        el((4, 13, 4), (12, 21, 12), "#snow"),
        el((3.75, 12.5, 3.75), (12.25, 14, 12.25), "#scarf"), el((4.5, 7, 3), (6.5, 13, 3.75), "#scarf"),
        el((3, 21, 3), (13, 22, 13), "#hat"), el((5, 22, 5), (11, 28, 11), "#hat"),
        el((4.9, 22, 4.9), (11.1, 23.5, 11.1), "#band"),
        el((5.75, 17.5, 3.5), (6.75, 18.5, 4), "#coal"), el((9.25, 17.5, 3.5), (10.25, 18.5, 4), "#coal"),
        el((7.5, 16, 1), (8.5, 17, 4), "#carrot"),
        el((7.5, 9.5, 1.25), (8.5, 10.5, 1.75), "#coal"), el((7.5, 6.5, 0.5), (8.5, 7.5, 1), "#coal"),
        el((15, 0, 7.5), (16, 20, 8.5), "#stick", rot={"angle": -22.5, "axis": "z", "origin": [15.5, 0, 8]}),
        el((14, -1, 6.5), (18, 4, 9.5), "#straw", rot={"angle": -22.5, "axis": "z", "origin": [15.5, 0, 8]}),
    ]
    model("snowman_b", t, e)


def wheel():
    """Hand cart wheel against the south edge of its cell (like an open trapdoor facing north)."""
    t = {"w": "minecraft:block/dark_oak_planks", "hub": "minecraft:block/iron_block"}
    z0, z1 = 14, 16
    e = [el((5, 0, z0), (11, 2, z1), "#w"), el((5, 12, z0), (11, 14, z1), "#w"),
         el((1, 4, z0), (3, 10, z1), "#w"), el((13, 4, z0), (15, 10, z1), "#w"),
         el((3, 2, z0), (5, 4, z1), "#w"), el((11, 2, z0), (13, 4, z1), "#w"),
         el((3, 10, z0), (5, 12, z1), "#w"), el((11, 10, z0), (13, 12, z1), "#w"),
         el((7.25, 2, z0 + 0.5), (8.75, 12, z1 - 0.5), "#w"), el((3, 6.25, z0 + 0.5), (13, 7.75, z1 - 0.5), "#w"),
         el((6.5, 5.5, z0 - 0.5), (9.5, 8.5, z1 + 0.5), "#hub")]
    model("sq_wheel", t, e)


def wheel_els(cx, cy, z0, z1, r=5, tex="#w", hub="#hub"):
    """A spoked wheel in the x-y plane between z0 and z1, centre (cx, cy), radius r (pixels)."""
    return [el((cx - 2.5, cy - r, z0), (cx + 2.5, cy - r + 1.5, z1), tex),
            el((cx - 2.5, cy + r - 1.5, z0), (cx + 2.5, cy + r, z1), tex),
            el((cx - r, cy - 2.5, z0), (cx - r + 1.5, cy + 2.5, z1), tex),
            el((cx + r - 1.5, cy - 2.5, z0), (cx + r, cy + 2.5, z1), tex),
            el((cx - 4, cy - 4, z0), (cx - 2.5, cy - 2.5, z1), tex), el((cx + 2.5, cy - 4, z0), (cx + 4, cy - 2.5, z1), tex),
            el((cx - 4, cy + 2.5, z0), (cx - 2.5, cy + 4, z1), tex), el((cx + 2.5, cy + 2.5, z0), (cx + 4, cy + 4, z1), tex),
            el((cx - 0.5, cy - r + 1.5, z0 + 0.25), (cx + 0.5, cy + r - 1.5, z1 - 0.25), tex),
            el((cx - r + 1.5, cy - 0.5, z0 + 0.25), (cx + r - 1.5, cy + 0.5, z1 - 0.25), tex),
            el((cx - 1.25, cy - 1.25, z0 - 0.5), (cx + 1.25, cy + 1.25, z1 + 0.5), hub)]


def hand_cart():
    """Market stall option C: a hand cart, two blocks long (this cell and the next one east), handles to the west,
    festival goods in the bed and a pole for a lantern (the lantern is a normal block two up, one east)."""
    t = {"p": "minecraft:block/spruce_planks", "w": "minecraft:block/dark_oak_planks", "hub": "minecraft:block/iron_block",
         "log": "minecraft:block/stripped_spruce_log", "red": "colony_seasons:block/gift_red",
         "green": "colony_seasons:block/gift_green", "blue": "colony_seasons:block/gift_blue",
         "orn": "colony_seasons:block/ornament_leaves_0", "wreath": "colony_seasons:block/wreath"}
    e = [el((0, 7, 2), (28, 9, 14), "#p"),
         el((0, 9, 2), (28, 13, 3.5), "#p"), el((0, 9, 12.5), (28, 13, 14), "#p"),
         el((26.5, 9, 3.5), (28, 13, 12.5), "#p"), el((0, 9, 3.5), (1.5, 13, 12.5), "#p"),
         el((13.5, 4.5, 0.5), (15.5, 6.5, 15.5), "#w"),
         el((-10, 9, 3), (0, 10.5, 4.5), "#w"), el((-10, 9, 11.5), (0, 10.5, 13), "#w"),
         el((1, 0, 3), (2.5, 7, 4.5), "#w"), el((1, 0, 11.5), (2.5, 7, 13), "#w"),
         el((2.5, 9, 4), (8.5, 14, 10), "#red"), el((8.5, 9, 7), (13.5, 13, 12), "#green"),
         el((3.5, 14, 5), (7.5, 17, 9), "#blue"),
         el((14, 9, 4), (22, 17, 12), "#orn"),
         el((22.5, 9, 7), (24.5, 32, 9), "#log"),
         el((15, 12, 1.9), (25, 22, 1.9), "#wreath", faces=("north",)),
         el((15, 12, 14.1), (25, 22, 14.1), "#wreath", faces=("south",))]
    e += wheel_els(14.5, 5.5, 14.5, 16.5) + wheel_els(14.5, 5.5, -0.5, 1.5)
    model("sq_hand_cart", t, e)


def lantern_post_a():
    """Street piece: a festive lantern post, white with red bands, a wreath on the front, a lantern on top (the
    lantern is the normal block two up). Two blocks tall."""
    t = {"base": "minecraft:block/stone_bricks", "cap": "minecraft:block/polished_andesite",
         "pole": "minecraft:block/white_concrete", "band": "minecraft:block/red_concrete",
         "green": "minecraft:block/azalea_leaves", "wreath": "colony_seasons:block/wreath"}
    e = [el((3.5, 0, 3.5), (12.5, 2, 12.5), "#base"), el((5, 2, 5), (11, 4, 11), "#cap"),
         el((6.5, 4, 6.5), (9.5, 31, 9.5), "#pole"), el((5.5, 31, 5.5), (10.5, 32, 10.5), "#cap")]
    for y in range(6, 30, 5):
        e.append(el((6.25, y, 6.25), (9.75, y + 2, 9.75), "#band"))
    e += [el((5, 27, 5), (11, 29.5, 11), "#green"),
          el((2.5, 13, 6.1), (13.5, 24, 6.1), "#wreath", faces=("north",))]
    model("sq_lantern_post_a", t, e)


def lantern_post_b():
    """Street piece: a dark crook post wound with an evergreen garland and a red bow; the lantern hangs from the
    crook over the next cell to the front (a normal hanging lantern block)."""
    t = {"base": "minecraft:block/polished_blackstone_bricks", "pole": "minecraft:block/polished_blackstone",
         "green": "minecraft:block/azalea_leaves", "bow": "minecraft:block/red_wool"}
    e = [el((4, 0, 4), (12, 3, 12), "#base"), el((7, 3, 7), (9, 32, 9), "#pole"),
         el((7, 30, -8.5), (9, 32, 7), "#pole"), el((7.25, 27, -9), (8.75, 30, -7.5), "#pole")]
    for i, y in enumerate(range(6, 28, 5)):
        dx = (-1, 0, 1, 0)[i % 4]
        e.append(el((6 + dx * 0.5, y, 6), (10 + dx * 0.5, y + 2.5, 10), "#green"))
    e += [el((6.5, 20, 5.25), (9.5, 22.5, 6), "#bow"), el((5, 20.5, 5.5), (6.5, 22, 6), "#bow"),
          el((9.5, 20.5, 5.5), (11, 22, 6), "#bow"), el((7.25, 17.5, 5.5), (8.75, 20, 6), "#bow")]
    model("sq_lantern_post_b", t, e)


def main():
    textures()
    hand_cart()
    lantern_post_a()
    lantern_post_b()
    water()
    autumn_leaves()
    hut_notice()
    hut_well()
    hut_lamp()
    hut_fountain()
    snowman_a()
    snowman_b()
    wheel()
    print("draft models written to", OUT)


if __name__ == "__main__":
    main()
