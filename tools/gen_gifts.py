"""Writes the gift loot tables (chunk S7): resources/data/colony_seasons/loot_table/gifts/*.json.

A gift (festival/Gifts.java) opens into one roll of its table, so pack makers can change what is in it by shipping
their own file at the same path in a datapack. The tables here are the mod's defaults:

  winter_daily     the small gift of every day of the winter window: one thing, mostly food and a little gold
  winter_festival  the better gift of the Winter Festival days: two things, our decorations among them, and a small
                   chance of a diamond
  new_year         the gift of the New Year days: a few festival rockets and one small thing

Run:  python -I tools/gen_gifts.py          writes the files (the same bytes on every run)
      python -I tools/gen_gifts.py --check  only says whether the committed files are in step (a step of checks.sh)
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

OUT = paths.res("data", "colony_seasons", "loot_table", "gifts")
NS = "colony_seasons"

# festival colours: red, gold, green, warm white, blue
RED, GOLD, GREEN, WHITE, BLUE = 0xD8322E, 0xF0C030, 0x3FA34D, 0xF4F1EA, 0x3B6FD0


def item(name, weight, low=1, high=None, functions=None):
    entry = {"type": "minecraft:item", "name": name, "weight": weight}
    funcs = list(functions or [])
    if high is not None and (low, high) != (1, 1):
        funcs.insert(0, {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": low, "max": high}})
    if funcs:
        entry["functions"] = funcs
    return entry


def rocket(weight, colours, fade, shape, low, high):
    explosion = {"shape": shape, "colors": colours, "fade_colors": fade, "has_trail": True, "has_twinkle": shape == "star"}
    return item("minecraft:firework_rocket", weight, low, high, [{
        "function": "minecraft:set_components",
        "components": {"minecraft:fireworks": {"flight_duration": 1, "explosions": [explosion]}},
    }])


def table(name, pools):
    return {"type": "minecraft:gift", "pools": pools, "random_sequence": f"{NS}:gifts/{name}"}


SMALL = [
    item("minecraft:cookie", 20, 3, 6),
    item("minecraft:pumpkin_pie", 10),
    item("minecraft:sweet_berries", 10, 4, 8),
    item("minecraft:glow_berries", 5, 3, 5),
    item("minecraft:apple", 8, 2, 3),
    item("minecraft:gold_nugget", 10, 3, 6),
    item("minecraft:emerald", 5),
    item("minecraft:snowball", 6, 4, 8),
    item("minecraft:red_candle", 3),
    item("minecraft:green_candle", 3),
    item("minecraft:white_candle", 3),
    item("minecraft:lantern", 4),
    item(f"{NS}:wreath", 4),
]

TABLES = {
    "winter_daily": table("winter_daily", [{"rolls": 1, "bonus_rolls": 0, "entries": SMALL}]),
    "winter_festival": table("winter_festival", [
        {"rolls": 2, "bonus_rolls": 0, "entries": [
            item("minecraft:cookie", 12, 6, 10),
            item("minecraft:pumpkin_pie", 10, 2, 2),
            item("minecraft:cake", 6),
            item("minecraft:golden_carrot", 6, 2, 4),
            item("minecraft:emerald", 8, 2, 4),
            item("minecraft:gold_ingot", 6, 1, 2),
            item(f"{NS}:wreath", 5),
            item(f"{NS}:garland", 5, 2, 4),
            item(f"{NS}:lantern_string", 4, 2, 4),
            item(f"{NS}:fairy_lights", 4, 2, 4),
            item(f"{NS}:tree_star", 2),
        ]},
        {"rolls": 1, "bonus_rolls": 0, "entries": [item("minecraft:diamond", 1)],
         "conditions": [{"condition": "minecraft:random_chance", "chance": 0.15}]},
    ]),
    "new_year": table("new_year", [
        {"rolls": 1, "bonus_rolls": 0, "entries": [
            rocket(1, [GOLD, RED], [WHITE], "large_ball", 3, 5),
            rocket(1, [GREEN, BLUE], [GOLD], "star", 3, 5),
            rocket(1, [RED, WHITE], [GOLD], "burst", 3, 5),
        ]},
        {"rolls": 1, "bonus_rolls": 0, "entries": SMALL},
    ]),
}


def text(data):
    return json.dumps(data, indent=2) + "\n"


def main(check):
    os.makedirs(OUT, exist_ok=True)
    stale = 0
    for name, data in TABLES.items():
        path = os.path.join(OUT, name + ".json")
        want = text(data)
        have = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if check:
            if have != want:
                print(f"  !! {os.path.relpath(path, paths.ROOT)} is not what tools/gen_gifts.py writes")
                stale += 1
        else:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(want)
    if check:
        print("  gift tables: in step with tools/gen_gifts.py" if stale == 0 else f"  {stale} gift table(s) out of step")
    else:
        print(f"{len(TABLES)} gift tables written to {os.path.relpath(OUT, paths.ROOT)}")
    return 1 if stale else 0


if __name__ == "__main__":
    sys.exit(main("--check" in sys.argv[1:]))
