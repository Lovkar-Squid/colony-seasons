"""Static checks of the winter clothes data (chunk S6), no game needed.

  * hat_models.json has the shape HatModels reads, and every model id in it is a citizen model id of the
    MineColonies jar this mod is built for (read out of ModModelTypes.class), never the player-skin model;
  * every texture the clothes code names exists (the %s paths of ClothesRules expanded with its colours and,
    for the scarf, the festive one), each at the size its model declares (TEX_WIDTH x TEX_HEIGHT), and no texture
    in textures/entity/ is left that nothing names;
  * every box of ScarfModel and WoolHatModel has its UV block inside the texture;
  * the festival id that brings out the festive scarf is the server's (SeasonsConfig.WINTER_FESTIVAL);
  * no texture is shipped into MineColonies' 'nether' style folder (design 7, [B]).

Run:  python -I tools/check_clothes.py [folder with the MineColonies jar]
The MineColonies jar is minecolonies-<lowest version the mods.toml accepts>*.jar in that folder, or $MINECOLONIES_JAR.
"""
import glob
import json
import os
import re
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # also works under python -I
import paths  # noqa: E402

SRC = os.path.join(paths.ROOT, "src", "me", "lovkar", "seasons")
CLIENT = os.path.join(SRC, "client")
ASSETS = paths.res("assets", "colony_seasons")
HAT_MODELS = os.path.join(ASSETS, "clothes", "hat_models.json")
TYPES_CLASS = "com/minecolonies/api/client/render/modeltype/ModModelTypes.class"
NOT_A_HEAD = {"custom"}       # the player-skin model: MineColonies hides the citizen head for it


