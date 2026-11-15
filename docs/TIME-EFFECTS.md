# What changes over time, and what the merge does with it

Nov 15, 2026 · Minecraft 26.3 · Checked with scenarios 13 to 16 of `tools/mc/scenarios.py` and the game's own classes (`tools/mc/probe_game_facts.py`)

Two friends who share a base both have it loaded, at different times. Everything the game changes by itself there changes on both sides, and differently: a crop is further on one side, copper has weathered a stage more, a tadpole is a frog. This document lists what the game changes over time and says what the merge does when both sides have such a change.

## Result

- **Blocks check out.** The game ticks 112 of its 1,286 blocks at random. Each is covered by a rule or excused by name, and the generator of the rules file fails if a game version adds one. A garden with one of each, left to grow on both sides for different lengths of time, merges with no conflict, and the merged world loads clean.
- **A player's change beats the game's.** Before this check, a crop that grew on one side and was harvested on the other was a true conflict, and a newer side that only watched it grow put the harvested crop back. So did every other case of this shape. The rule "One side only" in the spec settles them all for the player.
- **Creatures check out since Nov 15.** A creature that lives on under its own UUID was always in the merged world once. Whatever ends a creature and starts another was there twice: a tadpole and its frog, a villager cured on both sides, a sniffer from an egg both sides hatched, bees that left the nest on one side. Four rules decided that day settle these; see Creatures.

## How it was checked

1. **Which blocks.** `GameFacts.java` runs against the server's own classes and lists, for every block, whether the game ticks it at random, and the game's table of copper weathering. `gen_rules.py` reads that list.
2. **A garden.** `tools/mc/garden.py` builds 30 plots on a flat world, one for each kind of block below. Random ticks run at 130 times their usual rate.
3. **Scenario 13:** 150 game ticks pass on ours and 400 on theirs, where it is also night. Most things are part-grown and differ between the sides.
4. **Scenario 14:** 1,500 ticks on ours and 4,000 on theirs, in daylight. Crops ripen, stems bear fruit, saplings become trees, copper weathers through.
5. **Scenario 15:** a player works in the garden on ours while 4,000 ticks pass on theirs.
6. **Scenario 16:** pens of animals, eggs and bees. A day passes on ours and two on theirs.

Each scenario is merged with either side as the newer one and loaded in the game. The scripts print, plot by plot, what each side made of a block and what the merge kept, and what the game changed in the merged world right after loading it.

## Blocks the game ticks at random

| Blocks | What the game does | What the merge does where both sides differ | Seen in |
| --- | --- | --- | --- |
| Wheat, carrots, potatoes, beetroots, nether wart, sweet berry bush, cocoa, pitcher crop (8) | Raises `age` | Higher age. A side below the base's age harvested: its age | 13 |
| Torchflower crop | Raises `age`, then becomes a torchflower | The flower beats the crop | 13 |
| Melon stem, pumpkin stem | Raises `age`; at 7 puts a fruit next to itself and becomes an attached stem; goes back when the fruit is taken | Newer side's stem. Each side's fruit stays, so there can be two | 13, 14 |
| Cactus, sugar cane | `age` counts to 15 and starts over; then a new block on top. A cactus can grow a flower on its top instead | Newer side's `age`; the taller column. A cactus block beats the flower the other side grew in its place, which could not carry the cactus above | 13, 14 |
| Bamboo sapling, bamboo | The sapling becomes bamboo; bamboo grows up and changes `age`, `leaves`, `stage` | Bamboo beats the sapling; newer side's properties | 13 |
| Kelp, cave vines, twisting vines, weeping vines | The tip moves on and leaves a stalk block behind | The stalk beats the tip where one side grew further | 13, 14 |
| Vine | Spreads to more faces and to air nearby | Newer side's faces | 13 |
| Chorus flower | Becomes a chorus plant and puts new flowers above and beside | The newer side's plant, whole. See below | 13, 14 |
| Saplings (9), mangrove propagule | Raise `stage`, then become a tree | The log beats a sapling that only aged. Two trees from one sapling: a log beats a leaf, and nothing conflicts | 14 |
| Leaves (14) | Change `distance`; decay to air without a log | Newer side's `distance`; a decayed leaf is air | 14, 15 |
| Brown and red mushroom | Spread into air nearby in the dark | Nothing to settle: a new mushroom is on one side only, or the same block on both | 14 |
| Grass block, mycelium | Spread onto dirt; die under a block | Newer side | 13 |
| Crimson and warped nylium | Die under a block and become netherrack | Newer side | both sides alike |
| Farmland | `moisture` falls without water; then it becomes dirt and its crop drops | Newer side's `moisture`; dirt beats farmland that only dried | 13 |
| Copper, 45 blocks in 15 chains | Weathers a stage: plain, exposed, weathered, oxidized. A door's or a double chest's halves change together | The later stage | 13, 14 |
| Budding amethyst | Puts a bud on a free face and grows it: small, medium, large, cluster | The larger bud | 14 |
| Pointed dripstone, sulfur spike | Grows longer and starts a spike below; dripstone fills a cauldron under it | Newer side's `thickness`, which the game corrects on load; the higher cauldron level | 13, 14 |
| Snow | Melts near light | Newer side | both sides alike |
| Ice | Melts into water near light | Not in a rule: one side's melting is a plain change | both sides alike |
| Lava | Sets fire to what burns nearby | Fire is in a rule: newer side | — |
| Redstone ore, deepslate redstone ore | Goes dark again after being lit | Newer side's `lit` | — |
| Turtle egg, dried ghast | Raise `hatch` or `hydration`, then hatch | Higher value. Hatching is under Creatures | 14, 16 |
| Closed and open eyeblossom, and both in a pot (4) | Open at night, close in the morning | Newer side | 13 |
| Nether portal | Spawns a zombified piglin now and then | Nothing: the block does not change | — |

