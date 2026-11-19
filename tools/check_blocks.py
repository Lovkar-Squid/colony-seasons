"""Does every decoration block have all its files, and do they agree with each other?

A block the game knows but whose files are incomplete fails quietly: no blockstate or model means a magenta
cube, no loot table means it drops nothing, a missing lang line shows the raw key, a recipe naming an item that
does not exist is dropped with one log line. So for every block registered in SeasonsBlocks.java this checks:

  * its blockstate exists and every model it names exists (ours under resources/, vanilla in the client jar);
  * every model's parent exists, its render_type is a real one, its elements stay inside -16..32, and any
    neoforge_data has block_light / sky_light between 0 and 15;
  * a variant with lit=true points at a model with a glowing element, a variant with lit=false (or without lit) at
    one without (the lit look must glow at night and the unlit one must not);
  * its item model exists and resolves; its lang line block.colony_seasons.<id> exists;
  * its loot table drops colony_seasons:<id>; a crafting recipe makes it, and every ingredient of every recipe is
    a real item (vanilla: models/item in the client jar; ours: registered) or a real item tag;
  * every block tag file names only registered blocks;
  * every model of ours is used by a blockstate or an item model.

Run:  python -I tools/check_blocks.py   (needs the client assets jar, see tools/paths.py)
"""
import glob
import json
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

NS = "colony_seasons"
REGISTRATION = os.path.join(paths.ROOT, "src", "me", "lovkar", "seasons", "block", "SeasonsBlocks.java")
ASSETS = paths.res("assets", NS)
DATA = paths.res("data")
RENDER_TYPES = {"minecraft:solid", "minecraft:cutout", "minecraft:cutout_mipped", "minecraft:translucent",
                "minecraft:tripwire", "solid", "cutout", "cutout_mipped", "translucent", "tripwire"}

problems = []


def bad(msg):
    problems.append(msg)
    print("  !! " + msg)


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


class Vanilla:
    def __init__(self):
        jar = paths.lib(os.environ.get("MC_ASSETS_JAR", "client-*-extra.jar"))
        self.zip = zipfile.ZipFile(jar)
        self.names = set(self.zip.namelist())

    def has(self, entry):
        return entry in self.names

    def json(self, entry):
        return json.loads(self.zip.read(entry).decode("utf-8"))


def split(ref, default="minecraft"):
    return ref.split(":", 1) if ":" in ref else (default, ref)


def model_json(vanilla, ref):
    ns, path = split(ref)
    if ns == NS:
        p = os.path.join(ASSETS, "models", *path.split("/")) + ".json"
        return load(p) if os.path.exists(p) else None
    entry = f"assets/{ns}/models/{path}.json"
    return vanilla.json(entry) if vanilla.has(entry) else None


def glows(model):
    return any("neoforge_data" in e and (e["neoforge_data"].get("block_light", 0) > 0
                                         or e["neoforge_data"].get("sky_light", 0) > 0)
               for e in model.get("elements") or [])


def check_model(vanilla, ref, where, seen):
    if ref in seen:
        return seen[ref]
    model = model_json(vanilla, ref)
    seen[ref] = model
    if model is None:
        bad(f"{where}: model {ref} does not exist")
        return None
    parent = model.get("parent")
    if parent and not split(parent)[1].startswith("builtin/"):
        check_model(vanilla, parent if ":" in parent else "minecraft:" + parent, f"model {ref}", seen)
    rt = model.get("render_type")
    if rt is not None and rt not in RENDER_TYPES:
        bad(f"model {ref}: render_type {rt} is not one of {sorted(RENDER_TYPES)}")
    for i, e in enumerate(model.get("elements") or []):
        for v in e["from"] + e["to"]:
            if not -16 <= v <= 32:
                bad(f"model {ref}: element {i} reaches {v}, outside -16..32")
        if "forge_data" in e:
            bad(f"model {ref}: element {i} has forge_data, which NeoForge 21.1 refuses (it wants neoforge_data)")
        nd = e.get("neoforge_data")
        if nd is not None:
            for k in ("block_light", "sky_light"):
                if k in nd and not (isinstance(nd[k], int) and 0 <= nd[k] <= 15):
                    bad(f"model {ref}: element {i} {k} = {nd[k]} (0..15)")
            unknown = set(nd) - {"block_light", "sky_light", "ambient_occlusion", "color"}
            if unknown:
                bad(f"model {ref}: element {i} neoforge_data has unknown keys {sorted(unknown)}")
        if not e.get("faces"):
            bad(f"model {ref}: element {i} has no faces")
    return model


