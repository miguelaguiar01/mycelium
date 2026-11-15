# Mycelium — Chunk Merge Layer Spec

Nov 12, 2026 · Draft, revised after format verification · Build milestone 1 of [SPEC.md](SPEC.md)

Format facts in this spec are backed by [docs/FORMAT-26.3.md](docs/FORMAT-26.3.md). The changes made after verification, and why, are in [docs/SPEC-FINDINGS.md](docs/SPEC-FINDINGS.md).

## Overview

The chunk merge layer takes three copies of a Minecraft 26.3 world: a common ancestor O and two divergent sides A and B. It produces one merged world and a report of what it resolved and what truly conflicted. It is a Java library with a thin command-line tool around it.

This is the riskiest part of Mycelium, so it is built and proven first, with no logs, networking or Fabric code. Later milestones call the same library with section states taken from session logs instead of world folders.

**Goals**

- Merge two divergent copies of a 26.3 world into one world that the game loads cleanly.
- Produce the same merged world on every machine for the same inputs.
- Never lose data the layer does not understand, including data written by other mods.
- Report every true conflict with enough detail for a person to find and judge it.
- Keep the merge logic free of Minecraft classes, so it runs without the game installed.

**Non-goals**

- Session logs, hybrid logical clocks, signatures, gossip and nodes. These come in later milestones.
- Rebase and item corrections for provisional sessions. The layer reports container conflicts but never fixes item counts.
- Upgrading worlds between Minecraft versions. The mod runs the game's own data upgrader before merging.
- Merging more than two sides at once. Later milestones merge three or more heads pairwise in canonical order.
- Recomputing lighting, heightmaps or POI data. The game rebuilds them.

**Design principles**

- **Values, not encodings.** Two sections are equal when their decoded block states are equal, regardless of palette order or bit width.
- **Unknown data passes through.** Any tag the layer does not model is carried over with a whole-value 3-way rule, never dropped.
- **Pure merge, thin I/O.** Merge functions take decoded models and return decoded models. File reading and writing live in a separate outer layer.
- **Rules as data.** Noise rules and multi-block definitions live in tables, because they change between Minecraft versions.
- **Verify the format, don't remember it.** Every format detail below is checked against real files from a 26.3 world before it is relied on. See the verification checklist.

## Inputs and outputs

**Command line**

```
mycelium-merge \
  --base   <world O> \
  --ours   <world A> \
  --theirs <world B> \
  --newer  ours|theirs \
  --out    <merged world> \
  --report <conflicts.json> \
  [--generated-base <fresh world from the same seed>] \
  [--dimension <dimension id>|all] \
  [--relight changed|neighbours|all] \
  [--compression zlib|none|lz4] \
  [--block-report <blocks.json>]... \
  [--rules <rules.json>]...
```

- `--newer` names the side that wins "newest wins" rules. It stands in for hybrid logical clocks until logs exist.
- `--generated-base` supplies ancestors for chunks that O never generated. See Missing ancestors.
- `--out` must not exist or must be empty. The tool never writes into an input world.
- An input world that is open in the game is refused. The game holds a lock on `session.lock` while it has a world open, and the tool takes that lock on each input for as long as it runs.
- `--dimension` takes a dimension id such as `minecraft:overworld`, or `all` (the default). A dimension is a folder under `dimensions/<namespace>/` that holds storage or data; data packs can add more, and an id's path can have several parts, as in `mymod:worlds/mining`. The other dimensions are the newer side's, so the output is always a whole world: their storage and data files are copied from it file by file. See also Entities in player files.
- `--relight` selects which chunks lose their light data so the game relights them. See Derived data.
- `--compression` sets how chunks are written. The default is zlib, the game's default. The tool writes zlib at level 4, which takes less than half the time of the library's default level for chunks about 4% larger; the game compresses a chunk again whenever it saves it.
- `--block-report` and `--rules` add block tables for mods, and can be repeated. See Block tables.
- Exit codes: `0` merged with no true conflicts, `1` merged with true conflicts or refused chunks, `2` refused as a whole (bad input, an input open in the game, a `level.dat` of another version, differing world-gen settings), `3` the merge failed after it began to write, which includes running out of memory, a read or write error, and a report that cannot be written. A refused merge writes nothing: everything that can refuse it is checked before the output folder is created. After exit code 3 the output folder is incomplete, and the tool says so.
- The tool needs no tuning. It divides its work to fit the heap limit and processors of the JVM it runs in; see How the work is divided.

**Library entry points**

```java
MergeReport        WorldMerger.merge(WorldMerger.Options options);                        // divides the work to fit the JVM
MergeReport        WorldMerger.merge(WorldMerger.Options options, WorldMerger.Tuning tuning);
ChunkMergeResult   Merger.mergeChunk(ChunkData base, ChunkData ours, ChunkData theirs, MergeContext context);
SectionMergeResult Merger.mergeSection(Section base, Section ours, Section theirs, ChunkPos chunk, MergeContext context);
```

`WorldMerger.merge` throws `MergeRefusedException` if it wrote nothing and `MergeFailedException` if it stopped after it began to write. When it returns or throws, none of its threads is still writing.

`MergeContext` carries a `Precedence` that answers "which side is newer for this change". In this milestone it always returns the `--newer` side. Later it is backed by per-entry hybrid logical clocks, without changes to the merge functions.

**Scope by world file**

Minecraft 26.3 keeps every dimension, the overworld included, under `dimensions/<namespace>/<path>/`.

| Path in the world folder | Handled by |
| --- | --- |
| `dimensions/*/*/region/*.mca` | Chunk merge, this spec |
| `dimensions/*/*/entities/*.mca` | Entity merge, this spec |
| `dimensions/*/*/poi/*.mca` | The entry of each merged chunk is removed; the game rebuilds it |
| `dimensions/*/*/data/minecraft/*.dat` | World file rules, below |
| `level.dat` | World file rules, below |
| `data/minecraft/*.dat` and anything else under `data/` | World file rules, below |
| `players/data`, `players/advancements`, `players/stats` | World file rules, below |
| `datapacks/`, `icon.png`, `level.dat_old` | Taken from the newer side |
| `session.lock` | Never copied |
| Anything else | Copied as a whole file with a 3-way rule, newer side on conflict |

## Format layer

