"""Generate the built-in block table and vanilla rules file from the game's block report.

Reads blocks.json from the 26.3 server's data generator (see README) and writes
two resources for the merge library:

  blocks-26.3.json   every block with its properties and its default state, in
                     the block report's own format with the other states dropped
  rules-26.3.json    which blocks join which noise rule and which unit kind

Blocks are picked by the properties they have, plus short hand-written lists
where a property alone does not say enough. Every property that could matter is
either assigned to a rule or listed under UNASSIGNED with the reason, and so is
every block the game ticks at random, so a new game version cannot add one
unnoticed. Which blocks those are, and the stages of copper, come from the game's
own classes: run probe_game_facts.py first.

    python3 tools/mc/probe_game_facts.py
    python3 tools/mc/gen_rules.py
"""

from __future__ import annotations

import json

from mcserver import MC_HOME, REPO_ROOT

REPORT = MC_HOME / "server" / "26.3" / "datagen" / "generated" / "reports" / "blocks.json"
FACTS = MC_HOME / "server" / "26.3" / "probe" / "game_facts.json"
OUT = REPO_ROOT / "merge" / "src" / "main" / "resources" / "mycelium" / "rules"

AIR = ["minecraft:air", "minecraft:cave_air", "minecraft:void_air"]


def mc(*names):
    return ["minecraft:" + name for name in names]


