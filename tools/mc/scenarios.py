"""The merge scenarios of CHUNK-MERGE-SPEC.md that can be scripted without a player.

Each scenario forks a 26.3 world, plays both copies with console commands,
merges them with mycelium-merge and checks the merged world in the game against
its written expected result. Results are recorded in docs/SCENARIOS.md.

    python3 tools/mc/scenarios.py            # all
    python3 tools/mc/scenarios.py 03 07      # by number

Not scripted: the player-ridden boat of scenario 5 and anything about player
files, which need a real client.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import time

import garden
import nbtlib
import pens
import regionlib
from build_probe_world import wait_loaded
from mcserver import REPO_ROOT
from scenariolib import (DIM, Merge, Scenario, block_at, block_entity_at, chunk_root, counts, expect_counts,
                         heightmap, play, poi_records, run, run_dir, same_blocks, set_blocks, sprint)


def uid(n):
    return f"[I;0,0,0,{n}]"


def uuid(n):
    return f"00000000-0000-0000-0000-{n:012x}"


def at(n, x, y, z):
    """A check that entity n stands within a block of a position."""
    return f"execute as {uuid(n)} at @s positioned {x} {y} {z} if entity @s[distance=..1]"


DOOR = "minecraft:oak_door[facing=north,hinge=left,open=false,powered=false,half={}]"
BED = "minecraft:red_bed[facing={},occupied=false,part={}]"
CHEST = 'minecraft:chest[facing=north,type={}]{{Items:[{{Slot:0b,id:"minecraft:{}",count:{}}}]}}'


def no_conflicts(entries):
    bad = [e for e in entries if e["severity"] != "resolved"]
    return [f"unexpected {e['severity']} {e['category']} at {e.get('pos')}: {e.get('detail')}" for e in bad[:5]]


# ---- 1 ------------------------------------------------------------------------------------------

def files_01(worlds, label):
    problems = same_blocks(worlds["out"], worlds["control"], (0, 0, 47, 15))
    if poi_records(worlds["out"], 0, 0) != poi_records(worlds["control"], 0, 0):
        problems.append(f"POI records of chunk 0,0 are {poi_records(worlds['out'], 0, 0)},"
                        f" the control has {poi_records(worlds['control'], 0, 0)}")
    if len(poi_records(worlds["out"], 0, 0)) != 3:
        problems.append("expected the bell, barrel and bed to be points of interest again")
    for x, z in ((12, 9), (3, 8), (2, 2), (20, 8)):
        if heightmap(worlds["out"], x, z) != heightmap(worlds["control"], x, z):
            problems.append(f"heightmap at {x},{z} is {heightmap(worlds['out'], x, z)},"
                            f" the control has {heightmap(worlds['control'], x, z)}")
    return problems


S01 = Scenario(
    key="01", title="Building in different areas of the same chunk",
    expected="Both builds are in the merged chunk, block for block the same as a world where both were built in"
             " turn. Nothing is reported. The game rebuilds the chunk's heightmaps and its points of interest"
             " (a bell, a barrel and a bed that were in the chunk before). Light is compared with that world.",
    base=["setblock 2 1 2 minecraft:bell[attachment=floor]", "setblock 5 1 2 minecraft:barrel",
          f"setblock 8 1 3 {BED.format('north', 'foot')} strict", f"setblock 8 1 2 {BED.format('north', 'head')} strict"],
    ours=["fill 1 1 6 5 3 10 minecraft:oak_planks hollow", "setblock 3 1 8 minecraft:glowstone",
          "setblock 3 2 6 minecraft:air"],
    theirs=["fill 9 4 6 14 4 12 minecraft:stone", "fill 9 1 6 9 3 6 minecraft:cobblestone",
            "setblock 12 1 9 minecraft:lantern"],
    control=True,
    light=[(x, y, z) for y in (1, 2) for x in range(0, 20, 2) for z in range(5, 14, 2)],
    merges={
        "relight-changed": Merge("ours", report=expect_counts({}), checks={
            "execute if block 3 1 8 minecraft:glowstone": True, "execute if block 12 4 9 minecraft:stone": True,
            "execute if block 12 1 9 minecraft:lantern": True, "execute if block 2 1 2 minecraft:bell": True}),
        "relight-neighbours": Merge("ours", report=expect_counts({}), args=["--relight", "neighbours"]),
    },
    files=files_01)

# ---- 2 ------------------------------------------------------------------------------------------


def report_02(winner):
    def check(entries):
        wanted = {"severity": "conflict", "category": "block", "dimension": "minecraft:overworld", "pos": [4, 1, 4],
                  "base": "minecraft:oak_planks", "ours": "minecraft:stone_bricks", "theirs": "minecraft:glass",
                  "winner": winner}
        return [] if entries == [wanted] else [f"report entries are {entries}, expected exactly {wanted}"]
    return check


S02 = Scenario(
    key="02", title="Building the same block differently",
    expected="The newer side's block is in the merged world, and the report holds exactly one conflict that names"
             " the position, the base block, both sides' blocks and the winner.",
    base=["setblock 4 1 4 minecraft:oak_planks"],
    ours=["setblock 4 1 4 minecraft:stone_bricks"],
    theirs=["setblock 4 1 4 minecraft:glass"],
    merges={
        "newer-ours": Merge("ours", report=report_02("ours"), checks={"execute if block 4 1 4 minecraft:stone_bricks": True}),
        "newer-theirs": Merge("theirs", report=report_02("theirs"), checks={"execute if block 4 1 4 minecraft:glass": True}),
    })

# ---- 3 ------------------------------------------------------------------------------------------

CROPS = [(x, 1, z) for x in range(2, 9) for z in (2, 4)]


def age(state):
    if state is None or "[" not in state:
        return 0
    return int(dict(pair.split("=") for pair in state.split("[")[1].rstrip("]").split(",")).get("age", 0))


def cane_height(world):
    return sum(1 for y in range(1, 5) if (block_at(world, 9, y, 3) or "").startswith("minecraft:sugar_cane"))


def files_03(worlds, label):
    problems = []
    grown = [0, 0]
    for pos in CROPS:
        ours, theirs, merged = (age(block_at(worlds[w], *pos)) for w in ("ours", "theirs", "out"))
        grown[0] += ours
        grown[1] += theirs
        if merged != max(ours, theirs):
            problems.append(f"crop at {pos}: ours age {ours}, theirs age {theirs}, merged age {merged}")
    heights = [cane_height(worlds[w]) for w in ("ours", "theirs", "out")]
    if heights[2] != max(heights[:2]):
        problems.append(f"sugar cane is {heights[2]} tall, the sides have {heights[:2]}")
    if grown[0] == 0 or grown[0] == grown[1]:
        problems.append(f"the farms did not grow differently (total ages {grown}); the scenario proves nothing")
    times = [int(nbtlib.load_file(worlds[w] / "level.dat")[1]["Data"]["Time"]) for w in ("ours", "theirs", "out")]
    if times[2] < max(times[:2]):
        problems.append(f"game time is {times[2]}, the sides have {times[:2]}")
    print(f"      farm: total crop age ours {grown[0]}, theirs {grown[1]}; cane heights ours/theirs/merged {heights}")
    return problems


def report_03(entries):
    problems = no_conflicts(entries)
    other = sorted({e.get("detail") for e in entries} - {"growth"})
    if other:
        problems.append(f"resolved entries by rules other than growth: {other}")
    if not entries:
        problems.append("no crop was settled by the growth rule; the scenario proves nothing")
    return problems


S03 = Scenario(
    key="03", title="Farms growing on both sides",
    expected="Wheat, carrots and sugar cane grow for 30,000 ticks on one side and 60,000 on the other, with nobody"
             " online. Every crop in the merged world is at the further grown of the two stages, the cane is as tall"
             " as the taller side, and game time is the later one. The report has only resolved growth entries, no"
             " conflicts.",
    base=["fill 1 -1 1 10 -1 5 minecraft:stone", "fill 2 0 2 8 0 4 minecraft:farmland[moisture=7] strict",
          "fill 2 0 3 8 0 3 minecraft:water", "fill 2 1 2 8 1 2 minecraft:wheat strict",
          "fill 2 1 4 8 1 4 minecraft:carrots strict", "setblock 9 0 3 minecraft:dirt",
          "setblock 9 1 3 minecraft:sugar_cane strict"],
    ours=[sprint(30000)],
    theirs=[sprint(60000)],
    merges={"newer-ours": Merge("ours", report=report_03)},
    files=files_03)

# ---- 4 ------------------------------------------------------------------------------------------


def items(world, x, y, z):
    block_entity = block_entity_at(world, x, y, z)
    return {int(i["Slot"]): (str(i["id"]), int(i["count"])) for i in block_entity.get("Items", [])} if block_entity else None


def files_04(worlds, label):
    ours, theirs = items(worlds["ours"], 3, 1, 3), items(worlds["theirs"], 3, 1, 3)
    print(f"      furnace slots: ours {ours}, theirs {theirs}")
    if ours == theirs or ours.get(2) is None or theirs.get(2) is None:
        return ["the furnaces did not run differently; the scenario proves nothing"]
    return []


def furnace_checks(newer):
    """The merged furnace holds exactly the newer side's stacks, and the furnace block is lit."""
    def checks(worlds):
        wanted = {f'execute if data block 3 1 3 Items[{{Slot:{slot}b,id:"{name}",count:{count}}}]': True
                  for slot, (name, count) in items(worlds[newer], 3, 1, 3).items()}
        wanted["execute if block 3 1 3 minecraft:furnace[lit=true]"] = True
        return wanted
    return checks


