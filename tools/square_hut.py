"""The Town Square's blueprints (0.2.0, docs/town-square-design.md 4.1 and 4.2): the owner's look A "Village plaza", 21 x 21,
in five levels (chunk Q6; Q1 shipped one placeholder level). tools/build_pack.py turns square(level) into
square/townsquare1..5.blueprint and checks it (check_square, check_levels).

Coordinates as in design/src/squares.py: x east, y up, z south; the paving is y = 0 and the `tree` cell, the centre of
the square (where the 0.1.0 festival marker stood), is (0, 1, 0). The box is x and z -10..10 and y 0..19 at every level,
so the grand tree (11 x 11, 18 high, standing on the `tree` cell) fits inside it; nothing is drawn in the 11 x 11
column over the `tree` cell. The hut block (the notice board, the owner's pick) stands at (2, 1, 8), where the pictures
had it, off the centre (so a turned square is not the same as an unturned one) and on the paving.

What each level draws is the picked look of design/src/squares.py (square("A", level, season=None)), block for block
above the paving, in vanilla ids only (the picture's draft models, sq_*, are not shipped blocks): level 1 a paved
centre with its ring of path and two benches; 2 wider paving and four lamp posts; 3 a low edge wall, four planters and
four more benches; 4 two stall pads and four more lamp posts; 5 full paving, the bandstand in the corner (-8, 8), two
more planters, two more benches and two more stall pads. The picture's draft hut block is the real hut block, and its
lamp post at (-6, 6) gives way to the bandstand's corner post at level 5, as in the picture.

THE SLOTS ([B] design 4.1: every tag keeps its cell at every level). A tag's cell never moves; higher levels only ADD
cells, so a tag of level N is there, with the same name, at every level above N, and the counts are the table of
design 4.2 (decor/SquareSets.slots). `launch` is one cell at every level (the crate itself is gated by the piece's level
in the Decorator's code); `tree`, `gift` and `launch` are the same at all five levels. Where a tag sits:

  tree    the centre, air; the grand tree stands on it
  gift    air on full paving, outside the tree's column
  launch  air on full paving with open sky above, at every level
  gather  air on full paving: the crowd's standing spots (16 of them at level 3, 24 at level 5)
  sit     the seat itself, a bench's bottom stair (as in MineColonies' tavern and cookery)
  lamp    the air over a lamp post's lantern, (x, 5, z); the post's stripped log is at (x, 3, z), with air on its four
          sides, where garlands and lantern strings hang
  bed     the air over a planter's grass (x, 2, z): the touch pieces below stand here
  stall   the back middle of a stall pad, air on the pad's planks (the Trade Post's stalls, Q10)

Standing tags (`gift`, `launch`, `gather`, `stall`) stand on a full block of the blueprint (the stone mix or the pad's
planks), never on `minecraft:dirt_path` (it has no sturdy top: Gathering.standable would refuse it) and never on a
substitution cell. No block with collision stands over a dirt path (it would turn to dirt): the stone mix is under
every block at y = 1.

THE TOUCHES (the owner's answer "small seasonal touches between festivals"): four tiny pieces, one per season
(pieces/bed_spring|summer|autumn|winter.blueprint), a 1 x 1 column each. PLACEMENT CONTRACT: the piece's anchor is an
air cell tagged `invisible`; its blocks start in the cell above it; the anchor goes on the planter's SOIL cell, which
is the `bed` tag cell minus one in y, so the tag cell receives the piece's first block. Only blocks that need no
support and do not change by themselves (no flowers, bushes, snow layers or crops). Q6 shipped the pieces and the
contract; from Q12 on the Decorator places them between the festivals (decor/DecorWork.touch, SquareSets.touchPath).
"""
import functools
import math

from voxel import Structure

