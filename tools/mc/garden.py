"""A garden of everything the game changes by itself, for the time scenarios in scenarios.py.

Each plot is a small build around its own origin on the flat platform: a crop on watered
farmland, a row of copper, a budding amethyst, a sapling. The scenarios let time pass in two
copies of the garden for different lengths, merge them and print, plot by plot, what each side
made of it and what the merge kept. docs/TIME-EFFECTS.md was written from that output.

Plots stand 8 blocks apart in two rows, at z = 6 and z = 22. A plot's box is the 8 columns from
its origin's x - 2 and the 16 rows of its chunk row; trees and chorus plants get a wider box.
"""

from __future__ import annotations

import collections
from dataclasses import dataclass
from pathlib import Path

import regionlib
from check_probe_world import state_string
from scenariolib import DIM, chunk_root, section_states

AREA = (0, 0, 143, 31)
TOP = 40    # the highest block a plot is read to


@dataclass
class Plot:
    name: str
    x: int
    z: int
    commands: list[str]
    width: int = 8

    def box(self) -> tuple[int, int, int, int]:
        z1 = self.z & ~15
        return self.x - 2, z1, self.x - 2 + self.width - 1, z1 + 15

    def holds(self, x: float, z: float) -> bool:
        x1, z1, x2, z2 = self.box()
        return x1 <= x < x2 + 1 and z1 <= z < z2 + 1


def crop(block: str):
    """A crop on farmland with water next to it, walled in so the water stays."""
    return lambda x, z: [f"fill {x - 1} 1 {z - 1} {x + 2} 1 {z + 1} minecraft:stone", f"setblock {x + 1} 1 {z} minecraft:water",
                         f"setblock {x} 1 {z} minecraft:farmland[moisture=7]", f"setblock {x} 2 {z} minecraft:{block}"]


def stem(block: str):
    """A stem with dirt on three sides, so that its fruit can grow on any of them."""
    return lambda x, z: crop(block)(x, z) + [f"setblock {x - 1} 1 {z} minecraft:dirt", f"setblock {x} 1 {z - 1} minecraft:dirt",
                                             f"setblock {x} 1 {z + 1} minecraft:dirt"]


def on(ground: str, block: str):
    return lambda x, z: [f"setblock {x} 1 {z} minecraft:{ground}", f"setblock {x} 2 {z} minecraft:{block}"]


def row(*blocks: str, y: int = 1, dz: int = 0):
    """Blocks side by side along x."""
    return lambda x, z: [f"setblock {x + i} {y} {z + dz} minecraft:{block}" for i, block in enumerate(blocks)]


def both(*parts):
    return lambda x, z: [command for part in parts for command in part(x, z)]


def cane(x, z):
    return [f"fill {x - 1} 1 {z - 1} {x + 2} 1 {z + 1} minecraft:stone", f"setblock {x + 1} 1 {z} minecraft:water",
            f"setblock {x} 1 {z} minecraft:sand", f"setblock {x} 2 {z} minecraft:sugar_cane"]


def kelp(x, z):
    return [f"fill {x - 1} 1 {z - 1} {x + 1} 9 {z + 1} minecraft:glass", f"fill {x} 1 {z} {x} 8 {z} minecraft:water",
            f"setblock {x} 1 {z} minecraft:kelp"]


def hanging(x, z):
    """Cave vines and weeping vines under a ceiling, twisting vines on the floor."""
    return [f"fill {x} 9 {z} {x + 3} 9 {z} minecraft:stone", f"setblock {x} 8 {z} minecraft:cave_vines",
            f"setblock {x + 3} 8 {z} minecraft:weeping_vines", f"setblock {x + 1} 1 {z + 4} minecraft:stone",
            f"setblock {x + 1} 2 {z + 4} minecraft:twisting_vines"]


def vine(x, z):
    return [f"fill {x} 1 {z} {x} 6 {z} minecraft:stone", f"setblock {x + 1} 4 {z} minecraft:vine[west=true]"]


def cocoa(x, z):
    return [f"fill {x} 1 {z} {x} 3 {z} minecraft:jungle_log", f"setblock {x + 1} 2 {z} minecraft:cocoa[facing=west]"]


def mushroom(x, z):
    """A mushroom in a dark room: it spreads only where light is low."""
    return [f"fill {x - 2} 1 {z - 2} {x + 2} 4 {z + 2} minecraft:stone", f"fill {x - 1} 1 {z - 1} {x + 1} 3 {z + 1} minecraft:air",
            f"setblock {x} 1 {z} minecraft:brown_mushroom"]


