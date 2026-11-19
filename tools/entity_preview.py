"""Preview renders of the winter clothes on MineColonies' citizen models (the [V] check of design section 7).

Adapted from Voyager's tools/entity_preview.py: it packs model boxes and textures for the Blender renderer
(tools/mcrender.py). Here the boxes come from three places:

  * MineColonies' own models, read out of its jar by tools/mc_models.py (a copy of their boxes, not the mesh);
  * our ScarfModel.java and WoolHatModel.java, whose texOffs/addBox lines are parsed below, so the preview
    draws exactly the boxes the game draws;
  * the same placement as ClothesLayer: the scarf on the model's body part moved onto the top of its first box,
    the hat on its head part, and for a child the scaling a young HumanoidModel gets (head 0.75, body 0.5).

Writes PNG sheets to docs/renders/s6/ (or the folder given with --out). Needs Blender ($BLENDER, else blender
on the PATH) and the MineColonies jar ($MINECOLONIES_JAR); tools/preview.sh sets both from tools/rigpaths.sh.

Run:  bash tools/preview.sh [--out <folder>] [--date YYYY-MM-DD] [sheet ...]
"""
import argparse
import datetime
import json
import math
import os
import re
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # also works under python -I
import paths  # noqa: E402

BLENDER = os.environ.get("BLENDER", "blender")
SRC = os.path.join(paths.ROOT, "src", "me", "lovkar", "seasons", "client")
OUR_TEX = paths.res("assets", "colony_seasons", "textures", "entity")
HAT_MODELS = paths.res("assets", "colony_seasons", "clothes", "hat_models.json")

# parts a model swaps for another one while it works (only one of each pair is ever drawn)
HIDE = {"glassesOff", "Right_Arm_Folded", "Left_Arm_Folded", "FoldedRightArm"}
ROOT_PARTS = ("head", "body", "right_arm", "left_arm", "right_leg", "left_leg", "hat")


# ---------------------------------------------------------------- matrices (4x4, row-major)

def ident():
    return [[1.0 if i == j else 0.0 for j in range(4)] for i in range(4)]


def mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def translate(x, y, z):
    m = ident()
    m[0][3], m[1][3], m[2][3] = x, y, z
    return m


def scale(s):
    m = ident()
    m[0][0] = m[1][1] = m[2][2] = s
    return m


