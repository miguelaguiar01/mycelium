"""Copy the probe worlds into the merge module's test fixtures.

Keeps only what the codec tests need: the region that holds the probe chunks,
its entity and POI files, and the small world-level data files that let the
game load the fixture as a world. Run build_probe_world.py first.

    python3 tools/mc/install_fixtures.py
"""

from __future__ import annotations

import shutil

from mcserver import MC_HOME, REPO_ROOT

FIXTURES = REPO_ROOT / "merge" / "src" / "test" / "resources" / "fixtures"
DIM = "dimensions/minecraft/overworld"

# variant -> the region that holds its probe chunks
VARIANTS = {"zlib": "r.1.1", "lz4": "r.0.0", "none": "r.0.0"}


def main() -> None:
    for variant, region in VARIANTS.items():
        run = MC_HOME / "runs" / f"probe-{variant}"
        target = FIXTURES / f"probe-{variant}"
        if target.exists():
            shutil.rmtree(target)
        rx, rz = (int(part) for part in region.split(".")[1:])
        files = [run / "expected.json", run / "world" / "level.dat"]
        files += sorted((run / "world" / "data").rglob("*.dat"))
        files += sorted((run / "world" / DIM / "data").rglob("*.dat"))
        for kind in ("region", "entities", "poi"):
            files.append(run / "world" / DIM / kind / f"{region}.mca")
        # External chunk files belong to the region that covers their chunk coordinates.
        for external in sorted((run / "world" / DIM / "region").glob("c.*.mcc")):
            x, z = (int(part) for part in external.name.split(".")[1:3])
            if (x >> 5, z >> 5) == (rx, rz):
                files.append(external)
        total = 0
        for source in files:
            destination = target / source.relative_to(run)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            total += source.stat().st_size
        print(f"{target.relative_to(REPO_ROOT)}: {len(files)} files, {total / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
