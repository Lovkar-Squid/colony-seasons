"""The floor pieces: gift_pile (the design's four wrapped boxes), firework_crate and festival_marker.

gift_pile is the design's model and paper unchanged (design/src/make_textures.py gift_pile()). The design drew no
firework crate and no festival marker; they are drawn here in the same manner and are the owner's to judge:
- firework_crate: a low wooden crate with a painted burst on its sides, nine paper rockets standing in straw;
- festival_marker: a spruce stake with a red and gold pennant that points the way the marker faces.
"""
from common import (CUTOUT, blank, block_model, facing_variants, from_grid, hexc, item_from_block, item_generated,
                    overlay, ref, round_floats, save_tex, write_json, STATES, ITEM_TEX)


# ------------------------------------------------------------------ gift pile (design, unchanged)
def gift(paper, ribbon):
    p0, p1, p2 = paper
    r0, r1, r2 = ribbon
    pal = {"a": p0, "b": p1, "c": p2, "R": r0, "S": r1, "T": r2}
    rows = [
        "bbbbbbbSTbbbbbbb",
        "bcbbbbbSTbbbbcbb",
        "bbbbbbbSTbbbbbbb",
        "bbbbcbbSTbbbbbbb",
        "bbbbbbbSTbbbbbcb",
        "bcbbbbbSTbbbbbbb",
        "bbbbbbbSTbbbcbbb",
        "SSSSSSSTTSSSSSSS",
        "RRRRRRRSRRRRRRRR",
        "bbbbbcbSTbbbbbbb",
        "bbbbbbbSTbcbbbbb",
        "bcbbbbbSTbbbbbbb",
        "bbbbbbbSTbbbbbcb",
        "abbbbbbSTbbbbbbb",
        "aabbbbbSTbbbbbba",
        "aaaaaaaRTaaaaaaa",
    ]
    return from_grid(rows, pal)


# the boxes of the pile, model units (also the block's outline: GiftPileBlock.java lists the same boxes)
GIFT_BOXES = (([1, 0, 1], [9, 6, 9], "#red", True), ([9, 0, 7], [15, 5, 13], "#green", True),
              ([3, 0, 10], [7, 4, 14], "#blue", True), ([10, 0, 2], [14, 3, 6], "#blue", False))


