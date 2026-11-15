"""Shared machinery for the merge scenarios in scenarios.py.

A scenario builds a base world on the 26.3 server, copies it twice, plays each
copy with console commands, merges the three with the mycelium-merge command,
loads the merged world in the game and checks it against a written expectation.
"""

from __future__ import annotations

import collections
import json
import re
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import nbtlib
import regionlib
from build_probe_world import wait_loaded
from check_probe_world import state_string, unwrap
from mcserver import MC_HOME, REPO_ROOT, Server
from probe_rebuild import light_levels

RUNS = MC_HOME / "runs"
TOOL = REPO_ROOT / "merge" / "build" / "install" / "mycelium-merge" / "bin" / "mycelium-merge"
DIM = "dimensions/minecraft/overworld"
FLAT = {"level-type": "minecraft:flat", "generate-structures": "false",
        "generator-settings": '{"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1}]}'}
FAILED = re.compile(r"<--\[HERE\]|Unknown|Incorrect|not loaded|No entity was found|Could not|Unable to|Invalid|Expected")
MASK64 = (1 << 64) - 1


def sprint(ticks: int):
    """A step that lets the world run for exactly this many ticks."""
    return ("sprint", ticks)


@dataclass
class Merge:
    """One run of the tool on a scenario's three worlds, and what the merged world must show."""
    newer: str
    # Console command -> expected "Test passed". May be a function of the scenario's worlds instead,
    # for expectations that depend on what the sessions produced.
    checks: dict[str, bool] | Callable[[dict[str, Path]], dict[str, bool]] = field(default_factory=dict)
    report: Callable[[list[dict]], list[str]] | None = None        # report entries -> problems
    args: list[str] = field(default_factory=list)                  # extra options for mycelium-merge
    load: bool = True                                              # load the result in the game
    before_checks: list[str] = field(default_factory=list)         # commands to run first, e.g. forceload
    output: Callable[[str], list[str]] | None = None               # what the tool printed -> problems


@dataclass
class Scenario:
    key: str
    title: str
    expected: str                                                  # the written expected result
    base: list = field(default_factory=list)
    ours: list = field(default_factory=list)
    theirs: list = field(default_factory=list)
    merges: dict[str, Merge] = field(default_factory=dict)
    area: tuple[int, int, int, int] = (0, 0, 47, 15)               # forced blocks: x1 z1 x2 z2
    # More areas, loaded in the merged world, whose chunks must get their heightmaps and light back.
    also_derived: list[tuple[int, int, int, int]] = field(default_factory=list)
    natural: bool = False                                          # natural terrain instead of a flat platform
    control: bool = False                                          # also build base + ours + theirs in one world
    edit: Callable[[dict[str, Path]], None] | None = None          # change world files after the sessions
    files: Callable[[dict[str, Path], str], list[str]] | None = None   # worlds, merge label -> problems
    light: list[tuple[int, int, int]] = field(default_factory=list)    # positions to compare with the control
    light_is_limit: bool = False                                   # a light mismatch is a known limit: noted, not failed
    open_points: list[str] = field(default_factory=list)              # problems known to wait on a spec decision
    snapshot: bool = False                                         # keep the merged world as the tool wrote it


def run_dir(scenario: Scenario, part: str) -> Path:
    return RUNS / f"scn-{scenario.key}-{part}"


def play(run: Path, steps: list, properties: dict, setup: Callable[[Server], None] | None = None,
         area=None) -> None:
    """Start the server on run/world, apply the steps while frozen, save.

    `area` names forced chunks to wait for first, so that commands find their blocks and entities.
    """
    with Server(run, properties) as server:
        if setup:
            setup(server)
        server.run("tick freeze")
        if area:
            wait_loaded(server, chunks_of(area))
            server.wait_ticks(10)
        for step in steps:
            if isinstance(step, tuple) and step[0] == "sprint":
                server.sprint(step[1])
                continue
            if isinstance(step, tuple) and step[0] == "loaded":
                wait_loaded(server, chunks_of(step[1]))
                continue
            bad = [line for line in server.run(step) if FAILED.search(line.split("]: ", 1)[-1])]
            if bad:
                raise RuntimeError(f"{run.name}: command failed: {step}\n" + "\n".join(bad))
        server.save()


