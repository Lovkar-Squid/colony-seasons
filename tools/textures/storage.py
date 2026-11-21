"""The storeroom pieces of the Decorator's Workshop (chunk S10g1, docs/storage-look-design.md, variant A picked):
storage_box (a labelled spruce box, open at the top: content wreath, garland, lights, lanterns or gifts, fill full or
low, so 10 models) and storage_coil (a coil lying on a barrel: kind garland or lights, 2 models).

The geometry is the design round's look draft (design/src/storage_round.py: model_box, model_coil without the loose
end, which was variant B's), so the blocks look as the owner saw them in design/storage/sheet-storage-A.png. The
models name the mod's own decoration textures (wreath, garland, fairy lights, lantern string, gift paper) and vanilla's
stripped spruce log, dark oak planks and hay by reference; the one new texture is the card labels (storage_label),
drawn here as a grid of whole pixels like every other texture of the mod.

No item models: these blocks have no item (they are the Decorator's room dressing, placed only by him).
"""
from common import (CUTOUT, FACINGS, STATES, block_model, from_grid, ref, round_floats, save_tex, write_json)

# ------------------------------------------------------------------ the card labels (16 x 16, five 8 x 3 labels)
# a band in the content's colour along the top (colour-coded storage), one word of ink on paper below it, a shaded
# lower edge; the label of a content sits at LABEL_SLOT[content] (its top-left pixel)
LABEL_PAL = {
    "R": "#b0342e",   # wreath: red
    "G": "#3e7e38",   # garland: green
    "Y": "#dcaa28",   # lights: yellow
    "O": "#cc682c",   # lanterns: orange
    "B": "#3c62a6",   # gifts: blue
    "p": "#e2d8b8", "q": "#d8cdac",   # paper, a darker fleck
    "i": "#3e322c",   # ink
    "e": "#beb290",   # the shaded lower edge
}
LABEL = [
    "RRRRRRRRGGGGGGGG",
    "piiiiiippiiiiiip",
    "eeeeeeeeeeeeeeee",
    "................",
    "YYYYYYYYOOOOOOOO",
    "piiiiiippiiiiipq",
    "eeeeeeeeeeeeeeee",
    "................",
    "BBBBBBBB........",
    "piiiiiip........",
    "eeeeeeee........",
    "................",
    "................",
    "................",
    "................",
    "................",
]
CONTENTS = ("wreath", "garland", "lights", "lanterns", "gifts")
FILLS = ("full", "low")
COILS = ("garland", "lights")
LABEL_SLOT = {"wreath": (0, 0), "garland": (8, 0), "lights": (0, 4), "lanterns": (8, 4), "gifts": (0, 8)}
LABEL_W, LABEL_H = 8, 3

TEX = {"garland": ref("garland"), "lights": ref("fairy_lights"), "wreath": ref("wreath"),
       "lantern": ref("lantern_string_0"), "red": ref("gift_red"), "green": ref("gift_green"),
       "blue": ref("gift_blue"), "label": ref("storage_label"), "post": "minecraft:block/dark_oak_planks",
       "box": "minecraft:block/stripped_spruce_log", "straw": "minecraft:block/hay_block_top"}


# ------------------------------------------------------------------ elements (storage_round.py's helpers)
def el(frm, to, tex, faces=None, uv=None, rot=None):
    e = {"from": list(frm), "to": list(to), "faces": {}}
    for f in faces or ("north", "south", "east", "west", "up", "down"):
        e["faces"][f] = {"texture": tex}
        if uv:
            u = uv.get(f, uv.get("*"))
            if u:
                e["faces"][f]["uv"] = list(u)
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
G_TOP, G_SIDE = [0, 6, 16, 8], [0, 5, 16, 8]          # the leafy band of garland.png
WIRE = [0, 4, 1, 5]                                    # one dark wire pixel of fairy_lights.png
BULBS = [[1, 6, 3, 8], [5, 6, 7, 8], [9, 6, 11, 8], [13, 6, 15, 8]]   # red, green, blue, yellow
CAP = [1, 4, 3, 5]                                     # the bulb holder
FULL = [0, 0, 16, 16]
RED_LANTERN, YELLOW_LANTERN = [2, 6, 7, 12], [9, 7, 14, 13]     # two paper lanterns of lantern_string_0.png


