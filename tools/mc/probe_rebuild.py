"""Check what 26.3 rebuilds when a chunk comes back without derived data.

Copies the zlib probe world, strips heightmaps, light, POI and whole sections
from chosen chunks, lets the server load and save it, then compares the result
with an untouched copy that went through the same load and save.

Light is compared in the running game, by asking the server for the light
level at a grid of positions, because the stored arrays leave out uniform
sections and do not say what the game will show. Each way of stripping light
gets its own copy of the world, so the cases cannot disturb each other.
Run build_probe_world.py first.

    python3 tools/mc/probe_rebuild.py
"""

from __future__ import annotations

import shutil

import nbtlib
import regionlib
from mcserver import MC_HOME, Server

SOURCE = MC_HOME / "runs" / "probe-zlib" / "world"
RUNS = MC_HOME / "runs"
DIM = "dimensions/minecraft/overworld"

NO_HEIGHTMAPS = (49, 46)        # Heightmaps removed; natural terrain
NO_SECTIONS = (46, 46)          # sections 19 (all stone) and 5 (terrain) removed
BARE = (49, 48)                 # optional top-level tags removed
POI_CHUNK = (46, 47)            # has workstation and bell records in the original
LIGHT_CHUNK = (47, 47)          # lit from inside by lava and fire, and from its neighbours under the platform


def index(chunk):
    return (chunk[1] & 31) * 32 + (chunk[0] & 31)


def load(world, kind="region", name="r.1.1.mca"):
    region = regionlib.Region(world / DIM / kind / name)
    return {e.index: (e.timestamp, e.nbt()) for e in region.entries()}


def fresh_copy(name):
    run = RUNS / name
    if run.exists():
        shutil.rmtree(run)
    shutil.copytree(SOURCE, run / "world")
    (run / "world" / "session.lock").unlink(missing_ok=True)
    return run


def rewrite_chunks(run, change):
    """Apply change(pos, root) to every chunk of the world; it returns True if it changed the chunk."""
    changed = 0
    for path in sorted((run / "world" / DIM / "region").glob("r.*.mca")):
        chunks = load(run / "world", name=path.name)
        for _, root in chunks.values():
            changed += bool(change((int(root["xPos"]), int(root["zPos"])), root))
        regionlib.write_region(path, chunks)
    return changed


def strip_light(root, drop_flag=True):
    if drop_flag:
        root.pop("isLightOn", None)
    kept = nbtlib.List(nbtlib.COMPOUND)
    for section in root["sections"]:
        section.pop("BlockLight", None)
        section.pop("SkyLight", None)
        if "block_states" in section:      # light-only sections disappear entirely
            kept.append(section)
    root["sections"] = kept
    return True


def zero_light(root):
    root.pop("isLightOn", None)
    for section in root["sections"]:
        for key in ("BlockLight", "SkyLight"):
            if key in section:
                section[key] = nbtlib.ByteArray(bytes(2048))
    return True


def near(pos, centre, radius):
    return abs(pos[0] - centre[0]) <= radius and abs(pos[1] - centre[1]) <= radius


# name -> change applied to each chunk
LIGHT_CASES = {
    "light arrays removed, isLightOn=1 kept":
        lambda pos, root: pos == LIGHT_CHUNK and strip_light(root, drop_flag=False),
    "isLightOn and light arrays removed":
        lambda pos, root: pos == LIGHT_CHUNK and strip_light(root),
    "isLightOn removed, light arrays kept but zeroed":
        lambda pos, root: pos == LIGHT_CHUNK and zero_light(root),
    "isLightOn and light arrays removed, on the chunk and its 8 neighbours":
        lambda pos, root: near(pos, LIGHT_CHUNK, 1) and strip_light(root),
    "isLightOn and light arrays removed, on every chunk of the world":
        lambda pos, root: strip_light(root),
}


def sample_positions():
    """A grid in LIGHT_CHUNK just under and just over the probe platform at y=239."""
    x0, z0 = LIGHT_CHUNK[0] * 16, LIGHT_CHUNK[1] * 16
    return [(x0 + dx, y, z0 + dz) for y in (238, 241) for dx in range(16) for dz in range(0, 16, 3)]


def light_levels(server, positions):
    """Ask the running game for the light level (0-15) at each position."""
    levels = {}
    for x, y, z in positions:
        commands = [f"execute positioned {x} {y} {z} if predicate {{type:\"minecraft:location_check\","
                    f"predicate:{{light:{{light:{{min:{n},max:{n}}}}}}}}}" for n in range(16)]
        lines = [line for line in server.run(*commands) if "Test passed" in line or "Test failed" in line]
        passed = [n for n, line in enumerate(lines) if "Test passed" in line]
        if len(lines) != 16 or len(passed) != 1:
            raise RuntimeError(f"could not read the light level at {x} {y} {z}: {lines}")
        levels[(x, y, z)] = passed[0]
    return levels


