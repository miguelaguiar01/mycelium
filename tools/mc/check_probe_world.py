"""Check every format claim in docs/FORMAT-26.3.md against the probe worlds.

Reads the worlds built by build_probe_world.py with the independent Python
reader in nbtlib.py and regionlib.py, asserts each claim, and prints the
evidence. A failed assertion means the note is wrong for that world.

    python3 tools/mc/check_probe_world.py [run directory ...]

With no arguments it checks .mc/runs/probe-{zlib,lz4,none,oversized-zlib}.
"""

from __future__ import annotations

import collections
import json
import sys
from pathlib import Path

import nbtlib
import regionlib
from mcserver import MC_HOME

DATA_VERSION = 5023
REPORTS = MC_HOME / "server" / "26.3" / "datagen" / "generated" / "reports"
DIM = "dimensions/minecraft/overworld"
MASK64 = (1 << 64) - 1


def unwrap(entry):
    """Undo the {"": value} wrapper that mixed-type lists put around non-compound elements."""
    if entry.tag == nbtlib.COMPOUND and list(entry) == [""]:
        return entry[""], True
    return entry, False


def state_string(entry) -> str:
    entry, _ = unwrap(entry)
    if entry.tag == nbtlib.STRING:
        return str(entry)
    properties = entry.get("properties", {})
    if not properties:
        return str(entry["id"])
    return str(entry["id"]) + "[" + ",".join(f"{k}={properties[k]}" for k in sorted(properties)) + "]"