def copper_blocks(x, z):
    return row("copper_block", "cut_copper", "chiseled_copper", "copper_grate", "copper_bulb")(x - 1, z) + \
        row("cut_copper_stairs", "cut_copper_slab", "waxed_copper_block", "waxed_exposed_copper", "exposed_copper", dz=3)(x - 1, z)


def copper_things(x, z):
    return [f"setblock {x - 1} 1 {z} minecraft:copper_door[half=lower]", f"setblock {x - 1} 2 {z} minecraft:copper_door[half=upper]",
            f"setblock {x + 1} 1 {z} minecraft:copper_chest[facing=north,type=left]",
            f"setblock {x + 2} 1 {z} minecraft:copper_chest[facing=north,type=right]",
            f"setblock {x + 4} 1 {z} minecraft:copper_golem_statue"] + \
        row("copper_trapdoor", "copper_bars", "copper_chain", "copper_lantern", "lightning_rod", dz=3)(x - 1, z)


def amethyst(x, z):
    return [f"setblock {x} 3 {z} minecraft:budding_amethyst"]


def dripstone(x, z):
    """Two stalactites under wet dripstone. One hangs over a cauldron, one over bare stone."""
    return [f"fill {x - 1} 9 {z - 1} {x + 3} 10 {z + 1} minecraft:glass", f"fill {x} 9 {z} {x + 2} 9 {z} minecraft:dripstone_block",
            f"fill {x} 10 {z} {x + 2} 10 {z} minecraft:water", f"setblock {x} 8 {z} minecraft:pointed_dripstone[vertical_direction=down]",
            f"setblock {x + 2} 8 {z} minecraft:pointed_dripstone[vertical_direction=down]", f"setblock {x + 2} 1 {z} minecraft:cauldron"]


def sulfur(x, z):
    """The sulfur blocks of 26.x, set up like dripstone, to see what the game does with them."""
    return [f"fill {x - 1} 9 {z - 1} {x + 3} 10 {z + 1} minecraft:glass", f"fill {x} 9 {z} {x + 2} 9 {z} minecraft:sulfur",
            f"fill {x} 10 {z} {x + 2} 10 {z} minecraft:water", f"setblock {x} 8 {z} minecraft:sulfur_spike[vertical_direction=down]",
            f"setblock {x + 2} 8 {z} minecraft:sulfur_spike[vertical_direction=down]", f"setblock {x + 2} 1 {z} minecraft:cauldron",
            f"setblock {x} 1 {z + 4} minecraft:potent_sulfur", f"fill {x + 2} 1 {z + 3} {x + 4} 2 {z + 5} minecraft:glass",
            f"setblock {x + 3} 1 {z + 4} minecraft:potent_sulfur", f"setblock {x + 3} 2 {z + 4} minecraft:water"]


def soil(x, z):
    """Farmland without water, grass and mycelium next to dirt, grass and nylium under a block."""
    return on("farmland[moisture=7]", "wheat")(x - 1, z) + \
        row("grass_block", "dirt", "dirt", dz=3)(x - 1, z) + row("mycelium", "dirt", "dirt", dz=5)(x - 1, z) + \
        on("grass_block", "stone")(x + 3, z + 3) + on("crimson_nylium", "stone")(x + 3, z + 5)


def melt_and_burn(x, z):
    return [f"setblock {x - 1} 1 {z} minecraft:ice", f"setblock {x} 1 {z} minecraft:torch",
            f"setblock {x + 1} 1 {z} minecraft:snow[layers=3]", f"setblock {x + 1} 1 {z + 5} minecraft:netherrack",
            f"setblock {x + 1} 2 {z + 5} minecraft:fire", f"setblock {x + 2} 1 {z + 5} minecraft:oak_planks",
            f"setblock {x + 2} 2 {z + 5} minecraft:oak_planks", f"setblock {x + 3} 1 {z + 5} minecraft:oak_planks"]


def leaves(x, z):
    """Leaves with no log near, which decay, and a propagule hanging from leaves that stay."""
    return [f"fill {x - 1} 4 {z} {x + 1} 4 {z} minecraft:oak_leaves[distance=7]",
            f"setblock {x + 3} 5 {z + 4} minecraft:mangrove_leaves[persistent=true]",
            f"setblock {x + 3} 4 {z + 4} minecraft:mangrove_propagule[hanging=true,age=0]"]


