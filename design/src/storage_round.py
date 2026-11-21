"""Storage room picture round (chunk S10g, 21 Nov 2026): the Decorator's Workshop storage room with more dressing, in
two variants and two states each, for docs/storage-look-design.md.

The owner looked at the S10e cutaways (docs/renders/s10e) and asked for more decoration in the storage room and, by
season or festival, things hung up inside or the pieces not in use packed away. This script draws:

  variant A  tidy storeroom: ordered barrels, labelled boxes on the barrels, coils on wall pegs
  variant B  busy loft: half-open crates, sacks, hay, lanterns on fence brackets, a ladder, coils with a loose end

each in two states, "packed" (outside the festival window) and "festival" (in the window), at level 1 (the storage
corner) and level 5 (the attic), as cutaways like tools/render_s5.py's S10e ones (front wall and roof with the tie
beam left out), plus the level 5 exterior.

What is drawn follows the mechanism in docs/storage-look-design.md, and the script checks it before it renders:
  - the hut is read from the shipped blueprints (resources/blueprints/...), its racks keep their cells;
  - the blueprint layer ("furniture") goes only into cells the shipped blueprint leaves as air, strictly inside the
    walls, off the ladder and its exit; it is vanilla blocks, except B's crate, our own block (on the Builder's bill);
  - the Decorator's layer goes only into air (ledger rule): a standing piece on a barrel, crate or hay bale, a hung
    piece on a wall or beam face with a full block behind it; nothing of his stands on the floor or on a rack
    (MineColonies' full rack model draws two boxes 6 px up into the cell above), nothing right behind a window;
  - a colonist still reaches the hut block and every floor cell from the door (tools/walkcheck.py), so no exterior
    cell changes and the walk path and the ladder stay clear.
Racks are drawn as MineColonies draws them in the game: the full model while they hold anything (packed: all; in the
festival the attic racks that held decorations are empty), single racks (the game joins neighbours into doubles,
which look the same).

New blocks (namespace colonyseasons, names st_*) are look drafts written to the scratch folder at run time, with
vanilla textures by reference, the mod's own decoration textures (resources/assets) and two small hand-drawn ones
(a paper label and burlap). Nothing here is shipped.

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
CONTENT_COLOUR = {"wreath": (168, 50, 45), "garland": (63, 122, 55), "lights": (217, 169, 43),
                  "lanterns": (200, 100, 42), "gifts": (58, 95, 160)}
LABEL_SLOT = {"wreath": (0, 0), "garland": (8, 0), "lights": (0, 5), "lanterns": (8, 5), "gifts": (0, 10)}


def h01(x, y, salt=0):
    n = (x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)
    n = (n ^ (n >> 13)) * 1274126177
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def tex_label():
    """Paper labels, 7 x 4 px each: a coloured band for the content, a few ink strokes, a darker edge."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    for content, (x0, y0) in LABEL_SLOT.items():
        col = CONTENT_COLOUR[content]
        for y in range(4):
            for x in range(7):
                paper = (222, 211, 176) if h01(x0 + x, y0 + y, 3) < 0.7 else (210, 198, 160)
                if y == 3 or x == 6:
                    paper = (182, 168, 128)
                px[x0 + x, y0 + y] = paper + (255,)
            for y in range(4):
                px[x0, y0 + y] = col + (255,)
                px[x0 + 1, y0 + y] = tuple(max(0, c - 25) for c in col) + (255,)
            for x in (3, 4, 5):
                if h01(x0 + x, y0, 7) < 0.8:
                    px[x0 + x, y0 + 1] = (74, 64, 56, 255)
            for x in (3, 4):
                px[x0 + x, y0 + 2] = (96, 84, 72, 255)
    return im


def tex_burlap():
    """Coarse jute cloth: a fine two-tone weave with a few slubs, no stripes."""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    for y in range(16):
        for x in range(16):
            base = (186, 156, 108) if (x + y) % 2 == 0 else (170, 141, 96)
            k = h01(x, y, 11)
            if k < 0.10:
                base = (198, 170, 122)
            elif k > 0.90:
                base = (150, 122, 80)
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
L_TOP, L_SIDE = [0, 5, 16, 8], [0, 4, 16, 9]          # the bulbs (top) and wire and bulbs (sides) of fairy_lights.png
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
            out += ring(8, 8, 11, 2.4, top - 2.2, top - 0.6, LIGHTS, L_TOP, L_SIDE)
            out += ring(8, 8, 7, 2, top - 0.6, top + 0.6, LIGHTS, L_TOP, L_SIDE)
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
            out += ring(9, 7, 6, 1.8, floor, floor + 1.2, LIGHTS, L_TOP, L_SIDE)
        elif kind == "lanterns":
            out.append(lantern(9, floor, 9, YELLOW_LANTERN))
        elif kind == "gifts":
            out.append(gift([4, floor, 4], [9.5, floor + 4.5, 9.5], "green"))
    return out


