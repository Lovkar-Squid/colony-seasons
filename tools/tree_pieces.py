"""The tree pieces for the festival square (pieces/*.blueprint), placed by the Decorator's own code (chunk S4).

The owner's picks (design/src/trees.py, the pictures in design/sheet-tree.png): tree A "Village spruce" at hut levels
1 to 4 and tree B "Grand tiered tree" at level 5. Four pieces:

  village_spruce_small   hut levels 1 and 2: tree A drawn smaller (5 x 5 crown, 9 tall), three gift piles
  village_spruce_full    hut level 3: tree A as the owner saw it (7 x 7 crown, 13 tall), five gift piles, the crate
  village_spruce_rich    hut level 4: the same tree with more ornaments, seven gift piles, the crate
  grand_tree             hut level 5: tree B (the planter, four tiers, the gold tinsel spiral, the large star)

The contract with S4: every piece is drawn facing north (the festival marker's facing turns it), its anchor (the
blueprint's primary offset) is the cell where the marker stands and that cell is air, because the marker is never
replaced. The tree stands on the marker: the trunk starts in the cell above it (in the design the trunk of A starts on
the ground and B has podzol there; that one cell is the marker's now). Position tags: gift_pile on every gift pile (S4
skips them below hut level 2), firework_crate on the crate (skipped below level 3), star on the star (the lower cell
of the large star). Trunk cells are the spruce logs. Decorations are drawn unlit (lit=false); S7 lights them.

The design builds face south (the gifts towards the camera); a piece is the design turned half round, so it faces
north. The design's ground patch (paved square, grass, snow) and its scale post are not part of a piece.
"""
import math

from voxel import Structure

NS = "colony_seasons"
LEAF = "minecraft:spruce_leaves[persistent=true]"
LOG = "minecraft:spruce_log[axis=y]"
ORN = f"{NS}:ornament_leaves[lit=false]"
TINSEL = f"{NS}:tinsel_leaves[lit=false]"
STAR = f"{NS}:tree_star[lit=false]"
LARGE_STAR = (f"{NS}:large_star[half=lower,lit=false]", f"{NS}:large_star[half=upper,lit=false]")
CRATE = f"{NS}:firework_crate"
ANCHOR = (0, 1, 0)       # where the marker stands; the design trees stand on (0, 1, 0)

PIECES = ("village_spruce_small", "village_spruce_full", "village_spruce_rich", "grand_tree")


def h01(x, y, z, salt=0):
    """Stable 0..1 hash of a position (design/src/common.py), so ornaments and ragged edges are the design's."""
    n = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791) ^ (salt * 2654435761)
    n = (n ^ (n >> 13)) * 1274126177
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def disc_positions(r):
    rr = int(math.ceil(r))
    return [(dx, dz) for dx in range(-rr, rr + 1) for dz in range(-rr, rr + 1) if dx * dx + dz * dz <= r * r + 1e-6]


def gift(facing):
    return f"{NS}:gift_pile[facing={facing}]"


def _exposed(s, x, y, z):
    for d in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 1, 0)):
        if s.blocks.get((x + d[0], y + d[1], z + d[2])) is None:
            return True
    return False


