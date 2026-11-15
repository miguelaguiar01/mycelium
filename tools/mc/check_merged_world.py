"""Merge two played copies of a world with the real command-line tool and load the result in the game.

Builds a small base world on the server, copies it twice, plays each copy with
console commands, runs mycelium-merge, then starts the server on the merged
world and checks blocks, containers, entities and the log. Runs once with each
side as the newer one. This is a smoke test of the whole tool, not the scenario
suite.

    python3 tools/mc/check_merged_world.py
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys

from build_probe_world import expect, wait_loaded
from mcserver import MC_HOME, REPO_ROOT, Server

RUNS = MC_HOME / "runs"
TOOL = REPO_ROOT / "merge" / "build" / "install" / "mycelium-merge" / "bin" / "mycelium-merge"
PROPERTIES = {"level-type": "minecraft:flat", "generate-structures": "false",
              "generator-settings": '{"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1}]}'}
DOOR = "minecraft:oak_door[facing=north,hinge=left,open=false,powered=false,half={}]"
BED = "minecraft:red_bed[facing=east,occupied=false,part={}]"
COW = "00000000-0000-0000-0000-000000000064"


def uid(n):
    return f"[I;0,0,0,{n}]"


def play(run, commands):
    """Run console commands on a world while it is frozen, then save."""
    with Server(run, PROPERTIES) as server:
        expect(server, ["tick freeze"], "frozen")
        for command, success in commands:
            expect(server, [command], success)
        server.save()


SET = "Changed the block at"
BASE = [
    ('setblock 2 1 2 minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:64},'
     '{Slot:1b,id:"minecraft:iron_ingot",count:10},{Slot:2b,id:"minecraft:gold_ingot",count:5}]}', SET),
    ("setblock 4 0 2 minecraft:farmland[moisture=7] strict", SET),
    ("setblock 4 1 2 minecraft:wheat[age=1] strict", SET),
    (f"summon minecraft:cow 10.5 1 2.5 {{UUID:{uid(100)},NoAI:1b,PersistenceRequired:1b}}", "Summoned"),
]
OURS = [
    ("setblock 2 1 8 minecraft:stone", SET),                       # built only here
    ("setblock 6 1 8 minecraft:stone_bricks", SET),                # the same block, built differently
    (f"setblock 8 1 8 {DOOR.format('lower')} strict", SET),        # a door ...
    (f"setblock 8 2 8 {DOOR.format('upper')} strict", SET),
    (f"setblock 15 1 12 {BED.format('foot')} strict", SET),        # a bed across the chunk border
    (f"setblock 16 1 12 {BED.format('head')} strict", SET),
    ("setblock 20 1 8 minecraft:oak_fence strict", SET),           # a fence with nothing next to it
    ("item replace block 2 1 2 container.0 with minecraft:air", "Replaced"),
    ("item replace block 2 1 2 container.1 with minecraft:iron_ingot 4", "Replaced"),
    ("setblock 4 1 2 minecraft:wheat[age=5] strict", SET),
    (f"summon minecraft:pig 10.5 1 10.5 {{UUID:{uid(150)},NoAI:1b,PersistenceRequired:1b}}", "Summoned"),
    (f"tp {COW} 14.5 1 2.5", "Teleported"),
]
THEIRS = [
    ("setblock 4 1 8 minecraft:glass", SET),
    ("setblock 6 1 8 minecraft:glass", SET),
    ("setblock 8 2 8 minecraft:stone", SET),                       # ... where the other side puts a block
    ("setblock 16 1 12 minecraft:glass", SET),
    ("setblock 21 1 8 minecraft:stone", SET),                      # right next to the other side's fence
    ("item replace block 2 1 2 container.2 with minecraft:air", "Replaced"),
    ("item replace block 2 1 2 container.1 with minecraft:iron_ingot 7", "Replaced"),
    ("item replace block 2 1 2 container.9 with minecraft:bread 3", "Replaced"),
    ("setblock 4 1 2 minecraft:wheat[age=3] strict", SET),
    (f"summon minecraft:sheep 12.5 1 10.5 {{UUID:{uid(160)},NoAI:1b,PersistenceRequired:1b}}", "Summoned"),
    (f"tp {COW} 10.5 1 6.5", "Teleported"),
]

FENCE_JOINED = "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]"
# check -> expected answer, per newer side
COMMON = {
    "execute if block 2 1 8 minecraft:stone": True,
    "execute if block 4 1 8 minecraft:glass": True,
    "execute if block 21 1 8 minecraft:stone": True,
    f"execute if block 20 1 8 {FENCE_JOINED}": True,              # refreshed by the game on load
    "execute if block 4 1 2 minecraft:wheat[age=5]": True,        # the further grown crop
    "execute if data block 2 1 2 Items[{Slot:0b}]": False,        # emptied by ours
    "execute if data block 2 1 2 Items[{Slot:2b}]": False,        # emptied by theirs
    'execute if data block 2 1 2 Items[{Slot:9b,id:"minecraft:bread",count:3}]': True,
    "execute if entity 00000000-0000-0000-0000-000000000096": True,   # ours' pig
    "execute if entity 00000000-0000-0000-0000-0000000000a0": True,   # theirs' sheep
    f"execute if entity {COW}": True,
}
BY_NEWER = {
    "ours": {
        "execute if block 6 1 8 minecraft:stone_bricks": True,
        f"execute if block 8 1 8 {DOOR.format('lower')}": True,   # the door stands whole
        f"execute if block 8 2 8 {DOOR.format('upper')}": True,
        f"execute if block 15 1 12 {BED.format('foot')}": True,
        f"execute if block 16 1 12 {BED.format('head')}": True,
        'execute if data block 2 1 2 Items[{Slot:1b,count:4}]': True,
        f"execute as {COW} at @s if block ~ ~-1 ~-0 minecraft:smooth_stone positioned 14.5 1 2.5 if entity @s[distance=..0.5]": True,
    },
    "theirs": {
        "execute if block 6 1 8 minecraft:glass": True,
        "execute if block 8 1 8 minecraft:air": True,             # no half door left under the stone
        "execute if block 8 2 8 minecraft:stone": True,
        "execute if block 15 1 12 minecraft:air": True,           # the bed foot went with its head
        "execute if block 16 1 12 minecraft:glass": True,
        'execute if data block 2 1 2 Items[{Slot:1b,count:7}]': True,
        f"execute as {COW} at @s positioned 10.5 1 6.5 if entity @s[distance=..0.5]": True,
    },
}


def main():
    for name in ("merge-base", "merge-ours", "merge-theirs", "merge-out-ours", "merge-out-theirs"):
        if (RUNS / name).exists():
            shutil.rmtree(RUNS / name)
    with Server(RUNS / "merge-base", PROPERTIES) as server:
        expect(server, ["forceload add 0 0 31 15"], "Marked")
        wait_loaded(server, [(0, 0), (1, 0)])
        expect(server, ["tick freeze"], "frozen")
        expect(server, ["fill 0 0 0 31 0 15 minecraft:smooth_stone"], "Successfully filled")
        for command, success in BASE:
            expect(server, [command], success)
        server.save()
    for name, commands in (("merge-ours", OURS), ("merge-theirs", THEIRS)):
        shutil.copytree(RUNS / "merge-base" / "world", RUNS / name / "world")
        (RUNS / name / "world" / "session.lock").unlink(missing_ok=True)
        play(RUNS / name, commands)

    subprocess.run([str(REPO_ROOT / "gradlew"), "-q", ":merge:installDist"], cwd=REPO_ROOT, check=True)
    failures = 0
    for newer in ("ours", "theirs"):
        out = RUNS / f"merge-out-{newer}"
        report_path = out / "report.json"
        result = subprocess.run(
            [str(TOOL), "--base", str(RUNS / "merge-base" / "world"), "--ours", str(RUNS / "merge-ours" / "world"),
             "--theirs", str(RUNS / "merge-theirs" / "world"), "--newer", newer, "--out", str(out / "world"),
             "--report", str(report_path)], capture_output=True, text=True)
        print(f"\n=== --newer {newer}: exit {result.returncode}: {result.stdout.strip()}{result.stderr.strip()}")
        if result.returncode != 1:
            failures += 1
            print("   PROBLEM: expected exit code 1 (merged with conflicts)")
            continue
        report = json.loads(report_path.read_text())
        for entry in report["entries"]:
            where = ",".join(str(v) for v in entry.get("pos", []))
            print(f"   {entry['severity']:8s} {entry['category']:15s} {where:10s} winner={entry.get('winner', '-'):6s}"
                  f" {entry.get('detail', '')}")

        checks = {**COMMON, **BY_NEWER[newer]}
        with Server(out, PROPERTIES) as server:
            server.wait_ticks(40)      # let the chunks start ticking, which is when the game refreshes listed blocks
            server.run("tick freeze")
            wrong = []
            for command, expected in checks.items():
                lines = [line for line in server.run(command) if "Test passed" in line or "Test failed" in line]
                if len(lines) != 1 or ("Test passed" in lines[0]) != expected:
                    wrong.append(command)
            server.save()
        log = [line for line in server.errors() if "OFFLINE" not in line and "authenticate" not in line
               and "hackers" not in line and "online-mode" not in line and "codename" not in line]
        print(f"   in game: {len(checks) - len(wrong)} of {len(checks)} checks as expected; log warnings and errors: {len(log)}")
        for item in wrong + log:
            print("   PROBLEM:", item)
        failures += len(wrong) + len(log)
    print("\nall checks passed" if not failures else f"\n{failures} problems")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
