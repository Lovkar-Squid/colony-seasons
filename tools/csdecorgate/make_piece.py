"""The csdecorgate probe's own square piece: a small asymmetric test tree in a Structurize pack of the probe.

It follows the S4/S5 contract for the festival pieces (BUILDING.md, PieceLoader): drawn facing north; the primary
offset is the festival marker's cell, which is air in the piece (it carries the anchor's block entity data with the
position tags); tags gift_pile, firework_crate and star; the trunk is the log blocks. The same piece is written under
the four file names the Decorator asks for per hut level, so the probe can drive the real code path.

Pack folder: tools/csdecorgate/res/blueprints/colonyseasonsdecorgate/cstest/ (pack name "Colony Seasons Test").
The cells are listed once more, by hand, in CsDecorGate.TEST_PIECE: the probe checks the placed tree against that
list in all four facings, independently of Structurize's rotation.

Run:  python -I tools/csdecorgate/make_piece.py            writes the files
      python -I tools/csdecorgate/make_piece.py --check    exits 1 when the committed files differ from what it writes
Pure Python (gzip with a fixed time stamp), the same bytes on every run.
"""
import gzip
import io
import json
import pathlib
import struct
import sys

HERE = pathlib.Path(__file__).resolve().parent
PACK_DIR = HERE / "res" / "blueprints" / "colonyseasonsdecorgate" / "cstest"
PACK_NAME = "Colony Seasons Test"
DATA_VERSION = 3955          # Minecraft 1.21.1
FILES = ["village_spruce_small", "village_spruce_full", "village_spruce_rich", "grand_tree"]

SIZE = (5, 7, 5)
ANCHOR = (2, 0, 4)           # the marker's cell: air in the piece
LEAVES = "minecraft:spruce_leaves[distance=1,persistent=true,waterlogged=false]"
CELLS = [
    # trunk
    ((2, 0, 1), "minecraft:spruce_log[axis=y]"),
    ((2, 1, 1), "minecraft:spruce_log[axis=y]"),
    ((2, 2, 1), "minecraft:spruce_log[axis=y]"),
    ((2, 3, 1), "minecraft:spruce_log[axis=y]"),
    ((2, 4, 1), "minecraft:spruce_log[axis=y]"),
    # one branch to the east, so a turn is easy to see
    ((3, 2, 1), "minecraft:spruce_log[axis=x]"),
    # leaves and ornaments
    ((1, 2, 1), LEAVES),
    ((2, 2, 0), LEAVES),
    ((2, 2, 2), "colony_seasons:ornament_leaves[lit=false]"),
    ((4, 2, 1), LEAVES),
    ((1, 2, 2), LEAVES),
    ((1, 3, 1), "colony_seasons:ornament_leaves[lit=false]"),
    ((3, 3, 1), LEAVES),
    ((2, 3, 0), LEAVES),
    ((2, 3, 2), LEAVES),
    ((1, 4, 1), LEAVES),
    ((3, 4, 1), "colony_seasons:tinsel_leaves[lit=false]"),
    ((2, 4, 0), LEAVES),
    ((2, 4, 2), "colony_seasons:ornament_leaves[lit=false]"),
    # the large star on top, two halves
    ((2, 5, 1), "colony_seasons:large_star[half=lower,lit=false]"),
    ((2, 6, 1), "colony_seasons:large_star[half=upper,lit=false]"),
    # on the floor: the gift pile, a wrapped present (a tagged cell that is not a gift pile block) and the crate
    ((3, 0, 3), "colony_seasons:gift_pile[facing=north]"),
    ((1, 0, 3), "minecraft:red_wool"),
    ((0, 0, 3), "colony_seasons:firework_crate"),
]
TAGS = {
    (3, 0, 3): ["gift_pile"],
    (1, 0, 3): ["gift_pile"],
    (0, 0, 3): ["firework_crate"],
    (2, 5, 1): ["star"],
}

# ---------------------------------------------------------------- a minimal NBT writer

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY = range(12)


class Tag:
    def __init__(self, kind, value, element=None):
        self.kind, self.value, self.element = kind, value, element


def byte(v): return Tag(BYTE, v)
def short(v): return Tag(SHORT, v)
def int_(v): return Tag(INT, v)
def string(v): return Tag(STRING, v)
def compound(d): return Tag(COMPOUND, d)
def int_array(v): return Tag(INT_ARRAY, v)
def list_(element, items): return Tag(LIST, items, element)


def write_string(out, text):
    raw = text.encode("utf-8")
    out.write(struct.pack(">H", len(raw)))
    out.write(raw)