S04 = Scenario(
    key="04", title="Furnaces running on both sides",
    expected="A furnace smelts for 300 ticks on one side and 700 on the other. The merged furnace holds the newer"
             " side's input and output stacks. The two slots both sides changed are recorded as item conflicts for"
             " the later rebase. Burn time and cooking progress are not reported, and neither is the lit state.",
    base=['setblock 3 1 3 minecraft:furnace{Items:[{Slot:0b,id:"minecraft:raw_iron",count:16},'
          '{Slot:1b,id:"minecraft:coal",count:8}]}'],
    ours=[sprint(300)],
    theirs=[sprint(700)],
    merges={f"newer-{newer}": Merge(newer, report=expect_counts({"conflict container_slot": 2}),
                                    checks=furnace_checks(newer)) for newer in ("ours", "theirs")},
    files=files_04)

# ---- 5 ------------------------------------------------------------------------------------------

MOB = "PersistenceRequired:1b"


def stored_position(world, n):
    """Where a world's entity files have entity n, riders included."""
    for path in (world / DIM / "entities").glob("r.*.mca"):
        for entry in regionlib.Region(path).entries():
            waiting = list(entry.nbt()["Entities"])
            while waiting:
                entity = waiting.pop()
                if [int(part) for part in entity["UUID"]] == [0, 0, 0, n]:
                    return tuple(float(value) for value in entity["Pos"])
                waiting += entity.get("Passengers", [])
    raise SystemExit(f"entity {n} is not in {world}")


