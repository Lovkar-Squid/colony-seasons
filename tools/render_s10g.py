"""Renders of chunk S10g1 for the owner's look check, with the design's renderer (design/tools: mcmodel.py +
render_scene.py, Blender headless) reading the mod's own resources/assets and the MineColonies jar (for the racks).

    bash tools/render_s10g.sh [name ...]     (names: blocks, l1, l2, l3, l4, l5, levels; none = all)

What is drawn is read from what ships: the new blocks' blockstates and models (resources/assets), and the five
Workshop blueprints with their storeroom spot tags (resources/blueprints/...). Writes to docs/renders/s10g/:

  <date>-s10g-blocks.png          the contact sheet: every model of storage_box and storage_coil on a barrel, next to
                                  the textures it names, and the card labels drawn large
  <date>-s10g-l1-packed.png       the level 1 corner and the level 5 attic as cutaways (front wall and roof left out, as
  <date>-s10g-l1-festival.png     the S10e and the design round's cutaways), with the Decorator's pieces drawn at their
  <date>-s10g-l5-packed.png       tags: packed (outside the decoration window) and in the festival
  <date>-s10g-l5-festival.png
  <date>-s10g-levels.png          all five levels, packed and festival side by side (levels 2 to 4 were laid out by
                                  this chunk; the design round drew levels 1 and 5)

The facing of each piece is worked out as the game will (decorator_hut.spot_facing). Racks are drawn as single racks
facing south, full while they hold anything; in the festival the racks that held the decorations are drawn empty, as
the design round drew them (an assumption about the stock, not something the mod sets).
"""
import datetime
import os
import shutil
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import decorator_hut as dh  # noqa: E402
import paths  # noqa: E402
import voxel  # noqa: E402

os.environ["CS_ASSETS"] = os.path.join(ROOT, "resources", "assets")
os.environ.setdefault("MC_JAR", paths.lib(os.environ.get("MC_ASSETS_JAR", "client-*-extra.jar")))
os.environ.setdefault("CS_SCRATCH", paths.out("render_s10g"))
os.environ.setdefault("MC_TEX_DIR", paths.out("render_s10g_tex"))
if os.environ.get("MINECOLONIES_JAR"):
    os.environ.setdefault("MC_MOD_JARS", os.environ["MINECOLONIES_JAR"])
sys.path.insert(0, os.path.join(ROOT, "design", "tools"))
sys.path.insert(0, os.path.join(ROOT, "design", "src"))
import driver  # noqa: E402
import huts  # noqa: E402  (design/src: the picked look's ground and outside)

from PIL import Image, ImageDraw, ImageFont  # noqa: E402

NS = "colony_seasons"
PACK = os.path.join(ROOT, "resources", "blueprints", "colony_seasons", "colony_seasons")
ASSETS = os.path.join(ROOT, "resources", "assets", NS)
OUT = os.path.join(ROOT, "docs", "renders", "s10g")
DATE = os.environ.get("RENDER_DATE", datetime.date.today().isoformat())
SKY = [0.80, 0.85, 0.90]
BG = (236, 236, 232)
INK = (34, 34, 34)

CAM_PIECE = dict(yaw=-30, pitch=30, ortho=1.75, target=[0.5, 1.32, 0.5], res=[600, 600])
CAM_CORNER = dict(yaw=-24, pitch=36, ortho=9.6, target=[2.2, 2.0, -1.6], res=[1000, 1000])
CAM_CORNER_L2 = dict(yaw=-14, pitch=36, ortho=10.4, target=[1.4, 2.0, -1.6], res=[1000, 1000])
CAM_ATTIC = dict(yaw=-6, pitch=24, ortho=9.6, target=[0.4, 6.7, -2.0], res=[1000, 1000])
# the racks drawn empty in the festival: the ones that held the decorations (the design round's assumption)
EMPTY_IN_FESTIVAL = {1: {(1, 1, -3)}, 2: {(1, 1, -3)}, 3: {(-2, 6, -3), (-1, 6, -3)},
                     4: {(-2, 6, -3), (-1, 6, -3)}, 5: {(-2, 6, -3), (-1, 6, -3), (-2, 7, -3), (-1, 7, -3)}}


