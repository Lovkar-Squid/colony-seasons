"""The Town Square's blueprint (0.2.0, docs/town-square-design.md 4.1): the owner's look A "Village plaza", 21 x 21.

Chunk Q1 ships ONE placeholder level (square/townsquare1.blueprint) that carries every tag of design 4.1, so the
building, its tags and its Decorator can be built and tested; chunk Q6 draws the five real levels of the picked look
(design/src/squares.py) on the same box, with the same tag cells. tools/build_pack.py turns square(level) into the
blueprint and checks it (check_square).

Coordinates as in design/src/squares.py: x east, y up, z south; the paving is y = 0 and the `tree` cell, the centre
of the square (where the 0.1.0 festival marker stood), is (0, 1, 0). The box is x and z -10..10 and y 0..19 at every
level, so the grand tree (11 x 11, 18 high, standing on the `tree` cell) fits inside it; nothing is drawn in the
11 x 11 column over the `tree` cell. The hut block (the notice board, the owner's pick) stands at (2, 1, 8), where the
pictures had it, off the centre (so a turned square is not the same as an unturned one) and on the paving.

The level-1 placeholder: a paved disc of look A's stone mix with a paved way in from the south and to the hut block,
two benches, four fence posts, a planter of four cells and two stall places; the rest of the bottom layer is
"a solid block of this terrain". Every tag sits on an air cell, except `sit`: as in MineColonies' own blueprints (its
tavern and cookery), a `sit` tag marks the seat itself, a bench's stair, which is what its leisure wander sits on.
"""
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
LEVELS = (1,)                        # the levels this build ships; Q6 makes it (1, 2, 3, 4, 5)
GROUND = "structurize:blocksolidsubstitution"

# design 4.1: tag -> (fewest, most) per level
TAG_COUNTS = {"tree": (1, 1), "gift": (1, 1), "launch": (1, 1), "gather": (8, 24), "sit": (2, 12), "lamp": (0, 8),
              "bed": (0, 8), "stall": (0, 4)}
SEAT_TAGS = ("sit",)                 # tags on a seat (a stair), not on air

GIFT = (-2, 1, 6)
LAUNCH = (0, 1, -7)
GATHER = [(6, 1, -2), (6, 1, 2), (-6, 1, -2), (-6, 1, 2), (-2, 1, -6), (2, 1, -6), (4, 1, 6), (-4, 1, 6)]
BENCHES = [([(3, -7), (4, -7), (5, -7)], "north"), ([(-7, 3), (-7, 4), (-7, 5)], "west")]
POSTS = [(6, 6), (-6, 6), (6, -6), (-6, -6)]
PLANTER = [(8, -8), (9, -8), (8, -9), (9, -9)]
STALLS = [(9, 1, 4), (-9, 1, -4)]
DIRS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}


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


def paved(level, x, z):
    """True where the bottom layer is paving at this level (the rest is the terrain)."""
    if math.hypot(x, z) <= 7.6:
        return True
    if abs(x) <= 1 and z > 0:                       # the way in from the south
        return True
    return 1 <= x <= 3 and 6 <= z <= 9              # the way to the hut block


def bench(s, cells, facing):
    """Spruce stair seats in a row (the back toward `facing`), open trapdoors as arms (design/src/squares.py bench)."""
    for (x, z) in cells:
        s.set(x, 1, z, f"minecraft:spruce_stairs[facing={facing},half=bottom]")
    xs = sorted(cells)
    (x0, z0), (x1, z1) = xs[0], xs[-1]
    ends = [((x0 - 1, z0), "west"), ((x1 + 1, z0), "east")] if z0 == z1 else [((x0, z0 - 1), "north"), ((x0, z1 + 1), "south")]
    for (x, z), f in ends:
        s.set(x, 1, z, f"minecraft:spruce_trapdoor[facing={f},half=bottom,open=true]")


def planter(s, cells):
    """A raised bed: dirt one block up, a rim of open spruce trapdoors; the `bed` tags are the air over the dirt."""
    cs = set(cells)
    for (x, z) in cs:
        s.set(x, 1, z, "minecraft:dirt")
    for (x, z) in sorted(cs):
        for f, (dx, dz) in DIRS.items():
            n = (x + dx, z + dz)
            if n not in cs and s.get(n[0], 1, n[1]) is None:
                s.set(n[0], 1, n[1], f"minecraft:spruce_trapdoor[facing={f},half=bottom,open=true]")


def square(level):
    """The square at `level` as the blueprint holds it (Q1: level 1, the placeholder)."""
    if level not in LEVELS:
        raise ValueError(level)
    s = Structure(f"{LOOK}{level}")
    for x in range(-HALF, HALF + 1):
        for z in range(-HALF, HALF + 1):
            s.set(x, 0, z, pave(x, z) if paved(level, x, z) else GROUND)
    for cells, facing in BENCHES:
        bench(s, cells, facing)
    for (x, z) in POSTS:
        s.set(x, 1, z, "minecraft:cobblestone_wall")
        s.set(x, 2, z, "minecraft:spruce_fence")
        s.tag(x, 3, z, "lamp")
    planter(s, PLANTER)
    for (x, z) in PLANTER:
        s.tag(x, 2, z, "bed")
    s.set_anchor(*HUT_SPOT, HUT)
    s.tag(*HUT_SPOT, "name=" + LOOK_LABEL)
    s.tag(*TREE, "tree")
    s.tag(*GIFT, "gift")
    s.tag(*LAUNCH, "launch")
    for p in GATHER:
        s.tag(*p, "gather")
    for cells, _ in BENCHES:
        x, z = cells[len(cells) // 2]
        s.tag(x, 1, z, "sit")
    for p in STALLS:
        s.tag(*p, "stall")
    return s
