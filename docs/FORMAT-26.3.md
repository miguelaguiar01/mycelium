# Minecraft 26.3 world format — verification note

Nov 12, 2026 · Evidence for the format layer in [CHUNK-MERGE-SPEC.md](../CHUNK-MERGE-SPEC.md)

Everything here was read from worlds written by the 26.3 dedicated server, not from memory or older documentation. Where a fact comes from the server jar instead of a file on disk, it says so. The section on player files and maps was read from worlds saved by the 26.3 client, added on Nov 14, 2026.

## How this was checked

| What | Where |
| --- | --- |
| Server | `server.jar` from Mojang's version manifest, SHA-1 `33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`, run on Temurin 25.0.5 |
| Probe worlds | Built by `tools/mc/build_probe_world.py`, seed `mycelium`, by scripting console commands (`forceload`, `setblock … strict`, `fill`, `fillbiome`, `item replace`, `summon`, `tick freeze`) |
| `probe-zlib` | Natural terrain, default compression. 12 forced chunks at chunk 46..49 × 46..48, with their generation rings: 870 chunks in `r.1.1` |
| `probe-lz4`, `probe-none` | Flat worlds written with `region-file-compression=lz4` and `=none`. `probe-none` holds one chunk too large for a region file |
| `probe-oversized-zlib` | A zlib chunk too large for a region file. Not kept as a fixture (2 MB of noise) |
| Known values | `expected.json` in each probe world lists every block state, biome and entity the script placed |
| Checks | `tools/mc/check_probe_world.py` asserts each claim below on every chunk of every probe world, using a Python reader that shares no code with the Java codec. It passes on all four worlds |
| Rebuild tests | `tools/mc/probe_rebuild.py` strips data from a copy of `probe-zlib`, loads it in the server and compares with a control copy |
| Fixtures | `merge/src/test/resources/fixtures/probe-{zlib,lz4,none}`, copied by `tools/mc/install_fixtures.py` |
| Play session | Three flat singleplayer worlds played by hand in the 26.3 client, following the steps in `docs/SCENARIOS.md`. Cut down to the fixture `play-session` by `tools/mc/install_play_fixture.py` |

To reproduce: `python3 tools/mc/build_probe_world.py && python3 tools/mc/check_probe_world.py && python3 tools/mc/probe_rebuild.py`.

The Java format layer in `merge/` is checked against the same worlds a second time: `./gradlew :merge:test` reads the fixtures, and `python3 tools/mc/check_rewritten_world.py` passes each fixture world through the Java codecs and loads the result in the game.

## Checklist

| Item | Result |
| --- | --- |
| Top-level chunk tag names and types | Confirmed, table below. Differs by generation status |
| Section tags, palettes, bit widths, single-entry case | Confirmed. **Block palette entries are not `{Name, Properties}`**; see Block states |
| Whether packed values span two longs | They never do. Unused high bits of each long are zero |
| Compression types and external chunk files | zlib (2), none (3) and LZ4 (4) seen on disk. gzip (1) is read-only in the game. External files confirmed |
| Entity file layout and rider nesting | Confirmed |
| Block entity layout and container items | Confirmed |
| What the game rebuilds when absent | Heightmaps and POI: rebuilt exactly. **Light: only partly**; see Rebuilt when absent |
| Data version for 26.3 | **5023** |

## World folder layout

26.3 does not use the layout the spec assumes. Observed:

```
level.dat
data/minecraft/game_rules.dat, weather.dat, world_clocks.dat, world_gen_settings.dat,
               scoreboard.dat, random_sequences.dat, scheduled_events.dat,
               stopwatches.dat, custom_boss_events.dat
dimensions/minecraft/overworld/region/r.X.Z.mca
dimensions/minecraft/overworld/entities/r.X.Z.mca
dimensions/minecraft/overworld/poi/r.X.Z.mca
dimensions/minecraft/overworld/data/minecraft/chunk_tickets.dat, raids.dat, world_border.dat
dimensions/minecraft/the_nether/…        same shape
dimensions/minecraft/the_end/…           same shape, plus data/minecraft/ender_dragon_fight.dat
players/data/<uuid>.dat, <uuid>.dat_old
players/advancements/<uuid>.json
players/stats/<uuid>.json
data/minecraft/maps/<id>.dat, last_id.dat
```