def fork(source: Path, target: Path) -> None:
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(source / "world", target / "world")
    (target / "world" / "session.lock").unlink(missing_ok=True)


def chunks_of(area) -> list[tuple[int, int]]:
    x1, z1, x2, z2 = area
    return [(cx, cz) for cx in range(x1 >> 4, (x2 >> 4) + 1) for cz in range(z1 >> 4, (z2 >> 4) + 1)]


# ---- reading world files ------------------------------------------------------------------------

def chunk_root(world: Path, cx: int, cz: int):
    path = world / DIM / "region" / f"r.{cx >> 5}.{cz >> 5}.mca"
    entry = regionlib.Region(path).chunk(cx, cz) if path.exists() else None
    return entry.nbt() if entry else None


def section_states(section) -> list[str]:
    """The 4,096 block states of a section, as text, in cell order."""
    palette = [state_string(e) for e in section["block_states"]["palette"]]
    if len(palette) == 1:
        return palette * 4096
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    cells = []
    for packed in section["block_states"]["data"]:
        packed &= MASK64
        for n in range(per_long):
            cells.append(palette[packed >> (n * bits) & ((1 << bits) - 1)])
    return cells[:4096]


def block_at(world: Path, x: int, y: int, z: int) -> str | None:
    root = chunk_root(world, x >> 4, z >> 4)
    if root is None:
        return None
    for section in root["sections"]:
        if int(section["Y"]) == y >> 4 and "block_states" in section:
            return section_states(section)[(y & 15) << 8 | (z & 15) << 4 | (x & 15)]
    return None


def block_entity_at(world: Path, x: int, y: int, z: int):
    root = chunk_root(world, x >> 4, z >> 4)
    for block_entity in root["block_entities"] if root else []:
        if (int(block_entity["x"]), int(block_entity["y"]), int(block_entity["z"])) == (x, y, z):
            return block_entity
    return None


def set_blocks(world: Path, blocks: dict[tuple[int, int, int], object], change=None) -> None:
    """Write block states straight into a world's region files, as another tool or a mod would.

    `blocks` maps positions to palette entries: a string or a {id, properties} compound.
    `change(root)` may edit the chunk root further. New entries are appended to the palette, so
    the result is valid but not in the game's canonical order.
    """
    by_chunk = collections.defaultdict(dict)
    for pos, entry in blocks.items():
        by_chunk[(pos[0] >> 4, pos[2] >> 4)][pos] = entry
    for (cx, cz), wanted in by_chunk.items():
        path = world / DIM / "region" / f"r.{cx >> 5}.{cz >> 5}.mca"
        chunks = {e.index: (e.timestamp, e.nbt()) for e in regionlib.Region(path).entries()}
        root = chunks[(cz & 31) * 32 + (cx & 31)][1]
        for (x, y, z), entry in wanted.items():
            section = next(s for s in root["sections"] if int(s["Y"]) == y >> 4)
            container = section["block_states"]
            palette = [unwrap(e)[0] for e in container["palette"]]
            bits = max(4, (len(palette) - 1).bit_length()) if len(palette) > 1 else 0
            cells = [0] * 4096
            if bits:
                per_long = 64 // bits
                cells = [(packed & MASK64) >> (n * bits) & ((1 << bits) - 1)
                         for packed in container["data"] for n in range(per_long)][:4096]
            palette.append(entry)
            cells[(y & 15) << 8 | (z & 15) << 4 | (x & 15)] = len(palette) - 1
            bits = max(4, (len(palette) - 1).bit_length())
            per_long = 64 // bits
            data = []
            for start in range(0, 4096, per_long):
                packed = 0
                for n, cell in enumerate(cells[start:start + per_long]):
                    packed |= cell << (n * bits)
                data.append(nbtlib.Long(packed - (1 << 64) if packed >= 1 << 63 else packed))
            if all(e.tag == nbtlib.STRING for e in palette):
                stored = nbtlib.List(nbtlib.STRING, palette)
            else:
                stored = nbtlib.List(nbtlib.COMPOUND, [
                    e if e.tag == nbtlib.COMPOUND else nbtlib.Compound({"": e}) for e in palette])
            container["palette"] = stored
            container["data"] = nbtlib.LongArray(data)
        if change:
            change(root)
        regionlib.write_region(path, chunks)


