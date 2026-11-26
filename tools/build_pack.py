"""Build and check the Structurize pack shipped in the Colony Seasons jar (copied from Voyager 0.4.0's
tools/build_pack.py and adapted).

    python -I tools/build_pack.py [<mods folder>]          writes the pack and the paste probe's manifest, then checks
    python -I tools/build_pack.py --check [<mods folder>]  checks, and that the files on disk are what the build writes

<mods folder> holds the Structurize and MineColonies jars whose block ids the blueprints use (tools/checks.sh passes
the rig's basemods/); the Minecraft client assets jar comes from tools/paths.py. Writes:

  resources/blueprints/colony_seasons/colony_seasons/pack.json         the pack ("Colony Seasons", its own pack)
  .../decorator/workshop1..5.blueprint                                  the Decorator's hut (tools/decorator_hut.py)
  .../square/townsquare1..5.blueprint                                   the Town Square, five levels of the picked look A
                                                                        (0.2.0, tools/square_hut.py, Q6)
  .../pieces/village_spruce_small|full|rich.blueprint, grand_tree.blueprint   the tree pieces (tools/tree_pieces.py)
  .../pieces/bed_spring|summer|autumn|winter.blueprint                  the square's seasonal touches (square_hut.touch)
  tools/cspaste/res/cspaste/manifest.json                               what the paste probe expects to read back
  tools/csspots/res/csspots/spots.json                                  the storeroom's spot tags per level (S10g1),
                                                                        for the csspots probe
  tools/csstoreroom/res/csstoreroom/sets.json                           the storeroom's two sets per level with their
                                                                        facings (S10g2, decorator_hut.storeroom_set),
                                                                        what the csstoreroom probe's placed room must be
  tools/cssquare/res/cssquare/square_tags.json                          the Town Square's tags per level, relative to
                                                                        its hut block, for the cssquare probe (Q1)

The icon (colony_seasons.png next to pack.json) is drawn by tools/render_s5.py; this script only checks it is there.
Checks, each printed, then ALL OK or PROBLEMS: every block id exists (client jar, our blockstates, the mod jars) with
real properties and values; our decorations are drawn unlit; no floating blocks; nothing that would pop off (ladders,
lanterns, wall decorations, doors, carpets); no bed; the hut block is reachable from the door and every floor cell from
the hut block; the levels share one box and one anchor; level 5 and the full and grand trees differ from the owner's
picked designs only where decorator_hut.DESIGN_CHANGES / tree_pieces.DESIGN_CHANGES say; the Workshop's storage room
(S10e: racks and vanilla blocks inside the walls, off the ladder, racks kept and more at every level, 2, 3, 6, 9 and
12 of them); the storeroom's spot tags (S10g1, docs/storage-look-design.md 3.1 and 3.5: in air inside the walls, nothing
above a rack, nothing behind a window, packed pieces standing on a barrel and never hung, hung pieces on a wall face,
the walk and the ladder clear with either set placed; the storeroom barrels into air, none above a rack or behind a
window; after a self-test of each rule); the Town Square (Q1, check_square: the notice board as the anchor, one 21 x 21
box, the tags of design 4.1 in their counts on air cells (`sit` on a bench's seat), standing room at the standing
tags, solid ground under `tree`, nothing in the 11 x 11 column over it, no tag on the anchor, the hut block reachable;
after a self-test of each rule); the piece contract (air
anchor, tags, the trunk a column of colony_seasons:festival_trunk and no log block in a piece, open sky over the
crate); and a read-back of every written file.
"""
import gzip
import io
import json
import os
import re
import sys
import zipfile

TOOLS = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, TOOLS)
import decorator_hut as dh  # noqa: E402
import nbt  # noqa: E402
import paths  # noqa: E402
import square_hut as sq  # noqa: E402
import tree_pieces as tp  # noqa: E402
import voxel  # noqa: E402
from floatcheck import attachment, components, floating  # noqa: E402
from walkcheck import next_to, standable, walk  # noqa: E402

PACK_NAME = "Colony Seasons"
PACK_DIR = paths.res("blueprints", "colony_seasons", "colony_seasons")
ICON = "colony_seasons.png"
HUT_FOLDER = "decorator"
PIECE_FOLDER = "pieces"
SQUARE_FOLDER = "square"
BUILDING_TYPE = "colony_seasons:decorator"         # DecoratorSetup.BUILDING_ID
SQUARE_TYPE = "colony_seasons:townsquare"          # TownSquareSetup.BUILDING_ID
BE_TYPE = "colony_seasons:colonybuilding"          # DecoratorSetup.BUILDING_BE
MARKER_BE = "colony_seasons:festival_marker"       # the piece's anchor holds what the marker cell holds: the tags
HUT_MODS = ("structurize", "minecolonies", "colony_seasons")
PIECE_MODS = ("structurize", "colony_seasons")
# One footprint for every level (the union of the five, anchors aligned): the build tool outline never changes
# between upgrades and the builder clears the whole area from level 1.
BOX = ((-6, 0, -5), (6, 15, 5))
GROUND = "structurize:blocksolidsubstitution"     # "a solid block of this terrain" (fills, costs materials)
KEEP = "structurize:blocksubstitution"            # "leave whatever is there" (no work, no materials)
MANIFEST = os.path.join(TOOLS, "cspaste", "res", "cspaste", "manifest.json")
# properties a block has that its blockstate JSON does not name, because they never change the model
EXTRA_PROPS = {"waterlogged": {"true", "false"}, "powered": {"true", "false"}}
LEAF_PROPS = {"persistent": {"true", "false"}, "distance": {str(i) for i in range(1, 8)}}
NS = "colony_seasons"
TRUNK = f"{NS}:festival_trunk"                    # the trunk of every tree piece: a spruce log to look at, in no log tag (S10c)

problems = []
NL = chr(10)


def problem(msg):
    problems.append(msg)
    print("!!", msg)


# ---------------------------------------------------------------------------------------------- block ids
class Blockstates:
    """Every blockstate file of the client assets jar, our resources and the mod jars, with the property values
    each one names."""

    def __init__(self, mod_jars):
        self.files = {}
        with zipfile.ZipFile(paths.lib(os.environ.get("MC_ASSETS_JAR", "client-*-extra.jar"))) as jar:
            self._scan(jar, "minecraft")
        own = paths.res("assets", NS, "blockstates")
        for f in sorted(os.listdir(own)):
            with open(os.path.join(own, f), encoding="utf-8") as fh:
                self.files[f"{NS}:{f[:-5]}"] = json.load(fh)
        for path in mod_jars:
            with zipfile.ZipFile(path) as jar:
                for ns in ("structurize", "minecolonies"):
                    self._scan(jar, ns)

    def _scan(self, jar, ns):
        for name in jar.namelist():
            m = re.match(rf"assets/{ns}/blockstates/([a-z0-9_]+)\.json$", name)
            if m:
                self.files[f"{ns}:{m.group(1)}"] = json.loads(jar.read(name))

    def props(self, name):
        bs = self.files.get(name)
        if bs is None:
            return None
        vals = {}
        if "variants" in bs:
            for key in bs["variants"]:
                for kv in filter(None, key.split(",")):
                    k, v = kv.split("=")
                    vals.setdefault(k, set()).add(v)
        else:
            def walk_cond(c):
                for sub in c.get("OR", []) + c.get("AND", []):
                    walk_cond(sub)
                for k, v in c.items():
                    if k not in ("OR", "AND"):
                        vals.setdefault(k, set()).update(str(v).split("|"))
            for part in bs["multipart"]:
                if "when" in part:
                    walk_cond(part["when"])
        for k, v in list(vals.items()):
            if v <= {"true", "false"}:
                vals[k] = {"true", "false"}      # a multipart names only the value that adds a part
            if k in ("north", "south", "east", "west") and v <= {"true", "false", "none", "low", "tall", "side", "up"}:
                vals[k] = v | {"true", "false", "none", "low", "tall", "side", "up"}
        if not name.startswith(NS + ":"):
            for k, v in EXTRA_PROPS.items():
                vals.setdefault(k, v)
            if name.endswith("_leaves"):
                vals.update(LEAF_PROPS)
        return vals

    def check(self, label, s):
        bad = 0
        for pos, state in sorted(s.blocks.items()):
            name, props = voxel.parse_state(state)
            known = self.props(name)
            if known is None:
                problem(f"{label} {pos}: unknown block {name} (Structurize would make it air)")
                bad += 1
                continue
            for k, v in props.items():
                if k not in known:
                    problem(f"{label} {pos}: {name} has no property '{k}'")
                    bad += 1
                elif v not in known[k]:
                    problem(f"{label} {pos}: {name} {k}={v} not in {sorted(known[k])}")
                    bad += 1
            if name.startswith(NS + ":") and "lit" in known and props.get("lit") != "false":
                problem(f"{label} {pos}: {state} is not drawn unlit (lit=false)")
                bad += 1
        return bad == 0