def write_payload(out, tag):
    k, v = tag.kind, tag.value
    if k == BYTE:
        out.write(struct.pack(">b", v))
    elif k == SHORT:
        out.write(struct.pack(">h", v))
    elif k == INT:
        out.write(struct.pack(">i", v))
    elif k == STRING:
        write_string(out, v)
    elif k == INT_ARRAY:
        out.write(struct.pack(">i", len(v)))
        for n in v:
            out.write(struct.pack(">i", n))
    elif k == LIST:
        out.write(struct.pack(">b", tag.element if v else END))
        out.write(struct.pack(">i", len(v)))
        for item in v:
            write_payload(out, item)
    elif k == COMPOUND:
        for name, child in v.items():
            out.write(struct.pack(">b", child.kind))
            write_string(out, name)
            write_payload(out, child)
        out.write(struct.pack(">b", END))
    else:
        raise ValueError(k)


def nbt_file(root):
    body = io.BytesIO()
    body.write(struct.pack(">b", COMPOUND))
    write_string(body, "")
    write_payload(body, root)
    packed = io.BytesIO()
    with gzip.GzipFile(fileobj=packed, mode="wb", mtime=0, filename="") as gz:
        gz.write(body.getvalue())
    return packed.getvalue()


# ---------------------------------------------------------------- the blueprint

def parse_state(text):
    if "[" not in text:
        return text, {}
    name, rest = text.split("[", 1)
    props = dict(p.split("=", 1) for p in rest.rstrip("]").split(",") if p)
    return name, props


def blueprint(file_name, path):
    sx, sy, sz = SIZE
    palette = ["minecraft:air"]
    index = {"minecraft:air": 0}
    cells = [0] * (sx * sy * sz)
    for (x, y, z), state in CELLS:
        if state not in index:
            index[state] = len(palette)
            palette.append(state)
        cells[(y * sz + z) * sx + x] = index[state]
    packed = []
    for i in range(0, len(cells), 2):
        v = (cells[i] << 16) | (cells[i + 1] if i + 1 < len(cells) else 0)
        packed.append(v - (1 << 32) if v >= 1 << 31 else v)
    pal = []
    for state in palette:
        name, props = parse_state(state)
        entry = {"Name": string(name)}
        if props:
            entry["Properties"] = compound({k: string(v) for k, v in sorted(props.items())})
        pal.append(compound(entry))
    ax, ay, az = ANCHOR
    tag_map = []
    for (tx, ty, tz), names in sorted(TAGS.items()):
        tag_map.append(compound({
            "tagPos": compound({"x": int_(tx - ax), "y": int_(ty - ay), "z": int_(tz - az)}),
            "tagNameList": list_(COMPOUND, [compound({"tagName": string(n)}) for n in names]),
        }))
    anchor_data = compound({
        "id": string("minecolonies:decorationcontroller"),
        "x": short(ax), "y": short(ay), "z": short(az),
        "blueprintDataProvider": compound({
            "corner1": compound({"x": int_(-ax), "y": int_(-ay), "z": int_(-az)}),
            "corner2": compound({"x": int_(sx - 1 - ax), "y": int_(sy - 1 - ay), "z": int_(sz - 1 - az)}),
            "path": string(path),
            "schematicName": string(file_name.replace(".blueprint", "")),
            "pack": string(PACK_NAME),
            "posTagMap": list_(COMPOUND, tag_map),
        }),
    })
    root = compound({
        "version": byte(1),
        "mcversion": int_(DATA_VERSION),
        "name": string(file_name),
        "size_x": short(sx), "size_y": short(sy), "size_z": short(sz),
        "required_mods": list_(STRING, [string("colony_seasons")]),
        "palette": list_(COMPOUND, pal),
        "blocks": int_array(packed),
        "tile_entities": list_(COMPOUND, [anchor_data]),
        "entities": list_(COMPOUND, []),
        "optional_data": compound({"structurize": compound({
            "primary_offset": compound({"x": int_(ax), "y": int_(ay), "z": int_(az)})})}),
    })
    return nbt_file(root)


def pack_json():
    meta = {
        "name": PACK_NAME,
        "icon": "icon.png",
        "authors": ["Lovkar", "Claude"],
        "desc": "Test pieces of the csdecorgate probe (Colony Seasons); never shipped.",
        "mods": ["structurize"],
        "version": "1",
        "pack-format": "1",
    }
    return (json.dumps(meta, indent=2) + "\n").encode("utf-8")


def outputs():
    out = {PACK_DIR / "pack.json": pack_json()}
    for name in FILES:
        file_name = name + ".blueprint"
        out[PACK_DIR / "pieces" / file_name] = blueprint(file_name, "pieces/" + file_name)
    return out


def main(argv):
    files = outputs()
    if "--check" in argv:
        bad = [p for p, data in files.items() if not p.exists() or p.read_bytes() != data]
        for p in bad:
            print(f"  !! {p.relative_to(HERE.parent.parent)} is not what make_piece.py writes (run it)")
        print("  test piece in step" if not bad else "  test piece out of step")
        return 1 if bad else 0
    for p, data in files.items():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        print("wrote", p.relative_to(HERE.parent.parent))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