def checks_05(worlds):
    # A mob that was just moved walks a few blocks before it stands still, so the newer side's place is
    # where its world has the mob, not where the command put it.
    def where_theirs_has(n):
        return at(n, *stored_position(worlds["theirs"], n))
    return {
        where_theirs_has(201): True,
        f"execute if entity {uuid(202)}": False,
        f"execute if entity {uuid(209)}": True,
        where_theirs_has(206): True,
        f"execute as {uuid(207)} on vehicle if entity @s[type=minecraft:oak_boat]": True,
        f"execute if data entity {uuid(203)} {{leash:[I;10,1,10]}}": True,
        f"execute if data entity {uuid(205)} leash{{UUID:{uid(204)}}}": True,
        where_theirs_has(205): True,
        where_theirs_has(204): True,
        f"execute if entity {uuid(208)}": True,
    }


def report_05(entries):
    problems = no_conflicts(entries)
    if not any(e["category"] == "entity" and uuid(201) in e.get("detail", "") for e in entries):
        problems.append("the cow moved by both sides is not in the report as resolved")
    return problems


def files_05(worlds, label):
    found = []

    def walk(entity):
        found.append("".join(f"{v & 0xFFFFFFFF:08x}" for v in entity["UUID"]))
        for rider in entity.get("Passengers", []):
            walk(rider)

    for path in (worlds["out"] / DIM / "entities").glob("r.*.mca"):
        for entry in regionlib.Region(path).entries():
            for entity in entry.nbt()["Entities"]:
                walk(entity)
    doubles = sorted({u for u in found if found.count(u) > 1})
    return [f"entities stored twice: {doubles}"] if doubles else []


S05 = Scenario(
    key="05", title="Mobs moving across chunk borders, with leashes and a ridden boat",
    expected="Mobs left alone do not wander with nobody online, so each side moves them with /tp. A cow moved by both sides is"
             " once in the merged world, at the newer side's place, recorded as resolved. A sheep killed on one side"
             " and moved on the other is gone: the other side only had it loaded. A second sheep, killed on one side"
             " and given a name on the other, is kept. A boat with a pig in it, moved into different chunks by both sides,"
             " is once in the merged world, in the newer side's chunk, with its rider still in it. A cow tied to a"
             " fence and a pig led by a villager keep their leashes, also after the villager and pig move to another"
             " chunk. A chicken added by one side is there. No entity is stored twice. (The player-ridden boat needs"
             " a client and is not covered.)",
    base=[f"summon minecraft:cow 14.5 1 4.5 {{UUID:{uid(201)},{MOB}}}",
          f"summon minecraft:sheep 12.5 1 8.5 {{UUID:{uid(202)},{MOB}}}",
          f"summon minecraft:sheep 12.5 1 13.5 {{UUID:{uid(209)},{MOB}}}",
          "setblock 10 1 10 minecraft:oak_fence",
          f"summon minecraft:cow 11.5 1 10.5 {{UUID:{uid(203)},{MOB},leash:[I;10,1,10]}}",
          f"summon minecraft:villager 20.5 1 4.5 {{UUID:{uid(204)},NoAI:1b}}",
          f"summon minecraft:pig 21.5 1 5.5 {{UUID:{uid(205)},{MOB},leash:{{UUID:{uid(204)}}}}}",
          f'summon minecraft:oak_boat 5.5 1 12.5 {{UUID:{uid(206)},Passengers:[{{id:"minecraft:pig",UUID:{uid(207)},{MOB}}}]}}'],
    ours=[f"tp {uuid(201)} 18.5 1 4.5", f"tp {uuid(206)} 40.5 1 12.5", f"kill {uuid(202)}", f"kill {uuid(209)}", sprint(40)],
    theirs=[f"tp {uuid(201)} 2.5 1 4.5", f"tp {uuid(202)} 30.5 1 8.5", f"tp {uuid(206)} 25.5 1 12.5",
            f'data merge entity {uuid(209)} {{CustomName:"Dolly"}}',
            f"tp {uuid(204)} 36.5 1 4.5",
            f"tp {uuid(205)} 35.5 1 5.5", f"summon minecraft:chicken 40.5 1 8.5 {{UUID:{uid(208)},{MOB}}}", sprint(40)],
    merges={"newer-theirs": Merge("theirs", report=report_05, checks=checks_05)},
    files=files_05)

# ---- 6 ------------------------------------------------------------------------------------------

S06 = Scenario(
    key="06", title="Both sides taking items from the same chest",
    expected="A slot emptied by one side stays empty, a slot filled by one side stays filled, and the one slot both"
             " sides changed holds the newer side's stack and is recorded as an item conflict. No stack appears that"
             " neither side had.",
    base=['setblock 2 1 2 minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:64},'
          '{Slot:1b,id:"minecraft:iron_ingot",count:10},{Slot:2b,id:"minecraft:gold_ingot",count:5}]}'],
    ours=["item replace block 2 1 2 container.0 with minecraft:air",
          "item replace block 2 1 2 container.1 with minecraft:iron_ingot 4"],
    theirs=["item replace block 2 1 2 container.2 with minecraft:air",
            "item replace block 2 1 2 container.1 with minecraft:iron_ingot 7",
            "item replace block 2 1 2 container.9 with minecraft:bread 3"],
    merges={f"newer-{newer}": Merge(newer, report=expect_counts({"conflict container_slot": 1}), checks={
        "execute if data block 2 1 2 Items[{Slot:0b}]": False,
        "execute if data block 2 1 2 Items[{Slot:2b}]": False,
        'execute if data block 2 1 2 Items[{Slot:9b,id:"minecraft:bread",count:3}]': True,
        f"execute if data block 2 1 2 Items[{{Slot:1b,count:{count}}}]": True,
    }) for newer, count in (("ours", 4), ("theirs", 7))})

