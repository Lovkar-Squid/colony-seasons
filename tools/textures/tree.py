"""The tree pieces: ornament_leaves (spruce leaves with baubles and lights), tinsel_leaves (the gold garland swag of
the grand tree), tree_star and the two-block large_star.

Started from design/src/make_textures.py (ornament_leaves(), tree_star()), the record of trees A and B the owner
picked on 19 Nov 2026. Changed here, as he asked: the baubles are larger (6 and 5 px round instead of 4 and 3) so
they read from about 20 blocks. The leaf base is the vanilla spruce-leaf texture with the spruce foliage colour
(#619961) baked in, so the models have no tintindex. The stars keep the drawings of the design; their models are
rebuilt from a few merged boxes (the design had one box per pixel row: 34 and 96).

`lit=true` adds a second cube (ornaments) or turns the whole star into elements that carry full block light.
"""
from common import (CUTOUT, CUTOUT_MIPPED, blank, block_model, cube, from_grid, glowing, greedy_rects,
                    item_from_block, item_generated, merge_onto, overlay, ref, round_floats, save_tex,
                    vanilla_texture, write_json, STATES, ITEM_TEX)


def spruce_base():
    """Vanilla spruce_leaves.png with the spruce foliage colour (#619961) baked in, as the game shows it."""
    im = vanilla_texture("spruce_leaves")
    px = im.load()
    for y in range(16):
        for x in range(16):
            r, g, b, a = px[x, y]
            px[x, y] = (r * 0x61 // 255, g * 0x99 // 255, b * 0x61 // 255, a)
    return im


ORN = {"R": "#7a1622", "r": "#b3262c", "h": "#e2553f", "w": "#ffd9b0",   # red bauble: shadow, base, light, glint
       "G": "#7a4a12", "g": "#c98a1f", "y": "#f2c94a", "W": "#fff4c2",   # gold bauble
       "k": "#3b3a36",                                                   # cap
       "L": "#fff1a8", "l": "#f0a43a"}                                   # warm light: core, amber
# 6 px round bauble under a 2 px cap, lit from the top left (the design's large one was 4 px)
RED6 = ["..kk..",
        ".hhrr.",
        "hwhrrr",
        "hhrrrR",
        "rrrrRR",
        "rrrRRR",
        ".RRRR."]
GOLD6 = ["..kk..",
         ".yygg.",
         "yWyggg",
         "yygggG",
         "ggggGG",
         "gggGGG",
         ".GGGG."]
# 5 px round bauble (the design's small one was 3 px)
RED5 = ["..k..",
        ".hrr.",
        "hwrrr",
        "hrrrR",
        "rrrRR",
        ".RRR."]
GOLD5 = ["..k..",
         ".ygg.",
         "yWggg",
         "ygggG",
         "gggGG",
         ".GGG."]
LIGHT = ["LL",
         "ll"]
SHINES = "RrhwGgyWLl"          # everything but the cap


def draw_ornaments(img, layout):
    """Draws the ornaments of a layout onto img; returns the glow texture: the same pixels without the caps."""
    glow = blank()
    for rows, x, y in layout:
        layer = blank()
        overlay(layer, rows, ORN, x, y)
        merge_onto(img, layer)
        for yy, row in enumerate(rows):
            for xx, ch in enumerate(row):
                if ch in SHINES:
                    glow.putpixel((x + xx, y + yy), layer.getpixel((x + xx, y + yy)))
    return glow


ORNAMENT_LAYOUTS = [
    # 0: big red, gold, three lights
    [(RED6, 1, 1), (GOLD5, 9, 8), (LIGHT, 11, 2), (LIGHT, 3, 11), (LIGHT, 14, 14)],
    # 1: big gold, red, three lights
    [(GOLD6, 8, 1), (RED5, 2, 8), (LIGHT, 2, 2), (LIGHT, 13, 11), (LIGHT, 8, 13)],
    # 2: lights and one red (the design's 'lights only + one small red')
    [(RED5, 6, 5), (LIGHT, 1, 1), (LIGHT, 12, 2), (LIGHT, 13, 11), (LIGHT, 2, 11), (LIGHT, 9, 13)],
]


def leaf_models(name, tex, glow_tex):
    textures = {"all": ref(tex), "particle": ref(tex)}
    block_model(name, textures, [cube("#all")], CUTOUT_MIPPED, ao=True)
    textures_lit = dict(textures, glow=ref(glow_tex))
    block_model(name + "_lit", textures_lit, [cube("#all"), glowing(cube("#glow"))], CUTOUT_MIPPED, ao=True)


def ornament_leaves():
    out = []
    for i, layout in enumerate(ORNAMENT_LAYOUTS):
        img = spruce_base()
        glow = draw_ornaments(img, layout)
        save_tex(img, f"ornament_leaves_{i}")
        save_tex(glow, f"ornament_leaves_{i}_glow")
        leaf_models(f"ornament_leaves_{i}", f"ornament_leaves_{i}", f"ornament_leaves_{i}_glow")
        out.append(img)
    # vanilla-style random texture per position (a weighted list, like stone's rotations); the pick depends on the
    # position only, so a block keeps its look when it is lit
    write_json(STATES, "ornament_leaves", {"variants": {
        "lit=false": [{"model": ref(f"ornament_leaves_{i}")} for i in range(3)],
        "lit=true": [{"model": ref(f"ornament_leaves_{i}_lit")} for i in range(3)]}})
    item_from_block("ornament_leaves", "ornament_leaves_0")
    return out


def tinsel_leaves():
    img = spruce_base()
    tin = {"a": "#fff0a0", "b": "#e9b83a", "c": "#a8701c"}
    swag = ["bb............bb",
            "abb..........bba",
            "cabb........bbac",
            ".cabbb....bbbac.",
            "..ccabbbbbbacc..",
            "....ccaaaacc....",
            "......cccc......"]
    overlay(img, swag, tin, 0, 5)
    # a red bauble and two lights over the swag, as in the design (the bauble now 5 px instead of 3)
    glow = draw_ornaments(img, [(RED5, 6, 0), (LIGHT, 2, 1), (LIGHT, 12, 1)])
    save_tex(img, "tinsel_leaves")
    save_tex(glow, "tinsel_leaves_glow")
    leaf_models("tinsel_leaves", "tinsel_leaves", "tinsel_leaves_glow")
    write_json(STATES, "tinsel_leaves", {"variants": {
        "lit=false": {"model": ref("tinsel_leaves")}, "lit=true": {"model": ref("tinsel_leaves_lit")}}})
    item_from_block("tinsel_leaves", "tinsel_leaves")
    return img


# ------------------------------------------------------------------ stars
STAR_PAL = {"o": "#6e4310", "d": "#b27418", "m": "#e3a52a", "l": "#f7d055", "h": "#fff6c4"}
STAR = [
    "................",
    ".......oo.......",
    "......ohlo......",
    "......ohlo......",
    ".....ohllmo.....",
    "ooooohllllmooooo",
    ".ohhhllllllmmdo.",
    "..ollllllmmmdo..",
    "...ommmmmmmdo...",
    "....ommmmmddo...",
    "....ommmdddddo..",
    "...ommmdoodddo..",
    "...ommdo..oddo..",
    "..ommdo....odo..",
    "..oddo......oo..",
    "................",
]
# 24 px star on a 32 px canvas (unchanged drawing; the design split it into four 16 px files)
STAR_L = [
    "................................",
    "................................",
    "................................",
    "...............oo...............",
    "..............ohlo..............",
    "..............ohlo..............",
    ".............ohhlmo.............",
    ".............ohllmo.............",
    "............ohhllmmo............",
    "............ohlllmmo............",
    "....ooooooooohllllmmooooooooo...",
    "....ohhhhhhhhllllllmmmmmmmmdo...",
    ".....ohhhllllllllllllmmmmmdo....",
    "......ollllllllllllllmmmmdo.....",
    ".......ommmllllllllmmmmmdo......",
    "........ommmmmmmmmmmmmmdo.......",
    ".........ommmmmmmmmmmmddo.......",
    "..........ommmmmmmmmmddo........",
    "..........ommmmmmmmdddddo.......",
    ".........ommmmmmmdddddddo.......",
    ".........ommmmmmdoodddddo.......",
    "........ommmmmdoo..oodddo.......",
    "........ommmmdo......odddo......",
    ".......ommmmdo........oddo......",
    ".......ommdoo..........oddo.....",
    "......oddoo.............ooo.....",
    "......ooo.......................",
    "................................",
    "................................",
    "................................",
    "................................",
    "................................",
]


def fix_width(rows, w):
    return [r.replace(" ", "")[:w].ljust(w, ".") for r in rows]


def star_elements(img, tex, canvas, x_off, y_top, rows_from=0, rows_to=None, max_cores=6):
    """Two crossed stars, 2 px thick (z 7..9 and x 7..9), from few elements: per plane one slab whose front and back
    show the whole drawing (the cutout gives the outline) and a few core boxes inside the outline that give the
    star its thickness when it is seen from the side (their walls take the colour of the outline). The design had
    one box per pixel row instead. rows_from/rows_to cut the drawing to the part that falls into one block (the
    large star is split at the block boundary); x_off is the model x (and z) of the drawing's left edge, y_top the
    model y of its top edge."""
    w, h = img.size
    rows_to = h if rows_to is None else rows_to
    s = 16 / canvas
    solid = lambda x, y: 0 <= x < w and 0 <= y < h and img.getpixel((x, y))[3] > 0
    part = blank(w, h)
    core = blank(w, h)
    for y in range(rows_from, rows_to):
        for x in range(w):
            if solid(x, y):
                part.putpixel((x, y), img.getpixel((x, y)))
                if all(solid(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    core.putpixel((x, y), (255, 255, 255, 255))
    bx0, by0, bx1, by1 = part.getbbox()
    ox, oy = next((x, y) for y in range(h) for x in range(w) if img.getpixel((x, y))[3])   # an outline texel
    wall = {"uv": [ox * s, oy * s, (ox + 1) * s, (oy + 1) * s], "texture": tex}
    front = [bx0 * s, by0 * s, bx1 * s, by1 * s]
    back = [bx1 * s, by0 * s, bx0 * s, by1 * s]
    ya, yb = y_top - by1, y_top - by0
    els = [{"from": [bx0 + x_off, ya, 7], "to": [bx1 + x_off, yb, 9],
            "faces": {"north": {"uv": back, "texture": tex}, "south": {"uv": front, "texture": tex}}},
           {"from": [7, ya, bx0 + x_off], "to": [9, yb, bx1 + x_off],
            "faces": {"east": {"uv": back, "texture": tex}, "west": {"uv": front, "texture": tex}}}]
    rects = sorted(greedy_rects(core), key=lambda r: -(r[2] - r[0]) * (r[3] - r[1]))[:max_cores]
    for (x0, y0, x1, y1) in sorted(rects):
        ca, cb = y_top - y1, y_top - y0
        els.append({"from": [x0 + x_off, ca, 7], "to": [x1 + x_off, cb, 9],
                    "faces": {k: dict(wall) for k in ("east", "west", "up", "down")}})
        els.append({"from": [7, ca, x0 + x_off], "to": [9, cb, x1 + x_off],
                    "faces": {k: dict(wall) for k in ("north", "south", "up", "down")}})
    return els


def star_model(name, tex_name, els, lit):
    textures = {"star": ref(tex_name), "particle": ref(tex_name)}
    block_model(name, textures, round_floats([glowing(e) for e in els] if lit else els), CUTOUT)


def tree_star():
    img = from_grid(STAR, STAR_PAL)
    save_tex(img, "tree_star")
    # sprite row 14 (its lowest) sits 1 px above the block floor: top edge at model y 16
    els = star_elements(img, "#star", 16, 0, 16)
    star_model("tree_star", "tree_star", els, False)
    star_model("tree_star_lit", "tree_star", els, True)
    write_json(STATES, "tree_star", {"variants": {
        "lit=false": {"model": ref("tree_star")}, "lit=true": {"model": ref("tree_star_lit")}}})
    item_generated("tree_star", ref("tree_star"))
    return img, len(els)


def large_star():
    big = from_grid(fix_width(STAR_L, 32), STAR_PAL, size=32)
    save_tex(big, "large_star")
    # the 32 px canvas is centred on the block (x -8..24) as in the design; its top edge is at model y 27, 1 px
    # higher than the design's, so the lowest points rest on the floor of the block instead of 1 px inside the
    # leaves below. Canvas rows 0..10 fall into the upper block (model y 16..27 -> 0..11 there), 11.. the lower.
    lower = star_elements(big, "#star", 32, -8, 27, rows_from=11)
    upper = star_elements(big, "#star", 32, -8, 11, rows_from=0, rows_to=11)
    for half, els in (("lower", lower), ("upper", upper)):
        star_model(f"large_star_{half}", "large_star", els, False)
        star_model(f"large_star_{half}_lit", "large_star", els, True)
    write_json(STATES, "large_star", {"variants": {
        f"half={h},lit={lit}": {"model": ref(f"large_star_{h}" + ("_lit" if lit == "true" else ""))}
        for h in ("lower", "upper") for lit in ("false", "true")}})
    # inventory icon: the same star drawn at 16 px with two sparkles, so the two stars differ in a slot
    icon = from_grid(STAR, STAR_PAL)
    spark = {"h": "#fff6c4", "l": "#f7d055"}
    overlay(icon, [".h.", "hlh", ".h."], spark, 0, 0)
    overlay(icon, [".h.", "hlh", ".h."], spark, 13, 9)
    save_tex(icon, "large_star", ITEM_TEX)
    item_generated("large_star", ref("large_star", "item"))
    return big, len(lower) + len(upper)


def generate():
    return {"ornament_leaves": ornament_leaves(), "tinsel_leaves": tinsel_leaves(), "tree_star": tree_star(),
            "large_star": large_star()}
