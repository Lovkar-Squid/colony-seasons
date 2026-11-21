"""The Decorator's hut blueprints: look A "Workshop with shopfront" in five levels (decorator/workshop1..5.blueprint).

Not to be confused with tools/gen_decorator_hut.py (chunk S3), which writes the hut BLOCK's model; this file builds the
BUILDING the player puts up with the build tool. tools/build_pack.py turns workshop(level) into the blueprints.

Level 5 is the owner's pick (design/src/huts.py build_a, the picture in design/sheet-hut.png): an 11 x 9 workshop with a
stone ground floor, a big shop window under a striped awning, a wreath over the door, a timber-framed plaster upper
floor and a dark oak gable roof. Levels 1 to 4 stand on the same plan and grow towards it in the same style:

  1  the stone workshop: one storey, two small front windows, the door with its wreath, a dark oak roof on the stone
     walls; inside the hut block, a crafting table and two racks
  2  the shopfront: the big window with its sill box, the wall lantern on its bracket, the loom, the shop bench, a third
     rack, a barrel outside
  3  the upper floor: timber frame and plaster under the roof of level 5, reached by a ladder inside; the stonecutter
  4  roof detail and dressing: the striped awning, the ridge slab, the gable window, more barrels outside, a fifth rack
  5  the full picture: the last rack, hay and the ladder outside, the attic full of racks

The storage room (S10e, storage_room()): the Decorator keeps last year's decorations in MineColonies racks, which are
the hut's storage. At levels 1 and 2 it is the ground floor's back-east corner (the racks and stacked barrels); from
level 3 it is the attic upstairs, with more racks at every level: 2, 3, 6, 9 and 12 racks in the hut at levels 1 to 5.
S10g1 dresses it (docs/storage-look-design.md, variant A): a few more barrels in the blueprint, and position tags for
the spots where the Decorator puts his packed boxes and coils and, in the festival, hangs his decorations (STOREROOM).

Every level is a complete building: a door, light (lanterns inside), storage (MineColonies racks, which are what a
hut's inventory is made of; the design's barrels outside stay barrels), walkable floors and no bed (the Decorator lives
in an ordinary house). Coordinates as in the design: x east, y up, z south, the front faces south, the foundation is
y = 0 and the hut block stands at (0, 1, 0).

What level 5 changes from design build_a, and why, is listed in DESIGN_CHANGES (build_pack.py checks that level 5
equals the design apart from exactly these cells).
"""
import math

from voxel import Structure

LOOK = "workshop"                       # the file names, decorator/workshop1..5 (BuildingDecorator.DEFAULT_LOOK)
LOOK_LABEL = "Workshop with shopfront"  # what the build tool shows: the owner's name for look A
HUT = "colony_seasons:blockhutdecorator[facing=north]"   # facing north turns the model's shop front south, to the door
RACK = "minecolonies:blockminecoloniesrack"
# White plaster, as in the picture. A Silk Touch block in survival: see the owner items of chunk S5.
PLASTER = "minecraft:mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]"
WREATH = "colony_seasons:wreath[facing=south]"
LANTERN_HANGING = "minecraft:lantern[hanging=true]"
LANTERN_STANDING = "minecraft:lantern[hanging=false]"

X0, X1, Z0, Z1 = -5, 5, -4, 4           # the walls (11 x 9), the same at every level
HUT_SPOT = (0, 1, 0)
DOOR_X = 3


def h01(x, y, z, salt=0):
    """Stable 0..1 hash of a position (design/src/common.py), so the stone mix is the design's, block for block."""
    n = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791) ^ (salt * 2654435761)
    n = (n ^ (n >> 13)) * 1274126177
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def stone_mix(x, y, z, salt=3):
    k = h01(x, y, z, salt)
    return ("minecraft:stone_bricks" if k < 0.45 else "minecraft:cobblestone" if k < 0.75
            else "minecraft:mossy_cobblestone" if k < 0.85 else "minecraft:andesite")


