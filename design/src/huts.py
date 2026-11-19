"""SET 2: the Decorator's hut, mid level - three look options as voxel builds.

x east, y up, z south; the front faces south (+z); ground top at y=0, floors at y=0.
Every hut has: a door, windows, a workbench corner, barrels for storage and a clear 1x1 spot for
the hut block (HUT_SPOT, marked with tag 'hut' - left as air in the render).
Custom blocks used: colonyseasons:wreath, string_bunting (invented for B), ornament_leaves, gift_pile.
"""
import math

from common import Structure, h01

HUT_SPOT = {}


def ground(s, r=7):
    for x in range(-r, r + 1):
        for z in range(-r, r + 1):
            s.set(x, -1, z, "minecraft:dirt")
            s.set(x, 0, z, "minecraft:grass_block")
            s.set(x, 1, z, "minecraft:snow[layers=%d]" % (2 if h01(x, 1, z, 4) < 0.35 else 1))


def clear_box(s, x0, y0, z0, x1, y1, z1):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                s.set(x, y, z, None)


def path(s, cells):
    for (x, z) in cells:
        s.set(x, 0, z, "minecraft:dirt_path")
        s.set(x, 1, z, None)


def stone_mix(x, y, z, salt=3):
    k = h01(x, y, z, salt)
    return ("minecraft:stone_bricks" if k < 0.45 else "minecraft:cobblestone" if k < 0.75
            else "minecraft:mossy_cobblestone" if k < 0.85 else "minecraft:andesite")


def gable_x(s, x0, x1, z0, z1, y, stair, full, ov=1, steep=False, slab=None):
    """Gable roof with the ridge along x over the box x0..x1 / z0..z1 (walls top at y-1).
    Returns {(x,z): top y of roof surface} for filling gables."""
    za, zb = z0 - ov, z1 + ov
    i = 0
    heights = {}
    while za + i <= zb - i:
        zn, zs = za + i, zb - i
        step = 2 if steep else 1
        yy = y + i * step
        for x in range(x0 - ov, x1 + ov + 1):
            if zn == zs:
                s.set(x, yy, zn, full)
                if steep:
                    s.set(x, yy - 1, zn, full)
                if slab:
                    s.set(x, yy + 1, zn, slab)
            else:
                s.set(x, yy, zs, f"{stair}[facing=north,half=bottom]")
                s.set(x, yy, zn, f"{stair}[facing=south,half=bottom]")
                if steep and i > 0:
                    s.set(x, yy - 1, zs, full)
                    s.set(x, yy - 1, zn, full)
        heights[zn] = yy
        heights[zs] = yy
        i += 1
    return heights


def gable_z(s, x0, x1, z0, z1, y, stair, full, ov=1, steep=False, slab=None):
    """Gable roof with the ridge along z (gable ends face north/south)."""
    xa, xb = x0 - ov, x1 + ov
    i = 0
    heights = {}
    while xa + i <= xb - i:
        xw, xe = xa + i, xb - i
        step = 2 if steep else 1
        yy = y + i * step
        for z in range(z0 - ov, z1 + ov + 1):
            if xw == xe:
                s.set(xw, yy, z, full)
                if steep:
                    s.set(xw, yy - 1, z, full)
                if slab:
                    s.set(xw, yy + 1, z, slab)
            else:
                s.set(xe, yy, z, f"{stair}[facing=west,half=bottom]")
                s.set(xw, yy, z, f"{stair}[facing=east,half=bottom]")
                if steep and i > 0:
                    s.set(xe, yy - 1, z, full)
                    s.set(xw, yy - 1, z, full)
        heights[xw] = yy
        heights[xe] = yy
        i += 1
    return heights


def fill_gable_z(s, heights, x0, x1, zface, y0, block_fn):
    """Fill a gable end (wall plane z=zface) under a ridge-along-z roof, from y0 up to below the roof."""
    for x in range(x0, x1 + 1):
        top = heights.get(x)
        if top is None:
            continue
        for y in range(y0, top):
            if s.get(x, y, zface) is None:
                s.set(x, y, zface, block_fn(x, y))


def fill_gable_x(s, heights, z0, z1, xface, y0, block_fn):
    for z in range(z0, z1 + 1):
        top = heights.get(z)
        if top is None:
            continue
        for y in range(y0, top):
            if s.get(xface, y, z) is None:
                s.set(xface, y, z, block_fn(z, y))


