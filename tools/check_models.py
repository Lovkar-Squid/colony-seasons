"""Every texture our block models name has to exist, or the block comes out magenta and black.

Minecraft says nothing when a model points at a texture that is not there - you find out by
looking at the block in game. Two whole families of vanilla textures do not exist even though
the blocks do:

  * walls, fences and most "shaped" blocks have no texture of their own - a wall is drawn with the
    texture of the block it is cut from (deepslate_brick_wall -> deepslate_bricks);
  * every waxed copper block reuses the unwaxed texture (waxed_exposed_copper -> exposed_copper).

Run over resources/assets/**/models/**/*.json, resolving vanilla textures against the client
assets jar (found in libs/ or $SEASONS_LIBS, override the name with $MC_ASSETS_JAR) and ours
against resources/. On a tree without models it checks nothing and passes.

The ornament and tinsel leaves (S10d) are checked once more: each of their eight models draws the
game's own spruce-leaf texture with tintindex 0 on every face (the client colour handler tints it),
our ornaments and lights on top with no tintindex, and no opaque pixel of our leaf textures is the
game's spruce-leaf pixel at the same place, raw or tinted (no copied Mojang pixels ship).
"""
import glob
import json
import os
import re
import struct
import sys
import zipfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # also works under python -I
import paths  # noqa: E402

MC_ASSET_PATTERN = os.environ.get("MC_ASSETS_JAR", "client-*-extra.jar")
RES = paths.res()


def vanilla_textures():
    with zipfile.ZipFile(paths.lib(MC_ASSET_PATTERN)) as jar:
        return {n[len("assets/minecraft/textures/"):-len(".png")]
                for n in jar.namelist()
                if n.startswith("assets/minecraft/textures/") and n.endswith(".png")}


LEAF_MODELS = [f"ornament_leaves_{i}{lit}" for i in range(3) for lit in ("", "_lit")] + ["tinsel_leaves", "tinsel_leaves_lit"]
LEAF_TEXTURES = [f"ornament_leaves_{i}" for i in range(3)] + ["tinsel_leaves"]
SPRUCE = "minecraft:block/spruce_leaves"
EVERGREEN = (0x61, 0x99, 0x61)


def resolve(textures, ref):
    """A face's '#name' followed through the model's own texture slots."""
    seen = 0
    while isinstance(ref, str) and ref.startswith("#") and seen < 8:
        ref = textures.get(ref[1:])
        seen += 1
    return ref