def gable_x(s, x0, x1, z0, z1, y, stair, full, ov=1, slab=None):
    """Gable roof with the ridge along x over the box x0..x1 / z0..z1 (design huts.gable_x, not steep).
    Returns {z: y of the roof surface} for filling the gables."""
    za, zb = z0 - ov, z1 + ov
    i = 0
    heights = {}
    while za + i <= zb - i:
        zn, zs = za + i, zb - i
        yy = y + i
        for x in range(x0 - ov, x1 + ov + 1):
            if zn == zs:
                s.set(x, yy, zn, full)
                if slab:
                    s.set(x, yy + 1, zn, slab)
            else:
                s.set(x, yy, zs, f"{stair}[facing=north,half=bottom]")
                s.set(x, yy, zn, f"{stair}[facing=south,half=bottom]")
        heights[zn] = yy
        heights[zs] = yy
        i += 1
    return heights


def fill_gable_x(s, heights, z0, z1, xface, y0, block_fn):
    for z in range(z0, z1 + 1):
        top = heights.get(z)
        if top is None:
            continue
        for y in range(y0, top):
            if s.get(xface, y, z) is None:
                s.set(xface, y, z, block_fn(z, y))


def gable_plaster(z, y):
    return PLASTER if (z + y) % 5 else "minecraft:spruce_log[axis=y]"