def derived_data_problems(world: Path, area) -> list[str]:
    """After the game has loaded and saved a world, every finished chunk must have its derived data back."""
    problems = []
    for cx, cz in chunks_of(area):
        root = chunk_root(world, cx, cz)
        if root is None or str(root["Status"]) != "minecraft:full":
            problems.append(f"chunk {cx},{cz} is missing or unfinished after the load")
            continue
        if root.get("isLightOn") != 1 or len(root.get("Heightmaps", {})) != 4:
            problems.append(f"chunk {cx},{cz}: isLightOn={root.get('isLightOn')},"
                            f" heightmaps={sorted(root.get('Heightmaps', {}))}")
    return problems


def same_blocks(a: Path, b: Path, area) -> list[str]:
    """Compare every block of the forced chunks of two worlds."""
    problems = []
    for cx, cz in chunks_of(area):
        ra, rb = chunk_root(a, cx, cz), chunk_root(b, cx, cz)
        for sa, sb in zip(ra["sections"], rb["sections"]):
            if "block_states" not in sa:
                continue
            ca, cb = section_states(sa), section_states(sb)
            differing = [i for i in range(4096) if ca[i] != cb[i]]
            if differing:
                i = differing[0]
                problems.append(f"chunk {cx},{cz} section {int(sa['Y'])}: {len(differing)} blocks differ, first at"
                                f" x={cx * 16 + (i & 15)} y={int(sa['Y']) * 16 + (i >> 8)} z={cz * 16 + (i >> 4 & 15)}:"
                                f" {ca[i]} vs {cb[i]}")
    return problems


def poi_records(world: Path, cx: int, cz: int) -> list:
    path = world / DIM / "poi" / f"r.{cx >> 5}.{cz >> 5}.mca"
    entry = regionlib.Region(path).chunk(cx, cz) if path.exists() else None
    if entry is None:
        return []
    return sorted((tuple(r["pos"]), str(r["type"])) for s in entry.nbt()["Sections"].values() for r in s["Records"])


