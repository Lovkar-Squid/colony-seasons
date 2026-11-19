"""Static checks of the festival events (chunk S7, design 5).

1. The mixin fails soft: the mixin config is listed in neoforge.mods.toml, is not required, has defaultRequire 0,
   names every class of src/.../mixin/, and every @Inject there says require = 0.
2. The sounds: every sound event festival/FestivalSounds registers has an entry in sounds.json, every file an entry
   names exists as .ogg under sounds/, and every subtitle has a lang line.
3. The gift tables: the tables festival/Gifts names exist, are what tools/gen_gifts.py writes, and every item they
   name exists (vanilla ids against the client assets jar, ours against our item models).
4. The gathering ends before bedtime: the default events.gatherUntilTick and Gathering.LATEST_UNTIL are below
   Gathering.BEDTIME, and later than the default gatherFromTick.
5. In the festival package only TreeLighting sets blocks (a lit change of a ledger-listed light, by the S4 rule).

Run: python -I tools/check_festival.py   (exit 1 on a failure)
"""
import glob
import json
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_gifts  # noqa: E402
import paths  # noqa: E402

ROOT = paths.ROOT
SRC = os.path.join(ROOT, "src", "me", "lovkar", "seasons")
ASSETS = paths.res("assets", "colony_seasons")
MC_ASSET_PATTERN = os.environ.get("MC_ASSETS_JAR", "client-*-extra.jar")


def read(*parts):
    with open(os.path.join(*parts), encoding="utf-8") as f:
        return f.read()


def annotations(text, start):
    """The argument text of every annotation that begins with `start` (an open parenthesis), nested ones included."""
    found = []
    i = text.find(start)
    while i >= 0:
        depth, j = 0, i + len(start) - 1
        while j < len(text):
            depth += {"(": 1, ")": -1}.get(text[j], 0)
            if depth == 0:
                break
            j += 1
        found.append(text[i + len(start):j])
        i = text.find(start, j)
    return found


def check_mixin():
    bad = []
    toml = read(paths.res("META-INF", "neoforge.mods.toml"))
    configs = re.findall(r'^\[\[mixins\]\]\s*\nconfig\s*=\s*"([^"]+)"', toml, re.M)
    if "colony_seasons.mixins.json" not in configs:
        bad.append("neoforge.mods.toml lists no [[mixins]] config colony_seasons.mixins.json")
        return bad
    data = json.loads(read(paths.res("colony_seasons.mixins.json")))
    if data.get("required", True) is not False:
        bad.append("colony_seasons.mixins.json must say \"required\": false (the mixin fails soft)")
    if data.get("injectors", {}).get("defaultRequire", 1) != 0:
        bad.append("colony_seasons.mixins.json must say injectors.defaultRequire 0")
    package = data.get("package", "")
    folder = os.path.join(ROOT, "src", *package.split("."))
    classes = sorted(os.path.splitext(os.path.basename(p))[0] for p in glob.glob(os.path.join(folder, "*.java")))
    listed = sorted(data.get("mixins", []) + data.get("server", []) + data.get("client", []))
    if classes != listed:
        bad.append(f"the mixin config lists {listed}, the package {package} holds {classes}")
    for java in glob.glob(os.path.join(folder, "*.java")):
        text = read(java)
        for inject in annotations(text, "@Inject("):
            if not re.search(r"(?<![A-Za-z])require[ ]*=[ ]*0(?![0-9])", inject):
                bad.append(f"{os.path.basename(java)}: an @Inject without require = 0")
    return bad


