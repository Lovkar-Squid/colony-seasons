"""Storage room picture round (chunk S10g, 21 Nov 2026): the Decorator's Workshop storage room with more dressing, in
two variants and two states each, for docs/storage-look-design.md.

The owner looked at the S10e cutaways (docs/renders/s10e) and asked for more decoration in the storage room and, by
season or festival, things hung up inside or the pieces not in use packed away. This script draws:

  variant A  tidy storeroom: ordered barrels, labelled boxes and coils lying on the barrels
  variant B  busy loft: half-open crates, sacks, hay, a lantern on a fence bracket, a ladder, coils with a loose end

each in two states, "packed" (outside the festival window) and "festival" (in the window), at level 1 (the storage
corner) and level 5 (the attic), as cutaways like tools/render_s5.py's S10e ones (front wall and roof with the tie
beam left out), plus the level 5 exterior.

What is drawn follows the mechanism in docs/storage-look-design.md, and the script checks it before it renders:
  - the hut is read from the shipped blueprints (resources/blueprints/...), its racks keep their cells;
  - the blueprint layer ("furniture") goes only into cells the shipped blueprint leaves as air, strictly inside the
    walls, off the ladder and its exit; it is vanilla blocks, except B's crate, our own block (on the Builder's bill);
  - the Decorator's layer goes only into air (ledger rule): a standing piece on a barrel, crate or hay bale, a hung
    piece (festival only) on a wall or beam face with a full block behind it; nothing of his stands on the floor,
    nothing hangs in the packed state, nothing right behind a window;
  - nothing at all (his pieces, hung or standing, and the furniture) in the cell directly above a rack: MineColonies'
    full rack model draws boxes 6 px up into that cell, and the double rack's reach its edge;
  - a colonist still reaches the hut block and every floor cell from the door (tools/walkcheck.py), so no exterior
    cell changes and the walk path and the ladder stay clear.
Racks are drawn as single racks facing south with the full model while they hold anything (packed: all). In the game
neighbouring racks can join into doubles, whose boxes sit elsewhere, and the blueprint's racks carry no facing; the
rule above keeps either layout clear. The festival panels assume that the racks that held decorations are
empty while those are out on the colony's buildings; that is an assumption about the stock, not something the mod
sets.

New blocks (namespace colonyseasons, names st_*) are look drafts written to the scratch folder at run time, with
vanilla textures by reference, the mod's own decoration textures (resources/assets) and three small hand-drawn ones
(colour-coded paper labels, burlap and rope). Nothing here is shipped.

    python storage_round.py [--sheets]      (needs MC_JAR or SEASONS_LIBS, MINECOLONIES_JAR or a minecolonies jar
                                             in SEASONS_LIBS, and BLENDER, as the other design scripts)

Writes design/storage/<name>.png (one panel per scene, git-ignored) and design/storage/sheet-storage-A.png and
sheet-storage-B.png (committed). Coordinates as in tools/decorator_hut.py: x east, y up, z south, the hut block at
(0, 1, 0), the front faces south.
"""
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DESIGN = os.path.dirname(HERE)
ROOT = os.path.dirname(DESIGN)
TOOLS = os.path.join(ROOT, "tools")
OUT = os.path.join(DESIGN, "storage")
SCRATCH = os.environ.get("CS_SCRATCH") or os.path.join(tempfile.gettempdir(), "cs_storage")
ASSETS = os.path.join(SCRATCH, "assets")
os.environ["CS_SCRATCH"] = SCRATCH
os.environ["CS_ASSETS"] = ASSETS
os.environ.setdefault("MC_TEX_DIR", os.path.join(SCRATCH, "tex"))

sys.path.insert(0, TOOLS)
import paths  # noqa: E402
import voxel  # noqa: E402  (the repo's voxel.py: reads blueprints)
import decorator_hut as dh  # noqa: E402
from walkcheck import next_to, standable, walk  # noqa: E402

if not os.environ.get("MC_JAR"):
    os.environ["MC_JAR"] = paths.lib(os.environ.get("MC_ASSETS_JAR", "client-*-extra.jar"))
if not os.environ.get("MC_MOD_JARS"):
    os.environ["MC_MOD_JARS"] = os.environ.get("MINECOLONIES_JAR") or paths.lib("minecolonies-*.jar")
sys.path.insert(0, os.path.join(DESIGN, "tools"))
sys.path.insert(0, HERE)
import huts  # noqa: E402  (design/src: the picked look's ground and outside, as tools/render_s5.py uses them)

from PIL import Image, ImageDraw, ImageFont  # noqa: E402

NS = "colony_seasons"        # the mod's shipped blocks
D = "colonyseasons"          # the drafts of this round
PACK = os.path.join(ROOT, "resources", "blueprints", "colony_seasons", "colony_seasons")
SKY = [0.80, 0.85, 0.90]
RACK = dh.RACK
FACING_Y = {"north": 0, "east": 90, "south": 180, "west": 270}

# ------------------------------------------------------------------ draft textures (hand-drawn, own pixels)
CONTENT_COLOUR = {"wreath": (176, 52, 46), "garland": (62, 126, 56), "lights": (220, 170, 40),
                  "lanterns": (204, 104, 44), "gifts": (60, 98, 166)}         # the label's colour band
LABEL_SLOT = {"wreath": (0, 0), "garland": (8, 0), "lights": (0, 4), "lanterns": (8, 4), "gifts": (0, 8)}
LABEL_W, LABEL_H = 8, 3


