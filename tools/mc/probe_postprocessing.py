"""Check whether a chunk's PostProcessing list makes the game refresh stale neighbour states.

A fence stores which sides it connects on. This places fences next to stone
with every connection off, which the game would never write itself, saves, and
then lists some of those fences in the chunk's PostProcessing list. After a
reload, a refreshed fence has its connection to the stone turned on.

    python3 tools/mc/probe_postprocessing.py

Result on 26.3: recorded in docs/FORMAT-26.3.md.
"""

from __future__ import annotations

import shutil

import nbtlib
import regionlib
from build_probe_world import expect, wait_loaded
from mcserver import MC_HOME, Server

DIM = "dimensions/minecraft/overworld"
STALE = "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]"
FRESH = "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]"

# name -> fence position; stone goes one block east of each.
FENCES = {
    "listed": (3, 5, 9),
    "listed, another section": (6, 40, 12),
    "not listed": (10, 5, 2),
    "not listed, x and z of the first swapped": (9, 5, 3),
    "not listed, y of the first in x": (5, 3, 9),
}
LISTED = ("listed", "listed, another section")


def pack(pos):
    """Position inside its section as the game packs it: x, then y, then z, four bits each."""
    return (pos[0] & 15) | (pos[1] & 15) << 4 | (pos[2] & 15) << 8


def states(run):
    with Server(run) as server:
        server.run("tick freeze")
        server.wait_ticks(40)
        result = {}
        for name, (x, y, z) in FENCES.items():
            lines = server.run(f"execute if block {x} {y} {z} {FRESH}", f"execute if block {x} {y} {z} {STALE}")
            answers = ["Test passed" in line for line in lines if "Test " in line]
            result[name] = "refreshed" if answers == [True, False] else "stale" if answers == [False, True] else "other"
    return result


def main():
    run = MC_HOME / "runs" / "probe-postprocessing"
    if run.exists():
        shutil.rmtree(run)
    properties = {"level-type": "minecraft:flat", "generate-structures": "false",
                  "generator-settings": '{"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1}]}'}
    with Server(run, properties) as server:
        expect(server, ["forceload add 0 0 15 15"], "Marked")
        wait_loaded(server, [(0, 0)])
        expect(server, ["tick freeze"], "frozen")
        for x, y, z in FENCES.values():
            expect(server, [f"setblock {x} {y} {z} {STALE} strict", f"setblock {x + 1} {y} {z} minecraft:stone strict"],
                   "Changed the block at")
        server.save()

    print("reloaded as saved:           ", states(run))

    path = run / "world" / DIM / "region" / "r.0.0.mca"
    chunks = {e.index: (e.timestamp, e.nbt()) for e in regionlib.Region(path).entries()}
    root = chunks[0][1]
    lists = root["PostProcessing"]
    for name in LISTED:
        pos = FENCES[name]
        index = (pos[1] >> 4) - int(root["yPos"])
        lists[index] = nbtlib.List(nbtlib.SHORT, list(lists[index]) + [nbtlib.Short(pack(pos))])
    regionlib.write_region(path, chunks)
    print("reloaded with two listed:    ", states(run))

    root = {e.index: e.nbt() for e in regionlib.Region(path).entries()}[0]
    print("PostProcessing after the load:", [len(inner) for inner in root["PostProcessing"] if len(inner)] or "all empty")


if __name__ == "__main__":
    main()