def workshop(level):
    if not 1 <= level <= 5:
        raise ValueError(level)
    s = Structure(f"{LOOK}{level}")
    upper = level >= 3
    shop = level >= 2
    x0, x1, z0, z1 = X0, X1, Z0, Z1

    # foundation: stone edge, spruce boards inside; the stone apron in front of the shop (design: the paved strip)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            s.set(x, 0, z, "minecraft:stone_bricks" if edge else "minecraft:spruce_planks")
    for x in range(x0 - 1, x1 + 2):
        s.set(x, 0, z1 + 1, stone_mix(x, 0, z1 + 1, 9))

    # ground floor: stone, y = 1..4, polished andesite and stone brick quoins
    for y in range(1, 5):
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                s.set(x, y, z, stone_mix(x, y, z))
        for z in range(z0, z1 + 1):
            for x in (x0, x1):
                s.set(x, y, z, stone_mix(x, y, z))
    for (x, z) in ((x0, z0), (x0, z1), (x1, z0), (x1, z1)):
        for y in range(1, 5):
            s.set(x, y, z, "minecraft:polished_andesite" if y % 2 else "minecraft:stone_bricks")

    if shop:
        # the shopfront: posts, sill boards, the big window x -4..0, y 2..3, a beam over it; the window box outside
        for x in (-5, 1):
            for y in range(1, 5):
                s.set(x, y, z1, "minecraft:spruce_log[axis=y]")
        for x in range(-4, 1):
            s.set(x, 1, z1, "minecraft:spruce_planks")
            s.set(x, 2, z1, "minecraft:glass_pane")
            s.set(x, 3, z1, "minecraft:glass_pane")
            s.set(x, 4, z1, "minecraft:stripped_spruce_log[axis=x]")
            s.set(x, 1, z1 + 1, "minecraft:spruce_trapdoor[facing=south,half=top,open=false]")
    else:
        # the small workshop: two small windows in the stone front where the shop window will be
        for x in (-3, -1):
            s.set(x, 2, z1, "minecraft:glass_pane")
            s.set(x, 3, z1, "minecraft:glass_pane")

    # the door (x = 3) in a log frame, the wreath over it, the step
    for y in range(1, 4):
        s.set(DOOR_X - 1, y, z1, "minecraft:spruce_log[axis=y]")
        s.set(DOOR_X + 1, y, z1, "minecraft:spruce_log[axis=y]")
    s.set(DOOR_X, 1, z1, "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=false]")
    s.set(DOOR_X, 2, z1, "minecraft:spruce_door[facing=north,half=upper,hinge=left,open=false]")
    s.set(DOOR_X, 3, z1, "minecraft:stripped_spruce_log[axis=x]")
    for x in (DOOR_X - 1, DOOR_X, DOOR_X + 1):
        s.set(x, 4, z1, "minecraft:stripped_spruce_log[axis=x]")
    s.set(DOOR_X, 3, z1 + 1, WREATH)
    s.set(DOOR_X, 0, z1 + 1, "minecraft:spruce_slab[type=top]")

    if shop:
        # the wall lantern beside the door hangs from a fence bracket (in the design the awning's last trapdoor took
        # the bracket's cell, and a lantern does not hang from a top trapdoor: it would drop)
        s.set(1, 4, z1 + 1, "minecraft:spruce_fence")
        s.set(1, 3, z1 + 1, LANTERN_HANGING)

    # side windows (east), ground floor
    for z in (-1, 1):
        s.set(x1, 2, z, "minecraft:glass_pane")
        s.set(x1, 3, z, "minecraft:glass_pane")

    if level >= 4:
        # the awning over the shop window: spruce trapdoors with red and white carpet stripes
        for x in range(-5, 1):
            s.set(x, 4, z1 + 1, "minecraft:spruce_trapdoor[facing=south,half=top,open=false]")
        for x in range(-5, 2):
            s.set(x, 5, z1 + 1, "minecraft:red_carpet" if x % 2 else "minecraft:white_carpet")

    if upper:
        _upper_floor(s, level)
        roof_y = 9
    else:
        # one storey: the same roof sits on the stone walls; tie beams over the room carry the lanterns
        roof_y = 5
        for x in (-2, 2):
            for z in range(z0, z1 + 1):
                s.set(x, 5, z, "minecraft:stripped_spruce_log[axis=z]")
            s.set(x, 4, -1 if x < 0 else 1, LANTERN_HANGING)

    # the roof: dark oak, ridge along x, one block of overhang; plaster and log gables
    hz = gable_x(s, x0, x1, z0, z1, roof_y, "minecraft:dark_oak_stairs", "minecraft:dark_oak_planks", ov=1,
                 slab="minecraft:dark_oak_slab[type=bottom]" if level >= 4 else None)
    fill_gable_x(s, hz, z0, z1, x0, roof_y, gable_plaster)
    fill_gable_x(s, hz, z0, z1, x1, roof_y, gable_plaster)
    if level >= 4:
        # the gable window on the east end
        s.set(x1, 10, 0, "minecraft:glass_pane")
        s.set(x1, 11, 0, "minecraft:glass_pane")
        s.set(x1, 9, -1, "minecraft:spruce_log[axis=y]")
        s.set(x1, 9, 1, "minecraft:spruce_log[axis=y]")

    # outside: barrels and hay by the side walls, the ladder on the east wall
    outside = []
    if level >= 2:
        outside.append(((6, 1, 3), "minecraft:barrel[facing=up]"))
    if level >= 3:
        outside.append(((6, 1, 2), "minecraft:barrel[facing=south]"))
    if level >= 4:
        outside += [((6, 2, 3), "minecraft:barrel[facing=up]"), ((-6, 1, 3), "minecraft:barrel[facing=up]")]
    if level >= 5:
        outside.append(((-6, 1, 2), "minecraft:hay_block[axis=x]"))
        # the ladder on the east wall: one block south of the design's, which crossed a window (a ladder does not
        # hold on glass panes; the paste probe saw two rungs drop)
        outside += [((6, y, 0), "minecraft:ladder[facing=east]") for y in range(1, 6)]
    for (x, y, z), b in outside:
        s.set(x, y, z, b)
        if y == 1:
            s.set(x, 0, z, "structurize:blocksolidsubstitution")   # whatever ground is there, made solid

    # inside: the hut block, the workbench corner and the racks on the back wall
    s.set_anchor(*HUT_SPOT, HUT)
    s.tag(*HUT_SPOT, "name=" + LOOK_LABEL)
    s.set(-4, 1, -3, "minecraft:crafting_table")
    if level >= 2:
        s.set(-3, 1, -3, "minecraft:loom[facing=south]")
        s.set(-4, 1, 3, "minecraft:spruce_stairs[facing=north]")   # the shop bench behind the window
    if level >= 3:
        s.set(-2, 1, -3, "minecraft:stonecutter[facing=south]")
    for p in GROUND_RACKS[:{1: 2, 2: 3, 3: 4, 4: 5, 5: 6}[level]]:
        s.set(*p, RACK)
    # the storage room (S10e) goes in last: the corner at levels 1 and 2, the attic from level 3
    for p, b in storage_room(level).items():
        s.set(*p, b)
    # the storeroom's spots (S10g1): position tags in the hut block's data, not blocks
    for p, name in storeroom_tags(level).items():
        s.tag(*p, name)
    return s


