"""SET 1: the decorated tree for the colony square - three look options as voxel builds.

Each build_* returns a voxel.Structure: the ground patch (-7..7, paved square), the decoration,
and a door-height lantern post at (4, 1, 5) for scale. Coordinates: x east, y up, z south;
the tree / pole stands on (0, 1, 0). Custom blocks (namespace colonyseasons):
  ornament_leaves[style=mixed|garland]  spruce-leaf cube with baubles/lights (3 random textures) or gold tinsel
  tree_star[size=small|large]           extruded gold star topper (large = 24 px, 2 blocks tall)
  gift_pile[facing=...]                 4 wrapped boxes on one floor block
"""
import math

from common import Structure, ground_patch, lantern_post, h01, disc_positions

LEAF = "minecraft:spruce_leaves[persistent=true]"
ORN = "colonyseasons:ornament_leaves[style=mixed]"
GARL = "colonyseasons:ornament_leaves[style=garland]"


def _is_leaf(s, p):
    b = s.blocks.get(p, "")
    return "leaves" in b


def _exposed(s, x, y, z):
    for d in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 1, 0)):
        if not _is_leaf(s, (x + d[0], y + d[1], z + d[2])) and s.blocks.get((x + d[0], y + d[1], z + d[2])) is None:
            return True
    return False


