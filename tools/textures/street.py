"""The street pieces of the Decorator's wand (0.2.0, Q11): models and blockstates of the two snowmen, the festive lantern
post, the garland across a street and the thin street lights, the item models, and the wand's item texture.

The owner's picks of the pictures round (docs/town-square-design.md, "Owner answers, pictures round"): snowman A (the
classic one, red scarf, stick arms) is the default and B (top hat, broom) the second; the lantern post is B, the crook
with a garland. The snowmen start from the round's draft models (design/src/square_assets/.../snowman_a.json and
snowman_b.json), the post from sq_lantern_post_b.json with its crook pulled in so the lantern hangs at the edge of its
cell; everything is drawn with the game's own textures (snow, wool, blackstone, azalea leaves, the lantern) through
model elements, as the drafts were. The lit models carry NeoForge's glow data on the lantern and the bulbs.

Run:  python -I tools/textures/street.py     (or through make_all.py); the files are committed.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from common import (CUTOUT, ITEM_MODELS, ITEM_TEX, STATES, ensure_dirs, from_grid, glowing, ref,  # noqa: E402
                    save_tex, write_json, block_model)

FACES = ("down", "up", "north", "south", "west", "east")


def _uv(face, frm, to):
    """Vanilla's default uv of a face, moved into 0..16 (a face of an element that reaches out of its cell would
    otherwise sample the next sprite on the atlas)."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    uv = {"down": [x0, 16 - z1, x1, 16 - z0], "up": [x0, z0, x1, z1],
          "north": [16 - x1, 16 - y1, 16 - x0, 16 - y0], "south": [x0, 16 - y1, x1, 16 - y0],
          "west": [z0, 16 - y1, z1, 16 - y0], "east": [16 - z1, 16 - y1, 16 - z0, 16 - y0]}[face]
    out = list(uv)
    for a, b in ((0, 2), (1, 3)):
        lo, hi = min(out[a], out[b]), max(out[a], out[b])
        if hi - lo > 16:
            lo, hi = 0, 16
        shift = 0
        if lo < 0:
            shift = -lo
        elif hi > 16:
            shift = 16 - hi
        out[a] += shift
        out[b] += shift
        out[a] = max(0, min(16, out[a]))
        out[b] = max(0, min(16, out[b]))
    return [round(v, 3) for v in out]


def box(frm, to, tex, rotation=None, faces=FACES, uv=None):
    """One element; every face samples `tex` (a slot name), uv from vanilla's default moved into range unless given."""
    e = {"from": list(frm), "to": list(to),
         "faces": {f: {"uv": (uv if uv is not None else _uv(f, frm, to)), "texture": "#" + tex} for f in faces}}
    if rotation:
        e["rotation"] = rotation
    return e


def moved(element, dx=0, dy=0, dz=0):
    e = dict(element)
    e["from"] = [element["from"][0] + dx, element["from"][1] + dy, element["from"][2] + dz]
    e["to"] = [element["to"][0] + dx, element["to"][1] + dy, element["to"][2] + dz]
    if "rotation" in element:
        r = dict(element["rotation"])
        o = r["origin"]
        r["origin"] = [o[0] + dx, o[1] + dy, o[2] + dz]
        e["rotation"] = r
    return e


# ------------------------------------------------------------------ the snowmen (the drafts, element for element)
SNOW = {"snow": "minecraft:block/snow", "coal": "minecraft:block/coal_block", "carrot": "minecraft:block/orange_terracotta",
        "particle": "minecraft:block/snow"}


