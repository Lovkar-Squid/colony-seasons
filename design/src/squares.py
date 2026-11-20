"""Town Square picture round (0.2.0, chunk Q0, docs/town-square-design.md section 5 and the wand addendum).

Voxel scenes of the square in three looks and five levels, the hut block options, seasonal touches, a night view,
the street pieces for the marking wand and the Trade Post market stalls. Renders with the design renderer
(tools/mcmodel.py + tools/render_scene.py, Blender headless), one Blender process at a time.

    python square_standins.py                 (draft models for blocks that do not exist yet)
    python squares.py [looks levels hutblock seasons night street stalls] [--sheets]

Writes design/square/*.png: one picture per scene (1300 px wide) and the contact sheets sheet-*.png.
Coordinates as everywhere in design/: x east, y up, z south, the paving's top at y = 0, the square's centre (the
`tree` cell) at (0, 1, 0). The square is 21 x 21 (x and z from -10 to 10), the size recommended in section 5 (2B).
Blocks: vanilla 1.21.1, the mod's shipped blocks (namespace colony_seasons, read from resources/assets) and the
draft models of square_standins.py (namespace colonyseasons, names start with sq_ or snowman_).
"""
import math
import os
import re
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DESIGN = os.path.dirname(HERE)
ROOT = os.path.dirname(DESIGN)
OUT = os.path.join(DESIGN, "square")
SCRATCH = os.environ.get("CS_SCRATCH") or os.path.join(tempfile.gettempdir(), "cs_square")
ASSETS = os.path.join(SCRATCH, "assets")
os.environ["CS_SCRATCH"] = SCRATCH
os.environ["CS_ASSETS"] = ASSETS
os.environ.setdefault("MC_TEX_DIR", os.path.join(SCRATCH, "tex"))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(DESIGN, "tools"))
import importlib.util  # noqa: E402
sys.path.append(os.path.join(ROOT, "tools"))     # for the repo voxel's own nbt import (appended: design/tools wins)
_spec = importlib.util.spec_from_file_location("repo_voxel", os.path.join(ROOT, "tools", "voxel.py"))
voxel = importlib.util.module_from_spec(_spec)   # the repo's voxel.py: reads blueprints and turns them
_spec.loader.exec_module(voxel)
from common import h01  # noqa: E402

NS = "colony_seasons"
D = "colonyseasons"          # draft models (square_standins.py)
PIECES = os.path.join(ROOT, "resources", "blueprints", "colony_seasons", "colony_seasons", "pieces")
SKY_DAY = [0.80, 0.85, 0.90]
SKY_NIGHT = [0.035, 0.045, 0.10]
H = 10                       # half size of the square: 21 x 21

import glob  # noqa: E402
LIGHTS_JAVA = glob.glob(os.path.join(ROOT, "src", "**", "block", "Lights.java"), recursive=True)[0]
with open(LIGHTS_JAVA, encoding="utf-8") as _f:
    LIGHT = {k.lower(): int(v) for k, v in re.findall(r"public static final int ([A-Z_]+) = (\d+);", _f.read())}
VANILLA_LIGHT = {"lantern": 15, "soul_lantern": 10, "torch": 14, "wall_torch": 14, "glowstone": 15,
                 "sea_lantern": 15, "jack_o_lantern": 15, "campfire": 15, "candle": 3}

DIRS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
OPP = {"north": "south", "south": "north", "east": "west", "west": "east"}


def merge_assets():
    """The renderer reads one assets folder: the mod's own blocks plus the draft models."""
    if os.path.isdir(ASSETS):
        shutil.rmtree(ASSETS)
    shutil.copytree(os.path.join(ROOT, "resources", "assets", NS), os.path.join(ASSETS, NS))
    shutil.copytree(os.path.join(HERE, "square_assets", D), os.path.join(ASSETS, D))


def emission(name, props):
    ns, short = name.split(":", 1)
    if ns == NS:
        return LIGHT.get(short, 0) if props.get("lit") == "true" else 0
    if short == "candle":
        return 3 * int(props.get("candles", "1")) if props.get("lit") == "true" else 0
    return VANILLA_LIGHT.get(short, 0)


# ------------------------------------------------------------------ looks
LOOKS = {
    "A": dict(name="Village plaza", wood="spruce", ring="minecraft:dirt_path", wall="minecraft:cobblestone_wall",
              pad="minecraft:spruce_planks", edge=None),
    "B": dict(name="Market square", wood="oak", ring="minecraft:coarse_dirt", wall="minecraft:spruce_fence",
              pad="minecraft:stripped_spruce_wood[axis=y]", edge="minecraft:stripped_spruce_log"),
    "C": dict(name="Formal plaza", wood="dark_oak", ring="minecraft:bricks", wall="minecraft:brick_wall",
              pad="minecraft:smooth_stone", edge="minecraft:bricks"),
}


