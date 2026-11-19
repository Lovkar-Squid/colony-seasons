"""Reads MineColonies' citizen models out of its jar: which model types exist, which male and female
model class each one uses, and the boxes every model class builds in its createMesh().

The jar has no sources, so this runs `javap -c` on the model classes and plays their createMesh()
bytecode on a tiny stack machine that knows the dozen calls a model mesh is made of
(CubeListBuilder, CubeDeformation, PartPose, PartDefinition, HumanoidModel.createMesh). The result
is a copy of the boxes, not the real mesh: good enough to see where a scarf or a hat sits on every
model and to fit them, never shipped.

Output: tools/out/mc_models.json
  {"types":  {"<model id>": {"field": "BUILDER_ID", "textures": n, "male": "MaleBuilderModel", "female": "FemaleBuilderModel"}},
   "models": {"<class>": {"tex": [w, h], "parts": {"head": {"offset": [x,y,z], "rot": [rx,ry,rz],
                                                        "boxes": [{"tex": [u,v], "from": [x,y,z], "size": [w,h,d], "inflate": g, "mirror": b}],
                                                        "children": {...}}, ...}}}}

Run:  python -I tools/mc_models.py [minecolonies.jar]      (tools/preview.sh runs it)
The jar defaults to $MINECOLONIES_JAR; javap comes from $JDK_BIN (both set by tools/rigpaths.sh).
"""
import json
import os
import re
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # also works under python -I
import paths  # noqa: E402

MODEL_PKG = "com/minecolonies/core/client/model/"
TYPES_CLASS = "com.minecolonies.api.client.render.modeltype.ModModelTypes"
INIT_CLASS = "com.minecolonies.apiimp.initializer.ModModelTypeInitializer"
BASE_CLASS = "com.minecolonies.api.client.render.modeltype.CitizenModel"

INSN = re.compile(r"^\s+(\d+): (\w+)\s*(.*?)\s*$")