# the S5 racks on the ground floor's back wall, on the design's barrel spots (2, 3, 4, 5, 6 of them at levels 1 to 5)
GROUND_RACKS = [(1, 1, -3), (2, 1, -3), (3, 1, -3), (4, 1, -3), (1, 2, -3), (3, 2, -3)]
# the attic's racks along the back wall upstairs (S10e): 2, 4 and 6 at levels 3, 4 and 5, a rack stays a rack upwards
ATTIC_RACKS = {3: [(-2, 6, -3), (-1, 6, -3)],
               4: [(0, 6, -3), (1, 6, -3)],
               5: [(-2, 7, -3), (-1, 7, -3)]}
BARREL_UP = "minecraft:barrel[facing=up]"


def storage_room(level):
    """The Decorator's storage room (the owner's decision of 20 Nov 2026, S10e), {position: block}: where last year's
    decorations wait for the next festival, in MineColonies racks (a hut's stock lives only in racks) with barrels
    beside them as crates, so it shows what the room is. Levels 1 and 2 have no upper floor, so the room is the
    ground floor's back-east corner: the S5 racks with barrels stacked in the corner. From level 3 it is the attic, the
    back wall of the upper floor: 2, 4 and 6 racks at levels 3, 4 and 5 (12 racks in the hut at level 5) and crates.
    Only racks and vanilla blocks (the Builder's bill gets no decoration), all inside the walls, nothing on the ladder's
    way up, away from the front windows; build_pack.py checks these rules. The S5 racks are not part of it.

    S10g1 (docs/storage-look-design.md, variant A "tidy storeroom", picked 21 Nov 2026) adds the barrels the
    Decorator's packed pieces stand on, the same kind of block the bill already has (storeroom_barrels): at level 1 and
    level 5 where design/src/storage_round.py places them, at levels 2 to 4 laid out by this chunk on the same rules."""
    if not 1 <= level <= 5:
        raise ValueError(level)
    room = {}
    if level <= 2:
        room[(4, 1, -3)] = BARREL_UP
        room[(4, 2, -3)] = BARREL_UP
        room[(4, 1, -2)] = "minecraft:barrel[facing=west]"
    else:
        for lv in range(3, level + 1):
            for p in ATTIC_RACKS[lv]:
                room[p] = RACK
        room[(-3, 6, -3)] = BARREL_UP
        room[(-4, 6, -3)] = "minecraft:barrel[facing=east]"
        if level >= 4:
            room[(-4, 6, -2)] = BARREL_UP
        if level >= 5:
            room[(-3, 7, -3)] = BARREL_UP
            room[(2, 6, -3)] = "minecraft:barrel[facing=west]"
            room[(3, 6, -3)] = "minecraft:loom[facing=south]"
    room.update(storeroom_barrels(level))
    return room


BARREL_WEST = "minecraft:barrel[facing=west]"
# Variant A's barrels (S10g1), on top of the S10e room. Levels 1 and 5 are storage_round.py's plan("A", 1 / 5): two
# standing side by side and one lying at level 1; a stack of two in the east corner, one on the west stack and one on
# the east side at level 5. Level 2: its third rack takes level 1's barrel cell at (3, 1, -3), and a barrel in front
# of that rack would wall it in, so the standing pair moves west along the back wall, next to the racks (0 and -1);
# the lying one stays. Levels 3 and 4: the attic of level 5 one barrel lower (single barrels where level 5 stacks
# two), with level 5's barrels at (-4, 6, -2) and (2, 6, -3) from level 3 on, so every level carries the same six
# pieces as level 5.
STOREROOM_BARRELS = {
    1: {(3, 1, -3): BARREL_UP, (3, 1, -2): BARREL_UP, (4, 1, 0): BARREL_WEST},
    2: {(-1, 1, -3): BARREL_UP, (0, 1, -3): BARREL_UP, (4, 1, 0): BARREL_WEST},
    3: {(4, 6, -3): BARREL_UP, (4, 6, -1): BARREL_UP, (-4, 6, -2): BARREL_UP, (2, 6, -3): BARREL_WEST},
    4: {(4, 6, -3): BARREL_UP, (4, 6, -1): BARREL_UP, (2, 6, -3): BARREL_WEST},
    5: {(4, 6, -3): BARREL_UP, (4, 7, -3): BARREL_UP, (-4, 7, -2): BARREL_UP, (4, 6, -1): BARREL_UP},
}