def _decorate(s, density, salt, keep=()):
    """Swap a share of the outer leaves for ornament leaves (design trees._decorate: deterministic, evenly spread,
    never two ornament blocks side by side)."""
    leaves = [p for p, b in s.blocks.items() if b == LEAF]
    for (x, y, z) in sorted(leaves):
        if (x, y, z) in keep or not _exposed(s, x, y, z):
            continue
        if h01(x, y, z, salt) < density:
            if any(s.blocks.get((x + dx, y, z + dz)) == ORN for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                continue
            s.set(x, y, z, ORN)


def _layers(s, layers, top_y):
    """layers {y: radius}; the trunk runs up to top_y - 1, so a radius under 1 still wraps it (design trees._layers)."""
    for y, r in layers.items():
        rr = max(r, 1.0) if y < top_y else r
        for dx, dz in disc_positions(rr):
            if (dx, dz) == (0, 0) and y < top_y:
                continue
            if rr >= 2 and dx * dx + dz * dz > (rr - 0.6) ** 2 and h01(dx, y, dz, 7) < 0.22:
                continue  # ragged natural edge
            s.set(dx, y, dz, LEAF)


def _gifts(s, y, spots):
    for (x, z, f) in spots:
        s.set(x, y, z, gift(f))
        s.tag(x, y, z, "gift_pile")


def _trunk(s, y_top):
    """The trunk stands on the marker: from the cell above the anchor up to y_top."""
    for y in range(ANCHOR[1] + 1, y_top + 1):
        s.set(0, y, 0, LOG)


def _spruce(name, layers, top_y, star_y, density, gifts, crate=None):
    """Tree A, the village spruce, in the design's (south-facing) frame."""
    s = Structure(name)
    _trunk(s, top_y - 1)
    _layers(s, layers, top_y)
    _decorate(s, density, 11)
    s.set(0, star_y, 0, STAR)
    s.tag(0, star_y, 0, "star")
    _gifts(s, 1, gifts)
    if crate:
        s.set(crate[0], 1, crate[1], CRATE)
        s.tag(crate[0], 1, crate[1], "firework_crate")
    return s


# tree A exactly as the owner saw it (design trees.build_a)
A_LAYERS = {2: 3.2, 3: 2.3, 4: 3.0, 5: 2.0, 6: 2.6, 7: 1.5, 8: 2.1, 9: 1.2, 10: 1.5, 11: 1.0, 12: 0.0}
A_GIFTS = [(1, 3, "south"), (3, 2, "west"), (-2, 3, "east"), (3, -1, "west"), (0, 4, "north")]
# the crate (hut level 3 and up) stands clear of the boughs, so the New Year rockets have open sky above them
A_CRATE = (-4, 2)


def village_spruce_small_design():
    """Hut levels 1 and 2: tree A at about two thirds of its size - the same alternating wide and narrow rings, one
    ornament density, the small star."""
    layers = {2: 2.3, 3: 1.6, 4: 2.1, 5: 1.2, 6: 1.6, 7: 1.0, 8: 0.0}
    gifts = [(1, 3, "south"), (3, 1, "west"), (-2, 2, "east")]
    return _spruce("village_spruce_small", layers, 8, 9, 0.42, gifts)


def village_spruce_full_design():
    return _spruce("village_spruce_full", A_LAYERS, 12, 13, 0.42, A_GIFTS, A_CRATE)


def village_spruce_rich_design():
    """Hut level 4: the same tree with more ornaments (as many as fit without two side by side) and two more gifts."""
    gifts = A_GIFTS + [(-3, -1, "east"), (2, -3, "north")]
    return _spruce("village_spruce_rich", A_LAYERS, 12, 13, 0.85, gifts, A_CRATE)


def grand_tree_design():
    """Hut level 5: tree B (design trees.build_b) - a raised round planter (stone-brick curb, four lanterns on it),
    a 9 x 9 base, four clear tiers, a gold tinsel spiral, the large star."""
    s = Structure("grand_tree")
    for dx in range(-6, 7):
        for dz in range(-6, 7):
            d = math.hypot(dx, dz)
            if 5.0 <= d < 6.0:
                s.set(dx, 1, dz, "minecraft:stone_bricks")
            elif d < 5.0 and (dx, dz) != (0, 0):
                s.set(dx, 1, dz, "minecraft:podzol")     # (0, 1, 0) is the marker's cell
    for (x, z) in ((0, 5), (0, -5), (5, 0), (-5, 0)):
        s.set(x, 1, z, "minecraft:chiseled_stone_bricks")
        s.set(x, 2, z, "minecraft:lantern[hanging=false]")
    _trunk(s, 15)
    tiers = [(3, [4.0, 3.4, 2.4]), (6, [3.6, 2.8, 1.8]), (9, [3.0, 2.2, 1.2]), (12, [2.2, 1.5, 1.0]), (15, [1.0, 0.0])]
    for y0, rs in tiers:
        for i, r in enumerate(rs):
            y = y0 + i
            for dx, dz in disc_positions(max(r, 1.0) if y < 16 else r):
                if (dx, dz) == (0, 0) and y < 16:
                    continue
                s.set(dx, y, dz, LEAF)
    # the tinsel: a gold spiral, dropping one layer every half turn on the outer leaves of each tier
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
        s.set(*p, TINSEL)
    _decorate(s, 0.30, 21, keep=garl)
    s.set(0, 17, 0, LARGE_STAR[0])
    s.set(0, 18, 0, LARGE_STAR[1])
    s.tag(0, 17, 0, "star")
    _gifts(s, 2, [(3, 3, "north"), (4, 1, "west"), (1, 4, "north"), (-3, 3, "east"), (3, -3, "west")])
    s.set(-4, 2, 2, CRATE)
    s.tag(-4, 2, 2, "firework_crate")
    return s


DESIGNS = {
    "village_spruce_small": village_spruce_small_design,
    "village_spruce_full": village_spruce_full_design,
    "village_spruce_rich": village_spruce_rich_design,
    "grand_tree": grand_tree_design,
}


def piece(name):
    """A piece as shipped: the design-frame build turned half round so it faces north, anchored on the marker cell,
    tagged invisible so the build tool does not list it (S4 places it, the player never builds it by hand)."""
    s = DESIGNS[name]().rotated_180()
    s.set_anchor(*ANCHOR)
    s.tag(*ANCHOR, "invisible")
    return s


def ship_design_state(state):
    """A design state (namespace colonyseasons, the look-option names, design/README.md "Shipped ids") as shipped."""
    from voxel import parse_state, state_string
    name, props = parse_state(state)
    if name == "minecraft:spruce_log" and not props:
        return LOG
    if not name.startswith("colonyseasons:"):
        return state
    short = name.split(":", 1)[1]
    if short == "ornament_leaves":
        return TINSEL if props.get("style") == "garland" else ORN
    if short == "tree_star":
        return LARGE_STAR[0] if props.get("size") == "large" else STAR
    if short == "wreath":
        return f"{NS}:wreath[facing={props['facing']}]"
    return state_string(f"{NS}:{short}", props)


# Where the pieces differ from design/src/trees.py (design frame, before the half turn), once the design's ids are
# mapped to the shipped ones and its ground patch and scale post are left out. build_pack.py checks that
# village_spruce_full and grand_tree differ from build_a and build_b in exactly these cells.
DESIGN_CHANGES = {
    "marker cell": "(0, 1, 0) is the festival marker's cell, air in the piece: it was the lowest trunk log of A and "
                   "the centre podzol of B",
    "crate": "the firework crate (hut level 3 and up), which the design does not draw",
    "large star upper": "the large star's upper half, in the cell above its lower half (S2's large_star is two cells)",
}


def design_diff(name):
    """{position: DESIGN_CHANGES key} in the design frame, for the two pieces drawn straight from the design."""
    d = {ANCHOR: "marker cell"}
    if name == "village_spruce_full":
        d[(A_CRATE[0], 1, A_CRATE[1])] = "crate"
    if name == "grand_tree":
        d[(-4, 2, 2)] = "crate"
        d[(0, 18, 0)] = "large star upper"
    return d
