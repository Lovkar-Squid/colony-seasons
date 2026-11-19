"""Build and check the Structurize pack shipped in the Colony Seasons jar (copied from Voyager 0.4.0's
tools/build_pack.py and adapted).

    python -I tools/build_pack.py [<mods folder>]          writes the pack and the paste probe's manifest, then checks
    python -I tools/build_pack.py --check [<mods folder>]  checks, and that the files on disk are what the build writes

<mods folder> holds the Structurize and MineColonies jars whose block ids the blueprints use (tools/checks.sh passes
the rig's basemods/); the Minecraft client assets jar comes from tools/paths.py. Writes:

  resources/blueprints/colony_seasons/colony_seasons/pack.json         the pack ("Colony Seasons", its own pack)
  .../decorator/workshop1..5.blueprint                                  the Decorator's hut (tools/decorator_hut.py)
  .../pieces/village_spruce_small|full|rich.blueprint, grand_tree.blueprint   the tree pieces (tools/tree_pieces.py)
  tools/cspaste/res/cspaste/manifest.json                               what the paste probe expects to read back

The icon (colony_seasons.png next to pack.json) is drawn by tools/render_s5.py; this script only checks it is there.
Checks, each printed, then ALL OK or PROBLEMS: every block id exists (client jar, our blockstates, the mod jars) with
real properties and values; our decorations are drawn unlit; no floating blocks; nothing that would pop off (ladders,
lanterns, wall decorations, doors, carpets); no bed; the hut block is reachable from the door and every floor cell from
the hut block; the levels share one box and one anchor; level 5 and the full and grand trees differ from the owner's
picked designs only where decorator_hut.DESIGN_CHANGES / tree_pieces.DESIGN_CHANGES say; the piece contract (air
anchor, tags, trunk, open sky over the crate); and a read-back of every written file.
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
import tree_pieces as tp  # noqa: E402
import voxel  # noqa: E402
from floatcheck import attachment, components, floating  # noqa: E402
from walkcheck import next_to, standable, walk  # noqa: E402

PACK_NAME = "Colony Seasons"
PACK_DIR = paths.res("blueprints", "colony_seasons", "colony_seasons")
ICON = "colony_seasons.png"
HUT_FOLDER = "decorator"
PIECE_FOLDER = "pieces"
BUILDING_TYPE = "colony_seasons:decorator"         # DecoratorSetup.BUILDING_ID
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

problems = []


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
    logs = sorted(p for p, b in s.blocks.items() if voxel.parse_state(b)[0] == "minecraft:spruce_log")
    column = [(0, y, 0) for y in range(a[1] + 1, a[1] + 1 + len(logs))]
    if logs != column:
        problem(f"{label}: the trunk {logs[:3]}... is not one log column standing on the marker")
        ok = False
    for p, b in s.blocks.items():
        if voxel.parse_state(b)[0] == f"{NS}:firework_crate":
            above = [q for q in s.blocks if q[0] == p[0] and q[2] == p[2] and q[1] > p[1]]
            if above:
                problem(f"{label}: the crate at {p} has {s.get(*above[0])} above it (rockets need open sky)")
                ok = False
    return ok, f"{len(logs)} logs, {len(stars)} star, {sum('gift_pile' in t for t in s.tags.values())} gifts"


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
    return {
        "path": path,
        "kind": "hut" if path.startswith(HUT_FOLDER + "/") else "piece",
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

    files = hut_files() + piece_files()
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

    # the files on disk
    manifest_text = json.dumps(manifest, indent=1, sort_keys=False) + "\n"
    targets = {os.path.join(PACK_DIR, "pack.json"): pack_json().encode("utf-8"),
               MANIFEST: manifest_text.encode("utf-8")}
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
