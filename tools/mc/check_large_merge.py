"""Merge the 2x2-region worst case of each large world and load the result in the game.

Both sides changed every chunk, so every chunk of the result is a mix of the two. The game is
asked, chunk by chunk in two regions, for the block each side placed, the newer side's block
where both placed one, and the containers. The server log must stay clean.

    python3 tools/mc/check_large_merge.py [overworld|nether|end ...]
    python3 tools/mc/check_large_merge.py relit

`relit` merges the 2x2-region overworld in which each side played in part of it, with
--relight all. Most chunks are copied there, and a copied chunk is then written again without
its light and heightmaps. The game must give both back in two regions and log nothing.

Needs the worlds of build_large_world.py.
"""

from __future__ import annotations

import subprocess
import sys

from build_large_world import DIMENSIONS, PROPERTIES, force_region, region_chunks, release
from mcserver import Server
from measure_merge import case_worlds, command
from regionlib import Region
from scenariolib import DIM

REGIONS = [(0, 0), (1, 1)]
EXPECTED_LOG = ("OFFLINE", "authenticate", "hackers", "online-mode", "codename", "Can't keep up")


def checks(dimension: str, cx: int, cz: int) -> list[str]:
    """Conditions that must hold in one merged chunk; `theirs` was the newer side."""
    x, z = cx * 16, cz * 16
    y = DIMENSIONS[dimension][1]
    wanted = [f"block {x + 3} {y} {z + 3} minecraft:gold_block", f"block {x + 12} {y} {z + 12} minecraft:iron_block"]
    if cx % 8 == 0 and cz % 8 == 0:
        wanted.append(f"block {x + 8} {y + 1} {z + 8} minecraft:emerald_block")
    if cx % 8 == 4 and cz % 8 == 4:
        wanted.append(f"data block {x + 5} {y} {z + 5} Items[{{id:\"minecraft:bread\",count:{1 + cx % 60}}}]")
        wanted.append(f"data block {x + 10} {y} {z + 10} Items[{{id:\"minecraft:coal\",count:{1 + cz % 60}}}]")
    return [f"execute in {DIMENSIONS[dimension][0]} if {condition}" for condition in wanted]


def check(dimension: str) -> list[str]:
    whole = 8 if dimension == "overworld" else 4
    case, worlds = case_worlds(dimension, 2, whole, "all")
    out = case / "out"
    merged = subprocess.run(command(worlds, out), capture_output=True, text=True)
    if merged.returncode not in (0, 1) or "merged into" not in merged.stdout:
        return [f"mycelium-merge exited with {merged.returncode}: {merged.stderr.strip()[-300:]}"]
    problems = []
    with Server(out, PROPERTIES, memory="4G") as server:
        for rx, rz in REGIONS:
            force_region(server, dimension, rx, rz)
            commands = [c for cx, cz in region_chunks(rx, rz) for c in checks(dimension, cx, cz)]
            lines = server.run(*commands)
            passed = sum("Test passed" in line for line in lines)
            if passed != len(commands):
                failed = [line for line in lines if "Test failed" in line or "<--[HERE]" in line]
                problems.append(f"region {rx},{rz}: {passed} of {len(commands)} checks pass; {failed[:3]}")
            else:
                print(f"  {dimension} region {rx},{rz}: {passed} of {len(commands)} checks pass", flush=True)
            release(server, dimension)
        server.save()
    problems += [f"log: {line}" for line in server.errors() if not any(known in line for known in EXPECTED_LOG)]
    return problems


def light(world, rx: int, rz: int) -> tuple[int, int]:
    """How many finished chunks of a region have their light and all four heightmaps, and how many there are."""
    lit = finished = 0
    for entry in Region(world / DIM / "region" / f"r.{rx}.{rz}.mca").entries():
        root = entry.nbt()
        if str(root["Status"]) == "minecraft:full":
            finished += 1
            lit += root.get("isLightOn") == 1 and len(root.get("Heightmaps", {})) == 4
    return lit, finished


def check_relit() -> list[str]:
    case, worlds = case_worlds("overworld", 2, 8, "some")
    out = case / "out-relit"
    merged = subprocess.run(command(worlds, out) + ["--relight", "all"], capture_output=True, text=True)
    if merged.returncode not in (0, 1) or "merged into" not in merged.stdout:
        return [f"mycelium-merge exited with {merged.returncode}: {merged.stderr.strip()[-300:]}"]
    problems = []
    for rx, rz in REGIONS:
        lit, finished = light(out / "world", rx, rz)
        if lit or not finished:
            problems.append(f"region {rx},{rz} as merged: {lit} of {finished} finished chunks still have their light")
    with Server(out, PROPERTIES, memory="4G") as server:
        for rx, rz in REGIONS:
            force_region(server, "overworld", rx, rz)
            server.wait_ticks(100)
            release(server, "overworld")
        server.save()
    # The worlds of this case are put together from region files of two worlds. An animal that crossed a
    # region border on one side is then in both of that side's files, and the game says so when it loads it.
    known_here = EXPECTED_LOG + ("UUID of added entity already exists",)
    problems += [f"log: {line}" for line in server.errors() if not any(known in line for known in known_here)]
    for rx, rz in REGIONS:
        lit, finished = light(out / "world", rx, rz)
        print(f"  overworld region {rx},{rz}: the game relit {lit} of {finished} finished chunks", flush=True)
        if lit != finished or finished < 1024:
            problems.append(f"region {rx},{rz} after loading: {lit} of {finished} finished chunks have light and heightmaps")
    return problems


def main() -> None:
    failed = False
    if sys.argv[1:] == ["relit"]:
        problems = check_relit()
        print("relit: " + ("ok" if not problems else "\n  " + "\n  ".join(problems[:20])), flush=True)
        sys.exit(1 if problems else 0)
    for dimension in sys.argv[1:] or sorted(DIMENSIONS):
        problems = check(dimension)
        failed |= bool(problems)
        print(f"{dimension}: " + ("ok" if not problems else "\n  " + "\n  ".join(problems[:20])), flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
