# CHUNK-MERGE-SPEC.md — what verification turned up

Nov 12, 2026 · After build steps 1 and 2 · Evidence in [FORMAT-26.3.md](FORMAT-26.3.md)

## Decided on Nov 12, 2026

All items below were settled and are now in the spec:

| Item | Decision |
| --- | --- |
| 1. Block states | A built-in vanilla block table, plus `--block-report` and `--rules` for mods. Mod tables can declare defaults and rule membership. Defaults come in the game's own block report format. A block in no report merges as whole values and is listed once in the report |
| 2. World files | The drafted rules as written, including refusing the merge when world-gen settings differ |
| 3. Unfinished chunks | Taken whole; a finished chunk always wins; nothing reported |
| 4. Lighting | `--relight changed|neighbours|all`, default `changed`; proper relight left to the mod |
| 5 to 8 and the smaller points | Adopted as proposed |

The rest of this file is the record of what was found and proposed.

## Decided on Nov 12, 2026, second round

| Point | Decision |
| --- | --- |
| Refused chunks | Write the newer readable side, then the other side, then the base; record `refused`; finish the merge |
| Noise list | Keep the built-in list as generated |
| Fences, walls, stairs, redstone wire | Tested: the game refreshes blocks listed in a chunk's post-processing list. The merge lists every pair of adjacent blocks that never stood together in an input |

All three are in the spec. The notes below are the record of what was found.

## Decided on Nov 12, 2026, third round

After the scenario worlds ([SCENARIOS.md](SCENARIOS.md)):

| Point | Decision |
| --- | --- |
| `wandering_trader.dat` | Newer side, unrecorded, like weather |
| `chunk_tickets.dat` | Merged as a set of tickets, so additions from both sides are kept and a removal on either side holds |
| Chunks without an ancestor | The tool counts them, says so and points to `--generated-base`; the report carries the count |

## Decided on Nov 13, 2026, fourth round

After the large-world measurement ([MEASUREMENTS.md](MEASUREMENTS.md)):

| Point | Decision |
| --- | --- |
| Memory | The tool no longer holds a dimension in memory. It compares stored chunks byte for byte, copies what needs no merging, and merges the rest in squares with the changed chunks around them. It must be fast and need no tuning |
| Exit code | Running out of memory and other errors exit with 3, not with the JVM's 1 |
| `random_sequences.dat` | Newer side, unrecorded |
| Vault and beehive timers | Progress fields, which can now be paths into the block entity. Newer side, unrecorded |

Changes that came with the rework, all in the spec:

| Point | Change |
| --- | --- |
| Copied chunks | A chunk taken whole from one input is copied as stored, not written again in canonical form. A chunk that is byte for byte the same on both sides is not decoded, so damage inside it is not reported |
| Unit repair | Each round decides from the blocks as they stand when it starts. This bounds how far a change can reach, to 24 blocks, which is what makes merging in pieces exact |
| Post-processing lists | 3-way, no longer a plain union: a side that never loaded the chunk does not bring the base's entries back |
| Light where blocks match both sides | The data of the side that changed it, no longer always the newer side's |
| Chunks without an ancestor | Only chunks that differ between the sides are counted |
| Unknown blocks | Only chunks that are merged cell by cell are searched |

## Decided on Nov 14, 2026, fifth round

After the play session in the 26.3 client ([SCENARIOS.md](SCENARIOS.md), The play session). All seven proposals were accepted and are in the spec and the tool:

| Point | Decision |
| --- | --- |
| Vehicles in player files | A vehicle a player left the game in is that side's version of the entity, and being taken along is a change. It stays in the player's file only if that file is the one written; otherwise it goes back into the entity files. A written file whose vehicle did not win loses it |
| Map ids | If both sides created maps, the older side's new maps are renumbered to follow the newer side's, in the map files and in every `minecraft:map_id` component of the older side's chunks, entities and player files. `last_id.dat` is the highest id in the merged world |
| Unit repair without an ancestor | A block counts as changed only where the sides differ. This is the fix for the slow merge; the merged world is the same |
| `chunksWithoutAncestor` | Counts a chunk only if a conflict was recorded in it |
| Statistics | Counters are `ours + theirs − base`. The two "time since" timers, listed in the rules file as `stat_timers`, merge as plain values |
| Recipe book | Merged 3-way as a set when both sides changed the player file |
| `players/data/*.dat_old` | Whole file 3-way, newer side, unrecorded |

Left as they are, and worth knowing:

- **Game time is still the maximum.** `level.dat` `Time` and the world clocks take the later value, while play time in the statistics now adds up. A world played 3,070 ticks on one side and 2,401 on the other since the base ends 3,070 ticks after it, not 5,471.
- **The player's inventory is still one value.** When the same player played both sides, the older side's inventory, experience and position are in the backup file only.
- **An entity that changes dimension on one side** is merged in each dimension on its own, so it can be removed in one and kept in the other. Not tested.
- **`singleplayer_uuid` in `level.dat`** is the host's UUID. Between friends it differs on every side, so it will be recorded as a `level` conflict whenever both sides have a different host than the base. It should probably go to the newer side unrecorded; that depends on what the game does with it, which is a question for the mod.

## Decided on Nov 14, 2026, sixth round

After the review of the tool ([REVIEW-FINDINGS.md](REVIEW-FINDINGS.md)). Every finding was accepted for fixing. The decisions are listed there, finding by finding; these are the ones that change what a merged world holds:

| Point | Decision |
| --- | --- |
| Block entities | A block entity follows its block's id, not its full state. A chest that became half of a double chest is still that chest |
| Containers | A loot container both sides opened is the newer side's. After the slot merge, each kind of item is counted against what the two sides did, and a surplus from a stack both moved is taken out |
| Growth | A side that went below the base's growth stage harvested, and its stage wins |
| Unit repair | Half a double chest without its other half becomes a single chest and keeps its contents |
| Entities | In terrain both sides generated, the older side's generated wildlife is left out where the newer side has its own. A dropped item one side picked up stays gone. An unreadable entity chunk counts as unchanged |
| Players | Backups are numbered. A parrot on a shoulder is handled like the boat. An unreadable player file does not win |
| Version gate | Only `level.dat` refuses the merge. Chunks of another version are handled one by one |
| `--dimension` | The other dimensions are copied from the newer side, with their data files and the players in them |
| Inputs | A world that is open in the game is refused |
| Failures | Anything that fails after writing began exits with 3 |

Added the same day, after scenario 12: **a sapling that grew into a tree beats one that only aged.** The rules file calls this kind of rule `becomes`; leaf decay is the same rule with air as what the leaf becomes. The seventh round replaced `becomes` with a more general rule.

Left as they are:

- **Other growth that changes the block** had no rule: a stem that attaches to its fruit, an amethyst bud that grows a size, copper that oxidizes a stage. Each was a conflict when both sides had the chunk loaded and it went differently. The seventh round gave them rules.
- **Light at the border between a chunk of each side** is stale in every mode but `--relight all`. Measured in scenario 11; the fix belongs to the mod.
- **A mob one side killed and the other only had loaded comes back.** Only short-lived entities such as dropped items stay removed.
- **Boats, minecarts and item frames in terrain both sides generated** are kept from both sides.
- **Item counts in a slot both sides changed** are still the newer side's, recorded for the rebase of a later milestone.

## Changed on Nov 15, 2026, seventh round

After checking everything the game changes over time; see [TIME-EFFECTS.md](TIME-EFFECTS.md). The first row is the one that changes what the spec said. The others add rules of kinds it already had.

