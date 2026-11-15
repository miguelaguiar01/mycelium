"""Ask the game where the other half of each multi-block shape sits.

For doors, tall plants, beds and double chests, both candidate layouts are
placed without block updates. Every empty neighbour is then poked with a block
update. The game keeps a correctly linked pair and breaks or unlinks a wrong
one, which tells us the real geometry. Pistons are extended by the game itself
in all six directions and the head is looked up afterwards.

    python3 tools/mc/probe_units.py

The results are recorded in docs/FORMAT-26.3.md and encoded in the unit rules.
"""

from __future__ import annotations

import shutil

from build_probe_world import expect, wait_loaded
from mcserver import MC_HOME, Server

DIRECTIONS = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0),
              "up": (0, 1, 0), "down": (0, -1, 0)}
CLOCKWISE = {"north": "east", "east": "south", "south": "west", "west": "north"}
OPPOSITE = {"north": "south", "south": "north", "west": "east", "east": "west", "up": "down", "down": "up"}
Y = 2


def add(pos, direction):
    dx, dy, dz = DIRECTIONS[direction]
    return pos[0] + dx, pos[1] + dy, pos[2] + dz


def at(pos):
    return f"{pos[0]} {pos[1]} {pos[2]}"


def survives(server, first, second):
    """Place two blocks without updates, poke their empty neighbours, report whether both remain."""
    (pos_a, state_a), (pos_b, state_b) = first, second
    expect(server, [f"setblock {at(pos_a)} {state_a} strict", f"setblock {at(pos_b)} {state_b} strict"],
           "Changed the block at")
    pokes = []
    for pos in (pos_a, pos_b):
        for direction in DIRECTIONS:
            neighbour = add(pos, direction)
            if neighbour not in (pos_a, pos_b) and neighbour[1] >= Y:
                pokes += [f"setblock {at(neighbour)} minecraft:stone", f"setblock {at(neighbour)} minecraft:air"]
    server.run(*pokes)
    server.wait_ticks(4)
    lines = server.run(f"execute if block {at(pos_a)} {state_a}", f"execute if block {at(pos_b)} {state_b}")
    return sum("Test passed" in line for line in lines) == 2


def main():
    run = MC_HOME / "runs" / "probe-units"
    if run.exists():
        shutil.rmtree(run)
    properties = {"level-type": "minecraft:flat", "generate-structures": "false",
                  "generator-settings": '{"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1}]}'}
    with Server(run, properties) as server:
        expect(server, ["forceload add 0 0 255 31"], "Marked")
        wait_loaded(server, [(cx, cz) for cx in range(16) for cz in range(2)])
        expect(server, [f"fill 0 {Y - 1} 0 255 {Y - 1} 31 minecraft:smooth_stone"], "Successfully filled")
        slot = iter((4 + 6 * n, Y, 8) for n in range(40))

        print("two-part vertical blocks (half=lower|upper):")
        for block, extra in (("oak_door", ",facing=north,hinge=left,open=false,powered=false"), ("sunflower", "")):
            for label, direction in (("upper half above the lower", "up"), ("upper half below the lower", "down")):
                ground = next(slot)
                expect(server, [f"setblock {at(add(ground, 'down'))} minecraft:grass_block"], "Changed the block at")
                lower = ground if direction == "up" else add(ground, "up")
                good = survives(server, (lower, f"minecraft:{block}[half=lower{extra}]"),
                                (add(lower, direction), f"minecraft:{block}[half=upper{extra}]"))
                print(f"   {block}: {label}: {'kept' if good else 'broken'}")

        print("beds (part=foot|head, facing):")
        for facing in CLOCKWISE:
            for label, direction in (("head at foot + facing", facing), ("head at foot - facing", OPPOSITE[facing])):
                foot = next(slot)
                good = survives(server, (foot, f"minecraft:red_bed[facing={facing},part=foot,occupied=false]"),
                                (add(foot, direction), f"minecraft:red_bed[facing={facing},part=head,occupied=false]"))
                print(f"   facing={facing}: {label}: {'kept' if good else 'broken'}")

        print("double chests (type=left|right, facing):")
        for facing in CLOCKWISE:
            for label, direction in (("right half at left + clockwise(facing)", CLOCKWISE[facing]),
                                     ("right half at left + counterclockwise(facing)", OPPOSITE[CLOCKWISE[facing]])):
                left = next(slot)
                good = survives(server, (left, f"minecraft:chest[facing={facing},type=left,waterlogged=false]"),
                                (add(left, direction), f"minecraft:chest[facing={facing},type=right,waterlogged=false]"))
                print(f"   facing={facing}: {label}: {'kept' if good else 'unlinked'}")

        print("pistons, extended by the game:")
        for facing in DIRECTIONS:
            base = add(add(next(slot), "up"), "up")
            power = add(base, "west" if facing not in ("west", "east") else "north")
            expect(server, [f"setblock {at(base)} minecraft:sticky_piston[facing={facing}]",
                            f"setblock {at(power)} minecraft:redstone_block"], "Changed the block at")
            server.wait_ticks(10)
            lines = server.run(
                f"execute if block {at(base)} minecraft:sticky_piston[facing={facing},extended=true]",
                f"execute if block {at(add(base, facing))} minecraft:piston_head[facing={facing},type=sticky,short=false]")
            print(f"   facing={facing}: base extended=true and head at base + facing with the same facing and"
                  f" type=sticky: {sum('Test passed' in line for line in lines) == 2}")


if __name__ == "__main__":
    main()
