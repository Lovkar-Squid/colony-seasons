"""Vanilla-faithful block model resolver (plain Python + Pillow, no Blender).

Turns a dict {(x, y, z): "ns:block[props]"} into textured quads the way the
1.21.1 client bakes them: blockstate variants / multipart -> model parent chain
-> elements -> faces with per-face UV, UV rotation, element rotation,
blockstate x/y rotation (+ uvlock), cullface culling, tint indices, the vanilla
directional face shade (up 1.0, N/S 0.8, E/W 0.6, down 0.5) and smooth-lighting
ambient occlusion at block corners. Assets come from the client jar (MC_JAR),
optional mod jars (MC_MOD_JARS) and the design's own src/assets folder (custom
`colonyseasons:` blocks with real blockstate + model JSON + 16x16 textures; point CS_ASSETS at the mod's
resources/assets for the shipped `colony_seasons:` blocks).

Glow and night (added for chunk S2): an element with NeoForge's {"neoforge_data": {"block_light": 15, ...}} is
drawn at full brightness and flagged "e" in the quads; write_scene(..., night=emission) lights the scene the way
the game does at midnight: block and sky light flood-filled from the light sources (emission(name, props) gives
a block's light level), smooth-lit per vertex and turned into colour by vanilla's lightmap (LightTexture, default
brightness 0.5, sky darkened to 0.2).

Block entities (chests, banners, signs, beds) have no JSON model in vanilla and
are drawn as nothing - designs avoid them.

Output: write_scene(blocks, path) -> JSON {quads:[...], textures:{key: png}}
which render_scene.py (inside Blender) turns into meshes.
"""
import json
import math
import os
import re
import tempfile
import zipfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
DESIGN = os.path.dirname(HERE)
JAR = os.environ.get("MC_JAR", r"D:\Claude\voyager-libs\client-1.21.1-20240808.144430-extra.jar")
MOD_JARS = [j for j in os.environ.get("MC_MOD_JARS", "").split(os.pathsep) if j]
CUSTOM_ASSETS = os.environ.get("CS_ASSETS", os.path.join(DESIGN, "src", "assets"))
TEX_CACHE = os.environ.get("MC_TEX_DIR", os.path.join(tempfile.gettempdir(), "cs_design_tex"))
OWN_NAMESPACES = ("colonyseasons", "colony_seasons")   # the design's look options and the mod's blocks

# biome-independent colours used for tintindex faces (plains for grass/foliage)
GRASS = (0x91, 0xBD, 0x59)
FOLIAGE = (0x77, 0xAB, 0x2F)
TINTS = {
    "spruce_leaves": (0x61, 0x99, 0x61), "birch_leaves": (0x80, 0xA7, 0x55),
    "oak_leaves": FOLIAGE, "jungle_leaves": FOLIAGE, "acacia_leaves": FOLIAGE, "dark_oak_leaves": FOLIAGE,
    "mangrove_leaves": (0x92, 0xC1, 0x48), "vine": FOLIAGE,
    "grass_block": GRASS, "short_grass": GRASS, "tall_grass": GRASS, "fern": GRASS, "large_fern": GRASS,
    "sugar_cane": GRASS, "potted_fern": GRASS, "lily_pad": (0x20, 0x80, 0x30), "water": (0x3F, 0x76, 0xE4),
}

SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}
DIRS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
VEC2DIR = {v: k for k, v in DIRS.items()}

# vanilla FaceInfo vertex order, (x,y,z) selectors 0=min 1=max; vertex i takes UV corner i:
# 0=(u1,v1) 1=(u1,v2) 2=(u2,v2) 3=(u2,v1) (shifted by the face's uv rotation)
FACE_VERTS = {
    "down": [(0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)],
    "up": [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)],
    "north": [(1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)],
    "south": [(0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)],
    "west": [(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)],
    "east": [(1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)],
}

