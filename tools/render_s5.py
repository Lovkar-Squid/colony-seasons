"""Renders of the S5 blueprints for the owner and the pack icon, with the design's renderer (design/tools: mcmodel.py +
render_scene.py, Blender headless) reading the mod's own resources/assets and the MineColonies jar (for the racks).

    bash tools/render_s5.sh [name ...]      (names: hut-l1 ... hut-l5, tree-small, tree-full, tree-rich, tree-grand,
                                             levels, compare, icon)

What is drawn is read back from the shipped blueprints (resources/blueprints/...), not from the scripts that made them.
Every scene stands on the design's own ground patch and is seen through the design's own camera (design/src/render_set
CAMERAS), so it compares with design/sheet-hut.png and design/sheet-tree.png directly: the hut faces south as in the
design; a tree piece is drawn facing north, so it is shown as the Decorator places it at a festival marker that faces
south (turned half round), with the marker in its cell and the design's door-height lantern post for scale (the post
and the ground are not part of any piece). Writes docs/renders/s5/<date>-s5-*.png (1000 px wide, at most 600 KB) and
resources/blueprints/colony_seasons/colony_seasons/colony_seasons.png (the pack icon, 256 x 256, from hut level 5).
"""
import datetime
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import paths  # noqa: E402
import voxel  # noqa: E402

os.environ["CS_ASSETS"] = os.path.join(ROOT, "resources", "assets")
os.environ.setdefault("MC_JAR", paths.lib(os.environ.get("MC_ASSETS_JAR", "client-*-extra.jar")))
os.environ.setdefault("CS_SCRATCH", paths.out("render_s5"))
os.environ.setdefault("MC_TEX_DIR", paths.out("render_s5_tex"))
if os.environ.get("MINECOLONIES_JAR"):
    os.environ.setdefault("MC_MOD_JARS", os.environ["MINECOLONIES_JAR"])
sys.path.insert(0, os.path.join(ROOT, "design", "tools"))
sys.path.insert(0, os.path.join(ROOT, "design", "src"))
import driver  # noqa: E402
import huts  # noqa: E402  (design/src: the owner's look options; also imports design/tools/voxel under our name)
import render_set  # noqa: E402
import trees  # noqa: E402
from common import ground_patch, lantern_post  # noqa: E402

from PIL import Image, ImageDraw, ImageFont  # noqa: E402

PACK = os.path.join(ROOT, "resources", "blueprints", "colony_seasons", "colony_seasons")
OUT = os.path.join(ROOT, "docs", "renders", "s5")
DATE = os.environ.get("RENDER_DATE", datetime.date.today().isoformat())
SKY = [0.80, 0.85, 0.90]
GROUND_IDS = {"minecraft:grass_block", "minecraft:dirt", "minecraft:snow", "minecraft:snow_block", "minecraft:dirt_path"}


def cam(which):
    c = dict(render_set.CAMERAS[which])
    c["res"] = [1000, round(1000 * c["res"][1] / c["res"][0])]
    return c


def shipped(path):
    """A shipped blueprint, in the frame it was built in (anchor at its design position)."""
    s, _ = voxel.load_blueprint(os.path.join(PACK, *path.split("/")))
    return s


def hut_scene(level):
    """Hut level `level` on the design's ground patch, its foundation where the design's is."""
    s = shipped(f"decorator/workshop{level}.blueprint")
    ax, ay, az = s.anchor
    hx, hy, hz = (0, 1, 0)           # decorator_hut.HUT_SPOT: the design frame
    ground = voxel.Structure("ground")
    huts.ground(ground)                 # the design's snowy grass patch
    blocks = dict(ground.blocks)
    inside_box = lambda x, y, z: -6 <= x <= 6 and 0 <= y <= 15 and -5 <= z <= 5   # noqa: E731
    for (x, y, z), b in huts.build_a().blocks.items():
        if not inside_box(x, y, z):
            blocks[(x, y, z)] = b       # outside the hut's box the design is ground and the path to the door
    for (x, y, z), b in s.blocks.items():
        p = (x - ax + hx, y - ay + hy, z - az + hz)
        if b.startswith("structurize:"):
            continue                    # substitution cells are the ground that is there
        blocks[p] = b
    for (x, y, z), b in list(blocks.items()):
        if y == 1 and voxel.parse_state(b)[0] == "minecraft:snow":
            under = blocks.get((x, 0, z), "")
            if not under.startswith("minecraft:grass_block") or (-5 <= x <= 5 and -4 <= z <= 4):
                del blocks[(x, y, z)]
    return blocks


def tree_scene(name):
    """A tree piece as placed at a marker facing south (the piece turned half round), on the design's square."""
    s = shipped(f"pieces/{name}.blueprint").rotated_180()
    ax, ay, az = s.anchor
    ground = voxel.Structure("ground")
    ground_patch(ground, plaza=6.6 if name == "grand_tree" else 5.5)
    lantern_post(ground, *trees.SCALE_POST)
    blocks = dict(ground.blocks)
    for (x, y, z), b in s.blocks.items():
        blocks[(x - ax, y - ay + 1, z - az)] = b
    blocks[(0, 1, 0)] = "colony_seasons:festival_marker[facing=south]"
    return blocks


def job(name, blocks, which):
    return dict(blocks=blocks, out=os.path.join(OUT, f"{DATE}-s5-{name}.png"), bg=SKY, samples=24, **cam(which))


JOBS = {f"hut-l{lv}": (lambda lv=lv: hut_scene(lv), "hut") for lv in range(1, 6)}
JOBS.update({"tree-small": (lambda: tree_scene("village_spruce_small"), "tree"),
             "tree-full": (lambda: tree_scene("village_spruce_full"), "tree"),
             "tree-rich": (lambda: tree_scene("village_spruce_rich"), "tree"),
             "tree-grand": (lambda: tree_scene("grand_tree"), "tree")})