| Point | Change |
| --- | --- |
| A player's change against the game's | New rule, "One side only". Where one side's block is a change the game makes by itself and the other side's is not, the other side's block is taken and nothing is recorded as a conflict. Before, the rules applied only where both changes matched a rule, so wheat harvested on one side and grown on the other was a true conflict, and a newer side that only watched it grow put it back |
| Blocks that change into other blocks | New rule type `stages`: copper weathering in 15 chains taken from the game's own table, a torchflower crop and its flower, a cauldron filling. The later stage wins; a side that went back, with an axe or a bottle, did it by hand |
| Growth into the same place | The same rule type with the blocks growth starts from: amethyst buds, kelp and vine stalks, bamboo, chorus plants, a cactus against its flower, and a log against a leaf where two trees grew from one sapling. Scenario 12's two trees no longer conflict |
| Stems and other pairs | A stem and its attached form, an open and a closed eyeblossom, grass or mycelium and dirt, nylium and netherrack join fire and snow as blocks the game swaps both ways: newer side |
| Leaves | A leaf a player placed never counts as the game's. A tree's leaf that decayed is air whichever side is newer, as before; a block built where it decayed, or where a leaf of the other side's tree grew, stands |
| Tree growth | No longer a rule of its own (`becomes` is gone from the rules file). A sapling that only aged is the game's doing, so the other side's log stands by the first row |
| More noise properties | Dripstone and sulfur spike shape, vine and chorus plant faces, cauldron level (higher), composter level, dripleaf tilt, vault, trial spawner and creaking heart state, scaffolding distance, snow on grass |
| Coverage | The rules generator reads from the game's classes which blocks the game ticks at random, 112 in 26.3, and fails unless each is in a rule or excused by name |
| Compression | Chunks are written at zlib level 4: 11% faster in the worst case, output 3.7% larger |

Tried and taken out: comparing stored chunk lengths before their bytes. It made no measurable difference; see [MEASUREMENTS.md](MEASUREMENTS.md).

## Decided on Nov 15, 2026, eighth round

Scenario 16 showed creatures in the merged world twice. Four rules, each agreed before it was built:

| Point | Decision |
| --- | --- |
| An entity one side no longer has | It is gone, unless the other side changed an item it holds, gave it a mark of a player or put it into something. Before, it stayed whenever the other side had changed it, and whatever is loaded changes. The sixth round had left this as it was for a mob one side killed. The list `transient_entities` is gone from the rules file: the rule it made for dropped items is now the rule for everything |
| Twins | A creature that turned into another kind on both sides, or an egg block that hatched on both, leaves each side with its own new creatures. The older side's are left out, as many as the newer side has, nearest first, within 32 blocks. New in the rules file: `entity_becomes`, read from the game's classes, `hatches`, and `entity_birth_marks` for the flag the game itself sets on a frog and on what hatches from frogspawn |
| Bees | Where both sides changed a nest, the nest is the newer side's, unrecorded, and so are its bees. A bee that is new on the older side stays only while the nest has room: 3, counting the bees inside and the newer side's in the air. New in the rules file: `hives` |
| Chorus plant | Taken whole from the newer side wherever that side had the chunk in play. New rule type `newer_only` |

Left as they are: two statues from one copper golem, two fruits from one stem, items dropped on both sides. Also left, decided the same day after scenario 17: a mob that one side took through a portal and the other side named is in both dimensions.

Checked afterwards, on Nov 15: a mob that goes through a portal on one side (scenario 17) is in the Nether only, as the first rule says. A turtle egg block now pairs off as many turtles as it held eggs, not up to four.

Found while checking the last two speed-ups against the way of merging that decodes everything: **an entity chunk both sides changed alike was not read**, since its entities are the same on both sides. But the base's entities there were then unknown too. A cow one side killed and the other led into the next chunk came back, and a tadpole that became a frog on both sides and hopped away left two frogs, whenever nothing else told the two sides' chunks apart. Such chunks are read now.

Found while testing these rules through world folders: the property test that merges a dimension one chunk at a time left out, of the chunks around, those that both sides had changed alike. The tool itself has counted them since the sixth round. A run in which both sides put the same half of a chest in the same place failed for it; the test now takes them in.