# property values assumed when a block string leaves them out
DEFAULTS = {"half": ("bottom", "lower"), "shape": ("straight",), "open": ("false",), "powered": ("false",),
            "hinge": ("left",), "axis": ("y",), "type": ("bottom", "single"), "hanging": ("false",),
            "snowy": ("false",), "waterlogged": ("false",), "facing": ("north", "up"), "layers": ("1",),
            "lit": ("false",), "up": ("true",), "north": ("false", "none"), "south": ("false", "none"),
            "east": ("false", "none"), "west": ("false", "none"), "in_wall": ("false",), "face": ("wall",),
            "attachment": ("floor",), "rotation": ("0",), "signal_fire": ("false",), "age": ("0",),
            "candles": ("1",), "level": ("0",), "persistent": ("true",), "distance": ("7",), "bottom": ("false",),
            "has_book": ("false",), "slot_0_occupied": ("false",), "slot_1_occupied": ("false",),
            "slot_2_occupied": ("false",), "slot_3_occupied": ("false",), "slot_4_occupied": ("false",),
            "slot_5_occupied": ("false",), "berries": ("false",), "segment_amount": ("1",), "flower_amount": ("1",),
            "variant": ("0",), "style": ("0",), "size": ("small",)}

_zip = {}
_json_cache = {}
_tex_alpha = {}


def _open(jar):
    if jar not in _zip:
        z = zipfile.ZipFile(jar)
        _zip[jar] = (z, set(z.namelist()))
    return _zip[jar]


def read_asset(ns, rel):
    """bytes of assets/<ns>/<rel> from the custom folder, the vanilla jar or a mod jar."""
    p = os.path.join(CUSTOM_ASSETS, ns, *rel.split("/"))
    if os.path.exists(p):
        with open(p, "rb") as f:
            return f.read()
    for jar in [JAR] + MOD_JARS:
        if not os.path.exists(jar):
            continue
        z, names = _open(jar)
        entry = f"assets/{ns}/{rel}"
        if entry in names:
            return z.read(entry)
    return None


def load_json(ns, rel):
    key = (ns, rel)
    if key not in _json_cache:
        b = read_asset(ns, rel)
        _json_cache[key] = json.loads(b.decode("utf-8")) if b else None
    return _json_cache[key]


def split_id(ref, default_ns="minecraft"):
    if ":" in ref:
        ns, path = ref.split(":", 1)
    else:
        ns, path = default_ns, ref
    return ns, path


def parse_state(s):
    m = re.match(r"^([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[(.*)\])?$", s.strip())
    if not m:
        raise ValueError("bad block state: " + s)
    props = {}
    if m.group(2):
        for kv in m.group(2).split(","):
            k, v = kv.split("=")
            props[k.strip()] = v.strip()
    return m.group(1), props


# ---------------------------------------------------------------- models
def load_model(ref):
    """Resolve the parent chain: returns {'textures':{}, 'elements':[...] or None, 'ao':bool}."""
    textures, elements, ao = {}, None, True
    chain = []
    seen = 0
    while ref and seen < 20:
        ns, path = split_id(ref)
        if path.startswith("builtin/"):
            break
        m = load_json(ns, f"models/{path}.json")
        if m is None:
            raise FileNotFoundError(f"model {ns}:{path}")
        chain.append(m)
        ref = m.get("parent")
        seen += 1
    for m in reversed(chain):  # root first, children override
        textures.update(m.get("textures", {}))
        if "elements" in m:
            elements = m["elements"]
        if "ambientocclusion" in m:
            ao = m["ambientocclusion"]
    return {"textures": textures, "elements": elements, "ao": ao}


def resolve_tex(textures, ref):
    seen = 0
    while ref.startswith("#") and seen < 12:
        ref = textures.get(ref[1:], "missingno")
        seen += 1
    return ref


def _cond_ok(cond, props):
    if "OR" in cond:
        return any(_cond_ok(c, props) for c in cond["OR"])
    if "AND" in cond:
        return all(_cond_ok(c, props) for c in cond["AND"])
    for k, allowed in cond.items():
        vals = str(allowed).split("|")
        v = props.get(k)
        if v is None:
            if not any(d in vals for d in DEFAULTS.get(k, ())):
                return False
        elif v not in vals:
            return False
    return True