def unpack(container, cells: int, min_bits: int, counts: collections.Counter, kind: str) -> list[int]:
    """Decode a paletted container, asserting the packing rules on the way."""
    size = len(container["palette"])
    if size == 1:
        assert "data" not in container, f"{kind}: single-entry palette with a data array"
        counts[f"{kind}: single entry, no data array"] += 1
        return [0] * cells
    bits = max(min_bits, (size - 1).bit_length())
    per_long = 64 // bits
    data = container["data"]
    assert len(data) == -(-cells // per_long), f"{kind}: {len(data)} longs for {size} entries"
    values = []
    for packed in data:
        packed &= MASK64
        for n in range(per_long):
            values.append(packed >> (n * bits) & ((1 << bits) - 1))
        assert packed >> (per_long * bits) == 0, f"{kind}: padding bits are set"
    assert not any(values[cells:]), f"{kind}: unused trailing cells are set"
    values = values[:cells]
    assert max(values) < size, f"{kind}: index outside the palette"
    first_use = list(dict.fromkeys(values))
    assert first_use == list(range(size)), f"{kind}: palette is not in first-use order or has unused entries"
    counts[f"{kind}: {bits} bits, {per_long} per long, {len(data)} longs"] += 1
    return values


def default_states() -> dict[str, dict[str, str]]:
    blocks = json.loads((REPORTS / "blocks.json").read_text())
    return {name: next(s for s in block["states"] if s.get("default")).get("properties", {})
            for name, block in blocks.items()}


def check_world(run: Path, defaults) -> None:
    world = run / "world"
    print(f"\n=== {run.name} ===")
    level = nbtlib.load_file(world / "level.dat")[1]["Data"]
    assert int(level["DataVersion"]) == DATA_VERSION
    print(f"level.dat DataVersion {int(level['DataVersion'])}, Version.Name {level['Version']['Name']}")

    regions = sorted((world / DIM / "region").glob("r.*.mca"))
    counts: collections.Counter = collections.Counter()
    compression: collections.Counter = collections.Counter()
    top_level: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    section_keys: collections.Counter = collections.Counter()
    chunks = {}
    for path in regions:
        region = regionlib.Region(path)
        assert region.size % regionlib.SECTOR == 0
        for entry in region.entries():
            compression[entry.compression_byte] += 1
            if entry.external:
                x, z = region.rx * 32 + entry.local[0], region.rz * 32 + entry.local[1]
                assert entry.external.name == f"c.{x}.{z}.mcc"
                assert entry.length == 1 and entry.sectors == 1
                print(f"external chunk: {entry.external.name}, {len(entry.payload)} bytes, header length field 1,"
                      f" compression byte {entry.compression_byte:#x}")
            root = entry.nbt()
            assert int(root["DataVersion"]) == DATA_VERSION
            assert (int(root["xPos"]) & 31, int(root["zPos"]) & 31) == entry.local
            chunks[(int(root["xPos"]), int(root["zPos"]))] = root
            status = str(root["Status"])
            for key, value in root.items():
                top_level[status][f"{key}: {nbtlib.shape(value).split('[')[0]}"] += 1
    print(f"region files: {len(regions)}, chunks: {len(chunks)}")
    print("compression bytes:", {f"{k:#x}": v for k, v in sorted(compression.items())})
    for kind in ("entities", "poi"):
        seen = collections.Counter(e.compression_byte for p in (world / DIM / kind).glob("r.*.mca")
                                   for e in regionlib.Region(p).entries())
        print(f"{kind} compression bytes:", {f"{k:#x}": v for k, v in sorted(seen.items())})

    statuses = collections.Counter(str(c["Status"]) for c in chunks.values())
    print("chunk statuses:", dict(statuses))
    for status in sorted(top_level):
        print(f"top-level tags, {status} ({statuses[status]} chunks):")
        for key, n in sorted(top_level[status].items()):
            print(f"    {key}" + ("" if n == statuses[status] else f"   ({n} chunks)"))

    empty_lists: collections.Counter = collections.Counter()

    def walk(value):
        if value.tag == nbtlib.LIST:
            if not value:
                empty_lists[nbtlib.TYPE_NAMES[value.element]] += 1
            for item in value:
                walk(item)
        elif value.tag == nbtlib.COMPOUND:
            for item in value.values():
                walk(item)

    for root in chunks.values():
        walk(root)
        min_section = int(root["yPos"])
        ys = [int(s["Y"]) for s in root["sections"]]
        assert ys == sorted(ys)
        with_blocks = [int(s["Y"]) for s in root["sections"] if "block_states" in s]
        assert with_blocks == list(range(min_section, min_section + len(with_blocks))), "sections are not contiguous"
        for section in root["sections"]:
            section_keys[", ".join(sorted(section))] += 1
            if "block_states" not in section:
                assert set(section) <= {"Y", "SkyLight", "BlockLight"}
                continue
            palette = section["block_states"]["palette"]
            assert set(section["block_states"]) <= {"palette", "data"}
            forms = set()
            for raw in palette:
                entry, wrapped = unwrap(raw)
                if entry.tag == nbtlib.STRING:
                    forms.add("wrapped string" if wrapped else "string")
                else:
                    assert not wrapped and set(entry) == {"id", "properties"}
                    forms.add("compound")
                    properties = {k: str(v) for k, v in entry["properties"].items()}
                    assert all(v.tag == nbtlib.STRING for v in entry["properties"].values())
                    name = str(entry["id"])
                    assert properties != defaults[name], f"default state of {name} written in compound form"
                    assert set(properties) == set(defaults[name]), f"{name} written with a partial property set"
            if palette.element == nbtlib.STRING:
                assert forms == {"string"}
                counts["block palette: list of strings (every entry a default state)"] += 1
            else:
                assert palette.element == nbtlib.COMPOUND and forms <= {"compound", "wrapped string"}
                counts["block palette: list of compounds (" + " + ".join(sorted(forms)) + ")"] += 1
            unpack(section["block_states"], 4096, 4, counts, "blocks")
            biomes = section["biomes"]
            assert biomes["palette"].element == nbtlib.STRING and set(biomes) <= {"palette", "data"}
            unpack(biomes, 64, 1, counts, "biomes")
        for name, heightmap in root["Heightmaps"].items():
            assert heightmap.tag == nbtlib.LONG_ARRAY and len(heightmap) == 37
        assert len(root["PostProcessing"]) == len(with_blocks)
        for tick in list(root["block_ticks"]) + list(root["fluid_ticks"]):
            assert {k: v.tag for k, v in tick.items()} == {
                "i": nbtlib.STRING, "p": nbtlib.INT, "t": nbtlib.INT,
                "x": nbtlib.INT, "y": nbtlib.INT, "z": nbtlib.INT}
            assert (int(tick["x"]) >> 4, int(tick["z"]) >> 4) == (int(root["xPos"]), int(root["zPos"]))
        for block_entity in root["block_entities"]:
            assert {"id", "x", "y", "z"} <= set(block_entity)
            assert (int(block_entity["x"]) >> 4, int(block_entity["z"]) >> 4) == (int(root["xPos"]), int(root["zPos"]))
            for item in block_entity.get("Items", []):
                assert item["Slot"].tag == nbtlib.BYTE and item["count"].tag == nbtlib.INT and item["id"].tag == nbtlib.STRING

    print("section tag sets:")
    for keys, n in section_keys.most_common():
        print(f"    {n:6d}  {keys}")
    print("palette and packing (every section checked):")
    for key, n in sorted(counts.items()):
        print(f"    {n:6d}  {key}")
    print("empty lists by declared element type:", dict(empty_lists))

    expected_path = run / "expected.json"
    if not expected_path.exists():
        return
    expected = json.loads(expected_path.read_text())

    def canonical(state: str) -> str:
        name, _, rest = state.partition("[")
        properties = dict(pair.split("=") for pair in rest.rstrip("]").split(",")) if rest else {}
        return name if properties == defaults[name] else state

    for wanted in expected["blockSections"]:
        section = next(s for s in chunks[tuple(wanted["chunk"])]["sections"] if int(s["Y"]) == wanted["sectionY"])
        palette = [state_string(e) for e in section["block_states"]["palette"]]
        values = unpack(section["block_states"], 4096, 4, collections.Counter(), "blocks")
        assert [palette[v] for v in values] == [canonical(wanted["states"][v]) for v in wanted["cells"]], \
            f"block section {wanted['chunk']} y={wanted['sectionY']} does not match what was placed"
    for wanted in expected["biomeSections"]:
        section = next(s for s in chunks[tuple(wanted["chunk"])]["sections"] if int(s["Y"]) == wanted["sectionY"])
        palette = [str(e) for e in section["biomes"]["palette"]]
        values = unpack(section["biomes"], 64, 1, collections.Counter(), "biomes")
        assert [palette[v] for v in values] == [wanted["biomes"][v] for v in wanted["cells"]], \
            f"biome section {wanted['chunk']} y={wanted['sectionY']} does not match what was placed"
    print(f"expected.json: {len(expected['blockSections'])} block sections"
          f" ({sorted(len(s['states']) for s in expected['blockSections'])} states) and"
          f" {len(expected['biomeSections'])} biome sections"
          f" ({sorted(len(s['biomes']) for s in expected['biomeSections'])} biomes) decode to exactly what was placed,"
          " with block index y*256+z*16+x and biome cell index y*16+z*4+x")

    found = {}

    def collect(entity, vehicle):
        digits = "".join(f"{v & 0xFFFFFFFF:08x}" for v in entity["UUID"])
        uuid = f"{digits[:8]}-{digits[8:12]}-{digits[12:16]}-{digits[16:20]}-{digits[20:]}"
        found[uuid] = (str(entity["id"]), vehicle)
        assert entity["UUID"].tag == nbtlib.INT_ARRAY and len(entity["UUID"]) == 4
        assert entity["Pos"].element == nbtlib.DOUBLE and len(entity["Pos"]) == 3
        for rider in entity.get("Passengers", []):
            collect(rider, uuid)

    for path in sorted((world / DIM / "entities").glob("r.*.mca")):
        for entry in regionlib.Region(path).entries():
            root = entry.nbt()
            assert set(root) == {"DataVersion", "Position", "Entities"}
            assert root["Position"].tag == nbtlib.INT_ARRAY and len(root["Position"]) == 2
            for entity in root["Entities"]:
                assert (int(float(entity["Pos"][0]) // 16), int(float(entity["Pos"][2]) // 16)) == tuple(root["Position"])
                collect(entity, None)
    for uuid, wanted in expected["entities"].items():
        assert uuid in found and found[uuid][0] == wanted["id"], f"entity {uuid} ({wanted['id']}) not found"
        assert found[uuid][1] == wanted["vehicle"], f"entity {uuid} rides {found[uuid][1]}, expected {wanted['vehicle']}"
    riders = {uuid: vehicle for uuid, (_, vehicle) in found.items() if vehicle}
    print(f"entities: all {len(expected['entities'])} summoned entities found by UUID; riders nested under"
          f" their vehicle's Passengers list: {len(riders)}")


def main() -> None:
    runs = [Path(arg) for arg in sys.argv[1:]] or [
        MC_HOME / "runs" / f"probe-{name}" for name in ("zlib", "lz4", "none", "oversized-zlib")]
    defaults = default_states()
    for run in runs:
        check_world(run, defaults)
    print("\nall checks passed")


if __name__ == "__main__":
    main()