LOOK = "townsquare"                  # the file names, square/townsquare1..5 (BuildingTownSquare.DEFAULT_LOOK)
LOOK_LABEL = "Village plaza"         # what the build tool shows: the owner's name for look A
HUT = "colony_seasons:blockhuttownsquare[facing=north]"
HUT_SPOT = (2, 1, 8)
TREE = (0, 1, 0)
HALF = 10                            # the square is 21 x 21: x and z from -10 to 10
TOP = 19                             # the box's top layer
BOX = ((-HALF, 0, -HALF), (HALF, TOP, HALF))
TREE_HALF = 5                        # the grand tree piece is 11 x 11 round the `tree` cell
ENTRANCE = (0, 1, HALF)              # where a colonist walks in (the paved way from the south)
LEVELS = (1, 2, 3, 4, 5)             # the five levels of design 4.2
GROUND = "structurize:blocksolidsubstitution"
PATH = "minecraft:dirt_path"
PAD = "minecraft:spruce_planks"
LAMP_Y = 5                           # the `lamp` tag: over the lantern on the post's log
POST = ["minecraft:cobblestone_wall", "minecraft:spruce_fence", "minecraft:stripped_spruce_log[axis=y]"]
LANTERN = "minecraft:lantern[hanging=false]"

# design 4.1: tag -> (fewest, most) per level; SLOTS is what each level has (design 4.2, decor/SquareSets.slots)
TAG_COUNTS = {"tree": (1, 1), "gift": (1, 1), "launch": (1, 1), "gather": (8, 24), "sit": (2, 12), "lamp": (0, 8),
              "bed": (0, 8), "stall": (0, 4)}
SEAT_TAGS = ("sit",)                 # tags on a seat (a stair), not on air
SLOTS = {
    1: {"tree": 1, "gift": 1, "launch": 1, "gather": 8, "sit": 2, "lamp": 0, "bed": 0, "stall": 0},
    2: {"tree": 1, "gift": 1, "launch": 1, "gather": 12, "sit": 2, "lamp": 4, "bed": 0, "stall": 0},
    3: {"tree": 1, "gift": 1, "launch": 1, "gather": 16, "sit": 6, "lamp": 4, "bed": 4, "stall": 0},
    4: {"tree": 1, "gift": 1, "launch": 1, "gather": 16, "sit": 6, "lamp": 8, "bed": 4, "stall": 2},
    5: {"tree": 1, "gift": 1, "launch": 1, "gather": 24, "sit": 12, "lamp": 8, "bed": 8, "stall": 4},
}

DIRS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}

