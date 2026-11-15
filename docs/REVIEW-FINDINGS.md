# Review of the merge tool — findings

Nov 14, 2026 · Five independent reviews of `merge/`, one aspect each: the block merge rules, the format layer, the division of work, entities and players, and the evidence. This is the deduplicated list. All of it was then taken on; the decisions and the status of every finding are at the end.

**Result.** The core is sound: the 3-way cascade, the symmetry between the sides, the 24-block bound behind merging in pieces, the map and vehicle rules, determinism and thread safety all held up. The defects are at the edges. Eight of them lose or duplicate things in ordinary play, without a report. Seven more make damaged input cost far more than the damaged part. Most of the first group are rules the tool follows faithfully, so they are spec changes as much as code changes.

"Verified" says how each finding was checked after the reviewers reported: **run** means reproduced with the built tool or in the game, **read** means confirmed in the code, **unconfirmed** means plausible and not yet shown.

## A. Things lost or duplicated in ordinary play

| # | Finding | Where | Trigger and result | Verified |
| --- | --- | --- | --- | --- |
| A1 | Terrain both sides generated on their own gets both sides' entities | `EntityMerger` rule "present on one side only"; `--generated-base` is not used for entities | Both friends explore the same new area. Generated animals and chest minecarts have random UUIDs, so each side's are kept: twice the animals, and loot twice | Run: the same 64 chunks generated twice from one seed hold the same 5 chickens and 5 chest minecarts with no UUID in common |
| A2 | A block entity is taken whole from the side that changed the block state | `ChunkMerge.blockEntities`, the spec's "Block entities follow their block" | Ours places a chest next to a chest, which turns the first into `type=left`. Theirs puts items in the first chest. Theirs' items are gone, unreported. The same for a furnace that is lit on one side | Read |
| A3 | Where both sides changed a block the same way, the base's block entity is ignored | `ChunkMerge.java:256` | Both light a furnace with 20 coal. Ours leaves 12, theirs takes all. Result: 12 coal, no conflict | Read |
| A4 | A loot chest opened on both sides merges as the union of both | `BlockEntityMerge`, per-slot rule with an empty base | Ours takes the diamond, theirs leaves it. The diamond stays in the chest and is in ours' inventory | Read |
| A5 | A stack both sides moved to different slots is there twice | `BlockEntityMerge`, per-slot rule | Base has a stack in slot 0; ours moves it to slot 5, theirs to slot 7. Both slots hold it, unreported | Read |
| A6 | "Higher stage" undoes a harvest | `Rules.propertyNoise`, which does not see the base | Base bush at age 2. Ours picks it (age 1), theirs lets it grow (age 3). Result: age 3, and the berries exist twice. The same for wheat harvested and replanted | Read |
| A7 | A second merge overwrites the new player backup with the old one | `WorldFiles.merge`: an input `<uuid>.dat.ours-backup` goes through the whole-file rule after the new backup is written | Merge, play both copies of the result, merge again. The older side's inventory of the second round is lost | Run |
| A8 | A parrot on a player's shoulder has no rule | `PlayerVehicles` reads only `RootVehicle`; the game also stores `ShoulderEntityLeft` and `ShoulderEntityRight` | Like the boat: lost if the other side's player file wins, stored twice if the other side had its chunk loaded | Read; the tag names are in the 26.3 jar |
| A9 | An entity one side removed comes back if the other side only had it loaded | `EntityMerger`, row "removed, modified"; nothing is recorded | Ours picks up a dropped item; theirs had the chunk loaded. The item is back on the ground and in ours' inventory | Read. Listed as a known limit, but it duplicates items silently |
| A10 | Light is stale between a chunk taken from ours and a neighbour taken from theirs | `ChunkMerge` derived data; `--relight neighbours` does not cover it | Ours places a torch at the edge of one chunk; theirs builds in the next. The neighbour keeps theirs' light: a dark band beside the torch | Unconfirmed; needs the game |

