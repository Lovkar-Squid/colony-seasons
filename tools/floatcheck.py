"""Find blocks that are not connected to the ground, and blocks that would pop off in the game
(copied from Voyager 0.4.0's tools/floatcheck.py and adapted).

A block "floats" when its 6-neighbourhood component never reaches the lowest layer of the structure or one of the
extra support cells (a tree piece stands on the festival marker, which is not in the piece: its anchor cell is air).
attachment() knows what each attached block needs: a ladder and our wall decorations a whole block behind them (not a
glass pane: the picked hut's outside ladder crossed a window, and the paste probe saw two of its rungs drop), a
lantern a block that really holds it (a top slab, a top trapdoor or a carpet does not hold a hanging lantern).
"""
from collections import deque

from voxel import parse_state

NEI = [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]


def floating(s, supports=()):
    if not s.blocks:
        return []
    ymin = min(y for (_, y, _) in s.blocks)
    seen = set(p for p in s.blocks if p[1] == ymin)
    q = deque(seen)
    for p in supports:
        seen.add(p)
        q.append(p)
    while q:
        x, y, z = q.popleft()
        for dx, dy, dz in NEI:
            n = (x + dx, y + dy, z + dz)
            if n in s.blocks and n not in seen:
                seen.add(n)
                q.append(n)
    return sorted(p for p in s.blocks if p not in seen)


def components(pts):
    pts = set(pts)
    comps = []
    while pts:
        start = pts.pop()
        comp = [start]
        q = deque([start])
        while q:
            x, y, z = q.popleft()
            for dx, dy, dz in NEI:
                n = (x + dx, y + dy, z + dz)
                if n in pts:
                    pts.remove(n)
                    comp.append(n)
                    q.append(n)
        comps.append(sorted(comp))
    return comps


# the block a facing block hangs on: a ladder or a wall decoration facing north hangs on the block south of it
BEHIND = {"north": (0, 0, 1), "south": (0, 0, -1), "west": (1, 0, 0), "east": (-1, 0, 0)}
WALL_DECOR = {"colony_seasons:wreath", "colony_seasons:garland", "colony_seasons:lantern_string",
              "colony_seasons:fairy_lights"}
# shapes that are not a whole block (what they offer above and below is worked out in face_holds)
THIN = ("_carpet", "_pressure_plate", "lantern", "torch", "_door", "ladder", "_sign", "_banner", "flower", "_sapling",
        "chain", "rail", "_button", "lever", "tripwire", "_bed")


def face_holds(state, face):
    """Does this block's top ('up') or bottom ('down') face hold the centre of a lantern (vanilla canSupportCenter)?"""
    if state is None:
        return False
    name, props = parse_state(state)
    short = name.split(":", 1)[1]
    if name.startswith("structurize:"):
        return True        # substitution cells stand for the ground that is already there
    if name in WALL_DECOR or name.startswith("colony_seasons:") and short not in ("ornament_leaves", "tinsel_leaves"):
        return False       # our decorations have no collision, or are small models (stars, gifts, crate, marker)
    if short.endswith("_slab"):
        t = props.get("type", "bottom")
        return t == "double" or (t == "bottom") == (face == "down")
    if short.endswith("_stairs"):
        return (props.get("half", "bottom") == "bottom") == (face == "down")
    if short.endswith("_trapdoor"):
        if props.get("open", "false") == "true":
            return False
        return (props.get("half", "bottom") == "bottom") == (face == "down")
    if short.endswith("_fence") or short.endswith("_wall") or short in ("chain", "iron_bars") or short.endswith("_pane"):
        return short != "chain" or props.get("axis", "y") == "y"
    if short in ("stonecutter", "lectern", "enchanting_table", "daylight_detector", "composter", "cauldron"):
        return face == "down"
    if short == "snow" or any(w in short for w in THIN):
        return False
    return True


def side_sturdy(state):
    """Is this block's side a whole sturdy face, as a ladder or one of our wall decorations needs behind it? Only whole
    blocks: a glass pane, fence, wall, slab, stair, trapdoor or a small model has no full side."""
    if state is None:
        return False
    name, props = parse_state(state)
    short = name.split(":", 1)[1]
    if name.startswith("structurize:"):
        return True
    if name.startswith("colony_seasons:"):
        return short in ("ornament_leaves", "tinsel_leaves")
    if short.endswith("_slab"):
        return props.get("type") == "double"
    if any(short.endswith(w) for w in ("_stairs", "_trapdoor", "_fence", "_fence_gate", "_wall", "_pane", "_door")):
        return False
    if short in ("iron_bars", "chain", "stonecutter", "lectern", "enchanting_table", "daylight_detector", "composter",
                 "cauldron", "snow") or any(w in short for w in THIN):
        return False
    return True


def attachment(s):
    """Blocks that would pop off in the game: ladders without a wall behind them, lanterns without a block that holds
    them, doors without a floor, wall decorations without a solid block behind them, carpets on air."""
    bad = []
    for (x, y, z), st in s.blocks.items():
        name, props = parse_state(st)
        short = name.split(":", 1)[1]
        ok = True
        if short == "ladder" or name in WALL_DECOR:
            d = BEHIND[props.get("facing", "north")]
            ok = side_sturdy(s.get(x + d[0], y, z + d[2]))
        elif short in ("lantern", "soul_lantern"):
            if props.get("hanging") == "true":
                ok = face_holds(s.get(x, y + 1, z), "down")
            else:
                ok = face_holds(s.get(x, y - 1, z), "up")
        elif short.endswith("_door") and props.get("half", "lower") == "lower":
            ok = face_holds(s.get(x, y - 1, z), "up") and (s.get(x, y + 1, z) or "").startswith(name)
        elif short.endswith("_carpet") or short in ("torch", "soul_torch"):
            ok = s.get(x, y - 1, z) is not None
        if not ok:
            bad.append(((x, y, z), st))
    return sorted(bad)
