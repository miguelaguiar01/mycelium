"""Pens of animals, eggs and bees, for the creature scenario in scenarios.py.

Each pen is a glass box on the flat platform, 8 blocks apart along x. The scenario lets a day
pass in one copy and two days in the other, merges them and prints, pen by pen, which
creatures each side has and which the merge kept. docs/TIME-EFFECTS.md was written from that.

Creatures the scenario summons have UUIDs that end in a small number, so the output can say
which of them is which. Everything else was made by the game in one of the worlds.
"""

from __future__ import annotations

import collections
from pathlib import Path

from garden import entities_of
from scenariolib import block_at, block_entity_at

AREA = (0, 0, 79, 15)
Z = 4


def uid(n: int) -> str:
    return f"[I;0,0,0,{n}]"


def box(x: int, floor: str, height: int = 3, roof: str | None = None) -> list[str]:
    commands = [f"fill {x} 1 {Z} {x + 6} {height} {Z + 6} minecraft:glass",
                f"fill {x + 1} 1 {Z + 1} {x + 5} {height} {Z + 5} minecraft:air",
                f"fill {x + 1} 0 {Z + 1} {x + 5} 0 {Z + 5} minecraft:{floor}"]
    if roof:
        commands.append(f"fill {x} {height + 1} {Z} {x + 6} {height + 1} {Z + 6} minecraft:{roof}")
    return commands


def livestock(x):
    """A calf that grows up within two days, a shorn sheep on grass and a hen about to lay."""
    return box(x, "grass_block") + [
        f"summon minecraft:cow {x + 2} 1 {Z + 2} {{UUID:{uid(201)},Age:-20000}}",
        f"summon minecraft:sheep {x + 4} 1 {Z + 2} {{UUID:{uid(202)},Sheared:1b}}",
        f"summon minecraft:chicken {x + 3} 1 {Z + 4} {{UUID:{uid(203)},EggLayTime:300}}"]


def tadpoles(x):
    """One tadpole that is a frog within a day and one that needs two."""
    return box(x, "stone", roof="glass") + [
        f"fill {x + 1} 1 {Z + 1} {x + 5} 1 {Z + 5} minecraft:water",
        f"summon minecraft:tadpole {x + 2} 1 {Z + 2} {{UUID:{uid(211)},Age:22000}}",
        f"summon minecraft:tadpole {x + 4} 1 {Z + 4} {{UUID:{uid(212)},Age:5000}}"]


def cures(x):
    """Two zombie villagers being cured, apart from each other: one is done within a day, one needs two."""
    return box(x, "stone", roof="stone") + [
        f"fill {x + 3} 1 {Z + 1} {x + 3} 3 {Z + 5} minecraft:glass",
        f"summon minecraft:zombie_villager {x + 1} 1 {Z + 3} {{UUID:{uid(221)},ConversionTime:1000}}",
        f"summon minecraft:zombie_villager {x + 5} 1 {Z + 3} {{UUID:{uid(222)},ConversionTime:20000}}"]


def household(x):
    """A sitting wolf, a villager, a diamond on the floor and a copper golem about to weather."""
    return box(x, "stone") + [
        f"summon minecraft:wolf {x + 1} 1 {Z + 1} {{UUID:{uid(231)},Owner:{uid(999)},Sitting:1b}}",
        f"summon minecraft:villager {x + 4} 1 {Z + 4} {{UUID:{uid(232)}}}",
        f"summon minecraft:item {x + 1} 1 {Z + 5} {{UUID:{uid(233)},Item:{{id:\"minecraft:diamond\",count:1}}}}",
        f"summon minecraft:copper_golem {x + 5} 1 {Z + 1} {{UUID:{uid(234)},weather_state:\"weathered\",next_weather_age:5000L}}"]


BEE = '{entity_data:{id:"minecraft:bee"},ticks_in_hive:0,min_ticks_in_hive:0}'


def nests(x: int) -> list[tuple[int, int, int]]:
    return [(x + 1, 2, Z + 3), (x + 5, 2, Z + 3)]


