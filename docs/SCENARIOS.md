# Merge scenarios — expected results and outcomes

Nov 12, 2026, rerun on Nov 13 after the tool was reworked to merge in pieces twice on Nov 14, after the play session and after the review fixes, and on Nov 15 with scenarios 13 to 16 · Scenario worlds of [CHUNK-MERGE-SPEC.md](../CHUNK-MERGE-SPEC.md), built by `tools/mc/scenarios.py`

Each scenario builds a base world on the 26.3 server, copies it twice, plays each copy with console commands, merges the three with `mycelium-merge`, loads the merged world in the game and checks it. The checks are made on a world standing still. The world then runs for 200 ticks, so that its block entities, entities and scheduled ticks are used, and only then is the log read: it must hold no warning or error. The world must come back from the game with heightmaps and light on every finished chunk, including the chunks that were merged block by block. The tool's exit code must be 1 exactly when its report holds a conflict or a refusal.

Run all with `python3 tools/mc/scenarios.py`, or some with `python3 tools/mc/scenarios.py 03 07`.

## Results

| # | Scenario | Result |
| --- | --- | --- |
| 1 | Building in different areas of the same chunk | Passed |
| 2 | Building the same block differently | Passed |
| 3 | Farms growing on both sides | Passed |
| 4 | Furnaces running on both sides | Passed |
| 5 | Mobs across chunk borders, leashes, a ridden boat | Passed; the player-ridden boat is in the play session |
| 6 | Both sides taking items from the same chest | Passed |
| 7 | A door against a block in its top half | Passed |
| 8 | A bed and a double chest across a chunk border | Passed |
| 9 | Each side exploring new chunks | Passed; shows the limit of merging without a generated base |
| 10 | Blocks from an unknown mod | Passed in the files; cannot be loaded in vanilla |
| 11 | Light across the border between a chunk of each side | Blocks right; light wrong except with `--relight all`. A known limit |
| 12 | A sapling that becomes a tree on one side only | Passed |
| 13 | A garden grows on both sides, a little | Passed |
| 14 | A garden grows on both sides, a lot | Passed |
| 15 | A player's hand in the garden against the game's | Passed |
| 16 | Animals, eggs and bees over a day on one side and two on the other | Passed, since the four rules of Nov 15 |
| 17 | A mob that goes through a portal on one side | Passed; a mob named on the other side is in both dimensions |

The server console has no player. The player-ridden boat of scenario 5, player data, advancements, statistics and maps were checked in one session in the 26.3 client; see The play session.

## The scenarios

### 1. Building in different areas of the same chunk

**Setup.** The chunk holds a bell, a barrel and a bed. One side builds a plank hut with glowstone inside. The other builds a stone roof on a pillar with a lantern under it, in the same chunk.

**Expected.** Both builds are in the merged chunk, block for block the same as a world where both were built in turn. Nothing is reported. The game rebuilds the chunk's heightmaps and its three points of interest. Light is compared with that world at 100 positions in and next to the chunk.

**Outcome.** As expected. Light matched at 100 of 100 positions with `--relight changed` and with `--relight neighbours`.

### 2. Building the same block differently

**Expected.** The newer side's block is in the merged world. The report holds exactly one conflict that names the position, the base block, both sides' blocks and the winner.

**Outcome.** As expected, for either side as the newer one.

### 3. Farms growing on both sides

**Setup.** Wheat, carrots and sugar cane on watered farmland grow for 30,000 ticks on one side and 60,000 on the other, with nobody online.

**Expected.** Every crop is at the further grown of the two stages, the cane is as tall as the taller side, and game time is the later one. The report has only resolved growth entries and no conflicts.

**Outcome.** As expected: every one of the 14 crops is at the higher stage, the cane is 3 tall, and the report has only resolved growth entries. The first run also reported a file conflict for the wandering trader's spawn timer, which led to rule change 1 below.

### 4. Furnaces running on both sides

**Setup.** A furnace with raw iron and coal smelts for 300 ticks on one side and 700 on the other.

**Expected.** The merged furnace is lit and holds the newer side's input and output stacks. The two slots both sides changed are recorded as item conflicts for the later rebase. Burn time, cooking progress and the lit state are not reported.

**Outcome.** As expected, for either side as the newer one.

### 5. Mobs across chunk borders, leashes, a ridden boat

**Setup.** Mobs left alone do not wander with nobody online, so each side moves them with `/tp`. A mob that was just moved walks a few blocks before it stands still, so the merged world is checked against where the newer side's world has each mob, not against where the command put it. Both sides move a cow, and both move a boat with a pig in it into different chunks. One side kills a sheep that the other moves, and a second sheep that the other gives a name. One side moves a villager and the pig it leads into another chunk, and adds a chicken. A second cow is tied to a fence.

