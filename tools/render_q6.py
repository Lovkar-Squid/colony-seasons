"""Renders of the Town Square as the SHIPPED blueprints hold it (chunk Q6): docs/renders/q6/.

    bash tools/render_q6.sh            (or: python tools/render_q6.py, with SEASONS_LIBS and MC_ASSETS_JAR set)

  level1.png .. level5.png     by day, the bare square, read from square/townsquare1..5.blueprint in the resources
  level5-night-winter.png      at night, the level-5 square with the Winter Festival's set as the Decorator's code places it:
                               pieces/grand_tree.blueprint on the `tree` cell with its blocks lit (the lit tree), the gift
                               pile at the `gift` cell facing away from the tree, the firework crate at the `launch` cell
                               (decor/DecorWork.fixtures); nothing on the lamp posts or in the planters, because no code
                               places anything there yet

The camera is the picture round's (design/src/squares.py CAM_SQUARE), so the pictures sit beside the owner's picks in
design/square/. Real block models through design/tools/mcmodel.py (the client jar's, the mod's own blockstates and
models, the real hut block), Blender headless; one Blender process, never beside a probe. The ground outside the
paving is grass over dirt, as in the picture round; a substitution cell of the blueprint ("the terrain") is drawn as
grass.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(ROOT, "design", "src"))
import paths  # noqa: E402
import square_hut as sq  # noqa: E402  (the repo's voxel first: design/src/squares.py puts the design's own copy of it on the path)
import voxel  # noqa: E402

os.environ.setdefault("MC_JAR", paths.lib(os.environ.get("MC_ASSETS_JAR", "client-*-extra.jar")))
import squares  # noqa: E402  (design/src/squares.py: the camera, the lights, the asset merge)

OUT = os.path.join(ROOT, "docs", "renders", "q6")
PACK = os.path.join(ROOT, "resources", "blueprints", "colony_seasons", "colony_seasons")
GROUND_R = 12


def square_blocks(level):
    """The shipped square blueprint of `level` in the square's frame (x, z from -10 to 10, the `tree` cell at (0, 1, 0))."""
    st, _ = voxel.load_blueprint(os.path.join(PACK, "square", f"townsquare{level}.blueprint"))
    (x0, y0, z0), _ = sq.BOX
    out = {}
    for (x, y, z), b in st.blocks.items():
        p = (x + x0, y + y0, z + z0)
        if b.startswith("structurize:"):
            b = "minecraft:grass_block"                      # the terrain that is already there
        out[p] = b
    return out


def with_ground(blocks):
    """Grass and dirt round the square, and the dirt roads into it from the south and the east (as the picture round)."""
    out = dict(blocks)
    for x in range(-GROUND_R, GROUND_R + 1):
        for z in range(-GROUND_R, GROUND_R + 1):
            out.setdefault((x, -1, z), "minecraft:dirt")
            out.setdefault((x, 0, z), "minecraft:grass_block")
    for t in range(sq.HALF + 1, GROUND_R + 1):
        for c in (-1, 0, 1):
            out[(c, 0, t)] = "minecraft:dirt_path"
            out[(t, 0, c)] = "minecraft:dirt_path"
    return out


def winter_set(blocks, level):
    """The Winter Festival's set on the square as the code places it (decor/DecorWork, PieceLoader): the centrepiece of the
    level (SquareSets.WINTER, piece level = the lower of the square's and the Workshop's, here both `level`), every
    block of its cells but Structurize's placeholders, lit; then the gift pile and the crate."""
    names = {1: "village_spruce_small", 2: "village_spruce_small", 3: "village_spruce_full", 4: "village_spruce_rich",
             5: "grand_tree"}
    piece, _ = voxel.load_blueprint(os.path.join(PACK, "pieces", names[level] + ".blueprint"))
    ax, ay, az = piece.anchor
    tx, ty, tz = sq.TREE
    out = dict(blocks)
    for (x, y, z), b in piece.blocks.items():
        if b.startswith("structurize:"):
            continue
        out[(tx + x - ax, ty + y - ay, tz + z - az)] = b.replace("lit=false", "lit=true")
    gx, gy, gz = sq.GIFT
    away = ("east" if gx > 0 else "west") if abs(gx) > abs(gz) else ("south" if gz > 0 else "north")   # Direction.getNearest
    out[(gx, gy, gz)] = f"colony_seasons:gift_pile[facing={away}]"
    lx, ly, lz = sq.LAUNCH
    out[(lx, ly, lz)] = "colony_seasons:firework_crate"
    return out


def job(name, blocks, night):
    return squares.job(name, blocks, squares.CAM_SQUARE, night=night, samples=32 if night else 24)


def main():
    os.makedirs(OUT, exist_ok=True)
    squares.OUT = OUT
    squares.merge_assets()
    import driver  # noqa: E402  (after CS_ASSETS is set)
    jobs = []
    names = {}
    for lv in sq.LEVELS:
        jobs.append(job(f"level{lv}", with_ground(square_blocks(lv)), False))
    jobs.append(job("level5-night-winter", with_ground(winter_set(square_blocks(5), 5)), True))
    for j in jobs:
        names[j["out"]] = j["out"]
    driver.render_jobs(jobs)
    for j in jobs:
        if os.path.exists(j["out"]):
            squares.shrink(j["out"])
            print("wrote", os.path.relpath(j["out"], ROOT))
        else:
            print("MISSING", j["out"])
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