def heightmap(world: Path, x: int, z: int, kind: str = "WORLD_SURFACE") -> int:
    """The y just above the highest block of a column, as the chunk's heightmap has it."""
    root = chunk_root(world, x >> 4, z >> 4)
    index = (z & 15) * 16 + (x & 15)
    packed = root["Heightmaps"][kind][index // 7] & MASK64
    return (packed >> (index % 7 * 9) & 511) + int(root["yPos"]) * 16


def counts(entries: list[dict]) -> dict[str, int]:
    return dict(collections.Counter(f"{e['severity']} {e['category']}" for e in entries))


def expect_counts(wanted: dict[str, int]):
    """A report check: exactly these numbers of entries per severity and category."""
    def check(entries):
        got = counts(entries)
        return [] if got == wanted else [f"report has {got}, expected {wanted}"]
    return check


# ---- running a scenario -------------------------------------------------------------------------

def run(scenario: Scenario) -> tuple[list[str], list[str]]:
    """Build, merge and check one scenario. Returns (problems, notes)."""
    properties = {} if scenario.natural else FLAT
    x1, z1, x2, z2 = scenario.area

    def setup(server):
        server.run(f"forceload add {x1} {z1} {x2} {z2}")
        wait_loaded(server, chunks_of(scenario.area))
        if not scenario.natural:
            server.run("tick freeze", f"fill {x1} 0 {z1} {x2} 0 {z2} minecraft:smooth_stone")

    worlds = {}
    base = run_dir(scenario, "base")
    if base.exists():
        shutil.rmtree(base)
    play(base, scenario.base, properties, setup)
    worlds["base"] = base / "world"
    for part, steps in (("ours", scenario.ours), ("theirs", scenario.theirs)):
        fork(base, run_dir(scenario, part))
        play(run_dir(scenario, part), steps, properties, area=scenario.area)
        worlds[part] = run_dir(scenario, part) / "world"
    if scenario.control:
        fork(base, run_dir(scenario, "control"))
        play(run_dir(scenario, "control"), scenario.ours + scenario.theirs, properties, area=scenario.area)
        worlds["control"] = run_dir(scenario, "control") / "world"
    if scenario.edit:
        scenario.edit(worlds)

    problems, notes = [], []
    control_light = None
    if scenario.light:
        with Server(run_dir(scenario, "control"), properties) as server:
            server.run("tick freeze")
            server.wait_ticks(60)
            control_light = light_levels(server, scenario.light)

    for label, merge in scenario.merges.items():
        out = run_dir(scenario, f"out-{label}")
        if out.exists():
            shutil.rmtree(out)
        report_path = out / "report.json"
        result = subprocess.run(
            [str(TOOL), "--base", str(worlds["base"]), "--ours", str(worlds["ours"]), "--theirs", str(worlds["theirs"]),
             "--newer", merge.newer, "--out", str(out / "world"), "--report", str(report_path), *merge.args],
            capture_output=True, text=True)
        if result.returncode not in (0, 1):
            problems.append(f"[{label}] mycelium-merge exited with {result.returncode}: {result.stderr.strip()}")
            continue
        report = json.loads(report_path.read_text())
        entries = report["entries"]
        # Exit code 1 means, and only means, that something was decided against a side or refused.
        expected_exit = 1 if any(e["severity"] in ("conflict", "refused") for e in entries) else 0
        if result.returncode != expected_exit:
            problems.append(f"[{label}] mycelium-merge exited with {result.returncode}, but its report calls for"
                            f" {expected_exit}")
        if merge.output:
            problems += [f"[{label}] {problem}" for problem in merge.output(result.stdout)]
        lines = result.stdout.strip().splitlines()
        notes.append(f"[{label}] {lines[0].split(': ', 1)[-1]}; entries {counts(entries)}")
        notes += [f"[{label}] {line[:110]}..." for line in lines[1:]]
        if merge.report:
            problems += [f"[{label}] {problem}" for problem in merge.report(entries)]
        worlds["out"] = out / "world"
        worlds["report"] = report_path
        if scenario.snapshot:
            # The game changes the world it loads. This copy stays as the tool wrote it.
            shutil.copytree(out / "world", out / "merged")
            worlds["merged"] = out / "merged"

        if merge.load:
            with Server(out, properties) as server:
                server.run("tick freeze", *merge.before_checks)
                server.wait_ticks(40)
                checks = merge.checks(worlds) if callable(merge.checks) else merge.checks
                for command, expected in checks.items():
                    answer = server.run(command)
                    lines = [line for line in answer if "Test passed" in line or "Test failed" in line]
                    if len(lines) != 1 or ("Test passed" in lines[0]) != expected:
                        said = "; ".join(line.split("]: ", 1)[-1] for line in answer) or "nothing"
                        problems.append(f"[{label}] in game: expected {'pass' if expected else 'fail'}: {command}"
                                        f" (the game said: {said})")
                if control_light is not None:
                    merged_light = light_levels(server, scenario.light)
                    wrong = [pos for pos in scenario.light if merged_light[pos] != control_light[pos]]
                    notes.append(f"[{label}] light: {len(scenario.light) - len(wrong)} of {len(scenario.light)} sampled"
                                 f" positions match the control")
                    if wrong and scenario.light_is_limit:
                        notes.append(f"[{label}] light misses, first (position, control, merged): "
                                     f"{[(p, control_light[p], merged_light[p]) for p in wrong[:4]]}")
                    elif wrong:
                        problems.append(f"[{label}] light differs from the control at {len(wrong)} of {len(scenario.light)}"
                                        f" positions; first (position, control, merged): "
                                        f"{[(p, control_light[p], merged_light[p]) for p in wrong[:3]]}")
                # Everything above was asked of a world standing still. Now it runs, so that block entities,
                # entities and scheduled ticks of the merged world are used at least once before the log is read.
                server.run("tick unfreeze")
                server.sprint(200)
                server.save()
            log = [line for line in server.errors() if "OFFLINE" not in line and "authenticate" not in line
                   and "hackers" not in line and "online-mode" not in line and "codename" not in line]
            problems += [f"[{label}] log: {line}" for line in log]
            for area in [scenario.area, *scenario.also_derived]:
                problems += [f"[{label}] {problem}" for problem in derived_data_problems(out / "world", area)]
        if scenario.files:
            problems += [f"[{label}] {problem}" for problem in scenario.files(worlds, label)]
    return problems, notes