def mushroom_plaster():
    return "minecraft:mushroom_stem[north=true,south=true,east=true,west=true,up=true,down=true]"


# =====================================================================  A: workshop with shopfront
def build_a():
    """A 'Workshop with shopfront' - 11x9, stone ground floor with a big shop window and striped
    awning, timber-framed plaster upper floor, dark oak gable roof (ridge along x), wreath over the
    door, wall lantern, barrels and a ladder outside."""
    s = Structure("hut_a_workshop")
    ground(s)
    x0, x1, z0, z1 = -5, 5, -4, 4
    clear_box(s, x0, 1, z0, x1, 1, z1)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            s.set(x, 0, z, "minecraft:stone_bricks" if edge else "minecraft:spruce_planks")
    # ground floor: stone, y=1..4
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
    # shopfront: posts, sill, big window x -4..0, y 2..3
    for x in (-5, 1):
        for y in range(1, 5):
            s.set(x, y, z1, "minecraft:spruce_log[axis=y]")
    for x in range(-4, 1):
        s.set(x, 1, z1, "minecraft:spruce_planks")
        s.set(x, 2, z1, "minecraft:glass_pane")
        s.set(x, 3, z1, "minecraft:glass_pane")
        s.set(x, 4, z1, "minecraft:stripped_spruce_log[axis=x]")
    # window sill outside
    for x in range(-4, 1):
        s.set(x, 1, z1 + 1, "minecraft:spruce_trapdoor[facing=south,half=top,open=false]")
    # door x=3 with log frame
    for y in range(1, 4):
        s.set(2, y, z1, "minecraft:spruce_log[axis=y]")
        s.set(4, y, z1, "minecraft:spruce_log[axis=y]")
    s.set(3, 1, z1, "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=false]")
    s.set(3, 2, z1, "minecraft:spruce_door[facing=north,half=upper,hinge=left,open=false]")
    s.set(3, 3, z1, "minecraft:stripped_spruce_log[axis=x]")
    s.set(2, 4, z1, "minecraft:stripped_spruce_log[axis=x]"); s.set(3, 4, z1, "minecraft:stripped_spruce_log[axis=x]")
    s.set(4, 4, z1, "minecraft:stripped_spruce_log[axis=x]")
    s.set(3, 3, z1 + 1, "colonyseasons:wreath[facing=south,style=classic]")
    s.set(3, 0, z1 + 1, "minecraft:spruce_slab[type=top]")  # door step
    # wall lantern on a fence bracket beside the door
    s.set(5, 3, z1 + 1, None)
    s.set(1, 4, z1 + 1, "minecraft:spruce_fence")
    s.set(1, 3, z1 + 1, "minecraft:lantern[hanging=true]")
    # side windows (east) ground floor
    for z in (-1, 1):
        s.set(x1, 2, z, "minecraft:glass_pane")
        s.set(x1, 3, z, "minecraft:glass_pane")
    # awning over the shop window: spruce trapdoors with red/white carpet stripes
    for x in range(-5, 2):
        s.set(x, 4, z1 + 1, "minecraft:spruce_trapdoor[facing=south,half=top,open=false]")
        s.set(x, 5, z1 + 1, "minecraft:red_carpet" if x % 2 else "minecraft:white_carpet")
    # upper floor y=5..8: timber frame, plaster infill
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x0 < x < x1 and z0 < z < z1:
                s.set(x, 5, z, "minecraft:spruce_planks")
    for y in range(5, 9):
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                s.set(x, y, z, mushroom_plaster())
        for z in range(z0, z1 + 1):
            for x in (x0, x1):
                s.set(x, y, z, mushroom_plaster())
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
    # upper windows: front 2, east 1
    for x in (-4, -3, 3):
        s.set(x, 6, z1, "minecraft:glass_pane"); s.set(x, 7, z1, "minecraft:glass_pane")
    for x in (-1, 0):
        s.set(x, 7, z1, "minecraft:glass_pane")
    for z in (-2, 2):
        s.set(x1, 6, z, "minecraft:glass_pane"); s.set(x1, 7, z, "minecraft:glass_pane")
    # shutters beside the upper front windows
    s.set(2, 6, z1 + 1, "minecraft:spruce_trapdoor[facing=south,half=bottom,open=true]") if False else None
    # roof: dark oak, ridge along x, 1 overhang
    hz = gable_x(s, x0, x1, z0, z1, 9, "minecraft:dark_oak_stairs", "minecraft:dark_oak_planks", ov=1,
                 slab="minecraft:dark_oak_slab[type=bottom]")
    plaster = lambda z, y: mushroom_plaster() if (z + y) % 5 else "minecraft:spruce_log[axis=y]"
    fill_gable_x(s, hz, z0, z1, x0, 9, plaster)
    fill_gable_x(s, hz, z0, z1, x1, 9, plaster)
    s.set(x1, 10, 0, "minecraft:glass_pane"); s.set(x1, 11, 0, "minecraft:glass_pane")
    s.set(x1, 9, -1, "minecraft:spruce_log[axis=y]"); s.set(x1, 9, 1, "minecraft:spruce_log[axis=y]")
    # upside-down stairs under the gable verge (east, visible)
    # outside: barrels + hay, ladder on the east wall
    s.set(6, 1, 3, "minecraft:barrel[facing=up]")
    s.set(6, 2, 3, "minecraft:barrel[facing=up]")
    s.set(6, 1, 2, "minecraft:barrel[facing=south]")
    s.set(-6, 1, 3, "minecraft:barrel[facing=up]")
    s.set(-6, 1, 2, "minecraft:hay_block[axis=x]")
    for y in range(1, 6):
        s.set(6, y, -1, "minecraft:ladder[facing=east]")
    # interior: workbench corner (crafting table, loom, stonecutter), barrels on the back wall
    s.set(-4, 1, -3, "minecraft:crafting_table"); s.set(-3, 1, -3, "minecraft:loom[facing=south]")
    s.set(-2, 1, -3, "minecraft:stonecutter[facing=south]")
    for x in range(1, 5):
        s.set(x, 1, -3, "minecraft:barrel[facing=up]")
        if x % 2:
            s.set(x, 2, -3, "minecraft:barrel[facing=up]")
    s.set(-4, 1, 3, "minecraft:spruce_stairs[facing=north]")  # shop bench behind the window
    HUT_SPOT["A"] = (0, 1, 0)
    for x in range(-6, 7):
        for z in (5, 6):
            if s.get(x, 0, z) == "minecraft:grass_block":
                s.set(x, 0, z, stone_mix(x, 0, z, 9) if z == 5 or abs(x - 3) <= 1 else "minecraft:grass_block")
                if z == 5 or abs(x - 3) <= 1:
                    if s.get(x, 1, z) and "snow" in s.get(x, 1, z):
                        s.set(x, 1, z, None)
    path(s, [(3, 7)])
    return s


