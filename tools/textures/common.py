"""Shared helpers of the decoration generators (chunk S2): pixel grids, palettes, model elements, file output.

The textures are drawn the way design/src/make_textures.py drew the look options the owner picked: character
grids over small hand-picked palettes, whole pixels only (no gradients, no noise, no blending). Everything is
written under resources/assets/colony_seasons/. Nothing is read from the game's textures: the ornament and tinsel
leaves name the game's own spruce-leaf texture in their models instead (S10d, tree.py).

Glow: a model element that should shine at night carries NeoForge's element data
{"neoforge_data": {"block_light": 15, "sky_light": 15, "ambient_occlusion": false}} and "shade": false. NeoForge
21.1 reads that key in BlockElement's deserializer and bakes the light into the quad (FaceBakery ->
QuadTransformers.applyingLightmap), so the pixels are drawn at full brightness whatever the light around them.
"""
import json
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
sys.path.insert(0, TOOLS)
import paths  # noqa: E402

NS = "colony_seasons"
ASSETS = paths.res("assets", NS)
TEX = os.path.join(ASSETS, "textures", "block")
ITEM_TEX = os.path.join(ASSETS, "textures", "item")
MODELS = os.path.join(ASSETS, "models", "block")
ITEM_MODELS = os.path.join(ASSETS, "models", "item")
STATES = os.path.join(ASSETS, "blockstates")

FACINGS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))
GLOW = {"block_light": 15, "sky_light": 15, "ambient_occlusion": False}
CUTOUT = "minecraft:cutout"
CUTOUT_MIPPED = "minecraft:cutout_mipped"

written = []


def ensure_dirs():
    for d in (TEX, ITEM_TEX, MODELS, ITEM_MODELS, STATES):
        os.makedirs(d, exist_ok=True)