def storeroom_barrels(level):
    """The barrels variant A adds to the storage room at `level` (S10g1), {position: block}."""
    return dict(STOREROOM_BARRELS[level])


# ---------------------------------------------------------------------------------------------- the storeroom's spots
# Where the Decorator dresses the storeroom (S10g1, docs/storage-look-design.md 3.5): Structurize position tags in the
# hut block's blueprint data, not blocks (nothing goes on the Builder's bill). cs_pack_<set>_<piece> marks a packed
# piece standing on a barrel (a box swaps to its nearly empty state in the festival, a coil is taken away);
# cs_hang_<set>_<piece> a festival piece hung on a wall face. The set id is in every name (3.6); 0.1.0 has one set.
# The Java side reads them with decor/StoreroomSpots.java, which names the same pieces.
PACK_PREFIX, HANG_PREFIX = "cs_pack_", "cs_hang_"
SETS = ("winter",)
PACKED_PIECES = ("box_wreath", "box_garland", "box_lights", "box_lanterns", "box_gifts", "coil_garland", "coil_lights")
HUNG_PIECES = ("garland", "wreath", "lights")
FURNITURE = ("minecraft:barrel",)        # what a packed piece stands on (variant A: barrels only)


def _row(piece, cells):
    return {c: piece for c in cells}


# Level 1 and level 5 are storage_round.py's plan("A", 1 / 5), cell for cell. Level 2 keeps level 1's pieces on the
# moved barrels and its hung row; levels 3 and 4 carry level 5's six pieces one barrel lower and level 5's hung pieces.
# Nothing hangs over the upper attic racks' cells and nothing stands on the floor or above a rack (3.1).
_HANG_LOW = {**_row("garland", [(x, 4, -3) for x in range(1, 5)]), (2, 3, -3): "wreath",
             **_row("lights", [(4, 4, z) for z in range(-2, 3)])}
_HANG_ATTIC = {**_row("garland", [(x, 8, -3) for x in range(0, 4)]), (3, 7, -3): "wreath",
               **_row("lights", [(-4, 8, z) for z in (-1, 1, 2, 3)]), **_row("lights", [(4, 8, z) for z in range(-2, 4)])}
_PACK_ATTIC_LOW = {(-4, 7, -3): "box_wreath", (-3, 7, -3): "box_gifts", (-4, 7, -2): "box_lights",
                   (2, 7, -3): "box_lanterns", (4, 7, -3): "box_garland", (4, 7, -1): "coil_lights"}
STOREROOM = {
    1: {"pack": {(3, 2, -3): "box_lights", (3, 2, -2): "coil_garland", (4, 3, -3): "coil_lights",
                 (4, 2, -2): "box_gifts", (4, 2, 0): "box_wreath"},
        "hang": _HANG_LOW},
    2: {"pack": {(0, 2, -3): "box_lights", (-1, 2, -3): "coil_garland", (4, 3, -3): "coil_lights",
                 (4, 2, -2): "box_gifts", (4, 2, 0): "box_wreath"},
        "hang": _HANG_LOW},
    3: {"pack": _PACK_ATTIC_LOW, "hang": _HANG_ATTIC},
    4: {"pack": _PACK_ATTIC_LOW, "hang": _HANG_ATTIC},
    5: {"pack": {(-4, 7, -3): "box_wreath", (-3, 8, -3): "box_gifts", (-4, 8, -2): "box_lights",
                 (2, 7, -3): "box_lanterns", (4, 8, -3): "box_garland", (4, 7, -1): "coil_lights"},
        "hang": _HANG_ATTIC},
}