# ---- 7 ------------------------------------------------------------------------------------------

S07 = Scenario(
    key="07", title="A door on one side, a block in its top half on the other",
    expected="There is never half a door. If the door's side is newer, the door stands whole and the block is not"
             " placed: one block conflict. If the block's side is newer, the block stands and the whole door is gone:"
             " one block conflict and one unit conflict.",
    ours=[f"setblock 8 1 8 {DOOR.format('lower')} strict", f"setblock 8 2 8 {DOOR.format('upper')} strict"],
    theirs=["setblock 8 2 8 minecraft:stone"],
    merges={
        "newer-ours": Merge("ours", report=expect_counts({"conflict block": 1}), checks={
            f"execute if block 8 1 8 {DOOR.format('lower')}": True, f"execute if block 8 2 8 {DOOR.format('upper')}": True}),
        "newer-theirs": Merge("theirs", report=expect_counts({"conflict block": 1, "conflict unit": 1}), checks={
            "execute if block 8 1 8 minecraft:air": True, "execute if block 8 2 8 minecraft:stone": True}),
    })

# ---- 8 ------------------------------------------------------------------------------------------

S08 = Scenario(
    key="08", title="A bed and a double chest across a chunk border",
    expected="One side turns a chest at the edge of a chunk into a double chest reaching into the next chunk, and"
             " lays a bed across the same border. The other side puts a block where each second half would be. If"
             " the first side is newer, both stand whole. If the second is newer, its blocks stand, the chest is a"
             " single chest again with its original items, and the bed is gone from both chunks: two block conflicts"
             " and two unit conflicts.",
    base=[f"setblock 15 1 4 {CHEST.format('single', 'diamond', 10)}"],
    ours=[f"setblock 15 1 4 {CHEST.format('left', 'diamond', 10)} strict",
          f"setblock 16 1 4 {CHEST.format('right', 'emerald', 5)} strict",
          f"setblock 15 1 12 {BED.format('east', 'foot')} strict", f"setblock 16 1 12 {BED.format('east', 'head')} strict"],
    theirs=["setblock 16 1 4 minecraft:stone", "setblock 16 1 12 minecraft:glass"],
    merges={
        "newer-ours": Merge("ours", report=expect_counts({"conflict block": 2}), checks={
            "execute if block 15 1 4 minecraft:chest[type=left]": True,
            "execute if block 16 1 4 minecraft:chest[type=right]": True,
            'execute if data block 16 1 4 Items[{Slot:0b,id:"minecraft:emerald",count:5}]': True,
            f"execute if block 15 1 12 {BED.format('east', 'foot')}": True,
            f"execute if block 16 1 12 {BED.format('east', 'head')}": True}),
        "newer-theirs": Merge("theirs", report=expect_counts({"conflict block": 2, "conflict unit": 2}), checks={
            "execute if block 15 1 4 minecraft:chest[type=single]": True,
            'execute if data block 15 1 4 Items[{Slot:0b,id:"minecraft:diamond",count:10}]': True,
            "execute if block 16 1 4 minecraft:stone": True,
            "execute if block 15 1 12 minecraft:air": True, "execute if block 16 1 12 minecraft:glass": True}),
    })

# ---- 9 ------------------------------------------------------------------------------------------

# Finished chunks reach two chunks beyond a forced area, so the areas are kept well apart.
OVERLAP = (768, 640, 799, 671)       # chunks 48..49 x 40..41, generated by both sides
ONLY_OURS = (896, 640, 927, 671)     # chunks 56..57 x 40..41
ONLY_THEIRS = (640, 768, 671, 799)   # chunks 40..41 x 48..49
GOLD, EMERALD = "execute if block 900 200 650 minecraft:gold_block", "execute if block 650 200 775 minecraft:emerald_block"
DIAMOND, LAPIS = "execute if block 770 200 650 minecraft:diamond_block", "execute if block 790 200 660 minecraft:lapis_block"
BOTH = "execute if block 780 200 655 minecraft:stone"


def entity_count(world, area):
    total = 0
    for path in (world / DIM / "entities").glob("r.*.mca"):
        for entry in regionlib.Region(path).entries():
            root = entry.nbt()
            x, z = (int(v) for v in root["Position"])
            if area[0] <= x * 16 <= area[2] and area[1] <= z * 16 <= area[3]:
                total += len(root["Entities"])
    return total


def report_09(generated):
    def check(entries):
        problems = []
        mine = [e for e in entries if e.get("pos") == [780, 200, 655]]
        wanted = "block" if generated else "no_ancestor"
        if [e["category"] for e in mine] != [wanted] or mine[0]["winner"] != "theirs":
            problems.append(f"the block both sides built is reported as {mine}, expected one {wanted} conflict won by theirs")
        lost = [e for e in entries if e.get("pos") == [770, 200, 650]]
        if generated and lost:
            problems.append(f"ours' diamond block is reported as {lost}; with a generated base it is a plain one-sided change")
        if not generated and [e["category"] for e in lost] != ["no_ancestor"]:
            problems.append(f"ours' diamond block is reported as {lost}, expected a no-ancestor conflict")
        terrain = [e for e in entries if e["category"] == "no_ancestor" and e.get("pos") not in ([780, 200, 655], [770, 200, 650])]
        files = [e.get("detail") for e in entries if e["category"] == "file"]
        print(f"      no-ancestor conflicts in terrain the two sides generated differently: {len(terrain)}"
              + (f", e.g. {terrain[0].get('pos')}: {terrain[0].get('ours')} vs {terrain[0].get('theirs')}" if terrain else ""))
        if files:
            problems.append(f"file conflicts: {files}; the forced-chunk lists of both sides should merge as a set")
        unexpected = [e for e in entries if e["category"] not in ("no_ancestor", "file", "block") and e["severity"] != "resolved"]
        problems += [f"unexpected {e['category']} conflict at {e.get('pos')}" for e in unexpected[:3]]
        conflicts = [e for e in entries if e["severity"] == "conflict"]
        if generated and len(conflicts) != 1:
            problems.append(f"with a generated base there are {len(conflicts)} conflicts, expected the one block both built")
        return problems
    return check