def snowman_a():
    t = dict(SNOW, scarf="minecraft:block/red_wool", stick="minecraft:block/dark_oak_log")
    e = [box([2, 0, 2], [14, 11, 14], "snow"), box([3, 1, 1], [13, 10, 2], "snow"), box([3, 1, 14], [13, 10, 15], "snow"),
         box([1, 1, 3], [2, 10, 13], "snow"), box([14, 1, 3], [15, 10, 13], "snow"),
         box([3.5, 11, 3.5], [12.5, 19, 12.5], "snow"), box([4.5, 12, 2.75], [11.5, 18, 3.5], "snow"),
         box([5, 19, 5], [11, 25, 11], "snow"),
         box([4.25, 18.5, 4.25], [11.75, 20, 11.75], "scarf"), box([9, 13, 3.5], [11, 19, 4.25], "scarf"),
         box([6.25, 22, 4.5], [7.25, 23, 5], "coal"), box([8.75, 22, 4.5], [9.75, 23, 5], "coal"),
         box([7.5, 20.5, 2], [8.5, 21.5, 5], "carrot"),
         box([7.5, 15.5, 2.25], [8.5, 16.5, 2.75], "coal"), box([7.5, 13.5, 2.25], [8.5, 14.5, 2.75], "coal"),
         box([-3, 15, 7.5], [3.5, 16, 8.5], "stick", {"angle": -22.5, "axis": "z", "origin": [3.5, 15.5, 8]}),
         box([12.5, 15, 7.5], [19, 16, 8.5], "stick", {"angle": 22.5, "axis": "z", "origin": [12.5, 15.5, 8]})]
    return t, e


def snowman_b():
    t = dict(SNOW, scarf="minecraft:block/green_wool", hat="minecraft:block/black_wool", band="minecraft:block/red_wool",
             stick="minecraft:block/stripped_spruce_log", straw="minecraft:block/hay_block_side")
    e = [box([1.5, 0, 1.5], [14.5, 13, 14.5], "snow"), box([2.5, 1, 0.75], [13.5, 12, 1.5], "snow"),
         box([2.5, 1, 14.5], [13.5, 12, 15.25], "snow"), box([0.75, 1, 2.5], [1.5, 12, 13.5], "snow"),
         box([14.5, 1, 2.5], [15.25, 12, 13.5], "snow"),
         box([4, 13, 4], [12, 21, 12], "snow"),
         box([3.75, 12.5, 3.75], [12.25, 14, 12.25], "scarf"), box([4.5, 7, 3], [6.5, 13, 3.75], "scarf"),
         box([3, 21, 3], [13, 22, 13], "hat"), box([5, 22, 5], [11, 28, 11], "hat"), box([4.9, 22, 4.9], [11.1, 23.5, 11.1], "band"),
         box([5.75, 17.5, 3.5], [6.75, 18.5, 4], "coal"), box([9.25, 17.5, 3.5], [10.25, 18.5, 4], "coal"),
         box([7.5, 16, 1], [8.5, 17, 4], "carrot"),
         box([7.5, 9.5, 1.25], [8.5, 10.5, 1.75], "coal"), box([7.5, 6.5, 0.5], [8.5, 7.5, 1], "coal"),
         box([15, 0, 7.5], [16, 20, 8.5], "stick", {"angle": -22.5, "axis": "z", "origin": [15.5, 0, 8]}),
         box([14, -1, 6.5], [18, 4, 9.5], "straw", {"angle": -22.5, "axis": "z", "origin": [15.5, 0, 8]})]
    return t, e


# ------------------------------------------------------------------ the lantern post (B, the crook), drawn facing north
POST = {"base": "minecraft:block/polished_blackstone_bricks", "pole": "minecraft:block/polished_blackstone",
        "green": "minecraft:block/azalea_leaves", "bow": "minecraft:block/red_wool", "lantern": "minecraft:block/lantern",
        "bulb": "minecraft:block/yellow_terracotta", "bulb2": "minecraft:block/red_terracotta",
        "particle": "minecraft:block/polished_blackstone_bricks"}
LIT_BULBS = {"bulb": "minecraft:block/yellow_wool", "bulb2": "minecraft:block/red_wool"}


def post_lower(lit):
    e = [box([4, 0, 4], [12, 3, 12], "base"), box([7, 3, 7], [9, 16, 9], "pole"),
         box([5.5, 6, 6], [9.5, 8.5, 10], "green"), box([6, 11, 6], [10, 13.5, 10], "green")]
    bulbs = [box([5, 7, 7.5], [6, 8, 8.5], "bulb"), box([9.5, 12, 7.5], [10.5, 13, 8.5], "bulb2"),
             box([7.5, 11.5, 5.5], [8.5, 12.5, 6.5], "bulb")]
    e += [glowing(b) for b in bulbs] if lit else bulbs
    return e


