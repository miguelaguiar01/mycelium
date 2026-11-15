"""Merge the worlds of the play session both ways and load the results in the game.

The three worlds were played by hand in the 26.3 client; docs/SCENARIOS.md lists the
steps. They hold what the server console cannot make: a player file, advancements,
statistics, maps, and a boat that a player left the game in.

Each merged world is read back with the Python readers and then loaded on the server,
which is asked for the blocks, barrels and boats. The server log must stay clean.

    python3 tools/mc/check_play_session.py

Needs the worlds unpacked as .mc/play/zip-{base,ours,theirs}/myc-{base,ours,theirs}.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys

import nbtlib
from mcserver import MC_HOME, REPO_ROOT, Server
from regionlib import Region

PLAY = MC_HOME / "play"
TOOL = REPO_ROOT / "merge" / "build" / "install" / "mycelium-merge" / "bin" / "mycelium-merge"
DIM = "dimensions/minecraft/overworld"
EXPECTED_LOG = ("OFFLINE", "authenticate", "hackers", "online-mode", "codename", "Can't keep up")
# Where ours and theirs put the barrel with their new map, and the middle of the area that map shows.
BARRELS = {"ours": ((300, -60, 4), 256), "theirs": ((-300, -60, 3), -256)}


def world(side: str):
    return PLAY / f"zip-{side}" / f"myc-{side}"


def player_file(folder) -> dict:
    files = sorted((folder / "players" / "data").glob("*.dat"))
    if len(files) != 1:
        raise SystemExit(f"{folder}: expected one player file, found {len(files)}")
    return nbtlib.load_file(files[0])[1]


def boats(folder, kind: str) -> list:
    found = []
    for file in sorted((folder / DIM / "entities").glob("*.mca")):
        for entry in Region(file).entries():
            found += [e for e in entry.nbt().get("Entities", []) if str(e["id"]) == f"minecraft:{kind}_boat"]
    return found


def stats(folder) -> dict:
    file = next((folder / "players" / "stats").glob("*.json"))
    return json.loads(file.read_text())["stats"]


def check_files(merged, newer: str) -> list[str]:
    """What the merged world must hold on disk. `newer` is the side that won newer-side rules."""
    older = "ours" if newer == "theirs" else "theirs"
    problems = []

    def expect(what: str, got, wanted) -> None:
        if got != wanted:
            problems.append(f"{what}: {got!r}, expected {wanted!r}")

    player = player_file(merged)
    winner = player_file(world(newer))
    expect("inventory is the newer side's", player["Inventory"], winner["Inventory"])
    expect("older side's player file is kept as a backup",
           len(list((merged / "players" / "data").glob(f"*.dat.{older}-backup-1"))), 1)
    # Ours left the game in the birch boat. It is in the player's file if ours is newer, else back in the world.
    riding = "RootVehicle" in player
    expect("player sits in the birch boat", riding, newer == "ours")
    expect("birch boats in the world", len(boats(merged, "birch")), 0 if riding else 1)
    expect("oak boats in the world", len(boats(merged, "oak")), 1)
    for boat in boats(merged, "birch") + boats(merged, "oak"):
        expect(f"{boat['id']} is at the end of its channel", round(float(boat["Pos"][0])), 63)

    # Both sides made a map with id 1. The newer side's keeps it; the older side's becomes map 2.
    maps = merged / "data" / "minecraft" / "maps"
    expect("last map id", int(nbtlib.load_file(maps / "last_id.dat")[1]["data"]["map"]), 2)
    for side, number in ((newer, 1), (older, 2)):
        centre = int(nbtlib.load_file(maps / f"{number}.dat")[1]["data"]["xCenter"])
        expect(f"map {number} is the one {side} made", centre, BARRELS[side][1])

    base, ours, theirs, merged_stats = (stats(folder)["minecraft:custom"] for folder in
                                        (world("base"), world("ours"), world("theirs"), merged))
    for counter in ("minecraft:play_time", "minecraft:leave_game"):
        expect(counter, merged_stats[counter], ours[counter] + theirs[counter] - base[counter])
    newer_stats = ours if newer == "ours" else theirs
    expect("time since death is the newer side's", merged_stats["minecraft:time_since_death"],
           newer_stats["minecraft:time_since_death"])
    advancements = json.loads(next((merged / "players" / "advancements").glob("*.json")).read_text())
    expect("criteria of A Balanced Diet", sorted(advancements["minecraft:husbandry/balanced_diet"]["criteria"]),
           ["apple", "bread"])
    return problems


def check_in_game(merged, newer: str) -> list[str]:
    older = "ours" if newer == "theirs" else "theirs"
    (x1, y1, z1), (x2, y2, z2) = BARRELS[newer][0], BARRELS[older][0]
    checks = {
        "execute if block 2 -60 14 minecraft:gold_block": "Test passed",
        "execute if block 2 -60 18 minecraft:emerald_block": "Test passed",
        "execute if block 30 -61 8 minecraft:water": "Test passed",
        "execute if block 30 -61 24 minecraft:water": "Test passed",
        f"data get block {x1} {y1} {z1} Items[0].components": '{"minecraft:map_id": 1}',
        f"data get block {x2} {y2} {z2} Items[0].components": '{"minecraft:map_id": 2}',
        "execute if entity @e[type=minecraft:oak_boat]": "Count: 1",
        "execute if entity @e[type=minecraft:birch_boat]": "Test failed" if newer == "ours" else "Count: 1",
    }
    run = PLAY / f"run-{newer}-newer"
    shutil.rmtree(run, ignore_errors=True)
    shutil.copytree(merged, run / "world")
    problems = []
    with Server(run, {"sync-chunk-writes": "false"}, memory="4G") as server:
        server.run("forceload add 0 0 79 31", "forceload add 288 0 303 15", "forceload add -304 0 -289 15")
        server.sprint(100)
        for command, wanted in checks.items():
            answer = " ".join(server.run(command))
            if wanted not in answer:
                problems.append(f"{command}: {answer.split(']: ', 1)[-1]}")
        server.run("forceload remove all")
        server.save()
    problems += [f"log: {line}" for line in server.errors() if not any(known in line for known in EXPECTED_LOG)]
    shutil.rmtree(run, ignore_errors=True)
    return problems


def main() -> None:
    subprocess.run([str(REPO_ROOT / "gradlew"), "-q", ":merge:installDist"], cwd=REPO_ROOT, check=True)
    failed = False
    for newer in ("theirs", "ours"):
        merged = PLAY / f"merged-{newer}-newer"
        shutil.rmtree(merged, ignore_errors=True)
        result = subprocess.run([str(TOOL), "--base", str(world("base")), "--ours", str(world("ours")), "--theirs",
                                 str(world("theirs")), "--newer", newer, "--out", str(merged)],
                                capture_output=True, text=True)
        print(f"newer = {newer}: {result.stdout.strip()}", flush=True)
        if result.returncode != 1 or "1 conflicts" not in result.stdout or "note:" in result.stdout:
            problems = [f"mycelium-merge exited with {result.returncode}: {result.stderr.strip()[-300:]}"]
        else:
            problems = check_files(merged, newer) + check_in_game(merged, newer)
        failed |= bool(problems)
        print("  ok" if not problems else "  " + "\n  ".join(problems), flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