def output_09(generated):
    def check(printed):
        noted = "note:" in printed and "--generated-base" in printed
        if generated and "note:" in printed:
            return ["the tool prints a note about chunks without an ancestor although every chunk has one"]
        if not generated and not noted:
            return ["the tool does not say that chunks were merged without an ancestor"]
        return []
    return check


def files_09(worlds, label):
    numbers = {w: entity_count(worlds[w], OVERLAP) for w in ("ours", "theirs", "out")}
    print(f"      entities in the chunks both sides generated: {numbers}")
    return []


S09 = Scenario(
    key="09", title="Each side exploring new chunks, with and without overlap",
    expected="Chunks only one side generated are taken from that side with what was built there. Chunks both sides"
             " generated have no ancestor. Without a generated base, equal blocks are kept and every difference goes"
             " to the newer side as a no-ancestor conflict: the newer side's build stays and the older side's build"
             " in those chunks is lost and reported. With a generated base they merge 3-way: both builds stay, and"
             " only the one block both built differently is a conflict. The unfinished chunks around the explored"
             " areas are taken whole and not reported. Each side forced different chunks in order to explore; the"
             " merged world forces all of them, so every explored area loads without being asked for.",
    area=(640, 640, 671, 671), natural=True,
    ours=[f"forceload add {OVERLAP[0]} {OVERLAP[1]} {OVERLAP[2]} {OVERLAP[3]}",
          f"forceload add {ONLY_OURS[0]} {ONLY_OURS[1]} {ONLY_OURS[2]} {ONLY_OURS[3]}",
          ("loaded", OVERLAP), ("loaded", ONLY_OURS),
          "setblock 900 200 650 minecraft:gold_block", "setblock 770 200 650 minecraft:diamond_block",
          "setblock 780 200 655 minecraft:glass"],
    theirs=[f"forceload add {OVERLAP[0]} {OVERLAP[1]} {OVERLAP[2]} {OVERLAP[3]}",
            f"forceload add {ONLY_THEIRS[0]} {ONLY_THEIRS[1]} {ONLY_THEIRS[2]} {ONLY_THEIRS[3]}",
            ("loaded", OVERLAP), ("loaded", ONLY_THEIRS),
            "setblock 650 200 775 minecraft:emerald_block", "setblock 790 200 660 minecraft:lapis_block",
            "setblock 780 200 655 minecraft:stone"],
    merges={
        "two-way": Merge("theirs", report=report_09(False), output=output_09(False),
                         checks={GOLD: True, EMERALD: True, LAPIS: True, BOTH: True, DIAMOND: False}),
        "generated-base": Merge("theirs", report=report_09(True), output=output_09(True),
                                checks={GOLD: True, EMERALD: True, LAPIS: True, BOTH: True, DIAMOND: True},
                                args=["--generated-base", "GENERATED"]),
    },
    # The chunks both sides generated are the ones merged block by block, so they are checked too.
    also_derived=[OVERLAP],
    files=files_09)

# ---- 10 -----------------------------------------------------------------------------------------


def modded(name, **properties):
    if not properties:
        return nbtlib.String(name)
    return nbtlib.Compound({"id": nbtlib.String(name), "properties": nbtlib.Compound(
        {key: nbtlib.String(str(value)) for key, value in properties.items()})})


MACHINE = nbtlib.Compound({"id": nbtlib.String("somemod:machine"), "x": nbtlib.Int(6), "y": nbtlib.Int(1),
                           "z": nbtlib.Int(6), "energy": nbtlib.Int(500),
                           "upgrades": nbtlib.List(nbtlib.STRING, [nbtlib.String("speed")])})


def edit_10(worlds):
    def add_machine_data(root):
        root["block_entities"] = nbtlib.List(nbtlib.COMPOUND, list(root["block_entities"]) + [MACHINE])
        root["somemod:chunk_data"] = nbtlib.Compound({"flux": nbtlib.Int(7)})

    set_blocks(worlds["base"], {(14, 1, 14): modded("somemod:crop", age=1)})
    set_blocks(worlds["ours"], {(6, 1, 6): modded("somemod:machine", power=3), (8, 1, 8): modded("somemod:pipe"),
                                (10, 1, 10): modded("somemod:machine", power=1),
                                (14, 1, 14): modded("somemod:crop", age=2)}, add_machine_data)
    set_blocks(worlds["theirs"], {(10, 1, 10): modded("somemod:machine", power=2), (12, 1, 12): modded("othermod:lamp"),
                                  (14, 1, 14): modded("somemod:crop", age=5)})


def files_10(worlds, label):
    out = worlds["out"]
    wanted = {(6, 1, 6): "somemod:machine[power=3]", (8, 1, 8): "somemod:pipe", (10, 1, 10): "somemod:machine[power=1]",
              (12, 1, 12): "othermod:lamp", (14, 1, 14): "somemod:crop[age=2]", (2, 1, 2): "minecraft:stone",
              (4, 1, 4): "minecraft:glass"}
    problems = [f"block at {pos} is {block_at(out, *pos)}, expected {state}" for pos, state in wanted.items()
                if block_at(out, *pos) != state]
    if block_entity_at(out, 6, 1, 6) != MACHINE:
        problems.append(f"the modded block entity came out as {block_entity_at(out, 6, 1, 6)}")
    if chunk_root(out, 0, 0).get("somemod:chunk_data") != nbtlib.Compound({"flux": nbtlib.Int(7)}):
        problems.append("the mod's chunk field is gone or changed")
    report = json.loads(worlds["report"].read_text())
    if report["unknownBlocks"] != ["othermod:lamp", "somemod:crop", "somemod:machine", "somemod:pipe"]:
        problems.append(f"unknownBlocks is {report['unknownBlocks']}")
    return problems


