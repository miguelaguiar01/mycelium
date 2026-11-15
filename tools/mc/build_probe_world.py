"""Build the format probe worlds used by docs/FORMAT-26.3.md and the codec tests.

Starts a 26.3 dedicated server on a fixed seed and scripts console commands to
create content whose on-disk form we need to see: block palettes at every bit
width, biome palettes, block entities, multi-block shapes, scheduled ticks and
entities with riders and leashes. Everything placed with a known value is
written to expected.json so decoders can be checked against it.

    python3 tools/mc/build_probe_world.py [zlib|lz4|none|oversized-zlib ...]

Worlds land in .mc/runs/probe-<variant>/world. `zlib` is the main probe world
on natural terrain. `lz4` and `none` are small flat worlds written with the
other two region-file-compression settings; `none` also holds a chunk too large
for a region file. `oversized-zlib` makes a zlib chunk that large.
"""

from __future__ import annotations

import json
import random
import re
import shutil
import sys
import time
from pathlib import Path

from mcserver import MC_HOME, Server

REPORTS = MC_HOME / "server" / "26.3" / "datagen" / "generated" / "reports"

BIOMES = """badlands bamboo_jungle basalt_deltas beach birch_forest cherry_grove cold_ocean
crimson_forest dappled_forest dark_forest deep_cold_ocean deep_dark deep_frozen_ocean
deep_lukewarm_ocean deep_ocean desert dripstone_caves end_barrens end_highlands end_midlands
eroded_badlands flower_forest forest frozen_ocean frozen_peaks frozen_river grove ice_spikes
jagged_peaks jungle lukewarm_ocean lush_caves mangrove_swamp meadow mushroom_fields nether_wastes
ocean old_growth_birch_forest old_growth_pine_taiga old_growth_spruce_taiga pale_garden plains
river savanna savanna_plateau small_end_islands snowy_beach snowy_plains snowy_slopes snowy_taiga
soul_sand_valley sparse_jungle stony_peaks stony_shore sulfur_caves sunflower_plains swamp taiga
the_end the_void warm_ocean warped_forest windswept_forest windswept_gravelly_hills
windswept_hills windswept_savanna wooded_badlands""".split()


class CommandFailed(RuntimeError):
    pass


def expect(server: Server, commands: list[str], success: str, chunk: int = 2000) -> list[str]:
    """Run commands and require one success line per command."""
    pattern = re.compile(success)
    output: list[str] = []
    for start in range(0, len(commands), chunk):
        batch = commands[start:start + chunk]
        lines = server.run(*batch)
        hits = sum(1 for line in lines if pattern.search(line))
        if hits != len(batch):
            other = [line for line in lines if not pattern.search(line)]
            raise CommandFailed(
                f"{len(batch) - hits} of {len(batch)} commands did not report {success!r}:\n"
                + "\n".join(other[:30]) + f"\nfirst command: {batch[0]}")
        output += lines
    return output


def state_string(name: str, properties: dict[str, str] | None) -> str:
    if not properties:
        return name
    return name + "[" + ",".join(f"{k}={v}" for k, v in sorted(properties.items())) + "]"


def inert_states() -> list[str]:
    """Block states with no block entity and no fluid, in global state-id order.

    A block's default state is given as the bare block name, which is how 26.3 stores it.
    """
    blocks = json.loads((REPORTS / "blocks.json").read_text())
    states: list[tuple[int, str]] = []
    for name, block in blocks.items():
        if not (name.endswith(("_stairs", "_wall", "_slab", "_fence")) or name == "minecraft:note_block"):
            continue
        for state in block["states"]:
            properties = state.get("properties", {})
            if properties.get("waterlogged") == "true":
                continue
            text = name if state.get("default") else state_string(name, properties)
            states.append((state["id"], text))
    return [text for _, text in sorted(states)]