- Every dimension, the overworld included, lives under `dimensions/<namespace>/<path>/`.
- `level.dat` is small. It holds `DataVersion`, `Version`, `Time`, `spawn {pos, dimension, yaw, pitch}`, `difficulty_settings`, `GameType`, `LevelName`, `DataPacks` and a few flags. It holds **no game rules, weather, day time or world-gen settings**; those are separate files under `data/minecraft/`.
- Raids and the world border are per dimension.
- Standalone `.dat` files are gzip-compressed NBT with an empty root name. Each is `{DataVersion, data: {…}}`, except `level.dat`, which is `{Data: {…}}`.
- The last four lines are from a world saved by the 26.3 client on Nov 14, 2026; see Player files and maps. The scripted worlds have no player and no maps.

## Region files

- 8 KiB header: 1,024 big-endian location entries (`sector << 8 | sectorCount`), then 1,024 timestamps in epoch seconds. Entry index is `(z & 31) * 32 + (x & 31)`. Sectors are 4,096 bytes; the file length is a multiple of that.
- Each chunk starts with a 4-byte big-endian length (compression byte plus payload) and a 1-byte compression type.
- Compression types (from the jar's `RegionFileVersion`, ids confirmed on disk for 2, 3, 4):

| Id | Type | Written by 26.3 |
| --- | --- | --- |
| 1 | gzip | No. It has no `region-file-compression` name; read-only |
| 2 | zlib (`deflate`) | Yes, the default |
| 3 | none | Yes |
| 4 | LZ4 | Yes |
| 127 | custom, named by a string | Only by mods |

- LZ4 is lz4-java's block-stream framing, not the LZ4 frame format: repeated blocks of `"LZ4Block"`, a token byte, little-endian compressed length, original length and checksum, then data. Observed token `0x26`, 64 KiB blocks, and an empty end block. The game bundles `at.yawk.lz4:lz4-java:1.10.1`.
- The same setting applies to `entities/` and `poi/` region files.
- **External chunks.** A chunk that would need 256 or more sectors (jar: `EXTERNAL_CHUNK_THRESHOLD = 256`) is written to `c.<chunkX>.<chunkZ>.mcc` next to the region file, with absolute chunk coordinates. The region file keeps a one-sector entry whose length field is 1 and whose compression byte has bit `0x80` set (`0x82`, `0x83` seen). The `.mcc` file is the bare compressed stream with no header. Bytes after the 5-byte stub in the sector are leftovers and mean nothing.
- Region header timestamps differ between chunks of one file, so they carry real per-chunk save times.
- **The file is padded to whole sectors only when the game closes it.** A region file whose last chunk stops right after its payload, as in a world copied while the game runs or after a crash, was loaded by the server: the block placed in that chunk was there and the log was clean. The game padded the file when it closed it.
- **The game holds a lock on `session.lock`** for as long as it has the world open. Another process that tries to take the lock is refused; seen with a running server.
- Error cases the game itself distinguishes (jar strings): truncated header, sector overlapping the header, sector count 0, sector out of bounds, truncated chunk header, truncated stream, negative length, unknown compression id, missing external file, and a chunk with both an internal and an external stream.

## Chunk root

Types as stored. `full` chunks are what players see; every other status is a partly generated chunk.

| Tag | Type | Notes |
| --- | --- | --- |
| `DataVersion` | int | 5023 |
| `xPos`, `zPos` | int | Chunk coordinates |
| `yPos` | int | Lowest section: −4 in the overworld, 0 in the Nether and End |
| `Status` | string | `minecraft:full`, or `structure_starts`, `biomes`, `terrain`, `initialize_light` for partly generated chunks |
| `LastUpdate`, `InhabitedTime` | long | Game ticks |
| `sections` | list of compounds | See Sections |
| `block_entities` | list of compounds | See Block entities |
| `block_ticks`, `fluid_ticks` | list of compounds | `{i: string id, p: int priority, t: int delay in ticks, x, y, z: int}` with absolute coordinates |
| `Heightmaps` | compound of long[37] | 9 bits per column, 7 per long, value = height − `yPos*16`. `full`: `MOTION_BLOCKING`, `MOTION_BLOCKING_NO_LEAVES`, `OCEAN_FLOOR`, `WORLD_SURFACE`. Partly generated: also `OCEAN_FLOOR_WG`, `WORLD_SURFACE_WG`, or empty |
| `isLightOn` | byte | **Only in `full` chunks** |
| `structures` | compound | `{starts: {<id>: {…}}, References: {<id>: long[]}}` |
| `PostProcessing` | list of lists of shorts | One inner list per section |
| `entities` | list | **Only in partly generated chunks**; entities of full chunks are in `entities/*.mca` |

**Most chunks on disk are not `full`.** In `probe-zlib`, region `r.1.1` holds 870 chunks for 12 forced ones: 56 `full`, 34 `initialize_light`, 42 `terrain`, 50 `biomes` and 688 `structure_starts`. Every explored area is surrounded by these rings.

**Empty lists** are always written with element type `end` (0), including `block_entities` and `fluid_ticks`. All 38,997 empty lists in the `probe-zlib` world's chunks are typed this way.

## Sections

- `sections` holds one entry per section in the dimension's range, in ascending `Y` (a byte), including sections that are all air: 24 in the overworld (−4..19), 16 in the Nether and End (0..15).
- A `full` chunk can also hold one **light-only section** above the top (`Y` = 20 in the overworld) with just `Y` and `SkyLight`. No section below the bottom was seen.
- A normal section has `Y`, `block_states`, `biomes`, and optionally `BlockLight` and `SkyLight` (byte[2048] each, 4 bits per block). Light arrays are present only where they are not trivially uniform, and can also be present in `initialize_light` chunks.
- `block_states` and `biomes` are `{palette, data}`. With a single palette entry there is no `data`.

**Packing.** Confirmed on every section of every probe world, and against known values for palettes of 1, 2, 16, 17, 33, 65, 129, 257, 513, 1,025, 2,049 and 4,096 states and 1, 2, 3, 5, 9, 17, 33 and 64 biomes:

| | Blocks | Biomes |
| --- | --- | --- |
| Cells | 4,096; index `y*256 + z*16 + x` | 64 cells of 4×4×4 blocks; index `y*16 + z*4 + x` |
| Bits per cell | `max(4, ceil(log2(n)))`: 4 to 12 seen | `ceil(log2(n))`: 1 to 6 seen |
| Cells per long | `floor(64 / bits)`, lowest bits first | Same |
| Longs | `ceil(cells / cellsPerLong)` | Same |

- Values never span two longs. The unused high bits of each long, and unused cells in the last long, are zero.
- The palette always stays a list, even at 4,096 entries. There is no "global palette" form on disk.
- **The game's own output is canonical.** In every section, the palette lists exactly the states in use, ordered by first use in index order. So encoding with that rule reproduces the game's bytes for unchanged sections.

## Block states

This is the largest difference from older versions and from the spec.

A palette entry is one of:

| Form on disk | Meaning |
| --- | --- |
| A bare string, `"minecraft:chest"` | The block's **default state**, whatever its properties are |
| `{id: "minecraft:oak_sign", properties: {rotation: "4", waterlogged: "false"}}` | A non-default state, with **all** of its properties, as strings |

- If every entry is a default state, the palette is a list of strings. In the `probe-zlib` world that is 33,133 of 33,576 block palettes.
- Otherwise the palette is a list of compounds, and each string entry is wrapped as `{"": "minecraft:air"}`. This wrapper is 26.3's general NBT rule for lists of mixed types (jar: `ListTag.wrapIfNeeded`, `tryUnwrap`): non-compound elements, and compounds that look like a wrapper, are wrapped in a compound with the single key `""`, and unwrapped on read.
- The game never writes a default state in compound form, and never writes a partial property set. Checked against the block report from the jar's data generator (1,286 blocks, 35,723 states) for every compound-form entry in the probe worlds.
- Property order inside `properties` is arbitrary (hash order).
- The same two forms are used wherever a block state is stored, such as a block display's `block_state`.

**Consequence.** `"minecraft:chest"` and a chest with `facing=north,type=single,waterlogged=false` are the same state, but the file only shows that through the block's default. The game always writes the short form for it, so two game-written palettes never disagree. Expanding a default state into its properties needs a table of default states per block.

This matters as soon as code looks at a property. In the fixture, the lower half of a closed north-facing door is stored as `"minecraft:oak_door"` and the foot of a north-facing bed as `"minecraft:red_bed"`, with no `half` or `part` to read; their other halves carry full property lists.

## Biomes

Biome palette entries are plain strings. A section that the game has to create because it is missing from the file comes back as all air with biome `minecraft:plains`, not with the biome of its neighbours (rebuild test: the removed sections were `minecraft:ocean`). The game itself never omits a section.

## Block entities

- One compound per block entity in the chunk's `block_entities`: `id`, absolute `x`, `y`, `z`, `components`, `keepPacked`, plus type fields. There is no `bed` block entity type in 26.3.
- In partly generated chunks, block entities have no `keepPacked`, and some are placeholders with id `DUMMY` and only a position.
- **Container items**: `Items` is a list of `{Slot: byte, id: string, count: int, components?: {…}}`. Seen on chest, trapped chest, barrel, shulker box, hopper, dropper, dispenser, crafter, shelf, chiseled bookshelf, campfire, furnace, blast furnace, smoker and brewing stand.
- Nested containers use the item component `minecraft:container`: a list of `{slot: int, item: {id, count, components?}}`. Bundles use `minecraft:bundle_contents`, a list of items.
- An unopened loot container has `LootTable` and `LootTableSeed` and no `Items`.
- Single-item holders: jukebox `RecordItem`, lectern `Book`, decorated pot `item`.
- Furnace family progress: `lit_time_remaining`, `lit_total_time`, `cooking_time_spent`, `cooking_total_time`, `speed_multiplier`, `RecipesUsed`. Brewing stand: `BrewTime`, `Fuel`, `total_brew_time`, `total_fuel`, `speed_multiplier`. Campfire: `CookingTimes`, `CookingTotalTimes`. Hopper: `TransferCooldown`. Crafter: `crafting_ticks_remaining`, `triggered`, `disabled_slots`.
- A double chest is two independent block entities; only the block states (`type=left|right`) link them.

## Player files and maps

Seen in three singleplayer worlds saved by the 26.3 client, the play session of `docs/SCENARIOS.md`. The server console cannot create a player, so nothing here is checked by `check_probe_world.py`; the merge module's `play-session` fixture holds the files.

- `players/data/<uuid>.dat` is gzip NBT with an unnamed root compound. It holds, among others, `Pos`, `Dimension`, `Inventory`, `EnderItems`, `equipment`, `abilities` and `recipeBook: {recipes, toBeDisplayed}`, two lists of recipe ids. `<uuid>.dat_old` is the game's copy of the file before the last save.
- **A player who leaves while riding takes the vehicle along.** The file then has `RootVehicle: {Attach: int[4], Entity: {…}}`. `Entity` is the complete vehicle, with any other riders in its `Passengers`, and `Attach` is the UUID of the entity the player sits on. The vehicle is no longer in the entity files.
- **A parrot on a shoulder** is in the player's file too, as `ShoulderEntityLeft` or `ShoulderEntityRight`. These two names are constants of `ServerPlayer` in the 26.3 jar; no world with a parrot on a shoulder was looked at.
- `level.dat` of a singleplayer world holds no copy of the player. It has `singleplayer_uuid: int[4]`, the host's UUID.
- `players/stats/<uuid>.json` is `{"DataVersion": 5023, "stats": {"minecraft:custom": {"minecraft:play_time": 2730, …}, "minecraft:used": {…}, …}}`. Every number seen only went up. `time_since_death` and `time_since_rest` are known to restart at zero on death and after sleeping; the player of these worlds did neither, so that was not seen.
- `players/advancements/<uuid>.json` is `{"<advancement id>": {"criteria": {"<name>": "<date>"}, "done": bool}, …, "DataVersion": 5023}`. Unlocked recipes are advancements too, under `minecraft:recipes/`.
- A map is `data/minecraft/maps/<id>.dat`: `{DataVersion, data: {xCenter, zCenter, dimension, colors: byte[16384], …}}`. The file does not hold its own id. `maps/last_id.dat` is `{DataVersion, data: {map: <last id given out>}}`.
- A map item is `minecraft:filled_map` with the component `minecraft:map_id`. Two copies of a world both give their next map the id after `last_id`.

## Entity files

- World generation gives its entities random UUIDs. The same 64 chunks, generated twice from the same seed by two servers, held the same 5 chickens, 5 chest minecarts and 14 falling blocks, with no UUID in common.
- `entities/r.X.Z.mca` uses the region format. Each chunk is `{DataVersion: int, Position: int[2] (chunk x, z), Entities: list}`. Chunks with no entities have no entry.
- Each entity has `id`, `UUID` (int[4]), `Pos` (3 doubles) and its own fields. It is stored in the chunk that contains `Pos`.
- **Riders nest.** A vehicle carries `Passengers`, a list of complete entity compounds, each with its own `id`, `UUID` and `Pos`, and possibly its own `Passengers`. Seen three deep (pig, zombie, chicken) and two riders on one boat. A rider is saved with its vehicle, in the vehicle's chunk.
- Leashes: `leash: {UUID: int[4]}` when held by an entity, `leash: int[3]` (block position) when tied to a fence. The knot on the fence is not saved as an entity.
- Owner references: `Owner: int[4]`.
- Item-bearing fields seen: `Item` (item frame, dropped item), `equipment: {mainhand, head, chest, …}` (mobs, armour stands), `Items` (chest and hopper minecarts, donkey), `Inventory` (villager).
- Hanging entities (item frame, painting) carry `block_pos: int[3]`.

## Multi-block shapes

Checked in the game by `tools/mc/probe_units.py`. Both candidate layouts of each shape were placed without block updates and every empty neighbour was then poked; the game keeps a correctly linked pair and breaks or unlinks a wrong one. Pistons were extended by the game itself in all six directions.

| Shape | Where the other half is |
| --- | --- |
| Door, tall plant (`half=lower|upper`) | The upper half is directly above the lower half |
| Bed (`part=foot|head`, `facing`) | The head is one step from the foot in the direction of `facing` |
| Double chest (`type=left|right`, `facing`) | The right half is one step clockwise from `facing`, seen from the left half: east of it for a chest facing north |
| Extended piston (`extended=true`, `facing`) | The `piston_head` is one step along `facing` and has the same `facing` |

These are the only blocks in the 26.3 block report whose state names a partner block: 22 doors, 9 two-part plants, 17 beds, 10 chests, and the two pistons with their head. A pitcher crop has `half` too, but a young one is a single block, so it is not counted.

Many more blocks store how they connect to their neighbours: fences, walls, panes, stairs (`shape`), redstone wire, rails, vines, shelves (`side_chain`), dripstone (`thickness`), and `snowy` ground. Those are not links to one partner; the game derives them from whatever is next to the block.

## Post-processing list

Checked in the game by `tools/mc/probe_postprocessing.py`. Fences were placed next to stone with every connection off, which the game never writes itself, and some of them were then listed in the chunk's `PostProcessing`.

- A stale fence that is not listed stays stale across reloads.
- A listed fence is refreshed when the finished chunk loads and starts ticking: its connection to the stone is turned on. This also works in another section of the same chunk.
- A position is packed as `x | y << 4 | z << 8`, with the block's coordinates inside its section. The list index is the section's `Y` minus `yPos`. Fences at the swapped coordinates were left alone, which confirms the packing.
- The game empties the list once it has processed it.

## POI files

`poi/r.X.Z.mca`: `{DataVersion, Sections: {"<sectionY>": {Valid: byte, Records: [{pos: int[3], type: string, free_tickets?: int}]}}}`. Only chunks with records have an entry.

## Rebuilt when absent

Tested by stripping data from copies of `probe-zlib` and loading them (`tools/mc/probe_rebuild.py`). The control is an untouched copy that went through the same load and save. The log showed no warnings or errors in any run.

| Removed | What 26.3 does |
| --- | --- |
| `Heightmaps` | Rebuilt on load. Identical to the control |
| `poi/r.1.1.mca` (whole file) | Rebuilt from block states when chunks load. All 13 chunks got identical records back |
| `block_ticks`, `fluid_ticks`, `PostProcessing`, `structures`, `block_entities`, `InhabitedTime`, `LastUpdate` | Accepted when absent; written back as empty or zero |
| Whole sections | Recreated as air with biome `minecraft:plains` |

**Light is not rebuilt cleanly.** Each case below was run on its own copy of the world. Light was read in the running game at 192 positions of chunk (47, 47), just under and just over the probe platform, and compared with the control. A second load of each world gave the same answers as the first.

| Change | Positions matching the control |
| --- | --- |
| Light arrays removed, `isLightOn` left at 1 | 74 of 192. The game trusts the flag and treats the missing light as dark |
| `isLightOn` and all light arrays removed | 170 of 192. Every miss is within 3 blocks of the chunk border |
| `isLightOn` removed, light arrays kept but zeroed | 170 of 192, the same misses. Stale arrays are not trusted |
| `isLightOn` and all light arrays removed, on the chunk and its 8 neighbours | 192 of 192 |
| `isLightOn` and all light arrays removed, on every chunk of the world | 192 of 192 |

A chunk without `isLightOn` is relit from its own light sources and from open sky only. Light that neighbouring chunks had already spread across the border is not pulled back in, so positions near the border that were lit from next door stay dark. Light travels at most 15 blocks, so the gap is limited to that distance from a border, and relighting the neighbours at the same time closes it for the centre chunk. The neighbours then have the same gap at their own outer edges, unless the whole world is relit.

Light was compared in game with `execute positioned X Y Z if predicate {type:"minecraft:location_check", predicate:{light:{light:{min:N,max:N}}}}`, because stored arrays leave out uniform sections and do not show what the game will display.

## Other observations

- `execute if biome` at a block does not always name the biome of the cell that holds the block: 150 of 512 queries in `probe-zlib` named a neighbouring cell's biome. The game blends biomes across cell borders when it looks one up. The stored cells are as described above; `check_probe_world.py` reads them directly.
- `tick freeze` is not saved; a restarted server runs normally.
- `tick sprint N` on a frozen world runs exactly N ticks and leaves the world frozen, which gives sessions an exact length.
- With nobody online, crops and sugar cane in forced chunks still grow and furnaces still run, but a mob that is left alone does not wander: a cow stood still for 12,000 ticks. One that was just summoned or teleported does walk first: in scenario 5 a cow was 3 blocks from where `/tp` had put it 40 ticks earlier.
- Random ticks run in forced chunks with nobody online, at the rate of the game rule `random_tick_speed`: with the rule at 3,000, wheat was ripe and a copper block had weathered after 400 ticks. The game ticks 112 of its 1,286 blocks at random; `tools/mc/probe_game_facts.py` reads which from the game's classes.
- Copper weathers one block into the next: the game holds a table of 45 steps, which makes 15 chains of 4 blocks. The two halves of a copper door and of a double copper chest change together.
- A creature that becomes another is a new entity with a new UUID: a tadpole that became a frog and a zombie villager that was cured had none of the UUID they were summoned with. A calf that grew up kept its UUID.
- A bee in its nest is stored in the nest's block entity without a UUID, and is a new entity each time it leaves. A flying bee names its nest in `hive_pos`, three integers. A nest holds 3 bees (`MAX_OCCUPANTS` in the game's classes).
- The game's classes turn 11 kinds of creature into another kind through `Mob.convertTo` or a timed conversion: tadpole to frog; zombie villager to villager; villager to zombie villager or witch; zombie to drowned; husk to zombie; skeleton to stray; pig, piglin and piglin brute to zombified piglin; hoglin to zoglin; mooshroom to cow.
- A frog that was a tadpole has `PersistenceRequired` set, and so has a tadpole out of frogspawn, which also has `FromBucket` set. Frogspawn gives 2 to 5 tadpoles.
- Sand placed with `/fill` stays where it is while the game is frozen and falls once it runs, if nothing is under it.
- With nobody online, a mob that stands in a nether portal goes through it, and the Nether's side of the portal is built. It keeps its UUID: a cow and a sitting wolf put into a portal at (9, 2, 8) were in the Nether's entity files at (2, 64, 1) under the UUIDs they were summoned with.
- A sitting wolf's stored data stops changing once it has come to rest.
- A sulfur spike grows like pointed dripstone, longer and with a spike started below it, and did not fill the cauldron under it in 4,000 ticks, while dripstone next to it did.
- A turtle egg cracks by chance, and the chance is a setting of the world's environment (`TURTLE_EGG_HATCH_CHANCE` in the game's classes), not a fixed hour.
- A copper golem weathers one stage every 504,000 to 552,000 ticks, then turns into a statue block where it stands.
- `time set <ticks>` and `time query day` work; `time query daytime` does not exist in 26.3.
- Finished chunks reach two chunks beyond a forced area. Two worlds that generate the same chunks from the same seed do not produce identical blocks: 295 blocks differed in one run and 114 in another, across 4 forced chunks and their surroundings, mostly ores.
- `/summon` of an item frame or painting logs `Block-attached entity at invalid position` once. It is an artefact of the command; the saved world loads without it.
- Without players, the server keeps almost nothing loaded: a new world has one full chunk at the world spawn, what `forceload` asks for, and the generation rings around both.
- The Nether and End use the same chunk layout with `yPos` 0 and 16 sections. Their heightmaps are also long[37].
