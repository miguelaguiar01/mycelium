package mycelium.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.chunk.BlockState;
import mycelium.chunk.ChunkPos;
import mycelium.chunk.PalettedContainer;
import mycelium.chunk.Section;
import mycelium.nbt.CompoundTag;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import mycelium.testing.TestChunks;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/** The merge laws of the spec, on generated sections. */
class SectionMergePropertyTest {

    private static final ChunkPos CHUNK = new ChunkPos(3, -2);

    /** Plain blocks, blocks the noise rules know, and unit blocks, in default and non-default states. */
    private static final List<BlockState> STATES = List.of(
            "minecraft:air", "minecraft:stone", "minecraft:oak_planks", "minecraft:glass", "minecraft:wheat",
            "minecraft:wheat[age=3]", "minecraft:wheat[age=7]", "minecraft:furnace[facing=north,lit=true]",
            "minecraft:furnace", "minecraft:snow", "minecraft:snow[layers=4]", "minecraft:oak_door",
            "minecraft:fire[age=3,east=false,north=false,south=false,up=false,west=false]",
            "minecraft:oak_leaves[distance=3,persistent=false,waterlogged=false]", "somemod:machine[on=true]")
            .stream().map(BlockState::parse).toList();
    private static final List<String> BIOMES = List.of("minecraft:plains", "minecraft:desert", "minecraft:forest");

    private static Section random(Random random, int variety) {
        List<BlockState> blocks = new ArrayList<>();
        for (int i = 0; i < Section.BLOCKS; i++) {
            blocks.add(STATES.get(random.nextInt(variety)));
        }
        List<String> biomes = new ArrayList<>();
        for (int i = 0; i < Section.BIOME_CELLS; i++) {
            biomes.add(BIOMES.get(random.nextInt(BIOMES.size())));
        }
        return new Section(5, PalettedContainer.of(blocks), PalettedContainer.of(biomes), null, null, CompoundTag.empty());
    }

    /** A copy with some cells changed, the way a play session changes a section. */
    private static Section edited(Section section, Random random, int edits) {
        List<BlockState> blocks = section.blockStates().toList();
        List<String> biomes = section.biomes().toList();
        for (int i = 0; i < edits; i++) {
            blocks.set(random.nextInt(Section.BLOCKS), STATES.get(random.nextInt(STATES.size())));
        }
        if (edits > 0) {
            biomes.set(random.nextInt(Section.BIOME_CELLS), BIOMES.get(random.nextInt(BIOMES.size())));
        }
        return new Section(section.y(), PalettedContainer.of(blocks), PalettedContainer.of(biomes), null, null,
                section.extra());
    }

    private static SectionMergeResult merge(Section base, Section ours, Section theirs, Side newer) {
        return Merger.mergeSection(base, ours, theirs, CHUNK, TestChunks.context(newer));
    }

    @Property(tries = 150)
    void aSideThatChangedNothingLeavesTheOtherSideAsItIs(@ForAll long seed, @ForAll @IntRange(min = 1, max = 15) int variety,
                                                           @ForAll @IntRange(min = 0, max = 600) int edits, @ForAll boolean oursNewer) {
        Random random = new Random(seed);
        Section base = random(random, variety);
        Section changed = edited(base, random, edits);
        Side newer = oursNewer ? Side.OURS : Side.THEIRS;

        SectionMergeResult onlyOurs = merge(base, changed, base, newer);
        assertEquals(changed, onlyOurs.section(), "merge(O, A, O) == A");
        assertTrue(onlyOurs.entries().isEmpty());
        SectionMergeResult onlyTheirs = merge(base, base, changed, newer);
        assertEquals(changed, onlyTheirs.section(), "merge(O, O, B) == B");
        assertTrue(onlyTheirs.entries().isEmpty());
        SectionMergeResult same = merge(base, changed, changed, newer);
        assertEquals(changed, same.section(), "merge(O, A, A) == A");
        assertTrue(same.entries().isEmpty());

        for (int index = 0; index < Section.BLOCKS; index++) {
            boolean differs = !changed.blockStates().get(index).equals(base.blockStates().get(index));
            assertEquals(differs ? Origin.OURS : Origin.BASE, onlyOurs.origins().get(index));
            assertEquals(differs ? Origin.THEIRS : Origin.BASE, onlyTheirs.origins().get(index));
            assertEquals(differs ? Origin.BOTH : Origin.BASE, same.origins().get(index));
        }
    }