def model_box(content, fill):
    """A: a labelled spruce box, open at the top, 14 x 10 x 14 px; the label faces north (the block's front)."""
    w = "#box"
    els = [el([1, 0, 1], [15, 1, 15], w), el([1, 1, 1], [15, 10, 2], w), el([1, 1, 14], [15, 10, 15], w),
           el([1, 1, 2], [2, 10, 14], w), el([14, 1, 2], [15, 10, 14], w),
           el([1, 9.4, 0.9], [15, 10.2, 2.1], "#post", faces=["north", "up"]),
           el([4.5, 3.5, 0.75], [11.5, 7.5, 1], "#label", faces=["north"],
              uv={"north": [LABEL_SLOT[content][0], LABEL_SLOT[content][1],
                            LABEL_SLOT[content][0] + 7, LABEL_SLOT[content][1] + 4]})]
    return els + contents(content, fill, 1, 9.2)


def model_coil(kind, drape):
    """A coil lying on a rack or barrel top; drape: one end hangs over the front edge (north)."""
    tex, top, side = (GARLAND, G_TOP, G_SIDE) if kind == "garland" else (LIGHTS, L_TOP, L_SIDE)
    els = ring(8, 8, 13, 3, 0, 2, tex, top, side) + ring(8, 8, 8.5, 2.5, 2, 3.6, tex, top, side)
    els += ring(8, 8, 4.4, 1.6, 0, 1.6, tex, top, side)
    if drape:
        els.append(el([4, -8, 0.15], [11, 1, 0.35], tex, faces=["north", "south"],
                      uv={"*": [0, 4, 16, 10] if kind == "garland" else [0, 3, 16, 10]},
                      frot={"north": 90, "south": 90}))
    return els


def model_hung_coil(kind, drape):
    """A coil hung on a wooden peg on the wall (the wall is south, as for the shipped wall decorations): two rings
    flat against the wall; drape: a loose end hangs down to the bottom of the cell."""
    tex, side = (GARLAND, G_SIDE) if kind == "garland" else (LIGHTS, L_SIDE)
    uv = {"*": side}

    def vring(cx, cy, outer, width, z0, z1):
        r, w = outer / 2.0, width
        side = 2 * r * 0.4142
        out = [el([cx - side / 2, cy + r - w, z0], [cx + side / 2, cy + r, z1], tex, uv=uv),
               el([cx - side / 2, cy - r, z0], [cx + side / 2, cy - r + w, z1], tex, uv=uv),
               el([cx - r, cy - side / 2, z0], [cx - r + w, cy + side / 2, z1], tex, uv=uv),
               el([cx + r - w, cy - side / 2, z0], [cx + r, cy + side / 2, z1], tex, uv=uv)]
        d = (r - w / 2) * 0.7071
        for sx, sy in ((1, 1), (-1, -1), (1, -1), (-1, 1)):
            mx, my = cx + sx * d, cy + sy * d
            out.append(el([mx - side * 0.56, my - w / 2, z0], [mx + side * 0.56, my + w / 2, z1], tex, uv=uv,
                          rot={"angle": -45 if sx * sy > 0 else 45, "axis": "z", "origin": [mx, my, (z0 + z1) / 2]}))
        return out
    els = [el([7, 12.5, 12], [9, 14, 16], "#post")]
    els += vring(8, 7.5, 11, 2.6, 13.2, 15.8) + vring(8.6, 7.0, 8, 2.2, 11.4, 13.2)
    if drape:
        els.append(el([10, 0.2, 12.2], [13, 7, 12.4], tex, faces=["north", "south"],
                      uv={"*": [0, 4, 16, 10] if kind == "garland" else [0, 3, 16, 10]},
                      frot={"north": 90, "south": 90}))
    return els