## B. Damaged or hostile input

These matter more once worlds travel between machines.

| # | Finding | Where | Trigger and result | Verified |
| --- | --- | --- | --- | --- |
| B1 | An unreadable entity slot or region on one side deletes the other side's intact entities | `EntityRegions.entities` returns an empty list, which the merge reads as "removed" | Ours' entity slot is corrupt; theirs equals the base. Every entity there is removed. Block chunks with the same damage are written from the readable side | Read; found by three reviewers |
| B2 | One entity without a valid `Pos` empties every merged entity chunk of the dimension | `Entities.flatten` checks the UUID only; `EntityIndex.toChunks` throws; `EntityRegions` catches for the whole dimension | One such entity on either side | Run by the reviewer against the built jar |
| B3 | The last chunk of a region file that was not padded is refused | `RegionFile.java:176` requires whole sectors; the game needs only the payload | A world copied while the game runs or after a crash. That side's last saved chunk in the file is dropped | Run for the tool; the game's side is read from its bytecode |
| B4 | A 4 KB chunk runs the tool out of memory | `NbtIo.readList` allocates the declared length at every nesting level | 500 nested lists declaring 4 million elements each. Exit 3 for the whole merge instead of one refused chunk | Run by the reviewer |
| B5 | Deeply nested JSON overflows the stack; a gzip file has no size limit | `Json.value`, `NbtIo.readGzipFile` | A crafted advancements file, or a player file that inflates to 2 GB | Run for JSON; read for gzip |
| B6 | Copying a damaged slot that was never decoded aborts the merge when compression differs | `RegionWriter.Record.copy` decompresses to recompress | An LZ4 world merged to zlib with one damaged chunk that is the same on both sides, or a damaged POI slot. Exit 2 with the output half written | Run by the reviewer |
| B7 | An undecodable player file can win | `WorldFiles.playerData` falls back to comparing bytes | Ours' player file is cut short and ours is newer: the broken file is written and the good one is the backup | Read |
| B8 | Smaller: `r.007.0.mca` aborts the merge; a recipe list of another type throws; a duplicate UUID on one side is dropped unreported; unknown top-level tags of a merged entity chunk are dropped | `RegionPos.parse`, `ListTag.of`, `EntityIndex` | — | Read |

## C. Contract and spec

| # | Finding | Where | Verified |
| --- | --- | --- | --- |
| C1 | An I/O error after output was written exits with 2, which the spec defines as "writes nothing". Pool threads can still be writing after `merge()` has thrown, which matters when the mod calls the library | `Main`, `Storage.parallel`, `WorldMerger.merge` | Read |
| C2 | The version gate checks only `level.dat`. The spec says it covers chunks, entity chunks and data files, and contradicts itself on whether a mismatch refuses the chunk or the merge. A world the game upgraded lazily gets per-chunk refusals | `WorldMerger.requireVersion` | Read |
| C3 | `--dimension`: the other dimensions are merged with themselves instead of copied, and their data files and players are still merged 3-way, so a vehicle there can be lost or stored twice | `WorldMerger.mergeDimension`, `WorldFiles` | Read |
| C4 | A dimension id with a nested path, such as `mymod:worlds/mining`, is not merged as chunks | `WorldMerger.dimensions` lists two folder levels | Read; the game's folder layout for such ids is not checked |
| C5 | Nothing stops a merge of a world that is open in the game | No look at `session.lock` | Read |
| C6 | Chunks that are byte-equal on both sides but differ from the base count as unchanged in the plan, which is not what a whole merge does | `Plan.meets`, `Plan.changed`, `RegionMerge.copy` | Read. Needs a base older than the fork point, so it is for the later milestones |
| C7 | Unit repair stops after 8 rounds and can leave a torn unit unreported; it deletes an orphan chest half where making it a single chest would lose nothing; a unit block without `facing` throws | `UnitRepair`, `Rules.unit` | Read; the first needs a contrived input |
| C8 | Memory: a region's output is held twice until it is written, which `Tuning.fitting` does not count. `--compression none` may not fit a heap that zlib output fits | `RegionMerge.records`, `RegionWriter.write` | Unconfirmed |
| C9 | The entity phase reads every unchanged entity chunk, and again on each pass of the join loop | `EntityRegions` | Read; size of the effect not measured |
| C10 | Smaller: entities the base holds inside unfinished chunks are reported as "new on both sides"; a leash held by a player may be reported as dangling; two ticks for one position are kept when only the delay differs; the spec's library entry points and its sentence on unfinished chunks do not match the code | — | Read or unconfirmed |

