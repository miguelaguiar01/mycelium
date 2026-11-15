# Time and memory on large worlds

Nov 13, 2026 · Measured with `tools/mc/build_large_world.py` and `tools/mc/measure_merge.py` on an Apple Silicon Mac with 12 cores and 36 GB of memory, Java 21.0.4 (Temurin) with its default collector.

**Result.** A world of 64 regions merges in 1 to 5 seconds when the sides changed little, and in 30 seconds when both sides changed every chunk; 24 seconds since Nov 15, when the tool began to compress at zlib level 4. It needs no memory setting: the worst case finishes with a 256 MB heap. The first build of the tool could not merge this world with a 24 GB heap.

## What was measured

Three worlds per dimension, all made by the 26.3 server from the seed `mycelium`:

- **Base.** A square of whole regions of natural terrain: 64 regions in the overworld, 16 in the Nether, 16 in the End.
- **Ours and theirs.** Copies of the base in which the game loaded every chunk, placed one block per chunk, a container in every 64th chunk and a block at the same position on both sides in every 64th chunk, and then ran 100 ticks.

Smaller worlds and lighter cases are cut from these by region file:

| Case | Meaning |
| --- | --- |
| none | Neither side changed anything |
| some | Each side played in 2 regions, 1 of them the same region: 3,072 chunks changed, 1,024 of them by both |
| all | Both sides changed every chunk |

Each case ran with the JVM's default heap limit, which is a quarter of the machine's memory (9 GB here), and then with limits of 4 GB, 2 GB, 1 GB, 512 MB, 256 MB, 128 MB and 64 MB. A 2 GB limit is the default on a machine with 8 GB of memory.

## Overworld

Run time in seconds.

| Regions | Chunks | Size on disk | Case | Default heap | 2 GB heap | 512 MB heap | Smallest heap that works |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 4 | 4,096 | 34 MB | none | 0.4 | 0.3 | 0.3 | 64 MB |
| | | | some | 2.5 | 2.8 | 3.2 | 64 MB |
| | | | all | 3.7 | 4.5 | 6.8 | 64 MB |
| 16 | 16,384 | 142 MB | none | 0.6 | 0.5 | 0.6 | 64 MB |
| | | | some | 2.9 | 3.0 | 3.1 | 64 MB |
| | | | all | 9.1 | 10.6 | 23.1 | 64 MB |
| 36 | 36,864 | 313 MB | none | 1.0 | 0.7 | 0.7 | 64 MB |
| | | | some | 3.8 | 3.5 | 3.7 | 64 MB |
| | | | all | 15.7 | 19.3 | 59.4 | 128 MB |
| 64 | 79,524 | 649 MB | none | 1.6 | 1.0 | 1.2 | 64 MB |
| | | | some | 5.2 | 3.5 | 3.8 | 64 MB |
| | | | all | 30.5 | 39.6 | 114.0 | 256 MB |

64 MB was the smallest limit tried. The 64-region world has 65,536 finished chunks and 13,988 unfinished ones that the game left around the square.

- **The cost follows the changes, not the world.** The "some" case takes about 3 seconds in a 4-region world and about 4 in a 64-region world. The same 3,072 changed chunks are merged in both; the rest is copied.
- **An unchanged world is copied** at 400 to 650 MB a second.
- **The worst case scales with the world**, at 0.4 to 0.9 ms per chunk with the default heap. The larger the world, the lower the figure, because the start of the program weighs less.
- **A small heap costs time, not success.** The tool merges in smaller squares on fewer threads when the heap is small. With 512 MB the worst case takes up to four times as long as with the default.
- **The process uses about as much memory as the heap limit allows** in the worst case: 9.9 GB resident with the 9 GB default, 2.3 GB with a 2 GB limit, 0.65 GB with a 512 MB limit. In the "some" case it stays at about 2 GB or less whatever the limit.

When the heap is too small, the tool stops with exit code 3 and says so. That happened only with 128 MB and less, late in the run, where the entities of the whole dimension are merged as one set.

### Before the rework

The first build decoded all three worlds in full before it merged the first chunk. With the heap it needed, it took:

| Regions | Chunks | Time, any case | Heap needed |
| --- | --- | --- | --- |
| 4 | 4,096 | 7 to 8 s | 1.5 to 2 GB |
| 16 | 16,384 | 28 to 31 s | 6 to 8 GB |
| 36 | 36,864 | 69 to 76 s | 12 to 24 GB |
| 64 | 79,524 | did not finish | more than 24 GB |