**Expected.** The cow and the boat are each once in the merged world, at the newer side's place, and the pig is still in the boat. The sheep that was only moved is gone: the side that killed it has its wool and mutton. The sheep that was given a name is kept. Both leashes are intact. The chicken is there. No entity is stored twice. Entities changed by both sides are recorded as resolved, not as conflicts.

**Outcome.** As expected. Until Nov 15 the first sheep was kept as well; see scenario 16.

### 6. Both sides taking items from the same chest

**Expected.** A slot emptied by one side stays empty, a slot filled by one side stays filled, and the one slot both sides changed holds the newer side's stack and is recorded as an item conflict. No stack appears that neither side had.

**Outcome.** As expected, for either side as the newer one.

### 7. A door against a block in its top half

**Expected.** There is never half a door. If the door's side is newer, the door stands whole and the block is not placed: one block conflict. If the block's side is newer, the block stands and the whole door is gone: one block conflict and one unit conflict.

**Outcome.** As expected.

### 8. A bed and a double chest across a chunk border

**Setup.** One side turns a chest at the edge of a chunk into a double chest reaching into the next chunk, and lays a bed across the same border. The other side puts a block where each second half would be.

**Expected.** If the first side is newer, both stand whole. If the second is newer, its blocks stand, the chest is a single chest again with its original items, and the bed is gone from both chunks: two block conflicts and two unit conflicts.

**Outcome.** As expected.

### 9. Each side exploring new chunks

**Setup.** Natural terrain. Each side generates an area of its own and both generate a shared area, then build in them. In the shared area each side builds one block of its own, and both build a different block at one position.

**Expected.** Chunks only one side generated are taken from that side with what was built there. Chunks both sides generated have no ancestor. Without a generated base, every difference goes to the newer side as a no-ancestor conflict, so the older side's build in those chunks is lost and reported. With a generated base they merge 3-way: both builds stay and only the one block both built differently is a conflict. Unfinished chunks around the explored areas are taken whole and not reported. The merged world forces every chunk either side forced, so all explored areas load without being asked for.

**Outcome.** As expected, and it answers an open question of the spec:

- **Without a generated base**, the older side's block in the shared chunks is gone. The two sides also generated the same chunks slightly differently, mostly ore placement, which gave between 103 and 295 further no-ancestor conflicts per run. The tool prints a note with the number of chunks that had no ancestor and a conflict in them, 8 in the last run, and names `--generated-base`.
- **With a generated base**, both builds are there and the report has the one real conflict.

### 10. Blocks from an unknown mod

**Setup.** Modded block states, a modded block entity and a mod's chunk field are written into the world files directly, since a vanilla server cannot hold them.

**Expected.** All of them come through the merge unchanged. A modded block both sides changed is a whole-value conflict won by the newer side, and no growth rule is applied to a modded crop. The report lists each unknown block id once.

**Outcome.** As expected. The merged world is checked in its files, not loaded: vanilla cannot load modded blocks.

### 11. Light across the border between a chunk of each side

**Setup.** A dark stone box lies across the border between two chunks. Ours puts a torch inside it, in the last column of the first chunk. Theirs places a block inside the second chunk.

**Expected.** Each chunk was changed by one side only, so each is taken whole, with that side's light. The blocks are right and nothing is reported. The second chunk keeps the light of a world that had no torch. This scenario measures how far off that is; it does not fail on it.

**Outcome.** Light was sampled at 18 positions, 9 in each chunk, and compared with a world that has both changes:

| `--relight` | Positions that match | In the second chunk |
| --- | --- | --- |
| `changed` (the default) | 9 of 18 | Dark: 0 where the control has 11 to 13 |
| `neighbours` | 9 of 18 | The same |
| `all` | 18 of 18 | Right |

The tool does not compute light, and when the game relights a chunk it does not pull light across from a neighbour that keeps its stored light. Only relighting every chunk gives the right result, so the fix is the mod's: it can run the light engine on the chunks a merge touched.

### 12. A sapling that becomes a tree on one side only

**Setup.** Two oak saplings on dirt. The trees are grown the way the game grows them: the sapling is removed and the tree placed with its lowest log where the sapling stood. The first sapling grows on ours and only ages on theirs. The second grows on both sides, into two trees of different shape.

**Expected.** The first tree is whole in the merged world, with a log where the sapling stood, whichever side is newer. That block is recorded as resolved growth and nothing around the tree conflicts. The second tree's trunk is a log on both sides; where the two shapes differ, the cells are true conflicts.

