"""Renders of the decoration blocks for the look check (chunk S2), with the design's renderer (design/tools:
mcmodel.py + render_scene.py, Blender headless) reading the mod's own resources/assets.

Run:  bash tools/render_decor.sh        (Blender 5.1; BLENDER overrides its path)
Writes docs/renders/s2/<date>-s2-*.png, at most 1000 px wide:
  wreath-day, garland-day, lanterns-day, fairylights-day   each wall block on the design's colony wall, with the
                                                           design's own camera (to compare with design/sheet-*.png)
  closeup-day, closeup-night     two walls with every wall block and the tree and floor pieces in front
  square-day, square-night       the village spruce (tree A) and two decorated house fronts, seen as from about
                                 20 blocks away; the night one is lit the way the game lights it at midnight
  sheet                          the owner's picked looks (design sheets) beside the shipped blocks
"""
import datetime
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import paths  # noqa: E402

os.environ["CS_ASSETS"] = os.path.join(ROOT, "resources", "assets")
os.environ.setdefault("MC_JAR", paths.lib(os.environ.get("MC_ASSETS_JAR", "client-*-extra.jar")))
os.environ.setdefault("CS_SCRATCH", paths.out("render_s2"))
os.environ.setdefault("MC_TEX_DIR", paths.out("render_s2_tex"))
sys.path.insert(0, os.path.join(ROOT, "design", "tools"))
sys.path.insert(0, os.path.join(ROOT, "design", "src"))
import driver  # noqa: E402
import mcmodel  # noqa: E402
import trees  # noqa: E402
import walls  # noqa: E402
from common import h01  # noqa: E402

from PIL import Image, ImageDraw, ImageFont  # noqa: E402

OUT = os.path.join(ROOT, "docs", "renders", "s2")
DATE = os.environ.get("RENDER_DATE", datetime.date.today().isoformat())
NS = "colony_seasons"
SKY_DAY = [0.80, 0.85, 0.90]
SKY_NIGHT = [0.035, 0.045, 0.10]

# light levels from the mod's own constants (Lights.java), so a render never disagrees with the game
LIGHTS_JAVA = os.path.join(ROOT, "src", "me", "lovkar", "seasons", "block", "Lights.java")
with open(LIGHTS_JAVA, encoding="utf-8") as _f:
    LIGHT = {k.lower(): int(v) for k, v in re.findall(r"public static final int ([A-Z_]+) = (\d+);", _f.read())}
VANILLA_LIGHT = {"lantern": 15, "soul_lantern": 10, "torch": 14, "wall_torch": 14, "glowstone": 15, "sea_lantern": 15}


def emission(name, props):
    ns, short = name.split(":", 1)
    if ns == NS:
        return LIGHT.get(short, 0) if props.get("lit") == "true" else 0
    if short == "campfire":
        return 15 if props.get("lit", "true") == "true" else 0
    return VANILLA_LIGHT.get(short, 0)


def lit_word(lit):
    return "true" if lit else "false"


def ship(state, lit):
    """A design build's state (namespace colonyseasons, the look options) as the shipped block."""
    name, props = mcmodel.parse_state(state)
    L = lit_word(lit)
    if not name.startswith("colonyseasons:"):
        return state
    short = name.split(":", 1)[1]
    if short == "ornament_leaves":
        return f"{NS}:{'tinsel_leaves' if props.get('style') == 'garland' else 'ornament_leaves'}[lit={L}]"
    if short == "tree_star":
        return f"{NS}:{'large_star' if props.get('size') == 'large' else 'tree_star'}" + (
            f"[half=lower,lit={L}]" if props.get("size") == "large" else f"[lit={L}]")
    if short == "gift_pile":
        return f"{NS}:gift_pile[facing={props.get('facing', 'north')}]"
    return state