S10 = Scenario(
    key="10", title="A world with blocks from a mod the tool does not know",
    expected="Modded block states, a modded block entity and a mod's chunk field are written into the world files"
             " directly, since a vanilla server cannot hold them. All of them come through the merge unchanged."
             " A modded block both sides changed is a whole-value conflict won by the newer side, with no growth"
             " rule applied to a modded crop. The report lists each unknown block id once. The merged world is"
             " checked in its files, not loaded: vanilla cannot load modded blocks.",
    ours=["setblock 2 1 2 minecraft:stone"],
    theirs=["setblock 4 1 4 minecraft:glass"],
    edit=edit_10,
    merges={"newer-ours": Merge("ours", report=expect_counts({"conflict block": 2}), load=False)},
    files=files_10)

# ---- 11 -----------------------------------------------------------------------------------------

# A dark stone box across the border between chunks 0 and 1. The inside is x 11..20, y 2..3, z 6..10.
S11 = Scenario(
    key="11", title="Light across the border between a chunk of each side",
    expected="Ours puts a torch in the last column of chunk 0. Theirs builds in chunk 1, next to it. Each chunk was"
             " changed by one side only, so each is taken whole with that side's light: chunk 1 keeps the light of a"
             " world without the torch. The blocks are right and nothing is reported. The light in chunk 1 is not"
             " what a world with both changes has; this measures how far off it is in each relight mode. The tool"
             " does not compute light, and the game does not pull light across a chunk border when it relights.",
    base=["fill 10 1 5 21 4 11 minecraft:stone hollow"],
    ours=["setblock 15 2 8 minecraft:torch"],
    theirs=["setblock 19 2 8 minecraft:oak_planks"],
    control=True,
    light=[(x, 2, z) for x in (12, 13, 14, 16, 17, 18) for z in (7, 8, 9)],
    light_is_limit=True,
    merges={mode: Merge("theirs", report=expect_counts({}), args=["--relight", mode], checks={
        "execute if block 15 2 8 minecraft:torch": True, "execute if block 19 2 8 minecraft:oak_planks": True})
        for mode in ("changed", "neighbours", "all")})

# ---- 12 -----------------------------------------------------------------------------------------

# How the game grows a tree: the sapling goes, then the tree is placed with its lowest log where it stood.
def grow_oak(x, z):
    return [f"setblock {x} 2 {z} minecraft:air", f"place feature minecraft:oak {x} 2 {z}"]


def report_12(entries):
    problems = []
    first = [e for e in entries if e.get("pos") == [8, 2, 8]]
    if [(e["severity"], e["category"], e.get("detail")) for e in first] != [("resolved", "block", "growth")]:
        problems.append(f"the sapling that grew on one side only is reported as {first}, expected one resolved growth entry")
    near_first = [e for e in entries if e["severity"] == "conflict" and e.get("pos") and e["pos"][0] < 16]
    if near_first:
        problems.append(f"{len(near_first)} conflicts around the tree only one side grew, e.g. {near_first[0]}")
    both = [e for e in entries if e["severity"] == "conflict" and e.get("pos") and e["pos"][0] >= 16]
    print(f"      conflicts where both sides grew a tree from the same sapling: {len(both)}"
          + (f", e.g. {both[0].get('pos')}: {both[0].get('ours')} vs {both[0].get('theirs')}" if both else ""))
    return problems


S12 = Scenario(
    key="12", title="A sapling that becomes a tree on one side only",
    expected="Two oak saplings on dirt. The first grows into a tree on ours and only ages on theirs. The whole tree"
             " is in the merged world, with its lowest log where the sapling stood, whichever side is newer; that"
             " block is recorded as resolved growth and nothing around the tree conflicts. The second sapling grows"
             " on both sides, into two trees of different shape. Its trunk is a log on both sides; where the shapes"
             " differ the cells are true conflicts, and their number is printed.",
    base=["fill 4 1 4 28 1 12 minecraft:dirt", "setblock 8 2 8 minecraft:oak_sapling", "setblock 24 2 8 minecraft:oak_sapling"],
    ours=grow_oak(8, 8) + grow_oak(24, 8),
    theirs=["setblock 8 2 8 minecraft:oak_sapling[stage=1]"] + grow_oak(24, 8),
    merges={f"newer-{newer}": Merge(newer, report=report_12, checks={
        "execute if block 8 2 8 minecraft:oak_log": True, "execute if block 8 3 8 minecraft:oak_log": True,
        "execute if block 24 2 8 minecraft:oak_log": True}) for newer in ("ours", "theirs")})


# ---- 13, 14 -------------------------------------------------------------------------------------

def passing(ticks, night=False):
    """Steps that let the garden grow: random ticks at 130 times their usual rate for a number of game ticks."""
    return (["time set 14000"] if night else []) + ["gamerule random_tick_speed 400", sprint(ticks), "gamerule random_tick_speed 3"]