# ---------------------------------------------------------------------------------------------- the files
def aligned(fn, level):
    """Level `level` of a look, moved so its anchor coincides with level 5's anchor."""
    s = fn(level)
    a5 = fn(5).anchor
    dx, dz = a5[0] - s.anchor[0], a5[2] - s.anchor[2]
    return s.translated(dx, 0, dz) if (dx or dz) else s


def pad_ground(s):
    """The ground inside the shared footprint that the design does not use is left as it is (no landscaping); the
    builder clears what stands above it, so the outline is always free."""
    (bx0, _, bz0), (bx1, _, bz1) = BOX
    for x in range(bx0, bx1 + 1):
        for z in range(bz0, bz1 + 1):
            if (x, 0, z) not in s.blocks:
                s.set(x, 0, z, KEEP)


def hut_entity(file_name):
    return {"id": BE_TYPE, "type": BUILDING_TYPE, "version": nbt.Int(2), "pack": PACK_NAME,
            "path": f"{HUT_FOLDER}/{file_name}"}


def piece_entity():
    return {"id": MARKER_BE}


def square_entity(file_name):
    return {"id": BE_TYPE, "type": SQUARE_TYPE, "version": nbt.Int(2), "pack": PACK_NAME,
            "path": f"{SQUARE_FOLDER}/{file_name}"}


def square_files():
    out = []
    for lv in sq.LEVELS:
        s = sq.square(lv)
        fn = f"{s.name}.blueprint"
        root = s.blueprint_nbt(fn, PACK_NAME, f"{SQUARE_FOLDER}/{fn}", square_entity(fn), HUT_MODS, box=sq.BOX)
        out.append((f"{SQUARE_FOLDER}/{fn}", s, root, sq.BOX))
    return out


def hut_files():
    out = []
    for lv in range(1, 6):
        s = aligned(dh.workshop, lv)
        pad_ground(s)
        fn = f"{s.name}.blueprint"
        root = s.blueprint_nbt(fn, PACK_NAME, f"{HUT_FOLDER}/{fn}", hut_entity(fn), HUT_MODS, box=BOX)
        out.append((f"{HUT_FOLDER}/{fn}", s, root, BOX))
    return out


def piece_files():
    out = []
    for name in tp.PIECES:
        s = tp.piece(name)
        fn = f"{name}.blueprint"
        root = s.blueprint_nbt(fn, PACK_NAME, f"{PIECE_FOLDER}/{fn}", piece_entity(), PIECE_MODS)
        out.append((f"{PIECE_FOLDER}/{fn}", s, root, None))
    for season in sq.TOUCHES:                      # the square's seasonal touches (Q6, square_hut.touch)
        s = sq.touch(season)
        fn = f"{s.name}.blueprint"
        root = s.blueprint_nbt(fn, PACK_NAME, f"{PIECE_FOLDER}/{fn}", piece_entity(), PIECE_MODS)
        out.append((f"{PIECE_FOLDER}/{fn}", s, root, None))
    return out


def pack_json():
    return json.dumps({
        "icon": ICON,
        "name": PACK_NAME,
        "authors": ["Lovkar", "Claude"],
        "desc": "Works with every colony style",
        "mods": list(HUT_MODS),
        "version": "1",
        "pack-format": "1",
    }, indent=2) + "\n"


# ---------------------------------------------------------------------------------------------- the checks
def check_common(label, s, supports=()):
    ok = True
    loose = floating(s, supports)
    if loose:
        groups = components(loose)
        problem(f"{label}: {len(loose)} floating blocks in {len(groups)} groups, e.g. "
                f"{[(g[0], s.blocks[g[0]]) for g in groups[:3]]}")
        ok = False
    unattached = attachment(s)
    if unattached:
        problem(f"{label}: nothing holds {unattached[:4]}")
        ok = False
    beds = [p for p, b in s.blocks.items() if voxel.parse_state(b)[0].endswith("_bed")]
    if beds:
        problem(f"{label}: a bed at {beds[:2]} (the Decorator lives in an ordinary house)")
        ok = False
    return ok


def check_hut(label, s, level):
    ok = True
    if s.anchor != dh.HUT_SPOT or s.get(*s.anchor) != dh.HUT:
        problem(f"{label}: the anchor {s.anchor} is not the hut block {dh.HUT} at {dh.HUT_SPOT}")
        ok = False
    if s.tags.get(s.anchor) != ["name=" + dh.LOOK_LABEL]:
        problem(f"{label}: the anchor's tags {s.tags.get(s.anchor)} are not name={dh.LOOK_LABEL}")
        ok = False
    doors = [p for p, b in s.blocks.items() if "_door" in b and "half=lower" in b]
    if len(doors) != 1:
        problem(f"{label}: {len(doors)} doors (one is drawn)")
        return False, ""
    dx, dy, dz = doors[0]
    start = (dx, dy, dz + 1)                     # the front faces south: the door's outside square
    if not standable(s, *start):
        problem(f"{label}: nowhere to stand outside the door at {start}")
        return False, ""
    seen = walk(s, [start])
    if not next_to(seen, s.anchor):
        problem(f"{label}: the hut block {s.anchor} cannot be reached from the door")
        ok = False
    floors = {1: "ground floor"}
    if level >= 3:
        floors[6] = "upper floor"
    reached = {}
    for y, what in floors.items():
        cells = [(x, y, z) for x in range(dh.X0 + 1, dh.X1) for z in range(dh.Z0 + 1, dh.Z1) if standable(s, x, y, z)]
        missed = [c for c in cells if c not in seen]
        reached[what] = f"{len(cells) - len(missed)}/{len(cells)}"
        if not cells or missed:
            problem(f"{label}: {what}: {len(missed)} of {len(cells)} floor cells cannot be reached, e.g. {missed[:3]}")
            ok = False
    lights = sum(1 for b in s.blocks.values() if voxel.parse_state(b)[0] == "minecraft:lantern")
    racks = sum(1 for b in s.blocks.values() if voxel.parse_state(b)[0] == dh.RACK)
    return ok, f"door {doors[0]}, floors {reached}, {lights} lanterns, {racks} racks"


def check_storage():
    """The Workshop's storage room (S10e, decorator_hut.storage_room): only racks and vanilla blocks (the Builder's bill
    gets no decoration), every cell strictly inside the walls on a floor of the hut (the ground floor, y 1..4, or from
    level 3 the attic, y 6..8), nothing on the ladder's way up, every cell in the level's blueprint as listed, a rack
    stays a rack at every higher level, and the racks in the hut grow with every level. Returns the racks per level."""
    ok = True
    ladder = {(-4, y, 0) for y in range(1, 9)} | {(-4, 6, -1), (-4, 6, 1), (-3, 6, 0)}
    racks_at = {}
    for lv in range(1, 6):
        room = dh.storage_room(lv)
        built = dh.workshop(lv)
        if not room:
            problem(f"storage room, level {lv}: empty")
            ok = False
        for (x, y, z), b in sorted(room.items()):
            name = voxel.parse_state(b)[0]
            if name != dh.RACK and not name.startswith("minecraft:"):
                problem(f"storage room, level {lv}, {(x, y, z)}: {b} is neither a rack nor a vanilla block")
                ok = False
            floor = 1 <= y <= 4 or (lv >= 3 and 6 <= y <= 8)
            if not (dh.X0 < x < dh.X1 and dh.Z0 < z < dh.Z1 and floor):
                problem(f"storage room, level {lv}, {(x, y, z)}: not inside the walls on a floor of the hut")
                ok = False
            if (x, y, z) in ladder:
                problem(f"storage room, level {lv}, {(x, y, z)}: on the way up the ladder")
                ok = False
            if built.get(x, y, z) != b:
                problem(f"storage room, level {lv}, {(x, y, z)}: the blueprint has {built.get(x, y, z)}, not {b}")
                ok = False
        racks_at[lv] = {p for p, b in built.blocks.items() if voxel.parse_state(b)[0] == dh.RACK}
    for lv in range(1, 5):
        lost = racks_at[lv] - racks_at[lv + 1]
        if lost:
            problem(f"storage room: racks of level {lv} that level {lv + 1} does not have: {sorted(lost)[:3]}")
            ok = False
        if len(racks_at[lv + 1]) <= len(racks_at[lv]):
            problem(f"storage room: level {lv + 1} has {len(racks_at[lv + 1])} racks, not more than level {lv}'s {len(racks_at[lv])}")
            ok = False
    counts = [len(racks_at[lv]) for lv in range(1, 6)]
    if tuple(counts) != RACK_COUNTS:
        problem(f"storage room: racks at levels 1 to 5 are {counts}, not the shipped {list(RACK_COUNTS)} (S10e)")
        ok = False
    return ok, counts