def night_plants(x, z):
    return on("dirt", "closed_eyeblossom")(x - 1, z) + [
        f"setblock {x + 1} 1 {z} minecraft:potted_closed_eyeblossom",
        f"fill {x + 3} 1 {z} {x + 3} 3 {z} minecraft:pale_oak_log", f"setblock {x + 3} 2 {z} minecraft:creaking_heart[natural=true]"]


def eggs(x, z):
    return on("sand", "turtle_egg[eggs=2]")(x - 1, z) + [
        f"fill {x + 1} 1 {z - 1} {x + 3} 1 {z + 1} minecraft:glass", f"setblock {x + 2} 1 {z} minecraft:water",
        f"setblock {x + 2} 2 {z} minecraft:frogspawn", f"setblock {x - 1} 1 {z + 4} minecraft:moss_block",
        f"setblock {x - 1} 2 {z + 4} minecraft:sniffer_egg", f"fill {x + 1} 1 {z + 3} {x + 3} 1 {z + 5} minecraft:glass",
        f"setblock {x + 2} 1 {z + 4} minecraft:dried_ghast[waterlogged=true]"]


def sapling(kind: str):
    return lambda x, z: [f"fill {x - 1} 1 {z - 1} {x + 1} 1 {z + 1} minecraft:dirt", f"setblock {x} 2 {z} minecraft:{kind}_sapling"]


def chorus(x, z):
    return on("end_stone", "chorus_flower")(x, z)


def plots() -> list[Plot]:
    first = [("wheat", crop("wheat")), ("carrots", crop("carrots")), ("beetroots", crop("beetroots")),
             ("torchflower", crop("torchflower_crop")), ("pitcher crop", crop("pitcher_crop")),
             ("melon stem", stem("melon_stem")), ("pumpkin stem", stem("pumpkin_stem")),
             ("nether wart", on("soul_sand", "nether_wart")), ("sweet berries", on("dirt", "sweet_berry_bush")),
             ("cocoa", cocoa), ("sugar cane", cane), ("cactus", on("sand", "cactus")), ("bamboo", on("dirt", "bamboo_sapling")),
             ("kelp", kelp), ("hanging and twisting vines", hanging), ("vine", vine)]
    second = [("mushroom", mushroom), ("copper blocks", copper_blocks), ("copper things", copper_things),
              ("amethyst", amethyst), ("dripstone", dripstone), ("sulfur", sulfur), ("soil", soil),
              ("ice, snow and fire", melt_and_burn), ("leaves", leaves), ("eyeblossoms and creaking heart", night_plants),
              ("eggs", eggs)]
    made = [Plot(name, 4 + 8 * i, 6, build(4 + 8 * i, 6)) for i, (name, build) in enumerate(first)]
    made += [Plot(name, 4 + 8 * i, 22, build(4 + 8 * i, 22)) for i, (name, build) in enumerate(second)]
    # Plants that grow wide get a box of 16.
    for i, (name, build) in enumerate([("chorus plant", chorus), ("birch sapling", sapling("birch")), ("oak sapling", sapling("oak"))]):
        x = 98 + 16 * i
        made.append(Plot(name, x, 23, build(x, 23), width=16))
    return made


def origin(name: str) -> tuple[int, int]:
    """Where the plot of this name stands."""
    return next((plot.x, plot.z) for plot in plots() if plot.name == name)


def base_commands() -> list[str]:
    return [command for plot in plots() for command in plot.commands]


# ---- reading the worlds -------------------------------------------------------------------------

def blocks_of(world: Path, plot: Plot) -> dict[tuple[int, int, int], str]:
    """Every block of a plot's box that is not air, from the platform up."""
    x1, z1, x2, z2 = plot.box()
    found = {}
    for cx in range(x1 >> 4, (x2 >> 4) + 1):
        for cz in range(z1 >> 4, (z2 >> 4) + 1):
            root = chunk_root(world, cx, cz)
            for section in root["sections"] if root else []:
                sy = int(section["Y"])
                if "block_states" not in section or sy < 0 or sy * 16 > TOP:
                    continue
                for index, state in enumerate(section_states(section)):
                    x, y, z = cx * 16 + (index & 15), sy * 16 + (index >> 8), cz * 16 + (index >> 4 & 15)
                    if state != "minecraft:air" and x1 <= x <= x2 and z1 <= z <= z2 and y >= 1:
                        found[(x, y, z)] = state
    return found