def run_server(run, label):
    """Load the world, freeze it so nothing but the load changes a chunk, sample light, save."""
    with Server(run) as server:
        state = [line for line in server.run("tick query") if "frozen" in line or "running" in line]
        server.run("tick freeze")
        server.wait_ticks(100)
        levels = light_levels(server, sample_positions())
        server.save()
    problems = [line for line in server.errors() if "OFFLINE" not in line and "authenticate" not in line
                and "hackers" not in line and "online-mode" not in line and "codename" not in line]
    print(f"{label}: tick state after restart: {state[0].split(']: ')[-1]}; log warnings/errors: {len(problems)}")
    for line in problems[:20]:
        print("   ", line)
    return levels


def light_summary(root):
    block = sum("BlockLight" in s for s in root["sections"])
    sky = sum("SkyLight" in s for s in root["sections"])
    return f"isLightOn={root.get('isLightOn')}, BlockLight in {block} sections, SkyLight in {sky} sections"


def main():
    control = fresh_copy("probe-rebuild-control")
    control_light = run_server(control, "control")
    original = load(control / "world")

    # ---- everything except light, in one world ---------------------------------------------
    run = fresh_copy("probe-rebuild")

    def strip_other(pos, root):
        if pos == NO_HEIGHTMAPS:
            root.pop("Heightmaps")
        elif pos == NO_SECTIONS:
            root["sections"] = nbtlib.List(nbtlib.COMPOUND, (s for s in root["sections"] if int(s["Y"]) not in (19, 5)))
        elif pos == BARE:
            for key in ("block_ticks", "fluid_ticks", "PostProcessing", "structures", "block_entities",
                        "Heightmaps", "InhabitedTime", "LastUpdate", "isLightOn"):
                root.pop(key, None)
        else:
            return False
        return True

    rewrite_chunks(run, strip_other)
    (run / "world" / DIM / "poi" / "r.1.1.mca").unlink()
    run_server(run, "stripped")
    after = load(run / "world")

    def pair(chunk):
        return original[index(chunk)][1], after[index(chunk)][1]

    before, now = pair(NO_HEIGHTMAPS)
    print("\nHeightmaps removed:")
    print("   types now:", sorted(now.get("Heightmaps", {})))
    print("   identical to the control:", now.get("Heightmaps") == before["Heightmaps"])

    before, now = pair(NO_SECTIONS)
    print("\nsections 19 and 5 removed:")
    print("   section Ys now:", [int(s["Y"]) for s in now["sections"]])
    for y in (19, 5):
        section = next((s for s in now["sections"] if int(s["Y"]) == y), None)
        old = next(s for s in before["sections"] if int(s["Y"]) == y)
        if section is None:
            print(f"   section {y}: still absent")
            continue
        print(f"   section {y}: block palette {nbtlib.snbt(section['block_states']['palette'])[:80]}"
              f" (was {len(old['block_states']['palette'])} entries);"
              f" biomes {nbtlib.snbt(section['biomes']['palette'])} (was {nbtlib.snbt(old['biomes']['palette'])})")

    before, now = pair(BARE)
    print("\noptional top-level tags removed:")
    print("   keys now:", sorted(now))
    print("   keys in the control but not now:", sorted(set(before) - set(now)))

    poi_path = run / "world" / DIM / "poi" / "r.1.1.mca"
    print("\npoi/r.1.1.mca deleted:")
    if not poi_path.exists():
        print("   not recreated")
    else:
        old = {i: root for i, (_, root) in load(control / "world", "poi").items()}
        new = {i: root for i, (_, root) in load(run / "world", "poi").items()}

        def records(root):
            return sorted((tuple(r["pos"]), str(r["type"])) for s in root["Sections"].values() for r in s["Records"])
        same = sum(1 for i in old if i in new and records(old[i]) == records(new[i]))
        print(f"   chunks with records: control {len(old)}, now {len(new)}; identical records in {same}")
        i = index(POI_CHUNK)
        print(f"   chunk {POI_CHUNK}: control {len(records(old[i]))} records, now "
              f"{len(records(new[i])) if i in new else 'absent'}")

    # ---- light, one world per case ---------------------------------------------------------
    print(f"\nlight in chunk {LIGHT_CHUNK}, {len(control_light)} sampled positions, compared with the control in game:")
    print("   control file:", light_summary(original[index(LIGHT_CHUNK)][1]))
    for number, (name, change) in enumerate(LIGHT_CASES.items(), start=1):
        run = fresh_copy(f"probe-rebuild-light-{number}")
        changed = rewrite_chunks(run, change)
        first = run_server(run, f"light case {number}, first load")
        second = run_server(run, f"light case {number}, second load")
        now = load(run / "world")[index(LIGHT_CHUNK)][1]
        wrong = {pos: (control_light[pos], level) for pos, level in first.items() if level != control_light[pos]}
        print(f"{number}. {name} ({changed} chunks changed)")
        print(f"   file after the load: {light_summary(now)}")
        print(f"   {len(first) - len(wrong)} of {len(first)} positions match the control;"
              f" the second load gives the same answers: {first == second}")
        if wrong:
            from_border = [min(x & 15, 15 - (x & 15), z & 15, 15 - (z & 15)) for x, _, z in wrong]
            print(f"   wrong positions are {min(from_border)} to {max(from_border)} blocks from the chunk border;"
                  f" examples (control, now): {list(wrong.items())[:3]}")


if __name__ == "__main__":
    main()