def storeroom_tags(level, set_id="winter"):
    """{position: tag name} of every storeroom spot of `level` (one tag per cell)."""
    if not 1 <= level <= 5:
        raise ValueError(level)
    tags = {p: PACK_PREFIX + set_id + "_" + piece for p, piece in STOREROOM[level]["pack"].items()}
    tags.update({p: HANG_PREFIX + set_id + "_" + piece for p, piece in STOREROOM[level]["hang"].items()})
    return tags


def parse_tag(name):
    """('pack' or 'hang', set id, piece) for one of our spot tags, or None for any other tag (or an unknown piece)."""
    for kind, prefix, pieces in (("pack", PACK_PREFIX, PACKED_PIECES), ("hang", HANG_PREFIX, HUNG_PIECES)):
        if name.startswith(prefix):
            rest = name[len(prefix):]
            for piece in pieces:
                if rest.endswith("_" + piece) and rest[:-len(piece) - 1] in SETS:
                    return kind, rest[:-len(piece) - 1], piece
    return None


# the order the facing rule looks round a spot, and the facing away from each side (x east, z south)
_SIDES = (((0, 0, -1), "south"), ((1, 0, 0), "west"), ((-1, 0, 0), "east"), ((0, 0, 1), "north"))
_NOT_FULL = ("lantern", "ladder", "glass", "_pane", "_bars", "chain", "torch", "stonecutter", "_door", "_trapdoor",
             "_stairs", "_slab", "_carpet", "_fence", "_wall")


def full_wall_block(state):
    """True for a whole block a wall piece can hang on, and a standing piece can turn its back to: not a thin or
    partial block and not furniture (a barrel or a rack is what the pieces stand on, not a wall)."""
    if state is None:
        return False
    name = state.split("[", 1)[0]
    return name not in FURNITURE and name != RACK and not any(w in name for w in _NOT_FULL)


def spot_facing(get, pos, kind):
    """The facing of the piece at `pos` (S10g2 works it out in the game the same way, docs/storage-look-design.md 3.5):
    away from the first whole wall block beside it, looking north, east, west and south in that order (the back wall
    first). A hung piece needs one (None when there is none); a standing piece with none faces south, into the room.
    `get(x, y, z)` gives the block state at a cell or None for air."""
    x, y, z = pos
    for (dx, dy, dz), facing in _SIDES:
        if full_wall_block(get(x + dx, y + dy, z + dz)):
            return facing
    return None if kind == "hang" else "south"


def piece_state(piece, kind, festival, facing):
    """The block state the Decorator places at a spot: packed (festival False) or in the festival. A coil is taken off
    in the festival (None)."""
    if kind == "hang":
        name = {"garland": "garland", "wreath": "wreath", "lights": "fairy_lights"}[piece]
        lit = ",lit=false" if name == "fairy_lights" else ""
        return f"colony_seasons:{name}[facing={facing}{lit}]"
    what, sort = piece.split("_", 1)
    if what == "coil":
        return None if festival else f"colony_seasons:storage_coil[facing={facing},kind={sort}]"
    return f"colony_seasons:storage_box[content={sort},facing={facing},fill={'low' if festival else 'full'}]"


def storeroom_set(level, structure, festival):
    """{position: block state} of the storeroom dressing of `level` in `structure` (its blueprint), packed or in the
    festival: what the Decorator's set looks like with the facings worked out (renders and build_pack's walk check)."""
    out = {}
    for pos, name in storeroom_tags(level).items():
        kind, _, piece = parse_tag(name)
        if kind == "hang" and not festival:
            continue
        state = piece_state(piece, kind, festival, spot_facing(structure.get, pos, kind))
        if state is not None:
            out[pos] = state
    return out


