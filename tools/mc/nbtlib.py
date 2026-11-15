"""Minimal binary NBT reader and writer for inspection scripts.

This is tooling for looking at world files and preparing probe worlds. It is
independent of the Java codec in merge/ on purpose, so the two can be checked
against each other. Standard library only.
"""

from __future__ import annotations

import gzip
import struct
import zlib

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)

TYPE_NAMES = ["end", "byte", "short", "int", "long", "float", "double", "byte[]",
              "string", "list", "compound", "int[]", "long[]"]


class Byte(int):
    tag = BYTE


class Short(int):
    tag = SHORT


class Int(int):
    tag = INT


class Long(int):
    tag = LONG


class Float(float):
    tag = FLOAT


class Double(float):
    tag = DOUBLE


class ByteArray(bytes):
    tag = BYTE_ARRAY


class String(str):
    tag = STRING


class List(list):
    """A list tag. `element` is the declared element type id, kept even when empty."""
    tag = LIST

    def __init__(self, element: int, items=()):
        super().__init__(items)
        self.element = element


class Compound(dict):
    tag = COMPOUND


class IntArray(list):
    tag = INT_ARRAY


class LongArray(list):
    tag = LONG_ARRAY


def _decode_mutf8(raw: bytes) -> str:
    # Java's modified UTF-8: NUL as C0 80 and supplementary characters as surrogate pairs.
    try:
        text = raw.decode("utf-8")
        if "\x00" not in text:
            return text
    except UnicodeDecodeError:
        pass
    return raw.replace(b"\xc0\x80", b"\x00").decode("utf-8", "surrogatepass").encode(
        "utf-16", "surrogatepass").decode("utf-16", "surrogatepass")


def _encode_mutf8(text: str) -> bytes:
    if text.isascii() and "\x00" not in text:
        return text.encode("ascii")
    out = bytearray()
    for unit in struct.unpack(f">{len(text.encode('utf-16-be', 'surrogatepass')) // 2}H",
                              text.encode("utf-16-be", "surrogatepass")):
        if unit == 0:
            out += b"\xc0\x80"
        elif unit < 0x80:
            out.append(unit)
        elif unit < 0x800:
            out += bytes((0xC0 | unit >> 6, 0x80 | unit & 0x3F))
        else:
            out += bytes((0xE0 | unit >> 12, 0x80 | unit >> 6 & 0x3F, 0x80 | unit & 0x3F))
    return bytes(out)


class _Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def unpack(self, fmt: str):
        size = struct.calcsize(fmt)
        values = struct.unpack_from(fmt, self.data, self.pos)
        self.pos += size
        return values[0] if len(values) == 1 else values

    def string(self) -> String:
        length = self.unpack(">H")
        raw = self.data[self.pos:self.pos + length]
        self.pos += length
        return String(_decode_mutf8(raw))

    def payload(self, tag: int):
        if tag == BYTE:
            return Byte(self.unpack(">b"))
        if tag == SHORT:
            return Short(self.unpack(">h"))
        if tag == INT:
            return Int(self.unpack(">i"))
        if tag == LONG:
            return Long(self.unpack(">q"))
        if tag == FLOAT:
            return Float(self.unpack(">f"))
        if tag == DOUBLE:
            return Double(self.unpack(">d"))
        if tag == BYTE_ARRAY:
            length = self.unpack(">i")
            raw = self.data[self.pos:self.pos + length]
            self.pos += length
            return ByteArray(raw)
        if tag == STRING:
            return self.string()
        if tag == LIST:
            element = self.unpack(">b")
            length = self.unpack(">i")
            return List(element, (self.payload(element) for _ in range(length)))
        if tag == COMPOUND:
            compound = Compound()
            while True:
                child = self.unpack(">b")
                if child == END:
                    return compound
                name = self.string()
                compound[name] = self.payload(child)
        if tag == INT_ARRAY:
            length = self.unpack(">i")
            values = struct.unpack_from(f">{length}i", self.data, self.pos)
            self.pos += 4 * length
            return IntArray(values)
        if tag == LONG_ARRAY:
            length = self.unpack(">i")
            values = struct.unpack_from(f">{length}q", self.data, self.pos)
            self.pos += 8 * length
            return LongArray(values)
        raise ValueError(f"unknown tag id {tag} at offset {self.pos}")