def png_rgba(data):
    """A small PNG reader (the checks run under python -I, with no Pillow): 8-bit grey, RGB, grey+alpha and RGBA, and
    palette images of 1 to 8 bits with tRNS, not interlaced. Returns (width, height, rows of (r, g, b, a))."""
    if data[:8] != bytes([0x89]) + b"PNG" + bytes([13, 10, 26, 10]):
        raise ValueError("not a PNG")
    pos, idat, palette, trns = 8, b"", [], b""
    width = height = depth = ctype = interlace = 0
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
        elif kind == b"PLTE":
            palette = [tuple(body[i:i + 3]) for i in range(0, len(body), 3)]
        elif kind == b"tRNS":
            trns = body
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if interlace:
        raise ValueError("interlaced PNG")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    if ctype != 3 and ctype != 0 and depth != 8:
        raise ValueError(f"bit depth {depth} of colour type {ctype}")
    bits = channels * depth
    stride = (width * bits + 7) // 8
    bpp = max(1, bits // 8)
    raw = zlib.decompress(idat)
    rows, prev, at = [], bytearray(stride), 0
    for _ in range(height):
        kind, line = raw[at], bytearray(raw[at + 1:at + 1 + stride])
        at += 1 + stride
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if kind == 1:
                line[i] = (line[i] + a) & 255
            elif kind == 2:
                line[i] = (line[i] + b) & 255
            elif kind == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif kind == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        out = []
        for x in range(width):
            if depth < 8:
                byte = line[(x * depth) // 8]
                shift = 8 - depth - (x * depth) % 8
                v = (byte >> shift) & ((1 << depth) - 1)
                samples = [v]
            else:
                samples = list(line[x * channels:(x + 1) * channels])
            if ctype == 3:
                r, g, b = palette[samples[0]]
                out.append((r, g, b, trns[samples[0]] if samples[0] < len(trns) else 255))
            elif ctype == 0:
                v = samples[0] * 255 // ((1 << depth) - 1)
                out.append((v, v, v, 255))
            elif ctype == 2:
                out.append((samples[0], samples[1], samples[2], 255))
            elif ctype == 4:
                out.append((samples[0], samples[0], samples[0], samples[1]))
            else:
                out.append(tuple(samples))
        rows.append(out)
    return width, height, rows


def leaf_problems():
    """The tinted leaf models and their overlay textures (see the module comment)."""
    problems = []
    models = os.path.join(RES, "assets", "colony_seasons", "models", "block")
    for name in LEAF_MODELS:
        path = os.path.join(models, name + ".json")
        if not os.path.exists(path):
            problems.append(f"{name}: the model is missing")
            continue
        with open(path, encoding="utf-8") as f:
            model = json.load(f)
        textures = model.get("textures") or {}
        base = 0
        for el in model.get("elements") or []:
            faces = el.get("faces") or {}
            on_spruce = [resolve(textures, fd.get("texture")) == SPRUCE for fd in faces.values()]
            if faces and all(on_spruce):
                if len(faces) == 6 and all(fd.get("tintindex") == 0 for fd in faces.values()):
                    base += 1
                else:
                    problems.append(f"{name}: the spruce-leaf element has a face without tintindex 0 (or not six faces)")
            else:
                if any(on_spruce):
                    problems.append(f"{name}: an element mixes the spruce leaves with our textures")
                if any("tintindex" in fd for fd in faces.values()):
                    problems.append(f"{name}: an overlay or glow face has a tintindex (it would be tinted green)")
        if base != 1:
            problems.append(f"{name}: {base} elements draw the tinted spruce leaves, wanted 1")
        if resolve(textures, "#particle") != SPRUCE:
            problems.append(f"{name}: the particle is not the game's spruce leaves")
    with zipfile.ZipFile(paths.lib(MC_ASSET_PATTERN)) as jar:
        sw, sh, spruce = png_rgba(jar.read("assets/minecraft/textures/block/spruce_leaves.png"))
    tinted = [[(r * EVERGREEN[0] // 255, g * EVERGREEN[1] // 255, b * EVERGREEN[2] // 255, a) for (r, g, b, a) in row]
              for row in spruce]
    for name in LEAF_TEXTURES:
        path = os.path.join(RES, "assets", "colony_seasons", "textures", "block", name + ".png")
        with open(path, "rb") as f:
            w, h, img = png_rgba(f.read())
        same = 0
        for y in range(min(h, sh)):
            for x in range(min(w, sw)):
                p = img[y][x]
                if p[3] and (p[:3] == spruce[y][x][:3] or p[:3] == tinted[y][x][:3]):
                    same += 1
        if same:
            problems.append(f"{name}.png: {same} opaque pixel(s) are the game's spruce-leaf pixels (raw or tinted)")
    return problems


def ours():
    found = set()
    for path in glob.glob(os.path.join(RES, "assets", "*", "textures", "**", "*.png"), recursive=True):
        rel = os.path.relpath(path, os.path.join(RES, "assets"))
        ns, _, sub = rel.partition(os.sep + "textures" + os.sep)
        found.add(f"{ns}:{sub[:-len('.png')]}".replace(os.sep, "/"))
    return found


def main():
    models = sorted(glob.glob(os.path.join(RES, "assets", "**", "models", "**", "*.json"), recursive=True))
    if not models:
        print("0 texture reference(s) checked, 0 missing (no models yet)")
        return 0
    vanilla = vanilla_textures()
    mine = ours()
    bad = 0
    checked = 0
    for path in models:
        with open(path, encoding="utf-8") as f:
            try:
                model = json.load(f)
            except json.JSONDecodeError as e:
                print(f"!! {os.path.relpath(path, RES)}: not valid JSON: {e}")
                bad += 1
                continue
        for key, value in (model.get("textures") or {}).items():
            if not isinstance(value, str) or value.startswith("#"):
                continue          # a reference to another slot, resolved by the parent
            checked += 1
            ns, _, sub = value.partition(":")
            if not _:
                ns, sub = "minecraft", value
            known = vanilla if ns == "minecraft" else mine
            wanted = sub if ns == "minecraft" else f"{ns}:{sub}"
            if wanted not in known:
                print(f"!! {os.path.relpath(path, RES)}: \"{key}\": \"{value}\" does not exist")
                bad += 1
    print(f"{checked} texture reference(s) checked, {bad} missing")
    leaves = leaf_problems()
    for problem in leaves:
        print(f"!! {problem}")
    print(f"{len(LEAF_MODELS)} leaf models and {len(LEAF_TEXTURES)} leaf textures checked, {len(leaves)} problem(s)")
    return 1 if bad or leaves else 0


if __name__ == "__main__":
    sys.exit(main())