class Probe:
    """Collects commands and the values they are expected to leave on disk."""

    def __init__(self, server: Server, origin_chunk: tuple[int, int], top_section: int):
        self.server = server
        self.cx, self.cz = origin_chunk
        self.top_section = top_section
        self.expected: dict = {
            "dimension": "minecraft:overworld",
            "blockSections": [], "biomeSections": [], "blocks": {}, "entities": {},
        }
        self.rng = random.Random(26_3)

    # -- palettes ---------------------------------------------------------

    def block_section(self, chunk: tuple[int, int], section_y: int, states: list[str]) -> None:
        """Fill one section so that it holds exactly `states`, in a seeded random layout."""
        x0, y0, z0 = chunk[0] * 16, section_y * 16, chunk[1] * 16
        if len(states) == 1:
            expect(self.server, [f"fill {x0} {y0} {z0} {x0 + 15} {y0 + 15} {z0 + 15} {states[0]} strict"],
                   "Successfully filled 4096")
            cells = [0] * 4096
        else:
            order = list(range(4096))
            self.rng.shuffle(order)
            cells = [0] * 4096
            for n, index in enumerate(order):
                cells[index] = n % len(states)
            commands = []
            for index, state in enumerate(cells):
                x, z, y = index & 15, index >> 4 & 15, index >> 8
                commands.append(f"setblock {x0 + x} {y0 + y} {z0 + z} {states[state]} strict")
            expect(self.server, commands, "Changed the block at")
        # cells[i] is the state at local index i = y * 256 + z * 16 + x.
        self.expected["blockSections"].append({
            "chunk": list(chunk), "sectionY": section_y, "states": states, "cells": cells})

    def biome_section(self, chunk: tuple[int, int], section_y: int, biomes: list[str]) -> None:
        x0, y0, z0 = chunk[0] * 16, section_y * 16, chunk[1] * 16
        order = list(range(64))
        self.rng.shuffle(order)
        cells = [0] * 64
        for n, index in enumerate(order):
            cells[index] = n % len(biomes)
        commands = []
        for index, biome in enumerate(cells):
            x, z, y = (index & 3) * 4, (index >> 2 & 3) * 4, (index >> 4) * 4
            a = f"{x0 + x} {y0 + y} {z0 + z}"
            b = f"{x0 + x + 3} {y0 + y + 3} {z0 + z + 3}"
            commands.append(f"fillbiome {a} {b} minecraft:{biomes[biome]}")
        # A cell that already holds the biome reports "No biome entries were changed".
        expect(self.server, commands, "biome entry/entries set|No biome entries were changed")
        # cells[i] is the biome at cell index i = y * 16 + z * 4 + x, in 4-block cells.
        self.expected["biomeSections"].append({
            "chunk": list(chunk), "sectionY": section_y,
            "biomes": [f"minecraft:{b}" for b in biomes], "cells": cells})

    def palettes(self, sizes_by_chunk: dict[tuple[int, int], list[int]]) -> None:
        states = inert_states()
        for chunk, sizes in sizes_by_chunk.items():
            for n, size in enumerate(sizes):
                section_y = self.top_section - n
                picked = ["minecraft:stone"] if size == 1 else states[:size]
                self.block_section(chunk, section_y, picked)

    # -- single blocks ----------------------------------------------------

    def block(self, pos: tuple[int, int, int], block: str, mode: str = "", note: str = "") -> None:
        x, y, z = pos
        expect(self.server, [f"setblock {x} {y} {z} {block} {mode}".strip()], "Changed the block at")
        self.expected["blocks"][f"{x},{y},{z}"] = {"placed": block, "note": note}

    def item(self, pos: tuple[int, int, int], slot: int, item: str, count: int = 1) -> None:
        x, y, z = pos
        expect(self.server, [f"item replace block {x} {y} {z} container.{slot} with {item} {count}"],
               r"Replaced \d+ slot")

    def summon(self, entity: str, pos: tuple[float, float, float], uuid: int, nbt: str = "",
               note: str = "") -> None:
        x, y, z = pos
        tag = f"UUID:[I;0,0,0,{uuid}]" + ("," + nbt if nbt else "")
        expect(self.server, [f"summon minecraft:{entity} {x} {y} {z} {{{tag}}}"], "Summoned new")
        self.expect_entity(uuid, entity, note)

    def expect_entity(self, uuid: int, entity: str, note: str, vehicle: int | None = None) -> None:
        self.expected["entities"][uuid_string(uuid)] = {
            "id": f"minecraft:{entity}", "note": note,
            "vehicle": None if vehicle is None else uuid_string(vehicle)}


