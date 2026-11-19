"""Writes every texture, block model, item model and blockstate of the decoration blocks (chunk S2) and the gift item (S7).

Run:  python -I tools/textures/make_all.py      (needs Pillow and the Minecraft client assets jar, see tools/paths.py)
Output: resources/assets/colony_seasons/{textures/block,textures/item,models/block,models/item,blockstates}/.
The files are committed; run this again after changing a drawing and look at the result (tools/render_decor.py).
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import common  # noqa: E402
import floor  # noqa: E402
import gift  # noqa: E402
import tree  # noqa: E402
import walls  # noqa: E402


def main():
    common.ensure_dirs()
    walls.generate()
    t = tree.generate()
    floor.generate()
    gift.generate()
    print(f"tree_star: {t['tree_star'][1]} elements, large_star: {t['large_star'][1]} elements (both halves)")
    print(f"{len(common.written)} files written under {os.path.relpath(common.ASSETS, common.paths.ROOT)}")


if __name__ == "__main__":
    main()
