"""The four wall decorations: wreath (A Classic), garland (A Evergreen garland), lantern_string (B Lantern string,
three colour orders as `variant`) and fairy_lights (C Fairy lights, with larger bulbs).

Started from design/src/make_textures.py (wreaths() variant A and strings() A, B, C), which stays as the record of
the look the owner picked on 19 Nov 2026. Changed here, as he asked: the fairy-light bulbs are larger (3 px wide and
5 tall instead of 2 by 3) and stand 1 px proud, so they read from about 20 blocks. Lit lanterns and bulbs glow: in
the `lit=true` models their elements carry the full-brightness element data (see common.GLOW).
"""
import math

from common import (CUTOUT, blank, facing_variants, from_grid, glowing, front_overlay, hexc, item_generated,
                    keep_only, overlay, ref, save_tex, tex_rect_relief, wall_plane, block_model, centred_icon,
                    ITEM_TEX, write_json, STATES)


def wall_model(name, tex_name, extra=(), glow_tex=None, textures_extra=None):
    textures = {"tex": ref(tex_name), "particle": ref(tex_name)}
    if glow_tex:
        textures["glow"] = ref(glow_tex)
    textures.update(textures_extra or {})
    els = [wall_plane()]
    if glow_tex:
        els.append(glowing(front_overlay("#glow")))
    els += list(extra)
    block_model(name, textures, els, CUTOUT)


# ------------------------------------------------------------------ wreath A "Classic"
def ring_clumps(pal_ramp, n=9, r_mid=4.9, r_clump=2.75, light=(-0.62, -0.78), phase=0.35, size=16):
    """Wreath ring made of overlapping round needle clumps, each shaded top-left -> bottom-right with the
    4-step ramp (0 = darkest). Returns {(x, y): ramp index}. Unchanged from the design."""
    cx = cy = (size - 1) / 2
    pix = {}
    for i in range(n):
        a = phase + i * 2 * math.pi / n
        ccx, ccy = cx + r_mid * math.cos(a), cy + r_mid * math.sin(a)
        for y in range(size):
            for x in range(size):
                dx, dy = x - ccx, y - ccy
                dd = math.hypot(dx, dy)
                if dd <= r_clump:
                    v = (dx * light[0] + dy * light[1]) / r_clump
                    if dd > r_clump - 0.85 and v < 0.1:
                        idx = 0
                    elif v > 0.42:
                        idx = 3
                    elif v > -0.05:
                        idx = 2
                    else:
                        idx = 1
                    pix[(x, y)] = idx
    for (x, y) in list(pix):
        if math.hypot(x - cx, y - cy) < 2.6:
            del pix[(x, y)]
    return pix