def bees(x):
    """Two nests of three bees among flowers, under a glass roof."""
    commands = box(x, "grass_block", height=5, roof="glass")
    for nx, ny, nz in nests(x):
        commands += [f"setblock {nx} 1 {nz} minecraft:oak_fence", f"setblock {nx} {ny} {nz} minecraft:bee_nest{{bees:[{BEE},{BEE},{BEE}]}}"]
    return commands + [f"setblock {x + 3} 1 {Z + 1} minecraft:dandelion", f"setblock {x + 3} 1 {Z + 5} minecraft:poppy",
                       f"setblock {x + 2} 1 {Z + 5} minecraft:cornflower", f"setblock {x + 4} 1 {Z + 1} minecraft:allium"]


def bees_at_the_end(side: str) -> list:
    """Where the bees are when a side is saved.

    Left alone they are in or out by chance. So each side ends in a fixed state: on ours every bee
    of both nests is in the air, on theirs the bees of the first nest rest in it and the second
    nest holds one bee, the other two having died.
    """
    x = origin("bees")
    first, second = nests(x)
    # First no bee anywhere: the nests are emptied, and the bees in the air die, which takes a second.
    commands = [f"data modify block {nx} {ny} {nz} bees set value []" for nx, ny, nz in (first, second)]
    commands += ["execute as @e[type=minecraft:bee] run kill @s", ("sprint", 40)]
    if side == "ours":
        for n, (nx, ny, nz) in enumerate((first, second)):
            commands += [f"summon minecraft:bee {x + 2 + i} 3 {Z + 2 + 2 * n} {{UUID:{uid(241 + 3 * n + i)},hive_pos:[I;{nx},{ny},{nz}]}}"
                         for i in range(3)]
    else:
        commands.append(f"data modify block {first[0]} {first[1]} {first[2]} bees set value [{BEE},{BEE},{BEE}]")
        commands.append(f"data modify block {second[0]} {second[1]} {second[2]} bees set value [{BEE}]")
    return commands


def hatchery(x):
    """Turtle eggs on sand, frogspawn over a pool, a sniffer egg on moss and a dried ghast in water."""
    # The floor is stone: sand would fall, since nothing lies under the platform.
    return box(x, "stone", height=4, roof="glass") + [
        f"setblock {x + 1} -1 {Z + 1} minecraft:stone", f"setblock {x + 1} 0 {Z + 1} minecraft:sand",
        f"setblock {x + 1} 1 {Z + 1} minecraft:turtle_egg[eggs=2]",
        f"fill {x + 3} 1 {Z + 1} {x + 5} 1 {Z + 3} minecraft:water", f"setblock {x + 4} 2 {Z + 2} minecraft:frogspawn",
        # The pool is closed on all sides and above, or the tadpoles flop out of it.
        f"fill {x + 2} 1 {Z + 1} {x + 2} 3 {Z + 4} minecraft:glass", f"fill {x + 3} 1 {Z + 4} {x + 5} 3 {Z + 4} minecraft:glass",
        f"fill {x + 3} 3 {Z + 1} {x + 5} 3 {Z + 3} minecraft:glass",
        f"setblock {x + 1} 0 {Z + 5} minecraft:moss_block", f"setblock {x + 1} 1 {Z + 5} minecraft:sniffer_egg",
        f"setblock {x + 4} 0 {Z + 5} minecraft:glass", f"setblock {x + 4} 1 {Z + 5} minecraft:dried_ghast[waterlogged=true]",
        f"setblock {x + 3} 1 {Z + 5} minecraft:glass", f"setblock {x + 5} 1 {Z + 5} minecraft:glass"]


PENS = [("livestock", livestock), ("tadpoles", tadpoles), ("cures", cures), ("household", household), ("bees", bees),
        ("hatchery", hatchery)]


def origin(pen: str) -> int:
    """The lowest x of a pen. The hatchery stands apart, further from the tadpoles than a twin is looked for."""
    index = [name for name, _ in PENS].index(pen)
    return 1 + 8 * index + (24 if pen == "hatchery" else 0)