It needed about 0.45 MB of heap per chunk whether or not anything had changed. Half of that was block and biome cells unpacked to one 16-bit number per cell.

## Nether and End

Run time in seconds. The 16-region worlds have 16,384 finished chunks and 7,332 unfinished ones around them. Every case finished with a 64 MB heap.

| Dimension | Regions | Chunks | Size on disk | Case | Default heap | 2 GB heap | 512 MB heap |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Nether | 4 | 4,096 | 28 MB | none | 0.4 | 0.3 | 0.3 |
| | | | | some | 2.2 | 2.5 | 2.3 |
| | | | | all | 2.4 | 2.8 | 4.6 |
| Nether | 16 | 23,716 | 137 MB | none | 0.6 | 0.5 | 0.5 |
| | | | | some | 3.1 | 2.9 | 2.8 |
| | | | | all | 7.0 | 7.3 | 18.6 |
| End | 4 | 4,096 | 19 MB | none | 0.3 | 0.3 | 0.3 |
| | | | | some | 1.1 | 1.2 | 1.1 |
| | | | | all | 1.4 | 1.5 | 2.0 |
| End | 16 | 23,716 | 100 MB | none | 0.4 | 0.4 | 0.4 |
| | | | | some | 1.4 | 1.2 | 1.2 |
| | | | | all | 3.6 | 3.5 | 7.1 |

A Nether chunk costs about two thirds of an overworld chunk to merge and an End chunk about 40%. The tool merges one dimension after the other, so the run time of a world is the sum of its dimensions.

## Relight modes

The 64-region overworld, "some" case, default heap:

| `--relight` | Time |
| --- | --- |
| `changed` (the default) | 3 to 6 s |
| `neighbours` | 3 to 6 s |
| `all` | 14 s; 5 to 7 s since Nov 15 |

Since Nov 15, 2026 `--relight all` takes the light off a chunk that would be copied without merging it or unpacking its blocks. Run in turn on the same worlds, in seconds: before 14.2, 12.0, 10.9; after 5.4, 5.1, 6.6. Where both sides changed every chunk nothing is copied, and the time is the same as before.

Checked in the game with `python3 tools/mc/check_large_merge.py relit`: the 4-region "some" case merged with `--relight all` has no light or heightmaps on any finished chunk of two regions as written, and all 2,048 of them have both again after the game loaded and saved them.

`all` removes the light of every chunk, so it decodes and rewrites the whole world. With a 2 GB heap it takes 15 seconds.

## Chunks without an ancestor

Nov 14, 2026 · Found with the worlds of the play session in [SCENARIOS.md](SCENARIOS.md): a flat world of 35 MB, 8,462 chunks in the base, played by hand for a few minutes on each side.

That merge took 43 seconds. Both sides had rowed a boat the same way and generated the same 277 chunks beyond the base. Such chunks have no ancestor, and unit repair treated every one of their 98,304 blocks as changed. The large worlds above could not show this, because every chunk of theirs is in the base.

Unit repair now starts only from the blocks where the two sides differ, which is how it already decided whether a side had changed a block. The merged world is the same: on the played world and on scenario 9's worlds, whose chunks without an ancestor do differ, the builds before and after the change write the same world, with either side as the newer one.

| World | Before | After |
| --- | --- | --- |
| The played world, 277 chunks without an ancestor, alike on both sides | 43 s | 1.5 s |
| 4 regions of the large overworld, both sides changed every chunk, the base holds 1 region: 3,072 chunks without an ancestor that differ | ran out of memory after 9 minutes with the default 9 GB heap | 4.1 s |

The second row is the 4-region worst case of the first table with three of the base's regions taken away. With the whole base it takes 3.7 seconds. The three cases of the 64-region world were run again after the change and took 1.9, 5.3 and 30.1 seconds.

## After the review fixes

Nov 14, 2026 · The fixes of [REVIEW-FINDINGS.md](REVIEW-FINDINGS.md) add checks to the merge and change how region files are written, so the 64-region overworld was timed again. Run time in seconds, one run each:

| Case | Before the fixes | After |
| --- | --- | --- |
| none, default heap | 1.6 | 1.7 |
| some, default heap | 5.2 | 5.3 |
| all, default heap | 30.5 | 30.6 |
| all, 2 GB heap | 39.6 | 47.0 |
| some, `--relight all`, default heap | 14 | 18.2 |
| 4 regions, all, the base holds 1 region | 4.1 | 4.0 |