## D. Evidence

| # | Gap | Verified |
| --- | --- | --- |
| D1 | The two-machine check has not been run. `CompareWorlds` ignores list order and everything outside region storage, so it could not show a difference in player files, maps or entity order | Read |
| D2 | The scenario scripts freeze the merged world before it ticks, so "clean log" means clean on load | Read |
| D3 | Results that `docs/SCENARIOS.md` states and no script asserts: scenario 1's light, heightmaps and light in scenario 9's merged chunks, "the one real conflict" with a generated base, exit codes | Read |
| D4 | The decision to copy instead of merge is tested by one hand-built layout. The branch for "a chunk only ours changed next to one only theirs changed" could be deleted with all tests green | Read |
| D5 | No automated test for: `--dimension`, any dimension but the overworld, the world-gen refusal, exit code 3, game rules, world clocks, `level.dat` fields, a merge of the LZ4 and uncompressed fixtures | Read |
| D6 | Most properties of the spec's list are tested at section level only | Read |
| D7 | Stale text in `docs/SPEC-FINDINGS.md`, a plant count in `docs/FORMAT-26.3.md`, a comment in `measure_merge.py` | Read |

## Found sound

- **Cascade and symmetry.** The 3-way rule in blocks, biomes and compounds, with and without a base. Swapping the sides together with `--newer` gives the same world.
- **Merging in pieces.** Every unit partner is one step away, so unit repair reaches about 16 blocks; `REACH = 24` and `HALO = 2` are safe. The change to `changedCells` cannot skip a unit or a refresh pair.
- **Determinism and threads.** No result depends on hash order, file listing order, the clock or scheduling. Each task has its own handles and output files.
- **Vehicles and maps.** `holds`, `settle` and `apply` agree in every case walked. The map shift is right with no maps in the base, with gaps and with a missing counter, and cannot collide.
- **Statistics, advancements, recipe book.** The game ignores `done` on load, so the union of criteria is enough.
- **Format layer.** Truncated and oversized NBT arrays, bit packing, sector layout, negative coordinates, external chunks and float bits all behave.

## Decisions for the fixes

Taken on Nov 14, 2026, when all findings were accepted for fixing. Each is in the spec once its fix is done; the status of every finding is in the table at the end.