# the racks in the hut at levels 1 to 5 as S10e shipped them; the storeroom's dressing (S10g1) never moves one
RACK_COUNTS = (2, 3, 6, 9, 12)
LADDER_WAY = {(-4, y, 0) for y in range(1, 9)} | {(-4, 6, -1), (-4, 6, 1), (-3, 6, 0)}
SPOTS = os.path.join(TOOLS, "csspots", "res", "csspots", "spots.json")
SETS = os.path.join(TOOLS, "csstoreroom", "res", "csstoreroom", "sets.json")


def spot_problems(level, s):
    """The rules of docs/storage-look-design.md 3.1 and 3.5 on the storeroom's position tags of one level's Workshop
    `s` (decorator_hut's frame, the hut block at (0, 1, 0)). Returns a list of problems (empty when all hold):
    every tag is one of ours by name (prefix, set id, piece) and alone on its cell; the cell is air, strictly inside
    the walls, on the room's floor (the ground floor at levels 1 and 2, the attic from 3: its corner below stays
    bare), off the ladder's way, not directly above a rack (a full rack draws boxes 6 px up into that cell), and not
    right behind a window; a packed piece stands on furniture (never hung, never on the floor); a hung piece has a
    whole wall block beside it to hang on; both sets are there; and a colonist still reaches the hut block and every
    floor cell from the door with either set placed (the pieces counted as obstacles, as the design round did)."""
    out = []
    ours = {}
    for pos, names in s.tags.items():
        for name in names:
            if name.startswith((dh.PACK_PREFIX, dh.HANG_PREFIX)):
                if dh.parse_tag(name) is None:
                    out.append(f"{pos}: tag {name} names no known set and piece")
                elif pos in ours:
                    out.append(f"{pos}: two spot tags on one cell ({ours[pos]}, {name})")
                else:
                    ours[pos] = name
            elif not name.startswith("name="):
                out.append(f"{pos}: unknown tag {name}")
    glass = {q for q, b in s.blocks.items() if "glass" in b}
    floor_ys = (1, 2, 3, 4) if level <= 2 else (6, 7, 8)
    kinds = set()
    for pos, name in sorted(ours.items()):
        kind, _, piece = dh.parse_tag(name)
        kinds.add(kind)
        x, y, z = pos
        if s.get(x, y, z) is not None:
            out.append(f"{name} {pos}: not air ({s.get(x, y, z)})")
        if not (dh.X0 < x < dh.X1 and dh.Z0 < z < dh.Z1 and y in floor_ys):
            out.append(f"{name} {pos}: not inside the walls on the room's floor (y {floor_ys[0]} to {floor_ys[-1]})")
        if pos in LADDER_WAY:
            out.append(f"{name} {pos}: on the ladder's way")
        below = s.get(x, y - 1, z)
        if below is not None and voxel.parse_state(below)[0] == dh.RACK:
            out.append(f"{name} {pos}: directly above a rack (its boxes stand 6 px up into this cell)")
        if any((x + dx, y, z + dz) in glass for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            out.append(f"{name} {pos}: right behind a window")
        if kind == "pack":
            if below is None or voxel.parse_state(below)[0] not in dh.FURNITURE:
                out.append(f"{name} {pos}: a packed piece that does not stand on furniture ({below}): never hung, "
                           f"never on the floor")
        elif dh.spot_facing(s.get, pos, kind) is None:
            out.append(f"{name} {pos}: a hung piece with no whole wall block beside it")
    if kinds != {"pack", "hang"}:
        out.append(f"the spots hold {sorted(kinds)}, not both sets (pack and hang)")
    for festival in (False, True):
        dressed = voxel.Structure("dressed")
        dressed.blocks = dict(s.blocks)
        dressed.blocks.update(dh.storeroom_set(level, s, festival))
        what = "festival" if festival else "packed"
        seen = walk(dressed, [(dh.DOOR_X, 1, dh.Z1 + 1)])
        if not next_to(seen, dh.HUT_SPOT):
            out.append(f"{what}: the hut block cannot be reached from the door")
        for fy in ((1,) if level < 3 else (1, 6)):
            cells = [(x, fy, z) for x in range(dh.X0 + 1, dh.X1) for z in range(dh.Z0 + 1, dh.Z1)
                     if standable(dressed, x, fy, z)]
            missed = [c for c in cells if c not in seen]
            if missed:
                out.append(f"{what}: floor y={fy}: {len(missed)} cells cannot be reached, e.g. {missed[:3]}")
    return out


def barrel_problems(bare, barrels):
    """The blueprint layer of docs/storage-look-design.md 3.1 for S10g1's storeroom barrels `barrels` ({position:
    block}) on one level's Workshop without them (`bare`): each goes into a cell `bare` leaves as air, none stands
    directly above a rack (3.1: nothing at all in that cell, blueprint furniture included) and none right behind a
    window (the outside stays look A). Inside the walls and off the ladder's way: check_storage."""
    out = []
    glass = {q for q, b in bare.blocks.items() if "glass" in b}
    for (x, y, z), b in sorted(barrels.items()):
        if bare.get(x, y, z) is not None:
            out.append(f"barrel {(x, y, z)}: the blueprint has {bare.get(x, y, z)} there (only air cells)")
        below = bare.get(x, y - 1, z)
        if below is not None and voxel.parse_state(below)[0] == dh.RACK:
            out.append(f"barrel {(x, y, z)}: directly above a rack (its boxes stand 6 px up into this cell)")
        if any((x + dx, y, z + dz) in glass for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            out.append(f"barrel {(x, y, z)}: right behind a window")
    return out


def check_spots():
    """The storeroom's spots (S10g1) of every level: spot_problems, and barrel_problems for the level's storeroom
    barrels (which add nothing else to the blueprint), after a self-test that the rules catch the faults the design
    round's review found (a festival piece above a rack, a packed piece hung on a wall) and the others.
    Returns (ok, per level (packed, hung))."""
    ok = selftest_spots()
    counts = []
    for lv in range(1, 6):
        s = dh.workshop(lv)
        bare = dh.workshop(lv, barrels=False)
        barrels = dh.storeroom_barrels(lv)
        found = spot_problems(lv, s) + barrel_problems(bare, barrels)
        if s.blocks != {**bare.blocks, **barrels}:
            found.append("the blueprint differs from the one without the storeroom barrels in more than those barrels")
        for p in found:
            problem(f"storeroom spots, level {lv}: {p}")
            ok = False
        names = [dh.parse_tag(n) for ns in s.tags.values() for n in ns if dh.parse_tag(n)]
        counts.append((sum(1 for k, _, _ in names if k == "pack"), sum(1 for k, _, _ in names if k == "hang")))
    return ok, counts


def selftest_spots():
    """Each fault planted once into a copy of level 5 (and level 1) must be found."""
    faults = [
        (5, (-2, 8, -3), "cs_hang_winter_garland", "directly above a rack"),
        (5, (1, 7, 3), "cs_pack_winter_box_wreath", "does not stand on furniture"),     # on a wall face: hung
        (1, (0, 1, -1), "cs_pack_winter_box_gifts", "does not stand on furniture"),
        (5, (-4, 6, -3), "cs_pack_winter_box_wreath", "not air"),
        (5, (4, 7, 2), "cs_hang_winter_lights", "right behind a window"),
        (5, (-4, 6, 1), "cs_hang_winter_lights", "on the ladder's way"),
        (5, (0, 7, 0), "cs_hang_winter_wreath", "no whole wall block"),
        (5, (0, 2, -3), "cs_hang_winter_wreath", "not inside the walls on the room's floor"),
        (5, (0, 7, -3), "cs_hang_winter_tinsel", "names no known set and piece"),
        (5, (4, 7, -2), "cs_pack_summer_box_wreath", "names no known set and piece"),
    ]
    ok = True
    for level, pos, name, want in faults:
        s = dh.workshop(level)
        s.tags = {p: list(n) for p, n in s.tags.items()}
        s.tags.setdefault(pos, []).append(name)
        found = spot_problems(level, s)
        if not any(str(pos) in f and want in f for f in found):
            problem(f"storeroom spots self-test: {name} at {pos} on level {level} was not reported as '{want}': {found[:3]}")
            ok = False
    for level, pos, want in [(5, (-2, 8, -3), "directly above a rack"), (5, (4, 7, 2), "right behind a window"),
                             (5, (-4, 6, -3), "only air cells")]:
        found = barrel_problems(dh.workshop(level, barrels=False), {**dh.storeroom_barrels(level), pos: dh.BARREL_UP})
        if not any(str(pos) in f and want in f for f in found):
            problem(f"storeroom spots self-test: a barrel at {pos} on level {level} was not reported as '{want}': {found[:3]}")
            ok = False
    s = dh.workshop(1)
    for z in range(-3, 4):                                    # a wall across the room, between the door and the hut block
        s.blocks[(2, 1, z)] = "minecraft:stone_bricks"
        s.blocks[(2, 2, z)] = "minecraft:stone_bricks"
    if not any("cannot be reached" in f for f in spot_problems(1, s)):
        problem("storeroom spots self-test: a wall across the room was not reported by the walk check")
        ok = False
    return ok


def spots_json():
    """What the csspots probe expects to read back from a pasted or upgraded Workshop: per level the spot tags,
    relative to the hut block, unturned (the probe turns them with the building)."""
    levels = {}
    for lv in range(1, 6):
        s = dh.workshop(lv)
        ax, ay, az = s.anchor
        levels[str(lv)] = sorted([[x - ax, y - ay, z - az, n] for (x, y, z), ns in s.tags.items() for n in ns
                                  if dh.parse_tag(n)])
    return {"sets": list(dh.SETS), "packedPieces": list(dh.PACKED_PIECES), "hungPieces": list(dh.HUNG_PIECES),
            "furniture": list(dh.FURNITURE), "levels": levels}


def spots_text():
    """spots_json() as written: one spot to a line."""
    d = spots_json()
    lines = ["{"] + [f" {json.dumps(k)}: {json.dumps(v)}," for k, v in d.items() if k != "levels"] + [' "levels": {']
    for i, (lv, spots) in enumerate(d["levels"].items()):
        lines.append(f"  {json.dumps(lv)}: [")
        lines += [f"   {json.dumps(sp)}" + ("," if j < len(spots) - 1 else "") for j, sp in enumerate(spots)]
        lines.append("  ]" + ("," if i < len(d["levels"]) - 1 else ""))
    lines += [" }", "}"]
    text = "\n".join(lines) + "\n"
    if json.loads(text) != d:
        raise ValueError("spots_text does not read back as spots_json")
    return text


def sets_json():
    """The storeroom's dressing as the Decorator must place it (S10g2): per level the packed set and the festival set,
    each a sorted list of [dx, dy, dz, block state] relative to the hut block, unturned, with the facings
    decorator_hut.storeroom_set works out (the probe turns offsets and facings with the building)."""
    levels = {}
    for lv in range(1, 6):
        s = dh.workshop(lv)
        ax, ay, az = s.anchor
        one = {}
        for name, festival in (("packed", False), ("festival", True)):
            one[name] = sorted([[x - ax, y - ay, z - az, state]
                                for (x, y, z), state in dh.storeroom_set(lv, s, festival).items()])
        levels[str(lv)] = one
    return {"sets": list(dh.SETS), "levels": levels}


def sets_text():
    """sets_json() as written: one piece to a line."""
    d = sets_json()
    lines = ["{", f' "sets": {json.dumps(d["sets"])},', ' "levels": {']
    for i, (lv, one) in enumerate(d["levels"].items()):
        lines.append(f"  {json.dumps(lv)}: {{")
        for k, (name, pieces) in enumerate(one.items()):
            lines.append(f"   {json.dumps(name)}: [")
            lines += [f"    {json.dumps(p)}" + ("," if j < len(pieces) - 1 else "") for j, p in enumerate(pieces)]
            lines.append("   ]" + ("," if k < len(one) - 1 else ""))
        lines.append("  }" + ("," if i < len(d["levels"]) - 1 else ""))
    lines += [" }", "}"]
    text = "\n".join(lines) + "\n"
    if json.loads(text) != d:
        raise ValueError("sets_text does not read back as sets_json")
    return text


SQUARE_TAGS = os.path.join(TOOLS, "cssquare", "res", "cssquare", "square_tags.json")
STANDING_TAGS = sq.STANDING
STONE_MIX = {"minecraft:stone_bricks", "minecraft:cobblestone", "minecraft:andesite", "minecraft:cracked_stone_bricks",
             "minecraft:mossy_cobblestone"}
FULL_FLOORS = STONE_MIX | {sq.PAD}                 # what a standing tag may stand on: a full block of the blueprint
SQUARE_PATH_BLOCK = sq.PATH
LOG_FAMILY = ("_log", "_wood", "_stem", "_hyphae")
# the touches' blocks: whole blocks that need no support and do not change by themselves (design A4)
TOUCH_BLOCKS = {"minecraft:pumpkin", "minecraft:carved_pumpkin", "minecraft:hay_block", "minecraft:melon"}
TOUCH_LEAVES = ("minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves", "minecraft:spruce_leaves",
                "minecraft:oak_leaves")
FLOOR_RANK = {GROUND: 0, sq.PATH: 1}                # the bottom layer: the terrain, a path, then anything solid (2)


def floor_rank(block):
    return FLOOR_RANK.get(block, 2)


def square_problems(s, level):
    """The rules of docs/town-square-design.md 4.1 and 4.2 on one level of the Town Square `s` (square_hut's frame: the
    `tree` cell at (0, 1, 0)): the anchor, the box, the tags (their counts are the table of design 4.2, their cells the
    ones square_hut gives, on air or on a bench's seat), standing room and a sturdy floor at the standing tags, the
    lamp posts and planters, no block with collision over a dirt path, the paved bottom layer, the 11 x 11 column over
    the tree, the walk from the entrance. Returns a list of problems (empty when all hold)."""
    out = []
    a = s.anchor
    if a != sq.HUT_SPOT or s.get(*a) != sq.HUT:
        out.append(f"the anchor {a} is not the hut block {sq.HUT} at {sq.HUT_SPOT}")
    if s.tags.get(a) != ["name=" + sq.LOOK_LABEL]:
        out.append(f"the anchor's tags {s.tags.get(a)} are not just name={sq.LOOK_LABEL} (no square tag on the anchor: "
                   f"MineColonies reads the anchor's tags for its allowed jobs)")
    (bx0, by0, bz0), (bx1, by1, bz1) = sq.BOX
    if (bx1 - bx0 + 1, bz1 - bz0 + 1) != (21, 21):
        out.append(f"the box {sq.BOX} is not 21 x 21")
    (x0, y0, z0), (x1, y1, z1) = s.bounds()
    if x0 < bx0 or y0 < by0 or z0 < bz0 or x1 > bx1 or y1 > by1 or z1 > bz1:
        out.append(f"blocks {s.bounds()} reach outside the box {sq.BOX}")
    found = {}
    cells = {}
    for pos, names in s.tags.items():
        x, y, z = pos
        if not (bx0 <= x <= bx1 and by0 <= y <= by1 and bz0 <= z <= bz1):
            out.append(f"{pos}: tags {names} outside the box")
        for name in names:
            if name.startswith("name="):
                continue
            if name not in sq.TAG_COUNTS:
                out.append(f"{pos}: unknown tag {name}")
                continue
            if pos in cells:
                out.append(f"{pos}: two square tags on one cell ({cells[pos]}, {name})")
            cells[pos] = name
            found.setdefault(name, []).append(pos)
    for name in sq.TAG_COUNTS:
        n = len(found.get(name, []))
        want = sq.SLOTS[level][name] if level in sq.SLOTS else None
        if want is not None and n != want:
            out.append(f"tag {name}: {n} cells at level {level}, the table of design 4.2 says {want}")
        want_cells = sorted(sq.tag_cells(level).get(name, [])) if level in sq.SLOTS else None
        if want_cells is not None and sorted(found.get(name, [])) != want_cells:
            out.append(f"tag {name}: its cells at level {level} are not the ones square_hut.SLOT_CELLS gives")
    for pos, name in sorted(cells.items()):
        block = s.get(*pos)
        if name in sq.SEAT_TAGS:
            if block is None or not voxel.parse_state(block)[0].endswith("_stairs") or "half=top" in block:
                out.append(f"{name} {pos}: not on a seat (a bottom stair), but {block}")
        elif block is not None:
            out.append(f"{name} {pos}: not air ({block})")
        if name in STANDING_TAGS:
            if not standable(s, *pos):
                out.append(f"{name} {pos}: no room to stand (solid ground, feet and head free)")
            floor = s.get(pos[0], pos[1] - 1, pos[2])
            if floor not in FULL_FLOORS:
                out.append(f"{name} {pos}: not on a full block of the blueprint (the stone mix or the pad's planks), but "
                           f"{floor} (a dirt path has no sturdy top, a substitution cell is the terrain)")
    # launch: open sky above it
    for pos in found.get("launch", []):
        over = [p for p in s.blocks if p[0] == pos[0] and p[2] == pos[2] and p[1] > pos[1]]
        if over:
            out.append(f"launch {pos}: {s.get(*over[0])} above it (the rockets need open sky)")
    # lamp posts: the log under the tag cell's lantern, air on its four sides (garlands and strings hang there)
    for pos in found.get("lamp", []):
        x, y, z = pos
        if s.get(x, 3, z) != sq.POST[2]:
            out.append(f"lamp {pos}: the post's log is not at ({x}, 3, {z}), but {s.get(x, 3, z)}")
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if s.get(x + dx, 3, z + dz) is not None:
                out.append(f"lamp {pos}: {s.get(x + dx, 3, z + dz)} beside the post's log at y 3 (strings hang there)")
    # beds: grass under the tag cell, air over it, no lamp post beside
    for pos in found.get("bed", []):
        x, y, z = pos
        if s.get(x, y - 1, z) != "minecraft:grass_block":
            out.append(f"bed {pos}: the soil under it is {s.get(x, y - 1, z)}, not minecraft:grass_block")
        if s.get(x, y + 1, z) is not None:
            out.append(f"bed {pos}: {s.get(x, y + 1, z)} above it (the touch is two blocks high at most)")
        for lx, _, lz in found.get("lamp", []):
            if abs(lx - x) + abs(lz - z) == 1:
                out.append(f"bed {pos}: beside the lamp post at ({lx}, {lz})")
    # the bottom layer: every cell set; no block with collision over a dirt path
    flat = [(x, z) for x in range(bx0, bx1 + 1) for z in range(bz0, bz1 + 1) if s.get(x, 0, z) is None]
    if flat:
        out.append(f"{len(flat)} cells of the bottom layer are not set, e.g. {flat[:3]}")
    for (x, y, z), b in sorted(s.blocks.items()):
        if y >= 1 and s.get(x, 0, z) == sq.PATH and not b.startswith("structurize:"):
            out.append(f"{(x, y, z)}: {b} stands over a dirt path (it would turn to dirt)")
            break
    trees = found.get("tree", [])
    if trees:
        tx, ty, tz = trees[0]
        if (tx, ty, tz) != sq.TREE:
            out.append(f"the tree tag {trees[0]} is not the square's centre {sq.TREE}")
        under = s.get(tx, ty - 1, tz)
        if under is None or under.startswith("structurize:"):
            out.append(f"the cell under the tree tag is {under}, not a solid block of the square")
        blocked = sorted(p for p, b in s.blocks.items() if abs(p[0] - tx) <= sq.TREE_HALF and abs(p[2] - tz) <= sq.TREE_HALF
                         and p[1] >= ty and b != KEEP)
        if blocked:
            out.append(f"{len(blocked)} blocks in the {2 * sq.TREE_HALF + 1} x {2 * sq.TREE_HALF + 1} column over the tree, "
                       f"e.g. {blocked[0]} {s.get(*blocked[0])}")
        logs = sorted(p for p, b in s.blocks.items() if voxel.parse_state(b)[0].endswith(LOG_FAMILY)
                      and max(abs(p[0] - tx), abs(p[2] - tz)) == sq.TREE_HALF + 1
                      and min(abs(p[0] - tx), abs(p[2] - tz)) <= sq.TREE_HALF)
        if logs:
            out.append(f"a log-family block {logs[0]} {s.get(*logs[0])} beside the tree's column (the Forester takes trees)")
        if by1 < ty + 17:
            out.append(f"the box top y {by1} does not hold the grand tree (18 high from y {ty})")
    if not standable(s, *sq.ENTRANCE):
        out.append(f"nowhere to stand at the entrance {sq.ENTRANCE}")
    else:
        seen = walk(s, [sq.ENTRANCE])
        if not next_to(seen, a):
            out.append(f"the hut block {a} cannot be reached from the entrance {sq.ENTRANCE}")
        for name, pos in sorted((n, p) for p, n in cells.items()):
            if name in STANDING_TAGS and pos not in seen:
                out.append(f"{name} {pos}: cannot be reached from the entrance {sq.ENTRANCE}")
            if name in sq.SEAT_TAGS and not next_to(seen, pos):
                out.append(f"{name} {pos}: no cell beside the seat can be reached from the entrance")
    return out


def square_level_problems(squares, roots):
    """The rules across the five levels ([B] design 4.1: the footprint and every tag cell are the same at every level, an
    upgrade only adds). `squares` {level: Structure}, `roots` {level: the blueprint's root compound}. Returns problems."""
    out = []
    levels = sorted(squares)
    first = levels[0]
    f = squares[first]
    for lv in levels:
        s = squares[lv]
        r = roots[lv]
        if tuple(int(r[k]) for k in ("size_x", "size_y", "size_z")) != tuple(int(roots[first][k]) for k in ("size_x", "size_y", "size_z")):
            out.append(f"level {lv}: the blueprint's size differs from level {first}'s")
        po = tuple(int(r["optional_data"]["structurize"]["primary_offset"][k]) for k in "xyz")
        po0 = tuple(int(roots[first]["optional_data"]["structurize"]["primary_offset"][k]) for k in "xyz")
        if po != po0 or s.anchor != f.anchor or s.get(*s.anchor) != f.get(*f.anchor):
            out.append(f"level {lv}: the anchor (the hut block) is not where and what it is at level {first}")
        for name in ("tree", "gift", "launch"):
            mine = sorted(p for p, ns in s.tags.items() if name in ns)
            theirs = sorted(p for p, ns in f.tags.items() if name in ns)
            if mine != theirs:
                out.append(f"level {lv}: the {name} tag is at {mine}, at level {first} it is at {theirs}")
    for lo, hi in zip(levels, levels[1:]):
        for pos, names in sorted(squares[lo].tags.items()):
            for name in names:
                if name.startswith("name="):
                    continue
                if name not in squares[hi].tags.get(pos, []):
                    out.append(f"tag {name} at {pos} of level {lo} is not there at level {hi} (a slot never moves or goes)")
        for x in range(sq.BOX[0][0], sq.BOX[1][0] + 1):
            for z in range(sq.BOX[0][2], sq.BOX[1][2] + 1):
                a, b = squares[lo].get(x, 0, z), squares[hi].get(x, 0, z)
                if a is not None and b is not None and floor_rank(b) < floor_rank(a):
                    out.append(f"the bottom layer at ({x}, {z}) goes back from {a} (level {lo}) to {b} (level {hi})")
    return out


def selftest_square():
    """Each fault planted once into a copy of a level must be found."""
    ok = True

    def planted(edit, want, level=1):
        nonlocal ok
        s = sq.square(level)
        edit(s)
        found = square_problems(s, level)
        if not any(want in f for f in found):
            problem(f"square self-test: '{want}' was not reported at level {level}: {found[:3]}")
            ok = False

    def across(edit, want):
        nonlocal ok
        squares = {lv: sq.square(lv) for lv in sq.LEVELS}
        roots = {lv: squares[lv].blueprint_nbt(f"{squares[lv].name}.blueprint", PACK_NAME, "square/x.blueprint",
                                               square_entity("x.blueprint"), HUT_MODS, box=sq.BOX) for lv in sq.LEVELS}
        edit(squares, roots)
        found = square_level_problems(squares, roots)
        if not any(want in f for f in found):
            problem(f"square self-test (levels): '{want}' was not reported: {found[:3]}")
            ok = False

    def wall(s):
        for x in range(-sq.HALF, sq.HALF + 1):
            for y in (1, 2, 3):
                s.set(x, y, 9, "minecraft:stone_bricks")

    def move_tag(s, name, frm, to):
        s.tags[frm].remove(name)
        if not s.tags[frm]:
            del s.tags[frm]
        s.tags.setdefault(to, []).append(name)

    def a_path_cell(level):
        s = sq.square(level)
        return next((x, z) for (x, y, z), b in sorted(s.blocks.items()) if y == 0 and b == sq.PATH and s.get(x, 1, z) is None
                    and abs(x) > 3 and abs(z) > 3)

    gather1 = sq.tag_cells(1)["gather"][0]
    planted(lambda s: s.set(3, 9, -2, "minecraft:stone"), "column over the tree")
    planted(lambda s: s.set(6, 3, 0, "minecraft:oak_log[axis=y]"), "log-family block")
    planted(lambda s: s.tags[sq.HUT_SPOT].append("gather"), "no square tag on the anchor")
    planted(lambda s: s.set(*gather1, "minecraft:stone"), "not air")
    planted(lambda s: s.set(0, 0, 0, None), "the cell under the tree tag")
    planted(lambda s: s.tags.pop(sq.GIFT[:3]), "tag gift: 0 cells")
    planted(lambda s: s.tags.setdefault(sq.GIFT[:3], []).append("lamp"), "two square tags on one cell")
    planted(lambda s: s.tags[(4, 1, -7)].remove("sit"), "tag sit: 1 cells")
    planted(lambda s: s.tags.setdefault((0, 1, -3), []).append("sit"), "not on a seat")
    planted(lambda s: s.tags.setdefault((0, 1, 9), []).append("tinsel"), "unknown tag tinsel")
    planted(lambda s: s.set(gather1[0], 2, gather1[2], "minecraft:stone"), "no room to stand")
    planted(wall, "cannot be reached from the entrance")
    planted(lambda s: s.set(gather1[0], 0, gather1[2], sq.PATH), "not on a full block")
    planted(lambda s: s.set(gather1[0], 0, gather1[2], sq.GROUND), "not on a full block")
    planted(lambda s: s.set(0, 5, -6, "minecraft:stone"), "above it (the rockets need open sky)")
    planted(lambda s: s.set(0, 0, 3, None), "cells of the bottom layer are not set")
    px, pz = a_path_cell(2)
    planted(lambda s: s.set(px, 1, pz, "minecraft:cobblestone_wall"), "stands over a dirt path", 2)
    lamp5 = sq.tag_cells(2)["lamp"][0]
    planted(lambda s: s.set(*lamp5, "minecraft:stone"), "not air", 5)
    planted(lambda s: s.set(lamp5[0] + 1, 3, lamp5[2], "minecraft:spruce_fence"), "beside the post's log", 2)
    planted(lambda s: s.set(lamp5[0], 3, lamp5[2], "minecraft:stone"), "the post's log is not at", 2)
    bed3 = sq.tag_cells(3)["bed"][0]
    planted(lambda s: s.set(bed3[0], 1, bed3[2], "minecraft:dirt"), "not minecraft:grass_block", 3)
    planted(lambda s: s.set(bed3[0], 3, bed3[2], "minecraft:stone"), "above it (the touch", 3)
    planted(lambda s: move_tag(s, "gather", (6, 1, -2), (6, 1, -3)), "its cells at level 3", 3)
    planted(lambda s: s.tags.pop(sq.tag_cells(4)["lamp"][-1]), "tag lamp: 7 cells", 4)
    # across the levels
    across(lambda sqs, r: move_tag(sqs[3], "gather", (6, 1, -2), (6, 1, -3)), "of level 2 is not there at level 3")
    across(lambda sqs, r: sqs[4].tags.pop(sq.tag_cells(2)["lamp"][0]), "of level 3 is not there at level 4")
    across(lambda sqs, r: move_tag(sqs[4], "launch", sq.LAUNCH, (0, 1, -5)), "the launch tag is at")
    across(lambda sqs, r: r[3].__setitem__("size_x", nbt.Short(25)), "size differs")
    across(lambda sqs, r: sqs[3].set(0, 0, 8, sq.GROUND), "goes back")
    across(lambda sqs, r: sqs[3].set(0, 0, 8, sq.PATH), "goes back")
    across(lambda sqs, r: setattr(sqs[2], "anchor", (3, 1, 8)), "the anchor")
    return ok


def check_square(label, s, level):
    ok = True
    for p in square_problems(s, level):
        problem(f"{label}: {p}")
        ok = False
    counts = {n: sum(1 for ts in s.tags.values() for t in ts if t == n) for n in sq.TAG_COUNTS}
    return ok, ", ".join(f"{n} {c}" for n, c in counts.items())


def square_tags_json():
    """What the cssquare probe expects from a pasted or built square: per level every tag's cells, the box, the `tree`
    cell and the seat tags, all relative to the hut block and unturned (the probe turns them with the building)."""
    levels = {}
    for lv in sq.LEVELS:
        s = sq.square(lv)
        ax, ay, az = s.anchor
        tags = {}
        for (x, y, z), names in sorted(s.tags.items()):
            for n in names:
                if n in sq.TAG_COUNTS:
                    tags.setdefault(n, []).append([x - ax, y - ay, z - az])
        levels[str(lv)] = {n: sorted(tags.get(n, [])) for n in sq.TAG_COUNTS}
    ax, ay, az = sq.HUT_SPOT
    (x0, y0, z0), (x1, y1, z1) = sq.BOX
    tx, ty, tz = sq.TREE
    return {"tags": list(sq.TAG_COUNTS), "seat": list(sq.SEAT_TAGS), "standing": list(STANDING_TAGS),
            "box": [[x0 - ax, y0 - ay, z0 - az], [x1 - ax, y1 - ay, z1 - az]],
            "tree": [tx - ax, ty - ay, tz - az], "treeHalf": sq.TREE_HALF, "levels": levels}


def square_tags_text():
    """square_tags_json() as written: one tag to a line."""
    d = square_tags_json()
    lines = ["{"] + [f" {json.dumps(k)}: {json.dumps(v)}," for k, v in d.items() if k != "levels"] + [' "levels": {']
    for i, (lv, tags) in enumerate(d["levels"].items()):
        lines.append(f"  {json.dumps(lv)}: {{")
        lines += [f"   {json.dumps(n)}: {json.dumps(c)}" + ("," if j < len(tags) - 1 else "") for j, (n, c) in enumerate(tags.items())]
        lines.append("  }" + ("," if i < len(d["levels"]) - 1 else ""))
    lines += [" }", "}"]
    text = NL.join(lines) + NL
    if json.loads(text) != d:
        raise ValueError("square_tags_text does not read back as square_tags_json")
    return text


def check_piece(label, s):
    ok = True
    a = s.anchor
    if a != tp.ANCHOR or s.get(*a) is not None:
        problem(f"{label}: the anchor {a} must be the air cell {tp.ANCHOR} where the marker stands")
        ok = False
    if s.tags.get(a) != ["invisible"]:
        problem(f"{label}: the anchor's tags {s.tags.get(a)} are not ['invisible']")
        ok = False
    want = {"gift_pile": f"{NS}:gift_pile", "firework_crate": f"{NS}:firework_crate"}
    for tag, block in want.items():
        tagged = {p for p, ts in s.tags.items() if tag in ts}
        placed = {p for p, b in s.blocks.items() if voxel.parse_state(b)[0] == block}
        if tagged != placed:
            problem(f"{label}: '{tag}' tags {sorted(tagged)} are not the {block} cells {sorted(placed)}")
            ok = False
    stars = [p for p, ts in s.tags.items() if "star" in ts]
    star_ok = len(stars) == 1 and voxel.parse_state(s.get(*stars[0]) or "minecraft:air")[0] in (
        f"{NS}:tree_star", f"{NS}:large_star") and "half=upper" not in s.get(*stars[0])
    if not star_ok:
        problem(f"{label}: the 'star' tag must be on the one star cell (the lower half of a large star), not {stars}")
        ok = False
    unknown = {t for ts in s.tags.values() for t in ts} - {"invisible", "gift_pile", "firework_crate", "star"}
    if unknown:
        problem(f"{label}: unknown tags {unknown}")
        ok = False
    logs = sorted(p for p, b in s.blocks.items() if voxel.parse_state(b)[0] == TRUNK)
    column = [(0, y, 0) for y in range(a[1] + 1, a[1] + 1 + len(logs))]
    if not logs or logs != column:
        problem(f"{label}: the trunk {logs[:3]}... is not one {TRUNK} column standing on the marker")
        ok = False
    vanilla_logs = sorted(p for p, b in s.blocks.items() if voxel.parse_state(b)[0].endswith(("_log", "_wood", "_stem", "_hyphae")))
    if vanilla_logs:
        problem(f"{label}: log blocks {vanilla_logs[:3]} in the piece (the Forester would cut the tree; the trunk is {TRUNK})")
        ok = False
    for p, b in s.blocks.items():
        if voxel.parse_state(b)[0] == f"{NS}:firework_crate":
            above = [q for q in s.blocks if q[0] == p[0] and q[2] == p[2] and q[1] > p[1]]
            if above:
                problem(f"{label}: the crate at {p} has {s.get(*above[0])} above it (rockets need open sky)")
                ok = False
    return ok, f"{len(logs)} logs, {len(stars)} star, {sum('gift_pile' in t for t in s.tags.values())} gifts"


def check_touch(label, s):
    """A seasonal touch (square_hut.touch): an air anchor tagged `invisible`, one 1 x 1 column of at most two blocks
    above it, only whole blocks that need no support and do not change by themselves, no other tag."""
    ok = True
    a = s.anchor
    if a != sq.TOUCH_ANCHOR or s.get(*a) is not None:
        problem(f"{label}: the anchor {a} must be the air cell {sq.TOUCH_ANCHOR}")
        ok = False
    if s.tags != {a: ["invisible"]}:
        problem(f"{label}: the tags {s.tags} are not just the anchor's ['invisible']")
        ok = False
    for p, b in sorted(s.blocks.items()):
        name, props = voxel.parse_state(b)
        if (p[0], p[2]) != (a[0], a[2]):
            problem(f"{label}: {p} is outside the 1 x 1 column")
            ok = False
        if not a[1] < p[1] <= a[1] + 2:
            problem(f"{label}: {p} is not within two blocks above the anchor")
            ok = False
        leaf = name in TOUCH_LEAVES and props.get("persistent") == "true"
        if not (leaf or name in TOUCH_BLOCKS):
            problem(f"{label}: {b} is not a block that needs no support and never changes by itself")
            ok = False
    if not s.blocks:
        problem(f"{label}: no block")
        ok = False
    return ok, f"{len(s.blocks)} block(s), anchor {a}"


def check_pieces_on_squares(squares):
    """The pieces against the square (Q6): at every level every `bed` tag takes every touch (its anchor on the soil under
    the tag, its blocks into air inside the box), and every Winter centrepiece of a level up to the square's stands
    inside the 11 x 11 column over `tree`, inside the box and on free cells (air or substitution). Returns the number of
    placements checked; problems are reported."""
    n = 0
    (bx0, by0, bz0), (bx1, by1, bz1) = sq.BOX
    tx, ty, tz = sq.TREE
    for lv, s in sorted(squares.items()):
        for (x, y, z) in sq.tag_cells(lv).get("bed", []):
            for season in sq.TOUCHES:
                piece = sq.touch(season)
                ax, ay, az = piece.anchor
                for (px, py, pz), b in piece.blocks.items():
                    w = (x + px - ax, y - 1 + py - ay, z + pz - az)
                    if not (bx0 <= w[0] <= bx1 and by0 <= w[1] <= by1 and bz0 <= w[2] <= bz1) or s.get(*w) is not None:
                        problem(f"level {lv}: the {season} touch's block at {w} (bed tag {(x, y, z)}) is not on free air "
                                f"inside the box: {s.get(*w)}")
                n += 1
        for name in tp.PIECES:
            piece = tp.piece(name)
            ax, ay, az = piece.anchor
            for (px, py, pz) in piece.blocks:
                w = (tx + px - ax, ty + py - ay, tz + pz - az)
                if not (abs(w[0] - tx) <= sq.TREE_HALF and abs(w[2] - tz) <= sq.TREE_HALF and by0 <= w[1] <= by1):
                    problem(f"level {lv}: {name} reaches {w}, outside the tree's {2 * sq.TREE_HALF + 1} x "
                            f"{2 * sq.TREE_HALF + 1} column or the box")
                    break
                b = s.get(*w)
                if b is not None and b != KEEP:
                    problem(f"level {lv}: {name}'s block at {w} lands on {b}")
                    break
            n += 1
    return n


def compare_squares_with_designs():
    """Each level against the owner's picked look A (design/src/squares.py square('A', level, season=None)): from the
    first layer up, inside the box, the one difference is the hut block (the picture's draft model is the real one)."""
    saved = list(sys.path)
    sys.path.insert(0, os.path.join(paths.ROOT, "design", "src"))
    try:
        import squares      # noqa: E402
    finally:
        sys.path[:] = saved
    (bx0, by0, bz0), (bx1, by1, bz1) = sq.BOX
    out = []
    for lv in sq.LEVELS:
        want = {p: b for p, b in squares.square("A", lv, season=None).items()
                if p[1] >= 1 and bx0 <= p[0] <= bx1 and bz0 <= p[2] <= bz1}
        mine = {p: b for p, b in sq.square(lv).blocks.items() if p[1] >= 1}
        diff = sorted(p for p in set(want) | set(mine) if want.get(p) != mine.get(p))
        if diff != [sq.HUT_SPOT]:
            problem(f"townsquare{lv} differs from the picked look A where it should not: "
                    f"{[(p, want.get(p), mine.get(p)) for p in diff[:4]]}")
            out.append((lv, False))
        else:
            out.append((lv, True))
    return out


def design_compare(label, mine, design, expected, mapper, inside=None, ignore=()):
    """Cells where `mine` differs from the owner's picked design (ids mapped, ground patch left out) must be exactly
    `expected`."""
    GROUND_PATCH = {"minecraft:grass_block", "minecraft:dirt", "minecraft:snow", "minecraft:snow_block",
                    "minecraft:dirt_path"}
    D = {}
    for p, b in design.blocks.items():
        if p in ignore or voxel.parse_state(b)[0] in GROUND_PATCH or (inside and not inside(p)):
            continue
        D[p] = voxel.parse_state(mapper(b))
    M = {p: voxel.parse_state(b) for p, b in mine.blocks.items() if not b.startswith("structurize:")}
    diff = {p for p in set(D) | set(M) if D.get(p) != M.get(p)}
    extra = sorted(diff - set(expected))
    missing = sorted(set(expected) - diff)
    if extra or missing:
        problem(f"{label}: differs from the picked design where it should not: "
                f"{[(p, D.get(p), M.get(p)) for p in extra[:4]]}; meant to differ but does not: {missing[:4]}")
        return False
    kinds = sorted(set(expected.values()))
    return f"{len(diff)} cells differ, all listed ({', '.join(kinds)})"


def compare_with_designs():
    saved = list(sys.path)
    sys.path.insert(0, os.path.join(paths.ROOT, "design", "src"))
    try:
        # design/src: the owner's look options. Its common.py puts design/tools first on the path and imports voxel
        # from there; ours is imported already, and the design builds only use what both copies have.
        import huts      # noqa: E402
        import trees     # noqa: E402
    finally:
        sys.path[:] = saved
    results = []
    (bx0, by0, bz0), (bx1, by1, bz1) = BOX
    inside = lambda p: bx0 <= p[0] <= bx1 and by0 <= p[1] <= by1 and bz0 <= p[2] <= bz1   # noqa: E731
    r = design_compare("workshop5 vs design hut A", dh.workshop(5), huts.build_a(), dh.design_diff(),
                       tp.ship_design_state, inside)
    results.append(("workshop5 vs design hut A", r))
    px, pz = trees.SCALE_POST
    post = {(px, y, pz) for y in range(0, 4)}
    plaza = lambda p: p[1] >= 1   # noqa: E731  the paved square (y = 0) is the design's ground patch
    for name, fn, letter in (("village_spruce_full", trees.build_a, "A"), ("grand_tree", trees.build_b, "B")):
        r = design_compare(f"{name} vs design tree {letter}", tp.DESIGNS[name](), fn(), tp.design_diff(name),
                           tp.ship_design_state, plaza, ignore=post)
        results.append((f"{name} vs design tree {letter}", r))
    return results


# ---------------------------------------------------------------------------------------------- read-back
def readback(label, path, s, root, box):
    """Read the written bytes back and compare: every block, the anchor, the tags, the anchor block entity."""
    back, te = voxel.read_blueprint(nbt.loads(nbt.dumps(root))[1])
    (x0, y0, z0), _ = box if box else s.bounds()
    norm = {(x - x0, y - y0, z - z0): voxel.parse_state(b) for (x, y, z), b in s.blocks.items()}
    same = norm == {p: voxel.parse_state(b) for p, b in back.blocks.items()}
    anchor_ok = back.anchor == (s.anchor[0] - x0, s.anchor[1] - y0, s.anchor[2] - z0)
    tags_ok = {(x - x0, y - y0, z - z0): t for (x, y, z), t in s.tags.items()} == back.tags
    te_ok = te is not None and te["id"] == root["tile_entities"][0]["id"] and \
        te["blueprintDataProvider"]["path"] == path and te["blueprintDataProvider"]["pack"] == PACK_NAME and \
        te["blueprintDataProvider"]["schematicName"] == os.path.basename(path)[:-len(".blueprint")]
    if not (same and anchor_ok and tags_ok and te_ok):
        problem(f"{label}: read-back blocks={same} anchor={anchor_ok} tags={tags_ok} anchor entity={te_ok}")
        return False
    return True


def manifest_entry(path, s, root, box):
    """What the paste probe checks: per block id the count (substitution cells left out: the world decides what
    stands there), our blocks' exact states, the palette as written, the tags relative to the anchor, the anchor and
    the size. Positions are relative to the anchor."""
    (x0, y0, z0), _ = box if box else s.bounds()
    ax, ay, az = s.anchor
    counts = {}
    subst = []
    for (x, y, z), b in s.blocks.items():
        n = voxel.parse_state(b)[0]
        if n.startswith("structurize:"):
            subst.append([x - ax, y - ay, z - az])     # the world decides what stands there
            continue
        counts[n] = counts.get(n, 0) + 1
    ours = sorted([[x - ax, y - ay, z - az, b] for (x, y, z), b in s.blocks.items() if b.startswith(NS + ":")])
    kind = "hut" if path.startswith(HUT_FOLDER + "/") else "square" if path.startswith(SQUARE_FOLDER + "/") else "piece"
    building = {"hut": (BUILDING_TYPE, HUT_FOLDER), "square": (SQUARE_TYPE, SQUARE_FOLDER)}.get(kind)
    return {
        "path": path,
        "kind": kind,
        **({"buildingType": building[0], "folder": building[1]} if building else {}),
        "size": [int(root["size_x"]), int(root["size_y"]), int(root["size_z"])],
        "anchor": [ax - x0, ay - y0, az - z0],
        "anchorBlock": s.get(*s.anchor) or "minecraft:air",
        "anchorEntity": root["tile_entities"][0]["id"],
        "counts": dict(sorted(counts.items())),
        "ours": ours,
        "subst": sorted(subst),
        "palette": sorted({b for b in s.blocks.values()}),
        "tags": sorted([[x - ax, y - ay, z - az, t] for (x, y, z), ts in s.tags.items() for t in ts]),
    }


# ---------------------------------------------------------------------------------------------- main
def main(argv):
    check_only = "--check" in argv
    args = [a for a in argv if not a.startswith("--")]
    mods_dir = args[0] if args else os.path.join(paths.ROOT, "libs")
    mod_jars = [os.path.join(mods_dir, f) for f in sorted(os.listdir(mods_dir))
                if re.match(r"(structurize|minecolonies)-.*\.jar$", f)] if os.path.isdir(mods_dir) else []
    if len(mod_jars) != 2:
        problem(f"the Structurize and MineColonies jars are not both in {mods_dir}: {mod_jars}")
    states = Blockstates(mod_jars)

    files = hut_files() + square_files() + piece_files()
    written = {}
    manifest = {"pack": PACK_NAME, "files": []}
    for path, s, root, box in files:
        label = path
        ok = states.check(label, s)
        if path.startswith(HUT_FOLDER + "/"):
            level = int(re.search(r"(\d)\.blueprint$", path).group(1))
            ok = check_common(label, s) and ok
            r = check_hut(label, s, level)
            info = r[1]
            ok = r[0] and ok
        elif path.startswith(SQUARE_FOLDER + "/"):
            ok = check_common(label, s) and ok
            r = check_square(label, s, int(re.search(r"(\d)\.blueprint$", path).group(1)))
            info = r[1]
            ok = r[0] and ok
        elif os.path.basename(path).startswith("bed_"):
            ok = check_common(label, s, supports=[s.anchor]) and ok
            r = check_touch(label, s)
            info = r[1]
            ok = r[0] and ok
        else:
            ok = check_common(label, s, supports=[s.anchor]) and ok
            r = check_piece(label, s)
            info = r[1]
            ok = r[0] and ok
        ok = readback(label, path, s, root, box) and ok
        data = nbt.dumps(root)
        written[path] = root
        manifest["files"].append(manifest_entry(path, s, root, box))
        print(f"{path:38s} {str(tuple(int(root[k]) for k in ('size_x', 'size_y', 'size_z'))):14s} "
              f"{len(s.blocks):4d} blocks  {len(data):6d} B nbt  {'ok' if ok else 'PROBLEMS'}  {info}")

    anchors = {s.anchor for p, s, _, _ in files if p.startswith(HUT_FOLDER + "/")}
    if anchors != {dh.HUT_SPOT}:
        problem(f"the hut levels do not share one anchor: {anchors}")
    for label, r in compare_with_designs():
        print(f"{label:38s} {r if r else 'PROBLEMS'}")
    storage_ok, rack_counts = check_storage()
    print(f"{'storage room (S10e)':38s} racks at levels 1 to 5: {rack_counts}  {'ok' if storage_ok else 'PROBLEMS'}")
    squares = {int(re.search(r"(\d)\.blueprint$", p).group(1)): s for p, s, _, _ in files if p.startswith(SQUARE_FOLDER + "/")}
    square_roots = {int(re.search(r"(\d)\.blueprint$", p).group(1)): r for p, _, r, _ in files if p.startswith(SQUARE_FOLDER + "/")}
    level_problems = square_level_problems(squares, square_roots)
    for p in level_problems:
        problem(f"town square levels: {p}")
    print(f"{'town square, five levels (Q6)':38s} same box, anchor, tree/gift/launch cells; tags only added; paving only "
          f"grows  {'ok' if not level_problems else 'PROBLEMS'}")
    placed = check_pieces_on_squares(squares)
    print(f"{'pieces on the square (Q6)':38s} {placed} placements (touches on every bed tag, every winter centrepiece "
          f"in the tree column)")
    for lv, good in compare_squares_with_designs():
        print(f"{f'townsquare{lv} vs design look A':38s} {'only the hut block differs' if good else 'PROBLEMS'}")
    square_ok = selftest_square()
    print(f"{'town square self-test (Q1, Q6)':38s} {'ok' if square_ok else 'PROBLEMS'}")
    spots_ok, spot_counts = check_spots()
    print(f"{'storeroom spots (S10g1)':38s} packed / hung at levels 1 to 5: "
          f"{' '.join(f'{p}/{h}' for p, h in spot_counts)}  {'ok' if spots_ok else 'PROBLEMS'}")

    # the files on disk
    manifest_text = json.dumps(manifest, indent=1, sort_keys=False) + "\n"
    targets = {os.path.join(PACK_DIR, "pack.json"): pack_json().encode("utf-8"),
               MANIFEST: manifest_text.encode("utf-8"),
               SPOTS: spots_text().encode("utf-8"),
               SETS: sets_text().encode("utf-8"),
               SQUARE_TAGS: square_tags_text().encode("utf-8")}
    for path, root in written.items():
        buf = io.BytesIO()
        with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0) as g:
            g.write(nbt.dumps(root))
        targets[os.path.join(PACK_DIR, *path.split("/"))] = buf.getvalue()
    if check_only:
        stale = []
        for f, data in sorted(targets.items()):
            try:
                with open(f, "rb") as fh:
                    if fh.read() != data:
                        stale.append(f)
            except FileNotFoundError:
                stale.append(f)
        if stale:
            problem(f"{len(stale)} files are not what the build writes (run python tools/build_pack.py): "
                    f"{[os.path.relpath(f, paths.ROOT) for f in stale]}")
        else:
            print(f"{len(targets)} files on disk are what the build writes")
    else:
        for f, data in sorted(targets.items()):
            os.makedirs(os.path.dirname(f), exist_ok=True)
            with open(f, "wb") as fh:
                fh.write(data)
        print(f"wrote {len(targets)} files")
    # every written blueprint read back from disk
    for path, s, root, box in files:
        f = os.path.join(PACK_DIR, *path.split("/"))
        if os.path.exists(f) and nbt.load_gz(f) != root:
            problem(f"{path}: the file on disk does not read back as written")
    if not os.path.exists(os.path.join(PACK_DIR, ICON)):
        problem(f"no pack icon {ICON} (python tools/render_s5.py draws it)")
    leftovers = sorted(set(os.path.relpath(os.path.join(d, f), PACK_DIR).replace(os.sep, "/")
                           for d, _, fs in os.walk(PACK_DIR) for f in fs)
                       - {p for p, *_ in files} - {"pack.json", ICON})
    if leftovers:
        problem(f"files in the pack that the build does not make: {leftovers}")
    print("ALL OK" if not problems else f"PROBLEMS ({len(problems)})")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
