"""SETS 3 and 4: wreath and wall string shown on a small colony wall mock-up (5 wide, 4 high).

Wall plane x 0..4 at z=0, facing south: stone-brick base course with an oak door in the middle,
spruce-log posts, plaster infill (mushroom stem), stripped spruce top beam, a dark oak eave above.
The wreath hangs above the door at (2, 2, 1); the strings run along under the eave at y=3, z=1.
"""
from common import Structure, h01

PLASTER = "minecraft:mushroom_stem[north=true,south=true,east=true,west=true,up=true,down=true]"


def wall():
    s = Structure("wall_mockup")
    for x in range(-1, 6):
        for z in range(-1, 4):
            s.set(x, -2, z, "minecraft:dirt")
            s.set(x, -1, z, "minecraft:grass_block")
            if z >= 1:
                s.set(x, 0, z, "minecraft:snow[layers=%d]" % (2 if h01(x, 0, z, 3) < 0.3 else 1))
    for x in range(0, 5):
        k = h01(x, 0, 0, 2)
        s.set(x, 0, 0, "minecraft:stone_bricks" if k < 0.6 else "minecraft:cobblestone")
        s.set(x, 3, 0, "minecraft:stripped_spruce_log[axis=x]")
        for y in (1, 2):
            s.set(x, y, 0, PLASTER)
    for x in (0, 4):
        for y in (0, 1, 2):
            s.set(x, y, 0, "minecraft:spruce_log[axis=y]")
    s.set(2, 0, 0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false]")
    s.set(2, 1, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false]")
    s.set(2, 0, 1, None)
    s.set(2, -1, 1, "minecraft:dirt_path"); s.set(2, -1, 2, "minecraft:dirt_path"); s.set(2, 0, 2, None)
    # eave: planks over the wall, stairs overhanging the front, a second course behind
    for x in range(-1, 6):
        s.set(x, 4, 1, "minecraft:dark_oak_stairs[facing=north,half=bottom]")
        s.set(x, 4, 0, "minecraft:dark_oak_planks")
        s.set(x, 5, 0, "minecraft:dark_oak_stairs[facing=north,half=bottom]")
    return s


def wreath(style):
    def build():
        s = wall()
        s.set(2, 2, 1, f"colonyseasons:wreath[facing=south,style={style}]")
        return s
    return build


def string(block, variants=None):
    def build():
        s = wall()
        for x in range(0, 5):
            v = f",variant={variants[x % len(variants)]}" if variants else ""
            s.set(x, 3, 1, f"colonyseasons:{block}[facing=south{v}]")
        return s
    return build


WREATHS = {"A": ("Classic", wreath("classic")), "B": ("Frosted", wreath("frosted")), "C": ("Rustic", wreath("rustic"))}
STRINGS = {"A": ("Evergreen garland", string("string_garland")),
           "B": ("Lantern string", string("string_lanterns", ["0", "1", "2"])),
           "C": ("Fairy lights", string("string_fairy_lights"))}