## Found while building the merge

- **A refused chunk still needs an output.** When the inputs for one chunk disagree on data version, position or height, the spec said to refuse that chunk but not what to write. It is written from the newer side that can be read, then the other side, then the base, and recorded as `refused`. The spec says so now.
- **Neighbour-derived states.** A fence, wall, pane, stair or redstone wire stores how it connects to the blocks next to it. If a fence comes from one side and its neighbour from the other, the stored connection can be wrong. This is not a unit. It is handled by the neighbour refresh: both blocks are put on their chunk's post-processing list, which makes the game recompute them on load. Checked in the game with `tools/mc/probe_postprocessing.py`.
- **The pitcher crop is not a unit.** It has `half=lower|upper`, but a young crop is a single lower half. Treating it as a unit would "repair" healthy crops.
- **Noise rules beyond the spec's table.** The built-in rules file also treats these as noise, each as "newer side": fluid `level` on water and lava, farmland `moisture`, beehive `honey_level`, leaf `distance`, cave vine `berries`, brewing stand `has_bottle_*`, redstone wire connections, and sculk sensor and crafter activity. Egg `hatch`, dried ghast `hydration` and sapling `stage` use "higher". A lever's `powered` and a repeater's `delay` are deliberately not noise, because players set them. The full list is `rules-26.3.json`, generated by `tools/mc/gen_rules.py`; say if any of it should be a real conflict instead.
- **Block entities.** Containers merge slot by slot and everything else as a whole value. The first build merged every block entity as a whole value; that is no longer so.
- **Scheduled ticks on unchanged blocks use the 3-way rule.** The spec only covers ticks at positions whose block came from one side. Where the block is the same on both sides, the ticks at that position are taken from the side that changed them, or from both when both did.
- **The probe world's double chest was laid out backwards.** The in-game check showed the right half sits clockwise from the facing, not the other way round. The probe script and fixtures were corrected.

## Original findings

Each item says what the spec stated, what 26.3 does, and the change that was proposed.

## Needs a decision

### 1. Block states are stored differently, and default states carry no properties

**Spec.** "A block state is its name plus its properties, sorted by key. Equal states compare equal no matter how they were encoded."

**26.3.** A palette entry is a bare string for a block's default state and `{id, properties}` for every other state. The old `{Name, Properties}` layout is gone. A default state carries no properties at all: the lower half of a closed north-facing door is stored as `"minecraft:oak_door"`.

**Effect.** Equality still works for game-written data, because the game always writes the short form for a default state. Anything that reads a property does not work without more data: the growth, machine, redstone and snow rules, and unit repair. A door's lower half has no `half` to read, and `wheat` at age 0 has no `age`.

**Proposal.**
- Section model: a block state is a name plus either "the default state" or a full property set. Equality is on that stored form.
- Add a block table to `rules`: default property values per block, generated from the game's own block report (`--reports`, 1,286 blocks). Rules and unit repair expand default states through it.
- A block missing from the table (a modded block) gets no property rules. Its states merge as whole values. This is what scenario 10 needs anyway.

### 2. The world folder layout and world files are different

**Spec.** `region/*.mca`, `entities/*.mca`, `poi/*.mca`, `playerdata/`, `advancements/`, `stats/`, `data/map_*.dat`; game time, weather, spawn and game rules in `level.dat`; raid data as one file; `--dimension overworld|nether|end|all`.

**26.3.**

