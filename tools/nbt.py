"""A small reader and writer of Minecraft's NBT format, enough for Structurize blueprints.

The blueprint tools run with `python -I` (no user site-packages), so they cannot lean on nbtlib the way Voyager's do.
This is the whole format: a value is one of the typed wrappers below or a plain Python dict (compound) / str (string);
lists carry their element type. `dump_gz` writes a gzip file with a fixed header time, so the same data always gives
the same bytes (build_pack.py --check compares the shipped files byte for byte).
"""
import gzip
import io
import struct

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class _Num:
    tag = None
    fmt = None

    def __init__(self, value):
        self.value = value

    def __eq__(self, other):
        return type(self) is type(other) and self.value == other.value

    def __int__(self):
        return int(self.value)

    def __float__(self):
        return float(self.value)

    def __repr__(self):
        return f"{type(self).__name__}({self.value!r})"


class Byte(_Num):
    tag, fmt = BYTE, ">b"


class Short(_Num):
    tag, fmt = SHORT, ">h"


class Int(_Num):
    tag, fmt = INT, ">i"


class Long(_Num):
    tag, fmt = LONG, ">q"


class Float(_Num):
    tag, fmt = FLOAT, ">f"


class Double(_Num):
    tag, fmt = DOUBLE, ">d"


class IntArray:
    tag = INT_ARRAY

    def __init__(self, values):
        self.values = list(values)

    def __eq__(self, other):
        return isinstance(other, IntArray) and self.values == other.values


class List:
    """A typed list: `List(STRING, ["a", "b"])`, `List(COMPOUND, [{...}])`. An empty list keeps its type."""
    tag = LIST

    def __init__(self, elem, items=()):
        self.elem = elem
        self.items = list(items)

    def __eq__(self, other):
        return isinstance(other, List) and self.items == other.items and (self.elem == other.elem or not self.items)

    def __iter__(self):
        return iter(self.items)

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        return self.items[i]


def _tag_of(v):
    if isinstance(v, dict):
        return COMPOUND
    if isinstance(v, str):
        return STRING
    if isinstance(v, (_Num, IntArray, List)):
        return v.tag
    raise TypeError(f"not an NBT value: {v!r}")


def _write_str(out, s):
    b = s.encode("utf-8")      # Java's modified UTF-8 only differs for NUL and characters outside the BMP
    out.write(struct.pack(">H", len(b)))
    out.write(b)


def _write_payload(out, v):
    t = _tag_of(v)
    if t == COMPOUND:
        for k, x in v.items():
            out.write(struct.pack(">b", _tag_of(x)))
            _write_str(out, k)
            _write_payload(out, x)
        out.write(b"\x00")
    elif t == STRING:
        _write_str(out, v)
    elif t == LIST:
        elem = v.elem if not v.items else _tag_of(v.items[0])
        if any(_tag_of(x) != elem for x in v.items):
            raise TypeError("an NBT list holds one type")
        out.write(struct.pack(">bi", elem if v.items else v.elem, len(v.items)))
        for x in v.items:
            _write_payload(out, x)
    elif t == INT_ARRAY:
        out.write(struct.pack(">i", len(v.values)))
        out.write(struct.pack(f">{len(v.values)}i", *v.values))
    else:
        out.write(struct.pack(v.fmt, v.value))


def dumps(root, name=""):
    """The uncompressed bytes of a named root compound."""
    out = io.BytesIO()
    out.write(struct.pack(">b", COMPOUND))
    _write_str(out, name)
    _write_payload(out, root)
    return out.getvalue()


def dump_gz(root, path):
    data = dumps(root)
    with open(path, "wb") as f:
        # mtime=0 and no file name in the header: the same blueprint always gives the same bytes
        with gzip.GzipFile(filename="", mode="wb", fileobj=f, mtime=0) as g:
            g.write(data)


NUMS = {BYTE: Byte, SHORT: Short, INT: Int, LONG: Long, FLOAT: Float, DOUBLE: Double}


class _Reader:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def take(self, fmt):
        n = struct.calcsize(fmt)
        v = struct.unpack_from(fmt, self.d, self.i)
        self.i += n
        return v

    def string(self):
        (n,) = self.take(">H")
        s = self.d[self.i:self.i + n].decode("utf-8")
        self.i += n
        return s

    def payload(self, t):
        if t == COMPOUND:
            c = {}
            while True:
                (ct,) = self.take(">b")
                if ct == END:
                    return c
                k = self.string()
                c[k] = self.payload(ct)
        if t == STRING:
            return self.string()
        if t == LIST:
            elem, n = self.take(">bi")
            return List(elem, [self.payload(elem) for _ in range(n)])
        if t == INT_ARRAY:
            (n,) = self.take(">i")
            return IntArray(self.take(f">{n}i"))
        if t == BYTE_ARRAY:
            (n,) = self.take(">i")
            return List(BYTE, [Byte(b) for b in self.take(f">{n}b")])
        if t == LONG_ARRAY:
            (n,) = self.take(">i")
            return List(LONG, [Long(b) for b in self.take(f">{n}q")])
        cls = NUMS[t]
        return cls(self.take(cls.fmt)[0])


def loads(data):
    """(name, root compound) of uncompressed NBT bytes."""
    r = _Reader(data)
    (t,) = r.take(">b")
    if t != COMPOUND:
        raise ValueError("the root of an NBT file is a compound")
    name = r.string()
    root = r.payload(COMPOUND)
    if r.i != len(data):
        raise ValueError(f"{len(data) - r.i} bytes left after the root compound")
    return name, root


def load_gz(path):
    with gzip.open(path, "rb") as g:
        return loads(g.read())[1]