def h01(x, y, salt=0):
    n = (x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)
    n = (n ^ (n >> 13)) * 1274126177
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def tex_label():
    """Card labels, 8 x 3 px each, wider than tall: a band in the content's colour along the top (colour-coded
    storage), one word of ink writing below it, a shaded lower edge. No frame and no centred mark."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    ink = (62, 50, 44, 255)
    for content, (x0, y0) in LABEL_SLOT.items():
        col = CONTENT_COLOUR[content]
        for x in range(LABEL_W):
            px[x0 + x, y0] = col + (255,)
            px[x0 + x, y0 + 1] = ((226, 216, 184) if h01(x0 + x, y0, 3) < 0.8 else (216, 205, 172)) + (255,)
            px[x0 + x, y0 + 2] = (190, 178, 144, 255)
        for x in range(1, 6 + int(h01(x0, y0, 5) * 2)):  # one word, 5 or 6 letters, from the left (two short
            px[x0 + x, y0 + 1] = ink                  # words read as a pair of eyes at this size)
    return im


def tex_burlap():
    """Coarse jute cloth: an open weave (light threads over darker gaps), a few slubs, no stripes."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            if x % 2 == 1 and y % 2 == 1:
                base = (122, 98, 62)                       # the gap between threads
            elif (x // 2 + y // 2) % 2 == 0:
                base = (184, 158, 106) if x % 2 == 0 else (176, 150, 100)
            else:
                base = (168, 142, 94) if y % 2 == 0 else (160, 134, 88)
            k = h01(x, y, 11)
            if k < 0.07:
                base = (198, 174, 124)
            elif k > 0.94:
                base = (140, 114, 74)
            px[x, y] = base + (255,)
    return im


def tex_rope():
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = ((201, 180, 138) if (x + y) % 4 < 2 else (150, 128, 88)) + (255,)
    return im


# ------------------------------------------------------------------ draft models
def el(frm, to, tex, faces=None, uv=None, rot=None, frot=None):
    e = {"from": list(frm), "to": list(to), "faces": {}}
    for f in faces or ("north", "south", "east", "west", "up", "down"):
        e["faces"][f] = {"texture": tex}
        if uv:
            u = uv.get(f, uv.get("*"))
            if u:
                e["faces"][f]["uv"] = list(u)
        if frot and f in frot:
            e["faces"][f]["rotation"] = frot[f]
    if rot:
        e["rotation"] = rot
    return e


def ring(cx, cz, outer, width, y0, y1, tex, top_uv, side_uv):
    """A coil lying flat: an eight-sided ring round (cx, cz), `outer` across, `width` thick (four straight bars and
    four corner bars turned 45 degrees, the steepest turn a model element may have)."""
    r, w = outer / 2.0, width
    side = 2 * r * 0.4142                      # the side of a regular octagon with apothem r
    uv = {"up": top_uv, "down": top_uv, "*": side_uv}
    els = [el([cx - side / 2, y0, cz - r], [cx + side / 2, y1, cz - r + w], tex, uv=uv),
           el([cx - side / 2, y0, cz + r - w], [cx + side / 2, y1, cz + r], tex, uv=uv),
           el([cx - r, y0, cz - side / 2], [cx - r + w, y1, cz + side / 2], tex, uv=uv),
           el([cx + r - w, y0, cz - side / 2], [cx + r, y1, cz + side / 2], tex, uv=uv)]
    d = (r - w / 2) * 0.7071
    for sx, sz in ((1, 1), (-1, -1), (1, -1), (-1, 1)):
        mx, mz = cx + sx * d, cz + sz * d
        els.append(el([mx - side * 0.56, y0, mz - w / 2], [mx + side * 0.56, y1, mz + w / 2], tex, uv=uv,
                      rot={"angle": 45 if sx * sz > 0 else -45, "axis": "y", "origin": [mx, (y0 + y1) / 2, mz]}))
    return els


GARLAND = "#garland"
LIGHTS = "#lights"
G_TOP, G_SIDE = [0, 6, 16, 8], [0, 5, 16, 8]          # the leafy band of garland.png (rows 6-7 fully opaque)
WIRE = [0, 4, 1, 5]                                    # one dark wire pixel of fairy_lights.png
BULBS = [[1, 6, 3, 8], [5, 6, 7, 8], [9, 6, 11, 8], [13, 6, 15, 8]]   # red, green, blue, yellow (2 x 2 px each)
CAP = [1, 4, 3, 5]                                     # the grey bulb holder


def light_coil(cx, cz, outer, y0, n_bulbs, phase=0):
    """A coil of fairy lights lying flat: a thin dark wire ring with bulbs lying on it, each a little longer than
    wide and pointing out from the coil, its grey holder on the wire (bulbs at the eight 45-degree points only,
    the turns a model element may make)."""
    els = ring(cx, cz, outer, 1.0, y0, y0 + 1.0, LIGHTS, WIRE, WIRE)
    r = outer / 2.0 - 0.5
    step = 8 // n_bulbs if n_bulbs <= 8 else 1
    for i in range(n_bulbs):
        k = (i * step + phase) % 8                    # the 45-degree point of this bulb
        dx, dz = [(1, 0), (0.7071, 0.7071), (0, 1), (-0.7071, 0.7071), (-1, 0), (-0.7071, -0.7071), (0, -1),
                  (0.7071, -0.7071)][k]
        bx, bz = cx + r * dx, cz + r * dz
        bulb = BULBS[(i + phase) % 4]
        # laid out along x from the wire outwards, then turned about y to the bulb's direction
        if k in (0, 4):
            sgn = 1 if k == 0 else -1
            hold = el([min(bx, bx + sgn * 0.9), y0 + 0.4, bz - 0.6], [max(bx, bx + sgn * 0.9), y0 + 1.6, bz + 0.6],
                      LIGHTS, uv={"*": CAP})
            body = el([min(bx + sgn * 0.9, bx + sgn * 2.6), y0 + 0.3, bz - 0.8],
                      [max(bx + sgn * 0.9, bx + sgn * 2.6), y0 + 1.9, bz + 0.8], LIGHTS, uv={"*": bulb})
        elif k in (2, 6):
            sgn = 1 if k == 2 else -1
            hold = el([bx - 0.6, y0 + 0.4, min(bz, bz + sgn * 0.9)], [bx + 0.6, y0 + 1.6, max(bz, bz + sgn * 0.9)],
                      LIGHTS, uv={"*": CAP})
            body = el([bx - 0.8, y0 + 0.3, min(bz + sgn * 0.9, bz + sgn * 2.6)],
                      [bx + 0.8, y0 + 1.9, max(bz + sgn * 0.9, bz + sgn * 2.6)], LIGHTS, uv={"*": bulb})
        else:
            sgn = 1 if dx > 0 else -1
            ang = -45 if dx * dz > 0 else 45
            rot = {"angle": ang, "axis": "y", "origin": [bx, y0 + 1, bz]}
            hold = el([min(bx, bx + sgn * 0.9), y0 + 0.4, bz - 0.6], [max(bx, bx + sgn * 0.9), y0 + 1.6, bz + 0.6],
                      LIGHTS, uv={"*": CAP}, rot=rot)
            body = el([min(bx + sgn * 0.9, bx + sgn * 2.6), y0 + 0.3, bz - 0.8],
                      [max(bx + sgn * 0.9, bx + sgn * 2.6), y0 + 1.9, bz + 0.8], LIGHTS, uv={"*": bulb}, rot=rot)
        els += [hold, body]
    return els


TEXTURES = {"garland": f"{NS}:block/garland", "lights": f"{NS}:block/fairy_lights", "wreath": f"{NS}:block/wreath",
            "lantern": f"{NS}:block/lantern_string_0", "lantern2": f"{NS}:block/lantern_string_1",
            "red": f"{NS}:block/gift_red", "green": f"{NS}:block/gift_green", "blue": f"{NS}:block/gift_blue",
            "label": f"{D}:block/st_label", "burlap": f"{D}:block/st_burlap", "rope": f"{D}:block/st_rope",
            "planks": "minecraft:block/spruce_planks", "post": "minecraft:block/dark_oak_planks",
            "box": "minecraft:block/stripped_spruce_log", "straw": "minecraft:block/hay_block_top",
            "particle": "minecraft:block/spruce_planks"}
FULL = [0, 0, 16, 16]
RED_LANTERN, YELLOW_LANTERN = [2, 6, 7, 12], [9, 7, 14, 13]


def gift(frm, to, colour):
    return el(frm, to, "#" + colour, uv={"*": FULL})


def lantern(x, y, z, which):
    return el([x, y, z], [x + 3, y + 4, z + 3], "#lantern", uv={"*": which, "up": [3, 8, 6, 11], "down": [3, 8, 6, 11]})


def contents(kind, fill, floor, top):
    """What lies in a box or crate whose inside runs from `floor` up, filled to `top` (full) or nearly empty."""
    out = []
    if fill == "full":
        if kind in ("wreath", "lanterns", "lights"):
            out.append(el([2.2, floor, 2.2], [13.8, top - 1.4, 13.8], "#straw", faces=["up"]))
        if kind == "wreath":
            out += [el([3, top - 1.2, 3], [12, top - 1.0, 12], "#wreath", faces=["up"], uv={"up": FULL}),
                    el([4, top - 0.6, 3.6], [13, top - 0.4, 12.6], "#wreath", faces=["up"], uv={"up": FULL}),
                    el([3.4, top, 4], [12.4, top + 0.2, 13], "#wreath", faces=["up"], uv={"up": FULL})]
        elif kind == "garland":
            out += ring(8, 8, 11.6, 3, floor, top - 1.5, GARLAND, G_TOP, G_SIDE)
            out += ring(8, 8, 7.5, 2.4, top - 1.5, top + 0.6, GARLAND, G_TOP, G_SIDE)
        elif kind == "lights":
            out += light_coil(8, 8, 11.4, top - 3.0, 7)
            out += light_coil(8.4, 7.6, 7.6, top - 1.8, 5, 1)
        elif kind == "lanterns":
            for i, (x, z) in enumerate([(2.6, 2.6), (6.5, 2.6), (10.4, 2.6), (2.6, 6.6), (6.5, 6.6), (10.4, 6.6),
                                        (2.6, 10.4), (6.5, 10.4), (10.4, 10.4)]):
                out.append(lantern(x, top - 3.4, z, RED_LANTERN if i % 2 == 0 else YELLOW_LANTERN))
        elif kind == "gifts":
            out += [gift([2.2, floor, 2.2], [8, top - 2, 8], "red"), gift([8, floor, 2.2], [13.8, top - 3, 8.5], "green"),
                    gift([2.2, floor, 8], [8.5, top - 3, 13.8], "blue"), gift([8.5, floor, 8.5], [13.8, top - 2.5, 13.8], "red"),
                    gift([5, top - 3, 5], [10.5, top + 1.5, 10.5], "blue")]
    else:
        if kind == "wreath":
            out.append(el([3, floor + 0.1, 3], [12, floor + 0.3, 12], "#wreath", faces=["up"], uv={"up": FULL}))
        elif kind == "garland":
            out += ring(7, 9, 6.5, 2.2, floor, floor + 1.6, GARLAND, G_TOP, G_SIDE)
        elif kind == "lights":
            out += light_coil(9, 7, 6, floor, 3)
        elif kind == "lanterns":
            out.append(lantern(9, floor, 9, YELLOW_LANTERN))
        elif kind == "gifts":
            out.append(gift([4, floor, 4], [9.5, floor + 4.5, 9.5], "green"))
    return out


def model_box(content, fill):
    """A: a labelled spruce box, open at the top, 14 x 10 x 14 px; the colour-coded card label sits high on the front
    (north, the block's front), wider than tall."""
    w = "#box"
    sx, sy = LABEL_SLOT[content]
    els = [el([1, 0, 1], [15, 1, 15], w), el([1, 1, 1], [15, 10, 2], w), el([1, 1, 14], [15, 10, 15], w),
           el([1, 1, 2], [2, 10, 14], w), el([14, 1, 2], [15, 10, 14], w),
           el([1, 9.4, 0.9], [15, 10.2, 2.1], "#post", faces=["north", "up"]),
           el([3, 5.5, 0.75], [11, 8.5, 1], "#label", faces=["north"],
              uv={"north": [sx, sy, sx + LABEL_W, sy + LABEL_H]})]
    return els + contents(content, fill, 1, 9.2)


def model_coil(kind, drape):
    """A coil lying on a barrel, crate or hay top; drape: one end hangs over the front edge (north). Garland: three
    leafy rings; lights: two wire rings with their bulbs."""
    if kind == "garland":
        els = ring(8, 8, 13, 3, 0, 2, GARLAND, G_TOP, G_SIDE) + ring(8, 8, 8.5, 2.5, 2, 3.6, GARLAND, G_TOP, G_SIDE)
        els += ring(8, 8, 4.4, 1.6, 0, 1.6, GARLAND, G_TOP, G_SIDE)
    else:
        els = light_coil(8, 8, 13, 0, 8) + light_coil(8.4, 7.6, 8.6, 1.0, 4, 1) + light_coil(7.8, 8.2, 4.6, 2.0, 2, 3)
    if drape:
        if kind == "garland":
            els.append(el([4, -8, 0.15], [11, 1, 0.35], GARLAND, faces=["north", "south"],
                          uv={"*": [0, 4, 16, 10]}, frot={"north": 90, "south": 90}))
        else:
            els.append(el([7.5, -7, 0.4], [8.5, 1, 1.4], LIGHTS, uv={"*": WIRE}))
            for i, y in enumerate((-2.5, -6)):
                els.append(el([7.2, y, 0.2], [8.8, y + 1.6, 1.6], LIGHTS, uv={"*": BULBS[i + 1]}))
    return els


def model_sack(fill):
    """B: a jute sack, wider than deep, sagging at the bottom, its top gathered, tied with rope and flaring into two
    floppy ears (no rim, no neck ring: it must not read as a pot); turned a little. Empty: folded flat."""
    b = "#burlap"
    turn = {"angle": 22.5, "axis": "y", "origin": [8, 0, 8]}
    if fill == "full":
        parts = [el([2.6, 0, 4.0], [13.4, 1, 12.0], b), el([1.8, 1, 3.4], [14.2, 5.5, 12.6], b),
                 el([2.4, 5.5, 3.8], [13.6, 8.0, 12.2], b), el([3.8, 8.0, 4.8], [12.2, 9.2, 11.2], b),
                 el([5.6, 9.2, 6.0], [10.4, 10.4, 10.0], b),
                 el([6.4, 10.4, 6.6], [9.6, 11.4, 9.4], "#rope"),
                 el([4.8, 11.0, 6.9], [7.8, 12.8, 9.1], b), el([8.2, 11.0, 6.7], [11.2, 12.1, 9.3], b),
                 el([10.6, 11.4, 7.2], [12.0, 12.0, 8.8], b)]
        for e in parts:
            e["rotation"] = turn
        return parts
    parts = [el([2, 0, 3], [14, 1.2, 13], b), el([2.5, 1.2, 3.5], [9, 2.2, 12.5], b), el([13, 0.2, 6], [15.6, 1, 10], b)]
    for e in parts:
        e["rotation"] = turn
    return parts


def model_crate():
    """B: a slatted spruce crate on dark posts, 12 px tall, the lid slid back over the rear half (north = front)."""
    els = [el([1, 0, 1], [15, 1, 15], "#planks")]
    for (x0, z0) in ((1, 1), (13, 1), (1, 13), (13, 13)):
        els.append(el([x0, 0, z0], [x0 + 2, 12, z0 + 2], "#post"))
    for y0, y1 in ((1, 4.2), (5, 8.2), (9, 12)):
        els += [el([3, y0, 1.5], [13, y1, 2.5], "#planks"), el([3, y0, 13.5], [13, y1, 14.5], "#planks"),
                el([1.5, y0, 3], [2.5, y1, 13], "#planks"), el([13.5, y0, 3], [14.5, y1, 13], "#planks")]
    els += [el([0.6, 12, 8.5], [15.4, 13.4, 15.6], "#planks"), el([0.6, 13.4, 10.5], [15.4, 14.2, 12], "#post")]
    return els


def model_heap(kind, fill):
    """B: what lies in the crate below, drawn from the cell above (the crate's floor is 15 px down, its rim 4)."""
    floor = -15
    els = []
    if fill == "full":
        els.append(el([2.6, floor, 2.6], [13.4, -5.5, 13.4], "#straw", faces=["up", "north", "south", "east", "west"]))
        if kind == "garland":
            els += ring(6.5, 6, 9, 3, -5.5, -3.2, GARLAND, G_TOP, G_SIDE)
            els += ring(8.5, 7, 6, 2.4, -3.2, -1.2, GARLAND, G_TOP, G_SIDE)
            els.append(el([6, -14, 1.0], [11, -3.6, 1.2], GARLAND, faces=["north", "south"],
                          uv={"*": [0, 4, 16, 10]}, frot={"north": 90, "south": 90}))
        elif kind == "wreath":
            els += [el([3, -8, 4], [12, 1, 4.2], "#wreath", faces=["north", "south"], uv={"*": FULL},
                       rot={"angle": 22.5, "axis": "x", "origin": [8, -8, 4]}),
                    el([4, -8.5, 6.2], [13, 0.5, 6.4], "#wreath", faces=["north", "south"], uv={"*": FULL},
                       rot={"angle": 22.5, "axis": "x", "origin": [8, -8.5, 6.2]})]
        elif kind == "lights":
            els += light_coil(7, 6, 9.5, -5.5, 7) + light_coil(8, 6.5, 6, -4.4, 4, 1)
            els.append(el([7.5, -12, 0.6], [8.5, -4.5, 1.6], LIGHTS, uv={"*": WIRE}))
            for i, y in enumerate((-7.5, -11)):
                els.append(el([7.2, y, 0.4], [8.8, y + 1.6, 1.8], LIGHTS, uv={"*": BULBS[(i + 2) % 4]}))
        elif kind == "gifts":
            els += [gift([3, -9, 3], [8.5, -3, 8.5], "red"), gift([8, -10, 2.8], [13.2, -4, 8], "green"),
                    gift([5, -6, 4.5], [10.5, -0.5, 10], "blue")]
    else:
        if kind == "wreath":
            els.append(el([3, floor + 0.1, 3], [12, floor + 0.3, 12], "#wreath", faces=["up"], uv={"up": FULL}))
        elif kind == "garland":
            els += ring(7, 6, 6.5, 2.2, floor, floor + 1.6, GARLAND, G_TOP, G_SIDE)
        elif kind == "lights":
            els += light_coil(7, 6, 6, floor, 3)
        elif kind == "gifts":
            els.append(gift([5, floor, 3.5], [10, floor + 4.5, 8.5], "green"))
    return els


def model_gifts(fill):
    """B: wrapped gifts stacked loose on a barrel."""
    if fill == "full":
        return [gift([1.5, 0, 1.5], [8.5, 6, 8.5], "red"), gift([8, 0, 6.5], [14.5, 5, 14.5], "green"),
                gift([2, 0, 9], [7.5, 4.5, 14.5], "blue"), gift([3.5, 6, 3], [9.5, 10.5, 9], "blue"),
                gift([9, 5, 8], [13, 8, 12], "red")]
    return [gift([5, 0, 5], [11, 5, 11], "green")]


def write_drafts():
    """The drafts' textures, models and blockstates, and the mod's own assets beside them, in one folder."""
    if os.path.isdir(ASSETS):
        shutil.rmtree(ASSETS)
    shutil.copytree(os.path.join(ROOT, "resources", "assets", NS), os.path.join(ASSETS, NS))
    base = os.path.join(ASSETS, D)
    for name, im in (("st_label", tex_label()), ("st_burlap", tex_burlap()), ("st_rope", tex_rope())):
        p = os.path.join(base, "textures", "block", name + ".png")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        im.save(p)

    def save_model(name, elements):
        p = os.path.join(base, "models", "block", name + ".json")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            json.dump({"textures": TEXTURES, "elements": elements, "ambientocclusion": False}, f)

    def save_state(name, variants):
        p = os.path.join(base, "blockstates", name + ".json")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            json.dump({"variants": variants}, f, indent=1)

    def facing_variants(prefix_key, model):
        return {(prefix_key + "," if prefix_key else "") + f"facing={fc}":
                ({"model": f"{D}:block/{model}", "y": y} if y else {"model": f"{D}:block/{model}"})
                for fc, y in FACING_Y.items()}

    v = {}
    for c in CONTENT_COLOUR:
        for fill in ("full", "low"):
            save_model(f"st_box_{c}_{fill}", model_box(c, fill))
            v.update(facing_variants(f"content={c},fill={fill}", f"st_box_{c}_{fill}"))
    save_state("st_box", v)
    v = {}
    for k in ("garland", "lights"):
        for drape in ("false", "true"):
            save_model(f"st_coil_{k}_{drape}", model_coil(k, drape == "true"))
            v.update(facing_variants(f"drape={drape},kind={k}", f"st_coil_{k}_{drape}"))
    save_state("st_coil", v)
    v = {}
    for fill in ("full", "empty"):
        save_model(f"st_sack_{fill}", model_sack(fill))
        v.update(facing_variants(f"fill={fill}", f"st_sack_{fill}"))
    save_state("st_sack", v)
    save_model("st_crate", model_crate())
    save_state("st_crate", facing_variants("", "st_crate"))
    v = {}
    for k in ("garland", "wreath", "lights", "gifts"):
        for fill in ("full", "low"):
            save_model(f"st_heap_{k}_{fill}", model_heap(k, fill))
            v.update(facing_variants(f"content={k},fill={fill}", f"st_heap_{k}_{fill}"))
    save_state("st_heap", v)
    v = {}
    for fill in ("full", "low"):
        save_model(f"st_gifts_{fill}", model_gifts(fill))
        v.update(facing_variants(f"fill={fill}", f"st_gifts_{fill}"))
    save_state("st_gifts", v)


# ------------------------------------------------------------------ block strings
def box(content, fill, facing="south"):
    return f"{D}:st_box[content={content},facing={facing},fill={fill}]"


def coil(kind, drape=False, facing="south"):
    return f"{D}:st_coil[drape={'true' if drape else 'false'},facing={facing},kind={kind}]"


def sack(fill, facing="south"):
    return f"{D}:st_sack[facing={facing},fill={fill}]"


def heap(content, fill, facing="south"):
    return f"{D}:st_heap[content={content},facing={facing},fill={fill}]"


def gifts(fill, facing="south"):
    return f"{D}:st_gifts[facing={facing},fill={fill}]"


CRATE = f"{D}:st_crate[facing=south]"


def wall(name, facing, **props):
    """One of the mod's shipped wall decorations (wreath, garland, lantern_string, fairy_lights), unlit by day."""
    p = {"facing": facing}
    if name in ("lantern_string", "fairy_lights"):
        p["lit"] = "false"
    if name == "lantern_string":
        p["variant"] = str(props.get("variant", 0))
    return f"{NS}:{name}[" + ",".join(f"{k}={v}" for k, v in sorted(p.items())) + "]"


def row(name, facing, cells):
    out = {}
    for i, c in enumerate(cells):
        out[c] = wall(name, facing, variant=i % 3)
    return out


BARREL_UP = "minecraft:barrel[facing=up]"
HAY = "minecraft:hay_block[axis=y]"
LANTERN_HANGING = "minecraft:lantern[hanging=true]"


# ------------------------------------------------------------------ the two variants
def plan(variant, level):
    """{furniture, packed, festival, empty_in_festival}: the blueprint layer (into the shipped blueprint's air), the
    Decorator's two sets (into air, on furniture or walls) and the racks drawn empty in the window. Nothing stands
    right behind a window, so the outside looks the same; nothing hangs in the packed set (hung things are the
    festival's signal); and nothing at all is in the cell directly above a rack: MineColonies' full rack model draws
    boxes 6 px up into that cell (the double rack's reach its edge)."""
    if (variant, level) == ("A", 1):
        furniture = {(3, 1, -3): BARREL_UP, (3, 1, -2): BARREL_UP, (4, 1, 0): "minecraft:barrel[facing=west]"}
        packed = {(3, 2, -3): box("lights", "full"), (3, 2, -2): coil("garland"), (4, 3, -3): coil("lights"),
                  (4, 2, -2): box("gifts", "full", "west"), (4, 2, 0): box("wreath", "full", "west")}
        festival = {p: b.replace("fill=full", "fill=low") for p, b in packed.items() if "st_box" in b}
        festival.update(row("garland", "south", [(x, 4, -3) for x in range(1, 5)]))
        festival[(2, 3, -3)] = wall("wreath", "south")
        festival.update(row("fairy_lights", "west", [(4, 4, z) for z in range(-2, 3)]))
        return dict(furniture=furniture, packed=packed, festival=festival, empty_in_festival={(1, 1, -3)})
    if (variant, level) == ("A", 5):
        furniture = {(4, 6, -3): BARREL_UP, (4, 7, -3): BARREL_UP, (-4, 7, -2): BARREL_UP, (4, 6, -1): BARREL_UP}
        packed = {(-4, 7, -3): box("wreath", "full"), (-3, 8, -3): box("gifts", "full"),
                  (-4, 8, -2): box("lights", "full", "east"), (2, 7, -3): box("lanterns", "full"),
                  (4, 8, -3): box("garland", "full"), (4, 7, -1): coil("lights", facing="west")}
        festival = {p: b.replace("fill=full", "fill=low") for p, b in packed.items() if "st_box" in b}
        # the back beam above the upper racks (x -2 and -1) stays bare: those are the cells above a rack
        festival.update(row("garland", "south", [(x, 8, -3) for x in range(0, 4)]))
        festival[(3, 7, -3)] = wall("wreath", "south")
        festival.update(row("fairy_lights", "east", [(-4, 8, z) for z in (-1, 1, 2, 3)]))
        festival.update(row("fairy_lights", "west", [(4, 8, z) for z in range(-2, 4)]))
        return dict(furniture=furniture, packed=packed, festival=festival,
                    empty_in_festival={(-2, 6, -3), (-1, 6, -3), (-2, 7, -3), (-1, 7, -3)})
    if (variant, level) == ("B", 1):
        furniture = {(3, 1, -3): CRATE, (4, 1, 2): HAY}
        furniture.update({(4, y, 0): "minecraft:ladder[facing=west]" for y in (1, 2, 3)})
        packed = {(3, 2, -3): heap("garland", "full"), (4, 3, -3): coil("lights", True, "west"),
                  (4, 2, 2): sack("full", "west"), (4, 2, -2): gifts("full", "west")}
        festival = {(3, 2, -3): heap("garland", "low"), (4, 2, 2): sack("empty", "west"),
                    (4, 2, -2): gifts("low", "west")}
        festival.update(row("lantern_string", "south", [(x, 4, -3) for x in range(1, 5)]))
        festival.update(row("garland", "west", [(4, 4, z) for z in range(-2, 3)]))
        festival[(3, 3, -3)] = wall("wreath", "south")
        return dict(furniture=furniture, packed=packed, festival=festival, empty_in_festival={(1, 1, -3)})
    if (variant, level) == ("B", 5):
        furniture = {(4, 6, -3): CRATE, (4, 6, -1): CRATE, (-3, 6, -2): CRATE,
                     (4, 6, 1): HAY, (4, 6, 3): HAY, (4, 7, 3): HAY,
                     (4, 8, 1): "minecraft:spruce_fence", (4, 7, 1): LANTERN_HANGING}
        furniture.update({(4, y, 0): "minecraft:ladder[facing=west]" for y in (6, 7, 8)})
        packed = {(4, 7, -3): heap("garland", "full"), (4, 7, -1): heap("wreath", "full", "west"),
                  (-3, 7, -2): heap("lights", "full"), (-4, 7, -2): coil("garland", True, "east"),
                  (4, 8, 3): sack("full", "west"), (-3, 8, -3): sack("full"), (2, 7, -3): sack("full"),
                  (-4, 7, -3): gifts("full")}
        festival = {(4, 7, -3): heap("garland", "low"), (4, 7, -1): heap("wreath", "low", "west"),
                    (-3, 7, -2): heap("lights", "low"),
                    (4, 8, 3): sack("empty", "west"), (-3, 8, -3): sack("empty"), (2, 7, -3): sack("empty"),
                    (-4, 7, -3): gifts("low")}
        # the back beam above the upper racks (x -2 and -1) stays bare: those are the cells above a rack
        festival.update(row("lantern_string", "south", [(x, 8, -3) for x in range(0, 5)]))
        festival.update(row("garland", "east", [(-4, 8, z) for z in (-3, -2, -1, 1, 2, 3)]))
        festival[(4, 8, -2)] = wall("wreath", "west")
        return dict(furniture=furniture, packed=packed, festival=festival,
                    empty_in_festival={(-2, 6, -3), (-1, 6, -3), (-2, 7, -3), (-1, 7, -3)})
    raise ValueError((variant, level))


VARIANTS = {"A": "Tidy storeroom", "B": "Busy loft"}


# ------------------------------------------------------------------ the hut and the checks
def shipped(level):
    """The shipped blueprint of hut level `level` in decorator_hut's frame (hut block at (0, 1, 0))."""
    s, _ = voxel.load_blueprint(os.path.join(PACK, "decorator", f"workshop{level}.blueprint"))
    ax, ay, az = s.anchor
    t = voxel.Structure(f"workshop{level}")
    for (x, y, z), b in s.blocks.items():
        if not b.startswith("structurize:"):
            t.set(x - ax, y - ay + 1, z - az, b)
    t.anchor = dh.HUT_SPOT
    return t


def name_of(b):
    return voxel.parse_state(b)[0]


LADDER_WAY = {(-4, y, 0) for y in range(1, 9)} | {(-4, 6, -1), (-4, 6, 1), (-3, 6, 0)}
FURNITURE_UNDER = ("minecraft:barrel", "minecraft:hay_block", f"{D}:st_crate")
OPP = {"north": (0, 0, 1), "south": (0, 0, -1), "east": (-1, 0, 0), "west": (1, 0, 0)}
NOT_FULL = ("lantern", "ladder", "glass_pane", "_door", "_trapdoor", "_stairs", "_slab", "_carpet", "_fence")


def above_rack(blocks, q):
    """True when the cell right below q holds a rack: MineColonies' full rack model draws boxes 6 px up into q (the
    single rack's at y 16-22, the double rack's too, one of them out to the cell's edge), so nothing may be there."""
    x, y, z = q
    below = blocks.get((x, y - 1, z))
    return below is not None and name_of(below) == RACK


def check(variant, level, p=None):
    """The rules of docs/storage-look-design.md on one variant and level (or on the plan `p`, for the self-test).
    Returns (problems, facts)."""
    base = shipped(level)
    p = p or plan(variant, level)
    problems = []
    racks = {q for q, b in base.blocks.items() if name_of(b) == RACK}
    floor_ys = (1, 2, 3, 4) if level < 3 else (1, 2, 3, 4, 6, 7, 8, 9)
    for q, b in p["furniture"].items():
        x, y, z = q
        if q in base.blocks:
            problems.append(f"furniture {q}: the blueprint has {base.blocks[q]} there (only air cells)")
        if not (dh.X0 < x < dh.X1 and dh.Z0 < z < dh.Z1 and y in floor_ys):
            problems.append(f"furniture {q}: not strictly inside the walls")
        if q in LADDER_WAY:
            problems.append(f"furniture {q}: on the ladder's way")
        n = name_of(b)
        if not (n.startswith("minecraft:") or n == f"{D}:st_crate"):
            problems.append(f"furniture {q}: {n} is not vanilla (nor B's crate)")
    glass = {q for q, b in base.blocks.items() if "glass" in b}

    def behind_window(q):
        x, y, z = q
        return any((x + dx, y, z + dz) in glass for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))

    for q in p["furniture"]:
        if behind_window(q):
            problems.append(f"furniture {q}: right behind a window")
        if above_rack(base.blocks, q):
            problems.append(f"furniture {q}: directly above a rack (its boxes stand 6 px up into this cell)")
    built = voxel.Structure("built")
    built.blocks = dict(base.blocks)
    built.blocks.update(p["furniture"])
    for state in ("packed", "festival"):
        for q, b in p[state].items():
            x, y, z = q
            if q in built.blocks:
                problems.append(f"{state} {q}: not air ({built.blocks[q]})")
            if not (dh.X0 < x < dh.X1 and dh.Z0 < z < dh.Z1):
                problems.append(f"{state} {q}: outside the walls")
            if q in LADDER_WAY:
                problems.append(f"{state} {q}: on the ladder's way")
            n = name_of(b)
            props = voxel.parse_state(b)[1]
            on_wall = n.startswith(f"{NS}:")             # the shipped wall decorations: the only hung pieces
            if not on_wall and behind_window(q):
                problems.append(f"{state} {q}: right behind a window")
            if above_rack(built.blocks, q):
                problems.append(f"{state} {q}: directly above a rack (its boxes stand 6 px up into this cell)")
            if on_wall and state == "packed":
                problems.append(f"packed {q}: {n} hangs on a wall (hung things are the festival's signal)")
            if on_wall:
                dx, dy, dz = OPP[props["facing"]]
                behind = built.get(x + dx, y + dy, z + dz)
                if behind is None or any(w in behind for w in NOT_FULL):
                    problems.append(f"{state} {q}: {n} has no full block behind it ({behind})")
            else:
                under = built.get(x, y - 1, z)
                if under is None or name_of(under) not in FURNITURE_UNDER:
                    problems.append(f"{state} {q}: {n} does not stand on furniture ({under})")
        dressed = voxel.Structure("dressed")
        dressed.blocks = dict(built.blocks)
        dressed.blocks.update(p[state])
        if {q for q, b in dressed.blocks.items() if name_of(b) == RACK} != racks:
            problems.append(f"{state}: the racks changed")
        start = (dh.DOOR_X, 1, dh.Z1 + 1)
        seen = walk(dressed, [start])
        if not next_to(seen, dh.HUT_SPOT):
            problems.append(f"{state}: the hut block cannot be reached from the door")
        for fy in ((1,) if level < 3 else (1, 6)):
            cells = [(x, fy, z) for x in range(dh.X0 + 1, dh.X1) for z in range(dh.Z0 + 1, dh.Z1)
                     if standable(dressed, x, fy, z)]
            missed = [c for c in cells if c not in seen]
            if missed:
                problems.append(f"{state}: floor y={fy}: {len(missed)} cells cannot be reached, e.g. {missed[:3]}")
    own_bp = sum(1 for b in p["furniture"].values() if name_of(b).startswith(D + ":"))
    facts = dict(racks=len(racks), furniture=len(p["furniture"]), own_in_blueprint=own_bp,
                 packed=len(p["packed"]), festival=len(p["festival"]),
                 swapped=len(set(p["packed"]) & set(p["festival"])),
                 hung=sum(1 for b in p["festival"].values() if name_of(b).startswith(NS + ":")))
    return problems, facts