def javap(jar, classes):
    """javap -c -p output split per class: {fully qualified name: [instruction lines per method]}."""
    exe = os.path.join(os.environ.get("JDK_BIN", ""), "javap.exe" if os.name == "nt" else "javap")
    if not os.path.exists(exe):
        exe = "javap"
    text = subprocess.run([exe, "-c", "-p", "-cp", jar] + classes, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", check=True).stdout
    result = {}
    current = None
    method = None
    for line in text.splitlines():
        head = re.match(r"^(?:public |final |abstract )*class (\S+?)(?:<.*?>)? ", line)
        if head:
            current = head.group(1)
            result[current] = {}
            continue
        if current is None:
            continue
        if line.strip() == "static {};":
            method = "<clinit>()"
            result[current][method] = []
            continue
        sig = re.match(r"^  (?:public |private |protected |static |final |synchronized )*[\w.<>$\[\], ?]*?\b([\w$<>]+)\((.*)\);$", line)
        if sig:
            method = sig.group(1) + "(" + sig.group(2) + ")"
            result[current][method] = []
            continue
        m = INSN.match(line)
        if m and method is not None:
            result[current][method].append((m.group(2), m.group(3)))
    return result


def comment(operand):
    return operand.split("//", 1)[1].strip() if "//" in operand else ""


def constant(operand):
    """The value an ldc pushes, from javap's comment: float, int or String."""
    c = comment(operand)
    if c.startswith("float "):
        return float(c[6:].rstrip("f"))
    if c.startswith("int "):
        return int(c[4:])
    if c.startswith("String "):
        return c[7:]
    if c.startswith("double "):
        return float(c[7:].rstrip("d"))
    raise ValueError("unknown constant " + operand)


class Part:
    def __init__(self, boxes, pose):
        self.boxes = boxes
        self.offset = pose[:3]
        self.rot = pose[3:]
        self.children = {}

    def child(self, name, boxes, pose):
        part = Part(boxes, pose)
        old = self.children.get(name)
        if old is not None:          # PartDefinition.addOrReplaceChild keeps the replaced part's children
            part.children.update(old.children)
        self.children[name] = part
        return part

    def to_json(self):
        return {"offset": self.offset, "rot": self.rot, "boxes": self.boxes,
                "children": {k: v.to_json() for k, v in self.children.items()}}


class Builder:
    def __init__(self):
        self.tex = (0, 0)
        self.mirror = False
        self.boxes = []


def box(tex, frm, size, inflate, mirror):
    return {"tex": list(tex), "from": list(frm), "size": list(size), "inflate": inflate, "mirror": mirror}


def humanoid_mesh(deform, y):
    """HumanoidModel.createMesh(deformation, yOffset) of Minecraft 1.21.1."""
    root = Part([], [0, 0, 0, 0, 0, 0])
    root.child("head", [box((0, 0), (-4, -8, -4), (8, 8, 8), deform, False)], [0, y, 0, 0, 0, 0])
    root.child("hat", [box((32, 0), (-4, -8, -4), (8, 8, 8), deform + 0.5, False)], [0, y, 0, 0, 0, 0])
    root.child("body", [box((16, 16), (-4, 0, -2), (8, 12, 4), deform, False)], [0, y, 0, 0, 0, 0])
    root.child("right_arm", [box((40, 16), (-3, -2, -2), (4, 12, 4), deform, False)], [-5, 2 + y, 0, 0, 0, 0])
    root.child("left_arm", [box((40, 16), (-1, -2, -2), (4, 12, 4), deform, True)], [5, 2 + y, 0, 0, 0, 0])
    root.child("right_leg", [box((0, 16), (-2, 0, -2), (4, 12, 4), deform, False)], [-1.9, 12 + y, 0, 0, 0, 0])
    root.child("left_leg", [box((0, 16), (-2, 0, -2), (4, 12, 4), deform, True)], [1.9, 12 + y, 0, 0, 0, 0])
    return root


def run_mesh(insns):
    """Plays createMesh() and returns (root part, (tex w, tex h))."""
    stack, local = [], {}
    tex = None
    for op, arg in insns:
        name = comment(arg)
        if op in ("ldc", "ldc_w", "ldc2_w"):
            stack.append(constant(arg))
        elif op.startswith("fconst_"):
            stack.append(float(op[-1]))
        elif op.startswith("iconst_"):
            stack.append(-1 if op.endswith("m1") else int(op[-1]))
        elif op in ("bipush", "sipush"):
            stack.append(int(arg.split()[0]))
        elif op.startswith("astore"):
            local[op.split("_")[1] if "_" in op else arg.split()[0]] = stack.pop()
        elif op.startswith("aload"):
            stack.append(local[op.split("_")[1] if "_" in op else arg.split()[0]])
        elif op == "getstatic":
            if "CubeDeformation.NONE" in name:
                stack.append(0.0)
            elif "PartPose.ZERO" in name:
                stack.append([0, 0, 0, 0, 0, 0])
            else:
                raise ValueError("getstatic " + name)
        elif op == "new":
            stack.append(("new", name))
        elif op == "dup":
            stack.append(stack[-1])
        elif op == "pop":
            stack.pop()
        elif op == "invokespecial" and "CubeDeformation.\"<init>\":(F)V" in name:
            g = stack.pop(); stack.pop(); stack.pop(); stack.append(g)
        elif op == "invokespecial" and "CubeDeformation.\"<init>\":(FFF)V" in name:
            gz = stack.pop(); stack.pop(); stack.pop(); stack.pop(); stack.pop(); stack.append(gz)
        elif op == "invokevirtual" and "CubeDeformation.extend:(F)" in name:
            g = stack.pop(); base = stack.pop(); stack.append(base + g)
        elif op == "invokestatic" and "HumanoidModel.createMesh" in name:
            y = stack.pop(); g = stack.pop(); stack.append(("mesh", humanoid_mesh(g, y)))
        elif op == "invokevirtual" and "MeshDefinition.getRoot" in name:
            stack.append(stack.pop()[1])
        elif op == "invokestatic" and "CubeListBuilder.create" in name:
            stack.append(Builder())
        elif op == "invokevirtual" and "CubeListBuilder.texOffs" in name:
            v = stack.pop(); u = stack.pop(); b = stack[-1]; b.tex = (u, v)
        elif op == "invokevirtual" and "CubeListBuilder.mirror:()" in name:
            stack[-1].mirror = True
        elif op == "invokevirtual" and "CubeListBuilder.mirror:(Z)" in name:
            flag = stack.pop(); stack[-1].mirror = bool(flag)
        elif op == "invokevirtual" and "CubeListBuilder.addBox:(FFFFFFLnet" in name:
            g = stack.pop(); d = stack.pop(); h = stack.pop(); w = stack.pop(); z = stack.pop(); y = stack.pop(); x = stack.pop()
            b = stack[-1]; b.boxes.append(box(b.tex, (x, y, z), (w, h, d), g, b.mirror))
        elif op == "invokevirtual" and "CubeListBuilder.addBox:(FFFFFF)" in name:
            d = stack.pop(); h = stack.pop(); w = stack.pop(); z = stack.pop(); y = stack.pop(); x = stack.pop()
            b = stack[-1]; b.boxes.append(box(b.tex, (x, y, z), (w, h, d), 0.0, b.mirror))
        elif op == "invokestatic" and "PartPose.offset:(FFF)" in name:
            z = stack.pop(); y = stack.pop(); x = stack.pop(); stack.append([x, y, z, 0, 0, 0])
        elif op == "invokestatic" and "PartPose.rotation:(FFF)" in name:
            z = stack.pop(); y = stack.pop(); x = stack.pop(); stack.append([0, 0, 0, x, y, z])
        elif op == "invokestatic" and "PartPose.offsetAndRotation" in name:
            vals = [stack.pop() for _ in range(6)][::-1]; stack.append(vals)
        elif op == "invokevirtual" and "PartDefinition.addOrReplaceChild" in name:
            pose = stack.pop(); b = stack.pop(); child = stack.pop(); parent = stack.pop()
            stack.append(parent.child(child, b.boxes, pose))
        elif op == "invokevirtual" and "PartDefinition.getChild" in name:
            child = stack.pop(); parent = stack.pop(); stack.append(parent.children[child])
        elif op == "invokestatic" and "LayerDefinition.create" in name:
            h = stack.pop(); w = stack.pop(); mesh = stack.pop(); tex = (w, h); stack.append(("layer", mesh))
        elif op == "areturn":
            layer = stack.pop()
            return layer[1][1], tex
        else:
            raise ValueError(f"createMesh: cannot play {op} {arg}")
    raise ValueError("createMesh did not return")


def model_types(code):
    """{id: {field, textures, male, female}} from ModModelTypes' ids and the initializer's SimpleModelTypes."""
    ids = {}
    pending = None
    for op, arg in code[TYPES_CLASS].get("<clinit>()", []):
        if op in ("ldc", "ldc_w"):
            value = constant(arg)
            pending = value if value != "minecolonies" else pending
        elif op == "putstatic" and pending is not None:
            field = re.search(r"Field (\w+):", arg).group(1)
            if field.endswith("_ID"):
                ids[field] = pending
            pending = None
    types = {}
    current = None
    for op, arg in code[INIT_CLASS].get("init(net.minecraft.client.renderer.entity.EntityRendererProvider$Context)", []):
        name = comment(arg)
        if op == "getstatic" and "ModModelTypes." in name and name.split(".")[-1].split(":")[0].endswith("_ID"):
            field = name.split(".")[-1].split(":")[0]
            current = {"field": field, "textures": None, "models": []}
            types[ids[field]] = current
        elif current is not None and current["textures"] is None and (op.startswith("iconst_") or op == "bipush"):
            current["textures"] = int(op[-1]) if op.startswith("iconst_") else int(arg.split()[0])
        elif current is not None and op == "new" and len(current["models"]) < 2 and "SimpleModelType" not in name:
            current["models"].append(name.split("/")[-1])
    for one in types.values():
        models = one.pop("models")
        one["male"], one["female"] = (models + models)[:2] if models else (None, None)
    return types


def main():
    jar = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("MINECOLONIES_JAR", "")
    if not jar or not os.path.exists(jar):
        raise SystemExit("usage: python -I tools/mc_models.py <minecolonies-1.1.1420-1.21.1.jar> (or set MINECOLONIES_JAR)")
    with zipfile.ZipFile(jar) as z:
        names = [n[:-6].replace("/", ".") for n in z.namelist()
                 if n.startswith(MODEL_PKG) and n.endswith(".class") and n.count("/") == MODEL_PKG.count("/") and "$" not in n]
    code = javap(jar, [TYPES_CLASS, INIT_CLASS, BASE_CLASS] + sorted(names))
    models = {}
    for cls, methods in code.items():
        mesh = methods.get("createMesh()")
        if mesh is None or not (cls.startswith(MODEL_PKG.replace("/", ".")) or cls == BASE_CLASS):
            continue
        try:
            root, tex = run_mesh(mesh)
        except (ValueError, KeyError, IndexError) as broken:
            print(f"  !! {cls}: {broken}")
            continue
        models[cls.split(".")[-1]] = {"tex": list(tex), "parts": {k: v.to_json() for k, v in root.children.items()}}
    types = model_types(code)
    out = paths.out("mc_models.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"types": types, "models": models}, f, indent=1)
    print(f"{len(types)} model types, {len(models)} model classes -> {out}")


if __name__ == "__main__":
    main()