# ------------------------------------------------------------------ what ships
def shipped(level):
    """Workshop level `level` read back from its blueprint, in decorator_hut's frame (hut block at (0, 1, 0)), tags
    included."""
    s, _ = voxel.load_blueprint(os.path.join(PACK, "decorator", f"workshop{level}.blueprint"))
    ax, ay, az = s.anchor
    hx, hy, hz = dh.HUT_SPOT
    t = voxel.Structure(f"workshop{level}")
    for (x, y, z), b in s.blocks.items():
        if not b.startswith("structurize:"):
            t.set(x - ax + hx, y - ay + hy, z - az + hz, b)
    for (x, y, z), names in s.tags.items():
        t.tag(x - ax + hx, y - ay + hy, z - az + hz, *names)
    t.anchor = dh.HUT_SPOT
    return t


def pieces_at_tags(s, festival):
    """{position: state} of the Decorator's pieces at the blueprint's own spot tags, packed or in the festival."""
    out = {}
    for pos, names in s.tags.items():
        for name in names:
            parsed = dh.parse_tag(name)
            if parsed is None:
                continue
            kind, _, piece = parsed
            if kind == "hang" and not festival:
                continue
            state = dh.piece_state(piece, kind, festival, dh.spot_facing(s.get, pos, kind))
            if state is not None:
                out[pos] = state
    return out


def hut_scene(s):
    """The Workshop on the design's ground patch (as tools/render_s5.hut_scene)."""
    ground = voxel.Structure("ground")
    huts.ground(ground)
    blocks = dict(ground.blocks)
    for (x, y, z), b in huts.build_a().blocks.items():
        if not (-6 <= x <= 6 and 0 <= y <= 15 and -5 <= z <= 5):
            blocks[(x, y, z)] = b
    blocks.update(s.blocks)
    for (x, y, z), b in list(blocks.items()):
        if y == 1 and voxel.parse_state(b)[0] == "minecraft:snow":
            under = blocks.get((x, 0, z), "")
            if not under.startswith("minecraft:grass_block") or (-5 <= x <= 5 and -4 <= z <= 4):
                del blocks[(x, y, z)]
    return blocks


def cutaway(level, festival):
    s = shipped(level)
    blocks = hut_scene(s)
    blocks.update(pieces_at_tags(s, festival))
    empty = EMPTY_IN_FESTIVAL[level] if festival else set()
    for q, b in list(blocks.items()):
        if voxel.parse_state(b)[0] == dh.RACK:
            blocks[q] = f"{dh.RACK}[facing=south,variant={'blockrackemptysingle' if q in empty else 'blockrackfullsingle'}]"
    roof_y = 9 if level >= 3 else 5
    for (x, y, z) in list(blocks):
        if -6 <= x <= 6 and -6 <= z <= 6 and y >= 1 and (z >= 4 or y >= roof_y):
            del blocks[(x, y, z)]
    for (x, y, z), b in list(blocks.items()):
        if "hanging=true" in b and (x, y + 1, z) not in blocks:
            del blocks[(x, y, z)]
    return blocks


def camera(level):
    return CAM_ATTIC if level >= 3 else (CAM_CORNER_L2 if level == 2 else CAM_CORNER)


# ------------------------------------------------------------------ the blocks
BOXES = [(c, f) for c in ("wreath", "garland", "lights", "lanterns", "gifts") for f in ("full", "low")]
COILS = ("garland", "lights")


def piece_states():
    """(file key, caption, state, model name) of every model, facing south as a standing piece faces the room."""
    out = []
    for c, f in BOXES:
        out.append((f"box-{c}-{f}", f"storage_box  content={c}  fill={f}",
                    f"{NS}:storage_box[content={c},facing=south,fill={f}]", f"storage_box_{c}_{f}"))
    for k in COILS:
        out.append((f"coil-{k}", f"storage_coil  kind={k}", f"{NS}:storage_coil[facing=south,kind={k}]", f"storage_coil_{k}"))
    return out


def model_textures(model):
    import json
    with open(os.path.join(ASSETS, "models", "block", model + ".json"), encoding="utf-8") as f:
        return {k: v for k, v in json.load(f)["textures"].items() if k != "particle"}