# ------------------------------------------------------------------ scenes
def hut_scene(level, extra):
    """As tools/render_s5.hut_scene: the shipped hut on the design's ground patch, plus `extra` cells."""
    hut = shipped(level)
    ground = voxel.Structure("ground")
    huts.ground(ground)
    blocks = dict(ground.blocks)
    for (x, y, z), b in huts.build_a().blocks.items():
        if not (-6 <= x <= 6 and 0 <= y <= 15 and -5 <= z <= 5):
            blocks[(x, y, z)] = b
    for q, b in hut.blocks.items():
        blocks[q] = b
    for (x, y, z), b in list(blocks.items()):
        if y == 1 and voxel.parse_state(b)[0] == "minecraft:snow":
            under = blocks.get((x, 0, z), "")
            if not under.startswith("minecraft:grass_block") or (-5 <= x <= 5 and -4 <= z <= 4):
                del blocks[(x, y, z)]
    blocks.update(extra)
    return blocks


def rack_states(blocks, empty):
    for q, b in list(blocks.items()):
        if name_of(b) == RACK:
            kind = "blockrackemptysingle" if q in empty else "blockrackfullsingle"
            blocks[q] = f"{RACK}[facing=south,variant={kind}]"


def cutaway(blocks, level):
    """The front wall and everything south of it and the roof (with the tie beam under it and the lanterns hanging
    from it) left out, as tools/render_s5.cutaway_scene draws the S10e cutaways."""
    roof_y = 9 if level >= 3 else 5
    for (x, y, z) in list(blocks):
        in_box = -6 <= x <= 6 and -6 <= z <= 6 and y >= 1
        if in_box and (z >= 4 or y >= roof_y):
            del blocks[(x, y, z)]
    for (x, y, z), b in list(blocks.items()):
        if "hanging=true" in b and (x, y + 1, z) not in blocks:
            del blocks[(x, y, z)]
    return blocks


