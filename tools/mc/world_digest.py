"""Print a digest of everything a world folder holds, to compare two runs of the same merge.

The merge must give the same world on any machine. "The same" is defined on what the files hold
once decompressed, because compressed bytes depend on the version of the compression library.
So this hashes, for every file: the decompressed NBT of each chunk with its slot and its save
time, the decompressed content of gzip files, and the bytes of everything else. Unlike the
comparison in the merge module's tests, it is sensitive to the order of entities and block
entities, and it covers player files, maps and the report if it lies in the folder.

    python3 tools/mc/world_digest.py <world folder> [--files]

Two worlds are the same if the last line is the same. With --files, every file's own digest is
printed first, so that two listings can be compared with diff.
"""

from __future__ import annotations

import gzip
import hashlib
import sys
from pathlib import Path

from regionlib import Region, decompress


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    if path.suffix == ".mca":
        for entry in sorted(Region(path).entries(), key=lambda e: e.index):
            digest.update(f"{entry.index} {entry.timestamp} ".encode())
            digest.update(hashlib.sha256(decompress(entry.compression, entry.payload)).digest())
        return digest.hexdigest()
    data = path.read_bytes()
    if data[:2] == b"\x1f\x8b":
        try:
            data = gzip.decompress(data)
        except OSError:
            pass    # not gzip after all
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    arguments = [a for a in sys.argv[1:] if a != "--files"]
    if len(arguments) != 1:
        sys.exit(__doc__)
    world = Path(arguments[0])
    total = hashlib.sha256()
    # External chunk files are read through their region file, and the lock file is not part of the world.
    files = sorted(f for f in world.rglob("*") if f.is_file() and f.suffix != ".mcc" and f.name != "session.lock")
    for file in files:
        line = f"{file_digest(file)}  {file.relative_to(world).as_posix()}"
        total.update(line.encode())
        if "--files" in sys.argv:
            print(line)
    print(f"{total.hexdigest()}  {len(files)} files")


if __name__ == "__main__":
    main()
