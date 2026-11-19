"""Writes the data files of the decoration blocks (chunk S2): a loot table per block (each drops itself), a
crafting-table recipe per block and the block tags.

Run:  python -I tools/gen_blockdata.py      (plain json; the files are committed)
Output: resources/data/colony_seasons/{loot_table/blocks,recipe}/ and resources/data/minecraft/tags/block/.

The recipes follow the Decorator's kit examples of design section 4 (wreath x2 = 4 leaves + 1 string + 1 red dye;
garland x4 = 4 leaves + 2 string; lantern string x4 = 2 string + 2 torches + 1 paper; fairy lights x4 = 2 string +
1 glass pane + 1 glowstone dust; wrapped gifts from paper, string and dye); the tree pieces and the crate are made
from what the design's tree and show ask for (spruce leaves, gold, glowstone, firework rockets).
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

NS = "colony_seasons"
DATA = paths.res("data")

BLOCKS = ["wreath", "garland", "lantern_string", "fairy_lights", "ornament_leaves", "tinsel_leaves", "tree_star",
          "large_star", "gift_pile", "firework_crate", "festival_marker"]

# id -> (pattern, key, count); key values are item ids or #tags
RECIPES = {
    "wreath": (["LSL", "L L", " D "], {"L": "#minecraft:leaves", "S": "minecraft:string", "D": "minecraft:red_dye"}, 2),
    "garland": (["SLS", "LLL"], {"L": "#minecraft:leaves", "S": "minecraft:string"}, 4),
    "lantern_string": (["SPS", "T T"], {"S": "minecraft:string", "P": "minecraft:paper", "T": "minecraft:torch"}, 4),
    "fairy_lights": (["SGS", " D "], {"S": "minecraft:string", "G": "minecraft:glass_pane",
                                      "D": "minecraft:glowstone_dust"}, 4),
    "ornament_leaves": ([" N ", "LRL", "LDL"], {"N": "minecraft:gold_nugget", "L": "minecraft:spruce_leaves",
                                                "R": "minecraft:red_dye", "D": "minecraft:glowstone_dust"}, 4),
    "tinsel_leaves": (["NSN", "LDL", "L L"], {"N": "minecraft:gold_nugget", "S": "minecraft:string",
                                              "L": "minecraft:spruce_leaves", "D": "minecraft:glowstone_dust"}, 4),
    "tree_star": ([" N ", "NDN", "N N"], {"N": "minecraft:gold_nugget", "D": "minecraft:glowstone_dust"}, 1),
    "large_star": ([" G ", "GSG", " D "], {"G": "minecraft:gold_ingot", "S": f"{NS}:tree_star",
                                           "D": "minecraft:glowstone_dust"}, 1),
    "gift_pile": (["PSP", "RGB"], {"P": "minecraft:paper", "S": "minecraft:string", "R": "minecraft:red_dye",
                                   "G": "minecraft:green_dye", "B": "minecraft:blue_dye"}, 1),
    "firework_crate": ([" F ", "FBF"], {"F": "minecraft:firework_rocket", "B": "minecraft:barrel"}, 1),
    "festival_marker": (["W", "S", "S"], {"W": "#minecraft:wool", "S": "minecraft:stick"}, 1),
}

# block tag -> our blocks in it
TAGS = {
    "leaves": ["ornament_leaves", "tinsel_leaves"],
    "mineable/hoe": ["ornament_leaves", "tinsel_leaves"],
    "mineable/axe": ["firework_crate", "festival_marker"],
}

# only the lower half of the two-block star drops the item
HALF_LOWER = {"large_star"}


def write(rel, data):
    path = os.path.join(DATA, *rel.split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    return path


def loot(block):
    pool = {"rolls": 1, "bonus_rolls": 0,
            "entries": [{"type": "minecraft:item", "name": f"{NS}:{block}"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}]}
    if block in HALF_LOWER:
        pool["conditions"].append({"condition": "minecraft:block_state_property", "block": f"{NS}:{block}",
                                   "properties": {"half": "lower"}})
    return {"type": "minecraft:block", "pools": [pool], "random_sequence": f"{NS}:blocks/{block}"}


def ingredient(value):
    return {"tag": value[1:]} if value.startswith("#") else {"item": value}


def recipe(block):
    pattern, key, count = RECIPES[block]
    return {"type": "minecraft:crafting_shaped", "category": "misc", "group": f"{NS}:decorations",
            "pattern": pattern, "key": {k: ingredient(v) for k, v in key.items()},
            "result": {"id": f"{NS}:{block}", "count": count}}


def main():
    n = 0
    for b in BLOCKS:
        write(f"{NS}/loot_table/blocks/{b}.json", loot(b))
        write(f"{NS}/recipe/{b}.json", recipe(b))
        n += 2
    for tag, blocks in TAGS.items():
        write(f"minecraft/tags/block/{tag}.json", {"replace": False, "values": [f"{NS}:{b}" for b in blocks]})
        n += 1
    print(f"{n} data files written under {os.path.relpath(DATA, paths.ROOT)}")


if __name__ == "__main__":
    main()