def light_coil(cx, cz, outer, y0, n_bulbs, phase=0):
    """A coil of fairy lights lying flat: a thin dark wire ring with bulbs lying on it, each pointing out from the
    coil, its holder on the wire (bulbs at the eight 45-degree points only, the turns a model element may make)."""
    els = ring(cx, cz, outer, 1.0, y0, y0 + 1.0, LIGHTS, WIRE, WIRE)
    r = outer / 2.0 - 0.5
    step = 8 // n_bulbs if n_bulbs <= 8 else 1
    for i in range(n_bulbs):
        k = (i * step + phase) % 8
        dx, dz = [(1, 0), (0.7071, 0.7071), (0, 1), (-0.7071, 0.7071), (-1, 0), (-0.7071, -0.7071), (0, -1),
                  (0.7071, -0.7071)][k]
        bx, bz = cx + r * dx, cz + r * dz
        bulb = BULBS[(i + phase) % 4]
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


def gift(frm, to, colour):
    return el(frm, to, "#" + colour, uv={"*": FULL})


def lantern(x, y, z, which):
    return el([x, y, z], [x + 3, y + 4, z + 3], "#lantern", uv={"*": which, "up": [3, 8, 6, 11], "down": [3, 8, 6, 11]})


def contents(kind, fill, floor, top):
    """What lies in a box whose inside runs from `floor` up, filled to `top` (full) or nearly empty (low)."""
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


def box_elements(content, fill):
    """A labelled spruce box, open at the top, 14 x 10 x 14 px; the colour-coded card label sits high on the front
    (north, the model's front), wider than tall."""
    w = "#box"
    sx, sy = LABEL_SLOT[content]
    els = [el([1, 0, 1], [15, 1, 15], w), el([1, 1, 1], [15, 10, 2], w), el([1, 1, 14], [15, 10, 15], w),
           el([1, 1, 2], [2, 10, 14], w), el([14, 1, 2], [15, 10, 14], w),
           el([1, 9.4, 0.9], [15, 10.2, 2.1], "#post", faces=["north", "up"]),
           el([3, 5.5, 0.75], [11, 8.5, 1], "#label", faces=["north"],
              uv={"north": [sx, sy, sx + LABEL_W, sy + LABEL_H]})]
    return els + contents(content, fill, 1, 9.2)


def coil_elements(kind):
    """A coil lying on a barrel top. Garland: three leafy rings; lights: three wire rings with their bulbs."""
    if kind == "garland":
        els = ring(8, 8, 13, 3, 0, 2, GARLAND, G_TOP, G_SIDE) + ring(8, 8, 8.5, 2.5, 2, 3.6, GARLAND, G_TOP, G_SIDE)
        return els + ring(8, 8, 4.4, 1.6, 0, 1.6, GARLAND, G_TOP, G_SIDE)
    return light_coil(8, 8, 13, 0, 8) + light_coil(8.4, 7.6, 8.6, 1.0, 4, 1) + light_coil(7.8, 8.2, 4.6, 2.0, 2, 3)


def used_textures(elements, particle):
    """The texture slots the elements name, plus the particle (what breaking the block sprays)."""
    names = sorted({f["texture"][1:] for e in elements for f in e["faces"].values()})
    out = {n: TEX[n] for n in names}
    out["particle"] = particle
    return out


def model(name, elements, particle):
    elements = round_floats(elements)
    block_model(name, used_textures(elements, particle), elements, render_type=CUTOUT)
    return len(elements)


# ------------------------------------------------------------------ blockstates
def variants(extra, model_for):
    """Every combination of `extra` (a list of property dicts) with the four facings; north is the model as drawn."""
    v = {}
    for combo in extra:
        for f, rot in FACINGS:
            props = dict(combo, facing=f)
            key = ",".join(f"{k}={props[k]}" for k in sorted(props))
            entry = {"model": ref(model_for(combo))}
            if rot:
                entry["y"] = rot
            v[key] = entry
    return v


def generate():
    save_tex(from_grid(LABEL, LABEL_PAL), "storage_label")
    counts = {}
    for c in CONTENTS:
        for fill in FILLS:
            counts[f"storage_box_{c}_{fill}"] = model(f"storage_box_{c}_{fill}", box_elements(c, fill), TEX["box"])
    write_json(STATES, "storage_box", {"variants": variants(
        [{"content": c, "fill": f} for c in CONTENTS for f in FILLS],
        lambda p: f"storage_box_{p['content']}_{p['fill']}")})
    for k in COILS:
        counts[f"storage_coil_{k}"] = model(f"storage_coil_{k}", coil_elements(k), TEX[k])
    write_json(STATES, "storage_coil", {"variants": variants(
        [{"kind": k} for k in COILS], lambda p: f"storage_coil_{p['kind']}")})
    return counts
