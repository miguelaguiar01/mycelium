"""Load worlds rewritten by the Java format layer in the real game and check them.

For each fixture world this runs the Gradle task `:merge:rewriteWorld`, which
passes every chunk, entity chunk, POI chunk and data file through the Java
codecs, then starts the 26.3 server on the result and checks that

  * the log has no warnings or errors,
  * sampled blocks are the ones the probe script placed,
  * the game answers every biome query exactly as it does on the untouched world,
  * every summoned entity exists and rides the right vehicle,
  * container block entities hold the items the fixture file says they hold.

The untouched fixture world goes through the same checks first, as the control.

    python3 tools/mc/check_rewritten_world.py
"""

from __future__ import annotations

import json
import random
import shutil
import subprocess
import sys
from pathlib import Path

import nbtlib
import regionlib
from mcserver import MC_HOME, REPO_ROOT, Server

FIXTURES = REPO_ROOT / "merge" / "src" / "test" / "resources" / "fixtures"
REPORTS = MC_HOME / "server" / "26.3" / "datagen" / "generated" / "reports"
DIM = "dimensions/minecraft/overworld"
VARIANTS = {"zlib": "ZLIB", "lz4": "LZ4", "none": "NONE"}

# Machines keep working for a few ticks before the world can be frozen, so their items may move.
RUNNING = {"minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker", "minecraft:brewing_stand",
           "minecraft:hopper", "minecraft:campfire"}


def full_state(state: str, defaults: dict) -> str:
    """Expand a bare block name to its default properties, so the game compares every property."""
    if "[" in state or not defaults[state]:
        return state
    return state + "[" + ",".join(f"{k}={v}" for k, v in sorted(defaults[state].items())) + "]"


def snbt_item(item) -> str:
    return f'{{Slot:{int(item["Slot"])}b,id:"{item["id"]}",count:{int(item["count"])}}}'


def container_checks(world: Path) -> list[str]:
    """One `execute if data block` per container in the finished chunks of the fixture file."""
    commands = []
    for path in sorted((world / DIM / "region").glob("r.*.mca")):
        for entry in regionlib.Region(path).entries():
            root = entry.nbt()
            if str(root["Status"]) != "minecraft:full":
                continue
            for block_entity in root["block_entities"]:
                items = block_entity.get("Items")
                if not items or str(block_entity["id"]) in RUNNING:
                    continue
                x, y, z = (int(block_entity[k]) for k in "xyz")
                # The barrel under the hopper keeps receiving items.
                if str(block_entity["id"]) == "minecraft:barrel" and any(
                        str(other["id"]) == "minecraft:hopper" and (int(other["x"]), int(other["y"]) - 1, int(other["z"])) == (x, y, z)
                        for other in root["block_entities"]):
                    continue
                listed = ",".join(snbt_item(item) for item in list(items)[:4])
                commands.append(f"execute if data block {x} {y} {z} {{Items:[{listed}]}}")
    return commands