def base_commands() -> list[str]:
    return [command for name, build in PENS for command in build(origin(name))]


def pen_of(entity: dict) -> str | None:
    return next((name for name, _ in PENS if origin(name) <= entity["pos"][0] < origin(name) + 8), None)


def label(entity: dict, sides: dict[str, set[str]]) -> str:
    """An entity as text: its kind, and its number if the scenario summoned it, else which worlds made it."""
    uuid = entity["uuid"]
    tag = entity["tag"]
    extra = "".join(f" {key}={tag[key]}" for key in ("Age", "Sheared", "weather_state") if key in tag and tag[key] not in (0, "unaffected"))
    if uuid.startswith("00000000-00000000-00000000-"):
        return f"{entity['id']} #{int(uuid.rsplit('-', 1)[1], 16)}{extra}"
    return f"{entity['id']} (new in {'+'.join(side for side in ('ours', 'theirs') if uuid in sides[side])}){extra}"


def describe(worlds: dict[str, Path], merged: str = "merged") -> list[str]:
    names = ("base", "ours", "theirs", merged)
    found = {side: entities_of(worlds[side]) for side in names}
    sides = {side: {e["uuid"] for e in found[side]} for side in names}
    lines = []
    for pen, _ in PENS:
        lines.append(f"{pen}:")
        for side in names:
            here = sorted(label(e, sides) for e in found[side] if pen_of(e) == pen)
            counted = collections.Counter(here)
            lines.append(f"    {side:6} " + (", ".join(f"{n} x {what}" if n > 1 else what for what, n in sorted(counted.items())) or "nothing"))
        x = origin(pen)
        if pen == "bees":
            for side in names:
                inside = [len((block_entity_at(worlds[side], *pos) or {}).get("bees", [])) for pos in nests(x)]
                lines.append(f"    {side:6} bees in the two nests: {inside[0]} and {inside[1]}")
        if pen == "hatchery":
            spots = {"turtle eggs": (x + 1, 1, Z + 1), "frogspawn": (x + 4, 2, Z + 2), "sniffer egg": (x + 1, 1, Z + 5),
                     "dried ghast": (x + 4, 1, Z + 5)}
            for what, pos in spots.items():
                lines.append(f"    {what}: " + ", ".join(f"{side} {(block_at(worlds[side], *pos) or '?').removeprefix('minecraft:')}"
                                                         for side in names))
    return lines


def problems(worlds: dict[str, Path], merged: str = "merged") -> list[str]:
    """Where the merged world does not have every creature once."""
    found = {side: collections.Counter(e["id"] for e in entities_of(worlds[side])) for side in ("ours", "theirs", merged)}

    def young(side: str) -> int:
        return found[side]["tadpole"] + found[side]["frog"]

    got = {kind: n for kind, n in found[merged].items() if kind not in ("item", "tadpole", "frog")}
    for pos in nests(origin("bees")):
        got["bee"] = got.get("bee", 0) + len((block_entity_at(worlds[merged], *pos) or {}).get("bees", []))
    got["tadpoles and frogs"] = young(merged)
    # What hatched or grew up on either side is there, and each creature once. Each side has the two of the
    # tadpole pen, as tadpoles or frogs, and what its frogspawn gave: 2 to 5 tadpoles. Where the two sides got
    # different numbers from the spawn, the larger one stays.
    wanted = {"chicken": 1, "cow": 1, "sheep": 1, "villager": 3, "wolf": 1, "sniffer": 1, "bee": 6,
              "turtle": max(found["ours"]["turtle"], found["theirs"]["turtle"]),
              "happy_ghast": max(found["ours"]["happy_ghast"], found["theirs"]["happy_ghast"]),
              "tadpoles and frogs": max(young("ours"), young("theirs"))}
    wanted = {kind: n for kind, n in wanted.items() if n}
    if got != wanted:
        return [f"creatures: the merged world has {dict(sorted(got.items()))}, expected {dict(sorted(wanted.items()))}"]
    return []