def clean_orphans(img):
    """Replaces single pixels whose colour no orthogonal neighbour shares. Unchanged from the design."""
    w, h = img.size
    src = img.copy()
    for y in range(h):
        for x in range(w):
            c = src.getpixel((x, y))
            if c[3] == 0:
                continue
            nbs = [src.getpixel((x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                   if 0 <= x + dx < w and 0 <= y + dy < h]
            opaque = [n for n in nbs if n[3]]
            if opaque and c not in opaque:
                best = max(set(opaque), key=opaque.count)
                if opaque.count(best) >= 2:
                    img.putpixel((x, y), best)
    return img


def wreath():
    greens = ["#173326", "#22502f", "#3a7a3a", "#6ea444"]
    img = blank()
    for (x, y), i in ring_clumps(greens).items():
        img.putpixel((x, y), hexc(greens[i]))
    img = clean_orphans(img)
    pal = {"D": "#5a1420", "r": "#9a2226", "R": "#cc3a32", "H": "#f07a5a", "k": "#7a1a20"}
    bow = [".DD..DD.",
           "DHRDDRHD",
           "DRRkkRRD",
           ".DDkkDD.",
           "..DrrD..",
           ".DrDDrD.",
           ".DD..DD."]
    overlay(img, bow, pal, 4, 9)
    berries = {"R": "#c4302c", "H": "#f26a52", "D": "#6a1420"}
    for (x, y) in ((3, 3), (11, 2), (13, 7), (1, 7)):
        overlay(img, ["HR", "RD"], berries, x, y)
    save_tex(img, "wreath")
    # the bow knot stands 1 px proud
    wall_model("wreath", "wreath", [tex_rect_relief(7, 11, 9, 13)])
    write_json(STATES, "wreath", {"variants": facing_variants(lambda p: "wreath")})
    item_generated("wreath", ref("wreath"))
    return img


# ------------------------------------------------------------------ garland A "Evergreen garland"
def garland():
    g = {"a": "#173326", "b": "#22502f", "c": "#3a7a3a", "d": "#6ea444",
         "D": "#5a1420", "r": "#9a2226", "R": "#cc3a32", "H": "#f07a5a", "k": "#3e0c14"}
    # one swag per tile, high under the bow at the tile centre, lowest at the edges where it meets the next
    # tile's swag; content starts at row 2 so the eave above does not hide it (unchanged from the design)
    rows = [
        "................",
        "................",
        "......DD.DD.....",
        ".....DRHDHRD....",
        "....cDRRkRRDc...",
        "..cdcbDrkrDbcdc.",
        "dcdcbbaDrDabbcdc",
        "cdcbbaaDrDaabbcd",
        "bcbba..D.D..abbc",
        "bbaa..........ab",
        "a..............a",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]
    img = from_grid(rows, g)
    save_tex(img, "garland")
    wall_model("garland", "garland", [tex_rect_relief(7, 3, 9, 6)])
    write_json(STATES, "garland", {"variants": facing_variants(lambda p: "garland")})
    save_tex(centred_icon(img), "garland", ITEM_TEX)
    item_generated("garland", ref("garland", "item"))
    return img


# ------------------------------------------------------------------ lantern string B
LANTERN_PALS = {
    "red": {"c": "#3a2418", "L": "#d8402e", "H": "#ff9060", "S": "#962428", "t": "#f2c94a"},
    "yellow": {"c": "#3a2418", "L": "#efbe46", "H": "#fff1a0", "S": "#b07e1e", "t": "#d8402e"},
    "orange": {"c": "#3a2418", "L": "#ea7e2c", "H": "#ffc27a", "S": "#a4501a", "t": "#f2c94a"},
}
LANTERN = ["..k..",
           ".ccc.",
           "LHLLS",
           "SSSSS",
           "LHLLS",
           "LHLLS",
           ".ccc.",
           "..t.."]
CORD = ["kk..............",
        "..kk..........kk",
        "....kkkk..kkkk..",
        "........kk......"]
LANTERN_ORDERS = (("0", ("red", "yellow")), ("1", ("yellow", "orange")), ("2", ("orange", "red")))


def lantern_string():
    cords = {"k": "#3a2a1e"}
    dy = 2  # keep clear of the eave
    first = None
    for name, order in LANTERN_ORDERS:
        img = blank()
        glow = blank()
        overlay(img, CORD, cords, 0, 1 + dy)
        p1 = dict(LANTERN_PALS[order[0]]); p1.update(cords)
        p2 = dict(LANTERN_PALS[order[1]]); p2.update(cords)
        overlay(img, LANTERN, p1, 2, 3 + dy)
        overlay(img, LANTERN, p2, 9, 4 + dy)
        # the paper (L, H, S) shines when lit; the caps, the hook and the tassel do not
        for ox, oy in ((2, 3 + dy), (9, 4 + dy)):
            part = keep_only(img, "LHS", LANTERN, ox, oy)
            for y in range(16):
                for x in range(16):
                    if part.getpixel((x, y))[3]:
                        glow.putpixel((x, y), part.getpixel((x, y)))
        save_tex(img, f"lantern_string_{name}")
        save_tex(glow, f"lantern_string_{name}_glow")
        if first is None:
            first = img
        # lantern bodies stand 1 px proud of the cord plane (paper lanterns are round), as in the design
        reliefs = [tex_rect_relief(3, 5 + dy, 6, 9 + dy), tex_rect_relief(10, 6 + dy, 13, 10 + dy)]
        wall_model(f"lantern_string_{name}", f"lantern_string_{name}", reliefs)
        wall_model(f"lantern_string_{name}_lit", f"lantern_string_{name}", [glowing(r) for r in reliefs],
                   glow_tex=f"lantern_string_{name}_glow")
    write_json(STATES, "lantern_string", {"variants": facing_variants(
        lambda p: f"lantern_string_{p['variant']}" + ("_lit" if p["lit"] == "true" else ""),
        [{"variant": v, "lit": lit} for v in ("0", "1", "2") for lit in ("false", "true")])})
    save_tex(centred_icon(first), "lantern_string", ITEM_TEX)
    item_generated("lantern_string", ref("lantern_string", "item"))
    return first


# ------------------------------------------------------------------ fairy lights C, larger bulbs
FAIRY = {"k": "#26201c", "q": "#55504a",
         "R": "#e8443a", "r": "#ffb0a0", "1": "#a82a28",
         "G": "#3ec060", "g": "#b8ffc8", "2": "#24843e",
         "B": "#3a86f0", "b": "#b8dcff", "3": "#2458a8",
         "Y": "#f6cc3a", "y": "#fff6c0", "4": "#b88a1e"}
# the cord dips at the gaps between the four bulbs (as in the design); each bulb is a grey cap and a 3 px wide,
# 5 px tall glass: light corner top left, shaded corner and tip at the bottom (the design's bulbs were 2 by 3)
FAIRY_ROWS = ["................",
              "................",
              "................",
              "..k...k...k...k.",
              "kqqqkqqqkqqqkqqq",
              ".rRR.gGG.bBB.yYY",
              ".RRR.GGG.BBB.YYY",
              ".RRR.GGG.BBB.YYY",
              ".RR1.GG2.BB3.YY4",
              "..1...2...3...4.",
              "................",
              "................",
              "................",
              "................",
              "................",
              "................"]
BULB_X = (1, 5, 9, 13)
BULB_ROWS = (5, 10)          # glass rows, end-exclusive


def fairy_lights():
    img = from_grid(FAIRY_ROWS, FAIRY)
    save_tex(img, "fairy_lights")
    bulbs = [tex_rect_relief(x, BULB_ROWS[0], x + 3, BULB_ROWS[1]) for x in BULB_X]
    wall_model("fairy_lights", "fairy_lights", bulbs)
    wall_model("fairy_lights_lit", "fairy_lights", [glowing(b) for b in bulbs])
    write_json(STATES, "fairy_lights", {"variants": facing_variants(
        lambda p: "fairy_lights" + ("_lit" if p["lit"] == "true" else ""),
        [{"lit": "false"}, {"lit": "true"}])})
    save_tex(centred_icon(img), "fairy_lights", ITEM_TEX)
    item_generated("fairy_lights", ref("fairy_lights", "item"))
    return img


def generate():
    return {"wreath": wreath(), "garland": garland(), "lantern_string": lantern_string(),
            "fairy_lights": fairy_lights()}