def files_garden(worlds, label):
    entries = json.loads(worlds["report"].read_text())["entries"]
    lines = garden.describe(worlds, entries)
    if label == "newer-ours":
        for line in lines:
            print("      " + line)
    problems = []
    plot = None
    for line in lines:
        if not line.startswith(" "):
            plot = line.split(":")[0]
        # A few blocks change in any world that runs, and leaves next to another tree's log count their distance
        # again. Many blocks turning into other blocks means the merge left something that cannot stand.
        elif "after loading" in line and int(line.split(" of them")[0].split()[-1]) > 8:
            problems.append(f"{plot}: {line.strip()}")
    return problems


S13 = Scenario(
    key="13", title="A garden grows on both sides, a little",
    expected="One of everything the game changes by itself: crops, stems, vines, kelp, copper, amethyst, dripstone,"
             " leaves, saplings. Time passes on both sides, longer on theirs, where it is also night. Nothing a player"
             " did, so nothing may conflict, whichever side is newer, and the merged world loads clean.",
    base=garden.base_commands(), ours=passing(150), theirs=passing(400, night=True), area=garden.AREA, snapshot=True,
    files=files_garden,
    merges={f"newer-{newer}": Merge(newer, report=no_conflicts) for newer in ("ours", "theirs")})

S14 = Scenario(
    key="14", title="A garden grows on both sides, a lot",
    expected="The garden of scenario 13 with ten times the time, in daylight on both sides: crops ripen, stems bear"
             " fruit, saplings become trees of different shapes, copper weathers through. Nothing may conflict, and"
             " the merged world loads clean.",
    base=garden.base_commands(), ours=passing(1500), theirs=passing(4000), area=garden.AREA, snapshot=True,
    files=files_garden,
    merges={f"newer-{newer}": Merge(newer, report=no_conflicts) for newer in ("ours", "theirs")})


# ---- 15 -----------------------------------------------------------------------------------------

def hand():
    """What a player does in the garden while the game works on it in the other copy: position -> block."""
    def at(name, dx=0, dy=0, dz=0):
        x, z = garden.origin(name)
        return f"{x + dx} {dy} {z + dz}"
    return {
        at("wheat", dy=2): "air",                                          # harvested, not replanted
        at("melon stem", dy=2): "air",                                     # dug up
        at("kelp", dy=1): "water",                                         # cut
        at("copper blocks", dx=-1, dy=1): "waxed_copper_block",            # waxed
        at("copper blocks", dx=3, dy=1, dz=3): "copper_block",             # an exposed block scraped clean
        at("copper blocks", dx=-1, dy=1, dz=3): "cut_copper_stairs[facing=east]",   # turned
        at("soil", dy=1, dz=3): "farmland",                                # tilled where grass would spread
        at("leaves", dy=4): "glass",                                       # built where a leaf would decay
        at("dripstone", dx=2, dy=1): "hopper",                             # put where the cauldron stood
        at("oak sapling", dx=1, dy=5): "stone",                            # built where a leaf of the tree would grow
        # The three below meet something on the other side that a player could have done as well.
        at("sugar cane", dy=3): "stone",                                   # built where the cane would grow
        at("amethyst", dx=1, dy=3): "stone",                               # built where a bud would grow
        at("birch sapling", dy=2): "air",                                  # dug up before it became a tree
    }


HAND = hand()
CONTESTED = list(HAND)[-3:]


def report_15(entries):
    conflicts = sorted(" ".join(map(str, e["pos"])) for e in entries if e["severity"] != "resolved")
    return [] if conflicts == sorted(CONTESTED) else [f"conflicts at {conflicts}, expected exactly at {sorted(CONTESTED)}"]


S15 = Scenario(
    key="15", title="A player's hand in the garden against the game's",
    expected="The garden of scenario 13. On ours a player harvests the wheat, digs up the melon stem, cuts the kelp,"
             " waxes one copper block, scrapes another and turns the stairs, tills dirt, swaps the"
             " cauldron for a hopper and builds where a leaf decays and where a leaf of the oak will grow. On theirs"
             " only time passes. Each of these blocks is in the merged world as the player left it, whichever side is"
             " newer, and none is a conflict. Three more are true conflicts, because a player could have done what"
             " the game did on theirs: a block built where cane grew and one where a bud grew, and a sapling dug up"
             " where the tree now stands. There the newer side's block stands.",
    base=garden.base_commands(), ours=[f"setblock {pos} minecraft:{block}" for pos, block in HAND.items()],
    theirs=passing(4000), area=garden.AREA, snapshot=True, files=files_garden,
    merges={f"newer-{newer}": Merge(newer, report=report_15, checks={
        f"execute if block {pos} minecraft:{block}": newer == "ours" or pos not in CONTESTED
        for pos, block in HAND.items()}) for newer in ("ours", "theirs")})


# ---- 16 -----------------------------------------------------------------------------------------

def days(ticks):
    """Steps that let the pens live: first the hours before dawn with fast random ticks, when turtle eggs hatch."""
    return ["time set 20500", "gamerule random_tick_speed 400", sprint(2500), "gamerule random_tick_speed 3", sprint(ticks)]


def files_pens(worlds, label):
    if label == "newer-ours":
        for line in pens.describe(worlds):
            print("      " + line)
    # The copper golem turns into a statue where it stands. Now and then that is the same block on both sides,
    # in two poses, which is a conflict like any two blocks in one place.
    conflicts = [e for e in json.loads(worlds["report"].read_text())["entries"]
                 if e["severity"] != "resolved" and "copper_golem_statue" not in (e.get("ours") or "")]
    return pens.problems(worlds) + [f"unexpected {e['severity']} {e['category']} at {e.get('pos')}: base {e.get('base')},"
                                    f" ours {e.get('ours')}, theirs {e.get('theirs')}" for e in conflicts]


