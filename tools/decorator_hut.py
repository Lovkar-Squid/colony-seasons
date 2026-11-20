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
    way up, away from the front windows; build_pack.py checks these rules. The S5 racks are not part of it."""
    if not 1 <= level <= 5:
        raise ValueError(level)
    room = {}
    if level <= 2:
        room[(4, 1, -3)] = BARREL_UP
        room[(4, 2, -3)] = BARREL_UP
        room[(4, 1, -2)] = "minecraft:barrel[facing=west]"
        return room
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
    return room


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