def _decorate(s, density, salt, keep=()):
    """Swap a share of the outer leaves for ornament leaves (deterministic, evenly spread)."""
    leaves = [p for p, b in s.blocks.items() if b == LEAF]
    for (x, y, z) in sorted(leaves):
        if (x, y, z) in keep or not _exposed(s, x, y, z):
            continue
        if h01(x, y, z, salt) < density:
            # avoid two ornament blocks touching sideways - spreads the colour evenly
            if any(s.blocks.get((x + dx, y, z + dz)) == ORN for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                continue
            s.set(x, y, z, ORN)


def _snow_caps(s, share, salt):
    """Thin snow on some upward-facing outer leaves (snow layers sit on leaves in vanilla)."""
    for (x, y, z), b in sorted(s.blocks.items()):
        if "leaves" in b and (x, y + 1, z) not in s.blocks and (x or z) and h01(x, y, z, salt) < share:
            s.set(x, y + 1, z, "minecraft:snow[layers=1]")


def _gifts(s, spots):
    for (x, z, f) in spots:
        s.set(x, 1, z, f"colonyseasons:gift_pile[facing={f}]")


SCALE_POST = (-2, 6)   # door-height lantern post, same spot in every variant


def _layers(s, layers, top_y):
    """layers {y: radius}; the trunk runs up to top_y - 1, so radius < 1 still wraps it (plus shape)."""
    for y, r in layers.items():
        rr = max(r, 1.0) if y < top_y else r
        for dx, dz in disc_positions(rr):
            if (dx, dz) == (0, 0) and y < top_y:
                continue
            if rr >= 2 and dx * dx + dz * dz > (rr - 0.6) ** 2 and h01(dx, y, dz, 7) < 0.22:
                continue  # ragged natural edge
            s.set(dx, y, dz, LEAF)


def build_a():
    """A 'Village spruce': natural conical spruce, 7x7 base, leaves to y=12, small star at 13."""
    s = Structure("tree_a_village_spruce")
    ground_patch(s)
    for y in range(1, 12):
        s.set(0, y, 0, "minecraft:spruce_log")
    _layers(s, {2: 3.2, 3: 2.3, 4: 3.0, 5: 2.0, 6: 2.6, 7: 1.5, 8: 2.1, 9: 1.2, 10: 1.5, 11: 1.0, 12: 0.0}, 12)
    _decorate(s, 0.42, 11)
    s.set(0, 13, 0, "colonyseasons:tree_star[size=small]")
    _gifts(s, [(1, 3, "south"), (3, 2, "west"), (-2, 3, "east"), (3, -1, "west"), (0, 4, "north")])
    lantern_post(s, *SCALE_POST)
    return s


def build_b():
    """B 'Grand tiered tree': raised round planter (stone-brick curb, 4 lanterns on it), 9x9 base,
    four clear tiers to y=16, gold tinsel garland spiral, large star."""
    s = Structure("tree_b_grand_tiered")
    ground_patch(s, plaza=6.6)
    for dx in range(-6, 7):
        for dz in range(-6, 7):
            d = math.hypot(dx, dz)
            if 5.0 <= d < 6.0:
                s.set(dx, 1, dz, "minecraft:stone_bricks")
                s.set(dx, 2, dz, None)
            elif d < 5.0:
                s.set(dx, 1, dz, "minecraft:podzol")
                s.set(dx, 2, dz, None)
    for (x, z) in ((0, 5), (0, -5), (5, 0), (-5, 0)):
        s.set(x, 1, z, "minecraft:chiseled_stone_bricks")
        s.set(x, 2, z, "minecraft:lantern[hanging=false]")
    for y in range(2, 16):
        s.set(0, y, 0, "minecraft:spruce_log")
    tiers = [(3, [4.0, 3.4, 2.4]), (6, [3.6, 2.8, 1.8]), (9, [3.0, 2.2, 1.2]), (12, [2.2, 1.5, 1.0]), (15, [1.0, 0.0])]
    for y0, rs in tiers:
        for i, r in enumerate(rs):
            y = y0 + i
            for dx, dz in disc_positions(max(r, 1.0) if y < 16 else r):
                if (dx, dz) == (0, 0) and y < 16:
                    continue
                s.set(dx, y, dz, LEAF)
    # garland: gold tinsel spiral, dropping one layer every half turn on the outer leaves of each tier
    garl = set()
    for y0, rs in tiers[:4]:
        for i in range(2):
            y = y0 + i
            for k in range(8):
                if (k < 4) != (i == 0):
                    continue
                ang = (k / 8.0) * 2 * math.pi
                best = None
                for dx, dz in disc_positions(rs[i]):
                    a2 = math.atan2(dz, dx) % (2 * math.pi)
                    diff = min(abs(a2 - ang), 2 * math.pi - abs(a2 - ang))
                    score = (dx * dx + dz * dz) - 6 * diff
                    if best is None or score > best[0]:
                        best = (score, dx, dz)
                garl.add((best[1], y, best[2]))
    for p in garl:
        s.set(*p, GARL)
    _decorate(s, 0.30, 21, keep=garl)
    s.set(0, 17, 0, "colonyseasons:tree_star[size=large]")
    for (x, z, f) in ((3, 3, "north"), (4, 1, "west"), (1, 4, "north"), (-3, 3, "east"), (3, -3, "west")):
        s.set(x, 2, z, f"colonyseasons:gift_pile[facing={f}]")
    lantern_post(s, *SCALE_POST)
    return s


def build_c():
    """C 'Lantern pole': log + fence pole, evergreen wreath ring near the top, four cords of chain
    stepping down (one out, two down; vertical runs so every lantern really hangs from a chain) to
    short fence posts on a circle, lanterns on posts between them - a cone of lights. Vanilla
    blocks only, apart from the ornament leaves in the wreath ring."""
    s = Structure("tree_c_lantern_pole")
    ground_patch(s)
    for dx, dz, f in ((1, 0, "west"), (-1, 0, "east"), (0, 1, "north"), (0, -1, "south")):
        s.set(dx, 1, dz, f"minecraft:stone_brick_stairs[facing={f},half=bottom]")
    for dx, dz in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        s.set(dx, 1, dz, "minecraft:stone_brick_slab[type=bottom]")
    for y in range(1, 7):
        s.set(0, y, 0, "minecraft:spruce_log[axis=y]")
    for y in range(7, 16):
        s.set(0, y, 0, "minecraft:spruce_fence")
    s.set(0, 16, 0, "minecraft:lantern[hanging=false]")
    # evergreen wreath ring round the pole near the top
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            if (dx, dz) != (0, 0):
                s.set(dx, 13, dz, ORN if (dx and dz) else LEAF)
                if dx == 0 or dz == 0:
                    s.set(dx, 12, dz, ORN if (dx + dz) > 0 else LEAF)   # bushy lower collar

    for ddx, ddz, axis in ((1, 0, "x"), (-1, 0, "x"), (0, 1, "z"), (0, -1, "z")):
        for r in range(2, 7):
            yh = 13 - 2 * (r - 2)
            x, z = ddx * r, ddz * r
            s.set(x, yh, z, f"minecraft:chain[axis={axis}]")
            s.set(x, yh - 1, z, "minecraft:chain[axis=y]")
            s.set(x, yh - 2, z, "minecraft:chain[axis=y]")
            s.set(x, yh - 3, z, "minecraft:lantern[hanging=true]")
        px, pz = ddx * 7, ddz * 7
        s.set(px, 0, pz, "minecraft:stone_bricks")
        for y in range(1, 4):
            s.set(px, y, pz, "minecraft:spruce_fence")
    for (x, z) in ((5, 5), (-5, 5), (5, -5), (-5, -5)):
        s.set(x, 0, z, "minecraft:stone_bricks")
        for y in range(1, 3):
            s.set(x, y, z, "minecraft:spruce_fence")
        s.set(x, 3, z, "minecraft:lantern[hanging=false]")
    lantern_post(s, *SCALE_POST)
    return s

def _opp(f):
    return {"north": "south", "south": "north", "east": "west", "west": "east"}[f]


BUILDS = {"A": ("Village spruce", build_a), "B": ("Grand tiered tree", build_b), "C": ("Lantern pole", build_c)}