def loads(data: bytes) -> tuple[str, object]:
    """Parse an uncompressed named root tag. Returns (root name, value)."""
    reader = _Reader(data)
    tag = reader.unpack(">b")
    name = reader.string()
    value = reader.payload(tag)
    if reader.pos != len(data):
        raise ValueError(f"{len(data) - reader.pos} trailing bytes after the root tag")
    return name, value


def _write_string(out: bytearray, text: str) -> None:
    raw = _encode_mutf8(text)
    out += struct.pack(">H", len(raw)) + raw


def _write_payload(out: bytearray, value) -> None:
    tag = value.tag
    if tag == BYTE:
        out += struct.pack(">b", value)
    elif tag == SHORT:
        out += struct.pack(">h", value)
    elif tag == INT:
        out += struct.pack(">i", value)
    elif tag == LONG:
        out += struct.pack(">q", value)
    elif tag == FLOAT:
        out += struct.pack(">f", value)
    elif tag == DOUBLE:
        out += struct.pack(">d", value)
    elif tag == BYTE_ARRAY:
        out += struct.pack(">i", len(value)) + value
    elif tag == STRING:
        _write_string(out, value)
    elif tag == LIST:
        out += struct.pack(">bi", value.element, len(value))
        for item in value:
            _write_payload(out, item)
    elif tag == COMPOUND:
        for name, child in value.items():
            out += struct.pack(">b", child.tag)
            _write_string(out, name)
            _write_payload(out, child)
        out.append(END)
    elif tag == INT_ARRAY:
        out += struct.pack(f">i{len(value)}i", len(value), *value)
    elif tag == LONG_ARRAY:
        out += struct.pack(f">i{len(value)}q", len(value), *value)
    else:
        raise ValueError(f"cannot write tag id {tag}")


def dumps(value, name: str = "") -> bytes:
    out = bytearray()
    out.append(value.tag)
    _write_string(out, name)
    _write_payload(out, value)
    return bytes(out)


def load_file(path) -> tuple[str, object]:
    """Read a standalone NBT file (level.dat, data/*.dat), gzip or plain."""
    with open(path, "rb") as handle:
        data = handle.read()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    elif data[:1] == b"\x78":
        data = zlib.decompress(data)
    return loads(data)


def snbt(value, indent: int | None = None, _depth: int = 0, max_array: int = 12) -> str:
    """Render a tag in an SNBT-like form with explicit types, for reading."""
    tag = value.tag
    if tag == BYTE:
        return f"{int(value)}b"
    if tag == SHORT:
        return f"{int(value)}s"
    if tag == INT:
        return str(int(value))
    if tag == LONG:
        return f"{int(value)}L"
    if tag == FLOAT:
        return f"{float(value)!r}f"
    if tag == DOUBLE:
        return f"{float(value)!r}d"
    if tag == STRING:
        return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
    if tag in (BYTE_ARRAY, INT_ARRAY, LONG_ARRAY):
        prefix = {BYTE_ARRAY: "B", INT_ARRAY: "I", LONG_ARRAY: "L"}[tag]
        items = [str(v) for v in value[:max_array]]
        if len(value) > max_array:
            items.append(f"... {len(value)} total")
        return f"[{prefix}; " + ", ".join(items) + "]"
    pad = "" if indent is None else "\n" + " " * (indent * (_depth + 1))
    end = "" if indent is None else "\n" + " " * (indent * _depth)
    if tag == LIST:
        if not value:
            return f"[]<{TYPE_NAMES[value.element]}>"
        body = ("," + (pad or " ")).join(snbt(v, indent, _depth + 1, max_array) for v in value)
        return f"[{pad}{body}{end}]"
    if tag == COMPOUND:
        if not value:
            return "{}"
        body = ("," + (pad or " ")).join(
            f"{name}: {snbt(child, indent, _depth + 1, max_array)}" for name, child in value.items())
        return f"{{{pad}{body}{end}}}"
    raise ValueError(f"cannot render tag id {tag}")


def shape(value) -> str:
    """One-word type description, e.g. 'list<compound>' or 'long[37]'."""
    tag = value.tag
    if tag == LIST:
        return f"list<{TYPE_NAMES[value.element]}>[{len(value)}]"
    if tag in (BYTE_ARRAY, INT_ARRAY, LONG_ARRAY):
        return f"{TYPE_NAMES[tag][:-2]}[{len(value)}]"
    return TYPE_NAMES[tag]