def pick_models(name, props, pos):
    """blockstate -> list of (model_ref, x, y, uvlock)."""
    ns, path = split_id(name)
    bs = load_json(ns, f"blockstates/{path}.json")
    if bs is None:
        raise FileNotFoundError(f"blockstate {name}")

    def choose(v):
        if isinstance(v, list):  # weighted random variants: stable pick by position
            h = (pos[0] * 3129871) ^ (pos[2] * 116129781) ^ (pos[1])
            h = (h * h * 42317861 + h * 11) & 0xFFFFFFF
            total = sum(e.get("weight", 1) for e in v)
            r = h % total
            for e in v:
                r -= e.get("weight", 1)
                if r < 0:
                    return e
            return v[0]
        return v

    out = []
    if "variants" in bs:
        best, best_score = None, -1
        for key, v in bs["variants"].items():
            conds = dict(kv.split("=") for kv in key.split(",")) if key else {}
            ok, score = True, 0
            for k, val in conds.items():
                if k in props:
                    if props[k] != val:
                        ok = False
                        break
                    score += 10
                elif val in DEFAULTS.get(k, ()):
                    score += 1
            if ok and score > best_score:
                best, best_score = v, score
        if best is None:
            raise ValueError(f"no variant for {name} {props}")
        e = choose(best)
        out.append((e["model"], e.get("x", 0), e.get("y", 0), e.get("uvlock", False)))
    else:
        for part in bs["multipart"]:
            if "when" in part and not _cond_ok(part["when"], props):
                continue
            e = choose(part["apply"])
            out.append((e["model"], e.get("x", 0), e.get("y", 0), e.get("uvlock", False)))
    return out