# ---- the geometry of look A (design/src/squares.py), levels as keys
POSTS = {2: [(6, 6), (-6, 6), (6, -6), (-6, -6)], 4: [(8, 2), (8, -2), (-8, 2), (-8, -2)]}
BENCHES = {   # level: [(cells, facing)]
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
STALLS = {    # level: [(back middle cell, front)]
    4: [((9, 4), "west"), ((-9, -4), "east")],
    5: [((-4, -9), "south"), ((-4, 9), "north")],
}
CORNER = (-8, 8)                     # level 5: the bandstand, 5 x 5 round this cell
ARM = 6                              # level 1: the four arms of paving where the standing tags are (Chebyshev 6)

# ---- where the tags are: (x, y, z, first level); a cell never moves, higher levels add cells
GIFT = (-2, 1, 6)
LAUNCH = (0, 1, -6)
GATHER = [(6, 1, -2, 1), (6, 1, 2, 1), (-6, 1, -2, 1), (-6, 1, 2, 1), (-2, 1, -6, 1), (2, 1, -6, 1), (2, 1, 6, 1),
          (0, 1, 6, 1),
          (-6, 1, -5, 2), (-6, 1, 5, 2), (5, 1, -6, 2), (5, 1, 6, 2),
          (-4, 1, -6, 3), (-4, 1, 6, 3), (6, 1, -4, 3), (6, 1, 4, 3),
          (6, 1, 0, 5), (-6, 1, 0, 5), (-8, 1, -6, 5), (8, 1, 6, 5), (-6, 1, -8, 5), (6, 1, 8, 5), (-2, 1, -8, 5),
          (-2, 1, 8, 5)]
SIT = [(4, 1, -7, 1), (-7, 1, 4, 1), (7, 1, -4, 3), (-4, 1, 7, 3), (-4, 1, -7, 3), (-7, 1, -4, 3),
       (7, 1, 3, 5), (7, 1, 4, 5), (7, 1, 5, 5), (4, 1, 7, 5), (5, 1, 7, 5), (6, 1, 7, 5)]
LAMP = [(x, LAMP_Y, z, lv) for lv, ps in POSTS.items() for (x, z) in ps]
BED = [(9, 2, -4, 3), (4, 2, -8, 3), (-9, 2, 4, 3), (8, 2, 8, 3), (8, 2, -8, 5), (9, 2, -9, 5), (-8, 2, -8, 5),
       (-9, 2, -9, 5)]
STALL = [(9, 1, 4, 4), (-9, 1, -4, 4), (-4, 1, -9, 5), (-4, 1, 9, 5)]
SLOT_CELLS = {"gift": [GIFT + (1,)], "launch": [LAUNCH + (1,)], "gather": GATHER, "sit": SIT, "lamp": LAMP, "bed": BED,
              "stall": STALL}
STANDING = ("gift", "launch", "gather", "stall")
# what each season's touch is (A4: a block that needs no support and does not change by itself)
TOUCHES = {
    "spring": "minecraft:flowering_azalea_leaves[persistent=true]",
    "summer": "minecraft:azalea_leaves[persistent=true]",
    "autumn": "minecraft:pumpkin",
    "winter": "minecraft:spruce_leaves[persistent=true]",
}
TOUCH_ANCHOR = (0, 1, 0)             # the piece's air anchor; the touch's block is the cell above it


def tag_cells(level):
    """{tag: [(x, y, z)]} of the cells a square of `level` has (the tree is the one cell at (0, 1, 0))."""
    out = {"tree": [TREE]}
    for name, cells in SLOT_CELLS.items():
        got = [(x, y, z) for (x, y, z, first) in cells if first <= level]
        if got:
            out[name] = got
    return out


def h01(x, y, z, salt=0):
    """Stable 0..1 hash of a position (design/src/common.py), so the stone mix is the design's, block for block."""
    n = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791) ^ (salt * 2654435761)
    n = (n ^ (n >> 13)) * 1274126177
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def pave(x, z):
    """Look A's paving (design/src/squares.py pave, look A)."""
    k = h01(x, 0, z, 1)
    return ("minecraft:stone_bricks" if k < 0.45 else "minecraft:cobblestone" if k < 0.68
            else "minecraft:andesite" if k < 0.88 else "minecraft:cracked_stone_bricks" if k < 0.95
            else "minecraft:mossy_cobblestone")


def paved_cells(level):
    """(x, z) -> 'pave' | 'ring' for the paving at a level (design/src/squares.py paved_cells); the rest of the box is the
    terrain."""
    cells = {}
    for x in range(-HALF, HALF + 1):
        for z in range(-HALF, HALF + 1):
            d = math.hypot(x, z)
            entrance = abs(x) <= 1 or abs(z) <= 1
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
                if max(abs(x), abs(z)) <= HALF - 1 and (d <= 10.2 or entrance):
                    cells[(x, z)] = "pave"
                elif max(abs(x), abs(z)) <= HALF - 1 and d <= 11.6:
                    cells[(x, z)] = "ring"
                elif max(abs(x), abs(z)) == HALF and entrance:
                    cells[(x, z)] = "pave"
            else:
                cells[(x, z)] = "pave"
    return cells


