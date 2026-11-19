"""Glue: Structure/blocks -> quads JSON (mcmodel) -> Blender render (render_scene.py)."""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mcmodel  # noqa: E402

BLENDER = os.environ.get("BLENDER", r"C:\Program Files\Blender Foundation\Blender 5.1\blender.exe")
SCRATCH = os.environ.get("CS_SCRATCH", os.path.join(os.environ.get("TEMP", "."), "cs_render"))


def render_jobs(jobs):
    """jobs: list of dicts with 'blocks' ({(x,y,z): state}) + camera fields; renders all in one Blender run.
    An optional 'night' (emission(name, props) -> light level) lights that scene as at midnight."""
    os.makedirs(SCRATCH, exist_ok=True)
    out_jobs = []
    for i, j in enumerate(jobs):
        qpath = os.path.join(SCRATCH, f"scene_{os.path.basename(j['out'])}.json")
        n = mcmodel.write_scene(j["blocks"], qpath, night=j.get("night"))
        print("scene", j["out"], "quads", n)
        jj = {k: v for k, v in j.items() if k not in ("blocks", "night")}
        jj["scene"] = qpath
        out_jobs.append(jj)
    jp = os.path.join(SCRATCH, "jobs.json")
    with open(jp, "w") as f:
        json.dump(out_jobs, f)
    r = subprocess.run([BLENDER, "--background", "--factory-startup", "--python",
                        os.path.join(HERE, "render_scene.py"), "--", jp], capture_output=True, text=True)
    lines = [l for l in r.stdout.splitlines() if "RENDERED" in l or "Error" in l or "error" in l.lower()]
    print("\n".join(lines[-20:]))
    if r.returncode != 0:
        print(r.stdout[-3000:], r.stderr[-3000:])


def blocks_of(structure):
    return dict(structure.blocks)
