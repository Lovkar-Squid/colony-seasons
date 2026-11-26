"""The festival market's price file (chunk Q10, docs/town-square-design.md 7.2 a and 7.3).

resources/data/mctradepost/item_values.json is read by Trade Post for MineColonies (and by nothing else). It must:
  - never set "replace" to true (that would wipe the player's whole price list), and be a plain "values" map;
  - list exactly the outputs of the six kits in resources/data/colony_seasons/decorator_kits, and nothing else: no
    gift, gift pile, firework crate, festival marker, large star, tinsel leaves or hut block (a price on a fixture would
    turn a set swap into money);
  - give each good an integer price of at least 1 and below 1000 (a Trade Coin is 1000 points).
When the Trade Post jar is there (the TRADEPOST_JAR environment variable names it) each price is also held against that
jar's own list: price x kit count must not be more than 1.25 x the summed value of the kit's ingredients (a tag
ingredient counts at its cheapest member), so crafting and selling is a trade, not a mint. Without the jar that part is
reported as skipped.

  python -I tools/check_market.py [repo]
"""
import json
import os
import pathlib
import sys
import zipfile

NOT_PRICED = ("gift", "gift_pile", "firework_crate", "festival_marker", "large_star", "tinsel_leaves", "storage", "hut")
MAX_MARKUP = 1.25


def tag_members(tag, values):
    """Items a tag stands for, as far as the price list knows them (only vanilla leaves are used by our kits)."""
    if tag == "minecraft:leaves":
        return [k for k in values if k.startswith("minecraft:") and k.endswith("_leaves")]
    return []


def check(repo):
    repo = pathlib.Path(repo)
    errors = []
    path = repo / "resources/data/mctradepost/item_values.json"
    if not path.exists():
        print(f"  {path}: missing")
        return 1
    doc = json.loads(path.read_text(encoding="utf-8"))
    if doc.get("replace") is not False:
        errors.append('"replace" must be false (true would wipe the whole price list)')
    values = doc.get("values")
    if not isinstance(values, dict):
        errors.append('"values" must be an object')
        values = {}
    outputs = {}
    kits = {}
    for kit in sorted((repo / "resources/data/colony_seasons/decorator_kits").glob("*.json")):
        data = json.loads(kit.read_text(encoding="utf-8"))
        outputs[data["output"]["item"]] = data["output"].get("count", 1)
        kits[data["output"]["item"]] = data
    if set(values) != set(outputs):
        errors.append(f"the keys must be exactly the kits' outputs: extra {sorted(set(values) - set(outputs))}, "
                      f"missing {sorted(set(outputs) - set(values))}")
    for key, price in values.items():
        if not key.startswith("colony_seasons:"):
            errors.append(f"{key}: only colony_seasons: keys may be listed")
        name = key.split(":", 1)[-1]
        if any(word in name for word in NOT_PRICED):
            errors.append(f"{key}: a fixture, the gift or a hut block has no price")
        if not isinstance(price, int) or isinstance(price, bool) or not 1 <= price < 1000:
            errors.append(f"{key}: the price must be an integer from 1 to 999, got {price!r}")

    jar = os.environ.get("TRADEPOST_JAR", "")
    if jar and pathlib.Path(jar).exists():
        with zipfile.ZipFile(jar) as z:
            theirs = json.loads(z.read("data/mctradepost/item_values.json").decode("utf-8")).get("values", {})
        for key, price in values.items():
            kit = kits.get(key)
            if kit is None or not isinstance(price, int):
                continue
            total = 0
            for ing in kit["ingredients"]:
                if "item" in ing:
                    unit = theirs.get(ing["item"])
                else:
                    members = [theirs[m] for m in tag_members(ing["tag"], theirs) if m in theirs]
                    unit = min(members) if members else None
                if unit is None:
                    errors.append(f"{key}: no value for the ingredient {ing.get('item') or ing.get('tag')} in Trade Post's list")
                    break
                total += unit * ing["count"]
            else:
                ceiling = int(MAX_MARKUP * total)
                if price * outputs[key] > ceiling:
                    errors.append(f"{key}: {price} x {outputs[key]} = {price * outputs[key]} is above the ceiling {ceiling} "
                                  f"(ingredients {total} + 25 %)")
        print(f"  prices held against {pathlib.Path(jar).name}")
    else:
        print("  prices against Trade Post's own list: skipped (TRADEPOST_JAR not set or not there)")

    for e in errors:
        print("  " + e)
    if not errors:
        print(f"  item_values.json: {len(values)} priced goods, replace false")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(check(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent))