**Outcome.** As expected, for either side as the newer one. Before the rule existed, the sapling and the log were two different blocks both sides had changed: a conflict, and with theirs as the newer side the aged sapling replaced the lowest log of ours' tree. The two trees of the second sapling differed in one cell, a log on one side and leaves on the other, which was one conflict. Since Nov 15 a log beats a leaf where both grew into the same air, and the second tree gives none.

### 13. A garden grows on both sides, a little

**Setup.** `tools/mc/garden.py` builds 30 plots on the flat world, one for each kind of block the game changes by itself: crops on watered farmland, melon and pumpkin stems, nether wart, berries, cocoa, cane, cactus, bamboo, kelp, four kinds of vine, a mushroom in a dark room, 20 copper blocks, a budding amethyst, dripstone and sulfur spikes over cauldrons, dry farmland, grass and mycelium next to dirt, ice, snow and fire, leaves without a log, eyeblossoms, a creaking heart, eggs, a chorus flower and two saplings. Random ticks run at 130 times their usual rate. 150 ticks pass on ours and 400 on theirs, where it is also night.

**Expected.** Nothing a player did, so nothing conflicts, whichever side is newer. The merged world loads clean, and the game does not at once undo what the merge made.

**Outcome.** No conflict, and about 20 blocks resolved by a rule in each run: the further grown crop, the stalk over the tip of kelp and vines, the fuller cauldron, the creaking heart's state. What only one side grew is taken as it is. At first the chorus plant, which had grown into two shapes, lost 10 to 39 blocks when the merged world was loaded. It is now taken whole from the newer side, and in seven runs since the game broke nothing of it.

### 14. A garden grows on both sides, a lot

**Setup.** The same garden. 1,500 ticks pass on ours and 4,000 on theirs, in daylight on both.

**Expected.** As for scenario 13.

**Outcome.** No conflict. Copper that was a stage further on theirs took the later stage; an amethyst bud took the larger size; the melon stem bore a fruit on each side, facing different ways, and the merged world has the newer side's stem and both melons. The oak sapling became a tree on both sides, in two shapes. Where one has a log and the other a leaf, the log stands. One of five runs had a conflict: the cactus had grown a block taller on ours and a flower on its top on theirs. A cactus block now beats the flower, and four more runs had none. The chorus plant broke as in scenario 13, by 34 to 58 blocks, until it was taken whole from the newer side.

### 15. A player's hand in the garden against the game's

**Setup.** The same garden. On ours a player harvests the wheat, digs up the melon stem, cuts the kelp, waxes one copper block, scrapes another, turns the copper stairs, tills a dirt block, swaps the cauldron for a hopper, and builds glass where a leaf hangs and stone where the oak's crown will be. No time passes there. On theirs 4,000 ticks pass.

**Expected.** Each of these blocks is in the merged world as the player left it, whichever side is newer, and none is a conflict. Three more are true conflicts, because a player could have done what the game did on theirs: stone built where the cane grew, stone built where a bud grew, and the birch sapling dug up where the tree now stands. There the newer side's block stands.

**Outcome.** As expected: exactly those three conflicts, and every block checked in the game. Before the rule "One side only" each row was a conflict, and with theirs as the newer side the wheat was back and the copper weathered.

### 16. Animals, eggs and bees over a day on one side and two on the other

**Setup.** `tools/mc/pens.py` builds six glass pens: a calf, a shorn sheep and a hen; two tadpoles; two zombie villagers being cured; a sitting wolf, a villager, a diamond on the floor and a copper golem about to weather; two bee nests with three bees each among flowers; and, 24 blocks apart from the rest, turtle eggs, frogspawn, a sniffer egg and a dried ghast. 15,500 ticks pass on ours and 28,500 on theirs. One tadpole and one zombie villager are done within the shorter time, the other two only within the longer. At the end the bees are put where the scenario needs them: on ours all six are in the air, on theirs the first nest holds its three and the second holds one.

**Expected.** The merged world has every creature once: what grew up or was cured on one side is not also there as it was before, what hatched on both sides hatched once, and each nest has three bees, in it or in the air.

**Outcome.** As expected since Nov 15, in seven runs and with either side as the newer one. Before, a creature that keeps its UUID was there once, as the newer side has it, and the rest was there twice:

| Pen | Base | Merged before | Merged now |
| --- | --- | --- | --- |
| Tadpoles | 2 tadpoles | 3 frogs and a tadpole | 2 frogs |
| Cures | 2 zombie villagers | 3 villagers and a zombie villager | 2 villagers |
| Hatchery | 2 turtle eggs, 1 sniffer egg, 1 frogspawn, 1 dried ghast | 2 sniffers, all tadpoles of both sides | 2 turtles, 1 sniffer, 1 ghast, and of the tadpoles and frogs the larger of the two sides' numbers |
| Bees | 3 bees in a nest | 6, 5 and 3 in three runs; the nest a conflict | 3 for each nest; no conflict |
| Household | 1 copper golem | 2 statues | 2 statues: left as it is |

The copper golem turns into a statue where it stands. In 2 of about 12 runs that was the same block on both sides, in two poses, which is one block conflict; the scenario allows it. [TIME-EFFECTS.md](TIME-EFFECTS.md) has the four rules and what they cannot know.

### 17. A mob that goes through a portal on one side

**Setup.** A nether portal on the flat world, and next to it two cows, a sitting wolf with an owner and a third cow. In the base a pig has gone through, so the Nether's side of the portal exists. With nobody online a mob that stands in a portal goes through: it is put there with `/tp`. On ours all four go through. On theirs the first cow is moved and stays in the overworld, the wolf is left alone, the second cow goes through as well, and the third is given a name.

**Expected.** The first cow, the wolf and the second cow are in the Nether and in no other dimension, whichever side is newer. The named cow is in both: going through a portal and naming are both a player's doing, and each dimension is merged on its own. Nothing is a conflict.

**Outcome.** As expected, in three runs. Each mob keeps its UUID through the portal. The overworld records the first cow as "removed by ours, only loaded by theirs" and the named cow as "removed by ours, changed by theirs, kept"; the Nether records the second cow as new on both sides and takes the newer side's.

## The play session

Nov 14, 2026 · One player in the 26.3 client, vanilla. The three worlds are kept under `.mc/play`, and `python3 tools/mc/check_play_session.py` merges them both ways, reads the results and loads them in the game. Cut down by `tools/mc/install_play_fixture.py`, they are also the test fixture `play-session`. The fixture does not identify the player: the script replaces the account UUID with a placeholder, in file names and inside the files, and fails if a trace is left.

**World.** A new singleplayer world: Creative, Peaceful, commands on, Superflat, no structures.

**Base.** Two water channels, a boat in each, and one map:

```
/tp @s 2 -60 16
/fill 0 -61 7 63 -61 9 minecraft:water
/fill 0 -61 23 63 -61 25 minecraft:water
/summon minecraft:oak_boat 3.5 -60 8.5
/summon minecraft:birch_boat 3.5 -60 24.5
/give @s minecraft:crafting_table
/give @s minecraft:bread 16
/give @s minecraft:map
```

Then use the empty map once, which makes map 0, and save and quit. The world folder is copied twice.

**Ours**, the older side:

```
/give @s minecraft:diamond 5
/give @s minecraft:cobblestone
/advancement grant @s only minecraft:husbandry/balanced_diet apple
/setblock 2 -60 14 minecraft:gold_block
/tp @s 300 -60 2
/give @s minecraft:map
/setblock 300 -60 4 minecraft:barrel
```

Use the new empty map and put the map in the barrel. Go back, get in the birch boat, row east to the end of the channel, and save and quit while sitting in the boat.

**Theirs**, the newer side:

```
/give @s minecraft:emerald 7
/give @s minecraft:iron_pickaxe
/advancement grant @s only minecraft:husbandry/balanced_diet bread
/setblock 2 -60 18 minecraft:emerald_block
/tp @s -300 -60 2
/give @s minecraft:map
/setblock -300 -60 3 minecraft:barrel
```

Use the new empty map and put the map in the barrel. Drop five bread. Go back, get in the oak boat, row east to the end of the channel, get out, and save and quit.

**Expected and outcome.** The merge was run with each side as the newer one. Both merged worlds load on the server with a clean log.

| What | With theirs newer | With ours newer |
| --- | --- | --- |
| Player file | Theirs: emeralds and the pickaxe. Ours is next to it as a backup | Ours: diamonds. Theirs is the backup |
| Recipe book | The 43 recipes either side unlocked | The same 43 |
| Advancements | "A Balanced Diet" has apple and bread; the story advancements of both sides are done | The same |
| Statistics | Play time 8,201 ticks, which is 2,730 of the base plus 3,070 and 2,401; maps used 3; time since death is theirs | The same totals; time since death is ours |
| Gold and emerald blocks, channels, barrels | All there | All there |
| Oak boat | Once, at the east end where theirs left it | The same |
| Birch boat | Once, in the world at the east end where ours rowed it | Once, in the player's file, as ours saved it |
| Maps | Theirs' new map is map 1. Ours' is map 2, and the barrel at x=300 holds map 2 | Ours' is map 1 and theirs' is map 2, in the barrel at x=-300 |
| Report | One conflict, the player file | One conflict, the player file |