def check_sounds():
    bad = []
    sounds = json.loads(read(ASSETS, "sounds.json"))
    lang = json.loads(read(ASSETS, "lang", "en_us.json"))
    registered = re.findall(r'register\("([a-z0-9_.]+)"\)', read(SRC, "festival", "FestivalSounds.java"))
    for name in registered:
        if name not in sounds:
            bad.append(f"sound event {name} has no entry in sounds.json")
    for name, entry in sounds.items():
        subtitle = entry.get("subtitle")
        if subtitle and subtitle not in lang:
            bad.append(f"sounds.json {name}: subtitle {subtitle} has no lang line")
        for one in entry.get("sounds", []):
            ref = one["name"] if isinstance(one, dict) else one
            ns, _, path = ref.partition(":")
            if ns != "colony_seasons":
                continue
            if not os.path.isfile(paths.res("assets", ns, "sounds", *path.split("/")) + ".ogg"):
                bad.append(f"sounds.json {name}: no file sounds/{path}.ogg")
    return bad, len(registered)


def check_gifts():
    bad = []
    if gen_gifts.main(True) != 0:
        bad.append("the gift tables are not what tools/gen_gifts.py writes (run it)")
    gifts = read(SRC, "festival", "Gifts.java")
    for path in re.findall(r'table\("(gifts/[a-z_]+)"\)', gifts):
        if not os.path.isfile(paths.res("data", "colony_seasons", "loot_table", *path.split("/")) + ".json"):
            bad.append(f"Gifts names {path}, which has no loot table")
    with zipfile.ZipFile(paths.lib(MC_ASSET_PATTERN)) as jar:
        vanilla = {n[len("assets/minecraft/models/item/"):-len(".json")] for n in jar.namelist()
                   if n.startswith("assets/minecraft/models/item/") and n.endswith(".json")}
    ours = {os.path.splitext(os.path.basename(p))[0] for p in glob.glob(os.path.join(ASSETS, "models", "item", "*.json"))}
    names = 0
    for path in glob.glob(paths.res("data", "colony_seasons", "loot_table", "gifts", "*.json")):
        text = read(path)
        for ident in re.findall(r'"name":\s*"([a-z0-9_]+:[a-z0-9_/]+)"', text):
            names += 1
            ns, _, item = ident.partition(":")
            if ns == "minecraft" and item not in vanilla or ns == "colony_seasons" and item not in ours \
                    or ns not in ("minecraft", "colony_seasons"):
                bad.append(f"{os.path.basename(path)}: {ident} is not an item")
    return bad, names


def check_hours():
    bad = []
    config = read(SRC, "SeasonsConfig.java")
    gathering = read(SRC, "festival", "Gathering.java")

    def const(text, name):
        m = re.search(name + r"\s*=\s*(\d+)\s*;", text)
        return int(m.group(1)) if m else None

    bedtime = const(gathering, "BEDTIME")
    latest = const(gathering, "LATEST_UNTIL")
    frm = const(config, "DEFAULT_GATHER_FROM_TICK")
    until = const(config, "DEFAULT_GATHER_UNTIL_TICK")
    if None in (bedtime, latest, frm, until):
        return ["a gathering constant was not found"]
    if not frm < until <= latest < bedtime:
        bad.append(f"the hours must end before bedtime: from {frm}, until {until}, latest {latest}, bedtime {bedtime}")
    if bedtime != 10600:
        bad.append(f"MineColonies sends colonists to bed after 10600, Gathering.BEDTIME says {bedtime}")
    return bad


def check_blocks():
    bad = []
    for java in sorted(glob.glob(os.path.join(SRC, "festival", "*.java"))):
        if os.path.basename(java) == "TreeLighting.java":
            continue
        for n, line in enumerate(read(java).splitlines(), 1):
            if re.search(r"\.(setBlock|setBlockAndUpdate|removeBlock|destroyBlock)\(", line):
                bad.append(f"festival/{os.path.basename(java)}:{n} sets a block; only TreeLighting may")
    return bad


def main():
    bad = check_mixin()
    sound_bad, sounds = check_sounds()
    gift_bad, items = check_gifts()
    bad += sound_bad + gift_bad + check_hours() + check_blocks()
    for line in bad:
        print(f"  !! {line}")
    if not bad:
        print(f"  festival: the mixin fails soft, {sounds} sounds with files and subtitles, {items} gift entries name real items, "
              f"the hours end before bedtime, only TreeLighting sets blocks")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