def entities_of(world: Path) -> list[dict]:
    """Every entity of the overworld with its riders: id, uuid, position and the whole tag."""
    found = []

    def add(entity):
        uuid = "-".join(f"{int(part) & 0xFFFFFFFF:08x}" for part in entity["UUID"])
        found.append({"id": str(entity["id"]).split(":")[1], "uuid": uuid, "pos": [float(v) for v in entity["Pos"]], "tag": entity})
        for rider in entity.get("Passengers", []):
            add(rider)

    for file in sorted((world / DIM / "entities").glob("*.mca")):
        for entry in regionlib.Region(file).entries():
            for entity in entry.nbt().get("Entities", []):
                add(entity)
    return found


def name(state: str | None) -> str:
    return "air" if state is None else state.split("[")[0].removeprefix("minecraft:")


def short(state: str | None) -> str:
    return "air" if state is None else state.removeprefix("minecraft:")


def describe(worlds: dict[str, Path], entries: list[dict], merged: str = "merged", loaded: str | None = "out") -> list[str]:
    """What each side and the merge made of every plot, as lines of text.

    `loaded` names the merged world after the game ran it for a moment. What the game changed in
    it right away is listed too: a plant the merge left without its stalk breaks there.
    """
    lines = []
    by_pos = collections.defaultdict(list)
    for entry in entries:
        if entry.get("pos"):
            by_pos[tuple(entry["pos"])].append(entry)
    creatures = {side: entities_of(worlds[side]) for side in ("base", "ours", "theirs", merged)}
    for plot in plots():
        cells = {side: blocks_of(worlds[side], plot) for side in ("base", "ours", "theirs", merged)}
        groups = collections.defaultdict(list)
        for pos in sorted(set().union(*cells.values())):
            was, ours, theirs, out = (cells[side].get(pos) for side in ("base", "ours", "theirs", merged))
            if ours == was and theirs == was and out == was:
                continue
            verdicts = sorted({f"{e['severity']}:{e.get('detail') or e['category']}" for e in by_pos.get(pos, [])
                               if e["category"] in ("block", "unit", "no_ancestor", "block_entity", "item")})
            kind = "both" if ours != was and theirs != was and ours != theirs else "same" if ours == theirs else "one"
            groups[(name(was), name(ours), name(theirs), name(out), kind, " ".join(verdicts))].append((pos, was, ours, theirs, out))
        conflicts = sum(1 for (x, y, z), found in by_pos.items() if plot.holds(x, z)
                        for e in found if e["severity"] == "conflict")
        lines.append(f"{plot.name}: {conflicts} conflicts" if conflicts else f"{plot.name}:")
        for (was, ours, theirs, out, kind, verdict), members in sorted(groups.items(), key=lambda item: item[1][0][0]):
            if kind == "same":
                continue    # both sides did the same: nothing to merge
            pos, b, o, t, m = members[0]
            which = "ours" if m == o and m != t else "theirs" if m == t and m != o else "neither" if m != o else "both"
            lines.append(f"    {len(members):3} x {kind:4} {short(b)} -> ours {short(o)} | theirs {short(t)} => {short(m)}"
                         f" ({which}){' [' + verdict + ']' if verdict else ''}")
        if loaded:
            after = blocks_of(worlds[loaded], plot)
            moved = [(pos, cells[merged].get(pos), after.get(pos)) for pos in sorted(set(cells[merged]) | set(after))
                     if cells[merged].get(pos) != after.get(pos)]
            if moved:
                pos, was, now = moved[0]
                others = sum(name(before) != name(then) for _, before, then in moved)
                lines.append(f"    after loading, the game changed {len(moved)} blocks, {others} of them into another block,"
                             f" first {pos}: {short(was)} -> {short(now)}")
        for side in ("base", "ours", "theirs", merged):
            here = collections.Counter(e["id"] for e in creatures[side] if plot.holds(e["pos"][0], e["pos"][2]))
            if here or any(plot.holds(e["pos"][0], e["pos"][2]) for s in creatures.values() for e in s):
                lines.append(f"    entities in {side}: {dict(sorted(here.items())) or 'none'}")
    return lines
