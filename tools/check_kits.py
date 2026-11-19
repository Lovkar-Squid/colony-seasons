"""Every Decorator kit we ship has the right shape and names things that exist.

A kit the game cannot use is skipped with a warning and nothing else (decor/Kits.java), so a typo in a shipped kit
would never show up as a failure in game: the Decorator would just never make that decoration. This checks the
files in resources/data/*/decorator_kits/ before they ship:

  * the file is JSON with {"output": {"item", "count"}, "ingredients": [{"item" | "tag", "count"}, ...]}, counts
    1 to 64, 1 to 9 ingredients, each with exactly one of item or tag;
  * every item is one of our decorations (colony_seasons:<a name below>) or a vanilla item, and every tag is a
    vanilla item tag (both read from the client assets jar, found like tools/check_models.py finds it);
  * no shipped kit takes an ingredient of the csdecorator probe's test kit (#minecraft:wool, minecraft:clay_ball),
    or the probe could not tell its own kit's work from a shipped one.

Run:  python -I tools/check_kits.py
"""
import glob
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

OUR_ITEMS = {"wreath", "garland", "lantern_string", "fairy_lights", "ornament_leaves", "tree_star"}
PROBE_INGREDIENTS = {"tag:minecraft:wool", "item:minecraft:clay_ball"}
MC_ASSET_PATTERN = os.environ.get("MC_ASSETS_JAR", "client-*-extra.jar")


def vanilla():
    with zipfile.ZipFile(paths.lib(MC_ASSET_PATTERN)) as jar:
        names = jar.namelist()
    items = {n[len("assets/minecraft/models/item/"):-5] for n in names
             if n.startswith("assets/minecraft/models/item/") and n.endswith(".json")}
    tags = {n[len("data/minecraft/tags/item/"):-5] for n in names
            if n.startswith("data/minecraft/tags/item/") and n.endswith(".json")}
    return items, tags


def item_known(ident, items):
    ns, _, path = ident.partition(":")
    if not _:
        return False
    if ns == "colony_seasons":
        return path in OUR_ITEMS
    return ns == "minecraft" and path in items


def count_ok(obj):
    value = obj.get("count", 1)
    return isinstance(value, int) and not isinstance(value, bool) and 1 <= value <= 64


def check_file(path, items, tags):
    problems = []
    try:
        with open(path, encoding="utf-8") as f:
            kit = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        return [f"not valid JSON: {e}"]
    if not isinstance(kit, dict):
        return ["not a JSON object"]
    out = kit.get("output")
    if not isinstance(out, dict) or not isinstance(out.get("item"), str):
        problems.append("no output item")
    else:
        if not item_known(out["item"], items):
            problems.append(f"unknown output item {out['item']}")
        if not count_ok(out):
            problems.append("output count must be 1 to 64")
    ings = kit.get("ingredients")
    if not isinstance(ings, list) or not 1 <= len(ings) <= 9:
        problems.append("1 to 9 ingredients")
        ings = ings if isinstance(ings, list) else []
    for ing in ings:
        if not isinstance(ing, dict) or (("item" in ing) == ("tag" in ing)):
            problems.append(f"an ingredient must name exactly one of item or tag: {ing}")
            continue
        if not count_ok(ing):
            problems.append(f"ingredient count must be 1 to 64: {ing}")
        if "item" in ing:
            key = "item:" + str(ing["item"])
            if not item_known(str(ing["item"]), items):
                problems.append(f"unknown item {ing['item']}")
        else:
            tag = str(ing["tag"]).lstrip("#")
            key = "tag:" + tag
            ns, _, name = tag.partition(":")
            if ns != "minecraft" or name not in tags:
                problems.append(f"unknown item tag #{tag}")
        if key in PROBE_INGREDIENTS:
            problems.append(f"uses {key[key.index(':') + 1:]}, an ingredient of the probe's test kit")
    for extra in set(kit) - {"output", "ingredients"}:
        problems.append(f"unknown key {extra}")
    return problems


def main():
    files = sorted(glob.glob(os.path.join(paths.RESOURCES, "data", "*", "decorator_kits", "*.json")))
    if not files:
        print("kits: none shipped")
        return 0
    items, tags = vanilla()
    bad = 0
    for path in files:
        rel = os.path.relpath(path, paths.ROOT).replace(os.sep, "/")
        problems = check_file(path, items, tags)
        for p in problems:
            print(f"  !! {rel}: {p}")
        bad += len(problems)
    print(f"kits: {len(files)} file(s) checked, {bad} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