As expected in every row, after the changes below.

**What it turned up.** The first merge of these worlds went wrong in seven ways. All seven are fixed, in the spec and the tool:

1. **A boat a player left the game in was lost.** The game moves the boat into the player's file. With theirs as the newer side, the merge read that as "removed by ours" and wrote theirs' player file, so the boat was in neither. A vehicle in a player's file now counts as that side's version of the entity.
2. **Both sides numbered their new map 1.** The merged world kept one of the two, so the other side's barrel held a map of the wrong place. The older side's new maps are now renumbered, in their files and in every item that shows them.
3. **The merge took 43 seconds for a 35 MB world.** Both sides had generated the same 277 chunks beyond the base. Unit repair treated every block of a chunk without an ancestor as changed. Only blocks that differ between the sides count now, and the merge takes 1.5 seconds.
4. **"277 chunks have no common ancestor" was a false alarm.** No block differed in any of them. A chunk is now counted only if a difference in it was decided for the newer side.
5. **Statistics undercounted.** The rule was the larger number of each counter. Counters are now the base's number plus what each side added.
6. **The older side's recipes were lost.** The recipe book is in the player file, while the advancements that unlock recipes merge as done. The book now merges as a set when both sides changed the file.
7. **The game's backup of the player file was reported as a conflict.** `<uuid>.dat_old` now goes to the newer side unrecorded.

**Not covered.** Both sides were played by the same player, so the usual case between friends, a player who was only on one side, is tested with a variant of the fixture and not in the game. What the game does when another account opens a world whose `singleplayer_uuid` is not theirs is a question for the mod.

**Seen in the client.** The server has no player, so two things cannot be asked of it. Both merged worlds were opened in the 26.3 client on Nov 14, 2026: with theirs as the newer side, the map in each barrel shows the area around that barrel, and with ours as the newer side the player loads sitting in the birch boat.

## What the scenarios turned up

Three findings became rule changes, agreed on Nov 12, 2026 and now in the spec and the tool:

1. **Timers.** `wandering_trader.dat` changes on both sides by itself and was reported as a file conflict in every long session. It now goes to the newer side unrecorded, like weather.
2. **Forced chunks.** `chunk_tickets.dat` merged as a whole file, so one side's forced chunks were dropped. It now merges as a set: a ticket either side added is kept, and a ticket either side removed is gone.
3. **Chunks without an ancestor.** Merging them 2-way loses the older side's work there and floods the report with terrain differences. The tool now counts such chunks, says so, and points to `--generated-base`; the spec calls the 2-way merge a last resort.

Still worth knowing:

4. **An entity loaded on both sides always comes from the newer side.** Anything that ticks counts as changed, so a deliberate change on the older side, such as moving a boat, is dropped whenever the newer side had that entity loaded too. This is what the spec says.
5. **Mobs left alone do not wander with nobody online; crops do grow.** Scenario 5 is therefore scripted with `/tp`. A mob walks a few blocks right after it was moved, which made one check of this scenario fail now and then until it compared with the newer side's world. A real wandering test needs a player.
6. **Lighting.** In scenario 1 the default `--relight changed` gave the same light as the control everywhere sampled. The gap found during format verification needs light that crosses in from an unmerged neighbouring chunk, which this scenario does not have.
7. **Growth that changes the block.** A sapling that becomes a tree on one side while it only ages on the other is a different block on each side. That was a conflict at the sapling's position until scenario 12; the tree now wins. Other growth of this kind had no rule and was a conflict when both sides had the chunk loaded: a melon or pumpkin stem that attaches to its fruit, an amethyst bud that grows a size, copper that oxidizes a stage. Since Nov 15 each has a rule, and scenarios 13 and 14 grow them all on both sides without a conflict.
8. **What the game did against what a player did.** The rules used to apply only where both sides' changes were the game's. Wheat that one side harvested and the other only saw grow was a conflict, and the newer side's wheat came back. Since Nov 15 the player's block stands; scenario 15.
9. **Creatures that end and begin.** A creature that becomes another, hatches or rests in a nest is not the same entity afterwards, and the merge by UUID had it twice. Four rules of Nov 15 settle it; scenario 16.
10. **Sand falls.** The first hatchery had a floor of sand on the flat world's platform, with nothing under it. The sand fell, and the tadpoles with it, 64 blocks down, which hid for several runs that they were never paired. The floor is stone now.