# ------------------------------------------------------------------ pixels
def hexc(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def blank(w=16, h=16):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def from_grid(rows, pal, size=16):
    img = blank(size, size)
    for y, row in enumerate(rows):
        row = row.replace(" ", "")
        assert len(row) == size, (y, row, len(row))
        for x, ch in enumerate(row):
            if ch != ".":
                img.putpixel((x, y), hexc(pal[ch]))
    return img


def overlay(img, rows, pal, ox=0, oy=0, mask=None):
    """Draws a grid onto img; '.' and ' ' are see-through. With mask (an image of the same size), the same
    pixels are also drawn there - that is how a glow texture collects exactly the pixels that shine."""
    for y, row in enumerate(rows):
        row = row.replace(" ", "")
        for x, ch in enumerate(row):
            if ch not in ". ":
                img.putpixel((ox + x, oy + y), hexc(pal[ch]))
                if mask is not None:
                    mask.putpixel((ox + x, oy + y), hexc(pal[ch]))
    return img


def keep_only(img, chars, rows, ox=0, oy=0):
    """A copy of img that keeps only the pixels where `rows` (placed at ox, oy) has one of `chars`."""
    out = blank(*img.size)
    for y, row in enumerate(rows):
        row = row.replace(" ", "")
        for x, ch in enumerate(row):
            if ch in chars:
                out.putpixel((ox + x, oy + y), img.getpixel((ox + x, oy + y)))
    return out


def merge_onto(dst, src):
    """Copies every opaque pixel of src onto dst."""
    for y in range(src.size[1]):
        for x in range(src.size[0]):
            p = src.getpixel((x, y))
            if p[3]:
                dst.putpixel((x, y), p)
    return dst


def centred_icon(img):
    """A 16x16 item icon: the texture's opaque content moved to the middle (wall strings sit near the top of
    their block, which would leave the inventory icon hanging in the upper half)."""
    box = img.getbbox()
    if not box:
        return img.copy()
    part = img.crop(box)
    out = blank(16, 16)
    out.paste(part, ((16 - part.size[0]) // 2, (16 - part.size[1]) // 2))
    return out


def save_tex(img, name, folder=None):
    path = os.path.join(folder or TEX, name + ".png")
    img.save(path)
    written.append(path)
    return img


# ------------------------------------------------------------------ json
def write_json(folder, name, data):
    path = os.path.join(folder, name + ".json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    written.append(path)


def ref(name, kind="block"):
    return f"{NS}:{kind}/{name}"


def block_model(name, textures, elements, render_type=CUTOUT, ao=False, parent="minecraft:block/block"):
    data = {"parent": parent}
    if not ao:
        data["ambientocclusion"] = False
    if render_type:
        data["render_type"] = render_type
    data["textures"] = textures
    data["elements"] = elements
    write_json(MODELS, name, data)


def item_generated(name, texture_ref):
    write_json(ITEM_MODELS, name, {"parent": "minecraft:item/generated", "textures": {"layer0": texture_ref}})


def item_from_block(name, model_name):
    write_json(ITEM_MODELS, name, {"parent": ref(model_name)})


def glowing(element):
    """The element shines: full block and sky light baked into its quads, no face shade, no AO."""
    e = dict(element)
    e["shade"] = False
    e["neoforge_data"] = dict(GLOW)
    return e


# ------------------------------------------------------------------ elements
def wall_plane(tex="#tex", depth=1):
    """A flat cutout plane against the wall (like a ladder): the block faces north, the wall is to the south."""
    return {"from": [0, 0, 16 - depth], "to": [16, 16, 16],
            "faces": {"north": {"uv": [0, 0, 16, 16], "texture": tex},
                      "south": {"uv": [16, 0, 0, 16], "texture": tex, "cullface": "south"}}}


def front_overlay(tex, depth=1):
    """The front face of a wall plane again, with another texture; drawn after the plane at the same depth,
    the way vanilla draws the grass block's side overlay."""
    return {"from": [0, 0, 16 - depth], "to": [16, 16, 16],
            "faces": {"north": {"uv": [0, 0, 16, 16], "texture": tex}}}


def relief(rect_uv, frm, to, tex="#tex"):
    """A small raised box (bow, bulb, lantern) whose faces all sample one texture rectangle."""
    return {"from": frm, "to": to,
            "faces": {k: {"uv": rect_uv, "texture": tex} for k in ("north", "east", "west", "up", "down")}}


def tex_rect_relief(x0, y0, x1, y1, proud=1, depth=1, tex="#tex"):
    """A relief box over texture pixels x0..x1-1, y0..y1-1 of a north-facing wall plane, standing `proud`
    pixels out of it. The north face shows u from the right (x) and v from the top (y): model x = 16 - u."""
    back = 16 - depth
    return relief([x0, y0, x1, y1], [16 - x1, 16 - y1, back - proud], [16 - x0, 16 - y0, back], tex)


def cube(tex, cull=True):
    faces = {}
    for d in ("down", "up", "north", "south", "west", "east"):
        f = {"uv": [0, 0, 16, 16], "texture": tex}
        if cull:
            f["cullface"] = d
        faces[d] = f
    return {"from": [0, 0, 0], "to": [16, 16, 16], "faces": faces}


def facing_variants(model_for, extra_props=None):
    """Blockstate variants for the four horizontal facings (north = model as drawn, others turned about y).
    model_for(props) -> model name; extra_props: list of dicts of further properties to combine."""
    v = {}
    for combo in (extra_props or [{}]):
        for f, rot in FACINGS:
            props = {"facing": f}
            props.update(combo)
            key = ",".join(f"{k}={props[k]}" for k in sorted(props))
            entry = {"model": ref(model_for(props))}
            if rot:
                entry["y"] = rot
            v[key] = entry
    return v


def greedy_rects(img, x_tiles=None):
    """Splits the opaque pixels of a sprite into few rectangles (greedy: widest run first, then as many rows
    down as share it). Returns [(x0, y0, x1, y1)], end-exclusive. x_tiles/y bounds are not split."""
    w, h = img.size
    used = [[False] * w for _ in range(h)]
    solid = [[img.getpixel((x, y))[3] > 0 for x in range(w)] for y in range(h)]
    rects = []
    for y in range(h):
        for x in range(w):
            if not solid[y][x] or used[y][x]:
                continue
            x1 = x
            while x1 < w and solid[y][x1] and not used[y][x1]:
                x1 += 1
            y1 = y + 1
            while y1 < h and all(solid[y1][i] and not used[y1][i] for i in range(x, x1)):
                y1 += 1
            for yy in range(y, y1):
                for xx in range(x, x1):
                    used[yy][xx] = True
            rects.append((x, y, x1, y1))
    return rects


def extrusion(rects, tex, canvas, depth_from, depth_to, plane, x_off, y_top, uv_scale):
    """Item-style extrusion of a sprite: one box per rectangle, front and back show the rectangle, the sides
    show its edge pixels. canvas = sprite size in pixels (one sprite pixel = one model unit); y_top = model y of
    the sprite's top edge; x_off = model x (or z) of its left edge; uv_scale = 16 / canvas.
    plane 'z': the sprite stands in the x/y plane; plane 'x': the same sprite turned 90 degrees."""
    els = []
    s = uv_scale
    for (x0, y0, x1, y1) in rects:
        u0, u1, v0, v1 = x0 * s, x1 * s, y0 * s, y1 * s
        my1 = y_top - y0
        my0 = y_top - y1
        face = [u0, v0, u1, v1]
        left = [u0, v0, u0 + s, v1]
        right = [u1 - s, v0, u1, v1]
        top = [u0, v0, u1, v0 + s]
        bottom = [u0, v1 - s, u1, v1]
        if plane == "z":
            el = {"from": [x0 + x_off, my0, depth_from], "to": [x1 + x_off, my1, depth_to],
                  "faces": {"north": {"uv": [u1, v0, u0, v1], "texture": tex},
                            "south": {"uv": face, "texture": tex},
                            "west": {"uv": left, "texture": tex},
                            "east": {"uv": right, "texture": tex},
                            "up": {"uv": top, "texture": tex},
                            "down": {"uv": bottom, "texture": tex}}}
        else:
            el = {"from": [depth_from, my0, x0 + x_off], "to": [depth_to, my1, x1 + x_off],
                  "faces": {"east": {"uv": [u1, v0, u0, v1], "texture": tex},
                            "west": {"uv": face, "texture": tex},
                            "north": {"uv": left, "texture": tex},
                            "south": {"uv": right, "texture": tex},
                            "up": {"uv": top, "texture": tex},
                            "down": {"uv": bottom, "texture": tex}}}
        els.append(el)
    return els


def round_floats(o):
    if isinstance(o, float):
        r = round(o, 4)
        return int(r) if r == int(r) else r
    if isinstance(o, list):
        return [round_floats(v) for v in o]
    if isinstance(o, dict):
        return {k: round_floats(v) for k, v in o.items()}
    return o
