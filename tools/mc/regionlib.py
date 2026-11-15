"""Minimal region (.mca) reader and writer for inspection scripts.

Reports what is on disk without interpreting it: header entries, the stored
length and compression byte of each chunk, and external (.mcc) chunk files.
Standard library only; the LZ4 block-stream decoder is implemented here.
"""

from __future__ import annotations

import gzip
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path

import nbtlib

SECTOR = 4096
EXTERNAL_FLAG = 0x80
COMPRESSION_NAMES = {1: "gzip", 2: "zlib", 3: "none", 4: "lz4", 127: "custom"}


def _lz4_block(src: bytes, size: int) -> bytes:
    """Decompress one raw LZ4 block to exactly `size` bytes."""
    out = bytearray()
    pos = 0
    while pos < len(src):
        token = src[pos]
        pos += 1
        literal = token >> 4
        if literal == 15:
            while True:
                extra = src[pos]
                pos += 1
                literal += extra
                if extra != 255:
                    break
        out += src[pos:pos + literal]
        pos += literal
        if pos >= len(src):
            break
        offset = src[pos] | src[pos + 1] << 8
        pos += 2
        match = token & 15
        if match == 15:
            while True:
                extra = src[pos]
                pos += 1
                match += extra
                if extra != 255:
                    break
        match += 4
        start = len(out) - offset
        for i in range(match):  # may overlap its own output
            out.append(out[start + i])
    if len(out) != size:
        raise ValueError(f"LZ4 block decoded to {len(out)} bytes, expected {size}")
    return bytes(out)


def lz4_block_stream(data: bytes) -> bytes:
    """Decode lz4-java's LZ4BlockOutputStream framing ("LZ4Block" magic)."""
    out = bytearray()
    pos = 0
    while pos < len(data):
        if data[pos:pos + 8] != b"LZ4Block":
            raise ValueError(f"bad LZ4Block magic at {pos}: {data[pos:pos + 8]!r}")
        token = data[pos + 8]
        compressed, original, _checksum = struct.unpack_from("<iii", data, pos + 9)
        pos += 21
        method = token & 0xF0
        if original == 0:
            break  # end mark
        body = data[pos:pos + compressed]
        pos += compressed
        if method == 0x10:
            out += body
        elif method == 0x20:
            out += _lz4_block(body, original)
        else:
            raise ValueError(f"unknown LZ4Block method {method:#x}")
    return bytes(out)


def decompress(kind: int, payload: bytes) -> bytes:
    if kind == 1:
        return gzip.decompress(payload)
    if kind == 2:
        return zlib.decompress(payload)
    if kind == 3:
        return payload
    if kind == 4:
        return lz4_block_stream(payload)
    raise ValueError(f"unsupported compression type {kind}")


@dataclass
class ChunkEntry:
    index: int            # z * 32 + x within the region
    sector: int           # first sector, from the header
    sectors: int          # sector count, from the header
    timestamp: int        # header timestamp, epoch seconds
    length: int           # stored length field (compression byte + payload)
    compression_byte: int  # raw byte, including the external flag
    payload: bytes        # compressed bytes, read from the .mcc file when external
    external: Path | None = None

    @property
    def local(self) -> tuple[int, int]:
        return self.index % 32, self.index // 32

    @property
    def compression(self) -> int:
        return self.compression_byte & ~EXTERNAL_FLAG

    def nbt(self):
        return nbtlib.loads(decompress(self.compression, self.payload))[1]


class Region:
    def __init__(self, path):
        self.path = Path(path)
        name = self.path.name.split(".")
        self.rx, self.rz = int(name[1]), int(name[2])
        self.data = self.path.read_bytes()
        self.size = len(self.data)

    def entries(self) -> list[ChunkEntry]:
        entries = []
        if self.size < 2 * SECTOR:
            return entries
        offsets = struct.unpack_from(">1024I", self.data, 0)
        stamps = struct.unpack_from(">1024I", self.data, SECTOR)
        for index, location in enumerate(offsets):
            if location == 0:
                continue
            sector, sectors = location >> 8, location & 0xFF
            start = sector * SECTOR
            length, kind = struct.unpack_from(">IB", self.data, start)
            payload = self.data[start + 5:start + 4 + length]
            external = None
            if kind & EXTERNAL_FLAG:
                x, z = self.rx * 32 + index % 32, self.rz * 32 + index // 32
                external = self.path.with_name(f"c.{x}.{z}.mcc")
                payload = external.read_bytes()
            entries.append(ChunkEntry(index, sector, sectors, stamps[index], length, kind,
                                      payload, external))
        return entries

    def chunk(self, chunk_x: int, chunk_z: int) -> ChunkEntry | None:
        index = (chunk_z & 31) * 32 + (chunk_x & 31)
        return next((e for e in self.entries() if e.index == index), None)


def write_region(path, chunks: dict[int, tuple[int, object]]) -> None:
    """Write a region file from {index: (timestamp, root compound)}, zlib-compressed."""
    offsets = [0] * 1024
    stamps = [0] * 1024
    body = bytearray()
    sector = 2
    for index in sorted(chunks):
        timestamp, root = chunks[index]
        payload = zlib.compress(nbtlib.dumps(root))
        record = struct.pack(">IB", len(payload) + 1, 2) + payload
        count = -(-len(record) // SECTOR)
        if count > 255:
            raise ValueError("chunk too large for this simple writer")
        body += record + b"\x00" * (count * SECTOR - len(record))
        offsets[index] = sector << 8 | count
        stamps[index] = timestamp
        sector += count
    Path(path).write_bytes(struct.pack(">1024I", *offsets) + struct.pack(">1024I", *stamps) + body)