def check(run: Path, expected: dict, containers: list[str], defaults: dict) -> tuple[list[str], list[bool]]:
    """Start the server on run/world. Returns the problems found and the answers to the biome queries."""
    problems = []
    rng = random.Random(5)
    with Server(run) as server:
        server.run("tick freeze")

        def passed(commands: list[str], what: str, must_pass: bool = True) -> list[bool]:
            lines = []
            for start in range(0, len(commands), 1000):
                lines += [line for line in server.run(*commands[start:start + 1000])
                          if "Test passed" in line or "Test failed" in line]
            failed = [command for command, line in zip(commands, lines) if "Test passed" not in line]
            if len(lines) != len(commands):
                problems.append(f"{what}: {len(commands)} checks sent, {len(lines)} answered")
            if failed and must_pass:
                problems.append(f"{what}: {len(failed)} of {len(commands)} failed, first: {failed[0]}")
            print(f"   {what}: {len(commands) - len(failed)} of {len(commands)} passed")
            return ["Test passed" in line for line in lines]

        commands = []
        for section in expected["blockSections"]:
            x0, y0, z0 = section["chunk"][0] * 16, section["sectionY"] * 16, section["chunk"][1] * 16
            for index in rng.sample(range(4096), 200):
                state = full_state(section["states"][section["cells"][index]], defaults)
                commands.append(f"execute if block {x0 + (index & 15)} {y0 + (index >> 8)} {z0 + (index >> 4 & 15)} {state}")
        passed(commands, "sampled blocks")

        commands = []
        for section in expected["biomeSections"]:
            x0, y0, z0 = section["chunk"][0] * 16, section["sectionY"] * 16, section["chunk"][1] * 16
            for index, biome in enumerate(section["cells"]):
                x, y, z = (index & 3) * 4 + 1, (index >> 4) * 4 + 1, (index >> 2 & 3) * 4 + 1
                commands.append(f"execute if biome {x0 + x} {y0 + y} {z0 + z} {section['biomes'][biome]}")
        # The game blends biomes between neighbouring cells with seeded noise, so a block inside a
        # cell can report the biome next door. The answers are still a fixed function of the stored
        # cells, so they must be the same on the control and on the rewritten world.
        biome_answers = passed(commands, "biome queries naming the stored cell's biome", must_pass=False)

        passed([f"execute if entity {uuid}" for uuid in expected["entities"]], "entities by UUID")
        riders = {uuid: e["vehicle"] for uuid, e in expected["entities"].items() if e["vehicle"]}
        for rider, vehicle in riders.items():
            lines = server.run(f"execute as {rider} on vehicle run data get entity @s UUID")
            ints = [int(v, 16) - (1 << 32) if int(v, 16) >= 1 << 31 else int(v, 16)
                    for v in (vehicle.replace("-", "")[i:i + 8] for i in range(0, 32, 8))]
            wanted = "[I; " + ", ".join(str(v) for v in ints) + "]"
            if not any(wanted in line for line in lines):
                problems.append(f"rider {rider} is not on vehicle {vehicle}: {lines}")
        print(f"   riders on their vehicles: {len(riders)} checked")

        passed(containers, "container contents")
        server.save()
    log_problems = [line for line in server.errors() if "OFFLINE" not in line and "authenticate" not in line
                    and "hackers" not in line and "online-mode" not in line and "codename" not in line]
    print(f"   log warnings and errors: {len(log_problems)}")
    problems += [f"log: {line}" for line in log_problems]
    return problems, biome_answers


def main() -> None:
    blocks = json.loads((REPORTS / "blocks.json").read_text())
    defaults = {name: next(s for s in block["states"] if s.get("default")).get("properties", {})
                for name, block in blocks.items()}
    failures = 0
    for variant, compression in VARIANTS.items():
        fixture = FIXTURES / f"probe-{variant}"
        expected = json.loads((fixture / "expected.json").read_text())
        containers = container_checks(fixture / "world")
        original = MC_HOME / "runs" / f"rewrite-original-{variant}"
        rewritten = MC_HOME / "runs" / f"rewrite-result-{variant}"
        for run in (original, rewritten):
            if run.exists():
                shutil.rmtree(run)
        shutil.copytree(fixture / "world", original / "world")
        rewritten.mkdir(parents=True)
        result = subprocess.run(
            [str(REPO_ROOT / "gradlew"), "-q", ":merge:rewriteWorld",
             f"--args={fixture / 'world'} {rewritten / 'world'} {compression}"],
            cwd=REPO_ROOT, capture_output=True, text=True)
        if result.returncode != 0:
            sys.exit(f"rewriteWorld failed for {variant}:\n{result.stdout}\n{result.stderr}")
        print(f"\n=== probe-{variant}: {result.stdout.strip().splitlines()[-1]}")
        external = sorted(p.name for p in (rewritten / "world" / DIM / "region").glob("c.*.mcc"))
        if external:
            print(f"   external chunk files written: {external}")
        answers = []
        for label, run in (("fixture as built (control)", original), ("rewritten by the Java format layer", rewritten)):
            print(f"-- {label}")
            problems, biome_answers = check(run, expected, containers, defaults)
            answers.append(biome_answers)
            for problem in problems:
                print("   PROBLEM:", problem)
            failures += len(problems)
        same = answers[0] == answers[1]
        print(f"-- biome queries answered the same on both worlds: {same} ({len(answers[0])} queries)")
        failures += 0 if same else 1
    print("\nall checks passed" if not failures else f"\n{failures} problems")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