def main():
    vanilla = Vanilla()
    with open(REGISTRATION, encoding="utf-8") as f:
        java = f.read()
    blocks = re.findall(r'BLOCKS\.register\(\s*"([a-z0-9_]+)"', java)
    if not blocks:
        print("no blocks registered - nothing to check")
        return 0
    lang = load(os.path.join(ASSETS, "lang", "en_us.json"))
    seen = {}
    used_models = set()

    recipes = {}
    for p in glob.glob(os.path.join(DATA, NS, "recipe", "*.json")):
        recipes[os.path.basename(p)[:-5]] = load(p)
    made = {r.get("result", {}).get("id") for r in recipes.values()}

    for b in blocks:
        bs_path = os.path.join(ASSETS, "blockstates", b + ".json")
        if not os.path.exists(bs_path):
            bad(f"{b}: no blockstate")
        else:
            bs = load(bs_path)
            variants = bs.get("variants", {})
            for key, v in variants.items():
                props = dict(kv.split("=") for kv in key.split(",")) if key else {}
                for one in (v if isinstance(v, list) else [v]):
                    ref = one["model"]
                    used_models.add(ref)
                    m = check_model(vanilla, ref, f"{b}[{key}]", seen)
                    if m is None:
                        continue
                    lit = props.get("lit")
                    if lit == "true" and not glows(m):
                        bad(f"{b}[{key}]: lit, but model {ref} has no glowing element")
                    if lit != "true" and glows(m):
                        bad(f"{b}[{key}]: not lit, but model {ref} glows")
            for part in bs.get("multipart", []):
                for one in (part["apply"] if isinstance(part["apply"], list) else [part["apply"]]):
                    used_models.add(one["model"])
                    check_model(vanilla, one["model"], f"{b} multipart", seen)
        item_ref = f"{NS}:item/{b}"
        if check_model(vanilla, item_ref, f"{b} item", seen) is not None:
            used_models.add(item_ref)
            parent = seen[item_ref].get("parent", "")
            if parent.startswith(NS + ":"):
                used_models.add(parent)
        if f"block.{NS}.{b}" not in lang:
            bad(f"{b}: no lang line block.{NS}.{b}")
        loot_path = os.path.join(DATA, NS, "loot_table", "blocks", b + ".json")
        if not os.path.exists(loot_path):
            bad(f"{b}: no loot table")
        elif f"{NS}:{b}" not in json.dumps(load(loot_path)):
            bad(f"{b}: its loot table does not drop {NS}:{b}")
        if f"{NS}:{b}" not in made:
            bad(f"{b}: no recipe makes it")

    for name, r in sorted(recipes.items()):
        for k, ing in r.get("key", {}).items():
            for one in (ing if isinstance(ing, list) else [ing]):
                if "item" in one:
                    ns, path = split(one["item"])
                    ok = path in blocks if ns == NS else vanilla.has(f"assets/{ns}/models/item/{path}.json")
                    if not ok:
                        bad(f"recipe {name}: '{k}' = item {one['item']} does not exist")
                elif "tag" in one:
                    ns, path = split(one["tag"])
                    if ns != "minecraft" or not vanilla.has(f"data/minecraft/tags/item/{path}.json"):
                        bad(f"recipe {name}: '{k}' = tag #{one['tag']} is not a vanilla item tag")
        res = r.get("result", {}).get("id", "")
        if split(res)[0] == NS and split(res)[1] not in blocks:
            bad(f"recipe {name}: makes {res}, which is not registered")

    for p in glob.glob(os.path.join(DATA, "*", "tags", "block", "**", "*.json"), recursive=True):
        for v in load(p).get("values", []):
            ident = v["id"] if isinstance(v, dict) else v
            ns, path = split(ident)
            if ns == NS and path not in blocks:
                bad(f"tag {os.path.relpath(p, DATA)}: {ident} is not registered")

    for p in glob.glob(os.path.join(ASSETS, "models", "block", "*.json")):
        ref = f"{NS}:block/{os.path.basename(p)[:-5]}"
        if ref not in used_models:
            bad(f"model {ref} is used by no blockstate and no item")

    print(f"  {len(blocks)} block(s), {len(seen)} model(s), {len(recipes)} recipe(s) checked, {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    print("blocks:")
    sys.exit(main())