The format layer turns files into decoded models and back. It knows nothing about merging.

**NBT.** A small reader and writer for the binary NBT format, built in-house or on the basic tag classes of an existing library. It must keep every tag, including unknown ones. It must round-trip exactly: reading and writing a tag tree gives back the same tree.

**Region files.** Each `.mca` file holds a 32×32 grid of chunks. The layer must handle:

- The header of chunk offsets and timestamps, with chunks stored in 4 KiB sectors.
- Reading zlib, gzip, uncompressed and LZ4 chunks. The game writes zlib by default, and uncompressed or LZ4 when configured. It reads gzip but never writes it. LZ4 is lz4-java's block stream, not the LZ4 frame format.
- Writing zlib, uncompressed or LZ4.
- Custom compression (type 127, supplied by a mod). This is treated as a corrupt slot.
- Oversized chunks: a chunk that needs 256 sectors or more is stored in `c.<chunkX>.<chunkZ>.mcc` next to the region file.
- A last chunk that is not padded. The game pads a region file to whole sectors only when it closes it, and loads a chunk whose payload is all there. A world copied while the game runs, or left by a crash, has such a file. Checked in the game.
- Only the file names the game writes, `r.<x>.<z>.mca` with plain numbers. Anything else in the folder is not a region file.
- Empty chunk slots and corrupt slots. A corrupt slot in any input refuses the merge for that chunk and is reported, never silently skipped. The same goes for a chunk whose inputs disagree on data version or position. A refused chunk is written from the newer side if that side can be read, otherwise from the other side, otherwise from the base, so nothing built is lost.
- A stored chunk that is copied without being decoded keeps its bytes. If the output is compressed differently and the chunk cannot be decompressed to recompress it, it is copied with its own compression; a region file may mix them.

**Untrusted input.** World files will come from other people's machines, so no file may cost more than its own size warrants:

- A list's declared length is checked against the smallest size its elements can have, and nothing is reserved for a length before the elements arrive.
- One NBT tree holds at most 8 million tags and nests at most 512 deep, the game's own limit.
- A chunk, a standalone gzip file and an external chunk file each may hold at most 256 MiB once decompressed.
- JSON nests at most 256 deep.

Input beyond a limit is a corrupt slot or an unreadable file, handled like any other; it does not end the run.

**Chunk model.** A decoded chunk keeps the fields the merge understands as typed values and everything else as raw NBT:

| Field | Model |
| --- | --- |
| Data version | Integer; must be equal across all three inputs |
| Chunk position and lowest section | Integers; must be equal |
| Generation status | String. Only `minecraft:full` chunks are finished; most chunks on disk are not |
| Sections | List of `Section`, keyed by section Y |
| Block entities | Map from block position to raw NBT |
| Block and fluid ticks | Lists of scheduled ticks with position and type |
| Heightmaps, lighting flag | Kept only to be stripped on output |
| Structure starts and references | Raw NBT per structure key |
| Post-processing lists | Per-section position lists |
| Everything else | Raw NBT, carried through |

**Section model.** Each section is 16×16×16 blocks:

- **Block states.** Decoded to 4,096 values in index order `y*256 + z*16 + x`. 26.3 stores a block's default state as a bare name with no properties, and any other state as a name with all of its properties. A block state is therefore a name plus either "the default state" or a full property set, sorted by key. States compare by that stored form, whatever the palette order or bit width. The game always writes a default state the short way, so equal states written by the game compare equal. Reading a property of a default state needs a block table; see Block tables.
- **Biomes.** Decoded to the 64 biome cells of 4×4×4 blocks, in index order `y*16 + z*4 + x`.
- **Light.** Block and sky light arrays, kept only to be stripped on output. A finished chunk can also hold one section above the top of the world that carries only sky light.
- **Unknown fields.** Carried through. A block state or palette with a field the layer does not know is the exception: the chunk is refused and reported, because the field could change what the state means.

Decoding must handle palettes with a single entry, which store no data array, and the minimum bit widths for blocks and biomes. Encoding must produce what the game itself would accept.

**Entity files.** Entity region files hold one entry per chunk, each with a list of entities. Riders are stored nested inside the entity they ride. The layer flattens every entity, including nested riders, into one world-wide index keyed by UUID. It rebuilds the nesting on output.

**Version gate.** The tool refuses to merge if the `level.dat` of any input carries a data version other than 26.3's, which is 5023, and names the file. Chunks and entity chunks are checked one by one, because a world the game has upgraded still holds chunks of the older version wherever nobody has been since: the game upgrades a chunk when it loads it.

- A chunk or entity chunk of another version that is the same on both sides is copied as stored. The game upgrades it later, as it would have.
- One that only one side has not changed takes no part: the other side's chunk is written.
- One that would have to be merged is refused, like a corrupt slot. Upgrading chunks is the game's job and is left to the mod.

Player files and other data files of another version follow their file rules; a friend who has not joined since an upgrade must not block the merge.

**Verification checklist.** Each item was checked against worlds written by the 26.3 server. The findings, the worlds and the scripts are in [docs/FORMAT-26.3.md](docs/FORMAT-26.3.md).

- [x] Top-level chunk tag names and types.
- [x] Section tag names, block and biome palette layout, minimum bit widths, and the single-entry case.
- [x] Whether packed values can span two longs. They cannot.
- [x] Compression types present, and how external chunk files are named and flagged.
- [x] Entity file layout and how riders nest.
- [x] Block entity layout and how container items are stored.
- [x] Which tags the game rebuilds when absent. Heightmaps and POI are rebuilt exactly; light is rebuilt only in part.
- [x] The data version number for 26.3: 5023.
- [x] Where the other half of a door, bed, double chest and piston sits (`tools/mc/probe_units.py`).

## Merge algorithm

**Cascade.** For each chunk and each section, compare O, A and B by value:

1. A equals O: take B.
2. B equals O: take A.
3. A equals B: take A.
4. Otherwise descend one level: chunk, then section, then each block.

At block level, a block that changed on both sides to different values goes to the noise rules. If no rule applies, the newer side wins and a true conflict is recorded.

**Unfinished chunks.** Every explored area is ringed by chunks whose generation has not finished. They are never merged cell by cell:

- A finished chunk beats an unfinished one. In the cascade and in the missing-ancestor table, an unfinished chunk counts as missing.
- If no side has a finished chunk, the output takes the chunk of the side that changed it when the other side's equals the base, and the newer side's otherwise. Nothing is reported.
- An unfinished chunk keeps its entities in its own NBT. They travel with the chunk.

**Block tables.** Rules and unit repair read block properties, and a default state stores none. The layer therefore carries block tables:

- A **block report** lists every block with its properties and its default state. The built-in one is generated from the 26.3 server's data generator. `--block-report` adds more in the same format, which is the `blocks.json` the game itself writes, so a modded server can produce one with its mods loaded.
- A **rules file** says which blocks join which noise rule and which unit kind. The built-in one covers vanilla. `--rules` adds more in the same format. Every block and property a rules file names must exist in a loaded block report.
- A block that no loaded report knows merges as whole values, with no noise rules and no unit repair. The report lists each such block id once.

**Chunk-level fields**

| Field | Rule |
| --- | --- |
| Data version, position, lowest section | Must match; otherwise refuse |
| Generation status | Finished chunks are merged; see Unfinished chunks |
| Last update time, inhabited time | Maximum of A and B |
| Sections | Section merge |
| Block entities | Follow their block, below |
| Block and fluid ticks | Keep the ticks of a position from the side its final block came from. Where the block is the same on both sides, the ticks at that position follow the 3-way rule. If both changed them, the newer side's ticks stand and the older side adds only tick types the newer side has none of there, so a block is ticked once per type |
| Heightmaps, lighting flag, light arrays | See Derived data |
| Structure starts and references | 3-way per structure key; newer side on conflict |
| Post-processing lists | The list of the side that changed it, or the union if both did, plus the blocks marked by the neighbour refresh below. The game empties the list when it loads the chunk, so a side that never loaded the chunk must not bring the base's entries back |
| Unknown fields | Whole-value 3-way; newer side on conflict, recorded as an unknown-field conflict |

**Section merge.** Block states and biome cells each merge cell by cell with the cascade. Unknown section fields use the whole-value 3-way rule.

**Derived data.** Heightmaps, the `isLightOn` flag, light arrays and light-only sections describe a chunk's blocks:

- If the merged blocks of a chunk equal one side's blocks, that side's derived data is kept as it is. If they equal both sides' blocks, the data of the side that changed it is kept, or the newer side's if both did: light also depends on the chunks around, so it can differ where the blocks do not.
- Otherwise all of it is removed. The game rebuilds heightmaps exactly. It relights the chunk from the chunk's own light sources and open sky, but does not pull back light that neighbouring chunks had spread across the border. Light arrays are never removed while the flag stays, because the game would then treat the chunk as dark.
- `--relight changed` (the default) removes derived data only as above. `neighbours` also removes it from the 8 chunks around each such chunk, which makes the merged chunk correct and moves the gap to the outer edge of its neighbours. `all` removes it from every chunk.
- Light is also stale where a chunk taken whole from one side lies next to a chunk taken whole from the other: each keeps the light of a world that did not have the other's change. A torch one side put at the edge of its chunk does not light the neighbour the other side built in. Measured in the game (scenario 11): with `changed` and with `neighbours` the neighbour is dark at 9 of 18 sampled positions, 0 where the control has 11 to 13. With `all` every position matches. The tool does not compute light, so it cannot do better than `all`, which rewrites every chunk and makes the game relight the whole world.
- The Fabric mod can run the game's light engine after materializing a merged world, which closes the gap for good.

**Noise rules.** Changes the game makes by itself are resolved without recording a conflict. They apply where both sides changed the same block to different values. The rules live in one table:

| Rule | Applies when | Result |
| --- | --- | --- |
| Growth | Same block type on both sides; only a growth property differs, such as crop, sapling or cave vine age | Higher stage. If one side is below the base's stage, that side's stage: it harvested or replanted, and growth on the other side must not undo that |
| Stages | The game turned the base's block into another block along a chain that only goes one way, on both sides, and one side got further: copper weathering stage by stage, a torchflower crop becoming its flower, an empty cauldron filling | The later stage |
| Grown into the same place | The base has air or water, and each side has a block of one chain there: amethyst buds of two sizes, the tip and the stalk of kelp or a vine, a cactus block and a cactus flower, a leaf of one tree and a log of another grown from the same sapling | The later stage: the larger bud, the stalk, the cactus, the log |
| Machine state | Same block type; only lit or active properties differ, such as on furnaces, smokers and campfires | Newer side |
| Redstone | Same block type; only power, powered, triggered or similar properties differ | Newer side; the game re-evaluates on the next tick |
| Back and forth | Blocks the game swaps both ways, where the base and each side have one of the pair: snow and air; a melon or pumpkin stem and the same stem attached to its fruit; an open and a closed eyeblossom; grass or mycelium and dirt; nylium and netherrack. Fire and air count whatever the base holds, because fire takes the place of what it burns | Newer side |
| Leaves | A leaf of a tree decayed on one side and is still there on the other, give or take noise properties | Air, whichever side is newer: the leaf has lost its tree. Needs the base, to know a leaf was there |
| One side only | One side's change is one the game makes by itself, and the other side's is not | The other side's block: a player did that. Harvested wheat stays harvested though it grew on the other side, a waxed or scraped copper block stays as the player left it, a block built where a leaf decayed or grass spread stands. Needs the base |

**What the game makes by itself**, measured from the base's block to a side's block, is one of three things:

- The same block with only properties changed that a rule lists, not to a value only a player sets and not below the base's stage.
- A block of a back-and-forth pair where the base has the other, or a tree's leaf where the base has air or the other way round. A leaf a player placed is `persistent` and never counts.
- A later stage of the base's block along its chain, with the properties the two have in common unchanged apart from noise.

A block that merely appears where the base has air does not count, apart from fire, snow and a tree's leaf. Players plant cane, place buds and build with logs, so a block one side built where the other side's cane, bud or log grew is a true conflict, and so is a sapling one side dug up where the other side's tree now stands.

Tree growth needs no rule of its own. A sapling that became a tree on one side is a log; on the other side it only aged, which is the game's doing, so the log stands. A sapling that only aged must not stand under the other side's tree.

Without an ancestor the rules that compare two sides still apply, and settle a difference before it becomes a no-ancestor conflict. The Leaves rule and the One side only rule do not: they need to know what was there.

