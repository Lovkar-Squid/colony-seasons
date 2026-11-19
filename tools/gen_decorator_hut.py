"""The Decorator's hut block model: a miniature of hut A, the "Workshop with shopfront", from vanilla textures only.

A stone-brick plinth, a shopfront on spruce posts with a glass window under a red and white awning and a spruce
door with a small wreath, a white plaster upper floor in a spruce frame, a stepped dark-oak roof with plaster gable
ends. The front is the south face: MineColonies sets a hut's facing to the direction the player looks, so the
blockstate turns the south face towards the player (facing=north is y 0, as in Voyager's huts).

Writes resources/assets/colony_seasons/models/block/blockhutdecorator.json and models/item/blockhutdecorator.json.
    python tools/gen_decorator_hut.py
    python tools/gen_decorator_hut.py --render docs/renders/s3/hut.png    (also renders it with Blender, see below)
The render uses the design renderer in design/tools (env BLENDER, MC_JAR as in design/README.md); our own
model is read from resources/assets.
"""
import json
import os
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(TOOLS)
MODELS = os.path.join(ROOT, "resources", "assets", "colony_seasons", "models")

TEXTURES = {
    "andesite": "minecraft:block/polished_andesite",
    "bricks": "minecraft:block/stone_bricks",
    "planks": "minecraft:block/spruce_planks",
    "log": "minecraft:block/spruce_log",
    "log_top": "minecraft:block/spruce_log_top",
    "beam": "minecraft:block/stripped_spruce_log",
    "glass": "minecraft:block/glass",
    "door_bottom": "minecraft:block/spruce_door_bottom",
    "door_top": "minecraft:block/spruce_door_top",
    "red": "minecraft:block/red_wool",
    "white": "minecraft:block/white_wool",
    "plaster": "minecraft:block/mushroom_stem",
    "roof": "minecraft:block/dark_oak_planks",
    "leaves": "minecraft:block/azalea_leaves",
    "lantern": "minecraft:block/lantern",
    "particle": "minecraft:block/spruce_planks",
}
ALL = ("north", "south", "east", "west", "up", "down")


def auto_uv(face, a, b):
    (x1, y1, z1), (x2, y2, z2) = a, b
    if face in ("north", "south"):
        return [x1, 16 - y2, x2, 16 - y1]
    if face in ("east", "west"):
        return [z1, 16 - y2, z2, 16 - y1]
    return [x1, z1, x2, z2]


def element(name, a, b, faces, rotation=None, uv=None, uv_rot=None):
    """faces: one texture key for every face, or {face: key}; uv / uv_rot: optional {face: value} overrides."""
    if isinstance(faces, str):
        faces = {f: faces for f in ALL}
    out = {}
    for f, key in faces.items():
        face = {"uv": (uv or {}).get(f, auto_uv(f, a, b)), "texture": "#" + key}
        if uv_rot and f in uv_rot:
            face["rotation"] = uv_rot[f]
        out[f] = face
    el = {"name": name, "from": list(a), "to": list(b), "faces": out}
    if rotation:
        axis, angle, origin = rotation
        el["rotation"] = {"angle": angle, "axis": axis, "origin": list(origin)}
    return el


def sides(side, up=None, down=None, south=None):
    f = {d: side for d in ("north", "south", "east", "west")}
    if south:
        f["south"] = south
    if up:
        f["up"] = up
    if down:
        f["down"] = down
    return f


def post(name, x, y0, y1, z0, z1):
    return element(name, (x, y0, z0), (x + 1, y1, z1), sides("log", up="log_top", down="log_top"))