def decorated_wall(dx, dz, lights, lit):
    """The design's 5 x 4 colony wall (walls.wall(), facing south) moved by dx, dz, with the wreath above the door,
    the garland in the row under the beam beside it and a string of lights under the eave ("lights: the row above
    the garland", design 3.4). lights: 'lantern_string', 'fairy_lights' or None."""
    s = walls.wall()
    blocks = {}
    for (x, y, z), st in s.blocks.items():
        blocks[(x + dx, y, z + dz)] = st
    L = lit_word(lit)
    blocks[(2 + dx, 2, 1 + dz)] = f"{NS}:wreath[facing=south]"
    for x in (0, 1, 3, 4):
        blocks[(x + dx, 2, 1 + dz)] = f"{NS}:garland[facing=south]"
    for x in range(5):
        if lights == "lantern_string":
            blocks[(x + dx, 3, 1 + dz)] = f"{NS}:lantern_string[facing=south,lit={L},variant={(x + dx) % 3}]"
        elif lights == "fairy_lights":
            blocks[(x + dx, 3, 1 + dz)] = f"{NS}:fairy_lights[facing=south,lit={L}]"
    return blocks


def single_wall(block, lit=False):
    """Set 3 / 4 of the design: one decoration on the design wall, as the owner saw the options."""
    s = walls.wall()
    blocks = dict(s.blocks)
    L = lit_word(lit)
    if block == "wreath":
        blocks[(2, 2, 1)] = f"{NS}:wreath[facing=south]"
    else:
        for x in range(5):
            extra = {"lantern_string": f",lit={L},variant={x % 3}", "fairy_lights": f",lit={L}"}.get(block, "")
            blocks[(x, 3, 1)] = f"{NS}:{block}[facing=south{extra}]"
    return blocks


def pieces(blocks, dz, lit):
    """The tree and floor pieces in a row in front of the walls (z = 2 + dz)."""
    L = lit_word(lit)
    z = 2 + dz
    row = {
        (0, 0): f"{NS}:ornament_leaves[lit={L}]", (0, 1): f"{NS}:ornament_leaves[lit={L}]", (0, 2): f"{NS}:tree_star[lit={L}]",
        (1, 0): f"{NS}:ornament_leaves[lit={L}]",
        (3, 0): f"{NS}:gift_pile[facing=south]",
        (5, 0): f"{NS}:firework_crate",
        (7, 0): f"{NS}:festival_marker[facing=south]",
        (9, 0): f"{NS}:tinsel_leaves[lit={L}]", (9, 1): f"{NS}:tinsel_leaves[lit={L}]",
        (9, 2): f"{NS}:large_star[half=lower,lit={L}]", (9, 3): f"{NS}:large_star[half=upper,lit={L}]",
        (10, 0): f"{NS}:tinsel_leaves[lit={L}]",
    }
    for (x, y), st in row.items():
        blocks[(x, y, z)] = st
    return blocks


def ground(blocks, x0, x1, z0, z1):
    """Snowy grass under everything that has nothing under it yet."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            blocks.setdefault((x, -2, z), "minecraft:dirt")
            blocks.setdefault((x, -1, z), "minecraft:grass_block")
            if (x, 0, z) not in blocks and (x, 1, z) not in blocks:
                blocks[(x, 0, z)] = "minecraft:snow[layers=%d]" % (2 if h01(x, 0, z, 3) < 0.3 else 1)
    return blocks


def closeup(lit):
    blocks = {}
    blocks.update(decorated_wall(0, 0, "lantern_string", lit))
    blocks.update(decorated_wall(6, 0, "fairy_lights", lit))
    pieces(blocks, 1, lit)
    return ground(blocks, -2, 12, -1, 5)


def square(lit):
    s = trees.build_a()
    # one block down, so the paved square is level with the grass the walls stand on
    blocks = {(x, y - 1, z): ship(st, lit) for (x, y, z), st in s.blocks.items()}
    blocks[(-4, 0, 1)] = f"{NS}:firework_crate"
    blocks[(1, 0, 5)] = f"{NS}:festival_marker[facing=south]"
    blocks.update(decorated_wall(-12, -8, "lantern_string", lit))
    blocks.update(decorated_wall(7, -8, "fairy_lights", lit))
    return ground(blocks, -13, 12, -9, 7)


def job(name, blocks, cam, night=False, samples=24):
    return dict(blocks=blocks, out=os.path.join(OUT, f"{DATE}-s2-{name}.png"), bg=SKY_NIGHT if night else SKY_DAY,
                samples=samples, night=emission if night else None, **cam)


def design_cam(which):
    c = dict(driver_cams()[which])
    c["res"] = [1000, round(1000 * c["res"][1] / c["res"][0])]
    return c


def driver_cams():
    import render_set  # design/src: the shared cameras of the look options
    return render_set.CAMERAS


CLOSE = dict(yaw=22, pitch=13, ortho=13.5, target=[5.0, 1.9, 1.5], res=[1000, 640])
SQUARE = dict(yaw=30, pitch=24, ortho=24.5, target=[1.2, 3.4, -2.5], res=[1000, 780])


def font(size):
    for f in ("C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def design_cell(sheet, index, count=3):
    """One variant's picture out of a design contact sheet (make_sheet.py: 16 px gaps, cells side by side)."""
    im = Image.open(os.path.join(ROOT, "design", sheet)).convert("RGB")
    gap = 16
    cell_w = (2000 - gap * (count + 1)) // count
    cell_h = round(1100 * cell_w / 1300)
    x = gap + index * (cell_w + gap)
    return im.crop((x, gap, x + cell_w, gap + cell_h))


