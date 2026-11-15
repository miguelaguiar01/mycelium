"""Cut the play-session worlds down to a test fixture for the merge module.

The three worlds were played by hand in the 26.3 client, following the steps in
docs/SCENARIOS.md ("The play session"), and unpacked under .mc/play. They are the
only inputs with player files, maps and a boat that a player left the game in,
none of which the server console can produce.

Kept from each world: level.dat, the world-gen settings, the map files, the
player files, the entity region that holds the boats, and the two chunks with
the barrels.

The fixture must not identify the person who played. The player's account UUID
is replaced everywhere, in file names and inside the files, by PLACEHOLDER, and
the script fails if any trace of it is left. Nothing else in these files names
the player: 26.3 stores no player name in a world.

    python3 tools/mc/install_play_fixture.py
"""

from __future__ import annotations

import gzip
import shutil
import struct
import uuid
import zlib
from pathlib import Path

from mcserver import MC_HOME, REPO_ROOT
from regionlib import SECTOR, Region, decompress

FIXTURE = REPO_ROOT / "merge" / "src" / "test" / "resources" / "fixtures" / "play-session"
DIM = "dimensions/minecraft/overworld"
# The barrel ours placed is in chunk (18, 0), the one theirs placed in chunk (-19, 0).
BARREL_CHUNKS = [(18, 0), (-19, 0)]
PLACEHOLDER = uuid.UUID("00000000-0000-4000-8000-000000000001")
ZLIB = 2


def scrubbed(data: bytes, player: uuid.UUID) -> bytes:
    """Uncompressed NBT or text with the player's UUID replaced. NBT stores a UUID as its 16 bytes."""
    return (data.replace(player.bytes, PLACEHOLDER.bytes)
            .replace(str(player).encode(), str(PLACEHOLDER).encode())
            .replace(player.hex.encode(), PLACEHOLDER.hex.encode()))


def chunk_records(entries, player: uuid.UUID) -> dict[int, tuple[int, int, bytes]]:
    """Region records {index: (timestamp, compression byte, payload)}, as stored unless the UUID was in them."""
    records = {}
    for entry in entries:
        if entry.external is not None:
            raise SystemExit(f"chunk {entry.index} is stored outside its region file")
        plain = decompress(entry.compression, entry.payload)
        clean = scrubbed(plain, player)
        records[entry.index] = ((entry.timestamp, entry.compression_byte, entry.payload) if clean == plain
                                else (entry.timestamp, ZLIB, zlib.compress(clean)))
    return records


def write_region(path: Path, records: dict[int, tuple[int, int, bytes]]) -> None:
    offsets = [0] * 1024
    stamps = [0] * 1024
    body = bytearray()
    sector = 2
    for index in sorted(records):
        timestamp, kind, payload = records[index]
        record = struct.pack(">IB", len(payload) + 1, kind) + payload
        count = -(-len(record) // SECTOR)
        body += record + b"\x00" * (count * SECTOR - len(record))
        offsets[index] = sector << 8 | count
        stamps[index] = timestamp
        sector += count
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(struct.pack(">1024I", *offsets) + struct.pack(">1024I", *stamps) + body)


def traces(folder: Path, player: uuid.UUID) -> list[str]:
    """Every place under a folder that still holds the player's UUID, in a name or in content."""
    needles = [player.bytes, str(player).encode(), player.hex.encode()]
    found = []
    for file in sorted(f for f in folder.rglob("*") if f.is_file()):
        contents = [file.read_bytes()]
        if file.suffix == ".mca":
            contents = [decompress(entry.compression, entry.payload) for entry in Region(file).entries()]
        elif contents[0][:2] == b"\x1f\x8b":
            contents = [gzip.decompress(contents[0])]
        if any(needle in str(file).encode() for needle in needles) or any(n in c for n in needles for c in contents):
            found.append(str(file.relative_to(folder)))
    return found


def main() -> None:
    if FIXTURE.exists():
        shutil.rmtree(FIXTURE)
    players = {file.stem for side in ("base", "ours", "theirs")
               for file in (MC_HOME / "play" / f"zip-{side}" / f"myc-{side}" / "players" / "data").glob("*.dat")}
    if len(players) != 1:
        raise SystemExit(f"expected one player in the three worlds, found {len(players)}")
    player = uuid.UUID(players.pop())
    for side in ("base", "ours", "theirs"):
        world = MC_HOME / "play" / f"zip-{side}" / f"myc-{side}"
        target = FIXTURE / side
        files = [world / "level.dat", world / "data" / "minecraft" / "world_gen_settings.dat"]
        files += sorted((world / "data" / "minecraft" / "maps").glob("*.dat"))
        files += sorted(file for file in (world / "players").rglob("*") if file.is_file())
        for file in files:
            copy = target / str(file.relative_to(world)).replace(str(player), str(PLACEHOLDER))
            copy.parent.mkdir(parents=True, exist_ok=True)
            data = file.read_bytes()
            if data[:2] == b"\x1f\x8b":
                plain = gzip.decompress(data)
                clean = scrubbed(plain, player)
                # mtime=0 keeps the fixture the same from run to run.
                copy.write_bytes(data if clean == plain else gzip.compress(clean, mtime=0))
            else:
                copy.write_bytes(scrubbed(data, player))
        write_region(target / DIM / "entities" / "r.0.0.mca",
                     chunk_records(Region(world / DIM / "entities" / "r.0.0.mca").entries(), player))
        cut: dict[tuple[int, int], list] = {}
        for cx, cz in BARREL_CHUNKS:
            entry = Region(world / DIM / "region" / f"r.{cx >> 5}.{cz >> 5}.mca").chunk(cx, cz)
            if entry is None:
                raise SystemExit(f"{side}: chunk {cx},{cz} is missing")
            cut.setdefault((cx >> 5, cz >> 5), []).append(entry)
        for (rx, rz), entries in cut.items():
            write_region(target / DIM / "region" / f"r.{rx}.{rz}.mca", chunk_records(entries, player))
        size = sum(file.stat().st_size for file in target.rglob("*") if file.is_file())
        print(f"{side}: {sum(1 for file in target.rglob('*') if file.is_file())} files, {size // 1024} KiB")
    left = traces(FIXTURE, player)
    if left:
        shutil.rmtree(FIXTURE)
        raise SystemExit("the player's UUID is still in: " + ", ".join(left) + "; the fixture was removed")
    print("no trace of the player's UUID in the fixture")


if __name__ == "__main__":
    main()