def rot_zyx(rx, ry, rz):
    """PoseStack.mulPose(new Quaternionf().rotationZYX(rz, ry, rx)) as ModelPart.translateAndRotate does: Rz * Ry * Rx."""
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    rxm = [[1, 0, 0, 0], [0, cx, -sx, 0], [0, sx, cx, 0], [0, 0, 0, 1]]
    rym = [[cy, 0, sy, 0], [0, 1, 0, 0], [-sy, 0, cy, 0], [0, 0, 0, 1]]
    rzm = [[cz, -sz, 0, 0], [sz, cz, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
    return mul(rzm, mul(rym, rxm))


def apply(m, p):
    return [m[i][0] * p[0] + m[i][1] * p[1] + m[i][2] * p[2] + m[i][3] for i in range(3)]


def part_matrix(parent, offset, rot):
    return mul(parent, mul(translate(*offset), rot_zyx(*rot)))


# ---------------------------------------------------------------- boxes

def box_uv(tex_off, size, tex_w, tex_h, mirror=False):
    """Voyager's _box_uv: the UV corners of each face of a Minecraft model cube."""
    u, v = tex_off
    w, h, d = size
    rects = {
        "top": (u + d, v, u + d + w, v + d),
        "bottom": (u + d + w, v, u + d + 2 * w, v + d),
        "right": (u, v + d, u + d, v + d + h),
        "front": (u + d, v + d, u + d + w, v + d + h),
        "left": (u + d + w, v + d, u + 2 * d + w, v + d + h),
        "back": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
    }
    out = {}
    for face, (x0, y0, x1, y1) in rects.items():
        a, b = (x1, x0) if mirror else (x0, x1)
        out[face] = [(a / tex_w, 1 - y0 / tex_h), (b / tex_w, 1 - y0 / tex_h), (b / tex_w, 1 - y1 / tex_h), (a / tex_w, 1 - y1 / tex_h)]
    if mirror:
        out["right"], out["left"] = out["left"], out["right"]
    return out


def emit_box(m, box, tex, tex_size, name):
    g = box["inflate"]
    gx, gy, gz = (g, g, g) if not isinstance(g, (list, tuple)) else g
    (x, y, z), (w, h, d) = box["from"], box["size"]
    lo = (x - gx, y - gy, z - gz)
    hi = (x + w + gx, y + h + gy, z + d + gz)
    corners = []
    for xi in (0, 1):
        for yi in (0, 1):
            for zi in (0, 1):
                corners.append(apply(m, (hi[0] if xi else lo[0], hi[1] if yi else lo[1], hi[2] if zi else lo[2])))
    return {"name": name, "corners": corners, "tex": tex,
            "uv": box_uv(box["tex"], box["size"], tex_size[0], tex_size[1], box.get("mirror", False))}


def walk(part, m, tex, tex_size, name, out, hide):
    if name.split("/")[-1] in hide:
        return
    pm = part_matrix(m, part["offset"], part["rot"])
    for i, b in enumerate(part["boxes"]):
        out.append(emit_box(pm, b, tex, tex_size, f"{name}_{i}"))
    for child, sub in part["children"].items():
        walk(sub, pm, tex, tex_size, name + "/" + child, out, hide)


# ---------------------------------------------------------------- our clothes, read out of the Java models

CHILD = re.compile(r"(?:final PartDefinition (\w+) = )?(\w+)\.addOrReplaceChild\(\"(\w+)\",\s*CubeListBuilder\.create\(\)"
                   r"((?:\s*\.texOffs\(\d+, \d+\)\.addBox\([^;]*?\)\)?)+),\s*PartPose\.(\w+)\)")
BOX = re.compile(r"\.texOffs\((\d+), (\d+)\)\.addBox\(([^n]*?)(?:,\s*new CubeDeformation\(([^)]*)\))?\)")
TEX = re.compile(r"TEX_WIDTH = (\d+);.*?TEX_HEIGHT = (\d+);", re.S)


def number(text):
    return float(text.strip().rstrip("Ff"))


def java_model(file_name):
    """{'tex': (w, h), 'parts': {name: {offset, rot, boxes, children}}} from one of our model classes."""
    text = open(os.path.join(SRC, file_name), encoding="utf-8").read()
    w, h = (int(n) for n in TEX.search(text).groups())
    parts = {"root": {"offset": [0, 0, 0], "rot": [0, 0, 0], "boxes": [], "children": {}}}
    by_var = {"root": parts["root"]}
    for var, parent, name, chain, pose in CHILD.findall(text):
        if pose != "ZERO":
            raise ValueError(f"{file_name}: part {name} has PartPose.{pose}; only ZERO is read here")
        boxes = []
        for u, v, args, deform in BOX.findall(chain):
            nums = [number(a) for a in args.split(",") if a.strip()]
            g = [number(a) for a in deform.split(",")] if deform else [0.0]
            boxes.append({"tex": [int(u), int(v)], "from": nums[:3], "size": nums[3:6],
                          "inflate": g if len(g) == 3 else g[0], "mirror": False})
        node = {"offset": [0, 0, 0], "rot": [0, 0, 0], "boxes": boxes, "children": {}}
        by_var[parent]["children"][name] = node
        if var:
            by_var[var] = node
    if not by_var["root"]["children"]:
        raise ValueError(f"{file_name}: no parts found")
    return {"tex": (w, h), "parts": by_var["root"]["children"]}


# ---------------------------------------------------------------- figures

def first_box_anchor(part, standard_top):
    if not part["boxes"]:
        return (0.0, 0.0, 0.0)
    b = part["boxes"][0]
    (x, y, z), (w, h, d) = b["from"], b["size"]
    return (x + w / 2, y - standard_top, z + d / 2)


def figure(models, mc_tex, spec, scarf_model, hat_model):
    model = models[spec["model"]]
    parts = json.loads(json.dumps(model["parts"]))       # a copy: poses are changed below
    tex_size = model["tex"]
    young = spec.get("young", False)
    pitch, yaw = spec.get("head", (0.0, 0.0))
    parts["head"]["rot"] = [parts["head"]["rot"][0] + math.radians(pitch), parts["head"]["rot"][1] + math.radians(yaw), parts["head"]["rot"][2]]
    if "hat" in parts:                                   # HumanoidModel.setupAnim: hat.copyFrom(head)
        parts["hat"]["offset"], parts["hat"]["rot"] = parts["head"]["offset"], parts["head"]["rot"]
    for arm, swing in (("right_arm", spec.get("arms", 0.0)), ("left_arm", -spec.get("arms", 0.0))):
        parts[arm]["rot"][0] += math.radians(swing)
    turn = rot_zyx(0.0, math.radians(spec.get("turn", 0.0)), 0.0)   # the whole figure turned on the spot
    head_group = mul(turn, mul(scale(0.75), translate(0, 16, 0)) if young else ident())
    body_group = mul(turn, mul(scale(0.5), translate(0, 24, 0)) if young else ident())
    hide = HIDE | set(spec.get("hide", ()))
    out = []
    tex = mc_tex(spec["texture"])
    for name in ROOT_PARTS:
        if name not in parts or (young and name == "hat"):
            continue
        walk(parts[name], head_group if name == "head" else body_group, tex, tex_size, name, out, hide)
    outfit = spec.get("outfit", {})
    if outfit.get("scarf"):
        body = parts["body"]
        m = mul(part_matrix(body_group, body["offset"], body["rot"]), translate(*first_box_anchor(body, 0.0)))
        chest = "breast" in body["children"] and "breast" not in hide
        scarf = json.loads(json.dumps(scarf_model["parts"]["scarf"]))
        scarf["children"].pop("tail" if chest else "tail_chest")
        walk(scarf, m, os.path.join(OUR_TEX, f"scarf_{outfit['scarf']}.png"), scarf_model["tex"], "scarf", out, set())
    if outfit.get("hat"):
        head = parts["head"]
        m = mul(part_matrix(head_group, head["offset"], head["rot"]), translate(*first_box_anchor(head, -8.0)))
        walk(hat_model["parts"]["hat"], m, os.path.join(OUR_TEX, f"wool_hat_{outfit['hat']}.png"), hat_model["tex"], "woolhat", out, set())
    return {"label": spec.get("label", ""), "boxes": out}


def hat_allowed():
    data = json.load(open(HAT_MODELS, encoding="utf-8"))["models"]
    return {(k.split(":", 1)[1], g) for k, gs in data.items() for g in gs}


# ---------------------------------------------------------------- the sheets

COLOURS = ["red", "green", "blue", "mustard", "plum"]
JOBS = ["settler", "citizen", "builder", "courier", "miner", "blacksmith", "baker", "healer", "enchanter", "knight"]


def sheets(types, allowed, date):
    def spec(job, gender, colour, label=None, **kw):
        t = types[job]
        cls = t["male"] if gender == "male" else t["female"]
        hat = colour if (job, gender) in allowed else None
        return dict(model=cls, texture=f"{job}{gender}1_b.png", label=label or job, young=job == "child",
                    outfit={"scarf": colour, "hat": hat}, **kw)

    out = []
    for gender, title in (("male", "men"), ("female", "women")):
        figs = [spec(job, gender, COLOURS[i % 5]) for i, job in enumerate(JOBS)]
        out.append(dict(name=f"s6-{title}-{date}", figures=figs, columns=5, yaw=28, pitch=12))
        side = [spec(job, gender, COLOURS[i % 5], turn=55) for i, job in enumerate(["settler", "courier", "baker", "healer", "enchanter"])]
        out.append(dict(name=f"s6-{title}-side-{date}", figures=side, columns=5, yaw=8, pitch=8))
    kids = [spec("settler", "male", "red", "adult"),
            spec("child", "male", "green", "boy"), spec("child", "female", "blue", "girl"),
            spec("settler", "female", "red", "adult, side", turn=80),
            spec("child", "male", "mustard", "boy, side", turn=80), spec("child", "female", "plum", "girl, side", turn=80)]
    out.append(dict(name=f"s6-children-{date}", figures=kids, columns=6, yaw=20, pitch=10, spacing=1.15))
    colours = [spec("settler", "female" if i % 2 else "male", c, c) for i, c in enumerate(COLOURS)]
    colours.append(dict(spec("settler", "female", "festive", "festive"), outfit={"scarf": "festive", "hat": "red"}))
    out.append(dict(name=f"s6-colours-{date}", figures=colours, columns=6, yaw=22, pitch=12, spacing=1.15))
    hats = sorted(allowed)
    figs = [spec(job, g, COLOURS[i % 5], f"{job} {g[0]}") for i, (job, g) in enumerate(hats)]
    out.append(dict(name=f"s6-hats-{date}", figures=figs, columns=7, yaw=24, pitch=12, spacing=1.25, row_height=2.9, size=(1000, 900)))
    moves = [spec("settler", "male", "red", "front"), spec("settler", "male", "red", "turned 60", head=(0, 60)),
             spec("settler", "female", "blue", "looks down", head=(35, 0)), spec("settler", "female", "blue", "looks up", head=(-30, 0)),
             spec("blacksmith", "male", "green", "beard, turned", head=(15, -50)), spec("courier", "female", "plum", "walking", arms=35)]
    close = [spec("settler", "male", "red", "settler, man"), spec("settler", "female", "blue", "settler, woman"),
             spec("child", "female", "mustard", "girl")]
    out.append(dict(name=f"s6-close-{date}", figures=close, columns=3, yaw=20, pitch=12, spacing=1.35,
                    frame=(0.3, 2.35), label_z=0.4, size=(1000, 540)))
    out.append(dict(name=f"s6-moves-{date}", figures=moves, columns=6, yaw=15, pitch=6, spacing=1.15))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(paths.ROOT, "docs", "renders", "s6"))
    ap.add_argument("--date", default=datetime.date.today().isoformat())
    ap.add_argument("only", nargs="*")
    args = ap.parse_args()
    mc_json = paths.out("mc_models.json")
    if not os.path.exists(mc_json):
        raise SystemExit("run python -I tools/mc_models.py <minecolonies jar> first")
    mc = json.load(open(mc_json, encoding="utf-8"))
    jar = os.environ.get("MINECOLONIES_JAR", "")
    if not os.path.exists(jar):
        raise SystemExit("set MINECOLONIES_JAR to the MineColonies jar (the citizen skins are read out of it)")
    tex_dir = paths.out("preview_tex")
    os.makedirs(tex_dir, exist_ok=True)
    zf = zipfile.ZipFile(jar)

    def mc_tex(name):
        target = os.path.join(tex_dir, name)
        if not os.path.exists(target):
            with open(target, "wb") as f:
                f.write(zf.read("assets/minecolonies/textures/entity/citizen/default/" + name))
        return target

    scarf_model, hat_model = java_model("ScarfModel.java"), java_model("WoolHatModel.java")
    jobs = []
    for sheet in sheets(mc["types"], hat_allowed(), args.date):
        if args.only and not any(o in sheet["name"] for o in args.only):
            continue
        figs = [figure(mc["models"], mc_tex, f, scarf_model, hat_model) for f in sheet["figures"]]
        jobs.append({"out": os.path.join(args.out, sheet["name"] + ".png"), "figures": figs,
                     "columns": sheet["columns"], "yaw": sheet["yaw"], "pitch": sheet["pitch"],
                     "spacing": sheet.get("spacing", 1.3), "row_height": sheet.get("row_height", 2.6),
                     "size": sheet.get("size", (1000, 640)),
                     **{k: sheet[k] for k in ("frame", "label_z") if k in sheet}})
    job_file = paths.out("preview_job.json")
    with open(job_file, "w", encoding="utf-8") as f:
        json.dump(jobs, f)
    subprocess.run([BLENDER, "-b", "--factory-startup", "--python", os.path.join(paths.TOOLS, "mcrender.py"), "--", job_file],
                   check=True)
    for job in jobs:
        shrink(job["out"])
        print(job["out"], os.path.getsize(job["out"]))


def shrink(png):
    """A 256-colour palette keeps a sheet well under the 600 KB a render may take (needs Pillow; skipped without it)."""
    try:
        from PIL import Image
    except ImportError:
        return
    with Image.open(png) as im:
        small = im.convert("RGB").quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    small.save(png, optimize=True)


if __name__ == "__main__":
    main()
