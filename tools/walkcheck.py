"""Can a 2-tall colonist walk to the hut block, and up to every floor? (Copied from Voyager 0.4.0's
tools/walkcheck.py and adapted: ladders are climbed, the way MineColonies' path finding climbs them.)

BFS over cells a colonist can occupy: two passable cells, and either a block to stand on below or a ladder in the
cell. Steps up 1 (with headroom), drops down up to 3, climbs ladders up and down. Doors are passable (colonists open
them); glass panes, fences and carpets on the way are not walked through.
"""
from collections import deque

from voxel import parse_state

# what a colonist walks through: doors (opened), ladders (climbed), our wall decorations (no collision). By whole
# name or suffix: Voyager's copy matched the word "air", which "stairs" also contains.
PASSABLE = {"minecraft:air", "minecraft:ladder", "colony_seasons:wreath", "colony_seasons:garland",
            "colony_seasons:lantern_string", "colony_seasons:fairy_lights"}


def passable(s, x, y, z):
    b = s.get(x, y, z)
    if b is None:
        return True
    name = parse_state(b)[0]
    if name.startswith("structurize:"):
        return False      # a substitution cell is the ground that is already there
    return name in PASSABLE or name.endswith("_door")


def ladder(s, x, y, z):
    b = s.get(x, y, z)
    return b is not None and parse_state(b)[0] == "minecraft:ladder"


def solid(s, x, y, z):
    b = s.get(x, y, z)
    if b is None:
        return False
    name = parse_state(b)[0]
    if name in ("minecraft:lantern", "minecraft:soul_lantern"):
        return False
    return not any(w in name for w in ("_door", "chorus", "torch", "end_rod", "lightning_rod", "chain", "ladder",
                                       "_carpet", "colony_seasons:wreath", "colony_seasons:garland",
                                       "colony_seasons:lantern_string", "colony_seasons:fairy_lights"))


def standable(s, x, y, z):
    return solid(s, x, y - 1, z) and passable(s, x, y, z) and passable(s, x, y + 1, z)


def occupiable(s, x, y, z):
    """Standing, or hanging on a ladder."""
    return passable(s, x, y, z) and passable(s, x, y + 1, z) and (solid(s, x, y - 1, z) or ladder(s, x, y, z))


def walk(s, starts):
    """Every cell reachable from the start cells."""
    seen = set(starts)
    q = deque(starts)
    while q:
        x, y, z = q.popleft()
        if ladder(s, x, y, z):
            for ny in (y + 1, y - 1):
                if (x, ny, z) not in seen and occupiable(s, x, ny, z):
                    seen.add((x, ny, z))
                    q.append((x, ny, z))
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, nz = x + dx, z + dz
            for ny in (y + 1, y, y - 1, y - 2, y - 3):
                if (nx, ny, nz) in seen:
                    break
                if occupiable(s, nx, ny, nz):
                    # stepping up needs headroom at the current cell too
                    if ny == y + 1 and not passable(s, x, y + 2, z):
                        break
                    if ny < y and not all(passable(s, nx, yy, nz) for yy in range(ny + 2, y + 2)):
                        break
                    seen.add((nx, ny, nz))
                    q.append((nx, ny, nz))
                    break
                if not passable(s, nx, ny, nz) and ny <= y:
                    break
    return seen


def next_to(seen, goal):
    gx, gy, gz = goal
    return any(abs(x - gx) + abs(z - gz) == 1 and abs(y - gy) <= 1 for (x, y, z) in seen)


def reachable(s, start, goal):
    """Is a cell next to `goal` reachable from `start`? Returns (ok, visited cells)."""
    seen = walk(s, [start])
    return next_to(seen, goal), seen