# =====================================================================  B: steep-roof cottage
def build_b():
    """B 'Steep-roof cottage' - 9x9, cobble plinth, white plaster with dark oak frame, steep (2:1)
    deepslate-tile roof with the gable to the front, brick chimney, bunting along the eaves."""
    s = Structure("hut_b_cottage")
    ground(s)
    x0, x1, z0, z1 = -4, 4, -4, 4
    clear_box(s, x0, 1, z0, x1, 1, z1)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            s.set(x, 0, z, "minecraft:cobblestone" if edge else "minecraft:spruce_planks")
    for y in range(1, 5):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if x in (x0, x1) or z in (z0, z1):
                    if y == 1:
                        b = stone_mix(x, y, z, 5)
                    elif y == 4:
                        b = "minecraft:stripped_dark_oak_log[axis=%s]" % ("x" if z in (z0, z1) else "z")
                    else:
                        b = mushroom_plaster()
                    s.set(x, y, z, b)
    for (x, z) in ((x0, z0), (x0, z1), (x1, z0), (x1, z1)):
        for y in range(1, 5):
            s.set(x, y, z, "minecraft:dark_oak_log[axis=y]")
    for z in (-2, 2):
        for x in (x0, x1):
            for y in (2, 3):
                s.set(x, y, z, "minecraft:dark_oak_log[axis=y]")
    for x in (-2, 2):
        for y in (2, 3):
            s.set(x, y, z0, "minecraft:dark_oak_log[axis=y]")
    # door + windows (front), windows (sides)
    s.set(0, 1, z1, "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=false]")
    s.set(0, 2, z1, "minecraft:spruce_door[facing=north,half=upper,hinge=left,open=false]")
    s.set(0, 3, z1, "minecraft:stripped_dark_oak_log[axis=x]")
    for x in (-2, 2):
        s.set(x, 2, z1, "minecraft:glass_pane"); s.set(x, 3, z1, "minecraft:glass_pane")
        s.set(x, 1, z1 + 1, "minecraft:spruce_trapdoor[facing=south,half=top,open=false]")
        for sx in ((x - 1,) if x < 0 else (x + 1,)):   # outer shutter only (door lanterns sit inside)
            for y in (2, 3):
                s.set(sx, y, z1 + 1, "minecraft:spruce_trapdoor[facing=%s,half=bottom,open=true]" % ("east" if sx < x else "west"))
    for z in (-3, 0, 3):
        s.set(x1, 2, z, "minecraft:glass_pane"); s.set(x1, 3, z, "minecraft:glass_pane")
    s.set(x0, 2, 0, "minecraft:glass_pane"); s.set(x0, 3, 0, "minecraft:glass_pane")
    # small porch roof over the door: spruce stairs on fence posts
    for x in (-1, 0, 1):
        s.set(x, 4, z1 + 1, "minecraft:spruce_stairs[facing=north,half=bottom]")
    s.set(-1, 3, z1 + 1, "minecraft:spruce_trapdoor[facing=south,half=top,open=false]")
    s.set(1, 3, z1 + 1, "minecraft:spruce_trapdoor[facing=south,half=top,open=false]")
    s.set(1, 2, z1 + 1, "minecraft:lantern[hanging=true]") if False else None
    # steep roof, ridge along z, gable to the front
    hx = gable_z(s, x0, x1, z0, z1, 5, "minecraft:deepslate_tile_stairs", "minecraft:deepslate_tiles", ov=1,
                 steep=True, slab="minecraft:deepslate_tile_slab[type=bottom]")
    timber = lambda x, y: ("minecraft:stripped_dark_oak_log[axis=x]" if y == 8 else
                           "minecraft:dark_oak_log[axis=y]" if x in (-3, 3) and y < 8 else mushroom_plaster())
    fill_gable_z(s, hx, x0, x1, z1, 5, timber)
    fill_gable_z(s, hx, x0, x1, z0, 5, timber)
    # attic window in the front gable
    s.set(0, 6, z1, "minecraft:glass_pane"); s.set(0, 7, z1, "minecraft:glass_pane")
    s.set(0, 5, z1 + 1, "minecraft:spruce_trapdoor[facing=south,half=top,open=false]")  # attic window sill
    # brick chimney through the east slope, campfire on top for smoke
    for y in range(1, 15):
        s.set(2, y, -2, "minecraft:bricks")
    s.set(2, 15, -2, "minecraft:campfire[lit=true,facing=south,signal_fire=false]")
    s.set(2, 14, -2, "minecraft:bricks")
    # bunting along both eaves and across the front at eave height
    for z in range(z0, z1 + 1):
        v = "0" if z % 2 == 0 else "1"
        # one block below the steep eave, so the flags are not hidden under the overhang
        s.set(x1 + 1, 3, z, f"colonyseasons:string_bunting[facing=east,variant={v}]")
        s.set(x0 - 1, 3, z, f"colonyseasons:string_bunting[facing=west,variant={v}]")
    for x in (-4, -3, -2, 2, 3, 4):
        v = "0" if x % 2 == 0 else "1"
        s.set(x, 4, z1 + 1, f"colonyseasons:string_bunting[facing=south,variant={v}]")
    # lanterns either side of the door, hanging from fence brackets that also carry the porch roof
    for x in (-1, 1):
        s.set(x, 3, z1 + 1, "minecraft:spruce_fence")
        s.set(x, 2, z1 + 1, "minecraft:lantern[hanging=true]")
    # interior: workbench corner, barrels, hut block spot
    s.set(-3, 1, -3, "minecraft:crafting_table"); s.set(-2, 1, -3, "minecraft:loom[facing=south]")
    for z in (-3, -2, -1):
        s.set(3, 1, z, "minecraft:barrel[facing=up]")
    HUT_SPOT["B"] = (0, 1, 0)
    # outside: wood pile + barrel by the side
    s.set(5, 1, 3, "minecraft:barrel[facing=up]")
    for z in (1, 2):
        s.set(5, 1, z, "minecraft:spruce_log[axis=z]") if False else None
    path(s, [(0, z) for z in range(5, 8)])
    return s


