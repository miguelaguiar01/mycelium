package mycelium.merge;

import static mycelium.testing.TestChunks.context;
import static mycelium.testing.TestChunks.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.chunk.BlockPos;
import mycelium.chunk.BlockState;
import mycelium.chunk.ChunkData;
import mycelium.chunk.ChunkPos;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import mycelium.testing.TestChunks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Unit repair across blocks and chunk borders, and the rules for missing and unfinished chunks. */
class DimensionMergerTest {

    private static final String UPPER = "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]";
    private static final BlockState AIR = BlockState.of("minecraft:air");

    /** A little world: the named chunks, each empty. */
    private static Map<ChunkPos, ChunkData> world(int... chunkXs) {
        Map<ChunkPos, ChunkData> world = new HashMap<>();
        for (int x : chunkXs) {
            world.put(new ChunkPos(x, 0), TestChunks.empty(x, 0));
        }
        return world;
    }

    /** Sets a block in whichever chunk of the world holds it. */
    private static Map<ChunkPos, ChunkData> with(Map<ChunkPos, ChunkData> world, int x, int y, int z, String state) {
        Map<ChunkPos, ChunkData> changed = new HashMap<>(world);
        ChunkPos pos = new ChunkPos(x >> 4, z >> 4);
        changed.put(pos, set(world.get(pos), x, y, z, state));
        return changed;
    }

    private static DimensionMerger.Result merge(Map<ChunkPos, ChunkData> base, Map<ChunkPos, ChunkData> ours,
                                               Map<ChunkPos, ChunkData> theirs, Side newer) {
        return DimensionMerger.merge(base, ours, theirs, Map.of(), context(newer), DimensionMerger.Relight.CHANGED);
    }

    private static BlockState block(DimensionMerger.Result result, int x, int y, int z) {
        return result.chunks().get(new ChunkPos(x >> 4, z >> 4)).blockState(x, y, z);
    }

    private static List<ReportEntry> units(DimensionMerger.Result result) {
        return result.entries().stream().filter(entry -> entry.category() == Category.UNIT).toList();
    }