def _upper_floor(s, level):
    """Timber-framed plaster upper floor, y = 5..8 (design build_a), and what is inside it."""
    x0, x1, z0, z1 = X0, X1, Z0, Z1
    for x in range(x0 + 1, x1):
        for z in range(z0 + 1, z1):
            s.set(x, 5, z, "minecraft:spruce_planks")
    for y in range(5, 9):
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                s.set(x, y, z, PLASTER)
        for z in range(z0, z1 + 1):
            for x in (x0, x1):
                s.set(x, y, z, PLASTER)
    for x in range(x0, x1 + 1):
        for z in (z0, z1):
            s.set(x, 5, z, "minecraft:stripped_spruce_log[axis=x]")
            s.set(x, 8, z, "minecraft:stripped_spruce_log[axis=x]")
    for z in range(z0, z1 + 1):
        for x in (x0, x1):
            s.set(x, 5, z, "minecraft:stripped_spruce_log[axis=z]")
            s.set(x, 8, z, "minecraft:stripped_spruce_log[axis=z]")
    for x in (-5, -2, 1, 5):
        for z in (z0, z1):
            for y in (6, 7):
                s.set(x, y, z, "minecraft:spruce_log[axis=y]")
    for z in (-4, 0, 4):
        for x in (x0, x1):
            for y in (6, 7):
                s.set(x, y, z, "minecraft:spruce_log[axis=y]")
    # upper windows: three in the front plus a wide one under the beam, two on the east side
    for x in (-4, -3, 3):
        s.set(x, 6, z1, "minecraft:glass_pane")
        s.set(x, 7, z1, "minecraft:glass_pane")
    for x in (-1, 0):
        s.set(x, 7, z1, "minecraft:glass_pane")
    for z in (-2, 2):
        s.set(x1, 6, z, "minecraft:glass_pane")
        s.set(x1, 7, z, "minecraft:glass_pane")
    # inside (not in the design, which shows no interior up here): the ladder up through the floor on the west
    # wall, a lantern under each half of the ground-floor ceiling, a tie beam over the upper room with its lantern
    for y in range(1, 6):
        s.set(-4, y, 0, "minecraft:ladder[facing=east]")
    s.set(-2, 4, 0, LANTERN_HANGING)
    s.set(2, 4, 0, LANTERN_HANGING)
    for z in range(z0, z1 + 1):
        s.set(0, 9, z, "minecraft:stripped_spruce_log[axis=z]")
    s.set(0, 8, 0, LANTERN_HANGING)


# How level 5 differs from design/src/huts.py build_a, once the design's ids are mapped to the shipped ones (its
# colonyseasons:wreath[facing=south,style=classic] is our colony_seasons:wreath[facing=south]) and its ground patch is
# left out (grass, dirt and snow round the hut, and the path beyond the front apron, which is outside the hut's box). build_pack.py checks that
# level 5 and the design differ in exactly these cells.
DESIGN_CHANGES = {
    "hut block": "the hut block on the design's hut spot, which the design leaves as air",
    "lantern bracket": "(1, 4, 5) is the fence bracket the design meant; a top trapdoor there would drop the lantern",
    "outside ladder": "the ladder on the east wall stands at z = 0, not z = -1, where two rungs hung on window glass",
    "racks": "the six barrels inside on the back wall are MineColonies racks: a hut stores its stock only in racks",
    "ladder inside": "a ladder on the west wall, (-4, 1..5, 0), through the upper floor: the way upstairs",
    "lights inside": "lanterns under the ceiling and under a tie beam upstairs (the design has no light inside)",
    "storage room": "the attic's racks, crates and loom along the back wall upstairs, storage_room(5) (the design leaves "
                    "the upper room empty; S10e)",
}


def design_diff():
    """{position: DESIGN_CHANGES key} of every cell where level 5 is meant to differ from the design."""
    d = {HUT_SPOT: "hut block", (1, 4, Z1 + 1): "lantern bracket"}
    for p in GROUND_RACKS:
        d[p] = "racks"
    for y in range(1, 6):
        d[(-4, y, 0)] = "ladder inside"
        d[(6, y, -1)] = "outside ladder"
        d[(6, y, 0)] = "outside ladder"
    for p in [(-2, 4, 0), (2, 4, 0), (0, 8, 0)] + [(0, 9, z) for z in range(Z0, Z1 + 1)]:
        d[p] = "lights inside"
    for p in storage_room(5):
        d[p] = "storage room"
    return d