def model_sack(fill):
    """B: a burlap sack, tied at the neck, turned a little; empty: folded flat."""
    b = "#burlap"
    turn = {"angle": 22.5, "axis": "y", "origin": [8, 0, 8]}
    if fill == "full":
        parts = [el([3.4, 0, 3.4], [12.6, 1, 12.6], b), el([2.6, 1, 2.6], [13.4, 8, 13.4], b),
                 el([3.6, 8, 3.6], [12.4, 9.6, 12.4], b), el([6, 9.6, 6], [10, 11.6, 10], b),
                 el([5.6, 10, 5.6], [10.4, 11, 10.4], "#rope"), el([5, 11.6, 5.4], [11, 12.8, 10.6], b),
                 el([4.6, 11.8, 7], [5.6, 12.6, 9], b)]
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
            els += ring(7, 6, 9, 2.4, -5.5, -3.8, LIGHTS, L_TOP, L_SIDE)
            els += ring(8, 6.5, 5.5, 2, -3.8, -2.6, LIGHTS, L_TOP, L_SIDE)
            els.append(el([5, -14, 1.0], [10, -3.6, 1.2], LIGHTS, faces=["north", "south"],
                          uv={"*": [0, 3, 16, 10]}, frot={"north": 90, "south": 90}))
        elif kind == "gifts":
            els += [gift([3, -9, 3], [8.5, -3, 8.5], "red"), gift([8, -10, 2.8], [13.2, -4, 8], "green"),
                    gift([5, -6, 4.5], [10.5, -0.5, 10], "blue")]
    else:
        if kind == "wreath":
            els.append(el([3, floor + 0.1, 3], [12, floor + 0.3, 12], "#wreath", faces=["up"], uv={"up": FULL}))
        elif kind == "garland":
            els += ring(7, 6, 6.5, 2.2, floor, floor + 1.6, GARLAND, G_TOP, G_SIDE)
        elif kind == "lights":
            els += ring(7, 6, 6, 1.8, floor, floor + 1.2, LIGHTS, L_TOP, L_SIDE)
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
    for k in ("garland", "lights"):
        for drape in ("false", "true"):
            save_model(f"st_hung_coil_{k}_{drape}", model_hung_coil(k, drape == "true"))
            v.update(facing_variants(f"drape={drape},kind={k}", f"st_hung_coil_{k}_{drape}"))
    save_state("st_hung_coil", v)
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