def scene(variant, level, state, cut=True):
    p = plan(variant, level)
    extra = dict(p["furniture"])
    extra.update(p[state])
    blocks = hut_scene(level, extra)
    rack_states(blocks, p["empty_in_festival"] if state == "festival" else set())
    return cutaway(blocks, level) if cut else blocks


CAM_CORNER = dict(yaw=-24, pitch=36, ortho=9.6, target=[2.2, 2.0, -1.6], res=[1000, 1000])
CAM_ATTIC = dict(yaw=-6, pitch=24, ortho=9.6, target=[0.4, 6.7, -2.0], res=[1000, 1000])   # the attic only
CAM_OUTSIDE = dict(yaw=32, pitch=28, ortho=24.0, target=[0, 6.0, 0.5], res=[1000, 1000])   # render_set "hut"

PANELS = [("l1-packed", 1, "packed", CAM_CORNER, "Level 1 corner, packed (outside the window)"),
          ("l1-festival", 1, "festival", CAM_CORNER, "Level 1 corner, festival (in the window)"),
          ("l5-outside", 5, "packed", CAM_OUTSIDE, "Level 5 outside: look A as picked, no outside cell changed"),
          ("l5-packed", 5, "packed", CAM_ATTIC, "Level 5 attic, packed (outside the window)"),
          ("l5-festival", 5, "festival", CAM_ATTIC, "Level 5 attic, festival (in the window)")]