| # | Decision |
| --- | --- |
| A1 | In terrain without a finished base chunk, an entity new on the older side is left out if it shows no sign of a player and the newer side has a new entity of the same type there to pair it with. Counted per type over the dimension, recorded once per type |
| A2, A3 | A block entity follows its block's id, not its full state. Same id on both sides: merged 3-way, against the base's block entity if the base block has that id too. Different ids: it follows the block, and a conflict is recorded if the other side had changed its block entity |
| A4 | A container whose base still has its loot table and that both sides opened is taken whole from the newer side, recorded as a conflict if the sides differ |
| A5 | After the slot merge, an item of which the container holds more than `ours + theirs − base` is reduced in the slots that only the older side filled with it, and recorded. Scenario 4 showed that anything broader reports a furnace that burned the same coal on both sides |
| A6 | A "higher" property takes the value of a side that went below the base's value, because that side reset it by hand. Leaf decay applies only if the other side's leaf differs from the base by noise. A fluid source one side placed wins over the other side's flow |
| A7 | Backups are numbered, `<uuid>.dat.<side>-backup-<n>`, with the first number no input uses |
| A8 | `ShoulderEntityLeft` and `ShoulderEntityRight` are held entities like `RootVehicle` |
| A9 | For entities that only exist for a while (dropped items, experience orbs, projectiles, falling blocks), a removal wins over a change that touched no item or player mark. For everything else the changed entity is still kept, and now recorded |
| A10 | Cannot be fixed in the tool, which does not compute light. Measured in the game and written down as a limit |
| B1 | A side whose entity slot or region cannot be read counts as unchanged there: it takes the base's entities, or the other side's if the base has none |
| B2 | `Pos` is checked when an entity chunk is read, so only that chunk is refused. If the entity merge still fails, the newer side's entity chunks are written as stored |
| B3 | A chunk is readable if its payload is in the file, whatever the padding. Confirmed in the game first |
| B4, B5 | List lengths are checked against the smallest size of their elements, lists grow as elements arrive, a tree may hold 8 million tags, JSON may nest 256 deep, a gzip file may inflate to 256 MiB |
| B6 | A stored slot that cannot be recompressed is copied with its own compression |
| B7 | An unreadable player file counts as unchanged: the base's version stands in for it, or the other side's. Recorded as refused |
| C1 | Any failure after the output folder was created exits with 3 and says the folder is incomplete. The dimension is checked before the folder is created. The pool is awaited before `merge` returns or throws |
| C2 | The spec changes: the version gate covers `level.dat`. Chunks and entity chunks of another version are copied if untouched and refused if they would need merging |
| C3 | Dimensions that are not selected are copied file by file from the newer side, with their data files. A player whom either side has in such a dimension is taken from the newer side, with the usual backup and record |
| C4 | A dimension is any folder under `dimensions/` that holds `region`, `entities` or `poi`, at any depth |
| C5 | The tool takes the lock on each input's `session.lock` and refuses if the game holds it |
| C6 | A chunk that is the same on both sides but not the base's counts as changed by both: it makes a meeting with a neighbour only one side changed, and it is in `around`. The reference mode of `Tuning` takes every chunk around a square |
| C7 | A unit still broken after the last round is recorded. An orphan half of a double chest becomes a single chest and keeps its contents. A unit block without `facing` is not a unit |
| C8 | Region files are written record by record. The heap estimate counts a region's output, by compression |
| C9 | Unchanged entity chunks are read only if a leash points outside the merged set, and a chunk that joins the merge is the only one read again |
| C10 | Entities new on both sides under one UUID are a conflict only if items differ. A leash held by a player is not dangling. One tick per position and type |

## Status

Nov 14, 2026, after the fixes. "Fixed" means the rule is in the spec, the code follows it and a test or a run in the game shows it.