The same check covers what else is driven by time and has its own rule: fire, fluid levels, redstone and machine states, a creaking heart (dormant by day, awake by night), a composter filled by a hopper, a vault and a trial spawner that wake when a player is near.

**A chorus plant** grew into two different shapes. Merged block by block, the result was a shape the game does not allow, and between 10 and 58 of its blocks broke when the merged worlds of scenarios 13 and 14 were loaded, in nearly every run. It is now taken whole from the newer side: what only the older side grew of it is left out wherever the newer side had the chunk in play. In seven runs of each scenario since, the game broke nothing of the merged plant after loading it; the only changes were the plant growing on.

## A player's hand against the game's

Scenario 15, with either side as the newer one. Ours is the player, theirs is time.

| Ours, by hand | Theirs, by itself | Merged |
| --- | --- | --- |
| Harvested the wheat, left the soil bare | The wheat ripened | Bare soil |
| Dug up the melon stem | The stem bore a melon | No stem; the melon lies there |
| Cut the kelp at the bottom | The kelp grew 7 blocks | Cut. The game breaks the 7 blocks above when the world loads, as it would have |
| Waxed a copper block; scraped an exposed one; turned copper stairs | The copper weathered | As the player left each |
| Tilled a dirt block | Grass spread onto it | Farmland |
| Built glass where a leaf was | The leaf decayed | Glass |
| Built stone where a leaf of the oak would grow | The oak grew | Stone, inside the tree's crown |
| Replaced the cauldron with a hopper | The cauldron filled from the dripstone | Hopper |

None of these is a conflict. Three more are, and stay so, because the game's change on theirs is one a player could have made as well, and the tool cannot tell which it was:

| Ours, by hand | Theirs, by itself | Merged |
| --- | --- | --- |
| Built stone above the sugar cane | The cane grew into that block | Newer side's block; conflict |
| Built stone next to the budding amethyst | A bud grew there | Newer side's block; conflict |
| Dug up the birch sapling | It became a tree | Newer side's block; conflict. With ours newer the tree stands without its lowest log |

## Creatures

Scenario 16. The first column of results is what the merge did before the four rules below, the second what it does now. Both were checked in the game with either side as the newer one.