# ---------------------------------------------------------------- geometry helpers
def rot_x(p, deg):
    """blockstate x rotation about (8,8,8): 90 moves down->south, up->north (vanilla buttons)."""
    x, y, z = p[0] - 8, p[1] - 8, p[2] - 8
    for _ in range((deg // 90) % 4):
        y, z = z, -y
    return (x + 8, y + 8, z + 8)


def rot_y(p, deg):
    """blockstate y rotation about (8,8,8): 90 = clockwise seen from above (north->east)."""
    x, y, z = p[0] - 8, p[1] - 8, p[2] - 8
    for _ in range((deg // 90) % 4):
        x, z = -z, x
    return (x + 8, y + 8, z + 8)


def rot_dir(d, xr, yr):
    v = DIRS[d]
    p = rot_y(rot_x((v[0] * 8 + 8, v[1] * 8 + 8, v[2] * 8 + 8), xr), yr)
    v2 = (round((p[0] - 8) / 8), round((p[1] - 8) / 8), round((p[2] - 8) / 8))
    return VEC2DIR[v2]


def elem_rotate(p, rot):
    if not rot:
        return p
    ang = math.radians(rot["angle"])
    o = rot.get("origin", [8, 8, 8])
    x, y, z = p[0] - o[0], p[1] - o[1], p[2] - o[2]
    c, s = math.cos(ang), math.sin(ang)
    ax = rot["axis"]
    sc = 1.0 / math.cos(ang) if rot.get("rescale") else 1.0
    if ax == "x":
        y, z = y * c - z * s, y * s + z * c
        y, z = y * sc, z * sc
    elif ax == "y":
        x, z = x * c + z * s, -x * s + z * c
        x, z = x * sc, z * sc
    else:
        x, y = x * c - y * s, x * s + y * c
        x, y = x * sc, y * sc
    return (x + o[0], y + o[1], z + o[2])


def default_uv(face, f, t):
    if face == "down":
        return [f[0], 16 - t[2], t[0], 16 - f[2]]
    if face == "up":
        return [f[0], f[2], t[0], t[2]]
    if face == "north":
        return [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]]
    if face == "south":
        return [f[0], 16 - t[1], t[0], 16 - f[1]]
    if face == "west":
        return [f[2], 16 - t[1], t[2], 16 - f[1]]
    return [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]]


# ---------------------------------------------------------------- textures
def texture_file(ref, tint=None):
    """Prepare a texture png in the cache (first animation frame, optional tint). Returns (key, path)."""
    ns, path = split_id(ref)
    key = f"{ns}_{path.replace('/', '_')}" + (f"_{tint[0]:02x}{tint[1]:02x}{tint[2]:02x}" if tint else "")
    out = os.path.join(TEX_CACHE, key + ".png")
    if not os.path.exists(out) or ns in OWN_NAMESPACES:
        b = read_asset(ns, f"textures/{path}.png")
        os.makedirs(TEX_CACHE, exist_ok=True)
        if b is None:
            img = Image.new("RGBA", (16, 16), (255, 0, 255, 255))
            for yy in range(8):
                for xx in range(8):
                    img.putpixel((xx, yy), (0, 0, 0, 255)); img.putpixel((xx + 8, yy + 8), (0, 0, 0, 255))
            print("MISSING TEXTURE", ref)
        else:
            import io
            img = Image.open(io.BytesIO(b)).convert("RGBA")
            w, h = img.size
            if h > w:
                img = img.crop((0, 0, w, w))
        if tint:
            px = img.load()
            for yy in range(img.size[1]):
                for xx in range(img.size[0]):
                    r, g, bb, a = px[xx, yy]
                    px[xx, yy] = (r * tint[0] // 255, g * tint[1] // 255, bb * tint[2] // 255, a)
        img.save(out)
    if key not in _tex_alpha:
        im = Image.open(out)
        a = im.getchannel("A")
        lo = min(a.getdata())
        mids = sum(1 for v in a.getdata() if 0 < v < 255)
        _tex_alpha[key] = "opaque" if lo == 255 else ("blend" if mids > 0 else "cut")
    return key, out


# ---------------------------------------------------------------- auto block properties
FENCE_LIKE = re.compile(r"_(fence|wall)$|_pane$|^minecraft:iron_bars$|^minecraft:glass_pane$")


def _short(n):
    return n.split(":", 1)[1] if ":" in n else n


class Scene:
    def __init__(self, blocks, night=None):
        self.raw = dict(blocks)
        self.parsed = {p: parse_state(s) for p, s in self.raw.items()}
        self._baked = {}
        self._full = {}
        self.auto_props()
        # night: emission(name, props) -> light level of a block; None renders the daylight look as before
        self.emission = night
        self.block_light = {}
        self.sky_light = {}
        if night:
            self.compute_light()

    # ---- night light (vanilla's light engine, simplified): block light from the light sources, sky light from
    # the open sky, both losing 1 a block (leaves 2), stopped by opaque full cubes
    def light_cost(self, pos):
        if pos not in self.parsed:
            return 0
        if self.is_full(pos)[0]:
            return 15
        return 1 if "leaves" in self.parsed[pos][0] else 0

    def compute_light(self):
        from collections import deque
        xs = [p[0] for p in self.parsed]; ys = [p[1] for p in self.parsed]; zs = [p[2] for p in self.parsed]
        lo = (min(xs) - 3, min(ys) - 3, min(zs) - 3)
        hi = (max(xs) + 3, max(ys) + 3, max(zs) + 3)

        def inside(p):
            return all(lo[i] <= p[i] <= hi[i] for i in range(3))

        def spread(grid, queue):
            while queue:
                p = queue.popleft()
                v = grid[p]
                for d in DIRS.values():
                    q = (p[0] + d[0], p[1] + d[1], p[2] + d[2])
                    if not inside(q):
                        continue
                    cost = self.light_cost(q)
                    if cost >= 15:
                        continue
                    nv = v - 1 - cost
                    if nv > grid.get(q, 0):
                        grid[q] = nv
                        queue.append(q)

        queue = deque()
        for pos, (name, props) in self.parsed.items():
            e = self.emission(name, props)
            if e > 0:
                self.block_light[pos] = e
                queue.append(pos)
        spread(self.block_light, queue)
        queue = deque()
        for x in range(lo[0], hi[0] + 1):
            for z in range(lo[2], hi[2] + 1):
                level = 15
                for y in range(hi[1], lo[1] - 1, -1):
                    cost = self.light_cost((x, y, z))
                    if cost >= 15:
                        break
                    level -= cost
                    if level <= 0:
                        break
                    self.sky_light[(x, y, z)] = level
                    queue.append((x, y, z))
        spread(self.sky_light, queue)

    def light_at(self, p):
        return self.sky_light.get(p, 0), self.block_light.get(p, 0)

    # ---- which blocks are full opaque cubes (cull neighbours / cast AO)
    def bake(self, pos):
        if pos in self._baked:
            return self._baked[pos]
        name, props = self.parsed[pos]
        parts = []
        try:
            picks = pick_models(name, props, pos)
        except FileNotFoundError as e:
            print("MISSING", e)
            picks = []
        for ref, xr, yr, uvl in picks:
            m = load_model(ref)
            parts.append((m, xr, yr, uvl))
        self._baked[pos] = parts
        return parts

    def is_full(self, pos):
        """(occludes faces, darkens AO)"""
        if pos not in self.parsed:
            return (False, False)
        if pos in self._full:
            return self._full[pos]
        parts = self.bake(pos)
        res = (False, False)
        if len(parts) == 1 and parts[0][0]["elements"] and len(parts[0][0]["elements"]) >= 1:
            m = parts[0][0]
            els = m["elements"]
            if any(e["from"] == [0, 0, 0] and e["to"] == [16, 16, 16] and not e.get("rotation") for e in els):
                name = self.parsed[pos][0]
                opaque = True
                for e in els:
                    for fd in e["faces"].values():
                        tref = resolve_tex(m["textures"], fd["texture"])
                        k, _ = texture_file(tref)
                        if _tex_alpha[k] != "opaque":
                            opaque = False
                glassy = "glass" in name or "ice" in name
                res = (opaque, not glassy)
        self._full[pos] = res
        return res

    # ---- connections, stair corners, snowy grass
    def auto_props(self):
        P = self.parsed
        for pos, (name, props) in list(P.items()):
            sn = _short(name)
            if FENCE_LIKE.search(name) or sn == "iron_bars" or sn.endswith("glass_pane"):
                for d in ("north", "south", "east", "west"):
                    if d in props:
                        continue
                    v = DIRS[d]
                    nb = (pos[0] + v[0], pos[1], pos[2] + v[2])
                    con = False
                    if nb in P:
                        nn = _short(P[nb][0])
                        if sn.endswith("_fence"):
                            con = nn.endswith("_fence") or nn.endswith("_fence_gate") or self._solid(nb)
                        elif sn.endswith("_wall"):
                            con = nn.endswith("_wall") or nn.endswith("_fence_gate") or self._solid(nb)
                        else:
                            con = nn.endswith("_pane") or nn == "iron_bars" or nn.endswith("_wall") or self._solid(nb)
                    if sn.endswith("_wall"):
                        props[d] = "low" if con else "none"
                    else:
                        props[d] = "true" if con else "false"
                if sn.endswith("_wall") and "up" not in props:
                    ns_ = props["north"] != "none" and props["south"] != "none" and props["east"] == "none" and props["west"] == "none"
                    ew = props["east"] != "none" and props["west"] != "none" and props["north"] == "none" and props["south"] == "none"
                    above = (pos[0], pos[1] + 1, pos[2])
                    props["up"] = "false" if (ns_ or ew) and above not in P else "true"
            if sn in ("grass_block", "podzol", "mycelium") and "snowy" not in props:
                above = (pos[0], pos[1] + 1, pos[2])
                props["snowy"] = "true" if above in P and _short(P[above][0]) in ("snow", "snow_block", "powder_snow") else "false"
        for pos, (name, props) in list(P.items()):
            if _short(name).endswith("_stairs") and "shape" not in props:
                props["shape"] = self._stair_shape(pos, props)

    def _solid(self, pos):
        name, props = self.parsed[pos]
        sn = _short(name)
        bad = ("_fence", "_wall", "_pane", "_slab", "_stairs", "_door", "_trapdoor", "lantern", "torch", "_carpet",
               "ladder", "chain", "snow", "rod", "_sign", "_button", "flower", "grass", "fern", "leaves", "glass", "bars")
        if any(b in sn for b in bad) and sn not in ("snow_block", "grass_block"):
            return False
        return not name.startswith(tuple(n + ":" for n in OWN_NAMESPACES))

    def _stair(self, pos):
        if pos in self.parsed and _short(self.parsed[pos][0]).endswith("_stairs"):
            return self.parsed[pos][1]
        return None

    def _stair_shape(self, pos, props):
        facing = props.get("facing", "north")
        half = props.get("half", "bottom")
        ccw = {"north": "west", "west": "south", "south": "east", "east": "north"}
        opp = {"north": "south", "south": "north", "east": "west", "west": "east"}
        axis = lambda d: "x" if d in ("east", "west") else "z"

        def rel(d):
            v = DIRS[d]
            return (pos[0] + v[0], pos[1], pos[2] + v[2])

        def can_take(face):
            o = self._stair(rel(face))
            return o is None or o.get("facing", "north") != facing or o.get("half", "bottom") != half

        b1 = self._stair(rel(facing))
        if b1 and b1.get("half", "bottom") == half:
            d1 = b1.get("facing", "north")
            if axis(d1) != axis(facing) and can_take(opp[d1]):
                return "outer_left" if d1 == ccw[facing] else "outer_right"
        b2 = self._stair(rel(opp[facing]))
        if b2 and b2.get("half", "bottom") == half:
            d2 = b2.get("facing", "north")
            if axis(d2) != axis(facing) and can_take(d2):
                return "inner_left" if d2 == ccw[facing] else "inner_right"
        return "straight"

    # ---- quads
    def ao_shade(self, p):
        return 0.2 if self.is_full(p)[1] else 1.0

    def quads(self):
        out = []
        textures = {}
        for pos in sorted(self.parsed):
            name, props = self.parsed[pos]
            sn = _short(name)
            tint = TINTS.get(sn)
            for m, xr, yr, uvl in self.bake(pos):
                if not m["elements"]:
                    continue
                emitting = bool(self.emission) and self.emission(name, props) > 0
                for el in m["elements"]:
                    f, t = el["from"], el["to"]
                    erot = el.get("rotation")
                    shade_on = el.get("shade", True)
                    nd = el.get("neoforge_data") or {}
                    glow = nd.get("block_light", 0) > 0 or nd.get("sky_light", 0) > 0
                    for face, fd in el["faces"].items():
                        cull = fd.get("cullface")
                        if cull:
                            cd = rot_dir(cull, xr, yr)
                            v = DIRS[cd]
                            nb = (pos[0] + v[0], pos[1] + v[1], pos[2] + v[2])
                            if self.is_full(nb)[0]:
                                continue
                            # a thin decoration of ours hung on a solid wall block: its back face lies in the wall's
                            # front plane. The game culls it; drawn here it is coplanar with the wall face, and a ray
                            # through a transparent pixel passes both and shows the inside of the wall block.
                            if (name.startswith(tuple(n + ":" for n in OWN_NAMESPACES))
                                    and nb in self.parsed and self._solid(nb)):
                                continue
                            # same-block glass / ice skip their shared faces
                            if nb in self.parsed and self.parsed[nb][0] == name and ("glass" in sn or sn == "ice"):
                                continue
                        tref = resolve_tex(m["textures"], fd["texture"])
                        tt = tint if (tint and "tintindex" in fd) else None
                        key, path = texture_file(tref, tt)
                        textures[key] = path
                        # corners in model space
                        corners = []
                        for sel in FACE_VERTS[face]:
                            p = (t[0] if sel[0] else f[0], t[1] if sel[1] else f[1], t[2] if sel[2] else f[2])
                            p = elem_rotate(p, erot)
                            p = rot_y(rot_x(p, xr), yr)
                            corners.append(p)
                        world_face = rot_dir(face, xr, yr) if not erot else None
                        uv = fd.get("uv")
                        if uvl and (xr or yr) and world_face:
                            xs = [c[0] for c in corners]; ys = [c[1] for c in corners]; zs = [c[2] for c in corners]
                            uv = default_uv(world_face, [min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)])
                            # re-order corners to match the world face's vanilla vertex order
                            lo = (min(xs), min(ys), min(zs)); hi = (max(xs), max(ys), max(zs))
                            corners = [(hi[0] if s[0] else lo[0], hi[1] if s[1] else lo[1], hi[2] if s[2] else lo[2])
                                       for s in FACE_VERTS[world_face]]
                            urot = 0
                        else:
                            if uv is None:
                                uv = default_uv(face, f, t)
                            urot = fd.get("rotation", 0) // 90
                        u1, v1, u2, v2 = uv
                        uvc = [(u1, v1), (u1, v2), (u2, v2), (u2, v1)]
                        uvs = [uvc[(i + urot) % 4] for i in range(4)]
                        # normal from geometry
                        a, b, c = corners[0], corners[1], corners[2]
                        e1 = (b[0] - a[0], b[1] - a[1], b[2] - a[2]); e2 = (c[0] - a[0], c[1] - a[1], c[2] - a[2])
                        n = (e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
                        ln = math.sqrt(sum(q * q for q in n)) or 1
                        n = tuple(q / ln for q in n)
                        if shade_on:
                            ax = max(range(3), key=lambda i: abs(n[i]))
                            dv = [0, 0, 0]; dv[ax] = 1 if n[ax] > 0 else -1
                            ndir = VEC2DIR[tuple(dv)]
                            # blend shade for tilted faces
                            sh = sum(abs(n[i]) ** 2 * SHADE[VEC2DIR[tuple((1 if (j == i and n[i] > 0) else -1 if j == i else 0) for j in range(3))]]
                                     for i in range(3) if abs(n[i]) > 1e-6)
                        else:
                            ndir = None
                            sh = 1.0
                        # ambient occlusion per vertex
                        bright = [sh] * 4
                        if m["ao"] and ndir and not erot:
                            dv = DIRS[ndir]
                            ax = [i for i in range(3) if dv[i] != 0][0]
                            plane = [i for i in range(3) if i != ax]
                            coord = corners[0][ax]
                            flush = coord in (0, 16) and ((coord == 16) == (dv[ax] > 0))
                            base = list(pos)
                            if flush:
                                base[ax] += dv[ax]
                            centre_s = self.ao_shade(tuple(base))
                            bright = []
                            for cpt in corners:
                                sa = 1 if cpt[plane[0]] > 8 else -1
                                sb = 1 if cpt[plane[1]] > 8 else -1
                                p1 = list(base); p1[plane[0]] += sa
                                p2 = list(base); p2[plane[1]] += sb
                                p3 = list(base); p3[plane[0]] += sa; p3[plane[1]] += sb
                                s1 = self.ao_shade(tuple(p1)); s2 = self.ao_shade(tuple(p2))
                                s3 = s1 if (s1 < 1 and s2 < 1) else self.ao_shade(tuple(p3))
                                bright.append(sh * (centre_s + s1 + s2 + s3) / 4.0)
                        colour = [round(b_, 4) for b_ in bright]
                        if self.emission:
                            colour = self.night_colour(pos, corners, n, bright, glow, m["ao"] and not erot and not emitting)
                        # a glowing element drawn over a plane at the same depth (the game draws it after, like
                        # the grass block's overlay) is lifted a hair here, or the renderer would let them fight
                        lift = 0.03 if glow else 0.0
                        verts = [(pos[0] + (c_[0] + n[0] * lift) / 16, pos[1] + (c_[1] + n[1] * lift) / 16,
                                  pos[2] + (c_[2] + n[2] * lift) / 16) for c_ in corners]
                        q_ = {"t": key, "v": [[round(q, 5) for q in vv] for vv in verts],
                              "uv": [[u / 16, 1 - v / 16] for (u, v) in uvs], "c": colour, "a": _tex_alpha[key]}
                        if glow:
                            q_["e"] = 1
                        out.append(q_)
        return out, textures

    def night_colour(self, pos, corners, n, bright, glow, smooth):
        """Per-vertex RGB at night: lightmap(sky, block) x face shade x AO; a glowing element is full white."""
        if glow:
            return [[1.0, 1.0, 1.0] for _ in corners]
        ax = max(range(3), key=lambda i: abs(n[i]))
        sign = 1 if n[ax] > 0 else -1
        coord = corners[0][ax]
        flush = all(c[ax] == coord for c in corners) and coord in (0, 16) and ((coord == 16) == (sign > 0))
        base = list(pos)
        if flush:
            base[ax] += sign
        base = tuple(base)
        plane = [i for i in range(3) if i != ax]
        out = []
        for i, cpt in enumerate(corners):
            if smooth:
                samples = [base]
                sa = 1 if cpt[plane[0]] > 8 else -1
                sb = 1 if cpt[plane[1]] > 8 else -1
                for da, db in ((sa, 0), (0, sb), (sa, sb)):
                    q = list(base)
                    q[plane[0]] += da
                    q[plane[1]] += db
                    samples.append(tuple(q))
                vals = [self.light_at(q) if self.light_cost(q) < 15 else self.light_at(base) for q in samples]
                sky = sum(v[0] for v in vals) / 4.0
                blk = sum(v[1] for v in vals) / 4.0
            else:
                sky, blk = self.light_at(base)
            r, g, b = lightmap(sky, blk)
            out.append([round(r * bright[i], 4), round(g * bright[i], 4), round(b * bright[i], 4)])
        return out


def _brightness(level):
    f = level / 15.0
    return f / (4.0 - 3.0 * f)


def _lightmap_exact(sky, block, sky_darken=0.2, gamma=0.5):
    """Vanilla 1.21.1 LightTexture.updateLightTexture for one (sky, block) pair: overworld, midnight sky
    (getSkyDarken 0.2), default brightness 0.5, no torch flicker."""
    f1 = sky_darken * 0.95 + 0.05
    sv = [sky_darken + (1 - sky_darken) * 0.35] * 2 + [1.0]
    f8 = _brightness(sky) * f1
    f9 = _brightness(block) * 1.5
    c = [f9, f9 * ((f9 * 0.6 + 0.4) * 0.6 + 0.4), f9 * (f9 * f9 * 0.6 + 0.4)]
    c = [c[i] + sv[i] * f8 for i in range(3)]
    c = [v + (0.75 - v) * 0.04 for v in c]
    c = [min(max(v, 0.0), 1.0) for v in c]
    c = [v + ((1 - (1 - v) ** 4) - v) * gamma for v in c]
    c = [v + (0.75 - v) * 0.04 for v in c]
    return [min(max(v, 0.0), 1.0) for v in c]


def lightmap(sky, block):
    """The lightmap colour at (possibly fractional, smooth-lit) light levels, bilinear like the game's texture."""
    s0, b0 = int(math.floor(sky)), int(math.floor(block))
    s1, b1 = min(s0 + 1, 15), min(b0 + 1, 15)
    fs, fb = sky - s0, block - b0
    c00, c01 = _lightmap_exact(s0, b0), _lightmap_exact(s0, b1)
    c10, c11 = _lightmap_exact(s1, b0), _lightmap_exact(s1, b1)
    return [c00[i] * (1 - fs) * (1 - fb) + c01[i] * (1 - fs) * fb + c10[i] * fs * (1 - fb) + c11[i] * fs * fb
            for i in range(3)]


def write_scene(blocks, path, extra=None, night=None):
    """night: emission(name, props) -> light level; given, the scene is lit as at midnight (see Scene)."""
    sc = Scene(blocks, night)
    quads, textures = sc.quads()
    xs = [p[0] for p in blocks]; ys = [p[1] for p in blocks]; zs = [p[2] for p in blocks]
    data = {"quads": quads, "textures": textures,
            "bounds": [[min(xs), min(ys), min(zs)], [max(xs) + 1, max(ys) + 1, max(zs) + 1]]}
    data.update(extra or {})
    with open(path, "w") as f:
        json.dump(data, f)
    return len(quads)