| # | Status | Shown by |
| --- | --- | --- |
| A1 | Fixed for animals, monsters and unopened loot containers. Boats, minecarts and item frames are still kept from both sides, because nothing tells a placed one from a generated one | `ContainerAndEntityMergeTest.terrainBothSidesGeneratedDoesNotGetItsWildlifeTwice` |
| A2, A3 | Fixed | `ChunkMergeTest.aBlockIsTheSameBlockWhenOnlyItsStateChanged`, `aBlockEntityFollowsItsBlock` |
| A4 | Fixed | `ContainerAndEntityMergeTest.aLootChestBothSidesOpenedIsTheNewerSidesNotBothRolls` |
| A5 | Fixed for stacks that moved. Where a slot conflict is recorded for an item, its count is left to the later rebase, as before | `aStackBothSidesMovedIsThereOnce` |
| A6 | Fixed | `RulesTest.aHarvestIsNotUndoneByGrowthOnTheOtherSide`, `aLeafOrFluidAPlayerPlacedIsNotNoise` |
| A7 | Fixed | `PlaySessionMergeTest.aSecondMergeKeepsTheBackupOfTheFirst` |
| A8 | Fixed from the jar's tag names; no world with a parrot on a shoulder was looked at | `anEntityOnAPlayersShoulderIsKeptOnceLikeAVehicle` |
| A9 | Fixed for dropped items and other short-lived entities. A mob one side killed and the other only had loaded still comes back, and that is now recorded | `aDroppedItemOneSidePickedUpStaysGone` |
| A10 | Confirmed in the game and not fixable in the tool. With `changed` and `neighbours` the neighbouring chunk is dark at 9 of 18 positions; with `all` it is right. Written into the spec as a limit, with the mod's light engine as the fix | Scenario 11 |
| B1, B2 | Fixed | `anEntityChunkOneSideCannotReadDoesNotDeleteWhatTheOtherSideStillHas`, `anEntityWithoutAPositionCostsItsChunkNotTheDimension` |
| B3 | Fixed, after the game confirmed it loads such a chunk | `RegionFileTest.theLastChunkOfAFileThatWasNotPaddedStillReads` |
| B4, B5 | Fixed | `NbtIoTest.aSmallFileCannotUnfoldIntoGigabytes`, `JsonTest` |
| B6 | Fixed | `RegionFileTest.aStoredChunkThatCannotBeRecompressedIsCopiedAsItIs` |
| B7 | Fixed. A vehicle that is only in the unreadable file is still lost with it | `aPlayerFileThatCannotBeReadDoesNotWin` |
| B8 | Fixed | `regionFileNamesParse`; duplicates and unknown tags of entity chunks in `EntityRegions` |
| C1 | Fixed | `WorldMergerTest.aFailureAfterWritingBeganExitsWithThreeAndSaysTheOutputIsIncomplete` |
| C2 | The spec is changed to what the tool does, and says why | Spec, "Version gate" |
| C3 | Fixed | `everyDimensionIsMergedAndOneCanBeSelected` |
| C4 | Fixed in the tool. The game's folder layout for an id with a nested path was not checked in the game | The same test, with `mymod:worlds/mining` |
| C5 | Fixed, and tried against a running server | `aWorldThatIsOpenInTheGameIsRefused` |
| C6 | Fixed | `aChunkBothSidesChangedAlikeStillMeetsAChunkOneSideChanged` |
| C7 | Fixed | `DimensionMergerTest.aRunOfRepairsThatDoesNotEndIsStoppedAndSaid`, `overlappingDoubleChestsSettleOnTheNewerSide`, `RulesTest.aUnitBlockWithoutItsFacingIsNotAUnit` |
| C8 | Region files are written record by record, and the heap estimate counts the output by its compression | See `docs/MEASUREMENTS.md` |
| C9 | Fixed | The play session and large-world checks |
| C10 | Fixed | `oneUuidOnBothSidesWithoutABaseIsOneEntityNotAConflict`, `aLeashHeldByAPlayerIsNotDangling` |
| D1 | `tools/mc/world_digest.py` covers every file and list order, and `CompareWorlds` covers every file. The same merge gives the same digest under Java 21 and Java 25 and with two divisions of the work. The run on a second machine is still to do | — |
| D2, D3 | Fixed in `tools/mc/scenariolib.py` and `scenarios.py`. The stricter runs also showed that one check of scenario 5 failed now and then: it compared a mob with where `/tp` had put it, and a mob walks a little after it is moved. It now compares with the newer side's world | The scenario runs; scenario 5 passed 8 times in a row afterwards |
| D4 | Fixed with two layouts where copying is wrong. Breaking the copy decision on purpose makes both tests fail | `changesOfTheTwoSidesInNeighbouringChunksAreMergedNotCopied` and the C6 test |
| D5 | Fixed | `WorldMergerTest` |
| D6 | An unchanged side and swapped sides are now tested on a whole world. The "no item invented" property is still tested by examples only | `PlaySessionMergeTest` |
| D7 | Fixed | — |