def hung_coil(kind, drape=False, facing="south"):
    return f"{D}:st_hung_coil[drape={'true' if drape else 'false'},facing={facing},kind={kind}]"


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
    Decorator's two sets (into air, on furniture or walls) and the racks that are empty in the window. Nothing stands
    right behind a window, so the outside looks the same, and nothing of his sits on a rack: MineColonies' full rack
    model draws two boxes standing 6 px up into the cell above the rack."""
    if (variant, level) == ("A", 1):
        furniture = {(3, 1, -3): BARREL_UP, (4, 1, 0): "minecraft:barrel[facing=west]"}
        packed = {(3, 2, -3): box("lights", "full"), (4, 3, -3): coil("garland"),
                  (4, 2, -2): box("gifts", "full", "west"), (4, 2, 0): box("wreath", "full", "west"),
                  (1, 3, -3): hung_coil("garland"), (2, 3, -3): hung_coil("lights")}
        festival = {p: b.replace("fill=full", "fill=low") for p, b in packed.items() if "st_box" in b}
        festival.update(row("garland", "south", [(x, 4, -3) for x in range(1, 5)]))
        festival[(2, 3, -3)] = wall("wreath", "south")
        festival.update(row("fairy_lights", "west", [(4, 4, z) for z in range(-2, 3)]))
        return dict(furniture=furniture, packed=packed, festival=festival, empty_in_festival={(1, 1, -3)})
    if (variant, level) == ("A", 5):
        furniture = {(4, 6, -3): BARREL_UP, (4, 7, -3): BARREL_UP, (-4, 7, -2): BARREL_UP}
        packed = {(-4, 7, -3): box("wreath", "full"), (-3, 8, -3): box("gifts", "full"),
                  (-4, 8, -2): box("lights", "full", "east"), (2, 7, -3): box("lanterns", "full"),
                  (4, 8, -3): box("garland", "full"),
                  (0, 8, -3): hung_coil("garland"), (1, 8, -3): hung_coil("lights"), (3, 8, -3): hung_coil("garland")}
        festival = {p: b.replace("fill=full", "fill=low") for p, b in packed.items() if "st_box" in b}
        festival.update(row("garland", "south", [(x, 8, -3) for x in range(-2, 4)]))
        festival[(3, 7, -3)] = wall("wreath", "south")
        festival.update(row("fairy_lights", "east", [(-4, 8, z) for z in (-1, 1, 2, 3)]))
        festival.update(row("fairy_lights", "west", [(4, 8, z) for z in range(-2, 4)]))
        return dict(furniture=furniture, packed=packed, festival=festival,
                    empty_in_festival={(-2, 6, -3), (-1, 6, -3), (-2, 7, -3), (-1, 7, -3)})
    if (variant, level) == ("B", 1):
        furniture = {(3, 1, -3): CRATE, (4, 1, 2): HAY}
        furniture.update({(4, y, 0): "minecraft:ladder[facing=west]" for y in (1, 2, 3)})
        packed = {(3, 2, -3): heap("garland", "full"), (1, 3, -3): hung_coil("garland", True),
                  (2, 3, -3): hung_coil("lights", True), (4, 2, 2): sack("full", "west"),
                  (4, 3, -3): sack("full", "west"), (4, 2, -2): gifts("full", "west")}
        festival = {(3, 2, -3): heap("garland", "low"), (4, 2, 2): sack("empty", "west"),
                    (4, 3, -3): sack("empty", "west"), (4, 2, -2): gifts("low", "west")}
        festival.update(row("lantern_string", "south", [(x, 4, -3) for x in range(1, 5)]))
        festival.update(row("garland", "west", [(4, 4, z) for z in range(-2, 3)]))
        festival[(3, 3, -3)] = wall("wreath", "south")
        return dict(furniture=furniture, packed=packed, festival=festival, empty_in_festival={(1, 1, -3)})
    if (variant, level) == ("B", 5):
        furniture = {(4, 6, -3): CRATE, (4, 6, -1): CRATE, (-3, 6, -2): CRATE,
                     (4, 6, 1): HAY, (4, 6, 3): HAY, (4, 7, 3): HAY,
                     (3, 8, -3): "minecraft:spruce_fence", (3, 7, -3): LANTERN_HANGING,
                     (4, 8, 1): "minecraft:spruce_fence", (4, 7, 1): LANTERN_HANGING}
        furniture.update({(4, y, 0): "minecraft:ladder[facing=west]" for y in (6, 7, 8)})
        packed = {(4, 7, -3): heap("garland", "full"), (4, 7, -1): heap("wreath", "full", "west"),
                  (-3, 7, -2): heap("lights", "full"),
                  (0, 8, -3): hung_coil("lights", True), (1, 8, -3): hung_coil("garland", True),
                  (-4, 7, -2): coil("garland", True, "east"),
                  (4, 8, 3): sack("full", "west"), (-3, 8, -3): sack("full"), (2, 7, -3): sack("full"),
                  (-4, 7, -3): gifts("full")}
        festival = {(4, 7, -3): heap("garland", "low"), (4, 7, -1): heap("wreath", "low", "west"),
                    (-3, 7, -2): heap("lights", "low"),
                    (4, 8, 3): sack("empty", "west"), (-3, 8, -3): sack("empty"), (2, 7, -3): sack("empty"),
                    (-4, 7, -3): gifts("low")}
        festival.update(row("lantern_string", "south", [(x, 8, -3) for x in range(-2, 3)]))
        festival.update(row("garland", "east", [(-4, 8, z) for z in (-3, -2, -1, 1, 2, 3)]))
        festival.update(row("garland", "west", [(4, 8, z) for z in (-3, -1, 2)]))
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


def check(variant, level):
    """The rules of docs/storage-look-design.md on one variant and level. Returns (problems, facts)."""
    base = shipped(level)
    p = plan(variant, level)
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
            on_wall = n.startswith(f"{NS}:") or n == f"{D}:st_hung_coil"
            if not on_wall and behind_window(q):
                problems.append(f"{state} {q}: right behind a window")
            below = built.get(x, y - 1, z)
            if not on_wall and below is not None and name_of(below) == RACK:
                problems.append(f"{state} {q}: on top of a rack (MineColonies draws its full rack's boxes there)")
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
CAM_ATTIC = dict(yaw=-6, pitch=30, ortho=10.8, target=[0.6, 5.5, -1.0], res=[1000, 1000])
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
        lines = ["Builder (blueprint): vanilla only, nothing new on the bill:",
                 f"  L1 +{f1['furniture']} barrels, L5 +{f5['furniture']} barrels; {racks}.",
                 "Decorator (own thin blocks, ledger rule, into air only):",
                 f"  packed: labelled boxes on the barrels, coils on wall pegs {counts}; nothing of his stands on "
                 "the floor or on a rack.",
                 f"  festival: the boxes swapped to nearly empty, the coils taken off; hung: garland on the back beam, "
                 f"a wreath, fairy lights {hung}."]
    else:
        lines = ["Builder (blueprint): hay, a ladder, lanterns on fence brackets (vanilla) and our own crate, which "
                 f"goes on the bill: L1 {f1['own_in_blueprint']}, L5 {f5['own_in_blueprint']}; {racks}.",
                 "Decorator (own thin blocks, ledger rule, into air only):",
                 f"  packed: heaps in the crates, sacks and gifts on top, coils hung on wall pegs {counts}; nothing "
                 "of his on the floor or on a rack.",
                 f"  festival: crates nearly empty, sacks folded, coils taken off; hung: lantern string on the back "
                 f"beam, garland on the side beams, a wreath {hung}."]
    lines += ["Racks look as MineColonies draws them: while they hold anything, two boxes on top; in the window "
              "the racks of decorations are empty.",
              "Checked: inside the walls, nothing right behind a window, every floor cell reachable from the door, "
              "the ladder and its exit clear."]
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
    d.text((gap, 14), f"Storage room, variant {variant}: {VARIANTS[variant]}  (left: packed, outside the festival "
                      f"window; middle: festival)", fill=(30, 30, 30), font=font(32))
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


def main():
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