def lantern(lit):
    """The game's hanging lantern (template_hanging_lantern's body and cap, its own uv), hung from the crook."""
    faces_body = {"down": [0, 9, 6, 15], "up": [0, 9, 6, 15], "north": [0, 2, 6, 9], "south": [0, 2, 6, 9],
                  "west": [0, 2, 6, 9], "east": [0, 2, 6, 9]}
    faces_cap = {"down": [1, 10, 5, 14], "up": [1, 10, 5, 14], "north": [1, 0, 5, 2], "south": [1, 0, 5, 2],
                 "west": [1, 0, 5, 2], "east": [1, 0, 5, 2]}
    body = {"from": [5, 3, -3.5], "to": [11, 10, 2.5], "faces": {f: {"uv": uv, "texture": "#lantern"} for f, uv in faces_body.items()}}
    cap = {"from": [6, 10, -2.5], "to": [10, 12, 1.5], "faces": {f: {"uv": uv, "texture": "#lantern"} for f, uv in faces_cap.items()}}
    return [glowing(body), glowing(cap)] if lit else [body, cap]


def post_upper(lit):
    e = [box([7, 0, 7], [9, 14, 9], "pole"), box([7, 14, -2], [9, 16, 9], "pole"), box([7.5, 12, -1], [8.5, 14, 0], "pole"),
         box([6.5, 0, 6], [10.5, 2.5, 10], "green"), box([6, 5, 6], [10, 7.5, 10], "green"),
         box([5.5, 10, 6], [9.5, 12.5, 10], "green"),
         box([6.5, 4, 5.25], [9.5, 6.5, 6], "bow"), box([5, 4.5, 5.5], [6.5, 6, 6], "bow"), box([9.5, 4.5, 5.5], [11, 6, 6], "bow"),
         box([7.25, 1.5, 5.5], [8.75, 4, 6], "bow")]
    bulbs = [box([5, 1, 7.5], [6, 2, 8.5], "bulb2"), box([10, 6, 7.5], [11, 7, 8.5], "bulb")]
    e += [glowing(b) for b in bulbs] if lit else bulbs
    return e + lantern(lit)


# ------------------------------------------------------------------ the garland across a street (along x, drawn sagging)
def garland():
    t = {"green": "minecraft:block/azalea_leaves", "bow": "minecraft:block/red_wool", "particle": "minecraft:block/azalea_leaves"}
    e = [box([0, 13, 7], [3, 15, 9], "green"), box([3, 12, 7], [6, 14, 9], "green"), box([6, 11, 7], [10, 13, 9], "green"),
         box([10, 12, 7], [13, 14, 9], "green"), box([13, 13, 7], [16, 15, 9], "green"),
         box([7, 9.5, 6.5], [9, 11.5, 9.5], "bow"), box([6, 11, 6.75], [7, 12, 9.25], "bow"), box([9, 11, 6.75], [10, 12, 9.25], "bow")]
    return t, e


# ------------------------------------------------------------------ the thin street lights (along x, on a fence's top)
def street_lights(lit):
    t = {"wire": "minecraft:block/black_concrete", "red": "minecraft:block/red_terracotta", "yellow": "minecraft:block/yellow_terracotta",
         "green": "minecraft:block/lime_terracotta", "blue": "minecraft:block/light_blue_terracotta",
         "particle": "minecraft:block/black_concrete"}
    if lit:
        t.update(red="minecraft:block/red_wool", yellow="minecraft:block/yellow_wool", green="minecraft:block/lime_wool",
                 blue="minecraft:block/light_blue_wool")
    e = [box([0, 0.5, 7.5], [16, 1, 8.5], "wire")]
    bulbs = [box([1.5, 1, 7.25], [3, 2.5, 8.75], "red"), box([5.5, 1, 7.25], [7, 2.5, 8.75], "yellow"),
             box([9.5, 1, 7.25], [11, 2.5, 8.75], "green"), box([13.5, 1, 7.25], [15, 2.5, 8.75], "blue")]
    e += [glowing(b) for b in bulbs] if lit else bulbs
    return t, e


