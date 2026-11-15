package mycelium.merge;

import static mycelium.testing.TestChunks.blockEntity;
import static mycelium.testing.TestChunks.context;
import static mycelium.testing.TestChunks.set;
import static mycelium.testing.TestChunks.setBlockEntity;
import static mycelium.testing.TestChunks.tick;
import static mycelium.testing.TestChunks.withExtra;
import static mycelium.testing.TestChunks.withTicks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.chunk.BlockPos;
import mycelium.chunk.BlockState;
import mycelium.chunk.ChunkCodec;
import mycelium.chunk.ChunkData;
import mycelium.chunk.ChunkFormatException;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import mycelium.testing.Fixtures;
import mycelium.testing.TestChunks;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ChunkMergeTest {

    private static final ChunkData BASE = TestChunks.empty(0, 0);

    private static ChunkMergeResult merge(ChunkData base, ChunkData ours, ChunkData theirs, Side newer)
            throws MergeRefusedException {
        return Merger.mergeChunk(base, ours, theirs, context(newer));
    }

    private static BlockState state(String text) {
        return BlockState.parse(text);
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void aRealChunkMergedWithAnUnchangedSideComesBackExactly(Side newer) throws Exception {
        // A real 26.3 chunk with block entities, ticks, light and heightmaps, edited the way a session would.
        ChunkData base = ChunkCodec.decode(Fixtures.chunkRoot("probe-zlib", 47, 47));
        ChunkData played = set(set(base, 760, 245, 760, "minecraft:glass"), 753, 200, 753, "minecraft:torch");
        played = setBlockEntity(played, new BlockPos(760, 245, 761), blockEntity("minecraft:sign", 760, 245, 761, "new"));
        played = withTicks(played, List.of(tick("minecraft:sand", 760, 246, 760)));

        ChunkMergeResult onlyOurs = merge(base, played, base, newer);
        assertEquals(played, onlyOurs.chunk(), "merge(O, A, O) == A");
        assertTrue(onlyOurs.entries().isEmpty());
        assertFalse(onlyOurs.relit(), "one side's blocks keep that side's light and heightmaps");
        assertEquals(played, merge(base, base, played, newer).chunk(), "merge(O, O, B) == B");
        assertEquals(played, merge(base, played, played, newer).chunk(), "merge(O, A, A) == A");
        assertEquals(base, merge(base, base, base, newer).chunk());
        assertEquals(onlyOurs, merge(base, played, base, newer), "merging twice gives the same result");
        assertEquals(ChunkCodec.encode(played), ChunkCodec.encode(onlyOurs.chunk()));
    }

    @Test
    void buildingInDifferentPlacesOfOneChunkKeepsBoth() throws Exception {
        ChunkData ours = set(BASE, 2, 64, 2, "minecraft:stone_bricks");
        ChunkData theirs = set(BASE, 12, 70, 9, "minecraft:glass");
        ChunkMergeResult result = merge(BASE, ours, theirs, Side.OURS);
        assertEquals(state("minecraft:stone_bricks"), result.chunk().blockState(2, 64, 2));
        assertEquals(state("minecraft:glass"), result.chunk().blockState(12, 70, 9));
        assertTrue(result.entries().isEmpty());
        // The blocks match neither side, so everything derived from them is dropped, flag and arrays together.
        assertTrue(result.relit());
        assertNull(result.chunk().heightmaps());
        assertNull(result.chunk().lightOn());
        assertTrue(result.chunk().sections().stream().noneMatch(s -> s.isLightOnly() || s.skyLight() != null || s.blockLight() != null));
        assertEquals(24, result.chunk().sections().size());
        assertEquals(result.chunk(), merge(BASE, theirs, ours, Side.THEIRS).chunk());
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void theSameBlockBuiltDifferentlyIsOneRecordedConflict(Side newer) throws Exception {
        ChunkData base = set(BASE, 8, 63, 4, "minecraft:oak_planks");
        ChunkData ours = set(base, 8, 63, 4, "minecraft:stone_bricks");
        ChunkData theirs = set(base, 8, 63, 4, "minecraft:glass");
        ChunkMergeResult result = merge(base, ours, theirs, newer);
        assertEquals(List.of(new ReportEntry(Severity.CONFLICT, Category.BLOCK, TestChunks.OVERWORLD, new BlockPos(8, 63, 4),
                "minecraft:oak_planks", "minecraft:stone_bricks", "minecraft:glass", newer, null)), result.entries());
        assertEquals(state(newer == Side.OURS ? "minecraft:stone_bricks" : "minecraft:glass"), result.chunk().blockState(8, 63, 4));
        assertFalse(result.relit(), "the result is exactly the winner's chunk");
    }

    @Test
    void noiseIsSettledWithoutAConflict() throws Exception {
        ChunkData base = set(set(set(BASE, 1, 64, 1, "minecraft:wheat[age=1]"), 3, 64, 1, "minecraft:furnace"), 5, 64, 1, "minecraft:furnace");
        ChunkData ours = set(set(set(base, 1, 64, 1, "minecraft:wheat[age=6]"), 3, 64, 1, "minecraft:furnace[facing=north,lit=true]"),
                5, 64, 1, "minecraft:furnace[facing=south,lit=false]");
        ChunkData theirs = set(set(set(base, 1, 64, 1, "minecraft:wheat[age=3]"), 3, 64, 1, "minecraft:furnace[facing=east,lit=false]"),
                5, 64, 1, "minecraft:furnace[facing=east,lit=false]");
        ChunkMergeResult result = merge(base, ours, theirs, Side.OURS);
        assertEquals(state("minecraft:wheat[age=6]"), result.chunk().blockState(1, 64, 1), "the further grown crop");
        assertEquals(1, result.entries().stream().filter(e -> e.severity() == Severity.RESOLVED && "growth".equals(e.detail())).count());
        // One furnace was lit on one side and turned on the other. Lighting is the game's doing and turning a
        // player's, so the turned furnace stands, though ours is newer.
        assertEquals(state("minecraft:furnace[facing=east,lit=false]"), result.chunk().blockState(3, 64, 1));
        assertEquals(1, result.entries().stream().filter(e -> e.severity() == Severity.RESOLVED && "machine".equals(e.detail())).count());
        // The other was turned on both sides, each its own way: a true conflict, and the newer side's stands.
        assertEquals(1, result.entries().stream().filter(e -> e.severity() == Severity.CONFLICT).count());
        assertEquals(state("minecraft:furnace[facing=south,lit=false]"), result.chunk().blockState(5, 64, 1));
    }

    @Test
    void aChorusPlantIsTakenWholeFromTheNewerSide() throws Exception {
        String stalk = "minecraft:chorus_plant[down=true,east=false,north=false,south=false,up=true,west=false]";
        ChunkData base = set(set(BASE, 5, 64, 5, "minecraft:end_stone"), 5, 65, 5, "minecraft:chorus_flower");
        // Ours grew it three blocks straight up. Theirs grew it one block up and then to the east.
        ChunkData ours = set(set(set(set(base, 5, 65, 5, stalk), 5, 66, 5, stalk), 5, 67, 5, stalk), 5, 68, 5, "minecraft:chorus_flower");
        ChunkData theirs = set(set(set(base, 5, 65, 5, stalk), 5, 66, 5, stalk), 6, 66, 5, "minecraft:chorus_flower[age=1]");
        ChunkMergeResult result = merge(base, ours, theirs, Side.THEIRS);
        assertEquals(theirs.blockState(6, 66, 5), result.chunk().blockState(6, 66, 5));
        assertEquals(state("minecraft:air"), result.chunk().blockState(5, 67, 5), "what only the older side grew is left out");
        assertEquals(state("minecraft:air"), result.chunk().blockState(5, 68, 5));
        assertEquals(0, result.entries().stream().filter(e -> e.severity() == Severity.CONFLICT).count());
        assertEquals(1, result.entries().stream().filter(e -> e.detail() != null && e.detail().startsWith("2 blocks of a plant")).count());
        // With ours as the newer side it is ours' plant, without theirs' branch.
        ChunkMergeResult other = merge(base, ours, theirs, Side.OURS);
        assertEquals(state("minecraft:air"), other.chunk().blockState(6, 66, 5));
        assertEquals(ours.blockState(5, 68, 5), other.chunk().blockState(5, 68, 5));
        // A newer side that never had the chunk in play has no plant of its own, and the older side's stands.
        assertEquals(ours.blockState(5, 68, 5), merge(base, ours, base, Side.THEIRS).chunk().blockState(5, 68, 5));
        // What the older side cut down stays cut down, though the newer side's plant grew on.
        ChunkData cut = set(base, 5, 65, 5, "minecraft:air");
        assertEquals(state("minecraft:air"), merge(base, cut, theirs, Side.THEIRS).chunk().blockState(5, 65, 5));
    }

    @Test
    void aBlockEntityFollowsItsBlock() throws Exception {
        BlockPos pos = new BlockPos(5, 64, 5);
        ChunkData base = setBlockEntity(set(BASE, 5, 64, 5, "minecraft:chest"), pos, blockEntity("minecraft:chest", 5, 64, 5, "full"));
        // Ours replaces the chest with a furnace. Theirs only takes items out of the chest.
        ChunkData ours = setBlockEntity(set(base, 5, 64, 5, "minecraft:furnace"), pos, blockEntity("minecraft:furnace", 5, 64, 5, "smelting"));
        ChunkData theirs = setBlockEntity(base, pos, blockEntity("minecraft:chest", 5, 64, 5, "emptied"));
        for (Side newer : Side.values()) {
            ChunkMergeResult result = merge(base, ours, theirs, newer);
            assertEquals(state("minecraft:furnace"), result.chunk().blockState(5, 64, 5));
            assertEquals(blockEntity("minecraft:furnace", 5, 64, 5, "smelting"), result.chunk().blockEntities().get(pos),
                    "never a furnace holding a chest's data");
            // What theirs did inside the chest is gone with the chest. That is a loss, so it is said.
            assertEquals(List.of(Category.BLOCK_ENTITY), result.entries().stream().map(ReportEntry::category).toList());
            assertEquals(Severity.CONFLICT, result.entries().get(0).severity());
            assertEquals(Side.OURS, result.entries().get(0).winner());
        }
        // The block is removed on one side: its block entity goes with it.
        ChunkData removed = setBlockEntity(set(base, 5, 64, 5, "minecraft:air"), pos, null);
        ChunkMergeResult gone = merge(base, removed, theirs, Side.THEIRS);
        assertTrue(gone.chunk().blockEntities().isEmpty());
        assertEquals(1, gone.entries().size());
        // If the other side left the chest as it was, nothing is lost and nothing is said.
        assertTrue(merge(base, ours, set(base, 1, 1, 1, "minecraft:stone"), Side.THEIRS).entries().isEmpty());
    }

    @Test
    void aBlockIsTheSameBlockWhenOnlyItsStateChanged() throws Exception {
        BlockPos pos = new BlockPos(5, 64, 5);
        ChunkData base = setBlockEntity(set(BASE, 5, 64, 5, "minecraft:chest"), pos, blockEntity("minecraft:chest", 5, 64, 5, "empty"));
        // Ours puts a second chest next to it, which turns the first into half of a double chest.
        // Theirs puts items into the first chest. It is still that chest, so theirs' items are in it.
        ChunkData ours = set(set(base, 5, 64, 5, "minecraft:chest[facing=north,type=left,waterlogged=false]"),
                4, 64, 5, "minecraft:chest[facing=north,type=right,waterlogged=false]");
        ChunkData theirs = setBlockEntity(base, pos, blockEntity("minecraft:chest", 5, 64, 5, "emeralds"));
        for (Side newer : Side.values()) {
            ChunkMergeResult result = merge(base, ours, theirs, newer);
            assertEquals(state("minecraft:chest[facing=north,type=left,waterlogged=false]"), result.chunk().blockState(5, 64, 5));
            assertEquals(blockEntity("minecraft:chest", 5, 64, 5, "emeralds"), result.chunk().blockEntities().get(pos));
            assertTrue(result.entries().isEmpty());
        }

        // Both sides light a furnace, which changes its state the same way on both. What each did
        // with the fuel is still compared with the base's furnace, so the difference is a conflict.
        ChunkData cold = setBlockEntity(set(BASE, 5, 64, 5, "minecraft:furnace"), pos, blockEntity("minecraft:furnace", 5, 64, 5, "20 coal"));
        String lit = "minecraft:furnace[facing=north,lit=true]";
        ChunkData some = setBlockEntity(set(cold, 5, 64, 5, lit), pos, blockEntity("minecraft:furnace", 5, 64, 5, "12 coal"));
        ChunkData none = setBlockEntity(set(cold, 5, 64, 5, lit), pos, blockEntity("minecraft:furnace", 5, 64, 5, "no coal"));
        ChunkMergeResult both = merge(cold, some, none, Side.THEIRS);
        assertEquals(blockEntity("minecraft:furnace", 5, 64, 5, "no coal"), both.chunk().blockEntities().get(pos));
        assertEquals(List.of(Category.BLOCK_ENTITY), both.entries().stream().map(ReportEntry::category).toList());
        assertEquals(blockEntity("minecraft:furnace", 5, 64, 5, "12 coal"),
                merge(cold, some, set(cold, 5, 64, 5, lit), Side.THEIRS).chunk().blockEntities().get(pos),
                "only ours changed what is in it");
    }

    @Test
    void blockEntitiesOfAnUnchangedBlockMergeAsWholeValues() throws Exception {
        BlockPos pos = new BlockPos(5, 64, 5);
        ChunkData base = setBlockEntity(set(BASE, 5, 64, 5, "minecraft:oak_sign"), pos, blockEntity("minecraft:sign", 5, 64, 5, "hello"));
        ChunkData ours = setBlockEntity(base, pos, blockEntity("minecraft:sign", 5, 64, 5, "ours"));
        ChunkData theirs = setBlockEntity(base, pos, blockEntity("minecraft:sign", 5, 64, 5, "theirs"));
        assertEquals(blockEntity("minecraft:sign", 5, 64, 5, "ours"), merge(base, ours, base, Side.THEIRS).chunk().blockEntities().get(pos));
        ChunkMergeResult both = merge(base, ours, theirs, Side.THEIRS);
        assertEquals(blockEntity("minecraft:sign", 5, 64, 5, "theirs"), both.chunk().blockEntities().get(pos));
        assertEquals(List.of(Category.BLOCK_ENTITY), both.entries().stream().map(ReportEntry::category).toList());
        assertEquals(Severity.CONFLICT, both.entries().get(0).severity());
    }

    @Test
    void scheduledTicksFollowTheirBlock() throws Exception {
        ChunkData base = withTicks(set(BASE, 1, 64, 1, "minecraft:water"), List.of(tick("minecraft:water", 1, 64, 1)));
        // Ours puts sand at a new place; theirs replaces the water with stone, which ends its tick.
        ChunkData ours = withTicks(set(base, 9, 80, 9, "minecraft:sand"), List.of(tick("minecraft:water", 1, 64, 1), tick("minecraft:sand", 9, 80, 9)));
        ChunkData theirs = withTicks(set(base, 1, 64, 1, "minecraft:stone"), List.of());
        for (Side newer : Side.values()) {
            ChunkMergeResult result = merge(base, ours, theirs, newer);
            assertEquals(List.of(tick("minecraft:sand", 9, 80, 9)), result.chunk().blockTicks(),
                    "the sand's tick stays, the tick of the replaced water goes");
        }
        // Both sides hold the same tick on an unchanged block: it is kept once.
        ChunkData again = withTicks(base, List.of(tick("minecraft:water", 1, 64, 1)));
        assertEquals(1, merge(base, set(again, 3, 3, 3, "minecraft:stone"), set(again, 4, 4, 4, "minecraft:stone"), Side.OURS)
                .chunk().blockTicks().size());
    }

    @Test
    void unknownFieldsFollowTheThreeWayRuleAndAreNeverDropped() throws Exception {
        ChunkData base = withExtra(BASE, CompoundTag.builder().put("mod:a", new IntTag(1)).put("mod:b", new IntTag(1)).build());
        ChunkData ours = withExtra(base, CompoundTag.builder().put("mod:a", new IntTag(2)).put("mod:b", new IntTag(2)).put("mod:c", new IntTag(9)).build());
        ChunkData theirs = withExtra(base, CompoundTag.builder().put("mod:a", new IntTag(1)).put("mod:b", new IntTag(3)).build());
        ChunkMergeResult result = merge(base, ours, theirs, Side.THEIRS);
        assertEquals(new IntTag(2), result.chunk().extra().get("mod:a"), "changed by ours only");
        assertEquals(new IntTag(3), result.chunk().extra().get("mod:b"), "changed by both: newer side");
        assertEquals(new IntTag(9), result.chunk().extra().get("mod:c"), "added by ours");
        assertEquals(List.of(Category.UNKNOWN_FIELD), result.entries().stream().map(ReportEntry::category).toList());
        assertTrue(result.entries().get(0).detail().contains("mod:b"));
    }

    @Test
    void unknownBlocksMergeAsWholeValuesAndAreNoted() throws Exception {
        ChunkData base = set(BASE, 1, 1, 1, "somemod:machine[power=1]");
        ChunkData ours = set(base, 1, 1, 1, "somemod:machine[power=2]");
        ChunkData theirs = set(base, 2, 2, 2, "othermod:lamp");
        ChunkMergeResult result = merge(base, ours, theirs, Side.OURS);
        assertEquals(state("somemod:machine[power=2]"), result.chunk().blockState(1, 1, 1));
        assertEquals(state("othermod:lamp"), result.chunk().blockState(2, 2, 2));
        assertEquals(List.of("othermod:lamp", "somemod:machine"), List.copyOf(result.unknownBlocks()));
        ChunkMergeResult conflict = merge(base, ours, set(base, 1, 1, 1, "somemod:machine[power=3]"), Side.OURS);
        assertEquals(Severity.CONFLICT, conflict.entries().get(0).severity(), "no property rules for a block in no report");
    }

    @Test
    void biomesMergeCellByCell() throws Exception {
        ChunkData ours = TestChunks.setBiome(BASE, 1, 64, 1, "minecraft:desert");
        ChunkData theirs = TestChunks.setBiome(TestChunks.setBiome(BASE, 9, 64, 9, "minecraft:forest"), 1, 64, 1, "minecraft:swamp");
        ChunkMergeResult result = merge(BASE, ours, theirs, Side.OURS);
        assertEquals("minecraft:desert", result.chunk().biome(1, 64, 1));
        assertEquals("minecraft:forest", result.chunk().biome(9, 64, 9));
        assertEquals(List.of(Category.BIOME), result.entries().stream().map(ReportEntry::category).toList());
        assertFalse(result.relit(), "biomes do not affect light");
    }

    @Test
    void withoutAnAncestorDifferencesGoToTheNewerSideAndAreRecorded() throws Exception {
        ChunkData ours = set(set(BASE, 1, 1, 1, "minecraft:stone"), 2, 2, 2, "minecraft:glass");
        ChunkData theirs = set(set(BASE, 1, 1, 1, "minecraft:stone"), 2, 2, 2, "minecraft:dirt");
        ChunkMergeResult result = merge(null, ours, theirs, Side.THEIRS);
        assertEquals(state("minecraft:stone"), result.chunk().blockState(1, 1, 1));
        assertEquals(state("minecraft:dirt"), result.chunk().blockState(2, 2, 2));
        assertEquals(List.of(Category.NO_ANCESTOR), result.entries().stream().map(ReportEntry::category).toList());
    }

    @Test
    void withoutAnAncestorOnlyTheBlocksThatDifferCountAsChanged() throws Exception {
        ChunkData ours = set(BASE, 1, 1, 1, "minecraft:stone");
        ChunkData theirs = set(set(BASE, 1, 1, 1, "minecraft:stone"), 2, 2, 2, "minecraft:dirt");
        // Unit repair looks at every changed block. A chunk both sides generated alike must give it nothing to do.
        assertTrue(ChunkMerge.begin(null, ours, ours, context(Side.OURS)).changedCells().isEmpty());
        assertEquals(List.of(new BlockPos(2, 2, 2)),
                List.copyOf(ChunkMerge.begin(null, ours, theirs, context(Side.OURS)).changedCells()));
        assertEquals(List.of(new BlockPos(1, 1, 1), new BlockPos(2, 2, 2)),
                List.copyOf(ChunkMerge.begin(BASE, theirs, theirs, context(Side.OURS)).changedCells()),
                "with an ancestor, a block both sides changed alike is still a changed block");
    }

    @Test
    void aMissingSectionCountsAsAirAndPlains() throws Exception {
        ChunkData withoutTop = TestChunks.withSections(BASE, BASE.sections().stream().filter(s -> s.y() != 19).toList());
        ChunkData ours = set(BASE, 3, 310, 3, "minecraft:stone");
        ChunkMergeResult result = merge(withoutTop, ours, withoutTop, Side.OURS);
        assertEquals(state("minecraft:stone"), result.chunk().blockState(3, 310, 3));
        assertEquals("minecraft:plains", result.chunk().biome(0, 305, 0));
        assertNotNull(result.chunk().section(19));
    }

    @Test
    void inputsThatDoNotBelongTogetherAreRefused() throws ChunkFormatException {
        ChunkData elsewhere = TestChunks.empty(1, 0);
        assertThrows(MergeRefusedException.class, () -> merge(BASE, BASE, elsewhere, Side.OURS));
        ChunkData unfinished = TestChunks.withStatus(BASE, "minecraft:terrain", 100);
        assertThrows(MergeRefusedException.class, () -> merge(BASE, unfinished, BASE, Side.OURS));
        assertThrows(MergeRefusedException.class, () -> merge(unfinished, BASE, BASE, Side.OURS));
    }
}