def png(name):
    return os.path.join(OUT, name + ".png")


def jobs():
    out = []
    for v in VARIANTS:
        for key, level, state, cam, _ in PANELS:
            out.append(dict(blocks=scene(v, level, state, cut=cam is not CAM_OUTSIDE), out=png(f"storage-{v}-{key}"),
                            bg=SKY, samples=24, **cam))
    return out


# ------------------------------------------------------------------ sheets
def font(size):
    for f in ("C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def notes(variant):
    """The text cell of a sheet, its numbers taken from the checks."""
    f1, f5 = check(variant, 1)[1], check(variant, 5)[1]
    racks = f"racks as shipped ({f1['racks']} and {f5['racks']}), none added or moved"
    counts = f"(L1 {f1['packed']}, L5 {f5['packed']} pieces)"
    hung = f"(L1 {f1['hung']}, L5 {f5['hung']} blocks)"
    if variant == "A":
        lines = ["Builder (blueprint): vanilla only, no new kind of block on the bill:",
                 f"  L1 +{f1['furniture']} barrels, L5 +{f5['furniture']} barrels; {racks}.",
                 "Decorator (own thin blocks, ledger rule, into air only):",
                 f"  packed: labelled boxes and coils lying on the barrels {counts}; nothing hung, nothing on the "
                 "floor, nothing in a cell above a rack.",
                 f"  festival: the boxes swapped to nearly empty, the coils taken off; hung: garland on the back beam, "
                 f"a wreath, fairy lights {hung}."]
    else:
        lines = ["Builder (blueprint): hay, a ladder, a lantern on a fence bracket (vanilla; hay and the ladder are new "
                 f"kinds on the L1 bill) and our own crate on the bill: L1 {f1['own_in_blueprint']}, "
                 f"L5 {f5['own_in_blueprint']}; {racks}.",
                 "Decorator (own thin blocks, ledger rule, into air only):",
                 f"  packed: heaps in the crates, sacks, gifts and a coil with a loose end on top {counts}; nothing "
                 "hung, nothing on the floor or above a rack.",
                 f"  festival: crates nearly empty, sacks folded, gifts down to one, the coil taken off; hung: lantern "
                 f"string on the back beam, garland along a side wall, a wreath {hung}."]
    lines += ["Racks drawn as single racks, full while they hold anything (in the game neighbours can join into "
              "doubles); the festival panels assume the racks that held the decorations are empty while those are out.",
              "Checked: inside the walls, nothing right behind a window, nothing above a rack, every floor cell "
              "reachable from the door, the ladder and its exit clear."]
    return lines


def wrap(d, text, fnt, width):
    """Lines of `text` no wider than `width` pixels; a leading indent is kept on every wrapped line."""
    indent = text[:len(text) - len(text.lstrip())]
    words, out, cur = text.split(), [], indent
    for w in words:
        trial = (cur + " " + w) if cur.strip() else indent + w
        if d.textlength(trial, font=fnt) > width and cur.strip():
            out.append(cur)
            cur = indent + "  " + w if indent else "  " + w
        else:
            cur = trial
    out.append(cur)
    return out


def sheet(variant):
    cols, gap, label_h, top = 3, 16, 46, 64
    W = 2000
    cw = (W - gap * (cols + 1)) // cols
    rows = 2
    H = top + rows * (cw + label_h + gap) + gap
    out = Image.new("RGB", (W, H), (236, 236, 232))
    d = ImageDraw.Draw(out)
    d.text((gap, 14), f"Storage room, variant {variant}: {VARIANTS[variant]}  (left: packed, all year outside the "
                      f"decoration window; middle: festival, in the window)", fill=(30, 30, 30), font=font(30))
    f = font(24)
    order = ["l1-packed", "l1-festival", "l5-outside", "l5-packed", "l5-festival", None]
    labels = {k: lab for k, _, _, _, lab in PANELS}
    for i, key in enumerate(order):
        x = gap + (i % cols) * (cw + gap)
        y = top + (i // cols) * (cw + label_h + gap)
        if key is None:
            fn = font(22)
            k = 0
            for para in notes(variant):
                for line in wrap(d, para, fn, cw - 12):
                    d.text((x + 6, y + 8 + k * 31), line, fill=(40, 40, 40), font=fn)
                    k += 1
            continue
        im = Image.open(png(f"storage-{variant}-{key}")).convert("RGB").resize((cw, cw), Image.LANCZOS)
        out.paste(im, (x, y))
        tw = d.textlength(labels[key], font=f)
        d.text((x + (cw - tw) / 2, y + cw + 8), labels[key], fill=(34, 34, 34), font=f)
    p = png(f"sheet-storage-{variant}")
    out.save(p, optimize=True)
    print("sheet", os.path.relpath(p, ROOT), out.size, os.path.getsize(p) // 1024, "KB")


def selftest():
    """The check must catch the two faults the S10g review found in the first draft: a festival piece on the back
    beam above an upper attic rack, and a coil hung on a wall peg in the packed set."""
    p = plan("A", 5)
    bad = dict(p, festival=dict(p["festival"]), packed=dict(p["packed"]))
    bad["festival"][(-2, 8, -3)] = wall("garland", "south")
    bad["packed"][(0, 8, -3)] = wall("wreath", "south")
    problems = check("A", 5, bad)[0]
    assert any("(-2, 8, -3): directly above a rack" in pr for pr in problems), problems
    assert any("packed (0, 8, -3)" in pr and "hangs on a wall" in pr for pr in problems), problems
    print("self-test: the check finds a piece above a rack and a hung piece in the packed set")


def main():
    selftest()
    ok = True
    for v in VARIANTS:
        for level in (1, 5):
            problems, facts = check(v, level)
            print(f"variant {v}, level {level}: {facts}  {'ok' if not problems else 'PROBLEMS'}")
            for pr in problems:
                print("   ", pr)
            ok = ok and not problems
    if not ok:
        sys.exit("the variants break the rules: nothing rendered")
    os.makedirs(OUT, exist_ok=True)
    if "--sheets" not in sys.argv:
        write_drafts()
        import driver  # noqa: E402  (after CS_ASSETS is set)
        js = jobs()
        only = [a for a in sys.argv[1:] if not a.startswith("--")]
        if only:
            js = [j for j in js if any(o in os.path.basename(j["out"]) for o in only)]
        driver.render_jobs(js)
        for j in js:
            if os.path.exists(j["out"]):
                Image.open(j["out"]).convert("RGB").save(j["out"], optimize=True)
    if all(os.path.exists(png(f"storage-{v}-{k}")) for v in VARIANTS for k, *_ in PANELS):
        for v in VARIANTS:
            sheet(v)


if __name__ == "__main__":
    main()