# ------------------------------------------------------------------ the wand's item texture
WAND_PAL = {"d": "#5a3a1e", "l": "#8a5a2e", "r": "#b02a2e", "R": "#d8504a", "y": "#e9b83a", "Y": "#fff0a0", "W": "#ffffff"}
WAND = [
    "................",
    "............y...",
    "...........yYy..",
    "..........yYWYy.",
    "...........yYy..",
    "..........r.y...",
    ".........rRr....",
    "........dRr.....",
    ".......dlr......",
    "......dl........",
    ".....dl.........",
    "....dl..........",
    "...dl...........",
    "..dl............",
    ".dd.............",
    "................",
]

FACINGS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def generate():
    ensure_dirs()
    for name, (textures, elements) in (("snowman", snowman_a()), ("snowman_b", snowman_b())):
        block_model(name, textures, elements, CUTOUT)
        write_json(STATES, name, {"variants": {f"facing={f}": ({"model": ref(name), "y": rot} if rot else {"model": ref(name)})
                                               for f, rot in FACINGS}})
        write_json(ITEM_MODELS, name, {"parent": ref(name)})
    for half, maker in (("lower", post_lower), ("upper", post_upper)):
        block_model(f"lantern_post_{half}", POST, maker(False), CUTOUT)
        block_model(f"lantern_post_{half}_lit", dict(POST, **LIT_BULBS), maker(True), CUTOUT)
    variants = {}
    for half in ("lower", "upper"):
        for lit in ("false", "true"):
            model = ref(f"lantern_post_{half}" + ("_lit" if lit == "true" else ""))
            for f, rot in FACINGS:
                entry = {"model": model}
                if rot:
                    entry["y"] = rot
                variants[f"facing={f},half={half},lit={lit}"] = entry
    write_json(STATES, "lantern_post", {"variants": variants})
    # the item: both halves in one model, the upper one 16 px up, shown smaller
    whole = post_lower(False) + [moved(e, dy=16) for e in post_upper(False)]
    block_model("lantern_post_item", POST, whole, CUTOUT)
    write_json(ITEM_MODELS, "lantern_post", {
        "parent": ref("lantern_post_item"),
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, -3, 0], "scale": [0.36, 0.36, 0.36]},
                    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.18, 0.18, 0.18]},
                    "fixed": {"rotation": [0, 0, 0], "translation": [0, -2, 0], "scale": [0.3, 0.3, 0.3]},
                    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 0, 0], "scale": [0.25, 0.25, 0.25]},
                    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, -2, 0], "scale": [0.25, 0.25, 0.25]},
                    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, -2, 0], "scale": [0.25, 0.25, 0.25]}}})
    t, e = garland()
    block_model("street_garland", t, e, CUTOUT)
    write_json(STATES, "street_garland", {"variants": {"axis=x": {"model": ref("street_garland")},
                                                       "axis=z": {"model": ref("street_garland"), "y": 90}}})
    for lit in (False, True):
        t, e = street_lights(lit)
        block_model("street_lights" + ("_lit" if lit else ""), t, e, CUTOUT)
    write_json(STATES, "street_lights", {"variants": {
        "axis=x,lit=false": {"model": ref("street_lights")}, "axis=z,lit=false": {"model": ref("street_lights"), "y": 90},
        "axis=x,lit=true": {"model": ref("street_lights_lit")}, "axis=z,lit=true": {"model": ref("street_lights_lit"), "y": 90}}})
    save_tex(from_grid(WAND, WAND_PAL), "decorators_wand", ITEM_TEX)
    write_json(ITEM_MODELS, "decorators_wand", {"parent": "minecraft:item/handheld", "textures": {"layer0": ref("decorators_wand", "item")}})
    return {"snowman": 2, "lantern_post": 5, "street_garland": 1, "street_lights": 2}


if __name__ == "__main__":
    import common  # noqa: E402
    generate()
    print(f"{len(common.written)} files written")