# what goes to docs/renders/s5 as its own file; levels 2 and 4 are rendered for the level sheet only
KEEP_FILES = {"hut-l1", "hut-l3", "hut-l5", "tree-small", "tree-full", "tree-rich", "tree-grand"}


def font(size):
    for f in ("C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def design_cell(sheet, index, src_h, count=3):
    """One variant's picture out of a design contact sheet (make_sheet.py: 16 px gaps, cells side by side)."""
    im = Image.open(os.path.join(ROOT, "design", sheet)).convert("RGB")
    gap = 16
    cell_w = (2000 - gap * (count + 1)) // count
    cell_h = round(src_h * cell_w / 1300)
    x = gap + index * (cell_w + gap)
    return im.crop((x, gap, x + cell_w, gap + cell_h))


def scratch(name):
    return os.path.join(os.environ["CS_SCRATCH"], f"{DATE}-s5-{name}.png")


def rendered(name):
    p = os.path.join(OUT, f"{DATE}-s5-{name}.png")
    return p if os.path.exists(p) else scratch(name)


def levels_sheet():
    """All five hut levels side by side, the picked picture last."""
    cw, gap, label = 316, 12, 30
    cells = [(f"Level {lv}", Image.open(rendered(f"hut-l{lv}")).convert("RGB")) for lv in range(1, 6)]
    cells.append(("Picked: hut A (design, level 5)", design_cell("sheet-hut.png", 0, 1300)))
    cols = 3
    W = gap + cols * (cw + gap)
    H = gap + 2 * (cw + label + gap)
    out = Image.new("RGB", (W, H), (236, 236, 232))
    d = ImageDraw.Draw(out)
    f = font(20)
    for i, (title, im) in enumerate(cells):
        x = gap + (i % cols) * (cw + gap)
        y = gap + (i // cols) * (cw + label + gap)
        out.paste(im.resize((cw, cw), Image.LANCZOS), (x, y))
        d.text((x, y + cw + 4), title, fill=(34, 34, 34), font=f)
    path = os.path.join(OUT, f"{DATE}-s5-hut-levels.png")
    out.save(path, optimize=True)
    return path


def compare_sheet():
    """The owner's picked pictures beside the shipped blueprints rendered the same way."""
    rows = [("Hut A (picked)", design_cell("sheet-hut.png", 0, 1300), 1300, "Hut level 5 (shipped)", "hut-l5"),
            ("Tree A Village spruce (picked)", design_cell("sheet-tree.png", 0, 1400), 1400,
             "village_spruce_full, hut level 3 (shipped)", "tree-full"),
            ("Tree B Grand tiered tree (picked)", design_cell("sheet-tree.png", 1, 1400), 1400,
             "grand_tree, hut level 5 (shipped)", "tree-grand")]
    cw, gap, label = 440, 10, 32
    W = gap * 3 + cw * 2
    H = gap
    for _, _, h, _, _ in rows:
        H += round(cw * h / 1300) + label + gap
    out = Image.new("RGB", (W, H), (236, 236, 232))
    d = ImageDraw.Draw(out)
    f = font(18)
    y = gap
    for title, picked, h, title2, ours in rows:
        ch = round(cw * h / 1300)
        out.paste(picked.resize((cw, ch), Image.LANCZOS), (gap, y))
        out.paste(Image.open(rendered(ours)).convert("RGB").resize((cw, ch), Image.LANCZOS), (gap * 2 + cw, y))
        d.text((gap, y + ch + 4), title, fill=(34, 34, 34), font=f)
        d.text((gap * 2 + cw, y + ch + 4), title2, fill=(34, 34, 34), font=f)
        y += ch + label + gap
    path = os.path.join(OUT, f"{DATE}-s5-compare.png")
    out.save(path, optimize=True)
    return path


def icon():
    """The pack icon: hut level 5, cropped square round the building."""
    im = Image.open(rendered("hut-l5")).convert("RGB")
    w, h = im.size
    side = int(w * 0.80)
    left = (w - side) // 2
    top = max(0, int(h * 0.47) - side // 2)
    im = im.crop((left, top, left + side, top + side)).resize((256, 256), Image.LANCZOS)
    path = os.path.join(PACK, "colony_seasons.png")
    im.quantize(colors=256, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).save(path, optimize=True)
    return path


def shrink(path, limit=600 * 1024):
    im = Image.open(path).convert("RGB")
    im.save(path, optimize=True, compress_level=9)
    if os.path.getsize(path) > limit:
        im.quantize(colors=256, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).save(path, optimize=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    if not shutil.which(driver.BLENDER) and not os.path.exists(driver.BLENDER):
        sys.exit(f"Blender not found at {driver.BLENDER} (set BLENDER)")
    only = sys.argv[1:]
    names = [n for n in JOBS if not only or n in only]
    jobs = []
    for n in names:
        fn, which = JOBS[n]
        j = job(n, fn(), which)
        if n not in KEEP_FILES:
            j["out"] = scratch(n)
        jobs.append(j)
    if jobs:
        driver.render_jobs(jobs)
    for j in jobs:
        if os.path.exists(j["out"]):
            shrink(j["out"])
    if not only or "levels" in only:
        shrink(levels_sheet())
    if not only or "compare" in only:
        shrink(compare_sheet())
    if not only or "icon" in only:
        print("icon", icon())
    for name in sorted(os.listdir(OUT)):
        p = os.path.join(OUT, name)
        print(f"{name}: {Image.open(p).size}, {os.path.getsize(p) // 1024} KB")


if __name__ == "__main__":
    main()