def class_strings(data):
    """The string constants (CONSTANT_String) of a class file: the literals its code loads with ldc."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    count = struct.unpack(">H", data[8:10])[0]
    utf8, strings = {}, []
    i, pos = 1, 10
    while i < count:
        tag = data[pos]
        if tag == 1:
            n = struct.unpack(">H", data[pos + 1:pos + 3])[0]
            utf8[i] = data[pos + 3:pos + 3 + n].decode("utf-8", "replace")
            pos += 3 + n
        elif tag in (3, 4):
            pos += 5
        elif tag in (5, 6):
            pos += 9
            i += 1
        elif tag in (7, 16, 19, 20):
            pos += 3
        elif tag == 8:
            strings.append(struct.unpack(">H", data[pos + 1:pos + 3])[0])
            pos += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 5
        elif tag == 15:
            pos += 4
        else:
            raise ValueError(f"unknown constant tag {tag}")
        i += 1
    return [utf8[s] for s in strings]


def minecolonies_jar(folder):
    jar = os.environ.get("MINECOLONIES_JAR", "")
    if jar and os.path.exists(jar):
        return jar
    toml = open(paths.res("META-INF", "neoforge.mods.toml"), encoding="utf-8").read()
    want = re.search(r'modId="minecolonies".*?versionRange="\[([^,\]]+)', toml, re.S)
    version = want.group(1) if want else ""
    hits = sorted(glob.glob(os.path.join(folder or paths.LIBS, f"minecolonies-{version}*.jar")))
    if not hits:
        raise FileNotFoundError(f"no minecolonies-{version}*.jar in {folder or paths.LIBS} (pass the folder or set MINECOLONIES_JAR)")
    return hits[0]


def citizen_model_ids(jar):
    with zipfile.ZipFile(jar) as z:
        consts = class_strings(z.read(TYPES_CLASS))
    return {s for s in consts if re.fullmatch(r"[a-z_]+", s) and s != "minecolonies"}


def java_constant(text, name):
    m = re.search(name + r'\s*=\s*"([^"]*)"', text)
    return m.group(1) if m else None


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    return struct.unpack(">II", head[16:24])


def main():
    bad = []
    folder = sys.argv[1] if len(sys.argv) > 1 else None

    # ---- the hat allow-list
    jar = minecolonies_jar(folder)
    ids = citizen_model_ids(jar)
    data = json.load(open(HAT_MODELS, encoding="utf-8"))
    models = data.get("models")
    if not isinstance(models, dict) or not models:
        bad.append("hat_models.json: no \"models\" object")
        models = {}
    for key, genders in models.items():
        ns, _, path = key.partition(":")
        if ns != "minecolonies":
            bad.append(f"hat_models.json: {key} is not a MineColonies model id")
        elif path not in ids:
            bad.append(f"hat_models.json: {key} is not a citizen model of {os.path.basename(jar)}")
        elif path in NOT_A_HEAD:
            bad.append(f"hat_models.json: {key} draws a player skin, not a citizen head")
        if not isinstance(genders, list) or not genders or any(g not in ("male", "female") for g in genders):
            bad.append(f"hat_models.json: {key} needs a list of male / female, has {genders!r}")
    print(f"  hat allow-list: {len(models)} model(s), checked against {len(ids)} citizen model ids of {os.path.basename(jar)}")

    # ---- textures the code names
    rules = open(os.path.join(CLIENT, "ClothesRules.java"), encoding="utf-8").read()
    colours = re.findall(r'"(\w+)"', re.search(r"COLOURS = List\.of\(([^)]*)\)", rules).group(1))
    festive = java_constant(rules, "FESTIVE")
    templates = {"SCARF_TEXTURE": colours + [festive], "HAT_TEXTURE": colours}
    named = {}
    for java in glob.glob(os.path.join(CLIENT, "*.java")):
        text = open(java, encoding="utf-8").read()
        for literal in re.findall(r'"(textures/[^"]+\.png)"', text):
            const = next((n for n in templates if java_constant(text, n) == literal), None)
            for one in ([literal % c for c in templates[const]] if const else [literal]):
                named[one] = os.path.basename(java)
    sizes = {}
    for model in ("ScarfModel", "WoolHatModel"):
        text = open(os.path.join(CLIENT, model + ".java"), encoding="utf-8").read()
        w = int(re.search(r"TEX_WIDTH = (\d+);", text).group(1))
        h = int(re.search(r"TEX_HEIGHT = (\d+);", text).group(1))
        sizes[model] = (w, h)
        for u, v, args in re.findall(r"\.texOffs\((\d+), (\d+)\)\.addBox\(([^n)]*)", text):
            nums = [float(a.strip().rstrip("Ff")) for a in args.split(",") if a.strip()]
            bw, bh, bd = (int(round(n)) for n in nums[3:6])
            if int(u) + 2 * (bd + bw) > w or int(v) + bd + bh > h:
                bad.append(f"{model}: the box at texOffs({u}, {v}) needs {2 * (bd + bw)}x{bd + bh} texels and runs off the {w}x{h} texture")
    for path, where in sorted(named.items()):
        file = os.path.join(ASSETS, *path.split("/"))
        if not os.path.exists(file):
            bad.append(f"{where} names {path}, which does not exist")
            continue
        model = "WoolHatModel" if os.path.basename(path).startswith("wool_hat_") else "ScarfModel"
        if png_size(file) != sizes[model]:
            bad.append(f"{path} is {png_size(file)}, {model} declares {sizes[model]}")
    shipped = {"textures/entity/" + os.path.basename(p) for p in glob.glob(os.path.join(ASSETS, "textures", "entity", "*.png"))}
    for spare in sorted(shipped - set(named)):
        bad.append(f"{spare} is shipped but no clothes code names it")
    print(f"  textures: {len(named)} named by the code, {len(shipped)} shipped in textures/entity/")

    # ---- the festival id is the server's
    server = java_constant(open(os.path.join(SRC, "SeasonsConfig.java"), encoding="utf-8").read(), "WINTER_FESTIVAL")
    if java_constant(rules, "WINTER_FESTIVAL") != server:
        bad.append(f"ClothesRules.WINTER_FESTIVAL is not SeasonsConfig.WINTER_FESTIVAL ('{server}')")

    # ---- nothing in MineColonies' nether style folder
    nether = paths.res("assets", "minecolonies", "textures", "entity", "citizen", "nether")
    if os.path.exists(nether):
        bad.append("resources/assets/minecolonies/textures/entity/citizen/nether/ exists: MineColonies' late-October style is theirs")

    for line in bad:
        print("  !! " + line)
    print("  clothes data ok" if not bad else f"  {len(bad)} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
