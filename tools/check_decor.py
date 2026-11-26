"""Static checks of the placing code (chunk S4, design 3.2 and 3.3).

1. Every decoration block the Decorator can place tells the ledger about changes: its class (or a parent in this
   repo) overrides onRemove and calls DecorLedger.blockChanged. Without it a light switched by hand would keep an
   old state in the ledger and the exact-state rule would leave the block up at take-down.
2. Only DecorPlacer sets or clears blocks among the Decorator's code (the packages decor, ai, command): one place
   holds the cell rule and the removal rule.
3. The festival marker registers itself with the ledger when placed and unregisters when it goes.
4. Every decoration block the Decorator can place (its class or a parent) overrides getDrops and passes its drops
   through DecorDrops.filter (S10d: a free decoration, the gift pile and the crate drop nothing when broken).

Run: python -I tools/check_decor.py   (exit 1 on a failure)
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
BLOCKS = REPO / "src" / "me" / "lovkar" / "seasons" / "block"
# the blocks a building or the square can get from the Decorator, and the class each one is made of
PLACEABLE = {
    "wreath": "WallDecorationBlock", "garland": "WallDecorationBlock", "lantern_string": "LanternStringBlock",
    "fairy_lights": "LitWallDecorationBlock", "ornament_leaves": "OrnamentLeavesBlock", "tinsel_leaves": "OrnamentLeavesBlock",
    "tree_star": "TreeStarBlock", "large_star": "LargeStarBlock", "gift_pile": "GiftPileBlock", "firework_crate": "FireworkCrateBlock",
    "festival_trunk": "FestivalTrunkBlock",
    # the storeroom's dressing in the Workshop (S10g2): no item, placed for free
    "storage_box": "StorageBoxBlock", "storage_coil": "StorageCoilBlock",
    # the street pieces on the spots marked with the Decorator's wand and the street lights (0.2.0, Q11)
    "snowman": "SnowmanBlock", "snowman_b": "SnowmanBlock", "lantern_post": "LanternPostBlock",
    "street_garland": "StreetGarlandBlock", "street_lights": "StreetLightsBlock",
}
EXTENDS = re.compile(r"class\s+(\w+)\s+extends\s+(\w+)")


def tells_ledger(cls):
    """True when the class or one of its parents in block/ calls DecorLedger.blockChanged from onRemove."""
    seen = set()
    while cls and cls not in seen:
        seen.add(cls)
        path = BLOCKS / (cls + ".java")
        if not path.exists():
            return False
        text = path.read_text(encoding="utf-8")
        if re.search(r"void onRemove\(", text) and "DecorLedger.blockChanged(" in text:
            return True
        match = EXTENDS.search(text)
        cls = match.group(2) if match else None
    return False


def filters_drops(cls):
    """True when the class or one of its parents in block/ overrides getDrops and calls DecorDrops.filter."""
    seen = set()
    while cls and cls not in seen:
        seen.add(cls)
        path = BLOCKS / (cls + ".java")
        if not path.exists():
            return False
        text = path.read_text(encoding="utf-8")
        if re.search(r"List<ItemStack> getDrops\(", text) and "DecorDrops.filter(" in text:
            return True
        match = EXTENDS.search(text)
        cls = match.group(2) if match else None
    return False


def main():
    bad = 0
    blocks = (BLOCKS / "SeasonsBlocks.java").read_text(encoding="utf-8")
    for block_id, cls in PLACEABLE.items():
        if f'register("{block_id}"' not in blocks:
            print(f"  !! {block_id} is not registered in SeasonsBlocks")
            bad += 1
        elif not tells_ledger(cls):
            print(f"  !! {block_id} ({cls}) does not call DecorLedger.blockChanged from onRemove")
            bad += 1
        elif not filters_drops(cls):
            print(f"  !! {block_id} ({cls}) does not pass its drops through DecorDrops.filter from getDrops")
            bad += 1
    base = REPO / "src" / "me" / "lovkar" / "seasons"
    for folder in ("decor", "ai", "command"):
        for java in sorted((base / folder).glob("*.java")):
            if java.name == "DecorPlacer.java":
                continue
            text = java.read_text(encoding="utf-8")
            for n, line in enumerate(text.splitlines(), 1):
                if re.search(r"\.(setBlock|setBlockAndUpdate|removeBlock|destroyBlock)\(", line):
                    print(f"  !! {folder}/{java.name}:{n} sets a block; only DecorPlacer may")
                    bad += 1
    marker = (BLOCKS / "FestivalMarkerBlock.java").read_text(encoding="utf-8")
    for call in ("DecorEvents.markerPlaced(", "DecorEvents.markerRemoved("):
        if call not in marker:
            print(f"  !! FestivalMarkerBlock does not call {call[:-1]}")
            bad += 1
    print("  placing code: every placeable block tells the ledger and filters its drops, only DecorPlacer sets blocks" if bad == 0
          else f"  {bad} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