    @Test
    void aDoorIsNeverLeftWithOneHalf() {
        // Scenario 7: ours places a door, theirs places a block where its top half would be.
        Map<ChunkPos, ChunkData> base = world(0);
        Map<ChunkPos, ChunkData> ours = with(with(base, 5, 64, 5, "minecraft:oak_door"), 5, 65, 5, UPPER);
        Map<ChunkPos, ChunkData> theirs = with(base, 5, 65, 5, "minecraft:stone");

        // Ours newer: the block conflict goes to ours and the door stands whole. Nothing to repair.
        DimensionMerger.Result oursWins = merge(base, ours, theirs, Side.OURS);
        assertEquals(BlockState.of("minecraft:oak_door"), block(oursWins, 5, 64, 5));
        assertEquals(BlockState.parse(UPPER), block(oursWins, 5, 65, 5));
        assertEquals(0, oursWins.unitRepairs());
        assertEquals(1, oursWins.entries().size());

        // Theirs newer: block by block the result would be a door's bottom half under a stone.
        // The unit is taken whole from the newer side, so the door goes and the stone stays.
        DimensionMerger.Result theirsWins = merge(base, ours, theirs, Side.THEIRS);
        assertEquals(AIR, block(theirsWins, 5, 64, 5));
        assertEquals(BlockState.of("minecraft:stone"), block(theirsWins, 5, 65, 5));
        assertEquals(1, theirsWins.unitRepairs());
        ReportEntry repair = units(theirsWins).get(0);
        assertEquals(Severity.CONFLICT, repair.severity(), "both sides changed the unit");
        assertEquals(new BlockPos(5, 64, 5), repair.pos());
        assertEquals("door", repair.detail());
        assertEquals(Side.THEIRS, repair.winner());
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void aBedAcrossAChunkBorderIsRepairedInBothChunks(Side newer) {
        // Scenario 8: the foot is in chunk 0, the head one step east in chunk 1.
        Map<ChunkPos, ChunkData> base = world(0, 1);
        Map<ChunkPos, ChunkData> ours = with(with(base, 15, 64, 5, "minecraft:red_bed[facing=east,occupied=false,part=foot]"),
                16, 64, 5, "minecraft:red_bed[facing=east,occupied=false,part=head]");
        Map<ChunkPos, ChunkData> theirs = with(base, 16, 64, 5, "minecraft:glass");
        DimensionMerger.Result result = merge(base, ours, theirs, newer);
        if (newer == Side.OURS) {
            assertEquals("minecraft:red_bed", block(result, 15, 64, 5).name());
            assertEquals("minecraft:red_bed", block(result, 16, 64, 5).name());
            assertEquals(0, result.unitRepairs());
        } else {
            assertEquals(AIR, block(result, 15, 64, 5), "the foot in the other chunk goes with its head");
            assertEquals(BlockState.of("minecraft:glass"), block(result, 16, 64, 5));
            assertEquals(1, result.unitRepairs());
            assertEquals(new BlockPos(15, 64, 5), units(result).get(0).pos());
        }
    }

    @Test
    void aRunOfRepairsThatDoesNotEndIsStoppedAndSaid() {
        // Ten beds foot to head in a row. Ours shifted every bed one block west; theirs put a stone where
        // the first shifted bed begins. Each repair restores one block of the base's row and so breaks the
        // next bed. That is twenty repairs in a row, more than the pass allows itself.
        String foot = "minecraft:red_bed[facing=east,occupied=false,part=foot]";
        String head = "minecraft:red_bed[facing=east,occupied=false,part=head]";
        Map<ChunkPos, ChunkData> base = world(0, 1);
        Map<ChunkPos, ChunkData> ours = world(0, 1);
        for (int x = 3; x <= 21; x += 2) {
            base = with(with(base, x, 64, 5, foot), x + 1, 64, 5, head);
            ours = with(with(ours, x - 1, 64, 5, foot), x, 64, 5, head);
        }
        Map<ChunkPos, ChunkData> theirs = with(base, 2, 64, 5, "minecraft:stone");
        DimensionMerger.Result result = merge(base, ours, theirs, Side.THEIRS);

        assertEquals(BlockState.of("minecraft:stone"), block(result, 2, 64, 5));
        List<ReportEntry> units = units(result);
        ReportEntry last = units.get(units.size() - 1);
        assertTrue(last.detail().endsWith("left broken after 8 rounds of repair"), last.detail());
        assertEquals(Severity.CONFLICT, last.severity());
        assertEquals(8, result.unitRepairs());
        // The part that was repaired is the base's row again; the block the report names starts what is not.
        for (int x = 3; x < last.pos().x(); x++) {
            assertEquals(base.get(new ChunkPos(x >> 4, 0)).blockState(x, 64, 5), block(result, x, 64, 5), "x=" + x);
        }
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void overlappingDoubleChestsSettleOnTheNewerSide(Side newer) {
        // Base: one chest. Ours adds a chest to its east, theirs adds one to its west, so each
        // side turns the middle chest into a different half. The pairs overlap and cannot both stand.
        // The newer side's pair stands, and the older side's new chest stays as a single chest: it
        // may hold items, and nothing about a chest needs a second half.
        String single = "minecraft:chest";
        String left = "minecraft:chest[facing=north,type=left,waterlogged=false]";
        String right = "minecraft:chest[facing=north,type=right,waterlogged=false]";
        Map<ChunkPos, ChunkData> base = with(world(0), 8, 64, 8, single);
        Map<ChunkPos, ChunkData> ours = with(with(base, 8, 64, 8, left), 9, 64, 8, right);
        Map<ChunkPos, ChunkData> theirs = with(with(base, 7, 64, 8, left), 8, 64, 8, right);
        DimensionMerger.Result result = merge(base, ours, theirs, newer);

        Map<ChunkPos, ChunkData> winner = newer == Side.OURS ? ours : theirs;
        int alone = newer == Side.OURS ? 7 : 9;
        for (int x = 7; x <= 9; x++) {
            assertEquals(x == alone ? BlockState.of(single) : winner.get(new ChunkPos(0, 0)).blockState(x, 64, 8),
                    block(result, x, 64, 8), "x=" + x);
        }
        assertEquals(1, result.unitRepairs());
        assertEquals(Severity.CONFLICT, units(result).get(0).severity());
        // Every chest half left in the result has its other half.
        for (int x = 7; x <= 9; x++) {
            var link = TestChunks.RULES.unit(block(result, x, 64, 8), new BlockPos(x, 64, 8));
            if (link != null) {
                assertTrue(link.accepts().test(block(result, link.partner().x(), 64, 8)), "x=" + x);
            }
        }
    }

    @Test
    void unitsThatBothSidesLeftAloneOrOneSideChangedAreNotTouched() {
        Map<ChunkPos, ChunkData> base = with(with(world(0), 5, 64, 5, "minecraft:oak_door"), 5, 65, 5, UPPER);
        // Ours opens the door, which changes both halves. Theirs builds next to it.
        Map<ChunkPos, ChunkData> ours = with(with(base, 5, 64, 5, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=true,powered=false]"),
                5, 65, 5, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=true,powered=false]");
        Map<ChunkPos, ChunkData> theirs = with(base, 6, 64, 5, "minecraft:stone");
        DimensionMerger.Result result = merge(base, ours, theirs, Side.THEIRS);
        assertEquals("true", block(result, 5, 64, 5).properties().get("open"));
        assertEquals("true", block(result, 5, 65, 5).properties().get("open"));
        assertEquals(0, result.unitRepairs());
        assertTrue(result.entries().isEmpty());

        // A lone door half that was already in the base is not ours to fix.
        Map<ChunkPos, ChunkData> broken = with(world(0), 5, 64, 5, "minecraft:oak_door");
        DimensionMerger.Result kept = merge(broken, with(broken, 1, 1, 1, "minecraft:stone"), with(broken, 5, 66, 5, "minecraft:glass"), Side.OURS);
        assertEquals(BlockState.of("minecraft:oak_door"), block(kept, 5, 64, 5));
        assertEquals(0, kept.unitRepairs());
    }

    @Test
    void missingAndUnfinishedChunksAreTakenWhole() {
        ChunkPos pos = new ChunkPos(0, 0);
        ChunkData full = TestChunks.empty(0, 0);
        ChunkData built = set(full, 1, 1, 1, "minecraft:stone");
        ChunkData early = TestChunks.withStatus(full, "minecraft:structure_starts", 5);
        ChunkData later = TestChunks.withStatus(full, "minecraft:terrain", 9);
        Map<ChunkPos, ChunkData> none = Map.of();

        assertSame(built, merge(none, Map.of(pos, built), none, Side.THEIRS).chunks().get(pos), "only ours has it");
        assertSame(built, merge(Map.of(pos, full), none, Map.of(pos, built), Side.OURS).chunks().get(pos),
                "ours lost it: unchanged, so theirs");
        assertSame(full, merge(Map.of(pos, full), none, none, Side.OURS).chunks().get(pos), "nobody else has it: the base");
        assertSame(built, merge(Map.of(pos, early), Map.of(pos, built), Map.of(pos, later), Side.THEIRS).chunks().get(pos),
                "a finished chunk beats an unfinished one, whoever is newer");
        // No side finished it: the side that changed it, or the newer side if both did.
        assertSame(later, merge(Map.of(pos, early), Map.of(pos, early), Map.of(pos, later), Side.OURS).chunks().get(pos));
        assertSame(later, merge(Map.of(pos, early), Map.of(pos, later), Map.of(pos, early), Side.THEIRS).chunks().get(pos));
        ChunkData other = TestChunks.withStatus(full, "minecraft:biomes", 7);
        DimensionMerger.Result both = merge(Map.of(pos, early), Map.of(pos, later), Map.of(pos, other), Side.THEIRS);
        assertSame(other, both.chunks().get(pos));
        assertTrue(both.entries().isEmpty(), "unfinished chunks are never reported");
        assertTrue(both.relit().isEmpty());
    }

    @Test
    void chunksNewOnBothSidesUseTheGeneratedBaseOrATwoWayMerge() {
        ChunkPos pos = new ChunkPos(0, 0);
        ChunkData fresh = set(TestChunks.empty(0, 0), 4, 4, 4, "minecraft:dirt");
        ChunkData ours = set(fresh, 1, 1, 1, "minecraft:stone");
        ChunkData theirs = set(fresh, 4, 4, 4, "minecraft:air");
        DimensionMerger.Result twoWay = merge(Map.of(), Map.of(pos, ours), Map.of(pos, theirs), Side.OURS);
        assertEquals(BlockState.of("minecraft:dirt"), twoWay.chunks().get(pos).blockState(4, 4, 4), "no ancestor: the newer side's dirt");
        assertEquals(2, twoWay.entries().size());
        assertTrue(twoWay.entries().stream().allMatch(entry -> entry.category() == Category.NO_ANCESTOR));

        DimensionMerger.Result threeWay = DimensionMerger.merge(Map.of(), Map.of(pos, ours), Map.of(pos, theirs),
                Map.of(pos, fresh), context(Side.OURS), DimensionMerger.Relight.CHANGED);
        assertEquals(BlockState.of("minecraft:air"), threeWay.chunks().get(pos).blockState(4, 4, 4), "theirs dug the dirt out");
        assertEquals(BlockState.of("minecraft:stone"), threeWay.chunks().get(pos).blockState(1, 1, 1));
        assertTrue(threeWay.entries().isEmpty());
    }

    @Test
    void relightModesChooseWhichChunksLoseTheirLight() {
        Map<ChunkPos, ChunkData> base = world(0, 1, 2, 3);
        Map<ChunkPos, ChunkData> ours = with(base, 20, 64, 5, "minecraft:stone");     // chunk 1
        Map<ChunkPos, ChunkData> theirs = with(base, 25, 70, 5, "minecraft:glass");   // chunk 1
        assertEquals(Set.of(new ChunkPos(1, 0)), merge(base, ours, theirs, Side.OURS).relit());

        DimensionMerger.Result neighbours = DimensionMerger.merge(base, ours, theirs, Map.of(), context(Side.OURS),
                DimensionMerger.Relight.NEIGHBOURS);
        assertEquals(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(2, 0)), neighbours.relit());
        ChunkData neighbour = neighbours.chunks().get(new ChunkPos(2, 0));
        assertNull(neighbour.lightOn());
        assertNull(neighbour.heightmaps());
        assertTrue(neighbour.sections().stream().noneMatch(s -> s.isLightOnly() || s.skyLight() != null));
        assertEquals(Boolean.TRUE, neighbours.chunks().get(new ChunkPos(3, 0)).lightOn(), "two chunks away keeps its light");

        DimensionMerger.Result all = DimensionMerger.merge(base, ours, theirs, Map.of(), context(Side.OURS),
                DimensionMerger.Relight.ALL);
        assertEquals(4, all.relit().size());
        assertFalse(all.chunks().values().stream().anyMatch(chunk -> chunk.lightOn() != null));
    }

    @Test
    void theResultDoesNotDependOnMapOrder() {
        Map<ChunkPos, ChunkData> base = world(0, 1, 2);
        Map<ChunkPos, ChunkData> ours = with(with(base, 15, 64, 5, "minecraft:red_bed[facing=east,occupied=false,part=foot]"),
                16, 64, 5, "minecraft:red_bed[facing=east,occupied=false,part=head]");
        Map<ChunkPos, ChunkData> theirs = with(with(base, 16, 64, 5, "minecraft:glass"), 40, 3, 3, "minecraft:stone");
        DimensionMerger.Result first = merge(base, ours, theirs, Side.THEIRS);
        DimensionMerger.Result second = merge(new java.util.TreeMap<>(base), new java.util.LinkedHashMap<>(ours),
                new java.util.TreeMap<>(theirs), Side.THEIRS);
        assertEquals(first, second);
    }
}