The table above is the core. The built-in rules file adds other properties that only the simulation changes, each as "newer side" unless said otherwise: fluid level (except that a source block one side placed wins over a flow the other side's water made, since only a player or a bucket makes a source), farmland moisture, leaf distance, beehive honey level, cave vine berries, brewing stand bottle slots, redstone wire connections, sculk and crafter activity, the shape of a dripstone or sulfur spike behind its tip, the faces a vine covers and a chorus plant connects on, a composter's level, a dripleaf's tilt, the state of a vault, a trial spawner and a creaking heart, a scaffolding's distance, and whether grass has snow on it. A water or powder snow cauldron's level takes the higher value, and a lower one than the base's is the player's who took some out. A lever's state and a repeater's delay are set by players and stay real conflicts.

The built-in rules file names exact blocks and property names. Each one is checked against the 26.3 block report when the rules are loaded. Some growth properties reset after reaching their maximum, as with sugar cane. Those use "newer side" instead of "higher stage". Properties are read through the block tables, so a default state takes part like any other.

**Every block the game ticks.** The game changes blocks by itself mainly through random ticks. Which blocks it ticks that way is read from the game's own classes (`tools/mc/probe_game_facts.py`): 112 of the 1,286 blocks in 26.3. The generator of the rules file refuses to run unless each of them is in a rule or excused by name with the reason. The stages of copper come from the same place, the game's own table of what each copper block weathers into: 15 chains of 4 blocks. `docs/TIME-EFFECTS.md` lists every block and creature that changes over time and what the merge does with it.

**A plant that only stands as a whole.** A chorus plant that grew into two shapes, merged block by block, has a shape the game does not allow, and the game breaks part of it when it loads the chunk. So it is taken whole from the newer side. Wherever the newer side's chunk differs from the base's at all, which it does once a player was near it, a chorus block that only the older side grew is not taken, and where both sides have one, the newer side's stands. What the older side cut down stays cut down. A newer side that never had the chunk in play has no plant of its own, and the older side's stands. The left-out blocks are recorded once per section, as resolved. The rules file has this as rule type `newer_only`.

**Known limits of merging what grew.** Each side's growth is kept, so a stem that bore a fruit on both sides has two fruits in the merged world, and two trees grown from one sapling stand in each other. Items the game dropped on both sides, such as the seeds of a crop that lost its farmland, lie there twice. A copper golem that weathered through on both sides is a statue twice, one where it stood on each side.

**Multi-block units.** Merging block by block can split a structure. A door can end up with its top half from one side and its bottom from the other. A unit is a group of positions that must come from the same side:

- Doors, tall plants and other two-part plants: `half=lower` with `half=upper` of the same block directly above. 31 blocks in 26.3: 22 doors and 9 plants. The pitcher crop is left out, because a young one has no upper half.
- Beds: `part=foot` with `part=head` of the same block one step in the direction of `facing`. 17 blocks.
- Double chests: `type=left` with `type=right` of the same kind and facing, one step clockwise from `facing`. 10 blocks: the chest, the trapped chest and 8 copper chests, which pair only within their kind.
- Extended pistons: a base with `extended=true` and a `piston_head` with the same `facing`, one step in that direction.

These are all the blocks in the 26.3 block report whose state names a partner. The geometry was checked in the game. Blocks whose state only reflects their neighbours, such as fence and wall connections, stair shapes and redstone wire, are not units; see Open questions.

Units can cross section and chunk borders, so unit repair runs as a pass over the merged chunks and the chunks around them, after the cell-by-cell merge:

1. Find every unit member in the merged result and its expected partners.
2. If a unit is incomplete or mixes sides, take the whole unit from one side. Use the side that changed it, or the newer side if both did.
3. Half a double chest whose other half is gone is not taken from a side. It becomes a single chest and keeps what it holds: a chest needs no second half, and taking it from the side that has air there would delete its items.
4. Record the repair. It is a true conflict only if both sides changed the unit.

The pass starts from the blocks that changed and looks at each of them and its six neighbours. In a chunk merged without an ancestor, a block counts as changed only where the sides differ: what both sides generated alike was changed by neither.

A unit that was already broken in the base is left alone, and so is one whose other half lies in a chunk that was not merged. Taking a unit from one side can break a neighbouring unit, so the pass repeats, up to 8 rounds. If something is still broken after the last round, it is left as it is and recorded as a conflict. Within the rounds, from the second round on, anything touching a repaired block is taken from the newer side, which makes the result settle.

Each round decides from the blocks as they stand when the round starts, and only then replaces blocks. A block's fate in one round therefore depends only on blocks within 3 of it, and after all rounds and the last look only on blocks within 27. This bound is what lets a dimension be merged in pieces; see How the work is divided.

**Neighbour refresh.** A fence, wall, pane, stair or redstone wire stores how it connects to the blocks next to it. After unit repair, every pair of adjacent blocks whose merged values never stood side by side in any of the three inputs is added to its chunk's post-processing list. The game recomputes listed blocks from their neighbours, and lets listed fluids flow, when it loads the chunk. This was checked in the game (`tools/mc/probe_postprocessing.py`).

**Block entities follow their block.** A block is the same block as long as its id is. A chest that became half of a double chest on one side, or a furnace that is lit on one, is still that chest and that furnace.

- Where both sides have a block of the same id, its block entity is merged by the table below. The base's block entity is the ancestor if the base had a block of that id there too.
- Where the ids differ, the block entity goes with the block that was taken. This prevents mismatches such as a chest holding a furnace's data. If the other side had changed its own block entity, that change is lost with the block, and it is recorded as a block entity conflict.

| Block entity | Rule | On true conflict |
| --- | --- | --- |
| Containers: chests, barrels, shulker boxes, hoppers, dispensers and similar | Per-slot 3-way, then the item balance below | Newer side; recorded as an item conflict for later rebase |
| A container that still had its loot table in the base and that both sides opened | Each side rolled the loot for itself, so the slots have no common past: the newer side's container, whole | Recorded as an item conflict if the two differ |
| Furnaces, smokers, blast furnaces, brewing stands | Item slots per-slot 3-way; progress fields 3-way, newer side if both changed them | Slots as containers; progress never recorded |
| Other fields of a container, such as its name or loot table | 3-way per field | Newer side; recorded |
| Everything else: signs, banners, spawners, vaults, beehives and others | Whole value 3-way. If both sides changed it, the values are compared without their progress fields: equal then means the newer side's value, unrecorded, and a difference on one side only means that side's value | Newer side; recorded |

**Item balance.** Merging slot by slot cannot see an item move. A stack both sides moved, each to another slot, would be there twice, and no slot would conflict. So after the slots are merged, each kind of item is counted: the container should hold what the base held plus what each side added or took, `ours + theirs − base`. If it holds more, and the older side put that kind into a slot where neither the base nor the newer side has it, the surplus is taken out of such slots and recorded as an item conflict. Nothing else is touched: a furnace that burned the same coal on both sides is one side's furnace as a whole, not a move. A kind of item for which a slot conflict is already recorded is left to that record.

Progress fields are listed in the rules file as `block_entity_progress`. Each is a path from the block entity's root: `a.b` is field `b` of the compound `a`, and `a[].b` is field `b` of every compound in the list `a`. Besides the furnace and brewing fields, the vanilla list has a vault's next update time (`server_data.state_updating_resumes_at`), and a beehive's bees and the flower they last flew to (`bees`, `flower_pos`). A vault's time ticks on both sides whenever the chunk is loaded, and bees come and go by themselves. A hive both sides changed is therefore the newer side's, unrecorded; the entity merge sees to it that each bee is there once.

**Entities.** Entities merge as one set keyed by UUID, across all chunks of a dimension:

| O | A | B | Result |
| --- | --- | --- | --- |
| absent | present | absent | A's entity, unless it is generated wildlife that B generated too; see below |
| present | removed | unchanged | Removed |
| present | removed | modified | Removed; recorded as resolved. B's entity only if B changed its items, gave it a mark of a player, or put it into or onto something |
| present | modified | modified | Newer side's entity |
| absent | present | present | Newer side's entity; recorded like the row above. One UUID is one entity: the base can hold it where the entity merge does not look, inside an unfinished chunk |

The rows are symmetric for A and B.

- Mobs move almost every tick, so "modified on both sides" is common and expected. It is recorded as resolved, not as a true conflict, unless an equipment or inventory field differs. Item frames, armour stands and entities that carry items count as item-bearing.
- Each surviving entity is written into the chunk that contains its position.
- A rider that is nested on one side and standalone on the other appears once. Its parent is taken from the side whose entity wins.
- A leash held by another entity points to that entity's UUID. Report any leash whose holder did not survive the merge. A holder that is a player is not reported. Owners are players, who are not in the entity files, so owner references are not checked.
- **An entity one side no longer has is gone.** Whatever is loaded changes every tick, so "modified" by itself says nothing. The side that no longer has the entity has what became of it: the drops of a cow it slaughtered, the frog a tadpole grew into, the bucket with the fish, the pet on the other side of a portal. Keeping the other side's copy would have it twice. The copy stays only if that side did something a player does: changed an item it holds, gave it a mark of a player, or put it into a vehicle or took it along. This holds for a dropped item as for a mob.
- **A mark of a player** is one of the fields the rules file lists as `entity_player_marks`: an owner, a custom name, a leash, the flag that keeps a mob from despawning. The game sets that flag itself on what hatches from frogspawn and on a frog that was a tadpole; the rules file lists it as `entity_birth_marks`, and on such a creature it does not count.
- **Terrain both sides generated.** Where the base has no finished chunk, each side generated the terrain on its own, with the same animals, monsters and loot carts under different UUIDs. Checked in the game: the same 64 chunks generated twice hold the same 5 chickens and 5 chest minecarts, with no UUID in common. There, for every new entity of a type on the newer side, one new entity of that type on the older side is left out, lowest UUID first. Only animals, monsters and containers that still have their loot table count, and none that carries a mark of a player, holds items, rides or is ridden. Boats, minecarts and item frames never count: players place those. The count is per type over the dimension, and each type left out is recorded as resolved.
- **What both sides ended and began again.** A creature that turns into another kind is a new entity with a new UUID: a tadpole that becomes a frog, a zombie villager that is cured. What hatches from an egg block is new as well. If that happened on both sides, each side has its own. So for each creature of the base that neither side still has and that turns into another kind, and for each egg block of the base that neither side still has, the older side's new creatures of the kinds it becomes are left out: as many as the newer side has of its own, at most as many as one such thing gives, the nearest to where it was first, and only within 32 blocks of it. A turtle egg block gives as many turtles as its state says it holds eggs; frogspawn gives 2 to 5 tadpoles by chance, so 5 is the most. A creature with a mark of a player is never left out. The rules file lists the kinds as `entity_becomes` and `hatches`; the conversions are read from the game's classes. This goes by number and place, because nothing ties a frog to the tadpole it was. Where the newer side got more out of an egg than the older side and the older side has another new creature of the kind close by, that one is left out in their stead. Each ending that leaves something out is recorded as resolved.
- **Bees.** A bee is an entity while it flies and an entry in its nest's block entity while it rests, where it has no UUID; it gets a new one each time it leaves. A flying bee names its nest. Where both sides changed a nest, the merged world has the newer side's nest, so its bees are the newer side's too: a bee the newer side has in the air stays, a bee of the base that the newer side no longer has in the air is gone, since it went into the nest or died there, and a bee that is new on the older side stays only while the nest has room for it, counting the bees inside the newer side's nest and those of its bees in the air. A nest holds 3. The rules file has this as `hives`. A nest only one side changed is that side's, and its bees need no rule: the other side never saw one leave or enter.
- **Known limit: a mob in two dimensions.** Each dimension is merged on its own. A mob that one side took through a portal is gone from the dimension it left, by the rule above, and is in the dimension it went to. If the other side did something a player does to it in the meantime, such as naming it, the dimension it left keeps it as well, and the merged world has it twice under one UUID. It takes both players handling the same animal between two syncs, and it leaves an animal too many, not one too few. Accepted as a limit on Nov 15, 2026. A fix would read the entities of all dimensions before merging any and let the newer side's version stand.
- **A side that cannot be read.** If a side's entity chunk or entity region file is corrupt, that side counts as not having changed the entities there: the base's stand in for it, or the other side's if the base has none. It is recorded as refused. Reading "no entities" would delete what the other side still has. An entity without a valid position makes its chunk unreadable in this sense. A UUID stored twice on one side is kept once, and that is recorded.

**Entities in player files.** A player who leaves the game while riding takes the vehicle along. The game stores the vehicle, with its other riders nested inside, in the player's file as `RootVehicle`, and removes it from the entity files. A parrot on a shoulder is stored the same way, as `ShoulderEntityLeft` or `ShoulderEntityRight`; those two tags are from the game's jar and have not been seen in a world. Read by UUID alone, that looks like a removal: the vehicle would be lost when the player's file is not the one written, and stored twice when the other side moved it.

- An entity in a player's file is that side's version of the entity. Being taken along counts as a change, so the table above applies as it stands.
- A surviving entity stays in the player's file only if that file is the one written for the player and, for a vehicle, the player's seat is still in it. Otherwise it is written into the entity files at its position, riders included.
- A player file that is written with an entity that did not win is written without it. The player then stands where the file says. This is recorded as resolved.
- With `--dimension`, the other dimensions are the newer side's, and so is every player whom either side has in one of them: the newer side's file is written, and the older side's is kept as a backup if it differs. Taking the older side's file would put its vehicle in a dimension that came from the other side, where it may stand already.

**Missing ancestors.** A chunk can be missing from one or more inputs:

| O | A | B | Result |
| --- | --- | --- | --- |
| missing | present | missing | A |
| missing | present | present | Merge against `--generated-base` if given; otherwise a 2-way merge where equal blocks are kept and differing blocks go to the newer side, recorded as no-ancestor conflicts |
| present | missing | present | Treat A as unchanged from O; take B |
| present | missing | missing | O |

An unfinished chunk counts as missing in this table.

The 2-way merge is a last resort. It keeps the newer side's block wherever the sides differ, so the older side's work in those chunks is lost, and two worlds generate the same chunks slightly differently, which adds a conflict for each such block. When any chunk is merged this way, the tool says how many and points to `--generated-base`. A world where both sides explored should be merged with a generated base.

A missing section inside a present chunk is treated as all air with biome `minecraft:plains`, which is what the game does when it loads such a chunk. The game itself writes every section, so this only arises with worlds edited by other tools.

## How the work is divided

The cost of a merge follows what the two sides changed, not the size of the world. Measured in `docs/MEASUREMENTS.md`.

**Compare first.** Every stored chunk of A, B and O is compared byte for byte, without decoding it. Each position is then one of:

| Comparison | Meaning | What happens |
| --- | --- | --- |
| All three are the same | Neither side changed it | Copied as stored |
| A and B are the same, O is not or has none | Both sides changed it alike, or generated the same chunk | Copied as stored, unless it lies next to a chunk only one side changed; then it is merged, because it holds changes of both sides |
| B is O, A is not | Only A changed it | A's chunk is decoded once to check it is sound, then copied as stored. If it names a map that is renumbered, it is written again instead; see Map ids |
| A is O, B is not | Only B changed it | The same, with B's chunk |
| All three differ, or a slot is damaged | Both changed it | Decoded and merged |

Chunks stored with different compression are compared by their decompressed bytes. Chunks that hold the same data in different bytes are merged, which gives the same result at a higher cost.

**Where copying is not enough.** A copied chunk is only right if the merge could not have changed it. The merge changes blocks across chunk borders in two ways, unit repair and the neighbour refresh, and both start where blocks of the two sides end up next to each other: in a chunk both sides changed, where a chunk only A changed touches a chunk only B changed, or where a chunk both changed alike touches a chunk only one of them changed. From there unit repair reaches at most 27 blocks and the refresh one more, which is less than 2 chunks. So every chunk within 2 chunks of such a place is decoded and merged, whatever the comparison said about it, and every other chunk is copied.

**Pieces.** The chunks to merge are handled in squares of 4, 8, 16 or 32 chunks. A square is merged together with every chunk within 2 chunks of it that either side changed, and only the square's own results are kept. Because of the 27-block bound, each square gets exactly the result that merging the whole dimension at once would give it. Squares are merged on several threads and their results written per region file. The tool picks the square size and the number of threads from the JVM's heap limit, its processor count and the compression of the output: a region's chunks are held as they will be stored until the region is complete, and then written to its file one after the other.

**Entities** are merged as one set per dimension, because an entity can move from any chunk to any other. An entity chunk that is the same in all three worlds is copied as stored, without being read, and its entities stay out of the set. One that both sides changed alike is read: its entities are the same on both sides, but only the base says what both no longer have there, such as the cow one side killed and the other led into the next chunk, or the tadpole that became a frog on both sides and hopped away. If a merged entity lands in such a chunk, the chunk joins the merge, and it is the only one read again. The copied chunks are read only if a leash points at an entity outside the set.

**POI** entries are copied as stored from the input that the chunk's blocks came from.

**What this gives up.** A chunk that is byte for byte the same on both sides is never decoded, so damage inside it is carried over without a report. Damage that shows in the region file's header is still found and refused. A chunk only one side changed is decoded before it is copied, so a damaged or unfinished one is still handled by the rules above.

**Relight modes.** `--relight neighbours` also decodes the chunks next to the merged ones. `--relight all` rewrites every chunk, so it costs time in proportion to the world. A chunk that would be copied is not merged for it: its heightmaps and light are taken off its stored tags and it is written again, without unpacking its blocks. That gives the same chunk as decoding it would, and like any copied chunk it is not checked inside.

**The reference.** `WorldMerger.Tuning` can turn the copying off. Every chunk is then decoded, merged with every chunk around its square whether changed or not, and written again. That way does not lean on the comparison above, and the tests and `MergeCheck` compare the other ways of dividing with it.

## World file rules

These cover the files outside region and entity storage. In 26.3 game rules, weather and day time are separate files, not parts of `level.dat`.

| Data | Rule | On conflict |
| --- | --- | --- |
| `level.dat` `Time` | Maximum | — |
| `level.dat` `spawn` and other fields | Whole-value 3-way per field | Newer side; recorded |
| `data/minecraft/world_clocks.dat` | Maximum `total_ticks` per clock | — |
| `data/minecraft/game_rules.dat` | Per rule, 3-way | Newer side |
| `data/minecraft/weather.dat`, `data/minecraft/wandering_trader.dat`, `data/minecraft/random_sequences.dat` | Newer side. The first two are timers the game runs by itself; the third is the state of its loot random numbers, which changes whenever loot is rolled | — |
| `data/minecraft/world_gen_settings.dat` | Must be equal on all three inputs | Refuse the merge |
| A dimension's `data/minecraft/raids.dat` | Discarded | — |
| A dimension's `data/minecraft/chunk_tickets.dat` | The forced chunks as a set: a ticket either side added is kept, a ticket either side removed is gone | — |
| Every file of a dimension that `--dimension` leaves out | The newer side's file | — |
| Other files under `data/` and a dimension's `data/` | Whole file 3-way | Newer side; recorded |
| Player data, one file per player in `players/data` | Whole file 3-way, except the recipe book, map ids and entities it holds; see Player files | Newer side; recorded, and the other version is written as a backup next to it |
| `players/data/*.dat_old`, the game's backup of a player file | Whole file 3-way | Newer side |
| Advancements, `players/advancements` | Union of completed criteria | — |
| Statistics, `players/stats` | Per counter, the base's number plus what each side added; see Statistics | — |
| Maps, `data/minecraft/maps/<id>.dat` | Whole file 3-way. Maps both sides created under the same id are renumbered; see Map ids | Newer side |
| `data/minecraft/maps/last_id.dat` | The highest map id in the merged world | — |

The player and map paths were confirmed in a world saved by the 26.3 client. `level.dat` holds no copy of the player, only a `singleplayer_uuid`. See the play session in `docs/SCENARIOS.md`.

**Player files.** A player's file is one value. Merging an inventory slot by slot from two play sessions would duplicate or lose items, so the side that changed the file wins, and the newer side if both did. Three things inside it follow rules of their own:

- **The recipe book.** When both sides changed the file, `recipeBook.recipes` and `recipeBook.toBeDisplayed` merge 3-way as sets: what either side added is kept and what either side removed is gone. Advancements merge as a union, and the game does not unlock a recipe again for an advancement that is already done. With the newer side's book alone, the older side's recipes would be lost for good.
- **Map ids**, below.
- **The entities it holds**: the vehicle the player left the game in and a parrot on a shoulder; see Entities in player files.

A player file that none of these rules changes is written as the game stored it.

The backup of the losing version is `<uuid>.dat.<side>-backup-<n>`, with the first number that no input uses. The inputs of a later merge come from a merged world and can hold backups already; those stay as they are.

A player file that cannot be read counts as not changed by its side: the base's version stands in for it, or the other side's if the base has none, and it is recorded as refused. A damaged file must not win over a good one because its side happens to be the newer.

**Statistics.** Every number under `stats` is a running total. The merged number is `ours + theirs − base`, with a number the base lacks counting as 0. A number that is lower on a side than in the base is not a total; then the larger of the two sides is taken. Two statistics restart at zero instead of counting up, `minecraft:time_since_death` and `minecraft:time_since_rest`. They are listed in the rules file as `stat_timers` and merge as plain values: the side that changed one wins, and the newer side if both did.

**Map ids.** The game numbers maps from one counter, `last_id.dat`, and an item shows a map by naming its id in its `minecraft:map_id` component. Two sides that each create a map after the fork give it the same id, for two different maps.

- If both sides created maps, the older side's new maps are renumbered to follow the newer side's: map `n` becomes `n + (the newer side's last id − the base's last id)`.
- The renumbering applies to the map's file and to every `minecraft:map_id` component in the older side's chunks, entities and player files, at any depth, so a map in a shulker box in a chest is found.
- A chunk, entity or player file that is the same on both sides is left as it is.
- Each renumbered map is recorded as resolved.
- A map the base already had is one file under one id. If both sides redrew it, the newer side's file is taken, unrecorded.

## Conflict report

The report is one JSON file. Every entry has a category and a severity. `conflict` means both sides changed the same thing in different ways. `resolved` means a rule settled it without a choice between real edits.

```json
{
  "dataVersion": 5023,
  "newer": "ours",
  "summary": { "conflict": 12, "resolved": 3408, "refused": 0 },
  "unknownBlocks": ["somemod:machine"],
  "chunksWithoutAncestor": 0,
  "entries": [
    {
      "severity": "conflict",
      "category": "block",
      "dimension": "minecraft:overworld",
      "pos": [104, 63, -220],
      "base":   "minecraft:oak_planks",
      "ours":   "minecraft:stone_bricks",
      "theirs": "minecraft:glass",
      "winner": "ours"
    }
  ]
}
```

Categories: `block`, `biome`, `unit`, `block_entity`, `container_slot`, `entity`, `entity_reference`, `player`, `level`, `file`, `unknown_field`, `no_ancestor`, `refused`. Entries are sorted by dimension, category, then position, so the report itself is deterministic. `unknownBlocks` lists, sorted, each block id that no loaded block report knows and that was found in a chunk merged cell by cell; chunks that are copied are not searched. `chunksWithoutAncestor` counts the chunks that were finished on both sides, had no ancestor, not even in the generated base, and in which at least one conflict was recorded. Chunks that both sides generated alike differ only in the game's bookkeeping; nothing is lost in them, so they are not counted.

## Determinism

- The merged world is a pure function of the three inputs, the `--newer` flag and the optional generated base.
- Determinism is defined on decoded NBT, not on compressed bytes. Compressed bytes depend on the compression library version.
- A chunk, entity chunk or POI chunk that is taken whole from one input is copied as that input stores it. Everything the tool encodes itself is canonical: palettes ordered by first use in index order, minimum bit widths, compound keys sorted, block entities ordered by position and entities ordered by UUID. Chunks are written to region files in slot order. Scheduled ticks keep their order, because it can decide which tick runs first.
- Region header timestamps are set to the maximum of the input timestamps for that chunk, never to the current time.
- No hash map iteration order, wall clock, random source or thread scheduling may affect the output. Parallel merging is allowed only if results are combined in a fixed order.
- How the work is divided, into how many threads and how large pieces, does not affect the output either. `MergeCheck` in the test sources merges the same worlds whole and in the smallest pieces and compares the results.
- `tools/mc/world_digest.py` prints one digest of everything a world folder holds once decompressed, list order included. Two runs of the same merge, on any machine and Java version, must print the same digest.

## Project layout

- One Gradle module, `merge`, on Java 21, with the Gradle wrapper checked in and pinned to Gradle 9.9.1. Gradle 9.9.1 is installed on the dev machine through SDKMAN, so the wrapper can be generated with `gradle wrapper --gradle-version 9.9.1`.
- Running Minecraft 26.3, including its dedicated server for test worlds, needs Java 25. The dev machine has Java 17 and 21 through SDKMAN, so Java 25 must be installed before any test that loads a world in the game. The library itself compiles for Java 21 and runs unchanged on Java 25.
- Base package `mycelium`, with packages `nbt`, `region`, `chunk` (models and codec), `merge`, `rules` (block tables, noise and unit rules), `report`, `cli`, and `json` (a small reader and writer for rules files and the report).
- No dependency on Minecraft or Fabric classes. The only runtime dependency is an LZ4 codec; the handful of command-line options are parsed by hand. The LZ4 codec is `at.yawk.lz4:lz4-java`, the library 26.3 itself bundles.
- Test dependencies: JUnit 5 and a property-testing library such as jqwik.

## Testing

**Codec tests.** Fixture region files from a real 26.3 world, checked into the repo:

- Read then write every chunk without changes, and confirm the decoded NBT is identical.
- Decode then encode every section, and confirm the block states are identical.
- Load the rewritten world in 26.3 and confirm it opens with no errors in the log.

**Property tests** on generated sections and chunks:

- `merge(O, A, O) == A` and `merge(O, O, B) == B`.
- `merge(O, A, A) == A`.
- Swapping A and B while also swapping `--newer` gives the same result.
- Running the merge twice gives identical decoded output.
- No item appears in the output that was not in at least one input.
- Merging a dimension one chunk at a time, each with the changed chunks around it, gives what merging it all at once gives. The worlds are strips of doors, beds and chests placed at random by both sides across chunk borders.

The first three are also tested on a whole world, the play-session fixture: an unchanged side leaves the other side's world, and swapping the sides with `--newer` gives the same world.

**World tests.** On worlds built from the fixtures, with the whole tool: every exit code; the refusals (version, world-gen settings, a world open in the game); a second and a nested dimension, and `--dimension`; game rules, clocks and `level.dat` fields; the LZ4 and the uncompressed fixture; damaged chunks, entity chunks and player files; the decision to copy a chunk instead of merging it, on layouts where copying would be wrong; and the creature rules from world folders: an egg both sides hatched, a creature that turned into another, and a bee nest both sides changed.

**Scenario worlds.** Fork a 26.3 world by copying it, play both copies separately, then merge. Each scenario has a written expected result:

1. Building in different areas of the same chunk.
2. Building the same block differently, which must give one recorded conflict.
3. Farms growing on both sides.
4. Furnaces running on both sides.
5. Mobs wandering across chunk borders, including a mob on a leash and a player-ridden boat.
6. Both sides taking items from the same chest.
7. A door placed on one side where the other side placed a block in its top half.
8. A bed and a double chest across a chunk border.
9. Each side exploring new chunks, with and without overlap.
10. A world with blocks from a mod that the tool does not know about.
11. Light across the border between a chunk taken from each side. This one measures a known limit; see Derived data.
12. A sapling that becomes a tree on one side only, and one that becomes a tree on both.
13. A garden of everything the game changes by itself, with a little time passing on both sides.
14. The same garden with a lot of time passing on both sides.
15. A player working in that garden on one side while only time passes on the other.
16. Animals, eggs and bees over a day on one side and two days on the other: every creature must be in the merged world once.
17. Mobs that go through a nether portal on one side.

The server console has no player. Scenario 5's player-ridden boat, player data, advancements, statistics and maps are covered by one play session in the 26.3 client instead. Its three worlds, cut down to what those rules read, are a test fixture.

**Acceptance.** Milestone 1 is done when:

- [ ] All codec, property and scenario tests pass.
- [ ] Every merged scenario world loads in 26.3 with no errors, and its heightmaps and villages recover. Lighting recovers within the limit of the chosen `--relight` mode: with `changed`, light that should enter a merged chunk from an unmerged neighbour is not restored.
- [ ] Running the merge on two different machines gives identical decoded output.
- [ ] The answers to the open questions below are written down.

## Open questions

- Lighting: is the default `--relight changed` good enough in real bases, or should the default be `neighbours` or `all`? Answered from scenarios 1 and 11: `changed` is right inside a chunk that mixes both sides, and wrong at the border between a chunk of each side, which is the ordinary case of two players building next to each other. Only `all` is right there, at the cost of rewriting and relighting the whole world. `changed` stays the default of the tool, and the mod has to run the light engine on what was merged; see Derived data. Still open: does `all` hold when chunks are first loaded in different play sessions?
- Is per-UUID merging enough for vehicles, leashed mobs and riders, or do some cases need special handling? Answered for two cases: an entity a player's file holds needs the rules under Entities in player files, and terrain both sides generated needs the rule for generated wildlife. Scenario 16 added four more, each with a rule under Entities: an entity one side no longer has while it only moved on the other; a creature that became another on both sides; an egg that hatched on both sides; and bees. An entity that changes dimension on one side falls under the first: the dimension it left no longer has it. Scenario 17 runs it: a mob that went through a portal on one side is in the Nether and nowhere else, whether the other side moved it, left it alone or sent it through as well. One case is left and accepted as a limit: if the other side did something a player does to it, such as naming it, it is in both dimensions; see Entities.
- Which scheduled ticks are safe to keep after a merge, and does dropping the rest leave visible glitches such as stuck water? Answered: a tick is kept with the block it belongs to and never dropped while that block stays, so nothing that was scheduled is lost. Where a block of one side ends up next to a block of the other, both are listed for the neighbour refresh, which makes fluids there flow; that was checked in the game (`tools/mc/probe_postprocessing.py`). The merged scenario worlds run for 200 ticks before their log is read.
- Which block properties count as noise in 26.3, beyond the rules above? Answered: the list is `rules-26.3.json`, generated from the block report by `tools/mc/gen_rules.py`, which names every property it assigns to a rule and every one it deliberately leaves out, and does the same for every block the game ticks at random.
