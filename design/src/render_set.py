"""Render a whole set with one shared camera:  python render_set.py tree|hut|wreath|string [A B C]

Outputs design/<set>-<V>.png. Needs Blender (env BLENDER) and the client jar (env MC_JAR).
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "tools"))
import driver  # noqa: E402
from common import DESIGN  # noqa: E402

SKY = [0.80, 0.85, 0.90]

CAMERAS = {
    # yaw/pitch/ortho/target/resolution shared by every variant of a set (fair comparison)
    "tree": dict(yaw=35, pitch=26, ortho=25.5, target=[0, 6.7, 0], res=[1300, 1400]),
    "hut": dict(yaw=32, pitch=28, ortho=24.0, target=[0, 6.0, 0.5], res=[1300, 1300]),
    "wreath": dict(yaw=26, pitch=11, ortho=8.0, target=[2.5, 2.35, 0.5], res=[1300, 1100]),
    "string": dict(yaw=26, pitch=11, ortho=8.0, target=[2.5, 2.35, 0.5], res=[1300, 1100]),
}


def main():
    which = sys.argv[1]
    variants = sys.argv[2:] or ["A", "B", "C"]
    if which == "tree":
        import trees as mod
    elif which == "hut":
        import huts as mod
    else:
        import walls as mod
    builds = mod.BUILDS if which in ("tree", "hut") else (mod.WREATHS if which == "wreath" else mod.STRINGS)
    jobs = []
    for v in variants:
        label, fn = builds[v]
        s = fn()
        cam = dict(CAMERAS[which])
        jobs.append(dict(blocks=dict(s.blocks), out=os.path.join(DESIGN, f"{which}-{v}.png"), bg=SKY,
                         samples=24, **cam))
    driver.render_jobs(jobs)


if __name__ == "__main__":
    main()