| Data | Where it is in 26.3 |
| --- | --- |
| Chunks, entities, POI | `dimensions/<namespace>/<path>/{region,entities,poi}/`, for every dimension including the overworld |
| Game rules | `data/minecraft/game_rules.dat` |
| Weather | `data/minecraft/weather.dat` |
| Day time | `data/minecraft/world_clocks.dat` (`total_ticks` per clock) |
| Game time, spawn, difficulty | `level.dat` (`Time`, `spawn`, `difficulty_settings`) |
| World-gen settings and seed | `data/minecraft/world_gen_settings.dat` |
| Raids, world border, forced chunks | `dimensions/<ns>/<path>/data/minecraft/{raids,world_border,chunk_tickets}.dat`, per dimension |
| Dragon fight | `dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat` |
| Scoreboard, random sequences, scheduled events, stopwatches, boss bars | `data/minecraft/*.dat` |
| Players | `players/data`, `players/advancements`, `players/stats` (from the jar; not seen on disk) |
| Maps | `data/minecraft/maps/` (from the jar; not seen on disk) |

**Proposal.** Replace the "Scope by world file" and "World file rules" tables with ones keyed on these paths:

| Data | Rule |
| --- | --- |
| `level.dat` `Time` | Maximum |
| `level.dat` `spawn`, other fields | Per field 3-way, newer side on conflict; recorded |
| `world_clocks.dat` | Maximum `total_ticks` per clock |
| `game_rules.dat` | Per rule 3-way, newer side on conflict |
| `weather.dat` | Newer side |
| `world_gen_settings.dat` | Must be equal on all three inputs; otherwise refuse |
| `raids.dat`, per dimension | Discarded, as now |
| `chunk_tickets.dat`, `world_border.dat`, other `data/**/*.dat` | Whole file 3-way, newer side on conflict; recorded |
| Players, maps | As in the spec, on the new paths, once a real client session has confirmed them |

`--dimension` should take a dimension id (`minecraft:overworld`) or `all`, because dimensions are folders named by id and data packs can add more.

### 3. Most chunks on disk are not fully generated

**Spec.** "Generation status: must be fully generated on all present sides; otherwise refuse that chunk."

**26.3.** Every explored area is ringed by partly generated chunks. In the probe world, 12 forced chunks produced 870 saved chunks, of which 56 are `full`. A refusal per unfinished chunk would bury the report and leave those slots without a rule.

**Proposal.**
- An unfinished chunk is never merged cell by cell. It is taken whole or dropped.
- A `full` chunk beats an unfinished one. In the cascade and in the missing-ancestor table, an unfinished chunk counts as "missing".
- If no side is `full`: take the unchanged side's chunk if one side equals the base, otherwise the newer side's. No report entry.
- Unfinished chunks keep their entities in the chunk's own `entities` tag, which travels with the chunk as an unknown field.

### 4. The game does not fully relight a chunk

**Spec.** "Heightmaps, lighting flag: removed; the chunk is marked for relighting." "Light arrays are dropped for any section whose blocks changed." Acceptance: "lighting, heightmaps and villages recover."

**26.3.** Heightmaps and POI recover exactly. Light does not:

| What the tool writes | Result in game |
| --- | --- |
| Light arrays dropped, `isLightOn` kept | Wrong: the missing light is treated as dark |
| `isLightOn` and all arrays dropped | The chunk is relit from its own sources and open sky. Light that should come in from neighbouring chunks is missing, up to 15 blocks from the border |
| `isLightOn` dropped, arrays kept | Same as above; the kept arrays are ignored |
| Same, on the chunk and its 8 neighbours | The chunk is correct. Its neighbours have the gap at their outer edges |
| Same, on every chunk | Correct at every sampled position |

In a base that spans a chunk border, the gap shows as a dark band inside the merged chunk next to an unmerged neighbour, wherever the neighbour's torches or daylight used to reach in. It stays until blocks there change.

**Proposal.**
- Rule: for every chunk whose blocks changed, drop `isLightOn`, every light array and light-only sections. Never drop arrays alone. Keeping arrays for unchanged sections has no benefit.
- Add `--relight changed|neighbours|all` to the CLI, default `changed`. It only selects which chunks get the rule, so it is cheap to build, and the scenario worlds can show which mode is good enough.
- Change the acceptance line to name the limit: with `changed`, light crossing into a merged chunk from an unmerged neighbour is not restored.
- The Fabric mod has the game's light engine. Milestone 2 can relight merged chunks properly after materializing, which removes the limit where it matters.