def frame(px, pz, front):
    """Local (u across, v depth, 0 = back) to world for a 3 x 2 stall pad whose front faces `front`."""
    fx, fz = DIRS[front]
    rx, rz = -fz, fx

    def at(u, v):
        return (px + rx * (u - 1) + fx * v, pz + rz * (u - 1) + fz * v)
    return at


def in_corner(x, z):
    return CORNER[0] - 2 <= x <= CORNER[0] + 2 and CORNER[1] - 2 <= z <= CORNER[1] + 2


def bench(s, cells, facing):
    """Spruce stair seats in a row (the back toward `facing`), open trapdoors as arms into free cells only."""
    for (x, z) in cells:
        s.set(x, 1, z, f"minecraft:spruce_stairs[facing={facing},half=bottom]")
    xs = sorted(cells)
    (x0, z0), (x1, z1) = xs[0], xs[-1]
    ends = [((x0 - 1, z0), "west"), ((x1 + 1, z0), "east")] if z0 == z1 else [((x0, z0 - 1), "north"), ((x0, z1 + 1), "south")]
    for (x, z), f in ends:
        if s.get(x, 1, z) is None:
            s.set(x, 1, z, f"minecraft:spruce_trapdoor[facing={f},half=bottom,open=true]")


def planter(s, cells):
    """A raised bed: grass one block up, a rim of open spruce trapdoors into free cells; the `bed` tags are the air above."""
    cs = set(cells)
    for (x, z) in cs:
        s.set(x, 1, z, "minecraft:grass_block")
    for (x, z) in sorted(cs):
        for f, (dx, dz) in DIRS.items():
            n = (x + dx, z + dz)
            if n not in cs and s.get(n[0], 1, n[1]) is None:
                s.set(n[0], 1, n[1], f"minecraft:spruce_trapdoor[facing={f},half=bottom,open=true]")


def bandstand(s):
    """Level 5's corner feature, look A (design/src/squares.py corner_feature): a spruce deck, four corner posts, a low
    fence round three sides (the open side faces the square), a dark oak roof, four hanging lanterns."""
    cx, cz = CORNER
    cells = [(cx + dx, cz + dz) for dx in range(-2, 3) for dz in range(-2, 3)]
    for (x, z) in cells:
        s.set(x, 1, z, "minecraft:spruce_slab[type=top]")
    for dx, dz in ((-2, -2), (2, -2), (-2, 2), (2, 2)):
        for y in range(1, 4):
            s.set(cx + dx, y, cz + dz, "minecraft:stripped_spruce_log[axis=y]")
    for (x, z) in cells:
        dx, dz = x - cx, z - cz
        edge = max(abs(dx), abs(dz)) == 2
        if edge and (dx not in (-2, 2) or dz not in (-2, 2)) and not (dx == 2 and abs(dz) <= 1):
            s.set(x, 2, z, "minecraft:spruce_fence")
    for (x, z) in cells:
        dx, dz = x - cx, z - cz
        if max(abs(dx), abs(dz)) == 2:
            f = "south" if dz == -2 else "north" if dz == 2 else "east" if dx == -2 else "west"
            s.set(x, 4, z, f"minecraft:dark_oak_stairs[facing={f},half=bottom]")
        else:
            s.set(x, 4, z, "minecraft:dark_oak_planks")
    for (x, z) in cells:
        dx, dz = x - cx, z - cz
        if max(abs(dx), abs(dz)) == 1:
            f = "south" if dz == -1 else "north" if dz == 1 else "east" if dx == -1 else "west"
            s.set(x, 5, z, f"minecraft:dark_oak_stairs[facing={f},half=bottom]")
    s.set(cx, 5, cz, "minecraft:dark_oak_slab[type=bottom]")
    for dx, dz in ((-1, -1), (1, 1), (-1, 1), (1, -1)):
        s.set(cx + dx, 3, cz + dz, "minecraft:lantern[hanging=true]")


