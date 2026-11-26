"""The Town Square's hut block: the notice board the owner picked in the pictures round (0.2.0, chunk Q1; design/src
square_standins.py hut_notice, design/square/hutblock-notice.png).

A stone-brick foot, two stripped spruce posts, a spruce board with paper notes pinned to it, under a small dark oak
roof: one block wide and one and a half high. Vanilla textures for the wood and stone; the notes are our own pixels on a
transparent sheet (textures/block/townsquare_notes.png), drawn on an element just in front of the board on both faces,
so nothing of the game's textures is copied into ours. The board shows on both sides, so every facing looks right.

Writes resources/assets/colony_seasons/blockstates/blockhuttownsquare.json, models/block/blockhuttownsquare.json,
models/item/blockhuttownsquare.json and textures/block/townsquare_notes.png (a small PNG writer of its own, so it
needs no packages); the same bytes on every run.
    python -I tools/gen_square_hut.py
    python -I tools/gen_square_hut.py --check    (says whether the files on disk are what it writes; a step of checks.sh)
"""
import json
import os
import struct
import sys
import zlib

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(TOOLS)
ASSETS = os.path.join(ROOT, "resources", "assets", "colony_seasons")
NAME = "blockhuttownsquare"
NOTES = "townsquare_notes"

TEXTURES = {
    "post": "minecraft:block/stripped_spruce_log",
    "board": "minecraft:block/spruce_planks",
    "notes": f"colony_seasons:block/{NOTES}",
    "roof": "minecraft:block/dark_oak_planks",
    "base": "minecraft:block/stone_bricks",
    "particle": "minecraft:block/stripped_spruce_log",
}
ALL = ("north", "south", "east", "west", "up", "down")
BOARD_UV = [2, 2, 14, 14]

# (x0, y0, x1, y1) of each note on the 16 x 16 sheet and its paper colour (the draft's); writing every other row
PAPER = [((3, 3, 7, 8), (236, 230, 208)), ((8, 2, 13, 6), (226, 218, 190)), ((9, 8, 13, 13), (240, 236, 222)),
         ((3, 10, 7, 13), (214, 196, 160))]
INK = (110, 104, 96)
PIN = (190, 40, 40)


def notes_pixels():
    """The sheet as {(x, y): (r, g, b, a)}; every other pixel is transparent."""
    px = {}
    for (x0, y0, x1, y1), c in PAPER:
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                px[x, y] = c + (255,)
        for y in range(y0 + 2, y1, 2):
            for x in range(x0 + 1, x1):
                if (x + y) % 3:
                    px[x, y] = INK + (255,)
        px[(x0 + x1) // 2, y0] = PIN + (255,)
    return px


def png(width, height, px):
    """An 8-bit RGBA PNG of the pixels (missing ones transparent)."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)                                   # filter: none
        for x in range(width):
            raw.extend(px.get((x, y), (0, 0, 0, 0)))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    head = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    signature = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
    return signature + chunk(b"IHDR", head) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


def notes_png():
    return png(16, 16, notes_pixels())


def element(name, a, b, faces, uv=None):
    if isinstance(faces, str):
        faces = {f: faces for f in ALL}
    out = {}
    for f, key in faces.items():
        face = {"texture": "#" + key}
        if uv and f in uv:
            face["uv"] = uv[f]
        out[f] = face
    return {"name": name, "from": list(a), "to": list(b), "faces": out}


def block_model():
    els = [
        element("foot", (0, 0, 3), (16, 2, 13), "base"),
        element("post left", (1, 2, 7), (3, 22, 9), "post"),
        element("post right", (13, 2, 7), (15, 22, 9), "post"),
        element("board", (2, 8, 7.25), (14, 20, 8.75), "board", uv={"north": BOARD_UV, "south": BOARD_UV}),
        element("notes", (2, 8, 7.2), (14, 20, 8.8), {"north": "notes", "south": "notes"},
                uv={"north": BOARD_UV, "south": BOARD_UV}),
        element("roof", (-1, 21, 4), (17, 23, 12), "roof"),
        element("ridge", (0, 23, 5.5), (16, 24.5, 10.5), "roof"),
    ]
    return {
        "credit": "Colony Seasons - Lovkar & Claude",
        "parent": "block/block",
        "render_type": "minecraft:cutout",
        "ambientocclusion": False,
        "textures": TEXTURES,
        "elements": els,
    }


def item_model():
    return {
        "parent": f"colony_seasons:block/{NAME}",
        "display": {
            "thirdperson_righthand": {"rotation": [45, -45, 0], "translation": [0, 2.5, 0], "scale": [0.3, 0.3, 0.3]},
            "thirdperson_lefthand": {"rotation": [45, -45, 0], "translation": [0, 2.5, 0], "scale": [0.3, 0.3, 0.3]},
            "firstperson_righthand": {"rotation": [0, -70, 0], "scale": [0.36, 0.36, 0.36]},
            "firstperson_lefthand": {"rotation": [0, -70, 0], "scale": [0.36, 0.36, 0.36]},
            "ground": {"translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
            "gui": {"rotation": [30, 45, 0], "translation": [0, -1.5, 0], "scale": [0.5, 0.5, 0.5]},
            "head": {"rotation": [0, 180, 0], "scale": [0.91, 0.91, 0.91]},
            "fixed": {"rotation": [0, 180, 0], "translation": [0, -1, 0], "scale": [0.5, 0.5, 0.5]},
        },
    }


def blockstate():
    variants = {}
    for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        v = {"model": f"colony_seasons:block/{NAME}"}
        if y:
            v["y"] = y
        variants[f"facing={facing}"] = v
    return {"variants": variants}


def text(doc):
    return (json.dumps(doc, indent=2) + "\n").encode("utf-8")


def targets():
    return {
        os.path.join(ASSETS, "blockstates", NAME + ".json"): text(blockstate()),
        os.path.join(ASSETS, "models", "block", NAME + ".json"): text(block_model()),
        os.path.join(ASSETS, "models", "item", NAME + ".json"): text(item_model()),
        os.path.join(ASSETS, "textures", "block", NOTES + ".png"): notes_png(),
    }


def main(argv):
    files = targets()
    if "--check" in argv:
        stale = []
        for path, data in sorted(files.items()):
            try:
                with open(path, "rb") as f:
                    if f.read() != data:
                        stale.append(path)
            except FileNotFoundError:
                stale.append(path)
        for path in stale:
            print("!! not what tools/gen_square_hut.py writes:", os.path.relpath(path, ROOT))
        print(f"{len(files) - len(stale)} of {len(files)} hut block files in step")
        return 1 if stale else 0
    for path, data in sorted(files.items()):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
        print("wrote", os.path.relpath(path, ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