The two slower rows are within what a single run varies by; see the three runs each under Where the time goes.

**Uncompressed output.** A task holds the chunks of its region as they will be stored until the region is complete. Without compression that is about 150 MB per region, which the first heap estimate did not count. Region files are now written record by record, not built in memory first, and the estimate allows for the output by its compression. The worst case with `--compression none`:

| Heap | Time | Result |
| --- | --- | --- |
| 2 GB | 71 s | Finished |
| 512 MB | 333 s | Finished, on one thread |

## Where the time goes in the worst case

Nov 14, 2026 · `python3 tools/mc/profile_merge.py all` samples the tool's threads while it merges the 64-region overworld in which both sides changed every chunk, with the default heap. Share of the running worker samples:

| Work | Share |
| --- | --- |
| Compressing the output (deflate) | 22% |
| Decompressing the inputs (inflate) | 14% |
| Comparing the stored chunks, the first pass | 12% |
| Merging cells | 11% |
| Parsing NBT | 10% |
| Decoding chunks: palettes and sections | 9% |
| Encoding chunks | 7% |
| Reading and writing files | 6% |
| Writing NBT | 2% |
| Unit repair and neighbour refresh | 2% |

Reading the inputs is a third of the time, writing the output another third, and the merge itself a seventh.

**A single run varies.** Three runs each, in seconds: the worst case with the default heap 30.0, 26.5, 27.2; with a 2 GB heap 45.2, 42.5, 39.5; `--relight all` on the "some" case 15.9, 13.9, 13.9. The first run of a batch is the slowest. The two slower rows of the table above are within this spread.

**What would make it faster.** All five were tried on Nov 15, 2026. Three are in, one made no difference and one was dropped:

| Change | Why it helps | Expected gain in the worst case | Outcome |
| --- | --- | --- | --- |
| Compress with a lower level | On 300 real chunks, zlib level 4 takes 0.27 ms per chunk against 0.60 ms at the default level 6, for output 5% larger. Level 1 takes 0.17 ms for output 21% larger. The game compresses a chunk again whenever it saves it | About 12% with level 4 | Done: 11% faster. See below |
| Compare stored lengths before bytes | The first pass reads every chunk of all three worlds to compare them. In 975 of 1,024 changed chunks of one region the two sides' stored lengths already differ, so the bytes need not be read | About 10% | Built, measured, taken out: no gain. See below |
| Decode each chunk once per region | A square is merged with the changed chunks within 2 chunks around it, and those are decoded and merged again for the next square: 1.56 times the work with squares of 16 chunks, 2.25 times with squares of 8, which is what a 2 GB heap gets | About 12% with the default heap, about 25% with 2 GB | Dropped: it needs more memory, and more memory made the tool slower. See below |
| A faster NBT reader | The reader went through a synchronized byte stream one value at a time, also for the long arrays that hold the blocks | About 5% | Done: reading a chunk's NBT takes 0.045 ms, against 0.077 ms, on 400 chunks of a played world. In the worst case three pairs of runs taken in turn gave 29.6 and 25.9 s, 26.8 and 23.7 s, 24.5 and 23.4 s |
| Strip light without decoding blocks | `--relight all` decoded and encoded every chunk only to drop a few tags | About 20% of `--relight all` | Done: `--relight all` takes less than half the time where most chunks are copied. See Relight modes |

**Compression level 4.** The worst case, three runs each on the same build apart from the level, in seconds:

| zlib level | Runs | Output |
| --- | --- | --- |
| 6, the default | 27.1, 26.4, 26.9 | 541 MB |
| 4 | 23.8, 23.6, 24.1 | 561 MB |

Level 4 is 11% faster for a world 3.7% larger. The tool now writes zlib chunks at level 4. A chunk copied as stored keeps the bytes the game wrote.

**Comparing stored lengths first made no difference.** With it the worst case took 24.5, 24.4 and 24.8 s, and 27.1, 24.3 and 24.2 s; without it 27.6, 24.1 and 24.4 s, and 27.9, 24.5 and 24.2 s. The first run of each batch is the slow one. The profile counted reading the chunks under the first pass because the first pass was the first to read them. A chunk that both sides changed is read again to be merged, so skipping the first read only moves the cost to the second. The change is not in the tool.