def _build(level):
    """The square's blocks above the paving, in the design's drawing order, and the stall pads' cells."""
    s = Structure(f"{LOOK}{level}")
    # the low edge wall from level 3, with four gaps for the entrances; the bandstand has its own edge at level 5
    if level >= 3:
        for i in range(-HALF, HALF + 1):
            for (x, z) in ((i, -HALF), (i, HALF), (-HALF, i), (HALF, i)):
                if abs(i) <= 1 or (level >= 5 and in_corner(x, z)):
                    continue
                s.set(x, 1, z, "minecraft:cobblestone_wall")
    if level >= 5:
        bandstand(s)
    pads = []
    for lv in (4, 5):
        if level >= lv:
            for (px, pz), front in STALLS[lv]:
                at = frame(px, pz, front)
                pads += [at(u, v) for u in range(3) for v in range(2)]
    s.set(*HUT_SPOT, HUT)
    for lv, rows in BENCHES.items():
        if level >= lv:
            for cells, facing in rows:
                bench(s, cells, facing)
    for lv, beds in PLANTERS.items():
        if level >= lv:
            for cells in beds:
                planter(s, cells)
    posts = [p for lv, ps in POSTS.items() if level >= lv for p in ps]
    if level >= 5:
        posts = [p for p in posts if p != (CORNER[0] + 2, CORNER[1] - 2)]     # the bandstand's corner post stands there
    for (x, z) in posts:
        for i, st in enumerate(POST):
            s.set(x, 1 + i, z, st)
        s.set(x, 4, z, LANTERN)
    return s, set(pads)


def _ground(s, level, pads):
    """The bottom layer: the design's paving, the stall pads' planks, and the stone mix under every block at y = 1 and
    under every standing tag (so nothing with collision stands on a dirt path and every standing tag has a sturdy floor)."""
    cells = paved_cells(level)
    if level == 1:                                    # the four arms where the standing tags are, whole
        for x in range(-HALF, HALF + 1):
            for z in range(-HALF, HALF + 1):
                if max(abs(x), abs(z)) == ARM and min(abs(x), abs(z)) <= 2:
                    cells[(x, z)] = "pave"
    standing = {(x, z) for name in STANDING for (x, y, z) in tag_cells(level).get(name, [])}
    for x in range(-HALF, HALF + 1):
        for z in range(-HALF, HALF + 1):
            kind = cells.get((x, z))
            if (x, 1, z) in s.blocks or (x, z) in standing:
                kind = "pave"
            s.set(x, 0, z, pave(x, z) if kind == "pave" else PATH if kind == "ring" else GROUND)
    for (x, z) in pads:
        s.set(x, 0, z, PAD)


def _tags(s, level):
    for name, cells in tag_cells(level).items():
        for (x, y, z) in cells:
            s.tag(x, y, z, name)
    s.set_anchor(*HUT_SPOT, HUT)
    s.tag(*HUT_SPOT, "name=" + LOOK_LABEL)


def _copy(s):
    t = Structure(s.name)
    t.blocks = dict(s.blocks)
    t.tags = {p: list(n) for p, n in s.tags.items()}
    t.anchor = s.anchor
    return t


@functools.lru_cache(maxsize=None)
def _square(level):
    s, pads = _build(level)
    _ground(s, level, pads)
    _tags(s, level)
    return s


def square(level):
    """The square at `level` as its blueprint holds it (a fresh copy: the caller may change it)."""
    if level not in LEVELS:
        raise ValueError(level)
    return _copy(_square(level))


def touch(season):
    """The seasonal touch for a planter: a piece with an air anchor tagged `invisible` and one block above it (see the
    placement contract in the module docstring)."""
    s = Structure(f"bed_{season}")
    s.set_anchor(*TOUCH_ANCHOR)
    s.tag(*TOUCH_ANCHOR, "invisible")
    x, y, z = TOUCH_ANCHOR
    s.set(x, y + 1, z, TOUCHES[season])
    return s