def contact_sheet():
    rows = [("Wreath A Classic", design_cell("sheet-wreath.png", 0), "wreath-day"),
            ("Wall string A Evergreen garland", design_cell("sheet-string.png", 0), "garland-day"),
            ("Wall string B Lantern string", design_cell("sheet-string.png", 1), "lanterns-day"),
            ("Wall string C Fairy lights", design_cell("sheet-string.png", 2), "fairylights-day")]
    cw, gap, label = 484, 10, 34
    ch = round(cw * 1100 / 1300)
    W = gap * 3 + cw * 2
    H = gap + 40 + len(rows) * (ch + label + gap)
    out = Image.new("RGB", (W, H), (236, 236, 232))
    d = ImageDraw.Draw(out)
    f, fb = font(20), font(24)
    d.text((gap, gap), "Picked on 19 Nov 2026 (design)", fill=(34, 34, 34), font=fb)
    d.text((gap * 2 + cw, gap), "Shipped blocks (chunk S2)", fill=(34, 34, 34), font=fb)
    y = gap + 40
    for title, picked, ours in rows:
        shipped = Image.open(os.path.join(OUT, f"{DATE}-s2-{ours}.png")).convert("RGB")
        out.paste(picked.resize((cw, ch), Image.LANCZOS), (gap, y))
        out.paste(shipped.resize((cw, ch), Image.LANCZOS), (gap * 2 + cw, y))
        d.text((gap, y + ch + 4), title, fill=(34, 34, 34), font=f)
        note = {"fairylights-day": "bulbs 3 x 5 px, 1 px proud (were 2 x 3)"}.get(ours, "same drawing")
        d.text((gap * 2 + cw, y + ch + 4), note, fill=(34, 34, 34), font=f)
        y += ch + label + gap
    path = os.path.join(OUT, f"{DATE}-s2-sheet.png")
    out.save(path, optimize=True)
    return path


def shrink(path, limit=600 * 1024):
    """Keeps a render under the size limit of docs/renders: an RGB PNG saved small, and only if that is still too
    big a 256-colour one (octree, which keeps the few saturated texel colours that a median cut averages away)."""
    im = Image.open(path).convert("RGB")
    im.save(path, optimize=True, compress_level=9)
    if os.path.getsize(path) > limit:
        im.quantize(colors=256, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).save(path, optimize=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    if not shutil.which(driver.BLENDER) and not os.path.exists(driver.BLENDER):
        sys.exit(f"Blender not found at {driver.BLENDER} (set BLENDER)")
    jobs = [job("wreath-day", single_wall("wreath"), design_cam("wreath")),
            job("garland-day", single_wall("garland"), design_cam("string")),
            job("lanterns-day", single_wall("lantern_string"), design_cam("string")),
            job("fairylights-day", single_wall("fairy_lights"), design_cam("string")),
            job("closeup-day", closeup(False), CLOSE),
            job("closeup-night", closeup(True), CLOSE, night=True, samples=32),
            job("square-day", square(False), SQUARE),
            job("square-night", square(True), SQUARE, night=True, samples=32)]
    only = sys.argv[1:]
    if only:
        jobs = [j for j in jobs if any(o in os.path.basename(j["out"]) for o in only)]
    driver.render_jobs(jobs)
    for j in jobs:
        if os.path.exists(j["out"]):
            shrink(j["out"])
    if not only or "sheet" in only:
        shrink(contact_sheet())
    for name in sorted(os.listdir(OUT)):
        p = os.path.join(OUT, name)
        print(f"{name}: {Image.open(p).size}, {os.path.getsize(p) // 1024} KB")


if __name__ == "__main__":
    main()