    @Property(tries = 150)
    void swappingTheSidesAndTheNewerFlagGivesTheSameResult(@ForAll long seed, @ForAll @IntRange(min = 1, max = 15) int variety,
                                                            @ForAll @IntRange(min = 0, max = 900) int editsOurs,
                                                            @ForAll @IntRange(min = 0, max = 900) int editsTheirs) {
        Random random = new Random(seed);
        Section base = random(random, variety);
        Section ours = edited(base, random, editsOurs);
        Section theirs = edited(base, random, editsTheirs);

        SectionMergeResult result = merge(base, ours, theirs, Side.OURS);
        SectionMergeResult swapped = merge(base, theirs, ours, Side.THEIRS);
        assertEquals(result.section(), swapped.section());
        assertEquals(result.entries().size(), swapped.entries().size());
        assertEquals(result, merge(base, ours, theirs, Side.OURS), "merging twice gives the same result");

        int conflicts = 0;
        for (int index = 0; index < Section.BLOCKS; index++) {
            BlockState was = base.blockStates().get(index);
            BlockState mine = ours.blockStates().get(index);
            BlockState other = theirs.blockStates().get(index);
            BlockState merged = result.section().blockStates().get(index);
            Origin origin = result.origins().get(index);
            // Nothing is invented: every merged block is one of the three inputs for that cell,
            // and the recorded origin names a side that really holds it.
            assertTrue(merged.equals(was) || merged.equals(mine) || merged.equals(other), "cell " + index);
            switch (origin) {
                case BASE -> assertTrue(merged.equals(was) && mine.equals(was) && other.equals(was));
                case BOTH -> assertTrue(merged.equals(mine) && merged.equals(other) && !merged.equals(was));
                case OURS -> assertEquals(mine, merged);
                case THEIRS -> assertEquals(other, merged);
            }
            assertEquals(origin == Origin.OURS ? Origin.THEIRS : origin == Origin.THEIRS ? Origin.OURS : origin,
                    swapped.origins().get(index));
            if (!mine.equals(other) && !mine.equals(was) && !other.equals(was)) {
                conflicts++;
            } else if (mine.equals(was)) {
                assertEquals(other, merged, "a change on one side only is taken");
            } else if (other.equals(was)) {
                assertEquals(mine, merged, "a change on one side only is taken");
            }
        }
        long blockEntries = result.entries().stream().filter(e -> e.category().name().equals("BLOCK")).count();
        assertEquals(conflicts, blockEntries, "one entry per block both sides changed differently");
        for (ReportEntry entry : result.entries()) {
            assertTrue(entry.severity() == Severity.CONFLICT || entry.detail() != null, "resolved entries name their rule");
        }
    }

    @Property(tries = 60)
    void withoutAnAncestorEqualCellsAreKeptAndDifferencesAreReported(@ForAll long seed,
                                                                     @ForAll @IntRange(min = 0, max = 300) int edits) {
        Random random = new Random(seed);
        Section ours = random(random, 4);
        Section theirs = edited(ours, random, edits);
        SectionMergeResult result = merge(null, ours, theirs, Side.THEIRS);
        int differing = 0;
        for (int index = 0; index < Section.BLOCKS; index++) {
            BlockState mine = ours.blockStates().get(index);
            BlockState other = theirs.blockStates().get(index);
            if (mine.equals(other)) {
                assertEquals(mine, result.section().blockStates().get(index));
                assertEquals(Origin.BOTH, result.origins().get(index));
            } else {
                differing++;
            }
        }
        long reported = result.entries().stream()
                .filter(e -> e.category().name().equals("NO_ANCESTOR") || e.category().name().equals("BLOCK"))
                .filter(e -> !"biome".equals(e.detail())).count();
        assertEquals(differing, reported);
        assertEquals(result.section(), merge(null, theirs, ours, Side.OURS).section());
    }
}