**More memory does not make it faster.** Checked on Nov 15, 2026, to see whether the next speed-up in the table, decoding each chunk once per region, is worth building. It would hold more decoded chunks at a time. With a 24 GB heap the tool already merges a whole region at a time, which is the least repeated work it can do, 1.27 times against 1.56 with the default heap. The worst case, in seconds, run in turn:

| Heap | Squares of | Runs |
| --- | --- | --- |
| 9 GB, the default here | 16 chunks | 23.3, 23.9 |
| 9 GB | 8 chunks | 30.1, 31.4 |
| 9 GB | 4 chunks | 50.3 |
| 24 GB | 32 chunks | 33.4, 31.0 |
| 24 GB | 16 chunks | 32.8, 32.2 |
| 24 GB | 8 chunks | 33.4, 27.1 |

With the same heap, larger squares are faster, as expected. But the larger heap is slower whatever the squares, by more than the larger squares gain. So holding more in memory is not the way, and that speed-up is dropped. The parallel collector in place of the default one made no difference either: 28.6, 26.4 and 27.0 s against 26.1 and 27.0 s. Timings on this machine vary by 3 s from one batch to the next, so only runs taken in turn are compared.

One thing follows for machines with more memory than this one: their default heap is larger, and the tool may be slower there than here. Not measured on such a machine.

## How it works now

In short; the spec has the full account under "How the work is divided".

1. Every stored chunk of ours, theirs and the base is compared byte for byte, without decoding it.
2. A chunk that is the same on both sides is copied as stored. A chunk only one side changed is decoded once to check it, then copied as stored.
3. The rest is decoded and merged: the chunks both sides changed, the chunks where one side's changes touch the other's, and every chunk within 2 chunks of those. Unit repair and the neighbour refresh cannot reach further than that.
4. Merging is done in squares of 4 to 32 chunks on several threads. The tool picks both from the heap limit and the number of processors. Each square is merged with the changed chunks around it, so the result does not depend on how the work was divided.

## How the result was checked

- **Against the first build.** On cuts of the large worlds in all three dimensions, with each relight mode and each side as the newer one, the new build wrote the same world as the first build, compared chunk by chunk as decoded data.
- **Divided differently.** `./gradlew :merge:mergeCheck` merges the same worlds with every chunk decoded and merged in whole regions, with the default division, and in squares of 4 chunks on 12 threads. All three give the same world and the same report.
- **Not on the "some" cases.** They are glued together from region files of two worlds, so an animal that crossed a region border is in both files of that side, which no world of the game holds. The ways of dividing differ on such a world: the one that decodes everything finds the animal twice and keeps it once. These cases are for timing only.
- **A property test** merges random strips of doors, beds and chests one chunk at a time and all at once, and requires the same result. It fails if the chunks around are left out.
- **In the game.** `tools/mc/check_large_merge.py` merges the 4-region "all" case of each dimension and loads the result. In two regions per dimension it asks, chunk by chunk, for the block each side placed, the newer side's block where both placed one, and the containers with their contents. All 2,096 checks per region pass and the server logs are clean. The ten scenarios of `docs/SCENARIOS.md` were rerun and pass.

## What the large worlds turned up

These were found in the first measurement and are fixed:

1. **Running out of memory exited with code 1**, the code for "merged with true conflicts". It now exits with 3 and says that the output folder is incomplete.
2. **`data/minecraft/random_sequences.dat` conflicted whenever both sides played.** It holds the state of the game's loot random numbers. It now goes to the newer side, unrecorded.
3. **Vaults and beehives conflicted wherever both sides loaded the chunk**, 66 vaults and 18 beehives in the 4-region overworld. A vault stores the time of its next update and a beehive counts ticks for each bee inside. Both are now progress fields.

## Reproducing

```
python3 tools/mc/build_large_world.py --dimension overworld --regions 8
python3 tools/mc/build_large_world.py --dimension nether --regions 4
python3 tools/mc/build_large_world.py --dimension end --regions 4
python3 tools/mc/measure_merge.py --dimension overworld --whole 8
python3 tools/mc/measure_merge.py --dimension nether --whole 4
python3 tools/mc/measure_merge.py --dimension end --whole 4
python3 tools/mc/check_large_merge.py
```

Building takes about 12 minutes and 3 GB of disk. Measuring takes about 40 minutes, most of it in the worst case with the smallest heaps. The numbers are in `.mc/measure/results.json`.
