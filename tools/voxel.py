"""Voxel structure DSL for the Colony Seasons blueprints (copied from Voyager 0.4.0's tools/voxel.py and adapted).

A Structure is a sparse dict of block positions -> block state strings ("minecraft:oak_stairs[facing=north,half=bottom]").
The same data feeds the renders (tools/render_s5.py) and the Structurize .blueprint export (to_blueprint), so what the
owner sees in a render is exactly what the build tool and the Decorator place.

Coordinates: x east, y up, z south (Minecraft). Origin is free; export normalises to the bounding box (or a given box).
Changes from Voyager's copy: the NBT goes through tools/nbt.py instead of nbtlib (the checks run with `python -I`);
the anchor's block entity is given by the caller, so a hut (its block entity on the hut block) and a piece (the tag
data on an air cell where the festival marker stands) share one writer; Domum Ornamentum blocks and entities are left
out (this pack uses neither); `rotated_180` for pieces that are drawn facing north from a design that faces south.
"""
import math
import re

import nbt

AIR = "minecraft:air"
DATA_VERSION = 3955  # 1.21.1


def parse_state(s):
    """'ns:name[a=b,c=d]' -> ('ns:name', {'a': 'b', 'c': 'd'})"""
    m = re.match(r"^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[(.*)\])?$", s.strip())
    if not m:
        raise ValueError("bad block state: " + s)
    name, props = m.group(1), {}
    if m.group(2):
        for kv in m.group(2).split(","):
            k, v = kv.split("=")
            props[k.strip()] = v.strip()
    return name, props


def state_string(name, props):
    return name + ("[" + ",".join(f"{k}={props[k]}" for k in sorted(props)) + "]" if props else "")


# A half turn of a block state: facings swap, axes stay. Covers every rotating property this pack uses.
_OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "up", "down": "down"}


def rotate_state_180(state):
    name, props = parse_state(state)
    if not props:
        return state
    out = dict(props)
    if "facing" in props:
        out["facing"] = _OPPOSITE[props["facing"]]
    for a, b in (("north", "south"), ("east", "west")):
        if a in props and b in props:
            out[a], out[b] = props[b], props[a]
    return state_string(name, out) if out != props else state