Not verified: `all` when chunks are first loaded in different play sessions. The test loaded the whole area at once.

## Corrections to wording

### 5. Missing sections

**Spec.** "A missing section inside a present chunk is treated as all air with the chunk's default biome."

**26.3.** The game writes every section of the dimension's range, air or not, so a missing section only comes from other tools. When one is missing, the game recreates it as air with biome `minecraft:plains`, whatever the chunk's biomes are.

**Proposal.** "…is treated as all air with biome `minecraft:plains`, which is what the game does."

### 6. Compression types

**Spec.** "Every compression type the game can write. That includes zlib, gzip, uncompressed and LZ4."

**26.3.** It writes zlib, uncompressed and LZ4. gzip is read-only. Type 127 is a custom, mod-supplied compression named by a string.

**Proposal.** "Read zlib, gzip, uncompressed and LZ4. Write zlib by default, or uncompressed or LZ4 on request. A chunk with custom compression (127) is a corrupt slot: reported and refused." Add `--compression zlib|none|lz4` to the CLI.

### 7. Sections that hold only light

**26.3.** A finished chunk can carry one extra section above the top of the world (`Y` = 20) with only `SkyLight`. The section model needs "blocks and biomes, or light only". Under item 4 these are dropped from merged chunks.

### 8. Report example

`"dataVersion": 0` in the report example should read `5023`. The version gate should also check `level.dat` and the `data/*.dat` files, which carry `DataVersion` too.

## Smaller points to settle in step 3

- **Canonical list order.** "Lists in a defined order" is safe for block entities (by position) and entities (by UUID). Scheduled ticks should keep file order: their order may decide which tick runs first.
- **Leashes.** A leash to a fence is a block position, and the knot is not a saved entity. "Report any reference to an entity that did not survive" covers `leash.UUID` and `Owner`; a position leash needs its fence post to survive instead.
- **POI.** The game rebuilds POI per chunk when the chunk's entry is absent, so "discarded for merged chunks" means removing that chunk's entry from `poi/r.X.Z.mca`, not the file.
- **Unknown data inside a block state or a palette.** The codec refuses a chunk whose palette entry or `block_states` compound has a field it does not know, instead of carrying it through. Nothing in vanilla has such fields, and refusing is safer than guessing what they mean. Say so in the spec's "unknown data" principle.
- **Absent optional tags.** The game accepts a chunk without `block_ticks`, `fluid_ticks`, `PostProcessing`, `structures`, `block_entities` and writes them back empty. The codec does the same.

## Limits of the scripted worlds

- **No player.** The console cannot create one, so player data, advancements, stats, maps and scenario 5's player-ridden boat cannot be scripted. One manual session in the client on Nov 14, 2026 produced worlds and a fixture for them; see the fifth round above.
- **Growth without a player.** Crops and other random ticks do advance in forced chunks with nobody online; scenario 3 shows it. Mobs left alone do not wander; one that was just summoned or moved walks a few blocks first.

## Choices made in step 2

| Choice | Reason |
| --- | --- |
| Base package `mycelium` | The project name and nothing else. Renamed on Nov 14, 2026 from a package that held the GitHub account name |
| `at.yawk.lz4:lz4-java:1.10.1`, pure-Java safe decoder | The exact library 26.3 bundles; the safe decoder bounds-checks untrusted input and loads no native code |
| JUnit 5.14.4 and jqwik 1.10.2 | jqwik 1.10.2 is built on the JUnit 5.14 platform |
| NBT compounds keep key order; equality ignores it | Unchanged NBT is written back byte for byte, and a sorted mode exists for canonical output |
| Chunks from any data version other than 5023 are refused by the codec | The layout is only verified for 26.3 |
| Fixtures: 11 MB in the working tree, about 1.3 MB compressed | Real region files at 4 KiB per chunk; most of it is padding |
