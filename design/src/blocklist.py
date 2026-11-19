"""Block list + size of every tree and hut variant, without the ground/scenery patch it stands on.

python blocklist.py  -> prints Markdown (pasted into design/README.md)
"""
from collections import Counter

import huts
import trees
from common import Structure, ground_patch


def own_blocks(s, bare):
    return {p: b for p, b in s.blocks.items() if bare.blocks.get(p) != b}


def describe(label, s, bare):
    own = own_blocks(s, bare)
    # scale post and paving are scenery, not part of the piece
    xs = [p[0] for p in own]; ys = [p[1] for p in own]; zs = [p[2] for p in own]
    c = Counter(b.split("[")[0] for b in own.values())
    size = f"{max(xs) - min(xs) + 1} x {max(zs) - min(zs) + 1} footprint, {max(ys) - min(ys) + 1} tall"
    lines = [f"**{label}** - {size} (bounding box of everything placed, incl. ground-level changes)", ""]
    for name, n in sorted(c.items(), key=lambda kv: (-kv[1], kv[0])):
        lines.append(f"- {n} x `{name}`")
    return "\n".join(lines)


def main():
    out = []
    for k, (name, fn) in trees.BUILDS.items():
        bare = Structure("bare")
        ground_patch(bare, plaza=6.6 if k == "B" else 5.5)
        s = fn()
        # drop the scale post from the list
        for p in [(trees.SCALE_POST[0], y, trees.SCALE_POST[1]) for y in range(0, 4)]:
            s.blocks.pop(p, None); bare.blocks.pop(p, None)
        out.append(describe(f"Tree {k} {name}", s, bare))
    for k, (name, fn) in huts.BUILDS.items():
        bare = Structure("bare")
        huts.ground(bare)
        out.append(describe(f"Hut {k} {name}", fn(), bare))
    print("\n\n".join(out))


if __name__ == "__main__":
    main()
