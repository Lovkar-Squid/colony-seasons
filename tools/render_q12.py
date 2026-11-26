"""Renders of the Town Square's seasonal touches and the tree's foot (chunk Q12): docs/renders/q12/.

    bash tools/render_q12.sh            (or: python tools/render_q12.py, with SEASONS_LIBS and MC_ASSETS_JAR set)

  level3-spring.png .. level3-winter.png   by day, the SHIPPED level-3 square (square/townsquare3.blueprint) with the
                                           season's SHIPPED touch (pieces/bed_<season>.blueprint) on every `bed` cell,
                                           placed by the code's contract (decor/DecorWork.touch: the piece's anchor on
                                           the planter's soil cell, the `bed` cell minus one in y): spring flowering
                                           azalea leaves, summer azalea leaves, autumn pumpkins, winter spruce boughs
  level3-festival-foot.png                 by day, the level-3 square in the Winter Festival's window: the planters
                                           empty (the touches gave way), the tree of piece level 3 (the full village
                                           spruce) with its foot, one festival trunk in the `tree` cell itself
                                           (DecorWork.foot), the gift pile at `gift` and the crate at `launch`

The camera, the ground and the lights are render_q6's (the picture round's CAM_SQUARE), so the pictures sit beside
docs/renders/q6/. Real block models through design/tools/mcmodel.py, Blender headless; one Blender process, never
beside a probe.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import render_q6 as q6  # noqa: E402  (sets up the paths, the asset jar and design/src/squares.py)
import square_hut as sq  # noqa: E402
import voxel  # noqa: E402
import squares  # noqa: E402

OUT = os.path.join(ROOT, "docs", "renders", "q12")
SEASONS = ("spring", "summer", "autumn", "winter")
LEVEL = 3


def touch_piece(season):
    """The shipped touch piece: its blocks relative to its anchor (the code skips Structurize's placeholders)."""
    piece, _ = voxel.load_blueprint(os.path.join(q6.PACK, "pieces", f"bed_{season}.blueprint"))
    ax, ay, az = piece.anchor
    return {(x - ax, y - ay, z - az): b for (x, y, z), b in piece.blocks.items() if not b.startswith("structurize:")}


def with_touches(blocks, season):
    """The touch on every `bed` cell of the level: the piece's anchor on the soil cell (bed minus one in y)."""
    out = dict(blocks)
    cells = touch_piece(season)
    for (x, y, z) in sq.tag_cells(LEVEL)["bed"]:
        for (dx, dy, dz), b in cells.items():
            p = (x + dx, y - 1 + dy, z + dz)
            if out.get(p) in (None, "minecraft:air"):          # the cell rule: only into air
                out[p] = b
    return out


def with_foot(blocks):
    """The Winter Festival's set of piece level 3 (render_q6.winter_set, unlit) and the tree's foot in the `tree` cell."""
    out = q6.winter_set(blocks, LEVEL)
    # one crate, at the square's `launch` cell: the piece's own crate is not placed on a Town Square (PieceLoader.plan)
    out = {p: b.replace("lit=true", "lit=false") for p, b in out.items()
           if p == sq.LAUNCH or not b.startswith("colony_seasons:firework_crate")}
    out[sq.TREE] = "colony_seasons:festival_trunk[axis=y]"
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    squares.OUT = OUT
    squares.merge_assets()
    import driver  # noqa: E402  (after CS_ASSETS is set)
    base = q6.square_blocks(LEVEL)
    jobs = [q6.job(f"level{LEVEL}-{season}", q6.with_ground(with_touches(base, season)), False) for season in SEASONS]
    jobs.append(q6.job(f"level{LEVEL}-festival-foot", q6.with_ground(with_foot(base)), False))
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