def gift_pile():
    red = gift(("#6a1820", "#b02a2e", "#d8504a"), ("#a8701c", "#e9b83a", "#fff0a0"))
    green = gift(("#1d402c", "#2f6b3e", "#4f8f4a"), ("#7a1622", "#c0322e", "#ec7058"))
    blue = gift(("#2a3560", "#4058a0", "#6a86c8"), ("#9aa0aa", "#e4e8ec", "#ffffff"))
    save_tex(red, "gift_red")
    save_tex(green, "gift_green")
    save_tex(blue, "gift_blue")

    def box(f, t, tex, bow=True):
        w, h, d = t[0] - f[0], t[1] - f[1], t[2] - f[2]

        def side(width):
            u0 = 8 - width // 2
            return [u0, 7 - h if h <= 7 else 0, u0 + width, 7 if h <= 7 else h]
        top = [8 - w // 2, 8 - d // 2, 8 - w // 2 + w, 8 - d // 2 + d]
        faces = {"north": {"uv": side(w), "texture": tex}, "south": {"uv": side(w), "texture": tex},
                 "west": {"uv": side(d), "texture": tex}, "east": {"uv": side(d), "texture": tex},
                 "up": {"uv": top, "texture": tex}, "down": {"uv": top, "texture": tex, "cullface": "down"}}
        els = [{"from": f, "to": t, "faces": faces}]
        if bow:
            cx, cz = (f[0] + t[0]) / 2, (f[2] + t[2]) / 2
            ruv = [7, 0, 9, 2]
            for dx in (-2, 0):
                els.append({"from": [cx + dx, t[1], cz - 0.5], "to": [cx + dx + 2, t[1] + 2, cz + 0.5],
                            "faces": {k: {"uv": ruv, "texture": tex} for k in ("north", "south", "east", "west", "up")}})
        return els

    els = []
    for f, t, tex, bow in GIFT_BOXES:
        els += box(f, t, tex, bow)
    block_model("gift_pile", {"red": ref("gift_red"), "green": ref("gift_green"), "blue": ref("gift_blue"),
                              "particle": ref("gift_red")}, round_floats(els), None, ao=True)
    write_json(STATES, "gift_pile", {"variants": facing_variants(lambda p: "gift_pile")})
    item_from_block("gift_pile", "gift_pile")
    return red


# ------------------------------------------------------------------ firework crate
WOOD = {"F": "#3e2a17", "p": "#8a6238", "P": "#a87c4a", "s": "#6b4a2a",      # frame, plank, plank light, seam
        "Y": "#f2c94a", "y": "#d89a2a", "R": "#c0322e", "r": "#8c2026"}       # painted burst
# the crate side, 14 wide and 9 tall (texture x 1..14, y 7..15): boards with seams, a painted burst in the middle
CRATE_SIDE = ["FFFFFFFFFFFFFF",
              "FPPPPPPPPPPPPF",
              "FppprpRRprpppF",
              "FssssRYYRssssF",
              "FPPPRYYYYRPPPF",
              "FppppRYYRppppF",
              "FsssrsRRsrsssF",
              "FPPPPPPPPPPPPF",
              "FFFFFFFFFFFFFF"]
STRAW = {"F": "#3e2a17", "a": "#c8a050", "b": "#e0c070", "c": "#9a7838"}


def crate_top_rows():
    """14 x 14: a dark rim round loose straw (a fixed pattern of three straw colours)."""
    rows = []
    for y in range(14):
        if y in (0, 13):
            rows.append("F" * 14)
            continue
        rows.append("F" + "".join("abacb"[(x * 7 + y * 3) % 5] for x in range(1, 13)) + "F")
    return rows


# rocket paper: four styles, two pixel columns each; rows 0..1 the top (fuse cap), rows 2..9 the paper
TUBE_STYLES = (("#d8402e", "#a82a28", "#f4f0e8", "#6a1820"),    # red with a white band, dark cap
               ("#4a76c8", "#2f52a0", "#f2c94a", "#1e2c58"),    # blue with a gold band
               ("#4f9a4a", "#2f6b3e", "#f4f0e8", "#1d402c"),    # green with a white band
               ("#f2c94a", "#c98a1f", "#c0322e", "#7a4a12"))    # gold with a red band
TUBE_HEIGHTS = ((5, 6, 4), (6, 4, 5), (4, 5, 6))
TUBE_AT = (3, 7, 11)


def firework_crate():
    side = blank()
    overlay(side, CRATE_SIDE, WOOD, 1, 7)
    save_tex(side, "firework_crate_side")
    top = blank()
    overlay(top, crate_top_rows(), STRAW, 1, 1)
    save_tex(top, "firework_crate_top")
    tube = blank()
    for i, (light, base, band, cap) in enumerate(TUBE_STYLES):
        x = 2 * i
        for y in range(2):
            tube.putpixel((x, y), hexc(cap))
            tube.putpixel((x + 1, y), hexc(cap))
        for y in range(2, 10):
            c = band if y == 7 else None
            tube.putpixel((x, y), hexc(c or light))
            tube.putpixel((x + 1, y), hexc(c or base))
    save_tex(tube, "firework_tube")
    sides = {d: {"uv": [1, 7, 15, 16], "texture": "#side"} for d in ("north", "south", "east", "west")}
    els = [{"from": [1, 0, 1], "to": [15, 9, 15],
            "faces": dict(sides, up={"uv": [1, 1, 15, 15], "texture": "#top"},
                          down={"uv": [1, 1, 15, 15], "texture": "#top", "cullface": "down"})}]
    n = 0
    for iz, z in enumerate(TUBE_AT):
        for ix, x in enumerate(TUBE_AT):
            h = TUBE_HEIGHTS[iz][ix]
            u = 2 * (n % len(TUBE_STYLES))
            n += 1
            paper = [u, 10 - h, u + 2, 10]
            els.append({"from": [x, 9, z], "to": [x + 2, 9 + h, z + 2],
                        "faces": {"north": {"uv": paper, "texture": "#tube"}, "south": {"uv": paper, "texture": "#tube"},
                                  "east": {"uv": paper, "texture": "#tube"}, "west": {"uv": paper, "texture": "#tube"},
                                  "up": {"uv": [u, 0, u + 2, 2], "texture": "#tube"}}})
    block_model("firework_crate", {"side": ref("firework_crate_side"), "top": ref("firework_crate_top"),
                                   "tube": ref("firework_tube"), "particle": ref("firework_crate_side")},
                els, None, ao=True)
    write_json(STATES, "firework_crate", {"variants": {"": {"model": ref("firework_crate")}}})
    item_from_block("firework_crate", "firework_crate")
    return side


# ------------------------------------------------------------------ festival marker
MARK = {"a": "#9a7444", "b": "#6e4f2c", "c": "#4a3420",      # spruce stake: light, base, dark
        "R": "#c0322e", "r": "#8c2026", "H": "#e2604a",      # pennant red
        "G": "#f2c94a", "g": "#b8861e",                      # gold trim and knob
        "s": "#7c7a74", "S": "#5a5852"}                      # stone foot
# texture layout: x 0..1 the stake (16 rows), x 2..3 the knob (rows 0..1), x 4..10 rows 0..6 the pennant
# (hoist on the left), x 2..5 rows 12..15 the foot
PENNANT = ["GGGGGGG",
           "HRRRRRG",
           "HRRRRG.",
           "RRRRG..",
           "rRRG...",
           "rRG....",
           "gG....."]


def festival_marker():
    img = blank()
    for y in range(16):
        img.putpixel((0, y), hexc(MARK["a" if y % 5 != 3 else "b"]))
        img.putpixel((1, y), hexc(MARK["b" if y % 4 != 1 else "c"]))
    overlay(img, ["GG", "gg"], MARK, 2, 0)
    overlay(img, PENNANT, MARK, 4, 0)
    overlay(img, ["ssss", "sSss", "ssSs", "SSSS"], MARK, 2, 12)
    save_tex(img, "festival_marker")
    t = "#tex"
    post = [0, 1, 2, 15]
    els = [
        # stone foot
        {"from": [6, 0, 6], "to": [10, 1, 10],
         "faces": {d: {"uv": [2, 12, 6, 13], "texture": t} for d in ("north", "south", "east", "west")}
         | {"up": {"uv": [2, 12, 6, 16], "texture": t}, "down": {"uv": [2, 12, 6, 16], "texture": t, "cullface": "down"}}},
        # the stake
        {"from": [7, 1, 7], "to": [9, 15, 9],
         "faces": {d: {"uv": post, "texture": t} for d in ("north", "south", "east", "west")}},
        # the gold knob
        {"from": [7, 15, 7], "to": [9, 16, 9],
         "faces": {d: {"uv": [2, 0, 4, 1], "texture": t} for d in ("north", "south", "east", "west")}
         | {"up": {"uv": [2, 0, 4, 2], "texture": t}}},
        # the pennant: 1 px thick, flying north from the stake (the way the marker faces), rows 0..6 = y 14..8
        {"from": [7.5, 8, 0], "to": [8.5, 15, 7],
         "faces": {"east": {"uv": [4, 0, 11, 7], "texture": t},
                   "west": {"uv": [11, 0, 4, 7], "texture": t},
                   "up": {"uv": [4, 0, 11, 1], "texture": t, "rotation": 90},
                   "north": {"uv": [10, 0, 11, 7], "texture": t}}},
    ]
    block_model("festival_marker", {"tex": ref("festival_marker"), "particle": ref("festival_marker")},
                round_floats(els), CUTOUT, ao=True)
    write_json(STATES, "festival_marker", {"variants": facing_variants(lambda p: "festival_marker")})
    icon = blank()
    for y in range(2, 16):
        icon.putpixel((4, y), hexc(MARK["a"]))
        icon.putpixel((5, y), hexc(MARK["b"]))
    overlay(icon, ["GG", "gg"], MARK, 4, 0)
    overlay(icon, PENNANT, MARK, 6, 2)
    overlay(icon, ["ssssss", "SSSSSS"], MARK, 2, 14)
    save_tex(icon, "festival_marker", ITEM_TEX)
    item_generated("festival_marker", ref("festival_marker", "item"))
    return img


def generate():
    return {"gift_pile": gift_pile(), "firework_crate": firework_crate(), "festival_marker": festival_marker()}