def pave(look, x, z):
    k = h01(x, 0, z, 1)
    if look == "A":
        return ("minecraft:stone_bricks" if k < 0.45 else "minecraft:cobblestone" if k < 0.68
                else "minecraft:andesite" if k < 0.88 else "minecraft:cracked_stone_bricks" if k < 0.95
                else "minecraft:mossy_cobblestone")
    if look == "B":
        return ("minecraft:mud_bricks" if k < 0.42 else "minecraft:packed_mud" if k < 0.68
                else "minecraft:bricks" if k < 0.80 else "minecraft:coarse_dirt" if k < 0.92 else "minecraft:gravel")
    # C: light cross on the axes, checker of polished andesite and andesite, a diorite ring
    r = max(abs(x), abs(z))
    if abs(x) <= 1 or abs(z) <= 1:
        return "minecraft:polished_diorite"
    if r in (6,):
        return "minecraft:smooth_stone"
    return "minecraft:polished_andesite" if (x + z) % 2 == 0 else "minecraft:andesite"


def paved_cells(level):
    """(x, z) -> 'pave' | 'ring' for the paving at a level; the rest of the 21 x 21 box is grass."""
    cells = {}
    for x in range(-H, H + 1):
        for z in range(-H, H + 1):
            d = math.hypot(x, z)
            entrance = (abs(x) <= 1 or abs(z) <= 1)
            if level == 1:
                if d <= 5.5:
                    cells[(x, z)] = "pave"
                elif d <= 6.6 or (abs(x) <= 1 and z > 0):
                    cells[(x, z)] = "ring"
            elif level == 2:
                if d <= 8.4:
                    cells[(x, z)] = "pave"
                elif d <= 9.4 or entrance:
                    cells[(x, z)] = "ring"
            elif level in (3, 4):
                if max(abs(x), abs(z)) <= H - 1 and (d <= 10.2 or entrance):
                    cells[(x, z)] = "pave"
                elif max(abs(x), abs(z)) <= H - 1 and d <= 11.6:
                    cells[(x, z)] = "ring"
                elif max(abs(x), abs(z)) == H and entrance:
                    cells[(x, z)] = "pave"
            else:
                cells[(x, z)] = "pave"
    return cells