def wait_loaded(server: Server, chunks: list[tuple[int, int]], timeout: float = 300) -> None:
    deadline = time.monotonic() + timeout
    commands = [f"execute if loaded {cx * 16} 0 {cz * 16}" for cx, cz in chunks]
    while time.monotonic() < deadline:
        lines = server.run(*commands)
        if sum("Test passed" in line for line in lines) == len(chunks):
            return
        time.sleep(0.5)
    raise TimeoutError("forced chunks did not load")


def uid(n: int) -> str:
    return f"[I;0,0,0,{n}]"


def uuid_string(n: int) -> str:
    return f"00000000-0000-0000-0000-{n:012x}"


def build_block_entities(p: Probe, x0: int, y: int, z0: int) -> None:
    """Block entities on a grid starting at (x0, y, z0), two blocks apart."""
    slots = [(x0 + 2 * (n % 8), y, z0 + 1 + 2 * (n // 8)) for n in range(56)]
    it = iter(slots)

    # Containers, filled both through block NBT and through /item.
    pos = next(it)
    p.block(pos, 'minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",count:64},'
                 '{Slot:13b,id:"minecraft:oak_log",count:32},{Slot:26b,id:"minecraft:netherite_ingot",count:1}]}',
            note="chest filled through block NBT")
    pos = next(it)
    p.block(pos, "minecraft:chest[facing=north]", note="chest filled through /item, items with components")
    p.item(pos, 0, 'minecraft:diamond_sword[minecraft:enchantments={"minecraft:sharpness":5,"minecraft:unbreaking":3},minecraft:damage=17]')
    p.item(pos, 1, 'minecraft:stick[minecraft:custom_name="Probe stick",minecraft:lore=["first line","second line"]]', 3)
    p.item(pos, 2, 'minecraft:potion[minecraft:potion_contents={potion:"minecraft:strong_healing"}]')
    p.item(pos, 3, 'minecraft:written_book[minecraft:written_book_content={title:"Probe",author:"mycelium",pages:["page one","page two"]}]')
    p.item(pos, 4, 'minecraft:red_shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:emerald",count:12}},{slot:5,item:{id:"minecraft:bread",count:3}}]]')
    p.item(pos, 5, 'minecraft:bundle[minecraft:bundle_contents=[{id:"minecraft:wheat_seeds",count:10},{id:"minecraft:string",count:2}]]')
    p.item(pos, 6, 'minecraft:enchanted_book[minecraft:stored_enchantments={"minecraft:mending":1}]')
    p.item(pos, 7, 'minecraft:stone[minecraft:custom_data={mycelium:{probe:1b,list:[1,2,3]}}]', 16)
    p.item(pos, 26, "minecraft:cobblestone", 64)
    # For a chest facing north the right half sits east of the left half (tools/mc/probe_units.py).
    left = next(it)
    right = (left[0] + 1, left[1], left[2])
    p.block(left, 'minecraft:chest[facing=north,type=left]{Items:[{Slot:0b,id:"minecraft:gold_ingot",count:5}]}',
            mode="strict", note="double chest, left half")
    p.block(right, 'minecraft:chest[facing=north,type=right]{Items:[{Slot:0b,id:"minecraft:iron_ingot",count:7}]}',
            mode="strict", note="double chest, right half")
    pos = next(it)
    p.block(pos, 'minecraft:chest{LootTable:"minecraft:chests/simple_dungeon",LootTableSeed:1234L}',
            note="chest with an unrolled loot table")
    for block in ("trapped_chest", "barrel", "red_shulker_box", "dropper", "dispenser", "copper_chest"):
        pos = next(it)
        p.block(pos, f"minecraft:{block}", note="container filled through /item")
        p.item(pos, 0, "minecraft:redstone", 16)
        p.item(pos, 4, "minecraft:arrow", 8)
    pos = next(it)
    p.block(pos, "minecraft:crafter", note="crafter")
    p.item(pos, 0, "minecraft:oak_planks", 4)
    hopper = next(it)
    p.block((hopper[0], hopper[1], hopper[2]), "minecraft:barrel", note="barrel under a hopper")
    p.block((hopper[0], hopper[1] + 1, hopper[2]), "minecraft:hopper[facing=down]", note="hopper feeding the barrel")
    p.item((hopper[0], hopper[1] + 1, hopper[2]), 0, "minecraft:wheat", 32)

    # Machines; these run during the unfrozen phase so progress fields are non-zero.
    for block, raw in (("furnace", "raw_iron"), ("blast_furnace", "raw_gold"), ("smoker", "beef")):
        pos = next(it)
        p.block(pos, f"minecraft:{block}", note="machine left running")
        p.item(pos, 0, f"minecraft:{raw}", 16)
        p.item(pos, 1, "minecraft:coal", 8)
    pos = next(it)
    p.block(pos, "minecraft:brewing_stand", note="brewing stand left running")
    for slot in range(3):
        p.item(pos, slot, 'minecraft:potion[minecraft:potion_contents={potion:"minecraft:water"}]')
    p.item(pos, 3, "minecraft:nether_wart", 4)
    p.item(pos, 4, "minecraft:blaze_powder", 2)
    pos = next(it)
    p.block(pos, 'minecraft:campfire[lit=true]{Items:[{Slot:0b,id:"minecraft:beef",count:1},{Slot:2b,id:"minecraft:cod",count:1}],'
                 'CookingTimes:[I;0,0,0,0],CookingTotalTimes:[I;6000,6000,6000,6000]}',
            note="campfire cooking")

    # Everything else that has a block entity type in the 26.3 registry.
    others = [
        ('oak_sign[rotation=4]{front_text:{messages:["Mycelium","probe","world",""],color:"red",has_glowing_text:1b},'
         'back_text:{messages:["back","","",""]}}', "sign with text"),
        ('white_banner{patterns:[{pattern:"minecraft:stripe_top",color:"red"},{pattern:"minecraft:creeper",color:"black"}]}', "banner"),
        ('spawner{SpawnData:{entity:{id:"minecraft:zombie"}},Delay:200s}', "spawner"),
        ('lectern[has_book=true]{Book:{id:"minecraft:written_book",count:1,components:{"minecraft:written_book_content":'
         '{title:"Lectern",author:"mycelium",pages:["a","b","c"]}}},Page:1}', "lectern with a book"),
        ('jukebox[has_record=true]{RecordItem:{id:"minecraft:music_disc_13",count:1}}', "jukebox"),
        ('decorated_pot{item:{id:"minecraft:emerald",count:9}}', "decorated pot"),
        ("chiseled_bookshelf", "chiseled bookshelf"),
        ('beehive{bees:[{entity_data:{id:"minecraft:bee"},ticks_in_hive:0,min_ticks_in_hive:2400}]}', "beehive with a bee"),
        ("skeleton_skull", "skull"), ("bell[attachment=floor]", "bell"), ("beacon", "beacon"),
        ("conduit", "conduit"), ("end_gateway", "end gateway"),
        ('command_block{Command:"say probe"}', "command block"), ("structure_block", "structure block"),
        ("jigsaw", "jigsaw"), ("comparator", "comparator"), ("daylight_detector", "daylight detector"),
        ("enchanting_table", "enchanting table"), ("ender_chest", "ender chest"),
        ("sculk_sensor", "sculk sensor"), ("calibrated_sculk_sensor", "calibrated sculk sensor"),
        ("sculk_catalyst", "sculk catalyst"), ("sculk_shrieker", "sculk shrieker"),
        ('suspicious_sand{LootTable:"minecraft:archaeology/desert_pyramid"}', "brushable block"),
        ("trial_spawner", "trial spawner"), ("vault", "vault"), ("creaking_heart", "creaking heart"),
        ("oak_shelf", "shelf"), ("copper_golem_statue", "copper golem statue"),
        ("potent_sulfur", "potent sulfur"), ("end_portal", "end portal"),
    ]
    for block, note in others:
        pos = next(it)
        p.block(pos, "minecraft:" + block, note=note)
        if block == "chiseled_bookshelf":
            p.item(pos, 0, "minecraft:book")
            p.item(pos, 3, 'minecraft:enchanted_book[minecraft:stored_enchantments={"minecraft:efficiency":2}]')
        if block == "oak_shelf":
            p.item(pos, 1, "minecraft:clock")


def build_units_and_ticks(p: Probe, x0: int, y: int, z0: int) -> None:
    """Multi-block shapes and blocks that leave scheduled ticks. Runs while ticking is frozen."""
    north = "facing=north"
    p.block((x0 + 1, y, z0 + 1), f"minecraft:oak_door[{north},half=lower,hinge=left,open=false,powered=false]", "strict", "door, lower half")
    p.block((x0 + 1, y + 1, z0 + 1), f"minecraft:oak_door[{north},half=upper,hinge=left,open=false,powered=false]", "strict", "door, upper half")
    p.block((x0 + 3, y, z0 + 2), f"minecraft:red_bed[{north},part=foot]", "strict", "bed, foot")
    p.block((x0 + 3, y, z0 + 1), f"minecraft:red_bed[{north},part=head]", "strict", "bed, head")
    for n, plant in enumerate(("tall_grass", "sunflower", "large_fern", "peony", "pitcher_plant", "small_dripleaf")):
        x = x0 + 5 + n
        extra = ",facing=north,waterlogged=false" if plant == "small_dripleaf" else ""
        p.block((x, y - 1, z0 + 1), "minecraft:grass_block", "strict", "soil")
        p.block((x, y, z0 + 1), f"minecraft:{plant}[half=lower{extra}]", "strict", f"{plant}, lower half")
        p.block((x, y + 1, z0 + 1), f"minecraft:{plant}[half=upper{extra}]", "strict", f"{plant}, upper half")
    # Shapes that cross a chunk border: a double chest over the east border, a bed over the south border.
    p.block((x0 + 15, y, z0 + 4), 'minecraft:chest[facing=north,type=left]{Items:[{Slot:1b,id:"minecraft:apple",count:2}]}', "strict", "double chest across a chunk border, west half")
    p.block((x0 + 16, y, z0 + 4), 'minecraft:chest[facing=north,type=right]{Items:[{Slot:2b,id:"minecraft:carrot",count:3}]}', "strict", "double chest across a chunk border, east half")
    p.block((x0 + 4, y, z0 + 16), f"minecraft:blue_bed[{north},part=foot]", "strict", "bed across a chunk border, foot")
    p.block((x0 + 4, y, z0 + 15), f"minecraft:blue_bed[{north},part=head]", "strict", "bed across a chunk border, head")

    # Scheduled ticks: placed with normal updates while frozen, so the ticks stay pending.
    p.block((x0 + 2, y + 8, z0 + 6), "minecraft:sand", note="sand over air; pending block tick")
    p.block((x0 + 4, y, z0 + 6), "minecraft:water", note="water source; pending fluid tick")
    p.block((x0 + 8, y, z0 + 6), "minecraft:lava", note="lava source; pending fluid tick")
    p.block((x0 + 11, y, z0 + 6), "minecraft:repeater[facing=west,delay=4]", note="repeater about to turn on; pending block tick")
    p.block((x0 + 12, y, z0 + 6), "minecraft:redstone_block", note="power for the repeater")
    p.block((x0 + 14, y, z0 + 8), "minecraft:oak_slab[type=bottom,waterlogged=true]", note="waterlogged slab")
    # A range of block kinds the noise rules will care about later.
    for n, block in enumerate((
            "wheat[age=3]", "carrots[age=7]", "sugar_cane[age=9]", "oak_sapling[stage=1]",
            "snow[layers=3]", "fire[age=7]", "oak_leaves[distance=3,persistent=false]",
            "redstone_wire[power=9]", "cave_vines[age=12,berries=true]", "farmland[moisture=5]")):
        p.block((x0 + 1 + n, y, z0 + 10), "minecraft:" + block, "strict", "state sample")


def build_piston(p: Probe, x: int, y: int, z: int) -> None:
    """A piston extended by a redstone block; runs during the unfrozen phase."""
    p.block((x, y, z), "minecraft:sticky_piston[facing=up]", note="piston, extended by the block next to it")
    p.block((x + 1, y, z), "minecraft:redstone_block", note="power for the piston")
    p.expected["blocks"][f"{x},{y + 1},{z}"] = {"placed": "(piston head, created by the game)", "note": "piston head"}


def build_entities(p: Probe, x0: int, y: int, z0: int) -> None:
    """Entities with fixed UUIDs. Runs while ticking is frozen so nothing moves before the save."""
    calm = "NoAI:1b,PersistenceRequired:1b"
    p.summon("pig", (x0 + 2.5, y, z0 + 2.5), 1,
             f'{calm},Passengers:[{{id:"minecraft:zombie",UUID:{uid(2)},{calm},'
             f'Passengers:[{{id:"minecraft:chicken",UUID:{uid(3)},{calm}}}]}}]',
             note="pig carrying a zombie carrying a chicken")
    p.expect_entity(2, "zombie", "rides the pig", vehicle=1)
    p.expect_entity(3, "chicken", "rides the zombie", vehicle=2)
    p.summon("oak_boat", (x0 + 5.5, y, z0 + 2.5), 4,
             f'Passengers:[{{id:"minecraft:villager",UUID:{uid(5)},{calm}}},{{id:"minecraft:cat",UUID:{uid(6)},{calm}}}]',
             note="boat with two passengers")
    p.expect_entity(5, "villager", "first seat of the boat", vehicle=4)
    p.expect_entity(6, "cat", "second seat of the boat", vehicle=4)
    p.summon("chest_minecart", (x0 + 8.5, y, z0 + 2.5), 7,
             'Items:[{Slot:0b,id:"minecraft:rail",count:16},{Slot:9b,id:"minecraft:torch",count:5}]',
             note="container entity")
    p.summon("villager", (x0 + 11.5, y, z0 + 2.5), 8, calm, note="leash holder")
    p.summon("cow", (x0 + 13.5, y, z0 + 2.5), 9, f"{calm},leash:{{UUID:{uid(8)}}}", note="leashed to entity 8")
    p.block((x0 + 2, y, z0 + 5), "minecraft:oak_fence", "strict", "fence post for a leash knot")
    p.summon("sheep", (x0 + 3.5, y, z0 + 6.5), 10, f"{calm},leash:[I;{x0 + 2},{y},{z0 + 5}]", note="leashed to a fence post")
    p.block((x0 + 6, y, z0 + 5), "minecraft:stone", "strict", "backing block for hanging entities")
    p.block((x0 + 6, y + 1, z0 + 5), "minecraft:stone", "strict", "backing block for hanging entities")
    p.summon("item_frame", (x0 + 6.5, y + 0.5, z0 + 6.03125), 11,
             f'Facing:3b,block_pos:[I;{x0 + 6},{y},{z0 + 6}],Item:{{id:"minecraft:diamond_pickaxe",count:1}},ItemRotation:2b',
             note="item frame holding an item")
    p.summon("painting", (x0 + 6.5, y + 1.5, z0 + 6.03125), 12,
             f'facing:0b,block_pos:[I;{x0 + 6},{y + 1},{z0 + 6}],variant:"minecraft:kebab"', note="painting")
    p.summon("armor_stand", (x0 + 9.5, y, z0 + 6.5), 13,
             'equipment:{head:{id:"minecraft:iron_helmet",count:1},mainhand:{id:"minecraft:golden_sword",count:1}},ShowArms:1b',
             note="armour stand with equipment")
    p.summon("item", (x0 + 11.5, y, z0 + 6.5), 14, 'Item:{id:"minecraft:diamond",count:3},Age:0s', note="dropped item")
    p.summon("experience_orb", (x0 + 13.5, y, z0 + 6.5), 15, "Value:7s", note="experience orb")
    p.summon("wolf", (x0 + 2.5, y, z0 + 10.5), 16, f"{calm},Owner:{uid(999)},Sitting:1b", note="tamed wolf; owner is not in the world")
    p.summon("zombie", (x0 + 5.5, y, z0 + 10.5), 17,
             f'{calm},equipment:{{mainhand:{{id:"minecraft:iron_shovel",count:1}},chest:{{id:"minecraft:leather_chestplate",count:1}}}}',
             note="mob carrying equipment")
    p.summon("block_display", (x0 + 8.5, y, z0 + 10.5), 18,
             'block_state:{id:"minecraft:oak_stairs",properties:{facing:"east"}}', note="block display")
    p.summon("marker", (x0 + 10.5, y, z0 + 10.5), 19, 'data:{mycelium:"marker"}', note="marker with custom data")
    p.summon("donkey", (x0 + 12.5, y, z0 + 10.5), 20,
             f'{calm},Tame:1b,ChestedHorse:1b,Items:[{{Slot:0b,id:"minecraft:hay_block",count:4}}]', note="donkey with a chest")
    p.summon("end_crystal", (x0 + 14.5, y, z0 + 13.5), 21, "ShowBottom:0b", note="end crystal")
    p.summon("hopper_minecart", (x0 + 2.5, y, z0 + 13.5), 22, 'Items:[{Slot:0b,id:"minecraft:cobblestone",count:1}]', note="hopper minecart")


def build_oversized(server: Server, chunk: tuple[int, int], y: int, incompressible: bool) -> None:
    """Make one chunk's NBT too large for 255 region sectors (about 1 MiB)."""
    x0, z0 = chunk[0] * 16, chunk[1] * 16
    if not incompressible:
        items = ",".join(f'{{Slot:{slot}b,id:"minecraft:stone",count:64}}' for slot in range(27))
        # 1,024 full barrels are about 1.3 MB of NBT, just past the limit.
        expect(server, [f"fill {x0} {y} {z0} {x0 + 15} {y + 3} {z0 + 15} minecraft:barrel{{Items:[{items}]}}"],
               "Successfully filled")
        return
    rng = random.Random(1)
    alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/"
    commands = []
    for n in range(48):
        noise = [f'{{Slot:{slot}b,id:"minecraft:stone",count:1,components:{{"minecraft:custom_data":{{r:"'
                 + "".join(rng.choices(alphabet, k=2000)) + '"}}}' for slot in range(27)]
        x, z = x0 + n % 16, z0 + n // 16
        commands.append(f"setblock {x} {y} {z} minecraft:barrel{{Items:[{','.join(noise)}]}}")
    expect(server, commands, "Changed the block at", chunk=4)


def build(variant: str) -> Path:
    run_dir = MC_HOME / "runs" / f"probe-{variant}"
    if run_dir.exists():
        shutil.rmtree(run_dir)
    natural = variant == "zlib"
    properties = {"region-file-compression": {"zlib": "deflate", "oversized-zlib": "deflate"}.get(variant, variant)}
    if not natural:
        properties["level-type"] = "minecraft:flat"
        properties["generator-settings"] = json.dumps({
            "biome": "minecraft:plains",
            "layers": [{"block": "minecraft:bedrock", "height": 1},
                       {"block": "minecraft:dirt", "height": 2},
                       {"block": "minecraft:grass_block", "height": 1}]})
        properties["generate-structures"] = "false"

    # Natural world: work far from spawn so the fixture region holds only probe chunks, and
    # high above the terrain. Flat worlds: work just above the ground at y=-60.
    if natural:
        base = (46, 46)                       # chunks 46..49 x 46..48, all inside region r.1.1
        chunks = [(base[0] + dx, base[1] + dz) for dz in range(3) for dx in range(4)]
        top_section, floor = 19, 239
    else:
        base = (2, 2)
        chunks = [(base[0] + dx, base[1] + dz) for dz in range(3) for dx in range(4)]
        top_section, floor = 4, 0

    with Server(run_dir, properties) as server:
        xs = [c[0] for c in chunks]
        zs = [c[1] for c in chunks]
        expect(server, [f"forceload add {min(xs) * 16} {min(zs) * 16} {max(xs) * 16 + 15} {max(zs) * 16 + 15}"], "Marked")
        wait_loaded(server, chunks)
        p = Probe(server, base, top_section)

        if variant == "oversized-zlib":
            build_oversized(server, (base[0], base[1]), floor + 1, incompressible=True)
            server.save()
            return _finish(server, p, run_dir)

        row1 = base[1] + 1                    # second row of chunks: block entities, units, entities
        x0, z0 = base[0] * 16, row1 * 16
        expect(server, [f"fill {x0} {floor} {z0} {x0 + 63} {floor} {z0 + 31} minecraft:smooth_stone"], "Successfully filled")

        # Unfrozen phase: machines run, the hopper moves items, the piston extends.
        build_block_entities(p, x0, floor + 1, z0)
        build_piston(p, x0 + 16 + 2, floor + 1, z0 + 13)
        server.wait_ticks(120)

        expect(server, ["tick freeze"], "frozen")
        build_units_and_ticks(p, x0 + 16, floor + 1, z0)
        build_entities(p, x0 + 32, floor + 1, z0)
        if natural:
            p.palettes({
                base: [1, 2, 16, 17, 33],
                (base[0] + 1, base[1]): [65, 129, 257, 513, 1025],
                (base[0] + 2, base[1]): [2049, 4096],
            })
            biome_chunk = (base[0] + 3, base[1])
            for n, count in enumerate((1, 2, 3, 5, 9, 17, 33, 64)):
                p.biome_section(biome_chunk, top_section - n, BIOMES[:count])
        else:
            p.palettes({base: [1, 17, 4096]})
            p.biome_section((base[0] + 3, base[1]), top_section, BIOMES[:5])
            if variant == "none":
                build_oversized(server, (base[0] + 3, base[1] + 2), floor + 1, incompressible=False)
        server.save()
        return _finish(server, p, run_dir)


def _finish(server: Server, p: Probe, run_dir: Path) -> Path:
    server.stop()
    (run_dir / "expected.json").write_text(json.dumps(p.expected, separators=(",", ":")) + "\n")
    (run_dir / "server.log").write_text("\n".join(server.log) + "\n")
    problems = [line for line in server.errors() if "OFFLINE" not in line and "authenticate" not in line
                and "hackers" not in line and "online-mode" not in line and "codename" not in line
                # /summon reads block_pos before it moves the entity into place, so every summoned
                # item frame and painting logs this once. Loading the saved world does not.
                and "Block-attached entity at invalid position" not in line]
    print(f"{run_dir.name}: world at {server.world_dir}; {len(problems)} warnings or errors in the log")
    for line in problems[:40]:
        print("   ", line)
    return run_dir


if __name__ == "__main__":
    for name in sys.argv[1:] or ["zlib", "lz4", "none", "oversized-zlib"]:
        if name not in ("zlib", "lz4", "none", "oversized-zlib"):
            sys.exit(f"unknown variant {name!r}")
        build(name)