def build():
    els = []
    # the plinth: polished andesite on stone bricks
    els.append(element("plinth", (1, 0, 1), (15, 1, 15), sides("bricks", up="andesite", down="bricks")))
    # ground floor: stone-brick walls, spruce planking behind the shopfront
    els.append(element("ground floor", (2.5, 1, 3), (13.5, 7, 12), sides("bricks", up="planks", south="planks")))
    # the shopfront: three spruce posts, a counter, the window and the door
    els.append(post("post left", 2.5, 1, 7, 12, 13))
    els.append(post("post middle", 8.5, 1, 7, 12, 13))
    els.append(post("post right", 12.5, 1, 7, 12, 13))
    els.append(element("counter", (3.5, 1, 12), (8.5, 2, 13.5), sides("planks", up="planks", down="planks")))
    els.append(element("shop window", (3.5, 2, 12.1), (8.5, 6, 12.3), {"south": "glass", "north": "glass"},
                       uv={"south": [0, 0, 16, 16], "north": [0, 0, 16, 16]}))
    els.append(element("door lower", (9.5, 1, 12.05), (12.5, 3.5, 12.35), {"south": "door_bottom", "north": "door_bottom",
                                                                          "east": "planks", "west": "planks"},
                       uv={"south": [0, 0, 16, 16], "north": [0, 0, 16, 16]}))
    els.append(element("door upper", (9.5, 3.5, 12.05), (12.5, 6, 12.35), {"south": "door_top", "north": "door_top",
                                                                          "east": "planks", "west": "planks", "up": "planks"},
                       uv={"south": [0, 0, 16, 16], "north": [0, 0, 16, 16]}))
    # the small wreath on the door, a red bow under it
    els.append(element("wreath", (10.4, 4.4, 12.35), (11.6, 5.6, 12.55), {"south": "leaves", "north": "leaves",
                                                                          "east": "leaves", "west": "leaves",
                                                                          "up": "leaves", "down": "leaves"},
                       uv={"south": [3, 3, 13, 13], "north": [3, 3, 13, 13]}))
    els.append(element("bow", (10.75, 4.25, 12.5), (11.25, 4.65, 12.65), "red"))
    # the red and white awning over the window, sloping out towards the street
    edges = [2.5, 4.125, 5.75, 7.375, 9.0]
    for i in range(4):
        key = "red" if i % 2 == 0 else "white"
        els.append(element(f"awning {i + 1}", (edges[i], 6.1, 12.3), (edges[i + 1], 6.7, 15.3), key,
                           rotation=("x", -22.5, (8, 6.7, 12.3))))
    # a lantern on the right post, by the door
    els.append(element("lantern", (13.5, 4.4, 12.8), (14.3, 5.4, 13.6),
                       {"north": "lantern", "south": "lantern", "east": "lantern", "west": "lantern",
                        "up": "lantern", "down": "lantern"},
                       uv={f: [0, 2, 6, 9] for f in ("north", "south", "east", "west")} | {"up": [0, 9, 6, 15], "down": [0, 9, 6, 15]}))
    # the beam between the floors: stripped spruce, grain running along it
    els.append(element("floor beam", (2, 7, 2.5), (14, 8, 12.6), sides("beam", up="beam", down="beam"),
                       uv_rot={"north": 90, "south": 90, "up": 90, "down": 90}))
    # the upper floor: white plaster in a spruce frame with two windows
    els.append(element("upper floor", (2.5, 8, 3), (13.5, 11.5, 12.2), sides("plaster", up="plaster", down="plaster")))
    els.append(post("frame left", 2.5, 8, 11.5, 12, 12.5))
    els.append(post("frame middle", 7.5, 8, 11.5, 12, 12.5))
    els.append(post("frame right", 12.5, 8, 11.5, 12, 12.5))
    for name, x0, x1 in (("upper window left", 4.0, 6.5), ("upper window right", 9.5, 12.0)):
        els.append(element(name + " backing", (x0, 8.7, 12.2), (x1, 10.9, 12.25), {"south": "roof"}))
        els.append(element(name, (x0, 8.7, 12.25), (x1, 10.9, 12.45), {"south": "glass", "north": "glass"},
                           uv={"south": [0, 0, 16, 16], "north": [0, 0, 16, 16]}))
    # the roof: dark oak in steps, ridge running east-west, plaster gable ends above the eave
    steps = [(11.5, 12.5, 1.5, 13.5, "roof"), (12.5, 13.5, 3.0, 12.0, "plaster"), (13.5, 14.5, 4.5, 10.5, "plaster"),
             (14.5, 15.5, 6.0, 9.0, "plaster"), (15.5, 16.0, 7.0, 8.0, "plaster")]
    for i, (y0, y1, z0, z1, gable) in enumerate(steps):
        f = {"north": "roof", "south": "roof", "up": "roof", "down": "roof", "east": gable, "west": gable}
        els.append(element(f"roof step {i + 1}", (1.5, y0, z0), (14.5, y1, z1), f,
                           uv_rot={"up": 90, "down": 90}))
    return els


def write():
    block = {
        "credit": "Colony Seasons - Lovkar & Claude",
        "parent": "block/block",
        "render_type": "minecraft:cutout",
        "ambientocclusion": False,
        "textures": TEXTURES,
        "elements": build(),
    }
    item = {
        "parent": "colony_seasons:block/blockhutdecorator",
        "display": {
            "thirdperson_righthand": {"rotation": [45, -45, 0], "translation": [0, 2.5, 0], "scale": [0.3, 0.3, 0.3]},
            "thirdperson_lefthand": {"rotation": [45, -45, 0], "translation": [0, 2.5, 0], "scale": [0.3, 0.3, 0.3]},
            "firstperson_righthand": {"rotation": [0, -70, 0], "scale": [0.36, 0.36, 0.36]},
            "firstperson_lefthand": {"rotation": [0, -70, 0], "scale": [0.36, 0.36, 0.36]},
            "ground": {"translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
            "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
            "head": {"rotation": [0, 180, 0], "scale": [0.91, 0.91, 0.91]},
            "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
        },
    }
    for sub, name, doc in (("block", "blockhutdecorator.json", block), ("item", "blockhutdecorator.json", item)):
        os.makedirs(os.path.join(MODELS, sub), exist_ok=True)
        path = os.path.join(MODELS, sub, name)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps(doc, indent=2) + "\n")
        print("wrote", os.path.relpath(path, ROOT))
    print(len(block["elements"]), "elements")


def render(out):
    """One picture of the hut block, with the design renderer (Blender) and our own resources."""
    os.environ.setdefault("CS_ASSETS", os.path.join(ROOT, "resources", "assets"))
    sys.path.insert(0, os.path.join(ROOT, "design", "tools"))
    import driver  # noqa: E402  (design/tools/driver.py)
    job = dict(blocks={(0, 0, 0): "colony_seasons:blockhutdecorator[facing=north]"}, out=os.path.abspath(out),
               bg=[0.80, 0.85, 0.90], samples=24, yaw=32, pitch=28, ortho=1.9, target=[0.5, 0.5, 0.5], res=[900, 900])
    os.makedirs(os.path.dirname(job["out"]), exist_ok=True)
    driver.render_jobs([job])
    # a 256-colour palette keeps the picture small enough for the docs folder (about 600 KB at most)
    from PIL import Image
    Image.open(job["out"]).convert("RGB").quantize(colors=256, dither=Image.Dither.NONE).save(job["out"], optimize=True)
    print("saved", job["out"], os.path.getsize(job["out"]) // 1024, "KB")


if __name__ == "__main__":
    write()
    if len(sys.argv) >= 3 and sys.argv[1] == "--render":
        render(sys.argv[2])
