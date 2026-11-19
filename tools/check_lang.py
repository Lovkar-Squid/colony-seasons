"""Does every name the mod asks for actually exist?

The bug a MineColonies addon ships most easily is a window showing a raw translation key: some code
builds a key out of a prefix and an id and one of the ids has no line in en_us.json. A compiler never
catches that, and it does not show up until the tab is opened in the right state.

So it gets a checker. This walks the Java and the BlockUI layouts, collects every key the mod can
ask for, and expands the built ones against the enums they are built from: a key made of a prefix
plus an enum id is not one key, it is one per id, and all of them have to be there. Then it says
which are missing, and which lines in the lang file nothing asks for any more.

Run:  python tools/check_lang.py [repo ...]   (defaults to the repository this file lives in)
With no en_us.json yet (an empty resources tree) there is nothing to check and it passes.
"""
import json
import pathlib
import re
import sys

# key prefixes that are completed at runtime, and the enum each one is completed from:
# (prefix, source file, the regex that finds the ids in it)
BUILT = [
    ("com.colony_seasons.season.", "src/me/lovkar/seasons/calendar/Season.java"),
    # chunks that build keys from an enum add ("com.colony_seasons.<group>.", "src/me/lovkar/seasons/<path>/<Enum>.java") here
]

ENUM_ID = re.compile(r'^\s*[A-Z][A-Z_0-9]*\("([a-z_]+)"', re.M)
LITERAL = re.compile(r'"(com\.[a-z_]+\.[A-Za-z0-9_.]+)"')
JOINED = re.compile(r'"(com\.[a-z_]+\.[A-Za-z0-9_.]*\.)"\s*\+')
IN_XML = re.compile(r'\$\((com\.[A-Za-z0-9_.]+)\)')


def ids_in(path):
    """The string ids of an enum, in the order it declares them."""
    if not path.exists():
        return []
    return ENUM_ID.findall(path.read_text(encoding="utf-8"))


def check(repo):
    repo = pathlib.Path(repo)
    lang_files = list(repo.glob("resources/assets/*/lang/en_us.json"))
    if not lang_files:
        print(f"  {repo}: no en_us.json - nothing to check")
        return 0
    have = set()
    for one in lang_files:
        have |= set(json.loads(one.read_text(encoding="utf-8")).keys())

    wanted = {}          # key -> where it was asked for

    def want(key, where):
        wanted.setdefault(key, where)

    for java in repo.rglob("src/**/*.java"):
        text = java.read_text(encoding="utf-8")
        for key in LITERAL.findall(text):
            # a bare prefix ending in a dot is half a key; it is handled below
            if not key.endswith("."):
                want(key, java.name)
        for prefix in JOINED.findall(text):
            known = False
            for pre, source in BUILT:
                if prefix != pre:
                    continue
                known = True
                for one in ids_in(repo / source):
                    want(prefix + one, java.name)
            if not known:
                print(f"  ?  {java.name} builds keys from '{prefix}' and this checker does not "
                      f"know what completes it - add it to BUILT")

    for xml in repo.rglob("resources/**/*.xml"):
        for key in IN_XML.findall(xml.read_text(encoding="utf-8")):
            want(key, xml.name)

    # a key can also be claimed by a data file rather than by code - a research names its own
    # title, a block its own name - and those are asked for just as much as the rest
    claimed = set()
    for data in repo.rglob("resources/**/*.json"):
        if data.parent.name == "lang":
            continue
        text = data.read_text(encoding="utf-8")
        for key in have:
            if '"' + key + '"' in text:
                claimed.add(key)
    # and anything a registered block, item, job or research is named after is claimed by the
    # registry entry rather than by a line of code that mentions the key
    for java in repo.rglob("src/**/*.java"):
        text = java.read_text(encoding="utf-8")
        for key in have:
            if '"' + key + '"' in text:
                claimed.add(key)

    # MineColonies' own keys (guard settings, the help button) live in MineColonies' lang file, not ours
    missing = sorted(k for k in wanted if k not in have and not k.startswith("com.minecolonies."))
    # a settings label is built by MineColonies, not by us, so it is never "asked for" in our
    # source; it is still ours to provide, and it is not spare
    # what MineColonies builds for us - a settings label, a hut name, a job name - is ours to
    # provide and is never mentioned in our own source, so it is never spare
    THEIRS = ("com.minecolonies.", "block.", "item.", "entity.", "itemGroup.", "gui.", "key.",
              # and the ones MineColonies builds out of OUR namespace: a hut's name, a job's
              # name, a settings label, every line of a research tree
              "com.colony_seasons.building.", "com.colony_seasons.job.",
              "com.colony_seasons.setting.", "com.colony_seasons.research.",
              "colony_seasons:")
    spare = sorted(k for k in have
                   if k not in wanted and k not in claimed and not k.startswith(THEIRS))

    print(f"  {repo.name}: {len(wanted)} key(s) asked for, {len(have)} in the lang file")
    for key in missing:
        print(f"  !! MISSING  {key}   (asked for in {wanted[key]})")
    for key in spare:
        print(f"  -  spare    {key}")
    return len(missing)


if __name__ == "__main__":
    repos = sys.argv[1:] or [pathlib.Path(__file__).resolve().parent.parent]
    bad = 0
    print("names:")
    for repo in repos:
        bad += check(repo)
    print("  all names present" if bad == 0 else f"  {bad} name(s) missing")
    sys.exit(1 if bad else 0)