| What happens over time | Same UUID after? | Before | Now |
| --- | --- | --- | --- |
| A calf grows up | Yes | Once, as the newer side has it | The same |
| A shorn sheep eats grass and regrows wool | Yes | Once; the grass it ate on either side is dirt | The same |
| A hen lays an egg | Yes | Once; the egg on the ground is a new item | The same |
| A sitting wolf, an idle villager | Yes | Once | The same |
| An item on the ground runs out its 5 minutes | — | Gone, as on both sides | The same |
| A dried ghast hatches on one side, is half way on the other | — | One ghast, no block | The same |
| A tadpole becomes a frog on one side only | No: the frog is a new entity | The tadpole and the frog | The frog |
| A tadpole becomes a frog on both sides | No | Two frogs | One frog |
| A zombie villager is cured on one side only | No | The zombie villager and the villager | The villager |
| A zombie villager is cured on both sides | No | Two villagers | One villager |
| A sniffer egg hatches on both sides | — | Two sniffers | One sniffer |
| Turtle eggs hatch on both sides, 2 turtles each | — | not run | 2 turtles |
| Frogspawn hatches on both sides, 2 to 5 tadpoles each; on theirs they are frogs by the end | — | All of both sides, up to 10 | The larger of the two numbers |
| Bees: 3 in a nest, in or out at the moment each side was saved | No: a bee gets a new UUID each time it leaves | 6, 5 and 3 bees in three runs. The nest was a recorded conflict | 3, no conflict |
| A copper golem weathers through and becomes a statue | — | Two statues, one where it stood on each side | The same: left as it is |
| The game drops an item on both sides: seeds from a crop that lost its farmland | — | Two items | The same: left as it is |

## The four rules

Decided on Nov 15, 2026, and in the spec under Entities and Noise rules.

1. **An entity one side no longer has is gone, unless the other side did more than have it loaded.** The spec kept an entity that one side removed if the other side changed it, and whatever is loaded changes every tick. That kept the tadpole next to its frog and the zombie villager next to the cured villager, and by the same rule a cow one side slaughtered. Now the entity is gone unless the other side changed an item it holds, gave it a mark of a player or put it into something. This was already the rule for dropped items and arrows.
2. **Twins.** For each creature of the base that neither side still has and that turns into another kind, and for each egg block of the base that neither side still has, the older side's new creatures of the kinds it becomes are left out: as many as the newer side has of its own, the nearest first, within 32 blocks. Which creature turns into which is read from the game's classes: 11 kinds, from a tadpole to a mooshroom. The eggs are the turtle egg, the sniffer egg, frogspawn and the dried ghast.
3. **Bees follow the newer side's nest, while the nest has room.** Where both sides changed a nest, the merged world has the newer side's nest, unrecorded. The newer side's bees of that nest stay. A bee that is new on the older side stays only while the nest is not full: a nest holds 3, counting the bees inside the newer side's nest and its bees in the air.
4. **A chorus plant is taken whole from the newer side.**

**What the twin rule cannot know.** Nothing ties a frog to the tadpole it was, so the rule goes by number and place. Frogspawn gives 2 to 5 tadpoles, by chance, on each side. If the newer side got 4 and the older side 2, two of the newer side's tadpoles have no twin, and if the older side has another new frog or tadpole within 32 blocks, that one is left out in their stead. Frogspawn is the one egg where the numbers can differ. A sniffer egg and a dried ghast give one each, and a turtle egg block says how many eggs it holds, which is how many turtles are paired off. For those, the wrong creature can only be left out if a side's own hatchling died or went more than 32 blocks away. A creature with a name, an owner or a leash is never left out. The first run of the scenario with this rule showed it: the hatchery stood 30 blocks from the tadpole pen, and a frog of that pen was left out. The pens now stand further apart.

**How the bee rule was checked.** Left alone, the bees of the scenario are in or out by chance when a side is saved. So each side ends in a fixed state. On ours every bee of two nests is in the air. On theirs the first nest holds its 3 bees and the second holds 1, the other two having died.

| Newer | First nest | Second nest | Bees in all |
| --- | --- | --- | --- |
| Theirs | 3 inside; ours' 3 in the air left out | 1 inside; room for 2 of ours' 3 | 6 |
| Ours | Empty; ours' 3 in the air stay | Empty; ours' 3 in the air stay | 6 |

**Left as they are:** two statues from one copper golem, which needs seven hours of game time for each stage of weathering, on both sides; two fruits from one stem; items dropped on both sides, which the game removes after five minutes. A copper golem that becomes a statue on the very same block on both sides, in two poses, is a block conflict; it happened in 2 of about 12 runs of the scenario, where the golem has a pen of 25 blocks.

**Through a portal.** Scenario 17 sends mobs through a nether portal on one side. A mob the other side moved, left alone or sent through as well is in the Nether and nowhere else, whichever side is newer. One that the other side gave a name is in both dimensions: each dimension is merged on its own, and by rule 1 the named one stays where it was named.

## Reproducing

```
python3 tools/mc/probe_game_facts.py     # which blocks the game ticks; copper stages
python3 tools/mc/gen_rules.py            # fails if a ticked block has no rule
python3 tools/mc/scenarios.py 13 14 15 16
```
