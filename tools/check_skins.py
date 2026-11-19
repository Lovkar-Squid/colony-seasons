"""The Decorator's citizen textures are all there, the right size, and only where they belong.

MineColonies says nothing when a citizen texture is missing: the colonist simply renders with a missing texture.
This checks what tools/gen_decorator_skin.py writes:

  * the 8 body textures decorator{male,female}1_{a,b,d,w}.png in textures/entity/citizen/default/, 128x64;
  * the same 8 names as 16x16 icons in textures/entity_icon/citizen/<style>/ for all eight styles (the icon lookup
    has no fall-back to default);
  * no file of ours under textures/entity/citizen/ outside default/ (design 7: nothing in the nether style folder
    but the icons every addon has to ship there);
  * the model id's path in colony/DecoratorSetup.java ("decorator") is the texture base name.

Run:  python -I tools/check_skins.py
"""
import glob
import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

STYLES = ("default", "eastasian", "hellenic", "medieval", "modern", "nether", "nordic", "undead")
NAMES = [f"{g}1{s}" for g in ("male", "female") for s in ("_a", "_b", "_d", "_w")]
TEX = paths.res("assets", "minecolonies", "textures")
SETUP = os.path.join(paths.ROOT, "src", "me", "lovkar", "seasons", "colony", "DecoratorSetup.java")
MODEL_ID = re.compile(r'MODEL_ID\s*=\s*id\("([a-z0-9_]+)"\)')


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


def main():
    bad = 0
    found = MODEL_ID.search(open(SETUP, encoding="utf-8").read())
    base = found.group(1) if found else None
    if base is None:
        print("  !! no MODEL_ID = id(\"...\") in DecoratorSetup.java")
        return 1
    checked = 0
    for name in NAMES:
        body = os.path.join(TEX, "entity", "citizen", "default", f"{base}{name}.png")
        checked += 1
        if not os.path.exists(body):
            print(f"  !! missing body texture default/{base}{name}.png")
            bad += 1
        elif png_size(body) != (128, 64):
            print(f"  !! default/{base}{name}.png is {png_size(body)}, wanted 128x64")
            bad += 1
        for style in STYLES:
            icon = os.path.join(TEX, "entity_icon", "citizen", style, f"{base}{name}.png")
            checked += 1
            if not os.path.exists(icon):
                print(f"  !! missing icon {style}/{base}{name}.png")
                bad += 1
            elif png_size(icon) != (16, 16):
                print(f"  !! icon {style}/{base}{name}.png is {png_size(icon)}, wanted 16x16")
                bad += 1
    for path in glob.glob(os.path.join(TEX, "entity", "citizen", "*", "*")):
        style = os.path.basename(os.path.dirname(path))
        if style != "default":
            print(f"  !! entity/citizen/{style}/{os.path.basename(path)}: bodies go in default/ only")
            bad += 1
    print(f"skins: model id path '{base}', {checked} file(s) checked, {bad} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