def main():
    blocks = json.loads(REPORT.read_text())
    facts = json.loads(FACTS.read_text())

    def having(prop, values=None):
        return sorted(name for name, block in blocks.items()
                      if prop in block.get("properties", {})
                      and (values is None or block["properties"][prop] == values))

    def without(names, *excluded):
        return [name for name in names if name not in mc(*excluded)]

    doors = [name for name in having("half", ["upper", "lower"]) if "hinge" in blocks[name]["properties"]]
    # A pitcher crop has no upper half until it is tall enough, so a lone lower half is normal.
    tall_plants = [name for name in having("half", ["upper", "lower"])
                   if name not in doors and name != "minecraft:pitcher_crop"]
    chests = having("type", ["single", "left", "right"])
    copper_chests = [name for name in chests if "copper" in name]
    leaves = having("persistent")

    saplings = without(having("stage"), "bamboo")
    logs = sorted(name for name in blocks if name.endswith("_log") and "stripped" not in name) + mc("mangrove_roots")
    # Copper weathers one stage at a time. The game's own table gives each block's next stage.
    after = facts["copper_next"]
    copper = []
    for first in sorted(set(after) - set(after.values())):
        chain = [first]
        while chain[-1] in after:
            chain.append(after[chain[-1]])
        copper.append(chain)
    noise = [
        # Growth: the stage only goes up, so the higher one is the later one.
        {"name": "growth", "type": "property", "result": "higher", "properties": ["age"],
         "blocks": mc("beetroots", "carrots", "potatoes", "wheat", "nether_wart", "sweet_berry_bush", "cocoa",
                      "melon_stem", "pumpkin_stem", "torchflower_crop", "pitcher_crop", "chorus_flower", "kelp",
                      "cave_vines", "twisting_vines", "weeping_vines", "mangrove_propagule")},
        {"name": "growth", "type": "property", "result": "higher", "properties": ["stage"], "blocks": saplings},
        {"name": "growth", "type": "property", "result": "higher", "properties": ["hatch"], "blocks": having("hatch")},
        {"name": "growth", "type": "property", "result": "higher", "properties": ["hydration"],
         "blocks": having("hydration")},
        # Counters that start over after their maximum, and states that go both ways.
        {"name": "growth", "type": "property", "result": "newer", "properties": ["age"],
         "blocks": mc("cactus", "sugar_cane", "frosted_ice")},
        {"name": "growth", "type": "property", "result": "newer", "properties": ["age", "leaves", "stage"],
         "blocks": mc("bamboo")},
        {"name": "growth", "type": "property", "result": "newer", "properties": ["berries"], "blocks": having("berries")},
        {"name": "growth", "type": "property", "result": "newer", "properties": ["moisture"],
         "blocks": having("moisture")},
        {"name": "growth", "type": "property", "result": "newer", "properties": ["honey_level"],
         "blocks": having("honey_level")},
        # Level 0 is a source block, which only a player or a bucket makes: it wins over a flow that changed.
        {"name": "fluid", "type": "property", "result": "newer", "properties": ["level"],
         "blocks": mc("water", "lava"), "player_values": ["0"]},
        {"name": "machine", "type": "property", "result": "newer", "properties": ["lit"],
         "blocks": mc("furnace", "blast_furnace", "smoker", "campfire", "soul_campfire", "redstone_ore",
                      "deepslate_redstone_ore")},
        {"name": "machine", "type": "property", "result": "newer",
         "properties": ["has_bottle_0", "has_bottle_1", "has_bottle_2"], "blocks": mc("brewing_stand")},
        # Redstone: the game works these out again from the circuit. A lever is a player's switch, not a signal.
        {"name": "redstone", "type": "property", "result": "newer", "properties": ["power"], "blocks": having("power")},
        {"name": "redstone", "type": "property", "result": "newer", "properties": ["powered"],
         "blocks": without(having("powered"), "lever")},
        {"name": "redstone", "type": "property", "result": "newer", "properties": ["triggered"],
         "blocks": having("triggered")},
        {"name": "redstone", "type": "property", "result": "newer", "properties": ["lit"],
         "blocks": mc("redstone_torch", "redstone_wall_torch", "redstone_lamp")},
        {"name": "redstone", "type": "property", "result": "newer", "properties": ["locked"], "blocks": mc("repeater")},
        {"name": "redstone", "type": "property", "result": "newer", "properties": ["enabled"], "blocks": mc("hopper")},
        {"name": "redstone", "type": "property", "result": "newer", "properties": ["north", "east", "south", "west"],
         "blocks": mc("redstone_wire")},
        {"name": "redstone", "type": "property", "result": "newer",
         "properties": ["sculk_sensor_phase", "shrieking", "bloom", "crafting"],
         "blocks": mc("sculk_sensor", "calibrated_sculk_sensor", "sculk_shrieker", "sculk_catalyst", "crafter")},
        {"name": "leaves", "type": "property", "result": "newer", "properties": ["distance"], "blocks": leaves},
        # A leaf a player placed is persistent. One that is not belongs to a tree: it grew with it and decays
        # without it, and a decayed leaf is gone whichever side is newer.
        {"name": "leaves", "type": "property", "result": "newer", "properties": ["persistent"], "blocks": leaves,
         "player_values": ["true"]},
        {"name": "leaves", "type": "transient", "result": "empty", "blocks": leaves, "empty": AIR},
        # A stalactite or stalagmite that grows a block longer changes the shape of the blocks behind its tip.
        {"name": "growth", "type": "property", "result": "newer", "properties": ["thickness"], "blocks": having("thickness")},
        # A vine spreads to more faces of its block, and a chorus plant connects to what grew next to it.
        {"name": "growth", "type": "property", "result": "newer",
         "properties": ["north", "east", "south", "west", "up", "down"], "blocks": mc("vine", "chorus_plant")},
        # Rain, snow and dripping stone fill a cauldron. A player with a bottle or a bucket takes a level out.
        {"name": "cauldron", "type": "property", "result": "higher", "properties": ["level"],
         "blocks": mc("water_cauldron", "powder_snow_cauldron")},
        {"name": "machine", "type": "property", "result": "newer", "properties": ["level"], "blocks": mc("composter")},
        {"name": "machine", "type": "property", "result": "newer", "properties": ["tilt"], "blocks": having("tilt")},
        {"name": "machine", "type": "property", "result": "newer", "properties": ["vault_state", "trial_spawner_state"],
         "blocks": mc("vault", "trial_spawner")},
        {"name": "machine", "type": "property", "result": "newer", "properties": ["distance", "bottom"],
         "blocks": mc("scaffolding")},
        {"name": "day_night", "type": "property", "result": "newer", "properties": ["creaking_heart_state"],
         "blocks": having("creaking_heart_state")},
        {"name": "snow", "type": "property", "result": "newer", "properties": ["snowy"], "blocks": having("snowy")},
        # Fire takes the place of what it burns, so the base can hold any block.
        {"name": "fire", "type": "transient", "blocks": mc("fire", "soul_fire"), "empty": AIR, "consumes": True},
        {"name": "snow", "type": "transient", "blocks": mc("snow"), "empty": AIR},
        # A stem attaches to the fruit it grew and lets go again when the fruit is taken, by a player or a piston.
        {"name": "growth", "type": "transient", "blocks": mc("attached_melon_stem"), "empty": mc("melon_stem")},
        {"name": "growth", "type": "transient", "blocks": mc("attached_pumpkin_stem"), "empty": mc("pumpkin_stem")},
        # Eyeblossoms open at night and close in the morning.
        {"name": "day_night", "type": "transient", "blocks": mc("open_eyeblossom"), "empty": mc("closed_eyeblossom")},
        {"name": "day_night", "type": "transient", "blocks": mc("potted_open_eyeblossom"),
         "empty": mc("potted_closed_eyeblossom")},
        # Grass and mycelium spread onto dirt and die under a block. Nylium dies the same way.
        {"name": "spread", "type": "transient", "blocks": mc("grass_block", "mycelium"), "empty": mc("dirt")},
        {"name": "spread", "type": "transient", "blocks": mc("crimson_nylium", "warped_nylium"), "empty": mc("netherrack")},
        # Blocks the game turns into each other in one direction only: the later stage wins.
        {"name": "oxidation", "type": "stages", "chains": copper},
        {"name": "growth", "type": "stages", "chains": [mc("torchflower_crop", "torchflower")]},
        {"name": "cauldron", "type": "stages",
         "chains": [[mc("cauldron")[0], mc("water_cauldron", "powder_snow_cauldron", "lava_cauldron")]]},
        # With "from", also where both sides grew something into the same empty block: a bud of another size,
        # a longer vine, or two trees from one sapling, where a log of one tree beats a leaf of the other.
        {"name": "growth", "type": "stages", "from": mc("air", "water"),
         "chains": [mc("small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster")]},
        {"name": "growth", "type": "stages", "from": mc("water"), "chains": [mc("kelp", "kelp_plant")]},
        {"name": "growth", "type": "stages", "from": AIR,
         "chains": [[leaves, logs], mc("cave_vines", "cave_vines_plant"), mc("twisting_vines", "twisting_vines_plant"),
                    mc("weeping_vines", "weeping_vines_plant"), mc("bamboo_sapling", "bamboo"),
                    mc("chorus_flower", "chorus_plant"),
                    # A cactus grew a block taller on one side and a flower on its top on the other. The flower
                    # cannot carry the cactus above it, so the cactus stands.
                    mc("cactus_flower", "cactus")]},
        # A chorus plant only stands as a whole: two shapes of it, merged block by block, make one that the
        # game breaks. It is taken from the newer side wherever that side had the chunk in play.
        {"name": "growth", "type": "newer_only", "blocks": mc("chorus_plant", "chorus_flower")},
    ]
    units = [
        {"name": "door", "type": "vertical_pair", "shared": ["facing", "hinge", "open", "powered"], "blocks": doors},
        {"name": "tall_plant", "type": "vertical_pair", "shared": [], "blocks": tall_plants},
        {"name": "bed", "type": "bed", "blocks": having("part", ["head", "foot"])},
        {"name": "double_chest", "type": "double_chest", "blocks": mc("chest")},
        {"name": "double_chest", "type": "double_chest", "blocks": mc("trapped_chest")},
        {"name": "double_chest", "type": "double_chest", "blocks": copper_chests},
        {"name": "piston", "type": "piston", "blocks": mc("piston", "sticky_piston"),
         "head": "minecraft:piston_head", "moving": "minecraft:moving_piston"},
    ]

    # Properties that look like they could be noise or links but are deliberately left out.
    unassigned = {
        "age": {"minecraft:fire": "handled by the fire rule",
                "minecraft:shelf_mushroom": "the game never ticks it: its age is set when it is placed"},
        "lit": "candles, candle cakes and copper bulbs are lit by players",
        "powered": {"minecraft:lever": "a lever is switched by a player"},
        "level": "light is set by players, and a lava cauldron has no level",
        "extended": "pistons are a unit kind",
    }

    # Every block that has one of these properties must be in a rule or excused above.
    for prop in ("age", "stage", "hatch", "berries", "moisture", "honey_level", "power", "powered", "triggered"):
        covered = {name for rule in noise if prop in rule.get("properties", []) for name in rule["blocks"]}
        excused = unassigned.get(prop, {})
        missing = [name for name in having(prop) if name not in covered and name not in excused]
        assert not missing, f"blocks with '{prop}' that no rule covers: {missing}"
    # Every block the game ticks at random changes by itself, so it must be in a rule or excused here.
    left_alone = {
        "minecraft:budding_amethyst": "grows buds on the blocks around it; the buds have a rule",
        "minecraft:ice": "melts into water near a light; nothing else turns it",
        "minecraft:brown_mushroom": "spreads into air nearby and does not change itself",
        "minecraft:red_mushroom": "spreads into air nearby and does not change itself",
        "minecraft:nether_portal": "spawns piglins and does not change itself",
    }
    ruled = set()
    for rule in noise:
        ruled |= set(rule.get("blocks", [])) | set(rule.get("empty", []))
        for chain in rule.get("chains", []):
            for stage in chain:
                ruled |= set([stage] if isinstance(stage, str) else stage)
    ticked = [name for name, block in facts["blocks"].items() if block["random_tick_states"]]
    missing = [name for name in ticked if name not in ruled and name not in left_alone]
    assert not missing, f"blocks the game ticks at random that no rule covers: {missing}"
    assert set(facts["blocks"]) == set(blocks), "game_facts.json and the block report are of different versions"

    def named_by(rule):
        names = rule.get("blocks", []) + rule.get("empty", []) + rule.get("from", [])
        names += [rule[k] for k in ("head", "moving") if k in rule]
        for chain in rule.get("chains", []):
            for stage in chain:
                names += [stage] if isinstance(stage, str) else stage
        return names

    for rule in noise + units:
        named = named_by(rule)
        assert named and all(name in blocks for name in named), rule["name"]
        for prop in rule.get("properties", []) + rule.get("shared", []):
            assert any(prop in blocks[name].get("properties", {}) for name in rule["blocks"]), (rule["name"], prop)
    staged = [name for rule in noise for chain in rule.get("chains", []) for stage in chain
              for name in ([stage] if isinstance(stage, str) else stage)]
    assert len(staged) == len(set(staged)), "a block is in two chains"
    linked = (set(having("half", ["upper", "lower"])) | set(having("part")) | set(chests)) - set(mc("pitcher_crop"))
    assert linked == {name for rule in units for name in rule["blocks"]} - set(mc("piston", "sticky_piston"))

    table = {}
    for name, block in blocks.items():
        default = next(state for state in block["states"] if state.get("default"))
        entry = {}
        if "properties" in block:
            entry["properties"] = block["properties"]
        entry["states"] = [{"default": True, **({"properties": default["properties"]} if "properties" in default else {})}]
        table[name] = entry

    OUT.mkdir(parents=True, exist_ok=True)
    lines = [f"{json.dumps(name)}: {json.dumps(entry, separators=(',', ':'))}" for name, entry in table.items()]
    (OUT / "blocks-26.3.json").write_text("{\n" + ",\n".join(lines) + "\n}\n")
    # Block entity fields that only count progress. Both sides changing one is never a conflict.
    # A field is a path: a.b is field b of compound a, and a[].b is field b of every compound in list a.
    progress = ["lit_time_remaining", "lit_total_time", "cooking_time_spent", "cooking_total_time", "RecipesUsed",
                "BrewTime", "Fuel", "total_brew_time", "total_fuel", "CookingTimes", "CookingTotalTimes",
                "TransferCooldown", "crafting_ticks_remaining", "triggered", "last_interacted_slot",
                # A vault's next update time. The bees in a hive and the flower they last went to: bees come and
                # go by themselves, and the entity merge sees to it that each is there once.
                "server_data.state_updating_resumes_at", "bees", "flower_pos"]
    # Entity fields that hold items. Both sides changing one makes an entity change a true conflict.
    entity_items = ["Item", "Items", "Inventory", "equipment", "Offers"]
    # Statistics that are not running totals: they restart at zero, so they are not added up.
    stat_timers = ["minecraft:custom/minecraft:time_since_death", "minecraft:custom/minecraft:time_since_rest"]
    # Entity fields that show a player's hand: an entity that has one is never taken for generated wildlife.
    player_marks = ["Owner", "CustomName", "leash", "PersistenceRequired"]
    # Of those, the ones the game itself gives to what hatches or comes of another creature. Seen in the game:
    # a frog that was a tadpole and a tadpole out of frogspawn are both kept from despawning.
    birth_marks = ["PersistenceRequired"]
    # A creature that turns into another is a new entity with a new UUID. Read from the game's classes: every
    # call of Mob.convertTo and the two timed conversions, of a zombie and a skeleton.
    becomes = {"tadpole": ["frog"], "zombie_villager": ["villager"], "villager": ["zombie_villager", "witch"],
               "zombie": ["drowned"], "husk": ["zombie"], "skeleton": ["stray"], "pig": ["zombified_piglin"],
               "piglin": ["zombified_piglin"], "piglin_brute": ["zombified_piglin"], "hoglin": ["zoglin"],
               "mooshroom": ["cow"]}
    entity_becomes = {"minecraft:" + was: mc(*into) for was, into in becomes.items()}
    # What hatches from a block, in every form it may have by the time of the merge, and the most one block gives.
    # A turtle egg block says how many eggs it holds; frogspawn gives 2 to 5 tadpoles by chance.
    hatches = [{"block": "minecraft:turtle_egg", "into": mc("turtle"), "count": 4, "count_property": "eggs"},
               {"block": "minecraft:sniffer_egg", "into": mc("sniffer"), "count": 1},
               {"block": "minecraft:frogspawn", "into": mc("tadpole", "frog"), "count": 5},
               {"block": "minecraft:dried_ghast", "into": mc("happy_ghast"), "count": 1}]
    # Bees are entities while they fly and a list in their nest while they rest, where they have no UUID.
    hives = {"entities": mc("bee"), "home": "hive_pos", "occupants": "bees", "capacity": 3}
    known = set(json.loads((REPORT.parent / "registries.json").read_text())["minecraft:entity_type"]["entries"])
    named = set(entity_becomes) | {name for into in entity_becomes.values() for name in into} | set(hives["entities"])
    named |= {name for hatch in hatches for name in hatch["into"]}
    unknown = sorted(named - known)
    if unknown:
        raise SystemExit(f"not entity types of this version: {unknown}")
    assert all(hatch["block"] in blocks for hatch in hatches)
    rules = {"minecraft": "26.3", "noise": noise, "units": units,
             "block_entity_progress": progress, "entity_item_fields": entity_items, "stat_timers": stat_timers,
             "entity_player_marks": player_marks, "entity_birth_marks": birth_marks, "entity_becomes": entity_becomes, "hatches": hatches, "hives": hives}
    text = json.dumps(rules, indent=1)
    (OUT / "rules-26.3.json").write_text(text + "\n")
    print(f"{len(table)} blocks, {len(noise)} noise rules, {len(units)} unit rules")
    print(f"doors {len(doors)}, tall plants {len(tall_plants)}: {[n.split(':')[1] for n in tall_plants]}")
    print(f"chests {len(chests)}, leaves {len(leaves)}, redstone powered {len(without(having('powered'), 'lever'))}")
    for path in sorted(OUT.glob("*-26.3.json")):
        print(f"   {path.relative_to(REPO_ROOT)}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
