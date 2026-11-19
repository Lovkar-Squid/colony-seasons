"""Check every block state used by the design builds: the id must have a blockstate (vanilla jar or
src/assets), every property must be a real property of that block. Properties are read from the
blockstate JSON; a few vanilla properties that never change the model (persistent, distance,
waterlogged, signal_fire) are listed in KNOWN_EXTRA because they do not appear in the JSON.

python check_blocks.py   (prints problems, then 'ALL OK' and a per-build block count)
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "src"))
import mcmodel  # noqa: E402

KNOWN_EXTRA = {"persistent", "distance", "waterlogged", "signal_fire", "powered"}


def props_of(name):
    ns, path = mcmodel.split_id(name)
    bs = mcmodel.load_json(ns, f"blockstates/{path}.json")
    if bs is None:
        return None
    vals = {}
    if "variants" in bs:
        for key in bs["variants"]:
            if key:
                for kv in key.split(","):
                    k, v = kv.split("=")
                    vals.setdefault(k, set()).add(v)
    else:
        def walk(c):
            if "OR" in c:
                for x in c["OR"]:
                    walk(x)
            elif "AND" in c:
                for x in c["AND"]:
                    walk(x)
            else:
                for k, v in c.items():
                    vals.setdefault(k, set()).update(str(v).split("|"))
        for part in bs["multipart"]:
            if "when" in part:
                walk(part["when"])
    return vals


def check(builds):
    bad = 0
    for label, fn in builds:
        s = fn()
        for pos, state in sorted(s.blocks.items()):
            name, props = mcmodel.parse_state(state)
            known = props_of(name)
            if known is None:
                print(f"{label} {pos}: unknown block {name}")
                bad += 1
                continue
            for k, v in props.items():
                if k in KNOWN_EXTRA:
                    continue
                if k not in known:
                    print(f"{label} {pos}: {name} has no property '{k}'")
                    bad += 1
                elif v not in known[k] and not (k in ("north", "south", "east", "west", "up", "down") and v in ("true", "false", "none", "low", "tall")):
                    print(f"{label} {pos}: {name} {k}={v} not in {sorted(known[k])}")
                    bad += 1
        print(f"{label}: {len(s.blocks)} blocks")
    return bad


if __name__ == "__main__":
    import trees, huts, walls
    builds = [(f"tree-{k}", v[1]) for k, v in trees.BUILDS.items()] + [(f"hut-{k}", v[1]) for k, v in huts.BUILDS.items()]
    builds += [(f"wreath-{k}", v[1]) for k, v in walls.WREATHS.items()] + [(f"string-{k}", v[1]) for k, v in walls.STRINGS.items()]
    n = check(builds)
    print("ALL OK" if n == 0 else f"{n} PROBLEMS")
