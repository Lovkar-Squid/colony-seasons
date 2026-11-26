"""Renders of the street pieces of the Decorator's wand (chunk Q11): docs/renders/q11/.

    bash tools/render_q11.sh            (or: python tools/render_q11.py, with SEASONS_LIBS and MC_ASSETS_JAR set)

  snowman.png, snowman_b.png        by day, one piece on a snowy verge beside a village road, with the picture round's
  lantern_post.png                  door-height post for scale (design/src/squares.py street_scene's ground and camera)
  lantern_post-lit.png              at night, the post lit (the lantern in its crook, the bulbs in its garland)
  street_lights-lit.png             at night, thin street lights on a fence beside the road (decorator.streetLights)
  street.png, street-night.png      a marked street as the Decorator dresses it (the layout of probe cswand): a dirt
                                    road, snowman A on the road's edge, snowman B, two lantern posts across the road
                                    with the garland between their upper halves, a third post, a planter (winter's
                                    spruce boughs) and street lights on every third post of a fence; by day unlit, at
                                    night lit as the tree's lighting leaves them

The blocks are the mod's SHIPPED blockstates and models (resources/assets/colony_seasons), drawn through
design/tools/mcmodel.py, Blender headless; one Blender process, never beside a probe.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(ROOT, "design", "src"))
import paths  # noqa: E402

os.environ.setdefault("MC_JAR", paths.lib(os.environ.get("MC_ASSETS_JAR", "client-*-extra.jar")))
import squares  # noqa: E402  (design/src/squares.py: the cameras, the lights, the asset merge)

OUT = os.path.join(ROOT, "docs", "renders", "q11")
NS = "colony_seasons"


def verge(s, r=4, z0=-3, z1=6):
    """Grass and dirt with a three-wide dirt road at z 2 to 3, as the picture round's street scene."""
    for x in range(-r, r + 1):
        for z in range(z0, z1):
            s.set(x, -1, z, "minecraft:dirt", force=False)
            s.set(x, 0, z, "minecraft:dirt_path" if 2 <= z <= 3 else "minecraft:grass_block", force=False)


def post(s, x, z, facing, lit):
    lit = "true" if lit else "false"
    s.set(x, 1, z, f"{NS}:lantern_post[facing={facing},half=lower,lit={lit}]")
    s.set(x, 2, z, f"{NS}:lantern_post[facing={facing},half=upper,lit={lit}]")


def piece(kind, lit=False):
    s = squares.Scene()
    if kind in ("snowman", "snowman_b"):
        s.set(0, 1, 0, f"{NS}:{kind}[facing=south]")
    elif kind == "lantern_post":
        post(s, 0, 0, "south", lit)
    elif kind == "street_lights":
        for x in range(-2, 3):
            s.set(x, 1, 0, "minecraft:oak_fence[east=" + ("true" if x < 2 else "false") + ",west=" + ("true" if x > -2 else "false") + "]")
        for x in (-2, 1):
            s.set(x, 2, 0, f"{NS}:street_lights[axis=x,lit={'true' if lit else 'false'}]")
    squares.scale_post(s, -3, 1)
    verge(s)
    s.snow(r=5, paving=0.0, holes=0)
    return s.b


def street(lit):
    """The probe's street, turned for the camera: the road along x at z 2 to 3, the garland across it."""
    s = squares.Scene()
    L = "true" if lit else "false"
    s.set(-6, 1, 1, f"{NS}:snowman[facing=south]")
    s.set(-3, 1, 5, f"{NS}:snowman_b[facing=north]")
    post(s, 1, 0, "south", lit)
    post(s, 1, 5, "north", lit)
    for z in range(1, 5):
        s.set(1, 2, z, f"{NS}:street_garland[axis=z]")
    post(s, 5, 1, "south", lit)
    s.set(6, 1, 5, "minecraft:spruce_leaves[persistent=true]")
    for x in range(-7, -1):
        east = "true" if x < -2 else "false"
        west = "true" if x > -7 else "false"
        s.set(x, 1, -1, f"minecraft:spruce_fence[east={east},west={west}]")
    for x in (-6, -3):
        s.set(x, 2, -1, f"{NS}:street_lights[axis=x,lit={L}]")
    verge(s, r=7, z0=-3, z1=7)
    s.snow(r=8, paving=0.0, holes=0)
    return s.b


def main():
    os.makedirs(OUT, exist_ok=True)
    squares.OUT = OUT
    squares.merge_assets()
    import driver  # noqa: E402  (after CS_ASSETS is set)
    jobs = [squares.job("snowman", piece("snowman"), squares.CAM_STREET),
            squares.job("snowman_b", piece("snowman_b"), squares.CAM_STREET),
            squares.job("lantern_post", piece("lantern_post"), squares.CAM_STREET),
            squares.job("lantern_post-lit", piece("lantern_post", lit=True), squares.CAM_STREET, night=True, samples=32),
            squares.job("street_lights-lit", piece("street_lights", lit=True), squares.CAM_STREET, night=True, samples=32),
            squares.job("street", street(False), squares.CAM_STREET_WIDE),
            squares.job("street-night", street(True), squares.CAM_STREET_WIDE, night=True, samples=32)]
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
