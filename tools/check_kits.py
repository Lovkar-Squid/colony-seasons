"""Every Decorator kit we ship has the right shape and names things that exist.

A kit the game cannot use is skipped with a warning and nothing else (decor/Kits.java), so a typo in a shipped kit
would never show up as a failure in game: the Decorator would just never make that decoration. This checks the
files in resources/data/*/decorator_kits/ before they ship:

  * the file is JSON with {"output": {"item", "count"}, "ingredients": [{"item" | "tag", "count"}, ...]}, counts
    1 to 64, 1 to 9 ingredients, each with exactly one of item or tag, and optionally "cap": the kit's own stock cap,
    a whole number from 1 to 999 (Q13: the firework rocket kit keeps 24, the crate kit 2; a file without it keeps the
    hut's cap, as the six 0.1.0 kits do);
  * every item is one of our decorations (colony_seasons:<a name below>) or a vanilla item, and every tag is a
    vanilla item tag (both read from the client assets jar, found like tools/check_models.py finds it);
  * no shipped kit takes an ingredient of the csdecorator probe's test kit (#minecraft:wool, minecraft:clay_ball),
    or the probe could not tell its own kit's work from a shipped one;
  * no item comes back into itself: every kit and every crafting recipe we ship is an edge from each ingredient
    (an item tag expanded through the vanilla tags and ours, nested tags too) to its output, and that graph has no
    cycle. A cycle would be an endless loop of the Decorator's assembling, or a dupe if a step ever gave more than it
    took (S9 hostile review, H6).

Run:  python -I tools/check_kits.py
"""
import glob
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

OUR_ITEMS = {"wreath", "garland", "lantern_string", "fairy_lights", "ornament_leaves", "tree_star", "firework_crate"}
MAX_CAP = 999   # decor/Kits.MAX_CAP
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


def item_tags():
    """Every item tag we know, as {"ns:name": [entries]}: the vanilla ones of the client jar, then ours (merged)."""
    out = {}
    with zipfile.ZipFile(paths.lib(MC_ASSET_PATTERN)) as jar:
        for n in jar.namelist():
            if n.startswith("data/minecraft/tags/item/") and n.endswith(".json"):
                out["minecraft:" + n[len("data/minecraft/tags/item/"):-5]] = list(json.loads(jar.read(n)).get("values", []))
    for path in glob.glob(os.path.join(paths.RESOURCES, "data", "*", "tags", "item", "**", "*.json"), recursive=True):
        rel = os.path.relpath(path, paths.RESOURCES).replace(os.sep, "/").split("/")
        name = rel[1] + ":" + "/".join(rel[4:])[:-5]
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        entries = list(data.get("values", []))
        out[name] = entries if data.get("replace") else out.get(name, []) + entries
    return out


def expand(ref, tags, seen=None):
    """An item id, or every item of a tag (#ns:name, nested tags followed once)."""
    if isinstance(ref, dict):
        ref = ref.get("id", "")
    ref = str(ref)
    if not ref.startswith("#"):
        return {ref}
    seen = seen if seen is not None else set()
    name = ref[1:]
    if name in seen:
        return set()
    seen.add(name)
    found = set()
    for entry in tags.get(name, []):
        found |= expand(entry, tags, seen)
    return found


def ingredient_items(ing, tags):
    """The items one ingredient of a kit or a recipe accepts."""
    if isinstance(ing, list):
        found = set()
        for one in ing:
            found |= ingredient_items(one, tags)
        return found
    if not isinstance(ing, dict):
        return set()
    if "item" in ing:
        return {str(ing["item"])}
    if "tag" in ing:
        return expand("#" + str(ing["tag"]).lstrip("#"), tags)
    return set()


def edges():
    """{ingredient item: {output items}} over every kit and every crafting recipe we ship."""
    tags = item_tags()
    graph = {}

    def add(sources, output):
        for src in sources:
            graph.setdefault(src, set()).add(output)

    for path in glob.glob(os.path.join(paths.RESOURCES, "data", "*", "decorator_kits", "*.json")):
        with open(path, encoding="utf-8") as f:
            kit = json.load(f)
        out = kit.get("output", {}).get("item")
        for ing in kit.get("ingredients", []):
            add(ingredient_items(ing, tags), out)
    for path in glob.glob(os.path.join(paths.RESOURCES, "data", "*", "recipe", "*.json")):
        with open(path, encoding="utf-8") as f:
            recipe = json.load(f)
        result = recipe.get("result", {})
        out = result.get("id") or result.get("item")
        for ing in list(recipe.get("key", {}).values()) + list(recipe.get("ingredients", [])):
            add(ingredient_items(ing, tags), out)
    return graph


def find_cycle(graph):
    """One cycle as a list of items, or None."""
    state = {}

    def visit(node, path):
        if state.get(node) == 2:
            return None
        if state.get(node) == 1:
            return path[path.index(node):] + [node]
        state[node] = 1
        path.append(node)
        for nxt in sorted(graph.get(node, ())):
            found = visit(nxt, path)
            if found:
                return found
        path.pop()
        state[node] = 2
        return None

    for start in sorted(graph):
        found = visit(start, [])
        if found:
            return found
    return None


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
    if "cap" in kit:
        cap = kit["cap"]
        if not isinstance(cap, int) or isinstance(cap, bool) or not 1 <= cap <= MAX_CAP:
            problems.append(f"cap must be a whole number from 1 to {MAX_CAP}, got {cap!r}")
    for extra in set(kit) - {"output", "ingredients", "cap"}:
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
    graph = edges()
    cycle = find_cycle(graph)
    if cycle:
        print("  !! kits and recipes: an item comes back into itself: " + " -> ".join(cycle))
        bad += 1
    print(f"kits: {len(files)} file(s) checked, {sum(len(v) for v in graph.values())} ingredient-to-output edges without a cycle"
          if not cycle else f"kits: {len(files)} file(s) checked")
    print(f"kits: {bad} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
