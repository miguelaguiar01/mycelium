package mycelium.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.chunk.BlockPos;
import mycelium.chunk.BlockState;
import mycelium.merge.Side;
import mycelium.testing.TestChunks;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RulesTest {

    private static final BlockTable TABLE = TestChunks.TABLE;
    private static final Rules RULES = TestChunks.RULES;
    private static final BlockPos POS = new BlockPos(10, 64, 20);

    private static BlockState state(String text) {
        return BlockState.parse(text);
    }

    private static Rules.Noise noise(String base, String ours, String theirs, Side newer) {
        return RULES.noise(base == null ? null : state(base), state(ours), state(theirs), newer);
    }

    /** A block's default state with some properties set, written in full as the game stores it. */
    private static String with(String block, String property, String value) {
        java.util.SortedMap<String, String> properties = new java.util.TreeMap<>(TABLE.expand(state(block)).properties());
        properties.put(property, value);
        return new BlockState(block, properties).toString();
    }

    // ---- block table ------------------------------------------------------------------------

    @Test
    void vanillaTableKnowsEveryBlockAndItsDefaultState() {
        assertTrue(TABLE.knows("minecraft:stone"));
        assertFalse(TABLE.knows("somemod:machine"));
        assertEquals(state("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"),
                TABLE.expand(state("minecraft:oak_door")), "a default state gets its properties from the table");
        assertEquals(state("minecraft:stone"), TABLE.expand(state("minecraft:stone")), "a block without properties stays bare");
        assertEquals(state("minecraft:oak_door"),
                TABLE.canonical(state("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]")));
        BlockState upper = state("minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
        assertEquals(upper, TABLE.canonical(upper));
        assertEquals(upper, TABLE.expand(upper));
        assertEquals(state("somemod:machine"), TABLE.expand(state("somemod:machine")), "unknown blocks are left alone");
        assertEquals(java.util.List.of("0", "1", "2", "3", "4", "5", "6", "7"), TABLE.get("minecraft:wheat").properties().get("age"));
    }

    // ---- noise rules ------------------------------------------------------------------------

    @Test
    void growthTakesTheHigherStageAndReadsDefaultStatesThroughTheTable() {
        Rules.Noise settled = noise("minecraft:wheat[age=1]", "minecraft:wheat[age=2]", "minecraft:wheat[age=5]", Side.OURS);
        assertEquals(new Rules.Noise(state("minecraft:wheat[age=5]"), Side.THEIRS, "growth"), settled);
        // "minecraft:wheat" alone is age 0. Without the block table this pair could not be compared.
        assertEquals(state("minecraft:wheat[age=3]"),
                noise("minecraft:air", "minecraft:wheat", "minecraft:wheat[age=3]", Side.OURS).state());
        // Numbers, not text: 10 is higher than 9.
        assertEquals("10", noise("minecraft:cave_vines[age=1,berries=false]", "minecraft:cave_vines[age=9,berries=false]",
                "minecraft:cave_vines[age=10,berries=false]", Side.OURS).state().properties().get("age"));
        assertEquals(state("minecraft:oak_sapling[stage=1]"),
                noise("minecraft:air", "minecraft:oak_sapling", "minecraft:oak_sapling[stage=1]", Side.OURS).state());
    }

    @Test
    void countersThatStartOverTakeTheNewerSide() {
        assertEquals(new Rules.Noise(state("minecraft:sugar_cane[age=2]"), Side.OURS, "growth"),
                noise("minecraft:sugar_cane[age=14]", "minecraft:sugar_cane[age=2]", "minecraft:sugar_cane[age=15]", Side.OURS));
        assertEquals(state("minecraft:sugar_cane[age=15]"),
                noise("minecraft:sugar_cane[age=14]", "minecraft:sugar_cane[age=2]", "minecraft:sugar_cane[age=15]", Side.THEIRS).state());
    }

    @Test
    void severalNoisePropertiesAreSettledOneByOne() {
        // age is growth (higher), berries regrow and get picked (newer).
        Rules.Noise settled = noise("minecraft:cave_vines[age=1,berries=false]", "minecraft:cave_vines[age=7,berries=false]",
                "minecraft:cave_vines[age=3,berries=true]", Side.THEIRS);
        assertEquals(state("minecraft:cave_vines[age=7,berries=true]"), settled.state());
        assertEquals(Side.THEIRS, settled.origin(), "a mix of both goes with the newer side");
    }

    @Test
    void machineAndRedstoneStateTakeTheNewerSide() {
        String off = "minecraft:furnace[facing=east,lit=false]";
        String on = "minecraft:furnace[facing=east,lit=true]";
        assertEquals(new Rules.Noise(state(on), Side.THEIRS, "machine"),
                noise("minecraft:furnace[facing=south,lit=false]", off, on, Side.THEIRS));
        assertNull(noise(off, "minecraft:furnace[facing=north,lit=true]", "minecraft:furnace[facing=west,lit=false]", Side.OURS),
                "a furnace turned two ways is a real conflict");
        assertEquals("redstone", noise("minecraft:redstone_wire[east=none,north=none,power=3,south=none,west=none]",
                "minecraft:redstone_wire", "minecraft:redstone_wire[east=none,north=none,power=9,south=none,west=none]",
                Side.OURS).rule());
        assertNull(noise("minecraft:air", "minecraft:lever[face=wall,facing=north,powered=true]",
                "minecraft:lever[face=wall,facing=north,powered=false]", Side.OURS), "a lever is a player's switch");
        assertNull(noise("minecraft:air", "minecraft:repeater[delay=2,facing=north,locked=false,powered=false]",
                "minecraft:repeater[delay=4,facing=north,locked=false,powered=false]", Side.OURS), "a repeater's delay is set by hand");
    }

    @Test
    void fireSnowAndLeafDecay() {
        assertEquals(new Rules.Noise(state("minecraft:air"), Side.THEIRS, "fire"),
                noise("minecraft:oak_planks", "minecraft:fire[age=3,east=false,north=false,south=false,up=false,west=false]",
                        "minecraft:air", Side.THEIRS));
        assertEquals("fire", noise("minecraft:air", "minecraft:fire[age=3,east=false,north=false,south=false,up=false,west=false]",
                "minecraft:fire[age=9,east=false,north=false,south=false,up=false,west=false]", Side.OURS).rule());
        assertEquals(state("minecraft:snow[layers=5]"),
                noise("minecraft:snow", "minecraft:snow[layers=2]", "minecraft:snow[layers=5]", Side.THEIRS).state());
        assertEquals(state("minecraft:air"), noise("minecraft:snow[layers=3]", "minecraft:air", "minecraft:snow[layers=4]", Side.OURS).state());
        assertEquals(new Rules.Noise(state("minecraft:stone"), Side.OURS, "snow"),
                noise("minecraft:snow", "minecraft:stone", "minecraft:air", Side.THEIRS), "building where snow lay beats its melting");
        assertNull(noise("minecraft:snow", "minecraft:stone", "minecraft:dirt", Side.OURS), "two blocks built where snow lay");
        // Grass and dirt are a pair the game swaps, but not where a stone was: two players put them there.
        assertNull(noise("minecraft:stone", "minecraft:grass_block", "minecraft:dirt", Side.OURS));
        assertEquals(Side.THEIRS, noise("minecraft:grass_block", "minecraft:dirt", "minecraft:mycelium", Side.THEIRS).origin());
        assertEquals(Side.OURS, noise(null, "minecraft:grass_block", "minecraft:dirt", Side.OURS).origin());

        String leaves = "minecraft:oak_leaves[distance=3,persistent=false,waterlogged=false]";
        String further = "minecraft:oak_leaves[distance=6,persistent=false,waterlogged=false]";
        assertEquals(new Rules.Noise(state("minecraft:air"), Side.OURS, "leaves"), noise(leaves, "minecraft:air", further, Side.THEIRS));
        assertEquals(new Rules.Noise(state("minecraft:air"), Side.THEIRS, "leaves"), noise(leaves, further, "minecraft:air", Side.OURS));
        assertNull(noise(null, "minecraft:air", further, Side.OURS), "decay needs to know leaves were there");
        // Another tree's leaf where this one decayed: both are the game's doing, and the newer side's stands.
        assertEquals(Side.THEIRS, noise(leaves, "minecraft:air", "minecraft:birch_leaves[distance=6,persistent=false,waterlogged=false]",
                Side.THEIRS).origin());
        // A block built where the leaf decayed on the other side.
        assertEquals(new Rules.Noise(state("minecraft:glass"), Side.OURS, "leaves"), noise(leaves, "minecraft:glass", "minecraft:air", Side.THEIRS));
    }

    @Test
    void aHarvestIsNotUndoneByGrowthOnTheOtherSide() {
        // The base's wheat is at stage 5. Ours harvested and replanted it, so it is at 1. Theirs let it grow to 7.
        // Growth only goes up: a side below the base reset the plant by hand, and the crop it took exists once.
        assertEquals(new Rules.Noise(state("minecraft:wheat[age=1]"), Side.OURS, "growth"),
                noise("minecraft:wheat[age=5]", "minecraft:wheat[age=1]", "minecraft:wheat[age=7]", Side.THEIRS));
        assertEquals(new Rules.Noise(state("minecraft:wheat[age=1]"), Side.THEIRS, "growth"),
                noise("minecraft:wheat[age=5]", "minecraft:wheat[age=7]", "minecraft:wheat[age=1]", Side.THEIRS));
        // Both harvested: the further regrown of the two, as for any growth.
        assertEquals(state("minecraft:wheat[age=2]"),
                noise("minecraft:wheat[age=5]", "minecraft:wheat[age=1]", "minecraft:wheat[age=2]", Side.OURS).state());
        // The base was another block, so there is no stage to fall below.
        assertEquals(state("minecraft:wheat[age=7]"),
                noise("minecraft:air", "minecraft:wheat[age=1]", "minecraft:wheat[age=7]", Side.OURS).state());
    }

    @Test
    void aSaplingThatGrewIntoATreeBeatsOneThatOnlyAged() {
        // On ours the sapling grew: where it stood is now the tree's lowest log. On theirs it only aged.
        // Theirs' sapling must not win, or it would stand under ours' tree in place of the trunk.
        for (Side newer : Side.values()) {
            assertEquals(new Rules.Noise(state("minecraft:oak_log"), Side.OURS, "growth"),
                    noise("minecraft:oak_sapling", "minecraft:oak_log", "minecraft:oak_sapling[stage=1]", newer));
            assertEquals(new Rules.Noise(state("minecraft:mangrove_roots"), Side.THEIRS, "growth"),
                    noise("minecraft:mangrove_propagule[age=4,hanging=false,stage=0,waterlogged=false]",
                            "minecraft:mangrove_propagule[age=4,hanging=false,stage=1,waterlogged=false]",
                            "minecraft:mangrove_roots", newer));
        }
        // A sapling one side dug up is that side's doing, not growth: the tree and the hole conflict.
        assertNull(noise("minecraft:oak_sapling", "minecraft:oak_log", "minecraft:air", Side.OURS));
        // Without a base nobody knows a sapling stood there.
        assertNull(noise(null, "minecraft:oak_log", "minecraft:oak_sapling[stage=1]", Side.OURS));
        // A log where something other than a sapling stood is a block somebody placed.
        assertNull(noise("minecraft:dandelion", "minecraft:oak_log", "minecraft:poppy", Side.OURS));
    }

    @Test
    void whatTheGameDidAloneLosesToWhatAPlayerDid() {
        // Ours harvested the wheat and left the soil bare; on theirs the wheat only grew on. A newer theirs
        // must not put the wheat back: ours has it in a chest by now.
        for (Side newer : Side.values()) {
            assertEquals(new Rules.Noise(state("minecraft:air"), Side.OURS, "growth"),
                    noise("minecraft:wheat[age=3]", "minecraft:air", "minecraft:wheat[age=5]", newer));
            assertEquals(new Rules.Noise(state("minecraft:carrots"), Side.THEIRS, "growth"),
                    noise("minecraft:wheat[age=3]", "minecraft:wheat[age=7]", "minecraft:carrots", newer), "planted something else");
            assertEquals(new Rules.Noise(state("minecraft:stone"), Side.THEIRS, "redstone"),
                    noise("minecraft:redstone_wire", with("minecraft:redstone_wire", "power", "9"), "minecraft:stone", newer));
            assertEquals(new Rules.Noise(state("minecraft:stone"), Side.OURS, "fire"),
                    noise("minecraft:air", "minecraft:stone", "minecraft:fire", newer), "built where the other side burned");
            // A door that redstone powered on one side and a player opened on the other.
            assertEquals(Side.THEIRS, noise("minecraft:oak_door", with("minecraft:oak_door", "powered", "true"),
                    with("minecraft:oak_door", "open", "true"), newer).origin());
        }
        // Two things players did are still a conflict, and so is everything without a base.
        assertNull(noise("minecraft:wheat[age=3]", "minecraft:air", "minecraft:carrots", Side.OURS));
        assertNull(noise("minecraft:wheat[age=5]", "minecraft:wheat[age=1]", "minecraft:air", Side.OURS), "replanted, and dug up");
        assertNull(noise(null, "minecraft:air", "minecraft:wheat[age=5]", Side.OURS));
    }

    @Test
    void copperTakesTheLaterStageUnlessAPlayerHadAHand() {
        String stairs = "_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]";
        for (Side newer : Side.values()) {
            assertEquals(new Rules.Noise(state("minecraft:weathered_copper"), Side.THEIRS, "oxidation"),
                    noise("minecraft:copper_block", "minecraft:exposed_copper", "minecraft:weathered_copper", newer));
            assertEquals(new Rules.Noise(state("minecraft:oxidized_cut_copper" + stairs), Side.OURS, "oxidation"),
                    noise("minecraft:cut_copper" + stairs, "minecraft:oxidized_cut_copper" + stairs,
                            "minecraft:exposed_cut_copper" + stairs, newer));
            // Ours scraped a stage off with an axe while theirs weathered on.
            assertEquals(new Rules.Noise(state("minecraft:exposed_copper"), Side.OURS, "oxidation"),
                    noise("minecraft:weathered_copper", "minecraft:exposed_copper", "minecraft:oxidized_copper", newer));
            // Theirs waxed the block to keep it as it was.
            assertEquals(new Rules.Noise(state("minecraft:waxed_copper_block"), Side.THEIRS, "oxidation"),
                    noise("minecraft:copper_block", "minecraft:exposed_copper", "minecraft:waxed_copper_block", newer));
            // Theirs turned the stairs. They have not weathered there, and they are the player's.
            assertEquals(Side.THEIRS, noise("minecraft:cut_copper" + stairs, "minecraft:exposed_cut_copper" + stairs,
                    "minecraft:cut_copper_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", newer).origin());
            // A door that weathered on one side and was only powered on the other.
            assertEquals(new Rules.Noise(state("minecraft:exposed_copper_door"), Side.THEIRS, "oxidation"),
                    noise("minecraft:copper_door", with("minecraft:copper_door", "powered", "true"), "minecraft:exposed_copper_door", newer));
            assertEquals(state("minecraft:exposed_copper"), noise(null, "minecraft:copper_block", "minecraft:exposed_copper", newer).state());
        }
        assertNull(noise("minecraft:oxidized_copper", "minecraft:weathered_copper", "minecraft:exposed_copper", Side.OURS),
                "both scraped it, each to another stage");
        assertNull(noise("minecraft:stone", "minecraft:exposed_copper", "minecraft:weathered_copper", Side.OURS),
                "both replaced a stone, each with other copper");
        assertEquals(Side.THEIRS, noise("minecraft:copper_block", "minecraft:exposed_copper", "minecraft:exposed_cut_copper", Side.OURS).origin(),
                "theirs put another kind of copper there, which has no stage in common with the block");
    }

    @Test
    void whatBothSidesGrewIntoTheSameBlockTakesTheLaterStage() {
        String small = "minecraft:small_amethyst_bud[facing=up,waterlogged=false]";
        String medium = "minecraft:medium_amethyst_bud[facing=up,waterlogged=false]";
        String cluster = "minecraft:amethyst_cluster[facing=up,waterlogged=false]";
        String leaves = "minecraft:oak_leaves[distance=2,persistent=false,waterlogged=false]";
        for (Side newer : Side.values()) {
            assertEquals(new Rules.Noise(state(medium), Side.THEIRS, "growth"), noise("minecraft:air", small, medium, newer));
            assertEquals(new Rules.Noise(state(cluster), Side.OURS, "growth"), noise(small, cluster, medium, newer));
            assertEquals(Side.THEIRS, noise(medium, cluster, "minecraft:air", newer).origin(), "theirs mined it");
            assertEquals(Side.THEIRS, noise(medium, cluster, small, newer).origin(), "theirs mined it and a new one grew");
            // Kelp grew one block on ours and two on theirs: the block above the old top is a top and a stalk.
            assertEquals(new Rules.Noise(state("minecraft:kelp_plant"), Side.THEIRS, "growth"),
                    noise("minecraft:water", "minecraft:kelp[age=4]", "minecraft:kelp_plant", newer));
            assertEquals(state("minecraft:bamboo"), noise(null, "minecraft:bamboo_sapling", "minecraft:bamboo", newer).state());
            assertEquals(state("minecraft:cactus"), noise("minecraft:air", "minecraft:cactus_flower", "minecraft:cactus", newer).state(),
                    "the cactus grew taller on one side and flowered on the other");
            assertEquals(state("minecraft:torchflower"),
                    noise("minecraft:torchflower_crop", "minecraft:torchflower", "minecraft:torchflower_crop[age=1]", newer).state());
            // Two trees from one sapling: a trunk of one where the other has leaves.
            assertEquals(state("minecraft:oak_log"), noise("minecraft:air", "minecraft:oak_log", leaves, newer).state());
            assertEquals(state("minecraft:oak_log"), noise(null, leaves, "minecraft:oak_log", newer).state());
            // A tree's leaf grew where the other side built.
            assertEquals(new Rules.Noise(state("minecraft:stone"), Side.THEIRS, "leaves"), noise("minecraft:air", leaves, "minecraft:stone", newer));
        }
        // A log is also what a player builds with. Against another block in the same place it is a conflict.
        assertNull(noise("minecraft:air", "minecraft:oak_log", "minecraft:stone", Side.OURS));
        assertNull(noise("minecraft:stone", "minecraft:oak_log", "minecraft:oak_leaves[distance=2,persistent=true,waterlogged=false]", Side.OURS),
                "a log and a placed leaf where a stone was");
    }

    @Test
    void blocksTheGameSwapsBackAndForthTakeTheNewerSide() {
        String attached = "minecraft:attached_melon_stem[facing=east]";
        for (Side newer : Side.values()) {
            String newerOf = newer == Side.OURS ? attached : "minecraft:melon_stem[age=7]";
            // The stem bore a melon on ours and only grew on theirs; or ours' melon was taken and it let go.
            assertEquals(new Rules.Noise(state(newerOf), newer, "growth"), noise("minecraft:melon_stem[age=3]", attached, "minecraft:melon_stem[age=7]", newer));
            assertEquals(new Rules.Noise(state(newerOf), newer, "growth"),
                    noise("minecraft:attached_melon_stem[facing=north]", attached, "minecraft:melon_stem[age=7]", newer));
            assertEquals(Side.THEIRS, noise("minecraft:melon_stem[age=7]", attached, "minecraft:air", newer).origin(), "theirs dug the stem up");
            // An eyeblossom opens at night. It was night on ours when theirs picked the flower.
            assertEquals(new Rules.Noise(state("minecraft:air"), Side.THEIRS, "day_night"),
                    noise("minecraft:closed_eyeblossom", "minecraft:open_eyeblossom", "minecraft:air", newer));
            assertEquals(newer, noise(null, "minecraft:open_eyeblossom", "minecraft:closed_eyeblossom", newer).origin());
            // Grass spread onto the dirt on ours. Theirs tilled it.
            assertEquals(new Rules.Noise(state("minecraft:farmland"), Side.THEIRS, "spread"),
                    noise("minecraft:dirt", "minecraft:grass_block", "minecraft:farmland", newer));
        }
    }

    @Test
    void aChorusPlantIsTheNewerSidesWhereBothGrewIt() {
        String stalk = "minecraft:chorus_plant[down=true,east=false,north=false,south=false,up=true,west=false]";
        for (Side newer : Side.values()) {
            // The tip on one side, the stalk on the other, where the plant grew past: not the later stage but
            // the newer side's, or the newer side's plant would lose the flower it grows from.
            Rules.Noise settled = noise("minecraft:air", "minecraft:chorus_flower", stalk, newer);
            assertEquals(newer, settled.origin());
            assertEquals("growth", settled.rule());
            // The newer side built where the older side's plant grew.
            assertEquals(newer, noise("minecraft:air", newer == Side.OURS ? "minecraft:stone" : stalk,
                    newer == Side.OURS ? stalk : "minecraft:stone", newer).origin());
        }
        assertNull(noise("minecraft:air", stalk, "minecraft:stone", Side.OURS), "the older side built where the newer side's plant grew");
        // The older side harvested: its hole stands.
        assertEquals(new Rules.Noise(state("minecraft:air"), Side.THEIRS, "growth"), noise(stalk,
                "minecraft:chorus_plant[down=true,east=true,north=false,south=false,up=true,west=false]", "minecraft:air", Side.OURS));
    }

    @Test
    void aCauldronFillsByItselfAndIsEmptiedByHand() {
        for (Side newer : Side.values()) {
            assertEquals(new Rules.Noise(state("minecraft:water_cauldron[level=3]"), Side.THEIRS, "cauldron"),
                    noise("minecraft:cauldron", "minecraft:water_cauldron", "minecraft:water_cauldron[level=3]", newer));
            assertEquals(state("minecraft:water_cauldron"),
                    noise("minecraft:water_cauldron[level=2]", "minecraft:water_cauldron", "minecraft:water_cauldron[level=3]", newer).state(),
                    "ours took a bottle out while it rained on theirs");
            assertEquals(new Rules.Noise(state("minecraft:cauldron"), Side.OURS, "cauldron"),
                    noise("minecraft:water_cauldron[level=2]", "minecraft:cauldron", "minecraft:water_cauldron[level=3]", newer));
        }
    }

    @Test
    void aLeafOrFluidAPlayerPlacedIsNotNoise() {
        String leaves = "minecraft:oak_leaves[distance=3,persistent=false,waterlogged=false]";
        // One side's leaf decayed. The other side broke the leaf and placed it again, which makes it persistent.
        for (Side newer : Side.values()) {
            assertEquals(Side.THEIRS, noise(leaves, "minecraft:air", "minecraft:oak_leaves[distance=3,persistent=true,waterlogged=false]", newer).origin(),
                    "a placed leaf is somebody's block, not the tree's, and stays where the tree's leaf decayed");
        }
        assertNull(noise(leaves, "minecraft:stone", "minecraft:oak_leaves[distance=3,persistent=true,waterlogged=false]", Side.OURS),
                "and against another block it is a conflict");
        // Flowing water changed its level on one side; the other side put a source block there. The source wins.
        assertEquals(new Rules.Noise(state("minecraft:water"), Side.OURS, "fluid"),
                noise("minecraft:water[level=3]", "minecraft:water", "minecraft:water[level=5]", Side.THEIRS));
        assertEquals(new Rules.Noise(state("minecraft:water"), Side.THEIRS, "fluid"),
                noise("minecraft:air", "minecraft:water[level=5]", "minecraft:water", Side.OURS));
        // Two flows, or a source that was there before: the newer side's, as before.
        assertEquals(state("minecraft:water[level=5]"),
                noise("minecraft:water[level=3]", "minecraft:water[level=2]", "minecraft:water[level=5]", Side.THEIRS).state());
    }

    @Test
    void aUnitBlockWithoutItsFacingIsNotAUnit() {
        // The game always writes the facing. A state without it cannot say where its other half is.
        for (String partial : List.of("minecraft:red_bed", "minecraft:chest", "minecraft:piston", "minecraft:piston_head")) {
            BlockState state = new BlockState(partial, new java.util.TreeMap<>(Map.of("part", "head", "type", "left", "extended", "true")));
            assertNull(RULES.unit(state, POS), partial);
        }
    }

    @Test
    void realEditsAndUnknownBlocksAreNotNoise() {
        assertNull(noise("minecraft:oak_planks", "minecraft:stone_bricks", "minecraft:glass", Side.OURS));
        assertNull(noise("minecraft:air", "somemod:crop[age=1]", "somemod:crop[age=2]", Side.OURS),
                "a block in no report gets no property rules");
    }

    // ---- units ------------------------------------------------------------------------------

    @Test
    void doorHalvesLinkVerticallyEvenInDefaultForm() {
        Rules.UnitLink fromLower = RULES.unit(state("minecraft:oak_door"), POS);
        assertEquals("door", fromLower.rule());
        assertEquals(new BlockPos(10, 65, 20), fromLower.partner());
        BlockState upper = state("minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
        assertTrue(fromLower.accepts().test(upper));
        assertFalse(fromLower.accepts().test(state("minecraft:oak_door")), "two lower halves");
        assertFalse(fromLower.accepts().test(state("minecraft:spruce_door[facing=north,half=upper,hinge=left,open=false,powered=false]")));
        assertFalse(fromLower.accepts().test(state("minecraft:oak_door[facing=north,half=upper,hinge=left,open=true,powered=false]")),
                "an open top on a closed bottom");
        assertFalse(fromLower.accepts().test(state("minecraft:stone")));
        assertFalse(fromLower.accepts().test(null));

        Rules.UnitLink fromUpper = RULES.unit(upper, POS);
        assertEquals(new BlockPos(10, 63, 20), fromUpper.partner());
        assertTrue(fromUpper.accepts().test(state("minecraft:oak_door")));
        assertEquals("tall_plant", RULES.unit(state("minecraft:sunflower"), POS).rule());
        assertNull(RULES.unit(state("minecraft:pitcher_crop"), POS), "a young pitcher crop has no upper half");
        assertNull(RULES.unit(state("minecraft:oak_stairs"), POS), "half=top|bottom is not a link");
        assertNull(RULES.unit(state("minecraft:stone"), POS));
    }

    /** Geometry as the game itself gave it in tools/mc/probe_units.py. */
    @ParameterizedTest
    @CsvSource({"north, 0, -1", "east, 1, 0", "south, 0, 1", "west, -1, 0"})
    void bedHeadIsOneStepAlongTheFacing(String facing, int dx, int dz) {
        Rules.UnitLink fromFoot = RULES.unit(state("minecraft:red_bed[facing=" + facing + ",occupied=false,part=foot]"), POS);
        assertEquals(new BlockPos(10 + dx, 64, 20 + dz), fromFoot.partner());
        BlockState head = state("minecraft:red_bed[facing=" + facing + ",occupied=false,part=head]");
        assertTrue(fromFoot.accepts().test(head));
        assertFalse(fromFoot.accepts().test(state("minecraft:blue_bed[facing=" + facing + ",occupied=false,part=head]")));
        assertEquals(new BlockPos(10 - dx, 64, 20 - dz), RULES.unit(head, POS).partner());
    }

    @ParameterizedTest
    @CsvSource({"north, 1, 0", "east, 0, 1", "south, -1, 0", "west, 0, -1"})
    void rightChestHalfIsOneStepClockwiseFromTheFacing(String facing, int dx, int dz) {
        BlockState left = state("minecraft:chest[facing=" + facing + ",type=left,waterlogged=false]");
        BlockState right = state("minecraft:chest[facing=" + facing + ",type=right,waterlogged=false]");
        Rules.UnitLink fromLeft = RULES.unit(left, POS);
        assertEquals("double_chest", fromLeft.rule());
        assertEquals(new BlockPos(10 + dx, 64, 20 + dz), fromLeft.partner());
        assertTrue(fromLeft.accepts().test(right));
        assertFalse(fromLeft.accepts().test(left));
        assertFalse(fromLeft.accepts().test(state("minecraft:trapped_chest[facing=" + facing + ",type=right,waterlogged=false]")));
        assertEquals(new BlockPos(10 - dx, 64, 20 - dz), RULES.unit(right, POS).partner());
        assertNull(RULES.unit(state("minecraft:chest"), POS), "a single chest is not a unit");
    }

    @ParameterizedTest
    @CsvSource({"north, 0, 0, -1", "south, 0, 0, 1", "west, -1, 0, 0", "east, 1, 0, 0", "up, 0, 1, 0", "down, 0, -1, 0"})
    void pistonHeadIsOneStepAlongTheFacing(String facing, int dx, int dy, int dz) {
        BlockState base = state("minecraft:sticky_piston[extended=true,facing=" + facing + "]");
        BlockState head = state("minecraft:piston_head[facing=" + facing + ",short=false,type=sticky]");
        Rules.UnitLink fromBase = RULES.unit(base, POS);
        assertEquals(new BlockPos(10 + dx, 64 + dy, 20 + dz), fromBase.partner());
        assertTrue(fromBase.accepts().test(head));
        assertTrue(fromBase.accepts().test(state("minecraft:moving_piston[facing=" + facing + ",type=sticky]")), "mid-move");
        assertFalse(fromBase.accepts().test(state("minecraft:air")));
        Rules.UnitLink fromHead = RULES.unit(head, POS);
        assertEquals(new BlockPos(10 - dx, 64 - dy, 20 - dz), fromHead.partner());
        assertTrue(fromHead.accepts().test(base));
        assertFalse(fromHead.accepts().test(state("minecraft:sticky_piston[extended=false,facing=" + facing + "]")));
        assertNull(RULES.unit(state("minecraft:sticky_piston[extended=false,facing=" + facing + "]"), POS));
    }

    // ---- mod tables -------------------------------------------------------------------------

    private static final String MOD_REPORT = """
            {"somemod:tall_door": {"properties": {"half": ["upper", "lower"], "open": ["true", "false"]},
              "states": [{"id": 1, "properties": {"half": "upper", "open": "true"}},
                         {"id": 2, "default": true, "properties": {"half": "lower", "open": "false"}}]},
             "somemod:crop": {"properties": {"growth": ["0", "1", "2"]},
              "states": [{"default": true, "properties": {"growth": "0"}}]}}""";
    private static final String MOD_RULES = """
            {"noise": [{"name": "mod_growth", "type": "property", "result": "higher", "properties": ["growth"],
                        "blocks": ["somemod:crop"]}],
             "units": [{"name": "mod_door", "type": "vertical_pair", "shared": ["open"], "blocks": ["somemod:tall_door"]}]}""";

    @Test
    void modTablesGiveModdedBlocksTheSameRules() {
        BlockTable table = BlockTable.builder().addReport(MOD_REPORT, "mod report").build();
        Rules rules = Rules.builder(table).addRules(MOD_RULES, "mod rules").build();
        assertEquals(state("somemod:crop[growth=2]"),
                rules.noise(state("minecraft:air"), state("somemod:crop"), state("somemod:crop[growth=2]"), Side.OURS).state());
        Rules.UnitLink link = rules.unit(state("somemod:tall_door"), POS);
        assertNotNull(link);
        assertTrue(link.accepts().test(state("somemod:tall_door[half=upper,open=false]")));
        assertNotNull(rules.unit(state("minecraft:oak_door"), POS), "vanilla rules stay loaded");
    }

    @Test
    void rulesFilesAreCheckedAgainstTheBlockTable() {
        assertThrows(IllegalArgumentException.class, () -> Rules.builder(TABLE).addRules(MOD_RULES, "mod rules"),
                "a rules file without its block report");
        assertThrows(IllegalArgumentException.class, () -> Rules.builder(TABLE).addRules(
                "{\"noise\": [{\"name\": \"x\", \"type\": \"property\", \"result\": \"higher\", \"properties\": [\"nope\"],"
                        + " \"blocks\": [\"minecraft:wheat\"]}]}", "bad"), "a property the block does not have");
        assertThrows(IllegalArgumentException.class, () -> Rules.builder(TABLE).addRules(
                "{\"units\": [{\"name\": \"x\", \"type\": \"bed\", \"blocks\": [\"minecraft:stone\"]}]}", "bad"));
        assertThrows(IllegalArgumentException.class, () -> Rules.builder(TABLE).addRules(
                "{\"noise\": [{\"name\": \"x\", \"type\": \"magic\", \"blocks\": [\"minecraft:stone\"]}]}", "bad"));
        assertThrows(IllegalArgumentException.class, () -> Rules.builder(TABLE).addRules(
                "{\"noise\": [{\"name\": \"x\", \"type\": \"stages\", \"chains\": [[\"minecraft:stone\", \"minecraft:nope\"]]}]}", "bad"));
        assertThrows(IllegalArgumentException.class, () -> Rules.builder(TABLE).addRules(
                "{\"noise\": [{\"name\": \"x\", \"type\": \"stages\", \"chains\": []}]}", "bad"));
        assertThrows(IllegalArgumentException.class, () -> BlockTable.builder().addReport("{\"a:b\": {\"states\": []}}", "bad"));
        assertThrows(IllegalArgumentException.class, () -> BlockTable.builder().addReport("[1, 2", "bad"));
    }
}