S16 = Scenario(
    key="16", title="Animals, eggs and bees over a day on one side and two on the other",
    expected="Pens with a calf, a shorn sheep, a hen, tadpoles, zombie villagers being cured, a sitting wolf, a"
             " villager, a copper golem, a bee nest and eggs of four kinds. About a day passes on ours and two on"
             " theirs. The merged world has every creature once: what grew up or was cured on one side is not also"
             " there as it was before, and what hatched on both sides hatched once.",
    base=pens.base_commands(), ours=days(13000) + pens.bees_at_the_end("ours"),
    theirs=days(26000) + pens.bees_at_the_end("theirs"), area=pens.AREA, snapshot=True, files=files_pens,
    merges={f"newer-{newer}": Merge(newer) for newer in ("ours", "theirs")})


# ---- 17 -----------------------------------------------------------------------------------------

IN_PORTAL = "9.5 2 8.5"


def dimensions_of(world):
    """For each entity the scenario summoned, the dimensions whose entity files hold it, once for each time."""
    found = {}
    for folder in sorted((world / "dimensions" / "minecraft").iterdir()):
        waiting = [entity for path in sorted((folder / "entities").glob("r.*.mca"))
                   for entry in regionlib.Region(path).entries() for entity in entry.nbt()["Entities"]]
        while waiting:
            entity = waiting.pop()
            waiting += entity.get("Passengers", [])
            numbers = [int(part) for part in entity["UUID"]]
            if numbers[:3] == [0, 0, 0]:
                found.setdefault(numbers[3], []).append(folder.name)
    return {n: sorted(where) for n, where in sorted(found.items())}


def files_17(worlds, label):
    wanted = {300: ["the_nether"], 301: ["the_nether"], 302: ["the_nether"], 303: ["the_nether"],
              # Named on one side, through the portal on the other: both are a player's doing, and it is there twice.
              304: ["overworld", "the_nether"]}
    got = dimensions_of(worlds["merged"])
    return [] if got == wanted else [f"the entities are in {got}, expected {wanted}"]


S17 = Scenario(
    key="17", title="A mob that goes through a portal on one side",
    expected="A nether portal, and four mobs next to it. On ours all four go through the portal into the Nether. On"
             " theirs the first cow is moved and stays in the overworld, the sitting wolf is left alone, the second"
             " cow goes through the portal as well, and the third is given a name. In the merged world the first cow,"
             " the wolf and the second cow are in the Nether and nowhere else, whichever side is newer. The named cow"
             " is in both dimensions: going through a portal and naming are both a player's doing, and the tool"
             " merges each dimension on its own. Nothing is a conflict.",
    base=["difficulty peaceful", "fill 8 1 8 11 5 8 minecraft:obsidian", "fill 9 2 8 10 4 8 minecraft:nether_portal[axis=x]",
          # A pig goes through first, so that the base has the Nether's side of the portal.
          f"summon minecraft:pig {IN_PORTAL} {{UUID:{uid(300)},{MOB}}}", sprint(200),
          f"summon minecraft:cow 4.5 1 4.5 {{UUID:{uid(301)},{MOB}}}",
          f"summon minecraft:wolf 5.5 1 4.5 {{UUID:{uid(302)},Owner:{uid(999)},Sitting:1b}}",
          f"summon minecraft:cow 6.5 1 4.5 {{UUID:{uid(303)},{MOB}}}",
          f"summon minecraft:cow 7.5 1 4.5 {{UUID:{uid(304)},{MOB}}}", sprint(40)],
    ours=[f"tp {uuid(n)} {IN_PORTAL}" for n in (301, 302, 303, 304)] + [sprint(200)],
    theirs=[f"tp {uuid(301)} 30.5 1 4.5", f"tp {uuid(303)} {IN_PORTAL}", f'data merge entity {uuid(304)} {{CustomName:"Dolly"}}',
            sprint(200)],
    snapshot=True, files=files_17,
    merges={f"newer-{newer}": Merge(newer, report=no_conflicts) for newer in ("ours", "theirs")})

SCENARIOS = [S01, S02, S03, S04, S05, S06, S07, S08, S09, S10, S11, S12, S13, S14, S15, S16, S17]


def main():
    wanted = sys.argv[1:]
    subprocess.run([str(REPO_ROOT / "gradlew"), "-q", ":merge:installDist"], cwd=REPO_ROOT, check=True)
    failed = 0
    for scenario in SCENARIOS:
        if wanted and scenario.key not in wanted:
            continue
        started = time.time()
        print(f"\n== {scenario.key}. {scenario.title}")
        if scenario.key == "09":
            # A fresh world from the same seed with the shared chunks generated, as the ancestor for them.
            generated = run_dir(scenario, "generated")
            if generated.exists():
                shutil.rmtree(generated)
            play(generated, [], {}, lambda server: (
                server.run(f"forceload add {OVERLAP[0]} {OVERLAP[1]} {OVERLAP[2]} {OVERLAP[3]}"),
                wait_loaded(server, [(48, 40), (49, 40), (48, 41), (49, 41)])))
            for merge in scenario.merges.values():
                merge.args = [str(generated / "world") if arg == "GENERATED" else arg for arg in merge.args]
        problems, notes = run(scenario)
        waiting = [p for p in problems if any(point in p for point in scenario.open_points)]
        problems = [p for p in problems if p not in waiting]
        for note in notes:
            print("   ", note)
        for problem in waiting:
            print("    OPEN POINT:", problem)
        for problem in problems:
            print("    PROBLEM:", problem)
        verdict = "FAILED" if problems else "passed, with an open point" if waiting else "passed"
        print(f"    {verdict} in {time.time() - started:.0f}s")
        failed += bool(problems)
    print(f"\n{failed} scenarios failed" if failed else "\nall scenarios passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