# ------------------------------------------------------------------ helpers
class Scene:
    def __init__(self):
        self.b = {}

    def set(self, x, y, z, st, force=True):
        if force or (x, y, z) not in self.b:
            self.b[(x, y, z)] = st

    def free(self, x, y, z):
        return (x, y, z) not in self.b

    def ground(self, r=12, snow=True, pave_snow=False):
        """Grass and dirt under everything; snow layers on open grass (and a little on the paving)."""
        for x in range(-r, r + 1):
            for z in range(-r, r + 1):
                self.set(x, -1, z, "minecraft:dirt", force=False)
                self.set(x, 0, z, "minecraft:grass_block", force=False)

    def snow(self, r=12, paving=0.12, holes=0.06):
        for x in range(-r, r + 1):
            for z in range(-r, r + 1):
                if not self.free(x, 1, z):
                    continue
                top = self.b.get((x, 0, z), "")
                if top.startswith("minecraft:grass_block"):
                    if h01(x // 2, 1, z // 2, 4) >= holes:
                        self.b[(x, 1, z)] = "minecraft:snow[layers=1]"
                        self.b[(x, 0, z)] = "minecraft:grass_block[snowy=true]"
                elif top and "path" not in top and "slab" not in top and h01(x, 2, z, 5) < paving \
                        and max(abs(x), abs(z)) >= 6:
                    self.b[(x, 1, z)] = "minecraft:snow[layers=1]"


def stairs(wood, facing):
    return f"minecraft:{wood}_stairs[facing={facing},half=bottom]"


def bench(s, cells, facing, wood):
    """Stair seats in a row (the back toward `facing`), open trapdoors as arms at both ends."""
    for (x, z) in cells:
        s.set(x, 1, z, stairs(wood, facing))
    xs = sorted(cells)
    (x0, z0), (x1, z1) = xs[0], xs[-1]
    if z0 == z1:
        ends = [((x0 - 1, z0), "west"), ((x1 + 1, z0), "east")]
    else:
        ends = [((x0, z0 - 1), "north"), ((x0, z1 + 1), "south")]
    for (x, z), f in ends:
        s.set(x, 1, z, f"minecraft:{wood}_trapdoor[facing={f},half=bottom,open=true]", force=False)


def lamp_post(s, look, x, z, decor=None, lit=False):
    """A lamp post whose third block is a full block, so wall strings can hang on it (`lamp` tag: the top)."""
    if look == "A":
        col = ["minecraft:cobblestone_wall", "minecraft:spruce_fence", "minecraft:stripped_spruce_log[axis=y]"]
    elif look == "B":
        col = ["minecraft:stripped_spruce_log[axis=y]"] * 3
    else:
        col = ["minecraft:stone_bricks", "minecraft:polished_blackstone_wall", "minecraft:polished_blackstone_bricks"]
    for i, st in enumerate(col):
        s.set(x, 1 + i, z, st)
    s.set(x, 4, z, "minecraft:lantern[hanging=false]")
    if look == "B":                    # a timber arm with a hanging lantern, toward the square's centre
        dx, dz = (-1 if x > 0 else 1), 0
        if abs(z) > abs(x):
            dx, dz = 0, (-1 if z > 0 else 1)
        s.set(x + dx, 3, z + dz, "minecraft:spruce_fence")
        s.set(x + dx, 2, z + dz, "minecraft:lantern[hanging=true]")
    if decor:
        L = "true" if lit else "false"
        for f, (dx, dz) in DIRS.items():
            if not s.free(x + dx, 3, z + dz):
                continue
            if decor == "garland":
                st = f"{NS}:garland[facing={f}]"
            elif decor == "lantern_string":
                st = f"{NS}:lantern_string[facing={f},lit={L},variant={(x + z + dx + 3) % 3}]"
            else:
                st = f"{NS}:fairy_lights[facing={f},lit={L}]"
            s.set(x + dx, 3, z + dz, st)


def planter(s, look, cells, content, lit=False):
    """A raised bed (`bed` tag): soil one block up, a plank rim of open trapdoors round it, the season's plants."""
    wood = LOOKS[look]["wood"]
    cs = set(cells)
    for (x, z) in cs:
        s.set(x, 1, z, "minecraft:podzol" if content in ("winter", "festival") else "minecraft:grass_block")
    for (x, z) in cs:
        for f, (dx, dz) in DIRS.items():
            n = (x + dx, z + dz)
            if n in cs:
                continue
            s.set(n[0], 1, n[1], f"minecraft:{wood}_trapdoor[facing={f},half=bottom,open=true]", force=False)
    for i, (x, z) in enumerate(sorted(cs)):
        k = h01(x, 3, z, 9)
        if content in ("winter", "festival"):
            if k < 0.55:
                s.set(x, 2, z, "minecraft:spruce_leaves[persistent=true]")
                s.set(x, 3, z, "minecraft:snow[layers=1]")
            elif k < 0.8:
                s.set(x, 2, z, "minecraft:sweet_berry_bush[age=3]")
            else:
                s.set(x, 2, z, "minecraft:snow[layers=2]")
        elif content == "spring":
            fl = ["red_tulip", "pink_tulip", "white_tulip", "orange_tulip", "allium", "cornflower", "dandelion",
                  "lily_of_the_valley", "oxeye_daisy"]
            s.set(x, 2, z, "minecraft:" + fl[int(k * len(fl)) % len(fl)])
        elif content == "summer":
            if k < 0.3:
                s.set(x, 2, z, "minecraft:flowering_azalea")
            elif k < 0.5:
                s.set(x, 2, z, "minecraft:azalea")
            elif k < 0.7:
                s.set(x, 2, z, "minecraft:rose_bush[half=lower]")
                s.set(x, 3, z, "minecraft:rose_bush[half=upper]")
            elif k < 0.85:
                s.set(x, 2, z, "minecraft:fern")
            else:
                s.set(x, 2, z, "minecraft:blue_orchid")
        elif content == "autumn":
            if k < 0.35:
                s.set(x, 2, z, "minecraft:pumpkin")
            elif k < 0.6:
                s.set(x, 2, z, f"{D}:sq_autumn_leaves")
            elif k < 0.75:
                s.set(x, 2, z, "minecraft:carved_pumpkin[facing=south]")
            elif k < 0.88:
                s.set(x, 2, z, "minecraft:red_mushroom")
            else:
                s.set(x, 2, z, "minecraft:sweet_berry_bush[age=2]")


def hut_piece(s, kind, x, z, facing="south"):
    """The square's hut block (`blockhuttownsquare`, draft models) and what stands round it."""
    if kind == "notice":
        s.set(x, 1, z, f"{D}:sq_hut_notice[facing={facing}]")
    elif kind == "well":
        s.set(x, 1, z, f"{D}:sq_hut_well[facing={facing}]")
    elif kind == "lamp":
        s.set(x, 1, z, f"{D}:sq_hut_lamp[facing={facing}]")
        s.set(x, 2, z, "minecraft:dark_oak_fence")
        s.set(x, 3, z, "minecraft:lantern[hanging=false]")
    elif kind == "fountain":
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                if max(abs(dx), abs(dz)) == 2:
                    s.set(x + dx, 1, z + dz, "minecraft:smooth_stone_slab[type=bottom]")
                elif (dx, dz) != (0, 0):
                    s.set(x + dx, 0, z + dz, "minecraft:stone_bricks")
                    s.set(x + dx, 1, z + dz, f"{D}:sq_water")
        s.set(x, 1, z, f"{D}:sq_hut_fountain[facing={facing}]")


# ------------------------------------------------------------------ stalls (Trade Post, look B's pads)
def _frame(px, pz, front):
    """Local (u across, v depth, 0 = back) to world for a 3 x 2 stall pad whose front faces `front`."""
    fx, fz = DIRS[front]
    rx, rz = -fz, fx                  # across the front (right hand when looking out)

    def at(u, v):
        return (px + rx * (u - 1) + fx * v, pz + rz * (u - 1) + fz * v)
    return at


def stall(s, kind, px, pz, front, goods=True, colours=("red", "white"), lit=False):
    """A market stall on a 3 x 2 pad; (px, pz) is the back middle cell, the front row faces `front`.
    kind: awning (striped festival awning on timber posts), timber (plain timber stall), cart (hand cart)."""
    at = _frame(px, pz, front)
    L = "true" if lit else "false"
    side = {"north": "east", "south": "west", "east": "south", "west": "north"}[front]
    if kind == "awning" or kind == "timber":
        post = "minecraft:spruce_fence"
        for u in (0, 2):
            x, z = at(u, 0)
            s.set(x, 1, z, post); s.set(x, 2, z, post)
        for u in range(3):
            x, z = at(u, 1)
            s.set(x, 1, z, "minecraft:spruce_planks" if kind == "awning" else "minecraft:barrel[facing=up]")
        for u in (0, 2):
            x, z = at(u, 1)
            s.set(x, 2, z, post)
        if kind == "awning":
            for u in range(3):
                for v in range(3):
                    x, z = at(u, v)
                    c = colours[u % 2]
                    if v < 2:
                        s.set(x, 3, z, f"minecraft:{c}_wool")
                    else:
                        s.set(x, 3, z, f"minecraft:{c}_carpet")
            if goods:
                for u in range(3):
                    x, z = at(u, 2)
                    s.set(x, 2, z, f"{NS}:lantern_string[facing={front},lit={L},variant={u % 3}]")
        else:
            for u in range(3):
                for v in range(3):
                    x, z = at(u, v)
                    s.set(x, 3, z, "minecraft:spruce_slab[type=bottom]" if v < 2 else
                          "minecraft:spruce_trapdoor[facing=%s,half=top,open=false]" % front)
        if goods:
            x, z = at(1, 2)
            s.set(x, 1, z, f"{NS}:wreath[facing={front}]")
            x, z = at(1, 1)
            if kind == "awning":
                s.set(x, 2, z, f"{NS}:tree_star[lit={L}]")
            else:
                s.set(x, 2, z, f"{NS}:ornament_leaves[lit={L}]")
            x, z = at(1, 0)
            s.set(x, 1, z, "minecraft:barrel[facing=up]")
            s.set(x, 2, z, f"{NS}:tinsel_leaves[lit={L}]" if kind == "awning" else "minecraft:barrel[facing=up]")
            if kind == "timber":
                x, z = at(-1, 1)
                s.set(x, 1, z, "minecraft:hay_block[axis=y]", force=False)
                x, z = at(3, 1)
                s.set(x, 1, z, "minecraft:barrel[facing=%s]" % front, force=False)
                s.set(x, 2, z, "minecraft:lantern[hanging=false]", force=False)
    elif kind == "cart":
        # draft model, two blocks long: (px, pz) and the next cell to its west (facing south), lantern on its pole
        s.set(px, 1, pz, f"{D}:sq_hand_cart[facing={front}]")
        if goods:
            ex, ez = {"north": (1, 0), "east": (0, 1), "south": (-1, 0), "west": (0, -1)}[front]
            s.set(px + ex, 3, pz + ez, "minecraft:lantern[hanging=false]")


# ------------------------------------------------------------------ the square
TREE_BY_LEVEL = {1: "village_spruce_small", 2: "village_spruce_small", 3: "village_spruce_full",
                 4: "village_spruce_rich", 5: "grand_tree"}
POSTS_L2 = [(6, 6), (-6, 6), (6, -6), (-6, -6)]
POSTS_L4 = [(8, 2), (8, -2), (-8, 2), (-8, -2)]
HUT = (2, 8)
BENCHES = {  # level: [(cells, facing)]
    1: [([(3, -7), (4, -7), (5, -7)], "north"), ([(-7, 3), (-7, 4), (-7, 5)], "west")],
    3: [([(7, -3), (7, -4), (7, -5)], "east"), ([(-3, 7), (-4, 7), (-5, 7)], "south"),
        ([(-3, -7), (-4, -7), (-5, -7)], "north"), ([(-7, -3), (-7, -4), (-7, -5)], "west")],
    5: [([(7, 3), (7, 4), (7, 5)], "east"), ([(4, 7), (5, 7), (6, 7)], "south")],
}
PLANTERS = {
    3: [[(8, -3), (8, -4), (8, -5), (9, -3), (9, -4), (9, -5)], [(3, -8), (4, -8), (5, -8), (3, -9), (4, -9), (5, -9)],
        [(-8, 3), (-8, 4), (-8, 5), (-9, 3), (-9, 4), (-9, 5)], [(8, 8), (8, 9), (9, 8), (9, 9)]],
    5: [[(8, -8), (8, -9), (9, -8), (9, -9)], [(-8, -8), (-8, -9), (-9, -8), (-9, -9)]],
}
STALLS = {  # level: [(back middle cell, front)]
    4: [((9, 4), "west"), ((-9, -4), "east")],
    5: [((-4, -9), "south"), ((-4, 9), "north")],
}
CORNER = (-8, 8)             # level 5: bandstand (A), market hall (B) or fountain (C), 5 x 5 round this cell


def tree_piece(s, name, lit):
    st, _ = voxel.load_blueprint(os.path.join(PIECES, name + ".blueprint"))
    st = st.rotated_180()
    ax, ay, az = st.anchor
    L = "true" if lit else "false"
    for (x, y, z), b in st.blocks.items():
        if b.startswith("structurize:"):
            continue
        b = b.replace("lit=false", f"lit={L}").replace("lit=true", f"lit={L}")
        s.set(x - ax, y - ay + 1, z - az, b)
    s.set(0, 1, 0, "minecraft:spruce_log[axis=y]")      # the `tree` cell: the trunk's foot


def corner_feature(s, look, winter, lit):
    cx, cz = CORNER
    L = "true" if lit else "false"
    cells = [(cx + dx, cz + dz) for dx in range(-2, 3) for dz in range(-2, 3)]
    if look in ("A", "B"):
        deck = "minecraft:spruce_slab[type=top]" if look == "A" else "minecraft:oak_slab[type=bottom]"
        post = "minecraft:stripped_spruce_log[axis=y]" if look == "A" else "minecraft:stripped_oak_log[axis=y]"
        roof = "dark_oak" if look == "A" else "spruce"
        for (x, z) in cells:
            s.set(x, 1, z, deck)
        for dx, dz in ((-2, -2), (2, -2), (-2, 2), (2, 2)):
            for y in range(1, 4 if look == "A" else 4):
                s.set(cx + dx, y, cz + dz, post)
        if look == "A":
            for (x, z) in cells:
                if max(abs(x - cx), abs(z - cz)) == 2 and (x - cx) not in (-2, 2) or \
                        max(abs(x - cx), abs(z - cz)) == 2 and (z - cz) not in (-2, 2):
                    if not (x - cx == 2 and abs(z - cz) <= 1):          # the open side faces east, the square
                        s.set(x, 2, z, "minecraft:spruce_fence")
        for (x, z) in cells:
            dx, dz = x - cx, z - cz
            if max(abs(dx), abs(dz)) == 2:
                f = "south" if dz == -2 else "north" if dz == 2 else "east" if dx == -2 else "west"
                s.set(x, 4, z, stairs(roof, f))
            else:
                s.set(x, 4, z, f"minecraft:{roof}_planks")
        for (x, z) in cells:
            dx, dz = x - cx, z - cz
            if max(abs(dx), abs(dz)) == 1:
                f = "south" if dz == -1 else "north" if dz == 1 else "east" if dx == -1 else "west"
                s.set(x, 5, z, stairs(roof, f))
        s.set(cx, 5, cz, f"minecraft:{roof}_slab[type=bottom]")
        for dx, dz in ((-1, -1), (1, 1), (-1, 1), (1, -1)):
            s.set(cx + dx, 3, cz + dz, "minecraft:lantern[hanging=true]")
        if look == "B":
            s.set(cx - 1, 2, cz - 1, "minecraft:barrel[facing=up]")
            s.set(cx - 1, 2, cz, "minecraft:barrel[facing=up]")
            s.set(cx, 2, cz - 1, "minecraft:hay_block[axis=y]")
        if winter:
            for dx in (-2, -1, 0, 1, 2):
                for dz, f in ((-3, "north"), (3, "south")):
                    s.set(cx + dx, 4, cz + dz, f"{NS}:garland[facing={f}]", force=False)
            for dz in (-2, -1, 0, 1, 2):
                s.set(cx + 3, 4, cz + dz, f"{NS}:garland[facing=east]", force=False)
    else:   # C: a fountain, smooth stone rim, water, a pedestal with a bowl in the middle
        for (x, z) in cells:
            dx, dz = x - cx, z - cz
            if max(abs(dx), abs(dz)) == 2:
                s.set(x, 1, z, "minecraft:polished_andesite_slab[type=bottom]" if (dx + dz) % 2 else
                      "minecraft:smooth_stone_slab[type=bottom]")
            else:
                s.set(x, 0, z, "minecraft:stone_bricks")
                s.set(x, 1, z, f"{D}:sq_water")
        s.set(cx, 1, cz, "minecraft:chiseled_stone_bricks")
        s.set(cx, 2, cz, f"{D}:sq_hut_fountain[facing=south]")
        for dx, dz in ((-2, -2), (2, -2), (-2, 2), (2, 2)):
            s.set(cx + dx, 2, cz + dz, "minecraft:lantern[hanging=false]")


def square(look, level, season="festival", hut="notice", lit=False, show_tree=True):
    """The square at a level. season: festival (the winter set), winter, spring, summer, autumn (touches only),
    or None (bare)."""
    s = Scene()
    lk = LOOKS[look]
    winter_ground = season in ("festival", "winter")
    cells = paved_cells(level)
    for (x, z), kind in cells.items():
        s.set(x, 0, z, pave(look, x, z) if kind == "pave" else lk["ring"])
    # roads into the square from the south and the east
    for t in range(H + 1, 13):
        for c in (-1, 0, 1):
            s.set(c, 0, t, "minecraft:dirt_path")
            s.set(t, 0, c, "minecraft:dirt_path")
    # edge: from level 3 a low wall with gaps for the four entrances (C and B also edge the paving)
    if level >= 3:
        for i in range(-H, H + 1):
            for (x, z) in ((i, -H), (i, H), (-H, i), (H, i)):
                if abs(i) <= 1:
                    continue
                if level >= 5 and CORNER[0] - 2 <= x <= CORNER[0] + 2 and CORNER[1] - 2 <= z <= CORNER[1] + 2:
                    continue
                if lk["edge"]:
                    s.set(x, 0, z, lk["edge"] if "log" not in lk["edge"] else
                          lk["edge"] + ("[axis=x]" if abs(z) == H else "[axis=z]"))
                s.set(x, 1, z, lk["wall"])
                if look == "B" and (abs(i) % 4 == 2 or abs(i) == H):
                    s.set(x, 1, z, "minecraft:stripped_spruce_log[axis=y]")
    if level >= 5:
        corner_feature(s, look, season == "festival", lit)
    # stall pads (and look B's awning frames)
    for lv in (4, 5):
        if level < lv:
            continue
        for (px, pz), front in STALLS[lv]:
            at = _frame(px, pz, front)
            for u in range(3):
                for v in range(2):
                    x, z = at(u, v)
                    s.set(x, 0, z, lk["pad"])
            if look == "B":
                stall(s, "awning", px, pz, front, goods=False, colours=("red", "white"), lit=lit)
    # hut block piece
    hut_piece(s, hut, *HUT)
    # benches, planters, lamp posts
    for lv, rows in BENCHES.items():
        if level >= lv:
            for cells_, f in rows:
                bench(s, cells_, f, lk["wood"])
    for lv, beds in PLANTERS.items():
        if level >= lv:
            for bed in beds:
                planter(s, look, bed, season or "none", lit)
    posts = (POSTS_L2 if level >= 2 else []) + (POSTS_L4 if level >= 4 else [])
    if level >= 5:
        posts = [p for p in posts if p != (-6, 6)]
    for (x, z) in posts:
        decor = None
        if season == "festival":
            decor = {2: "garland", 3: "lantern_string"}.get(level, "lantern_string" if (x, z) in POSTS_L2 else "fairy_lights")
        lamp_post(s, look, x, z, decor, lit)
    # the set at the `tree` cell
    if season == "festival" and show_tree:
        tree_piece(s, TREE_BY_LEVEL[level], lit)
    elif season == "autumn":
        # small touches: a harvest corner by the hut block and pumpkins at the posts
        s.set(HUT[0] + 2, 1, HUT[1], "minecraft:hay_block[axis=y]", force=False)
        s.set(HUT[0] + 2, 2, HUT[1], "minecraft:pumpkin", force=False)
        s.set(HUT[0] + 3, 1, HUT[1], "minecraft:carved_pumpkin[facing=south]", force=False)
        for (x, z) in posts:
            s.set(x + (1 if x < 0 else -1), 1, z, "minecraft:pumpkin", force=False)
    elif season in ("spring", "summer"):
        pots = (["potted_red_tulip", "potted_allium", "potted_cornflower"] if season == "spring" else
                ["potted_azalea_bush", "potted_fern", "potted_flowering_azalea_bush"])
        for i, (x, z) in enumerate(posts):
            s.set(x + (1 if x < 0 else -1), 1, z, "minecraft:" + pots[i % 3], force=False)
    s.ground()
    if winter_ground:
        s.snow()
    return s.b


# ------------------------------------------------------------------ small scenes
def patch(s, r, look="A", snow=True, road=False):
    for x in range(-r, r + 1):
        for z in range(-r, r + 1):
            if road and abs(z - 2) <= 1:
                s.set(x, 0, z, "minecraft:dirt_path", force=False)
            else:
                s.set(x, 0, z, pave(look, x, z) if max(abs(x), abs(z)) <= r - 1 or not road else
                      "minecraft:grass_block", force=False)
            s.set(x, -1, z, "minecraft:dirt", force=False)


def scale_post(s, x, z):
    """Door-height reference, as in every design picture: two spruce fences and a lantern."""
    s.set(x, 1, z, "minecraft:spruce_fence")
    s.set(x, 2, z, "minecraft:spruce_fence")
    s.set(x, 3, z, "minecraft:lantern[hanging=false]")


def hutblock_scene(kind):
    s = Scene()
    hut_piece(s, kind, 0, 0, facing="south")
    scale_post(s, -3, 2)
    patch(s, 5)
    s.snow(r=5, paving=0.1, holes=0)
    return s.b


def street_scene(kind, lit=False):
    """A snowy verge beside a village road (the road runs east-west, south of the piece)."""
    s = Scene()
    L = "true" if lit else "false"
    if kind in ("snowman_a", "snowman_b"):
        s.set(0, 1, 0, f"{D}:{kind}[facing=south]")
    elif kind == "snowman_c":
        s.set(0, 1, 0, "minecraft:snow_block")
        s.set(0, 2, 0, "minecraft:snow_block")
        s.set(0, 3, 0, "minecraft:carved_pumpkin[facing=south]")
    elif kind == "lantern_post_a":
        s.set(0, 1, 0, f"{D}:sq_lantern_post_a[facing=south]")
        s.set(0, 3, 0, "minecraft:lantern[hanging=false]")
    elif kind == "lantern_post_b":
        s.set(0, 1, 0, f"{D}:sq_lantern_post_b[facing=south]")
        s.set(0, 2, 1, "minecraft:lantern[hanging=true]")
    elif kind == "street":
        # the pieces along one road, as the wand would mark them
        s.set(-5, 1, 0, f"{D}:snowman_a[facing=south]")
        s.set(-2, 1, 0, f"{D}:sq_lantern_post_a[facing=south]")
        s.set(-2, 3, 0, "minecraft:lantern[hanging=false]")
        s.set(4, 1, 0, f"{D}:sq_lantern_post_a[facing=south]")
        s.set(4, 3, 0, "minecraft:lantern[hanging=false]")
        s.set(1, 1, 0, f"{D}:snowman_b[facing=south]")
        s.set(1, 1, 5, f"{D}:sq_lantern_post_b[facing=north]")
        s.set(1, 2, 4, "minecraft:lantern[hanging=true]")
        s.set(-4, 1, 5, "minecraft:snow_block")
        s.set(-4, 2, 5, "minecraft:snow_block")
        s.set(-4, 3, 5, "minecraft:carved_pumpkin[facing=north]")
        s.set(6, 1, 5, f"{D}:snowman_a[facing=north]")
    if kind != "street":
        scale_post(s, -3, 1)
    r = 7 if kind == "street" else 4
    for x in range(-r, r + 1):
        for z in range(-3, 7 if kind == "street" else 6):
            s.set(x, -1, z, "minecraft:dirt", force=False)
            s.set(x, 0, z, "minecraft:dirt_path" if 2 <= z <= 3 else "minecraft:grass_block", force=False)
    s.snow(r=r + 1, paving=0.0, holes=0)
    return s.b


def stalls_scene(kind, lit=False):
    s = Scene()
    if kind == "awning":
        stall(s, "awning", -2, -1, "south", colours=("red", "white"), lit=lit)
        stall(s, "awning", 3, -1, "south", colours=("green", "white"), lit=lit)
    elif kind == "timber":
        stall(s, "timber", -2, -1, "south", lit=lit)
        stall(s, "timber", 3, -1, "south", lit=lit)
    else:
        stall(s, "cart", -2, -1, "south", lit=lit)
        stall(s, "cart", 3, -1, "south", lit=lit)
    if kind == "cart":
        for x0 in (-2, 3):
            s.set(x0 + 1, 1, -2, "minecraft:barrel[facing=up]")
            s.set(x0 - 3, 1, -1, "minecraft:hay_block[axis=y]")
            if x0 > 0:
                s.set(x0 - 3, 2, -1, "minecraft:pumpkin")
    scale_post(s, -5, 2)
    for x in range(-6, 7):
        for z in range(-4, 5):
            s.set(x, -1, z, "minecraft:dirt", force=False)
            s.set(x, 0, z, pave("A", x, z), force=False)
    s.snow(r=7, paving=0.08, holes=0)
    return s.b


# ------------------------------------------------------------------ cameras and jobs
CAM_SQUARE = dict(yaw=32, pitch=30, ortho=33.0, target=[0.4, 4.6, 0.8], res=[1300, 1050])
CAM_CLOSE = dict(yaw=32, pitch=22, ortho=6.6, target=[-0.9, 1.4, 0.4], res=[1100, 900])
CAM_STREET = dict(yaw=28, pitch=18, ortho=7.0, target=[-0.9, 2.3, 1.0], res=[1100, 1000])
CAM_STREET_WIDE = dict(yaw=24, pitch=22, ortho=13.0, target=[0.3, 1.8, 2.5], res=[1100, 1000])
CAM_SEASON = dict(yaw=32, pitch=30, ortho=28.5, target=[0.4, 1.2, 0.8], res=[1300, 1000])
CAM_STALL = dict(yaw=28, pitch=24, ortho=13.0, target=[0.5, 1.8, 0.4], res=[1200, 900])


def png(name):
    return os.path.join(OUT, name + ".png")


def job(name, blocks, cam, night=False, samples=24):
    return dict(blocks=blocks, out=png(name), bg=SKY_NIGHT if night else SKY_DAY, samples=samples,
                night=emission if night else None, **cam)


RECOMMENDED = "A"
SEASON_LABELS = {"spring": "Spring: flowers", "summer": "Summer: azalea, roses, ferns",
                 "autumn": "Autumn: pumpkins, leaves, harvest corner", "winter": "Winter: evergreen boughs, berries"}


def jobs_for(group):
    if group == "looks":
        return [job(f"square-{lk}-l{lv}", square(lk, lv), CAM_SQUARE) for lv in (5, 1) for lk in "ABC"]
    if group == "levels":
        return [job(f"square-{RECOMMENDED}-l{lv}", square(RECOMMENDED, lv), CAM_SQUARE) for lv in (2, 3, 4)]
    if group == "hutblock":
        return [job(f"hutblock-{k}", hutblock_scene(k), CAM_CLOSE) for k in ("notice", "well", "lamp", "fountain")]
    if group == "seasons":
        return [job(f"seasons-{RECOMMENDED}-{se}", square(RECOMMENDED, 3, season=se), CAM_SEASON)
                for se in ("spring", "summer", "autumn", "winter")]
    if group == "night":
        return [job("square-night", square(RECOMMENDED, 5, lit=True), CAM_SQUARE, night=True, samples=32)]
    if group == "street":
        return ([job(f"street-{k}", street_scene(k), CAM_STREET) for k in ("snowman_a", "snowman_b", "snowman_c",
                                                                          "lantern_post_a", "lantern_post_b")]
                + [job("street-all", street_scene("street"), CAM_STREET_WIDE),
                   job("street-all-night", street_scene("street", lit=True), CAM_STREET_WIDE, night=True, samples=32)])
    if group == "stalls":
        return [job(f"stalls-{k}", stalls_scene(k), CAM_STALL) for k in ("awning", "timber", "cart")]
    raise SystemExit("unknown group " + group)


# ------------------------------------------------------------------ sheets
def font(size):
    from PIL import ImageFont
    for f in ("C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def grid_sheet(name, title, cells, cols, max_w=2000, gap=16, label_h=52, note=None):
    """cells: [(png name or None, label)]; a plain grid, labels under each picture, a title line on top."""
    from PIL import Image, ImageDraw
    ims = [(Image.open(png(n)).convert("RGB") if n else None, lab) for n, lab in cells]
    cw = (max_w - gap * (cols + 1)) // cols
    first = next(im for im, _ in ims if im)
    ch = round(first.height * cw / first.width)
    rows = (len(ims) + cols - 1) // cols
    top = 60
    H_ = top + rows * (ch + label_h + gap) + gap + (40 if note else 0)
    out = Image.new("RGB", (gap * (cols + 1) + cw * cols, H_), (236, 236, 232))
    d = ImageDraw.Draw(out)
    d.text((gap, 12), title, fill=(30, 30, 30), font=font(32))
    f = font(24)
    for i, (im, lab) in enumerate(ims):
        x = gap + (i % cols) * (cw + gap)
        y = top + (i // cols) * (ch + label_h + gap)
        if im is not None:
            im = im.resize((cw, round(im.height * cw / im.width)), Image.LANCZOS)
            out.paste(im.crop((0, 0, cw, min(ch, im.height))), (x, y))
        lines = lab.split("\n")
        for k, line in enumerate(lines):
            tw = d.textlength(line, font=f)
            d.text((x + (cw - tw) / 2, y + ch + 8 + k * 26), line, fill=(34, 34, 34), font=f)
    if note:
        d.text((gap, H_ - 40), note, fill=(70, 70, 70), font=font(20))
    p = png(name)
    out.save(p, optimize=True)
    print("sheet", name, out.size)


def sheets(groups):
    if "looks" in groups:
        cells = [(f"square-{lk}-l5", f"{lk}  {LOOKS[lk]['name']}, level 5") for lk in "ABC"]
        cells += [(f"square-{lk}-l1", f"{lk}  {LOOKS[lk]['name']}, level 1") for lk in "ABC"]
        grid_sheet("sheet-square-looks", "Town Square looks, 21 x 21, winter set (top: level 5, bottom: level 1)",
                   cells, 3, note="Hut block: notice board (draft model). Tree: the shipped winter pieces. "
                                  "B's striped awnings belong to its stall pads.")
    if "levels" in groups or "looks" in groups:
        lv = {1: "paved centre, path ring, hut block, 2 benches", 2: "wider paving, 4 lamp posts (garland)",
              3: "low edge wall, 4 planters, 6 benches", 4: "2 stall pads, 8 lamp posts",
              5: "full paving, bandstand corner, 4 stall pads"}
        cells = [(f"square-{RECOMMENDED}-l{n}", f"Level {n}: {lv[n]}") for n in range(1, 6)]
        if all(os.path.exists(png(c[0])) for c in cells):
            grid_sheet("sheet-square-levels", f"{RECOMMENDED} {LOOKS[RECOMMENDED]['name']}, levels 1 to 5, winter set "
                       "(tree = lower of square and Workshop level)", cells, 3)
    if "hutblock" in groups:
        lab = {"notice": "A  Notice board", "well": "B  Well", "lamp": "C  Lamp post with a sign",
               "fountain": "D  Fountain base"}
        grid_sheet("sheet-square-hutblock", "The square's hut block (draft models; door-height post for scale)",
                   [(f"hutblock-{k}", lab[k]) for k in ("notice", "well", "lamp", "fountain")], 2)
    if "seasons" in groups:
        grid_sheet("sheet-square-seasons", f"Between festivals: seasonal touches, {LOOKS[RECOMMENDED]['name']} level 3",
                   [(f"seasons-{RECOMMENDED}-{se}", SEASON_LABELS[se]) for se in ("spring", "summer", "autumn", "winter")],
                   2, note="Autumn leaves: oak leaves recoloured (stand-in texture).")
    if "street" in groups:
        lab = {"snowman_a": "Snowman A  Classic", "snowman_b": "Snowman B  Top hat, broom",
               "snowman_c": "Snowman C  Pumpkin head", "lantern_post_a": "Post A  Red bands, wreath",
               "lantern_post_b": "Post B  Crook, garland", "all": "Along a street, day", "all-night": "Along a street, night"}
        grid_sheet("sheet-street-pieces", "Street pieces for the marking wand (door-height post for scale)",
                   [(f"street-{k}", lab[k]) for k in ("snowman_a", "snowman_b", "snowman_c", "all",
                                                      "lantern_post_a", "lantern_post_b", "all-night")], 4,
                   note="Snowmen A, B and both lantern posts are draft models (new blocks); snowman C is vanilla "
                        "blocks only (snow blocks and a carved pumpkin).")
    if "stalls" in groups:
        lab = {"awning": "A  Striped awnings (winter colours)", "timber": "B  Plain timber stalls",
               "cart": "C  Hand carts under a lantern (draft model)"}
        grid_sheet("sheet-stalls", "Trade Post market stalls (optional), festival goods on show",
                   [(f"stalls-{k}", lab[k]) for k in ("awning", "timber", "cart")], 3,
                   note="Goods: wreath, lantern string, star, ornament and tinsel leaves (no gifts: they have no "
                        "price). Harvest Fair can reuse A in autumn colours.")


def shrink(path):
    from PIL import Image
    im = Image.open(path).convert("RGB")
    im.save(path, optimize=True, compress_level=9)


ALL = ["looks", "levels", "hutblock", "seasons", "night", "street", "stalls"]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    groups = args or ALL
    os.makedirs(OUT, exist_ok=True)
    if "--sheets" not in sys.argv:
        merge_assets()
        import driver  # noqa: E402  (after CS_ASSETS is set)
        jobs = [j for g in groups for j in jobs_for(g)]
        driver.render_jobs(jobs)
        for j in jobs:
            if os.path.exists(j["out"]):
                shrink(j["out"])
    sheets(groups)


if __name__ == "__main__":
    main()