# =====================================================================  C: storehouse with hoist
def build_c():
    """C 'Storehouse with hoist' - narrow and tall: 7x9 body (stone ground floor, oak-boarded upper
    floor, spruce roof with the gable to the front) plus a 2-wide open lean-to workbench on the east
    side (9x9 in all); loft door under the gable with a hoist beam, chain, hanging lantern and a
    hoisted barrel; decorations stored in the loft."""
    s = Structure("hut_c_storehouse")
    ground(s)
    x0, x1, z0, z1 = -3, 3, -4, 4
    clear_box(s, x0, 1, z0, x1 + 2, 1, z1)
    for x in range(x0, x1 + 3):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            s.set(x, 0, z, "minecraft:stone_bricks" if (edge and x <= x1) else "minecraft:spruce_planks")
    # ground floor stone y=1..4
    for y in range(1, 5):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if x in (x0, x1) or z in (z0, z1):
                    s.set(x, y, z, stone_mix(x, y, z, 7))
    for (x, z) in ((x0, z0), (x0, z1), (x1, z0), (x1, z1)):
        for y in range(1, 5):
            s.set(x, y, z, "minecraft:stone_bricks" if y % 2 else "minecraft:polished_andesite")
    # big double door (front) and a window
    for x in (-1, 0):
        hinge = "left" if x == -1 else "right"
        s.set(x, 1, z1, f"minecraft:spruce_door[facing=north,half=lower,hinge={hinge},open=false]")
        s.set(x, 2, z1, f"minecraft:spruce_door[facing=north,half=upper,hinge={hinge},open=false]")
    for x in (-2, 1):
        for y in (1, 2, 3):
            s.set(x, y, z1, "minecraft:spruce_log[axis=y]")
    for x in (-1, 0):
        s.set(x, 3, z1, "minecraft:stripped_spruce_log[axis=x]")
    s.set(2, 2, z1, "minecraft:glass_pane"); s.set(2, 3, z1, "minecraft:glass_pane")
    for x in (-1, 0):
        s.set(x, 3, z1 + 1, "colonyseasons:string_garland[facing=south]")
    s.set(x0, 2, 0, "minecraft:glass_pane"); s.set(x0, 3, 0, "minecraft:glass_pane")
    # upper floor y=5..8: oak boards with spruce frame
    for x in range(x0 + 1, x1):
        for z in range(z0 + 1, z1):
            s.set(x, 5, z, "minecraft:spruce_planks")
    for y in range(5, 9):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if x in (x0, x1) or z in (z0, z1):
                    s.set(x, y, z, "minecraft:oak_planks")
    for (x, z) in ((x0, z0), (x0, z1), (x1, z0), (x1, z1)):
        for y in range(5, 9):
            s.set(x, y, z, "minecraft:spruce_log[axis=y]")
    for x in range(x0, x1 + 1):
        for z in (z0, z1):
            s.set(x, 5, z, "minecraft:stripped_spruce_log[axis=x]")
    for z in range(z0, z1 + 1):
        for x in (x0, x1):
            s.set(x, 5, z, "minecraft:stripped_spruce_log[axis=z]")
            if z in (-1, 2):
                for y in (6, 7, 8):
                    s.set(x, y, z, "minecraft:spruce_log[axis=y]")
    for z in (-3, 0, 1, 3):
        s.set(x1, 7, z, "minecraft:glass_pane") if z in (0, 1) else None
    s.set(x1, 6, 0, "minecraft:glass_pane"); s.set(x1, 7, 0, "minecraft:glass_pane")
    s.set(x1, 6, 1, "minecraft:glass_pane"); s.set(x1, 7, 1, "minecraft:glass_pane")
    s.set(-2, 6, z1, "minecraft:glass_pane"); s.set(-2, 7, z1, "minecraft:glass_pane")
    s.set(2, 6, z1, "minecraft:glass_pane"); s.set(2, 7, z1, "minecraft:glass_pane")
    # loft floor
    for x in range(x0 + 1, x1):
        for z in range(z0 + 1, z1):
            s.set(x, 9, z, "minecraft:spruce_planks")
    # roof: spruce, ridge along z, front gable
    hx = gable_z(s, x0, x1, z0, z1, 9, "minecraft:spruce_stairs", "minecraft:spruce_planks", ov=1,
                 slab="minecraft:spruce_slab[type=bottom]")
    boards = lambda x, y: "minecraft:spruce_log[axis=y]" if x in (-2, 2) else "minecraft:oak_planks"
    fill_gable_z(s, hx, x0, x1, z1, 9, boards)
    fill_gable_z(s, hx, x0, x1, z0, 9, boards)
    for x in range(x0, x1 + 1):
        s.set(x, 9, z1, "minecraft:stripped_spruce_log[axis=x]")
    # loft door (open) under the gable, hoist beam, lantern, chain with a hoisted barrel
    s.set(0, 10, z1, "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=true]")
    s.set(0, 11, z1, "minecraft:spruce_door[facing=north,half=upper,hinge=left,open=true]")
    s.set(0, 9, z1 + 1, "minecraft:spruce_slab[type=top]")
    for z in (z1, z1 + 1, z1 + 2):
        s.set(0, 13, z, "minecraft:stripped_spruce_log[axis=z]")
    s.set(0, 12, z1 + 1, "minecraft:lantern[hanging=true]")
    for y in (12, 11, 10, 9, 8):
        s.set(0, y, z1 + 2, "minecraft:chain[axis=y]")
    s.set(0, 7, z1 + 2, "minecraft:barrel[facing=up]")
    # decorations stored in the loft, seen through the door
    s.set(0, 10, z1 - 1, "colonyseasons:gift_pile[facing=south]")
    s.set(-1, 10, z1 - 1, "colonyseasons:ornament_leaves[style=mixed]")
    s.set(1, 10, z1 - 2, "colonyseasons:ornament_leaves[style=mixed]")
    s.set(-1, 11, z1 - 2, "colonyseasons:ornament_leaves[style=garland]")
    # east lean-to workshop (open front), x 4..5
    for z in range(-2, 3):
        s.set(5, 1, z, None)
    for z in (-2, 2):
        for y in (1, 2, 3):
            s.set(5, y, z, "minecraft:spruce_fence")
    for z in range(-3, 4):
        s.set(4, 5, z, "minecraft:spruce_stairs[facing=west,half=bottom]")
        s.set(5, 4, z, "minecraft:spruce_stairs[facing=west,half=bottom]")
        s.set(6, 3, z, "minecraft:spruce_stairs[facing=west,half=bottom]") if False else None
    for z in range(-2, 3):
        s.set(4, 4, z, "minecraft:spruce_planks")
    s.set(4, 1, -1, "minecraft:crafting_table")
    s.set(4, 1, 0, "minecraft:loom[facing=east]")
    s.set(4, 1, 1, "minecraft:barrel[facing=up]")
    s.set(4, 2, 1, "minecraft:barrel[facing=up]")
    s.set(5, 1, -1, "minecraft:spruce_slab[type=bottom]") if False else None
    s.set(4, 1, 2, "minecraft:barrel[facing=east]")
    # interior storage + hut block spot
    for x in (-2, -1, 1, 2):
        s.set(x, 1, -3, "minecraft:barrel[facing=up]")
        s.set(x, 2, -3, "minecraft:barrel[facing=up]")
    HUT_SPOT["C"] = (0, 1, -1)
    path(s, [(-1, z) for z in range(5, 8)] + [(0, z) for z in range(5, 8)])
    return s


def snow_under_sills(s):
    """Grass under a trapdoor sill would show as a green square in the snow: use a snow block there."""
    for (x, y, z), b in list(s.blocks.items()):
        if y == 0 and b.startswith("minecraft:grass_block"):
            above = s.get(x, 1, z)
            if above and "snow" not in above and ("trapdoor" in above or "slab" in above):
                s.set(x, 0, z, "minecraft:snow_block")


def _finish(fn):
    def wrapped():
        s = fn()
        snow_under_sills(s)
        return s
    wrapped.__doc__ = fn.__doc__
    return wrapped


build_a, build_b, build_c = _finish(build_a), _finish(build_b), _finish(build_c)

BUILDS = {"A": ("Workshop with shopfront", build_a), "B": ("Steep-roof cottage", build_b),
          "C": ("Storehouse with hoist", build_c)}