def texture_image(ref):
    """A texture as a 16 x 16 RGBA image: ours from resources/, vanilla's from the client jar."""
    ns, path = ref.split(":", 1)
    if ns == NS:
        return Image.open(os.path.join(ASSETS, "textures", *path.split("/")) + ".png").convert("RGBA")
    with zipfile.ZipFile(os.environ["MC_JAR"]) as jar:
        from io import BytesIO
        im = Image.open(BytesIO(jar.read(f"assets/{ns}/textures/{path}.png"))).convert("RGBA")
    return im.crop((0, 0, 16, 16))


def font(size):
    for f in ("C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def swatch(im, scale):
    """A texture scaled up by whole pixels on a light checkerboard (so see-through pixels show)."""
    w = 16 * scale
    out = Image.new("RGBA", (w, w))
    d = ImageDraw.Draw(out)
    c = max(4, scale * 2)
    for y in range(0, w, c):
        for x in range(0, w, c):
            d.rectangle([x, y, x + c - 1, y + c - 1], fill=(250, 250, 250, 255) if (x // c + y // c) % 2 else (214, 214, 214, 255))
    out.alpha_composite(im.resize((w, w), Image.NEAREST))
    return out.convert("RGB")


def blocks_sheet():
    cells = piece_states()
    cols, cw, gap = 4, 460, 16
    scale, per_row = 3, 4                   # a texture swatch: 16 px at 3x, four to a row, its name under it
    sw, row_h = 16 * scale, 16 * scale + 24
    cell_h = cw + 34 + 2 * row_h
    rows = (len(cells) + cols - 1) // cols
    label_h = 16 * 14 + 70
    W = gap + cols * (cw + gap)
    H = 70 + rows * (cell_h + gap) + label_h
    out = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(out)
    d.text((gap, 16), "Storeroom pieces (S10g1): storage_box (10 models) and storage_coil (2 models), each on a barrel, "
                      "with the textures its model names", fill=INK, font=font(26))
    small, tiny = font(19), font(14)
    for i, (key, caption, _, model) in enumerate(cells):
        x = gap + (i % cols) * (cw + gap)
        y = 70 + (i // cols) * (cell_h + gap)
        im = Image.open(scratch(f"piece-{key}")).convert("RGB").resize((cw, cw), Image.LANCZOS)
        out.paste(im, (x, y))
        d.text((x + 4, y + cw + 6), caption, fill=INK, font=small)
        for j, ref in enumerate(sorted(set(model_textures(model).values()))):
            tx = x + (j % per_row) * (cw // per_row)
            ty = y + cw + 34 + (j // per_row) * row_h
            out.paste(swatch(texture_image(ref), scale), (tx, ty))
            name = ref.split("/")[-1]
            while d.textlength(name, font=tiny) > cw // per_row - 6:
                name = name[:-1]
            d.text((tx, ty + sw + 2), name, fill=(70, 70, 70), font=tiny)
    # the card labels, large
    y0 = 70 + rows * (cell_h + gap)
    lab = texture_image(f"{NS}:block/storage_label")
    out.paste(swatch(lab, 14), (gap, y0))
    names = [("wreath", "red"), ("garland", "green"), ("lights", "yellow"), ("lanterns", "orange"), ("gifts", "blue")]
    tx = gap + 16 * 14 + 24
    d.text((tx, y0 + 4), "storage_label.png (new, 16 x 16, drawn as a pixel grid in tools/textures/storage.py):",
           fill=INK, font=small)
    d.text((tx, y0 + 36), "five card labels of 8 x 3 px, a band in the content's colour over one word of ink:",
           fill=INK, font=small)
    for j, (c, col) in enumerate(names):
        d.text((tx, y0 + 72 + j * 28), f"{c}: {col}", fill=INK, font=small)
    d.text((tx, y0 + 72 + 5 * 28 + 8), "Every other texture is shipped already (ours) or vanilla's, named by reference.",
           fill=(70, 70, 70), font=small)
    path = os.path.join(OUT, f"{DATE}-s10g-blocks.png")
    out.save(path, optimize=True)
    return path


# ------------------------------------------------------------------ output helpers (as tools/render_s5.py)
def scratch(name):
    return os.path.join(os.environ["CS_SCRATCH"], f"{DATE}-s10g-{name}.png")


def caption(path, text):
    im = Image.open(path).convert("RGB")
    bar = 40
    out = Image.new("RGB", (im.width, im.height + bar), BG)
    out.paste(im, (0, 0))
    ImageDraw.Draw(out).text((14, im.height + 8), text, fill=INK, font=font(22))
    out.save(path, optimize=True)


def shrink(path, limit=600 * 1024):
    im = Image.open(path).convert("RGB")
    im.save(path, optimize=True, compress_level=9)
    if os.path.getsize(path) > limit:
        im.quantize(colors=256, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).save(path, optimize=True)


def counts(level):
    s = shipped(level)
    p, f = pieces_at_tags(s, False), pieces_at_tags(s, True)
    hung = sum(1 for b in f.values() if "storage_" not in b)
    return len(p), len(f), hung


def levels_sheet():
    cw, gap, label = 520, 14, 34
    W = gap + 2 * (cw + gap)
    H = 60 + 5 * (cw + label + gap)
    out = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(out)
    d.text((gap, 14), "The storeroom at levels 1 to 5: packed (left) and in the festival (right)", fill=INK, font=font(26))
    f = font(19)
    for lv in range(1, 6):
        packed, festival, hung = counts(lv)
        y = 60 + (lv - 1) * (cw + label + gap)
        for j, state in enumerate(("packed", "festival")):
            x = gap + j * (cw + gap)
            out.paste(Image.open(scratch(f"l{lv}-{state}")).convert("RGB").resize((cw, cw), Image.LANCZOS), (x, y))
            text = (f"Level {lv}, packed: {packed} pieces" if state == "packed"
                    else f"Level {lv}, festival: {festival} blocks, {hung} hung")
            d.text((x + 4, y + cw + 5), text, fill=INK, font=f)
    path = os.path.join(OUT, f"{DATE}-s10g-levels.png")
    out.save(path, optimize=True)
    return path


CAPTIONS = {1: "Level 1 corner", 5: "Level 5 attic"}


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(os.environ["CS_SCRATCH"], exist_ok=True)
    if not shutil.which(driver.BLENDER) and not os.path.exists(driver.BLENDER):
        sys.exit(f"Blender not found at {driver.BLENDER} (set BLENDER)")
    only = set(sys.argv[1:]) or {"blocks", "l1", "l2", "l3", "l4", "l5", "levels"}
    jobs = []
    if "blocks" in only:
        for key, _, state, _ in piece_states():
            blocks = {(0, 0, 0): "minecraft:barrel[facing=up]", (0, 1, 0): state}
            jobs.append(dict(blocks=blocks, out=scratch(f"piece-{key}"), bg=SKY, samples=24, **CAM_PIECE))
    levels = [lv for lv in range(1, 6) if f"l{lv}" in only or "levels" in only]
    for lv in levels:
        for state in ("packed", "festival"):
            jobs.append(dict(blocks=cutaway(lv, state == "festival"), out=scratch(f"l{lv}-{state}"), bg=SKY,
                             samples=24, **camera(lv)))
    if jobs:
        driver.render_jobs(jobs)
    written = []
    if "blocks" in only:
        written.append(blocks_sheet())
    for lv in (1, 5):
        if lv not in levels:
            continue
        packed, festival, hung = counts(lv)
        for state, text in (("packed", f"{CAPTIONS[lv]}, packed: {packed} pieces at their tags, nothing hung"),
                            ("festival", f"{CAPTIONS[lv]}, festival: {festival} blocks at their tags, {hung} of them hung")):
            p = os.path.join(OUT, f"{DATE}-s10g-l{lv}-{state}.png")
            shutil.copyfile(scratch(f"l{lv}-{state}"), p)
            caption(p, text)
            written.append(p)
    if "levels" in only:
        written.append(levels_sheet())
    for p in written:
        shrink(p)
        print(f"{os.path.relpath(p, ROOT)}: {Image.open(p).size}, {os.path.getsize(p) // 1024} KB")


if __name__ == "__main__":
    main()