class Structure:
    def __init__(self, name):
        self.name = name
        self.blocks = {}
        self.tags = {}
        self.anchor = None

    # ---------------------------------------------------------------- editing
    def set(self, x, y, z, block):
        if block is None or block == AIR:
            self.blocks.pop((x, y, z), None)
        else:
            self.blocks[(x, y, z)] = block

    def get(self, x, y, z):
        return self.blocks.get((x, y, z))

    def box(self, x0, y0, z0, x1, y1, z1, block, hollow=False):
        """Inclusive box. hollow=True keeps only the shell."""
        xa, xb = sorted((x0, x1)); ya, yb = sorted((y0, y1)); za, zb = sorted((z0, z1))
        for x in range(xa, xb + 1):
            for y in range(ya, yb + 1):
                for z in range(za, zb + 1):
                    if hollow and xa < x < xb and ya < y < yb and za < z < zb:
                        continue
                    self.set(x, y, z, block)

    def column(self, x, z, y0, y1, block):
        for y in range(min(y0, y1), max(y0, y1) + 1):
            self.set(x, y, z, block)

    def disc(self, cx, cz, y, r, block, ring=False, inner=None):
        """Filled or ring disc, r may be fractional."""
        for x in range(int(math.floor(cx - r)) - 1, int(math.ceil(cx + r)) + 2):
            for z in range(int(math.floor(cz - r)) - 1, int(math.ceil(cz + r)) + 2):
                d = math.hypot(x - cx, z - cz)
                if ring:
                    lo = inner if inner is not None else r - 1
                    if lo < d <= r:
                        self.set(x, y, z, block)
                elif d <= r:
                    self.set(x, y, z, block)

    def tag(self, x, y, z, *names):
        self.tags.setdefault((x, y, z), []).extend(names)

    def set_anchor(self, x, y, z, block=None):
        """The blueprint's primary offset. A hut gives its hut block; a piece gives None (the cell stays air)."""
        self.anchor = (x, y, z)
        if block is not None:
            self.set(x, y, z, block)

    def translated(self, dx, dy, dz):
        """A copy of this structure moved by (dx, dy, dz), tags and anchor included."""
        t = Structure(self.name)
        t.merge(self, dx, dy, dz)
        if self.anchor is not None:
            t.anchor = (self.anchor[0] + dx, self.anchor[1] + dy, self.anchor[2] + dz)
        return t

    def rotated_180(self, cx=0, cz=0):
        """A copy turned half round the vertical axis through (cx, cz): block states, tags and anchor included."""
        t = Structure(self.name)
        for (x, y, z), b in self.blocks.items():
            t.set(2 * cx - x, y, 2 * cz - z, rotate_state_180(b))
        for (x, y, z), names in self.tags.items():
            t.tag(2 * cx - x, y, 2 * cz - z, *names)
        if self.anchor is not None:
            x, y, z = self.anchor
            t.anchor = (2 * cx - x, y, 2 * cz - z)
        return t

    def merge(self, other, dx=0, dy=0, dz=0):
        for (x, y, z), b in other.blocks.items():
            self.set(x + dx, y + dy, z + dz, b)
        for (x, y, z), t in other.tags.items():
            self.tag(x + dx, y + dy, z + dz, *t)

    # ---------------------------------------------------------------- queries
    def bounds(self):
        pts = list(self.blocks) + ([self.anchor] if self.anchor is not None else [])
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]; zs = [p[2] for p in pts]
        return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))

    def size(self):
        (x0, y0, z0), (x1, y1, z1) = self.bounds()
        return x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1

    def count(self):
        from collections import Counter
        return Counter(parse_state(b)[0] for b in self.blocks.values())

    # ---------------------------------------------------------------- export
    def blueprint_nbt(self, file_name, pack_name, pack_path, anchor_entity, required_mods, box=None):
        """The root compound of a Structurize v1 .blueprint.

        file_name: 'workshop1.blueprint'; pack_path: the path inside the pack, 'decorator/workshop1.blueprint'.
        anchor_entity: the block entity at the anchor without its position and tag data, e.g. a hut's
        {"id": "colony_seasons:colonybuilding", "type": "colony_seasons:decorator", ...}; this writer adds x/y/z and
        the blueprintDataProvider (corners, path, schematicName, pack and the tags, relative to the anchor), which is
        where Structurize reads a blueprint's tags from (BlueprintTagUtils.getBlueprintTags).
        box: optional ((x0, y0, z0), (x1, y1, z1)) forcing the blueprint size (filled with air, which the builder
        clears) so every level shares one footprint.
        """
        if self.anchor is None:
            raise ValueError(f"{self.name}: no anchor")
        (x0, y0, z0), (x1, y1, z1) = self.bounds()
        if box is not None:
            (bx0, by0, bz0), (bx1, by1, bz1) = box
            if bx0 > x0 or by0 > y0 or bz0 > z0 or bx1 < x1 or by1 < y1 or bz1 < z1:
                raise ValueError(f"{self.name}: blocks {self.bounds()} exceed the box {box}")
            (x0, y0, z0), (x1, y1, z1) = (bx0, by0, bz0), (bx1, by1, bz1)
        sx, sy, sz = x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1
        palette = [AIR]
        index = {AIR: 0}
        arr = [0] * (sx * sy * sz)
        for (x, y, z), b in sorted(self.blocks.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
            if b not in index:
                index[b] = len(palette)
                palette.append(b)
            arr[(y - y0) * sz * sx + (z - z0) * sx + (x - x0)] = index[b]
        packed = []
        for i in range(0, len(arr), 2):
            hi = arr[i]
            lo = arr[i + 1] if i + 1 < len(arr) else 0
            v = (hi << 16) | lo
            if v >= 1 << 31:
                v -= 1 << 32
            packed.append(v)
        pal = []
        for b in palette:
            name, props = parse_state(b)
            c = {"Name": name}
            if props:
                c["Properties"] = {k: props[k] for k in sorted(props)}
            pal.append(c)
        ax, ay, az = self.anchor
        tag_map = []
        for (tx, ty, tz), names in sorted(self.tags.items()):
            tag_map.append({
                "tagPos": {"x": nbt.Int(tx - ax), "y": nbt.Int(ty - ay), "z": nbt.Int(tz - az)},
                "tagNameList": nbt.List(nbt.COMPOUND, [{"tagName": n} for n in names]),
            })
        schematic_name = file_name.replace(".blueprint", "")
        te = {"id": anchor_entity["id"],
              "x": nbt.Short(ax - x0), "y": nbt.Short(ay - y0), "z": nbt.Short(az - z0)}
        for k, v in anchor_entity.items():
            if k != "id":
                te[k] = v
        te["blueprintDataProvider"] = {
            "corner1": {"x": nbt.Int(x0 - ax), "y": nbt.Int(y0 - ay), "z": nbt.Int(z0 - az)},
            "corner2": {"x": nbt.Int(x1 - ax), "y": nbt.Int(y1 - ay), "z": nbt.Int(z1 - az)},
            "path": pack_path,
            "schematicName": schematic_name,
            "pack": pack_name,
            "posTagMap": nbt.List(nbt.COMPOUND, tag_map),
        }
        return {
            "version": nbt.Byte(1),
            "mcversion": nbt.Int(DATA_VERSION),
            "name": file_name,
            "size_x": nbt.Short(sx), "size_y": nbt.Short(sy), "size_z": nbt.Short(sz),
            "required_mods": nbt.List(nbt.STRING, list(required_mods)),
            "palette": nbt.List(nbt.COMPOUND, pal),
            "blocks": nbt.IntArray(packed),
            "tile_entities": nbt.List(nbt.COMPOUND, [te]),
            "entities": nbt.List(nbt.COMPOUND, []),
            "optional_data": {"structurize": {
                "primary_offset": {"x": nbt.Int(ax - x0), "y": nbt.Int(ay - y0), "z": nbt.Int(az - z0)}}},
        }

    def to_blueprint(self, path, *args, **kwargs):
        nbt.dump_gz(self.blueprint_nbt(*args, **kwargs), path)
        return path


def read_blueprint(root):
    """A blueprint root compound back into a Structure (blocks, anchor, tags) plus its anchor block entity."""
    sx, sy, sz = int(root["size_x"]), int(root["size_y"]), int(root["size_z"])
    raw = [v & 0xFFFFFFFF for v in root["blocks"].values]
    arr = []
    for v in raw:
        arr += [(v >> 16) & 0xFFFF, v & 0xFFFF]
    pal = []
    for p in root["palette"]:
        props = {k: v for k, v in p.get("Properties", {}).items()}
        pal.append(state_string(p["Name"], props))
    s = Structure(root["name"].replace(".blueprint", ""))
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                b = pal[arr[y * sz * sx + z * sx + x]]
                if b != AIR:
                    s.set(x, y, z, b)
    po = root["optional_data"]["structurize"]["primary_offset"]
    s.anchor = (int(po["x"]), int(po["y"]), int(po["z"]))
    anchor_te = None
    for te in root["tile_entities"]:
        if (int(te["x"]), int(te["y"]), int(te["z"])) == s.anchor:
            anchor_te = te
    if anchor_te is not None and "blueprintDataProvider" in anchor_te:
        ax, ay, az = s.anchor
        for entry in anchor_te["blueprintDataProvider"]["posTagMap"]:
            p = entry["tagPos"]
            s.tag(int(p["x"]) + ax, int(p["y"]) + ay, int(p["z"]) + az, *[n["tagName"] for n in entry["tagNameList"]])
    return s, anchor_te


def load_blueprint(path):
    return read_blueprint(nbt.load_gz(path))
